package com.nscb.spiritscan.ui.entity

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nscb.spiritscan.entity.EntityOutput

/* ============================
   ENTITY MODE UI (v8.3 update)
   Core detection readout block.
   ============================ */
@Composable
fun EntityModeUI(output: EntityOutput) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("ENTITY SCAN", color = Color.Green, fontSize = 20.sp, fontFamily = FontFamily.Monospace)

        Text(
            "|B| ${"%.2f".format(output.magUt)} uT   z ${"%.2f".format(output.zMag)}",
            color = Color.Green, fontFamily = FontFamily.Monospace
        )
        Text(
            "QIDA ${"%.2f".format(output.qida)}",
            color = if (output.qida > 0.65f) Color.Red else Color.Green,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "OmegaTrust ${"%.2f".format(output.omegaTrust)}",
            color = if (output.omegaTrust < 0.35f) Color.Red else Color.Green,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "SDE ${"%.2f".format(output.sdeComposite)}  ok=${output.sdeOk}",
            color = if (output.sdeOk) Color.Green else Color.Red,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "Thermal d ${"%.2f".format(output.survey.thermalDelta)} C",
            color = Color.Gray, fontFamily = FontFamily.Monospace
        )
        Text(
            "Ambient ${output.survey.ambientC ?: 0f} C   Lux ${output.survey.lux ?: 0f}",
            color = Color.Gray, fontFamily = FontFamily.Monospace
        )
        Text(
            "Heading ${"%.1f".format(output.survey.heading)} deg",
            color = Color.Gray, fontFamily = FontFamily.Monospace
        )
        Text(
            "Residual: ${output.note}",
            color = Color.Gray, fontFamily = FontFamily.Monospace
        )
    }
}
