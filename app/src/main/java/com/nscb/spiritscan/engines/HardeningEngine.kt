package com.nscb.spiritscan.engines

import com.nscb.spiritscan.entity.EntityOutput
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * NSCB-style hardening for phone path (v81/v82 policy, lightweight).
 * Goals:
 *  - sanitize extreme sensor spikes
 *  - hysteresis so Jones / interference labels don't flicker
 *  - only confirm "threat" (real device/anomaly attention) on sustained evidence
 *
 * Maps loosely to: hardening_policy + sentinel hardening + input sanitize.
 */
data class HardeningState(
    val hardeningScore: Float,   // 0..1 how hard the gate is clamping
    val mitigated: Boolean,      // true when we suppressed a flicker / false spike
    val threatConfirmed: Boolean,// sustained anomaly — pay attention
    val stableLabel: String,     // hysteresis-smoothed Jones-like label
    val stableScore: Float,
    val note: String
)

object HardeningEngine {
    private var lastLabel = "normal"
    private var lastScore = 0.5f
    private var holdTicks = 0
    private var threatHold = 0

    private const val HOLD_ENTER = 4   // ticks before label can change
    private const val HOLD_EXIT = 6
    private const val THREAT_ENTER = 8

    fun reset() {
        lastLabel = "normal"
        lastScore = 0.5f
        holdTicks = 0
        threatHold = 0
    }

    fun evaluate(output: EntityOutput, noiseDominant: String? = null): HardeningState {
        val rawLabel = output.jonesLabel
        val rawScore = output.jonesScore.coerceIn(0f, 1f)
        val magUt = output.magUt
        val z = abs(output.zMag)
        val normalEarth = magUt in 30f..75f

        // --- H1 Input sanitize: treat extreme z on normal |B| as motion/noise, not threat ---
        val sanitizedLabel = when {
            normalEarth && z < 6f && rawLabel == "device_interference" -> "normal"
            normalEarth && noiseDominant == "QUIET" && rawLabel == "device_interference" -> "normal"
            normalEarth && noiseDominant == "MOTION" && rawLabel == "device_interference" -> "normal"
            else -> rawLabel
        }
        val sanitizedScore = if (sanitizedLabel != rawLabel) {
            max(0.55f, 1f - rawScore * 0.25f)
        } else rawScore

        // --- Hysteresis gate: require sustained agreement before flipping label ---
        if (sanitizedLabel == lastLabel) {
            holdTicks = min(HOLD_EXIT, holdTicks + 1)
        } else {
            holdTicks++
            if (holdTicks >= HOLD_ENTER) {
                lastLabel = sanitizedLabel
                lastScore = sanitizedScore
                holdTicks = 0
            }
        }
        // score ewma toward current sanitized
        lastScore = lastScore * 0.85f + sanitizedScore * 0.15f

        val mitigated = sanitizedLabel != rawLabel ||
            (rawLabel != lastLabel && holdTicks < HOLD_ENTER)

        // --- Threat confirmed: only sustained non-normal under abnormal field or strong device ---
        val candidateThreat = when {
            lastLabel == "device_interference" && !normalEarth -> true
            lastLabel == "candidate_entity" && lastScore > 0.65f && z > 5f -> true
            magUt < 25f || magUt > 85f -> true
            else -> false
        }
        if (candidateThreat) {
            threatHold = min(THREAT_ENTER + 2, threatHold + 1)
        } else {
            threatHold = max(0, threatHold - 1)
        }
        val threatConfirmed = threatHold >= THREAT_ENTER

        val hardeningScore = when {
            mitigated && !threatConfirmed -> 0.7f
            threatConfirmed -> 0.3f
            lastLabel == "normal" -> 0.9f
            else -> 0.5f
        }

        val note = when {
            threatConfirmed -> "Sustained anomaly — attention"
            mitigated -> "Hardening suppressed flicker / false device label"
            lastLabel == "normal" -> "Stable normal — gate relaxed"
            else -> "Label held: $lastLabel"
        }

        return HardeningState(
            hardeningScore = hardeningScore,
            mitigated = mitigated,
            threatConfirmed = threatConfirmed,
            stableLabel = lastLabel,
            stableScore = lastScore.coerceIn(0f, 1f),
            note = note
        )
    }
}
