// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportConfirmationSheetHelpersTest {

    @Test
    fun `photoSizeText formats KB and MB`() {
        assertEquals("1 photo · ≈80 KB", photoSizeText(1, 80_000L))
        val text = photoSizeText(18, 1_440_000L)
        assertEquals("18 photos · ≈1.4 MB", text)
    }

    @Test
    fun `effectiveIncludePhotos returns false when no pinned photos regardless of toggle`() {
        assertFalse(effectiveIncludePhotos(pinnedPhotoCount = 0, userToggle = true))
        assertFalse(effectiveIncludePhotos(pinnedPhotoCount = 0, userToggle = false))
    }

    @Test
    fun `effectiveIncludePhotos respects toggle when photos are pinned`() {
        assertTrue(effectiveIncludePhotos(pinnedPhotoCount = 5, userToggle = true))
        assertFalse(effectiveIncludePhotos(pinnedPhotoCount = 5, userToggle = false))
    }
}
