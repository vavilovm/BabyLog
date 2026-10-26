"""Prepare licensed field recordings and two genuine noise signals for offline playback.

Requires Python 3 + numpy and ffmpeg/ffprobe (FLAC support). Nature recordings are
never synthesized. Downloads are pinned and verified against audio/sources.json.
Usage: python3 scripts/prepare-soundscapes.py [--cache /tmp/babylog-audio-sources]
No API key is needed. File/source attribution is also packaged into the APK.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import subprocess
import urllib.request
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/soundscapes'
TARGET_LUFS = -27.0
PEAK_LIMIT = .5  # -6 dBFS, headroom for headphone/speaker playback.


def command(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, **kwargs)


def probe(path):
    info = json.loads(command('ffprobe', '-v', 'error', '-show_streams', '-of', 'json', str(path)).stdout)
    stream = info['streams'][0]
    return int(stream['sample_rate']), int(stream['channels'])


def loudness(samples, rate, channels):
    result = command('ffmpeg', '-hide_banner', '-f', 'f32le', '-ar', str(rate), '-ac', str(channels),
                     '-i', 'pipe:0', '-af', 'loudnorm=print_format=json', '-f', 'null', '-', input=samples.astype('<f4').tobytes())
    text = result.stderr.decode()
    stats = json.loads(text[text.rfind('{'):text.rfind('}') + 1])
    return float(stats['input_i'])


def circular_crossfade(audio, frames):
    # Output starts at original sample F. Its final sample is original F-1.
    # Thus even the last->first PCM transition is a real, adjacent recorded pair.
    fade = np.linspace(0, np.pi / 2, frames, dtype=np.float32)[:, None]
    blend = audio[-frames:] * np.cos(fade) + audio[:frames] * np.sin(fade)
    return np.concatenate((audio[frames:-frames], blend))


def match_loudness(audio, rate, channels):
    audio = audio - np.mean(audio, axis=0)
    # Static gain preserves the dynamics of water/waves. Only fire's occasional
    # loud cracks need limiting. Process three repeats and keep the middle repeat
    # so the lookahead/release also wraps continuously across the loop seam.
    for _ in range(8):
        integrated = loudness(audio, rate, channels)
        if abs(integrated - TARGET_LUFS) < .15 and np.max(np.abs(audio)) <= PEAK_LIMIT:
            break
        audio *= 10 ** ((TARGET_LUFS - integrated) / 20)
        if np.max(np.abs(audio)) > PEAK_LIMIT:
            tiled = np.tile(audio, (3, 1)).astype('<f4')
            result = command('ffmpeg', '-v', 'error', '-f', 'f32le', '-ar', str(rate), '-ac', str(channels),
                             '-i', 'pipe:0', '-af', f'alimiter=limit={PEAK_LIMIT}:attack=5:release=80:level=false:latency=true',
                             '-f', 'f32le', 'pipe:1', input=tiled.tobytes())
            limited = np.frombuffer(result.stdout, dtype='<f4').reshape(-1, channels)
            audio = limited[len(audio):2*len(audio)].copy()
    return audio


def save(sound_id, audio, rate, channels, tags):
    audio = match_loudness(audio, rate, channels)
    path = ASSETS / f'{sound_id}.flac'
    args = ['ffmpeg', '-v', 'error', '-y', '-f', 'f32le', '-ar', str(rate), '-ac', str(channels),
            '-i', 'pipe:0', '-c:a', 'flac', '-sample_fmt', 's16', '-compression_level', '8']
    for key, value in tags.items():
        args += ['-metadata', f'{key}={value}']
    command(*args, str(path), input=audio.astype('<f4').tobytes())
    decoded = command('ffmpeg', '-v', 'error', '-i', str(path), '-f', 'f32le', 'pipe:1').stdout
    pcm = np.frombuffer(decoded, dtype='<f4').reshape(-1, channels)
    delta_rms = float(np.sqrt(np.mean(np.diff(pcm, axis=0) ** 2)))
    seam = float(np.max(np.abs(pcm[0] - pcm[-1])))
    assert seam < max(.002, delta_rms * 6), (sound_id, 'abnormal seam', seam, delta_rms)
    level = loudness(pcm, rate, channels)
    assert abs(level - TARGET_LUFS) < .75, (sound_id, 'loudness mismatch', level)
    assert float(np.max(np.abs(pcm))) < .505, (sound_id, 'peak limit exceeded')
    return {'id': sound_id, 'file': path.name, 'sample_rate': rate, 'channels': channels,
            'duration_seconds': round(len(pcm)/rate, 6), 'lufs': level,
            'peak_dbfs': round(20*math.log10(float(np.max(np.abs(pcm)))), 2),
            'seam_max': seam, 'adjacent_delta_rms': delta_rms,
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache', type=Path, default=Path('/tmp/babylog-audio-sources'))
    cache = parser.parse_args().cache
    cache.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((ROOT / 'audio/sources.json').read_text())
    measurements = []
    for entry in manifest['recordings']:
        source = cache / f"{entry['id']}-{entry['source_sha256'][:16]}.source"
        if not source.exists():
            urllib.request.urlretrieve(entry['download_url'], source)
        assert hashlib.sha256(source.read_bytes()).hexdigest() == entry['source_sha256'], entry['id']
        rate, channels = probe(source)
        raw = command('ffmpeg', '-v', 'error', '-i', str(source), '-f', 'f32le', 'pipe:1').stdout
        audio = np.frombuffer(raw, dtype='<f4').reshape(-1, channels)
        loop = circular_crossfade(audio, int(rate * entry['crossfade_seconds']))
        measurement = save(entry['id'], loop, rate, channels, {
            'TITLE': entry['title'], 'ARTIST': entry['author'], 'LICENSE': entry['license_url'],
            'COMMENT': f"{entry['source_url']} | {entry['changes']}",
        })
        measurements.append(measurement)
        print(measurement, flush=True)
    # White/pink noise remain actual noise; no fake nature layers or crackle.
    rng = np.random.default_rng(20261026)
    rate, frames = 48000, 48000 * 60
    freq = np.fft.rfftfreq(frames, 1 / rate)
    for sound_id, exponent in [('white', 0), ('pink', 1)]:
        spectrum = np.fft.rfft(rng.standard_normal(frames))
        spectrum *= np.maximum(freq, 30) ** (-exponent / 2)
        spectrum *= (1 - np.exp(-(freq / 30) ** 4)) * np.exp(-(freq / 14000) ** 6)
        audio = np.fft.irfft(spectrum, n=frames).astype(np.float32)[:, None]
        audio /= np.std(audio)
        measurements.append(save(sound_id, audio, rate, 1, {'TITLE': sound_id, 'ARTIST': 'BabyLog', 'COMMENT': 'Procedural noise, loudness matched to the field recordings.'}))
    manifest['measurements'] = measurements
    (ASSETS / 'sources.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    (ROOT / 'audio/measurements.json').write_text(json.dumps(measurements, indent=2) + '\n')


if __name__ == '__main__':
    main()
