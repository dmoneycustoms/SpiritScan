package com.nscb.spiritscan.engines

import com.nscb.spiritscan.sensor.Sample9
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * True spectral analysis on |B| window:
 *  - estimate sample rate from timestamps
 *  - Hann window
 *  - radix-2 real FFT (Cooley–Tukey)
 *  - magnitude spectrum → HUD bands + mains / residual metrics
 */
data class SpectralState(
    /** 8 normalized band powers 0..1 for HUD bars */
    val bins: List<Float>,
    val binLabels: List<String>,
    val peakBand: String,
    val peakPower: Float,
    val mainsEnergy: Float,
    val residualSpectrum: Float,
    /** estimated sample rate Hz */
    val sampleRateHz: Float,
    /** peak frequency in Hz (0 if unknown) */
    val peakHz: Float,
    val note: String
)

object SpectralEngine {

    private const val N_FFT = 64  // power of 2

    fun analyze(window: List<Sample9>): SpectralState {
        val empty = SpectralState(
            bins = List(8) { 0f },
            binLabels = listOf("DC", "LO", "MID", "50", "60", "HI", "BB", "UNK"),
            peakBand = "—",
            peakPower = 0f,
            mainsEnergy = 0f,
            residualSpectrum = 0f,
            sampleRateHz = 0f,
            peakHz = 0f,
            note = "Need more samples…"
        )
        if (window.size < 16) return empty

        val take = min(N_FFT, window.size)
        val slice = window.takeLast(take)

        // --- sample rate from timestamps (ns) ---
        val fs = estimateFs(slice)
        val n = nextPow2(take).coerceAtMost(N_FFT)
        val mags = FloatArray(n)
        for (i in 0 until take) {
            mags[i] = slice[i].magUt
        }
        // zero-pad if needed
        for (i in take until n) mags[i] = mags[take - 1]

        // remove DC mean
        var mean = 0f
        for (i in 0 until n) mean += mags[i]
        mean /= n
        for (i in 0 until n) mags[i] -= mean

        // Hann window
        for (i in 0 until n) {
            val w = (0.5f * (1f - cos(2.0 * PI * i / (n - 1)))).toFloat()
            mags[i] *= w
        }

        val (re, im) = realFft(mags)
        val half = n / 2
        val magSpec = FloatArray(half + 1)
        var totalPow = 0f
        for (k in 0..half) {
            val p = re[k] * re[k] + im[k] * im[k]
            magSpec[k] = sqrt(p)
            totalPow += magSpec[k]
        }
        if (totalPow < 1e-12f) {
            return empty.copy(note = "Flat spectrum", sampleRateHz = fs)
        }

        // Hz per bin
        val df = if (fs > 1f) fs / n else 1f

        fun bandPower(f0: Float, f1: Float): Float {
            val k0 = max(0, (f0 / df).toInt())
            val k1 = min(half, (f1 / df).toInt())
            if (k1 < k0) return 0f
            var s = 0f
            for (k in k0..k1) s += magSpec[k]
            return s
        }

        // Physical bands (Hz) — clamped to Nyquist
        val nyq = fs / 2f
        val pDc = bandPower(0f, min(0.5f, nyq))
        val pLow = bandPower(0.5f, min(5f, nyq))
        val pMid = bandPower(5f, min(20f, nyq))
        // Mains: ±2 Hz around 50 and 60
        val p50 = bandPower(48f, min(52f, nyq))
        val p60 = bandPower(58f, min(62f, nyq))
        val pHi = bandPower(20f, min(45f, nyq)) + bandPower(62f, min(nyq, 100f))
        val pBb = totalPow
        // Unknown = energy outside DC/low/mains-ish
        val known = pDc + pLow + p50 + p60
        val pUnk = max(0f, totalPow - known)

        val raw = floatArrayOf(pDc, pLow, pMid, p50, p60, pHi, pBb, pUnk)
        val maxP = raw.max().coerceAtLeast(1e-12f)
        val bins = raw.map { (it / maxP).coerceIn(0f, 1f) }
        val labels = listOf("DC", "LO", "MID", "50", "60", "HI", "BB", "UNK")

        // Peak bin frequency
        var peakK = 1
        var peakVal = 0f
        for (k in 1..half) {
            if (magSpec[k] > peakVal) {
                peakVal = magSpec[k]
                peakK = k
            }
        }
        val peakHz = peakK * df
        val peakIdx = bins.indices.maxBy { bins[it] }
        val mains = ((p50 + p60) / totalPow).coerceIn(0f, 1f)
        val residSpec = (pUnk / totalPow).coerceIn(0f, 1f)

        val note = when {
            fs < 20f -> "Low sample rate (~${"%.0f".format(fs)} Hz) — coarse spectrum"
            mains > 0.25f -> "Mains energy elevated (50/60 Hz)"
            peakHz in 0.5f..5f && bins[1] > 0.5f -> "Low-band peak ~${"%.1f".format(peakHz)} Hz (motion/drift)"
            residSpec > 0.35f && mains < 0.2f -> "Residual spectral energy outside known bands"
            else -> "Peak ~${"%.1f".format(peakHz)} Hz  Fs~${"%.0f".format(fs)} Hz"
        }

        return SpectralState(
            bins = bins,
            binLabels = labels,
            peakBand = labels[peakIdx],
            peakPower = bins[peakIdx],
            mainsEnergy = mains,
            residualSpectrum = residSpec,
            sampleRateHz = fs,
            peakHz = peakHz,
            note = note
        )
    }

    private fun estimateFs(slice: List<Sample9>): Float {
        if (slice.size < 4) return 50f
        val dts = ArrayList<Float>(slice.size - 1)
        for (i in 1 until slice.size) {
            val dtNs = slice[i].tNs - slice[i - 1].tNs
            if (dtNs > 0) dts.add(dtNs / 1e9f)
        }
        if (dts.isEmpty()) return 50f
        dts.sort()
        val median = dts[dts.size / 2]
        if (median <= 1e-6f) return 50f
        return (1f / median).coerceIn(10f, 200f)
    }

    private fun nextPow2(n: Int): Int {
        var v = 1
        while (v < n) v = v shl 1
        return v
    }

    /** In-place radix-2 FFT; returns (re, im) length n */
    private fun realFft(x: FloatArray): Pair<FloatArray, FloatArray> {
        val n = x.size
        val re = x.copyOf()
        val im = FloatArray(n)

        // bit reversal
        var j = 0
        for (i in 1 until n - 1) {
            var bit = n shr 1
            while (j >= bit) {
                j -= bit
                bit = bit shr 1
            }
            j += bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wlenRe = cos(ang).toFloat()
            val wlenIm = sin(ang).toFloat()
            var i0 = 0
            while (i0 < n) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i0 + k]
                    val uIm = im[i0 + k]
                    val vRe = re[i0 + k + len / 2] * wRe - im[i0 + k + len / 2] * wIm
                    val vIm = re[i0 + k + len / 2] * wIm + im[i0 + k + len / 2] * wRe
                    re[i0 + k] = uRe + vRe
                    im[i0 + k] = uIm + vIm
                    re[i0 + k + len / 2] = uRe - vRe
                    im[i0 + k + len / 2] = uIm - vIm
                    val nWRe = wRe * wlenRe - wIm * wlenIm
                    wIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nWRe
                }
                i0 += len
            }
            len = len shl 1
        }
        return re to im
    }
}
