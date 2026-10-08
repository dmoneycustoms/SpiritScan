package com.nscb.spiritscan.novelty

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.util.Collections

/**
 * Runs the novelty autoencoder (assets/models/novelty_ae.onnx) through ONNX Runtime.
 * Input x: [1, 192] float32 (16 s x 12 channels, standardised). Output recon: [1, 192].
 * The network is a small denoising autoencoder trained on synthetic multichannel sensor windows (see docs/passive-onnx.md).
 */
class NoveltyOnnx(context: Context) {

    @Volatile
    var available: Boolean = false
        private set

    private val env: OrtEnvironment?
    private val session: OrtSession?

    init {
        var e: OrtEnvironment? = null
        var s: OrtSession? = null
        try {
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(1)
            s = e.createSession(bytes, opts)
            available = true
            Log.i(TAG, "Loaded $ASSET")
        } catch (ex: Exception) {
            Log.w(TAG, "novelty model unavailable: ${ex.message}")
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

    fun run(window: FloatArray): FloatArray? {
        val sess = session ?: return null
        val environment = env ?: return null
        var tensor: OnnxTensor? = null
        var result: OrtSession.Result? = null
        return try {
            val fb = FloatBuffer.wrap(window)
            tensor = OnnxTensor.createTensor(environment, fb, longArrayOf(1L, window.size.toLong()))
            val inputs: Map<String, OnnxTensor> = Collections.singletonMap("x", tensor)
            result = sess.run(inputs)
            val v: Any? = result.get(0).value
            when (v) {
                is Array<*> -> v[0] as? FloatArray
                is FloatArray -> v
                else -> null
            }
        } catch (ex: Exception) {
            Log.w(TAG, "novelty infer failed: ${ex.message}")
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

    fun close() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "NoveltyOnnx"
        private const val ASSET = "models/novelty_ae.onnx"
    }
}
