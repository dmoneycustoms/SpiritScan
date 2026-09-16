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
 * Filter overlays driven by ONNX/kernel model outputs.
 * Intensity scales with unusual readings so visuals match the data.
 */

private fun ironbow(t: Float, alpha: Float = 0.5f): Color {
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
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), alpha)
}

/** HEAT — QIDA residual + residualLevel + thermal (Jones residual path) */
@Composable
fun HeatOverlay(output: EntityOutput) {
    val unusual = (
        output.qida * 0.4f +
            output.residualLevel * 0.35f +
            abs(output.survey.thermalDelta) / 4f * 0.15f +
            output.jonesScore * 0.1f
        ).coerceIn(0f, 1f)

    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = min(size.width, size.height) * 0.48f

        // Align grid-like 8x divisions with camera box
        val stepX = size.width / 8f
        val stepY = size.height / 8f
        for (i in 1 until 8) {
            drawLine(Color(0x33FFFFFF), Offset(i * stepX, 0f), Offset(i * stepX, size.height), 1f)
            drawLine(Color(0x33FFFFFF), Offset(0f, i * stepY), Offset(size.width, i * stepY), 1f)
        }

        drawRect(ironbow(unusual * 0.45f, 0.35f))
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    ironbow((unusual + 0.3f).coerceIn(0f, 1f), 0.65f),
                    ironbow(unusual, 0.3f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = maxR
            ),
            radius = maxR,
            center = Offset(cx, cy)
        )
        if (unusual > 0.35f) {
            drawCircle(
                ironbow(0.95f, 0.55f),
                radius = 18f + unusual * 40f,
                center = Offset(cx, cy),
                style = Stroke(2.5f)
            )
        }
    }
}

/** UV — SDE instability + residual (system integrity path) */
@Composable
fun UvOverlay(output: EntityOutput) {
    val unusual = (
        (if (!output.sdeOk) 0.45f else 0f) +
            (1f - output.sdeComposite.coerceIn(0f, 1f)) * 0.25f +
            output.residualLevel * 0.3f
        ).coerceIn(0f, 1f)

    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = min(size.width, size.height) * 0.5f
        val stepX = size.width / 8f
        val stepY = size.height / 8f
        for (i in 1 until 8) {
            drawLine(Color(0x44AA88FF), Offset(i * stepX, 0f), Offset(i * stepX, size.height), 1f)
            drawLine(Color(0x44AA88FF), Offset(0f, i * stepY), Offset(size.width, i * stepY), 1f)
        }
        val c = Color(0.2f + unusual * 0.3f, 0.1f + unusual * 0.2f, 0.55f + unusual * 0.4f, 0.35f + unusual * 0.25f)
        drawRect(c.copy(alpha = 0.3f))
        drawCircle(
            brush = Brush.radialGradient(
                listOf(c, c.copy(alpha = 0.2f), Color.Transparent),
                center = Offset(cx, cy),
                radius = maxR
            ),
            radius = maxR,
            center = Offset(cx, cy)
        )
    }
}

/** MAG — magnetometer |B| + z-score (magnetic kernel path) */
@Composable
fun MagOverlay(output: EntityOutput) {
    val unusual = (
        (abs(output.zMag) / 5f).coerceIn(0f, 1f) * 0.55f +
            (output.magUt / 70f).coerceIn(0f, 1f) * 0.45f
        ).coerceIn(0f, 1f)

    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = min(size.width, size.height) * 0.45f
        val stepX = size.width / 8f
        val stepY = size.height / 8f
        for (i in 1 until 8) {
            drawLine(Color(0x3300E5FF), Offset(i * stepX, 0f), Offset(i * stepX, size.height), 1f)
            drawLine(Color(0x3300E5FF), Offset(0f, i * stepY), Offset(size.width, i * stepY), 1f)
        }
        drawRect(Color(0.05f, 0.25f + unusual * 0.3f, 0.35f, 0.28f))
        for (i in 1..5) {
            drawCircle(
                Color(0f, 0.7f, 0.85f, 0.12f + unusual * 0.1f),
                radius = maxR * (i / 5f),
                center = Offset(cx, cy),
                style = Stroke(1.5f)
            )
        }
        if (unusual > 0.3f) {
            drawCircle(
                Color(0f, 1f, 0.9f, 0.45f),
                radius = 12f + unusual * 35f,
                center = Offset(cx, cy)
            )
        }
    }
}

/** NIGHT — lux + residual (low-signal path) */
@Composable
fun NightOverlay(output: EntityOutput) {
    val lux = output.survey.lux ?: 80f
    val unusual = (
        (1f - (lux / 180f).coerceIn(0f, 1f)) * 0.5f +
            output.residualLevel * 0.5f
        ).coerceIn(0f, 1f)

    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stepX = size.width / 8f
        val stepY = size.height / 8f
        for (i in 1 until 8) {
            drawLine(Color(0x2200FF44), Offset(i * stepX, 0f), Offset(i * stepX, size.height), 1f)
            drawLine(Color(0x2200FF44), Offset(0f, i * stepY), Offset(size.width, i * stepY), 1f)
        }
        drawRect(Color(0f, 0.06f + unusual * 0.1f, 0.02f, 0.42f))
        drawCircle(
            Color(0.1f, 0.85f, 0.25f, 0.2f + unusual * 0.25f),
            radius = min(size.width, size.height) * 0.3f,
            center = Offset(cx, cy)
        )
    }
}

/** JONES — classifier score highlight */
@Composable
fun JonesOverlay(output: EntityOutput) {
    val unusual = output.jonesScore.coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stepX = size.width / 8f
        val stepY = size.height / 8f
        for (i in 1 until 8) {
            drawLine(Color(0x3300FFAA), Offset(i * stepX, 0f), Offset(i * stepX, size.height), 1f)
            drawLine(Color(0x3300FFAA), Offset(0f, i * stepY), Offset(size.width, i * stepY), 1f)
        }
        drawRect(Color(0f, 0.3f, 0.2f, 0.2f + unusual * 0.2f))
        drawCircle(
            Color(0f, 1f, 0.65f, 0.25f + unusual * 0.4f),
            radius = 20f + unusual * 55f,
            center = Offset(cx, cy),
            style = Stroke(2f + unusual * 2f)
        )
    }
}

/** OMEGA — trust / stability path */
@Composable
fun OmegaOverlay(output: EntityOutput) {
    val unusual = (1f - output.omegaTrust.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stepX = size.width / 8f
        val stepY = size.height / 8f
        for (i in 1 until 8) {
            drawLine(Color(0x3300FF00), Offset(i * stepX, 0f), Offset(i * stepX, size.height), 1f)
            drawLine(Color(0x3300FF00), Offset(0f, i * stepY), Offset(size.width, i * stepY), 1f)
        }
        drawRect(Color(0.05f, 0.25f, 0.05f, 0.25f + unusual * 0.2f))
        drawCircle(
            Color(0.2f, 1f, 0.2f, 0.3f + unusual * 0.35f),
            radius = 25f + unusual * 45f,
            center = Offset(cx, cy),
            style = Stroke(2f)
        )
    }
}
