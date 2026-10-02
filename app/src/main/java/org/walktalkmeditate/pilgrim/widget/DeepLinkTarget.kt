// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import android.content.Intent
import org.walktalkmeditate.pilgrim.honor.carriesLinkData

/**
 * Sealed type for widget → MainActivity → NavHost deep-link dispatch.
 * `parse(intent)` keeps Intent extra parsing in one place — testable in
 * isolation, no Activity lifecycle needed.
 *
 * Only an intent with no link data is read: MainActivity is exported, so
 * a link can arrive carrying any extras at all (Phase 21 U27). Link data
 * is a VIEW intent's: the walk notification sets no data, and the
 * widget's clicks carry Glance's own `glance-action:` data with no action.
 */
sealed interface DeepLinkTarget {
    data class WalkSummary(val walkId: Long) : DeepLinkTarget
    data object Home : DeepLinkTarget
    data object ActiveWalk : DeepLinkTarget

    companion object {
        const val EXTRA_DEEP_LINK = "org.walktalkmeditate.pilgrim.widget.EXTRA_DEEP_LINK"
        const val EXTRA_WALK_ID = "org.walktalkmeditate.pilgrim.widget.EXTRA_WALK_ID"
        const val DEEP_LINK_WALK_SUMMARY = "walk_summary"
        const val DEEP_LINK_HOME = "home"
        const val DEEP_LINK_ACTIVE_WALK = "active_walk"

        fun parse(intent: Intent?): DeepLinkTarget? {
            if (intent == null || carriesLinkData(intent)) return null
            return when (intent.getStringExtra(EXTRA_DEEP_LINK)) {
                DEEP_LINK_WALK_SUMMARY -> {
                    val id = intent.getLongExtra(EXTRA_WALK_ID, -1L)
                    if (id > 0) WalkSummary(id) else null
                }
                DEEP_LINK_HOME -> Home
                DEEP_LINK_ACTIVE_WALK -> ActiveWalk
                else -> null
            }
        }
    }
}
