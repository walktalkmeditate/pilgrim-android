// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.honor.HonorImportCoordinator
import org.walktalkmeditate.pilgrim.honor.HonorLinkRouter
import org.walktalkmeditate.pilgrim.ui.navigation.Routes
import org.walktalkmeditate.pilgrim.ui.navigation.honorLinkScreen
import org.walktalkmeditate.pilgrim.widget.DeepLinkTarget
import org.walktalkmeditate.pilgrim.widget.WidgetState
import org.walktalkmeditate.pilgrim.widget.asGlanceClick
import org.walktalkmeditate.pilgrim.widget.widgetClickIntent

/**
 * How MainActivity takes each intent it is handed (Phase 21 U27): the
 * widget's taps, Honor links, and a link opened outside Pilgrim's own task.
 * The Activity itself is left to the device pass; these are the decisions
 * it makes, on the production builders.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainActivityIntentsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val asked = mutableListOf<String>()

    private fun TestScope.router(honorEnabled: Boolean): HonorLinkRouter {
        val imports = HonorImportCoordinator(
            importShare = { id ->
                asked += id
                awaitCancellation()
            },
            honorEnabled = honorEnabled,
            scope = backgroundScope,
            media = { error("unused") },
        )
        return HonorLinkRouter(honorEnabled, imports, trackerWalking = { false }, scope = backgroundScope).also {
            it.screenChanged(honorLinkScreen(listOf(Routes.PATH)), owner = Any())
        }
    }

    // The widget's taps carry Glance's `glance-action:` data, and still open their screens.

    @Test
    fun `a Last Walk widget tap opens its summary, with the release flag on`() = runTest(dispatcher) {
        val tap = widgetClickIntent(context, lastWalk(42L)).asGlanceClick()

        val received = receiveIntent(tap, restored = false, links = router(honorEnabled = true))

        assertEquals(DeepLinkTarget.WalkSummary(42L), received.deepLink)
        assertSame("the widget's intent stays as it came", tap, received.attached)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a Last Walk widget tap opens its summary, with the release flag off`() = runTest(dispatcher) {
        val tap = widgetClickIntent(context, lastWalk(42L)).asGlanceClick()

        val received = receiveIntent(tap, restored = false, links = router(honorEnabled = false))

        assertEquals(DeepLinkTarget.WalkSummary(42L), received.deepLink)
    }

    @Test
    fun `an Empty widget tap opens the Journal, with the flag on and off`() = runTest(dispatcher) {
        listOf(true, false).forEach { honorEnabled ->
            val tap = widgetClickIntent(context, WidgetState.Empty).asGlanceClick()

            assertEquals(DeepLinkTarget.Home, receiveIntent(tap, restored = false, links = router(honorEnabled)).deepLink)
        }
    }

    @Test
    fun `a widget tap is never moved to another task`() {
        val tap = widgetClickIntent(context, lastWalk(42L)).asGlanceClick()

        assertFalse(movesToOwnTask(tap, isTaskRoot = false, honorEnabled = true))
    }

    // An Honor link routes once, and leaves nothing behind for a rebuild to read.

    @Test
    fun `a link routes, and the intent left attached carries neither it nor any extra`() = runTest(dispatcher) {
        val link = link().withWidgetExtras()

        val received = receiveIntent(link, restored = false, links = router(honorEnabled = true))

        assertEquals(listOf(ID), asked)
        assertNull(received.deepLink)
        assertNull(received.attached.data)
        assertNull(received.attached.extras?.getString(DeepLinkTarget.EXTRA_DEEP_LINK))
    }

    @Test
    fun `after a configuration change, a consumed link's widget extras open nothing`() = runTest(dispatcher) {
        val links = router(honorEnabled = true)
        val attached = receiveIntent(link().withWidgetExtras(), restored = false, links = links).attached

        val rebuilt = receiveIntent(attached, restored = true, links = links)

        assertNull(rebuilt.deepLink)
        assertEquals(listOf(ID), asked)
    }

    // A link opened without a new task moves to Pilgrim's own (P2: one MainActivity, in its own task).

    @Test
    fun `a link that starts a second MainActivity moves to Pilgrim's own task`() {
        assertTrue(movesToOwnTask(link(), isTaskRoot = false, honorEnabled = true))
        assertFalse("the task's own root keeps it", movesToOwnTask(link(), isTaskRoot = true, honorEnabled = true))
        assertFalse("the release build is left as it is", movesToOwnTask(link(), isTaskRoot = false, honorEnabled = false))
        assertFalse(
            "an instance the move started never moves again",
            movesToOwnTask(ownTaskIntent(context, link(), restored = false), isTaskRoot = false, honorEnabled = true),
        )
    }

    @Test
    fun `the move brings Pilgrim's MainActivity to the top of its own task with the link`() {
        val moved = ownTaskIntent(context, link().withWidgetExtras(), restored = false)

        assertEquals(ComponentName(context, MainActivity::class.java), moved.component)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(flags, moved.flags and flags)
        assertEquals(Intent.ACTION_VIEW, moved.action)
        assertEquals(link().data, moved.data)
        assertNull("only the link goes along", moved.getStringExtra(DeepLinkTarget.EXTRA_DEEP_LINK))
    }

    @Test
    fun `a link that wouldn't route here moves without it`() {
        val restored = ownTaskIntent(context, link(), restored = true)
        val fromRecents = ownTaskIntent(context, link().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), restored = false)
        val unparsed = ownTaskIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://honor.pilgrimapp.org/abc")), restored = false)

        listOf(restored, fromRecents, unparsed).forEach {
            assertNull(it.data)
            assertEquals(ComponentName(context, MainActivity::class.java), it.component)
        }
    }

    private fun link() = Intent(Intent.ACTION_VIEW, Uri.parse("https://honor.pilgrimapp.org/$ID"))

    private fun Intent.withWidgetExtras() = putExtra(DeepLinkTarget.EXTRA_DEEP_LINK, DeepLinkTarget.DEEP_LINK_WALK_SUMMARY)
        .putExtra(DeepLinkTarget.EXTRA_WALK_ID, 42L)

    private fun lastWalk(walkId: Long) =
        WidgetState.LastWalk(walkId = walkId, endTimestampMs = 0L, distanceMeters = 1_000.0, activeDurationMs = 600_000L)

    private companion object {
        const val ID = "Qoi4YmPHLN"
    }
}
