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
import androidx.compose.runtime.remember
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

private val Bg = Color(0xFF0B090B)
private val Surface = Color(0xFF15181E)
private val Card = Color(0xFF1A1E26)
private val Fg = Color(0xFFE8EAED)
private val Mute = Color(0xFF8B9196)
private val Signal = Color(0xFF709A8E)
private val Danger = Color(0xFFC47A72)
private val Border = Color(0xFF2A303A)

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
                it.surfaceProvider = previewView.surfaceProvider
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
private fun DataCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = {
            Text(
                title,
                color = Mute,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
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

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        // ===== FIXED HEADER + CONTROLS (never scrolls) =====
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("NSCB v8.3", color = Mute, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text("SpiritScan", color = Fg, fontSize = 22.sp)

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { vm.arm(ctx) },
                    colors = ButtonDefaults.buttonColors(containerColor = Signal)
                ) { Text("Arm") }
                Button(onClick = { vm.calibrate() }) { Text("Calibrate 8s") }
                Button(
                    onClick = { vm.toggleBox() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (boxOn) Signal else Surface
                    )
                ) { Text(if (boxOn) "Box ON" else "Spirit box") }
                Button(onClick = { vm.toggleWalk() }) {
                    Text(if (walking) "Stop walk" else "Walk")
                }
                TextButton(onClick = { vm.resetSurvey() }) {
                    Text("Reset", color = Mute)
                }
            }

            Spacer(Modifier.height(6.dp))

            // Sweep modes
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                com.nscb.spiritscan.sensor.SweepMode.entries.forEach { m ->
                    TextButton(onClick = { vm.setSweep(m) }) {
                        Text(
                            m.name,
                            color = if (sweep == m) Signal else Mute,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Scan modes
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ScanMode.entries.forEach { m ->
                    TextButton(onClick = { vm.setMode(m) }) {
                        Text(
                            m.name,
                            color = if (currentMode == m) Signal else Mute,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // ===== FIXED CAMERA (never scrolls) =====
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(horizontal = 12.dp)
        ) {
            CameraPreview(Modifier.fillMaxSize())
            LiveShader(output)
            UltraGridOverlay()
            UltraCorners(ultraColorForMode(currentMode.name))
            val f = fusion
            if (f != null) {
                UltraEntityRing(output, f, ultraColorForMode(currentMode.name))
            }
        }

        // ===== SCROLLABLE DATA AREA ONLY =====
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Tactical HUD card
            DataCard("TACTICAL HUD") {
                NSCBHud(hud)
            }

            // Core readings card
            DataCard("READINGS") {
                Text(
                    "Jones  ${output.jonesLabel}  ·  ${(output.jonesScore * 100).toInt()}%",
                    color = Fg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
                Text(
                    "|B| ${"%.2f".format(output.magUt)} µT   z ${"%.2f".format(output.zMag)}",
                    color = Mute,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                Text(
                    "QIDA ${"%.2f".format(output.qida)}   Omega ${"%.2f".format(output.omegaTrust)}   SDE ${"%.2f".format(output.sdeComposite)}",
                    color = Mute,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                Text(
                    if (output.calibrated) "baseline locked" else "calibrating ${(output.calProgress * 100).toInt()}%",
                    color = if (output.calibrated) Signal else Mute,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }

            // Site / residual card
            DataCard("SITE / RESIDUAL") {
                Text(
                    output.survey.activity.uppercase(),
                    color = if (output.survey.activity.contains("unclass", true)) Danger else Fg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
                Text(output.survey.note, color = Mute, fontSize = 12.sp)
                Text(
                    "Heading ${"%.1f".format(output.survey.heading)}°   Lux ${output.survey.lux?.toInt() ?: "n/a"}",
                    color = Mute,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                output.survey.ambientC?.let {
                    Text("Ambient ${"%.1f".format(it)} °C", color = Mute, fontSize = 11.sp)
                }
            }

            // Mode-specific panel
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

            // Diagnostics (collapsed style)
            DataCard("DIAGNOSTICS") {
                NSCBDiagnostics(diag)
                NSCBPerformanceOverlay(perf)
            }

            Text(
                "Candidate-entity is an unclassified residual after device and environmental subtraction — not a ghost detector.",
                color = Mute,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
