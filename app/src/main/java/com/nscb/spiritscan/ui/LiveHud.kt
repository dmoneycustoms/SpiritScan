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
import com.nscb.spiritscan.ui.entity.UltraCorners
import com.nscb.spiritscan.ui.entity.UltraEntityRing
import com.nscb.spiritscan.ui.entity.UltraGridOverlay
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
import com.nscb.spiritscan.ui.shader.LiveShader
import com.nscb.spiritscan.ui.vision.HeatOverlay
import com.nscb.spiritscan.ui.vision.UvOverlay
import com.nscb.spiritscan.ui.vision.MagOverlay
import com.nscb.spiritscan.ui.vision.NightOverlay

private val Bg = Color(0xFF0B090B)
private val Surface = Color(0xFF15181E)
private val Card = Color(0xFF1A1E26)
private val Fg = Color(0xFFE8EAED)
private val Mute = Color(0xFF8B9196)
private val Signal = Color(0xFF709A8E)
private val Danger = Color(0xFFC47A72)
private val Border = Color(0xFF2A303A)

enum class VisionMode {
    NORMAL,    // camera only
    HEAT,      // CMOS-style ironbow residual
    UV,        // cool UV false-color
    MAG,       // magnetic field overlay
    NIGHT,     // low-light green phosphor
    GRID,      // grid + corners
    RING,      // entity ring
    RESIDUAL,  // shader residual layer
    FULL       // all overlays
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

    val cameraProviderFuture = remember {
        ProcessCameraProvider.getInstance(context)
    }

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
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (selected) Signal else Mute
        )
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = if (selected) Signal else Mute
        )
    }
}

@Composable
private fun DataCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            Text(title, color = Mute, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
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

    var vision by remember { mutableStateOf(VisionMode.FULL) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
    ) {
        // ===== COMPACT TOP BAR (always visible) =====
        Column(
            Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SpiritScan", color = Fg, fontSize = 18.sp)
                Text("v8.3", color = Mute, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }

            // Primary actions — compact
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { vm.arm(ctx) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Signal)
                ) { Text("Arm", fontSize = 12.sp) }

                Button(
                    onClick = { vm.calibrate() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) { Text("Cal 8s", fontSize = 12.sp) }

                Button(
                    onClick = { vm.toggleBox() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (boxOn) Signal else Surface
                    )
                ) { Text(if (boxOn) "Box ON" else "Box", fontSize = 12.sp) }

                Button(
                    onClick = { vm.toggleWalk() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) { Text(if (walking) "Stop" else "Walk", fontSize = 12.sp) }

                TextButton(onClick = { vm.resetSurvey() }) {
                    Text("Reset", color = Mute, fontSize = 11.sp)
                }
            }

            // Vision mode row — changes what draws on the camera
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("VIEW ", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                VisionMode.entries.forEach { m ->
                    Chip(
                        label = m.name,
                        selected = vision == m,
                        onClick = { vision = m }
                    )
                }
            }

            // Scan mode row
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MODE ", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                ScanMode.entries.forEach { m ->
                    Chip(
                        label = m.name,
                        selected = currentMode == m,
                        onClick = { vm.setMode(m) }
                    )
                }
            }

            // Sweep row (only when box relevant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SWEEP", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                com.nscb.spiritscan.sensor.SweepMode.entries.forEach { m ->
                    Chip(
                        label = m.name,
                        selected = sweep == m,
                        onClick = { vm.setSweep(m) }
                    )
                }
            }
        }

        // ===== CAMERA (fixed height, never scrolls away) =====
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            // Always show live camera
            CameraPreview(Modifier.fillMaxSize())

            // Vision layers controlled by VIEW chips
            when (vision) {
                VisionMode.NORMAL -> {
                    // live camera only
                }
                VisionMode.HEAT -> {
                    HeatOverlay(output)
                }
                VisionMode.UV -> {
                    UvOverlay(output)
                }
                VisionMode.MAG -> {
                    MagOverlay(output)
                }
                VisionMode.NIGHT -> {
                    NightOverlay(output)
                }
                VisionMode.GRID -> {
                    UltraGridOverlay()
                    UltraCorners(ultraColorForMode(currentMode.name))
                }
                VisionMode.RING -> {
                    UltraCorners(ultraColorForMode(currentMode.name))
                    val f = fusion
                    if (f != null) UltraEntityRing(output, f, ultraColorForMode(currentMode.name))
                }
                VisionMode.RESIDUAL -> {
                    LiveShader(output)
                }
                VisionMode.FULL -> {
                    LiveShader(output)
                    UltraGridOverlay()
                    UltraCorners(ultraColorForMode(currentMode.name))
                    val f = fusion
                    if (f != null) UltraEntityRing(output, f, ultraColorForMode(currentMode.name))
                }
            }

            // Small vision label on camera
            Text(
                vision.name,
                color = Signal,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        // ===== SCROLLABLE DATA ONLY =====
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DataCard("READINGS") {
                Text(
                    "Jones  ${output.jonesLabel}  ${(output.jonesScore * 100).toInt()}%",
                    color = Fg, fontFamily = FontFamily.Monospace, fontSize = 13.sp
                )
                Text(
                    "|B| ${"%.2f".format(output.magUt)} µT   z ${"%.2f".format(output.zMag)}",
                    color = Mute, fontFamily = FontFamily.Monospace, fontSize = 12.sp
                )
                Text(
                    "QIDA ${"%.2f".format(output.qida)}  Omega ${"%.2f".format(output.omegaTrust)}  SDE ${"%.2f".format(output.sdeComposite)}",
                    color = Mute, fontFamily = FontFamily.Monospace, fontSize = 12.sp
                )
                Text(
                    if (output.calibrated) "baseline locked"
                    else "calibrating ${(output.calProgress * 100).toInt()}%",
                    color = if (output.calibrated) Signal else Mute,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }

            DataCard("SITE") {
                Text(
                    output.survey.activity.uppercase(),
                    color = if (output.survey.activity.contains("unclass", true)) Danger else Fg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
                Text(output.survey.note, color = Mute, fontSize = 12.sp)
                Text(
                    "Hdg ${"%.0f".format(output.survey.heading)}°  Lux ${output.survey.lux?.toInt() ?: "n/a"}",
                    color = Mute, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                )
            }

            DataCard("HUD") {
                NSCBHud(hud)
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

            Text(
                "Unclassified residual after device/environment subtraction — not a ghost detector.",
                color = Mute,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}
