# v9.1 — particle detector and ground-truth harness

## Particle detector (card: PARTICLE DETECTOR)
The idea behind DECO (UW–Madison), CRAYFIS (UC Davis) and CREDO (IFJ PAN): with the lens covered, an ionising particle crossing the CMOS sensor
liberates charge in a few pixels, which shows up as a small bright cluster in a long, noise-reduction-free exposure. I read their papers and project
pages, not their source code; this engine is written from the published method.
- Capture (`sensor/ParticleCamera.kt`): exclusive Camera2 session, manual 1.0 s exposure, ISO up to 3200, RAW_SENSOR if the rear camera offers it (YUV luma otherwise, less sensitive),
  noise reduction / hot-pixel / edge processing off. The HUD releases CameraX while this runs, so the camera preview pauses.
- Engine (`senses/ParticleEngine.java`): per-frame dark level and noise (median / MAD of a sub-sampled grid), seeds above 6 sigma grown through neighbours above 3 sigma
  (bridging one-pixel gaps), hot pixels learned from repetition (a pixel above threshold 3 times is masked), clusters classified as TRACK (straight, 6+ px), WORM (curly),
  SPOT (2-3 px), BLOB (12+ px round). Single bright pixels are tracked separately and never raise the rate. A rise of the dark level flags LIGHT LEAK and skips the frame.
  Rate: last 5 min versus the long-term rate, exact Poisson tail; ELEVATED needs 15+ min of history, p < 0.001 and at least 4 events above expectation; BURST = 3+ events in 3 s.
  The card shows 16x16-pixel tiles of the last events.
- Simulation (1500x1000 frames, 1 frame/s, noise sigma 3 DN, 200 hot pixels, 900 frames, 110 injected events): all 110 found; 0 noise-driven events; 199 of 200 hot pixels masked;
  BLOB 10/10, TRACK 19/19 classified correctly; WORM 42 found but only 21 labelled WORM (18 as TRACK, 3 as BLOB), so TRACK versus WORM overlaps; 18 one-pixel "SPOT" injections were
  treated as single pixels and not counted (the price of rejecting noise).
  Rate logic (12 null runs of 45 min at 3 events/min): ELEVATED falsely raised in 0.8 % of checks; a 3x rate rise for 5 min was flagged in 10/10 runs, a 2x rise in 4/10;
  a +30 DN light leak was flagged and counted 0 events.
- Not verified: Camera2 RAW on your phone (format, black level, whether the ISP really leaves single pixels alone), real sensor noise (random telegraph pixels, non-Gaussian tails),
  real event rates, and speed: a 12 MP frame took ~0.12 s per 1.5 MP in the simulation here, so a phone may need 1 s or more per frame and drop exposures (lower live time, same statistics).
  Warm phones raise the dark level and the hot-pixel count and can trigger LIGHT LEAK; let it cool, plug it in only when it stays cool.
- Safety: for a source test use only low-level household items (uranium glass, old orange Fiestaware). Do not handle radium dial items or open a smoke detector.

## Ground-truth harness (card: GROUND-TRUTH CHECK)
For one channel at a time it runs a random, shuffled schedule of STIMULUS windows (three strengths, two repetitions each) and 6 SHAM windows (stay still); no three windows of the same type in a row;
each window is 20 s (120 s for the particle detector), the first 3 s ignored, each preceded by 5 s of "next: ..." so you are told what is coming (it measures sensitivity, it is not blinded).
Protocols: magnetometer (magnet at 25 / 12 / 6 cm), band scanner (1 kHz tone from another device, quiet / medium / loud), voice signature (whisper / normal / loud),
ultrasonic sonar (hand wave at 2 / 1 / 0.5 m), AIR flow (hot mug at 20 / 10 / 5 cm), particle detector (low-level source at 10 cm / at the lens).
Per window the channel's strongest response is the score. The report gives, per level, how many windows rose above EVERY sham window (a zero-false-alarm threshold learned in that session) and how many passed the
channel's own threshold, the sham false-alarm count, the AUC of stimulus versus sham with an exact permutation p-value, and the weakest level from which every stronger level is reliably detected.
Results are saved as JSON in Android/data/<app>/files/harness/.
- Statistics check (simulated channels): with no real effect, p < 0.05 in 4.5 % of 3000 sessions; with effects of 0.8 / 2.5 / 6 sigma the sham windows produced 0 false alarms at a threshold of 3 sigma and
  the detection limit was found at the medium level in about 44 % of sessions and at the strong level only in about 3 % (the rest at the weakest tested level).
- Limits: stimulus strength depends on you (distances, volume), two repetitions per level is small, and the result describes this phone in this room. The sound camera is not included because its quantity of interest is accuracy,
  not detection; it can be checked with claps at known angles.
