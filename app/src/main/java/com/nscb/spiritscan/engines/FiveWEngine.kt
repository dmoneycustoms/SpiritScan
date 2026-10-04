package com.nscb.spiritscan.engines

import com.nscb.spiritscan.entity.EntityOutput
import kotlin.math.abs

/**
 * Phone-lite port of NSCB v82 5W-SPF packet validator ideas (v3.6).
 * Answers operational packet fields for a scan tick:
 *  Who / What / When / Where / Why
 * plus a compliance score against minimum confidence / trust rules.
 *
 * Rules (lite):
 *  R1 confidence floor
 *  R2 trust floor
 *  R3 physics band
 *  R4 motion contamination
 *  R5 collapse / decision present
 *  R6 explanation present
 *  R7 not claiming presence without attention+trust
 */
data class FiveWState(
    val who: String,
    val what: String,
    val `when`: String,
    val where: String,
    val why: String,
    val complianceScore: Float,
    val rulesOk: Int,
    val rulesTotal: Int,
    val compliant: Boolean,
    val note: String
)

object FiveWEngine {

    fun evaluate(
        output: EntityOutput,
        noise: NoiseSplitState? = null,
        hard: HardeningState? = null,
        trust: TrustState? = null,
        qida: QidaDecisionState? = null,
        explain: ExplainState? = null
    ): FiveWState {
        val tMs = System.currentTimeMillis()
        val whenStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(tMs))

        val who = when {
            hard?.threatConfirmed == true -> "operator+system (attention)"
            (noise?.motion ?: 0f) > 0.5f -> "operator (moving handset)"
            else -> "handset sensor stack"
        }

        val what = when {
            qida?.primaryState != null && qida.primaryState != "HOLD" ->
                "state=${qida.primaryState}"
            hard?.stableLabel != null ->
                "label=${hard.stableLabel}"
            else ->
                "label=${output.jonesLabel}"
        }

        val where = buildString {
            append("|B|=${"%.1f".format(output.magUt)}µT")
            append(" z=${"%.1f".format(output.zMag)}")
            append(" hdg=${"%.0f".format(output.survey.heading)}°")
            output.survey.lux?.let { append(" lux=${"%.0f".format(it)}") }
        }

        val why = explain?.why?.take(160)
            ?: output.note.take(120)

        // Rule checks
        var ok = 0
        val total = 7
        val conf = qida?.confidence ?: (1f - abs(output.zMag) / 20f).coerceIn(0f, 1f)
        val tr = trust?.trust ?: 0.5f
        val magUt = output.magUt
        val normalEarth = magUt in 25f..80f

        if (conf >= 0.35f) ok++                          // R1
        if (tr >= 0.35f) ok++                             // R2
        if (normalEarth || hard?.threatConfirmed == true) ok++ // R3 allow out-of-band only if threat path
        if ((noise?.motion ?: 0f) < 0.6f || qida?.primaryState == "MOTION") ok++ // R4
        if (qida?.collapseOk == true || qida?.primaryState == "HOLD") ok++ // R5 decision path alive
        if (explain != null) ok++                         // R6
        // R7: do not treat residual as presence without attention + trust
        val overclaim = output.jonesLabel == "candidate_entity" &&
            hard?.threatConfirmed != true && tr < 0.5f
        if (!overclaim) ok++

        val score = ok / total.toFloat()
        val compliant = score >= 0.85f && !overclaim

        val note = when {
            compliant -> "5W packet compliant"
            overclaim -> "Non-compliant — residual overclaim without attention/trust"
            score < 0.5f -> "Weak packet — improve cal / hold still / raise trust"
            else -> "Partial compliance ($ok/$total rules)"
        }

        return FiveWState(
            who = who,
            what = what,
            `when` = whenStr,
            where = where,
            why = why,
            complianceScore = score,
            rulesOk = ok,
            rulesTotal = total,
            compliant = compliant,
            note = note
        )
    }
}
