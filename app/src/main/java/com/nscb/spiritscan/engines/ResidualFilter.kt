package com.nscb.spiritscan.engines

import com.nscb.spiritscan.entity.EntityOutput
import kotlin.math.max
import kotlin.math.min

/**
 * Pass only unexplained residual — strip WIRE / MOTION / PHONE / quiet-Earth clutter.
 * Output is what is left after known noise is accounted for.
 */
data class ResidualFilterState(
    /** 0..1 unexplained residual after subtracting known noise */
    val unknown: Float,
    /** true when unknown is high enough to notice */
    val active: Boolean,
    /** known noise still present (for UI) */
    val knownNoise: Float,
    val stripped: String,   // what was removed this tick
    val note: String
)

object ResidualFilter {

    /** Minimum unknown level to surface (after stripping) */
    private const val ACTIVE_THRESHOLD = 0.28f

    fun evaluate(
        output: EntityOutput,
        noise: NoiseSplitState? = null,
        hard: HardeningState? = null,
        trust: TrustState? = null
    ): ResidualFilterState {
        val wire = noise?.wire ?: 0f
        val motion = noise?.motion ?: 0f
        val phone = noise?.phone ?: 0f
        val rawResid = noise?.residual ?: output.residualLevel.coerceIn(0f, 1f)

        // Known noise mass
        val known = max(wire, max(motion, phone))
        val knownSum = (wire * 0.45f + motion * 0.35f + phone * 0.30f).coerceIn(0f, 1f)

        // Strip: unexplained = residual attenuated by known sources
        // If motion or wire dominate, unknown collapses toward 0
        val stripFactor = when {
            motion > 0.50f -> 0.08f
            wire > 0.55f -> 0.12f
            phone > 0.50f -> 0.18f
            knownSum > 0.45f -> 0.25f
            else -> 1f - knownSum * 0.85f
        }

        // Also require trust gate + not mitigated false device
        val trustOk = trust?.gateOpen != false && (trust?.trust ?: 0.5f) >= 0.30f
        val notFalseDevice = hard?.stableLabel != "device_interference" ||
            (hard?.threatConfirmed == true)

        var unknown = (rawResid * stripFactor).coerceIn(0f, 1f)
        if (!trustOk) unknown *= 0.35f
        if (!notFalseDevice && hard?.mitigated == true) unknown *= 0.25f

        // Normal Earth + quiet dominant → almost never surface
        val magUt = output.magUt
        val normalEarth = magUt in 30f..75f
        if (normalEarth && noise?.dominant == "QUIET" && kotlin.math.abs(output.zMag) < 5f) {
            unknown *= 0.2f
        }

        val stripped = buildList {
            if (wire > 0.35f) add("WIRE")
            if (motion > 0.35f) add("MOTION")
            if (phone > 0.35f) add("PHONE")
            if (isEmpty()) add("none")
        }.joinToString("+")

        val active = unknown >= ACTIVE_THRESHOLD && trustOk

        val note = when {
            motion > 0.50f -> "Filtered — motion explains signal"
            wire > 0.55f -> "Filtered — wiring/mains explains signal"
            phone > 0.50f -> "Filtered — phone self-noise explains signal"
            active -> "Unknown residual active — not classified as known noise"
            unknown > 0.15f -> "Weak unknown residual (below surface threshold)"
            else -> "No unexplained residual"
        }

        return ResidualFilterState(
            unknown = unknown,
            active = active,
            knownNoise = knownSum,
            stripped = stripped,
            note = note
        )
    }
}
