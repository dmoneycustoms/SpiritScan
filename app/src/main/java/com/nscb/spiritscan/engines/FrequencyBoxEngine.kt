package com.nscb.spiritscan.engines

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * SpiritScan 72 Hz integrity engine (ported/simplified from NSCB FrequencyBox).
 * Tracks LOCK72 / hop cadence: PLL-style phase residual, jitter, Allan-like stability.
 */
data class FrequencyBoxState(
    val baseHz: Float,
    val pllLock: Float,
    val pllErrorMs: Float,
    val jitter: Float,
    val allanDev: Float,
    val stability: Float,
    val anomaly: Boolean,
    val level: Int,
    val note: String
)

object FrequencyBoxEngine {
    const val BASE_HZ = 72f
    private val GRID_MS = 1000.0 / BASE_HZ

    private val intervals = ArrayList<Long>(96)
    private var lastTs = 0L
    private var pllPhaseMs = 0.0
    private var pllLock = 0.5f

    fun reset() {
        intervals.clear()
        lastTs = 0L
        pllPhaseMs = 0.0
        pllLock = 0.5f
    }

    /** Call each time the box advances a hop / pulse (~each buffer). */
    fun pulse(nowMs: Long = System.currentTimeMillis(), hopHz: Float = BASE_HZ): FrequencyBoxState {
        if (lastTs > 0L) {
            val dt = (nowMs - lastTs).coerceIn(1L, 500L)
            intervals.add(dt)
            while (intervals.size > 96) intervals.removeAt(0)
            val residual = phaseResidualMs(dt.toDouble())
            pllPhaseMs = 0.88 * pllPhaseMs + 0.12 * residual
        }
        lastTs = nowMs

        if (intervals.size < 8) {
            return FrequencyBoxState(
                baseHz = hopHz,
                pllLock = pllLock,
                pllErrorMs = pllPhaseMs.toFloat(),
                jitter = 0f,
                allanDev = 0f,
                stability = 0.5f,
                anomaly = false,
                level = 1,
                note = "Profiling 72 Hz grid…"
            )
        }

        val mean = intervals.average()
        val exp = if (hopHz > 1f) 1000.0 / hopHz else GRID_MS
        // For LOCK72 the *tone* is 72 Hz; pulse interval is audio buffer rate (~20–50 ms)
        val jitter = sqrt(intervals.map { (it - mean) * (it - mean) }.average()).toFloat() / mean.toFloat().coerceAtLeast(1f)
        val drift = abs(mean - exp).toFloat() / exp.toFloat().coerceAtLeast(1f)
        val allan = allanLike(intervals)
        val pllErr = abs(pllPhaseMs).toFloat()
        pllLock = (1f - (pllErr / (GRID_MS.toFloat() / 2f)).coerceIn(0f, 1f)).coerceIn(0f, 1f)

        val stability = (
            0.35f * (1f - jitter.coerceIn(0f, 1f)) +
                0.25f * (1f - drift.coerceIn(0f, 1f)) +
                0.20f * (1f - allan.coerceIn(0f, 1f)) +
                0.20f * pllLock
            ).coerceIn(0f, 1f)

        // Anomaly: unstable cadence while claiming a locked tone
        val level = when {
            jitter > 0.45f && allan > 0.5f -> 4
            jitter > 0.30f || allan > 0.35f || pllLock < 0.25f -> 3
            stability > 0.85f && pllLock > 0.7f -> 1
            else -> 2
        }
        val anomaly = level >= 3 && intervals.size >= 16

        val note = when {
            anomaly -> "72 Hz grid unstable · jitter=${"%.2f".format(jitter)} pll=${"%.2f".format(pllLock)}"
            level == 1 -> "72 Hz grid locked"
            else -> "72 Hz grid settling"
        }

        return FrequencyBoxState(
            baseHz = hopHz,
            pllLock = pllLock,
            pllErrorMs = pllErr,
            jitter = jitter.coerceIn(0f, 2f),
            allanDev = allan.coerceIn(0f, 2f),
            stability = stability,
            anomaly = anomaly,
            level = level,
            note = note
        )
    }

    private fun phaseResidualMs(intervalMs: Double): Double {
        val k = kotlin.math.round(intervalMs / GRID_MS)
        return intervalMs - k * GRID_MS
    }

    private fun allanLike(xs: List<Long>): Float {
        if (xs.size < 4) return 0f
        var s = 0.0
        var n = 0
        for (i in 0 until xs.size - 1) {
            val d = (xs[i + 1] - xs[i]).toDouble()
            s += d * d
            n++
        }
        if (n == 0) return 0f
        val mean = xs.average().coerceAtLeast(1.0)
        return (sqrt(s / n) / mean).toFloat().coerceIn(0f, 2f)
    }
}
