package com.nscb.spiritscan.engines

import com.nscb.spiritscan.entity.EntityOutput

/* ============================
   HUD MODEL
   ============================ */
data class HudState(
    val mode: String,
    val composite: Float,
    val confidence: Float,
    val stability: Float,
    val interference: Boolean,
    val mag: Float,
    val qida: Float,
    val sde: Float,
    val omega: Float
)

/* ============================
   HUD ENGINE
   ============================ */
class HudEngine {

    fun build(modeName: String, output: EntityOutput, fusion: FusionState): HudState {
        return HudState(
            mode = modeName,
            composite = fusion.composite,
            confidence = fusion.confidence,
            stability = fusion.stability,
            interference = !output.sdeOk,
            mag = output.magUt,
            qida = output.qida,
            sde = output.sdeComposite,
            omega = output.omegaTrust
        )
    }
}
