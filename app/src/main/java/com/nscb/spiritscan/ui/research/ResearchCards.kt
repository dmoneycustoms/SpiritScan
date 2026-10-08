package com.nscb.spiritscan.ui.research

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nscb.spiritscan.dsp.NoveltyCore
import com.nscb.spiritscan.dsp.NoveltySnapshot
import com.nscb.spiritscan.feeds.ContextSnapshot
import com.nscb.spiritscan.trial.TrialState

private val CardC = Color(0xFF1A1E26)
private val BorderC = Color(0xFF2A303A)
private val MuteC = Color(0xFF8B9196)
private val FgC = Color(0xFFE8EAED)
private val SignalC = Color(0xFF709A8E)
private val DangerC = Color(0xFFE85D4C)
private val AmberC = Color(0xFFFFB020)
private val CyanC = Color(0xFF3DE8FF)

@Composable
private fun CardBox(content: @Composable () -> Unit) {
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
private fun Mono(text: String, color: Color = MuteC, size: Int = 10) {
    Text(text, color = color, fontSize = size.sp, fontFamily = FontFamily.Monospace)
}

/** Live context: what the outside world is doing right now. */
@Composable
fun ContextCard(
    snap: ContextSnapshot,
    explain: String,
    locationText: String,
    onRefresh: () -> Unit,
    onSetLocation: (Double, Double) -> Unit
) {
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono("LIVE CONTEXT · explains, never detects", MuteC, 11)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Mono("REFRESH", SignalC) }
        }
        val kp = snap.kp
        Mono(
            if (kp == null) "Geomagnetic Kp: no data yet"
            else "Geomagnetic Kp ${"%.1f".format(kp)}${if (snap.kpAgeMin != null) " (${snap.kpAgeMin} min old)" else ""}",
            if (kp != null && kp >= 4f) AmberC else FgC
        )
        if (snap.hasLocation) {
            val sb = StringBuilder("Weather ")
            if (snap.tempC != null) sb.append("${"%.0f".format(snap.tempC)}°C ")
            if (snap.humidity != null) sb.append("${"%.0f".format(snap.humidity)}% RH ")
            if (snap.pressureHpa != null) sb.append("${"%.0f".format(snap.pressureHpa)} hPa ")
            if (snap.windKmh != null) sb.append("wind ${"%.0f".format(snap.windKmh)}")
            if (snap.gustKmh != null) sb.append("/${"%.0f".format(snap.gustKmh)} km/h")
            Mono(sb.toString(), FgC)
        } else {
            Mono("Weather: enter your latitude and longitude below (kept on this phone)", MuteC)
        }
        if (snap.quakeMag != null && snap.quakeAgoMin != null) {
            Mono(
                "Last M4.5+ quake: M${"%.1f".format(snap.quakeMag)} ${snap.quakeAgoMin} min ago" +
                    (if (snap.quakeKm != null) ", ${snap.quakeKm} km away" else "") +
                    (if (!snap.quakePlace.isNullOrEmpty()) " · ${snap.quakePlace}" else ""),
                MuteC, 9
            )
        }
        Spacer(Modifier.height(4.dp))
        Mono("would explain an event now: $explain", if (explain.startsWith("no ")) SignalC else AmberC)
        if (snap.error.isNotEmpty()) Mono("feed problems: ${snap.error}", DangerC, 9)
        Spacer(Modifier.height(4.dp))
        Mono("location: $locationText", MuteC, 9)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = lat, onValueChange = { lat = it }, singleLine = true,
                label = { Mono("lat", MuteC, 9) }, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = lon, onValueChange = { lon = it }, singleLine = true,
                label = { Mono("lon", MuteC, 9) }, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                val a = lat.trim().toDoubleOrNull()
                val b = lon.trim().toDoubleOrNull()
                if (a != null && b != null) onSetLocation(a, b)
            }) { Mono("SET", SignalC) }
        }
    }
}

