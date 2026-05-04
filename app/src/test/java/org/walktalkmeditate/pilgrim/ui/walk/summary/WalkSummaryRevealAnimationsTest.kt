// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class WalkSummaryRevealAnimationsTest {

    @Test
    fun targetAlpha_default_zeroOnHidden() {
        assertEquals(0f, targetAlpha(RevealPhase.Hidden, fireOnZoomed = false))
    }

    @Test
    fun targetAlpha_default_zeroOnZoomed() {
        assertEquals(0f, targetAlpha(RevealPhase.Zoomed, fireOnZoomed = false))
    }

    @Test
    fun targetAlpha_default_oneOnRevealed() {
        assertEquals(1f, targetAlpha(RevealPhase.Revealed, fireOnZoomed = false))
    }

    @Test
    fun targetAlpha_fireOnZoomed_zeroOnHidden() {
        assertEquals(0f, targetAlpha(RevealPhase.Hidden, fireOnZoomed = true))
    }

    @Test
    fun targetAlpha_fireOnZoomed_oneOnZoomed() {
        assertEquals(1f, targetAlpha(RevealPhase.Zoomed, fireOnZoomed = true))
    }

    @Test
    fun targetAlpha_fireOnZoomed_oneOnRevealed() {
        assertEquals(1f, targetAlpha(RevealPhase.Revealed, fireOnZoomed = true))
    }
}
