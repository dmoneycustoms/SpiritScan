package com.nscb.spiritscan.vision

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * Runs spiritscan_mars_ood.onnx (MaRS AE + Mahalanobis OOD).
 *
 * Asset path: models/spiritscan_mars_ood.onnx
 * Input  x:       float32 [1,1,32,32]
 * Output score:   float32 [1]
 * Output is_ood:  float32 [1]
 * Output residual: float32 [1,1,32,32] (optional)
 */
data class MarsOodResult(
    val score: Float,
    val isOod: Boolean,
    val residualEnergy: Float
)

class MarsOodRunner(context: Context) {
    var available: Boolean = false
        private set

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    init {
        try {
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(2)
            session = env!!.createSession(bytes, opts)
            available = true
            Log.i(TAG, "Loaded $ASSET")
        } catch (e: Exception) {
            Log.w(TAG, "MaRS OOD unavailable: ${e.message}")
            available = false
            close()
        }
    }

    /**
     * @param lumGrid row-major luminance 0..1 (any size; resized to 32x32)
     */
    fun evaluate(lumGrid: FloatArray?): MarsOodResult? {
        val sess = session ?: return null
        val environment = env ?: return null
        if (lumGrid == null || lumGrid.isEmpty()) return null

        return try {
            val input = resizeTo32(lumGrid)
            val shape = longArrayOf(1, 1, 32, 32)
            val buf = FloatBuffer.allocate(32 * 32)
            buf.put(input)
            buf.rewind()
            OnnxTensor.createTensor(environment, buf, shape).use { tensor ->
                val results = sess.run(mapOf("x" to tensor))
                results.use {
                    val scoreArr = (it.get(0).value as Array<*>) // may be float[] or Array
                    val score = extractScalar(it, 0)
                    val isOodV = extractScalar(it, 1)
                    val residEnergy = try {
                        extractResidualEnergy(it, 2)
                    } catch (_: Exception) {
                        0f
                    }
                    MarsOodResult(
                        score = score,
                        isOod = isOodV > 0.5f || score > 50f, // tau placeholder; score scale varies
                        residualEnergy = residEnergy
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "infer failed: ${e.message}")
            null
        }
    }

    private fun extractScalar(result: OrtSession.Result, index: Int): Float {
        val v = result.get(index).value
        return when (v) {
            is FloatArray -> v[0]
            is Array<*> -> {
                val row = v[0]
                when (row) {
                    is FloatArray -> row[0]
                    is Float -> row
                    else -> (row as Array<*>)[0].toString().toFloat()
                }
            }
            is Float -> v
            else -> 0f
        }
    }

    private fun extractResidualEnergy(result: OrtSession.Result, index: Int): Float {
        val v = result.get(index).value
        // residual [1,1,32,32]
        var sum = 0.0
        var n = 0
        fun walk(a: Any?) {
            when (a) {
                is FloatArray -> {
                    for (x in a) {
                        sum += kotlin.math.abs(x)
                        n++
                    }
                }
                is Array<*> -> a.forEach { walk(it) }
            }
        }
        walk(v)
        return if (n == 0) 0f else (sum / n).toFloat().coerceIn(0f, 1f)
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
        session = null
        env = null
        available = false
    }

    companion object {
        private const val TAG = "MarsOod"
        const val ASSET = "models/spiritscan_mars_ood.onnx"
    }
}
