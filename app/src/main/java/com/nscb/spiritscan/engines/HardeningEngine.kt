package com.nscb.spiritscan.engines

import com.nscb.spiritscan.entity.EntityOutput
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Hardening gate — hysteresis + threat confirmation.
 * THREAT only when field/residual evidence is real, not a stuck device label.
 */
data class HardeningState(
    val hardeningScore: Float,
    val mitigated: Boolean,
    val threatConfirmed: Boolean,
    val stableLabel: String,
    val stableScore: Float,
    val note: String
)

object HardeningEngine {
    private var lastLabel = "normal"
    private var lastScore = 0.55f
    private var holdTicks = 0
    private var threatHold = 0

    private const val HOLD_ENTER = 5
    private const val THREAT_ENTER = 10

    fun reset() {
        lastLabel = "normal"
        lastScore = 0.55f
        holdTicks = 0
        threatHold = 0
    }

    fun evaluate(output: EntityOutput, noiseDominant: String? = null): HardeningState {
        val rawLabel = output.jonesLabel
        val rawScore = output.jonesScore.coerceIn(0f, 1f)
        val magUt = output.magUt
        val z = abs(output.zMag)
        val normalEarth = magUt in 28f..78f

        // Sanitize: quiet indoor + normal Earth never stays "device_interference"
        val sanitizedLabel = when {
            normalEarth && z < 6f && rawLabel == "device_interference" -> "normal"
            noiseDominant == "QUIET" && rawLabel == "device_interference" -> "normal"
            noiseDominant == "MOTION" && rawLabel == "device_interference" -> "normal"
            noiseDominant == "WIRE" && normalEarth && z < 8f -> "normal"
            else -> rawLabel
        }
        val sanitizedScore = if (sanitizedLabel == "normal" && rawLabel == "device_interference") {
            max(0.55f, 1f - rawScore * 0.2f)
        } else rawScore

        // Hysteresis
        if (sanitizedLabel == lastLabel) {
            holdTicks = min(8, holdTicks + 1)
        } else {
            holdTicks++
            if (holdTicks >= HOLD_ENTER) {
                lastLabel = sanitizedLabel
                lastScore = sanitizedScore
                holdTicks = 0
            }
        }
        lastScore = lastScore * 0.88f + sanitizedScore * 0.12f

        val mitigated = sanitizedLabel != rawLabel ||
            (rawLabel != lastLabel && holdTicks < HOLD_ENTER)

        // THREAT: only out-of-band field or extreme z — NOT label alone
        val realThreatEvidence =
            magUt < 22f || magUt > 88f ||
                (z > 10f && !normalEarth) ||
                (z > 14f)

        if (realThreatEvidence) {
            threatHold = min(THREAT_ENTER + 3, threatHold + 1)
        } else {
            // decay fast so stuck device label cannot hold threat
            threatHold = max(0, threatHold - 2)
        }
        val threatConfirmed = threatHold >= THREAT_ENTER

        // If no real threat, force stable display away from device_interference on normal Earth
        val displayLabel = when {
            threatConfirmed -> lastLabel
            normalEarth && lastLabel == "device_interference" -> "normal"
            else -> lastLabel
        }
        val displayScore = if (displayLabel == "normal" && lastLabel == "device_interference") {
            max(0.55f, lastScore)
        } else lastScore

        val hardeningScore = when {
            threatConfirmed -> 0.25f
            mitigated -> 0.75f
            displayLabel == "normal" -> 0.9f
            else -> 0.5f
        }

        val note = when {
            threatConfirmed -> "Sustained out-of-band field — attention"
            mitigated -> "Hardening suppressed false device / flicker"
            displayLabel == "normal" -> "Stable normal — gate relaxed"
            else -> "Label held: $displayLabel"
        }

        return HardeningState(
            hardeningScore = hardeningScore,
            mitigated = mitigated,
            threatConfirmed = threatConfirmed,
            stableLabel = displayLabel,
            stableScore = displayScore.coerceIn(0f, 1f),
            note = note
        )
    }
}
