// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportConfirmationSheetHelpersTest {

    @Test
    fun `walkCountText pluralizes`() {
        assertEquals("1 walk", walkCountText(1))
        assertEquals("23 walks", walkCountText(23))
        assertEquals("0 walks", walkCountText(0))
    }

    @Test
    fun `photoSizeText formats KB and MB`() {
        // 80 KB
        assertEquals("1 photo · ≈80 KB", photoSizeText(1, 80_000L))
        // 1.4 MB (using en-US locale defaults; tolerate either "1.4" or "1,4")
        val text = photoSizeText(18, 1_440_000L)
        assertTrue("expected '1.4' or '1,4' MB in: $text", text.contains("1.4 MB") || text.contains("1,4 MB"))
        assertTrue(text.startsWith("18 photos · ≈"))
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
