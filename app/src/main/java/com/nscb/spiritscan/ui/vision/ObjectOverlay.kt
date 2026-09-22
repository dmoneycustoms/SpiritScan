package com.nscb.spiritscan.ui.vision

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * ML Kit boxes + residual energy plumes.
 * Plumes pulse and drift slightly so they don't look static.
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

    val transition = rememberInfiniteTransition(label = "plume")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "plumePhase"
    )
    val breath by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "plumeBreath"
    )

    val measurer = rememberTextMeasurer()

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val twoPi = (2.0 * PI).toFloat()

        for ((idx, b) in boxes.withIndex()) {
            val left = b.left * w
            val top = b.top * h
            val right = b.right * w
            val bottom = b.bottom * h
            val bw = (right - left).coerceAtLeast(1f)
            val bh = (bottom - top).coerceAtLeast(1f)
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f

            drawRect(
                color = Color(0xFF00E5A0).copy(alpha = 0.85f),
                topLeft = Offset(left, top),
                size = Size(bw, bh),
                style = Stroke(width = 2f)
            )

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

            if (showPlumes && energy > 0.2f) {
                // Per-box phase offset so plumes don't all move in lockstep
                val local = phase + idx * 0.17f
                val ang = local * twoPi
                val driftX = cos(ang) * bw * 0.06f
                val driftY = sin(ang * 1.3f) * bh * 0.05f
                val px = cx + driftX
                val py = cy + driftY

                val baseR = min(bw, bh) * (0.32f + energy * 0.38f)
                val pr = baseR * breath

                // Soft outer halo
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(1f, 0.4f, 0.08f, 0.22f * energy),
                            Color.Transparent
                        ),
                        center = Offset(px, py),
                        radius = pr * 1.35f
                    ),
                    radius = pr * 1.35f,
                    center = Offset(px, py)
                )
                // Core
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(1f, 0.35f, 0.05f, 0.5f * energy * breath),
                            Color(1f, 0.55f, 0.12f, 0.22f * energy),
                            Color.Transparent
                        ),
                        center = Offset(px, py),
                        radius = pr
                    ),
                    radius = pr,
                    center = Offset(px, py)
                )
            }
        }
    }
}
