package com.csjotlab.cardashboard.nav.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.csjotlab.cardashboard.nav.engine.HeadingMath
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Compass heading from the rotation-vector sensor. Used only while the car is stationary, when GPS
 * course-over-ground has no meaning.
 */
class CompassHeadingProvider(
    context: Context,
) : HeadingProvider {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _readings = MutableSharedFlow<Float>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val readings: Flow<Float> = _readings.asSharedFlow()

    private val listener = object : SensorEventListener {
        private val rotationMatrix = FloatArray(9)
        private val orientation = FloatArray(3)

        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val azimuthDegrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
            _readings.tryEmit(HeadingMath.normalize(azimuthDegrees))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override suspend fun start() {
        rotationVectorSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override suspend fun stop() {
        sensorManager.unregisterListener(listener)
    }
}
