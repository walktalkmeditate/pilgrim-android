// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.export

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupTimeCodeTest {

    @Test
    fun `formats UTC instant with stable zero-padded layout`() {
        val instant = Instant.parse("2026-04-28T15:23:09Z")
        assertEquals(
            "20260428-152309",
            BackupTimeCode.format(instant, ZoneId.of("UTC")),
        )
    }

    @Test
    fun `respects supplied zone`() {
        val instant = Instant.parse("2026-01-01T00:30:00Z")
        // Asia/Tokyo is UTC+9 → 09:30 local on the same day.
        assertEquals(
            "20260101-093000",
            BackupTimeCode.format(instant, ZoneId.of("Asia/Tokyo")),
        )
    }
}
