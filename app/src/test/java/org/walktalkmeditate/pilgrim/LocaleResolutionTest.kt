// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocaleResolutionTest {

    @Test
    fun stubFrenchLocaleResolvesToFrValue() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val frConfig = Configuration(context.resources.configuration)
        frConfig.setLocale(Locale.FRANCE)
        val frContext = context.createConfigurationContext(frConfig)
        // If values-fr/strings.xml weren't wired correctly, this would
        // fall back to "en" (default values/). Asserting "fr" proves the
        // resolver walked the locale-qualified directory.
        assertEquals("fr", frContext.getString(R.string.locale_resolution_marker))
    }

    @Test
    fun defaultLocaleResolvesToDefaultValue() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("en", context.getString(R.string.locale_resolution_marker))
    }
}
