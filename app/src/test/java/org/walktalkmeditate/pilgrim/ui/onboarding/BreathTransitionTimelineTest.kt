// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Port of iOS `BreathTransitionView.runTransition`
 * (`BreathTransitionView.swift:27-63`) timeline.
 *
 * Full sequence (breath = 1.2 s): logo fades/scales in over 1.0 s,
 * inhale begins at 1.2 s, soft haptic at inhale+breath, exhale begins
 * at inhale+breath+0.3 s, completion at exhale+breath. Reduce-motion
 * collapses the whole thing to a 0.5 s hold.
 */
class BreathTransitionTimelineTest {

    @Test
    fun `reduce motion collapses to a half-second hold`() {
        val p = breathTransitionPlan(reduceMotion = true)
        assertEquals(500L, p.completeAtMs)
        assertEquals(0L, p.inhaleStartMs)
        assertEquals(0L, p.hapticAtMs)
        assertEquals(0L, p.exhaleStartMs)
    }

    @Test
    fun `full sequence keyframes at the iOS 1_2s breath cadence`() {
        val p = breathTransitionPlan(reduceMotion = false, breathMs = 1_200L)
        assertEquals(1_200L, p.breathMs)
        assertEquals(1_200L, p.inhaleStartMs)
        assertEquals(2_400L, p.hapticAtMs)   // inhaleStart + breath
        assertEquals(2_700L, p.exhaleStartMs) // inhaleStart + breath + 300
        assertEquals(3_900L, p.completeAtMs)  // exhaleStart + breath
    }

    @Test
    fun `keyframes scale with the breath duration`() {
        val p = breathTransitionPlan(reduceMotion = false, breathMs = 1_000L)
        assertEquals(1_000L, p.breathMs)
        assertEquals(1_200L, p.inhaleStartMs) // inhaleStart is fixed (iOS 1.2s)
        assertEquals(2_200L, p.hapticAtMs)
        assertEquals(2_500L, p.exhaleStartMs)
        assertEquals(3_500L, p.completeAtMs)
    }

    @Test
    fun `plan carries the input breath duration even in reduce motion`() {
        assertEquals(1_200L, breathTransitionPlan(reduceMotion = true, breathMs = 1_200L).breathMs)
    }
}
