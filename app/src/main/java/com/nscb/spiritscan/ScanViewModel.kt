package com.nscb.spiritscan

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nscb.spiritscan.audio.AudioEngine
import com.nscb.spiritscan.engines.DiagnosticsEngine
import com.nscb.spiritscan.engines.DiagnosticsState
import com.nscb.spiritscan.engines.FusionEngine
import com.nscb.spiritscan.engines.FusionState
import com.nscb.spiritscan.engines.HudEngine
import com.nscb.spiritscan.engines.HudState
import com.nscb.spiritscan.engines.PerformanceEngine
import com.nscb.spiritscan.engines.PerformanceState
import com.nscb.spiritscan.entity.EntityEngine
import com.nscb.spiritscan.entity.EntityOutput
import com.nscb.spiritscan.entity.SurveySnap
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

    // v8.3 engines
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

    // Soft vision residual from camera frames
    @Volatile
    private var visionResidual: Float = 0.01f

    fun onVisionFrame(residual: Float) {
        visionResidual = residual.coerceIn(0f, 1f)
    }

    fun setMode(mode: ScanMode) {
        _currentMode.value = mode
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

        sensors = SensorStreamManager(ctx) { sample ->
            // OFFLOAD ARM PIPELINE TO BACKGROUND THREAD
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val snap = sensors?.buffer?.snapshot().orEmpty()

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

                    // UI STATE UPDATES ON MAIN THREAD
                    withContext(Dispatchers.Main) {
                        _output.value = out
                        _fusion.value = fused
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
                    // keep app from closing on ARM crash
                }
            }
        }

        sensors?.start()
    }

    fun calibrate() = engine.startCal()

    fun toggleBox() {
        if (box.on) {
            box.stop()
            _boxOn.value = false
        } else {
            box.mode = _sweep.value
            box.start()
            _boxOn.value = true
        }
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
        box.stop()
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
