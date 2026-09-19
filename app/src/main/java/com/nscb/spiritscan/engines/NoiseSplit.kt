package com.nscb.spiritscan.engines

import com.nscb.spiritscan.sensor.Sample9
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Practical noise triage for a single phone magnetometer.
 * Not perfect source separation — layers that explain most false positives.
 *
 * WIRE   = 50/60 Hz periodic (mains / wall wiring)
 * MOTION = phone moving (accel)
 * PHONE  = self-noise heuristics (still + unstable short-term mag)
 * RESIDUAL = leftover after the above (unknown / environmental)
 */
data class NoiseSplitState(
    val wire: Float,       // 0..1
    val motion: Float,     // 0..1
    val phone: Float,      // 0..1
    val residual: Float,   // 0..1 unexplained
    val dominant: String,  // WIRE | MOTION | PHONE | RESIDUAL | QUIET
    val note: String
)

object NoiseSplit {

    fun analyze(
        window: List<Sample9>,
        sample: Sample9,
        boxOn: Boolean = false
    ): NoiseSplitState {
        if (window.size < 12) {
            return NoiseSplitState(0f, 0f, 0f, 0f, "QUIET", "Need more samples…")
        }

        val mag = window.map { it.magUt }
        val wire = mainsPower(mag)
        val motion = motionScore(window, sample)
        val phone = phoneSelfScore(window, sample, boxOn, motion)
        // Residual = residual variance not explained by wire/motion/phone
        val totalVar = variance(mag)
        val explained = (wire * 0.45f + motion * 0.35f + phone * 0.25f).coerceIn(0f, 1f)
        val residual = (totalVar * (1f - explained * 0.85f)).coerceIn(0f, 1f)

        val dominant = when {
            motion > 0.55f -> "MOTION"
            wire > 0.55f -> "WIRE"
            phone > 0.50f -> "PHONE"
            residual > 0.40f -> "RESIDUAL"
            else -> "QUIET"
        }

        val note = when (dominant) {
            "MOTION" -> "Phone moving — mag changes discounted"
            "WIRE" -> "Strong 50/60 Hz — wall wiring / mains"
            "PHONE" -> "Likely phone self-noise — set down or re-CAL"
            "RESIDUAL" -> "Unexplained residual after noise split"
            else -> "Quiet — no strong noise layer"
        }

        return NoiseSplitState(wire, motion, phone, residual, dominant, note)
    }

    /** Goertzel-style power at 50 and 60 Hz relative to total energy */
    private fun mainsPower(mag: List<Float>): Float {
        if (mag.size < 16) return 0f
        val n = mag.size
        fun powerAt(freqHz: Float): Float {
            // Assume ~50 Hz sample-ish stream; use relative bin
            val k = (n * freqHz * 0.02f).toInt().coerceIn(1, n - 1)
            val omega = (2.0 * Math.PI * k / n).toFloat()
            val coeff = 2f * cos(omega)
            var s0 = 0f
            var s1 = 0f
            var s2 = 0f
            for (x in mag) {
                s0 = x + coeff * s1 - s2
                s2 = s1
                s1 = s0
            }
            return max(0f, s1 * s1 + s2 * s2 - coeff * s1 * s2)
        }
        val tot = mag.fold(0f) { a, v -> a + v * v } + 1e-6f
        val p = max(powerAt(50f), powerAt(60f))
        return min(1f, sqrt(p / tot) * 1.8f)
    }

    private fun motionScore(window: List<Sample9>, sample: Sample9): Float {
        val accG = hypot(hypot(sample.accX, sample.accY), sample.accZ)
        val tilt = abs(accG - 9.81f)
        val gyro = hypot(hypot(sample.gyroX, sample.gyroY), sample.gyroZ)
        // Recent accel variance
        val accs = window.takeLast(20).map {
            hypot(hypot(it.accX, it.accY), it.accZ)
        }
        val accVar = variance(accs)
        val raw = (tilt / 3f).coerceIn(0f, 1f) * 0.35f +
            (gyro / 2f).coerceIn(0f, 1f) * 0.35f +
            (accVar * 4f).coerceIn(0f, 1f) * 0.30f
        return raw.coerceIn(0f, 1f)
    }

    /**
     * Phone self-noise: still phone + short-term mag jitter,
     * or spirit box / audio path active.
     */
    private fun phoneSelfScore(
        window: List<Sample9>,
        sample: Sample9,
        boxOn: Boolean,
        motion: Float
    ): Float {
        if (motion > 0.4f) return 0.1f // motion dominates
        val mag = window.takeLast(24).map { it.magUt }
        if (mag.size < 8) return 0f
        val shortVar = variance(mag)
        // High frequency micro-jitter while still → phone electronics
        val diffs = mag.zipWithNext { a, b -> abs(a - b) }
        val micro = if (diffs.isEmpty()) 0f else diffs.average().toFloat()
        var score = (shortVar * 3f).coerceIn(0f, 1f) * 0.5f +
            (micro / 0.8f).coerceIn(0f, 1f) * 0.4f
        if (boxOn) score = (score + 0.35f).coerceIn(0f, 1f)
        return score.coerceIn(0f, 1f)
    }

    private fun variance(xs: List<Float>): Float {
        if (xs.size < 2) return 0f
        val m = xs.average().toFloat()
        var s = 0f
        for (x in xs) {
            val d = x - m
            s += d * d
        }
        return (s / xs.size).coerceIn(0f, 10f) / 10f // normalize roughly
    }
}
