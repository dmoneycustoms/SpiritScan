package com.nscb.spiritscan.decode

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * NSCB VISUAL ANOMALY DECODER (v8.7)
 *
 * This does not detect spirits. It measures how far each region of the camera image
 * departs from a learned model of the scene, then tries to EXPLAIN every departure
 * with a known cause (dust, glare, flicker, shadow, edge shimmer, a person walking
 * through). Whatever survives all explanations is reported as UNEXPLAINED, which means
 * "unclassified by this pipeline", nothing more.
 *
 * Pipeline per camera frame (all on the analysis thread, allocation-light):
 *  1. Rotate + box-downsample the Y plane to 120x160 (or 160x120), upright.
 *  2. Exposure-normalise against a slow reference mean.
 *  3. Calibrate a per-pixel Gaussian background (Welford) for CAL_FRAMES frames.
 *  4. Gate on phone motion; relearn the scene when the phone settles after moving.
 *  5. Integer ego-shift search (+-3 px) so tiny hand drift does not smear the model.
 *  6. Per-pixel z-score with an edge-aware variance floor (edges shimmer, flat areas do not).
 *  7. Common-mode rejection (global flicker / exposure) and a global-change gate.
 *  8. 3x3 matched filter T = sum(z)/3, hit when |T| > 3.5 for 4 consecutive frames.
 *  9. Anomaly-gated learning: suspect pixels are absorbed 13x slower, so the model
 *     does not learn the thing it is trying to find.
 * 10. Lucas-Kanade flow on suspect cells, connected components, greedy tracking.
 * 11. Cause classifier. Unexplained = compact, persistent, steady, not saturated.
 */

enum class Cause(val label: String, val explained: Boolean) {
    UNEXPLAINED("UNEXPLAINED", false),
    PARTICLE("PARTICLE/ORB", true),
    GLARE("GLARE", true),
    FLICKER("LIGHT FLICKER", true),
    SHADOW("SHADOW/LIGHT", true),
    EDGE("EDGE SHIMMER", true),
    MOVER("LARGE MOVER", true)
}

enum class DecodeStatus { CALIBRATING, GATED, CLEAR, EXPLAINED, UNEXPLAINED }

class DecodedBlob(
    val id: Int,
    /** Centroid and half-extent, normalised 0..1 in upright map space. */
    val cx: Float,
    val cy: Float,
    val halfW: Float,
    val halfH: Float,
    /** Peak matched-filter statistic in sigma units (unsigned). */
    val sigma: Float,
    val areaFrac: Float,
    val ageSec: Float,
    val cause: Cause,
    val confidence: Float,
    /** Mean independent flow in map pixels per frame. */
    val flowX: Float,
    val flowY: Float,
    /** +1 brighter than background, -1 darker. */
    val polarity: Int,
    val confirmed: Boolean
)

class DecodeFrame(
    val seq: Long,
    val w: Int,
    val h: Int,
    /**
     * packed = true : ARGB where R=|T|/9, G=flow/2px, B=persistence/30, A=255 (for the AGSL shader).
     * packed = false: ready-to-draw false-colour ARGB (pre-API-33 fallback).
     */
    val pixels: IntArray,
    val packed: Boolean,
    val blobs: List<DecodedBlob>,
    val flowW: Int,
    val flowH: Int,
    val flow: FloatArray,
    val status: DecodeStatus,
    val note: String,
    val calibration: Float,
    val noiseFloor: Float,
    val commonMode: Float,
    val globalActivity: Float,
    val egoX: Int,
    val egoY: Int,
    val anomalyIndex: Float,
    val unexplainedCount: Int
)

class AnomalyDecoder {

    companion object {
        const val LONG_SIDE = 160
        const val SHORT_SIDE = 120
        private const val CAL_FRAMES = 45
        private const val EGO_R = 3
        private const val T_HIT = 3.5f
        private const val T_SUSPECT = 2.5f
        private const val RUN_MIN = 4
        private const val MIN_AREA = 4
        private const val ALPHA_BG = 0.02f
        private const val ALPHA_SUSPECT = 0.0015f
        private const val CELL = 4
        private const val MAX_BLOBS = 8
        private const val MOTION_GATE = 0.35f
        private const val GLOBAL_GATE = 0.22f
        private const val CONFIRM_SEC = 0.5f
    }

