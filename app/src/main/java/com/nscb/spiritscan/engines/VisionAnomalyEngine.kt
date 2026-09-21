package com.nscb.spiritscan.engines

import com.nscb.spiritscan.vision.DetectedObjectBox

/**
 * Unknown visual anomaly (Part 2 lite):
 *  - OOD: ML Kit boxes with low confidence or empty labels → unknown
 *  - Residual frame energy (visResidual) only when not motion-dominated
 *  - Dust/orb heuristic: very small boxes discounted
 *
 * Full autoencoder / dual-pixel focus not available via CameraX defaults —
 * this is the production-practical subset for S23.
 */
data class VisionAnomalyState(
    val unknownCount: Int,
    val residualActive: Boolean,
    val unknown: Boolean,
    val labels: List<String>,
    val note: String
)

object VisionAnomalyEngine {

    private const val OOD_CONF = 0.30f
    private const val MIN_BOX_AREA = 0.004f  // fraction of frame — tiny = dust-like

    fun evaluate(
        objects: List<DetectedObjectBox>,
        visResidual: Float,
        noiseDominant: String? = null,
        residUnknownActive: Boolean = false
    ): VisionAnomalyState {
        val motion = noiseDominant == "MOTION"
        val ood = objects.filter { box ->
            val w = (box.right - box.left).coerceAtLeast(0f)
            val h = (box.bottom - box.top).coerceAtLeast(0f)
            val area = w * h
            val lowConf = box.confidence < OOD_CONF
            val noLabel = box.label.isBlank() ||
                box.label.equals("unknown", true) ||
                box.label.equals("object", true) ||
                box.label.startsWith("obj", true)
            area >= MIN_BOX_AREA && (lowConf || noLabel)
        }

        // Residual path: vision residual only if phone not swinging
        val residualActive = !motion && visResidual > 0.12f

        val unknown = ood.isNotEmpty() || residualActive || residUnknownActive

        val labels = ood.take(4).map {
            val c = (it.confidence * 100).toInt()
            val name = it.label.ifBlank { "ood" }
            "$name $c%"
        }

        val note = when {
            motion -> "Vision anomalies gated — phone motion"
            ood.isNotEmpty() -> "OOD objects: ${ood.size} low-confidence/unknown"
            residualActive -> "Frame residual elevated (non-motion)"
            residUnknownActive -> "Mag unknown residual co-active"
            else -> "No unknown visual anomaly"
        }

        return VisionAnomalyState(
            unknownCount = ood.size,
            residualActive = residualActive,
            unknown = unknown,
            labels = labels,
            note = note
        )
    }
}
