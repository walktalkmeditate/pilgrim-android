// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.prompt.GeneratedPrompt
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Time after a Copy tap before the "Copied!" label reverts to "Copy" and
 * the AI deep-link pills slide back down. Mirrors iOS
 * `DispatchQueue.main.asyncAfter(deadline: .now() + 8)` in
 * `PromptDetailView.swift`. Re-tapping Copy cancels the prior reset job
 * and restarts the timer.
 */
internal const val PROMPT_DETAIL_FEEDBACK_RESET_MS = 8_000L

/**
 * Deep-link URLs for the AI provider pills. ACTION_VIEW Intents target
 * the web flows so users without the native apps still get a working
 * paste destination. Verbatim from iOS [PromptDetailView].
 */
internal const val CHATGPT_URL = "https://chat.openai.com/"
internal const val CLAUDE_URL = "https://claude.ai/new"

internal const val PROMPT_DETAIL_COPY_BUTTON_TAG = "PromptDetailDialog.copy"
internal const val PROMPT_DETAIL_SHARE_BUTTON_TAG = "PromptDetailDialog.share"
internal const val PROMPT_DETAIL_DONE_BUTTON_TAG = "PromptDetailDialog.done"
internal const val PROMPT_DETAIL_PILL_CHATGPT_TAG = "PromptDetailDialog.pill.chatgpt"
internal const val PROMPT_DETAIL_PILL_CLAUDE_TAG = "PromptDetailDialog.pill.claude"

/**
 * Full-screen dialog showing the assembled prompt text with Copy, Share,
 * and "Paste in your favorite AI" deep-link pills. Mirrors iOS
 * `PromptDetailView.swift`.
 *
 * Behavior on Copy:
 *  1. Write [GeneratedPrompt.text] to the system clipboard.
 *  2. Animate a brief 1f -> 0.95f -> 1f scale jiggle on the Copy button.
 *  3. Swap the Copy button label to "Copied!" + checkmark icon.
 *  4. Slide the ChatGPT / Claude pills up from the bottom with fade-in.
 *  5. After [PROMPT_DETAIL_FEEDBACK_RESET_MS] revert all three. Re-tapping
 *     Copy cancels the prior reset job and restarts the 8s timer.
 *
 * Content extracted to [PromptDetailContent] so unit tests can drive the
 * inner column directly without instantiating the [Dialog] /
 * [Surface] wrapper (Robolectric's Dialog harness is fragile).
 */
@Composable
fun PromptDetailDialog(
    prompt: GeneratedPrompt,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = pilgrimColors.parchment,
        ) {
            PromptDetailContent(prompt = prompt, onDismiss = onDismiss)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptDetailContent(
    prompt: GeneratedPrompt,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showCopiedFeedback by remember { mutableStateOf(false) }
    var showAIPills by remember { mutableStateOf(false) }
    var copyResetJob by remember { mutableStateOf<Job?>(null) }
    var copyJiggleTrigger by remember { mutableStateOf(false) }

    val copyScale by animateFloatAsState(
        targetValue = if (copyJiggleTrigger) 0.95f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "prompt-detail-copy-scale",
    )

    fun handleCopy() {
        copyTextToClipboard(context, prompt.text)
        showCopiedFeedback = true
        showAIPills = true
        copyResetJob?.cancel()
        copyJiggleTrigger = true
        copyResetJob = coroutineScope.launch {
            delay(150L)
            copyJiggleTrigger = false
            delay(PROMPT_DETAIL_FEEDBACK_RESET_MS - 150L)
            showCopiedFeedback = false
            showAIPills = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {},
            actions = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(PROMPT_DETAIL_DONE_BUTTON_TAG),
                ) {
                    Text(
                        text = stringResource(R.string.prompt_detail_done),
                        style = pilgrimType.button,
                        color = pilgrimColors.stone,
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
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(PilgrimSpacing.normal),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.big),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = prompt.icon,
                    contentDescription = null,
                    tint = pilgrimColors.stone,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = prompt.title,
                    style = pilgrimType.displayMedium,
                    color = pilgrimColors.ink,
                )
            }
            SelectionContainer {
                Text(
                    text = prompt.text,
                    style = pilgrimType.body,
                    color = pilgrimColors.ink,
                )
            }
        }

        HorizontalDivider(color = pilgrimColors.fog.copy(alpha = 0.2f))

        Column(
            modifier = Modifier.padding(
                horizontal = PilgrimSpacing.normal,
                vertical = PilgrimSpacing.small,
            ),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal)) {
                Button(
                    onClick = ::handleCopy,
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer { scaleX = copyScale; scaleY = copyScale }
                        .testTag(PROMPT_DETAIL_COPY_BUTTON_TAG),
                    shape = RoundedCornerShape(PilgrimCornerRadius.normal),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = pilgrimColors.stone,
                        contentColor = pilgrimColors.parchment,
                    ),
                ) {
                    val icon = if (showCopiedFeedback) Icons.Filled.Check
                    else Icons.Outlined.ContentCopy
                    val label = if (showCopiedFeedback) {
                        stringResource(R.string.prompt_detail_copied)
                    } else {
                        stringResource(R.string.prompt_detail_copy)
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = label, style = pilgrimType.button)
                }

                OutlinedButton(
                    onClick = { launchShare(context, prompt.text) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(PROMPT_DETAIL_SHARE_BUTTON_TAG),
                    shape = RoundedCornerShape(PilgrimCornerRadius.normal),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = pilgrimColors.stone,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.prompt_detail_share),
                        style = pilgrimType.button,
                    )
                }
            }

            AnimatedVisibility(
                visible = showAIPills,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
                ) {
                    Text(
                        text = stringResource(R.string.prompt_detail_paste_in_ai),
                        style = pilgrimType.caption,
                        color = pilgrimColors.fog,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
                    ) {
                        AIPill(
                            label = stringResource(R.string.prompt_detail_pill_chatgpt),
                            testTag = PROMPT_DETAIL_PILL_CHATGPT_TAG,
                            onClick = { launchExternalUrl(context, CHATGPT_URL) },
                        )
                        AIPill(
                            label = stringResource(R.string.prompt_detail_pill_claude),
                            testTag = PROMPT_DETAIL_PILL_CLAUDE_TAG,
                            onClick = { launchExternalUrl(context, CLAUDE_URL) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AIPill(
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(pilgrimColors.parchmentSecondary)
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(
                horizontal = PilgrimSpacing.normal,
                vertical = PilgrimSpacing.small,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = label, style = pilgrimType.caption, color = pilgrimColors.stone)
            Icon(
                imageVector = Icons.Outlined.NorthEast,
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

private fun copyTextToClipboard(context: Context, text: String) {
    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Pilgrim prompt", text))
}

private fun launchShare(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, null).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        // ACTION_SEND text/plain is universally handled on real devices.
        // Defensive no-op for stripped-down emulators / instrumentation.
    }
}

private fun launchExternalUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No browser installed; ignore silently.
    }
}
