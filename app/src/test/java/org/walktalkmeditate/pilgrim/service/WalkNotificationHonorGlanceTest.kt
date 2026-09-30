// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.walk.honor.HonorGlanceState

/**
 * The Honor glance line on the walk notification (parity spec D §10): iOS's
 * exact words, POSIX numbers, the line in every walk state, the units fixed
 * at Start, and a fingerprint that moves only when the words do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkNotificationHonorGlanceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var defaultLocale: Locale
    private val honorWalk = WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 1_234.0, mode = WalkMode.Honor)
    private val onTheWay = HonorGlanceState(distanceRemainingBucketMeters = 400, isOnWay = true, isArrived = false)

    @Before
    fun setUp() {
        defaultLocale = Locale.getDefault()
    }

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    private fun glance(bucket: Int, onWay: Boolean = true, arrived: Boolean = false) =
        HonorGlanceState(distanceRemainingBucketMeters = bucket, isOnWay = onWay, isArrived = arrived)

    private fun metric(bucket: Int) = honorGlanceLine(glance(bucket), UnitSystem.Metric)

    private fun imperial(bucket: Int) = honorGlanceLine(glance(bucket), UnitSystem.Imperial)

    // The words (PilgrimWidgetLiveActivity.swift:190-199,243-264@7c200bf)

    @Test
    fun `arrival reads their way, walked, whatever else the glance says`() {
        assertEquals("their way, walked", honorGlanceLine(glance(1_200, onWay = false, arrived = true), UnitSystem.Metric))
        assertEquals("their way, walked", honorGlanceLine(glance(0, arrived = true), UnitSystem.Imperial))
    }

    @Test
    fun `off the way reads off the way at any distance`() {
        assertEquals("off the way", honorGlanceLine(glance(2_000, onWay = false), UnitSystem.Metric))
        assertEquals("off the way", honorGlanceLine(glance(0, onWay = false), UnitSystem.Imperial))
    }

    @Test
    fun `under 100 m reads almost there`() {
        assertEquals("almost there", metric(0))
        assertEquals("almost there", imperial(0))
    }

    @Test
    fun `the last bucket reads 2 km+ or 1_2 mi+ to go, with no space before the plus`() {
        assertEquals("2 km+ to go", metric(2_000))
        assertEquals("1.2 mi+ to go", imperial(2_000))
    }

    @Test
    fun `metric middle buckets read metres, then tenths of a kilometre`() {
        assertEquals("~100 m to go", metric(100))
        assertEquals("~900 m to go", metric(900))
        assertEquals("~1.0 km to go", metric(1_000))
        assertEquals("~1.9 km to go", metric(1_900))
    }

    @Test
    fun `imperial middle buckets read tenths of a mile`() {
        assertEquals("~0.1 mi to go", imperial(100))
        assertEquals("~0.5 mi to go", imperial(800))
        assertEquals("~1.2 mi to go", imperial(1_900))
    }

    @Test
    fun `numbers keep a point in a comma locale`() {
        Locale.setDefault(Locale.FRANCE)

        assertEquals("~1.5 km to go", metric(1_500))
        assertEquals("~0.9 mi to go", imperial(1_500))
    }

    // The notification text, in every walk state

    @Test
    fun `an active honor walk shows its walked distance and the line`() {
        assertEquals(
            "Walking — 1.23 km · ~400 m to go",
            walkNotificationText(context, WalkState.Active(honorWalk), UnitSystem.Metric, honorGlance = onTheWay),
        )
    }

    @Test
    fun `paused and sitting still show the line, as iOS's row has no state gate`() {
        assertEquals(
            "Walk paused · ~400 m to go",
            walkNotificationText(context, WalkState.Paused(honorWalk, pausedAt = 5L), UnitSystem.Metric, honorGlance = onTheWay),
        )
        assertEquals(
            "Meditating · ~400 m to go",
            walkNotificationText(
                context,
                WalkState.Meditating(honorWalk, meditationStartedAt = 5L),
                UnitSystem.Metric,
                honorGlance = onTheWay,
            ),
        )
    }

    @Test
    fun `the line keeps the units fixed at Start after the walker switches`() {
        val text = walkNotificationText(
            context,
            WalkState.Active(honorWalk),
            units = UnitSystem.Imperial,
            honorGlance = glance(800),
            honorUnits = UnitSystem.Metric,
        )

        assertEquals("Walking — 0.77 mi · ~800 m to go", text)
    }

    @Test
    fun `with no glance yet an honor walk reads as any walk`() {
        assertEquals(
            context.getString(org.walktalkmeditate.pilgrim.R.string.walk_notification_active, "1.23 km"),
            walkNotificationText(context, WalkState.Active(honorWalk), UnitSystem.Metric, honorGlance = null),
        )
        assertEquals(
            context.getString(org.walktalkmeditate.pilgrim.R.string.walk_notification_paused),
            walkNotificationText(context, WalkState.Paused(honorWalk, pausedAt = 5L), UnitSystem.Metric),
        )
    }

    @Test
    fun `a glance never shows on a walk that isn't an honor walk`() {
        val wander = honorWalk.copy(mode = WalkMode.Wander)

        assertEquals(
            walkNotificationText(context, WalkState.Active(wander), UnitSystem.Metric),
            walkNotificationText(context, WalkState.Active(wander), UnitSystem.Metric, honorGlance = onTheWay),
        )
    }

    // The fingerprint

    private fun fingerprint(
        state: WalkState,
        glance: HonorGlanceState?,
        honorUnits: UnitSystem = UnitSystem.Metric,
    ) = WalkTrackingService.notificationFingerprint(state, seekGlance = null, unitsOrdinal = 0L, glance, honorUnits)

    @Test
    fun `walked-distance ticks alone never move an honor walk's fingerprint`() {
        val near = WalkState.Active(honorWalk.copy(distanceMeters = 100.0))
        val far = WalkState.Active(honorWalk.copy(distanceMeters = 480.0))

        assertEquals(fingerprint(near, onTheWay), fingerprint(far, onTheWay))
    }

    @Test
    fun `a changed line moves the fingerprint`() {
        val active = WalkState.Active(honorWalk)
        val base = fingerprint(active, onTheWay)

        assertNotEquals(base, fingerprint(active, glance(300)))
        assertNotEquals(base, fingerprint(active, glance(400, onWay = false)))
        assertNotEquals(base, fingerprint(active, glance(400, arrived = true)))
        assertNotEquals("the glance arriving is a change", base, fingerprint(active, null))
    }

    @Test
    fun `a bucket change the words don't show leaves the fingerprint alone`() {
        val active = WalkState.Active(honorWalk)

        assertEquals(fingerprint(active, glance(1_200, onWay = false)), fingerprint(active, glance(300, onWay = false)))
        assertEquals(fingerprint(active, glance(900, arrived = true)), fingerprint(active, glance(0, arrived = true)))
        // 100 m and 200 m both read "~0.1 mi to go".
        assertEquals(
            fingerprint(active, glance(100), UnitSystem.Imperial),
            fingerprint(active, glance(200), UnitSystem.Imperial),
        )
    }

    @Test
    fun `the display code matches the words one to one over every bucket`() {
        val glances = (0..2_000 step 100).flatMap { bucket ->
            listOf(glance(bucket), glance(bucket, onWay = false), glance(bucket, arrived = true))
        }
        for (units in UnitSystem.entries) {
            for (a in glances) {
                for (b in glances) {
                    assertEquals(
                        "$a vs $b in $units",
                        honorGlanceLine(a, units) == honorGlanceLine(b, units),
                        honorGlanceDisplayCode(a, units) == honorGlanceDisplayCode(b, units),
                    )
                }
            }
        }
    }

    @Test
    fun `paused and sitting fingerprints follow the line too`() {
        val paused = WalkState.Paused(honorWalk, pausedAt = 5L)

        assertEquals(
            fingerprint(paused, onTheWay),
            fingerprint(WalkState.Paused(honorWalk.copy(distanceMeters = 9_999.0), pausedAt = 5L), onTheWay),
        )
        assertNotEquals(fingerprint(paused, onTheWay), fingerprint(paused, glance(300)))
        assertNotEquals(fingerprint(paused, onTheWay), fingerprint(WalkState.Active(honorWalk), onTheWay))
    }

    @Test
    fun `a wander walk's fingerprint ignores any glance`() {
        val wander = WalkState.Active(honorWalk.copy(mode = WalkMode.Wander))

        assertEquals(fingerprint(wander, null), fingerprint(wander, onTheWay))
    }

    // The floor

    @Test
    fun `an active honor walk showing its glance takes the 15 s floor, so its walked distance stays fresh`() {
        assertTrue(WalkTrackingService.notifyFloorApplies(WalkState.Active(honorWalk), onTheWay))
        assertTrue(
            WalkTrackingService.shouldNotify(
                fingerprint = 1L,
                lastFingerprint = 1L,
                floorApplies = true,
                millisSinceLastNotify = WalkTrackingService.SEEK_NOTIFY_FLOOR_MILLIS,
            ),
        )
    }

    @Test
    fun `no floor without a glance, off honor, or while paused`() {
        assertFalse(WalkTrackingService.notifyFloorApplies(WalkState.Active(honorWalk), null))
        assertFalse(WalkTrackingService.notifyFloorApplies(WalkState.Active(honorWalk.copy(mode = WalkMode.Wander)), onTheWay))
        assertFalse(WalkTrackingService.notifyFloorApplies(WalkState.Paused(honorWalk, pausedAt = 5L), onTheWay))
    }

    @Test
    fun `an active seek walk keeps its floor`() {
        assertTrue(WalkTrackingService.notifyFloorApplies(WalkState.Active(honorWalk.copy(mode = WalkMode.Seek)), null))
    }
}
