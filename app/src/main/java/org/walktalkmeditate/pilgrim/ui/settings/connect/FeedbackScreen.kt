// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.EmojiNature
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.feedback.FeedbackCategory
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    onBack: () -> Unit,
    viewModel: FeedbackViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.showConfirmation) {
        if (state.showConfirmation) {
            delay(CONFIRMATION_DISMISS_MS)
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.feedback_principal_title),
                        style = pilgrimType.heading,
                        color = pilgrimColors.ink,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.feedback_back_content_description),
                            tint = pilgrimColors.ink,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pilgrimColors.parchment,
                ),
            )
        },
        containerColor = pilgrimColors.parchment,
    ) { padding ->
        if (state.showConfirmation) {
            ConfirmationOverlay(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            FormContent(
                state = state,
                onSelectCategory = viewModel::selectCategory,
                onUpdateMessage = viewModel::updateMessage,
                onToggleDeviceInfo = viewModel::toggleIncludeDeviceInfo,
                onSubmit = viewModel::submit,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
private fun FormContent(
    state: FeedbackUiState,
    onSelectCategory: (FeedbackCategory) -> Unit,
    onUpdateMessage: (String) -> Unit,
    onToggleDeviceInfo: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CategoryCard(
                icon = Icons.Outlined.BugReport,
                label = stringResource(R.string.feedback_category_bug),
                selected = state.selectedCategory == FeedbackCategory.Bug,
                onClick = { onSelectCategory(FeedbackCategory.Bug) },
            )
            CategoryCard(
                icon = Icons.Outlined.AutoAwesome,
                label = stringResource(R.string.feedback_category_feature),
                selected = state.selectedCategory == FeedbackCategory.Feature,
                onClick = { onSelectCategory(FeedbackCategory.Feature) },
            )
            CategoryCard(
                icon = Icons.Outlined.EmojiNature,
                label = stringResource(R.string.feedback_category_thought),
                selected = state.selectedCategory == FeedbackCategory.Thought,
                onClick = { onSelectCategory(FeedbackCategory.Thought) },
            )
        }

        OutlinedTextField(
            value = state.message,
            onValueChange = onUpdateMessage,
            placeholder = {
                Text(
                    text = stringResource(R.string.feedback_message_placeholder),
                    style = pilgrimType.body,
                    color = pilgrimColors.fog.copy(alpha = 0.5f),
                )
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = pilgrimColors.parchmentSecondary,
                unfocusedContainerColor = pilgrimColors.parchmentSecondary,
                focusedTextColor = pilgrimColors.ink,
                unfocusedTextColor = pilgrimColors.ink,
            ),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.feedback_include_device_info),
                style = pilgrimType.body,
                color = pilgrimColors.ink,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.includeDeviceInfo,
                onCheckedChange = onToggleDeviceInfo,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = pilgrimColors.parchment,
                    checkedTrackColor = pilgrimColors.stone,
                    uncheckedThumbColor = pilgrimColors.fog,
                    uncheckedTrackColor = pilgrimColors.parchmentTertiary,
                ),
            )
        }

        if (state.errorMessage != null) {
            val errorText = when (state.errorMessage) {
                FeedbackErrorMessage.RateLimited -> stringResource(R.string.feedback_error_rate_limited)
                FeedbackErrorMessage.Generic -> stringResource(R.string.feedback_error_generic)
            }
            Text(
                text = errorText,
                style = pilgrimType.caption,
                color = pilgrimColors.rust,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (state.canSubmit) pilgrimColors.stone
                    else pilgrimColors.fog.copy(alpha = 0.2f),
                )
                .clickable(enabled = state.canSubmit && !state.isSubmitting) { onSubmit() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    color = pilgrimColors.parchment,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = stringResource(R.string.feedback_send),
                    style = pilgrimType.button,
                    color = pilgrimColors.parchment,
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) pilgrimColors.stone.copy(alpha = 0.08f)
                else pilgrimColors.parchmentSecondary,
            )
            .let { mod ->
                if (selected) mod.border(1.dp, pilgrimColors.stone, RoundedCornerShape(12.dp)) else mod
            }
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = pilgrimColors.stone,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = pilgrimType.body,
            color = pilgrimColors.ink,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = pilgrimColors.moss,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ConfirmationOverlay(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(pilgrimColors.parchment).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(80.dp).clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = pilgrimColors.moss,
                modifier = Modifier.size(56.dp),
            )
        }
        Spacer(Modifier.heightIn(min = 16.dp))
        Text(
            text = stringResource(R.string.feedback_confirmation_line1),
            style = pilgrimType.body,
            color = pilgrimColors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.heightIn(min = 8.dp))
        Text(
            text = stringResource(R.string.feedback_confirmation_line2),
            style = pilgrimType.body.copy(fontStyle = FontStyle.Italic),
            color = pilgrimColors.fog,
            textAlign = TextAlign.Center,
        )
    }
}

private const val CONFIRMATION_DISMISS_MS = 2500L
