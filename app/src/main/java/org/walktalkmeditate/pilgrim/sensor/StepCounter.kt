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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS parity for `StepCounter.swift@db4196e` (`CMPedometer`). Streams a
 * live step count during the walk and returns the final diff at finish.
 * Mirrors `Walk.steps: Int?` (`Walk.swift:122`) and the live
 * `ActiveWalkViewModel.steps` (`ActiveWalkViewModel.swift:179-181`).
 *
 * Uses `Sensor.TYPE_STEP_COUNTER` (cumulative since last boot). No
 * permission needed below Android Q; `ACTIVITY_RECOGNITION` required on
 * Android Q+ — caller is responsible for the runtime grant before
 * [start]. Falls back to null when:
 *   - Device has no step-counter sensor (low-end / emulator)
 *   - Permission denied (sensor registration silently no-ops, OR
 *     registers but never delivers an event)
 *   - Cumulative count regresses (device rebooted mid-walk)
 *
 * iOS-faithful pause semantics: `StepCounter.swift:51-55` stops the
 * pedometer when `status != .recording` and resumes from a fresh date,
 * carrying the prior total in `stepsBeforeLastPause`. Steps taken while
 * Paused / Meditating are therefore EXCLUDED from the walk total. This
 * port reproduces that — `Sensor.TYPE_STEP_COUNTER` keeps ticking when
 * we are unregistered, so [pause] folds the current segment's delta into
 * [accumulatedBeforePause] and unregisters; [resume] re-registers and
 * starts a fresh segment baseline. The OS-counted steps between the
 * unregister and the next re-register event are never observed and so
 * never counted.
 *
 * Single-walk lifetime: [start] → ([pause]/[resume])* → [stop]. The
 * caller (WalkController) drives [pause]/[resume] from the reducer's
 * Active↔Paused / Active↔Meditating transitions. Re-entrant [start] is
 * idempotent. [stop] returns null if [start] was never called or
 * already stopped, or no event ever arrived.
 */
@Singleton
class StepCounter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val stepSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    /**
     * Baseline of the CURRENT recording segment — the cumulative
     * device-since-boot count captured on the first event after the most
     * recent [start] or [resume]. Null until that first event arrives.
     */
    @Volatile
    private var segmentBaseline: Long? = null

    /** Most recent cumulative device-since-boot count seen this segment. */
    @Volatile
    private var segmentLatest: Long? = null

    /**
     * iOS `stepsBeforeLastPause` — sum of all completed segments' deltas
     * before the current segment. Steps during Paused / Meditating are
     * NOT folded in here (the listener is unregistered for those spans).
     */
    @Volatile
    private var accumulatedBeforePause: Int = 0

    @Volatile
    private var active: Boolean = false

    /**
     * True between [start] and [stop], even while paused. Distinguishes
     * "walk in progress but listener suspended" (pause) from "no walk"
     * so [resume] is a no-op when called outside a walk.
     */
    @Volatile
    private var sessionOpen: Boolean = false

    private val _liveSteps = MutableStateFlow<Int?>(null)

    /**
     * iOS parity `ActiveWalkViewModel.steps` — live cumulative step
     * count for the in-progress walk. Null until the first sensor event
     * (no permission / no sensor / sub-tick walk). Holds its last value
     * across pause/resume; resets to null on the next [start].
     */
    val liveSteps: StateFlow<Int?> = _liveSteps.asStateFlow()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            val value = event?.values?.firstOrNull()?.toLong() ?: return
            if (segmentBaseline == null) segmentBaseline = value
            segmentLatest = value
            _liveSteps.value = currentTotalOrNull()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Current cumulative total across completed segments + the in-flight
     * one, or null when no event has ever arrived (so the UI shows the
     * genuine no-data dash rather than a misleading zero). A
     * within-segment regression (reboot mid-walk) collapses to the
     * accumulated prefix rather than going negative.
     */
    private fun currentTotalOrNull(): Int? {
        val base = segmentBaseline
        val end = segmentLatest
        if (base == null || end == null) {
            return if (accumulatedBeforePause > 0) accumulatedBeforePause else null
        }
        val segmentDelta = end - base
        if (segmentDelta < 0L) return accumulatedBeforePause
        return accumulatedBeforePause + segmentDelta.toInt()
    }

    /**
     * Register the step-counter listener and open a fresh walk session.
     * Idempotent — a second call while [active] is ignored. The sensor
     * is `SENSOR_DELAY_NORMAL` (~200ms cadence) which is enough
     * resolution for a live count and easy on battery.
     */
    fun start() {
        if (active) return
        val sm = sensorManager
        val sensor = stepSensor
        if (sm == null || sensor == null) {
            Log.i(TAG, "TYPE_STEP_COUNTER unavailable on this device")
            return
        }
        segmentBaseline = null
        segmentLatest = null
        accumulatedBeforePause = 0
        _liveSteps.value = null
        val registered = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        if (registered) {
            active = true
            sessionOpen = true
            Log.i(TAG, "step counter started")
        } else {
            Log.w(TAG, "registerListener returned false; permission likely denied")
        }
    }

    /**
     * iOS `StepCounter.swift:51-55` — suspend counting for a Paused /
     * Meditating span. Folds the current segment's delta into
     * [accumulatedBeforePause] and unregisters, so OS-counted steps
     * during the suspended span are never observed. No-op when not
     * actively counting (sensor unavailable, already paused, or no
     * session open).
     */
    fun pause() {
        if (!active) return
        sensorManager?.unregisterListener(listener)
        active = false
        val base = segmentBaseline
        val end = segmentLatest
        if (base != null && end != null) {
            val segmentDelta = end - base
            if (segmentDelta >= 0L) {
                accumulatedBeforePause += segmentDelta.toInt()
            }
        }
        segmentBaseline = null
        segmentLatest = null
        _liveSteps.value = currentTotalOrNull()
        Log.i(TAG, "step counter paused: accumulated=$accumulatedBeforePause")
    }

    /**
     * iOS `StepCounter.swift:48` (restart from a fresh date) — resume
     * counting after a [pause]. Re-registers the listener with a fresh
     * segment baseline. No-op when no session is open (e.g. resume
     * dispatched while sensor was unavailable from the outset) or
     * already actively counting.
     */
    fun resume() {
        if (active || !sessionOpen) return
        val sm = sensorManager
        val sensor = stepSensor
        if (sm == null || sensor == null) return
        segmentBaseline = null
        segmentLatest = null
        val registered = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        if (registered) {
            active = true
            Log.i(TAG, "step counter resumed")
        } else {
            Log.w(TAG, "registerListener returned false on resume; permission likely revoked")
        }
    }

    /**
     * Unregister the listener, close the session, and return the step
     * diff for this walk. Returns null when:
     *   - [start] was never called or sensor was unavailable
     *   - No sensor events ever arrived (baseline never set — very short
     *     walk under one sensor tick, or permission denied)
     *   - Cumulative regressed within a segment (device rebooted
     *     mid-walk) AND nothing was accumulated before it
     */
    fun stop(): Int? {
        if (!sessionOpen) return null
        if (active) sensorManager?.unregisterListener(listener)
        val total = currentTotalOrNull()
        active = false
        sessionOpen = false
        segmentBaseline = null
        segmentLatest = null
        accumulatedBeforePause = 0
        _liveSteps.value = null
        if (total == null) {
            Log.i(TAG, "step counter stopped without events")
            return null
        }
        Log.i(TAG, "step counter stopped: steps=$total")
        return total
    }

    private companion object {
        const val TAG = "StepCounter"
    }
}
