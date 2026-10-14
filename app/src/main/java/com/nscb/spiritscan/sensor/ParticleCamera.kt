package com.nscb.spiritscan.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.nscb.spiritscan.senses.ParticleEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteOrder

/**
 * Exclusive Camera2 session for the particle detector: manual exposure (1 s), high ISO, noise reduction / hot-pixel /
 * edge processing off, RAW_SENSOR when the phone offers it (YUV luma otherwise, with reduced sensitivity).
 * CameraX must be unbound first (the HUD does that when particle mode is on). The lens has to be covered.
 */
class ParticleCamera(private val context: Context, private val engine: ParticleEngine) {
    private val _info = MutableStateFlow("")
    val info: StateFlow<String> = _info

    @Volatile
    var running = false
        private set

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var chars: CameraCharacteristics? = null
    private var pixels = ShortArray(0)
    private var bytes = ByteArray(0)
    private var rawMode = false
    private var exposureSec = 1.0

    /** Returns an error message, or null when the session is being opened. */
    @SuppressLint("MissingPermission")
    fun start(exposureMs: Long = 1000L, isoWanted: Int = 3200): String? {
        if (running) return null
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return "Camera permission is needed"
        }
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var chosen: String? = null
        var chosenRaw = false
        try {
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
                val raw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                if (chosen == null || (raw && !chosenRaw)) {
                    chosen = id
                    chosenRaw = raw
                    if (raw) break
                }
            }
        } catch (e: Exception) {
            return "Could not list cameras: ${e.message}"
        }
        val camId = chosen ?: return "No rear camera found"
        val ch = cm.getCameraCharacteristics(camId)
        chars = ch
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return "No stream configuration"
        val format = if (chosenRaw) ImageFormat.RAW_SENSOR else ImageFormat.YUV_420_888
        val sizes = map.getOutputSizes(format) ?: return "No output sizes"
        val size = sizes.maxByOrNull { it.width.toLong() * it.height } ?: return "No output size"
        val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return "No manual exposure support"
        val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return "No manual ISO support"
        val minFrame = map.getOutputMinFrameDuration(format, size)
        val expNs = (exposureMs * 1_000_000L).coerceIn(expRange.lower, expRange.upper)
        val iso = isoWanted.coerceIn(isoRange.lower, isoRange.upper)
        exposureSec = expNs / 1e9
        rawMode = chosenRaw
        val ht = HandlerThread("particle-cam")
        ht.start()
        thread = ht
        val hd = Handler(ht.looper)
        handler = hd
        val rd = ImageReader.newInstance(size.width, size.height, format, 2)
        reader = rd
        rd.setOnImageAvailableListener({ r -> onImage(r) }, hd)
        _info.value = "${if (chosenRaw) "RAW" else "YUV (no RAW on this camera)"} ${size.width}x${size.height}, " +
            "${"%.1f".format(exposureSec)} s, ISO $iso"
        return try {
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    openSession(cam, rd, expNs, iso, maxOf(expNs, minFrame))
                }

                override fun onDisconnected(cam: CameraDevice) {
                    _info.value = "camera disconnected"
                    stop()
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    _info.value = "camera error $error (is another app using it?)"
                    stop()
                }
            }, hd)
            null
        } catch (e: Exception) {
            stop()
            "Could not open the camera: ${e.message}"
        }
    }

    @Suppress("DEPRECATION")
    private fun openSession(cam: CameraDevice, rd: ImageReader, expNs: Long, iso: Int, frameNs: Long) {
        try {
            cam.createCaptureSession(
                listOf(rd.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        try {
                            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL)
                            b.addTarget(rd.surface)
                            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                            b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
                            val c = chars
                            if (c != null) {
                                if (c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                                        ?.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF) == true
                                ) b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                                if (c.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
                                        ?.contains(CameraMetadata.HOT_PIXEL_MODE_OFF) == true
                                ) b.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
                                if (c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                                        ?.contains(CameraMetadata.EDGE_MODE_OFF) == true
                                ) b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                            }
                            s.setRepeatingRequest(b.build(), null, handler)
                            running = true
                        } catch (e: Exception) {
                            _info.value = "could not start the manual capture: ${e.message}"
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        _info.value = "camera session could not be configured"
                    }
                },
                handler
            )
        } catch (e: Exception) {
            _info.value = "could not create the capture session: ${e.message}"
        }
    }

    private fun onImage(r: ImageReader) {
        val img = try {
            r.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return
        try {
            val plane = img.planes[0]
            val w = img.width
            val h = img.height
            val buf = plane.buffer
            val now = System.currentTimeMillis()
            if (rawMode) {
                val stridePx = plane.rowStride / 2
                val n = minOf(stridePx * h, buf.remaining() / 2)
                if (pixels.size < stridePx * h) pixels = ShortArray(stridePx * h)
                buf.order(ByteOrder.nativeOrder()).asShortBuffer().get(pixels, 0, n)
                engine.process(pixels, w, h, stridePx, exposureSec, now)
            } else {
                val stride = plane.rowStride
                val ps = plane.pixelStride
                val total = buf.remaining()
                if (bytes.size < total) bytes = ByteArray(total)
                buf.get(bytes, 0, total)
                if (pixels.size < w * h) pixels = ShortArray(w * h)
                for (y in 0 until h) {
                    val row = y * stride
                    val orow = y * w
                    for (x in 0 until w) {
                        val idx = row + x * ps
                        pixels[orow + x] = if (idx < total) (bytes[idx].toInt() and 0xFF).toShort() else 0
                    }
                }
                engine.process(pixels, w, h, w, exposureSec, now)
            }
        } catch (e: Exception) {
            _info.value = "frame processing failed: ${e.message}"
        } finally {
            img.close()
        }
    }

    fun stop() {
        running = false
        try {
            session?.close()
        } catch (_: Exception) {
        }
        try {
            device?.close()
        } catch (_: Exception) {
        }
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        try {
            thread?.quitSafely()
        } catch (_: Exception) {
        }
        session = null
        device = null
        reader = null
        thread = null
        handler = null
    }
}
