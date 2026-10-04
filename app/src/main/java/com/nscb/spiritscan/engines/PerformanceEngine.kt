package com.nscb.spiritscan.engines

/* ============================
   PERFORMANCE STATE MODEL
   ============================ */
data class PerformanceState(
    val fusionWarm: Boolean,
    val frameSkip: Int,
    val shaderThrottle: Float,
    val avgFrameMs: Float
)

/* ============================
   PERFORMANCE ENGINE
   Warm-start + frame skipping + throttle + smoothing.
   ============================ */
class PerformanceEngine {

    private var frameCounter = 0
    private var frameAccum = 0f
    private var warmed = false

    fun warmFusion(engine: FusionEngine) {
        warmed = true
    }

    fun shouldSkipFrame(skip: Int): Boolean {
        frameCounter++
        return frameCounter % skip != 0
    }

    fun throttleShader(base: Float, throttle: Float): Float {
        return base * throttle
    }

    fun updateFrameTime(deltaMs: Float): Float {
        frameAccum = (frameAccum * 0.9f) + (deltaMs * 0.1f)
        return frameAccum
    }

    fun buildState(
        frameSkip: Int,
        shaderThrottle: Float,
        avgFrameMs: Float
    ): PerformanceState {
        return PerformanceState(
            fusionWarm = warmed,
            frameSkip = frameSkip,
            shaderThrottle = shaderThrottle,
            avgFrameMs = avgFrameMs
        )
    }
}
