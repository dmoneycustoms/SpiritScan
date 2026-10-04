package com.nscb.spiritscan.engines

import com.nscb.spiritscan.entity.EntityOutput
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * NSCB v8.3 — ARK Unified Temporal-Geometric Engine (Module 19)
 * Blueprint: ARK.onnx  ARK → SDE → Jones → Fusion
 * Geometric root: 1.999π cycle
 *
 * Native deterministic path (matches ARK.onnx). Drop ARK.onnx into assets later
 * for optional ONNX Runtime path.
 */
object ArkEngine {
    private const val TWO_PI = 2.0 * PI
    private const val ARK_CYCLE = 1.999 * PI

    data class ArkTick(
        val arkPhase: Double,
        val arkResidual: Double,
        val arkWeight: Double,
        val sdePhase: Double,
        val sdeDrift: Double,
        val sdeNoise: Double,
        val sdeResidual: Double,
        val jonesLabel: Int,
        val jonesLogits: DoubleArray,
        val unifiedLabel: Int,
        val unifiedScore: DoubleArray,
        val driftClass: Int,
        val alignmentClass: Int,
        val fusionNorm: Double,
        val source: String = "ark_native"
    ) {
        val jonesName: String
            get() = when (jonesLabel) {
                1 -> "watch"
                2 -> "drift"
                3 -> "spoof"
                else -> "normal"
            }
        val driftName: String
            get() = when (driftClass) {
                1 -> "mild"
                2 -> "high"
                else -> "stable"
            }
        val alignName: String
            get() = when (alignmentClass) {
                1 -> "partial"
                2 -> "misaligned"
                else -> "aligned"
            }
    }

    /**
     * Feature vector from SpiritScan sensors (10 dims):
     * [magNorm, headingFrac, omegaTrust, spoofProxy, residual, unc, sigma, activity, stress, timeFrac]
     */
    fun featuresFromScan(output: EntityOutput): DoubleArray {
        val tFrac = (System.currentTimeMillis() % 86_400_000L) / 86_400_000.0
        val magNorm = (output.magUt / 80.0).coerceIn(0.0, 2.0)
        val headingFrac = ((output.survey.heading % 360.0) / 360.0)
        val spoof = if (!output.sdeOk) 0.4 else 0.0
        val residual = output.residualLevel.toDouble().coerceIn(0.0, 5.0)
        val unc = (abs(output.zMag) / 10.0).coerceIn(0.0, 5.0)
        val sigma = output.sdeComposite.toDouble().coerceIn(0.0, 1.0)
        val stress = (output.qida * 0.5 + residual * 0.5).coerceIn(0.0, 1.0)
        return doubleArrayOf(
            magNorm,
            headingFrac,
            output.omegaTrust.toDouble().coerceIn(0.0, 1.0),
            spoof,
            residual,
            unc,
            sigma,
            (output.jonesScore.toDouble()).coerceIn(0.0, 2.0),
            stress,
            tFrac
        )
    }

    fun arkBlock(timeSec: Double): Triple<Double, Double, Double> {
        val scaled = timeSec * ARK_CYCLE
        val phase = ((scaled % TWO_PI) + TWO_PI) % TWO_PI
        val residual = TWO_PI - ARK_CYCLE
        val phaseNorm = phase / TWO_PI
        val weight = abs((sin(phase) * exp(-abs(residual))).coerceIn(-1.0, 1.0))
        return Triple(phaseNorm, residual, weight)
    }

    fun sdeBlock(
        timeSec: Double,
        features: DoubleArray,
        arkPhase: Double,
        arkWeight: Double
    ): DoubleArray {
        val fMean = features.average()
        val phase = ((arkPhase + 0.15 * fMean) % 1.0 + 1.0) % 1.0
        val drift = (features.getOrElse(0) { 0.0 } * 0.35 + arkWeight * 0.25 +
            features.getOrElse(4) { 0.0 } * 0.2).coerceIn(-1.0, 1.0)
        val noise = (0.08 + 0.12 * features.getOrElse(8) { 0.2 } +
            0.05 * abs(sin(timeSec * 0.7))).coerceIn(0.0, 1.0)
        val residual = (abs(drift) * 0.5 + noise * 0.5 - arkWeight * 0.2).coerceIn(0.0, 1.0)
        return doubleArrayOf(phase, drift, noise, residual)
    }