    /** True on API < 33: emit false-colour pixels instead of packed data. */
    var colormapFallback = false

    @Volatile
    private var resetRequested = true

    fun reset() {
        resetRequested = true
    }

    // ---- buffers -----------------------------------------------------------------
    private var w = 0
    private var h = 0
    private var n = 0
    private var fw = 0
    private var fh = 0
    private var cur = FloatArray(0)
    private var xa = FloatArray(0)
    private var prevXa = FloatArray(0)
    private var mu = FloatArray(0)
    private var vr = FloatArray(0)
    private var z = FloatArray(0)
    private var tt = FloatArray(0)
    private var tmp = FloatArray(0)
    private var runLen = IntArray(0)
    private var lab = IntArray(0)
    private var stack = IntArray(0)
    private var sus = BooleanArray(0)
    private var susD = BooleanArray(0)
    private var flow = FloatArray(0)
    private var fmag = FloatArray(0)

    // ---- running state -----------------------------------------------------------
    private var calCount = 0
    private var haveRef = false
    private var refMean = 0.5f
    private var lastMean = 0f
    private var egoRecent = 0f
    private var cmAbs = 0f
    private var movedSince = false
    private var seq = 0L
    private var lastT = 0L
    private var dtEma = 0.05f
    private var nextTrackId = 1

    private class Comp {
        var area = 0
        var sx = 0L
        var sy = 0L
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = 0
        var maxY = 0
        var sT = 0f
        var peak = 0f
        var sBright = 0f
        var sGrad = 0f
        var sFx = 0f
        var sFy = 0f
        var sRun = 0L
    }

    private class Track(val id: Int) {
        var cx = 0f
        var cy = 0f
        var frames = 0
        var missed = 0
        var matched = false
        val ser = FloatArray(16)
        var si = 0
        var sn = 0
        fun push(v: Float) {
            ser[si] = v
            si = (si + 1) % ser.size
            if (sn < ser.size) sn++
        }
    }

    private val tracks = ArrayList<Track>()

    private fun alloc(nw: Int, nh: Int) {
        w = nw
        h = nh
        n = nw * nh
        fw = nw / CELL
        fh = nh / CELL
        cur = FloatArray(n)
        xa = FloatArray(n)
        prevXa = FloatArray(n)
        mu = FloatArray(n)
        vr = FloatArray(n)
        z = FloatArray(n)
        tt = FloatArray(n)
        tmp = FloatArray(n)
        runLen = IntArray(n)
        lab = IntArray(n)
        stack = IntArray(n)
        sus = BooleanArray(n)
        susD = BooleanArray(n)
        flow = FloatArray(2 * fw * fh)
        fmag = FloatArray(fw * fh)
        calCount = 0
        tracks.clear()
    }

    private fun resetModel() {
        calCount = 0
        java.util.Arrays.fill(runLen, 0)
        tracks.clear()
        egoRecent = 0f
        cmAbs = 0f
        movedSince = false
    }

