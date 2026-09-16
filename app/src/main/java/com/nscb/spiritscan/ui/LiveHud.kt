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
private val ChipOff = Color(0xFF22262E)

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

    // Root: NO scroll on outer — only data panel scrolls
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
    ) {
        // ========== FIXED TOP BAR (always visible) ==========
        Column(
            Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(8.dp)
        ) {
            Text("SpiritScan v8.3", color = Fg, fontSize = 16.sp)

            Spacer(Modifier.height(6.dp))

            // ARM / CAL / BOX / WALK — large enough to hit
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { vm.arm(ctx) },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Signal)
                ) { Text("ARM", fontSize = 13.sp) }

                Button(
                    onClick = { vm.calibrate() },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                ) { Text("CAL 8s", fontSize = 13.sp) }

                Button(
                    onClick = { vm.toggleBox() },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (boxOn) Signal else ChipOff
                    )
                ) { Text(if (boxOn) "BOX ON" else "BOX", fontSize = 13.sp) }

                Button(
                    onClick = { vm.toggleWalk() },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                ) { Text(if (walking) "STOP" else "WALK", fontSize = 13.sp) }
            }

            Spacer(Modifier.height(8.dp))

            // FILTER row
            Text("FILTER", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterMode.entries.forEach { m ->
                    Button(
                        onClick = { filter = m },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (filter == m) ChipOn else ChipOff,
                            contentColor = if (filter == m) Signal else Mute
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(m.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // ========== SMALL CAMERA (fixed 140dp) ==========
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(horizontal = 8.dp)
                .border(1.dp, Border, RoundedCornerShape(4.dp))
        ) {
            CameraPreview(Modifier.fillMaxSize())

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
                    if (f != null) {
                        UltraEntityRing(output, f, ultraColorForMode(currentMode.name))
                    }
                }
            }

            Text(
                "${filter.label} · ${filter.modelHint}",
                color = Signal,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }

        // ========== DATA (only this scrolls) ==========
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Model outputs card
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Card, RoundedCornerShape(8.dp))
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text("MODEL OUTPUTS", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
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
                    "|B| ${"%.2f".format(output.magUt)} µT  z ${"%.2f".format(output.zMag)}  res ${"%.2f".format(output.residualLevel)}",
                    color = Mute, fontFamily = FontFamily.Monospace, fontSize = 11.sp
                )
                Text(
                    if (output.calibrated) "baseline locked"
                    else "calibrating ${(output.calProgress * 100).toInt()}%  → tap ARM then CAL",
                    color = if (output.calibrated) Signal else Danger,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }

            // Site
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Card, RoundedCornerShape(8.dp))
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text("SITE", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(
                    output.survey.activity.uppercase(),
                    color = if (output.survey.activity.contains("unclass", true)) Danger else Fg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                Text(output.survey.note, color = Mute, fontSize = 11.sp)
            }

            // Sweep
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SWEEP ", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                com.nscb.spiritscan.sensor.SweepMode.entries.forEach { m ->
                    TextButton(onClick = { vm.setSweep(m) }) {
                        Text(
                            m.name,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (sweep == m) Signal else Mute
                        )
                    }
                }
            }

            // Mode chips
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MODE ", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
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

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Card, RoundedCornerShape(8.dp))
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text("MODE · ${currentMode.name}", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
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
                Modifier
                    .fillMaxWidth()
                    .background(Card, RoundedCornerShape(8.dp))
                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text("HUD / DIAG", color = Mute, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                NSCBHud(hud)
                NSCBDiagnostics(diag)
                NSCBPerformanceOverlay(perf)
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}
