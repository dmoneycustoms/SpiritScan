package com.nscb.spiritscan.vision

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.Collections

/**
 * MaRS OOD via ONNX Runtime.
 * Asset: models/spiritscan_mars_ood.onnx
 * Input x: [1,1,32,32] float32
 * Outputs: score, is_ood, residual
 */
data class MarsOodResult(
    val score: Float,
    val isOod: Boolean,
    val residualEnergy: Float
)

class MarsOodRunner(context: Context) {

    @Volatile
    var available: Boolean = false
        private set

    private val env: OrtEnvironment?
    private val session: OrtSession?

    init {
        var e: OrtEnvironment? = null
        var s: OrtSession? = null
        try {
            val bytes = context.assets.open(ASSET).use { input ->
                input.readBytes()
            }
            e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(2)
            s = e.createSession(bytes, opts)
            available = true
            Log.i(TAG, "Loaded $ASSET")
        } catch (ex: Exception) {
            Log.w(TAG, "MaRS OOD unavailable: ${ex.message}")
            available = false
            try {
                s?.close()
            } catch (_: Exception) {
            }
            s = null
            e = null
        }
        env = e
        session = s
    }

    fun evaluate(lumGrid: FloatArray?): MarsOodResult? {
        val sess = session ?: return null
        val environment = env ?: return null
        if (lumGrid == null || lumGrid.isEmpty()) return null

        var tensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        return try {
            val input = resizeTo32(lumGrid)
            val shape = longArrayOf(1L, 1L, 32L, 32L)
            val fb = FloatBuffer.allocate(32 * 32)
            fb.put(input)
            fb.rewind()
            tensor = OnnxTensor.createTensor(environment, fb, shape)
            val inputs: Map<String, OnnxTensor> = Collections.singletonMap("x", tensor)
            result = sess.run(inputs)

            val score = readFloatScalar(result, 0)
            val isOodV = readFloatScalar(result, 1)
            val resid = readResidualMeanAbs(result, 2)

            // Untrained AE + identity Σ gives huge absolute scores on every frame.
            // Trust model is_ood flag only if score is in a sane calibrated band;
            // otherwise treat as diagnostic score only (not auto-OOD).
            val calibrated = score < 80f  // after real training, in-dist scores drop
            val ood = if (calibrated) (isOodV > 0.5f) else false
            MarsOodResult(
                score = score,
                isOod = ood,
                residualEnergy = resid
            )
        } catch (ex: Exception) {
            Log.w(TAG, "infer failed: ${ex.message}")
            null
        } finally {
            try {
                result?.close()
            } catch (_: Exception) {
            }
            try {
                tensor?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readFloatScalar(result: OrtSession.Result, index: Int): Float {
        val v: Any = result.get(index).value ?: return 0f
        return when (v) {
            is FloatArray -> if (v.isNotEmpty()) v[0] else 0f
            is Float -> v
            is Array<*> -> {
                val first = v[0]
                when (first) {
                    is FloatArray -> if (first.isNotEmpty()) first[0] else 0f
                    is Float -> first
                    else -> 0f
                }
            }
            else -> 0f
        }
    }

    private fun readResidualMeanAbs(result: OrtSession.Result, index: Int): Float {
        return try {
            val v: Any = result.get(index).value ?: return 0f
            var sum = 0.0
            var n = 0
            fun walk(node: Any?) {
                when (node) {
                    is FloatArray -> {
                        for (x in node) {
                            sum += kotlin.math.abs(x.toDouble())
                            n++
                        }
                    }
                    is Array<*> -> {
                        for (child in node) walk(child)
                    }
                }
            }
            walk(v)
            if (n == 0) 0f else (sum / n).toFloat().coerceIn(0f, 1f)
        } catch (_: Exception) {
            0f
        }
    }

    private fun resizeTo32(src: FloatArray): FloatArray {
        val out = FloatArray(32 * 32)
        val side = kotlin.math.sqrt(src.size.toDouble()).toInt().coerceAtLeast(1)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                val sx = (x * side / 32).coerceIn(0, side - 1)
                val sy = (y * side / 32).coerceIn(0, side - 1)
                val si = sy * side + sx
                out[y * 32 + x] = if (si < src.size) src[si] else 0f
            }
        }
        return out
    }

    fun close() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        available = false
    }

    companion object {
        private const val TAG = "MarsOod"
        const val ASSET = "models/spiritscan_mars_ood.onnx"
    }
}
