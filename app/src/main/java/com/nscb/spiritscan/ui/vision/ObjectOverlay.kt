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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private data class TrailDot(
    val x: Float,
    val y: Float,
    val strength: Float,
    val bornMs: Long
)

private data class TrackMem(
    var cx: Float,
    var cy: Float,
    var hits: Int,
    var lastMs: Long,
    var motion: Float,
    var dwell: Float
)

private data class ScoredBox(
    val index: Int,
    val box: DetectedObjectBox,
    val score: Float,
    val motion: Float,
    val dwell: Float
)

/** Mean |cell - globalMean| for grid cells under the box. */
private fun boxLumResidual(grid: FloatArray?, b: DetectedObjectBox): Float {
    if (grid == null || grid.isEmpty()) return 0f
    val side = sqrt(grid.size.toDouble()).toInt().coerceAtLeast(1)
    if (side * side > grid.size) return 0f

    var mean = 0.0
    for (v in grid) {
        mean += v.toDouble()
    }
    mean /= grid.size.toDouble()

    val x0 = (b.left * side).toInt().coerceIn(0, side - 1)
    val x1 = (b.right * side).toInt().coerceIn(0, side - 1)
    val y0 = (b.top * side).toInt().coerceIn(0, side - 1)
    val y1 = (b.bottom * side).toInt().coerceIn(0, side - 1)
    if (x1 < x0 || y1 < y0) return 0f

    var sum = 0.0
    var n = 0
    for (y in y0..y1) {
        for (x in x0..x1) {
            val i = y * side + x
            if (i in grid.indices) {
                sum += abs(grid[i] - mean)
                n++
            }
        }
    }
    if (n == 0) return 0f
    return ((sum / n) * 4.0).toFloat().coerceIn(0f, 1f)
}

