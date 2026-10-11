package com.nscb.spiritscan.ui.senses

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nscb.spiritscan.senses.CoincidenceEngine
import com.nscb.spiritscan.senses.DoaEngine
import com.nscb.spiritscan.senses.MagMapEngine
import com.nscb.spiritscan.senses.SonarEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal val CardC = Color(0xFF1A1E26)
internal val BorderC = Color(0xFF2A303A)
internal val MuteC = Color(0xFF8B9196)
internal val FgC = Color(0xFFE8EAED)
internal val SignalC = Color(0xFF709A8E)
internal val DangerC = Color(0xFFE85D4C)
internal val AmberC = Color(0xFFFFB020)
internal val CyanC = Color(0xFF3DE8FF)

@Composable
internal fun CardBox(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(CardC, RoundedCornerShape(8.dp))
            .border(1.dp, BorderC, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        content()
    }
}

@Composable
internal fun Mono(text: String, color: Color = MuteC, size: Int = 10) {
    Text(text, color = color, fontSize = size.sp, fontFamily = FontFamily.Monospace)
}

/** Ultrasonic sonar: range-time waterfall of motion over the learned quiet floor. */
@Composable
fun SonarCard(snap: SonarEngine.Snapshot?, on: Boolean, onToggle: () -> Unit) {
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono("ULTRASONIC SONAR · 18–20.5 kHz", MuteC, 11)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggle) { Mono(if (on) "STOP" else "START", if (on) DangerC else SignalC, 11) }
        }
        if (!on || snap == null) {
            Mono(
                "Plays a near-silent chirp and listens for echoes. Sees movement and breathing-like micro-motion out to 3.4 m, " +
                    "in the dark, but not direction and not through walls. Needs the scan running (starts it). Keep the media " +
                    "volume low: dogs and cats can hear 18+ kHz. Place the phone still on a surface and stay out of the room " +
                    "for the 3 s it learns the quiet.",
                MuteC, 10
            )
            return@CardBox
        }
        Mono(snap.note, if (snap.targets.isEmpty()) SignalC else AmberC)
        val bmp = remember(snap.waterfall) {
            Bitmap.createBitmap(snap.waterfall, snap.nb, snap.rows, Bitmap.Config.ARGB_8888)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
        ) {
            drawImage(
                image = bmp.asImageBitmap(),
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.None
            )
            val totalM = snap.nb * snap.mPerBin
            for (m in 1..3) {
                val x = size.width * (m / totalM)
                drawLine(Color(0x55FFFFFF), Offset(x, size.height - 8f), Offset(x, size.height), strokeWidth = 1f)
            }
            for (t in snap.targets) {
                val x = size.width * (t.rangeM / totalM)
                drawLine(if (t.living) SignalC else AmberC, Offset(x, 0f), Offset(x, 12f), strokeWidth = 3f)
            }
        }
        Mono("x = range 0 to ${"%.1f".format(snap.nb * snap.mPerBin)} m (ticks at 1, 2, 3 m) · newest at top · colour = dB over the quiet floor", MuteC, 9)
        Mono(
            "direct path ${"%.0f".format(snap.directDb)} dB · ${if (snap.signal) "echo signal ok" else "NO echo signal"}" +
                (if (!snap.ready) " · learning" else ""),
            if (snap.signal) MuteC else DangerC, 9
        )
        for (t in snap.targets.sortedByDescending { it.dopDb }.take(4)) {
            Mono(
                "${if (t.living) "●" else "◆"} ${"%.1f".format(t.rangeM)} m ${t.kind} " +
                    "${"%+.1f".format(t.velCms)} cm/s doppler ${"%.0f".format(t.dopDb)}dB ${"%.0f".format(t.ageSec)}s",
                if (t.living) SignalC else AmberC, 10
            )
        }
        if (snap.targets.any { it.kind == "NEAR-FIELD" }) {
            Mono("NEAR-FIELD = you or your hand next to the phone", MuteC, 9)
        }
    }
}

private fun devColor(dev: Float): Color {
    val a = (abs(dev) / 10f).coerceIn(0f, 1f)
    return if (dev >= 0f) Color(0.25f + 0.75f * a, 0.25f * (1f - a) + 0.1f, 0.15f * (1f - a), 1f)
    else Color(0.1f * (1f - a), 0.3f * (1f - a) + 0.2f * a, 0.3f + 0.7f * a, 1f)
}

