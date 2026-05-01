// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import org.junit.Assert.assertEquals
import org.junit.Test

class RevealAnimationTest {

    @Test
    fun smoothStepEasing_atZero_returnsZero() {
        assertEquals(0f, SmoothStepEasing.transform(0f), 0.0001f)
    }

    @Test
    fun smoothStepEasing_atOne_returnsOne() {
        assertEquals(1f, SmoothStepEasing.transform(1f), 0.0001f)
    }

    @Test
    fun smoothStepEasing_atHalf_returnsHalf() {
        // smooth-step(0.5) = 0.5 * 0.5 * (3 - 2*0.5) = 0.25 * 2 = 0.5
        assertEquals(0.5f, SmoothStepEasing.transform(0.5f), 0.0001f)
    }

    @Test
    fun smoothStepEasing_atQuarter_returnsLessThanQuarter() {
        // smooth-step at 0.25: 0.25 * 0.25 * (3 - 0.5) = 0.0625 * 2.5 = 0.15625
        // (slower acceleration at the start = ease-in characteristic)
        assertEquals(0.15625f, SmoothStepEasing.transform(0.25f), 0.0001f)
    }

    @Test
    fun smoothStepEasing_atThreeQuarter_returnsMoreThanThreeQuarter() {
        // smooth-step at 0.75: 0.75 * 0.75 * (3 - 1.5) = 0.5625 * 1.5 = 0.84375
        assertEquals(0.84375f, SmoothStepEasing.transform(0.75f), 0.0001f)
    }

    @Test
    fun revealPhase_enumOrder() {
        val values = RevealPhase.values()
        assertEquals(RevealPhase.Hidden, values[0])
        assertEquals(RevealPhase.Zoomed, values[1])
        assertEquals(RevealPhase.Revealed, values[2])
    }
}
