// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToriiSceneryTest {

    @Test
    fun `seeking gate grows five moss patches with pinned opacities`() {
        val moss = mossPatchesFor(WalkThreshold.Seeking)
        assertEquals(5, moss.size)
        assertEquals(listOf(0.50f, 0.38f, 0.26f, 0.44f, 0.30f), moss.map { it.alpha })
    }

    @Test
    fun `moss creeps heavier on the left pillar`() {
        val moss = mossPatchesFor(WalkThreshold.Seeking)
        assertEquals(3, moss.count { it.xFraction < 0f })
        assertEquals(2, moss.count { it.xFraction > 0f })
    }

    @Test
    fun `moss creeps up the pillars from the ground`() {
        val moss = mossPatchesFor(WalkThreshold.Seeking)
        // Every patch sits on the lower half of the gate, and each
        // pillar's patches thin out as they climb.
        assertTrue(moss.all { it.yFraction > 0f })
        val leftClimb = moss.filter { it.xFraction < 0f }.sortedByDescending { it.yFraction }
        assertEquals(leftClimb.map { it.alpha }.sortedDescending(), leftClimb.map { it.alpha })
    }

    @Test
    fun `practice and lottery gates grow no moss`() {
        assertTrue(mossPatchesFor(WalkThreshold.Practice).isEmpty())
        assertTrue(mossPatchesFor(null).isEmpty())
    }

    @Test
    fun `moss keeps the fixed weathered green`() {
        assertEquals(Color(0xFF738559), MOSS_GREEN)
    }

    @Test
    fun `seeking gate fills heavier than practice`() {
        assertEquals(0.45f, toriiFillAlpha(WalkThreshold.Seeking), 0f)
        assertEquals(0.35f, toriiFillAlpha(WalkThreshold.Practice), 0f)
        assertEquals(0.35f, toriiFillAlpha(null), 0f)
    }

    @Test
    fun `shimenawa hangs under the nuki - the eed14d1 geometry guard`() {
        // toriiGatePath draws the nuki at y 0.30-0.34; the rope must sag
        // from 0.33 between the pillar inner edges in top-left frame
        // fractions — never center-origin offsets.
        assertEquals(0.33f, ShimenawaGeometry.ROPE_Y, 0f)
        assertEquals(0.28f, ShimenawaGeometry.ROPE_LEFT_X, 0f)
        assertEquals(0.72f, ShimenawaGeometry.ROPE_RIGHT_X, 0f)
        assertEquals(0.06f, ShimenawaGeometry.ROPE_SAG, 0f)
        assertEquals(listOf(0.37f, 0.49f, 0.61f), ShimenawaGeometry.SHIDE_X)
    }
}
