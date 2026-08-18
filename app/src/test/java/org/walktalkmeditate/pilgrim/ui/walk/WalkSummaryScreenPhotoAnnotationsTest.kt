// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotationKind
import org.walktalkmeditate.pilgrim.ui.walk.reliquary.PhotoCandidate
import org.walktalkmeditate.pilgrim.ui.walk.reliquary.ReliquaryState

/**
 * The Walk Summary map was showing pinned-photo circles unconditionally
 * (device QA: two gold-ringed thumbnail circles the user reports iOS
 * does NOT show on its summary map). iOS gates photo pins behind BOTH
 * the reliquary toggle preference AND the live Photos permission
 * (`WalkSummaryView+Map.swift:29-33@2ee1185`); [WalkPhoto] rows persist
 * in Room independent of the live toggle/permission, so reading
 * `pinnedPhotos` unconditionally (as the old `combinedAnnotations`
 * block did) shows photos pinned in an earlier session even after the
 * user has since disabled the toggle or revoked the permission.
 */
class WalkSummaryScreenPhotoAnnotationsTest {

    private fun photo(
        id: Long,
        lat: Double? = 35.6,
        lng: Double? = 139.7,
    ) = WalkPhoto(
        id = id,
        walkId = 1L,
        photoUri = "content://media/external/images/media/$id",
        pinnedAt = 1_700_000_000_000L,
        capturedLat = lat,
        capturedLng = lng,
    )

    @Test
    fun `toggle off hides photo pins even when photos are pinned`() {
        val result = photoMapAnnotations(
            reliquaryState = ReliquaryState.ToggleOff,
            pinnedPhotos = listOf(photo(id = 1L)),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `permission denied hides photo pins even when photos are pinned`() {
        val result = photoMapAnnotations(
            reliquaryState = ReliquaryState.PermissionDenied,
            pinnedPhotos = listOf(photo(id = 1L)),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `loading hides photo pins`() {
        // Production-unreachable (isFetching is hard-wired false), but
        // the gate is conservative: only Populated shows pins, matching
        // iOS's binary guard everywhere else in the state space.
        val result = photoMapAnnotations(
            reliquaryState = ReliquaryState.Loading,
            pinnedPhotos = listOf(photo(id = 1L)),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `populated includes pinned photos with gps coords as Photo annotations`() {
        val result = photoMapAnnotations(
            reliquaryState = ReliquaryState.Populated(emptyList<PhotoCandidate>()),
            pinnedPhotos = listOf(photo(id = 7L, lat = 35.6, lng = 139.7)),
        )
        assertEquals(1, result.size)
        val kind = result[0].kind
        assertTrue(kind is WalkMapAnnotationKind.Photo)
        kind as WalkMapAnnotationKind.Photo
        assertEquals(7L, kind.walkPhotoId)
        assertEquals("content://media/external/images/media/7", kind.photoUri)
        assertEquals(35.6, result[0].latitude, 0.0)
        assertEquals(139.7, result[0].longitude, 0.0)
    }

    @Test
    fun `populated omits photos missing either gps coordinate`() {
        val result = photoMapAnnotations(
            reliquaryState = ReliquaryState.Populated(emptyList<PhotoCandidate>()),
            pinnedPhotos = listOf(
                photo(id = 1L, lat = null, lng = 139.7),
                photo(id = 2L, lat = 35.6, lng = null),
                photo(id = 3L, lat = null, lng = null),
            ),
        )
        assertTrue(result.isEmpty())
    }
}
