package com.nscb.spiritscan.trial

import android.content.Context
import com.nscb.spiritscan.dsp.TrialStats
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

data class MetricResult(
    val name: String,
    val liveRate: Double,
    val shamRate: Double,
    val diff: Double,
    val p: Double
)

data class TrialState(
    /** 0 idle, 1 running, 2 done */
    val phase: Int = 0,
    val block: Int = 0,
    val blocks: Int = 0,
    val secondsLeft: Int = 0,
    val settling: Boolean = false,
    val marks: Int = 0,
    val message: String = "",
    val results: List<MetricResult> = emptyList(),
    val narrative: String = "",
    val savedPath: String = ""
)

/**
 * Sham-controlled trial. A pre-committed, balanced, randomly ordered schedule of blocks: LIVE (spirit box ON) and SHAM
 * (box OFF). The first 10 s of every block is a settle period and is not counted. Per block it counts voice-like events
 * and unexplained band anomalies (from the scanner) and your MARK taps. After the last block an exact permutation test
 * compares the block rates. The primary metric (voice-like events per minute) is fixed in advance; the others are
 * secondary and uncorrected.
 *
 * What it can show: whether the box being ON changes the event rate. What it cannot show: why. The box's own noise is
 * the first suspect, so an effect here is an instrument effect until it survives an empty-room repeat.
 */
class TrialEngine(
    private val scope: CoroutineScope,
    private val context: Context,
    private val setBox: (Boolean) -> Unit,
    private val counters: () -> LongArray,
    private val contextText: () -> String
) {
    private val _state = MutableStateFlow(TrialState())
    val state: StateFlow<TrialState> = _state

    private var job: Job? = null

    @Volatile
    private var markCount = 0
    private val markTimes = ArrayList<Long>()

    fun mark() {
        if (_state.value.phase != 1) return
        markCount++
        markTimes.add(System.currentTimeMillis())
        _state.value = _state.value.copy(marks = markCount)
    }

    fun stop() {
        job?.cancel()
        job = null
        setBox(false)
        _state.value = if (_state.value.phase == 1) TrialState(message = "Trial stopped. Nothing was saved.") else TrialState()
    }

    fun start(blocks: Int, blockSec: Int) {
        if (job != null) return
        val n = blocks.coerceIn(4, 20)
        val sec = blockSec.coerceIn(60, 600)
        markCount = 0
        markTimes.clear()
        job = scope.launch {
            val ctxStart = contextText()
            val live = TrialStats.randomOrder(n, System.nanoTime())
            val voice = DoubleArray(n)
            val band = DoubleArray(n)
            val marks = DoubleArray(n)
            try {
                for (i in 0 until n) {
                    setBox(live[i])
                    for (s in 0 until SETTLE) {
                        _state.value = TrialState(
                            phase = 1, block = i + 1, blocks = n, secondsLeft = sec - s,
                            settling = true, marks = markCount,
                            message = "Block ${i + 1}/$n settling (condition hidden)"
                        )
                        delay(1000)
                    }
                    val c0 = counters()
                    val m0 = markCount
                    for (s in SETTLE until sec) {
                        _state.value = TrialState(
                            phase = 1, block = i + 1, blocks = n, secondsLeft = sec - s,
                            settling = false, marks = markCount,
                            message = "Block ${i + 1}/$n counting (condition hidden)"
                        )
                        delay(1000)
                    }
                    val c1 = counters()
                    val minutes = (sec - SETTLE) / 60.0
                    voice[i] = (c1[0] - c0[0]) / minutes
                    band[i] = (c1[1] - c0[1]) / minutes
                    marks[i] = (markCount - m0) / minutes
                }
            } finally {
                setBox(false)
            }
            val results = ArrayList<MetricResult>()
            results.add(metric("voice-like events/min (PRIMARY)", voice, live))
            results.add(metric("unexplained band anomalies/min", band, live))
            results.add(metric("your marks/min", marks, live))
            val narrative = narrate(results, n, ctxStart)
            val path = save(live, voice, band, marks, sec, results, narrative, ctxStart)
            _state.value = TrialState(
                phase = 2, blocks = n, marks = markCount, results = results,
                narrative = narrative, savedPath = path, message = "Trial complete"
            )
            job = null
        }
    }

    private fun metric(name: String, rates: DoubleArray, live: BooleanArray): MetricResult {
        val r = TrialStats.permutationTest(rates, live)
        return MetricResult(name, r.liveMean, r.shamMean, r.diff, r.pValue)
    }

    private fun f(v: Double): String = "%.2f".format(v)

    private fun narrate(results: List<MetricResult>, n: Int, ctxStart: String): String {
        val v = results[0]
        val sb = StringBuilder()
        sb.append("Voice-like events per minute: box ON ${f(v.liveRate)} vs box OFF ${f(v.shamRate)} ")
        sb.append("(difference ${f(v.diff)}, exact permutation p = ${"%.3f".format(v.p)} over $n blocks).\n")
        if (v.p < 0.05 && v.diff > 0) {
            sb.append("Higher with the box on. Most likely cause: the box's own noise being picked up and shaped into speech-like ")
            sb.append("structure by the detector. Treat this as an instrument effect until the same result survives a repeat in an ")
            sb.append("empty, quiet room and a NULL TEST.\n")
        } else if (v.p < 0.05 && v.diff < 0) {
            sb.append("Lower with the box on, probably because its noise masks the room.\n")
        } else {
            sb.append("No reliable difference between box on and box off.\n")
        }
        sb.append("The other two metrics are secondary and not corrected for multiple comparisons; treat p < 0.017 as the bar.\n")
        sb.append("External context at the start: $ctxStart.")
        return sb.toString()
    }

    private fun save(
        live: BooleanArray, voice: DoubleArray, band: DoubleArray, marks: DoubleArray, sec: Int,
        results: List<MetricResult>, narrative: String, ctxStart: String
    ): String {
        return try {
            val dir = File(context.getExternalFilesDir(null), "trials")
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val f = File(dir, "trial_$stamp.json")
            val root = JSONObject()
            root.put("app", "SpiritScan 8.9")
            root.put("blockSeconds", sec)
            root.put("settleSeconds", SETTLE)
            root.put("contextAtStart", ctxStart)
            val blocks = JSONArray()
            for (i in live.indices) {
                val b = JSONObject()
                b.put("condition", if (live[i]) "LIVE" else "SHAM")
                b.put("voiceEventsPerMin", voice[i])
                b.put("bandAnomaliesPerMin", band[i])
                b.put("marksPerMin", marks[i])
                blocks.put(b)
            }
            root.put("blocks", blocks)
            val res = JSONArray()
            for (r in results) {
                val o = JSONObject()
                o.put("metric", r.name)
                o.put("liveRate", r.liveRate)
                o.put("shamRate", r.shamRate)
                o.put("difference", r.diff)
                o.put("pExactPermutation", r.p)
                res.put(o)
            }
            root.put("results", res)
            root.put("markTimesEpochMs", JSONArray(markTimes))
            root.put("summary", narrative)
            f.writeText(root.toString(2))
            f.absolutePath
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        private const val SETTLE = 10
    }
}
