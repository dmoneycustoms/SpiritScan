package com.nscb.spiritscan.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.nscb.spiritscan.engines.FusionState
import com.nscb.spiritscan.entity.EntityOutput
import kotlin.math.sin

/* ============================
   AUDIO FX ENGINE (v8.3 update)
   QIDA pulse tone, SDE distortion, Omega hum,
   magnetic tick, interference alarm, composite mixer.
   ============================ */
class AudioEngine(context: Context) {

    private val sampleRate = 44100
    private val bufferSize = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
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
        .setBufferSizeInBytes(bufferSize)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    init {
        track.play()
    }

    private fun tone(freq: Float, amp: Float): ShortArray {
        val buf = ShortArray(256)
        for (i in buf.indices) {
            val v = sin(2.0 * Math.PI * freq * i / sampleRate) * amp
            buf[i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return buf
    }

    fun playQida(qida: Float) {
        val freq = 220f + (qida * 440f)
        val amp = qida.coerceIn(0f, 1f)
        track.write(tone(freq, amp), 0, 256)
    }

    fun playSde(sde: Float) {
        val freq = 80f + (sde * 120f)
        val amp = (sde * 0.6f).coerceIn(0f, 1f)
        track.write(tone(freq, amp), 0, 256)
    }

    fun playOmega(omega: Float) {
        val freq = 40f + (omega * 60f)
        val amp = (omega * 0.4f).coerceIn(0f, 1f)
        track.write(tone(freq, amp), 0, 256)
    }

    fun playMag(mag: Float) {
        val freq = 10f + ((mag / 60f).coerceIn(0f, 1f) * 40f)
        val amp = ((mag / 60f).coerceIn(0f, 1f) * 0.3f)
        track.write(tone(freq, amp), 0, 256)
    }

    fun playInterference(active: Boolean) {
        if (!active) return
        track.write(tone(880f, 0.7f), 0, 256)
    }

    fun playComposite(c: Float) {
        val freq = 120f + (c * 300f)
        val amp = (c * 0.5f).coerceIn(0f, 1f)
        track.write(tone(freq, amp), 0, 256)
    }

    fun apply(output: EntityOutput, fusion: FusionState) {
        playQida(output.qida)
        playSde(output.sdeComposite)
        playOmega(output.omegaTrust)
        playMag(output.magUt)
        playInterference(!output.sdeOk)
        playComposite(fusion.composite)
    }

    fun release() {
        try { track.stop(); track.release() } catch (_: Exception) {}
    }
}
