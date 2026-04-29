// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk

/**
 * One walk + its voice recordings, used as the section model for the
 * Recordings list. The screen renders a sticky header per [walk]
 * (date + intention) followed by each [VoiceRecording] row sorted by
 * [VoiceRecording.startTimestamp] ascending — chronological within
 * the walk, with the walks themselves ordered newest first.
 *
 * `@Immutable`: [Walk] is a Room entity from the data module and
 * [VoiceRecording] holds nullable primitives. The Compose compiler
 * cannot prove either stable on its own, so without this annotation
 * the LazyColumn that consumes the section list would skip-check-fail
 * on every emission of the underlying StateFlow. Same lesson as
 * [WalkSummary].
 */
@Immutable
data class RecordingsSection(
    val walk: Walk,
    val recordings: List<VoiceRecording>,
)
