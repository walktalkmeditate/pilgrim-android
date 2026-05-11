// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.walktalkmeditate.pilgrim.ui.walk.summary.REVEAL_ZOOM_PLANT_MS

/**
 * Compile-time + constant-value contract tests for [PilgrimMap]'s
 * reveal camera ease. The actual Mapbox `easeTo` invocation is
 * exercised on-device (no Robolectric shadow for the camera API);
 * here we lock the contract values that the production code reads.
 */
@RunWith(JUnit4::class)
class PilgrimMapRevealTest {

    @Test
    fun revealZoomPlantMs_matchesIosCameraDuration() {
        // iOS cameraDuration = 0.1 → 100ms.
        assertEquals(100L, REVEAL_ZOOM_PLANT_MS)
    }
}
