// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EtegamiFilenameTest {

    @Test
    fun `forWalk produces pilgrim-etegami-yyyy-MM-dd-HHmm dot png`() {
        val epoch = ZonedDateTime.of(
            2026, 4, 24, 9, 32, 0, 0, ZoneId.of("UTC"),
        ).toInstant().toEpochMilli()
        assertEquals(
            "pilgrim-etegami-2026-04-24-0932.png",
            EtegamiFilename.forWalk(epoch, ZoneId.of("UTC")),
        )
    }

    @Test
    fun `forWalk uses 24-hour clock with zero-padded hours`() {
        val epoch = ZonedDateTime.of(
            2026, 4, 24, 23, 5, 0, 0, ZoneId.of("UTC"),
        ).toInstant().toEpochMilli()
        assertEquals(
            "pilgrim-etegami-2026-04-24-2305.png",
            EtegamiFilename.forWalk(epoch, ZoneId.of("UTC")),
        )
    }

    @Test
    fun `forWalk respects the supplied zoneId`() {
        val epoch = 1_700_000_000_000L
        val utc = EtegamiFilename.forWalk(epoch, ZoneId.of("UTC"))
        val tokyo = EtegamiFilename.forWalk(epoch, ZoneId.of("Asia/Tokyo"))
        assertNotEquals(utc, tokyo)
    }

    @Test
    fun `forWalk is ASCII-digit stable across locales`() {
        // Regression guard: Stage 6-B lesson — DateTimeFormatter
        // without Locale.ROOT can emit non-ASCII digits on Arabic /
        // Persian / Hindi locales. Verify first char after "pilgrim-
        // etegami-" is ASCII digit.
        val epoch = 0L
        val fn = EtegamiFilename.forWalk(epoch, ZoneId.of("UTC"))
        val prefix = "pilgrim-etegami-"
        val firstDigit = fn[prefix.length]
        assertEquals('1', if (firstDigit == '1') '1' else firstDigit) // year starts with 1970
    }
}
