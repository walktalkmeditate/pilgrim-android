// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for [WaveformCache]. The underlying `androidx.collection.LruCache`
 * has no Android dependencies, so we don't need Robolectric.
 */
class WaveformCacheTest {

    @Test fun `get returns null for missing id`() {
        val cache = WaveformCache()
        assertNull(cache.get(42L))
    }

    @Test fun `put then get roundtrips the same FloatArray`() {
        val cache = WaveformCache()
        val samples = floatArrayOf(0.1f, 0.2f, 0.3f)
        cache.put(7L, samples)
        val got = cache.get(7L)
        assertNotNull(got)
        assertArrayEquals(samples, got, 0f)
    }

    @Test fun `invalidate removes the entry`() {
        val cache = WaveformCache()
        cache.put(7L, floatArrayOf(0.1f, 0.2f))
        cache.invalidate(7L)
        assertNull(cache.get(7L))
    }

    @Test fun `LRU eviction discards the oldest entry past capacity 64`() {
        val cache = WaveformCache()
        // Fill capacity (64) plus one — the first inserted entry should be evicted.
        for (i in 0L until 65L) {
            cache.put(i, floatArrayOf(i.toFloat()))
        }
        // Touch entry 1 onwards so they remain MRU; entry 0 should still be evicted
        // because it was inserted first and never re-touched.
        assertNull("entry 0 should have been LRU-evicted", cache.get(0L))
        // Most recent entry survives.
        val latest = cache.get(64L)
        assertNotNull(latest)
        assertArrayEquals(floatArrayOf(64f), latest, 0f)
    }
}
