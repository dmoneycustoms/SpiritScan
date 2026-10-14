package com.nscb.spiritscan.trial

import android.content.Context
import com.nscb.spiritscan.senses.HarnessStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One planted-stimulus protocol. Levels run weakest first. [metric] is read at 10 Hz; [score] turns a window's samples into one number. */
class HarnessProtocol(
    val id: String,
    val title: String,
    val levels: List<String>,
    val levelText: List<String>,
    val windowSec: Int,
    val repsPerLevel: Int,
    val shams: Int,
    val threshold: Double,
    val unit: String,
    val requirement: () -> String?,
    val metric: () -> Double,
    val score: (List<Double>) -> Double = { s -> s.maxOrNull() ?: 0.0 }
)

data class HarnessRow(val label: String, val text: String)

data class HarnessState(
    /** 0 idle, 1 running, 2 done */
    val phase: Int = 0,
    val protocol: String = "",
    val window: Int = 0,
    val windows: Int = 0,
    val secondsLeft: Int = 0,
    val getReady: Boolean = false,
    val prompt: String = "",
    val message: String = "",
    val rows: List<HarnessRow> = emptyList(),
    val shamLine: String = "",
    val verdict: String = "",
    val savedPath: String = ""
)

/**
 * Ground-truth harness: for one channel, a randomised schedule of STIMULUS windows (at several levels) and SHAM windows
 * (stay still). Each window yields the channel's strongest response. Afterwards it reports, per level, how many windows
 * rose above EVERY sham window (a zero-false-alarm threshold learned in this very session), the channel's own threshold hits,
 * an AUC with an exact permutation p-value, and the weakest level that is reliably detected. You are told what comes next:
 * this measures sensitivity, it is not a blinded experiment.
 */
class HarnessEngine(
    private val scope: CoroutineScope,
    private val context: Context,
    val protocols: List<HarnessProtocol>
) {
    private val _state = MutableStateFlow(HarnessState())
    val state: StateFlow<HarnessState> = _state
    private var job: Job? = null

    fun stop() {
        job?.cancel()
        job = null
        _state.value = HarnessState()
    }

    fun start(index: Int) {
        if (job != null) return
        if (index < 0 || index >= protocols.size) return
        val p = protocols[index]
        val req = p.requirement()
        if (req != null) {
            _state.value = HarnessState(message = req)
            return
        }
        job = scope.launch {
            val sched = HarnessStats.schedule(p.levels.size, p.repsPerLevel, p.shams, System.nanoTime())
            val scores = DoubleArray(sched.size)
            val n = sched.size
            for (wi in 0 until n) {
                val lvl = sched[wi]
                val what = if (lvl < 0) "STAY STILL: no stimulus" else p.levelText[lvl]
                for (s in GET_READY downTo 1) {
                    _state.value = HarnessState(
                        phase = 1, protocol = p.title, window = wi + 1, windows = n, secondsLeft = s,
                        getReady = true, prompt = "NEXT: $what"
                    )
                    delay(1000)
                }
                val samples = ArrayList<Double>()
                val steps = p.windowSec * 10
                for (k in 0 until steps) {
                    _state.value = HarnessState(
                        phase = 1, protocol = p.title, window = wi + 1, windows = n,
                        secondsLeft = p.windowSec - k / 10, getReady = false, prompt = "NOW: $what"
                    )
                    if (k >= SETTLE * 10) {
                        val v = p.metric()
                        if (!v.isNaN()) samples.add(v)
                    }
                    delay(100)
                }
                scores[wi] = p.score(samples)
            }
            val rep = HarnessStats.analyze(p.levels.toTypedArray(), sched, scores, p.threshold)
            val rows = ArrayList<HarnessRow>()
            for (l in rep.levels) {
                rows.add(
                    HarnessRow(
                        l.label,
                        "${l.hitsEmpirical}/${l.n} above every sham · ${l.hitsFixed}/${l.n} over ${p.threshold}${p.unit} · " +
                            "median ${"%.1f".format(l.median)}"
                    )
                )
            }
            val shamLine = "sham windows: median ${"%.1f".format(rep.shamMedian)}, highest ${"%.1f".format(rep.shamMax)}, " +
                "false alarms at ${p.threshold}${p.unit}: ${rep.shamFalseFixed}/${rep.shamN}"
            val path = save(p, sched, scores, rep, shamLine)
            _state.value = HarnessState(
                phase = 2, protocol = p.title, windows = n, rows = rows, shamLine = shamLine,
                verdict = rep.verdict, savedPath = path, message = "Check complete"
            )
            job = null
        }
    }

    private fun save(
        p: HarnessProtocol, sched: IntArray, scores: DoubleArray, rep: HarnessStats.Report, shamLine: String
    ): String {
        return try {
            val dir = File(context.getExternalFilesDir(null), "harness")
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val f = File(dir, "harness_${p.id}_$stamp.json")
            val root = JSONObject()
            root.put("app", "SpiritScan 9.1")
            root.put("protocol", p.title)
            root.put("windowSeconds", p.windowSec)
            root.put("threshold", p.threshold)
            root.put("unit", p.unit)
            val ws = JSONArray()
            for (i in sched.indices) {
                val o = JSONObject()
                o.put("level", if (sched[i] < 0) "SHAM" else p.levels[sched[i]])
                o.put("score", scores[i])
                ws.put(o)
            }
            root.put("windows", ws)
            root.put("auc", rep.auc)
            root.put("pExactPermutation", rep.pValue)
            root.put("shamSummary", shamLine)
            root.put("verdict", rep.verdict)
            f.writeText(root.toString(2))
            f.absolutePath
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        private const val GET_READY = 5
        private const val SETTLE = 3
    }
}
