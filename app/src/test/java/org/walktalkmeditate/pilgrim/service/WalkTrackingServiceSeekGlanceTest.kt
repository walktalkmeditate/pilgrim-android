// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.seek.SeekDirectionHint
import org.walktalkmeditate.pilgrim.domain.seek.SeekGlanceState
import org.walktalkmeditate.pilgrim.walk.WalkActionPublisher

/**
 * U10 tracker-side throttle + transport shape. The notify gate mirrors
 * iOS `WalkActivityManager.shouldPush` (`SeekLiveActivityTests.swift
 * :132-183@c1745e8`): a changed glance alone forces a notify, an
 * unchanged glance inside the 15 s floor does not, and the floor forces
 * one. Pure companion functions per the [WalkTrackingServiceDecisionTest]
 * precedent; the intent round-trip runs the real [WalkActionPublisher]
 * against Robolectric's captured service intents. Port spec:
 * docs/parity/2026-07-14-port-seek-glance-u10.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkTrackingServiceSeekGlanceTest {

    private val glance = SeekGlanceState(400, SeekDirectionHint.AHEAD, isComplete = false)

    // ─── Fingerprint (the glance IS the throttle key) ─────────────────

    @Test
    fun `identical glances produce identical fingerprints across distance ticks`() {
        // The seek fingerprint deliberately drops the 5 m distance
        // component: walked-distance ticks alone never rebuild a seek
        // notification (the 15 s floor refreshes the prefix instead).
        val a = WalkTrackingService.notificationFingerprint(seekActive(distanceMeters = 100.0), glance, 0L)
        val b = WalkTrackingService.notificationFingerprint(seekActive(distanceMeters = 480.0), glance, 0L)
        assertEquals(a, b)
    }

    @Test
    fun `fingerprint distinguishes bucket hint and completion`() {
        val base = WalkTrackingService.notificationFingerprint(seekActive(), glance, 0L)
        assertNotEquals(
            base,
            WalkTrackingService.notificationFingerprint(
                seekActive(),
                glance.copy(distanceBucketMeters = 300),
                0L,
            ),
        )
        assertNotEquals(
            base,
            WalkTrackingService.notificationFingerprint(
                seekActive(),
                glance.copy(directionHint = SeekDirectionHint.LEFT),
                0L,
            ),
        )
        assertNotEquals(
            base,
            WalkTrackingService.notificationFingerprint(
                seekActive(),
                glance.copy(directionHint = null),
                0L,
            ),
        )
        assertNotEquals(
            base,
            WalkTrackingService.notificationFingerprint(
                seekActive(),
                SeekGlanceState(0, null, isComplete = true),
                0L,
            ),
        )
        assertNotEquals(
            base,
            WalkTrackingService.notificationFingerprint(seekActive(), null, 0L),
        )
    }

    @Test
    fun `fingerprint tracks state class and units`() {
        val active = WalkTrackingService.notificationFingerprint(seekActive(), glance, 0L)
        val paused = WalkTrackingService.notificationFingerprint(
            WalkState.Paused(seekWalk(1_234.0), pausedAt = 0L),
            glance,
            0L,
        )
        val imperial = WalkTrackingService.notificationFingerprint(seekActive(), glance, 1L)
        assertNotEquals(active, paused)
        assertNotEquals(active, imperial)
    }

    @Test
    fun `wander fingerprint ignores the glance and keeps the 5 m distance bucket`() {
        val wander = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 100.0))
        assertEquals(
            WalkTrackingService.notificationFingerprint(wander, null, 0L),
            WalkTrackingService.notificationFingerprint(wander, glance, 0L),
        )
        val moved = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 105.0))
        assertNotEquals(
            WalkTrackingService.notificationFingerprint(wander, null, 0L),
            WalkTrackingService.notificationFingerprint(moved, null, 0L),
        )
    }

    // ─── Notify gate ──────────────────────────────────────────────────

    @Test
    fun `glance change alone forces a notify`() {
        val last = WalkTrackingService.notificationFingerprint(seekActive(), glance, 0L)
        val next = WalkTrackingService.notificationFingerprint(
            seekActive(),
            glance.copy(distanceBucketMeters = 300),
            0L,
        )
        assertTrue(
            WalkTrackingService.shouldNotify(
                fingerprint = next,
                lastFingerprint = last,
                isActiveSeek = true,
                millisSinceLastNotify = 1,
            ),
        )
    }

    @Test
    fun `identical glance inside the floor does not notify`() {
        val fingerprint = WalkTrackingService.notificationFingerprint(seekActive(), glance, 0L)
        assertFalse(
            WalkTrackingService.shouldNotify(
                fingerprint = fingerprint,
                lastFingerprint = fingerprint,
                isActiveSeek = true,
                millisSinceLastNotify = WalkTrackingService.SEEK_NOTIFY_FLOOR_MILLIS - 1,
            ),
        )
    }

    @Test
    fun `the floor forces a notify at exactly 15 seconds`() {
        val fingerprint = WalkTrackingService.notificationFingerprint(seekActive(), glance, 0L)
        assertTrue(
            WalkTrackingService.shouldNotify(
                fingerprint = fingerprint,
                lastFingerprint = fingerprint,
                isActiveSeek = true,
                millisSinceLastNotify = WalkTrackingService.SEEK_NOTIFY_FLOOR_MILLIS,
            ),
        )
    }

    @Test
    fun `wander walks have no floor — unchanged fingerprint never renotifies`() {
        val wander = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 100.0))
        val fingerprint = WalkTrackingService.notificationFingerprint(wander, null, 0L)
        assertFalse(
            WalkTrackingService.shouldNotify(
                fingerprint = fingerprint,
                lastFingerprint = fingerprint,
                isActiveSeek = false,
                millisSinceLastNotify = 600_000,
            ),
        )
    }

    // ─── Stray-intent guard (no active pipeline → self-stop) ──────────

    @Test
    fun `glance with no active pipeline stops the service`() {
        // A late glance intent after the tracker pipeline tore down (FGS
        // timeout, OEM cleanup) must stop the revived service instead of
        // leaving it started-but-unpromoted — the soundscape-guard twin.
        assertEquals(
            WalkTrackingService.SeekGlanceAction.StopNoPipeline,
            WalkTrackingService.decideSeekGlanceAction(pipelineActive = false),
        )
    }

    @Test
    fun `glance with a live pipeline stores and re-renders`() {
        assertEquals(
            WalkTrackingService.SeekGlanceAction.StoreAndRender,
            WalkTrackingService.decideSeekGlanceAction(pipelineActive = true),
        )
    }

    // ─── Transport round-trip (UI intent → tracker decode) ────────────

    @Test
    fun `publishSeekGlance round-trips through the service intent`() {
        assertEquals(glance, publishAndDecode(glance))
        assertEquals(
            SeekGlanceState(1200, directionHint = null, isComplete = false),
            publishAndDecode(SeekGlanceState(1200, directionHint = null, isComplete = false)),
        )
        assertEquals(
            SeekGlanceState(0, directionHint = null, isComplete = true),
            publishAndDecode(SeekGlanceState(0, directionHint = null, isComplete = true)),
        )
    }

    @Test
    fun `publishSeekGlance null clears the glance`() {
        assertNull(publishAndDecode(null))
    }

    @Test
    fun `unknown direction name on the wire collapses to no hint`() {
        // Forward-compat: a stale intent from a future binary must not
        // crash the tracker (the WalkMode.fromWire convention).
        val decoded = WalkTrackingService.seekGlanceFromExtras(
            present = true,
            bucketMeters = 400,
            directionName = "UPWARD",
            isComplete = false,
        )
        assertEquals(SeekGlanceState(400, directionHint = null, isComplete = false), decoded)
    }

    private fun publishAndDecode(published: SeekGlanceState?): SeekGlanceState? {
        val application = ApplicationProvider.getApplicationContext<Application>()
        WalkActionPublisher(application).publishSeekGlance(published)
        val intent: Intent = shadowOf(application).nextStartedService
        assertEquals(WalkTrackingService.ACTION_UPDATE_SEEK_GLANCE, intent.action)
        // Decode exactly the way handleSeekGlanceAction reads the extras.
        return WalkTrackingService.seekGlanceFromExtras(
            present = intent.getBooleanExtra(WalkTrackingService.EXTRA_SEEK_GLANCE_PRESENT, false),
            bucketMeters = intent.getIntExtra(WalkTrackingService.EXTRA_SEEK_GLANCE_BUCKET, 0),
            directionName = intent.getStringExtra(WalkTrackingService.EXTRA_SEEK_GLANCE_DIRECTION),
            isComplete = intent.getBooleanExtra(WalkTrackingService.EXTRA_SEEK_GLANCE_COMPLETE, false),
        )
    }

    private fun seekWalk(distanceMeters: Double): WalkAccumulator = WalkAccumulator(
        walkId = 1L,
        startedAt = 0L,
        distanceMeters = distanceMeters,
        mode = WalkMode.Seek,
    )

    private fun seekActive(distanceMeters: Double = 1_234.0): WalkState.Active =
        WalkState.Active(seekWalk(distanceMeters))
}
