package com.nscb.spiritscan.engines

import com.nscb.spiritscan.entity.EntityOutput
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Phone-lite port of NSCB DOD Explainability (v3.2) ideas:
 *  - primary + alternative hypotheses with confidences
 *  - physics / behavior violation flags
 *  - confidence band (low / mid / high)
 *  - plain-language why string for the HUD
 *
 * Does not claim paranormal proof — explains what the stack believes and why.
 */
data class ExplainState(
    val primary: String,
    val primaryConf: Float,
    val alternative: String,
    val altConf: Float,
    val physicsViolations: Int,
    val confidenceBand: String,  // LOW | MID | HIGH
    val why: String,
    val note: String
)

object ExplainEngine {

    private val hypotheses = listOf(
        "normal_quiet",
        "mains_wiring",
        "phone_motion",
        "phone_self_noise",
        "environmental_shift",
        "device_coupling",
        "unexplained_residual",
        "attention_required"
    )

    fun evaluate(
        output: EntityOutput,
        noise: NoiseSplitState? = null,
        hard: HardeningState? = null,
        trust: TrustState? = null,
        qida: QidaDecisionState? = null
    ): ExplainState {
        val z = abs(output.zMag)
        val magUt = output.magUt
        val normalEarth = magUt in 30f..75f
        val wire = noise?.wire ?: 0f
        val motion = noise?.motion ?: 0f
        val phone = noise?.phone ?: 0f
        val resid = noise?.residual ?: 0f
        val t = trust?.trust ?: 0.6f

        // Logits for 8 explainable hypotheses
        val logits = FloatArray(8)
        logits[0] = 1.3f * (if (noise?.dominant == "QUIET") 1f else 0.3f) * t *
            (if (hard?.stableLabel == "normal") 1.1f else 0.7f)
        logits[1] = 1.5f * wire
        logits[2] = 1.5f * motion
        logits[3] = 1.3f * phone
        logits[4] = 1.0f * (if (output.jonesLabel == "environmental_shift") 1f else 0.15f) +
            0.25f * (z / 8f).coerceIn(0f, 1f)
        logits[5] = 1.2f * (if (output.jonesLabel == "device_interference") 0.9f else 0f) *
            max(wire, if (!normalEarth) 0.5f else 0.1f)
        logits[6] = 1.1f * resid + 0.4f * output.qida +
            (if (qida?.primaryState == "RESIDUAL") 0.5f else 0f)
        logits[7] = 1.4f * (if (hard?.threatConfirmed == true) 1f else 0f) +
            (if (!normalEarth && z > 7f) 0.6f else 0f) +
            (if (qida?.primaryState == "ATTENTION") 0.5f else 0f)

        val maxL = logits.max()
        var sum = 0f
        val probs = FloatArray(8)
        for (i in 0 until 8) {
            probs[i] = exp((logits[i] - maxL).toDouble()).toFloat()
            sum += probs[i]
        }
        for (i in 0 until 8) probs[i] /= (sum + 1e-6f)

        val primaryIdx = probs.indices.maxBy { probs[it] }
        var altIdx = 0
        var altBest = -1f
        for (i in probs.indices) {
            if (i != primaryIdx && probs[i] > altBest) {
                altBest = probs[i]
                altIdx = i
            }
        }

        // Physics violations (count)
        var phys = 0
        if (magUt < 20f || magUt > 90f) phys++
        if (z > 12f && !normalEarth) phys++
        if (trust?.physicsViol == true) phys++

        val primaryConf = probs[primaryIdx]
        val band = when {
            primaryConf >= 0.55f && t >= 0.55f && phys == 0 -> "HIGH"
            primaryConf >= 0.35f -> "MID"
            else -> "LOW"
        }

        val primary = hypotheses[primaryIdx]
        val alternative = hypotheses[altIdx]

        val why = buildString {
            append("Primary $primary (${(primaryConf * 100).toInt()}%). ")
            append(
                when (primary) {
                    "normal_quiet" -> "Field in Earth band, noise quiet, trust OK."
                    "mains_wiring" -> "Strong 50/60 Hz component vs total energy."
                    "phone_motion" -> "Accel/gyro motion dominates mag changes."
                    "phone_self_noise" -> "Still handset with short-term mag jitter."
                    "environmental_shift" -> "Drift / z pattern without clear mains."
                    "device_coupling" -> "Device-class label with coupling evidence."
                    "unexplained_residual" -> "Residual left after noise split."
                    "attention_required" -> "Hardening threat or out-of-band field."
                    else -> "Mixed evidence."
                }
            )
            if (phys > 0) append(" Physics flags: $phys.")
            append(" Alt: $alternative (${(altBest * 100).toInt()}%).")
        }

        val note = when (band) {
            "HIGH" -> "Explanation high confidence — still not proof of presence"
            "MID" -> "Explanation mid confidence — treat as working hypothesis"
            else -> "Explanation low confidence — collect more clean samples"
        }

        return ExplainState(
            primary = primary,
            primaryConf = primaryConf,
            alternative = alternative,
            altConf = altBest,
            physicsViolations = phys,
            confidenceBand = band,
            why = why,
            note = note
        )
    }
}
