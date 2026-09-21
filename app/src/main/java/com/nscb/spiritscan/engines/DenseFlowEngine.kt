package com.nscb.spiritscan.engines

import com.nscb.spiritscan.sensor.Sample9
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dense optical-flow proxy on a downsampled luminance grid (Farneback-class idea, pure Kotlin).
 * Independent motion = flow energy not explained by gyro/accel phone motion.
 *
 * Full OpenCV Farneback can replace the grid matcher later; this stays reliable in CI without .so packaging.
 */
data class DenseFlowState(
    val flowEnergy: Float,          // 0..1 mean |flow|
    val phoneMotion: Float,         // 0..1
    val independentFlow: Float,     // 0..1 flow not explained by phone
    val unknown: Boolean,
    val gridW: Int,
    val gridH: Int,
    val note: String
)

object DenseFlowEngine {
    private const val GW = 24
    private const val GH = 18
    private var prev: FloatArray? = null

    fun reset() {
        prev = null
    }

    /**
     * @param grid row-major luminance 0..1 size GW*GH (or any; will resample if needed)
     */
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

        // Block matching optical flow (1-pixel search on grid)
        var energy = 0f
        var count = 0
        val search = 2
        for (y in search until GH - search) {
            for (x in search until GW - search) {
                val i = y * GW + x
                val target = g[i]
                var best = 1e9f
                var bestDx = 0
                var bestDy = 0
                for (dy in -search..search) {
                    for (dx in -search..search) {
                        val j = (y + dy) * GW + (x + dx)
                        val d = abs(p[j] - target)
                        if (d < best) {
                            best = d
                            bestDx = dx
                            bestDy = dy
                        }
                    }
                }
                val mag = sqrt((bestDx * bestDx + bestDy * bestDy).toFloat())
                // weight by match residual so noise doesn't dominate
                energy += mag * (1f - best.coerceIn(0f, 1f))
                count++
            }
        }
        val flowEnergy = if (count == 0) 0f else (energy / count / search).coerceIn(0f, 1f)

        val predicted = phone * 0.9f
        val independent = max(0f, flowEnergy - predicted * 1.2f)
        val still = phone < 0.30f && noiseDominant != "MOTION"
        val unknown = still && independent > 0.12f && flowEnergy > 0.10f

        val note = when {
            noiseDominant == "MOTION" || phone > 0.5f -> "Dense flow gated — phone moving"
            unknown -> "Independent dense flow (not gyro-explained)"
            flowEnergy > 0.15f -> "Flow present, consistent with phone"
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
