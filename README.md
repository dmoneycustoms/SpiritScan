# SpiritScan Android (NSCB v8.3)

On-device field instrument: magnetometer, accelerometer, gyroscope, ambient temperature (if the phone has one), white/pink/72 Hz/EVP spirit box, Jones HV → SDE → Omega → Olympus-Q → spherical QIDA, property walk grid, site verdict.

This is **not** a ghost detector. Candidate-entity is an unclassified residual after device and environmental subtraction.

## How you get an APK

GitHub Actions builds a **debug-signed, sideloadable** APK on every push.

1. Create an empty GitHub repo.
2. Upload **this entire folder** (the contents of this directory) as the repository root.
3. Push to `main`.
4. Open the repo → **Actions** → **Build APK** → download **SpiritScan-apk**.
5. On the phone: Settings → security → allow unknown sources → install the APK.

If Actions is disabled on a new repo, enable it once under Settings → Actions.

## Local build (optional)

```bash
./gradlew assembleRelease
# APK appears at: app/build/outputs/apk/release/
```

Requires JDK 17+.

## What the APK does

- **Arm** — magnetometer / IMU stream into a 150-sample window.
- **Calibrate 8s** — Welford baseline. Detection waits for lock.
- **Walk property** — step dead-reckoning paints |B| residual on a ±10 m grid.
- **Spirit box** — white noise, pink 1/f, 72 Hz NSCB lock, EVP 250–1800 Hz hop.
- **Site verdict** — quiet / environment / device / unclassified residual.

Ambient temperature uses `Sensor.TYPE_AMBIENT_TEMPERATURE`. Most phones do **not** have this sensor. False-temp Δ is luminance contrast, not a bolometer.

## Kernels

Kotlin ports of the five shipped ONNX graphs (same operator order as the web instrument). Original `.onnx` files are in `app/src/main/assets/models/`.

## Sideload signing

Release builds are signed with the Android **debug** keystore so the artifact installs without a Play App Signing key. Replace `signingConfig` in `app/build.gradle.kts` before a store release.

---

## v8.3 Full Update (integrated)

The complete NSCB v8.3 upgrade has been merged into this repo:

- **9 scan modes** — Jones, Magnetic, QIDA, Omega, SDE, Residual, Interference, Survey, Entity
- **Fusion engine** — multi-channel weighting + temporal smoothing + interference suppression
- **Shader engine** — distortion, pulse, spectral, ripple, glow, interference flash over live camera
- **Ultra layer** — grid overlay, corner markers, animated entity ring (composite halo / confidence / pulse)
- **Tactical HUD** — composite bar, confidence, stability, micro-bars
- **Audio FX engine** — QIDA pulse, SDE distortion, Omega hum, mag tick, interference alarm
- **Diagnostics + performance overlays** — FPS, frame/fusion time, jitter, warm-start, frame skip

See `docs/v8.3-upgrade-integration.md` for the full module map.
