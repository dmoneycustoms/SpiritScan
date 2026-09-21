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
import kotlin.math.abs

data class DetectedObjectBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val label: String,
    val confidence: Float,
    val trackingId: Int = -1,
    /** true when classifier is weak / unlabeled → OOD candidate */
    val isOod: Boolean = false
)

/**
 * ML Kit stream detector + lightweight frame residual (background subtraction proxy).
 * OOD = low confidence or no useful label (Part 2.1 style without custom TFLite weights).
 * Optional TFLite model can be added later under assets/models/vision_ood.tflite.
 */
class SpiritObjectDetector(
    private val onResult: (boxes: List<DetectedObjectBox>, frameResidual: Float) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector: ObjectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )

    private val busy = AtomicBoolean(false)

    // Rolling luminance grid for background subtraction (8x8)
    private var bg: FloatArray? = null
    private val gridN = 8

    private fun cleanLabel(raw: String?, trackingId: Int?, conf: Float): Pair<String, Boolean> {
        val id = trackingId?.toString() ?: "?"
        val ood = raw.isNullOrBlank() || conf < 0.30f
        if (ood) {
            return "unknown #$id" to true
        }
        val mapped = when {
            raw!!.contains("Home", ignoreCase = true) -> "furniture"
            raw.contains("Fashion", ignoreCase = true) -> "apparel"
            raw.contains("Food", ignoreCase = true) -> "food"
            raw.contains("Place", ignoreCase = true) -> "place"
            raw.contains("plant", ignoreCase = true) -> "plant"
            else -> raw.lowercase().take(16)
        }
        return "$mapped #$id" to (conf < 0.45f)
    }

    /** Downsample Y plane → residual vs rolling background */
    private fun frameResidual(imageProxy: ImageProxy): Float {
        return try {
            val y = imageProxy.planes[0].buffer
            val rowStride = imageProxy.planes[0].rowStride
            val w = imageProxy.width
            val h = imageProxy.height
            val cellW = (w / gridN).coerceAtLeast(1)
            val cellH = (h / gridN).coerceAtLeast(1)
            val grid = FloatArray(gridN * gridN)
            for (gy in 0 until gridN) {
                for (gx in 0 until gridN) {
                    var sum = 0L
                    var count = 0
                    val x0 = gx * cellW
                    val y0 = gy * cellH
                    // sparse sample inside cell
                    var yy = y0
                    while (yy < y0 + cellH && yy < h) {
                        var xx = x0
                        while (xx < x0 + cellW && xx < w) {
                            val idx = yy * rowStride + xx
                            if (idx < y.capacity()) {
                                sum += (y.get(idx).toInt() and 0xFF)
                                count++
                            }
                            xx += cellW / 2 + 1
                        }
                        yy += cellH / 2 + 1
                    }
                    grid[gy * gridN + gx] = if (count > 0) sum.toFloat() / count / 255f else 0f
                }
            }
            val prev = bg
            if (prev == null || prev.size != grid.size) {
                bg = grid.copyOf()
                return 0.01f
            }
            var diff = 0f
            for (i in grid.indices) {
                diff += abs(grid[i] - prev[i])
                // slow background adapt
                prev[i] = prev[i] * 0.92f + grid[i] * 0.08f
            }
            (diff / grid.size).coerceIn(0f, 1f)
        } catch (_: Exception) {
            0f
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val residual = frameResidual(imageProxy)

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            busy.set(false)
            imageProxy.close()
            onResult(emptyList(), residual)
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
                    val conf = first?.confidence ?: 0f
                    val tid = obj.trackingId ?: -1
                    val (labelText, ood) = cleanLabel(first?.text, obj.trackingId, conf)
                    boxes.add(
                        DetectedObjectBox(
                            left = (box.left / imgW).coerceIn(0f, 1f),
                            top = (box.top / imgH).coerceIn(0f, 1f),
                            right = (box.right / imgW).coerceIn(0f, 1f),
                            bottom = (box.bottom / imgH).coerceIn(0f, 1f),
                            label = labelText,
                            confidence = conf,
                            trackingId = tid,
                            isOod = ood
                        )
                    )
                }
                onResult(boxes, residual)
            }
            .addOnFailureListener {
                onResult(emptyList(), residual)
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
