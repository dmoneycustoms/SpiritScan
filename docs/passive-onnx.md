# v8.9 — live context, passive ONNX novelty, sham-controlled trial

None of this detects spirits. It makes the app better at saying what an event probably WAS, and at testing whether the spirit box changes anything.

## Live context (card: LIVE CONTEXT)
Polls every 5 min: NOAA SWPC 1-minute estimated Kp (checked live while building: JSON array, last element has `estimated_kp`), Open-Meteo current weather
for a lat/lon you type (stored on the phone only), USGS M4.5+ quakes of the last day. Adds the INTERNET permission (new in this version).
"Would explain an event now" lists only things that match: Kp >= 4 (storm at >= 5), thunderstorm weather code 95-99, gusts >= 50 km/h,
a quake within 60 min and 1500 km (or M6.5+ anywhere). It is a label on events, never a detector.

## Passive ingest + ONNX novelty (card: PASSIVE INGEST)
Once a second a 12-channel vector is read: magnetometer |B| and z, residual, audio z and speech residual, camera score and frame MAD,
spectral residual, voice score, strongest band excess, DECODE index, AIR flow index. Raw vectors are appended to
`Android/data/com.nscb.spiritscan/files/passive/passive_YYYYMMDD.csv`.
1. BASELINE: 5 minutes of "normal" for this phone and room (mean, spread, covariance). Channels that never change are ignored.
2. MODEL score: the last 16 s x 12 channels go through `assets/models/novelty_ae.onnx` (a small denoising autoencoder, hand-encoded ONNX, opset 13).
   The score is the mean of the 8 largest squared reconstruction errors, expressed against the upper-tail spread of the baseline windows.
3. STATS score: Mahalanobis distance of the current vector, same normalisation.
4. An EVENT opens when either stays >= 6 for 3 s and closes after 5 s below 3. Each event is stamped with the channels that contributed most and
   with the live context text. The camera HUD shows `NOV m.. s..` while it runs.

Synthetic evaluation (the network was trained on synthetic correlated, autocorrelated sensor-like windows; AUC normal vs anomalous):

| anomaly | AE mean err | AE top-8 err (used) | Mahalanobis |
|---|---|---|---|
| single spike | 0.78 | 0.99 | 0.98 |
| step on one channel | 0.82 | 0.84 | 0.81 |
| 0.25 Hz oscillation on two channels | 0.93 | 0.94 | 0.78 |
| jitter on one channel | 0.96 | 1.00 | 0.94 |

So the network adds something over plain statistics mainly for rhythmic and jittery disturbances. Honest limits:
- The network never saw YOUR room. Everything room-specific comes from the baseline normalisation, not the network.
- With a 5 min baseline: 0.0 false events per hour on stationary synthetic data and a 4.5-sigma 8 s step caught 5/5. With 150 s one test seed gave 20 false events in 2 h,
  so use the 5 min default. Real rooms are not stationary (doors, calls, lamps, day/night), so expect real events that are simply "unlike the baseline".
- Without the model (stats only) a 4.5-sigma step is not caught; only larger disturbances.
- The pre-existing `spiritscan_mars_ood.onnx` in the app is an untrained placeholder; this one is trained, but only on synthetic data.

## Sham-controlled trial (card: SHAM-CONTROLLED TRIAL)
Random, balanced, pre-committed order of LIVE (box ON) and SHAM (box OFF) blocks, 6-12 blocks of 2-5 min, first 10 s of each block ignored.
Counts per block: voice-like events (primary, fixed in advance), unexplained band anomalies, your MARK taps. Exact permutation test on block rates
(with 8 blocks the smallest possible p is 0.029; with 10 it is 0.008; simulated false-positive rate at p < 0.05 was 1.1 %).
Results and the schedule are saved as JSON in `.../files/trials/`. The condition is hidden on screen, but you can hear whether the box is on; the schedule is what
protects the analysis. Whatever it finds, an effect that appears only with the box ON is first an instrument effect (the box's own noise).

## Language model
Not included. A language model decoding noise into words produces fluent text from nothing; that is pareidolia with extra steps. The trial engine writes its
plain-language summary from a fixed template instead. If you want an on-device model later, the safe job for it is summarising the structured session JSON, and any
transcription experiment would have to pass the same sham/baseline test before its output is shown.

## Known limits
Not compiled by me as a full Android build. The Java engines and the ONNX file are tested; the Kotlin glue and Compose cards are not. Three more cards make the scroll long.
