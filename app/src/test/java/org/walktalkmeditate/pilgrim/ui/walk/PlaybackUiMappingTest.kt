// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.audio.PlaybackState

/**
 * Direct unit tests for the [PlaybackState] → [PlaybackUiState]
 * mapping. The VM-level `playbackUiState` flow is observed by Compose
 * via WhileSubscribed; this test exercises the pure mapping function
 * without needing a live Flow collector.
 */
class PlaybackUiMappingTest {

    @Test
    fun `Idle maps to IDLE`() {
        assertEquals(PlaybackUiState.IDLE, PlaybackState.Idle.toUi())
    }

    @Test
    fun `Playing maps to playingRecordingId set and isPlaying true`() {
        val ui = PlaybackState.Playing(recordingId = 42L).toUi()
        assertEquals(42L, ui.playingRecordingId)
        assertEquals(true, ui.isPlaying)
        assertEquals(null, ui.errorMessage)
    }

    @Test
    fun `Paused maps to playingRecordingId retained and isPlaying false`() {
        val ui = PlaybackState.Paused(recordingId = 7L).toUi()
        assertEquals(7L, ui.playingRecordingId)
        assertEquals(false, ui.isPlaying)
        assertEquals(null, ui.errorMessage)
    }

    @Test
    fun `Error surfaces the message + recordingId + isPlaying false`() {
        val ui = PlaybackState.Error(recordingId = 13L, message = "out of memory").toUi()
        assertEquals(13L, ui.playingRecordingId)
        assertEquals(false, ui.isPlaying)
        assertEquals("out of memory", ui.errorMessage)
    }
}
