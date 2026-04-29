// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PilgrimDateCodingTest {

    @Serializable
    data class Wrapper(
        @Serializable(with = EpochSecondsInstantSerializer::class)
        val instant: Instant,
    )

    private val json = Json { encodeDefaults = false }

    @Test
    fun `whole seconds round trip exactly`() {
        val original = Instant.ofEpochSecond(1_700_000_000)
        val encoded = json.encodeToString(Wrapper.serializer(), Wrapper(original))
        val decoded = json.decodeFromString(Wrapper.serializer(), encoded).instant
        assertEquals(original, decoded)
    }

    @Test
    fun `sub-second precision round trips within 100ns`() {
        val original = Instant.ofEpochSecond(1_700_000_000, 123_456_789)
        val encoded = json.encodeToString(Wrapper.serializer(), Wrapper(original))
        val decoded = json.decodeFromString(Wrapper.serializer(), encoded).instant
        assertEquals(original.epochSecond, decoded.epochSecond)
        assertTrue(
            "expected ~123_456_789 nanos, got ${decoded.nano}",
            kotlin.math.abs(original.nano - decoded.nano) <= 100,
        )
    }

    @Test
    fun `epoch zero encodes as 0`() {
        val encoded = json.encodeToString(Wrapper.serializer(), Wrapper(Instant.EPOCH))
        assertEquals("""{"instant":0.0}""", encoded)
    }
}
