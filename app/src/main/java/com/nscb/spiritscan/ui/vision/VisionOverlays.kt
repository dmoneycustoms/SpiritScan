package com.nscb.spiritscan.ui.vision

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nscb.spiritscan.entity.EntityOutput
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

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

/** Mutable particle — not a data class (avoids private data-class access errors). */
private class HeatParticle {
    var x = Random.nextFloat()
    var y = Random.nextFloat()
    var vx = (Random.nextFloat() - 0.5f) * 0.15f
    var vy = -0.05f - Random.nextFloat() * 0.12f
    var life = Random.nextFloat()
    var maxLife = 0.6f + Random.nextFloat() * 1.2f
    var size = 4f + Random.nextFloat() * 10f
    var heat = Random.nextFloat()
}

@Composable
fun HeatOverlay(output: EntityOutput) {
    val energy = (
        output.qida * 0.35f +
            output.residualLevel * 0.30f +
            (abs(output.zMag) / 8f).coerceIn(0f, 1f) * 0.20f +
            abs(output.survey.thermalDelta) / 4f * 0.10f +
            output.jonesScore * 0.05f
        ).coerceIn(0f, 1f)

    val particles = remember { List(28) { HeatParticle() } }
    var frame by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nanos ->
                frame = (nanos % 1_000_000_000L) / 1_000_000_000f
            }
            val dt = 0.032f
            val e = energy
            val spawnBoost = 0.35f + e * 1.5f
            for (p in particles) {
                p.life -= dt / p.maxLife
                p.x += p.vx * dt * (0.5f + e)
                p.y += p.vy * dt * (0.5f + e)
                p.vx += (0.5f - p.x) * 0.08f * e * dt
                p.vy += (0.45f - p.y) * 0.05f * e * dt - 0.025f * e * dt
                if (p.life <= 0f || p.y < -0.05f || p.x < -0.1f || p.x > 1.1f) {
                    if (Random.nextFloat() < spawnBoost) {
                        p.x = 0.15f + Random.nextFloat() * 0.7f
                        p.y = 0.65f + Random.nextFloat() * 0.4f
                        p.vx = (Random.nextFloat() - 0.5f) * 0.22f
                        p.vy = -0.08f - Random.nextFloat() * 0.18f * (0.4f + e)
                        p.life = 1f
                        p.maxLife = 0.45f + Random.nextFloat() * (0.9f + e)
                        p.size = 5f + Random.nextFloat() * (8f + e * 14f)
                        p.heat = 0.35f + e * 0.65f * Random.nextFloat()
                    } else {
                        p.life = 0f
                    }
                }
            }
        }
    }

    val tick = frame

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h * 0.42f
        val maxR = min(w, h) * (0.25f + energy * 0.35f)

        val stepX = w / 8f
        val stepY = h / 8f
        for (i in 1 until 8) {
            drawLine(Color(0x22FFFFFF), Offset(i * stepX, 0f), Offset(i * stepX, h), 1f)
            drawLine(Color(0x22FFFFFF), Offset(0f, i * stepY), Offset(w, i * stepY), 1f)
        }

        drawRect(ironbow(energy * 0.35f, 0.22f + energy * 0.15f))

        if (energy > 0.08f) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        ironbow((energy + 0.4f).coerceIn(0f, 1f), 0.55f),
                        ironbow(energy, 0.28f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = maxR
                ),
                radius = maxR,
                center = Offset(cx, cy)
            )
        }

        for (p in particles) {
            if (p.life <= 0f) continue
            val alpha = (p.life * (0.4f + energy * 0.6f)).coerceIn(0f, 0.95f)
            val r = p.size * (0.85f + energy * 0.5f)
            drawCircle(
                color = ironbow(p.heat * (0.5f + energy * 0.5f), alpha),
                radius = r,
                center = Offset(p.x * w, p.y * h)
            )
        }

        if (energy > 0.35f) {
            drawCircle(
                color = ironbow(0.95f, 0.4f + energy * 0.3f),
                radius = 14f + energy * 36f,
                center = Offset(cx, cy),
                style = Stroke(width = 2f + energy * 2f)
            )
        }
        // silence unused warning
        @Suppress("UNUSED_EXPRESSION")
        tick
    }
}

