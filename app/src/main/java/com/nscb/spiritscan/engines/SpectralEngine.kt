package com.nscb.spiritscan.engines

import com.nscb.spiritscan.sensor.Sample9
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight spectral snapshot for HUD.
 * Uses a short DFT over |B| samples — not a full lab FFT, good enough for phone rates.
 *
 * Bands (approx, relative to stream rate):
 *  DC / slow · low · mid · mains50 · mains60 · high · broadband
 */
data class SpectralState(
    /** 8 normalized bin powers 0..1 for bar display */
    val bins: List<Float>,
    val binLabels: List<String>,
    /** peak band name */
    val peakBand: String,
    val peakPower: Float,
    /** mains-ish energy 50/60 */
    val mainsEnergy: Float,
    /** non-mains residual spectral energy (proxy for unknown) */
    val residualSpectrum: Float,
    val note: String
)

object SpectralEngine {
    private val labels = listOf("DC", "LOW", "MID", "50", "60", "HI", "BB", "UNK")

    fun analyze(window: List<Sample9>): SpectralState {
        if (window.size < 24) {
            return SpectralState(
                bins = List(8) { 0f },
                binLabels = labels,
                peakBand = "—",
                peakPower = 0f,
                mainsEnergy = 0f,
                residualSpectrum = 0f,
                note = "Need more samples…"
            )
        }

        // Take last N magnitudes, detrend mean
        val n = minOf(64, window.size)
        val raw = window.takeLast(n).map { it.magUt }
        val mean = raw.average().toFloat()
        val x = FloatArray(n) { raw[it] - mean }

        // Relative frequency bins using Goertzel-like power at target fractions of window
        // Map: DC uses residual mean energy; others use k relative to n
        fun powerAtK(k: Float): Float {
            if (k < 0.5f) {
                // near-DC: variance of slow component (block mean diffs)
                val block = maxOf(4, n / 8)
                var s = 0f
                var c = 0
                var i = 0
                while (i + block <= n) {
                    var m = 0f
                    for (j in 0 until block) m += x[i + j]
                    m /= block
                    s += m * m
                    c++
                    i += block
                }
                return if (c == 0) 0f else s / c
            }
            val kk = k.toInt().coerceIn(1, n / 2)
            val omega = (2.0 * PI * kk / n).toFloat()
            var re = 0f
            var im = 0f
            for (i in 0 until n) {
                val ang = omega * i
                re += x[i] * cos(ang)
                im += x[i] * sin(ang)
            }
            return (re * re + im * im) / (n * n)
        }

        // Heuristic k indices for ~phone GAME rate (~50 Hz): 
        // 50 Hz and 60 Hz land near mid-high bins of short window — relative energy still ranks.
        val pDc = powerAtK(0.25f)
        val pLow = powerAtK(1.5f)
        val pMid = powerAtK(3.5f)
        val p50 = powerAtK((n * 0.22f).coerceAtLeast(2f))
        val p60 = powerAtK((n * 0.26f).coerceAtLeast(2f))
        val pHi = powerAtK((n * 0.35f).coerceAtLeast(3f))
        val pBb = x.fold(0f) { a, v -> a + v * v } / n  // total variance proxy
        val pUnk = maxOf(0f, pBb - (p50 + p60) * 0.5f - pLow * 0.3f)

        val rawBins = floatArrayOf(pDc, pLow, pMid, p50, p60, pHi, pBb, pUnk)
        val maxP = rawBins.max().coerceAtLeast(1e-9f)
        val bins = rawBins.map { (it / maxP).coerceIn(0f, 1f) }

        val peakIdx = bins.indices.maxBy { bins[it] }
        val mains = ((bins[3] + bins[4]) * 0.5f).coerceIn(0f, 1f)
        // residual spectrum: energy not in mains / low motion-ish
        val residSpec = (bins[7] * 0.6f + bins[5] * 0.2f + bins[2] * 0.2f) * (1f - mains * 0.7f)
            .coerceIn(0f, 1f)

        val note = when {
            mains > 0.55f -> "Spectrum peak near mains (wiring)"
            bins[1] > 0.6f && bins[1] >= bins[peakIdx] * 0.9f -> "Low-band energy (motion / drift)"
            residSpec > 0.4f -> "Broadband / unknown spectral residual"
            else -> "Spectrum calm"
        }

        return SpectralState(
            bins = bins,
            binLabels = labels,
            peakBand = labels[peakIdx],
            peakPower = bins[peakIdx],
            mainsEnergy = mains,
            residualSpectrum = residSpec,
            note = note
        )
    }
}
