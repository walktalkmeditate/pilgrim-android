// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.entity

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.ui.graphics.vector.ImageVector
import org.walktalkmeditate.pilgrim.R

/**
 * Mood-tag a walk on the summary screen. iOS-faithful port of
 * `pilgrim-ios/Pilgrim/Models/Walk/WalkFavicon.swift`. Persisted as
 * `Walk.favicon` String column; cross-platform `.pilgrim` archive
 * already round-trips this value.
 */
enum class WalkFavicon(
    val rawValue: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    FLAME("flame", R.string.summary_favicon_transformative, Icons.Filled.LocalFireDepartment),
    LEAF("leaf", R.string.summary_favicon_peaceful, Icons.Outlined.Spa),
    STAR("star", R.string.summary_favicon_extraordinary, Icons.Filled.Star),
    ;

    companion object {
        fun fromRawValue(raw: String?): WalkFavicon? =
            raw?.let { needle -> entries.firstOrNull { it.rawValue == needle } }
    }
}
