package com.nscb.spiritscan.engines

import kotlin.math.abs
import kotlin.math.sqrt

/* ============================
   RESIDUAL ENGINE MODEL (v8.3 update)
   ============================ */
data class ResidualState(
    val level: Float,
    val confidence: Float,
    val delta: Float,
    val note: String
)

/* ============================
   RESIDUAL ENGINE
   Welford baseline + z-style residual level.
   ============================ */
class ResidualEngine {

    private var n = 0
    private var mean = 0f
    private var m2 = 0f
    private var lastMag = 0f
    private var lastUpdate = 0L

    fun reset() {
        n = 0; mean = 0f; m2 = 0f; lastMag = 0f; lastUpdate = 0L
    }

    fun update(magUt: Float, now: Long): ResidualState {
        // baseline accumulation (Welford)
        n++
        val d = magUt - mean
        mean += d / n
        val d2 = magUt - mean
        m2 += d * d2
        val variance = if (n > 1) m2 / (n - 1) else 0f

        // delta from baseline
        val delta = magUt - mean

        // residual level
        val level = (delta / (sqrt(variance) + 0.01f)).coerceIn(-3f, 3f)

        // confidence
        val confidence = (abs(level) / 3f).coerceIn(0f, 1f)

        // note generation
        val note = when {
            confidence > 0.85f -> "High residual activity"
            confidence > 0.55f -> "Moderate residual activity"
            confidence > 0.25f -> "Low residual activity"
            else -> "Residual idle"
        }

        lastMag = magUt
        lastUpdate = now

        return ResidualState(level, confidence, delta, note)
    }
}
