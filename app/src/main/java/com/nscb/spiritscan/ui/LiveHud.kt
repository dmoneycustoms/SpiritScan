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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.nscb.spiritscan.entity.EntityOutput
import com.nscb.spiritscan.sensor.SweepMode

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

@Composable
fun LiveHud(
    output: EntityOutput,
    boxOn: Boolean,
    walking: Boolean,
    sweep: SweepMode,
    onArm: () -> Unit,
    onCal: () -> Unit,
    onBox: () -> Unit,
    onWalk: () -> Unit,
    onSweep: (SweepMode) -> Unit,
    onReset: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(16.dp)
    ) {
        // ===== LIVE CAMERA PREVIEW (non-scroll, stable) =====
        Text(
            "Live Camera",
            color = Mute,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(6.dp))

        CameraPreview(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
        )

        Spacer(Modifier.height(16.dp))

        // ===== SCROLLABLE HUD CONTENT =====
        Column(
            Modifier
                .fillMaxWidth()
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

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onArm) { Text("Arm") }
                Button(onClick = onCal) { Text("Calibrate 8s") }
                Button(
                    onClick = onBox,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (boxOn) Signal else Surface
                    )
                ) {
                    Text(if (boxOn) "Box on" else "Spirit box")
                }
                Button(onClick = onWalk) {
                    Text(if (walking) "Stop walk" else "Walk property")
                }
                TextButton(onClick = onReset) {
                    Text("Reset grid", color = Mute)
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SweepMode.entries.forEach { m ->
                    TextButton(onClick = { onSweep(m) }) {
                        Text(
                            m.name,
                            color = if (sweep == m) Fg else Mute,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    output.jonesLabel,
                    color = Fg,
                    fontSize = 22.sp
                )

                Text(
                    "p=${(output.jonesScore * 100).toInt()}%  |B| ${
                        "%.2f".format(output.magUT)
                    } µT  z ${
                        "%.1f".format(output.zScore)
                    }",
                    color = Mute,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )

                Text(
                    if (output.calibrated)
                        "baseline locked"
                    else
                        "calibrating ${(output.calProgress * 100).toInt()}%",
                    color = Signal,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
    }
}
