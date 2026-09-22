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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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

private data class TrailDot(
    val x: Float,
    val y: Float,
    val strength: Float,
    val bornMs: Long
)

/**
 * OBJ overlay:
 *  - green boxes + labels
 *  - ranked energy plumes (#1 #2 #3)
 *  - face-style ellipse on top ranks
 *  - fading energy trails (~2.5s)
 *  - soft EMF-style grid heat
 *  - audio spike ring when unknown audio
 */
@Composable
fun ObjectOverlay(
    boxes: List<DetectedObjectBox>,
    output: EntityOutput,
    showPlumes: Boolean = true,
    audioSpike: Boolean = false,
    gridHeat: Boolean = true
) {
    val baseEnergy = (
        output.qida * 0.25f +
            output.residualLevel * 0.3f +
            (abs(output.zMag) / 10f).coerceIn(0f, 1f) * 0.2f +
            output.jonesScore * 0.25f
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
    val ringPulse by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "audioRing"
    )

    val trails: SnapshotStateList<TrailDot> = remember { mutableStateListOf() }
    val measurer = rememberTextMeasurer()
    val now = System.currentTimeMillis()

    // Rank boxes by size * confidence proxy * shared energy (larger central objects rank higher when energy up)
    val ranked = boxes.mapIndexed { i, b ->
        val area = ((b.right - b.left) * (b.bottom - b.top)).coerceAtLeast(0.001f)
        val score = baseEnergy * (0.55f + 0.45f * b.confidence.coerceIn(0f, 1f)) * (0.7f + area)
        Triple(i, b, score)
    }.sortedByDescending { it.third }

    val rankOf = IntArray(boxes.size) { -1 }
    ranked.take(3).forEachIndexed { rank, (i, _, _) -> rankOf[i] = rank + 1 }

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val twoPi = (2.0 * PI).toFloat()

        // --- Grid heat (EMF-style) ---
        if (gridHeat && baseEnergy > 0.12f) {
            val gx = 8
            val gy = 6
            val cellW = w / gx
            val cellH = h / gy
            for (iy in 0 until gy) {
                for (ix in 0 until gx) {
                    // soft spatial variation from phase + energy
                    val n =
                        (sin((ix + phase * 3f) * 0.9f) * cos((iy + phase * 2f) * 0.8f) + 1f) * 0.5f
                    val a = (baseEnergy * 0.18f * n).coerceIn(0f, 0.22f)
                    if (a > 0.02f) {
                        drawRect(
                            color = Color(1f, 0.25f, 0.05f, a),
                            topLeft = Offset(ix * cellW, iy * cellH),
                            size = Size(cellW, cellH)
                        )
                    }
                }
            }
        }

        // --- Trails (prune + draw) ---
        trails.removeAll { now - it.bornMs > 2500L }
        for (t in trails) {
            val age = (now - t.bornMs) / 2500f
            val a = ((1f - age) * 0.35f * t.strength).coerceIn(0f, 0.35f)
            val r = 18f + t.strength * 40f
            drawCircle(
                color = Color(1f, 0.4f, 0.1f, a),
                radius = r * (1f - age * 0.4f),
                center = Offset(t.x * w, t.y * h)
            )
        }

        for ((idx, b) in boxes.withIndex()) {
            val left = b.left * w
            val top = b.top * h
            val right = b.right * w
            val bottom = b.bottom * h
            val bw = (right - left).coerceAtLeast(1f)
            val bh = (bottom - top).coerceAtLeast(1f)
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            val rank = if (idx < rankOf.size) rankOf[idx] else -1
            val localEnergy = baseEnergy * (if (rank in 1..3) 1.15f - rank * 0.08f else 0.75f)

            // Box
            drawRect(
                color = Color(0xFF00E5A0).copy(alpha = 0.85f),
                topLeft = Offset(left, top),
                size = Size(bw, bh),
                style = Stroke(width = if (rank == 1) 3f else 2f)
            )

            // Face-style ellipse on top ranks
            if (rank in 1..3 && localEnergy > 0.2f) {
                val ew = bw * 0.72f
                val eh = bh * 0.88f
                drawOval(
                    color = Color(1f, 0.55f, 0.2f, 0.55f * localEnergy),
                    topLeft = Offset(cx - ew / 2f, cy - eh / 2f),
                    size = Size(ew, eh),
                    style = Stroke(width = 2.5f)
                )
                // soft inner wash
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(1f, 0.3f, 0.05f, 0.2f * localEnergy),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = min(ew, eh) * 0.55f
                    ),
                    topLeft = Offset(cx - ew / 2f, cy - eh / 2f),
                    size = Size(ew, eh)
                )
            }

            // Label + rank badge
            val rankTag = if (rank in 1..3) " #$rank" else ""
            val label = "${b.label} ${"%.0f".format(b.confidence * 100)}%$rankTag"
            val layout = measurer.measure(
                label,
                style = TextStyle(color = Color.White, fontSize = 10.sp)
            )
            val badgeColor = when (rank) {
                1 -> Color(1f, 0.35f, 0.05f, 0.85f)
                2 -> Color(1f, 0.55f, 0.1f, 0.75f)
                3 -> Color(1f, 0.7f, 0.2f, 0.65f)
                else -> Color.Black.copy(alpha = 0.55f)
            }
            drawRect(
                color = badgeColor,
                topLeft = Offset(left, (top - layout.size.height - 4f).coerceAtLeast(0f)),
                size = Size(layout.size.width + 8f, layout.size.height + 4f)
            )
            drawText(
                layout,
                topLeft = Offset(left + 4f, (top - layout.size.height - 2f).coerceAtLeast(0f))
            )

            // Plumes
            if (showPlumes && localEnergy > 0.2f) {
                val local = phase + idx * 0.17f
                val ang = local * twoPi
                val driftX = cos(ang) * bw * 0.06f
                val driftY = sin(ang * 1.3f) * bh * 0.05f
                val px = cx + driftX
                val py = cy + driftY
                val baseR = min(bw, bh) * (0.32f + localEnergy * 0.38f)
                val pr = baseR * breath

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(1f, 0.4f, 0.08f, 0.22f * localEnergy),
                            Color.Transparent
                        ),
                        center = Offset(px, py),
                        radius = pr * 1.35f
                    ),
                    radius = pr * 1.35f,
                    center = Offset(px, py)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(1f, 0.35f, 0.05f, 0.5f * localEnergy * breath),
                            Color(1f, 0.55f, 0.12f, 0.22f * localEnergy),
                            Color.Transparent
                        ),
                        center = Offset(px, py),
                        radius = pr
                    ),
                    radius = pr,
                    center = Offset(px, py)
                )

                // Sample trail point ~4 Hz worth — throttle by phase buckets
                if (rank in 1..3 && (phase * 20).toInt() % 5 == 0) {
                    val nx = (px / w).coerceIn(0f, 1f)
                    val ny = (py / h).coerceIn(0f, 1f)
                    if (trails.size < 48) {
                        trails.add(TrailDot(nx, ny, localEnergy, now))
                    } else {
                        trails.removeAt(0)
                        trails.add(TrailDot(nx, ny, localEnergy, now))
                    }
                }
            }
        }

        // --- Audio spike ring ---
        if (audioSpike) {
            val cx = w * 0.5f
            val cy = h * 0.5f
            val r = min(w, h) * (0.28f + 0.08f * ringPulse)
            drawCircle(
                color = Color(0.2f, 0.9f, 1f, 0.55f * ringPulse),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 4f)
            )
            drawCircle(
                color = Color(0.4f, 1f, 0.9f, 0.25f * ringPulse),
                radius = r * 0.72f,
                center = Offset(cx, cy),
                style = Stroke(width = 2f)
            )
        }
    }
}
