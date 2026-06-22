// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.header

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.R

/**
 * The journey subtitle ("N walks · M months") deliberately diverges from
 * iOS (which renders "1 walks · 1 months" with no singular form) to use
 * correct English grammar. These lock the `one` vs `other` selection of
 * the plurals `pluralStringResource` resolves under the hood.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JourneySubtitlePluralsTest {

    private val res = ApplicationProvider.getApplicationContext<Application>().resources

    @Test fun `one walk is singular`() {
        assertEquals("1 walk", res.getQuantityString(R.plurals.home_journey_walks_count, 1, 1))
    }

    @Test fun `multiple walks are plural`() {
        assertEquals("2 walks", res.getQuantityString(R.plurals.home_journey_walks_count, 2, 2))
        assertEquals("0 walks", res.getQuantityString(R.plurals.home_journey_walks_count, 0, 0))
    }

    @Test fun `one month is singular`() {
        assertEquals("1 month", res.getQuantityString(R.plurals.home_journey_months, 1, 1))
    }

    @Test fun `multiple months are plural`() {
        assertEquals("5 months", res.getQuantityString(R.plurals.home_journey_months, 5, 5))
    }
}
