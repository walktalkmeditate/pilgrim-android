// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import androidx.compose.runtime.Immutable

@Immutable
data class WalkSnippet(
    val date: Long,
    val placeName: String?,
    val weatherCondition: String?,
    val celestialSummary: String?,
    val transcriptionPreview: String,
)
