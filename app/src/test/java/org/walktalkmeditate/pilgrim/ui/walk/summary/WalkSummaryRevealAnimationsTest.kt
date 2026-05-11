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

    @Test
    fun revealZoomPlantMs_is100ms() {
        // iOS WalkSummaryView.swift:362 — cameraDuration = 0.1
        assertEquals(100L, REVEAL_ZOOM_PLANT_MS)
    }

    @Test
    fun countUpSteps_is30() {
        // iOS WalkSummaryView.swift:380 — let steps = 30
        // Loop is 0..steps inclusive (31 emissions, 30 transitions).
        assertEquals(30, COUNT_UP_STEPS)
    }

    @Test
    fun countUpIntervalMs_is67ms() {
        // iOS interval is 2.0/30 = 66.67ms (Double, no truncation).
        // Android rounds UP to 67ms so total = 30 * 67 = 2010ms,
        // preserving the perceived iOS rhythm rather than truncating
        // to 66ms (which yields 1980ms, +20ms drift in wrong direction).
        assertEquals(67L, COUNT_UP_INTERVAL_MS)
    }
}
