// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager.Status

/**
 * Port of iOS `PilgrimageMapsRowTests.swift@7c200bf`, the model's four
 * (names kept, fixtures verbatim; spec D C4 test inventory #2–#5). The
 * body test is `PilgrimageMapsRowTest`'s and the morning card's is U47's.
 * Then the Android additions: `megabytes`' ties and probed rows (C1 §10)
 * and the saved face's TalkBack label. Robolectric for the strings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimageMapsRowModelTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    // ---- iOS's tests ----------------------------------------------------------

    @Test
    fun `the estimate is rounded and tilded`() {
        assertEquals("Save maps for the way · ~26 MB", PilgrimageMapsRowModel.label(resources, Status.None, estimateBytes = 26_400_000))
        assertEquals("Save maps for the way · ~2 MB", PilgrimageMapsRowModel.label(resources, Status.None, estimateBytes = 1_900_000))
        assertEquals("Save maps for the way · ~1 MB", PilgrimageMapsRowModel.label(resources, Status.None, estimateBytes = 400_000))
    }

    @Test
    fun `a partial save says how far it got`() {
        assertEquals(
            "Save maps for the way · 12 of 33 saved",
            PilgrimageMapsRowModel.label(resources, Status.Partial(saved = 12, of = 33), estimateBytes = 0),
        )
    }

    @Test
    fun `saved shows real bytes with no tilde`() {
        assertEquals("maps saved · 26 MB", PilgrimageMapsRowModel.savedLine(resources, bytes = 26_100_000))
    }

    /** `total` counts the two style packs; the walker counts stages. */
    @Test
    fun `saving counts stages not packs`() {
        assertEquals("maps · stage 12 of 33", PilgrimageMapsRowModel.savingLine(resources, done = 14, total = 35))
        assertEquals("maps · stage 0 of 33", PilgrimageMapsRowModel.savingLine(resources, done = 0, total = 35))
    }

    // ---- Android additions ------------------------------------------------------

    /** Swift's `rounded()` takes a tie away from zero, where Kotlin's `round` would give 2 (C1 §10's probed rows). */
    @Test
    fun `megabytes takes a tie away from zero, never reads under 1, and never groups its digits`() {
        mapOf(
            2_500_000L to "3 MB",
            1_500_000L to "2 MB",
            1_499_999L to "1 MB",
            500_000L to "1 MB",
            0L to "1 MB",
            -3_000_000L to "1 MB",
            999_999_999L to "1000 MB",
        ).forEach { (bytes, expected) -> assertEquals("$bytes", expected, PilgrimageMapsRowModel.megabytes(resources, bytes)) }
    }

    /** Past a 32-bit count, as Swift's 64-bit `Int` holds it: a Kotlin `Int` would wrap (spec D, across units). */
    @Test
    fun `an estimate past 2 GiB still reads its megabytes`() {
        val estimate = 600L * PilgrimageTilesManager.SEED_BYTES_PER_PACK
        assertEquals("Save maps for the way · ~2400 MB", PilgrimageMapsRowModel.label(resources, Status.None, estimate))
    }

    /** The saved face takes `Saved` first; the model gives it the estimate's form, as iOS's `case .none, .saved`. */
    @Test
    fun `saved in the label reads the estimate`() {
        val label = PilgrimageMapsRowModel.label(resources, Status.Saved(bytes = 5), estimateBytes = 26_400_000)
        assertEquals("Save maps for the way · ~26 MB", label)
    }

    /** iOS's `accessibilityLabel` (`PilgrimageMapsRow.swift:79@7c200bf`): a comma, then "Tap to save again" (D7, matched). */
    @Test
    fun `the saved face's TalkBack label is iOS's`() {
        assertEquals("maps saved, 26 MB. Tap to save again", PilgrimageMapsRowModel.savedA11y(resources, bytes = 26_100_000))
    }

    /** The saving line clamps each side at 0, so a save of no stages reads "0 of 0". */
    @Test
    fun `the saving line never counts below nothing`() {
        assertEquals("maps · stage 0 of 0", PilgrimageMapsRowModel.savingLine(resources, done = 1, total = 2))
    }
}
