package com.nscb.spiritscan.vision

import android.util.Log

/**
 * Optional OpenCV Farneback dense flow.
 *
 * To enable:
 *  1) Add product flavor or dependency, e.g.
 *       implementation("com.quickbirdstudios:opencv:4.5.3.0")
 *  2) Call OpenCVLoader.initDebug() once at app start
 *  3) available becomes true via reflection
 *
 * Without OpenCV AAR, [compute] returns null and DenseFlowEngine stays active.
 */
object OpenCvFarneback {
    private const val TAG = "OpenCvFlow"
    val available: Boolean by lazy { probe() }

    private fun probe(): Boolean {
        return try {
            Class.forName("org.opencv.video.Video")
            Class.forName("org.opencv.core.Mat")
            Log.i(TAG, "OpenCV classes present")
            true
        } catch (_: Exception) {
            Log.i(TAG, "OpenCV not on classpath — using DenseFlowEngine")
            false
        }
    }

    /**
     * Compute mean Farneback flow magnitude between two 8-bit gray ByteBuffers
     * (width x height). Returns 0..1 normalized energy or null if OpenCV missing.
     */
    fun meanFlowEnergy(
        prevGray: ByteArray,
        nextGray: ByteArray,
        width: Int,
        height: Int
    ): Float? {
        if (!available) return null
        if (prevGray.size < width * height || nextGray.size < width * height) return null
        return try {
            val matClass = Class.forName("org.opencv.core.Mat")
            val cvType = Class.forName("org.opencv.core.CvType")
            val video = Class.forName("org.opencv.video.Video")
            val core = Class.forName("org.opencv.core.Core")

            val cv8u = cvType.getField("CV_8UC1").getInt(null)
            val cv32fc2 = cvType.getField("CV_32FC2").getInt(null)

            val prev = matClass.getConstructor(Int::class.java, Int::class.java, Int::class.java)
                .newInstance(height, width, cv8u)
            val next = matClass.getConstructor(Int::class.java, Int::class.java, Int::class.java)
                .newInstance(height, width, cv8u)
            val flow = matClass.getConstructor(Int::class.java, Int::class.java, Int::class.java)
                .newInstance(height, width, cv32fc2)

            // put bytes — Mat.put(row,col, byte[])
            val put = matClass.getMethod("put", Int::class.java, Int::class.java, ByteArray::class.java)
            put.invoke(prev, 0, 0, prevGray.copyOf(width * height))
            put.invoke(next, 0, 0, nextGray.copyOf(width * height))

            // calcOpticalFlowFarneback(prev, next, flow, 0.5, 3, 15, 3, 5, 1.2, 0)
            val farneback = video.getMethod(
                "calcOpticalFlowFarneback",
                matClass, matClass, matClass,
                Double::class.java, Int::class.java, Int::class.java, Int::class.java,
                Int::class.java, Double::class.java, Int::class.java
            )
            farneback.invoke(null, prev, next, flow, 0.5, 3, 15, 3, 5, 1.2, 0)

            // Rough energy: mean abs of flow channels via Core.mean — simplified return
            // Without full Mat traversal API, return moderate energy signal
            val meanMethod = core.getMethod("mean", matClass)
            val scalar = meanMethod.invoke(null, flow)
            val valMethod = scalar.javaClass.getField("val")
            val vals = valMethod.get(scalar) as DoubleArray
            val mag = kotlin.math.sqrt(vals[0] * vals[0] + vals.getOrElse(1) { 0.0 } * vals.getOrElse(1) { 0.0 })
            (mag / 4.0).toFloat().coerceIn(0f, 1f)
        } catch (e: Exception) {
            Log.w(TAG, "Farneback failed: ${e.message}")
            null
        }
    }
}
