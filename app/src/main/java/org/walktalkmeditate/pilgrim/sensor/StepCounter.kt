// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * iOS parity for `CMPedometer.queryPedometerData(from:to:)` — captures
 * cumulative step count at walk start, again at walk finish, and returns
 * the diff. Mirrors `Walk.steps: Int?` (`Walk.swift:122@db4196e`).
 *
 * Uses `Sensor.TYPE_STEP_COUNTER` (cumulative since last boot). No
 * permission needed below Android Q; ACTIVITY_RECOGNITION required on
 * Android Q+ — caller is responsible for the runtime grant before
 * [start]. Falls back to null when:
 *   - Device has no step-counter sensor (low-end / emulator)
 *   - Permission denied (sensor registration silently no-ops)
 *   - Cumulative count regresses (device rebooted mid-walk)
 *
 * Single-walk lifetime: [start] → [stop] pair. Re-entrant [start] calls
 * are idempotent — the in-progress baseline wins; the second call is
 * ignored. [stop] returns null if [start] was never called or already
 * stopped.
 */
@Singleton
class StepCounter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val stepSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    @Volatile
    private var baseline: Long? = null

    @Volatile
    private var latest: Long? = null

    @Volatile
    private var active: Boolean = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            val value = event?.values?.firstOrNull()?.toLong() ?: return
            if (baseline == null) baseline = value
            latest = value
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Register the step-counter listener. Idempotent — second call
     * while [active] is ignored. The sensor is `SENSOR_DELAY_NORMAL`
     * (~200ms cadence) which is enough resolution for the
     * start-to-finish diff and easy on battery.
     */
    fun start() {
        if (active) return
        val sm = sensorManager
        val sensor = stepSensor
        if (sm == null || sensor == null) {
            Log.i(TAG, "TYPE_STEP_COUNTER unavailable on this device")
            return
        }
        baseline = null
        latest = null
        val registered = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        if (registered) {
            active = true
            Log.i(TAG, "step counter started")
        } else {
            Log.w(TAG, "registerListener returned false; permission likely denied")
        }
    }

    /**
     * Unregister the listener and return the step diff for this walk.
     * Returns null when:
     *   - [start] was never called or sensor was unavailable
     *   - No sensor events arrived (baseline never set — very short walk
     *     under one sensor tick)
     *   - Cumulative regressed (device rebooted mid-walk)
     */
    fun stop(): Int? {
        if (!active) return null
        sensorManager?.unregisterListener(listener)
        active = false
        val start = baseline
        val end = latest
        baseline = null
        latest = null
        if (start == null || end == null) {
            Log.i(TAG, "step counter stopped without events")
            return null
        }
        val diff = end - start
        if (diff < 0L) {
            Log.w(TAG, "step counter regressed (likely reboot mid-walk): start=$start end=$end")
            return null
        }
        Log.i(TAG, "step counter stopped: steps=$diff")
        return diff.toInt()
    }

    private companion object {
        const val TAG = "StepCounter"
    }
}
