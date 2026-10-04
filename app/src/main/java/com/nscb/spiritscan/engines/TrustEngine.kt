package com.nscb.spiritscan.engines

import com.nscb.spiritscan.entity.EntityOutput
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight phone port of NSCB v82 Trust Propagation (v3.5) ideas:
 *  - low-trust gate
 *  - violation count (physics / behavior / residual)
 *  - contamination resistance (don't raise trust on noisy frames)
 *  - EWMA trust update
 *
 * Not a full mesh propagator — single-node trust for the handset sensor stack.
 */
data class TrustState(
    val trust: Float,            // 0..1 current trust
    val gateOpen: Boolean,       // allowed to propagate residual upward
    val violationCount: Int,
    val physicsViol: Boolean,
    val behaviorViol: Boolean,
    val residualViol: Boolean,
    val note: String
)

object TrustEngine {
    private var trust = 0.78f
    private const val ALPHA_UP = 0.10f
    private const val ALPHA_DOWN = 0.14f
    private const val LOW_TRUST = 0.30f
    private const val TRUST_FLOOR = 0.28f

    fun reset() {
        trust = 0.72f
    }

    fun evaluate(
        output: EntityOutput,
        noise: NoiseSplitState? = null,
        hard: HardeningState? = null
    ): TrustState {
        val magUt = output.magUt
        val z = kotlin.math.abs(output.zMag)
        val normalEarth = magUt in 30f..75f

        // Physics violation: field outside plausible Earth indoor band or extreme z
        val physicsViol = magUt < 20f || magUt > 90f || (z > 12f && !normalEarth)

        // Behavior: phone motion dominates or box thrashing residual
        val behaviorViol = (noise?.motion ?: 0f) > 0.55f ||
            (noise?.dominant == "MOTION")

        // Residual / coupling: strong wire while claiming entity, or raw device with quiet split mismatch
        val residualViol = (output.jonesLabel == "device_interference" && (noise?.wire ?: 0f) > 0.7f) ||
            (output.jonesLabel == "candidate_entity" && !output.sdeOk && z > 6f)

        var viols = 0
        if (physicsViol) viols++
        if (behaviorViol) viols++
        if (residualViol) viols++

        // Evidence for raising trust: quiet, normal field, hardening stable, sde ok
        val goodEvidence = normalEarth &&
            (noise?.dominant == "QUIET" || noise == null) &&
            output.sdeOk &&
            (hard?.stableLabel == "normal" || hard == null) &&
            z < 5f

        // Contamination resistance: never climb trust while violations present
        if (viols > 0) {
            val drop = 0.12f * viols
            trust = (trust * (1f - ALPHA_DOWN) + (trust - drop) * ALPHA_DOWN).coerceIn(TRUST_FLOOR, 1f)
        } else if (goodEvidence) {
            trust = (trust * (1f - ALPHA_UP) + 0.92f * ALPHA_UP).coerceIn(TRUST_FLOOR, 1f)
        } else {
            // mild decay toward neutral
            trust = (trust * 0.985f + 0.70f * 0.015f).coerceIn(TRUST_FLOOR, 1f)
        }

        val gateOpen = trust >= LOW_TRUST && viols == 0 && hard?.threatConfirmed != true

        val note = when {
            physicsViol -> "Physics violation — field out of band"
            behaviorViol -> "Behavior gate — motion contamination"
            residualViol -> "Residual violation — coupling / unstable claim"
            !gateOpen && trust < LOW_TRUST -> "Low trust — propagation blocked"
            gateOpen && goodEvidence -> "Trust rising — clean window"
            gateOpen -> "Gate open"
            else -> "Trust held"
        }

        return TrustState(
            trust = trust,
            gateOpen = gateOpen,
            violationCount = viols,
            physicsViol = physicsViol,
            behaviorViol = behaviorViol,
            residualViol = residualViol,
            note = note
        )
    }
}
