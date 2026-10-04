package com.nscb.spiritscan.engines

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Camera anomaly from luminance grid (NSCB CameraAnomalyEngine metrics, grid-based).
 * MAD frame-to-frame, flicker, variance → anomaly_score.
 */
data class CameraAnomalyState(
    val lumMean: Float,
    val lumVar: Float,
    val frameMad: Float,
    val flicker: Float,
    val anomalyScore: Float,
    val unknown: Boolean,
    val flags: List<String>,
    val note: String
)

object CameraAnomalyEngine {
    private const val HIST = 48
    private val histMeans = ArrayList<Float>(HIST)
    private var prevGrid: FloatArray? = null
    private var prevMean = -1f

    fun reset() {
        histMeans.clear()
        prevGrid = null
        prevMean = -1f
    }

    fun evaluate(grid: FloatArray?): CameraAnomalyState {
        if (grid == null || grid.isEmpty()) {
            return CameraAnomalyState(0f, 0f, 0f, 0f, 0f, false, emptyList(), "No frame grid")
        }

        var sum = 0.0
        for (v in grid) sum += v
        val mean = (sum / grid.size).toFloat()
        var varSum = 0.0
        for (v in grid) {
            val d = v - mean
            varSum += d * d
        }
        val lumVar = (varSum / grid.size).toFloat()

        // Frame-to-frame MAD
        var mad = 0f
        val prev = prevGrid
        if (prev != null && prev.size == grid.size) {
            var s = 0.0
            for (i in grid.indices) s += abs(grid[i] - prev[i])
            mad = (s / grid.size).toFloat()
        }
        prevGrid = grid.copyOf()

        histMeans.add(mean)
        while (histMeans.size > HIST) histMeans.removeAt(0)

        // Flicker: short-term mean oscillation
        var flicker = 0f
        if (histMeans.size >= 8) {
            val recent = histMeans.takeLast(8)
            val m = recent.average()
            flicker = sqrt(recent.map { (it - m) * (it - m) }.average()).toFloat()
        }

        if (prevMean < 0f) prevMean = mean
        val meanJump = abs(mean - prevMean)
        prevMean = prevMean * 0.9f + mean * 0.1f

        // Anomaly score blend
        val madN = (mad / 0.12f).coerceIn(0f, 1f)
        val flickN = (flicker / 0.08f).coerceIn(0f, 1f)
        val jumpN = (meanJump / 0.15f).coerceIn(0f, 1f)
        val varN = (lumVar / 0.08f).coerceIn(0f, 1f)
        val score = (madN * 0.4f + flickN * 0.25f + jumpN * 0.2f + varN * 0.15f).coerceIn(0f, 1f)

        val flags = ArrayList<String>()
        if (madN > 0.55f) flags.add("MOTION")
        if (flickN > 0.55f) flags.add("FLICKER")
        if (jumpN > 0.6f) flags.add("SCENE")
        if (varN > 0.7f) flags.add("TEXTURE")

        val unknown = score >= 0.55f && flags.isNotEmpty()
        val note = when {
            unknown -> "Camera anomaly ${flags.joinToString("+")} score=${"%.2f".format(score)}"
            score > 0.3f -> "Elevated scene dynamics"
            else -> "Scene stable"
        }

        return CameraAnomalyState(
            lumMean = mean,
            lumVar = lumVar,
            frameMad = mad,
            flicker = flicker,
            anomalyScore = score,
            unknown = unknown,
            flags = flags,
            note = note
        )
    }
}