/** Magnetic floor map. */
@Composable
fun MapCard(
    snap: MagMapEngine.Snapshot?,
    on: Boolean,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    onStepLength: (Double) -> Unit = {}
) {
    val stepChoices = listOf(0.60, 0.65, 0.70, 0.75, 0.80)
    var stepIdx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(2) }
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono("MAGNETIC FLOOR MAP", MuteC, 11)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggle) { Mono(if (on) "STOP" else "START", if (on) DangerC else SignalC, 11) }
        }
        if (!on || snap == null) {
            Mono(
                "Walk the room holding the phone steady and facing the way you walk. Steps and gyroscope place you; every " +
                    "step deposits the local field strength in a 0.5 m cell. Walk the loop a second time (after 20 s): fixed " +
                    "anomalies are wiring or steel, a cell that differs from the first pass is flagged CHANGED. Dead reckoning " +
                    "drifts about a metre in two minutes: keep walks short and reset between rooms.",
                MuteC, 10
            )
            return@CardBox
        }
        val g = snap.g
        var minX = g
        var maxX = 0
        var minY = g
        var maxY = 0
        for (c in 0 until g * g) {
            if (snap.state[c].toInt() != 0) {
                val ix = c % g
                val iy = c / g
                if (ix < minX) minX = ix
                if (ix > maxX) maxX = ix
                if (iy < minY) minY = iy
                if (iy > maxY) maxY = iy
            }
        }
        val px = (snap.x / snap.cell + g / 2f)
        val py = (snap.y / snap.cell + g / 2f)
        minX = minOf(minX, px.toInt()) - 2
        maxX = maxOf(maxX, px.toInt()) + 2
        minY = minOf(minY, py.toInt()) - 2
        maxY = maxOf(maxY, py.toInt()) + 2
        if (maxX < minX) {
            minX = g / 2 - 8
            maxX = g / 2 + 8
            minY = g / 2 - 8
            maxY = g / 2 + 8
        }
        val spanX = maxOf(maxX - minX + 1, 16)
        val spanY = maxOf(maxY - minY + 1, 16)
        val span = maxOf(spanX, spanY)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(230.dp)
        ) {
            val cs = minOf(size.width, size.height) / span
            val offX = (size.width - cs * span) / 2f
            for (c in 0 until g * g) {
                val st = snap.state[c].toInt()
                if (st == 0) continue
                val ix = c % g
                val iy = c / g
                if (ix < minX || ix >= minX + span || iy < minY || iy >= minY + span) continue
                val x = offX + (ix - minX) * cs
                val y = (span - 1 - (iy - minY)) * cs
                drawRect(devColor(snap.mean[c] - snap.median), Offset(x, y), Size(cs * 0.95f, cs * 0.95f))
                if (st == 2) drawRect(DangerC, Offset(x, y), Size(cs * 0.95f, cs * 0.95f), style = Stroke(2f))
            }
            val cx = offX + (px - minX) * cs
            val cy = (span - (py - minY)) * cs
            drawCircle(Color.White, radius = 5f, center = Offset(cx, cy))
            val hd = Math.toRadians(snap.headingDeg.toDouble())
            drawLine(
                Color.White, Offset(cx, cy),
                Offset(cx + (cos(hd) * 16.0).toFloat(), cy - (sin(hd) * 16.0).toFloat()), strokeWidth = 2f
            )
        }
        Mono("blue = weaker, red = stronger than the room median · red outline = CHANGED since first pass · white dot = you", MuteC, 9)
        Mono(
            "steps ${snap.steps} · at (${"%.1f".format(snap.x)}, ${"%.1f".format(snap.y)}) m · cells ${snap.visited} · " +
                "noise ${"%.2f".format(snap.sigma)} µT · median ${"%.1f".format(snap.median)} µT",
            MuteC, 9
        )
        Mono(snap.note, if (snap.changedCells > 0) AmberC else SignalC)
        if (snap.changedCells > 0) Mono("${snap.changedCells} cell(s) CHANGED since you first walked there", AmberC)
        for (p in snap.pins.take(4)) {
            Mono(
                "${if (p.kind == "CHANGED") "◆" else "●"} ${p.kind} ${"%+.1f".format(p.dev)} µT at (${"%.1f".format(p.x)}, ${"%.1f".format(p.y)})",
                if (p.kind == "CHANGED") AmberC else FgC, 10
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onReset) { Mono("RESET MAP", SignalC) }
            TextButton(onClick = {
                stepIdx = (stepIdx + 1) % stepChoices.size
                onStepLength(stepChoices[stepIdx])
            }) { Mono("STEP ${"%.2f".format(stepChoices[stepIdx])} m", SignalC) }
        }
        Mono("Step length matters: a wrong value stretches the map. Walk 10 steps along a measured 7 m line and pick the value that fits.", MuteC, 9)
    }
}

