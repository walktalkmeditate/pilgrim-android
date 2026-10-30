// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.honor.HonorNoticeEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorNoticeKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness

/** What a notice row says on the walk's Way (iOS `showMarkCaption`, `showStampCaption`, `ActiveWalkViewModel+MarkPins.swift:62-73@e551b11`). */
class HonorCaptionTest {

    private val way: Way = HonorHarness.templeStage()

    private fun notice(kind: HonorNoticeKind, refId: String) =
        HonorNoticeEntity(walkId = 1L, kind = kind, refId = refId, meters = 3200.0, firedAt = 0L)

    @Test
    fun `a temple row reads its number and closing hour from the walk's Way, its metres from the row`() {
        assertEquals(
            HonorCaption.Stamp(templeNumber = 10, closesMinutes = 1020, meters = 3200.0),
            HonorCaption.of(notice(HonorNoticeKind.STAMP, "temple-10"), way),
        )
    }

    /** The UI's repeat of `:tracker`'s guard: either value missing, and the line says nothing. */
    @Test
    fun `a temple row says nothing on a Way with no hours, for a moment the Way doesn't carry, or for Temple 0`() {
        val stamp = notice(HonorNoticeKind.STAMP, "temple-10")
        val temple = way.moments.single()
        assertNull(HonorCaption.of(stamp, way.copy(stampHours = null)))
        assertNull(HonorCaption.of(notice(HonorNoticeKind.STAMP, "temple-11"), way))
        assertNull(HonorCaption.of(stamp, way.copy(moments = listOf(temple.copy(text = "Temple 0 · Koyasan Shingon")))))
        assertNull("no Way loaded yet", HonorCaption.of(stamp, null))
    }

    @Test
    fun `a water row is water, and an unknown kind or no row says nothing`() {
        assertEquals(HonorCaption.Water(3200.0), HonorCaption.of(notice(HonorNoticeKind.WATER, "wp-osm-water-node1"), way))
        assertNull(HonorCaption.of(notice(HonorNoticeKind.UNKNOWN, "wp-shrine-3"), way))
        assertNull(HonorCaption.of(null, way))
    }
}
