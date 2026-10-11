# v9.0 — six new senses

None of these detect spirits. Each adds an independent physical channel or a way to test the others. All engines are pure Java
(`senses/`), compiled and tested here on synthetic physics; the Kotlin/Compose glue and the real phone audio/sensor behaviour are untested.

## 1. Ultrasonic sonar (card: ULTRASONIC SONAR)
Speaker plays a Hann-windowed 18–20.5 kHz chirp every 21.3 ms; the 48 kHz microphone stream is matched-filtered per frame (FFT, circular, so the
start offset does not matter) to a complex range profile out to 3.4 m (6.9 cm resolution). The direct speaker→mic path is the phase/time reference
(cubic-interpolated, sub-sample), static clutter is removed per range bin, and what is left is motion energy over a learned quiet floor, plus a
5.5 s micro-Doppler spectrum per bin (0.89 cm/s per Hz). Narrow low-velocity line = BREATH-LIKE, narrow fast line = PERIODIC (fan), bins next to the phone =
NEAR-FIELD (you). A Doppler line shared by many bins is treated as audio-clock phase wander and removed.
Simulation: quiet/clutter-only 0 false targets in 70 s; breathing 4 mm@0.3 Hz at 2.0 m and 3 mm@0.25 Hz at 2.5 m found at the right range (≈86 % of snapshots) and tagged
BREATH-LIKE; 4 Hz flutter tagged PERIODIC; walker found; 20 ppm clock drift, a 37-sample audio glitch and 3x noise gave 0 false targets.
Limits: needs the phone speaker and mic to work at 18–20 kHz (the card says NO echo signal if not); sees range and motion, not direction; not through walls; a walking person
can be mislabelled PERIODIC; breathing that was already present while it learned becomes part of the quiet floor for the per-frame detector (the Doppler detector still sees it).
Pets hear this band. The scan's own band scanner ignores 17–21.5 kHz while the sonar runs.

## 2. Coincidence engine (card + SYNC ring on the camera HUD)
Six channels (MAG, AUDIO, SONAR, CAM, NOV, MAP) are sampled once a second. Episodes with 2+ (or 3+) channels recently active (3 s window) are counted and compared with
150 time-shifted surrogates of the same history (up to 1 h): same rates and burstiness, timing destroyed. Needs 10 min of history.
Simulation (6 independent bursty channels, 1 h): p3<0.01 in 0.5 % and p3<0.05 in 2.5 % of 200 null runs; with 8 genuine 3-channel coincidences planted: p3<0.05 in 80 %, p3<0.01 in 37 %; with 14: 100 % / 90 %;
with 4: 20 % / 5 %. A common cause (door, traffic, you) still counts as a coincidence: it says "not chance", never "not mundane".

## 3. Magnetic floor map (card: MAGNETIC FLOOR MAP)
Step detection from the accelerometer, heading from the gyroscope about gravity (the magnetometer is not used for heading), 0.5 m cells holding |B|. Second pass after 20 s is compared against
the range of old first-pass cells within 1 m (tolerates dead-reckoning drift): outside that range by 3 sigma + 0.5 µT twice in a row = CHANGED. STATIC pins list persistent anomalies.
Simulation (two-plus loops of a 6x4 m room, 0.25 µT noise): a source switched on after the first pass was flagged CHANGED (about 1.3 m from its true spot because of drift), the static source was pinned, and 3 no-change controls
(noisy field, 10 % step-length error, cheap gyro bias) gave 0 changed cells. Limits: position drifts about a metre in two minutes; step length must be set (STEP button); keep the phone pointing the way you walk; reset between rooms; a change that stays inside the local range is missed.

## 4. Sound camera (card + SOUND strip on the camera HUD)
Stereo 48 kHz capture (UNPROCESSED, falling back to MIC, falling back to mono); GCC-PHAT over 300 Hz–8 kHz, lag limited to the microphone spacing (assumed 14 cm), parabolic peak, angle = asin(c·tau/d).
Simulation: error 0.1° from -70° to +70° at 15 dB SNR; works at 10 dB, rejects 3 dB; independent noise produced 0 accepted directions in 3 minutes; duplicated mono detected; a 60 µs hardware bias removed by the clap calibration.
Limits: real rooms have echoes and the real microphone spacing is unknown (expect ±10–20°); the angle is in the plane of the two microphones (hold the phone sideways for left/right); many phones give mono for "stereo", which the card reports.

## 5. Geiger mode (card)
Poisson clicks, 0.4/s at rest to ~30/s, optional vibration (max 10/s), driven by magnetometer z, camera anomaly, passive novelty and coincidences. Voice score and sonar only count with headphones connected, because speaker clicks would feed back into the microphone channels.

## 6. Pareidolia meter (card + outlines on the DECODE/AIR HUD)
Looks for face-like triplets (two similar blobs side by side, a third below their midpoint) and figure-like stacks among confirmed DECODE blobs and shows the chance of the same pattern among n random blobs:
3 blobs 0.4 %, 5 blobs 3.6 %, 8 blobs 15.8 %, 10 blobs 29.9 % (Monte Carlo, uniform positions, 4–12 px radii). A session tally compares face-like frames with the chance prediction (z). On random blobs the z statistic exceeded 2 in 5.7 % of sessions (expected 5 %).

## Not verified
Everything on a real phone: speaker/mic response above 18 kHz, stereo capture, step detection with a real gait, CPU and battery with several of these running, and whether two simultaneous microphone streams are allowed on your device.
