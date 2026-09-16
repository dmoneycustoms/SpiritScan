package com.nscb.spiritscan.ui.shader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.nscb.spiritscan.engines.ShaderEngine
import com.nscb.spiritscan.entity.EntityOutput

/* ============================
   SHADER LAYER COMPOSABLE (v8.3 update)
   ============================ */
@Composable
fun LiveShader(output: EntityOutput) {

    val t = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            t.animateTo(
                targetValue = t.value + 0.016f,
                animationSpec = tween(durationMillis = 16, easing = LinearEasing)
            )
        }
    }

    val engine = remember { ShaderEngine() }

    Canvas(modifier = Modifier.fillMaxSize()) {

        val cx = size.width / 2
        val cy = size.height / 2

        // distortion
        val distortionColor = engine.distortion(output.sdeComposite)
        drawCircle(
            color = distortionColor,
            radius = 120f + (output.sdeComposite * 80f),
            center = Offset(cx, cy)
        )

        // pulse
        val pulse = engine.pulse(output.qida, t.value)
        drawCircle(
            color = Color.Red.copy(alpha = pulse * 0.3f),
            radius = 40f + (pulse * 60f),
            center = Offset(cx, cy)
        )

        // spectral
        val spectralColor = engine.spectral(output.sdeComposite)
        drawCircle(
            color = spectralColor,
            radius = 180f,
            center = Offset(cx, cy)
        )

        // interference flash
        val interference = engine.interference(!output.sdeOk, t.value)
        if (interference > 0f) {
            drawCircle(
                color = Color.Red.copy(alpha = interference * 0.4f),
                radius = 200f,
                center = Offset(cx, cy)
            )
        }

        // magnetic ripple
        val ripple = engine.ripple(output.magUt, t.value)
        drawCircle(
            color = Color.Green.copy(alpha = ripple * 0.4f),
            radius = 140f + (ripple * 60f),
            center = Offset(cx, cy)
        )

        // entity glow
        val glowColor = engine.entityGlow(output.qida)
        drawCircle(
            color = glowColor,
            radius = 60f + (output.qida * 40f),
            center = Offset(cx, cy)
        )
    }
}
