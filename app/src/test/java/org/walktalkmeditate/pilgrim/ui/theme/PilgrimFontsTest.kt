// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.R

/**
 * Smoke tests for the bundled font resources. Compose can't actually
 * lay out glyphs in a Robolectric JVM (needs a real Canvas backed by
 * Skia/HarfBuzz), but we can verify that every `R.font.*` resource
 * we reference in [PilgrimFonts] exists + the object itself
 * constructs without throwing. Real glyph rendering is validated on
 * device in Stage 3-G.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimFontsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `cormorant garamond family constructs without throwing`() {
        assertNotNull(PilgrimFonts.cormorantGaramond)
    }

    @Test
    fun `lato family constructs without throwing`() {
        assertNotNull(PilgrimFonts.lato)
    }

    @Test
    fun `cormorant variable TTF resource resolves`() {
        assertNotNull(context.resources.getResourceName(R.font.cormorant_garamond_variable))
    }

    @Test
    fun `lato regular TTF resource resolves`() {
        assertNotNull(context.resources.getResourceName(R.font.lato_regular))
    }

    @Test
    fun `lato bold TTF resource resolves`() {
        assertNotNull(context.resources.getResourceName(R.font.lato_bold))
    }

    @Test
    fun `cormorant italic variable TTF resource resolves`() {
        assertNotNull(context.resources.getResourceName(R.font.cormorant_garamond_italic_variable))
    }

    @Test
    fun `lato italic TTF resource resolves`() {
        assertNotNull(context.resources.getResourceName(R.font.lato_italic))
    }
}
