// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.seek.SeekDirectionHint
import org.walktalkmeditate.pilgrim.domain.seek.SeekGlanceState

/**
 * Validates the per-state notification action set built by
 * [addWalkActionsForState]. Tests the helper directly so we don't have
 * to spin up the full WalkTrackingService + Hilt lifecycle. The service
 * itself is a thin wrapper that delegates here, so coverage of this
 * helper is coverage of the on-device notification shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkNotificationFactoryTest {

    private lateinit var context: Context
    private lateinit var actions: WalkNotificationActions

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ensureChannel()
        actions = WalkNotificationActions(
            pause = stubPendingIntent(1),
            resume = stubPendingIntent(2),
            endMeditation = stubPendingIntent(3),
            markWaypoint = stubPendingIntent(4),
            finish = stubPendingIntent(5),
        )
    }

    @Test
    fun `Active state notification has 3 actions — Pause, Mark Waypoint, Finish`() {
        val notification = buildAndCollect(activeState())
        assertEquals(3, notification.actions.size)
        assertEquals(getString(R.string.walk_notification_action_pause), notification.actions[0].title)
        assertEquals(getString(R.string.walk_notification_action_mark_waypoint), notification.actions[1].title)
        assertEquals(getString(R.string.walk_notification_action_finish), notification.actions[2].title)
        assertSame(actions.pause, notification.actions[0].actionIntent)
        assertSame(actions.markWaypoint, notification.actions[1].actionIntent)
        assertSame(actions.finish, notification.actions[2].actionIntent)
    }

    @Test
    fun `Paused state notification has 3 actions — Resume, Mark Waypoint, Finish`() {
        val notification = buildAndCollect(pausedState())
        assertEquals(3, notification.actions.size)
        assertEquals(getString(R.string.walk_notification_action_resume), notification.actions[0].title)
        assertEquals(getString(R.string.walk_notification_action_mark_waypoint), notification.actions[1].title)
        assertEquals(getString(R.string.walk_notification_action_finish), notification.actions[2].title)
        assertSame(actions.resume, notification.actions[0].actionIntent)
    }

    @Test
    fun `Meditating state notification has 2 actions — End Meditation, Finish`() {
        val notification = buildAndCollect(meditatingState())
        assertEquals(2, notification.actions.size)
        assertEquals(
            getString(R.string.walk_notification_action_end_meditation),
            notification.actions[0].title,
        )
        assertEquals(getString(R.string.walk_notification_action_finish), notification.actions[1].title)
        assertSame(actions.endMeditation, notification.actions[0].actionIntent)
    }

    @Test
    fun `Idle state notification has 0 actions`() {
        val notification = buildAndCollect(WalkState.Idle)
        // notification.actions is null when no actions were added.
        assertTrue(notification.actions == null || notification.actions.isEmpty())
    }

    @Test
    fun `Finished state notification has 0 actions`() {
        val notification = buildAndCollect(finishedState())
        assertTrue(notification.actions == null || notification.actions.isEmpty())
    }

    @Test
    fun `notificationText for Active includes the distance in km`() {
        val state = WalkState.Active(
            WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 1_234.0),
        )
        val text = walkNotificationText(context, state, UnitSystem.Metric)
        assertTrue("expected km in text but got: $text", text.contains("1.23"))
    }

    @Test
    fun `notificationText for Active with Imperial uses miles`() {
        val state = WalkState.Active(
            WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 1_609.34),
        )
        val text = walkNotificationText(context, state, UnitSystem.Imperial)
        // ~1 mile.
        assertTrue("expected mi in text but got: $text", text.contains("1.00 mi"))
    }

    @Test
    fun `notificationText for Imperial below 0_1 mi falls back to feet`() {
        // 100 m ≈ 0.062 mi → below the 0.1 mi threshold. The notification
        // text MUST use the same <0.1 mi → ft fallback as every other
        // display surface (WalkStatsSheet, WalkSummaryScreen, widget).
        // Pre-fix: notification showed "0.06 mi"; the rest of the app
        // showed "328 ft". The inconsistency was visible early in any
        // Imperial-mode walk. Locking in the delegation here so a future
        // refactor can't silently revive the inconsistency.
        val state = WalkState.Active(
            WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 100.0),
        )
        val text = walkNotificationText(context, state, UnitSystem.Imperial)
        assertTrue("expected feet in text but got: $text", text.contains(" ft"))
        assertTrue("expected feet, not miles, in text but got: $text", !text.contains(" mi"))
    }

    @Test
    fun `notificationText covers every WalkState branch without crashing`() {
        // Belt-and-braces: walkNotificationText is a when() over a sealed
        // class. If any future branch is added without updating the helper,
        // this loop pins the failure to a specific state instead of an
        // obscure NotificationManager-side render bug.
        listOf(
            WalkState.Idle,
            activeState(),
            pausedState(),
            meditatingState(),
            finishedState(),
        ).forEach { state ->
            val text = walkNotificationText(context, state, UnitSystem.Metric)
            assertNotNull("null text for $state", text)
            assertTrue("empty text for $state", text.isNotEmpty())
        }
    }

    // ─── U10 seek glance line (port spec
    //     docs/parity/2026-07-14-port-seek-glance-u10.md B5) ──────────

    @Test
    fun `wander Active text is byte-identical to the pre-seek rendering`() {
        // Golden string: U10 must not perturb wander notifications in
        // any way, glance parameter present or not.
        val state = WalkState.Active(
            WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 1_234.0),
        )
        assertEquals(
            "Walking — 1.23 km",
            walkNotificationText(context, state, UnitSystem.Metric),
        )
        assertEquals(
            "Walking — 1.23 km",
            walkNotificationText(context, state, UnitSystem.Metric, seekGlance = null),
        )
    }

    @Test
    fun `stray glance on a wander walk never renders a seek line`() {
        val state = WalkState.Active(
            WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 1_234.0, mode = WalkMode.Wander),
        )
        val text = walkNotificationText(
            context,
            state,
            UnitSystem.Metric,
            seekGlance = SeekGlanceState(400, SeekDirectionHint.AHEAD, isComplete = false),
        )
        assertEquals("Walking — 1.23 km", text)
    }

    @Test
    fun `seek Active with a glance renders bucket and direction`() {
        val text = walkNotificationText(
            context,
            seekActiveState(),
            UnitSystem.Metric,
            seekGlance = SeekGlanceState(400, SeekDirectionHint.AHEAD, isComplete = false),
        )
        assertEquals("Walking — 1.23 km · ~400 m ahead", text)
    }

    @Test
    fun `seek Active without a glance renders the plain walking line`() {
        val text = walkNotificationText(context, seekActiveState(), UnitSystem.Metric)
        assertEquals("Walking — 1.23 km", text)
    }

    @Test
    fun `seek Active hidden hint renders the bucket alone`() {
        val text = walkNotificationText(
            context,
            seekActiveState(),
            UnitSystem.Metric,
            seekGlance = SeekGlanceState(400, directionHint = null, isComplete = false),
        )
        assertEquals("Walking — 1.23 km · ~400 m", text)
    }

    @Test
    fun `seek Active completion renders seeking complete`() {
        val text = walkNotificationText(
            context,
            seekActiveState(),
            UnitSystem.Metric,
            seekGlance = SeekGlanceState(0, directionHint = null, isComplete = true),
        )
        assertEquals("Walking — 1.23 km · seeking complete", text)
    }

    @Test
    fun `seek distance ladder metric`() {
        // iOS seekDistanceText (PilgrimWidgetLiveActivity.swift:232-242
        // @c1745e8) — the metric arm.
        assertEquals("close", seekGlanceDistanceText(context, 0, UnitSystem.Metric))
        assertEquals("~400 m", seekGlanceDistanceText(context, 400, UnitSystem.Metric))
        assertEquals("~900 m", seekGlanceDistanceText(context, 900, UnitSystem.Metric))
        assertEquals("~1.0 km", seekGlanceDistanceText(context, 1_000, UnitSystem.Metric))
        assertEquals("~1.2 km", seekGlanceDistanceText(context, 1_200, UnitSystem.Metric))
        assertEquals("~1.9 km", seekGlanceDistanceText(context, 1_900, UnitSystem.Metric))
        assertEquals("2 km +", seekGlanceDistanceText(context, 2_000, UnitSystem.Metric))
    }

    @Test
    fun `seek distance ladder imperial`() {
        assertEquals("close", seekGlanceDistanceText(context, 0, UnitSystem.Imperial))
        assertEquals("~0.1 mi", seekGlanceDistanceText(context, 100, UnitSystem.Imperial))
        assertEquals("~0.3 mi", seekGlanceDistanceText(context, 500, UnitSystem.Imperial))
        assertEquals("~0.7 mi", seekGlanceDistanceText(context, 1_200, UnitSystem.Imperial))
        assertEquals("~1.2 mi", seekGlanceDistanceText(context, 1_900, UnitSystem.Imperial))
        assertEquals("1.2 mi +", seekGlanceDistanceText(context, 2_000, UnitSystem.Imperial))
    }

    @Test
    fun `seek glance line imperial with direction`() {
        val line = seekGlanceLine(
            context,
            SeekGlanceState(500, SeekDirectionHint.LEFT, isComplete = false),
            UnitSystem.Imperial,
        )
        assertEquals("~0.3 mi left", line)
    }

    @Test
    fun `seek mode notification builds with actions and seek text intact`() {
        // R11 house rule: the platform builder path must be exercised
        // for real — .build() on the seek-rendered notification with
        // the Active action set attached.
        val state = seekActiveState()
        val glance = SeekGlanceState(400, SeekDirectionHint.AHEAD, isComplete = false)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Pilgrim")
            .setContentText(walkNotificationText(context, state, UnitSystem.Metric, glance))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        addWalkActionsForState(builder, context, state, actions)
        val notification = builder.build()
        assertEquals(3, notification.actions.size)
        assertEquals(getString(R.string.walk_notification_action_pause), notification.actions[0].title)
        assertEquals(
            "Walking — 1.23 km · ~400 m ahead",
            notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
        )
    }

    private fun seekActiveState(): WalkState.Active = WalkState.Active(
        WalkAccumulator(
            walkId = 1L,
            startedAt = 0L,
            distanceMeters = 1_234.0,
            mode = WalkMode.Seek,
        ),
    )

    private fun buildAndCollect(state: WalkState): android.app.Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Pilgrim")
            .setContentText(walkNotificationText(context, state, UnitSystem.Metric))
        addWalkActionsForState(builder, context, state, actions)
        return builder.build()
    }

    private fun stubPendingIntent(requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent("test_action_$requestCode"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "test", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun getString(resId: Int): String = context.getString(resId)

    private fun activeState(): WalkState.Active =
        WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 0.0))

    private fun pausedState(): WalkState.Paused = WalkState.Paused(
        WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 0.0),
        pausedAt = 0L,
    )

    private fun meditatingState(): WalkState.Meditating = WalkState.Meditating(
        WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 0.0),
        meditationStartedAt = 0L,
    )

    private fun finishedState(): WalkState.Finished = WalkState.Finished(
        WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 0.0),
        endedAt = 0L,
    )

    private companion object {
        const val CHANNEL_ID = "walk_tracking_test"
    }
}
