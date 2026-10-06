package com.nscb.spiritscan.ui.scan

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nscb.spiritscan.dsp.ScanSnapshot
import kotlin.math.ln
import kotlin.math.roundToInt

private val CardC = Color(0xFF1A1E26)
private val BorderC = Color(0xFF2A303A)
private val FgC = Color(0xFFE8EAED)
private val MuteC = Color(0xFF8B9196)
private val SignalC = Color(0xFF709A8E)
private val DangerC = Color(0xFFE85D4C)
private val AmberC = Color(0xFFFFB020)

private fun fmtHz(hz: Float): String =
    if (hz >= 1000f) "${"%.1f".format(hz / 1000f)} kHz" else "${hz.roundToInt()} Hz"

/**
 * Spirit Box full-band scan card: log-frequency waterfall (colour = dB above each band's own learned floor),
 * anomaly list, voice-signature meters and the NULL TEST baseline.
 */
@Composable
fun ScanCard(
    snap: ScanSnapshot?,
    on: Boolean,
    error: String,
    onToggle: () -> Unit,
    onNull: () -> Unit,
    onClearNull: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(CardC, RoundedCornerShape(8.dp))
            .border(1.dp, BorderC, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SPIRIT BOX · FULL-BAND SCAN", color = MuteC,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggle) {
                Text(
                    if (on) "STOP" else "START SCAN",
                    color = if (on) DangerC else SignalC,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }
        }
        if (error.isNotEmpty()) {
            Text(error, color = DangerC, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        if (!on || snap == null) {
            Text(
                "20 Hz to 23 kHz waterfall with per-band learned floors, ultrasonic bands, mains-hum tagging, " +
                    "and a voice-signature detector (pitch, formants, continuity). Phones have no AM/FM tuner, so the " +
                    "SCAN and GLIDE sweeps are synthesised. Run NULL TEST first in a quiet room.",
                color = MuteC, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
            return@Column
        }

        val bmp = remember(snap.waterfall) {
            Bitmap.createBitmap(snap.waterfall, snap.bands, snap.histRows, Bitmap.Config.ARGB_8888)
        }
        val paint = remember {
            Paint().apply {
                isAntiAlias = true
                typeface = Typeface.MONOSPACE
                color = android.graphics.Color.LTGRAY
            }
        }
        val anoms = snap.anomalies
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
        ) {
            drawImage(
                image = bmp.asImageBitmap(),
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.None
            )
            val span = ln(snap.fMaxHz / snap.fMinHz)
            for (a in anoms) {
                val x = size.width * (ln(a.centerHz / snap.fMinHz) / span)
                val col = if (a.explained) AmberC else DangerC
                drawLine(col, Offset(x, 0f), Offset(x, 14f), strokeWidth = 3f)
            }
            paint.textSize = 9.sp.toPx()
            val marks = floatArrayOf(50f, 100f, 500f, 1000f, 5000f, 10000f, 20000f)
            for (m in marks) {
                if (m < snap.fMinHz || m > snap.fMaxHz) continue
                val x = size.width * (ln(m / snap.fMinHz) / span)
                drawLine(Color(0x55FFFFFF), Offset(x, size.height - 10f), Offset(x, size.height), strokeWidth = 1f)
                val label = if (m >= 1000f) "${(m / 1000f).toInt()}k" else "${m.toInt()}"
                drawIntoCanvas { it.nativeCanvas.drawText(label, x + 2f, size.height - 2f, paint) }
            }
        }
        Text(
            "colour = dB above each band's own floor · newest at top · ticks = anomalies",
            color = MuteC, fontSize = 9.sp, fontFamily = FontFamily.Monospace
        )

        val src = "${snap.wideSource} | ${snap.voiceSource}"
        Text(src, color = MuteC, fontSize = 9.sp, fontFamily = FontFamily.Monospace)

        Spacer(Modifier.height(4.dp))
        if (!snap.wideReady) {
            Text("learning band floors…", color = MuteC, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        } else if (anoms.isEmpty()) {
            Text("BANDS · no departures from learned floors", color = SignalC, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        } else {
            for (a in anoms.sortedByDescending { it.excessDb }.take(5)) {
                val col = if (a.explained) AmberC else DangerC
                Text(
                    "${if (a.active) "●" else "○"} ${fmtHz(a.centerHz)} ${a.kind} +${"%.0f".format(a.excessDb)}dB " +
                        "${"%.1f".format(a.ageSec)}s ${a.cause}",
                    color = col, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        val v = snap.voice
        if (!snap.voiceReady) {
            Text("VOICE · starting…", color = MuteC, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        } else {
            val vcol = if (v.voicedNow) AmberC else SignalC
            Text(
                "VOICE-SIGNATURE ${(v.score * 100).toInt()}%  ${if (v.voicedNow) "VOICED run ${v.voicedRun}" else "no voiced structure"}",
                color = vcol, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
            Text(
                "F0 ${if (v.f0 > 0f && v.voicedNow) "${v.f0.roundToInt()} Hz" else "-"}  " +
                    "F1 ${if (v.f1 > 0f) v.f1.roundToInt() else 0}  F2 ${if (v.f2 > 0f) v.f2.roundToInt() else 0}  " +
                    "CPP ${"%.2f".format(v.cppDb)}  mod ${"%.0f".format(v.modRatioDb)}dB  lvl ${"%.0f".format(v.levelDb)}dBFS",
                color = MuteC, fontSize = 9.sp, fontFamily = FontFamily.Monospace
            )
            if (v.eventsTotal > 0) {
                Text(
                    "last voice-like event ${"%.0f".format(v.secSinceEvent)}s ago · F0 ${v.lastF0.roundToInt()} Hz · " +
                        "${"%.2f".format(v.lastDur)}s",
                    color = MuteC, fontSize = 9.sp, fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = onNull) {
                Text("NULL TEST", color = SignalC, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            if (snap.nullState != 0) {
                TextButton(onClick = onClearNull) {
                    Text("CLEAR", color = MuteC, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        when (snap.nullState) {
            1 -> Text(
                "NULL TEST collecting ${(snap.nullProgress * 100).toInt()}% — keep the room quiet, box running",
                color = AmberC, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
            2 -> {
                val vc = if (snap.voiceAbove) DangerC else SignalC
                val bc = if (snap.bandAbove) DangerC else SignalC
                Text(
                    "voice-like events 60s: ${snap.voiceEvents60} vs baseline ${"%.1f".format(snap.baseVoicePerMin)}/min " +
                        "z=${"%.1f".format(snap.voiceZ)} ${if (snap.voiceAbove) "ABOVE BASELINE" else "within baseline"}",
                    color = vc, fontSize = 9.sp, fontFamily = FontFamily.Monospace
                )
                Text(
                    "band anomalies 60s: ${snap.bandEvents60} vs baseline ${"%.1f".format(snap.baseBandPerMin)}/min " +
                        "z=${"%.1f".format(snap.bandZ)} ${if (snap.bandAbove) "ABOVE BASELINE" else "within baseline"}",
                    color = bc, fontSize = 9.sp, fontFamily = FontFamily.Monospace
                )
            }
            else -> Text(
                "No baseline yet. Without one, any event rate here means nothing: run NULL TEST in a quiet room.",
                color = MuteC, fontSize = 9.sp, fontFamily = FontFamily.Monospace
            )
        }
    }
}
