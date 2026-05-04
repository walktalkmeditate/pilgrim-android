// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import androidx.compose.runtime.Immutable

@Immutable
data class RecordingContext(
    val uuid: String,
    val timestamp: Long,
    val startCoordinate: LatLng?,
    val endCoordinate: LatLng?,
    val wordsPerMinute: Double?,
    val text: String,
)
