// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportDateRangeFormatterTest {

    @Test
    fun `same month collapses to single label`() {
        val a = LocalDate.of(2026, 4, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val b = LocalDate.of(2026, 4, 28).atStartOfDay().toInstant(ZoneOffset.UTC)
        assertEquals(
            "April 2026",
            ExportDateRangeFormatter.format(a, b, Locale.US, ZoneOffset.UTC),
        )
    }

    @Test
    fun `cross-month range uses en-dash`() {
        val a = LocalDate.of(2024, 3, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val b = LocalDate.of(2026, 4, 28).atStartOfDay().toInstant(ZoneOffset.UTC)
        assertEquals(
            "March 2024 – April 2026",
            ExportDateRangeFormatter.format(a, b, Locale.US, ZoneOffset.UTC),
        )
    }

    @Test
    fun `cross-year same-month range still expands`() {
        val a = LocalDate.of(2025, 4, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val b = LocalDate.of(2026, 4, 28).atStartOfDay().toInstant(ZoneOffset.UTC)
        assertEquals(
            "April 2025 – April 2026",
            ExportDateRangeFormatter.format(a, b, Locale.US, ZoneOffset.UTC),
        )
    }

    @Test
    fun `respects supplied locale for month name`() {
        val a = LocalDate.of(2026, 4, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val b = LocalDate.of(2026, 4, 28).atStartOfDay().toInstant(ZoneOffset.UTC)
        // German month name for April
        assertEquals(
            "April 2026",
            ExportDateRangeFormatter.format(a, b, Locale.GERMAN, ZoneOffset.UTC),
        )
    }
}
