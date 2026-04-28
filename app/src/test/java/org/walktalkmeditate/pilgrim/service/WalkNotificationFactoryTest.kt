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
import org.walktalkmeditate.pilgrim.domain.WalkState

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
