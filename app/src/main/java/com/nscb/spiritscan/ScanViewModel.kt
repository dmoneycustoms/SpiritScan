package com.nscb.spiritscan

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.nscb.spiritscan.entity.EntityEngine
import com.nscb.spiritscan.entity.EntityOutput
import com.nscb.spiritscan.entity.SurveySnap
import com.nscb.spiritscan.sensor.SensorStreamManager
import com.nscb.spiritscan.sensor.SpiritBox
import com.nscb.spiritscan.sensor.SweepMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = EntityEngine()
    private val box = SpiritBox()
    private var sensors: SensorStreamManager? = null
    private val _output = MutableStateFlow(idle())
    val output: StateFlow<EntityOutput> = _output
    private val _boxOn = MutableStateFlow(false)
    val boxOn: StateFlow<Boolean> = _boxOn
    private val _walking = MutableStateFlow(false)
    val walking: StateFlow<Boolean> = _walking
    private val _sweep = MutableStateFlow(SweepMode.HOP)
    val sweep: StateFlow<SweepMode> = _sweep

    fun arm(ctx: Context) {
        if (sensors != null) return
        engine.startCal()
        sensors = SensorStreamManager(ctx) { sample ->
            val snap = sensors?.buffer?.snapshot().orEmpty()
            _output.value = engine.process(
                sample, snap, visResidual = 0.01f, audioRms = box.rms,
                heading = sensors?.heading ?: 0f,
                ambientC = sensors?.ambientC,
                lux = sensors?.lux,
                lumHot = 0.55f,
                lumCold = 0.25f,
            )
        }
        sensors?.start()
    }

    fun calibrate() = engine.startCal()

    fun toggleBox() {
        if (box.on) {
            box.stop(); _boxOn.value = false
        } else {
            box.mode = _sweep.value
            box.start(); _boxOn.value = true
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
        super.onCleared()
    }

    private fun idle() = EntityOutput(
        "normal", 0f, 0f, 0f, 0.5f, true, 0.5f, 0f, false, 0f,
        SurveySnap("quiet", 0f, "Arm sensors, calibrate 8 s, then walk the property.", 0f, 0f, 0f, null, null, 0f, emptyList(), 0f, 0f),
        "Standby.",
    )
}
