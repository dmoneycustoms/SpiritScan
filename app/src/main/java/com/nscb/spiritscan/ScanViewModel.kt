package com.nscb.spiritscan

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nscb.spiritscan.audio.AudioEngine
import com.nscb.spiritscan.engines.ArkEngine
import com.nscb.spiritscan.engines.DiagnosticsEngine
import com.nscb.spiritscan.engines.DiagnosticsState
import com.nscb.spiritscan.engines.ExplainEngine
import com.nscb.spiritscan.engines.ExplainState
import com.nscb.spiritscan.engines.FiveWEngine
import com.nscb.spiritscan.engines.FiveWState
import com.nscb.spiritscan.engines.FusionEngine
import com.nscb.spiritscan.engines.FusionState
import com.nscb.spiritscan.engines.HardeningEngine
import com.nscb.spiritscan.engines.HardeningState
import com.nscb.spiritscan.engines.HudEngine
import com.nscb.spiritscan.engines.HudState
import com.nscb.spiritscan.engines.NoiseSplit
import com.nscb.spiritscan.engines.NoiseSplitState
import com.nscb.spiritscan.engines.PerformanceEngine
import com.nscb.spiritscan.engines.PerformanceState
import com.nscb.spiritscan.engines.QidaDecisionEngine
import com.nscb.spiritscan.engines.QidaDecisionState
import com.nscb.spiritscan.engines.ResidualFilter
import com.nscb.spiritscan.engines.ResidualFilterState
import com.nscb.spiritscan.engines.AudioAnomalyEngine
import com.nscb.spiritscan.logging.SessionLogger
import com.nscb.spiritscan.engines.FrequencyBoxState
import com.nscb.spiritscan.engines.FrequencyBoxEngine
import com.nscb.spiritscan.engines.CameraAnomalyState
import com.nscb.spiritscan.engines.CameraAnomalyEngine
import com.nscb.spiritscan.engines.AudioAnomalyState
import com.nscb.spiritscan.engines.SpectralEngine
import com.nscb.spiritscan.engines.SpectralState
import com.nscb.spiritscan.engines.DenseFlowEngine
import com.nscb.spiritscan.engines.DenseFlowState
import com.nscb.spiritscan.engines.FocusGate
import com.nscb.spiritscan.engines.FocusGateState
import com.nscb.spiritscan.engines.OpticalFlowEngine
import com.nscb.spiritscan.engines.OpticalFlowState
import com.nscb.spiritscan.engines.VisionAnomalyEngine
import com.nscb.spiritscan.engines.VisionAnomalyState
import com.nscb.spiritscan.engines.TrustEngine
import com.nscb.spiritscan.engines.TrustState
import com.nscb.spiritscan.entity.EntityEngine
import com.nscb.spiritscan.entity.EntityOutput
import com.nscb.spiritscan.entity.SurveySnap
import com.nscb.spiritscan.sensor.MicMonitor
import com.nscb.spiritscan.vision.MarsOodResult
import com.nscb.spiritscan.vision.MarsOodRunner
import com.nscb.spiritscan.sensor.SensorStreamManager
import com.nscb.spiritscan.sensor.SpiritBox
import com.nscb.spiritscan.sensor.SweepMode
import com.nscb.spiritscan.ui.modes.ScanMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext
    private val engine = EntityEngine()
    private val box = SpiritBox()
    private var sensors: SensorStreamManager? = null
    private var mic: MicMonitor? = null

    private val fusionEngine = FusionEngine()
    private val hudEngine = HudEngine()
    private val diagEngine = DiagnosticsEngine()
    private val perfEngine = PerformanceEngine()
    private var audioEngine: AudioEngine? = null

    private val _output = MutableStateFlow(idle())
    val output: StateFlow<EntityOutput> = _output

    private val _boxOn = MutableStateFlow(false)
    val boxOn: StateFlow<Boolean> = _boxOn

    private val _walking = MutableStateFlow(false)
    val walking: StateFlow<Boolean> = _walking

    private val _sweep = MutableStateFlow(SweepMode.HOP)
    val sweep: StateFlow<SweepMode> = _sweep

    private val _currentMode = MutableStateFlow(ScanMode.ENTITY)
    val currentMode: StateFlow<ScanMode> = _currentMode

    private val _fusion = MutableStateFlow<FusionState?>(null)
    val fusion: StateFlow<FusionState?> = _fusion

    private val _hud = MutableStateFlow<HudState?>(null)
    val hud: StateFlow<HudState?> = _hud

    private val _diag = MutableStateFlow<DiagnosticsState?>(null)
    val diag: StateFlow<DiagnosticsState?> = _diag

    private val _perf = MutableStateFlow<PerformanceState?>(null)
    val perf: StateFlow<PerformanceState?> = _perf

    private val _ark = MutableStateFlow<ArkEngine.ArkTick?>(null)
    val ark: StateFlow<ArkEngine.ArkTick?> = _ark

    private val _noise = MutableStateFlow<NoiseSplitState?>(null)
    val noise: StateFlow<NoiseSplitState?> = _noise

    private val _hard = MutableStateFlow<HardeningState?>(null)
    val hard: StateFlow<HardeningState?> = _hard

    private val _trust = MutableStateFlow<TrustState?>(null)
    val trust: StateFlow<TrustState?> = _trust

    private val _qidaDec = MutableStateFlow<QidaDecisionState?>(null)
    val qidaDec: StateFlow<QidaDecisionState?> = _qidaDec

    private val _explain = MutableStateFlow<ExplainState?>(null)
    val explain: StateFlow<ExplainState?> = _explain

    private val _fiveW = MutableStateFlow<FiveWState?>(null)
    val fiveW: StateFlow<FiveWState?> = _fiveW

    private val _residFilter = MutableStateFlow<ResidualFilterState?>(null)
    val residFilter: StateFlow<ResidualFilterState?> = _residFilter

    private val _spectral = MutableStateFlow<SpectralState?>(null)
    val spectral: StateFlow<SpectralState?> = _spectral

    private val _audioAnom = MutableStateFlow<AudioAnomalyState?>(null)
    val audioAnom: StateFlow<AudioAnomalyState?> = _audioAnom

    private val _visionAnom = MutableStateFlow<VisionAnomalyState?>(null)
    val visionAnom: StateFlow<VisionAnomalyState?> = _visionAnom

    private val _optFlow = MutableStateFlow<OpticalFlowState?>(null)
    val optFlow: StateFlow<OpticalFlowState?> = _optFlow

    private val _denseFlow = MutableStateFlow<DenseFlowState?>(null)
    val denseFlow: StateFlow<DenseFlowState?> = _denseFlow

    private val _focusGate = MutableStateFlow<FocusGateState?>(null)
    val focusGate: StateFlow<FocusGateState?> = _focusGate

    private val _marsOod = MutableStateFlow<MarsOodResult?>(null)
    val marsOod: StateFlow<MarsOodResult?> = _marsOod

    private val _camAnom = MutableStateFlow<CameraAnomalyState?>(null)
    val camAnom: StateFlow<CameraAnomalyState?> = _camAnom

    private val _freqBox = MutableStateFlow<FrequencyBoxState?>(null)
    val freqBox: StateFlow<FrequencyBoxState?> = _freqBox

    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive

    private val _sessionPath = MutableStateFlow("")
    val sessionPath: StateFlow<String> = _sessionPath

    private val _sessionRows = MutableStateFlow(0)
    val sessionRows: StateFlow<Int> = _sessionRows

    private var marsRunner: MarsOodRunner? = null

    @Volatile private var lastLumGrid: FloatArray? = null
    private val _lumGridFlow = MutableStateFlow<FloatArray?>(null)
    val lumGrid: StateFlow<FloatArray?> = _lumGridFlow
    @Volatile private var lastSample: com.nscb.spiritscan.sensor.Sample9? = null

    // ---- v8.7 visual anomaly decoder -------------------------------------------------
    private val decoder = com.nscb.spiritscan.decode.AnomalyDecoder().apply {
        colormapFallback = android.os.Build.VERSION.SDK_INT < 33
    }
    private val _decode = MutableStateFlow<com.nscb.spiritscan.decode.DecodeFrame?>(null)
    val decode: StateFlow<com.nscb.spiritscan.decode.DecodeFrame?> = _decode
    @Volatile private var decodeOn = false

    fun setDecodeEnabled(on: Boolean) {
        if (on && !decodeOn) decoder.reset()
        decodeOn = on
        if (!on) _decode.value = null
    }

    fun resetDecoder() = decoder.reset()

    fun setAirEnabled(on: Boolean) {
        decoder.airEnabled = on
    }

    // ---- v8.8 Spirit Box full-band scan ----------------------------------------------
    private val scanner = com.nscb.spiritscan.sensor.SpiritScanner(appContext)
    val scan: StateFlow<com.nscb.spiritscan.dsp.ScanSnapshot?> = scanner.state
    val scanError: StateFlow<String> = scanner.error
    private val _scanOn = MutableStateFlow(false)
    val scanOn: StateFlow<Boolean> = _scanOn

    fun toggleScan() {
        if (_scanOn.value) {
            if (_sonarOn.value) toggleSonar()
            scanner.stop()
            _scanOn.value = false
        } else {
            scanner.start()
            _scanOn.value = scanner.running
        }
    }

    fun scanNullTest() = scanner.engine.startNull()

    fun scanNullClear() = scanner.engine.clearNull()

    // ---- v8.9 live context, passive novelty, sham-controlled trial -----------------------
    private val ctxFeeds = com.nscb.spiritscan.feeds.ContextFeeds(appContext)
    val ctx: StateFlow<com.nscb.spiritscan.feeds.ContextSnapshot> = ctxFeeds.state

    fun startContext() = ctxFeeds.start()

    fun refreshContext() = ctxFeeds.refreshNow()

    fun setContextLocation(lat: Double, lon: Double) = ctxFeeds.setLocation(lat, lon)

    fun contextLocationText(): String {
        val l = ctxFeeds.location() ?: return "not set (global feeds only)"
        return "${"%.3f".format(l.first)}, ${"%.3f".format(l.second)}"
    }

    fun contextExplain(): String = ctxFeeds.explain()

    private fun passiveVector(): FloatArray {
        val o = _output.value
        val rf = _residFilter.value
        val au = _audioAnom.value
        val cam = _camAnom.value
        val sp = _spectral.value
        val sc = scanner.state.value
        val dec = _decode.value
        val v = FloatArray(12)
        v[0] = o.magUt
        v[1] = o.zMag
        v[2] = rf?.unknown ?: 0f
        v[3] = au?.zScore ?: 0f
        v[4] = au?.speechResidual ?: 0f
        v[5] = cam?.anomalyScore ?: 0f
        v[6] = cam?.frameMad ?: 0f
        v[7] = sp?.residualSpectrum ?: 0f
        v[8] = sc?.voice?.score ?: 0f
        v[9] = sc?.excess?.maxOrNull() ?: 0f
        v[10] = dec?.anomalyIndex ?: 0f
        v[11] = dec?.air?.airIndex ?: 0f
        for (i in v.indices) if (v[i].isNaN() || v[i].isInfinite()) v[i] = 0f
        return v
    }

    private val passive = com.nscb.spiritscan.novelty.PassiveRecorder(
        appContext, { passiveVector() }, { ctxFeeds.explain() }
    )
    val novelty: StateFlow<com.nscb.spiritscan.dsp.NoveltySnapshot?> = passive.state
    private val _passiveOn = MutableStateFlow(false)
    val passiveOn: StateFlow<Boolean> = _passiveOn

    fun togglePassive() {
        if (_passiveOn.value) {
            passive.stop()
            _passiveOn.value = false
        } else {
            passive.start(300)
            _passiveOn.value = true
        }
    }

    fun passiveRelearn() = passive.relearn(300)

    fun passiveClear() = passive.clearEvents()

    private val trial = com.nscb.spiritscan.trial.TrialEngine(
        viewModelScope,
        appContext,
        { on -> if (_boxOn.value != on) toggleBox() },
        {
            longArrayOf(
                scanner.engine.voice.result().eventsTotal,
                scanner.engine.wide.confirmedCount()
            )
        },
        { ctxFeeds.explain() }
    )
    val trialState: StateFlow<com.nscb.spiritscan.trial.TrialState> = trial.state

    fun trialStart(blocks: Int, blockSec: Int) {
        if (!_scanOn.value) toggleScan()
        if (!_scanOn.value) return
        trial.start(blocks, blockSec)
    }

    fun trialStop() = trial.stop()

    fun trialMark() = trial.mark()

    // ---- v9.0 senses: sonar, sound camera, magnetic map, coincidence, sonification, pareidolia ----
    private val sonar = com.nscb.spiritscan.senses.SonarEngine()
    private val sonarIo = com.nscb.spiritscan.sensor.SonarIO(sonar)
    private val _sonar = MutableStateFlow<com.nscb.spiritscan.senses.SonarEngine.Snapshot?>(null)
    val sonarState: StateFlow<com.nscb.spiritscan.senses.SonarEngine.Snapshot?> = _sonar
    private val _sonarOn = MutableStateFlow(false)
    val sonarOn: StateFlow<Boolean> = _sonarOn

    fun toggleSonar() {
        if (_sonarOn.value) {
            sonarIo.stop()
            scanner.wideListener = null
            scanner.engine.wide.setMask(0.0, 0.0)
            _sonarOn.value = false
            _sonar.value = null
            return
        }
        if (!_scanOn.value) toggleScan()
        if (!_scanOn.value) return
        scanner.wideListener = { b, n -> sonar.push(b, n) }
        scanner.engine.wide.setMask(17000.0, 21500.0)
        if (sonarIo.start()) {
            _sonarOn.value = true
        } else {
            scanner.wideListener = null
            scanner.engine.wide.setMask(0.0, 0.0)
        }
    }

    val doaState: StateFlow<com.nscb.spiritscan.senses.DoaEngine.Snapshot?> = scanner.doaState

    fun doaCalibrate() = scanner.doa.startCalibration()

    fun doaClearCalibration() = scanner.doa.clearCalibration()

    private val magMap = com.nscb.spiritscan.senses.MagMapEngine()
    private val _map = MutableStateFlow<com.nscb.spiritscan.senses.MagMapEngine.Snapshot?>(null)
    val mapState: StateFlow<com.nscb.spiritscan.senses.MagMapEngine.Snapshot?> = _map
    private val _mapOn = MutableStateFlow(false)
    val mapOn: StateFlow<Boolean> = _mapOn

    fun toggleMap() {
        val on = !_mapOn.value
        if (on) magMap.reset()
        _mapOn.value = on
        if (!on) _map.value = null
    }

    fun mapReset() = magMap.reset()

    fun mapStepLength(m: Double) = magMap.setStepLength(m)

    private val coin = com.nscb.spiritscan.senses.CoincidenceEngine(
        arrayOf("MAG", "AUDIO", "SONAR", "CAM", "NOV", "MAP", "RAD")
    )
    private val _coin = MutableStateFlow<com.nscb.spiritscan.senses.CoincidenceEngine.Snapshot?>(null)
    val coinState: StateFlow<com.nscb.spiritscan.senses.CoincidenceEngine.Snapshot?> = _coin
    private var lastChanged = 0

    fun coincidenceReset() = coin.reset()

    private fun coincidenceTick() {
        val act = BooleanArray(7)
        act[0] = (_residFilter.value?.active == true) || kotlin.math.abs(_output.value.zMag) >= 5f
        val sc = scanner.state.value
        act[1] = sc != null && (
            sc.anomalies.any { !it.explained && it.active } ||
                (sc.voice != null && sc.voice.eventsTotal > 0 && sc.voice.secSinceEvent < 3f)
            )
        val sn = _sonar.value
        act[2] = sn != null && sn.ready && sn.targets.any { !it.living }
        val d = _decode.value
        act[3] = d != null && (
            d.status == com.nscb.spiritscan.decode.DecodeStatus.UNEXPLAINED ||
                (d.air?.pulses?.any { !it.explained && it.confirmed } == true)
            )
        val nv = passive.state.value
        act[4] = nv != null && nv.phase == 3 &&
            (nv.aeZ >= com.nscb.spiritscan.dsp.NoveltyCore.OPEN_Z || nv.mahaZ >= com.nscb.spiritscan.dsp.NoveltyCore.OPEN_Z)
        val mp = _map.value
        act[5] = mp != null && mp.changedCells > lastChanged
        lastChanged = mp?.changedCells ?: 0
        val ps = _particle.value
        act[6] = ps != null && ps.calibrated && (ps.elevated || ps.burst)
        coin.tick(act, System.currentTimeMillis())
        _coin.value = coin.snapshot()
    }

    @Volatile
    private var headphonesCached = false

    private val sonifier = com.nscb.spiritscan.sensor.Sonifier(appContext) { sonifyLevel() }
    private val _sonifyMode = MutableStateFlow(0)
    val sonifyMode: StateFlow<Int> = _sonifyMode

    fun cycleSonify() {
        val m = (_sonifyMode.value + 1) % 3
        _sonifyMode.value = m
        sonifier.mode = m
        if (m > 0) sonifier.start() else sonifier.stop()
    }

    fun headphones(): Boolean = headphonesCached

    /**
     * Click rate driver, 0..1. Magnetometer, camera, novelty and coincidences always count. Microphone-derived
     * channels (voice score, sonar) count only with headphones, otherwise the clicks would feed back into them.
     */
    private fun sonifyLevel(): Float {
        var lv = kotlin.math.abs(_output.value.zMag) / 12f
        val dec = _decode.value
        if (dec != null) lv = maxOf(lv, dec.anomalyIndex * 0.8f)
        val nv = passive.state.value
        if (nv != null && nv.phase == 3) lv = maxOf(lv, maxOf(nv.aeZ, nv.mahaZ) / 12f)
        val c = _coin.value
        if (c != null) {
            if (c.k >= 3) lv = maxOf(lv, 0.8f) else if (c.k >= 2) lv = maxOf(lv, 0.5f)
        }
        if (headphonesCached) {
            val sc = scanner.state.value
            if (sc != null && sc.voice != null) lv = maxOf(lv, sc.voice.score * 0.9f)
            val sn = _sonar.value
            if (sn != null && sn.ready && sn.targets.any { !it.living }) lv = maxOf(lv, 0.5f)
        }
        return lv.coerceIn(0f, 1f)
    }

    private val pareMeter by lazy { com.nscb.spiritscan.senses.PareidoliaMeter(120.0, 160.0) }
    private val pareTally = com.nscb.spiritscan.senses.PareidoliaMeter.Tally()
    private val _pare = MutableStateFlow<com.nscb.spiritscan.senses.PareState?>(null)
    val pareState: StateFlow<com.nscb.spiritscan.senses.PareState?> = _pare
    private var lastPareMs = 0L

    fun pareReset() {
        pareTally.frames = 0
        pareTally.faceFrames = 0
        pareTally.expected = 0.0
        pareTally.variance = 0.0
    }

    private fun updatePareidolia(f: com.nscb.spiritscan.decode.DecodeFrame) {
        val now = System.currentTimeMillis()
        if (now - lastPareMs < 200) return
        lastPareMs = now
        val bl = f.blobs.filter { it.confirmed }
        val n = bl.size
        val xs = DoubleArray(n) { bl[it].cx * 120.0 }
        val ys = DoubleArray(n) { bl[it].cy * 160.0 }
        val rs = DoubleArray(n) { maxOf(2.0, maxOf(bl[it].halfW * 120.0, bl[it].halfH * 160.0)) }
        val hits = pareMeter.scan(xs, ys, rs, n)
        val face = hits.any { it.kind == 0 }
        val fig = hits.any { it.kind == 1 }
        val chance = pareMeter.chanceFace(n)
        pareTally.add(n, face, chance)
        val lines = ArrayList<FloatArray>()
        for (h in hits.take(4)) {
            val a = FloatArray(h.idx.size * 2 + 1)
            a[0] = h.kind.toFloat()
            for (i in h.idx.indices) {
                a[1 + 2 * i] = bl[h.idx[i]].cx
                a[2 + 2 * i] = bl[h.idx[i]].cy
            }
            lines.add(a)
        }
        _pare.value = com.nscb.spiritscan.senses.PareState(
            n, chance, face, fig, lines,
            pareTally.frames, pareTally.faceFrames, pareTally.expected, pareTally.z()
        )
    }

    // ---- v9.1 particle detector and ground-truth harness ----------------------------------
    private val particleEngine = com.nscb.spiritscan.senses.ParticleEngine()
    private val particleCam = com.nscb.spiritscan.sensor.ParticleCamera(appContext, particleEngine)
    private val _particleOn = MutableStateFlow(false)
    val particleOn: StateFlow<Boolean> = _particleOn
    private val _particle = MutableStateFlow<com.nscb.spiritscan.senses.ParticleEngine.Snapshot?>(null)
    val particleState: StateFlow<com.nscb.spiritscan.senses.ParticleEngine.Snapshot?> = _particle
    private val _particleErr = MutableStateFlow("")
    val particleError: StateFlow<String> = _particleErr
    val particleInfo: StateFlow<String> = particleCam.info

    /** The HUD releases CameraX when [particleOn] turns true; the Camera2 session opens a moment later. */
    fun toggleParticle() {
        if (_particleOn.value) {
            particleCam.stop()
            // give Camera2 a moment to release the device before the HUD rebinds CameraX
            viewModelScope.launch {
                kotlinx.coroutines.delay(700)
                _particleOn.value = false
                _particle.value = null
            }
            return
        }
        particleEngine.reset()
        _particleErr.value = ""
        _particleOn.value = true
        viewModelScope.launch {
            kotlinx.coroutines.delay(1000)
            val err = particleCam.start()
            if (err != null) {
                _particleErr.value = err
                _particleOn.value = false
            }
        }
    }

    fun particleReset() = particleEngine.reset()

    private fun buildProtocols(): List<com.nscb.spiritscan.trial.HarnessProtocol> {
        val list = ArrayList<com.nscb.spiritscan.trial.HarnessProtocol>()
        list.add(
            com.nscb.spiritscan.trial.HarnessProtocol(
                id = "mag", title = "Magnetometer",
                levels = listOf("25 cm", "12 cm", "6 cm"),
                levelText = listOf(
                    "hold a magnet (or speaker, headphone case) 25 cm from the phone and keep it still",
                    "hold the magnet 12 cm from the phone and keep it still",
                    "hold the magnet 6 cm from the phone and keep it still"
                ),
                windowSec = 20, repsPerLevel = 2, shams = 6, threshold = 5.0, unit = " z",
                requirement = { null },
                metric = { kotlin.math.abs(_output.value.zMag).toDouble() }
            )
        )
        list.add(
            com.nscb.spiritscan.trial.HarnessProtocol(
                id = "tone", title = "Band scanner (tone)",
                levels = listOf("quiet", "medium", "loud"),
                levelText = listOf(
                    "play a steady 1 kHz tone from another device about 1 m away, QUIET",
                    "play the 1 kHz tone from another device about 1 m away, MEDIUM volume",
                    "play the 1 kHz tone from another device about 1 m away, LOUD"
                ),
                windowSec = 20, repsPerLevel = 2, shams = 6, threshold = 7.0, unit = " dB",
                requirement = { if (!_scanOn.value) "Start the Spirit Box scan first" else null },
                metric = { scanner.state.value?.excess?.maxOrNull()?.toDouble() ?: Double.NaN }
            )
        )
        list.add(
            com.nscb.spiritscan.trial.HarnessProtocol(
                id = "voice", title = "Voice signature",
                levels = listOf("whisper", "normal", "loud"),
                levelText = listOf(
                    "WHISPER 'one two three four five' about 1 m from the phone",
                    "say 'one two three four five' at NORMAL volume about 1 m from the phone",
                    "say 'one two three four five' LOUDLY about 1 m from the phone"
                ),
                windowSec = 20, repsPerLevel = 2, shams = 6, threshold = 1.0, unit = " event",
                requirement = { if (!_scanOn.value) "Start the Spirit Box scan first" else null },
                metric = {
                    val v = scanner.state.value?.voice
                    if (v == null) Double.NaN else if (v.eventsTotal > 0 && v.secSinceEvent < 2f) 1.0 else 0.0
                }
            )
        )
        list.add(
            com.nscb.spiritscan.trial.HarnessProtocol(
                id = "sonar", title = "Ultrasonic sonar",
                levels = listOf("2 m", "1 m", "0.5 m"),
                levelText = listOf(
                    "wave your hand slowly about 2 m in front of the phone",
                    "wave your hand slowly about 1 m in front of the phone",
                    "wave your hand slowly about 0.5 m in front of the phone"
                ),
                windowSec = 20, repsPerLevel = 2, shams = 6, threshold = 10.8, unit = " dB",
                requirement = {
                    if (!_sonarOn.value) "Start the sonar first (phone still on a table, you out of range while it learns)" else null
                },
                metric = {
                    val s = _sonar.value
                    if (s == null || !s.ready) Double.NaN else (s.motionDb.maxOrNull() ?: 0f).toDouble()
                }
            )
        )
        list.add(
            com.nscb.spiritscan.trial.HarnessProtocol(
                id = "air", title = "AIR flow (camera)",
                levels = listOf("20 cm", "10 cm", "5 cm"),
                levelText = listOf(
                    "hold a mug of hot (not boiling) water about 20 cm in front of a textured background in view",
                    "hold the mug about 10 cm in front of the textured background",
                    "hold the mug about 5 cm in front of the textured background"
                ),
                windowSec = 20, repsPerLevel = 2, shams = 6, threshold = 0.3, unit = " idx",
                requirement = {
                    if (_decode.value?.air == null) "Select the AIR chip, point at a textured background, hold still until readings appear" else null
                },
                metric = { _decode.value?.air?.airIndex?.toDouble() ?: Double.NaN }
            )
        )
        list.add(
            com.nscb.spiritscan.trial.HarnessProtocol(
                id = "particle", title = "Particle detector (source)",
                levels = listOf("source 10 cm", "source at lens"),
                levelText = listOf(
                    "keep a low-level source (uranium glass, orange Fiestaware) about 10 cm from the covered lens",
                    "keep the source touching the tape over the lens"
                ),
                windowSec = 120, repsPerLevel = 2, shams = 4, threshold = 1.0, unit = " events",
                requirement = {
                    val ps = _particle.value
                    if (!_particleOn.value || ps == null || !ps.calibrated) "Start the particle detector first and wait until it is listening" else null
                },
                metric = { _particle.value?.particleLike?.toDouble() ?: Double.NaN },
                score = { s -> if (s.size < 2) 0.0 else s[s.size - 1] - s[0] }
            )
        )
        return list
    }

    private val harness = com.nscb.spiritscan.trial.HarnessEngine(viewModelScope, appContext, buildProtocols())
    val harnessState: StateFlow<com.nscb.spiritscan.trial.HarnessState> = harness.state
    val harnessNames: List<String> = harness.protocols.map { it.title }

    fun harnessStart(i: Int) = harness.start(i)

    fun harnessStop() = harness.stop()

    private var sensesJob: kotlinx.coroutines.Job? = null

    fun startSenses() {
        if (sensesJob != null) return
        sensesJob = viewModelScope.launch {
            var n = 0
            while (true) {
                kotlinx.coroutines.delay(100)
                n++
                try {
                    if (_sonarOn.value) _sonar.value = sonar.snapshot()
                    if (_mapOn.value) _map.value = magMap.snapshot()
                    if (_particleOn.value) _particle.value = particleEngine.snapshot(System.currentTimeMillis())
                    if (n % 20 == 0) headphonesCached = sonifier.headphonesConnected()
                    if (n % 10 == 0) coincidenceTick()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** STRICT -> NORMAL -> LOOSE -> STRICT. Looser = tolerates more hand movement, more false candidates. */
    fun cycleDecodeLevel() {
        decoder.level = (decoder.level + 1) % 3
    }

    /** Runs on the camera analysis thread for every frame while DECODE is selected. */
    fun decodeFrame(ip: androidx.camera.core.ImageProxy) {
        if (!decodeOn) return
        val plane = ip.planes[0]
        val s = lastSample
        val gyro = if (s == null) 0f else
            kotlin.math.sqrt(s.gyroX * s.gyroX + s.gyroY * s.gyroY + s.gyroZ * s.gyroZ)
        val acc = if (s == null) 0f else kotlin.math.abs(
            kotlin.math.sqrt(s.accX * s.accX + s.accY * s.accY + s.accZ * s.accZ) - 9.81f
        )
        // 0..1 = fraction of 1.0 rad/s (gyro) or 2.0 m/s^2 (accel deviation from 1 g).
        // The decoder compares this to its sensitivity-dependent gate (0.30 / 0.50 / 0.80).
        val phone = maxOf((gyro / 1.0f).coerceIn(0f, 1f), (acc / 2.0f).coerceIn(0f, 1f))
        val df = decoder.process(
            plane.buffer, plane.rowStride, plane.pixelStride,
            ip.width, ip.height, ip.imageInfo.rotationDegrees,
            ip.imageInfo.timestamp, phone
        )
        _decode.value = df
        updatePareidolia(df)
    }

    @Volatile
    private var visionResidual: Float = 0.01f

    fun onVisionFrame(residual: Float) {
        visionResidual = residual.coerceIn(0f, 1f)
    }

    fun onDetectedObjects(boxes: List<com.nscb.spiritscan.vision.DetectedObjectBox>) {
        val noiseDom = _noise.value?.dominant
        val residAct = _residFilter.value?.active == true
        _visionAnom.value = try {
            VisionAnomalyEngine.evaluate(boxes, visionResidual, noiseDom, residAct, visionResidual)
        } catch (_: Exception) {
            _visionAnom.value
        }
        // Focus gate + dense flow refresh on vision tick
        val of = _optFlow.value
        _focusGate.value = try {
            FocusGate.evaluate(
                frameResidual = visionResidual,
                oodCount = _visionAnom.value?.unknownCount ?: 0,
                independentFlow = of?.independentMotion ?: 0f
            )
        } catch (_: Exception) {
            null
        }
    }

    fun onLumGrid(grid: FloatArray?) {
        lastLumGrid = grid
        _lumGridFlow.value = grid
        val noiseDom = _noise.value?.dominant
        _denseFlow.value = try {
            DenseFlowEngine.evaluate(grid, lastSample, noiseDom)
        } catch (_: Exception) {
            null
        }
        _marsOod.value = try {
            marsRunner?.evaluate(grid)
        } catch (_: Exception) {
            null
        }
        _camAnom.value = try {
            CameraAnomalyEngine.evaluate(grid)
        } catch (_: Exception) {
            null
        }
    }

    fun setMode(mode: ScanMode) {
        _currentMode.value = mode
    }

    fun startSession(ctx: Context) {
        val path = SessionLogger.start(ctx)
        _sessionActive.value = SessionLogger.isActive()
        _sessionPath.value = path
        _sessionRows.value = 0
    }

    fun stopSession() {
        SessionLogger.stop()
        _sessionActive.value = false
        _sessionRows.value = SessionLogger.rowCount()
    }

    fun bookmark(note: String = "BOOKMARK") {
        SessionLogger.bookmark(note)
        _sessionRows.value = SessionLogger.rowCount()
    }

    fun arm(ctx: Context) {
        if (sensors != null) return

        engine.startCal()
        perfEngine.warmFusion(fusionEngine)

        if (audioEngine == null) {
            try {
                audioEngine = AudioEngine(appContext)
            } catch (_: Exception) {
            }
        }

        if (mic == null) {
            try {
                mic = MicMonitor().also { it.start() }
            } catch (_: Exception) {
            }
        }

        if (marsRunner == null) {
            try {
                marsRunner = MarsOodRunner(appContext)
            } catch (_: Exception) {
            }
        }

        var processing = false

        sensors = SensorStreamManager(ctx) { sample ->
            if (processing) return@SensorStreamManager
            processing = true

            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val snap = sensors?.buffer?.snapshot().orEmpty()
                    lastSample = sample
                    if (_mapOn.value) {
                        magMap.push(
                            sample.tNs / 1e9,
                            sample.accX.toDouble(), sample.accY.toDouble(), sample.accZ.toDouble(),
                            sample.gyroX.toDouble(), sample.gyroY.toDouble(), sample.gyroZ.toDouble(),
                            sample.magUt.toDouble()
                        )
                    }

                    val out = engine.process(
                        sample = sample,
                        window = snap,
                        visResidual = visionResidual,
                        heading = sensors?.heading ?: 0f,
                        ambientC = sensors?.ambientC,
                        lux = sensors?.lux,
                        lumHot = 0.55f,
                        lumCold = 0.25f,
                        audioRms = box.rms,
                    )

                    val t0 = System.nanoTime()
                    val fused = fusionEngine.fuse(out)
                    val fusionNs = System.nanoTime() - t0

                    val arkTick = try {
                        ArkEngine.tick(out)
                    } catch (_: Exception) {
                        null
                    }

                    val noiseState = try {
                        NoiseSplit.analyze(snap, sample, boxOn = _boxOn.value)
                    } catch (_: Exception) {
                        null
                    }

                    val hardState = try {
                        HardeningEngine.evaluate(out, noiseState?.dominant)
                    } catch (_: Exception) {
                        null
                    }

                    val trustState = try {
                        TrustEngine.evaluate(out, noiseState, hardState)
                    } catch (_: Exception) {
                        null
                    }

                    val qidaDecState = try {
                        QidaDecisionEngine.evaluate(out, noiseState, hardState, trustState)
                    } catch (_: Exception) {
                        null
                    }

                    val explainState = try {
                        ExplainEngine.evaluate(out, noiseState, hardState, trustState, qidaDecState)
                    } catch (_: Exception) {
                        null
                    }

                    val fiveWState = try {
                        FiveWEngine.evaluate(out, noiseState, hardState, trustState, qidaDecState, explainState)
                    } catch (_: Exception) {
                        null
                    }

                    val residFilterState = try {
                        ResidualFilter.evaluate(out, noiseState, hardState, trustState)
                    } catch (_: Exception) {
                        null
                    }

                    val spectralState = try {
                        SpectralEngine.analyze(snap)
                    } catch (_: Exception) {
                        null
                    }

                    val micRms = mic?.rms ?: 0f
                    // Prefer live mic; fall back to box RMS when mic unavailable
                    val audioLevel = if (micRms > 0.0001f) micRms else box.rms
                    // Tell mic which hop to notch
                    try {
                        mic?.hopFreqHz = box.freq
                        scanner.setOwnHz(if (_boxOn.value) box.freq.toDouble() else 0.0)
                    } catch (_: Exception) {
                    }
                    val speechRes = mic?.speechResidual ?: 0f
                    val audioAnomState = try {
                        AudioAnomalyEngine.evaluate(
                            rms = audioLevel,
                            boxOn = _boxOn.value,
                            noiseDominant = noiseState?.dominant,
                            speechResidual = speechRes,
                            hopHz = box.freq
                        )
                    } catch (_: Exception) {
                        null
                    }

                    val freqState = try {
                        if (_boxOn.value) FrequencyBoxEngine.pulse(hopHz = box.freq)
                        else null
                    } catch (_: Exception) {
                        null
                    }
                    _freqBox.value = freqState

                    val visionAnomState = try {
                        VisionAnomalyEngine.evaluate(
                            objects = emptyList(),
                            visResidual = visionResidual,
                            noiseDominant = noiseState?.dominant,
                            residUnknownActive = residFilterState?.active == true,
                            frameResidual = visionResidual
                        )
                    } catch (_: Exception) {
                        null
                    }

                    val optFlowState = try {
                        OpticalFlowEngine.evaluate(sample, visionResidual, noiseState?.dominant)
                    } catch (_: Exception) {
                        null
                    }

                    withContext(Dispatchers.Main) {
                        _output.value = out
                        _fusion.value = fused
                        _ark.value = arkTick
                        _noise.value = noiseState
                        _hard.value = hardState
                        _trust.value = trustState
                        _qidaDec.value = qidaDecState
                        _explain.value = explainState
                        _fiveW.value = fiveWState
                        _residFilter.value = residFilterState

                    // Session evidence on spikes / residual
                    try {
                        val spike = (residFilterState?.active == true) ||
                            (audioAnomState?.unknown == true) ||
                            (_camAnom.value?.unknown == true) ||
                            (_denseFlow.value?.unknown == true)
                        if (spike && SessionLogger.isActive()) {
                            SessionLogger.log(
                                magUt = out.magUt,
                                zMag = out.zMag,
                                residual = out.residualLevel,
                                jones = out.jonesLabel,
                                qida = out.qida,
                                audioZ = audioAnomState?.zScore ?: 0f,
                                hopHz = box.freq,
                                speechRes = speechRes,
                                camScore = _camAnom.value?.anomalyScore ?: 0f,
                                freqPll = _freqBox.value?.pllLock ?: 0f,
                                freqJitter = _freqBox.value?.jitter ?: 0f,
                                fp1 = "—",
                                banner = if (residFilterState?.active == true) "RESIDUAL" else if (audioAnomState?.unknown == true) "AUDIO" else "SPIKE",
                                note = audioAnomState?.note ?: residFilterState?.note ?: ""
                            )
                            _sessionRows.value = SessionLogger.rowCount()
                        }
                    } catch (_: Exception) {
                    }
                        _spectral.value = spectralState
                        _audioAnom.value = audioAnomState
                        _visionAnom.value = visionAnomState
                        _optFlow.value = optFlowState
                        _hud.value = hudEngine.build(_currentMode.value.name, out, fused)
                        _diag.value = diagEngine.build(out, fused, fusionNs)
                        _perf.value = perfEngine.buildState(
                            frameSkip = 2,
                            shaderThrottle = 0.85f,
                            avgFrameMs = perfEngine.updateFrameTime(_diag.value?.frameMs ?: 0f)
                        )
                    }

                    try {
                        audioEngine?.apply(out, fused)
                    } catch (_: Exception) {
                    }
                } catch (_: Exception) {
                } finally {
                    processing = false
                }
            }
        }

        sensors?.start()
    }

    fun calibrate() {
        engine.startCal()
        HardeningEngine.reset()
        TrustEngine.reset()
        AudioAnomalyEngine.reset()
        FrequencyBoxEngine.reset()
        CameraAnomalyEngine.reset()
        DenseFlowEngine.reset()
    }

    fun toggleBox() {
        if (box.on) {
            box.stop()
            _boxOn.value = false
        } else {
            box.mode = _sweep.value
            box.start()
            _boxOn.value = true
        }
        scanner.engine.fastAdapt()
    }

    fun setSweep(m: SweepMode) {
        _sweep.value = m
        box.mode = m
    }

    fun toggleWalk() {
        val next = !_walking.value
        _walking.value = next
        engine.setWalking(next)
    }

    fun resetSurvey() = engine.resetSurvey()

    override fun onCleared() {
        sensors?.stop()
        trial.stop()
        sonifier.stop()
        sonarIo.stop()
        harness.stop()
        particleCam.stop()
        passive.stop()
        ctxFeeds.stop()
        scanner.stop()
        box.stop()
        try {
            mic?.stop()
        } catch (_: Exception) {
        }
        mic = null
        try {
            marsRunner?.close()
        } catch (_: Exception) {
        }
        marsRunner = null
        try {
            audioEngine?.release()
        } catch (_: Exception) {
        }
        super.onCleared()
    }

    private fun idle() = EntityOutput(
        "normal",
        0f,
        0f,
        0f,
        0.5f,
        true,
        0.5f,
        0f,
        false,
        0f,
        SurveySnap(
            "quiet",
            0f,
            "Arm sensors, calibrate 8 s, then walk the property.",
            0f,
            0f,
            0f,
            null,
            null,
            0f,
            emptyList(),
            0f,
            0f
        ),
        "Standby.",
    )
}
