// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for [incrementalMap] (PR #45 AF9 / AF46). The contract: the
 * output is ALWAYS identical to mapping the new source from scratch, and on a
 * pure append only the new tail is run through `transform`.
 */
class IncrementalRouteTest {

    private data class Sample(val id: Int, val value: String)

    private fun fullMap(source: List<Sample>) = source.map { it.value.uppercase() }

    @Test
    fun `first emission maps the whole list`() {
        val transformed = mutableListOf<Int>()
        val source = listOf(Sample(1, "a"), Sample(2, "b"))

        val result = incrementalMap(
            prevSource = emptyList(),
            prevMapped = emptyList(),
            newSource = source,
            sameElement = { p, c -> p.id == c.id },
            transform = { transformed += it.id; it.value.uppercase() },
        )

        assertEquals(fullMap(source), result)
        assertEquals("maps every element on the first pass", listOf(1, 2), transformed)
    }

    @Test
    fun `append transforms only the new tail and matches a full remap`() {
        val prevSource = listOf(Sample(1, "a"), Sample(2, "b"))
        val prevMapped = fullMap(prevSource)
        val newSource = prevSource + listOf(Sample(3, "c"), Sample(4, "d"))
        val transformed = mutableListOf<Int>()

        val result = incrementalMap(
            prevSource = prevSource,
            prevMapped = prevMapped,
            newSource = newSource,
            sameElement = { p, c -> p.id == c.id },
            transform = { transformed += it.id; it.value.uppercase() },
        )

        assertEquals(fullMap(newSource), result)
        assertEquals("only the appended tail is transformed", listOf(3, 4), transformed)
    }

    @Test
    fun `no growth returns the previous mapping untouched`() {
        val source = listOf(Sample(1, "a"), Sample(2, "b"))
        val prevMapped = fullMap(source)
        var calls = 0

        val result = incrementalMap(
            prevSource = source,
            prevMapped = prevMapped,
            newSource = source,
            sameElement = { p, c -> p.id == c.id },
            transform = { calls++; it.value.uppercase() },
        )

        assertSame("identical list reuses the prior mapping", prevMapped, result)
        assertEquals("nothing re-transformed when the list didn't grow", 0, calls)
    }

    @Test
    fun `a changed prefix head falls back to a full remap`() {
        // A new walk that happens to share the boundary sample (id 2) but has a
        // different head (id 9 vs 1) must NOT splice the new tail onto the stale
        // prefix — the head check catches it and forces a full remap.
        val prevSource = listOf(Sample(1, "a"), Sample(2, "b"))
        val prevMapped = fullMap(prevSource)
        val newSource = listOf(Sample(9, "x"), Sample(2, "b"), Sample(3, "c"))
        val transformed = mutableListOf<Int>()

        val result = incrementalMap(
            prevSource = prevSource,
            prevMapped = prevMapped,
            newSource = newSource,
            sameElement = { p, c -> p.id == c.id },
            transform = { transformed += it.id; it.value.uppercase() },
        )

        assertEquals(fullMap(newSource), result)
        assertEquals("the whole new list is remapped", listOf(9, 2, 3), transformed)
    }

    @Test
    fun `an out-of-order insert that shifts the boundary falls back to a full remap`() {
        // The real production scenario: an out-of-order GPS fix (earlier
        // timestamp, higher id) sorts INTO the prefix under (timestamp, id),
        // shifting the old boundary element off index prevSize-1. Head still
        // matches, but the boundary check catches it → correct full remap.
        val prevSource = listOf(Sample(1, "a"), Sample(2, "b"))
        val prevMapped = fullMap(prevSource)
        val newSource = listOf(Sample(1, "a"), Sample(3, "x"), Sample(2, "b"))
        val transformed = mutableListOf<Int>()

        val result = incrementalMap(
            prevSource = prevSource,
            prevMapped = prevMapped,
            newSource = newSource,
            sameElement = { p, c -> p.id == c.id },
            transform = { transformed += it.id; it.value.uppercase() },
        )

        assertEquals(fullMap(newSource), result)
        assertEquals("the whole new list is remapped", listOf(1, 3, 2), transformed)
    }

    @Test
    fun `single-element prefix appends correctly (head equals boundary)`() {
        val prevSource = listOf(Sample(1, "a"))
        val prevMapped = fullMap(prevSource)
        val newSource = listOf(Sample(1, "a"), Sample(2, "b"))
        val transformed = mutableListOf<Int>()

        val result = incrementalMap(
            prevSource = prevSource,
            prevMapped = prevMapped,
            newSource = newSource,
            sameElement = { p, c -> p.id == c.id },
            transform = { transformed += it.id; it.value.uppercase() },
        )

        assertEquals(fullMap(newSource), result)
        assertEquals("only the new element is transformed", listOf(2), transformed)
    }

    @Test
    fun `a shrunk list falls back to a full remap`() {
        val prevSource = listOf(Sample(1, "a"), Sample(2, "b"), Sample(3, "c"))
        val prevMapped = fullMap(prevSource)
        val newSource = listOf(Sample(1, "a"))

        val result = incrementalMap(
            prevSource = prevSource,
            prevMapped = prevMapped,
            newSource = newSource,
            sameElement = { p, c -> p.id == c.id },
            transform = { it.value.uppercase() },
        )

        assertEquals(fullMap(newSource), result)
    }

    @Test
    fun `value-equality boundary works for the map-projection case`() {
        // AF46 keys on LocationPoint equality rather than an id.
        val prev = listOf("a", "b")
        val prevMapped = prev.map { it.uppercase() }
        val next = listOf("a", "b", "c")
        val transformed = mutableListOf<String>()

        val result = incrementalMap(
            prevSource = prev,
            prevMapped = prevMapped,
            newSource = next,
            sameElement = { p, c -> p == c },
            transform = { transformed += it; it.uppercase() },
        )

        assertEquals(listOf("A", "B", "C"), result)
        assertEquals(listOf("c"), transformed)
    }
}
