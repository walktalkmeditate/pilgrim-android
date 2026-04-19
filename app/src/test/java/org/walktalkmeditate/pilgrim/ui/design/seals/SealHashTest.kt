// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SealHashTest {
    private fun spec(
        uuid: String = "11111111-2222-3333-4444-555555555555",
        startMillis: Long = 1_700_000_000_000L,
        distance: Double = 5_000.0,
        duration: Double = 3_600.0,
    ) = SealSpec(
        uuid = uuid,
        startMillis = startMillis,
        distanceMeters = distance,
        durationSeconds = duration,
        displayDistance = "5.0",
        unitLabel = "km",
        ink = Color.Black,
    )

    @Test fun `fnv1a hash deterministic for identical specs`() {
        assertEquals(fnv1aHash(spec()), fnv1aHash(spec()))
    }

    @Test fun `fnv1a hash differs across uuids`() {
        assertNotEquals(
            fnv1aHash(spec(uuid = "aaaaaaaa-0000-0000-0000-000000000000")),
            fnv1aHash(spec(uuid = "bbbbbbbb-0000-0000-0000-000000000000")),
        )
    }

    @Test fun `fnv1a hash differs across timestamps`() {
        assertNotEquals(
            fnv1aHash(spec(startMillis = 1_000L)),
            fnv1aHash(spec(startMillis = 2_000L)),
        )
    }

    @Test fun `sealHashBytes length is 32`() {
        assertEquals(32, sealHashBytes(spec()).size)
    }

    @Test fun `sealHashBytes deterministic`() {
        assertTrue(sealHashBytes(spec()).contentEquals(sealHashBytes(spec())))
    }

    @Test fun `sealHashBytes differs across specs`() {
        val a = sealHashBytes(spec(uuid = "aaaa0000-0000-0000-0000-000000000000"))
        val b = sealHashBytes(spec(uuid = "bbbb0000-0000-0000-0000-000000000000"))
        assertTrue("byte arrays should differ", !a.contentEquals(b))
    }

    @Test fun `byte unsigned reader stays in 0 to 255`() {
        val bytes = ByteArray(32) { (it * 8).toByte() }
        for (i in 0 until 32) {
            val u = bytes.u(i)
            assertTrue("byte[$i] = $u out of range", u in 0..255)
        }
    }

    @Test fun `byte unsigned reader handles negative signed bytes`() {
        val bytes = ByteArray(32) { (-1).toByte() }   // all 0xFF
        for (i in 0 until 32) {
            assertEquals(255, bytes.u(i))
        }
    }
}
