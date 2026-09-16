package com.nscb.spiritscan.engines

import com.nscb.spiritscan.entity.SurveySnap
import kotlin.math.abs

/* ============================
   INTERFERENCE STATE MODEL (v8.3 update)
   ============================ */
data class InterferenceState(
    val rfSpike: Boolean,
    val emfSpike: Boolean,
    val magJitter: Boolean,
    val gyroDrift: Boolean,
    val luxFlicker: Boolean,
    val unstable: Boolean
)

/* ============================
   INTERFERENCE ENGINE
   ============================ */
class InterferenceEngine {

    private var lastMag = 0f
    private var lastLux = 0f
    private var lastGyro = 0f

    fun update(survey: SurveySnap): InterferenceState {

        val rfSpike = survey.magUt > 70f
        val emfSpike = abs(survey.zMag) > 18f
        val magJitter = abs(survey.magUt - lastMag) > 6f
        val gyroDrift = abs(survey.heading - lastGyro) > 12f
        val luxFlicker = survey.lux != null &&
                abs((survey.lux ?: 0f) - lastLux) > 40f

        val unstable = rfSpike || emfSpike || magJitter || gyroDrift || luxFlicker

        lastMag = survey.magUt
        lastLux = survey.lux ?: lastLux
        lastGyro = survey.heading

        return InterferenceState(
            rfSpike = rfSpike,
            emfSpike = emfSpike,
            magJitter = magJitter,
            gyroDrift = gyroDrift,
            luxFlicker = luxFlicker,
            unstable = unstable
        )
    }
}
