// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import android.content.Intent

/**
 * Sealed type for widget → MainActivity → NavHost deep-link dispatch.
 * `parse(intent)` keeps Intent extra parsing in one place — testable in
 * isolation, no Activity lifecycle needed.
 */
sealed interface DeepLinkTarget {
    data class WalkSummary(val walkId: Long) : DeepLinkTarget
    data object Home : DeepLinkTarget

    companion object {
        const val EXTRA_DEEP_LINK = "org.walktalkmeditate.pilgrim.widget.EXTRA_DEEP_LINK"
        const val EXTRA_WALK_ID = "org.walktalkmeditate.pilgrim.widget.EXTRA_WALK_ID"
        const val DEEP_LINK_WALK_SUMMARY = "walk_summary"
        const val DEEP_LINK_HOME = "home"

        fun parse(intent: Intent?): DeepLinkTarget? {
            if (intent == null) return null
            return when (intent.getStringExtra(EXTRA_DEEP_LINK)) {
                DEEP_LINK_WALK_SUMMARY -> {
                    val id = intent.getLongExtra(EXTRA_WALK_ID, -1L)
                    if (id > 0) WalkSummary(id) else null
                }
                DEEP_LINK_HOME -> Home
                else -> null
            }
        }
    }
}
