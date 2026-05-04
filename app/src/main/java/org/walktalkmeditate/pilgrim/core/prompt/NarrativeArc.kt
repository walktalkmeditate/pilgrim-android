// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import androidx.compose.runtime.Immutable

@Immutable
data class NarrativeArc(
    val attentionArc: String,
    val solitude: String,
    val recurringTheme: List<String>,
    val dominantColors: List<String>,
) {
    companion object {
        /** Sentinel for empty inputs. Matches iOS `NarrativeArc("none", "unknown", [], [])`. */
        val EMPTY: NarrativeArc = NarrativeArc(
            attentionArc = "none",
            solitude = "unknown",
            recurringTheme = emptyList(),
            dominantColors = emptyList(),
        )
    }
}
