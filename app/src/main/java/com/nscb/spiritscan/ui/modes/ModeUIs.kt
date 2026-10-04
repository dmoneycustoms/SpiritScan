package com.nscb.spiritscan.ui.modes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nscb.spiritscan.entity.EntityOutput
import kotlin.math.abs

/* ============================
   JONES MODE UI
   ============================ */
@Composable
fun JonesModeUI(output: EntityOutput) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("JONES CLASSIFIER", color = Color.Green, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
        Text("Label: ${output.jonesLabel}", color = Color.Green, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
        Text("Score: ${"%.3f".format(output.jonesScore)}", color = Color.Green, fontSize = 16.sp, fontFamily = FontFamily.Monospace)

        Box(Modifier.fillMaxWidth().height(14.dp).background(Color.DarkGray)) {
            Box(
                Modifier.fillMaxHeight()
                    .width((output.jonesScore.coerceIn(0f, 1f) * 300f).dp)
                    .background(Color.Green)
            )
        }

        Text(
            "OmegaTrust ${"%.2f".format(output.omegaTrust)}",
            color = if (output.omegaTrust < 0.35f) Color.Red else Color.Green,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "|B| ${"%.2f".format(output.magUt)} uT   z ${"%.2f".format(output.zMag)}",
            color = Color.Gray, fontFamily = FontFamily.Monospace
        )
        Text("Residual: ${output.note}", color = Color.Gray, fontFamily = FontFamily.Monospace)
    }
}

/* ============================
   MAGNETIC MODE UI
   ============================ */
@Composable
fun MagneticModeUI(output: EntityOutput) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("MAGNETIC FIELD", color = Color.Green, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
        Text("|B| ${"%.2f".format(output.magUt)} uT", color = Color.Green, fontSize = 18.sp, fontFamily = FontFamily.Monospace)

        Box(Modifier.fillMaxWidth().height(14.dp).background(Color.DarkGray)) {
            Box(
                Modifier.fillMaxHeight()
                    .width(((output.magUt / 60f).coerceIn(0f, 1f) * 300f).dp)
                    .background(Color.Green)
            )
        }

        Text(
            "zMag ${"%.2f".format(output.zMag)}",
            color = if (abs(output.zMag) > 12f) Color.Red else Color.Green,
            fontFamily = FontFamily.Monospace
        )

        val delta = output.magUt - 50f
        Text(
            "dB ${"%.2f".format(delta)} uT",
            color = if (abs(delta) > 8f) Color.Red else Color.Gray,
            fontFamily = FontFamily.Monospace
        )

        Text(
            "Stability ${"%.2f".format(output.omegaTrust)}",
            color = if (output.omegaTrust < 0.35f) Color.Red else Color.Green,
            fontFamily = FontFamily.Monospace
        )

        Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            val jitter = (abs(delta) / 20f).coerceIn(0f, 1f)
            val cx = size.width / 2
            val cy = size.height / 2
            drawCircle(color = Color(0f, 1f, 0f, alpha = jitter), radius = 40f + (jitter * 60f), center = Offset(cx, cy))
            drawCircle(color = Color.Green, radius = 10f, center = Offset(cx, cy))
        }

        Text("Residual: ${output.note}", color = Color.Gray, fontFamily = FontFamily.Monospace)
    }
}

/* ============================
   QIDA MODE UI
   ============================ */
@Composable
fun QidaModeUI(output: EntityOutput) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("QIDA ANALYSIS", color = Color.Green, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
        Text(
            "QIDA ${"%.3f".format(output.qida)}",
            color = if (output.qida > 0.65f) Color.Red else Color.Green,
            fontSize = 18.sp, fontFamily = FontFamily.Monospace
        )

        Box(Modifier.fillMaxWidth().height(14.dp).background(Color.DarkGray)) {
            Box(
                Modifier.fillMaxHeight()
                    .width((output.qida.coerceIn(0f, 1f) * 300f).dp)
                    .background(if (output.qida > 0.65f) Color.Red else Color.Green)
            )
        }

        Text(
            "Instability ${"%.2f".format(1f - output.omegaTrust)}",
            color = if (output.omegaTrust < 0.35f) Color.Red else Color.Green,
            fontFamily = FontFamily.Monospace
        )

        Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            val spike = output.qida.coerceIn(0f, 1f)
            val cx = size.width / 2
            val cy = size.height / 2
            drawCircle(color = Color(spike, 0f, 0f, alpha = spike * 0.6f), radius = 40f + (spike * 80f), center = Offset(cx, cy))
            drawCircle(color = if (spike > 0.65f) Color.Red else Color.Green, radius = 10f, center = Offset(cx, cy))
        }

        Text(
            "|B| ${"%.2f".format(output.magUt)} uT   z ${"%.2f".format(output.zMag)}",
            color = Color.Gray, fontFamily = FontFamily.Monospace
        )
        Text("Residual: ${output.note}", color = Color.Gray, fontFamily = FontFamily.Monospace)
    }
}

