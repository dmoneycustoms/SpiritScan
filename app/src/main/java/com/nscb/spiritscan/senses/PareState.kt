package com.nscb.spiritscan.senses

/**
 * One published pareidolia reading. [lines] holds up to four patterns, each as [kind, x0, y0, x1, y1, ...] with
 * kind 0 = face-like, 1 = figure-like and coordinates normalised 0..1 in upright map space.
 */
data class PareState(
    val n: Int,
    val chance: Double,
    val face: Boolean,
    val figure: Boolean,
    val lines: List<FloatArray>,
    val frames: Long,
    val faceFrames: Long,
    val expected: Double,
    val z: Double
)
