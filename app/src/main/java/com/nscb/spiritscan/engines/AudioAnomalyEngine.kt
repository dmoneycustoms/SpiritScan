package com.nscb.spiritscan.engines

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Unknown audio via rolling baseline + speech-band residual off hop.
 */
data class AudioAnomalyState(
    val level: Float,
    val baseline: Float,
    val zScore: Float,
    val speechResidual: Float,
    val hopHz: Float,
    val speechLike: Boolean,
    val unknown: Boolean,
    val note: String
)

object AudioAnomalyEngine {
    private var n = 0
    private var mean = 0.0
    private var m2 = 0.0
    private const val MIN_N = 40
    private const val SIGMA = 3.5f
    private const val SPEECH_THRESH = 0.22f

    fun reset() {
        n = 0
        mean = 0.0
        m2 = 0.0
    }

    fun evaluate(
        rms: Float,
        boxOn: Boolean,
        noiseDominant: String? = null,
        speechResidual: Float = 0f,
        hopHz: Float = 0f
    ): AudioAnomalyState {
        val x = rms.coerceIn(0f, 1f).toDouble()
        n++
        val d = x - mean
        mean += d / n
        m2 += d * (x - mean)

        val sd = if (n >= 2) sqrt(m2 / (n - 1).coerceAtLeast(1)) else 0.0
        val floorSd = max(sd, 0.008)
        val z = if (n < MIN_N) 0f else ((x - mean) / floorSd).toFloat()

        val motionish = noiseDominant == "MOTION"
        val speechLike = speechResidual >= SPEECH_THRESH && !motionish

        val unknown = n >= MIN_N &&
            !motionish &&
            (
                (abs(z) >= SIGMA && (!boxOn || abs(z) >= SIGMA + 1.2f)) ||
                    (speechLike && speechResidual >= SPEECH_THRESH + 0.08f)
                )

        val note = when {
            n < MIN_N -> "Profiling mic ambient baseline…"
            speechLike && unknown -> "Speech-like residual off hop ${"%.0f".format(hopHz)} Hz"
            unknown -> "Unknown audio spike z=${"%.1f".format(z)}"
            speechLike -> "Speech-band energy elevated"
            boxOn -> "Box on @ ${"%.0f".format(hopHz)} Hz — hop notched"
            else -> "Mic baseline stable"
        }

        return AudioAnomalyState(
            level = x.toFloat(),
            baseline = mean.toFloat().coerceIn(0f, 1f),
            zScore = z,
            speechResidual = speechResidual.coerceIn(0f, 1f),
            hopHz = hopHz,
            speechLike = speechLike,
            unknown = unknown,
            note = note
        )
    }
}
