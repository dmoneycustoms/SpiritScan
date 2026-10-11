package com.nscb.spiritscan.ui.senses

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nscb.spiritscan.senses.CoincidenceEngine
import com.nscb.spiritscan.senses.DoaEngine
import com.nscb.spiritscan.senses.PareState
import kotlin.math.max

/** Camera-HUD ring: one arc per channel, lit while that channel is recently active. Red when 3+ agree. */
@Composable
fun BoxScope.SyncRing(snap: CoincidenceEngine.Snapshot?) {
    if (snap == null) return
    val k = snap.k
    Column(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.size(46.dp)) {
            val n = snap.names.size
            val sweep = 360f / n
            val stroke = 7f
            for (i in 0 until n) {
                val on = snap.activeNow[i]
                val col = if (on) (if (k >= 3) DangerC else AmberC) else Color(0x44FFFFFF)
                drawArc(
                    color = col,
                    startAngle = -90f + i * sweep + 3f,
                    sweepAngle = sweep - 6f,
                    useCenter = false,
                    topLeft = Offset(stroke, stroke),
                    size = Size(size.width - 2 * stroke, size.height - 2 * stroke),
                    style = Stroke(stroke)
                )
            }
        }
        Text(
            "SYNC $k",
            color = if (k >= 3) DangerC else if (k >= 2) AmberC else MuteC,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.background(Color.Black.copy(0.55f), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp)
        )
    }
}

/** Camera-HUD strip showing where the last clear sound came from. */
@Composable
fun BoxScope.DoaStrip(doa: DoaEngine.Snapshot?) {
    if (doa == null || !doa.valid || !doa.stereoOk) return
    Column(
        Modifier
            .align(Alignment.TopCenter)
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(
            Modifier
                .width(180.dp)
                .height(12.dp)
        ) {
            drawRect(Color(0x66000000), Offset(0f, 0f), Size(size.width, size.height))
            drawLine(Color(0x66FFFFFF), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = 1f)
            val x = size.width * ((doa.angleDeg + 90f) / 180f)
            drawLine(AmberC, Offset(x, 0f), Offset(x, size.height), strokeWidth = 5f)
        }
        Text(
            "SOUND ${"%+.0f".format(doa.angleDeg)}°",
            color = AmberC,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** Draws the face-like / figure-like constellations the pareidolia meter found, with the chance of seeing them. */
@Composable
fun BoxScope.PareidoliaOverlay(p: PareState?, mapW: Int, mapH: Int) {
    if (p == null || (!p.face && !p.figure)) return
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
        color = android.graphics.Color.rgb(255, 79, 208)
    }
    Canvas(Modifier.fillMaxSize()) {
        val s = max(size.width / mapW, size.height / mapH)
        val offX = (size.width - mapW * s) / 2f
        val offY = (size.height - mapH * s) / 2f
        paint.textSize = 9.sp.toPx()
        for (a in p.lines) {
            val kind = a[0].toInt()
            val n = (a.size - 1) / 2
            val col = if (kind == 0) Color(0xFFFF4FD0) else Color(0xFF3DE8FF)
            val pts = ArrayList<Offset>()
            for (i in 0 until n) pts.add(Offset(offX + a[1 + 2 * i] * mapW * s, offY + a[2 + 2 * i] * mapH * s))
            for (i in 0 until pts.size) {
                for (j in i + 1 until pts.size) {
                    drawLine(col.copy(alpha = 0.7f), pts[i], pts[j], strokeWidth = 1.5f)
                }
                drawCircle(col, radius = 6f, center = pts[i], style = Stroke(2f))
            }
            if (pts.isNotEmpty()) {
                val label = (if (kind == 0) "FACE-LIKE" else "FIGURE-LIKE") + " · chance ${"%.0f".format(p.chance * 100)}% with ${p.n} blobs"
                drawIntoCanvas { it.nativeCanvas.drawText(label, max(2f, pts[0].x - 20f), pts[0].y - 12f, paint) }
            }
        }
    }
}

/** Geiger-style sonification control. */
@Composable
fun GeigerCard(mode: Int, headphones: Boolean, onCycle: () -> Unit) {
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono("GEIGER MODE · clicks and haptics", MuteC, 11)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCycle) {
                Mono(
                    when (mode) {
                        1 -> "CLICKS"
                        2 -> "CLICKS + HAPTIC"
                        else -> "OFF"
                    },
                    if (mode == 0) MuteC else AmberC, 11
                )
            }
        }
        Mono(
            "Click rate follows the strongest of: magnetometer, camera anomaly, passive novelty and coincidences" +
                (if (headphones) ", plus voice score and sonar (headphones detected)" else "") +
                ". Slow ticking at rest, a rattle when something stands out. Eyes-free in the dark.",
            MuteC, 10
        )
        if (!headphones) {
            Mono(
                "No headphones: microphone-based channels (voice, sonar) are left out so the clicks cannot feed back into them. " +
                    "Use haptics or plug in headphones to include them.",
                MuteC, 9
            )
        }
    }
}

/** Pareidolia meter card. */
@Composable
fun PareidoliaCard(p: PareState?, onReset: () -> Unit) {
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono("PAREIDOLIA METER · how often noise looks like a face", MuteC, 11)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onReset) { Mono("RESET", MuteC, 10) }
        }
        if (p == null) {
            Mono("Select DECODE or AIR and hold still: it watches the blobs the camera decoder reports.", MuteC, 10)
            return@CardBox
        }
        Mono(
            "blobs now ${p.n} · chance that this many random blobs contain a face-like triplet: ${"%.1f".format(p.chance * 100)}%",
            if (p.face) Color(0xFFFF4FD0) else FgC
        )
        Mono(
            if (p.face) "FACE-LIKE pattern in view right now" else if (p.figure) "FIGURE-LIKE stack in view right now" else "no face-like pattern right now",
            if (p.face || p.figure) Color(0xFFFF4FD0) else SignalC
        )
        Mono(
            "session: ${p.faceFrames} face-like frames out of ${p.frames} with 3+ blobs · chance predicts ${"%.1f".format(p.expected)} (z=${"%.1f".format(p.z)})",
            MuteC, 9
        )
        Mono(
            if (p.frames < 50) "collecting…"
            else if (p.z > 3.0) "more face-like patterns than random blobs would give (z>3). Worth a closer look, still not a face."
            else "consistent with chance: your eye (and this meter) finds faces in random blobs.",
            if (p.frames >= 50 && p.z > 3.0) AmberC else FgC, 10
        )
    }
}
