package com.nscb.spiritscan.engines

import com.nscb.spiritscan.entity.EntityOutput
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Phone-lite port of QIDA Trust-Math + 3CAI decision-core ideas:
 *  - confidence amplitude from residual + trust
 *  - phi / beta style violation penalties
 *  - 8 soft state amplitudes → collapse to primary decision
 *  - weighted decision score gated by trust + confidence thresholds
 *
 * States (collapsed label):
 *  QUIET, WIRE, MOTION, PHONE, ENV, DEVICE, RESIDUAL, ATTENTION
 */
data class QidaDecisionState(
    val confidence: Float,
    val decisionScore: Float,
    val primaryState: String,
    val amplitudes: List<Float>,  // 8
    val collapseOk: Boolean,
    val note: String
)

object QidaDecisionEngine {
    private val stateNames = listOf(
        "QUIET", "WIRE", "MOTION", "PHONE", "ENV", "DEVICE", "RESIDUAL", "ATTENTION"
    )

    fun evaluate(
        output: EntityOutput,
        noise: NoiseSplitState? = null,
        hard: HardeningState? = null,
        trust: TrustState? = null
    ): QidaDecisionState {
        val qidaR = output.qida.coerceIn(0f, 1f)
        val z = abs(output.zMag)
        val magUt = output.magUt
        val normalEarth = magUt in 30f..75f
        val t = (trust?.trust ?: 0.6f).coerceIn(0f, 1f)
        val wire = noise?.wire ?: 0f
        val motion = noise?.motion ?: 0f
        val phone = noise?.phone ?: 0f
        val resid = noise?.residual ?: 0f

        // phi ~ physics tension, beta ~ behavior tension
        val phi = when {
            magUt < 20f || magUt > 90f -> 0.9f
            z > 10f && !normalEarth -> 0.7f
            z > 6f -> 0.35f
            else -> 0.05f
        }
        val beta = when {
            motion > 0.55f -> 0.8f
            motion > 0.35f -> 0.4f
            phone > 0.5f -> 0.45f
            else -> 0.05f
        }

        // Base confidence from residual channel + inverse violations + trust
        val confRaw = (0.35f * (1f - qidaR) + 0.35f * t + 0.15f * (1f - phi) + 0.15f * (1f - beta))
            .coerceIn(0f, 1f)

        // 8-state logits (handcrafted features → soft amplitudes)
        val logits = FloatArray(8)
        logits[0] = 1.2f * (1f - wire) * (1f - motion) * (1f - resid) * t           // QUIET
        logits[1] = 1.4f * wire                                                     // WIRE
        logits[2] = 1.4f * motion                                                   // MOTION
        logits[3] = 1.2f * phone                                                    // PHONE
        logits[4] = 0.9f * (if (output.jonesLabel == "environmental_shift") 1f else 0.2f) + 0.3f * (z / 8f).coerceIn(0f, 1f)
        logits[5] = 1.1f * (if (output.jonesLabel == "device_interference") 0.8f else 0f) * wire
        logits[6] = 1.0f * resid + 0.5f * qidaR
        logits[7] = 1.3f * (if (hard?.threatConfirmed == true) 1f else 0f) +
            0.4f * (if (!normalEarth && z > 6f) 1f else 0f)

        // Softmax
        val maxL = logits.max()
        var sum = 0f
        val expv = FloatArray(8)
        for (i in 0 until 8) {
            expv[i] = exp((logits[i] - maxL).toDouble()).toFloat()
            sum += expv[i]
        }
        val amps = expv.map { it / (sum + 1e-6f) }

        val primaryIdx = amps.indices.maxBy { amps[it] }
        val primary = stateNames[primaryIdx]
        val maxConf = amps[primaryIdx]

        // 3CAI-style gates: confidence + trust thresholds
        val confOk = confRaw >= 0.32f && maxConf >= 0.22f
        val trustOk = t >= 0.28f
        val collapseOk = confOk && trustOk && (trust?.gateOpen != false)

        // Weighted decision score
        val decisionScore = (0.55f * confRaw + 0.45f * t * maxConf).coerceIn(0f, 1f)

        val note = when {
            !collapseOk && !trustOk -> "Hold — trust below threshold"
            !collapseOk && !confOk -> "Hold — weak collapse margin"
            !collapseOk && confOk && !trustOk -> "Hold — trust below threshold"
            primary == "ATTENTION" -> "Collapse → ATTENTION"
            primary == "QUIET" && collapseOk -> "Collapse → QUIET (clean)"
            else -> "Collapse → $primary"
        }

        return QidaDecisionState(
            confidence = confRaw,
            decisionScore = decisionScore,
            primaryState = if (collapseOk) primary else "HOLD",
            amplitudes = amps,
            collapseOk = collapseOk,
            note = note
        )
    }
}
