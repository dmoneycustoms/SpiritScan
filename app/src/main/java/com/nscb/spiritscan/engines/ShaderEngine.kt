package com.nscb.spiritscan.engines

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.sin

/* ============================
   SHADER ENGINE (v8.3 update)
   distortion / pulse / spectral / interference / ripple / entityGlow
   ============================ */
class ShaderEngine {

    fun distortion(level: Float): Color {
        val l = level.coerceIn(0f, 1f)
        return Color(
            red = l,
            green = (1f - l),
            blue = 0f,
            alpha = l * 0.4f
        )
    }

    fun pulse(qida: Float, t: Float): Float {
        val base = qida.coerceIn(0f, 1f)
        return base * (0.5f + 0.5f * sin(t * 4f))
    }

    fun spectral(sde: Float): Color {
        val x = sde.coerceIn(0f, 1f)
        return Color(
            red = x,
            green = x * 0.2f,
            blue = 1f - x,
            alpha = 0.35f
        )
    }

    fun interference(active: Boolean, t: Float): Float {
        if (!active) return 0f
        return 0.3f + 0.7f * abs(sin(t * 12f))
    }

    fun ripple(mag: Float, t: Float): Float {
        val m = (mag / 60f).coerceIn(0f, 1f)
        return m * (0.4f + 0.6f * sin(t * 2f))
    }

    fun entityGlow(confidence: Float): Color {
        val c = confidence.coerceIn(0f, 1f)
        return Color(
            red = c,
            green = 1f,
            blue = c,
            alpha = c * 0.5f
        )
    }
}
