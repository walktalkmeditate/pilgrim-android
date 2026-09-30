// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence
import org.walktalkmeditate.pilgrim.ui.walk.PRESET_CHIPS
import org.walktalkmeditate.pilgrim.ui.walk.WAYPOINT_CUSTOM_ICON_KEY

/**
 * Port of iOS `HonorPersistenceTests.swift@7c200bf`'s vocabulary. Robolectric
 * because the arrival label resolves through a real string resource. The
 * event raw values (5, 6) are iOS's Core Data storage, which Android replaces
 * with enum names; the `.pilgrim` strings are pinned in `PilgrimPackageConverterTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
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

    @Test
    fun `arrival label carries the Way's title`() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        assertEquals(
            "Walked their way: Rúa do Franco → Obradoiro",
            HonorPersistence.arrivalWaypointLabel(resources, wayTitle = "Rúa do Franco → Obradoiro"),
        )
    }
}
