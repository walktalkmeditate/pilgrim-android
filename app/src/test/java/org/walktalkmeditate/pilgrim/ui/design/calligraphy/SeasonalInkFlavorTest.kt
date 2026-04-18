// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class SeasonalInkFlavorTest {
    private fun millisFor(year: Int, month: Int, day: Int, zone: ZoneId = ZoneOffset.UTC): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun `march is moss`() {
        assertEquals(SeasonalInkFlavor.Moss, SeasonalInkFlavor.forMonth(millisFor(2026, 3, 15), ZoneOffset.UTC))
    }

    @Test fun `may is moss`() {
        assertEquals(SeasonalInkFlavor.Moss, SeasonalInkFlavor.forMonth(millisFor(2026, 5, 15), ZoneOffset.UTC))
    }

    @Test fun `june is rust`() {
        assertEquals(SeasonalInkFlavor.Rust, SeasonalInkFlavor.forMonth(millisFor(2026, 6, 1), ZoneOffset.UTC))
    }

    @Test fun `august is rust`() {
        assertEquals(SeasonalInkFlavor.Rust, SeasonalInkFlavor.forMonth(millisFor(2026, 8, 31), ZoneOffset.UTC))
    }

    @Test fun `september is dawn`() {
        assertEquals(SeasonalInkFlavor.Dawn, SeasonalInkFlavor.forMonth(millisFor(2026, 9, 1), ZoneOffset.UTC))
    }

    @Test fun `november is dawn`() {
        assertEquals(SeasonalInkFlavor.Dawn, SeasonalInkFlavor.forMonth(millisFor(2026, 11, 30), ZoneOffset.UTC))
    }

    @Test fun `december is ink`() {
        assertEquals(SeasonalInkFlavor.Ink, SeasonalInkFlavor.forMonth(millisFor(2026, 12, 20), ZoneOffset.UTC))
    }

    @Test fun `january is ink`() {
        assertEquals(SeasonalInkFlavor.Ink, SeasonalInkFlavor.forMonth(millisFor(2026, 1, 1), ZoneOffset.UTC))
    }

    @Test fun `february is ink`() {
        assertEquals(SeasonalInkFlavor.Ink, SeasonalInkFlavor.forMonth(millisFor(2026, 2, 28), ZoneOffset.UTC))
    }
}
