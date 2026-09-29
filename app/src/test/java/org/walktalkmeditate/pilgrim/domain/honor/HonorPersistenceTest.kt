// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence
import org.walktalkmeditate.pilgrim.ui.walk.PRESET_CHIPS
import org.walktalkmeditate.pilgrim.ui.walk.WAYPOINT_CUSTOM_ICON_KEY

/** Vocabulary half of iOS `HonorPersistence.swift@7c200bf`. */
class HonorPersistenceTest {

    @Test
    fun `reserved arrival icon is the iOS SF symbol verbatim`() {
        assertEquals("signpost.right.fill", HonorPersistence.ARRIVAL_WAYPOINT_ICON)
    }

    @Test
    fun `isArrivalWaypoint matches by icon only`() {
        assertTrue(HonorPersistence.isArrivalWaypoint(HonorPersistence.ARRIVAL_WAYPOINT_ICON))
        assertFalse(HonorPersistence.isArrivalWaypoint(SeekPersistence.ARRIVAL_WAYPOINT_ICON))
        assertFalse(HonorPersistence.isArrivalWaypoint("leaf"))
        assertFalse(HonorPersistence.isArrivalWaypoint(""))
        assertFalse(HonorPersistence.isArrivalWaypoint(null))
    }

    @Test
    fun `reserved icon collides with no user-pickable icon and not with Seek's`() {
        val takenIcons = PRESET_CHIPS.map { it.iconKey } +
            WAYPOINT_CUSTOM_ICON_KEY +
            SeekPersistence.ARRIVAL_WAYPOINT_ICON
        assertEquals("mappin", WAYPOINT_CUSTOM_ICON_KEY)
        assertFalse(takenIcons.contains(HonorPersistence.ARRIVAL_WAYPOINT_ICON))
    }
}
