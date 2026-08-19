// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

/**
 * Pure-function cover for the Talk chip's total
 * (`ActiveWalkViewModel.swift:455-458@2ee1185`): completed rows plus the
 * elapsed time of an in-flight recording, plus every stop-seam bridge that
 * keeps the total monotonic while a finished row is still in Room's
 * invalidation pipeline.
 */
class TalkTotalMillisTest {

    private fun row(start: Long, duration: Long, walkId: Long = 1L) = VoiceRecording(
        walkId = walkId,
        startTimestamp = start,
        endTimestamp = start + duration,
        durationMillis = duration,
        fileRelativePath = "recordings/x/$start.wav",
    )

    @Test
    fun `idle recorder with no rows totals zero`() {
        assertEquals(
            0L,
            talkTotalMillis(
                completed = emptyList(),
                recorder = VoiceRecorderUiState.Idle,
                bridges = emptyList(),
                walkId = 1L,
                nowMillis = 50_000L,
            ),
        )
    }

    @Test
    fun `idle recorder sums completed rows only`() {
        assertEquals(
            12_500L,
            talkTotalMillis(
                completed = listOf(row(1_000L, 5_000L), row(20_000L, 7_500L)),
                recorder = VoiceRecorderUiState.Idle,
                bridges = emptyList(),
                walkId = 1L,
                nowMillis = 90_000L,
            ),
        )
    }

    @Test
    fun `in-flight recording adds elapsed since its start`() {
        assertEquals(
            5_000L + 3_000L,
            talkTotalMillis(
                completed = listOf(row(1_000L, 5_000L)),
                recorder = VoiceRecorderUiState.Recording(startedAtMillis = 20_000L),
                bridges = emptyList(),
                walkId = 1L,
                nowMillis = 23_000L,
            ),
        )
    }

    @Test
    fun `a clock that ran backwards mid-recording contributes zero, never negative`() {
        assertEquals(
            5_000L,
            talkTotalMillis(
                completed = listOf(row(1_000L, 5_000L)),
                recorder = VoiceRecorderUiState.Recording(startedAtMillis = 20_000L),
                bridges = emptyList(),
                walkId = 1L,
                nowMillis = 19_000L,
            ),
        )
    }

    @Test
    fun `bridge covers a stopped recording whose row has not landed yet`() {
        val bridge = PendingTalkBridge(
            walkId = 1L,
            startTimestamp = 20_000L,
            endTimestamp = 23_000L,
            durationMillis = 3_000L,
        )
        assertEquals(
            5_000L + 3_000L,
            talkTotalMillis(
                completed = listOf(row(1_000L, 5_000L)),
                recorder = VoiceRecorderUiState.Idle,
                bridges = listOf(bridge),
                walkId = 1L,
                nowMillis = 23_010L,
            ),
        )
    }

    @Test
    fun `bridge stops contributing once its row appears`() {
        val bridge = PendingTalkBridge(
            walkId = 1L,
            startTimestamp = 20_000L,
            endTimestamp = 23_000L,
            durationMillis = 3_000L,
        )
        assertEquals(
            5_000L + 3_000L,
            talkTotalMillis(
                completed = listOf(row(1_000L, 5_000L), row(20_000L, 3_000L)),
                recorder = VoiceRecorderUiState.Idle,
                bridges = listOf(bridge),
                walkId = 1L,
                nowMillis = 40_000L,
            ),
        )
    }

    @Test
    fun `bridge from a previous walk never leaks into the next walk's total`() {
        val bridge = PendingTalkBridge(
            walkId = 1L,
            startTimestamp = 20_000L,
            endTimestamp = 23_000L,
            durationMillis = 3_000L,
        )
        assertEquals(
            0L,
            talkTotalMillis(
                completed = emptyList(),
                recorder = VoiceRecorderUiState.Idle,
                bridges = listOf(bridge),
                walkId = 2L,
                nowMillis = 40_000L,
            ),
        )
    }

    // The bridge is published before the recorder state flips to Idle
    // (two separate MutableStateFlow writes), so a combine can observe
    // (Recording, bridge-for-that-same-recording). Counting both terms
    // there would spike the chip by a whole recording for one frame.
    @Test
    fun `bridge and live term for the same recording are counted once`() {
        val bridge = PendingTalkBridge(
            walkId = 1L,
            startTimestamp = 20_000L,
            endTimestamp = 23_000L,
            durationMillis = 3_000L,
        )
        assertEquals(
            5_000L + 3_000L,
            talkTotalMillis(
                completed = listOf(row(1_000L, 5_000L)),
                recorder = VoiceRecorderUiState.Recording(startedAtMillis = 20_000L),
                bridges = listOf(bridge),
                walkId = 1L,
                nowMillis = 23_000L,
            ),
        )
    }

