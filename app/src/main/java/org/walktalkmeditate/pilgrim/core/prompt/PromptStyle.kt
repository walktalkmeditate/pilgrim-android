// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import org.walktalkmeditate.pilgrim.R

enum class PromptStyle(
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val icon: ImageVector,
) {
    Contemplative(
        R.string.prompt_style_contemplative_title,
        R.string.prompt_style_contemplative_desc,
        Icons.Outlined.Spa,
    ),
    Reflective(
        R.string.prompt_style_reflective_title,
        R.string.prompt_style_reflective_desc,
        Icons.Outlined.Visibility,
    ),
    Creative(
        R.string.prompt_style_creative_title,
        R.string.prompt_style_creative_desc,
        Icons.Outlined.Brush,
    ),
    Gratitude(
        R.string.prompt_style_gratitude_title,
        R.string.prompt_style_gratitude_desc,
        Icons.Outlined.Favorite,
    ),
    Philosophical(
        R.string.prompt_style_philosophical_title,
        R.string.prompt_style_philosophical_desc,
        Icons.AutoMirrored.Outlined.MenuBook,
    ),
    Journaling(
        R.string.prompt_style_journaling_title,
        R.string.prompt_style_journaling_desc,
        Icons.Outlined.Edit,
    ),
}
