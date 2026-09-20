package com.nscb.spiritscan.ui

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nscb.spiritscan.ScanViewModel
import com.nscb.spiritscan.engines.ArkEngine
import com.nscb.spiritscan.engines.HardeningState
import com.nscb.spiritscan.engines.ExplainState
import com.nscb.spiritscan.engines.QidaDecisionState
import com.nscb.spiritscan.engines.TrustState
import com.nscb.spiritscan.engines.NoiseSplitState
import com.nscb.spiritscan.entity.EntityOutput
import com.nscb.spiritscan.ui.diagnostics.NSCBDiagnostics
import com.nscb.spiritscan.ui.entity.EntityModeUI
import com.nscb.spiritscan.ui.entity.UltraEntityRing
import com.nscb.spiritscan.ui.entity.ultraColorForMode
import com.nscb.spiritscan.ui.hud.NSCBHud
import com.nscb.spiritscan.ui.modes.InterferenceModeUI
import com.nscb.spiritscan.ui.modes.JonesModeUI
import com.nscb.spiritscan.ui.modes.MagneticModeUI
import com.nscb.spiritscan.ui.modes.OmegaModeUI
import com.nscb.spiritscan.ui.modes.QidaModeUI
import com.nscb.spiritscan.ui.modes.ResidualModeUI
import com.nscb.spiritscan.ui.modes.ScanMode
import com.nscb.spiritscan.ui.modes.SdeModeUI
import com.nscb.spiritscan.ui.modes.SurveyModeUI
import com.nscb.spiritscan.ui.performance.NSCBPerformanceOverlay
import com.nscb.spiritscan.ui.vision.HeatOverlay
import com.nscb.spiritscan.ui.vision.JonesOverlay
import com.nscb.spiritscan.ui.vision.MagOverlay
import com.nscb.spiritscan.ui.vision.NightOverlay
import com.nscb.spiritscan.ui.vision.ObjectOverlay
import com.nscb.spiritscan.ui.vision.OmegaOverlay
import com.nscb.spiritscan.ui.vision.UvOverlay
import com.nscb.spiritscan.vision.DetectedObjectBox
import com.nscb.spiritscan.vision.SpiritObjectDetector
import java.util.concurrent.Executors
import kotlin.math.abs

private val Bg = Color(0xFF0B090B)
private val Surface = Color(0xFF12151A)
private val Card = Color(0xFF1A1E26)
private val Fg = Color(0xFFE8EAED)
private val Mute = Color(0xFF8B9196)
private val Signal = Color(0xFF709A8E)
private val Danger = Color(0xFFE85D4C)
private val Border = Color(0xFF2A303A)
private val ChipOff = Color(0xFF22262E)

enum class FilterMode(val label: String) {
    CAM("CAM"),
    HEAT("HEAT"),
    UV("UV"),
    MAG("MAG"),
    JONES("JONES"),
    OMEGA("OMEGA"),
    NIGHT("NIGHT"),
    RING("RING"),
    OBJ("OBJ")  // object detection boxes + residual plumes on objects
}

private fun filterStrength(mode: FilterMode, o: EntityOutput): Float = when (mode) {
    FilterMode.CAM, FilterMode.OBJ -> 0f
    FilterMode.HEAT -> (
        o.qida * 0.35f + o.residualLevel * 0.3f +
            (abs(o.zMag) / 12f).coerceIn(0f, 1f) * 0.25f
        ).coerceIn(0f, 1f)
    FilterMode.UV -> (
        (if (!o.sdeOk) 0.5f else 0f) + o.residualLevel * 0.3f
        ).coerceIn(0f, 1f)
    // MAG chip only glows when |B| is high (80+ scale)
    FilterMode.MAG -> ((o.magUt - 55f) / 40f).coerceIn(0f, 1f)
    FilterMode.JONES -> o.jonesScore.coerceIn(0f, 1f)
    FilterMode.OMEGA -> (1f - o.omegaTrust.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    FilterMode.NIGHT -> {
        val lux = o.survey.lux ?: 80f
        ((1f - (lux / 180f).coerceIn(0f, 1f)) * 0.5f + o.residualLevel * 0.5f).coerceIn(0f, 1f)
    }
    FilterMode.RING -> (o.qida * 0.5f + o.residualLevel * 0.5f).coerceIn(0f, 1f)
}

/**
 * Alerts are strict so normal house fields (~45–60 µT) do NOT fire.
 * MAG path only alerts when |B| >= 80.
 */
private fun isAnomalyAlert(o: EntityOutput): Boolean {
    val label = o.jonesLabel.lowercase()
    val highMag = o.magUt >= 80f
    val extremeZ = abs(o.zMag) >= 12f && (o.magUt < 30f || o.magUt > 80f)
    // SDE fail alone is common near wiring — do NOT alert on it by itself
    return highMag ||
        extremeZ ||
        o.residualLevel > 0.55f ||
        o.qida > 0.55f ||
        (o.jonesScore > 0.7f && (label.contains("unclass") || label.contains("entity") || label.contains("candidate"))) ||
        (label.contains("interference") && highMag) ||
        (!o.sdeOk && o.residualLevel > 0.4f && highMag)
}

private fun alertMessage(o: EntityOutput): String {
    val label = o.jonesLabel.lowercase()
    return when {
        o.magUt >= 80f ->
            "ALERT · HIGH FIELD ${"%.0f".format(o.magUt)} µT"
        abs(o.zMag) >= 10f ->
            "ALERT · EXTREME Z ${"%.1f".format(o.zMag)}"
        o.residualLevel > 0.55f ->
            "ALERT · RESIDUAL ${"%.2f".format(o.residualLevel)}"
        o.qida > 0.55f ->
            "ALERT · QIDA ${"%.2f".format(o.qida)}"
        label.contains("unclass") || label.contains("entity") || label.contains("candidate") ->
            "ALERT · ${o.jonesLabel}"
        !o.sdeOk && o.magUt >= 80f ->
            "ALERT · SDE + HIGH FIELD"
        else ->
            "ALERT · ANOMALY"
    }
}

@Composable
fun SpiritTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg, surface = Surface, onBackground = Fg, primary = Signal
        ),
        content = content
    )
}

