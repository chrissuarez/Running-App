package com.example.runningapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Wraps the device barometer, when present, so each GPS track point can be paired with the
 * most recent pressure reading. Devices without a pressure sensor always read null.
 */
class BarometerReader(context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    @Volatile
    private var lastPressureHpa: Float? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            lastPressureHpa = event.values.firstOrNull()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        val sensor = pressureSensor ?: return
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        lastPressureHpa = null
    }

    fun getLastPressureHpa(): Float? = lastPressureHpa
}
