package com.nscb.spiritscan.engines

import com.nscb.spiritscan.entity.EntityOutput

/* ============================
   FUSION STATE MODEL (v8.3 update)
   ============================ */
data class FusionState(
    val qida: Float,
    val sde: Float,
    val omega: Float,
    val mag: Float,
    val zMag: Float,
    val residual: Float,
    val interference: Boolean,
    val confidence: Float,
    val stability: Float,
    val composite: Float
)

/* ============================
   TEMPORAL WINDOW
   ============================ */
class TemporalWindow(private val size: Int = 12) {
    private val buffer = ArrayDeque<Float>()

    fun push(value: Float) {
        if (buffer.size >= size) buffer.removeFirst()
        buffer.addLast(value)
    }

    fun avg(): Float {
        if (buffer.isEmpty()) return 0f
        return buffer.sum() / buffer.size
    }
}

/* ============================
   FUSION ENGINE (EXPANDED)
   Multi-channel weighting + temporal smoothing
   + interference suppression, per upgrade spec.
   ============================ */
class FusionEngine {

    private val qidaWindow = TemporalWindow()
    private val sdeWindow = TemporalWindow()
    private val omegaWindow = TemporalWindow()
    private val magWindow = TemporalWindow()
    private val residualWindow = TemporalWindow()

    fun fuse(output: EntityOutput): FusionState {
        val qida = output.qida
        val sde = output.sdeComposite
        val omega = output.omegaTrust
        val mag = output.magUt
        val zMag = output.zMag
        val residual = output.survey.score.coerceIn(0f, 1f)
        val interference = !output.sdeOk

        // temporal smoothing
        qidaWindow.push(qida)
        sdeWindow.push(sde)
        omegaWindow.push(omega)
        magWindow.push(mag)
        residualWindow.push(residual)

        val qidaAvg = qidaWindow.avg()
        val sdeAvg = sdeWindow.avg()
        val omegaAvg = omegaWindow.avg()
        val magAvg = magWindow.avg()
        val residualAvg = residualWindow.avg()

        // multi-channel weighting
        val wQida = 0.32f
        val wSde = 0.28f
        val wOmega = 0.22f
        val wMag = 0.10f
        val wResidual = 0.08f

        val composite =
            (qidaAvg * wQida) +
            (sdeAvg * wSde) +
            ((1f - omegaAvg) * wOmega) +
            ((magAvg / 60f).coerceIn(0f, 1f) * wMag) +
            (residualAvg * wResidual)

        // confidence model
        val confidence =
            (qidaAvg * 0.4f) +
            (sdeAvg * 0.3f) +
            ((1f - omegaAvg) * 0.3f)

        val stability =
            (omegaAvg * 0.6f) +
            ((1f - residualAvg) * 0.4f)

        // interference suppression
        val finalComposite = if (interference) composite * 0.35f else composite
        val finalConfidence = if (interference) confidence * 0.25f else confidence
        val finalStability = if (interference) stability * 0.20f else stability

        return FusionState(
            qida = qidaAvg,
            sde = sdeAvg,
            omega = omegaAvg,
            mag = magAvg,
            zMag = zMag,
            residual = residualAvg,
            interference = interference,
            confidence = finalConfidence.coerceIn(0f, 1f),
            stability = finalStability.coerceIn(0f, 1f),
            composite = finalComposite.coerceIn(0f, 1f)
        )
    }
}
