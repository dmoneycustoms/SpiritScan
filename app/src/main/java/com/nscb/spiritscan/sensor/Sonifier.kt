package com.nscb.spiritscan.sensor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.concurrent.thread
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * Geiger-counter style feedback. Clicks arrive as a Poisson process whose rate follows a 0..1 level
 * (0.4 /s at rest, up to ~30 /s at full scale); optionally each click also taps the vibrator (at most 10 /s).
 * Eyes-free in the dark. Because speaker clicks are picked up by the microphone, the caller should only let
 * microphone-derived channels drive it when headphones are connected.
 */
class Sonifier(private val context: Context, private val level: () -> Float) {
    /** 0 off, 1 clicks, 2 clicks + haptics */
    @Volatile
    var mode = 0

    @Volatile
    private var running = false
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    fun headphonesConnected(): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
            }
        } catch (_: Exception) {
            false
        }
    }

    fun start() {
        if (running) return
        val sr = 22050
        val block = 220
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, block * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            return
        }
        try {
            t.play()
        } catch (_: Exception) {
            return
        }
        track = t
        running = true
        val vib: Vibrator? = try {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } catch (_: Exception) {
            null
        }
        worker = thread(name = "sonifier", isDaemon = true) {
            val buf = ShortArray(block)
            var lastBuzz = 0L
            while (running) {
                java.util.Arrays.fill(buf, 0.toShort())
                val m = mode
                if (m > 0) {
                    val lv = level().coerceIn(0f, 1f)
                    val rate = 0.4 + 30.0 * lv * lv
                    // expected clicks in this 10 ms block, Poisson sampled
                    val lam = rate * block / sr
                    var k = 0
                    var p = exp(-lam)
                    var cum = p
                    val u = Random.nextDouble()
                    while (u > cum && k < 4) {
                        k++
                        p *= lam / k
                        cum += p
                    }
                    for (c in 0 until k) {
                        val at = Random.nextInt(block - 60)
                        val amp = 9000.0 + 9000.0 * lv
                        for (i in 0 until 60) {
                            val env = exp(-i / 9.0)
                            val v = (Random.nextDouble() * 2.0 - 1.0) * amp * env
                            val idx = at + i
                            val sum = buf[idx] + v.toInt()
                            buf[idx] = sum.coerceIn(-32768, 32767).toShort()
                        }
                    }
                    if (k > 0 && m == 2 && vib != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastBuzz >= 100) {
                            lastBuzz = now
                            try {
                                vib.vibrate(VibrationEffect.createOneShot(8, 120))
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                val w = try {
                    t.write(buf, 0, buf.size)
                } catch (_: Exception) {
                    -1
                }
                if (w < 0) break
            }
        }
    }

    fun stop() {
        running = false
        try {
            worker?.join(500)
        } catch (_: Exception) {
        }
        try {
            track?.stop()
            track?.release()
        } catch (_: Exception) {
        }
        track = null
        worker = null
    }
}
