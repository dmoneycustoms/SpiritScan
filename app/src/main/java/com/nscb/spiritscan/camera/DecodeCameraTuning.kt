package com.nscb.spiritscan.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera

/**
 * Makes the sensor behave like a measuring instrument while DECODE is active.
 *
 * Everything the camera "helps" with is something the decoder would read as an anomaly:
 *  - OIS / EIS shift the image by sub-pixel amounts that the ego-motion search cannot see
 *  - heavy noise reduction smears pixels across time and hides real transients
 *  - auto exposure / white balance ramps look like global scene changes
 *
 * Each option is only set if the device advertises support, so unsupported HALs are never
 * handed a value they might reject.
 */
object DecodeCameraTuning {

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun applyStable(camera: Camera) {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val b = CaptureRequestOptions.Builder()

        b.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )

        val ois = info.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
        )
        if (ois != null && ois.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
            b.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }

        val nr = info.getCameraCharacteristic(
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
        )
        if (nr != null && nr.contains(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)) {
            b.setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
            )
        }

        Camera2CameraControl.from(camera.cameraControl).addCaptureRequestOptions(b.build())
    }

    /** Lock AE + AWB once they have converged. */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun lockExposure(camera: Camera) {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val aeOk = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        val awbOk = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        val b = CaptureRequestOptions.Builder()
        if (aeOk) b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        if (awbOk) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        Camera2CameraControl.from(camera.cameraControl).addCaptureRequestOptions(b.build())
    }

    /** Hand the camera back to the normal auto pipeline. */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun release(camera: Camera) {
        Camera2CameraControl.from(camera.cameraControl).clearCaptureRequestOptions()
    }
}
