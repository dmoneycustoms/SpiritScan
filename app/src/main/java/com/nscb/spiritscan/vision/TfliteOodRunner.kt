package com.nscb.spiritscan.vision

import android.content.Context
import android.util.Log

/**
 * Placeholder for optional TFLite vision OOD.
 * Does not depend on TensorFlow libraries so the APK builds without model/native conflicts.
 * When you add real weights, replace this with an Interpreter-based runner and
 * assets/models/vision_ood.tflite.
 */
class TfliteOodRunner(context: Context) {
    val available: Boolean = false

    init {
        val exists = try {
            context.assets.open("models/vision_ood.tflite").close()
            true
        } catch (_: Exception) {
            false
        }
        Log.i(
            "TfliteOod",
            if (exists) "vision_ood.tflite present but runner is stub — wire Interpreter later"
            else "No vision_ood.tflite — OOD uses ML Kit + dense flow"
        )
    }

    fun score(features: FloatArray): Float? = null

    fun close() {
        // no-op
    }
}
