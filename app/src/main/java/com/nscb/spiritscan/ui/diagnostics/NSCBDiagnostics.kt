package com.nscb.spiritscan.ui.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nscb.spiritscan.engines.DiagnosticsState
import com.nscb.spiritscan.ui.hud.MicroBar

/* ============================
   DIAGNOSTICS OVERLAY
   ============================ */
@Composable
fun NSCBDiagnostics(diag: DiagnosticsState?) {

    if (diag == null) return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("FPS ${diag.fps}", color = Color.Yellow, fontFamily = FontFamily.Monospace)
        Text("Frame ${"%.2f".format(diag.frameMs)} ms", color = Color.Gray, fontFamily = FontFamily.Monospace)
        Text("Fusion ${"%.2f".format(diag.fusionMs)} ms", color = Color.Gray, fontFamily = FontFamily.Monospace)
        Text("Jitter ${"%.2f".format(diag.magJitter)}", color = Color.Cyan, fontFamily = FontFamily.Monospace)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MicroBar("QIDA", diag.qida, Color.Red)
            MicroBar("SDE", diag.sde, Color.Magenta)
            MicroBar("OMEGA", diag.omega, Color.Green)
        }

        if (diag.interference) {
            Text("INTERFERENCE", color = Color.Red, fontFamily = FontFamily.Monospace)
        }
    }
}
