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
            scanner.stop()
            _scanOn.value = false
        } else {
            scanner.start()
            _scanOn.value = scanner.running
        }
    }

    fun scanNullTest() = scanner.engine.startNull()

    fun scanNullClear() = scanner.engine.clearNull()

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
        _decode.value = decoder.process(
            plane.buffer, plane.rowStride, plane.pixelStride,
            ip.width, ip.height, ip.imageInfo.rotationDegrees,
            ip.imageInfo.timestamp, phone
        )
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
