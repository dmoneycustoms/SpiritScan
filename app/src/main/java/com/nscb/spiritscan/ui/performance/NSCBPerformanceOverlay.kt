package com.nscb.spiritscan.ui.performance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nscb.spiritscan.engines.PerformanceState

/* ============================
   PERFORMANCE OVERLAY
   ============================ */
@Composable
fun NSCBPerformanceOverlay(perf: PerformanceState?) {

    if (perf == null) return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Avg Frame ${"%.2f".format(perf.avgFrameMs)} ms", color = Color.Green, fontFamily = FontFamily.Monospace)
        Text("Fusion Warm ${perf.fusionWarm}", color = Color.Gray, fontFamily = FontFamily.Monospace)
        Text("Frame Skip ${perf.frameSkip}", color = Color.Gray, fontFamily = FontFamily.Monospace)
        Text("Shader Throttle ${"%.2f".format(perf.shaderThrottle)}", color = Color.Gray, fontFamily = FontFamily.Monospace)
    }
}
