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

/**
 * Enhanced OBJ overlay — per-box footprint ranking:
 *  - track persistence (revisit / dwell)
 *  - in-box motion (center travel)
 *  - region residual proxy (edge vs center bias + global residual)
 *  - global stack energy
 *  - rank badges, face ellipses, plumes, trails, grid, audio ring
 */
@Composable
fun ObjectOverlay(
    boxes: List<DetectedObjectBox>,
    output: EntityOutput,
    showPlumes: Boolean = true,
    audioSpike: Boolean = false,
    gridHeat: Boolean = true,
    lumGrid: FloatArray? = null
) {
    val global = (
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

    // Update track memory + per-box footprint scores
    data class Scored(val index: Int, val box: DetectedObjectBox, val score: Float, val motion: Float, val dwell: Float)

    val scored = ArrayList<Scored>(boxes.size)
    val seen = HashSet<Int>()

    for ((index, b) in boxes.withIndex()) {
        val tid = if (b.trackingId >= 0) b.trackingId else (10000 + index)
        seen.add(tid)
        val cx = (b.left + b.right) * 0.5f
        val cy = (b.top + b.bottom) * 0.5f
        val area = ((b.right - b.left) * (b.bottom - b.top)).coerceAtLeast(0.001f)

        val mem = tracks[tid]
        val motion: Float
        val dwell: Float
        if (mem == null) {
            tracks[tid] = TrackMem(cx, cy, 1, now, 0f, 0.15f)
            motion = 0f
            dwell = 0.15f
        } else {
            val dist = hypot(cx - mem.cx, cy - mem.cy)
            // Normalized motion: large jump in normalized frame coords
            motion = (dist * 8f).coerceIn(0f, 1f) * 0.65f + mem.motion * 0.35f
            val dt = (now - mem.lastMs).coerceAtLeast(1L)
            // Dwell rises when box stays put and keeps being detected
            val stillBoost = if (dist < 0.02f) 0.04f else -0.02f
            dwell = (mem.dwell + stillBoost + 0.01f).coerceIn(0.05f, 1f)
            mem.cx = cx
            mem.cy = cy
            mem.hits = (mem.hits + 1).coerceAtMost(500)
            mem.lastMs = now
            mem.motion = motion
            mem.dwell = dwell
        }

        val hits = tracks[tid]?.hits ?: 1
        val persistence = (hits / 40f).coerceIn(0f, 1f)

        // True-ish region residual from luminance grid cells under this box
        val boxResidual = boxLumResidual(lumGrid, b)
        val regionResidual = (
            boxResidual * 0.55f +
                output.residualLevel * 0.2f * (0.5f + 0.5f * persistence) +
                motion * 0.15f +
                dwell * 0.10f
            ).coerceIn(0f, 1f)

        // Footprint score — emphasizes dwell + revisit + local residual, not only box size
        val score = (
            global * 0.25f +
                regionResidual * 0.35f +
                dwell * 0.2f +
                persistence * 0.12f +
                b.confidence.coerceIn(0f, 1f) * 0.08f
            ).coerceIn(0f, 1f) * (0.85f + area.coerceAtMost(0.4f))

        scored.add(Scored(index, b, score, motion, dwell))
    }

    // Decay tracks not seen
    val stale = tracks.keys.filter { it !in seen && (now - (tracks[it]?.lastMs ?: 0L)) > 3000L }
    stale.forEach { tracks.remove(it) }

    val ranked = scored.sortedByDescending { it.score }
    val rankOf = IntArray(boxes.size) { -1 }
    ranked.take(3).forEachIndexed { rank, s -> rankOf[s.index] = rank + 1 }
    val scoreOf = FloatArray(boxes.size) { 0f }
    for (s in scored) scoreOf[s.index] = s.score

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val twoPi = (2.0 * PI).toFloat()

        // Grid heat weighted by top footprint
        val heat = (ranked.firstOrNull()?.score ?: global)
        if (gridHeat && heat > 0.12f) {
            val gx = 8
            val gy = 6
            val cellW = w / gx
            val cellH = h / gy
            // Stronger cells near ranked box centers
            val centers = ranked.take(3).map {
                Offset((it.box.left + it.box.right) * 0.5f * w, (it.box.top + it.box.bottom) * 0.5f * h) to it.score
            }
            for (iy in 0 until gy) {
                for (ix in 0 until gx) {
                    val cx = (ix + 0.5f) * cellW
                    val cy = (iy + 0.5f) * cellH
                    var a = heat * 0.06f
                    for ((c, s) in centers) {
                        val d = hypot(cx - c.x, cy - c.y) / (min(w, h) * 0.45f)
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

        trails.removeAll { now - it.bornMs > 2800L }
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
            val fp = if (idx < scoreOf.size) scoreOf[idx] else 0f
            val localEnergy = fp.coerceIn(0f, 1f)

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
            val pct = (localEnergy * 100).toInt()
            val label = "${b.label} ${"%.0f".format(b.confidence * 100)}%$rankTag fp$pct"
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

                if (rank in 1..3 && (phase * 20).toInt() % 4 == 0) {
                    val nx = (px / w).coerceIn(0f, 1f)
                    val ny = (py / h).coerceIn(0f, 1f)
                    if (trails.size >= 64) trails.removeAt(0)
                    trails.add(TrailDot(nx, ny, localEnergy, now))
                }
            }
        }

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