@Composable
fun UvOverlay(output: EntityOutput) {
    val energy = (
        (if (!output.sdeOk) 0.4f else 0f) +
            (1f - output.sdeComposite.coerceIn(0f, 1f)) * 0.25f +
            output.residualLevel * 0.35f
        ).coerceIn(0f, 1f)

    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = min(size.width, size.height) * (0.3f + energy * 0.35f)
        val stepX = size.width / 8f
        val stepY = size.height / 8f
        for (i in 1 until 8) {
            drawLine(Color(0x44AA88FF), Offset(i * stepX, 0f), Offset(i * stepX, size.height), 1f)
            drawLine(Color(0x44AA88FF), Offset(0f, i * stepY), Offset(size.width, i * stepY), 1f)
        }
        val c = Color(0.25f + energy * 0.3f, 0.1f + energy * 0.15f, 0.55f + energy * 0.4f, 0.3f + energy * 0.3f)
        drawRect(c.copy(alpha = 0.25f))
        drawCircle(
            brush = Brush.radialGradient(
                listOf(c, c.copy(alpha = 0.15f), Color.Transparent),
                center = Offset(cx, cy),
                radius = maxR
            ),
            radius = maxR,
            center = Offset(cx, cy)
        )
    }
}

@Composable
fun MagOverlay(output: EntityOutput) {
    val energy = (
        (abs(output.zMag) / 6f).coerceIn(0f, 1f) * 0.55f +
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
        drawRect(Color(0.05f, 0.2f + energy * 0.3f, 0.35f, 0.25f + energy * 0.15f))
        for (i in 1..5) {
            drawCircle(
                Color(0f, 0.7f, 0.85f, 0.1f + energy * 0.12f),
                radius = maxR * (i / 5f),
                center = Offset(cx, cy),
                style = Stroke(1.5f)
            )
        }
        if (energy > 0.25f) {
            drawCircle(
                Color(0f, 1f, 0.9f, 0.35f + energy * 0.3f),
                radius = 10f + energy * 40f,
                center = Offset(cx, cy)
            )
        }
    }
}

@Composable
fun NightOverlay(output: EntityOutput) {
    val lux = output.survey.lux ?: 80f
    val energy = (
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
        drawRect(Color(0f, 0.06f + energy * 0.1f, 0.02f, 0.4f))
        drawCircle(
            Color(0.1f, 0.85f, 0.25f, 0.15f + energy * 0.3f),
            radius = min(size.width, size.height) * (0.25f + energy * 0.15f),
            center = Offset(cx, cy)
        )
    }
}

@Composable
fun JonesOverlay(output: EntityOutput) {
    val energy = output.jonesScore.coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stepX = size.width / 8f
        val stepY = size.height / 8f
        for (i in 1 until 8) {
            drawLine(Color(0x3300FFAA), Offset(i * stepX, 0f), Offset(i * stepX, size.height), 1f)
            drawLine(Color(0x3300FFAA), Offset(0f, i * stepY), Offset(size.width, i * stepY), 1f)
        }
        drawRect(Color(0f, 0.25f, 0.18f, 0.18f + energy * 0.22f))
        drawCircle(
            Color(0f, 1f, 0.65f, 0.2f + energy * 0.45f),
            radius = 18f + energy * 55f,
            center = Offset(cx, cy),
            style = Stroke(2f + energy * 2f)
        )
    }
}

@Composable
fun OmegaOverlay(output: EntityOutput) {
    val energy = (1f - output.omegaTrust.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stepX = size.width / 8f
        val stepY = size.height / 8f
        for (i in 1 until 8) {
            drawLine(Color(0x3300FF00), Offset(i * stepX, 0f), Offset(i * stepX, size.height), 1f)
            drawLine(Color(0x3300FF00), Offset(0f, i * stepY), Offset(size.width, i * stepY), 1f)
        }
        drawRect(Color(0.05f, 0.22f, 0.05f, 0.22f + energy * 0.2f))
        drawCircle(
            Color(0.2f, 1f, 0.2f, 0.25f + energy * 0.4f),
            radius = 22f + energy * 48f,
            center = Offset(cx, cy),
            style = Stroke(2f)
        )
    }
}
