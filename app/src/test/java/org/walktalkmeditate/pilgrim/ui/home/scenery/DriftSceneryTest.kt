// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftSceneryTest {

    @Test
    fun `face follows the walk month through the year`() {
        val expected = mapOf(
            1 to DriftFace.WinterFlurry,
            2 to DriftFace.WinterFlurry,
            3 to DriftFace.SpringPetals,
            4 to DriftFace.SpringPetals,
            5 to DriftFace.SpringPetals,
            6 to DriftFace.SummerFireflies,
            7 to DriftFace.SummerFireflies,
            8 to DriftFace.SummerFireflies,
            9 to DriftFace.AutumnDragonflies,
            10 to DriftFace.AutumnDragonflies,
            11 to DriftFace.AutumnDragonflies,
            12 to DriftFace.WinterFlurry,
        )
        for ((month, face) in expected) {
            assertEquals("month $month", face, driftFace(month))
        }
    }

    @Test
    fun `fireflies glow only when the walk met the dark`() {
        // A 16:59 start carries hour 16 and stays a dim static mote; a
        // 17:00 start carries hour 17 and glows.
        assertFalse(walkMetTheDark(16))
        assertTrue(walkMetTheDark(17))
    }
}
