package com.nscb.spiritscan.engines

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Unknown audio anomaly via rolling ambient baseline (Part 1.1).
 * Uses SpiritBox RMS as a proxy level when mic capture isn't separate.
 * Spike > N sigma above rolling mean, and not explained as steady box output, → UNKNOWN.
 */
data class AudioAnomalyState(
    val level: Float,          // current rms proxy 0..1
    val baseline: Float,
    val zScore: Float,
    val unknown: Boolean,
    val note: String
)

object AudioAnomalyEngine {
    private var n = 0
    private var mean = 0.0
    private var m2 = 0.0
    private const val MIN_N = 40          // ~ samples after arm
    private const val SIGMA = 3.5f

    fun reset() {
        n = 0
        mean = 0.0
        m2 = 0.0
    }

    fun evaluate(
        rms: Float,
        boxOn: Boolean,
        noiseDominant: String? = null
    ): AudioAnomalyState {
        val x = rms.coerceIn(0f, 1f).toDouble()

        // Always update baseline (rolling ambient / box bed)
        n++
        val d = x - mean
        mean += d / n
        m2 += d * (x - mean)

        val sd = if (n >= 2) sqrt(m2 / (n - 1).coerceAtLeast(1)) else 0.0
        val floorSd = max(sd, 0.008) // avoid explode on silence
        val z = if (n < MIN_N) 0f else ((x - mean) / floorSd).toFloat()

        // Unknown = significant spike not explained by motion-heavy handling
        val motionish = noiseDominant == "MOTION"
        val unknown = n >= MIN_N &&
            abs(z) >= SIGMA &&
            !motionish &&
            // if box is on, require stronger spike (box is noisy by design)
            (!boxOn || abs(z) >= SIGMA + 1.2f)

        val note = when {
            n < MIN_N -> "Profiling ambient audio baseline…"
            unknown -> "Unknown audio spike z=${"%.1f".format(z)}"
            boxOn -> "Box on — baseline adapting"
            else -> "Audio baseline stable"
        }

        return AudioAnomalyState(
            level = x.toFloat(),
            baseline = mean.toFloat().coerceIn(0f, 1f),
            zScore = z,
            unknown = unknown,
            note = note
        )
    }
}
