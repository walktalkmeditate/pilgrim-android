// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
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
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.sensor.StepCounter

/**
 * Integration coverage for the WalkController ↔ StepCounter pause/resume
 * wiring (`syncStepCounter`). Verifies steps taken while Paused /
 * Meditating are excluded from `controller.liveSteps`, mirroring iOS
 * `StepCounter.swift:75-83` (pedometer records only while `.recording`).
 * Uses a Robolectric-shadowed `TYPE_STEP_COUNTER` sensor so the
 * controller's transitions drive a real StepCounter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkControllerStepCounterTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var clock: FixedClock
    private lateinit var shadow: ShadowSensorManager
    private lateinit var stepCounter: StepCounter
    private lateinit var controller: WalkController

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WalkRepository(
            database = db,
            walkDao = db.walkDao(),
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = db.walkEventDao(),
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
            walkPhotoDao = db.walkPhotoDao(),
        )
        clock = FixedClock(initial = 1_000L)
        val sensorManager =
            context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shadow = shadowOf(sensorManager)
        shadow.addSensor(
            Sensor.TYPE_STEP_COUNTER,
            ShadowSensor.newInstance(Sensor.TYPE_STEP_COUNTER),
        )
        stepCounter = StepCounter(context)
        controller = WalkControllerImpl(repository, clock, stepCounter)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun emit(cumulativeSinceBoot: Long) {
        val event: SensorEvent = ShadowSensorManager.createSensorEvent(1)
        event.values[0] = cumulativeSinceBoot.toFloat()
        shadow.sendSensorEventToListeners(event)
    }

    @Test
    fun `startWalk arms the live step counter`() = runTest {
        controller.startWalk()
        assertNull(controller.liveSteps.value)
        emit(1000L)
        emit(1020L)
        assertEquals(20, controller.liveSteps.value)
    }

    @Test
    fun `pause excludes steps then resume keeps accumulating`() = runTest {
        controller.startWalk()
        emit(3000L)
        emit(3025L)
        assertEquals(25, controller.liveSteps.value)

        controller.pauseWalk()
        // OS counts steps during the pause; they must NOT be observed.
        emit(3100L)
        assertEquals(25, controller.liveSteps.value)

        controller.resumeWalk()
        emit(3100L)
        assertEquals(25, controller.liveSteps.value)
        emit(3110L)
        assertEquals(35, controller.liveSteps.value)
    }

    @Test
    fun `meditation start and end gate the step counter`() = runTest {
        controller.startWalk()
        emit(7000L)
        emit(7040L)
        assertEquals(40, controller.liveSteps.value)

        controller.startMeditation()
        emit(7200L)
        assertEquals(40, controller.liveSteps.value)

        controller.endMeditation()
        emit(7200L)
        emit(7205L)
        assertEquals(45, controller.liveSteps.value)
    }

    @Test
    fun `finishWalk persists the accumulated step total and clears live count`() = runTest {
        val walk = controller.startWalk()
        emit(100L)
        emit(150L)
        controller.pauseWalk()
        // Excluded paused steps.
        controller.resumeWalk()
        emit(900L)
        emit(912L)
        controller.finishWalk()

        assertEquals(62, repository.getWalk(walk.id)?.steps)
        assertNull(controller.liveSteps.value)
    }

    @Test
    fun `discardWalk stops the counter without persisting steps`() = runTest {
        val walk = controller.startWalk()
        emit(200L)
        emit(260L)
        controller.discardWalk()

        assertNull(repository.getWalk(walk.id))
        assertNull(controller.liveSteps.value)
    }
}

private class FixedClock(private val initial: Long) : Clock {
    override fun now(): Long = initial
}
