// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.UUID
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.prompt.CUSTOM_PROMPT_ICON_OPTIONS
import org.walktalkmeditate.pilgrim.core.prompt.CustomPromptStyle
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

internal const val CUSTOM_PROMPT_EDITOR_TITLE_FIELD_TAG = "CustomPromptEditor.title"
internal const val CUSTOM_PROMPT_EDITOR_INSTRUCTION_FIELD_TAG = "CustomPromptEditor.instruction"
internal const val CUSTOM_PROMPT_EDITOR_SAVE_BUTTON_TAG = "CustomPromptEditor.save"
internal const val CUSTOM_PROMPT_EDITOR_CANCEL_BUTTON_TAG = "CustomPromptEditor.cancel"
internal const val CUSTOM_PROMPT_EDITOR_COUNTER_TAG = "CustomPromptEditor.counter"
internal fun customPromptEditorIconCellTag(key: String) = "CustomPromptEditor.iconCell.$key"

/**
 * Full-screen dialog for creating or editing a [CustomPromptStyle]. Mirrors
 * iOS `CustomPromptEditorView.swift`:
 *  - Top toolbar with Cancel (left) and Save (right, disabled until both
 *    title and instruction are non-blank after trim).
 *  - Title text field, 5-column 20-icon grid, multiline instruction text
 *    field, "N of 3 custom styles" counter caption.
 *
 * Counter formula matches iOS line 113 (`store.styles.count + (editingStyle
 * == nil ? 1 : 0)`): in create mode the counter shows what the count WILL
 * be after save; in edit mode it shows the current count unchanged.
 *
 * Content extracted to [CustomPromptEditorContent] so unit tests can drive
 * the inner column directly without instantiating the [Dialog] /
 * [Surface] wrapper (Robolectric's Dialog harness is fragile).
 */
@Composable
fun CustomPromptEditorDialog(
    editing: CustomPromptStyle?,
    existingStyleCount: Int,
    onSave: (CustomPromptStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = pilgrimColors.parchment,
        ) {
            CustomPromptEditorContent(
                editing = editing,
                existingStyleCount = existingStyleCount,
                onSave = onSave,
                onDismiss = onDismiss,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomPromptEditorContent(
    editing: CustomPromptStyle?,
    existingStyleCount: Int,
    onSave: (CustomPromptStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(editing?.id) {
        mutableStateOf(editing?.title ?: "")
    }
    var selectedIcon by rememberSaveable(editing?.id) {
        mutableStateOf(editing?.icon ?: CUSTOM_PROMPT_ICON_OPTIONS.first().first)
    }
    var instruction by rememberSaveable(editing?.id) {
        mutableStateOf(editing?.instruction ?: "")
    }

    val canSave = title.trim().isNotEmpty() && instruction.trim().isNotEmpty()

    fun handleSave() {
        val style = CustomPromptStyle(
            id = editing?.id ?: UUID.randomUUID().toString(),
            title = title.trim(),
            icon = selectedIcon,
            instruction = instruction.trim(),
        )
        onSave(style)
    }

    // imePadding so the multiline instruction TextField stays scrollable
    // above the soft keyboard. Without it the bottom of the inner
    // verticalScroll Column lies BEHIND the IME and the user can't reach
    // the field's tail end via scroll. Save/Cancel live in the TopAppBar
    // (top of screen) so they are not obscured either way.
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                Text(
                    text = if (editing == null) {
                        stringResource(R.string.custom_prompt_create_title)
                    } else {
                        stringResource(R.string.custom_prompt_edit_title)
                    },
                    style = pilgrimType.heading,
                    color = pilgrimColors.ink,
                )
            },
            navigationIcon = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(CUSTOM_PROMPT_EDITOR_CANCEL_BUTTON_TAG),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = pilgrimColors.stone,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.custom_prompt_cancel),
                        style = pilgrimType.button,
                    )
                }
            },
            actions = {
                TextButton(
                    onClick = ::handleSave,
                    enabled = canSave,
                    modifier = Modifier.testTag(CUSTOM_PROMPT_EDITOR_SAVE_BUTTON_TAG),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = pilgrimColors.stone,
                        disabledContentColor = pilgrimColors.stone.copy(alpha = 0.38f),
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.custom_prompt_save),
                        style = pilgrimType.button,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = pilgrimColors.parchment,
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(PilgrimSpacing.normal),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.big),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
                Text(
                    text = stringResource(R.string.custom_prompt_title_label),
                    style = pilgrimType.heading,
                    color = pilgrimColors.ink,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.custom_prompt_title_placeholder),
                            color = pilgrimColors.fog,
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(CUSTOM_PROMPT_EDITOR_TITLE_FIELD_TAG),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
                Text(
                    text = stringResource(R.string.custom_prompt_icon_label),
                    style = pilgrimType.heading,
                    color = pilgrimColors.ink,
                )
                IconGrid(
                    selectedKey = selectedIcon,
                    onSelect = { selectedIcon = it },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
                Text(
                    text = stringResource(R.string.custom_prompt_instruction_label),
                    style = pilgrimType.heading,
                    color = pilgrimColors.ink,
                )
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.custom_prompt_instruction_placeholder),
                            color = pilgrimColors.fog,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .testTag(CUSTOM_PROMPT_EDITOR_INSTRUCTION_FIELD_TAG),
                )
                val counterValue =
                    existingStyleCount + (if (editing == null) 1 else 0)
                Text(
                    text = pluralStringResource(
                        id = R.plurals.custom_prompt_counter,
                        count = counterValue,
                        counterValue,
                    ),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(CUSTOM_PROMPT_EDITOR_COUNTER_TAG),
                )
            }
        }
    }
}

/**
 * 5-column manually-laid-out icon grid. Avoids `LazyVerticalGrid` because
 * the editor is hosted inside a `verticalScroll` `Column` — nesting an
 * unbounded vertically-scrolling lazy grid inside a vertically-scrolling
 * Column throws an `IllegalStateException` ("Vertically scrollable
 * component was measured with an infinity maximum height constraints").
 * The grid renders 4 rows of 5 cells statically via `chunked(5)`.
 */
@Composable
private fun IconGrid(
    selectedKey: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CUSTOM_PROMPT_ICON_OPTIONS.chunked(5).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems.forEach { (key, icon) ->
                    Box(modifier = Modifier.weight(1f)) {
                        IconCell(
                            iconKey = key,
                            icon = icon,
                            selected = selectedKey == key,
                            onClick = { onSelect(key) },
                        )
                    }
                }
                repeat(5 - rowItems.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun IconCell(
    iconKey: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(PilgrimCornerRadius.small))
            .background(
                if (selected) pilgrimColors.stone else pilgrimColors.parchmentSecondary,
            )
            .clickable(onClick = onClick)
            .testTag(customPromptEditorIconCellTag(iconKey))
            .padding(PilgrimSpacing.small),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) pilgrimColors.parchment else pilgrimColors.ink,
            modifier = Modifier.size(24.dp),
        )
    }
}