/** Sound camera: direction of arrival from two microphones. */
@Composable
fun SoundCamCard(doa: DoaEngine.Snapshot?, onCal: () -> Unit, onClear: () -> Unit) {
    CardBox {
        Mono("SOUND CAMERA · two-microphone direction", MuteC, 11)
        if (doa == null) {
            Mono("Start the Spirit Box scan: it opens the microphones.", MuteC, 10)
            return@CardBox
        }
        if (!doa.stereoOk) {
            Mono("This phone gives duplicated mono, so there is no direction to measure.", DangerC)
            return@CardBox
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            val bins = doa.hist.size
            var mx = 0.001f
            for (v in doa.hist) if (v > mx) mx = v
            val bw = size.width / bins
            for (i in 0 until bins) {
                val h = size.height * 0.8f * (doa.hist[i] / mx)
                drawRect(Color(0xFF3A5560), Offset(i * bw, size.height - h), Size(bw * 0.8f, h))
            }
            if (doa.valid) {
                val x = size.width * ((doa.angleDeg + 90f) / 180f)
                drawLine(AmberC, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4f)
            }
            drawLine(Color(0x44FFFFFF), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = 1f)
        }
        Mono("-90° · toward microphone 2 ... 0° broadside ... toward microphone 1 · +90°", MuteC, 9)
        Mono(
            if (doa.valid) "SOUND at ${"%+.0f".format(doa.angleDeg)}° · confidence ${(doa.confidence * 100).toInt()}% · delay ${"%.0f".format(doa.tauUs)} µs"
            else "no clear sound source",
            if (doa.valid) AmberC else SignalC
        )
        Mono(
            "level ${"%.0f".format(doa.levelDb)} dBFS · mic spacing assumed ${"%.0f".format(doa.baselineM * 100)} cm · bias ${"%.0f".format(doa.biasUs)} µs" +
                if (doa.note.isNotEmpty()) " · ${doa.note}" else "",
            MuteC, 9
        )
        Mono(
            "Angle is in the plane of the two microphones (the phone's long axis for a top/bottom pair): hold the phone sideways to " +
                "get left/right. Coarse: expect about ±10 to 20°.",
            MuteC, 9
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onCal) { Mono(if (doa.calibrating) "CALIBRATING…" else "CAL: clap straight ahead", SignalC) }
            TextButton(onClick = onClear) { Mono("CLEAR CAL", MuteC) }
        }
    }
}

/** Coincidence engine card. */
@Composable
fun CoincidenceCard(snap: CoincidenceEngine.Snapshot?, onReset: () -> Unit) {
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono("COINCIDENCE ENGINE · is it more than chance?", MuteC, 11)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onReset) { Mono("RESET", MuteC, 10) }
        }
        if (snap == null) {
            Mono("Collecting… it combines the magnetometer, audio, sonar, camera, passive novelty and magnetic-map channels.", MuteC)
            return@CardBox
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(66.dp)
        ) {
            val rows = snap.names.size
            val cw = size.width / 60f
            val rh = size.height / rows
            for (r in 0 until rows) {
                for (c in 0 until 60) {
                    if (snap.last60[r][c]) drawRect(AmberC, Offset(c * cw, r * rh), Size(cw * 0.9f, rh * 0.8f))
                    else drawRect(Color(0xFF232830), Offset(c * cw, r * rh), Size(cw * 0.9f, rh * 0.8f))
                }
            }
        }
        Mono(snap.names.joinToString(" · ") + "   (last 60 s, top to bottom)", MuteC, 9)
        Mono(
            "channels active together now: ${snap.k}   history ${snap.seconds / 60} min",
            if (snap.k >= 2) AmberC else SignalC
        )
        Mono(snap.verdict, if (snap.verdict.startsWith("MORE")) DangerC else if (snap.verdict.startsWith("more")) AmberC else FgC)
        if (snap.ready) {
            Mono(
                "2-channel episodes: ${snap.real2} real vs ${"%.1f".format(snap.exp2)} expected by chance (p=${"%.2f".format(snap.p2)})",
                MuteC, 9
            )
            Mono(
                "3-channel episodes: ${snap.real3} real vs ${"%.1f".format(snap.exp3)} expected by chance (p=${"%.2f".format(snap.p3)})",
                MuteC, 9
            )
        }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        for (e in snap.episodes.take(5)) {
            Mono(
                "${fmt.format(Date(e.startMs))} ${if (e.open) "●" else "○"} ${e.peakK} ch ${e.durSec}s ${e.channels}",
                if (e.peakK >= 3) AmberC else FgC, 10
            )
        }
        Mono(
            "Chance comes from time-shifting each channel against the others (150 shuffles). A common cause such as a door, a truck " +
                "or you walking still counts as a coincidence: 'not chance' never means 'not mundane'.",
            MuteC, 9
        )
    }
}
