// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.seek.SeekChain
import org.walktalkmeditate.pilgrim.domain.seek.SeekClearing
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint
import org.walktalkmeditate.pilgrim.service.TrackerStartExtras
import org.walktalkmeditate.pilgrim.service.WalkTrackingService
import org.walktalkmeditate.pilgrim.walk.seek.SeekSonarSettings
import org.walktalkmeditate.pilgrim.walk.seek.SeekStart

/**
 * The real seek intents (plan U25), built by the production
 * [WalkActionPublisher] and read back through the service's own decoders
 * (the house platform-object builder rule): the hand-off on ACTION_START,
 * a late hand-off, "Seek anew", and the sonar settings. A key drift between
 * the two processes surfaces here, not as a seek walk with no guidance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkActionPublisherSeekIntentTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val uuid = "11111111-2222-4333-8444-555555555555"
    private val handOff = SeekStart(
        chain = SeekChain(
            clearings = listOf(
                SeekClearing(SeekPoint(42.881_234_567, -8.544_812_345), radiusMeters = 97.5),
                SeekClearing(SeekPoint(42.885_1, -8.540_2), radiusMeters = 110.0),
            ),
            budgetMeters = 1_800.25,
        ),
        activeIndex = 1,
        durationMinutes = 45,
        tintHex = "#C9A646",
        seed = Long.MIN_VALUE + 7,
        seededAtEpochMillis = 1_700_000_000_123L,
        intention = "find the river",
        nextPulseDueAtMillis = 1_700_000_033_000L,
        sonar = SeekSonarSettings(sonarEnabled = true, sonarVolume = 0.35f, soundsEnabled = false),
    )
    private val seekRequest = WalkStartRequest(intention = "a slow morning", mode = WalkMode.Seek, walkUuid = uuid, seek = handOff)

    private fun publisher(bootNanos: () -> Long = { 1_000L }) =
        WalkActionPublisher(context, HonorCommandSequence(context), bootNanos)

    private fun nextStartedService(): Intent {
        val started = checkNotNull(shadowOf(context).nextStartedService) { "expected a service start" }
        assertEquals(ComponentName(context, WalkTrackingService::class.java), started.component)
        return started
    }

    // ACTION_START

    @Test
    fun `a seek start carries Begin's hand-off and the tracker reads it back whole`() {
        publisher().start(seekRequest)

        val started = nextStartedService()
        assertEquals(WalkTrackingService.ACTION_START, started.action)
        assertEquals(
            TrackerStartExtras(isFreshStart = true, request = seekRequest, honorGlanceUnits = null),
            WalkTrackingService.startExtrasFrom(started, honorEnabled = true),
        )
    }

    @Test
    fun `with the release flag off the tracker reads no hand-off, nor the uuid`() {
        publisher().start(seekRequest)

        val decoded = WalkTrackingService.startExtrasFrom(nextStartedService(), honorEnabled = false)
        assertEquals(WalkMode.Seek, decoded.request.mode)
        assertNull(decoded.request.seek)
        assertNull(decoded.request.walkUuid)
    }

    @Test
    fun `a hand-off with no tint, intention, or pulse due reads back without them`() {
        val bare = handOff.copy(tintHex = null, intention = null, nextPulseDueAtMillis = null)
        publisher().start(seekRequest.copy(seek = bare))

        assertEquals(bare, WalkTrackingService.startExtrasFrom(nextStartedService(), honorEnabled = true).request.seek)
    }

    @Test
    fun `an ordinary start carries no hand-off`() {
        publisher().start(WalkStartRequest(mode = WalkMode.Seek))

        assertNull(WalkTrackingService.seekStartFromExtras(nextStartedService()))
    }

    @Test
    fun `a chain that doesn't decode leaves the start without a hand-off`() {
        publisher().start(seekRequest)
        val started = nextStartedService().apply { putExtra(WalkTrackingService.EXTRA_SEEK_CHAIN, "{\"budget\":1}") }

        assertNull(WalkTrackingService.startExtrasFrom(started, honorEnabled = true).request.seek)
    }

    // The seek intents

    @Test
    fun `a late hand-off reads back as the same session`() {
        publisher().handOffSeekSession(handOff)

        val started = nextStartedService()
        assertEquals(WalkTrackingService.ACTION_SEEK_SESSION, started.action)
        assertEquals(handOff, WalkTrackingService.seekStartFromExtras(started))
    }

    @Test
    fun `seek anew carries a number that keeps rising past the boot clock`() {
        var boot = 5_000L
        val publisher = publisher { boot }

        publisher.sendSeekAnew()
        val first = nextStartedService()
        publisher.sendSeekAnew()
        val second = nextStartedService()
        boot = 9_000_000L
        publisher.sendSeekAnew()
        val third = nextStartedService()

        assertEquals(WalkTrackingService.ACTION_SEEK_ANEW, first.action)
        val seqs = listOf(first, second, third).map { it.getLongExtra(WalkTrackingService.EXTRA_SEEK_SEQ, 0L) }
        assertEquals(listOf(5_000L, 5_001L, 9_000_000L), seqs)
    }

    @Test
    fun `the sonar settings read back as sent, numbered`() {
        val settings = SeekSonarSettings(sonarEnabled = false, sonarVolume = 0.65f, soundsEnabled = true)
        publisher().publishSeekPreferences(settings)

        val started = nextStartedService()
        assertEquals(WalkTrackingService.ACTION_SEEK_PREFERENCES, started.action)
        assertEquals(settings, WalkTrackingService.seekSonarSettingsFromExtras(started))
        assertTrue(started.getLongExtra(WalkTrackingService.EXTRA_SEEK_SEQ, 0L) > 0L)
    }

    @Test
    fun `a sonar volume off the scale is clamped, and NaN is silent`() {
        val loud = publisher().seekPreferencesIntent(SeekSonarSettings(true, 2f, true), seq = 1L)
        val nan = publisher().seekPreferencesIntent(SeekSonarSettings(true, Float.NaN, true), seq = 2L)

        assertEquals(1f, WalkTrackingService.seekSonarSettingsFromExtras(loud).sonarVolume, 0f)
        assertEquals(0f, WalkTrackingService.seekSonarSettingsFromExtras(nan).sonarVolume, 0f)
    }

    @Test
    fun `every seek intent goes to the tracking service by its own action`() {
        val publisher = publisher()
        listOf(
            publisher.seekAnewIntent(seq = 1L) to WalkTrackingService.ACTION_SEEK_ANEW,
            publisher.seekPreferencesIntent(handOff.sonar, seq = 2L) to WalkTrackingService.ACTION_SEEK_PREFERENCES,
            publisher.seekSessionIntent(handOff) to WalkTrackingService.ACTION_SEEK_SESSION,
        ).forEach { (intent, action) ->
            assertEquals(action, intent.action)
            assertEquals(ComponentName(context, WalkTrackingService::class.java), intent.component)
        }
    }
}
