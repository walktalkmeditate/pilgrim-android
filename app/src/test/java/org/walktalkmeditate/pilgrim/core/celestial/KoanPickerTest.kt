// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KoanPickerTest {

    @Test fun `same inputs pick same koan`() {
        val a = KoanPicker.pick(42L, 1_700_000_000_000L)
        val b = KoanPicker.pick(42L, 1_700_000_000_000L)
        assertEquals(a, b)
    }

    @Test fun `different walks can pick different koans`() {
        val koans = (1L..200L).map {
            KoanPicker.pick(it, 1_700_000_000_000L)
        }.toSet()
        assertTrue(
            "expected at least 20 unique koans across 200 synthetic walks, got ${koans.size}",
            koans.size >= 20,
        )
    }

    @Test fun `every koan is reachable across a large input sweep`() {
        val picked = mutableSetOf<Koan>()
        for (walkId in 1L..1000L) {
            for (extra in 0L..5L) {
                picked += KoanPicker.pick(walkId, 1_700_000_000_000L + extra * 1_000L)
            }
        }
        assertEquals(
            "every koan should appear at least once across the sweep",
            Koans.all.toSet(),
            picked,
        )
    }

    @Test fun `corpus is non-empty and contains only allowed characters`() {
        assertTrue(Koans.all.isNotEmpty())
        val allowed = Regex("""[A-Za-z0-9\s.,:;?'"()—–…\u2018\u2019\u201C\u201D-]+""")
        for (koan in Koans.all) {
            assertFalse(
                "koan contains exclamation: ${koan.text}",
                koan.text.contains('!'),
            )
            assertTrue(
                "koan too long (${koan.text.length} chars): ${koan.text}",
                koan.text.length <= 120,
            )
            assertTrue(
                "koan has disallowed chars: ${koan.text}",
                allowed.matches(koan.text),
            )
            // Attributions share the same character-set contract —
            // otherwise a future addition like "Nāgārjuna" could slip
            // past the text-only check.
            koan.attribution?.let { attrib ->
                assertTrue(
                    "attribution has disallowed chars: $attrib",
                    allowed.matches(attrib),
                )
            }
        }
    }

    @Test fun `timestamp differences shift the pick`() {
        val base = KoanPicker.pick(1L, 1_700_000_000_000L)
        // Collect picks across 50 consecutive-second timestamps; at
        // least a few should differ from the base.
        val differCount = (1L..50L).count {
            KoanPicker.pick(1L, 1_700_000_000_000L + it * 1_000L) != base
        }
        assertTrue(
            "expected several distinct picks across timestamp sweep, got $differCount",
            differCount >= 20,
        )
    }
}
