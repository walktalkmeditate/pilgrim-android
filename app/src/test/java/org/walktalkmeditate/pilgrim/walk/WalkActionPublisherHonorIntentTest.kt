// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.service.TrackerStartExtras
import org.walktalkmeditate.pilgrim.service.WalkTrackingService
import org.walktalkmeditate.pilgrim.walk.honor.HonorCommand

/**
 * The real ACTION_START and Honor command intents, built by the production
 * [WalkActionPublisher] and read back through the service's own decoders
 * (the house platform-object builder rule): a key drift between the two
 * processes surfaces here, not as an honor walk silently started as a wander.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkActionPublisherHonorIntentTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val uuid = "11111111-2222-4333-8444-555555555555"
    private val honorRequest = WalkStartRequest(
        intention = "for her",
        mode = WalkMode.Honor,
        walkUuid = uuid,
        honor = HonorStart(
            wayId = "walk:0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50",
            settings = HonorSettings(voicesEnabled = true, softTapEnabled = false),
        ),
    )

    @Before
    fun clearSequence() {
        context.getSharedPreferences("honor_commands", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ACTION_START

    @Test
    fun `an honor start carries the uuid, the Way, the settings, and the glance's units`() {
        WalkActionPublisher(context).start(honorRequest, honorGlanceUnits = UnitSystem.Imperial)

        val started = nextStartedService()
        assertEquals(WalkTrackingService.ACTION_START, started.action)
        assertEquals(
            TrackerStartExtras(isFreshStart = true, request = honorRequest, honorGlanceUnits = UnitSystem.Imperial),
            WalkTrackingService.startExtrasFrom(started, honorEnabled = true),
        )
    }

    @Test
    fun `soft tap and voices ride as set`() {
        val request = honorRequest.copy(
            honor = honorRequest.honor!!.copy(settings = HonorSettings(voicesEnabled = false, softTapEnabled = true)),
        )
        WalkActionPublisher(context).start(request, honorGlanceUnits = UnitSystem.Metric)

        val decoded = WalkTrackingService.startExtrasFrom(nextStartedService(), honorEnabled = true)
        assertEquals(HonorSettings(voicesEnabled = false, softTapEnabled = true), decoded.request.honor!!.settings)
        assertEquals(UnitSystem.Metric, decoded.honorGlanceUnits)
    }

    @Test
    fun `with the release flag off the tracker reads no Honor extra`() {
        WalkActionPublisher(context).start(honorRequest, honorGlanceUnits = UnitSystem.Imperial)

        val decoded = WalkTrackingService.startExtrasFrom(nextStartedService(), honorEnabled = false)
        assertNull(decoded.request.walkUuid)
        assertNull(decoded.request.honor)
        assertNull(decoded.honorGlanceUnits)
    }

    @Test
    fun `an ordinary start carries no Honor extra`() {
        WalkActionPublisher(context).start(intention = "silence", mode = WalkMode.Seek)

        val started = nextStartedService()
        assertFalse(started.hasExtra(WalkTrackingService.EXTRA_WALK_UUID))
        assertFalse(started.hasExtra(WalkTrackingService.EXTRA_HONOR_WAY_ID))
        assertFalse(started.hasExtra(WalkTrackingService.EXTRA_HONOR_GLANCE_UNITS))
        assertEquals(
            TrackerStartExtras(
                isFreshStart = true,
                request = WalkStartRequest(intention = "silence", mode = WalkMode.Seek),
                honorGlanceUnits = null,
            ),
            WalkTrackingService.startExtrasFrom(started, honorEnabled = true),
        )
    }

    @Test
    fun `a watchdog revival's bare start reads as no fresh start and no Honor`() {
        val bare = WalkTrackingService.startIntent(context)

        assertEquals(
            TrackerStartExtras(isFreshStart = false, request = WalkStartRequest(), honorGlanceUnits = null),
            WalkTrackingService.startExtrasFrom(bare, honorEnabled = true),
        )
    }

    // Honor commands

    @Test
    fun `every command round-trips through its intent`() {
        val publisher = WalkActionPublisher(context)
        val commands = listOf(
            HonorCommand.TogglePlayback("voice-1"),
            HonorCommand.Scrub("voice-2", fraction = 0.4),
            HonorCommand.Skip,
            HonorCommand.CycleRate,
            HonorCommand.PlayReply("voice-3"),
        )

        for (command in commands) {
            publisher.sendHonorCommand(command)
            val intent = nextStartedService()
            assertEquals(WalkTrackingService.ACTION_HONOR_COMMAND, intent.action)
            assertEquals(WalkTrackingService::class.java.name, intent.component?.className)
            assertEquals(command, decode(intent))
        }
    }

    @Test
    fun `sequence numbers rise with every command, and past a UI restart`() {
        val first = WalkActionPublisher(context)
        first.sendHonorCommand(HonorCommand.Skip)
        val a = seqOf(nextStartedService())
        first.sendHonorCommand(HonorCommand.CycleRate)
        val b = seqOf(nextStartedService())

        // A new publisher is what a UI process restart builds.
        WalkActionPublisher(context).sendHonorCommand(HonorCommand.Skip)
        val c = seqOf(nextStartedService())

        assertTrue("got $a, $b, $c", a > 0 && b > a && c > b)
    }

    @Test
    fun `the stored number keeps the sequence rising when the clock stands still or runs back`() {
        val sequence = HonorCommandSequence(context, nowMillis = { 5_000L })
        val first = sequence.next()
        val second = HonorCommandSequence(context, nowMillis = { 1_000L }).next()

        assertEquals(5_000L, first)
        assertEquals(5_001L, second)
    }

    @Test
    fun `the clock keeps the sequence rising past a stored number a kill never flushed`() {
        HonorCommandSequence(context, nowMillis = { 5_000L }).next()
        context.getSharedPreferences("honor_commands", Context.MODE_PRIVATE).edit().clear().commit()

        assertEquals(9_000L, HonorCommandSequence(context, nowMillis = { 9_000L }).next())
    }

    // The service's surface

    @Test
    fun `the merged manifest keeps the walk service unexported in its own process`() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, WalkTrackingService::class.java), 0)

        assertFalse(info.exported)
        assertEquals("${context.packageName}:tracker", info.processName)
    }

    private fun nextStartedService(): Intent {
        val started = shadowOf(context).nextStartedService
        assertNotNull("expected a service start", started)
        return started!!
    }

    private fun seqOf(intent: Intent): Long = intent.getLongExtra(WalkTrackingService.EXTRA_HONOR_COMMAND_SEQ, -1L)

    /** Exactly the reads the service's command handler makes. */
    private fun decode(intent: Intent): HonorCommand? = WalkTrackingService.honorCommandFromExtras(
        kind = intent.getStringExtra(WalkTrackingService.EXTRA_HONOR_COMMAND),
        momentId = intent.getStringExtra(WalkTrackingService.EXTRA_HONOR_MOMENT_ID),
        fraction = intent.getDoubleExtra(WalkTrackingService.EXTRA_HONOR_SCRUB_FRACTION, 0.0),
    )
}
