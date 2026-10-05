# DECODE — visual anomaly decoder (v8.7.1)

Select the **DECODE** chip on the HUD. Set the phone down (or brace it) and let it learn the scene (~3 s).

**This is not a ghost detector.** It measures where the camera image departs from a learned model of the
room, tries to explain each departure (dust, glare, flicker, shadow, edge shimmer, a person walking
through), and reports what is left as `UNEXPLAINED` — meaning *unclassified by this pipeline*.

## What is on screen
| Element | Meaning |
|---|---|
| Inferno heat field (GPU / AGSL, Android 13+) | matched-filter significance in sigma. Contour lines every 1 sigma |
| Cyan tint / arrows | Lucas-Kanade flow left over after removing camera shift |
| Red brackets + ripple | UNEXPLAINED, confirmed (>= 0.5 s) |
| Amber brackets | explained (label says by what) |
| `x-chan n/4` | how many other channels agree: mag residual, audio, 72 Hz box, dense flow |
| `AI` | anomaly index, 1 - prod(1 - 0.9 * confidence) over confirmed unexplained regions |

A DECODE `UNEXPLAINED` only raises the main alert when the magnetometer residual filter is also active.

## Pipeline
1. Y plane -> rotated, 4-tap box downsample to 120x160 (upright)
2. exposure-normalise to a slow reference mean
3. 45-frame Welford calibration of a per-pixel Gaussian model
4. gate on gyro/accel phone motion; relearn when the phone settles after moving
5. integer ego-shift search (+-3 px) against the model
6. z = (x - mu) / sqrt(var + 0.25*|grad mu|^2 + floor^2)   (edges are allowed to shimmer, flat areas are not)
7. common-mode rejection + global-change gate (>22 % of pixels beyond 3 sigma = lighting event, re-adapt)
8. 3x3 matched filter T = sum(z)/3; hit when |T| > 3.5 for 4 consecutive frames
9. anomaly-gated learning: suspect pixels adapt 13x slower so the model does not absorb what it is hunting
10. Lucas-Kanade flow on suspect cells, connected components, greedy tracker
11. cause classifier: GLARE, PARTICLE/ORB, SHADOW, LARGE MOVER, LIGHT FLICKER, EDGE SHIMMER, else UNEXPLAINED

## Camera discipline (while DECODE is selected)
OIS and video stabilisation off, minimal noise reduction, AE + AWB locked after 1.5 s. Every option is only
requested if the camera advertises support, and everything is released when you leave DECODE.

## Numpy prototype results (synthetic: textured scene, 3 % gain flicker, 1 px jitter on 30 % of frames)
- pure noise, 355 frames: 0 false blobs
- orb at ~10 sigma raw, static: detected 66 % of frames over 275 frames (it is slowly absorbed by design)
- moving orb 150-230: detected 88 % of frames inside the window, 1 % after
- orb at ~2.5 sigma raw: not detected (below the noise gate, by design)

These numbers are from a prototype of the same maths, not from the Kotlin build on a phone.

## Limits
- Handheld use is mostly gated; this wants a tripod or a flat surface.
- Slow changes (more than ~10 s) are absorbed into the model by design.
- Not compiled or run on-device when this was written. First CI build may need small fixes.

## v8.7.1 changes (from field screenshots of v8.7)
v8.7 reported 5–6 "unexplained" regions at 99 % in dark and handheld scenes while every other channel said nothing
was there. Causes and fixes:
- **No absolute noise floor**: in near-black frames sigma was 0.005, so pixel flicker scored 14σ. Now floored at 0.010 and
  frames with mean luma < 0.07 report `DARK` instead of being scored.
- **Learning accepted a moving camera**: the motion gate sat after calibration. It now gates learning too, and learning
  restarts if consecutive frames differ by more than 3 % (mean abs).
- **Handheld edge streaks**: the ego-shift search saturated at +-3 px. Hitting the limit, or a smoothed ego above 0.75 px,
  now gates the frame. Thin streaks (aspect > 3.5) and blobs sitting on strong scene edges are classified `EDGE SHIMMER`.
- **Stricter motion metric** for DECODE: trips at ~0.18 rad/s or ~0.4 m/s^2 instead of ~0.9 rad/s.
- **Score**: now the strongest confirmed region's confidence. It no longer climbs to 99 % just because there are many blobs.
- **Cross-channel**: red `UNEXPLAINED` requires the magnetometer residual or the microphone to agree (x-chan n/2).
  Without that the HUD says `VISUAL ONLY` in amber. Camera flow and the box's own audio-timing jitter are not independent
  evidence and no longer count.
- **Tracker**: adaptive match radius and 20-frame memory so IDs stop churning. Only confirmed (0.8 s) regions are drawn,
  max 4, short labels.

Prototype re-run with the new floor/persistence: pure noise 0 false blobs, dark+noise 0 false blobs, moving orb 70 % of
frames in window, static 10σ orb 52 %. Sensitivity is slightly lower on purpose. Still not compiled on a phone by me.

## v8.7.2 — gates were too strict
v8.7.1 gated on tiny hand movement and never recovered. Changes:
- Motion gate now trips at 0.50 rad/s (NORMAL) instead of ~0.18; accel at ~1 m/s^2.
- Tap the DECODE status strip to cycle sensitivity STRICT (0.30) / NORMAL (0.50) / LOOSE (0.80 rad/s). Looser = tolerates more
  hand movement but produces more false candidates. Shown as `sens ...` on the strip.
- Small camera shifts (up to +-2 px) are compensated by the ego search instead of gated. Only sustained saturation (3 frames)
  or a high smoothed drift gates.
- A brief wobble no longer throws the learned scene away: the model is relearned only after ~0.8 s of continuous gating.
- Learning tolerance doubled (restart threshold 0.05 mean-abs frame difference).
- Gate notes now print the measured value vs the threshold so you can see what tripped it.
