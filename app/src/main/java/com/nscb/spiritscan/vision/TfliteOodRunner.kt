package com.nscb.spiritscan.vision

import android.content.Context
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Optional TFLite OOD runner.
 *
 * Place model at:  app/src/main/assets/models/vision_ood.tflite
 *
 * Expected default contract (adapt if your export differs):
 *   input  [1, 8, 8, 1] float32 luminance grid
 *   output [1, 1] float32 anomaly score 0..1
 *
 * Uses reflection so the app compiles without TensorFlow on the classpath.
 * Add to app/build.gradle.kts when you have a model:
 *   implementation("org.tensorflow:tensorflow-lite:2.14.0")
 */
class TfliteOodRunner(context: Context) {
    var available: Boolean = false
        private set

    private var interpreter: Any? = null
    private var runMethod: java.lang.reflect.Method? = null

    init {
        try {
            val hasAsset = try {
                context.assets.open("models/vision_ood.tflite").close()
                true
            } catch (_: Exception) {
                false
            }
            if (!hasAsset) {
                Log.i(TAG, "No assets/models/vision_ood.tflite — OOD stays ML Kit path")
            } else {
                val buf = loadModel(context, "models/vision_ood.tflite")
                if (buf != null) {
                    val clazz = Class.forName("org.tensorflow.lite.Interpreter")
                    val ctor = clazz.getConstructor(MappedByteBuffer::class.java)
                    interpreter = ctor.newInstance(buf)
                    runMethod = clazz.getMethod("run", Any::class.java, Any::class.java)
                    available = true
                    Log.i(TAG, "vision_ood.tflite loaded via Interpreter")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TFLite unavailable: ${e.message}. Add tensorflow-lite dependency to enable.")
            available = false
            interpreter = null
        }
    }

    /** Score 8x8 (or flat) luminance features → anomaly 0..1, or null if inactive */
    fun score(features: FloatArray): Float? {
        val ir = interpreter ?: return null
        val run = runMethod ?: return null
        return try {
            val input = Array(1) { Array(8) { Array(8) { FloatArray(1) } } }
            val n = minOf(features.size, 64)
            for (i in 0 until n) {
                val y = i / 8
                val x = i % 8
                if (y < 8 && x < 8) input[0][y][x][0] = features[i]
            }
            val output = Array(1) { FloatArray(1) }
            run.invoke(ir, input, output)
            output[0][0].coerceIn(0f, 1f)
        } catch (e: Exception) {
            Log.w(TAG, "score failed (check model I/O shapes): ${e.message}")
            null
        }
    }

    fun close() {
        try {
            interpreter?.javaClass?.getMethod("close")?.invoke(interpreter)
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
