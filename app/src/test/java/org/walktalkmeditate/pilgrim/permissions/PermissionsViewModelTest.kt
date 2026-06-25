// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.walktalkmeditate.pilgrim.audio.BellPlaying

/**
 * Orchestration tests for the #43 grant ritual, mirroring iOS
 * PermissionRitualTests' view-model section. The persisted once-flag is
 * faked in-memory so the ritual is synchronous and the scheduler fully
 * controls the pulse timing; the real DataStore-backed flag is covered by
 * PermissionsRepositoryTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var tempFile: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var ritualStore: FakeRitualStore
    private lateinit var bell: RecordingBell
    private lateinit var viewModel: PermissionsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempFile = File(
            System.getProperty("java.io.tmpdir"),
            "pilgrim-${UUID.randomUUID()}.preferences_pb",
        )
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFile },
        )
        ritualStore = FakeRitualStore()
        bell = RecordingBell()
        // celebrateGrant never touches the PermissionsRepository, but the ctor
        // requires one; the ritual flows through the faked PermissionRitualStore.
        viewModel = PermissionsViewModel(PermissionsRepository(dataStore), ritualStore, bell)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        dataStoreScope.cancel()
        tempFile.delete()
    }

    @Test
    fun `celebrateGrant fires the bell exactly once per permission`() {
        viewModel.celebrateGrant(PermissionRitual.Permission.Location, soundsEnabled = true, reduceMotion = false)
        viewModel.celebrateGrant(PermissionRitual.Permission.Location, soundsEnabled = true, reduceMotion = false)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, bell.playCount)
        assertEquals(listOf(0.5f), bell.scales)
        assertEquals(listOf(false), bell.haptics)
    }

    @Test
    fun `sounds off pulses the card but rings no bell`() {
        viewModel.celebrateGrant(PermissionRitual.Permission.Microphone, soundsEnabled = false, reduceMotion = false)
        dispatcher.scheduler.runCurrent()

        assertEquals(0, bell.playCount)
        assertTrue("the pulse still plays with sounds off", viewModel.microphonePulse.value)

        dispatcher.scheduler.advanceUntilIdle()
        assertFalse("the pulse springs back after the hold", viewModel.microphonePulse.value)
    }

    @Test
    fun `pulse fires only on the granted card`() {
        viewModel.celebrateGrant(PermissionRitual.Permission.Activity, soundsEnabled = true, reduceMotion = false)
        dispatcher.scheduler.runCurrent()

        assertTrue(viewModel.activityPulse.value)
        assertFalse(viewModel.locationPulse.value)
        assertFalse(viewModel.microphonePulse.value)
    }

    @Test
    fun `reduce-motion keeps the bell but skips the pulse`() {
        viewModel.celebrateGrant(PermissionRitual.Permission.Location, soundsEnabled = true, reduceMotion = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, bell.playCount)
        assertFalse(viewModel.locationPulse.value)
    }

    @Test
    fun `the grant haptic fires with the bell and not without it`() {
        var haptics = 0
        viewModel.celebrateGrant(
            PermissionRitual.Permission.Location,
            soundsEnabled = true,
            reduceMotion = false,
            onGrantHaptic = { haptics++ },
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("haptic fires alongside the bell", 1, haptics)

        viewModel.celebrateGrant(
            PermissionRitual.Permission.Microphone,
            soundsEnabled = false,
            reduceMotion = false,
            onGrantHaptic = { haptics++ },
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("no bell (sounds off) means no haptic", 1, haptics)
    }

    private class FakeRitualStore : PermissionRitualStore {
        private val played = mutableSetOf<PermissionRitual.Permission>()

        override suspend fun consumeBellGrant(
            permission: PermissionRitual.Permission,
            soundsEnabled: Boolean,
        ): Boolean {
            val should = PermissionRitual.shouldPlayBell(
                granted = true,
                soundsEnabled = soundsEnabled,
                alreadyPlayed = permission in played,
            )
            if (should) played += permission
            return should
        }

        override suspend fun hasPlayedBell(permission: PermissionRitual.Permission): Boolean =
            permission in played
    }

    private class RecordingBell : BellPlaying {
        var playCount = 0
        val scales = mutableListOf<Float>()
        val haptics = mutableListOf<Boolean>()

        override fun play() {
            playCount++
        }

        override fun play(scale: Float) {
            playCount++
            scales += scale
        }

        override fun play(scale: Float, withHaptic: Boolean) {
            playCount++
            scales += scale
            haptics += withHaptic
        }
    }
}
