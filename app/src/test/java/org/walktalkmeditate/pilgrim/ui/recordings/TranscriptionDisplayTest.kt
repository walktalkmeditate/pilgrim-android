// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TranscriptionDisplayTest {

    @Test
    fun shortText_doesNotNeedExpansion() {
        assertFalse(transcriptionNeedsExpansion("Hello world."))
    }

    @Test
    fun textOverCharLimit_needsExpansion() {
        val long = "a".repeat(281)
        assertTrue(transcriptionNeedsExpansion(long))
    }

    @Test
    fun textAtCharLimitBoundary_doesNotNeedExpansion() {
        val boundary = "a".repeat(280)
        assertFalse(transcriptionNeedsExpansion(boundary))
    }

    @Test
    fun textWithEightNewlines_needsExpansion() {
        val multiline = (1..9).joinToString("\n") { "line" }
        assertTrue(transcriptionNeedsExpansion(multiline))
    }

    @Test
    fun textWithSevenNewlines_doesNotNeedExpansion() {
        val multiline = (1..8).joinToString("\n") { "line" }
        assertFalse(transcriptionNeedsExpansion(multiline))
    }

    @Test
    fun emptyText_doesNotNeedExpansion() {
        assertFalse(transcriptionNeedsExpansion(""))
    }
}
