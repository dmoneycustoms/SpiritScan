package com.nscb.spiritscan.engines

import com.nscb.spiritscan.sensor.Sample9
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Optical-flow proxy + gyroscope consistency (Part 2.3 lite).
 *
 * True dense optical flow is heavy on mobile; we use:
 *  - frame residual as "visual motion energy"
 *  - gyro/accel from Sample9 as "phone motion energy"
 *  - independent motion = visual residual unexplained by phone motion
 *
 * Flag unknown only when residual is high AND phone is relatively still,
 * or residual far exceeds what gyro predicts.
 */
data class OpticalFlowState(
    val phoneMotion: Float,       // 0..1 from gyro/accel
    val visualMotion: Float,      // 0..1 from frame residual
    val independentMotion: Float, // residual not explained by phone
    val unknown: Boolean,
    val note: String
)

object OpticalFlowEngine {

    private var lastGyro = 0f
    private var lastResidual = 0f

    fun evaluate(
        sample: Sample9?,
        frameResidual: Float,
        noiseDominant: String? = null
    ): OpticalFlowState {
        val gyro = sample?.let {
            hypot(hypot(it.gyroX, it.gyroY), it.gyroZ)
        } ?: 0f
        val accTilt = sample?.let {
            abs(hypot(hypot(it.accX, it.accY), it.accZ) - 9.81f)
        } ?: 0f

        // Phone motion energy
        val phone = (
            (gyro / 2.5f).coerceIn(0f, 1f) * 0.65f +
                (accTilt / 3.5f).coerceIn(0f, 1f) * 0.35f
            ).coerceIn(0f, 1f)

        val visual = frameResidual.coerceIn(0f, 1f)

        // Expected visual motion scales with phone motion (shake → residual)
        val predicted = phone * 0.85f
        val independent = max(0f, visual - predicted * 1.15f)

        // Strong independent motion while phone relatively still
        val stillPhone = phone < 0.28f && noiseDominant != "MOTION"
        val unknown = stillPhone && independent > 0.10f && visual > 0.08f

        lastGyro = gyro
        lastResidual = visual

        val note = when {
            noiseDominant == "MOTION" || phone > 0.45f ->
                "Flow gated — phone moving (gyro)"
            unknown ->
                "Independent visual motion (not explained by gyro)"
            independent > 0.05f ->
                "Mild independent residual"
            else ->
                "Visual motion consistent with phone"
        }

        return OpticalFlowState(
            phoneMotion = phone,
            visualMotion = visual,
            independentMotion = independent.coerceIn(0f, 1f),
            unknown = unknown,
            note = note
        )
    }
}
