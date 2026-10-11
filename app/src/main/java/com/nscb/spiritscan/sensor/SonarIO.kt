package com.nscb.spiritscan.sensor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.nscb.spiritscan.senses.SonarEngine
import kotlin.concurrent.thread

/**
 * Speaker side of the ultrasonic sonar: plays the 18-20.5 kHz chirp frame back to back. The microphone side is the
 * scanner's wide 48 kHz stream, which feeds [SonarEngine] through SpiritScanner.wideListener.
 * Keep the level low: many pets hear 18+ kHz.
 */
class SonarIO(private val engine: SonarEngine) {
    @Volatile
    var running = false
        private set

    @Volatile
    var amplitude = 0.18

    private var track: AudioTrack? = null
    private var worker: Thread? = null

    fun start(): Boolean {
        if (running) return true
        val frame = SonarEngine.txFrame(amplitude)
        val min = AudioTrack.getMinBufferSize(
            SonarEngine.FS, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t: AudioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SonarEngine.FS)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (_: Exception) {
            return false
        }
        engine.reset()
        try {
            t.play()
        } catch (_: Exception) {
            try {
                t.release()
            } catch (_: Exception) {
            }
            return false
        }
        track = t
        running = true
        worker = thread(name = "sonar-tx", isDaemon = true) {
            val reps = 8
            val big = ShortArray(frame.size * reps)
            for (i in 0 until reps) System.arraycopy(frame, 0, big, i * frame.size, frame.size)
            while (running) {
                val w = try {
                    t.write(big, 0, big.size)
                } catch (_: Exception) {
                    -1
                }
                if (w < 0) break
            }
        }
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            worker?.join(800)
        } catch (_: Exception) {
        }
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
        worker = null
    }
}
