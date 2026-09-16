package com.nscb.spiritscan.ui

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nscb.spiritscan.ScanViewModel
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
import com.nscb.spiritscan.ui.vision.OmegaOverlay
import com.nscb.spiritscan.ui.vision.UvOverlay

private val Bg = Color(0xFF0B090B)
private val Surface = Color(0xFF12151A)
private val Card = Color(0xFF1A1E26)
private val Fg = Color(0xFFE8EAED)
private val Mute = Color(0xFF8B9196)
private val Signal = Color(0xFF709A8E)
private val Danger = Color(0xFFC47A72)
private val Border = Color(0xFF2A303A)
private val ChipOn = Color(0xFF1E3A34)
private val ChipOff = Color(0xFF1A1E26)

/** Each filter is tied to a model path. */
enum class FilterMode(val label: String, val modelHint: String) {
    CAM("CAM", "raw"),
    HEAT("HEAT", "QIDA+residual"),
    UV("UV", "SDE"),
    MAG("MAG", "magnetometer"),
    JONES("JONES", "Jones HV"),
    OMEGA("OMEGA", "Omega trust"),
    NIGHT("NIGHT", "lux+residual"),
    RING("RING", "fusion")
}

@Composable
fun SpiritTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg,
            surface = Surface,
            onBackground = Fg,
            primary = Signal
        ),
        content = content
    )
}

@Composable
private fun CameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    LaunchedEffect(Unit) {
        try {
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    AndroidView(modifier = modifier, factory = { previewView })
}

@Composable
private fun FilterChip(mode: FilterMode, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) ChipOn else ChipOff,
            contentColor = if (selected) Signal else Mute
        ),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.height(32.dp)
    ) {
        Text(mode.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DataCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        content = {
            Text(title, color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            content()
        }
    )
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
    val ctx = LocalContext.current

    var filter by remember { mutableStateOf(FilterMode.HEAT) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
    ) {
        // ===== TOP CONTROLS — compact, always visible =====
        Column(
            Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SpiritScan", color = Fg, fontSize = 16.sp)
                Text(
                    filter.modelHint,
                    color = Signal,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Action buttons
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = { vm.arm(ctx) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(34.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Signal)
                ) { Text("Arm", fontSize = 12.sp) }
                Button(
                    onClick = { vm.calibrate() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(34.dp)
                ) { Text("Cal 8s", fontSize = 12.sp) }
                Button(
                    onClick = { vm.toggleBox() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(34.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (boxOn) Signal else ChipOff
                    )
                ) { Text(if (boxOn) "Box ON" else "Box", fontSize = 12.sp) }
                Button(
                    onClick = { vm.toggleWalk() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(34.dp)
                ) { Text(if (walking) "Stop" else "Walk", fontSize = 12.sp) }
            }

            Spacer(Modifier.height(4.dp))

            // FILTER row — each tied to a model path
            Text("FILTER (model-driven)", color = Mute, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterMode.entries.forEach { m ->
                    FilterChip(m, selected = filter == m) { filter = m }
                }
            }

            // Sweep modes for spirit box
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SWEEP", color = Mute, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                com.nscb.spiritscan.sensor.SweepMode.entries.forEach { m ->
                    TextButton(
                        onClick = { vm.setSweep(m) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text(
                            m.name,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (sweep == m) Signal else Mute
                        )
                    }
                }
            }
        }

        // ===== CAMERA — fixed square-ish box aligned to 8x grid =====
        // 176dp ≈ readable on S23 and matches 8-cell grid overlays
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(176.dp)
                .border(1.dp, Border, RoundedCornerShape(4.dp))
        ) {
            CameraPreview(Modifier.fillMaxSize())

            when (filter) {
                FilterMode.CAM -> { /* raw only */ }
                FilterMode.HEAT -> HeatOverlay(output)
                FilterMode.UV -> UvOverlay(output)
                FilterMode.MAG -> MagOverlay(output)
                FilterMode.JONES -> JonesOverlay(output)
                FilterMode.OMEGA -> OmegaOverlay(output)
                FilterMode.NIGHT -> NightOverlay(output)
                FilterMode.RING -> {
                    val f = fusion
                    if (f != null) {
                        UltraEntityRing(output, f, ultraColorForMode(currentMode.name))
                    }
                }
            }

            // Filter label + live model hint on camera
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(filter.label, color = Signal, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(filter.modelHint, color = Mute, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // ===== DATA (scrollable) =====
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DataCard("MODEL OUTPUTS") {
                Text(
                    "Jones ${output.jonesLabel}  ${(output.jonesScore * 100).toInt()}%",
                    color = Fg, fontFamily = FontFamily.Monospace, fontSize = 12.sp
                )
                Text(
                    "QIDA ${"%.2f".format(output.qida)}  Omega ${"%.2f".format(output.omegaTrust)}  SDE ${"%.2f".format(output.sdeComposite)} ${if (output.sdeOk) "ok" else "FAIL"}",
                    color = if (output.sdeOk) Mute else Danger,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Text(
                    "|B| ${"%.2f".format(output.magUt)} µT  z ${"%.2f".format(output.zMag)}  residual ${"%.2f".format(output.residualLevel)}",
                    color = Mute, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                )
                Text(
                    if (output.calibrated) "baseline locked" else "calibrating ${(output.calProgress * 100).toInt()}%",
                    color = if (output.calibrated) Signal else Mute,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }

            DataCard("SITE") {
                Text(
                    output.survey.activity.uppercase(),
                    color = if (output.survey.activity.contains("unclass", true)) Danger else Fg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                Text(output.survey.note, color = Mute, fontSize = 11.sp)
            }

            DataCard("HUD") { NSCBHud(hud) }

            // Mode detail (optional deep dive)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ScanMode.entries.forEach { m ->
                    TextButton(onClick = { vm.setMode(m) }) {
                        Text(
                            m.name,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (currentMode == m) Signal else Mute
                        )
                    }
                }
            }

            DataCard("MODE · ${currentMode.name}") {
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

            DataCard("DIAG") {
                NSCBDiagnostics(diag)
                NSCBPerformanceOverlay(perf)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
