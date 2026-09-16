package com.nscb.spiritscan.vision

import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

data class DetectedObjectBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val label: String,
    val confidence: Float
)

/**
 * On-device ML Kit object detector for CameraX ImageAnalysis.
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

    private val busy = AtomicBoolean(false)

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            busy.set(false)
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotation)
        val imgW = imageProxy.width.toFloat().coerceAtLeast(1f)
        val imgH = imageProxy.height.toFloat().coerceAtLeast(1f)

        detector.process(image)
            .addOnSuccessListener { detectedObjects: List<DetectedObject> ->
                val boxes = ArrayList<DetectedObjectBox>(detectedObjects.size)
                for (obj in detectedObjects) {
                    val box: Rect = obj.boundingBox
                    val labels = obj.labels
                    val first = if (labels.isNotEmpty()) labels[0] else null
                    val labelText = first?.text ?: "object"
                    val conf = first?.confidence ?: 0f
                    boxes.add(
                        DetectedObjectBox(
                            left = (box.left / imgW).coerceIn(0f, 1f),
                            top = (box.top / imgH).coerceIn(0f, 1f),
                            right = (box.right / imgW).coerceIn(0f, 1f),
                            bottom = (box.bottom / imgH).coerceIn(0f, 1f),
                            label = labelText,
                            confidence = conf
                        )
                    )
                }
                onResult(boxes)
            }
            .addOnFailureListener {
                onResult(emptyList())
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
