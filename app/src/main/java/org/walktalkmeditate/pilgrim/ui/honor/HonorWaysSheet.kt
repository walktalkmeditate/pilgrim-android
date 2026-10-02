// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.honor.HonorImportCopy
import org.walktalkmeditate.pilgrim.honor.HonorImportState
import org.walktalkmeditate.pilgrim.honor.HonorLink
import org.walktalkmeditate.pilgrim.ui.settings.SettingNavRow
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS `HonorWaysSheet` ("Choose a way", `HonorWaysSheet.swift:23-117@7c200bf`,
 * parity spec F §4, shared-walk spec S4 §6–§7). iOS's four sections, in order:
 *  1. "Shared with you": the accepted shared Ways, newest acceptance first;
 *  2. "Your own walks": one nav row into the "Walk again" picker;
 *  3. "A pilgrimage" (Stage 21-2, not shown yet);
 *  4. "From a shared walk": the paste field and "Open". The clipboard is
 *     never read: the walker pastes, or types (S2 §8.2).
 */
@Composable
fun HonorWaysSheetContent(
    shared: SharedWaysUiState,
    importState: HonorImportState,
    onClose: () -> Unit,
    onChooseShared: (wayId: String) -> Unit,
    onWalkOneOfYours: () -> Unit,
    onOpenPasted: (text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    HonorSheetScaffold(
        title = stringResource(R.string.honor_ways_title),
        onClose = onClose,
        modifier = modifier,
    ) {
        HonorSheetSection(header = stringResource(R.string.honor_ways_shared_header)) {
            SharedWays(shared = shared, onChoose = onChooseShared)
        }
        HonorSheetSection(header = stringResource(R.string.honor_ways_own_header)) {
            // iOS's Button with no label or hint of its own (F §17.2).
            SettingNavRow(
                label = stringResource(R.string.honor_ways_own_row),
                onClick = onWalkOneOfYours,
                modifier = Modifier.fillMaxWidth(),
                role = Role.Button,
                onClickLabel = null,
            )
        }
        HonorSheetSection(
            header = stringResource(R.string.honor_ways_paste_header),
            footer = stringResource(R.string.honor_ways_paste_footer),
        ) {
            PasteField(importState = importState, onOpen = onOpenPasted)
        }
    }
}

/**
 * The rows once read, or iOS's empty copy when no shared Way is stored,
 * own walks or not. Nothing shows while the list is read, so the copy
 * never flashes.
 */
@Composable
private fun SharedWays(shared: SharedWaysUiState, onChoose: (wayId: String) -> Unit) {
    val rows = (shared as? SharedWaysUiState.Loaded)?.rows ?: return
    if (rows.isEmpty()) {
        Text(
            text = stringResource(R.string.honor_ways_shared_empty),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
            modifier = Modifier.padding(vertical = PilgrimSpacing.small),
        )
        return
    }
    rows.forEachIndexed { index, row ->
        SharedWayRowView(row = row, onClick = { onChoose(row.wayId) })
        if (index != rows.lastIndex) HorizontalDivider(color = pilgrimColors.fog.copy(alpha = 0.15f))
    }
}

/**
 * One button: the title over the date, a "·", and the counts, three
 * texts of their own inside the label, as iOS's row reads them; no
 * chevron and no hint (S4 §13.3, pilgrim-ios #108, matched).
 */
@Composable
private fun SharedWayRowView(row: SharedWayRow, onClick: () -> Unit) {
    val resources = LocalResources.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = PilgrimSpacing.small),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = row.title, style = pilgrimType.body, color = pilgrimColors.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
            val detail = if (row.voicesReturned) {
                stringResource(R.string.honor_ways_voices_returned)
            } else {
                HonorOverviewModel.countsLine(resources, row.voiceCount, row.photoCount)
            }
            listOf(row.date, ROW_SEPARATOR, detail).forEach {
                Text(text = it, style = pilgrimType.caption, color = pilgrimColors.fog)
            }
        }
    }
}

/**
 * iOS's paste section (S2 §8.3, S4 §7): the field stays editable in every
 * state and keeps its text after a failure; "Open" is enabled only while
 * the text parses and nothing is fetching; Return closes the keyboard and
 * opens nothing. The line under it is fog while fetching, rust for a
 * failure. The text lives with the sheet, so each opening starts empty.
 */
