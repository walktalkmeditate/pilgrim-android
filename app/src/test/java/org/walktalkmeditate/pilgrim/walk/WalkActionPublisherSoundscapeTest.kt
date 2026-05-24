// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.service.WalkTrackingService

/**
 * Exercises the real Intent construction in [WalkActionPublisher]'s two
 * soundscape commands — the CLAUDE.md platform-object rule requires a
 * Robolectric test that builds the production `Intent` so a key-name
 * drift between publisher (producer) and service (consumer) surfaces in
 * CI rather than as a silently-dropped extra on-device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkActionPublisherSoundscapeTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val publisher = WalkActionPublisher(context)

    @Test
    fun `setSoundscapeEnabled starts the tracker with the on extra`() {
        publisher.setSoundscapeEnabled(true)

        val started = shadowOf(context).nextStartedService
        assertNotNull("expected a service start", started)
        assertEquals(WalkTrackingService.ACTION_SET_SOUNDSCAPE, started!!.action)
        assertEquals(
            WalkTrackingService::class.java.name,
            started.component?.className,
        )
        // Default must NOT be the value under test — assert the extra
        // actually round-trips, not the getBooleanExtra fallback.
        assertEquals(true, started.getBooleanExtra(WalkTrackingService.EXTRA_SOUNDSCAPE_ON, false))
    }

    @Test
    fun `setSoundscapeEnabled false round-trips the off extra`() {
        publisher.setSoundscapeEnabled(false)

        val started = shadowOf(context).nextStartedService
        assertNotNull(started)
        assertEquals(false, started!!.getBooleanExtra(WalkTrackingService.EXTRA_SOUNDSCAPE_ON, true))
    }

    @Test
    fun `selectSoundscape starts the tracker with the id extra`() {
        publisher.selectSoundscape("rain")

        val started = shadowOf(context).nextStartedService
        assertNotNull(started)
        assertEquals(WalkTrackingService.ACTION_SELECT_SOUNDSCAPE, started!!.action)
        assertEquals("rain", started.getStringExtra(WalkTrackingService.EXTRA_SOUNDSCAPE_ID))
    }
}
