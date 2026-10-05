// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours

/**
 * The stage surfaces' formatter against what iOS's `StatsHelper` prints
 * (pilgrimage-stage spec P4 §5.3's table, probed on macOS with en_US),
 * then `WayStageFacts`'s line (§5.1). Rows marked "probed here" were run
 * through Foundation's `MeasurementFormatter` on this Mac for U37. Robolectric
 * for the facts line's strings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StageFormatTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    // ---- P4 §5.3: distances ----

    @Test
    fun `distances print as StatsHelper prints them, in kilometres and in miles`() {
        val table = listOf(
            24_000.0 to ("24 km" to "14.91 mi"),
            24_200.0 to ("24.2 km" to "15.04 mi"),
            24_235.0 to ("24.24 km" to "15.06 mi"),
            1_005.0 to ("1 km" to "0.62 mi"),
            764_000.0 to ("764 km" to "474.73 mi"),
            154_500.0 to ("154.5 km" to "96 mi"),
            2_345_678.0 to ("2,345.68 km" to "1,457.54 mi"),
            1_200.0 to ("1.2 km" to "0.75 mi"),
            50.0 to ("0.05 km" to "0.03 mi"),
            0.0 to ("0 km" to "0 mi"),
        )
        table.forEach { (meters, expected) ->
            assertEquals("$meters m", expected.first, StageFormat.distance(meters, UnitSystem.Metric))
            assertEquals("$meters m", expected.second, StageFormat.distance(meters, UnitSystem.Imperial))
        }
    }

    @Test
    fun `climbs print as StatsHelper prints them, in metres and in hundredths of a foot`() {
        val table = listOf(
            1_419.0 to ("1,419 m" to "4,655.51 ft"),
            1_400.0 to ("1,400 m" to "4,593.18 ft"),
            0.0 to ("0 m" to "0 ft"),
            0.015 to ("0.02 m" to "0.05 ft"),
        )
        table.forEach { (meters, expected) ->
            assertEquals("$meters m", expected.first, StageFormat.altitude(meters, UnitSystem.Metric))
            assertEquals("$meters m", expected.second, StageFormat.altitude(meters, UnitSystem.Imperial))
        }
    }

    /** Probed here: Foundation rounds the shortest decimal half-even, past every place iOS's table shows. */
    @Test
    fun `half-even rounding of the shortest decimal, as Foundation rounds it`() {
        assertEquals("2.68 m", StageFormat.altitude(2.675, UnitSystem.Metric))
        assertEquals("0.12 m", StageFormat.altitude(0.125, UnitSystem.Metric))
        assertEquals("0 m", StageFormat.altitude(0.005, UnitSystem.Metric))
        assertEquals("0.02 ft", StageFormat.altitude(0.005, UnitSystem.Imperial))
        assertEquals("1,000 km", StageFormat.distance(999_999.995, UnitSystem.Metric))
        assertEquals("621.37 mi", StageFormat.distance(999_999.995, UnitSystem.Imperial))
        assertEquals("12,345,678.9 m", StageFormat.altitude(12_345_678.9, UnitSystem.Metric))
        assertEquals("40,504,195.87 ft", StageFormat.altitude(12_345_678.9, UnitSystem.Imperial))
        assertEquals("10,000 km", StageFormat.distance(10_000_000.0, UnitSystem.Metric))
        assertEquals("32,808,398.95 ft", StageFormat.altitude(10_000_000.0, UnitSystem.Imperial))
    }

    /** Probed here: Foundation spells a non-finite figure rather than failing. */
    @Test
    fun `a figure that isn't finite is spelt as Foundation spells it`() {
        assertEquals("NaN km", StageFormat.distance(Double.NaN, UnitSystem.Metric))
        assertEquals("∞ km", StageFormat.distance(Double.POSITIVE_INFINITY, UnitSystem.Metric))
        assertEquals("-∞ ft", StageFormat.altitude(Double.NEGATIVE_INFINITY, UnitSystem.Imperial))
    }

    /** P4 A-5: iOS's German phone reads "24,23 km" and "1.400 m"; Android keeps the house's Locale.US digits. */
    @Test
    fun `digits and grouping are Locale US's whatever the phone's locale`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("24.23 km", StageFormat.distance(24_230.0, UnitSystem.Metric))
            assertEquals("1,400 m", StageFormat.altitude(1_400.0, UnitSystem.Metric))
        } finally {
            Locale.setDefault(before)
        }
    }

    // ---- P4 §5.1: the facts line ----

    @Test
    fun `the facts line reads distance, climb, hours and difficulty`() {
        assertEquals(
            "24.2 km · 1,419 m up · 7 to 9 hours · hard",
            facts(24.2, 1_419.0, WayStageHours(7.0, 9.0), "hard"),
        )
        assertEquals(
            "15.04 mi · 4,655.51 ft up · 7 to 9 hours · hard",
            facts(24.2, 1_419.0, WayStageHours(7.0, 9.0), "hard", UnitSystem.Imperial),
        )
    }

    /** Shikoku's stages all carry `""`: no part, and no trailing separator. */
    @Test
    fun `an empty difficulty adds nothing`() {
        assertEquals("28.3 km · 290 m up · 6 to 9 hours", facts(28.3, 290.0, WayStageHours(6.0, 9.0), ""))
    }

    /** pilgrim-ios #122 item 7, matched: iOS's interpolation has no singular. */
    @Test
    fun `equal hours read once, and never in the singular`() {
        assertEquals("10 km · 40 m up · 1 hours · easy", facts(10.0, 40.0, WayStageHours(1.0, 1.0), "easy"))
        assertEquals("10 km · 40 m up · 0 hours · easy", facts(10.0, 40.0, WayStageHours(0.2, 0.4), "easy"))
        assertEquals(
            "rounding first: 6.6 and 7.4 both read 7",
            "10 km · 40 m up · 7 hours · easy",
            facts(10.0, 40.0, WayStageHours(6.6, 7.4), "easy"),
        )
    }

    @Test
    fun `hours round half away from zero and clamp to 0 to 100`() {
        assertEquals("10 km · 40 m up · 3 to 100 hours · easy", facts(10.0, 40.0, WayStageHours(2.5, 400.0), "easy"))
        assertEquals("10 km · 40 m up · 0 to 100 hours · easy", facts(10.0, 40.0, WayStageHours(Double.NaN, Double.POSITIVE_INFINITY), "easy"))
        assertEquals("10 km · 40 m up · 0 to 5 hours · easy", facts(10.0, 40.0, WayStageHours(Double.NEGATIVE_INFINITY, 4.5), "easy"))
    }

    private fun facts(
        distanceKm: Double,
        gainMeters: Double,
        hours: WayStageHours,
        difficulty: String,
        units: UnitSystem = UnitSystem.Metric,
    ) = WayStageFacts.line(resources, distanceKm, gainMeters, hours, difficulty, units)
}
