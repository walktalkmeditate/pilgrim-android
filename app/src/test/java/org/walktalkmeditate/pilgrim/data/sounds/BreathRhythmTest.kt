// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sounds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreathRhythmTest {

    @Test
    fun `all has exactly 7 entries matching iOS`() {
        assertEquals(7, BreathRhythm.all.size)
    }

    @Test
    fun `Calm is the default at index 0`() {
        val default = BreathRhythm.byId(BreathRhythm.DEFAULT_ID)
        assertEquals(0, BreathRhythm.DEFAULT_ID)
        assertEquals("Calm", default.name)
    }

    @Test
    fun `None has zero inhale and isNone is true`() {
        val none = BreathRhythm.byId(6)
        assertEquals("None", none.name)
        assertEquals(0.0, none.inhaleSeconds, 0.0)
        assertTrue(none.isNone)
    }

    @Test
    fun `non-None entries have isNone false`() {
        for (id in 0..5) {
            val rhythm = BreathRhythm.byId(id)
            assertFalse("id=$id (${rhythm.name}) must not be isNone", rhythm.isNone)
            assertNotEquals(0.0, rhythm.inhaleSeconds, 0.0)
        }
    }

    @Test
    fun `byId returns the expected entry for ids 0 through 6`() {
        assertEquals("Calm",      BreathRhythm.byId(0).name)
        assertEquals("Equal",     BreathRhythm.byId(1).name)
        assertEquals("Relaxing",  BreathRhythm.byId(2).name)
        assertEquals("Box",       BreathRhythm.byId(3).name)
        assertEquals("Coherent",  BreathRhythm.byId(4).name)
        assertEquals("Deep calm", BreathRhythm.byId(5).name)
        assertEquals("None",      BreathRhythm.byId(6).name)
    }

    @Test
    fun `byId returns Calm fallback for out-of-range ids`() {
        assertEquals("Calm", BreathRhythm.byId(-1).name)
        assertEquals("Calm", BreathRhythm.byId(7).name)
        assertEquals("Calm", BreathRhythm.byId(999).name)
        assertEquals("Calm", BreathRhythm.byId(Int.MIN_VALUE).name)
        assertEquals("Calm", BreathRhythm.byId(Int.MAX_VALUE).name)
    }
}
