package com.nscb.spiritscan.engines

/**
 * Focus / depth gate (dual-pixel substitute).
 *
 * True Samsung Dual Pixel disparity maps are NOT exposed to third-party apps.
 * We use Camera2 LENS_FOCUS_DISTANCE (diopters) when available:
 *  - Very near focus + tiny high-residual blobs → treat as dust/orb candidates
 *  - Focus on mid/far + residual in focus band → allow unknown visual
 */
data class FocusGateState(
    val focusDiopters: Float,   // 0 = infinity; higher = closer
    val focusAvailable: Boolean,
    val dustLikely: Boolean,
    val note: String
)

object FocusGate {
    @Volatile
    var lastFocusDiopters: Float = 0f

    @Volatile
    var focusAvailable: Boolean = false

    fun updateFromCamera(focusDiopters: Float?) {
        if (focusDiopters != null && focusDiopters >= 0f) {
            lastFocusDiopters = focusDiopters
            focusAvailable = true
        }
    }

    fun evaluate(
        frameResidual: Float,
        oodCount: Int,
        independentFlow: Float
    ): FocusGateState {
        val d = lastFocusDiopters
        // High diopters = focused very near (lens hunting on dust close to glass)
        val near = d > 8f
        val dustLikely = near && frameResidual > 0.05f && oodCount == 0 && independentFlow < 0.2f

        val note = when {
            !focusAvailable -> "Focus distance unavailable (no dual-pixel API for apps)"
            dustLikely -> "Near-focus residual — likely dust/orb on lens"
            near -> "Focus near — treat small residuals cautiously"
            else -> "Focus mid/far — visual residual allowed"
        }

        return FocusGateState(
            focusDiopters = d,
            focusAvailable = focusAvailable,
            dustLikely = dustLikely,
            note = note
        )
    }
}
