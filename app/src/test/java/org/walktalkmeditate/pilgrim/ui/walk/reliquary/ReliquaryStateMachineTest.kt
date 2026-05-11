// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto

@RunWith(JUnit4::class)
class ReliquaryStateMachineTest {

    private val photo = WalkPhoto(
        walkId = 1L,
        photoUri = "content://media/1",
        pinnedAt = 5_000L,
    )

    @Test
    fun toggleOff_whenSettingDisabled_regardlessOfPermissionOrPhotos() {
        assertEquals(
            ReliquaryState.ToggleOff,
            resolveReliquaryState(
                toggleEnabled = false,
                permissionGranted = true,
                isFetching = false,
                photos = listOf(photo),
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
                photos = emptyList(),
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
                photos = emptyList(),
            ),
        )
    }

    @Test
    fun populated_whenToggleOnPermissionGrantedFetchCompleteAndPhotosNonEmpty() {
        val state = resolveReliquaryState(
            toggleEnabled = true,
            permissionGranted = true,
            isFetching = false,
            photos = listOf(photo),
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
            photos = emptyList(),
        )
        assertTrue(state is ReliquaryState.Populated)
        assertEquals(emptyList<WalkPhoto>(), (state as ReliquaryState.Populated).candidates)
    }

    @Test
    fun precedence_toggleOffBeatsPermissionDenied() {
        assertEquals(
            ReliquaryState.ToggleOff,
            resolveReliquaryState(
                toggleEnabled = false,
                permissionGranted = false,
                isFetching = false,
                photos = listOf(photo),
            ),
        )
    }
}
