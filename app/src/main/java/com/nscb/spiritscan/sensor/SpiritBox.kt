package com.nscb.spiritscan.sensor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.sin
import kotlin.random.Random

/** SCAN = radio-style scanning (random-dwell band-limited noise); GLIDE = slow log sweep 150 Hz..7 kHz. */
enum class SweepMode { HOP, WHITE, PINK, LOCK72, EVP, SCAN, GLIDE }

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
            var scanC = 1000.0
            var scanLeft = 0
            var glide = 0.0
            var fx1 = 0.0; var fx2 = 0.0; var fy1 = 0.0; var fy2 = 0.0
            var fb0 = 0.0; var fb2 = 0.0; var fa1 = 0.0; var fa2 = 0.0
            while (on) {
                hop = when (mode) {
                    SweepMode.HOP -> 180.0 + ((hop + 17) % 3920)
                    SweepMode.EVP -> 250.0 + ((hop + 11) % 1550)
                    SweepMode.LOCK72 -> 72.0
                    SweepMode.SCAN -> {
                        if (scanLeft <= 0) {
                            scanC = Math.exp(Random.nextDouble(Math.log(200.0), Math.log(6500.0)))
                            scanLeft = Random.nextInt(1, 7)
                        }
                        scanLeft--
                        scanC
                    }
                    SweepMode.GLIDE -> {
                        glide = (glide + 0.004) % 1.0
                        150.0 * Math.pow(7000.0 / 150.0, glide)
                    }
                    SweepMode.WHITE, SweepMode.PINK -> 1800.0
                }
                freq = hop.toFloat()
                val filtered = mode == SweepMode.SCAN || mode == SweepMode.GLIDE
                if (filtered) {
                    // RBJ band-pass, Q = 6, centred on the current scan frequency
                    val w0 = 2 * Math.PI * hop / sr
                    val alpha = sin(w0) / (2 * 6.0)
                    val a0 = 1 + alpha
                    fb0 = alpha / a0
                    fb2 = -alpha / a0
                    fa1 = -2 * Math.cos(w0) / a0
                    fa2 = (1 - alpha) / a0
                }
                val sineGain = if (filtered) 0.0 else 0.10
                var e = 0.0
                for (i in buf.indices) {
                    val w = Random.nextDouble() * 2 - 1
                    val noise = if (filtered) {
                        val y = fb0 * w + fb2 * fx2 - fa1 * fy1 - fa2 * fy2
                        fx2 = fx1
                        fx1 = w
                        fy2 = fy1
                        fy1 = y
                        (y * 3.2).coerceIn(-1.0, 1.0)
                    } else if (mode == SweepMode.PINK) {
                        b0 = 0.99886 * b0 + w * 0.0555179
                        b1 = 0.99332 * b1 + w * 0.0750759
                        b2 = 0.969 * b2 + w * 0.153852
                        (b0 + b1 + b2 + w * 0.1848) * 0.25
                    } else w * 0.28
                    phase += 2 * Math.PI * hop / sr
                    val v = (noise + sineGain * sin(phase)).coerceIn(-1.0, 1.0)
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