@Composable
fun ObjectOverlay(
    boxes: List<DetectedObjectBox>,
    output: EntityOutput,
    showPlumes: Boolean = true,
    audioSpike: Boolean = false,
    gridHeat: Boolean = true,
    lumGrid: FloatArray? = null
) {
    val global: Float = (
        output.qida * 0.2f +
            output.residualLevel * 0.35f +
            (abs(output.zMag) / 12f).coerceIn(0f, 1f) * 0.2f +
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
    val tracks: SnapshotStateMap<Int, TrackMem> = remember { mutableStateMapOf() }
    val measurer = rememberTextMeasurer()
    val now = System.currentTimeMillis()

    val scored = ArrayList<ScoredBox>(boxes.size)
    val seen = HashSet<Int>()

    for ((index, b) in boxes.withIndex()) {
        val tid = if (b.trackingId >= 0) b.trackingId else (10000 + index)
        seen.add(tid)
        val cx = (b.left + b.right) * 0.5f
        val cy = (b.top + b.bottom) * 0.5f
        val area = ((b.right - b.left) * (b.bottom - b.top)).coerceAtLeast(0.001f)

        val existing = tracks[tid]
        val motion: Float
        val dwell: Float
        if (existing == null) {
            tracks[tid] = TrackMem(cx, cy, 1, now, 0f, 0.15f)
            motion = 0f
            dwell = 0.15f
        } else {
            val dist = hypot(cx - existing.cx, cy - existing.cy)
            motion = (dist * 8f).coerceIn(0f, 1f) * 0.65f + existing.motion * 0.35f
            val stillBoost = if (dist < 0.02f) 0.04f else -0.02f
            dwell = (existing.dwell + stillBoost + 0.01f).coerceIn(0.05f, 1f)
            existing.cx = cx
            existing.cy = cy
            existing.hits = (existing.hits + 1).coerceAtMost(500)
            existing.lastMs = now
            existing.motion = motion
            existing.dwell = dwell
        }

        val hits = tracks[tid]?.hits ?: 1
        val persistence = (hits / 40f).coerceIn(0f, 1f)
        val boxResidual = boxLumResidual(lumGrid, b)
        val regionResidual: Float = (
            boxResidual * 0.55f +
                output.residualLevel * 0.2f * (0.5f + 0.5f * persistence) +
                motion * 0.15f +
                dwell * 0.10f
            ).coerceIn(0f, 1f)

        val conf = b.confidence.coerceIn(0f, 1f)
        val score: Float = (
            global * 0.25f +
                regionResidual * 0.35f +
                dwell * 0.2f +
                persistence * 0.12f +
                conf * 0.08f
            ).coerceIn(0f, 1f) * (0.85f + area.coerceAtMost(0.4f))

        scored.add(ScoredBox(index, b, score, motion, dwell))
    }

    val staleKeys = tracks.keys.filter { key ->
        key !in seen && (now - (tracks[key]?.lastMs ?: 0L)) > 3000L
    }
    for (k in staleKeys) {
        tracks.remove(k)
    }

    val ranked = scored.sortedByDescending { it.score }
    val rankOf = IntArray(boxes.size) { -1 }
    ranked.take(3).forEachIndexed { rank, s ->
        rankOf[s.index] = rank + 1
    }
    val scoreOf = FloatArray(boxes.size)
    for (s in scored) {
        scoreOf[s.index] = s.score
    }

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val twoPi = (2.0 * PI).toFloat()

        val heat = ranked.firstOrNull()?.score ?: global
        if (gridHeat && heat > 0.12f) {
            val gx = 8
            val gy = 6
            val cellW = w / gx
            val cellH = h / gy
            val centers = ranked.take(3).map { s ->
                val bx = (s.box.left + s.box.right) * 0.5f * w
                val by = (s.box.top + s.box.bottom) * 0.5f * h
                Offset(bx, by) to s.score
            }
            for (iy in 0 until gy) {
                for (ix in 0 until gx) {
                    val cellCx = (ix + 0.5f) * cellW
                    val cellCy = (iy + 0.5f) * cellH
                    var a = heat * 0.06f
                    for ((c, s) in centers) {
                        val d = hypot(cellCx - c.x, cellCy - c.y) / (min(w, h) * 0.45f)
                        a += s * 0.2f * (1f - d.coerceIn(0f, 1f))
                    }
                    a = a.coerceIn(0f, 0.28f)
                    if (a > 0.03f) {
                        drawRect(
                            color = Color(1f, 0.25f, 0.05f, a),
                            topLeft = Offset(ix * cellW, iy * cellH),
                            size = Size(cellW, cellH)
                        )
                    }
                }
            }
        }

        trails.removeAll { dot -> now - dot.bornMs > 2800L }
        for (t in trails) {
            val age = (now - t.bornMs) / 2800f
            val a = ((1f - age) * 0.4f * t.strength).coerceIn(0f, 0.4f)
            drawCircle(
                color = Color(1f, 0.4f, 0.1f, a),
                radius = (16f + t.strength * 44f) * (1f - age * 0.35f),
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
            val localEnergy = if (idx < scoreOf.size) scoreOf[idx] else 0f

            drawRect(
                color = Color(0xFF00E5A0).copy(alpha = 0.85f),
                topLeft = Offset(left, top),
                size = Size(bw, bh),
                style = Stroke(width = if (rank == 1) 3.5f else 2f)
            )

            if (rank in 1..3 && localEnergy > 0.18f) {
                val ew = bw * 0.72f
                val eh = bh * 0.88f
                drawOval(
                    color = Color(1f, 0.55f, 0.2f, 0.6f * localEnergy),
                    topLeft = Offset(cx - ew / 2f, cy - eh / 2f),
                    size = Size(ew, eh),
                    style = Stroke(width = 2.5f)
                )
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(1f, 0.3f, 0.05f, 0.25f * localEnergy),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = min(ew, eh) * 0.55f
                    ),
                    topLeft = Offset(cx - ew / 2f, cy - eh / 2f),
                    size = Size(ew, eh)
                )
            }

            val rankTag = if (rank in 1..3) " #$rank" else ""
            val pct = (localEnergy * 100f).toInt()
            val confPct = (b.confidence * 100f).toInt()
            val label = "${b.label} $confPct%$rankTag fp$pct"
            val layout = measurer.measure(
                label,
                style = TextStyle(color = Color.White, fontSize = 10.sp)
            )
            val badgeColor = when (rank) {
                1 -> Color(1f, 0.3f, 0.05f, 0.9f)
                2 -> Color(1f, 0.5f, 0.1f, 0.8f)
                3 -> Color(1f, 0.7f, 0.15f, 0.7f)
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

            if (showPlumes && localEnergy > 0.16f) {
                val local = phase + idx * 0.17f
                val ang = local * twoPi
                val driftX = cos(ang) * bw * 0.06f
                val driftY = sin(ang * 1.3f) * bh * 0.05f
                val px = cx + driftX
                val py = cy + driftY
                val baseR = min(bw, bh) * (0.28f + localEnergy * 0.42f)
                val pr = baseR * breath

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(1f, 0.4f, 0.08f, 0.25f * localEnergy),
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
                            Color(1f, 0.35f, 0.05f, 0.55f * localEnergy * breath),
                            Color(1f, 0.55f, 0.12f, 0.25f * localEnergy),
                            Color.Transparent
                        ),
                        center = Offset(px, py),
                        radius = pr
                    ),
                    radius = pr,
                    center = Offset(px, py)
                )

                if (rank in 1..3 && (phase * 20f).toInt() % 4 == 0) {
                    val nx = (px / w).coerceIn(0f, 1f)
                    val ny = (py / h).coerceIn(0f, 1f)
                    if (trails.size >= 64) {
                        trails.removeAt(0)
                    }
                    trails.add(TrailDot(nx, ny, localEnergy, now))
                }
            }
        }

        if (audioSpike) {
            val rcx = w * 0.5f
            val rcy = h * 0.5f
            val rr = min(w, h) * (0.28f + 0.08f * ringPulse)
            drawCircle(
                color = Color(0.2f, 0.9f, 1f, 0.55f * ringPulse),
                radius = rr,
                center = Offset(rcx, rcy),
                style = Stroke(width = 4f)
            )
            drawCircle(
                color = Color(0.4f, 1f, 0.9f, 0.25f * ringPulse),
                radius = rr * 0.72f,
                center = Offset(rcx, rcy),
                style = Stroke(width = 2f)
            )
        }
    }
}
