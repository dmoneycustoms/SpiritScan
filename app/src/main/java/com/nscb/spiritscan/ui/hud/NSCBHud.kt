package com.nscb.spiritscan.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nscb.spiritscan.engines.HudState

/* ============================
   MICRO BAR COMPOSABLE
   ============================ */
@Composable
fun MicroBar(label: String, value: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            color = Color.Gray,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Box(
            Modifier
                .width(40.dp)
                .height(6.dp)
                .background(Color.DarkGray)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width((value.coerceIn(0f, 1f) * 40f).dp)
                    .background(color)
            )
        }
    }
}

/* ============================
   HUD COMPOSABLE
   Composite bar + confidence/stability +
   interference flag + micro-bars.
   ============================ */
@Composable
fun NSCBHud(hud: HudState?) {

    if (hud == null) return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            hud.mode,
            color = Color.Cyan,
            fontSize = 20.sp,
            fontFamily = FontFamily.Monospace
        )

        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(Color.DarkGray)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width((hud.composite.coerceIn(0f, 1f) * 300f).dp)
                    .background(Color.Cyan)
            )
        }

        Text(
            "Conf ${"%.2f".format(hud.confidence)}   Stab ${"%.2f".format(hud.stability)}",
            color = Color.LightGray,
            fontFamily = FontFamily.Monospace
        )

        if (hud.interference) {
            Text(
                "INTERFERENCE",
                color = Color.Red,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MicroBar("QIDA", hud.qida, Color.Red)
            MicroBar("SDE", hud.sde, Color.Magenta)
            MicroBar("OMEGA", hud.omega, Color.Green)
            MicroBar("|B|", (hud.mag / 60f).coerceIn(0f, 1f), Color.Cyan)
        }
    }
}
