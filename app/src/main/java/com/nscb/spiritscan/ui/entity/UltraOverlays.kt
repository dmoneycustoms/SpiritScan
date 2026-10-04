package com.nscb.spiritscan.ui.entity

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nscb.spiritscan.engines.FusionState
import com.nscb.spiritscan.entity.EntityOutput
import kotlin.math.abs
import kotlin.math.sin

/* ============================
   ULTRA LAYER COLOR MODEL
   ============================ */
object UltraColors {
    val Jones = Color(0xFF00FFAA)
    val Magnetic = Color(0xFF00C8FF)
    val Qida = Color(0xFFFF0044)
    val Omega = Color(0xFF00FF00)
    val Sde = Color(0xFFFF00FF)
    val Residual = Color(0xFF888888)
    val Interference = Color(0xFFFF2222)
}

/* ============================
   MODE -> COLOR MAP
   ============================ */
fun ultraColorForMode(mode: String): Color {
    return when (mode) {
        "JONES", "Jones" -> UltraColors.Jones
        "MAGNETIC", "Magnetic" -> UltraColors.Magnetic
        "QIDA" -> UltraColors.Qida
        "OMEGA", "Omega" -> UltraColors.Omega
        "SDE" -> UltraColors.Sde
        "RESIDUAL", "Residual" -> UltraColors.Residual
        "INTERFERENCE", "Interference" -> UltraColors.Interference
        "candidate_entity" -> UltraColors.Qida
        "device_interference" -> UltraColors.Interference
        "environmental_shift" -> UltraColors.Magnetic
        else -> Color.Cyan
    }
}

/* ============================
   ULTRA GRID OVERLAY
   ============================ */
@Composable
fun UltraGridOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val step = 40f
        val w = size.width
        val h = size.height

        for (x in 0..(w / step).toInt()) {
            drawLine(
                color = Color(0x22FFFFFF),
                start = Offset(x * step, 0f),
                end = Offset(x * step, h),
                strokeWidth = 1f
            )
        }

        for (y in 0..(h / step).toInt()) {
            drawLine(
                color = Color(0x22FFFFFF),
                start = Offset(0f, y * step),
                end = Offset(w, y * step),
                strokeWidth = 1f
            )
        }
    }
}

/* ============================
   ULTRA CORNER MARKERS
   ============================ */
@Composable
fun UltraCorners(color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val s = 28f

        // TL
        drawLine(color, Offset(0f, 0f), Offset(s, 0f), 3f)
        drawLine(color, Offset(0f, 0f), Offset(0f, s), 3f)

        // TR
        drawLine(color, Offset(size.width, 0f), Offset(size.width - s, 0f), 3f)
        drawLine(color, Offset(size.width, 0f), Offset(size.width, s), 3f)

        // BL
        drawLine(color, Offset(0f, size.height), Offset(s, size.height), 3f)
        drawLine(color, Offset(0f, size.height), Offset(0f, size.height - s), 3f)

        // BR
        drawLine(color, Offset(size.width, size.height), Offset(size.width - s, size.height), 3f)
        drawLine(color, Offset(size.width, size.height), Offset(size.width, size.height - s), 3f)
    }
}

/* ============================
   ULTRA ENTITY RING
   Composite halo + confidence ring + pulse ring + interference flash.
   ============================ */
@Composable
fun UltraEntityRing(output: EntityOutput, fusion: FusionState, modeColor: Color) {

    val t = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            t.animateTo(
                t.value + 0.016f,
                animationSpec = tween(16, easing = LinearEasing)
            )
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {

        val cx = size.width / 2
        val cy = size.height / 2

        // composite halo
        drawCircle(
            color = modeColor.copy(alpha = fusion.composite * 0.4f),
            radius = 180f + (fusion.composite * 120f),
            center = Offset(cx, cy)
        )

        // entity glow ring
        drawCircle(
            color = modeColor.copy(alpha = fusion.confidence * 0.7f),
            radius = 80f + (fusion.confidence * 60f),
            style = Stroke(width = 6f),
            center = Offset(cx, cy)
        )

        // pulse ring
        val pulse = 0.5f + 0.5f * sin(t.value * 4f)
        drawCircle(
            color = modeColor.copy(alpha = pulse * 0.3f),
            radius = 120f + (pulse * 40f),
            style = Stroke(width = 3f),
            center = Offset(cx, cy)
        )

        // interference flash
        if (!output.sdeOk) {
            val flash = abs(sin(t.value * 12f))
            drawCircle(
                color = Color.Red.copy(alpha = flash * 0.5f),
                radius = 200f,
                center = Offset(cx, cy)
            )
        }
    }
}
