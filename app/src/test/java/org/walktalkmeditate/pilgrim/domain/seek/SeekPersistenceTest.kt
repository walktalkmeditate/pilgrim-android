// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.walk.PRESET_CHIPS
import org.walktalkmeditate.pilgrim.ui.walk.WAYPOINT_CUSTOM_ICON_KEY

/**
 * Port of iOS `SeekPersistenceTests.swift@c1745e8` (vocabulary half —
 * the builder/checkpoint channel tests map to the U8/U9 controller
 * work). Robolectric because the ordinal labels resolve through real
 * string resources; the asserted English values are the cross-platform
 * contract shared with iOS's `NSLocalizedString` defaults.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SeekPersistenceTest {

    private val resources: Resources =
        ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun `reserved arrival icon is the iOS SF symbol verbatim`() {
        assertEquals("sun.haze", SeekPersistence.ARRIVAL_WAYPOINT_ICON)
    }

    @Test
    fun `isArrivalWaypoint matches by icon only`() {
        assertTrue(SeekPersistence.isArrivalWaypoint(SeekPersistence.ARRIVAL_WAYPOINT_ICON))
        assertFalse(SeekPersistence.isArrivalWaypoint("leaf"))
        assertFalse(SeekPersistence.isArrivalWaypoint(""))
        assertFalse(SeekPersistence.isArrivalWaypoint(null))
    }

    @Test
    fun `ordinal labels match iOS values`() {
        assertEquals("First clearing", SeekPersistence.arrivalWaypointLabel(resources, 1))
        assertEquals("Second clearing", SeekPersistence.arrivalWaypointLabel(resources, 2))
        assertEquals("Third clearing", SeekPersistence.arrivalWaypointLabel(resources, 3))
        assertEquals("Clearing 4", SeekPersistence.arrivalWaypointLabel(resources, 4))
        assertEquals("Clearing 12", SeekPersistence.arrivalWaypointLabel(resources, 12))
    }

    @Test
    fun `arrivalOrdinal counts persisted arrivals not the engine index`() {
        // Two arrivals already persisted among user waypoints → the next
        // arrival is 3 regardless of which clearing index the engine
        // replays after "Seek anew".
        val icons = listOf(
            SeekPersistence.ARRIVAL_WAYPOINT_ICON,
            "leaf",
            SeekPersistence.ARRIVAL_WAYPOINT_ICON,
            null,
        )
        assertEquals(3, SeekPersistence.arrivalOrdinal(icons))
    }

    @Test
    fun `arrivalOrdinal on a fresh walk is 1`() {
        assertEquals(1, SeekPersistence.arrivalOrdinal(emptyList()))
    }

    @Test
    fun `reserved icon collides with no user-pickable waypoint icon`() {
        val userIcons = PRESET_CHIPS.map { it.iconKey } + WAYPOINT_CUSTOM_ICON_KEY
        assertFalse(userIcons.contains(SeekPersistence.ARRIVAL_WAYPOINT_ICON))
    }
}
