# Sound provenance and preparation

Nature options are field recordings, not procedural imitations. Sources and
permissions were checked on the original sound pages on 2026-10-26. The exact
source URLs, authors, license URLs and pinned Blanket download commit/hashes are
in `sources.json`. Blanket's separate sound licensing table is also linked there;
no Blanket application code is included.

- Rain: “rain ambience” by alex36917, CC BY 4.0; Blanket edit by Porrumentzio.
- Fireplace: “fire in fireplace close1.flac” by kyles, CC0 1.0. Uses the publicly
  accessible HQ MP3 preview (48 kHz stereo); the original 24-bit FLAC requires login.
- Ocean: “oceanwavescrushing.wav” by Luftrum, CC BY 4.0; Blanket edit by Porrumentzio.
- Stream: “stream2.wav” by gluckose, CC0 1.0.

BabyLog changes: circular equal-power crossfades (4–6 seconds), static loudness
matching to -27 LUFS and transparent peak limiting where necessary, then 16-bit
FLAC encoding. The field recordings retain their source stereo and 44.1/48 kHz sample rate.
These are processed editions of previously compressed recordings; FLAC avoids
another lossy encoding step and does not claim to restore the source's lost data.
The two noise options are generated signals and are labeled separately.

Run `python3 scripts/prepare-soundscapes.py` with numpy and FFmpeg/FFprobe installed.
Downloads are verified before processing. Cached source files may be supplied with
`--cache`. The script independently decodes each result, checks integrated
loudness, peak headroom and the loop's PCM transition against normal adjacent-sample
variation, and writes `measurements.json`. The report and credits are bundled into
the application; Android tests verify the file hashes, FLAC headers and attribution.

The first/last overlap is arranged so the final-to-first transition corresponds to
adjacent samples of the recording, rather than inserting a fade to silence. Peak
limiting processes repeated audio and retains the middle cycle to preserve the
limiter's lookahead/release through that transition. FFmpeg and floating-point
library versions can affect bit-exact reproduction; generated reports always
refer to the actual packaged assets. Technical measurements cannot establish
subjective pleasantness or substitute for listening on the user's device.
