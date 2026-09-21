package com.nscb.spiritscan.vision

import android.content.Context
import android.util.Log
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

/**
 * Optional TFLite vision OOD runner.
 * Place a model at assets/models/vision_ood.tflite (float input [1,H,W,3] or [1,N] — adapt below).
 *
 * If the model is missing or fails to load, [available] is false and callers use ML Kit OOD.
 */
class TfliteOodRunner(context: Context) {
    var available: Boolean = false
        private set
    private var interpreter: Interpreter? = null

    init {
        try {
            val buf = loadModel(context, "models/vision_ood.tflite")
            if (buf != null) {
                interpreter = Interpreter(buf)
                available = true
                Log.i(TAG, "vision_ood.tflite loaded")
            } else {
                Log.i(TAG, "No assets/models/vision_ood.tflite — TFLite OOD inactive")
            }
        } catch (e: Exception) {
            Log.w(TAG, "TFLite OOD unavailable: ${e.message}")
            available = false
            interpreter = null
        }
    }

    /**
     * Run model on a flat float feature vector (e.g. 8x8x3 downscale flattened).
     * Returns anomaly score 0..1 or null if inactive.
     */
    fun score(features: FloatArray): Float? {
        val ir = interpreter ?: return null
        return try {
            val input = Array(1) { features }
            // Generic single output score
            val output = Array(1) { FloatArray(1) }
            ir.run(input, output)
            output[0][0].coerceIn(0f, 1f)
        } catch (_: Exception) {
            // Shape mismatch for custom models — treat as inactive score
            null
        }
    }

    fun close() {
        try {
            interpreter?.close()
        } catch (_: Exception) {
        }
        interpreter = null
        available = false
    }

    private fun loadModel(context: Context, assetPath: String): MappedByteBuffer? {
        return try {
            val fd = context.assets.openFd(assetPath)
            FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "TfliteOod"
    }
}