    // Caught by the stop-seam integration test: `stopRecording` inserts the
    // row and only then writes Idle, so the row can be observed while the
    // state still reads Recording. Counting elapsed-since-start on top of
    // the landed row doubled the finished talk.
    @Test
    fun `a landed row is not counted again by a recorder state that has not flipped yet`() {
        val bridge = PendingTalkBridge(
            walkId = 1L,
            startTimestamp = 20_000L,
            endTimestamp = 23_000L,
            durationMillis = 3_000L,
        )
        assertEquals(
            3_000L,
            talkTotalMillis(
                completed = listOf(row(20_000L, 3_000L)),
                recorder = VoiceRecorderUiState.Recording(startedAtMillis = 20_000L),
                bridges = listOf(bridge),
                walkId = 1L,
                nowMillis = 23_000L,
            ),
        )
    }

    @Test
    fun `a new recording started before the previous row landed counts both`() {
        val bridge = PendingTalkBridge(
            walkId = 1L,
            startTimestamp = 20_000L,
            endTimestamp = 23_000L,
            durationMillis = 3_000L,
        )
        assertEquals(
            5_000L + 3_000L + 2_000L,
            talkTotalMillis(
                completed = listOf(row(1_000L, 5_000L)),
                recorder = VoiceRecorderUiState.Recording(startedAtMillis = 30_000L),
                bridges = listOf(bridge),
                walkId = 1L,
                nowMillis = 32_000L,
            ),
        )
    }

    // Reviewer-caught regression: a single-slot bridge dropped talk 1's
    // still-unlanded contribution the instant talk 2 published its own
    // bridge over it (reproduced: justBeforeTalk2Stops=4000,
    // justAfterTalk2Stops=1000). Two back-to-back recordings, NEITHER row
    // landed yet, must sum both durations — the type itself (a list) is
    // the fix; this pins the arithmetic once it can be expressed at all.
    @Test
    fun `two unlanded bridges from back-to-back recordings both count`() {
        val talk1 = PendingTalkBridge(
            walkId = 1L,
            startTimestamp = 1_000L,
            endTimestamp = 4_000L,
            durationMillis = 3_000L,
        )
        val talk2 = PendingTalkBridge(
            walkId = 1L,
            startTimestamp = 4_000L,
            endTimestamp = 5_000L,
            durationMillis = 1_000L,
        )
        assertEquals(
            3_000L + 1_000L,
            talkTotalMillis(
                completed = emptyList(),
                recorder = VoiceRecorderUiState.Idle,
                bridges = listOf(talk1, talk2),
                walkId = 1L,
                nowMillis = 10_000L,
            ),
        )
    }

    // One row landing must not disturb a sibling bridge still in flight —
    // the defensive "already landed" filter has to apply per-bridge, not
    // all-or-nothing.
    @Test
    fun `one bridge landing does not drop a sibling bridge still in flight`() {
        val talk1 = PendingTalkBridge(
            walkId = 1L,
            startTimestamp = 1_000L,
            endTimestamp = 4_000L,
            durationMillis = 3_000L,
        )
        val talk2 = PendingTalkBridge(
            walkId = 1L,
            startTimestamp = 4_000L,
            endTimestamp = 5_000L,
            durationMillis = 1_000L,
        )
        assertEquals(
            3_000L + 1_000L,
            talkTotalMillis(
                completed = listOf(row(1_000L, 3_000L)),
                recorder = VoiceRecorderUiState.Idle,
                bridges = listOf(talk1, talk2),
                walkId = 1L,
                nowMillis = 10_000L,
            ),
        )
    }

    // Generalizes the single-bridge "counted once" case: a live recording
    // plus TWO unlanded bridges from earlier talks — only the bridge that
    // matches the live recording's start collapses via maxOf; the other
    // is a disjoint span and contributes in full.
    @Test
    fun `a live recording plus two earlier unlanded bridges all count once each`() {
        val talk1 = PendingTalkBridge(
            walkId = 1L,
            startTimestamp = 1_000L,
            endTimestamp = 4_000L,
            durationMillis = 3_000L,
        )
        val talk2 = PendingTalkBridge(
            walkId = 1L,
            startTimestamp = 4_000L,
            endTimestamp = 5_000L,
            durationMillis = 1_000L,
        )
        assertEquals(
            3_000L + 1_000L + 2_000L,
            talkTotalMillis(
                completed = emptyList(),
                recorder = VoiceRecorderUiState.Recording(startedAtMillis = 10_000L),
                bridges = listOf(talk1, talk2),
                walkId = 1L,
                nowMillis = 12_000L,
            ),
        )
    }
}
