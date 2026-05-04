// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.prompt.CustomPromptStyle
import org.walktalkmeditate.pilgrim.core.prompt.GeneratedPrompt
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Hard cap mirroring iOS `CustomPromptStyleStore.maxStyles = 3`. Used to
 * disable the "Create Your Own" row when the user has hit the limit.
 * Counted against [CustomPromptStyle] entries (the raw store list), NOT
 * against [GeneratedPrompt] entries — the two lists are parallel-indexed
 * but the gating value is style count, not prompt count.
 */
internal const val MAX_CUSTOM_STYLES = 3

/**
 * AI Prompts list sheet. Mirrors iOS `PromptListView.swift`:
 *  - 6 built-in style rows (one per [org.walktalkmeditate.pilgrim.core.prompt.PromptStyle]).
 *  - Divider, then N custom rows (max 3).
 *  - Divider, then "Create Your Own" row — disabled at cap.
 *  - Counter caption ("N of 3 custom styles") when at least one custom exists.
 *
 * **Edit / Delete affordance.** iOS uses left-edge swipe-to-edit and
 * trailing swipe-to-delete. Android Material 3 doesn't ship a clean
 * swipe-to-action API and `SwipeToDismissBox` only models a single
 * gesture per row; rather than reach for accompanist or build a custom
 * gesture stack, this sheet renders inline trailing [Icons.Outlined.Edit]
 * + [Icons.Outlined.Delete] icon buttons on each custom row. The
 * affordance is more discoverable on Android (no hidden gesture) and
 * stays unit-testable without a gesture harness.
 *
 * **Testability.** [ModalBottomSheet]'s animation harness can be flaky
 * under Robolectric. Sheet content lives in [PromptListSheetContent]
 * (an internal composable) so unit tests can drive the row rendering
 * directly without instantiating the sheet wrapper.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptListSheet(
    builtInPrompts: List<GeneratedPrompt>,
    customPrompts: List<GeneratedPrompt>,
    customStyles: List<CustomPromptStyle>,
    onPromptClick: (GeneratedPrompt) -> Unit,
    onCreateCustom: () -> Unit,
    onEditCustom: (CustomPromptStyle) -> Unit,
    onDeleteCustom: (CustomPromptStyle) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = pilgrimColors.parchment,
    ) {
        PromptListSheetContent(
            builtInPrompts = builtInPrompts,
            customPrompts = customPrompts,
            customStyles = customStyles,
            onPromptClick = onPromptClick,
            onCreateCustom = onCreateCustom,
            onEditCustom = onEditCustom,
            onDeleteCustom = onDeleteCustom,
        )
    }
}

@Composable
internal fun PromptListSheetContent(
    builtInPrompts: List<GeneratedPrompt>,
    customPrompts: List<GeneratedPrompt>,
    customStyles: List<CustomPromptStyle>,
    onPromptClick: (GeneratedPrompt) -> Unit,
    onCreateCustom: () -> Unit,
    onEditCustom: (CustomPromptStyle) -> Unit,
    onDeleteCustom: (CustomPromptStyle) -> Unit,
) {
    // Defensive guard: caller (Task 18) supplies parallel lists by
    // splitting PromptsCoordinator's combined list at the built-in/custom
    // boundary. If they ever desync, fail fast in debug rather than
    // silently rendering the wrong style for an Edit/Delete tap.
    require(customPrompts.size == customStyles.size) {
        "customPrompts (${customPrompts.size}) and customStyles (${customStyles.size}) must be parallel"
    }
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = PilgrimSpacing.normal)) {
        Text(
            text = stringResource(R.string.prompts_sheet_title),
            style = pilgrimType.heading,
            color = pilgrimColors.ink,
            modifier = Modifier.padding(
                horizontal = PilgrimSpacing.normal,
                vertical = PilgrimSpacing.small,
            ),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(builtInPrompts, key = { "built-${it.id}" }) { prompt ->
                PromptStyleRow(
                    icon = prompt.icon,
                    title = prompt.title,
                    subtitle = prompt.subtitle,
                    onClick = { onPromptClick(prompt) },
                )
            }
            if (customPrompts.isNotEmpty()) {
                item("divider-after-builtin") {
                    HorizontalDivider(
                        color = pilgrimColors.fog.copy(alpha = 0.3f),
                        modifier = Modifier.padding(
                            horizontal = PilgrimSpacing.normal,
                            vertical = PilgrimSpacing.small,
                        ),
                    )
                }
            }
            itemsIndexed(
                items = customPrompts,
                key = { _, prompt -> "custom-${prompt.id}" },
            ) { index, prompt ->
                val style = customStyles[index]
                PromptStyleRow(
                    icon = prompt.icon,
                    title = prompt.title,
                    subtitle = prompt.subtitle,
                    onClick = { onPromptClick(prompt) },
                    trailingActions = {
                        IconButton(
                            onClick = { onEditCustom(style) },
                            modifier = Modifier.testTag(PROMPT_LIST_EDIT_BUTTON_TAG),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = stringResource(
                                    R.string.custom_prompt_edit_action,
                                ),
                                tint = pilgrimColors.ink.copy(alpha = 0.7f),
                            )
                        }
                        IconButton(
                            onClick = { onDeleteCustom(style) },
                            modifier = Modifier.testTag(PROMPT_LIST_DELETE_BUTTON_TAG),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(
                                    R.string.custom_prompt_delete,
                                ),
                                tint = pilgrimColors.ink.copy(alpha = 0.7f),
                            )
                        }
                    },
                )
            }
            item("divider-before-create") {
                HorizontalDivider(
                    color = pilgrimColors.fog.copy(alpha = 0.3f),
                    modifier = Modifier.padding(
                        horizontal = PilgrimSpacing.normal,
                        vertical = PilgrimSpacing.small,
                    ),
                )
            }
            item("create-your-own") {
                val canAdd = customStyles.size < MAX_CUSTOM_STYLES
                CreateYourOwnRow(enabled = canAdd, onClick = onCreateCustom)
            }
            if (customStyles.isNotEmpty()) {
                item("counter") {
                    Text(
                        text = pluralStringResource(
                            id = R.plurals.custom_prompt_counter,
                            count = customStyles.size,
                            customStyles.size,
                        ),
                        style = pilgrimType.caption,
                        color = pilgrimColors.fog,
                        modifier = Modifier.padding(
                            horizontal = PilgrimSpacing.normal,
                            vertical = PilgrimSpacing.xs,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptStyleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailingActions: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = PilgrimSpacing.normal,
                vertical = PilgrimSpacing.small,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = pilgrimColors.stone,
            modifier = Modifier.size(28.dp).widthIn(min = 40.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        ) {
            Text(
                text = title,
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
            )
            Text(
                text = subtitle,
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailingActions != null) {
            trailingActions()
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.NavigateNext,
                contentDescription = null,
                tint = pilgrimColors.fog,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun CreateYourOwnRow(enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) pilgrimColors.stone else pilgrimColors.fog
    val titleColor = if (enabled) pilgrimColors.ink else pilgrimColors.fog
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                horizontal = PilgrimSpacing.normal,
                vertical = PilgrimSpacing.small,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(28.dp).widthIn(min = 40.dp),
        )
        Text(
            text = stringResource(R.string.custom_prompt_create_title),
            style = pilgrimType.heading,
            color = titleColor,
            modifier = Modifier.weight(1f),
        )
    }
}

internal const val PROMPT_LIST_EDIT_BUTTON_TAG = "PromptListSheet.edit"
internal const val PROMPT_LIST_DELETE_BUTTON_TAG = "PromptListSheet.delete"
