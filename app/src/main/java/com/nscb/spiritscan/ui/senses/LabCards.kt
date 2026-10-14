package com.nscb.spiritscan.ui.senses

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nscb.spiritscan.senses.ParticleEngine
import com.nscb.spiritscan.trial.HarnessState
import kotlin.math.roundToInt

/** Covered-camera particle detector card with a gallery of the last captured events. */
@Composable
fun ParticleCard(
    snap: ParticleEngine.Snapshot?,
    info: String,
    err: String,
    on: Boolean,
    onToggle: () -> Unit,
    onReset: () -> Unit
) {
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono("PARTICLE DETECTOR · covered camera", MuteC, 11)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggle) { Mono(if (on) "STOP" else "START", if (on) DangerC else SignalC, 11) }
        }
        if (err.isNotEmpty()) Mono(err, DangerC)
        if (!on || snap == null) {
            Mono(
                "Cosmic-ray style detector (the idea behind DECO, CRAYFIS and CREDO). Cover the MAIN camera lens completely with " +
                    "opaque tape, plug the phone in and leave it still. Long exposures show particle hits as small bright clusters: " +
                    "tracks (muon-like), worms (electron or gamma-like), spots and blobs. The HUD camera pauses while this runs. " +
                    "Rates are low, a few events a minute at most, so it needs minutes to hours. For a quick test put uranium glass " +
                    "or an old orange Fiestaware plate next to the lens (low-level household items only; never handle radium).",
                MuteC, 10
            )
            return@CardBox
        }
        Mono(snap.status, if (snap.lightLeak) DangerC else SignalC)
        if (info.isNotEmpty()) Mono(info, MuteC, 9)
        Mono(
            "dark level ${"%.0f".format(snap.medianDN)} DN · noise ${"%.1f".format(snap.sigmaDN)} DN · hot pixels masked ${snap.hotMasked} · frames ${snap.frames}",
            MuteC, 9
        )
        Mono(
            "particle-like events ${snap.particleLike}: tracks ${snap.tracks} · worms ${snap.worms} · spots ${snap.spots} · blobs ${snap.blobs}" +
                "   (single bright pixels ${snap.singles} are not counted)",
            if (snap.particleLike > 0) FgC else MuteC, 10
        )
        val base = if (snap.baselinePerMin.isNaN()) "needs ~25 min" else "${"%.2f".format(snap.baselinePerMin)}/min"
        Mono(
            "last 5 min ${"%.2f".format(snap.rate5PerMin)}/min · long-term baseline $base · elapsed ${"%.0f".format(snap.elapsedMin)} min",
            MuteC, 9
        )
        if (snap.elevated) {
            Mono("RADIATION ELEVATED: more events than the long-term rate predicts (p=${"%.4f".format(snap.pElevated)})", DangerC)
        }
        if (snap.burst) Mono("BURST: 3+ events within 3 s (a shower or a source is close)", AmberC)
        if (snap.recent.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                for (e in snap.recent.take(10)) {
                    val bmp = remember(e) { Bitmap.createBitmap(e.thumb, ParticleEngine.THUMB, ParticleEngine.THUMB, Bitmap.Config.ARGB_8888) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Canvas(Modifier.size(48.dp)) {
                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstOffset = IntOffset(0, 0),
                                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                                filterQuality = FilterQuality.None
                            )
                        }
                        Text(e.kind, color = MuteC, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                        Text("${e.n}px", color = MuteC, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Mono("newest first · each tile is 16x16 sensor pixels around the hit", MuteC, 9)
        }
        TextButton(onClick = onReset) { Mono("RESET COUNTS", SignalC) }
    }
}

/** Ground-truth harness card. */
@Composable
fun HarnessCard(
    names: List<String>,
    st: HarnessState,
    onStart: (Int) -> Unit,
    onStop: () -> Unit
) {
    var idx by remember { mutableIntStateOf(0) }
    CardBox {
        Mono("GROUND-TRUTH CHECK · what can each channel really detect?", MuteC, 11)
        if (st.phase == 0) {
            Mono(
                "Pick a channel, then follow the prompts: the app mixes planted stimuli at several strengths with quiet 'sham' windows in " +
                    "random order and scores how often the channel responded to each. You are told what comes next, so this measures " +
                    "sensitivity and false alarms, it is not a blinded test. Keep the room otherwise quiet.",
                MuteC, 10
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { idx = (idx + 1) % names.size }) { Mono("CHANNEL: ${names[idx]}", SignalC, 11) }
                TextButton(onClick = { onStart(idx) }) { Mono("START", AmberC, 11) }
            }
            if (st.message.isNotEmpty()) Mono(st.message, DangerC, 10)
        } else if (st.phase == 1) {
            Mono("${st.protocol} · window ${st.window}/${st.windows}", MuteC, 10)
            Text(
                st.prompt,
                color = if (st.getReady) AmberC else FgC,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
            Mono(if (st.getReady) "starts in ${st.secondsLeft} s" else "${st.secondsLeft} s left", MuteC, 10)
            TextButton(onClick = onStop) { Mono("ABORT", DangerC, 11) }
        } else {
            Mono(st.protocol, FgC, 11)
            for (r in st.rows) Mono("${r.label}: ${r.text}", FgC, 10)
            Spacer(Modifier.height(4.dp))
            Mono(st.shamLine, MuteC, 9)
            Mono(st.verdict, AmberC, 10)
            if (st.savedPath.isNotEmpty()) Mono("saved: ${st.savedPath}", MuteC, 9)
            TextButton(onClick = onStop) { Mono("NEW CHECK", SignalC, 11) }
        }
    }
}
