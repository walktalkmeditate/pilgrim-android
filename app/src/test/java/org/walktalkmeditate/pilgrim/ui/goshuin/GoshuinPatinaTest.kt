// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import org.junit.Assert.assertEquals
import org.junit.Test

class GoshuinPatinaTest {

    @Test fun `zero walks — no tint`() {
        assertEquals(0f, patinaAlphaFor(0), 0f)
    }

    @Test fun `ten walks — no tint (below tier 1)`() {
        assertEquals(0f, patinaAlphaFor(10), 0f)
    }

    @Test fun `eleven walks — tier 1 alpha`() {
        assertEquals(PATINA_ALPHA_TIER_1, patinaAlphaFor(11), 0f)
    }

    @Test fun `thirty walks — tier 1 alpha (below tier 2 boundary)`() {
        assertEquals(PATINA_ALPHA_TIER_1, patinaAlphaFor(30), 0f)
    }

    @Test fun `thirty-one walks — tier 2 alpha`() {
        assertEquals(PATINA_ALPHA_TIER_2, patinaAlphaFor(31), 0f)
    }

    @Test fun `seventy walks — tier 2 alpha (below tier 3 boundary)`() {
        assertEquals(PATINA_ALPHA_TIER_2, patinaAlphaFor(70), 0f)
    }

    @Test fun `seventy-one walks — tier 3 alpha`() {
        assertEquals(PATINA_ALPHA_TIER_3, patinaAlphaFor(71), 0f)
    }

    @Test fun `five hundred walks — tier 3 alpha (no higher tier)`() {
        assertEquals(PATINA_ALPHA_TIER_3, patinaAlphaFor(500), 0f)
    }

    @Test fun `negative count clamps to zero tint`() {
        // Defensive — a malformed count must not return a bogus alpha.
        assertEquals(0f, patinaAlphaFor(-5), 0f)
    }
}
