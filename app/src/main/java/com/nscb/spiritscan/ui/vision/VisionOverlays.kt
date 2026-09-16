package com.nscb.spiritscan.ui.vision

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nscb.spiritscan.entity.EntityOutput
import kotlin.math.abs
import kotlin.math.min

/**
 * False-color vision layers for "things the eyes can't see".
 * These map residual / QIDA / magnetic / thermal signals onto the live camera.
 * Not a real thermal camera — derived from device sensors + luminance residual.
 */

private fun ironbow(t: Float): Color {
    // Classic ironbow: black -> blue -> cyan -> green -> yellow -> red -> white
    val x = t.coerceIn(0f, 1f)
    val r = when {
        x < 0.5f -> 0f
        x < 0.75f -> (x - 0.5f) * 4f
        else -> 1f
    }
    val g = when {
        x < 0.25f -> 0f
        x < 0.5f -> (x - 0.25f) * 4f
        x < 0.75f -> 1f
        else -> 1f - (x - 0.75f) * 2f
    }
    val b = when {
        x < 0.25f -> x * 4f
        x < 0.5f -> 1f
        x < 0.6f -> 1f - (x - 0.5f) * 10f
        else -> 0f
    }
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), 0.45f)
}

private fun uvColor(t: Float): Color {
    // Cool UV-style: deep violet -> blue -> cyan
    val x = t.coerceIn(0f, 1f)
    return Color(
        red = 0.15f + x * 0.35f,
        green = 0.05f + x * 0.4f,
        blue = 0.4f + x * 0.6f,
        alpha = 0.35f + x * 0.25f
    )
}

private fun magColor(t: Float): Color {
    // Magnetic: dark -> cyan/green
    val x = t.coerceIn(0f, 1f)
    return Color(
        red = 0.05f,
        green = 0.4f + x * 0.5f,
        blue = 0.5f + x * 0.4f,
        alpha = 0.3f + x * 0.3f
    )
}

@Composable
fun HeatOverlay(output: EntityOutput) {
    // CMOS-style heat: ironbow from residual + thermal delta + QIDA
    val level = (
        output.residualLevel * 0.45f +
            output.qida * 0.35f +
            abs(output.survey.thermalDelta) / 5f * 0.2f
        ).coerceIn(0f, 1f)

    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2
        val cy = size.height / 2
        val maxR = min(size.width, size.height) * 0.55f

        // Soft full-frame wash
        drawRect(ironbow(level * 0.5f))

        // Hot core
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    ironbow((level + 0.35f).coerceIn(0f, 1f)).copy(alpha = 0.7f),
                    ironbow(level).copy(alpha = 0.35f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = maxR
            ),
            radius = maxR,
            center = Offset(cx, cy)
        )

        // Hotspot rings from residual confidence
        val conf = output.residualConfidence.coerceIn(0f, 1f)
        if (conf > 0.15f) {
            drawCircle(
                color = ironbow(0.9f).copy(alpha = 0.5f),
                radius = 30f + conf * 50f,
                center = Offset(cx, cy),
                style = Stroke(width = 2f)
            )
        }
    }
}

@Composable
fun UvOverlay(output: EntityOutput) {
    // UV-style: cool mapping of residual + SDE instability
    val level = (
        output.residualLevel * 0.4f +
            (1f - if (output.sdeOk) 1f else output.sdeComposite) * 0.3f +
            output.qida * 0.3f
        ).coerceIn(0f, 1f)

    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2
        val cy = size.height / 2
        val maxR = min(size.width, size.height) * 0.6f

        drawRect(uvColor(level * 0.4f))

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    uvColor((level + 0.4f).coerceIn(0f, 1f)),
                    uvColor(level).copy(alpha = 0.25f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = maxR
            ),
            radius = maxR,
            center = Offset(cx, cy)
        )

        // Edge vignette (UV “leak”)
        drawCircle(
            color = Color(0.4f, 0.1f, 0.8f, 0.2f),
            radius = maxR * 1.1f,
            center = Offset(cx, cy),
            style = Stroke(width = 40f)
        )
    }
}

@Composable
fun MagOverlay(output: EntityOutput) {
    // Magnetic field visualization from |B| and z-score
    val level = (
        (abs(output.zMag) / 6f).coerceIn(0f, 1f) * 0.6f +
            (output.magUt / 80f).coerceIn(0f, 1f) * 0.4f
        ).coerceIn(0f, 1f)

    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2
        val cy = size.height / 2
        val maxR = min(size.width, size.height) * 0.5f

        drawRect(magColor(level * 0.35f))

        // Concentric field lines
        for (i in 1..4) {
            val r = maxR * (i / 4f)
            drawCircle(
                color = magColor(level).copy(alpha = 0.15f + i * 0.05f),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5f)
            )
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    magColor((level + 0.3f).coerceIn(0f, 1f)),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = maxR * 0.7f
            ),
            radius = maxR * 0.7f,
            center = Offset(cx, cy)
        )
    }
}

@Composable
fun NightOverlay(output: EntityOutput) {
    // Low-light / gain style — green phosphor look from lux + residual
    val lux = output.survey.lux ?: 50f
    val gain = (1f - (lux / 200f).coerceIn(0f, 1f)) * 0.5f + output.residualLevel * 0.3f

    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(0f, 0.08f + gain * 0.12f, 0.02f, 0.4f))
        val cx = size.width / 2
        val cy = size.height / 2
        drawCircle(
            color = Color(0.1f, 0.9f, 0.2f, 0.15f + gain * 0.2f),
            radius = min(size.width, size.height) * 0.35f,
            center = Offset(cx, cy)
        )
    }
}
