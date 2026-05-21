// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
@RunWith(JUnit4::class)
class ReliquaryStateMachineTest {

    private val photo = PhotoCandidate(
        uri = "content://media/1",
        takenAtMs = 5_000L,
        capturedLat = null,
        capturedLng = null,
        isPinned = true,
        pinnedPhotoId = 1L,
    )

    @Test
    fun toggleOff_whenSettingDisabled_regardlessOfPermissionOrPhotos() {
        assertEquals(
            ReliquaryState.ToggleOff,
            resolveReliquaryState(
                toggleEnabled = false,
                permissionGranted = true,
                isFetching = false,
                candidates = listOf(photo),
            ),
        )
    }

    @Test
    fun permissionDenied_whenToggleOnAndPermissionMissing() {
        assertEquals(
            ReliquaryState.PermissionDenied,
            resolveReliquaryState(
                toggleEnabled = true,
                permissionGranted = false,
                isFetching = false,
                candidates = emptyList(),
            ),
        )
    }

    @Test
    fun loading_whenToggleOnPermissionGrantedFetchInFlightAndEmpty() {
        assertEquals(
            ReliquaryState.Loading,
            resolveReliquaryState(
                toggleEnabled = true,
                permissionGranted = true,
                isFetching = true,
                candidates = emptyList(),
            ),
        )
    }

    @Test
    fun populated_whenToggleOnPermissionGrantedFetchCompleteAndPhotosNonEmpty() {
        val state = resolveReliquaryState(
            toggleEnabled = true,
            permissionGranted = true,
            isFetching = false,
            candidates = listOf(photo),
        )
        assertTrue(state is ReliquaryState.Populated)
        assertEquals(listOf(photo), (state as ReliquaryState.Populated).candidates)
    }

    @Test
    fun emptyLeaf_whenToggleOnPermissionGrantedFetchCompleteAndPhotosEmpty() {
        val state = resolveReliquaryState(
            toggleEnabled = true,
            permissionGranted = true,
            isFetching = false,
            candidates = emptyList(),
        )
        assertTrue(state is ReliquaryState.Populated)
        assertEquals(emptyList<PhotoCandidate>(), (state as ReliquaryState.Populated).candidates)
    }

    @Test
    fun precedence_toggleOffBeatsPermissionDenied() {
        assertEquals(
            ReliquaryState.ToggleOff,
            resolveReliquaryState(
                toggleEnabled = false,
                permissionGranted = false,
                isFetching = false,
                candidates = listOf(photo),
            ),
        )
    }
}
