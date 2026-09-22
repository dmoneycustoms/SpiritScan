package com.nscb.spiritscan.sensor

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mic RMS + short PCM window for hop-notch / speech-band residual.
 */
class MicMonitor {
    @Volatile var rms = 0f
        private set
    /** Speech-band residual after notching hop (and 2nd harmonic), 0..1 */
    @Volatile var speechResidual = 0f
        private set
    @Volatile var hopFreqHz = 0f
    @Volatile var running = false
        private set

    private var record: AudioRecord? = null
    private var worker: Thread? = null

    // Latest mono float window (~64 ms at 16 kHz)
    private val win = FloatArray(1024)
    private val winLock = Any()

    fun start() {
        if (running) return
        val sr = 16000
        val min = AudioRecord.getMinBufferSize(
            sr,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return
        try {
            val ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sr,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                min * 2
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                return
            }
            record = ar
            running = true
            ar.startRecording()
            worker = thread(name = "mic-monitor", isDaemon = true) {
                val buf = ShortArray(min.coerceAtLeast(1024))
                while (running) {
                    val n = try {
                        ar.read(buf, 0, buf.size)
                    } catch (_: Exception) {
                        -1
                    }
                    if (n > 0) {
                        var e = 0.0
                        val take = minOf(n, win.size)
                        synchronized(winLock) {
                            for (i in 0 until take) {
                                val v = buf[i] / 32768f
                                win[i] = v
                                e += v * v
                            }
                        }
                        val r = sqrt(e / take).toFloat().coerceIn(0f, 1f)
                        rms = rms * 0.85f + r * 0.15f
                        speechResidual = speechResidual * 0.8f + analyzeSpeechResidual(sr) * 0.2f
                    } else {
                        try {
                            Thread.sleep(20)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } catch (_: Exception) {
            running = false
            try {
                record?.release()
            } catch (_: Exception) {
            }
            record = null
        }
    }

    /** Goertzel power at f, normalized roughly 0..1 */
    private fun goertzel(samples: FloatArray, n: Int, sr: Int, f: Float): Float {
        if (f <= 0f || f >= sr / 2f || n < 16) return 0f
        val k = (0.5f + n * f / sr).toInt()
        val w = (2.0 * PI * k / n)
        val cosine = cos(w).toFloat()
        val sine = sin(w).toFloat()
        val coeff = 2f * cosine
        var s0: Float
        var s1 = 0f
        var s2 = 0f
        for (i in 0 until n) {
            s0 = samples[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return (power / (n * n)).coerceIn(0f, 1f)
    }

    private fun analyzeSpeechResidual(sr: Int): Float {
        val n = win.size
        val copy = FloatArray(n)
        synchronized(winLock) {
            System.arraycopy(win, 0, copy, 0, n)
        }
        // Total energy
        var total = 0.0
        for (i in 0 until n) total += copy[i] * copy[i]
        val tot = sqrt(total / n).toFloat().coerceAtLeast(1e-6f)

        val hop = hopFreqHz
        // Notch hop + 2nd harmonic contribution (approx by subtracting band powers)
        var hopPow = 0f
        if (hop > 40f) {
            hopPow += goertzel(copy, n, sr, hop)
            hopPow += goertzel(copy, n, sr, hop * 2f) * 0.5f
        }

        // Speech-ish bands (rough centers)
        val speech = (
            goertzel(copy, n, sr, 400f) +
                goertzel(copy, n, sr, 800f) +
                goertzel(copy, n, sr, 1200f) +
                goertzel(copy, n, sr, 1800f) +
                goertzel(copy, n, sr, 2400f)
            ) / 5f

        // Residual speech = speech energy not explained by hop bleed
        val residual = (speech - hopPow * 0.35f).coerceAtLeast(0f)
        return (residual / (tot + 0.02f)).coerceIn(0f, 1f)
    }

    fun stop() {
        running = false
        try {
            worker?.join(300)
        } catch (_: Exception) {
        }
        worker = null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        rms = 0f
        speechResidual = 0f
    }
}
