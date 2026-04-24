// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
    fun `forWalk yyyy-MM-dd-HHmm digits are all ASCII`() {
        // Regression guard for the Stage 6-B lesson: DateTimeFormatter
        // without Locale.ROOT can emit non-ASCII digits on Arabic /
        // Persian / Hindi locales (`١٩٧٠` instead of `1970`). Validate
        // EVERY digit position (year, month, day, hour, minute) with
        // a regex, not just the year — a partial guard would let a
        // refactor that drops Locale.ROOT on one sub-format slip by.
        val epoch = 0L
        val fn = EtegamiFilename.forWalk(epoch, ZoneId.of("UTC"))
        // Full filename is `pilgrim-etegami-yyyy-MM-dd-HHmm.png`.
        val pattern = Regex("""^pilgrim-etegami-\d{4}-\d{2}-\d{2}-\d{4}\.png$""")
        assertTrue("filename '$fn' has non-ASCII digits or wrong shape", pattern.matches(fn))
    }
}