    // =================================================================================
    fun process(
        buf: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        srcW: Int,
        srcH: Int,
        rotation: Int,
        tNs: Long,
        phoneMotion: Float
    ): DecodeFrame {
        val rot = ((rotation % 360) + 360) % 360
        val portrait = rot == 90 || rot == 270
        val nw = if (portrait) SHORT_SIDE else LONG_SIDE
        val nh = if (portrait) LONG_SIDE else SHORT_SIDE
        if (nw != w || nh != h) alloc(nw, nh)
        if (resetRequested) {
            resetModel()
            resetRequested = false
        }

        if (lastT != 0L) {
            val dt = (tNs - lastT) / 1e9f
            if (dt in 0.001f..0.5f) dtEma = dtEma * 0.9f + dt * 0.1f
        }
        lastT = tNs
        seq++

        downsample(buf, rowStride, pixelStride, srcW, srcH, rot)

        // ---- exposure normalisation ------------------------------------------------
        val mean = max(lastMean, 1e-3f)
        if (!haveRef) {
            refMean = mean
            haveRef = true
        } else {
            refMean += (mean - refMean) * 0.02f
        }
        val gain = refMean / mean
        for (i in 0 until n) cur[i] = cur[i] * gain

        // ---- calibration -----------------------------------------------------------
        if (calCount < CAL_FRAMES) {
            calCount++
            val c = calCount.toFloat()
            for (i in 0 until n) {
                val x = cur[i]
                if (calCount == 1) {
                    mu[i] = x
                    vr[i] = 0f
                } else {
                    val d = x - mu[i]
                    mu[i] += d / c
                    vr[i] += (d * (x - mu[i]) - vr[i]) / c
                }
            }
            System.arraycopy(cur, 0, prevXa, 0, n)
            return build(
                DecodeStatus.CALIBRATING,
                "Learning scene ${(100 * calCount / CAL_FRAMES)}% — hold the phone still",
                emptyList(), 0f, 0, 0, 0f, false
            )
        }

        // ---- motion gate -----------------------------------------------------------
        if (phoneMotion > MOTION_GATE) {
            movedSince = true
            System.arraycopy(cur, 0, prevXa, 0, n)
            return build(
                DecodeStatus.GATED, "Phone moving — brace it or set it down",
                emptyList(), 0f, 0, 0, 0f, false
            )
        }
        if (movedSince) {
            resetModel()
            return build(
                DecodeStatus.CALIBRATING, "Phone settled — relearning scene",
                emptyList(), 0f, 0, 0, 0f, false
            )
        }

        // ---- ego shift -------------------------------------------------------------
        var bestDx = 0
        var bestDy = 0
        var best = Float.MAX_VALUE
        var zeroSad = 0f
        val m = EGO_R + 1
        for (dy in -EGO_R..EGO_R) {
            for (dx in -EGO_R..EGO_R) {
                var s = 0f
                var y = m
                while (y < h - m) {
                    val row = y * w
                    val row2 = (y + dy) * w
                    var x = m
                    while (x < w - m) {
                        s += abs(cur[row2 + x + dx] - mu[row + x])
                        x += 2
                    }
                    y += 2
                }
                if (dx == 0 && dy == 0) zeroSad = s
                if (s < best) {
                    best = s
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        if ((bestDx != 0 || bestDy != 0) && best > zeroSad * 0.92f) {
            bestDx = 0
            bestDy = 0
        }
        egoRecent = egoRecent * 0.9f + hypot(bestDx.toFloat(), bestDy.toFloat()) * 0.1f
        for (y in 0 until h) {
            val sy = (y + bestDy).coerceIn(0, h - 1)
            for (x in 0 until w) {
                val sx = (x + bestDx).coerceIn(0, w - 1)
                xa[y * w + x] = cur[sy * w + sx]
            }
        }

        // ---- z-score with edge-aware variance --------------------------------------
        var varSum = 0f
        for (i in 0 until n) varSum += vr[i]
        val meanVar = varSum / n
        val floor2 = 0.3f * meanVar + 1e-7f // (0.55 * sigma_mean)^2
        for (y in 0 until h) {
            val ym = if (y > 0) y - 1 else y
            val yp = if (y < h - 1) y + 1 else y
            for (x in 0 until w) {
                val xm = if (x > 0) x - 1 else x
                val xp = if (x < w - 1) x + 1 else x
                val i = y * w + x
                val gx = (mu[y * w + xp] - mu[y * w + xm]) * 0.5f
                val gy = (mu[yp * w + x] - mu[ym * w + x]) * 0.5f
                val s2 = vr[i] + 0.25f * (gx * gx + gy * gy) + floor2
                z[i] = (xa[i] - mu[i]) / sqrt(s2)
            }
        }

        // ---- common-mode rejection + global gate -----------------------------------
        var cs = 0f
        var cc = 0
        for (i in 0 until n) {
            val v = z[i]
            if (v > -3f && v < 3f) {
                cs += v
                cc++
            }
        }
        val cm = if (cc > 0) cs / cc else 0f
        var act = 0
        for (i in 0 until n) {
            z[i] -= cm
            if (abs(z[i]) > 3f) act++
        }
        val globalAct = act.toFloat() / n
        cmAbs = cmAbs * 0.95f + abs(cm) * 0.05f

        if (globalAct > GLOBAL_GATE) {
            for (i in 0 until n) {
                val d = xa[i] - mu[i]
                mu[i] += 0.08f * d
                vr[i] += 0.08f * (d * d - vr[i])
                if (vr[i] < 1e-7f) vr[i] = 1e-7f
            }
            java.util.Arrays.fill(runLen, 0)
            System.arraycopy(xa, 0, prevXa, 0, n)
            return build(
                DecodeStatus.GATED, "Global change (light/exposure) — re-adapting",
                emptyList(), cm, bestDx, bestDy, globalAct, false
            )
        }

        // ---- 3x3 matched filter ----------------------------------------------------
        for (y in 0 until h) {
            val r = y * w
            for (x in 0 until w) {
                val xm = if (x > 0) x - 1 else x
                val xp = if (x < w - 1) x + 1 else x
                tmp[r + x] = z[r + xm] + z[r + x] + z[r + xp]
            }
        }
        for (y in 0 until h) {
            val ym = if (y > 0) y - 1 else y
            val yp = if (y < h - 1) y + 1 else y
            for (x in 0 until w) {
                tt[y * w + x] = (tmp[ym * w + x] + tmp[y * w + x] + tmp[yp * w + x]) / 3f
            }
        }

        // ---- persistence + suspect map ---------------------------------------------
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val a = abs(tt[i])
                val border = x < 2 || y < 2 || x >= w - 2 || y >= h - 2
                runLen[i] = if (!border && a > T_HIT) min(runLen[i] + 1, 120) else 0
                sus[i] = a > T_SUSPECT
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var any = false
                var yy = max(0, y - 1)
                while (yy <= min(h - 1, y + 1) && !any) {
                    var xx = max(0, x - 1)
                    while (xx <= min(w - 1, x + 1)) {
                        if (sus[yy * w + xx]) {
                            any = true
                            break
                        }
                        xx++
                    }
                    yy++
                }
                susD[y * w + x] = any
            }
        }

        // ---- Lucas-Kanade flow on suspect cells ------------------------------------
        java.util.Arrays.fill(flow, 0f)
        java.util.Arrays.fill(fmag, 0f)
        for (cy in 0 until fh) {
            for (cx in 0 until fw) {
                val x0 = cx * CELL
                val y0 = cy * CELL
                var any = false
                for (yy in y0 until y0 + CELL) {
                    for (xx in x0 until x0 + CELL) {
                        if (sus[yy * w + xx]) any = true
                    }
                }
                if (!any) continue
                var a = 0f
                var b = 0f
                var c = 0f
                var bx = 0f
                var by = 0f
                for (yy in max(1, y0 - 1) until min(h - 1, y0 + CELL + 1)) {
                    for (xx in max(1, x0 - 1) until min(w - 1, x0 + CELL + 1)) {
                        val i = yy * w + xx
                        val ix = (xa[i + 1] - xa[i - 1]) * 0.5f
                        val iy = (xa[i + w] - xa[i - w]) * 0.5f
                        val it = xa[i] - prevXa[i]
                        a += ix * ix
                        b += ix * iy
                        c += iy * iy
                        bx -= ix * it
                        by -= iy * it
                    }
                }
                val det = a * c - b * b
                val tr = a + c
                if (tr > 4e-4f && 4f * det / (tr * tr) > 0.1f) {
                    val u = ((c * bx - b * by) / det).coerceIn(-3f, 3f)
                    val v = ((a * by - b * bx) / det).coerceIn(-3f, 3f)
                    val ci = cy * fw + cx
                    flow[2 * ci] = u
                    flow[2 * ci + 1] = v
                    fmag[ci] = hypot(u, v)
                }
            }
        }

        // ---- connected components --------------------------------------------------
        java.util.Arrays.fill(lab, 0)
        val comps = ArrayList<Comp>()
        var nLab = 0
        for (start in 0 until n) {
            if (lab[start] != 0 || runLen[start] < RUN_MIN) continue
            nLab++
            val cp = Comp()
            var sp = 0
            stack[sp++] = start
            lab[start] = nLab
            while (sp > 0) {
                val p = stack[--sp]
                val px = p % w
                val py = p / w
                cp.area++
                cp.sx += px
                cp.sy += py
                if (px < cp.minX) cp.minX = px
                if (px > cp.maxX) cp.maxX = px
                if (py < cp.minY) cp.minY = py
                if (py > cp.maxY) cp.maxY = py
                val t = tt[p]
                cp.sT += t
                if (abs(t) > cp.peak) cp.peak = abs(t)
                cp.sBright += cur[p]
                cp.sRun += runLen[p]
                val gxm = abs(mu[p + 1] - mu[p - 1])
                val gym = abs(mu[p + w] - mu[p - w])
                cp.sGrad += gxm + gym
                val ci = min(py / CELL, fh - 1) * fw + min(px / CELL, fw - 1)
                cp.sFx += flow[2 * ci]
                cp.sFy += flow[2 * ci + 1]
                if (px > 0) {
                    val q = p - 1
                    if (lab[q] == 0 && runLen[q] >= RUN_MIN) {
                        lab[q] = nLab; stack[sp++] = q
                    }
                }
                if (px < w - 1) {
                    val q = p + 1
                    if (lab[q] == 0 && runLen[q] >= RUN_MIN) {
                        lab[q] = nLab; stack[sp++] = q
                    }
                }
                if (py > 0) {
                    val q = p - w
                    if (lab[q] == 0 && runLen[q] >= RUN_MIN) {
                        lab[q] = nLab; stack[sp++] = q
                    }
                }
                if (py < h - 1) {
                    val q = p + w
                    if (lab[q] == 0 && runLen[q] >= RUN_MIN) {
                        lab[q] = nLab; stack[sp++] = q
                    }
                }
            }
            if (cp.area >= MIN_AREA) comps.add(cp)
        }
        comps.sortByDescending { it.peak * it.area }
        while (comps.size > MAX_BLOBS) comps.removeAt(comps.size - 1)

        // ---- tracking --------------------------------------------------------------
        for (t in tracks) t.matched = false
        val blobs = ArrayList<DecodedBlob>(comps.size)
        var unexplained = 0
        var keep = 1f
        for (cp in comps) {
            val ccx = cp.sx.toFloat() / cp.area
            val ccy = cp.sy.toFloat() / cp.area
            var tr: Track? = null
            var bd = 10f
            for (t in tracks) {
                if (t.matched) continue
                val d = hypot(t.cx - ccx, t.cy - ccy)
                if (d < bd) {
                    bd = d
                    tr = t
                }
            }
            if (tr == null) {
                tr = Track(nextTrackId++)
                tracks.add(tr)
            }
            tr.matched = true
            tr.missed = 0
            tr.frames++
            tr.cx = ccx
            tr.cy = ccy
            val meanT = cp.sT / cp.area
            tr.push(meanT)

            val areaFrac = cp.area.toFloat() / n
            val ageSec = tr.frames * dtEma
            val fx = cp.sFx / cp.area
            val fy = cp.sFy / cp.area
            val flowMag = hypot(fx, fy)
            val brightness = cp.sBright / cp.area
            val gradMean = cp.sGrad / cp.area
            val polarity = if (meanT >= 0f) 1 else -1

            // temporal modulation depth of the blob statistic
            var depth = 0f
            var changes = 0
            if (tr.sn >= 10) {
                var mx = -1e9f
                var mn = 1e9f
                var avg = 0f
                for (k in 0 until tr.sn) {
                    val v = tr.ser[k]
                    if (v > mx) mx = v
                    if (v < mn) mn = v
                    avg += v
                }
                avg /= tr.sn
                depth = (mx - mn) / (abs(avg) + 1e-3f)
                var prevSign = 0
                for (k in 0 until tr.sn) {
                    val idx = (tr.si - tr.sn + k + tr.ser.size * 2) % tr.ser.size
                    val sgn = if (tr.ser[idx] - avg >= 0f) 1 else -1
                    if (prevSign != 0 && sgn != prevSign) changes++
                    prevSign = sgn
                }
            }

            val cause: Cause = when {
                brightness > 0.90f -> Cause.GLARE
                areaFrac < 0.010f && flowMag > 0.8f && polarity > 0 -> Cause.PARTICLE
                areaFrac > 0.12f -> Cause.SHADOW
                areaFrac > 0.03f && flowMag > 0.3f -> Cause.MOVER
                depth > 0.9f && changes >= 5 -> Cause.FLICKER
                egoRecent > 0.6f && gradMean > 0.08f -> Cause.EDGE
                else -> Cause.UNEXPLAINED
            }

            val peakTerm = ((cp.peak - T_HIT) / 6f).coerceIn(0f, 1f)
            val ageTerm = (ageSec / 3f).coerceIn(0f, 1f)
            val steadyTerm = 1f - (depth / 1.5f).coerceIn(0f, 1f)
            val conf = if (cause == Cause.UNEXPLAINED) {
                (0.40f * peakTerm + 0.35f * ageTerm + 0.25f * steadyTerm).coerceIn(0f, 1f)
            } else {
                (0.5f * peakTerm + 0.5f * ageTerm).coerceIn(0f, 1f)
            }
            val confirmed = ageSec >= CONFIRM_SEC
            if (cause == Cause.UNEXPLAINED && confirmed) {
                unexplained++
                keep *= (1f - 0.9f * conf)
            }

            blobs.add(
                DecodedBlob(
                    id = tr.id,
                    cx = (cp.minX + cp.maxX + 1) * 0.5f / w,
                    cy = (cp.minY + cp.maxY + 1) * 0.5f / h,
                    halfW = (cp.maxX - cp.minX + 1) * 0.5f / w,
                    halfH = (cp.maxY - cp.minY + 1) * 0.5f / h,
                    sigma = cp.peak,
                    areaFrac = areaFrac,
                    ageSec = ageSec,
                    cause = cause,
                    confidence = conf,
                    flowX = fx,
                    flowY = fy,
                    polarity = polarity,
                    confirmed = confirmed
                )
            )
        }
        val iter = tracks.iterator()
        while (iter.hasNext()) {
            val t = iter.next()
            if (!t.matched) {
                t.missed++
                if (t.missed > 10) iter.remove()
            }
        }

        // ---- anomaly-gated learning ------------------------------------------------
        for (i in 0 until n) {
            val a = if (susD[i]) ALPHA_SUSPECT else ALPHA_BG
            val d = xa[i] - mu[i]
            mu[i] += a * d
            vr[i] += a * (d * d - vr[i])
            if (vr[i] < 1e-7f) vr[i] = 1e-7f
        }
        System.arraycopy(xa, 0, prevXa, 0, n)

        val anomalyIndex = 1f - keep
        val status = when {
            unexplained > 0 -> DecodeStatus.UNEXPLAINED
            blobs.isNotEmpty() -> DecodeStatus.EXPLAINED
            else -> DecodeStatus.CLEAR
        }
        val note = when (status) {
            DecodeStatus.UNEXPLAINED -> "$unexplained unexplained region(s) after cause elimination"
            DecodeStatus.EXPLAINED -> blobs.joinToString(", ") { it.cause.label }.take(60) + " — explained"
            else -> "Scene matches learned model"
        }
        return build(status, note, blobs, cm, bestDx, bestDy, globalAct, true, anomalyIndex, unexplained)
    }

    // =================================================================================
    private fun downsample(
        buf: ByteBuffer, rowStride: Int, pixelStride: Int,
        srcW: Int, srcH: Int, rot: Int
    ) {
        val swap = rot == 90 || rot == 270
        val upW = if (swap) srcH else srcW
        val upH = if (swap) srcW else srcH
        val bx = upW.toFloat() / w
        val by = upH.toFloat() / h
        val ox1 = bx * 0.25f
        val ox2 = bx * 0.75f
        val oy1 = by * 0.25f
        val oy2 = by * 0.75f
        val cap = buf.limit()
        var sum = 0f
        for (y in 0 until h) {
            val uy0 = y * by
            for (x in 0 until w) {
                val ux0 = x * bx
                var acc = 0
                for (k in 0 until 4) {
                    val ux = (ux0 + (if ((k and 1) == 0) ox1 else ox2)).toInt().coerceIn(0, upW - 1)
                    val uy = (uy0 + (if ((k and 2) == 0) oy1 else oy2)).toInt().coerceIn(0, upH - 1)
                    val sx: Int
                    val sy: Int
                    when (rot) {
                        90 -> { sx = uy; sy = srcH - 1 - ux }
                        180 -> { sx = srcW - 1 - ux; sy = srcH - 1 - uy }
                        270 -> { sx = srcW - 1 - uy; sy = ux }
                        else -> { sx = ux; sy = uy }
                    }
                    val idx = sy * rowStride + sx * pixelStride
                    acc += if (idx in 0 until cap) (buf.get(idx).toInt() and 0xFF) else 0
                }
                val v = acc / 1020f
                cur[y * w + x] = v
                sum += v
            }
        }
        lastMean = sum / n
    }

    private fun build(
        status: DecodeStatus,
        note: String,
        blobs: List<DecodedBlob>,
        cm: Float,
        egoX: Int,
        egoY: Int,
        globalAct: Float,
        withMaps: Boolean,
        anomalyIndex: Float = 0f,
        unexplained: Int = 0
    ): DecodeFrame {
        val px = IntArray(n)
        if (withMaps) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val a = abs(tt[i])
                    if (!colormapFallback) {
                        val r = (a / 9f * 255f).coerceIn(0f, 255f).toInt()
                        val ci = min(y / CELL, fh - 1) * fw + min(x / CELL, fw - 1)
                        val g = (fmag[ci] / 2f * 255f).coerceIn(0f, 255f).toInt()
                        val b = (runLen[i] / 30f * 255f).coerceIn(0f, 255f).toInt()
                        px[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                    } else {
                        val t = (a / 9f).coerceIn(0f, 1f)
                        val al = ((a - 2f) / 4f).coerceIn(0f, 1f) * 0.85f
                        val rr = (t * 2.5f).coerceIn(0f, 1f)
                        val gg = (t * 2.5f - 1f).coerceIn(0f, 1f)
                        val bb = (t * 2.5f - 2f).coerceIn(0f, 1f)
                        px[i] = ((al * 255f).toInt() shl 24) or
                            ((rr * 255f).toInt() shl 16) or
                            ((gg * 255f).toInt() shl 8) or
                            (bb * 255f).toInt()
                    }
                }
            }
        } else if (!colormapFallback) {
            java.util.Arrays.fill(px, (255 shl 24))
        }
        var varSum = 0f
        for (i in 0 until n) varSum += vr[i]
        val floor = sqrt(varSum / max(n, 1))
        return DecodeFrame(
            seq = seq,
            w = w,
            h = h,
            pixels = px,
            packed = !colormapFallback,
            blobs = blobs,
            flowW = fw,
            flowH = fh,
            flow = if (withMaps) flow.copyOf() else FloatArray(flow.size),
            status = status,
            note = note,
            calibration = (calCount.toFloat() / CAL_FRAMES).coerceIn(0f, 1f),
            noiseFloor = floor,
            commonMode = cm,
            globalActivity = globalAct,
            egoX = egoX,
            egoY = egoY,
            anomalyIndex = anomalyIndex,
            unexplainedCount = unexplained
        )
    }
}
