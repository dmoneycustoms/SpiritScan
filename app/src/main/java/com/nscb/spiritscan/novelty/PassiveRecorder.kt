package com.nscb.spiritscan.novelty

import android.content.Context
import com.nscb.spiritscan.dsp.NoveltyCore
import com.nscb.spiritscan.dsp.NoveltySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Passive ingest: once a second it reads one 12-channel feature vector from the rest of the app, learns what "normal"
 * looks like for this phone and room (BASELINE), then scores every second against it with the ONNX autoencoder and a
 * Mahalanobis check. Captured events get a one-line explanation from live context (space weather, weather, quakes).
 * Raw vectors are appended to Android/data/<app>/files/passive/passive_YYYYMMDD.csv.
 */
class PassiveRecorder(
    private val context: Context,
    private val featureSource: () -> FloatArray,
    private val contextSource: () -> String
) {
    val core = NoveltyCore(NAMES)
    private val onnx = NoveltyOnnx(context)

    private val _state = MutableStateFlow<NoveltySnapshot?>(null)
    val state: StateFlow<NoveltySnapshot?> = _state

    @Volatile
    var running = false
        private set

    private var worker: Thread? = null
    private var csv: BufferedWriter? = null

    fun start(baselineSeconds: Int) {
        if (running) return
        running = true
        core.startLearn(baselineSeconds)
        openCsv()
        worker = thread(name = "passive-recorder", isDaemon = true) {
            var lines = 0
            while (running) {
                val t0 = System.currentTimeMillis()
                try {
                    val raw = featureSource()
                    val now = System.currentTimeMillis()
                    val w = core.push(raw, now)
                    if (w != null) {
                        core.finishStep(onnx.run(w), w)
                    }
                    if (core.phase() == NoveltyCore.PH_CALIB) calibrate()
                    val snap = core.snapshot()
                    for (e in snap.events) {
                        if (e.context.isEmpty()) e.context = contextSource()
                    }
                    writeCsv(raw, snap)
                    lines++
                    if (lines % 10 == 0) csv?.flush()
                    _state.value = snap
                } catch (_: Exception) {
                }
                val spent = System.currentTimeMillis() - t0
                try {
                    Thread.sleep(maxOf(20L, 1000L - spent))
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun calibrate() {
        val wins = core.calibrationWindows()
        val recs = ArrayList<FloatArray>()
        var ok = onnx.available
        if (ok) {
            for (w in wins) {
                val r = onnx.run(w)
                if (r == null) {
                    ok = false
                    break
                }
                recs.add(r)
            }
        }
        core.finishCalibration(wins, if (ok) recs else null)
    }

    fun relearn(baselineSeconds: Int) {
        if (!running) return
        core.startLearn(baselineSeconds)
    }

    fun clearEvents() {
        core.clearEvents()
    }

    fun stop() {
        running = false
        core.stop()
        try {
            worker?.join(1500)
        } catch (_: Exception) {
        }
        worker = null
        try {
            csv?.flush()
            csv?.close()
        } catch (_: Exception) {
        }
        csv = null
    }

    private fun openCsv() {
        try {
            val dir = File(context.getExternalFilesDir(null), "passive")
            dir.mkdirs()
            val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val f = File(dir, "passive_$day.csv")
            val isNew = !f.exists()
            val w = BufferedWriter(FileWriter(f, true))
            if (isNew) w.write("epoch_ms,phase," + NAMES.joinToString(",") + ",novelty_model,novelty_stats\n")
            csv = w
        } catch (_: Exception) {
            csv = null
        }
    }

    private fun writeCsv(raw: FloatArray, snap: NoveltySnapshot) {
        val w = csv ?: return
        try {
            val sb = StringBuilder()
            sb.append(System.currentTimeMillis()).append(',').append(snap.phase)
            for (v in raw) sb.append(',').append(v)
            sb.append(',').append(snap.aeZ).append(',').append(snap.mahaZ).append('\n')
            w.write(sb.toString())
        } catch (_: Exception) {
        }
    }

    companion object {
        val NAMES: Array<String> = arrayOf(
            "mag", "magZ", "residual", "audioZ", "speechRes", "camScore",
            "camMad", "specRes", "voice", "bandMax", "decode", "air"
        )
    }
}
