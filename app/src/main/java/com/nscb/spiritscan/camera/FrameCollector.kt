package com.nscb.spiritscan.camera

import com.nscb.spiritscan.ScanViewModel
import com.nscb.spiritscan.entity.SurveySnap

/**
 * Receives downsampled camera frames and forwards them into the ViewModel.
 * Currently used for vision residual / luminance signals.
 * Full ONNX vision path can be hooked here later.
 */
class FrameCollector(
    private val viewModel: ScanViewModel
) {
    fun onFrame(frame: FloatArray, survey: SurveySnap? = null, note: String = "") {
        // Lightweight vision residual estimate from luminance distribution
        if (frame.isEmpty()) return
        var sum = 0.0
        var sumSq = 0.0
        for (v in frame) {
            val d = v.toDouble()
            sum += d
            sumSq += d * d
        }
        val n = frame.size.toDouble()
        val mean = sum / n
        val variance = (sumSq / n) - (mean * mean)
        val residual = variance.toFloat().coerceIn(0f, 1f)

        // Feed a soft vision residual into the existing pipeline if the ViewModel exposes a hook.
        // For now this is a no-op-safe call site; ScanViewModel already drives sensors.
        viewModel.onVisionFrame(residual)
    }
}
