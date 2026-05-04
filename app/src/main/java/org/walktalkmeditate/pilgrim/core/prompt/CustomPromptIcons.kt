// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Material substitutions for the 20 SF Symbol icon options offered to a user
 * picking the visual identity of a custom prompt style. Pair `first` is the
 * iOS SF Symbol name persisted in [CustomPromptStyle.icon] (verbatim, so a
 * future `.pilgrim` ZIP export/import preserves icons across platforms);
 * pair `second` is the Material [ImageVector] rendered on this platform.
 *
 * Order matches iOS `CustomPromptEditorView.iconOptions` so the row/column
 * layout looks identical across builds.
 *
 * Lives in `core/prompt/` (alongside [PromptStyle]) so [PromptsCoordinator]
 * can resolve custom icons at prompt-generation time WITHOUT depending on
 * the UI module. Putting the table inside `ui/walk/summary/` would force
 * `PromptsCoordinator.resolveCustomIcon` to either stub-resolve every key
 * (the bug fixed by this file's introduction — every custom prompt rendered
 * the pencil icon regardless of the user's selection) or import upward
 * across architectural boundaries. The Compose `ImageVector` type itself
 * lives in `androidx.compose.ui.graphics.vector` which already feeds
 * `PromptStyle.icon` — there is no UI-module coupling.
 */
val CUSTOM_PROMPT_ICON_OPTIONS: List<Pair<String, ImageVector>> = listOf(
    "pencil.line" to Icons.Outlined.Edit,
    "text.quote" to Icons.Outlined.FormatQuote,
    "envelope.fill" to Icons.Outlined.Email,
    "lightbulb.fill" to Icons.Outlined.Lightbulb,
    "flame.fill" to Icons.Filled.LocalFireDepartment,
    "leaf.fill" to Icons.Outlined.Spa,
    "wind" to Icons.Outlined.Air,
    "drop.fill" to Icons.Outlined.WaterDrop,
    "sun.max.fill" to Icons.Outlined.WbSunny,
    "moon.fill" to Icons.Outlined.NightsStay,
    "star.fill" to Icons.Filled.Star,
    "sparkles" to Icons.Rounded.AutoAwesome,
    "figure.walk" to Icons.AutoMirrored.Outlined.DirectionsWalk,
    "mountain.2.fill" to Icons.Outlined.Terrain,
    "water.waves" to Icons.Outlined.Waves,
    "bird.fill" to Icons.Outlined.Pets,
    "hands.clap.fill" to Icons.Outlined.Celebration,
    "brain.head.profile" to Icons.Outlined.Psychology,
    "book.fill" to Icons.AutoMirrored.Outlined.MenuBook,
    "music.note" to Icons.Outlined.MusicNote,
)

/**
 * Resolve a custom-style icon key (iOS SF Symbol name) to a Material
 * [ImageVector]. Falls back to the first option (the pencil icon) when the
 * key is missing — this protects against keys synced from a future iOS
 * release that Android doesn't have a substitution for yet, rather than
 * crashing the picker / detail / list rendering paths.
 */
fun resolveCustomPromptIcon(key: String): ImageVector =
    CUSTOM_PROMPT_ICON_OPTIONS.firstOrNull { it.first == key }?.second
        ?: CUSTOM_PROMPT_ICON_OPTIONS.first().second
