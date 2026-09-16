package com.nscb.spiritscan.vision

import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.atomic.AtomicReference

data class DetectedObjectBox(
    val left: Float,   // 0..1 normalized
    val top: Float,
    val right: Float,
    val bottom: Float,
    val label: String,
    val confidence: Float
)

/**
 * On-device ML Kit object detector bound to CameraX ImageAnalysis.
 * Boxes are normalized to the analysis image size (0..1).
 */
class SpiritObjectDetector(
    private val onResult: (List<DetectedObjectBox>) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )

    private val busy = AtomicReference(false)

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val media = imageProxy.image
        if (media == null) {
            busy.set(false)
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
        val w = imageProxy.width.toFloat().coerceAtLeast(1f)
        val h = imageProxy.height.toFloat().coerceAtLeast(1f)

        detector.process(image)
            .addOnSuccessListener { objects ->
                val boxes = objects.map { obj ->
                    val box: RectF = RectF(obj.boundingBox)
                    val label = obj.labels.firstOrNull()?.text ?: "object"
                    val conf = obj.labels.firstOrNull()?.confidence ?: 0f
                    DetectedObjectBox(
                        left = (box.left / w).coerceIn(0f, 1f),
                        top = (box.top / h).coerceIn(0f, 1f),
                        right = (box.right / w).coerceIn(0f, 1f),
                        bottom = (box.bottom / h).coerceIn(0f, 1f),
                        label = label,
                        confidence = conf
                    )
                }
                onResult(boxes)
            }
            .addOnCompleteListener {
                busy.set(false)
                imageProxy.close()
            }
    }

    fun close() {
        try {
            detector.close()
        } catch (_: Exception) {
        }
    }
}