@Composable
private fun PasteField(importState: HonorImportState, onOpen: (text: String) -> Unit) {
    var pasted by rememberSaveable { mutableStateOf("") }
    val canOpen = HonorLink.parse(text = pasted) != null && importState != HonorImportState.Fetching
    TextField(
        value = pasted,
        onValueChange = { pasted = it },
        modifier = Modifier.fillMaxWidth(),
        textStyle = pilgrimType.body,
        placeholder = {
            Text(text = stringResource(R.string.honor_ways_paste_placeholder), style = pilgrimType.body)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = pilgrimColors.ink,
            unfocusedTextColor = pilgrimColors.ink,
            focusedPlaceholderColor = pilgrimColors.fog,
            unfocusedPlaceholderColor = pilgrimColors.fog,
            cursorColor = pilgrimColors.stone,
        ),
    )
    HorizontalDivider(color = pilgrimColors.fog.copy(alpha = 0.15f))
    TextButton(onClick = { onOpen(pasted) }, enabled = canOpen) {
        Text(
            text = stringResource(R.string.honor_ways_paste_open),
            style = pilgrimType.button,
            color = if (canOpen) pilgrimColors.stone else pilgrimColors.stone.copy(alpha = DISABLED_ALPHA),
        )
    }
    HonorImportCopy.line(LocalResources.current, importState)?.let {
        Text(
            text = it,
            style = pilgrimType.caption,
            color = if (importState is HonorImportState.Failed) pilgrimColors.rust else pilgrimColors.fog,
            modifier = Modifier.padding(bottom = PilgrimSpacing.small),
        )
    }
}

private const val ROW_SEPARATOR = "·"

/** The system's dimming of a disabled button. */
private const val DISABLED_ALPHA = 0.38f

/**
 * The chrome both Honor sheets share: iOS's inline navigation bar, "Close"
 * leading in stone and the title centred in heading ink, over sections.
 */
@Composable
internal fun HonorSheetScaffold(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(pilgrimColors.parchment)
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = PilgrimSpacing.small),
        ) {
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                Text(
                    text = stringResource(R.string.honor_close),
                    style = pilgrimType.button,
                    color = pilgrimColors.stone,
                )
            }
            Text(
                text = title,
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { heading() },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = PilgrimSpacing.normal)
                .padding(bottom = PilgrimSpacing.big),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
            content = content,
        )
    }
}

/** A grouped-list section: its caption header over one rounded group, and a caption footer under it. */
@Composable
internal fun HonorSheetSection(
    header: String,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
        Text(
            text = header,
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
            modifier = Modifier
                .padding(horizontal = PilgrimSpacing.small)
                .semantics { heading() },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PilgrimCornerRadius.normal))
                .background(pilgrimColors.parchmentSecondary)
                .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.xs),
            content = content,
        )
        if (footer != null) {
            Text(
                text = footer,
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                modifier = Modifier.padding(horizontal = PilgrimSpacing.small),
            )
        }
    }
}

/**
 * A full-height sheet with no drag handle, as iOS's large-detent sheets
 * show none. [content] gets a `hideThen` that slides the sheet down
 * before running its action, so the sheet is gone before whatever opens
 * next; a swipe or Back closes it through [onDismissed].
 *
 * Its route is a dialog (`honorSheet`): the route's own window lies under
 * the sheet's and doesn't dim, so only the sheet's scrim shades Path
 * behind. A route under the next sheet (the Ways sheet under the picker)
 * stays composed, hidden, and slides back up once it is on top again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HonorSheetHost(
    onDismissed: () -> Unit,
    content: @Composable (hideThen: (() -> Unit) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismissed by rememberUpdatedState(onDismissed)
    val routeWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { routeWindow?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND) }
    val onTop = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(onTop) {
        if (onTop && sheetState.targetValue == SheetValue.Hidden) sheetState.show()
    }
    val hideThen: (() -> Unit) -> Unit = remember(sheetState, scope) {
        { action ->
            scope.launch { sheetState.hide() }.invokeOnCompletion {
                if (!sheetState.isVisible) action()
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = { dismissed() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = PilgrimCornerRadius.big, topEnd = PilgrimCornerRadius.big),
        dragHandle = null,
        containerColor = pilgrimColors.parchment,
    ) {
        content(hideThen)
    }
}

/**
 * The Ways sheet as a route. "Walk one of yours again" leaves the sheet
 * for the picker; a shared row, and a paste once its import lands, close
 * the sheet and then open the Way's overview (S4 §6.5, §7.4).
 */
@Composable
fun HonorWaysSheetRoute(
    onClosed: () -> Unit,
    onOpenOwnWalks: () -> Unit,
    onOpenOverview: (wayId: String) -> Unit,
    viewModel: HonorWaysViewModel = hiltViewModel(),
) {
    val shared by viewModel.shared.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val fetched by viewModel.fetched.collectAsStateWithLifecycle()
    val openOverview by rememberUpdatedState(onOpenOverview)
    HonorSheetHost(onDismissed = onClosed) { hideThen ->
        LaunchedEffect(fetched) {
            val wayId = fetched ?: return@LaunchedEffect
            viewModel.consumeFetched(wayId)
            hideThen { openOverview(wayId) }
        }
        HonorWaysSheetContent(
            shared = shared,
            importState = importState,
            onClose = { hideThen(onClosed) },
            onChooseShared = { wayId -> hideThen { openOverview(wayId) } },
            onWalkOneOfYours = { hideThen(onOpenOwnWalks) },
            onOpenPasted = viewModel::open,
        )
    }
}
