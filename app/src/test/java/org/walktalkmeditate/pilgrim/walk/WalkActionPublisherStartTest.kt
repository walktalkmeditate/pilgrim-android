// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.service.WalkTrackingService

/**
 * Exercises the real ACTION_START Intent construction in
 * [WalkActionPublisher.start] — the CLAUDE.md platform-object rule: the
 * `EXTRA_WALK_MODE` key must round-trip through a production Intent so
 * a producer/consumer key drift surfaces in CI, not as a silently
 * wander-defaulted seek on-device. Mirrors
 * [WalkActionPublisherSoundscapeTest]'s pattern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkActionPublisherStartTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val publisher = WalkActionPublisher(context)

    @Test
    fun `start carries the seek mode extra alongside the fresh flag and intention`() {
        publisher.start(intention = "find the river", mode = WalkMode.Seek)

        val started = shadowOf(context).nextStartedService
        assertNotNull("expected a foreground service start", started)
        assertEquals(WalkTrackingService.ACTION_START, started!!.action)
        assertEquals(
            WalkTrackingService::class.java.name,
            started.component?.className,
        )
        assertTrue(started.getBooleanExtra(WalkTrackingService.EXTRA_FRESH_START, false))
        assertEquals("find the river", started.getStringExtra(WalkTrackingService.EXTRA_INTENTION))
        // Assert through the same parse the service uses so the whole
        // wire contract is pinned, not just the raw string.
        assertEquals(
            WalkMode.Seek,
            WalkMode.fromWire(started.getStringExtra(WalkTrackingService.EXTRA_WALK_MODE)),
        )
    }

    @Test
    fun `start defaults to wander with no intention extra`() {
        publisher.start(intention = null)

        val started = shadowOf(context).nextStartedService
        assertNotNull(started)
        assertEquals(
            WalkMode.Wander,
            WalkMode.fromWire(started!!.getStringExtra(WalkTrackingService.EXTRA_WALK_MODE)),
        )
        assertEquals(null, started.getStringExtra(WalkTrackingService.EXTRA_INTENTION))
    }
}
