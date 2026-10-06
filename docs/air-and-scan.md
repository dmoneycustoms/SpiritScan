# v8.8 — AIR mode and Spirit Box full-band scan

Neither feature detects spirits. Both measure physical things and tell you when something is *unexplained by this
pipeline*. Everything below was tested on synthetic signals on a laptop JVM; none of it has been run on a phone yet.

## AIR (HUD chip after DECODE) — what the camera can see that your eyes cannot
Runs on the same stabilised frames as DECODE (same camera locking, same motion/ego gates). Tap the status strip to cycle layers.

| Layer | What it is | Colour |
|---|---|---|
| AMP | Eulerian-style micro-variation: per-pixel 0.3–3 Hz band-pass, 3x3 smoothing, common-mode removal, in sigma units | warm = brighter, cool = darker |
| FLOW | Background-oriented schlieren: sub-pixel Lucas-Kanade of 6x6 px blocks vs the learned background. 4-parameter fit (dx, dy, offset, gain) so lighting flicker is not read as air motion; global hand shift removed by median | teal/white glow + arrows (x40) |
| PULSE | 4.3 s per 8x8 cell, regressed against room brightness AND the measured hand shift, then a 32-bin Goertzel bank 0.6–13 Hz. Needs 13 dB peak-to-median, 3 consecutive analyses, grouped with neighbours | pink rings with Hz label |

What FLOW really is: air at different temperature/density bends light slightly, so a textured background shifts by hundredths
of a pixel. You will see breath, a warm hand, hot electronics, vents, a candle. It does NOT give degrees, air quality or
"unknown air". Blank walls cannot be measured (the HUD says so). Pulse causes tagged as explained: LIGHT FLICKER (>=14 cells),
SCREEN/LAMP (very bright), HAND TREMOR/EDGE (>=3 Hz on strong edges). Everything else is an unexplained pulse, which is usually
a light, a screen or a pet.

Synthetic results: noise only 0 pulses (flow index 0.01); 0.3 px hand drift 0 pulses; 1.5 Hz pulse 12/1000 luma in 3x3 cells found at
1.48 Hz in the right place; 4 Hz 6/1000 found; 0.12 px air shimmer flow index 0.57; 6 % mains-style flicker 0 false pulses, flow 0.01;
0.5 px tremor 0 pulses. ~5–10 ms per frame on a laptop; a phone may be 2x slower, so AIR may run below 30 fps (the HUD shows fps and
the pulse bank adapts to the measured rate).

## Spirit Box scan (card above the SWEEP chips)
Two microphone streams: 48 kHz UNPROCESSED (falls back to MIC) for the band scan, and 16 kHz VOICE_COMMUNICATION with the platform
echo canceller ON and noise suppression / AGC OFF for voice analysis. If the second stream cannot open, the first is decimated and
the card says "no echo cancel".

- **Phones have no AM/FM tuner**, so a radio-sweep spirit box is impossible. New sweep modes SCAN (random-dwell band-limited noise
  hopping 200–6500 Hz) and GLIDE (slow log sweep 150 Hz–7 kHz) are synthesised.
- **Band scan:** 96 log bands, 20 Hz–23 kHz, each with its own learned floor and spread (anomaly-gated learning). Departures are grouped,
  tracked and classified: TONE, BLIP, CLICK, BROADBAND, BAND, prefixed ULTRASONIC above 18 kHz. Explained as MAINS HUM (50/60 Hz
  harmonics) or OWN BOX (near the box's current frequency). Ended events stay listed for 3 s.
- **Voice signature (no neural network):** band-limited cepstral peak prominence + F0 (70–400 Hz), cepstral-envelope formants, F0 continuity,
  and 1.3 s syllable-rate modulation. An event needs sustained voiced frames with continuous pitch in a 0.4 s window. It reports
  structure, not words, and noise can occasionally satisfy it.
- **NULL TEST** (30 s, quiet room, box running) measures how many voice-like events and band anomalies per minute THIS phone and room
  produce on their own. Afterwards the card only says "ABOVE BASELINE" at Poisson z >= 3. Without a baseline the counts mean nothing.

Synthetic results: 50 min of white/coloured noise 0 voice events; 20 min of noise 0 band anomalies; synthetic vowels F0 120/210/100 Hz found with the
right F0 (F1/F2 estimates are rough: the envelope resolution is ~500 Hz); vowel at -26 dB SNR still found; vowel at -6 dB SNR not found;
the old HOP tone sweep over noise gave 1 voice-like event in 30 s, which is the kind of thing NULL TEST exists to measure;
tones at 15 kHz, 21 kHz, 2.3 kHz found at the right frequency; 120 Hz tagged MAINS HUM; a click found as one CLICK event.

## Known limits
- Not compiled by me as a full Android build. The Java engines compile and run; the Kotlin glue and Compose UI are unverified.
- Running DECODE/AIR and the scan together costs real CPU and battery.
- Two simultaneous microphone streams may be refused on some phones; the card reports it.
- Android may report AEC available yet not cancel the box's output well; the voice score during playback should be judged against the NULL TEST.
