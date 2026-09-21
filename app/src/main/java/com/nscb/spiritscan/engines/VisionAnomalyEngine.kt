package com.nscb.spiritscan.engines

import com.nscb.spiritscan.vision.DetectedObjectBox

/**
 * Unknown visual anomaly:
 *  - OOD boxes (isOod / low conf)
 *  - Background-subtraction frame residual (motion-gated)
 *  - Tiny boxes discounted (dust-like)
 */
data class VisionAnomalyState(
    val unknownCount: Int,
    val residualActive: Boolean,
    val frameResidual: Float,
    val unknown: Boolean,
    val labels: List<String>,
    val note: String
)

object VisionAnomalyEngine {

    private const val MIN_BOX_AREA = 0.004f
    private const val RES_THRESH = 0.08f

    fun evaluate(
        objects: List<DetectedObjectBox>,
        visResidual: Float,
        noiseDominant: String? = null,
        residUnknownActive: Boolean = false,
        frameResidual: Float = 0f
    ): VisionAnomalyState {
        val motion = noiseDominant == "MOTION"
        val ood = objects.filter { box ->
            val w = (box.right - box.left).coerceAtLeast(0f)
            val h = (box.bottom - box.top).coerceAtLeast(0f)
            val area = w * h
            area >= MIN_BOX_AREA && (box.isOod || box.confidence < 0.30f ||
                box.label.startsWith("unknown", true) || box.label.startsWith("obj", true))
        }

        val res = maxOf(visResidual, frameResidual)
        val residualActive = !motion && res >= RES_THRESH

        val unknown = (!motion && ood.isNotEmpty()) || residualActive ||
            (residUnknownActive && !motion)

        val labels = ood.take(4).map {
            val c = (it.confidence * 100).toInt()
            "${it.label} $c%"
        }

        val note = when {
            motion -> "Vision gated — phone motion"
            ood.isNotEmpty() -> "OOD: ${ood.size} unknown/low-conf object(s)"
            residualActive -> "Frame residual ${"%.2f".format(res)} (bg subtract)"
            residUnknownActive -> "Mag unknown residual co-active"
            else -> "No unknown visual anomaly"
        }

        return VisionAnomalyState(
            unknownCount = ood.size,
            residualActive = residualActive,
            frameResidual = res,
            unknown = unknown,
            labels = labels,
            note = note
        )
    }
}
