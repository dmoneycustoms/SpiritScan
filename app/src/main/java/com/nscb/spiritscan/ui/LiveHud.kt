package com.nscb.spiritscan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nscb.spiritscan.entity.EntityOutput
import com.nscb.spiritscan.sensor.SweepMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val Bg = Color(0xFF08090B)
private val Surface = Color(0xFF111318)
private val Fg = Color(0xFFE8EAED)
private val Mute = Color(0xFF8B9198)
private val Signal = Color(0xFF7D9A8E)
private val Danger = Color(0xFFC47A72)

@Composable
fun SpiritTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(background = Bg, surface = Surface, onBackground = Fg, primary = Signal),
        content = content,
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
    onReset: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("NSCB v8.3", color = Mute, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text("SpiritScan", color = Fg, fontSize = 28.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onArm) { Text("Arm") }
            Button(onCal) { Text("Calibrate 8s") }
            Button(
                onBox,
                colors = ButtonDefaults.buttonColors(containerColor = if (boxOn) Signal else Surface),
            ) { Text(if (boxOn) "Box on" else "Spirit box") }
            Button(onWalk) { Text(if (walking) "Stop walk" else "Walk property") }
            TextButton(onReset) { Text("Reset grid", color = Mute) }
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
            "p=${(output.jonesScore * 100).toInt()}%  |B| ${"%.2f".format(output.magUt)} µT  z ${"%.1f".format(output.zMag)}  QIDA ${"%.3f".format(output.qida)}",
            color = Mute, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
        Text(
            if (output.calibrated) "baseline locked" else "calibrating ${(output.calProgress * 100).toInt()}%",
            color = Signal, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text("SITE  ${output.survey.activity.uppercase()}", color = if (output.survey.activity == "unclassified") Danger else Signal, fontFamily = FontFamily.Monospace)
        Text(output.survey.note, color = Mute, fontSize = 14.sp)
        output.survey.ambientC?.let {
            Text("Ambient ${"%.1f".format(it)} °C  ·  lux ${output.survey.lux?.toInt() ?: "n/a"}", color = Mute, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        } ?: Text(
            "No thermometer on this phone. False-temp Δ ${"%.2f".format(output.survey.thermalDelta)} is luminance, not heat.",
            color = Mute, fontSize = 12.sp,
        )
        Spacer(Modifier.height(16.dp))
        MagCanvas(output)
        Spacer(Modifier.height(16.dp))
        Text(
            "Candidate-entity is an unclassified residual after device and environmental subtraction — not a confirmed spirit. CMOS residual / near-IR modes live on the web instrument; this APK is the on-device Jones → SDE → QIDA → survey pipeline.",
            color = Mute, fontSize = 12.sp,
        )
    }
}

@Composable
private fun MagCanvas(output: EntityOutput) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(Surface),
    ) {
        val cx = size.width / 2
        val cy = size.height / 2
        val r = size.minDimension / 2 - 12
        drawCircle(Color(0x14E8EAED), r, Offset(cx, cy), style = Stroke(1f))
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
        drawLine(Fg, Offset(px, py), Offset(px + (cos(rad) * 18).toFloat(), py + (sin(rad) * 18).toFloat()), 2f)
    }
}
