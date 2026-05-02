// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import org.junit.Assert.assertEquals
import org.junit.Test

class ZodiacSignTest {
    @Test fun fromIndex_wrapsPositive() = assertEquals(ZodiacSign.Aries, ZodiacSign.fromIndex(12))
    @Test fun fromIndex_wrapsNegative() = assertEquals(ZodiacSign.Pisces, ZodiacSign.fromIndex(-1))
    @Test fun aries_isFireCardinal() {
        assertEquals(ZodiacSign.Element.Fire, ZodiacSign.Aries.element)
        assertEquals(ZodiacSign.Modality.Cardinal, ZodiacSign.Aries.modality)
    }
    @Test fun all12_distinct() = assertEquals(12, ZodiacSign.entries.toSet().size)
}
