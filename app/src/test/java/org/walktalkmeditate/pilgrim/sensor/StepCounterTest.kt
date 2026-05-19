// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.sensor

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

/**
 * Real-runtime exercise of the [StepCounter] sensor-registration path,
 * mandated by CLAUDE.md's platform-object-builder rule (only the no-op
 * `fakeStepCounter` factory existed before this — registerListener +
 * SensorEvent delivery had zero coverage). Uses Robolectric's
 * [ShadowSensorManager] to inject a `TYPE_STEP_COUNTER` sensor and feed
 * synthetic cumulative-since-boot samples.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StepCounterTest {

    private lateinit var context: Context
    private lateinit var sensorManager: SensorManager
    private lateinit var shadow: ShadowSensorManager
    private lateinit var stepSensor: Sensor
    private lateinit var counter: StepCounter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shadow = shadowOf(sensorManager)
        stepSensor = ShadowSensor.newInstance(Sensor.TYPE_STEP_COUNTER)
        shadow.addSensor(Sensor.TYPE_STEP_COUNTER, stepSensor)
        counter = StepCounter(context)
    }

    private fun emit(cumulativeSinceBoot: Long) {
        val event: SensorEvent = ShadowSensorManager.createSensorEvent(1)
        event.values[0] = cumulativeSinceBoot.toFloat()
        shadow.sendSensorEventToListeners(event)
    }

    @Test
    fun liveStepsIsNullBeforeFirstEvent() {
        counter.start()
        assertNull(counter.liveSteps.value)
    }

    @Test
    fun liveStepsEmitsDeltaFromBaseline() {
        counter.start()
        emit(1000L)
        assertEquals(0, counter.liveSteps.value)
        emit(1042L)
        assertEquals(42, counter.liveSteps.value)
        emit(1100L)
        assertEquals(100, counter.liveSteps.value)
    }

    @Test
    fun stopReturnsDiffAndResetsLiveSteps() {
        counter.start()
        emit(500L)
        emit(587L)
        val result = counter.stop()
        assertEquals(87, result)
        assertNull(counter.liveSteps.value)
    }

    @Test
    fun stopReturnsNullWhenNoEventsArrived() {
        counter.start()
        assertNull(counter.stop())
    }

    @Test
    fun pauseResumeAccumulatesAndExcludesPausedSteps() {
        counter.start()
        emit(2000L)
        emit(2030L)
        assertEquals(30, counter.liveSteps.value)

        counter.pause()
        // 30 steps banked; live value holds across the pause.
        assertEquals(30, counter.liveSteps.value)

        // Steps the OS counts while paused (2030 -> 2090) must be
        // EXCLUDED — iOS StepCounter.swift:51-55 parity. The fresh
        // segment baseline is whatever the cumulative count is at the
        // first post-resume event (2090), so its delta starts at 0.
        counter.resume()
        emit(2090L)
        assertEquals(30, counter.liveSteps.value)
        emit(2105L)
        assertEquals(45, counter.liveSteps.value)

        val result = counter.stop()
        assertEquals(45, result)
    }

    @Test
    fun stopAfterPauseReturnsAccumulatedTotal() {
        counter.start()
        emit(100L)
        emit(160L)
        counter.pause()
        val result = counter.stop()
        assertEquals(60, result)
    }

    @Test
    fun resumeWithoutOpenSessionIsNoOp() {
        counter.resume()
        assertNull(counter.liveSteps.value)
        assertNull(counter.stop())
    }

    @Test
    fun regressionWithinSegmentCollapsesToAccumulatedPrefix() {
        counter.start()
        emit(5000L)
        emit(5050L)
        counter.pause()
        counter.resume()
        // Reboot mid-walk: cumulative counter resets, so the new
        // segment baseline (10) sees a later sample (5) regress.
        emit(10L)
        emit(5L)
        // Segment delta is negative; total collapses to the 50 banked
        // before the pause rather than going negative.
        assertEquals(50, counter.liveSteps.value)
        assertEquals(50, counter.stop())
    }
}
