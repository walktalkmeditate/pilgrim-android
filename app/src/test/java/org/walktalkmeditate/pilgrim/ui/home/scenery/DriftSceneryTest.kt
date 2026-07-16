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

    // ---- Per-particle tables (U15 spec verbatim) ----

    @Test
    fun `petal table pins five unique particles`() {
        assertEquals(
            listOf(
                Triple(0.0f, 0.09f, 0.055f),
                Triple(2.1f, 0.13f, 0.045f),
                Triple(4.0f, 0.07f, 0.06f),
                Triple(1.2f, 0.11f, 0.04f),
                Triple(5.3f, 0.15f, 0.05f),
            ),
            PETAL_PARTICLES,
        )
        assertEquals(PETAL_PARTICLES.size, PETAL_PARTICLES.toSet().size)
    }

    @Test
    fun `firefly table pins three unique motes`() {
        assertEquals(
            listOf(
                Triple(0.0f, 0.31f, 0.23f),
                Triple(2.4f, 0.19f, 0.37f),
                Triple(4.7f, 0.27f, 0.17f),
            ),
            FIREFLY_MOTES,
        )
        assertEquals(FIREFLY_MOTES.size, FIREFLY_MOTES.toSet().size)
    }

    @Test
    fun `dragonfly table pins two unique hover phases`() {
        assertEquals(listOf(0.0, 2.6), DRAGONFLY_PHASES)
    }

    @Test
    fun `snowflake table pins six unique flakes`() {
        assertEquals(
            listOf(
                Flake(0.0f, 0.10f, -0.30f, 0.030f),
                Flake(1.7f, 0.14f, 0.10f, 0.022f),
                Flake(3.2f, 0.08f, 0.35f, 0.026f),
                Flake(4.5f, 0.12f, -0.12f, 0.020f),
                Flake(2.6f, 0.09f, 0.24f, 0.028f),
                Flake(5.5f, 0.13f, -0.38f, 0.018f),
            ),
            SNOW_FLAKES,
        )
        assertEquals(SNOW_FLAKES.size, SNOW_FLAKES.toSet().size)
    }
}
