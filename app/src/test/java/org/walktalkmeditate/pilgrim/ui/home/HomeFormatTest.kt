// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeFormatTest {

    private lateinit var context: Context
    private val zone: ZoneId = ZoneId.of("UTC")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `just now under 1 minute`() {
        val now = 10_000_000L
        val walk = now - 30_000L // 30 seconds ago
        assertEquals("Just now", HomeFormat.relativeDate(context, walk, now, zone))
    }

    @Test
    fun `exactly 1 minute reports minutes ago`() {
        val now = 10_000_000L
        val walk = now - 60_000L
        assertEquals("1 minutes ago", HomeFormat.relativeDate(context, walk, now, zone))
    }

    @Test
    fun `59 minutes ago reports minutes ago`() {
        val now = 10_000_000L
        val walk = now - 59L * 60_000L
        assertEquals("59 minutes ago", HomeFormat.relativeDate(context, walk, now, zone))
    }

    @Test
    fun `60 minutes ago reports hours ago`() {
        val now = 10_000_000L
        val walk = now - 60L * 60_000L
        assertEquals("1 hours ago", HomeFormat.relativeDate(context, walk, now, zone))
    }

    @Test
    fun `23 hours ago reports hours ago`() {
        val now = LocalDateTime.of(2026, 4, 18, 12, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val walk = now - 23L * 60L * 60_000L
        assertEquals("23 hours ago", HomeFormat.relativeDate(context, walk, now, zone))
    }

    @Test
    fun `1 day ago reports day of week`() {
        // now = Saturday April 18 2026 noon; walk = Friday April 17
        val now = LocalDateTime.of(2026, 4, 18, 12, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val walk = LocalDateTime.of(2026, 4, 17, 12, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals("Friday", HomeFormat.relativeDate(context, walk, now, zone))
    }

    @Test
    fun `6 days ago reports day of week`() {
        // now = Saturday April 18 2026; walk = Sunday April 12
        val now = LocalDateTime.of(2026, 4, 18, 12, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val walk = LocalDateTime.of(2026, 4, 12, 12, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals("Sunday", HomeFormat.relativeDate(context, walk, now, zone))
    }

    @Test
    fun `7 days ago reports MMM d`() {
        val now = LocalDateTime.of(2026, 4, 18, 12, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val walk = LocalDateTime.of(2026, 4, 11, 12, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals("Apr 11", HomeFormat.relativeDate(context, walk, now, zone))
    }

    @Test
    fun `months ago reports MMM d`() {
        val now = LocalDateTime.of(2026, 4, 18, 12, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val walk = LocalDateTime.of(2026, 1, 3, 12, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals("Jan 3", HomeFormat.relativeDate(context, walk, now, zone))
    }

    @Test
    fun `recording count null when zero`() {
        assertNull(HomeFormat.recordingCountLabel(context, 0))
    }

    @Test
    fun `recording count singular when one`() {
        assertEquals("1 voice note", HomeFormat.recordingCountLabel(context, 1))
    }

    @Test
    fun `recording count plural when many`() {
        assertEquals("5 voice notes", HomeFormat.recordingCountLabel(context, 5))
    }

    @Test
    fun `future timestamp treated as just now (clamped to zero)`() {
        // Defensive: wall-clock regression shouldn't produce "negative
        // minutes ago". Clamp to "Just now".
        val now = 10_000_000L
        val walk = now + 5_000L
        assertEquals("Just now", HomeFormat.relativeDate(context, walk, now, zone))
    }

    @Suppress("unused")
    private fun midnightUtc(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()
}
