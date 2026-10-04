package com.nscb.spiritscan.sensor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.sin
import kotlin.random.Random

enum class SweepMode { HOP, WHITE, PINK, LOCK72, EVP }

class SpiritBox {
    @Volatile var on = false
    @Volatile var mode = SweepMode.HOP
    @Volatile var freq = 1200f
    @Volatile var rms = 0f
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    // Peak amplitude scale (0..32767). 5500 ≈ quiet enough to not drown the room.
    private val amplitude = 5500

    fun start() {
        if (on) return
        on = true
        val sr = 22050
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
        worker = thread(name = "spirit-box") {
            val buf = ShortArray(1024)
            var phase = 0.0
            var b0 = 0.0; var b1 = 0.0; var b2 = 0.0
            var hop = 180.0
            while (on) {
                hop = when (mode) {
                    SweepMode.HOP -> 180.0 + ((hop + 17) % 3920)
                    SweepMode.EVP -> 250.0 + ((hop + 11) % 1550)
                    SweepMode.LOCK72 -> 72.0
                    SweepMode.WHITE, SweepMode.PINK -> 1800.0
                }
                freq = hop.toFloat()
                var e = 0.0
                for (i in buf.indices) {
                    val w = Random.nextDouble() * 2 - 1
                    val noise = if (mode == SweepMode.PINK) {
                        b0 = 0.99886 * b0 + w * 0.0555179
                        b1 = 0.99332 * b1 + w * 0.0750759
                        b2 = 0.969 * b2 + w * 0.153852
                        (b0 + b1 + b2 + w * 0.1848) * 0.25
                    } else w * 0.28
                    phase += 2 * Math.PI * hop / sr
                    val v = (noise + 0.10 * sin(phase)).coerceIn(-1.0, 1.0)
                    buf[i] = (v * amplitude).toInt().toShort()
                    e += v * v
                }
                rms = kotlin.math.sqrt(e / buf.size).toFloat()
                track?.write(buf, 0, buf.size)
            }
        }
    }

    fun stop() {
        on = false
        worker = null
        try {
            track?.stop()
            track?.release()
        } catch (_: Exception) {
        }
        track = null
        rms = 0f
    }
}