@Composable
private fun CameraWithDetection(
    modifier: Modifier = Modifier,
    onObjects: (List<DetectedObjectBox>) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    // Throttle UI updates to ~4 Hz — stops screen shake from every-frame ML results
    val detector = remember {
        var lastMs = 0L
        SpiritObjectDetector { boxes ->
            val now = System.currentTimeMillis()
            if (now - lastMs >= 250L) {
                lastMs = now
                onObjects(boxes)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            detector.close()
            analysisExecutor.shutdown()
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(Unit) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(640, 480))
                .build()
                .also { it.setAnalyzer(analysisExecutor, detector) }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    AndroidView(modifier = modifier, factory = { previewView }, update = { })
}

@Composable
fun LiveHud(vm: ScanViewModel) {
    val output by vm.output.collectAsState()
    val boxOn by vm.boxOn.collectAsState()
    val walking by vm.walking.collectAsState()
    val sweep by vm.sweep.collectAsState()
    val currentMode by vm.currentMode.collectAsState()
    val fusion by vm.fusion.collectAsState()
    val hud by vm.hud.collectAsState()
    val diag by vm.diag.collectAsState()
    val perf by vm.perf.collectAsState()
    val ark by vm.ark.collectAsState()
    val noise by vm.noise.collectAsState()
    val hard by vm.hard.collectAsState()
    val trust by vm.trust.collectAsState()
    val qidaDec by vm.qidaDec.collectAsState()
    val explain by vm.explain.collectAsState()
    val ctx = LocalContext.current

    var filter by remember { mutableStateOf(FilterMode.HEAT) }
    var objects by remember { mutableStateOf<List<DetectedObjectBox>>(emptyList()) }

    val alert = isAnomalyAlert(output)
    val pulse = rememberInfiniteTransition(label = "pulse")
    val blink by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blink"
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(top = 28.dp)
    ) {
        if (alert) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Danger.copy(alpha = 0.25f + blink * 0.55f))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    alertMessage(output),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text("SpiritScan", color = Fg, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text("FILTER", color = Mute, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterMode.entries.forEach { m ->
                    val strength = filterStrength(m, output)
                    val selected = filter == m
                    val glow = if (strength > 0.25f) 0.4f + strength * 0.6f * blink
                    else if (selected) 0.85f else 0.35f
                    val bg = when {
                        selected && strength > 0.35f -> Danger.copy(alpha = glow)
                        selected -> Signal.copy(alpha = 0.85f)
                        strength > 0.35f -> Danger.copy(alpha = glow * 0.7f)
                        strength > 0.15f -> Signal.copy(alpha = 0.35f + strength * 0.4f)
                        else -> ChipOff
                    }
                    Button(
                        onClick = { filter = m },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = bg,
                            contentColor = if (strength > 0.3f || selected) Color.White else Mute
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(m.label, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(
                    width = if (alert) 2.dp else 1.dp,
                    color = if (alert) Danger.copy(alpha = blink) else Border,
                    shape = RoundedCornerShape(4.dp)
                )
        ) {
            CameraWithDetection(
                modifier = Modifier.fillMaxSize(),
                onObjects = { objects = it }
            )

            when (filter) {
                FilterMode.CAM -> {}
                FilterMode.HEAT -> HeatOverlay(output)
                FilterMode.UV -> UvOverlay(output)
                FilterMode.MAG -> MagOverlay(output)
                FilterMode.JONES -> JonesOverlay(output)
                FilterMode.OMEGA -> OmegaOverlay(output)
                FilterMode.NIGHT -> NightOverlay(output)
                FilterMode.RING -> {
                    val f = fusion
                    if (f != null) UltraEntityRing(output, f, ultraColorForMode(currentMode.name))
                }
                FilterMode.OBJ -> {
                    ObjectOverlay(boxes = objects, output = output, showPlumes = true)
                }
            }

            if (alert) {
                Box(Modifier.fillMaxSize().background(Danger.copy(alpha = 0.08f * blink)))
            }

            Text(
                "${filter.label} · objs ${objects.size}",
                color = Signal,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(0.6f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Card, RoundedCornerShape(8.dp))
                    .border(1.dp, if (alert) Danger.copy(alpha = 0.5f) else Border, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text("MODEL OUTPUTS", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(
                    "Jones ${output.jonesLabel} ${(output.jonesScore * 100).toInt()}%",
                    color = if (output.jonesScore > 0.7f) Danger else Fg,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp
                )
                Text(
                    "QIDA ${"%.2f".format(output.qida)}  Om ${"%.2f".format(output.omegaTrust)}  SDE ${"%.2f".format(output.sdeComposite)}",
                    color = Mute, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                )
                Text(
                    "|B| ${"%.2f".format(output.magUt)}  z ${"%.2f".format(output.zMag)}  res ${"%.2f".format(output.residualLevel)}",
                    color = Mute, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                )
                Text(
                    if (output.calibrated) "baseline locked  ·  MAG alert ≥ 80 µT"
                    else "IDLE — press ARM then CAL",
                    color = if (output.calibrated) Signal else Danger,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp
                )
            }

            // ARK Module 19
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Card, RoundedCornerShape(8.dp))
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text("ARK · MODULE 19", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                val a = ark
                if (a == null) {
                    Text("waiting for ARM…", color = Mute, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                } else {
                    Text(
                        "phase ${"%.3f".format(a.arkPhase)}  weight ${"%.3f".format(a.arkWeight)}  fusion ${"%.3f".format(a.fusionNorm)}",
                        color = Fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    )
                    Text(
                        "SDE drift ${"%.2f".format(a.sdeDrift)}  noise ${"%.2f".format(a.sdeNoise)}  res ${"%.2f".format(a.sdeResidual)}",
                        color = Mute, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    )
                    Text(
                        "Jones ${a.jonesName.uppercase()}  ·  drift ${a.driftName.uppercase()}  ·  ${a.alignName.uppercase()}",
                        color = Signal, fontFamily = FontFamily.Monospace, fontSize = 12.sp
                    )
                }
            }

            // NOISE SPLIT
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Card, RoundedCornerShape(8.dp))
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text("NOISE SPLIT", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                val n = noise
                if (n == null) {
                    Text("waiting for ARM…", color = Mute, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                } else {
                    Text(
                        "WIRE ${"%.0f".format(n.wire * 100)}%  MOTION ${"%.0f".format(n.motion * 100)}%  PHONE ${"%.0f".format(n.phone * 100)}%",
                        color = Fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    )
                    Text(
                        "RESIDUAL ${"%.0f".format(n.residual * 100)}%  ·  dominant ${n.dominant}",
                        color = if (n.dominant == "RESIDUAL") Danger else Signal,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                    Text(n.note, color = Mute, fontSize = 11.sp)
                }
            }

            // HARDENING (v81/v82 policy)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Card, RoundedCornerShape(8.dp))
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text("HARDENING", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                val h = hard
                if (h == null) {
                    Text("waiting for ARM…", color = Mute, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                } else {
                    Text(
                        "stable ${h.stableLabel} ${"%.0f".format(h.stableScore * 100)}%  gate ${"%.0f".format(h.hardeningScore * 100)}%",
                        color = Fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    )
                    Text(
                        if (h.threatConfirmed) "THREAT CONFIRMED" else if (h.mitigated) "MITIGATED" else "STABLE",
                        color = if (h.threatConfirmed) Danger else Signal,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                    Text(h.note, color = Mute, fontSize = 11.sp)
                }
            }

            // TRUST PROPAGATION (v3.5 lite)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Card, RoundedCornerShape(8.dp))
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text("TRUST", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                val tr = trust
                if (tr == null) {
                    Text("waiting for ARM…", color = Mute, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                } else {
                    Text(
                        "trust ${"%.0f".format(tr.trust * 100)}%  gate ${if (tr.gateOpen) "OPEN" else "BLOCKED"}  viol ${tr.violationCount}",
                        color = Fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    )
                    val flags = buildList {
                        if (tr.physicsViol) add("PHYS")
                        if (tr.behaviorViol) add("BEHAV")
                        if (tr.residualViol) add("RES")
                    }.joinToString(" ")
                    Text(
                        if (flags.isEmpty()) "no violations" else "violations: $flags",
                        color = if (flags.isEmpty()) Signal else Danger,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                    Text(tr.note, color = Mute, fontSize = 11.sp)
                }
            }

            // QIDA DECISION (Trust-Math / 3CAI lite)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Card, RoundedCornerShape(8.dp))
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text("QIDA DECISION", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                val qd = qidaDec
                if (qd == null) {
                    Text("waiting for ARM…", color = Mute, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                } else {
                    Text(
                        "state ${qd.primaryState}  conf ${"%.0f".format(qd.confidence * 100)}%  score ${"%.0f".format(qd.decisionScore * 100)}%",
                        color = Fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    )
                    Text(
                        if (qd.collapseOk) "COLLAPSE OK" else "HOLD",
                        color = if (qd.collapseOk) Signal else Danger,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                    Text(qd.note, color = Mute, fontSize = 11.sp)
                }
            }

            // EXPLAIN (DOD XAI lite)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Card, RoundedCornerShape(8.dp))
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text("EXPLAIN", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                val ex = explain
                if (ex == null) {
                    Text("waiting for ARM…", color = Mute, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                } else {
                    Text(
                        "primary ${ex.primary} ${"%.0f".format(ex.primaryConf * 100)}%  band ${ex.confidenceBand}",
                        color = Fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    )
                    Text(
                        "alt ${ex.alternative} ${"%.0f".format(ex.altConf * 100)}%  phys ${ex.physicsViolations}",
                        color = Mute, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    )
                    Text(ex.why, color = Fg, fontSize = 11.sp)
                    Text(ex.note, color = Mute, fontSize = 10.sp)
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Card, RoundedCornerShape(8.dp))
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text("SITE  ${output.survey.activity.uppercase()}", color = Fg, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text(output.survey.note, color = Mute, fontSize = 11.sp)
                if (objects.isNotEmpty()) {
                    Text(
                        "OBJECTS  ${objects.joinToString { it.label }}",
                        color = Signal, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    )
                }
            }

            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Text("SWEEP ", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                com.nscb.spiritscan.sensor.SweepMode.entries.forEach { m ->
                    TextButton(onClick = { vm.setSweep(m) }) {
                        Text(m.name, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (sweep == m) Signal else Mute)
                    }
                }
            }

            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Text("MODE ", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                ScanMode.entries.forEach { m ->
                    TextButton(onClick = { vm.setMode(m) }) {
                        Text(m.name, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (currentMode == m) Signal else Mute)
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().background(Card, RoundedCornerShape(8.dp)).border(1.dp, Border, RoundedCornerShape(8.dp)).padding(10.dp)
            ) {
                when (currentMode) {
                    ScanMode.JONES -> JonesModeUI(output)
                    ScanMode.MAGNETIC -> MagneticModeUI(output)
                    ScanMode.QIDA -> QidaModeUI(output)
                    ScanMode.OMEGA -> OmegaModeUI(output)
                    ScanMode.SDE -> SdeModeUI(output)
                    ScanMode.RESIDUAL -> ResidualModeUI(output)
                    ScanMode.INTERFERENCE -> InterferenceModeUI(output)
                    ScanMode.SURVEY -> SurveyModeUI(output)
                    ScanMode.ENTITY -> EntityModeUI(output)
                }
            }

            Column(
                Modifier.fillMaxWidth().background(Card, RoundedCornerShape(8.dp)).border(1.dp, Border, RoundedCornerShape(8.dp)).padding(10.dp)
            ) {
                NSCBHud(hud)
                NSCBDiagnostics(diag)
                NSCBPerformanceOverlay(perf)
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(
            Modifier.fillMaxWidth().background(Surface).padding(horizontal = 8.dp, vertical = 8.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(onClick = { vm.arm(ctx) }, modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Signal)) { Text("ARM", fontSize = 14.sp) }
            Button(onClick = { vm.calibrate() }, modifier = Modifier.weight(1f).height(48.dp)) { Text("CAL", fontSize = 14.sp) }
            Button(onClick = { vm.toggleBox() }, modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (boxOn) Signal else ChipOff)) {
                Text(if (boxOn) "BOX*" else "BOX", fontSize = 14.sp)
            }
            Button(onClick = { vm.toggleWalk() }, modifier = Modifier.weight(1f).height(48.dp)) {
                Text(if (walking) "STOP" else "WALK", fontSize = 14.sp)
            }
        }
    }
}
