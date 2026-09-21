package com.nscb.spiritscan.engines

import com.nscb.spiritscan.sensor.Sample9
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Dense flow on luminance grid — conservative thresholds so indoor camera
 * noise does not peg independent flow at 90%+ and lock the alert bar.
 */
data class DenseFlowState(
    val flowEnergy: Float,
    val phoneMotion: Float,
    val independentFlow: Float,
    val unknown: Boolean,
    val gridW: Int,
    val gridH: Int,
    val note: String
)

object DenseFlowEngine {
    private const val GW = 24
    private const val GH = 18
    private var prev: FloatArray? = null
    private var unknownHold = 0

    fun reset() {
        prev = null
        unknownHold = 0
    }

    fun evaluate(
        grid: FloatArray?,
        sample: Sample9?,
        noiseDominant: String? = null
    ): DenseFlowState {
        val phone = phoneMotion(sample)
        if (grid == null || grid.size < 16) {
            return DenseFlowState(0f, phone, 0f, false, GW, GH, "No frame grid")
        }

        val g = if (grid.size == GW * GH) grid else resample(grid, GW, GH)
        val p = prev
        prev = g.copyOf()
        if (p == null || p.size != g.size) {
            return DenseFlowState(0f, phone, 0f, false, GW, GH, "Flow priming…")
        }

        // Mean absolute frame difference (more stable than noisy block-match mags)
        var diffSum = 0f
        for (i in g.indices) {
            diffSum += abs(g[i] - p[i])
        }
        val rawDiff = (diffSum / g.size).coerceIn(0f, 1f)
        // Indoor camera noise floor ~0.02–0.06; compress so only strong changes rise
        val flowEnergy = ((rawDiff - 0.04f) / 0.20f).coerceIn(0f, 1f)

        val predicted = phone * 1.1f + 0.08f // baseline visual noise allowance
        val independent = max(0f, flowEnergy - predicted)

        val still = phone < 0.25f && noiseDominant != "MOTION"
        val candidate = still && independent > 0.35f && flowEnergy > 0.30f

        if (candidate) unknownHold = minOf(12, unknownHold + 1)
        else unknownHold = maxOf(0, unknownHold - 2)

        // Require sustained independent flow (~1s at 4Hz) before unknown
        val unknown = unknownHold >= 6

        val note = when {
            noiseDominant == "MOTION" || phone > 0.45f -> "Dense flow gated — phone moving"
            unknown -> "Sustained independent flow"
            independent > 0.2f -> "Mild flow residual"
            else -> "Dense flow calm"
        }

        return DenseFlowState(
            flowEnergy = flowEnergy,
            phoneMotion = phone,
            independentFlow = independent.coerceIn(0f, 1f),
            unknown = unknown,
            gridW = GW,
            gridH = GH,
            note = note
        )
    }

    private fun phoneMotion(sample: Sample9?): Float {
        if (sample == null) return 0f
        val gyro = hypot(hypot(sample.gyroX, sample.gyroY), sample.gyroZ)
        val acc = abs(hypot(hypot(sample.accX, sample.accY), sample.accZ) - 9.81f)
        return ((gyro / 2.5f).coerceIn(0f, 1f) * 0.65f + (acc / 3.5f).coerceIn(0f, 1f) * 0.35f)
            .coerceIn(0f, 1f)
    }

    private fun resample(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        val side = sqrt(src.size.toDouble()).toInt().coerceAtLeast(1)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = (x * side / w).coerceIn(0, side - 1)
                val sy = (y * side / h).coerceIn(0, side - 1)
                val si = sy * side + sx
                out[y * w + x] = if (si < src.size) src[si] else 0f
            }
        }
        return out
    }
}
