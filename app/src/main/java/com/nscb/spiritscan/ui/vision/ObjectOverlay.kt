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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val cx: Float,
    val cy: Float
)

private fun boxLumResidual(grid: FloatArray?, b: DetectedObjectBox): Float {
    if (grid == null || grid.isEmpty()) return 0f
    val side = sqrt(grid.size.toDouble()).toInt().coerceAtLeast(1)
    if (side * side > grid.size) return 0f
    var mean = 0.0
    for (v in grid) mean += v.toDouble()
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

/**
 * OBJ overlay with two modes:
 *  - Idle: light footprint ranks (reference map)
 *  - Spike active: plumes collapse toward dense cluster center (true anomaly pull)
 */
@Composable
fun ObjectOverlay(
    boxes: List<DetectedObjectBox>,
    output: EntityOutput,
    showPlumes: Boolean = true,
    audioSpike: Boolean = false,
    gridHeat: Boolean = true,
    lumGrid: FloatArray? = null,
    /** Real stack spike: residual / vision OOD / dense unknown / audio unknown */
    spikeActive: Boolean = false,
    spikeStrength: Float = 0f
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
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "plumeBreath"
    )
    val ringPulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "audioRing"
    )

    val trails: SnapshotStateList<TrailDot> = remember { mutableStateListOf() }
    val tracks: SnapshotStateMap<Int, TrackMem> = remember { mutableStateMapOf() }
    // Smoothed cluster center (normalized 0..1)
    var clusterX by remember { mutableFloatStateOf(0.5f) }
    var clusterY by remember { mutableFloatStateOf(0.5f) }
    var clusterStr by remember { mutableFloatStateOf(0f) }
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

        scored.add(ScoredBox(index, b, score, cx, cy))
    }

    for (k in tracks.keys.filter { it !in seen && (now - (tracks[it]?.lastMs ?: 0L)) > 3000L }) {
        tracks.remove(k)
    }

    val ranked = scored.sortedByDescending { it.score }
    val rankOf = IntArray(boxes.size) { -1 }
    ranked.take(3).forEachIndexed { rank, s -> rankOf[s.index] = rank + 1 }
    val scoreOf = FloatArray(boxes.size)
    for (s in scored) scoreOf[s.index] = s.score

    // Dense signature cluster = weighted centroid of top ranks
    val top = ranked.take(3)
    if (top.isNotEmpty()) {
        var wsum = 0f
        var sx = 0f
        var sy = 0f
        for (s in top) {
            val w = s.score.coerceAtLeast(0.05f)
            sx += s.cx * w
            sy += s.cy * w
            wsum += w
        }
        val tx = if (wsum > 0f) sx / wsum else 0.5f
        val ty = if (wsum > 0f) sy / wsum else 0.5f
        // Pull cluster center harder when spike is active
        val lerp = if (spikeActive) 0.28f else 0.08f
        clusterX = clusterX * (1f - lerp) + tx * lerp
        clusterY = clusterY * (1f - lerp) + ty * lerp
        val targetStr = if (spikeActive) {
            (spikeStrength.coerceIn(0.2f, 1f) * 0.7f + (top.firstOrNull()?.score ?: 0f) * 0.3f)
        } else {
            0f
        }
        clusterStr = clusterStr * 0.85f + targetStr * 0.15f
    } else if (!spikeActive) {
        clusterStr *= 0.9f
    }

    // Pull amount: 0 idle → up to ~0.85 when spike strong
    val pull = if (spikeActive) (0.35f + clusterStr * 0.55f).coerceIn(0f, 0.9f) else 0f

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val twoPi = (2.0 * PI).toFloat()
        val cpx = clusterX * w
        val cpy = clusterY * h

        // --- Spike cluster core (only when anomaly active) ---
        if (spikeActive && clusterStr > 0.08f) {
            val coreR = min(w, h) * (0.12f + clusterStr * 0.18f) * breath
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(1f, 0.2f, 0.05f, 0.55f * clusterStr),
                        Color(1f, 0.45f, 0.1f, 0.25f * clusterStr),
                        Color.Transparent
                    ),
                    center = Offset(cpx, cpy),
                    radius = coreR * 1.6f
                ),
                radius = coreR * 1.6f,
                center = Offset(cpx, cpy)
            )
            drawCircle(
                color = Color(1f, 0.85f, 0.3f, 0.7f * clusterStr * ringPulse),
                radius = coreR * 0.35f,
                center = Offset(cpx, cpy)
            )
            // converging ring
            drawCircle(
                color = Color(1f, 0.4f, 0.1f, 0.45f * ringPulse),
                radius = coreR * (1.1f + 0.25f * (1f - ringPulse)),
                center = Offset(cpx, cpy),
                style = Stroke(width = 3f)
            )
        }

        // Grid only during spike (keeps idle cleaner)
        if (gridHeat && spikeActive && clusterStr > 0.1f) {
            val gx = 8
            val gy = 6
            val cellW = w / gx
            val cellH = h / gy
            for (iy in 0 until gy) {
                for (ix in 0 until gx) {
                    val cellCx = (ix + 0.5f) * cellW
                    val cellCy = (iy + 0.5f) * cellH
                    val d = hypot(cellCx - cpx, cellCy - cpy) / (min(w, h) * 0.5f)
                    val a = (clusterStr * 0.28f * (1f - d.coerceIn(0f, 1f))).coerceIn(0f, 0.3f)
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
            val a = ((1f - age) * 0.45f * t.strength).coerceIn(0f, 0.45f)
            drawCircle(
                color = Color(1f, 0.35f, 0.08f, a),
                radius = (14f + t.strength * 40f) * (1f - age * 0.35f),
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
            val bx = (left + right) / 2f
            val by = (top + bottom) / 2f
            val rank = if (idx < rankOf.size) rankOf[idx] else -1
            val localEnergy = if (idx < scoreOf.size) scoreOf[idx] else 0f

            // Always show boxes lightly
            drawRect(
                color = Color(0xFF00E5A0).copy(alpha = if (spikeActive) 0.9f else 0.7f),
                topLeft = Offset(left, top),
                size = Size(bw, bh),
                style = Stroke(width = if (rank == 1) 3f else 1.5f)
            )

            val rankTag = if (rank in 1..3) " #$rank" else ""
            val pct = (localEnergy * 100f).toInt()
            val confPct = (b.confidence * 100f).toInt()
            val label = "${b.label} $confPct%$rankTag fp$pct"
            val layout = measurer.measure(
                label,
                style = TextStyle(color = Color.White, fontSize = 10.sp)
            )
            val badgeColor = when {
                spikeActive && rank in 1..3 -> Color(1f, 0.25f, 0.05f, 0.9f)
                rank == 1 -> Color(1f, 0.4f, 0.1f, 0.75f)
                rank in 2..3 -> Color(1f, 0.55f, 0.15f, 0.65f)
                else -> Color.Black.copy(alpha = 0.5f)
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

            // Plumes: idle = soft rank markers; spike = pull toward cluster
            val show = showPlumes && (localEnergy > 0.16f) && (rank in 1..3 || spikeActive)
            if (show) {
                val local = phase + idx * 0.17f
                val ang = local * twoPi
                val driftX = cos(ang) * bw * 0.05f
                val driftY = sin(ang * 1.3f) * bh * 0.04f
                // Natural box center + drift
                val nx = bx + driftX
                val ny = by + driftY
                // Pull toward dense cluster when spike active
                val px = nx * (1f - pull) + cpx * pull
                val py = ny * (1f - pull) + cpy * pull

                val intensity = if (spikeActive) {
                    (localEnergy * 0.5f + clusterStr * 0.5f).coerceIn(0.2f, 1f)
                } else {
                    localEnergy * 0.55f
                }
                val baseR = min(bw, bh) * (0.22f + intensity * 0.4f)
                val pr = baseR * breath

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(1f, 0.35f, 0.05f, 0.5f * intensity),
                            Color(1f, 0.5f, 0.1f, 0.2f * intensity),
                            Color.Transparent
                        ),
                        center = Offset(px, py),
                        radius = pr * 1.3f
                    ),
                    radius = pr * 1.3f,
                    center = Offset(px, py)
                )

                // Converging filament toward cluster during spike
                if (spikeActive && pull > 0.2f && rank in 1..3) {
                    drawLine(
                        color = Color(1f, 0.45f, 0.1f, 0.35f * intensity),
                        start = Offset(nx, ny),
                        end = Offset(cpx, cpy),
                        strokeWidth = 2f
                    )
                }

                if (spikeActive && rank in 1..3 && (phase * 20f).toInt() % 3 == 0) {
                    if (trails.size >= 64) trails.removeAt(0)
                    trails.add(
                        TrailDot(
                            (px / w).coerceIn(0f, 1f),
                            (py / h).coerceIn(0f, 1f),
                            intensity,
                            now
                        )
                    )
                }
            }
        }

        if (audioSpike || (spikeActive && clusterStr > 0.15f)) {
            val rr = min(w, h) * (0.22f + 0.1f * ringPulse)
            drawCircle(
                color = Color(0.2f, 0.9f, 1f, 0.4f * ringPulse),
                radius = rr,
                center = Offset(cpx, cpy),
                style = Stroke(width = 3f)
            )
        }
    }
}