/* ============================
   OMEGA MODE UI
   ============================ */
@Composable
fun OmegaModeUI(output: EntityOutput) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("OMEGA-TRUST STABILITY", color = Color.Green, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
        val omega = output.omegaTrust
        Text(
            "OmegaTrust ${"%.3f".format(omega)}",
            color = if (omega < 0.35f) Color.Red else Color.Green,
            fontSize = 18.sp, fontFamily = FontFamily.Monospace
        )

        Box(Modifier.fillMaxWidth().height(14.dp).background(Color.DarkGray)) {
            Box(
                Modifier.fillMaxHeight()
                    .width((omega.coerceIn(0f, 1f) * 300f).dp)
                    .background(if (omega < 0.35f) Color.Red else Color.Green)
            )
        }

        val decay = 1f - omega
        Text(
            "Decay ${"%.2f".format(decay)}",
            color = if (decay > 0.65f) Color.Red else Color.Gray,
            fontFamily = FontFamily.Monospace
        )

        Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            val cx = size.width / 2
            val cy = size.height / 2
            drawCircle(color = Color(0f, omega, 0f, alpha = omega * 0.5f), radius = 40f + (omega * 80f), center = Offset(cx, cy))
            drawCircle(color = if (omega < 0.35f) Color.Red else Color.Green, radius = 40f + (omega * 40f), style = Stroke(width = 4f), center = Offset(cx, cy))
            drawCircle(color = Color.Green, radius = 12f, center = Offset(cx, cy))
        }

        Text(
            "|B| ${"%.2f".format(output.magUt)} uT   z ${"%.2f".format(output.zMag)}",
            color = Color.Gray, fontFamily = FontFamily.Monospace
        )
        Text("Residual: ${output.note}", color = Color.Gray, fontFamily = FontFamily.Monospace)
    }
}

/* ============================
   SDE MODE UI
   ============================ */
@Composable
fun SdeModeUI(output: EntityOutput) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SDE ANALYSIS", color = Color.Green, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
        val sde = output.sdeComposite
        Text(
            "SDE ${"%.3f".format(sde)}",
            color = if (!output.sdeOk) Color.Red else Color.Green,
            fontSize = 18.sp, fontFamily = FontFamily.Monospace
        )

        Box(Modifier.fillMaxWidth().height(14.dp).background(Color.DarkGray)) {
            Box(
                Modifier.fillMaxHeight()
                    .width((sde.coerceIn(0f, 1f) * 300f).dp)
                    .background(if (!output.sdeOk) Color.Red else Color.Green)
            )
        }

        Text(
            "Status: ${if (output.sdeOk) "OK" else "DISTORTED"}",
            color = if (output.sdeOk) Color.Green else Color.Red,
            fontFamily = FontFamily.Monospace
        )

        Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            val cx = size.width / 2
            val cy = size.height / 2
            val distortion = sde.coerceIn(0f, 1f)
            drawCircle(color = Color(distortion, 0f, 0f, alpha = distortion * 0.5f), radius = 40f + (distortion * 80f), center = Offset(cx, cy))
            drawCircle(color = if (!output.sdeOk) Color.Red else Color.Green, radius = 40f + (distortion * 40f), style = Stroke(width = 4f), center = Offset(cx, cy))
            drawCircle(color = Color.Green, radius = 12f, center = Offset(cx, cy))
        }

        Text(
            "|B| ${"%.2f".format(output.magUt)} uT   z ${"%.2f".format(output.zMag)}",
            color = Color.Gray, fontFamily = FontFamily.Monospace
        )
        Text("Residual: ${output.note}", color = Color.Gray, fontFamily = FontFamily.Monospace)
    }
}

/* ============================
   RESIDUAL MODE UI
   ============================ */