    fun jonesBlock(
        features: DoubleArray,
        sde: DoubleArray,
        arkPhase: Double
    ): Pair<DoubleArray, Int> {
        val logits = DoubleArray(4)
        val sog = features.getOrElse(0) { 0.0 }
        val trust = features.getOrElse(2) { 0.85 }
        val spoof = features.getOrElse(3) { 0.0 }
        val drift = sde[1]
        val noise = sde[2]
        logits[0] = 1.2 * trust - 0.8 * spoof - 0.4 * abs(drift) + 0.2 * cos(arkPhase * TWO_PI)
        logits[1] = 0.5 + 0.6 * noise + 0.3 * abs(drift) - 0.4 * trust
        logits[2] = 0.4 * abs(drift) + 0.5 * sog + 0.2 * sde[3]
        logits[3] = 1.1 * spoof + 0.5 * sde[3] + 0.3 * noise - 0.6 * trust
        val maxI = logits.indices.maxByOrNull { logits[it] } ?: 0
        return logits to maxI
    }

    fun fuseAndFinalize(
        arkPhase: Double,
        arkWeight: Double,
        sde: DoubleArray,
        jonesLogits: DoubleArray,
        jonesLabel: Int
    ): ArkTick {
        val peak = softmax(jonesLogits).maxOrNull() ?: 0.0
        val gates = doubleArrayOf(
            (0.35 + 0.65 * arkWeight).coerceIn(0.1, 1.0),
            (0.4 + 0.4 * (1.0 - sde[2])).coerceIn(0.1, 1.0),
            (0.35 + 0.5 * peak).coerceIn(0.1, 1.0)
        )
        val gSum = gates.sum()
        val fusion = (gates[0] / gSum) * arkPhase +
            (gates[1] / gSum) * ((sde[0] + (sde[1] + 1) / 2) / 2) +
            (gates[2] / gSum) * (jonesLabel / 3.0)
        val scores = softmax(jonesLogits)
        val driftClass = when {
            abs(sde[1]) < 0.15 && sde[2] < 0.2 -> 0
            abs(sde[1]) < 0.4 -> 1
            else -> 2
        }
        val alignmentClass = when {
            arkWeight > 0.7 && abs(sde[3]) < 0.25 -> 0
            arkWeight > 0.4 -> 1
            else -> 2
        }
        val unifiedLabel = when {
            jonesLabel == 3 || scores[3] > 0.55 -> 3
            driftClass == 2 -> 2
            jonesLabel == 1 -> 1
            else -> 0
        }
        return ArkTick(
            arkPhase = arkPhase,
            arkResidual = TWO_PI - ARK_CYCLE,
            arkWeight = arkWeight,
            sdePhase = sde[0],
            sdeDrift = sde[1],
            sdeNoise = sde[2],
            sdeResidual = sde[3],
            jonesLabel = jonesLabel,
            jonesLogits = jonesLogits,
            unifiedLabel = unifiedLabel,
            unifiedScore = scores,
            driftClass = driftClass,
            alignmentClass = alignmentClass,
            fusionNorm = fusion.coerceIn(0.0, 1.0)
        )
    }

    fun tick(output: EntityOutput): ArkTick {
        val timeSec = System.currentTimeMillis() / 1000.0
        val features = featuresFromScan(output)
        val (phase, _, weight) = arkBlock(timeSec)
        val sde = sdeBlock(timeSec, features, phase, weight)
        val (logits, label) = jonesBlock(features, sde, phase)
        return fuseAndFinalize(phase, weight, sde, logits, label)
    }

    fun statusLine(t: ArkTick): String =
        "ARK p=${"%.3f".format(t.arkPhase)} w=${"%.3f".format(t.arkWeight)} " +
            "drift=${t.driftName} align=${t.alignName} ${t.jonesName}"

    private fun softmax(logits: DoubleArray): DoubleArray {
        val max = logits.maxOrNull() ?: 0.0
        val exps = logits.map { exp(it - max) }
        val s = exps.sum().coerceAtLeast(1e-9)
        return DoubleArray(logits.size) { exps[it] / s }
    }
}
