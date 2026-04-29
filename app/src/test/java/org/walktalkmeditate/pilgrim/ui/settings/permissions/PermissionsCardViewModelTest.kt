// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.permissions

import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.permissions.PermissionAskedStore
import org.walktalkmeditate.pilgrim.permissions.PermissionStatus

class PermissionsCardViewModelTest {

    @Test
    fun `granted permission emits Granted regardless of asked flag`() = runTest {
        val vm = PermissionsCardViewModel(
            checks = FakePermissionChecks(location = true, microphone = true, motion = true),
            askedFlags = FakeAskedStore(),
        )

        vm.state.test(timeout = 5.seconds) {
            var state = awaitItem()
            while (state.location != PermissionStatus.Granted) state = awaitItem()
            assertEquals(PermissionStatus.Granted, state.location)
            assertEquals(PermissionStatus.Granted, state.microphone)
            assertEquals(PermissionStatus.Granted, state.motion)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ungranted plus never-asked equals NotDetermined`() = runTest {
        val vm = PermissionsCardViewModel(
            checks = FakePermissionChecks(location = false, microphone = false, motion = false),
            askedFlags = FakeAskedStore(),
        )

        vm.state.test(timeout = 5.seconds) {
            val state = awaitItem()
            assertEquals(PermissionStatus.NotDetermined, state.location)
            assertEquals(PermissionStatus.NotDetermined, state.microphone)
            assertEquals(PermissionStatus.NotDetermined, state.motion)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ungranted plus asked equals Denied`() = runTest {
        val vm = PermissionsCardViewModel(
            checks = FakePermissionChecks(location = false, microphone = false, motion = false),
            askedFlags = FakeAskedStore(
                asked = setOf(
                    PermissionAskedStore.Key.Location,
                    PermissionAskedStore.Key.Microphone,
                    PermissionAskedStore.Key.Motion,
                ),
            ),
        )

        vm.state.test(timeout = 5.seconds) {
            var state = awaitItem()
            while (state.location != PermissionStatus.Denied) state = awaitItem()
            assertEquals(PermissionStatus.Denied, state.location)
            assertEquals(PermissionStatus.Denied, state.microphone)
            assertEquals(PermissionStatus.Denied, state.motion)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onPermissionResult flips not-determined to denied when ungranted`() = runTest {
        val asked = FakeAskedStore()
        val vm = PermissionsCardViewModel(
            checks = FakePermissionChecks(location = false, microphone = false, motion = false),
            askedFlags = asked,
        )

        vm.state.test(timeout = 5.seconds) {
            var state = awaitItem()
            while (state.location != PermissionStatus.NotDetermined) state = awaitItem()
            assertEquals(PermissionStatus.NotDetermined, state.location)
            vm.onPermissionResult(PermissionAskedStore.Key.Location)
            var next = awaitItem()
            while (next.location != PermissionStatus.Denied) next = awaitItem()
            assertEquals(PermissionStatus.Denied, next.location)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh re-reads live state`() = runTest {
        val checks = FakePermissionChecks(location = false, microphone = false, motion = false)
        val vm = PermissionsCardViewModel(checks = checks, askedFlags = FakeAskedStore())

        vm.state.test(timeout = 5.seconds) {
            var state = awaitItem()
            while (state.location != PermissionStatus.NotDetermined) state = awaitItem()
            checks.location = true
            vm.refresh()
            var next = awaitItem()
            while (next.location != PermissionStatus.Granted) next = awaitItem()
            assertEquals(PermissionStatus.Granted, next.location)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

private class FakePermissionChecks(
    var location: Boolean,
    var microphone: Boolean,
    var motion: Boolean,
) : LivePermissionChecks {
    override fun isLocationGranted(): Boolean = location
    override fun isMicrophoneGranted(): Boolean = microphone
    override fun isMotionGranted(): Boolean = motion
}

private class FakeAskedStore(asked: Set<PermissionAskedStore.Key> = emptySet()) : AskedFlagSource {
    private val flags = MutableStateFlow(asked)
    override fun asked(key: PermissionAskedStore.Key): Flow<Boolean> =
        flags.map { key in it }
    override suspend fun markAsked(key: PermissionAskedStore.Key) {
        flags.value = flags.value + key
    }
}
