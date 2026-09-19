package com.nscb.spiritscan.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

data class Sample9(
    val tNs: Long,
    val magX: Float, val magY: Float, val magZ: Float,
    val accX: Float, val accY: Float, val accZ: Float,
    val gyroX: Float, val gyroY: Float, val gyroZ: Float,
) {
    val magUt: Float get() = hypot(hypot(magX, magY), magZ)
    val accG: Float get() = hypot(hypot(accX, accY), accZ)
}

class CircularBuffer(private val cap: Int = 150) {
    private val data = ArrayDeque<Sample9>(cap)
    fun add(s: Sample9) {
        if (data.size == cap) data.removeFirst()
        data.addLast(s)
    }
    fun ready() = data.size >= cap
    fun snapshot(): List<Sample9> = data.toList()
    fun magChannel() = data.map { it.magUt }
}

class SensorStreamManager(
    context: Context,
    private val onSample: (Sample9) -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val mag = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val ambient = manager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
    private val light = manager.getDefaultSensor(Sensor.TYPE_LIGHT)
    val buffer = CircularBuffer(150)
    @Volatile var lastMag = floatArrayOf(20f, 0f, 40f)
    @Volatile var lastAcc = floatArrayOf(0f, 0f, 9.81f)
    @Volatile var lastGyro = floatArrayOf(0f, 0f, 0f)
    @Volatile var ambientC: Float? = null
    @Volatile var lux: Float? = null
    @Volatile var heading = 0f

    fun start() {
        listOf(mag, accel, gyro, ambient, light).forEach { s ->
            if (s != null) manager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> lastMag = event.values.copyOf(3)
            Sensor.TYPE_ACCELEROMETER -> lastAcc = event.values.copyOf(3)
            Sensor.TYPE_GYROSCOPE -> lastGyro = event.values.copyOf(3)
            Sensor.TYPE_AMBIENT_TEMPERATURE -> ambientC = event.values[0]
            Sensor.TYPE_LIGHT -> lux = event.values[0]
        }
        val s = Sample9(
            event.timestamp,
            lastMag[0], lastMag[1], lastMag[2],
            lastAcc[0], lastAcc[1], lastAcc[2],
            lastGyro[0], lastGyro[1], lastGyro[2],
        )
        buffer.add(s)
        onSample(s)
        val g = lastMag
        if (g[0] != 0f || g[1] != 0f) {
            heading = Math.toDegrees(kotlin.math.atan2(g[1].toDouble(), g[0].toDouble())).toFloat()
            if (heading < 0) heading += 360f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/**
 * Online mean/variance. z() uses a minimum SD so a rock-still CAL
 * does not make every phone tilt look like z=±14.
 */
class Welford {
    var n = 0
    var mean = 0.0
    var m2 = 0.0

    fun push(x: Double) {
        n++
        val d = x - mean
        mean += d / n
        m2 += d * (x - mean)
    }

    /**
     * @param minSd floor on standard deviation (µT for |B|).
     * Default 2.5 µT — typical quiet indoor jitter floor.
     */
    fun z(x: Double, minSd: Double = 2.5): Float {
        if (n < 8) return 0f
        val rawSd = sqrt(m2 / (n - 1).coerceAtLeast(1))
        val sd = max(minSd, rawSd)
        return ((x - mean) / sd).toFloat()
    }

    fun reset() {
        n = 0
        mean = 0.0
        m2 = 0.0
    }
}
