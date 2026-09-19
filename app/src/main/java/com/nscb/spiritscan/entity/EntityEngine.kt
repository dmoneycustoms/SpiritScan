package com.nscb.spiritscan.entity

import com.nscb.spiritscan.ml.OlympusOut
import com.nscb.spiritscan.ml.OmegaOut
import com.nscb.spiritscan.ml.QidaOut
import com.nscb.spiritscan.ml.SdeOut
import com.nscb.spiritscan.ml.classifyJones
import com.nscb.spiritscan.ml.runJonesHv
import com.nscb.spiritscan.ml.runOlympus
import com.nscb.spiritscan.ml.runOmega
import com.nscb.spiritscan.ml.runQida
import com.nscb.spiritscan.ml.runSde
import com.nscb.spiritscan.sensor.Sample9
import com.nscb.spiritscan.sensor.Welford
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class MagCell(val gx: Int, val gy: Int, var magUt: Float, var z: Float, var n: Int)

data class SurveySnap(
    val activity: String,
    val score: Float,
    val note: String,
    val magUt: Float,
    val zMag: Float,
    val thermalDelta: Float,
    val ambientC: Float?,
    val lux: Float?,
    val heading: Float,
    val cells: List<MagCell>,
    val xM: Float,
    val yM: Float,
)

data class EntityOutput(
    val jonesLabel: String,
    val jonesScore: Float,
    val magUt: Float,
    val qida: Float,
    val omegaTrust: Float,
    val sdeOk: Boolean,
    val sdeComposite: Float,
    val zMag: Float,
    val calibrated: Boolean,
    val calProgress: Float,
    val survey: SurveySnap,
    val note: String,
    // v8.3 upgrade: residual channel exposed to Residual mode + fusion
    val residualLevel: Float = 0f,
    val residualConfidence: Float = 0f,
    val residualDelta: Float = 0f,
)

class EntityEngine {
    private val magBase = Welford()
    private val visBase = Welford()
    private var started = 0L
    private var lastStep = 0L
    private var lastAcc = 0f
    private var xM = 0f
    private var yM = 0f
    private var walking = false
    private val cells = LinkedHashMap<String, MagCell>()
    private var scoreEma = 0f
    var calUntil = 0L

    fun setWalking(on: Boolean) { walking = on }
    fun resetSurvey() {
        cells.clear(); xM = 0f; yM = 0f; scoreEma = 0f
    }
    fun startCal(now: Long = System.currentTimeMillis()) {
        magBase.reset(); visBase.reset(); calUntil = now + 8000
    }

