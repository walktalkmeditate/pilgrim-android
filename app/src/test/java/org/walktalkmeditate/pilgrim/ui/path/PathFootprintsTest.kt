// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.path

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathFootprintsTest {

    /**
     * The mode-card seek trail is verbatim iOS `dissolvingDots`
     * (`WalkStartView.swift:348-368@c1745e8`): six dots whose x wanders
     * 0.3-0.7 so the trail reads as steps dissolving upward — a straight
     * single-x column renders as "a few circles" (device QA finding).
     */
    @Test
    fun trailMatchesIosTable() {
        val dots = modeCardTrailDots()
        assertEquals(6, dots.size)
        assertEquals(listOf(0.5f, 0.3f, 0.7f, 0.4f, 0.6f, 0.5f), dots.map { it.x })
        assertEquals(listOf(2.5f, 2.0f, 2.0f, 1.5f, 1.5f, 1.0f), dots.map { it.radiusDp })
        assertEquals(listOf(0.08f, 0.07f, 0.06f, 0.04f, 0.03f, 0.02f), dots.map { it.alpha })
    }

    @Test
    fun trailDissolvesUpward() {
        val dots = modeCardTrailDots()
        assertTrue("y strictly descends (rises visually)", dots.zipWithNext().all { (a, b) -> b.y < a.y })
        assertTrue("radius never grows toward the unknown", dots.zipWithNext().all { (a, b) -> b.radiusDp <= a.radiusDp })
        assertTrue("alpha fades toward the unknown", dots.zipWithNext().all { (a, b) -> b.alpha < a.alpha })
        assertTrue("x jitters off a straight column", dots.map { it.x }.distinct().size > 1)
    }
}