/** Passive ingest + ONNX novelty. */
@Composable
fun PassiveCard(
    nov: NoveltySnapshot?,
    on: Boolean,
    onToggle: () -> Unit,
    onRelearn: () -> Unit,
    onClear: () -> Unit
) {
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono("PASSIVE INGEST · ONNX NOVELTY", MuteC, 11)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggle) {
                Mono(if (on) "STOP" else "START", if (on) DangerC else SignalC, 11)
            }
        }
        if (!on || nov == null) {
            Mono(
                "Watches 12 channels once a second (magnetometer, audio, camera, band scan, voice score, AIR). It first learns " +
                    "a 5 minute baseline of what is normal in this room, then an ONNX autoencoder and a statistical check score " +
                    "every second against it. 'Novel' means unlike the baseline: a door, a phone call and a lamp all count.",
                MuteC, 10
            )
            return@CardBox
        }
        when (nov.phase) {
            1 -> Mono(
                "learning baseline ${(nov.learnProgress * 100).toInt()}% · leave the room as it normally is",
                AmberC
            )
            2 -> Mono("calibrating the model on the baseline…", AmberC)
            3 -> {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                ) {
                    val lo = -2f
                    val hi = 12f
                    fun yOf(v: Float): Float {
                        val c = v.coerceIn(lo, hi)
                        return size.height * (1f - (c - lo) / (hi - lo))
                    }
                    val ty = yOf(NoveltyCore.OPEN_Z.toFloat())
                    drawLine(Color(0x66E85D4C), Offset(0f, ty), Offset(size.width, ty), strokeWidth = 1f)
                    val n = nov.aeHist.size
                    if (n > 1) {
                        val dx = size.width / 599f
                        val x0 = size.width - (n - 1) * dx
                        for (i in 1 until n) {
                            drawLine(
                                Color(0xFFFFB020),
                                Offset(x0 + (i - 1) * dx, yOf(nov.mahaHist[i - 1])),
                                Offset(x0 + i * dx, yOf(nov.mahaHist[i])), strokeWidth = 1.5f
                            )
                            drawLine(
                                CyanC,
                                Offset(x0 + (i - 1) * dx, yOf(nov.aeHist[i - 1])),
                                Offset(x0 + i * dx, yOf(nov.aeHist[i])), strokeWidth = 1.5f
                            )
                        }
                    }
                }
                Mono("cyan = ONNX model · amber = statistics · red line = event threshold (last 10 min)", MuteC, 9)
                val hot = nov.aeZ >= NoveltyCore.OPEN_Z || nov.mahaZ >= NoveltyCore.OPEN_Z
                Mono(
                    "MODEL ${"%.1f".format(nov.aeZ)}${if (!nov.aeAvailable) " (unavailable)" else ""}  " +
                        "STATS ${"%.1f".format(nov.mahaZ)}" +
                        (if (nov.topChannels.isNotEmpty()) "  ← ${nov.topChannels}" else ""),
                    if (hot) AmberC else SignalC
                )
                val sb = StringBuilder()
                for (i in nov.names.indices) {
                    sb.append(nov.names[i]).append('=')
                    if (nov.active[i]) sb.append("%.2f".format(nov.latest[i])) else sb.append("·")
                    sb.append(if ((i + 1) % 3 == 0) "\n" else "  ")
                }
                Mono(sb.toString().trimEnd(), MuteC, 9)
            }
        }
        if (nov.events.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Mono("CAPTURED", FgC, 10)
            for (e in nov.events.take(5)) {
                Mono(
                    "#${e.id} ${if (e.open) "●" else "○"} ${e.kind} peak ${"%.1f".format(e.peakZ)} " +
                        "${"%.0f".format(e.durSec)}s [${e.channels}]",
                    if (e.open) AmberC else FgC, 10
                )
                if (e.context.isNotEmpty()) Mono("   context: ${e.context}", MuteC, 9)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRelearn) { Mono("RE-LEARN BASELINE", SignalC) }
            TextButton(onClick = onClear) { Mono("CLEAR", MuteC) }
        }
    }
}

/** Sham-controlled trial runner. */
@Composable
fun TrialCard(
    st: TrialState,
    onStart: (Int, Int) -> Unit,
    onStop: () -> Unit,
    onMark: () -> Unit
) {
    val blockChoices = listOf(6, 8, 10, 12)
    val minChoices = listOf(2, 3, 5)
    var bi by remember { mutableIntStateOf(2) }
    var mi by remember { mutableIntStateOf(0) }
    CardBox {
        Mono("SHAM-CONTROLLED TRIAL", MuteC, 11)
        if (st.phase == 0) {
            Mono(
                "Alternates box ON and box OFF in a random, balanced, pre-committed order, counts voice-like events in each block " +
                    "and compares them with an exact permutation test. Choose the SWEEP mode first. The scan starts automatically. " +
                    "Primary metric: voice-like events per minute.",
                MuteC, 10
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { bi = (bi + 1) % blockChoices.size }) { Mono("${blockChoices[bi]} blocks", SignalC) }
                TextButton(onClick = { mi = (mi + 1) % minChoices.size }) { Mono("${minChoices[mi]} min each", SignalC) }
                TextButton(onClick = { onStart(blockChoices[bi], minChoices[mi] * 60) }) { Mono("START TRIAL", AmberC, 11) }
            }
            Mono(
                "total ${blockChoices[bi] * minChoices[mi]} min. Keep the room quiet and still; do not touch the phone.",
                MuteC, 9
            )
            if (st.message.isNotEmpty()) Mono(st.message, MuteC, 9)
        } else if (st.phase == 1) {
            Mono("${st.message} · ${st.secondsLeft}s left · marks ${st.marks}", AmberC)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onMark) { Mono("MARK: I heard something", FgC, 11) }
                TextButton(onClick = onStop) { Mono("ABORT", DangerC, 11) }
            }
        } else {
            for (r in st.results) {
                Mono(
                    "${r.name}: ON ${"%.2f".format(r.liveRate)} vs OFF ${"%.2f".format(r.shamRate)}  p=${"%.3f".format(r.p)}",
                    if (r.p < 0.05) AmberC else FgC, 10
                )
            }
            Spacer(Modifier.height(4.dp))
            Mono(st.narrative, FgC, 10)
            if (st.savedPath.isNotEmpty()) Mono("saved: ${st.savedPath}", MuteC, 9)
            TextButton(onClick = onStop) { Mono("NEW TRIAL", SignalC, 11) }
        }
    }
}

/** Tiny readout on the camera HUD while PASSIVE is running. */
@Composable
fun BoxScope.NoveltyStrip(nov: NoveltySnapshot?) {
    if (nov == null || nov.phase != 3) return
    val hot = nov.aeZ >= NoveltyCore.OPEN_Z || nov.mahaZ >= NoveltyCore.OPEN_Z
    Text(
        "NOV m${"%.1f".format(nov.aeZ)} s${"%.1f".format(nov.mahaZ)}",
        color = if (hot) AmberC else SignalC,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .background(Color.Black.copy(0.6f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}