    fun process(
        sample: Sample9,
        window: List<Sample9>,
        visResidual: Float,
        audioRms: Float,
        heading: Float,
        ambientC: Float?,
        lux: Float?,
        lumHot: Float,
        lumCold: Float,
    ): EntityOutput {
        val now = System.currentTimeMillis()
        if (started == 0L) started = now
        val magUt = sample.magUt
        val calibrating = now < calUntil || magBase.n < 40
        if (calibrating) magBase.push(magUt.toDouble())
        if (calibrating) visBase.push(visResidual.toDouble())
        val zMag = magBase.z(magUt.toDouble())
        // Mag-only Laplacian proxy — do NOT feed camera residual into Jones/SDE
        val magLap = abs(zMag) / 10f
        val jonesHv = runJonesHv(
            sample.magX / 80f, sample.magY / 80f, 0.35f, 1.1f, 2.4f,
            magLap, 0.55f, abs(zMag) / 8f, 0.85f, 0.08f,
        )
        val mag = window.map { it.magUt }
        val mains = mainsHum(mag)
        val drift = slowDrift(mag)
        val feats = floatArrayOf(
            0.5f, 0.5f, 1f - mains, 1f - drift, jonesHv.envelope, if (jonesHv.harmonicOk) 1f else 0.3f,
            jonesHv.routeWeight, 0.7f, 0.6f, 0.65f, drift, 0.5f, 0.1f, mains,
        )
        val sde: SdeOut = runSde(feats)
        val G = window.takeLast(20).map {
            floatArrayOf(it.magX, it.magY, it.magZ, 0f)
        }.toTypedArray().ifEmpty { arrayOf(floatArrayOf(sample.magX, sample.magY, sample.magZ, 0f)) }
        val qida: QidaOut = runQida(G, 0.02f)
        // Coincidence for Jones entity path — MAG ONLY (vision was swinging labels when lens covered)
        val coin = min(1f, if (abs(zMag) > 5f && (magUt < 30f || magUt > 80f)) 0.35f else 0f)
        val (label, p) = classifyJones(feats, qida.residual, mains, drift, coin)
        // Omega still may note vision as soft context only
        val omega: OmegaOut = runOmega(p, qida.residual, mains, 0.5f, 0.6f, 0.5f, 0.4f, coin)
        val oly: OlympusOut = runOlympus(p, 0.9f, 3f, 6f, qida.geodesic, 2f, 0.01f, 10f, 144f, 144f, 1f, 0.2f, 0.15f, 1f)

        val accH = hypot(sample.accX, sample.accY)
        if (walking && accH > 1.35f && accH - lastAcc > 0.4f && now - lastStep > 280) {
            lastStep = now
            val rad = Math.toRadians(heading.toDouble())
            xM = (xM + kotlin.math.sin(rad).toFloat() * 0.72f).coerceIn(-10f, 10f)
            yM = (yM + kotlin.math.cos(rad).toFloat() * 0.72f).coerceIn(-10f, 10f)
            val gx = (xM / 0.85f).toInt()
            val gy = (yM / 0.85f).toInt()
            val key = "$gx,$gy"
            val prev = cells[key]
            if (prev != null) {
                prev.n += 1
                prev.magUt += (magUt - prev.magUt) / prev.n
                prev.z += (zMag - prev.z) / prev.n
            } else cells[key] = MagCell(gx, gy, magUt, zMag, 1)
        }
        lastAcc = accH
        val thermal = (lumHot - lumCold).coerceIn(0f, 1f)
        val magHit = abs(zMag) > 5f && (magUt < 30f || magUt > 80f)
        // Survey residual score: magnetics primary; vision is soft only (does not drive Jones)
        val instant = (if (magHit) 0.40f else 0f) +
            (if (audioRms > 0.02f) 0.12f else 0f) +
            (if (thermal > 0.22f) 0.12f else 0f) +
            (if (visResidual > 0.08f) 0.08f else 0f)
        scoreEma = scoreEma * 0.92f + instant * 0.08f
        // Normal Earth |B| is ~25–65 µT. High z with normal |B| = phone tilt / move after CAL, not mains.
        val normalEarth = magUt in 30f..75f
        val strongDevice = label == "device_interference" && mains > 0.60f && !normalEarth
        val orientationDrift = !calibrating && normalEarth && abs(zMag) > 4f
        val activity = when {
            calibrating -> "quiet"
            strongDevice -> "device"
            orientationDrift -> "quiet"
            label == "environmental_shift" -> "environmental"
            label == "candidate_entity" && scoreEma > 0.22f -> "unclassified"
            scoreEma > 0.45f -> "unclassified"
            scoreEma > 0.22f -> "environmental"
            else -> "quiet"
        }
        // If Jones still says device but we are on normal Earth field, force label soft
        val outLabel = if (orientationDrift && label == "device_interference") "normal" else label
        val outScore = if (outLabel == "normal" && label == "device_interference") (1f - p).coerceAtLeast(0.2f) else p
        val note = when {
            activity == "quiet" && orientationDrift ->
                "Normal indoor |B|. High z from phone move/tilt — hold still or re-CAL."
            activity == "quiet" ->
                "Quiet residual. |B| ${"%.1f".format(magUt)} µT  z ${"%.1f".format(zMag)}. Baseline OK."
            activity == "device" ->
                "Strong periodic / mains coupling. Step off wiring and re-sample."
            activity == "environmental" ->
                "Field or operator motion. Walk a second loop while still."
            else ->
                "Unclassified residual after subtraction. Score ${"%.2f".format(scoreEma)}. Not a confirmed spirit."
        }
        val survey = SurveySnap(
            activity, scoreEma, note, magUt, zMag, thermal, ambientC, lux, heading,
            cells.values.toList(), xM, yM,
        )
        val progress = if (!calibrating) 1f else min(1f, magBase.n / 40f)
        return EntityOutput(
            outLabel, outScore, magUt, qida.residual, omega.omegaTrust, sde.systemOk, sde.composite,
            zMag, !calibrating, progress, survey, note,
            qida.residual, scoreEma.coerceIn(0f, 1f), zMag,
        )
    }

    private fun mainsHum(mag: List<Float>): Float {
        if (mag.size < 16) return 0f
        val n = mag.size
        fun g(freq: Float): Float {
            val k = (n * freq * 0.02f).toInt().coerceAtLeast(1)
            val omega = (2 * Math.PI * k / n).toFloat()
            val coeff = 2f * kotlin.math.cos(omega)
            var s0 = 0f; var s1 = 0f; var s2 = 0f
            for (x in mag) { s0 = x + coeff * s1 - s2; s2 = s1; s1 = s0 }
            return s1 * s1 + s2 * s2 - coeff * s1 * s2
        }
        val tot = mag.fold(0f) { a, v -> a + v * v } + 1e-6f
        return min(1f, sqrt(max(g(50f), g(60f)) / tot))
    }

    private fun slowDrift(xs: List<Float>): Float {
        if (xs.size < 8) return 0f
        val a = xs.take(xs.size / 3).average().toFloat()
        val b = xs.takeLast(xs.size / 3).average().toFloat()
        val mean = xs.average().toFloat()
        val sd = sqrt(xs.fold(0f) { s, v -> s + (v - mean) * (v - mean) } / xs.size) + 1e-6f
        return min(1f, abs(b - a) / (3f * sd))
    }
}