@Composable
fun ResidualModeUI(output: EntityOutput) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("RESIDUAL FIELD", color = Color.Green, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
        val level = output.residualLevel
        val confidence = output.residualConfidence
        val delta = output.residualDelta

        Text(
            "Level ${"%.3f".format(level)}",
            color = if (confidence > 0.55f) Color.Red else Color.Green,
            fontSize = 18.sp, fontFamily = FontFamily.Monospace
        )

        Box(Modifier.fillMaxWidth().height(14.dp).background(Color.DarkGray)) {
            Box(
                Modifier.fillMaxHeight()
                    .width((confidence.coerceIn(0f, 1f) * 300f).dp)
                    .background(if (confidence > 0.55f) Color.Red else Color.Green)
            )
        }

        Text(
            "dResidual ${"%.2f".format(delta)}",
            color = if (abs(delta) > 4f) Color.Red else Color.Gray,
            fontFamily = FontFamily.Monospace
        )

        Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            val cx = size.width / 2
            val cy = size.height / 2
            val glow = confidence.coerceIn(0f, 1f)
            drawCircle(color = Color(glow, 0f, 0f, alpha = glow * 0.5f), radius = 40f + (glow * 80f), center = Offset(cx, cy))
            drawCircle(color = if (confidence > 0.55f) Color.Red else Color.Green, radius = 40f + (glow * 40f), style = Stroke(width = 4f), center = Offset(cx, cy))
            drawCircle(color = Color.Green, radius = 12f, center = Offset(cx, cy))
        }

        Text(
            "|B| ${"%.2f".format(output.magUt)} uT   z ${"%.2f".format(output.zMag)}",
            color = Color.Gray, fontFamily = FontFamily.Monospace
        )
        Text("Residual: ${output.note}", color = Color.Gray, fontFamily = FontFamily.Monospace)
    }
}

/* ============================
   INTERFERENCE MODE UI
   ============================ */
@Composable
fun InterferenceModeUI(output: EntityOutput) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("DEVICE INTERFERENCE", color = Color.Green, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
        // Real interference = Jones device label OR severe SDE fail with abnormal |B|
        val normalEarth = output.magUt in 30f..75f
        val interference = (output.jonesLabel == "device_interference" && !normalEarth) ||
            (!output.sdeOk && (output.magUt < 30f || output.magUt > 80f))

        Text(
            "Status: ${if (interference) "INTERFERENCE DETECTED" else "STABLE"}",
            color = if (interference) Color.Red else Color.Green,
            fontSize = 18.sp, fontFamily = FontFamily.Monospace
        )

        val intensity = if (interference) 0.85f else 0.15f
        Box(Modifier.fillMaxWidth().height(14.dp).background(Color.DarkGray)) {
            Box(
                Modifier.fillMaxHeight()
                    .width((intensity * 300f).dp)
                    .background(if (interference) Color.Red else Color.Green)
            )
        }

        Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            val cx = size.width / 2
            val cy = size.height / 2
            val glow = if (interference) 1f else 0f
            drawCircle(color = Color(glow, 0f, 0f, alpha = glow * 0.5f), radius = 40f + (glow * 80f), center = Offset(cx, cy))
            drawCircle(color = if (interference) Color.Red else Color.Green, radius = 40f + (glow * 40f), style = Stroke(width = 4f), center = Offset(cx, cy))
            drawCircle(color = Color.Green, radius = 12f, center = Offset(cx, cy))
        }

        Text(
            "|B| ${"%.2f".format(output.magUt)} uT   z ${"%.2f".format(output.zMag)}",
            color = Color.Gray, fontFamily = FontFamily.Monospace
        )
        Text("Heading ${"%.1f".format(output.survey.heading)} deg", color = Color.Gray, fontFamily = FontFamily.Monospace)
        Text("Lux ${output.survey.lux ?: 0f}", color = Color.Gray, fontFamily = FontFamily.Monospace)
        Text("Residual: ${output.note}", color = Color.Gray, fontFamily = FontFamily.Monospace)
    }
}

/* ============================
   SURVEY MODE UI
   ============================ */
@Composable
fun SurveyModeUI(output: EntityOutput) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("SENSOR SURVEY", color = Color.Green, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
        val s = output.survey

        Text("|B| ${"%.2f".format(s.magUt)} uT", color = Color.Green, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
        Text(
            "zMag ${"%.2f".format(s.zMag)}",
            color = if (abs(s.zMag) > 12f) Color.Red else Color.Gray,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "Thermal d ${"%.2f".format(s.thermalDelta)} C",
            color = if (s.thermalDelta > 2f) Color.Red else Color.Gray,
            fontFamily = FontFamily.Monospace
        )
        Text("Ambient ${s.ambientC ?: 0f} C", color = Color.Gray, fontFamily = FontFamily.Monospace)
        Text("Lux ${s.lux ?: 0f}", color = Color.Gray, fontFamily = FontFamily.Monospace)
        Text("Heading ${"%.1f".format(s.heading)} deg", color = Color.Gray, fontFamily = FontFamily.Monospace)
        Text("dx ${"%.3f".format(s.xM)}   dy ${"%.3f".format(s.yM)}", color = Color.Gray, fontFamily = FontFamily.Monospace)
        Text("Grid cells mapped: ${s.cells.size}", color = Color.Gray, fontFamily = FontFamily.Monospace)
        Text("Residual: ${output.note}", color = Color.Gray, fontFamily = FontFamily.Monospace)
    }
}
