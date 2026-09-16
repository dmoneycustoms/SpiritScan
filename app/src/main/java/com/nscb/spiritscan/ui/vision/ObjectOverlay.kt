package com.nscb.spiritscan.ui.vision

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.nscb.spiritscan.entity.EntityOutput
import com.nscb.spiritscan.vision.DetectedObjectBox
import kotlin.math.abs
import kotlin.math.min

/**
 * Draws ML Kit object boxes and optional residual plumes on those objects
 * when model energy is elevated.
 */
@Composable
fun ObjectOverlay(
    boxes: List<DetectedObjectBox>,
    output: EntityOutput,
    showPlumes: Boolean = true
) {
    val energy = (
        output.qida * 0.3f +
            output.residualLevel * 0.3f +
            (abs(output.zMag) / 10f).coerceIn(0f, 1f) * 0.2f +
            output.jonesScore * 0.2f
        ).coerceIn(0f, 1f)

    val measurer = rememberTextMeasurer()

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        for (b in boxes) {
            val left = b.left * w
            val top = b.top * h
            val right = b.right * w
            val bottom = b.bottom * h
            val bw = (right - left).coerceAtLeast(1f)
            val bh = (bottom - top).coerceAtLeast(1f)
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f

            // Bounding box
            drawRect(
                color = Color(0xFF00E5A0).copy(alpha = 0.85f),
                topLeft = Offset(left, top),
                size = Size(bw, bh),
                style = Stroke(width = 2f)
            )

            // Label
            val label = "${b.label} ${"%.0f".format(b.confidence * 100)}%"
            val layout = measurer.measure(
                label,
                style = TextStyle(color = Color.White, fontSize = 10.sp)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(left, (top - layout.size.height - 4f).coerceAtLeast(0f)),
                size = Size(layout.size.width + 8f, layout.size.height + 4f)
            )
            drawText(
                layout,
                topLeft = Offset(left + 4f, (top - layout.size.height - 2f).coerceAtLeast(0f))
            )

            // Residual plume on object when models are elevated
            if (showPlumes && energy > 0.2f) {
                val pr = min(bw, bh) * (0.35f + energy * 0.4f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(1f, 0.35f, 0.05f, 0.45f * energy),
                            Color(1f, 0.6f, 0.1f, 0.2f * energy),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = pr
                    ),
                    radius = pr,
                    center = Offset(cx, cy)
                )
            }
        }
    }
}
