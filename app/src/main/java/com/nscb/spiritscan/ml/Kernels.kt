package com.nscb.spiritscan.ml

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class JonesHvOut(
    val r: Float, val envelope: Float, val wMod: Float, val forcing: Float,
    val residual: Float, val invariantLocal: Float, val inFirewall: Boolean,
    val harmonicOk: Boolean, val routeWeight: Float,
)
data class SdeOut(
    val sigmaSde: Float, val composite: Float, val temporal: Float, val harmonic: Float,
    val jones: Float, val sde: Float, val amp: Float, val parity: Float,
    val diff: Float, val systemOk: Boolean,
)
data class OmegaOut(
    val omegaTrust: Float, val penalty: Float, val globalTrust: Float,
    val anomalyFlag: Boolean, val trustStateIdx: Int,
)
data class OlympusOut(
    val normDcEffective: Float, val clampScale: Float, val dcViolation: Boolean,
    val aisHeavyTail: Boolean, val veViolation: Boolean, val systemOk: Boolean,
    val stabEngaged: Boolean, val paritySymmetric: Boolean, val qIndex: Float,
    val olympusComposite: Float,
)
data class QidaOut(
    val residual: Float, val aggregate: Float, val traceMean: Float,
    val theta: Float, val phi: Float, val omegaMag: Float, val geodesic: Float,
)

private const val EPS9 = 1e-9f
private const val EPS6 = 1e-6f
private const val EPS8 = 1e-8f

private fun triangleWave(v: Float, period: Float): Float {
    val p = max(period, EPS9)
    val frac = v / p - floor(v / p)
    return 4f * abs(frac - 0.5f) - 1f
}

/** Faithful port of nscb_jones_hv_field_v3.17 */
fun runJonesHv(
    x: Float, y: Float, lam: Float, sigma: Float, wPeriod: Float,
    lapPsi: Float, fwThreshold: Float, profileDeviation: Float, devTol: Float, loadNorm: Float,
): JonesHvOut {
    val r2 = x * x + y * y
    val r = sqrt(r2)
    val envelope = exp(-(r2 / max(sigma * sigma, EPS9)))
    val wMod = triangleWave(x, wPeriod) * triangleWave(y, wPeriod)
    val forcing = lam * envelope * wMod
    val residual = lapPsi + forcing
    val denIs = max(abs(lapPsi) * abs(forcing), EPS9)
    return JonesHvOut(
        r, envelope, wMod, forcing, residual, (lapPsi * forcing) / denIs,
        envelope >= fwThreshold, profileDeviation <= devTol,
        envelope * max(1f - loadNorm, EPS9),
    )
}

/** Faithful port of nscb_v82_3_18_sde_stability_v3.18 */
fun runSde(features: FloatArray): SdeOut {
    val x = FloatArray(14) { i -> features.getOrElse(i) { 0f } }
    val sAmp = x[8] * x[11]
    val sParity = 1f - x[12]
    val sDiff = 1f - x[10]
    val sigmaSde = x[7] * 0.35f + x[8] * 0.25f + x[9] * 0.25f + sDiff * 0.15f
    val temporal = (x[0] + x[1]) * 0.5f
    val harmonic = (x[2] + x[3]) * 0.5f
    val jones = (x[4] + x[5] + x[6]) / 3f
    val sde = (x[7] + sAmp + sParity + sDiff) * 0.25f
    val composite = temporal * 0.2f + harmonic * 0.2f + jones * 0.35f + sde * 0.25f
    // systemOk: allow mild indoor mains (x[13]); only fail on severe coupling + weak composite
    val systemOk = composite > 0.45f && sigmaSde > 0.40f && x[13] < 0.85f
    return SdeOut(
        sigmaSde, composite, temporal, harmonic, jones, sde, sAmp, sParity, sDiff,
        systemOk,
    )
}

/** Faithful port of nscb_v82_4_omega_trust_math_v3.12 */
fun runOmega(
    baseConfidence: Float, qidaResidual: Float, adversarialScore: Float,
    tGnss: Float, tImu: Float, tRf: Float, tHydro: Float, anomalyScore: Float,
): OmegaOut {
    val penalty = 0.4f * qidaResidual + 0.3f * adversarialScore
    val omegaTrust = min(1f, max(0f, baseConfidence - penalty))
    val globalTrust = 0.3f * tGnss + 0.2f * tImu + 0.3f * tRf + 0.2f * tHydro
    val anomalyFlag = anomalyScore > 0.5f || qidaResidual > 0.7f
    val th = floatArrayOf(0.1f, 0.3f, 0.55f, 0.7f, 0.85f, 0.95f)
    var idx = 0
    for (t in th) if (omegaTrust > t) idx++
    return OmegaOut(omegaTrust, penalty, globalTrust, anomalyFlag, idx)
}

