// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CompressionRatio] stands in for whisper.cpp's un-exposed per-segment
 * compression-ratio signal (Open question 2 in the parity spec): the JNI
 * layer surfaces raw segment text, and this ratio is computed Android-side
 * from that text using the same UTF-8-bytes-over-deflate-bytes heuristic
 * Whisper's own reference decoder uses to flag repetitive hallucination.
 */
class CompressionRatioTest {

    @Test
    fun `highly repetitive text exceeds the flag threshold`() {
        val repetitive = "the ".repeat(200)
        assertTrue(CompressionRatio.of(repetitive) > FLAG_THRESHOLD)
    }

    @Test
    fun `ordinary varied prose stays under the flag threshold`() {
        val prose = "I walked along the river this morning and noticed how quiet " +
            "the trail was, the light moving through the leaves."
        assertTrue(CompressionRatio.of(prose) <= FLAG_THRESHOLD)
    }

    @Test
    fun `empty text does not exceed the flag threshold`() {
        assertEquals(0.0, CompressionRatio.of(""), 0.0)
    }

    @Test
    fun `blank text does not exceed the flag threshold`() {
        assertTrue(CompressionRatio.of("   ") <= FLAG_THRESHOLD)
    }

    @Test
    fun `ratio strictly increases as repetition increases`() {
        val lowRepeat = CompressionRatio.of("the ".repeat(5))
        val midRepeat = CompressionRatio.of("the ".repeat(50))
        val highRepeat = CompressionRatio.of("the ".repeat(200))
        assertTrue(midRepeat > lowRepeat)
        assertTrue(highRepeat > midRepeat)
    }

    @Test
    fun `single word is never flagged`() {
        assertTrue(CompressionRatio.of("hello") <= FLAG_THRESHOLD)
    }

    private companion object {
        // Mirrors the TranscriptionRunner flag threshold (U2/BEH-56)
        // without importing it, so this test documents the contract
        // this utility exists to serve independently of that call site.
        const val FLAG_THRESHOLD = 2.4
    }
}
