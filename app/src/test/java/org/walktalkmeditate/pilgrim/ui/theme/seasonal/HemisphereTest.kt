// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import org.junit.Assert.assertEquals
import org.junit.Test

class HemisphereTest {

    @Test fun `sydney is southern`() {
        assertEquals(Hemisphere.Southern, Hemisphere.fromLatitude(-33.8688))
    }

    @Test fun `london is northern`() {
        assertEquals(Hemisphere.Northern, Hemisphere.fromLatitude(51.5074))
    }

    @Test fun `equator is northern by convention`() {
        assertEquals(Hemisphere.Northern, Hemisphere.fromLatitude(0.0))
    }

    @Test fun `just south of equator is southern`() {
        assertEquals(Hemisphere.Southern, Hemisphere.fromLatitude(-0.0001))
    }

    @Test fun `just north of equator is northern`() {
        assertEquals(Hemisphere.Northern, Hemisphere.fromLatitude(0.0001))
    }
}
