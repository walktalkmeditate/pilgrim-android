// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `LastWalk JSON round-trip preserves all fields exactly`() {
        val original: WidgetState = WidgetState.LastWalk(
            walkId = 42L,
            endTimestampMs = 1_700_000_000_000L,
            distanceMeters = 12_345.678,
            activeDurationMs = 1_800_000L,
        )
        val blob = json.encodeToString(WidgetState.serializer(), original)
        val decoded = json.decodeFromString(WidgetState.serializer(), blob)
        assertEquals(original, decoded)
    }

    @Test
    fun `Empty JSON round-trip stays Empty data object`() {
        val original: WidgetState = WidgetState.Empty
        val blob = json.encodeToString(WidgetState.serializer(), original)
        val decoded = json.decodeFromString(WidgetState.serializer(), blob)
        // data object equality + same singleton instance
        assertEquals(original, decoded)
        assertSame(WidgetState.Empty, decoded)
    }

    @Test
    fun `polymorphic decode resolves the type discriminator`() {
        val lastWalk: WidgetState = WidgetState.LastWalk(1L, 0L, 0.0, 0L)
        val empty: WidgetState = WidgetState.Empty

        val lastWalkBlob = json.encodeToString(WidgetState.serializer(), lastWalk)
        val emptyBlob = json.encodeToString(WidgetState.serializer(), empty)

        // Sealed-type encoding writes a discriminator we can spot.
        assertTrue("expected LastWalk discriminator in $lastWalkBlob", lastWalkBlob.contains("LastWalk"))
        assertTrue("expected Empty discriminator in $emptyBlob", emptyBlob.contains("Empty"))

        assertTrue(json.decodeFromString(WidgetState.serializer(), lastWalkBlob) is WidgetState.LastWalk)
        assertTrue(json.decodeFromString(WidgetState.serializer(), emptyBlob) is WidgetState.Empty)
    }

    @Test
    fun `LastWalk handles zero values without rounding loss`() {
        val zero: WidgetState = WidgetState.LastWalk(
            walkId = 0L,
            endTimestampMs = 0L,
            distanceMeters = 0.0,
            activeDurationMs = 0L,
        )
        val decoded = json.decodeFromString(
            WidgetState.serializer(),
            json.encodeToString(WidgetState.serializer(), zero),
        )
        assertEquals(zero, decoded)
    }
}
