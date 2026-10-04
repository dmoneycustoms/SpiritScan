package com.nscb.spiritscan.engines

import com.nscb.spiritscan.entity.EntityOutput
import kotlin.math.abs

/* ============================
   DIAGNOSTICS MODEL
   ============================ */
data class DiagnosticsState(
    val fps: Int,
    val frameMs: Float,
    val fusionMs: Float,
    val magJitter: Float,
    val qida: Float,
    val sde: Float,
    val omega: Float,
    val interference: Boolean
)

/* ============================
   DIAGNOSTICS ENGINE
   ============================ */
class DiagnosticsEngine {

    private var lastFrame = System.nanoTime()
    private var fpsCounter = 0
    private var fpsTimer = System.nanoTime()
    private var fpsValue = 0

    fun build(
        output: EntityOutput,
        fusion: FusionState,
        fusionTimeNs: Long
    ): DiagnosticsState {
        val now = System.nanoTime()
        val frameDelta = (now - lastFrame) / 1_000_000f
        lastFrame = now

        fpsCounter++
        if ((now - fpsTimer) > 1_000_000_000L) {
            fpsValue = fpsCounter
            fpsCounter = 0
            fpsTimer = now
        }

        val jitter = abs(output.zMag - output.magUt)

        return DiagnosticsState(
            fps = fpsValue,
            frameMs = frameDelta,
            fusionMs = fusionTimeNs / 1_000_000f,
            magJitter = jitter,
            qida = output.qida,
            sde = output.sdeComposite,
            omega = output.omegaTrust,
            interference = !output.sdeOk
        )
    }
}