/** Faithful port of nscb_v82_lx_olympus_q_v3.16 */
fun runOlympus(
    xDecisionNorm: Float, tauDc: Float, aisKurtosis: Float, tauAis: Float,
    vortexNorm: Float, tauVe: Float, epsVe: Float, loadCycles: Float,
    dataParityCycles: Float, syndromeParityCycles: Float, ampGain: Float,
    stbCoeff: Float, resStrength: Float, qInputNorm: Float,
): OlympusOut {
    val clampScale = min(tauDc / max(xDecisionNorm, EPS6), 1f)
    val dcViolation = xDecisionNorm > tauDc
    val aisHeavyTail = aisKurtosis > tauAis
    val veViolation = vortexNorm > tauVe || vortexNorm < epsVe
    val ampTerm = ampGain * qInputNorm
    val stbTerm = qInputNorm - stbCoeff * xDecisionNorm
    val resTerm = qInputNorm - resStrength * vortexNorm
    val composite = ampTerm + stbTerm + resTerm
    return OlympusOut(
        min(xDecisionNorm, tauDc), clampScale, dcViolation, aisHeavyTail, veViolation,
        !(dcViolation || aisHeavyTail || veViolation), loadCycles >= 288f,
        dataParityCycles == 144f && syndromeParityCycles == 144f,
        composite / max(qInputNorm, EPS6), composite,
    )
}

private fun l2(v: FloatArray): Float {
    var s = 0f
    for (x in v) s += x * x
    return sqrt(s)
}

/** Faithful port of nscb_v82_spherical_qida_integrator_v3_7 */
fun runQida(G: Array<FloatArray>, dtIn: Float): QidaOut {
    val T = G.size
    if (T < 2) return QidaOut(0f, 1f, 1f, 0f, 0f, 0f, 0f)
    val dt = max(dtIn, EPS8)
    val r = FloatArray(T)
    val theta = FloatArray(T)
    val phi = FloatArray(T)
    val S = Array(T) { FloatArray(3) }
    for (t in 0 until T) {
        val g = G[t]
        val x = g.getOrElse(0) { 0f }
        val y = g.getOrElse(1) { 0f }
        val z = g.getOrElse(2) { 0f }
        val rr = sqrt(x * x + y * y + z * z)
        val rSafe = rr + EPS8
        val th = acos(min(1f, max(-1f, z / rSafe)))
        val xy = sqrt(x * x + y * y)
        val ph = 2f * atan(y / (xy + x + EPS8)).toFloat()
        r[t] = rr; theta[t] = th; phi[t] = ph
        S[t][0] = rr; S[t][1] = th; S[t][2] = ph
    }
    var geoAcc = 0f
    var omAcc = 0f
    var meanKeep = 0f
    for (t in 0 until T) {
        val th0 = if (t == 0) theta[0] else theta[t - 1]
        val ph0 = if (t == 0) phi[0] else phi[t - 1]
        val dth = (theta[t] - th0) / dt
        val dph = (phi[t] - ph0) / dt
        val omegaMag = sqrt(dth * dth + dph * dph)
        val n = l2(S[t]) + EPS8
        val tan = floatArrayOf(S[t][0] / n, S[t][1] / n, S[t][2] / n)
        val prev = if (t == 0) tan else {
            val np = l2(S[t - 1]) + EPS8
            floatArrayOf(S[t - 1][0] / np, S[t - 1][1] / np, S[t - 1][2] / np)
        }
        val kg = l2(floatArrayOf(tan[0] - prev[0], tan[1] - prev[1], tan[2] - prev[2]))
        meanKeep += exp(-(omegaMag + kg + abs(phi[t] - ph0)))
        geoAcc += kg
        omAcc += omegaMag
    }
    meanKeep /= T
    return QidaOut(1f - meanKeep, meanKeep, meanKeep, theta[T - 1], phi[T - 1], omAcc / T, geoAcc / T)
}

fun classifyJones(features: FloatArray, qidaR: Float, mains: Float, drift: Float, coin: Float): Pair<String, Float> {
    // Only flag DEVICE when mains coupling is clearly strong
    val rawDevice = mains * 0.7f + features.getOrElse(13) { 0f } * 0.3f
    val device = when {
        mains < 0.40f -> rawDevice * 0.10f
        mains < 0.60f -> rawDevice * 0.30f
        mains < 0.75f -> rawDevice * 0.60f
        else -> rawDevice
    }
    val enviro = drift * 0.85f
    val entity = (qidaR * 0.55f + coin * 0.45f) * (1f - device.coerceIn(0f, 1f))
    val normal = max(0.25f, 1f - max(device, max(enviro, entity))) +
        if (mains < 0.5f && drift < 0.3f) 0.35f else 0f
    val scores = mapOf(
        "normal" to normal,
        "device_interference" to device,
        "environmental_shift" to enviro,
        "candidate_entity" to entity,
    )
    val best = scores.maxBy { it.value }
    // Hard gates: device never wins unless mains is strong and clearly best
    if (best.key == "device_interference") {
        if (mains < 0.65f) return "normal" to normal.coerceAtLeast(0.55f)
        if (device < normal + 0.20f) return "normal" to normal.coerceAtLeast(0.55f)
    }
    return best.key to best.value
}
