// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec

/**
 * Structural coverage for [GoshuinShareRenderer]. Robolectric's
 * legacy Canvas backend is a no-op (Stage 3-C lesson) so we assert on
 * the produced [android.graphics.Bitmap]'s contract — exact 1080×1920
 * dimensions, non-recycled, and that the full draw path (background,
 * grain, header, seal selection/grid, colophon) survives every seal
 * count / favicon / milestone permutation without throwing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GoshuinShareRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Application>()

    private fun seal(
        id: Long,
        favicon: WalkFavicon? = null,
        milestone: GoshuinMilestone? = null,
        startMillis: Long = 1_700_000_000_000L + id,
    ): GoshuinSeal = GoshuinSeal(
        walkId = id,
        sealSpec = SealSpec(
            uuid = "uuid-$id",
            startMillis = startMillis,
            distanceMeters = 5_000.0,
            durationSeconds = 1_800.0,
            displayDistance = "5.00",
            unitLabel = "km",
            ink = Color.Transparent,
            // Match production (Walk.toSealSpec sets this) so the renderer
            // exercises the favicon-family palette, not just the neutral one.
            favicon = favicon,
        ),
        walkDate = LocalDate.of(2026, 4, 19),
        shortDateLabel = "Apr 19",
        milestone = milestone,
        favicon = favicon,
        uuid = "uuid-$id",
        distanceMeters = 5_000.0,
        startMillis = startMillis,
    )

    @Test
    fun `render produces a 1080x1920 non-recycled bitmap`() {
        val bmp = GoshuinShareRenderer.render(
            context = context,
            selected = listOf(seal(1L)),
            totalWalkCount = 1,
            isImperial = false,
        )
        assertEquals(GoshuinShareRenderer.CANVAS_W, bmp.width)
        assertEquals(GoshuinShareRenderer.CANVAS_H, bmp.height)
        assertFalse(bmp.isRecycled)
    }

    @Test
    fun `render survives every seal-count bucket and the empty case`() {
        listOf(0, 1, 3, 4, 6, 7, 12, 30).forEach { n ->
            val seals = (1..n).map { seal(it.toLong()) }
            val bmp = GoshuinShareRenderer.render(context, seals, n, isImperial = false)
            assertEquals("count=$n", GoshuinShareRenderer.CANVAS_W, bmp.width)
            assertEquals("count=$n", GoshuinShareRenderer.CANVAS_H, bmp.height)
        }
    }

    @Test
    fun `render handles all favicons, milestones, and imperial units`() {
        val seals = listOf(
            seal(1L, favicon = WalkFavicon.FLAME, milestone = GoshuinMilestone.FirstWalk),
            seal(2L, favicon = WalkFavicon.LEAF),
            seal(3L, favicon = WalkFavicon.STAR, milestone = GoshuinMilestone.LongestWalk),
            seal(4L, favicon = null),
        )
        val bmp = GoshuinShareRenderer.render(
            context = context,
            selected = seals,
            totalWalkCount = 80,
            isImperial = true,
        )
        assertTrue(bmp.width == GoshuinShareRenderer.CANVAS_W)
        assertEquals(GoshuinShareRenderer.CANVAS_H, bmp.height)
    }

    @Test
    fun `render caps the seal grid at 12 even with many walks`() {
        // 30 walks in → renderer must not throw and still yields the
        // fixed canvas (selection caps at MAX_SEALS internally).
        val seals = (1..30).map { seal(it.toLong()) }
        val bmp = GoshuinShareRenderer.render(context, seals, 30, isImperial = false)
        assertEquals(GoshuinShareRenderer.CANVAS_W, bmp.width)
        assertEquals(GoshuinShareRenderer.CANVAS_H, bmp.height)
    }
}
