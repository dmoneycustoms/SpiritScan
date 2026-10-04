package com.nscb.spiritscan.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import com.nscb.spiritscan.engines.FocusGate

/**
 * Camera2 interop: push LENS_FOCUS_DISTANCE into FocusGate every capture.
 * Dual-pixel maps are still unavailable; diopters are the public API.
 */
object FocusCapture {

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun attachToAnalysis(builder: ImageAnalysis.Builder) {
        Camera2Interop.Extender(builder)
            .setSessionCaptureCallback(callback)
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun attachToPreview(builder: Preview.Builder) {
        Camera2Interop.Extender(builder)
            .setSessionCaptureCallback(callback)
    }

    private val callback = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: android.hardware.camera2.CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            FocusGate.updateFromCamera(focus)
        }
    }
}
