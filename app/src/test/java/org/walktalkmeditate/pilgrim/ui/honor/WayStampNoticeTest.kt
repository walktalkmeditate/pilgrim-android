// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.icu.text.DateTimePatternGenerator
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.walktalkmeditate.pilgrim.data.units.UnitSystem

/**
 * iOS `WayStampNotice` (`WayMomentHeader.swift:120-146@e551b11`): the
 * ports of `HonorWayRenderingTests.swift:143-173@e551b11`, which pass a
 * `Locale` where Android passes the 12-hour reading and the unit, then the
 * production reading of the walker's own 24-hour switch (annex A.11 item 2)
 * and the ASCII digits annex A.4.3 asks for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WayStampNoticeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resources = context.resources

    /** iOS's own test (`:143-145`): the locale's `"j"` skeleton marks a 12-hour clock with `a`, outside quoted text. */
    private fun isTwelveHourClock(locale: Locale): Boolean =
        DateTimePatternGenerator.getInstance(locale)
            .getBestPattern("j")
            .replace(Regex("'[^']*'"), "")
            .contains('a')

    private fun caption(templeNumber: Int, meters: Double, units: UnitSystem, locale: Locale) =
        WayStampNotice.caption(
            resources,
            templeNumber = templeNumber,
            closesMinutes = 17 * 60,
            meters = meters,
            units = units,
            twelveHour = isTwelveHourClock(locale),
        )

    /** The line a walker on the henro reads, in their own clock and their own unit. */
    @Test
    fun testTheStampNoticeSpeaksTheWalkersClockAndUnit() {
        assertEquals("temple 10 stamps until 5 · 3.2 km", caption(10, 3200.0, UnitSystem.Metric, Locale.US))
        assertEquals("temple 10 stamps until 17 · 3.2 km", caption(10, 3200.0, UnitSystem.Metric, Locale.JAPAN))
        assertEquals("temple 88 stamps until 5 · 11.3 mi", caption(88, 18_244.0, UnitSystem.Imperial, Locale.US))
    }

    /** The hour comes from the data: 17:00 is "5" on a 12-hour clock and "17" on a 24-hour one, and a half hour says so. */
    @Test
    fun testTheClosingHourFollowsTheLocalesOwnClock() {
        for (twentyFour in listOf(Locale.JAPAN, Locale.UK)) {
            val twelveHour = isTwelveHourClock(twentyFour)
            assertEquals("$twentyFour", "17", WayStampNotice.hour(17 * 60, twelveHour))
            assertEquals("$twentyFour", "8", WayStampNotice.hour(8 * 60, twelveHour))
        }
        val twelve = isTwelveHourClock(Locale.US)
        assertEquals("5", WayStampNotice.hour(17 * 60, twelve))
        assertEquals("8", WayStampNotice.hour(8 * 60, twelve))
        assertEquals("noon is not zero", "12", WayStampNotice.hour(12 * 60, twelve))
        assertEquals("5:30", WayStampNotice.hour(17 * 60 + 30, twelve))
    }

    /** Review Focus 4: iOS's `Locale.current` carries the 24-Hour Time switch; Android's walker sets "Use 24-hour format". */
    @Test
    fun `the walker's own 24-hour switch decides the hour`() {
        ShadowSettings.set24HourTimeFormat(true)
        assertFalse(WayStampNotice.isTwelveHourClock(context))
        assertEquals("17", WayStampNotice.hour(17 * 60, WayStampNotice.isTwelveHourClock(context)))

        ShadowSettings.set24HourTimeFormat(false)
        assertTrue(WayStampNotice.isTwelveHourClock(context))
        assertEquals("5", WayStampNotice.hour(17 * 60, WayStampNotice.isTwelveHourClock(context)))
    }

    @Test
    fun `minutes show only when they aren't zero, two digits wide, and midnight reads 12 or 0`() {
        assertEquals("5:05", WayStampNotice.hour(17 * 60 + 5, twelveHour = true))
        assertEquals("8:30", WayStampNotice.hour(8 * 60 + 30, twelveHour = false))
        assertEquals("12", WayStampNotice.hour(0, twelveHour = true))
        assertEquals("0", WayStampNotice.hour(0, twelveHour = false))
    }

    /** Annex A.4.3: every argument is formatted before the resource, so a configuration's digits can't reach it. */
    @Test
    fun `the number, the hour and the distance stay ASCII under an Arabic configuration`() {
        val arabic = context.createConfigurationContext(
            Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag("ar")) },
        ).resources

        assertEquals(
            "temple 10 stamps until 5 · 3.2 km",
            WayStampNotice.caption(
                arabic,
                templeNumber = 10,
                closesMinutes = 17 * 60,
                meters = 3200.0,
                units = UnitSystem.Metric,
                twelveHour = true,
            ),
        )
    }

    /** iOS `max(0, meters.isFinite ? meters : 0)`. */
    @Test
    fun `a distance that isn't a number reads 0 m`() {
        assertEquals(
            "temple 10 stamps until 5 · 0 m",
            WayStampNotice.caption(
                resources,
                templeNumber = 10,
                closesMinutes = 17 * 60,
                meters = Double.NaN,
                units = UnitSystem.Metric,
                twelveHour = true,
            ),
        )
    }
}
