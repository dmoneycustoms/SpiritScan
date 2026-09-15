package com.nscb.spiritscan.ui

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.nscb.spiritscan.entity.EntityOutput
import com.nscb.spiritscan.sensor.SweepMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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
            primary = Signal
        ),
        content = content
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
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // ===== LIVE CAMERA PREVIEW =====
        Text("Live Camera", color = Mute, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(6.dp))

        CameraPreview(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
        )

        Spacer(Modifier.height(16.dp))

        // ===== EXISTING UI =====
        Text("NSCB v8.3", color = Mute, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text("SpiritScan", color = Fg, fontSize = 28.sp)
        Spacer(Modifier.height(12.dp))

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onArm) { Text("Arm") }
            Button(onClick = onCal) { Text("Calibrate 8s") }
            Button(
                onClick = onBox,
                colors = ButtonDefaults.buttonColors(containerColor = if (boxOn) Signal else Surface)
            ) { Text(if (boxOn) "Box on" else "Spirit box") }
            Button(onClick = onWalk) { Text(if (walking) "Stop walk" else "Walk property") }
            TextButton(onClick = onReset) { Text("Reset grid", color = Mute) }
        }

        Spacer(Modifier.height(8.dp))

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SweepMode.entries.forEach { m ->
                TextButton(onClick = { onSweep(m) }) {
                    Text(m.name, color = if (sweep == m) Fg else Mute, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(output.jonesLabel.replace('_', ' '), color = Fg, fontSize = 22.sp)

        Text(
            "p=${(output.jonesScore * 100).toInt()}%  |B| ${"%.2f".format(output.magUT)} µT  z ${"%.1f".format(output.zScore)}",
            color = Mute, fontFamily = FontFamily.Monospace, fontSize = 12.sp
        )

        Text(
            if (output.calibrated) "baseline locked" else "calibrating ${(output.calProgress * 100).toInt()}%",
            color = Signal, fontFamily = FontFamily.Monospace, fontSize = 11.sp
        )

        Spacer(Modifier.height(12.dp))
        Text("SITE  ${output.survey.activity.uppercase()}", color = if (output.survey.activity == "unclassified") Danger else Fg)
        Text(output.survey.note, color = Mute, fontSize = 14.sp)

        output.survey.ambientC?.let {
            Text("Ambient ${"%.1f".format(it)} °C  ·  lux ${output.survey.lux?.toInt() ?: "n/a"}", color = Mute)
        } ?: Text(
            "No thermometer on this phone. False-temp Δ ${"%.2f".format(output.survey.thermalDelta)} is luminance contrast",
            color = Mute, fontSize = 12.sp
        )

        Spacer(Modifier.height(16.dp))
        MagCanvas(output)
        Spacer(Modifier.height(16.dp))

        Text(
            "Candidate-entity is an unclassified residual after device and environmental subtraction — not a ghost detector.",
            color = Mute, fontSize = 12.sp
        )
    }
}

@Composable
private fun CameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = modifier
            .background(Color.Black)
    )
}

@Composable
private fun MagCanvas(output: EntityOutput) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(Surface)
    ) {
        val cx = size.width / 2
        val cy = size.height / 2
        val r = size.minDimension / 2 - 12

        drawCircle(Color(0xFF4E8EAED), r, Offset(cx, cy), style = Stroke(1f))

        for (c in output.survey.cells) {
            val x = cx + (c.gx * 0.85f / 10f) * r
            val y = cy - (c.gy * 0.85f / 10f) * r
            val z = (abs(c.z) / 5f).coerceIn(0f, 1f)
            drawCircle(Danger.copy(alpha = 0.2f + z * 0.7f), 8f + z * 10f, Offset(x, y))
        }

        val px = cx + (output.survey.xM / 10f) * r
        val py = cy - (output.survey.yM / 10f) * r
        drawCircle(Signal, 6f, Offset(px, py))

        val rad = Math.toRadians((output.survey.heading - 90).toDouble())
        drawLine(
            Fg,
            Offset(px, py),
            Offset(px + (cos(rad) * 18).toFloat(), py + (sin(rad) * 18).toFloat()),
            strokeWidth = 2f
        )
    }
}
