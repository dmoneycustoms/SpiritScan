package com.nscb.spiritscan.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.nscb.spiritscan.engines.FusionState
import com.nscb.spiritscan.entity.EntityOutput
import kotlin.math.sin

/**
 * Quiet, event-based sonification.
 * Default volume is very low so SpiritBox remains audible.
 * Continuous multi-tone spam is disabled.
 */
class AudioEngine(context: Context) {

    // Master volume 0..1 — keep low so spirit box can be heard
    var masterVolume: Float = 0.06f

    // Set true only if user explicitly enables engine tones
    var enabled: Boolean = false

    private val sampleRate = 22050
    private val bufferSize = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(bufferSize * 2)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private var lastPlayMs = 0L
    private val minIntervalMs = 400L

    init {
        try {
            track.play()
        } catch (_: Exception) {
        }
    }

    private fun tone(freq: Float, amp: Float, samples: Int = 512): ShortArray {
        val a = (amp * masterVolume).coerceIn(0f, 0.25f)
        val buf = ShortArray(samples)
        for (i in buf.indices) {
            val v = sin(2.0 * Math.PI * freq * i / sampleRate) * a
            buf[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return buf
    }

    private fun canPlay(): Boolean {
        if (!enabled) return false
        val now = System.currentTimeMillis()
        if (now - lastPlayMs < minIntervalMs) return false
        lastPlayMs = now
        return true
    }

    /** Short interference chirp only when interference is active. */
    fun playInterference(active: Boolean) {
        if (!active || !canPlay()) return
        try {
            track.write(tone(660f, 0.35f, 384), 0, 384)
        } catch (_: Exception) {
        }
    }

    /** Optional short composite tick (rate-limited). */
    fun playComposite(c: Float) {
        if (c < 0.55f || !canPlay()) return
        try {
            track.write(tone(180f + c * 120f, 0.2f, 256), 0, 256)
        } catch (_: Exception) {
        }
    }

    /**
     * Called every sensor tick.
     * By default does almost nothing so SpiritBox stays clear.
     * Enable with audioEngine.enabled = true if you want soft cues.
     */
    fun apply(output: EntityOutput, fusion: FusionState) {
        if (!enabled) return
        // Only interference cue — no continuous multi-tone layer
        playInterference(!output.sdeOk)
    }

    fun release() {
        try {
            track.stop()
            track.release()
        } catch (_: Exception) {
        }
    }
}
