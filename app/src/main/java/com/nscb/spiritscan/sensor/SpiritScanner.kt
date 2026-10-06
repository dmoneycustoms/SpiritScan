package com.nscb.spiritscan.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.nscb.spiritscan.dsp.ScanEngine
import com.nscb.spiritscan.dsp.ScanSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

/**
 * Spirit Box capture side. Two microphone streams feed [ScanEngine]:
 *  - WIDE  : 48 kHz, UNPROCESSED (falls back to MIC). No AGC / noise suppression, up to ~23 kHz for the band scan.
 *  - VOICE : 16 kHz, VOICE_COMMUNICATION with the platform echo canceller ON (so the box's own speaker output is
 *            removed before voice analysis) and noise suppression / AGC OFF. If that stream cannot be opened the wide
 *            stream is decimated instead, and the HUD says so (no echo cancellation then).
 * The phone has no AM/FM tuner, so a classic radio-sweep spirit box is not possible; the SweepMode.SCAN / GLIDE output
 * is synthesised.
 */
class SpiritScanner(private val context: Context) {
    val engine = ScanEngine()

    private val _state = MutableStateFlow<ScanSnapshot?>(null)
    val state: StateFlow<ScanSnapshot?> = _state

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error

    @Volatile
    var running = false
        private set

    private var wideRec: AudioRecord? = null
    private var voiceRec: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var wideThread: Thread? = null
    private var voiceThread: Thread? = null
    private var pubThread: Thread? = null

    @SuppressLint("MissingPermission")
    private fun openRecord(sr: Int, sources: List<Pair<Int, String>>): Pair<AudioRecord, String>? {
        val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        for ((src, name) in sources) {
            try {
                val rec = AudioRecord(
                    src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, sr / 2) * 2
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) return Pair(rec, name)
                rec.release()
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun attachEffects(rec: AudioRecord): String {
        val sb = StringBuilder()
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(rec.audioSessionId)
                aec?.setEnabled(true)
                sb.append("+AEC")
            }
        } catch (_: Exception) {
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(rec.audioSessionId)
                ns?.setEnabled(false)
            }
        } catch (_: Exception) {
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(rec.audioSessionId)
                agc?.setEnabled(false)
            }
        } catch (_: Exception) {
        }
        return sb.toString()
    }

    fun start() {
        if (running) return
        _error.value = ""
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _error.value = "Microphone permission is needed for the scan"
            return
        }
        val wide = openRecord(
            48000,
            listOf(
                MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
                MediaRecorder.AudioSource.MIC to "MIC"
            )
        )
        if (wide == null) {
            _error.value = "Could not open the 48 kHz microphone (it may be in use)"
            return
        }
        val voice = openRecord(
            16000,
            listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMM",
                MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOG"
            )
        )
        val wideRecord = wide.first
        var voiceDesc = "decimated from 48 kHz (no echo cancel)"
        var voiceRecord: AudioRecord? = null
        if (voice != null) {
            voiceRecord = voice.first
            val fx = if (voice.second == "VOICE_COMM") attachEffects(voice.first) else ""
            voiceDesc = "16 kHz ${voice.second}$fx"
        }
        try {
            wideRecord.startRecording()
            voiceRecord?.startRecording()
        } catch (e: Exception) {
            _error.value = "Microphone start failed: ${e.message}"
            try {
                wideRecord.release()
            } catch (_: Exception) {
            }
            try {
                voiceRecord?.release()
            } catch (_: Exception) {
            }
            return
        }
        wideRec = wideRecord
        voiceRec = voiceRecord
        engine.setSources("48 kHz ${wide.second}", voiceDesc)
        engine.fastAdapt()
        running = true
        val decimateVoice = voiceRecord == null

        wideThread = thread(name = "scan-wide") {
            val buf = ShortArray(1920)
            val dec = ShortArray(640)
            var acc = 0
            var cnt = 0
            var di = 0
            while (running) {
                val r = try {
                    wideRecord.read(buf, 0, buf.size)
                } catch (_: Exception) {
                    -1
                }
                if (r < 0) break
                if (r == 0) continue
                engine.pushWide(buf, r)
                if (decimateVoice) {
                    for (i in 0 until r) {
                        acc += buf[i].toInt()
                        cnt++
                        if (cnt == 3) {
                            dec[di++] = (acc / 3).toShort()
                            acc = 0
                            cnt = 0
                            if (di == dec.size) {
                                engine.pushVoice(dec, di)
                                di = 0
                            }
                        }
                    }
                }
            }
        }
        val vr = voiceRecord
        if (vr != null) {
            voiceThread = thread(name = "scan-voice") {
                val buf = ShortArray(640)
                while (running) {
                    val r = try {
                        vr.read(buf, 0, buf.size)
                    } catch (_: Exception) {
                        -1
                    }
                    if (r < 0) break
                    if (r == 0) continue
                    engine.pushVoice(buf, r)
                }
            }
        }
        pubThread = thread(name = "scan-publish") {
            while (running) {
                try {
                    _state.value = engine.snapshot()
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(120)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            wideThread?.join(600)
            voiceThread?.join(600)
            pubThread?.join(600)
        } catch (_: Exception) {
        }
        try {
            aec?.release()
            ns?.release()
            agc?.release()
        } catch (_: Exception) {
        }
        aec = null
        ns = null
        agc = null
        for (r in listOf(wideRec, voiceRec)) {
            try {
                r?.stop()
            } catch (_: Exception) {
            }
            try {
                r?.release()
            } catch (_: Exception) {
            }
        }
        wideRec = null
        voiceRec = null
        wideThread = null
        voiceThread = null
        pubThread = null
    }

    fun setOwnHz(hz: Double) {
        engine.setOwnHz(hz)
    }
}
