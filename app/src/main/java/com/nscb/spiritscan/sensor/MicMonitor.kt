package com.nscb.spiritscan.sensor

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Background mic level monitor for ambient baseline / unknown spikes.
 * Does not record to disk — RMS only.
 */
class MicMonitor {
    @Volatile var rms = 0f
        private set
    @Volatile var running = false
        private set

    private var record: AudioRecord? = null
    private var worker: Thread? = null

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
                val buf = ShortArray(min)
                while (running) {
                    val n = try {
                        ar.read(buf, 0, buf.size)
                    } catch (_: Exception) {
                        -1
                    }
                    if (n > 0) {
                        var e = 0.0
                        for (i in 0 until n) {
                            val v = buf[i] / 32768.0
                            e += v * v
                        }
                        val r = sqrt(e / n).toFloat().coerceIn(0f, 1f)
                        // light EMA so baseline isn't pure sample noise
                        rms = rms * 0.85f + r * 0.15f
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
    }
}
