// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate

/**
 * The place card's compass (iOS `HeadingProvider`, `HeadingProvider.swift:16-52@7c200bf`,
 * parity spec B §17, E §9), in the UI process only: the device's own
 * heading, apart from the walk's location pipeline. Degrees clockwise from
 * true north when the walker's place is known (iOS's `trueHeading`), else
 * from magnetic north; null while the compass reports itself unreliable,
 * which hides the tick (iOS's negative `headingAccuracy`). A new value only
 * once the heading has turned 3° (iOS's `headingFilter`). A phone with no
 * rotation sensor never emits, as iOS skips a phone without a compass.
 * Like iOS's default orientation, the heading is where the top of the
 * phone points.
 */
class DeviceHeading @Inject constructor(@ApplicationContext private val context: Context) {

    fun headings(place: () -> WayCoordinate?): Flow<Double?> = callbackFlow {
        val sensors = context.getSystemService(SensorManager::class.java)
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensors == null || sensor == null) {
            awaitClose {}
            return@callbackFlow
        }
        val filter = HeadingFilter()
        val rotation = FloatArray(ROTATION_MATRIX_SIZE)
        val orientation = FloatArray(ORIENTATION_SIZE)
        val listener = object : SensorEventListener {
            private var reliable = true

            override fun onSensorChanged(event: SensorEvent) {
                if (!reliable) return
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                val magnetic = (Math.toDegrees(orientation[0].toDouble()) + FULL_TURN) % FULL_TURN
                val heading = place()?.let { (magnetic + declination(it) + FULL_TURN) % FULL_TURN } ?: magnetic
                filter.next(heading)?.let { trySend(it) }
            }

            override fun onAccuracyChanged(changed: Sensor, accuracy: Int) {
                reliable = accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE &&
                    accuracy != SensorManager.SENSOR_STATUS_NO_CONTACT
                if (!reliable) {
                    filter.reset()
                    trySend(null)
                }
            }
        }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sensors.unregisterListener(listener) }
    }

    private fun declination(place: WayCoordinate): Double =
        GeomagneticField(place.lat.toFloat(), place.lon.toFloat(), 0f, System.currentTimeMillis()).declination.toDouble()

    private companion object {
        const val ROTATION_MATRIX_SIZE = 9
        const val ORIENTATION_SIZE = 3
        const val FULL_TURN = 360.0
    }
}
