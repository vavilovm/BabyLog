"""Create original, deterministic 48 kHz PCM soundscapes; no third-party recordings.

All filtering and envelopes are periodic. Circular convolution wraps fire/water
transients across the seam, avoiding an artificial fade or silence every loop.
Requires numpy; regenerate with python3 scripts/generate-soundscapes.py.
"""
from pathlib import Path
import wave
import numpy as np

RATE = 48000
SECONDS = 40
N = RATE * SECONDS
rng = np.random.default_rng(20261024)
t = np.arange(N) / RATE
freq = np.fft.rfftfreq(N, 1 / RATE)
root = Path(__file__).resolve().parents[1] / 'app/src/main/assets/soundscapes'
root.mkdir(parents=True, exist_ok=True)


def noise(power=0, low=30, high=16000):
    spectrum = np.fft.rfft(rng.standard_normal(N))
    spectrum *= np.maximum(freq, low) ** (-power / 2)
    spectrum *= (1 - np.exp(-(freq / low) ** 4)) * np.exp(-(freq / high) ** 6)
    result = np.fft.irfft(spectrum, n=N)
    return result / np.std(result)


def drops(count, decay, high):
    impulses = np.zeros(N)
    positions = rng.integers(0, N, count)
    np.add.at(impulses, positions, rng.uniform(.15, 1, count))
    length = int(RATE * decay * 10)
    phase = np.arange(length) / RATE
    kernel = rng.standard_normal(length) * np.exp(-phase / decay)
    # Circular convolution: events near the end decay naturally into the start.
    result = np.fft.irfft(np.fft.rfft(impulses) * np.fft.rfft(kernel, n=N), n=N)
    spectrum = np.fft.rfft(result) * np.exp(-(freq / high) ** 4)
    result = np.fft.irfft(spectrum, n=N)
    return result / np.std(result)


sounds = {
    'white': noise(0),
    'pink': noise(1),
    'rain': .85 * noise(.45, 120, 11000) + .18 * drops(16000, .008, 9000),
    'fire': .5 * noise(1.7, 40, 2200) + .3 * drops(320, .012, 6500) + .15 * drops(1800, .003, 9000),
    'ocean': noise(1.05, 45, 7500) * (.55 + .24 * np.sin(2*np.pi*t/10) + .12*np.sin(2*np.pi*t/8 + 1.4)) + .12*noise(1.8, 30, 900),
    'brook': .65 * noise(.7, 150, 9000) + .3*drops(9000, .025, 4200),
}
for name, audio in sounds.items():
    audio -= np.mean(audio)
    # Matched comfortable RMS, with headroom for fire transients; no hard clipping.
    audio *= min(.12 / np.std(audio), .85 / np.max(np.abs(audio)))
    pcm = np.round(audio * 32767).astype('<i2')
    with wave.open(str(root / f'{name}.wav'), 'wb') as out:
        out.setparams((1, 2, RATE, 0, 'NONE', 'not compressed'))
        out.writeframes(pcm.tobytes())
    print(name, 'RMS', round(float(np.sqrt(np.mean(audio**2))), 4), 'seam', int(pcm[0])-int(pcm[-1]))
