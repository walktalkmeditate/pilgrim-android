// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.connect

import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.feedback.FeedbackCategory
import org.walktalkmeditate.pilgrim.data.feedback.FeedbackError

class FeedbackViewModelTest {

    @Test
    fun `canSubmit requires both category and non-empty message`() = runTest {
        val vm = FeedbackViewModel(FakeService(), FakeDeviceInfo("Android 14"))

        vm.state.test(timeout = 5.seconds) {
            assertFalse(awaitItem().canSubmit)

            vm.selectCategory(FeedbackCategory.Bug)
            assertFalse(awaitItem().canSubmit) // message still empty

            vm.updateMessage("hello")
            assertTrue(awaitItem().canSubmit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submit success transitions to confirmation`() = runTest {
        val service = FakeService()
        val vm = FeedbackViewModel(service, FakeDeviceInfo("Android 14"))
        vm.selectCategory(FeedbackCategory.Bug)
        vm.updateMessage("test")

        vm.submit()

        vm.state.test(timeout = 5.seconds) {
            // Drain to the eventual showConfirmation=true emission.
            var current = awaitItem()
            while (!current.showConfirmation) {
                current = awaitItem()
            }
            assertTrue(current.showConfirmation)
            assertFalse(current.isSubmitting)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("bug", service.lastCategory)
        assertEquals("test", service.lastMessage)
        assertEquals("Android 14", service.lastDeviceInfo)
    }

    @Test
    fun `device info omitted when toggle off`() = runTest {
        val service = FakeService()
        val vm = FeedbackViewModel(service, FakeDeviceInfo("Android 14"))
        vm.selectCategory(FeedbackCategory.Feature)
        vm.updateMessage("ok")
        vm.toggleIncludeDeviceInfo(false)

        vm.submit()
        vm.state.test(timeout = 5.seconds) {
            var current = awaitItem()
            while (!current.showConfirmation) {
                current = awaitItem()
            }
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(service.lastDeviceInfo)
    }

    @Test
    fun `rate-limited error surfaces specific message`() = runTest {
        val service = FakeService(throwOnNextSubmit = FeedbackError.RateLimited)
        val vm = FeedbackViewModel(service, FakeDeviceInfo("Android 14"))
        vm.selectCategory(FeedbackCategory.Thought)
        vm.updateMessage("rate me out")

        vm.submit()
        vm.state.test(timeout = 5.seconds) {
            var current = awaitItem()
            while (current.errorMessage == null) current = awaitItem()
            assertEquals("Too many submissions today.", current.errorMessage)
            assertFalse(current.showConfirmation)
            assertFalse(current.isSubmitting)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `network error surfaces generic message`() = runTest {
        val service = FakeService(throwOnNextSubmit = FeedbackError.NetworkError("offline"))
        val vm = FeedbackViewModel(service, FakeDeviceInfo("Android 14"))
        vm.selectCategory(FeedbackCategory.Bug)
        vm.updateMessage("hello")

        vm.submit()
        vm.state.test(timeout = 5.seconds) {
            var current = awaitItem()
            while (current.errorMessage == null) current = awaitItem()
            assertEquals("Couldn't send — please try again", current.errorMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

private class FakeService(
    private var throwOnNextSubmit: Throwable? = null,
) : FeedbackSubmitter {
    var lastCategory: String? = null
    var lastMessage: String? = null
    var lastDeviceInfo: String? = null

    override suspend fun submit(category: String, message: String, deviceInfo: String?) {
        throwOnNextSubmit?.let { throwOnNextSubmit = null; throw it }
        lastCategory = category
        lastMessage = message
        lastDeviceInfo = deviceInfo
    }
}

private class FakeDeviceInfo(private val info: String) : DeviceInfoProvider {
    override fun deviceInfo(): String = info
}
