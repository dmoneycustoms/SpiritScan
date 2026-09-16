package com.nscb.spiritscan.ui

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
private val Surface = Color(0xFF111318)
private val Fg = Color(0xFFE8EAED)
private val Mute = Color(0xFF8B9196)
private val Signal = Color(0xFF709A8E)
private val Danger = Color(0xFFC47A72)

@Composable
fun SpiritTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg,
            surface = Surface,
            onBackground = Fg,
            onSurface = Fg,
            primary = Signal,
            onPrimary = Bg,
            secondary = Mute,
            onSecondary = Bg,
            error = Danger,
            onError = Bg,
            outline = Mute
        ),
        content = content
    )
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier
) {
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
        val cameraProvider = cameraProviderFuture.get()

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            selector,
            preview
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { previewView }
    )
}

/* ============================
   LIVE HUD — v8.3 full integration
   Mode selector + camera stack with shader/ultra
   overlays + HUD + 9 mode screens + diagnostics.
   ============================ */
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

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "NSCB v8.3",
            color = Mute,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text("SpiritScan", color = Fg, fontSize = 28.sp)
        Spacer(Modifier.height(12.dp))

        // control row
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val ctx = LocalContext.current
            Button(onClick = { vm.arm(ctx) }) { Text("Arm") }
            Button(onClick = { vm.calibrate() }) { Text("Calibrate 8s") }
            Button(
                onClick = { vm.toggleBox() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (boxOn) Signal else Surface
                )
            ) {
                Text(if (boxOn) "Box on" else "Spirit box")
            }
            Button(onClick = { vm.toggleWalk() }) {
                Text(if (walking) "Stop walk" else "Walk property")
            }
            TextButton(onClick = { vm.resetSurvey() }) {
                Text("Reset grid", color = Mute)
            }
        }

        Spacer(Modifier.height(8.dp))

        // sweep row
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            com.nscb.spiritscan.sensor.SweepMode.entries.forEach { m ->
                TextButton(onClick = { vm.setSweep(m) }) {
                    Text(
                        m.name,
                        color = if (sweep == m) Fg else Mute,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // v8.3: scan mode selector
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ScanMode.entries.forEach { m ->
                TextButton(onClick = { vm.setMode(m) }) {
                    Text(
                        m.name,
                        color = if (currentMode == m) Fg else Mute,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // camera stack: preview + shader + ultra grid/corners/ring
        Box(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
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

        Spacer(Modifier.height(8.dp))

        // tactical HUD
        NSCBHud(hud)

        Spacer(Modifier.height(8.dp))

        // mode router
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

        Spacer(Modifier.height(8.dp))

        // calibration status
        Text(
            if (output.calibrated)
                "baseline locked"
            else
                "calibrating ${(output.calProgress * 100).toInt()}%",
            color = Signal,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )

        // diagnostics + performance overlays
        NSCBDiagnostics(diag)
        NSCBPerformanceOverlay(perf)
    }
}
