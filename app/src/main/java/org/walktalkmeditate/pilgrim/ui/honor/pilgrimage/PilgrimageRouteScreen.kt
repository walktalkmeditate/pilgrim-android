// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCopy
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedger
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageRouteStage
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.honor.DISABLED_ALPHA
import org.walktalkmeditate.pilgrim.ui.honor.HonorSheetFrame
import org.walktalkmeditate.pilgrim.ui.honor.HonorSheetGroupRow
import org.walktalkmeditate.pilgrim.ui.honor.HonorSheetHost
import org.walktalkmeditate.pilgrim.ui.honor.HonorSheetSection
import org.walktalkmeditate.pilgrim.ui.honor.HonorSheetSectionHeader
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/** What the route page's controls do; each is the ViewModel's, or the sheet's slide-then-go. */
@Immutable
class PilgrimageRouteActions(
    val onBack: () -> Unit,
    val onDownload: () -> Unit,
    val onOpenNext: () -> Unit,
    val onOpenStage: (index: Int) -> Unit,
    val onRetryStages: () -> Unit,
    val onRemove: () -> Unit,
    val onConfirmReplace: () -> Unit,
    val onConfirmRemove: () -> Unit,
    val onConfirmDownloadFirst: () -> Unit,
    val onDismissAlert: () -> Unit,
)

/**
 * iOS `PilgrimageRouteView` (`PilgrimageRouteView.swift:66-401@7c200bf`,
 * pilgrimage-stage spec P4 §4): the route's name in the bar, Back to the
 * catalog leading, and, for the installed route, the overflow's Remove;
 * then three sections: the summary with the download button and the
 * status lines under it, the next row on the installed route, and
 * "Stages", which stands whether or not the route is on the phone.
 */
@Composable
fun PilgrimageRouteContent(
    state: PilgrimageRouteUiState,
    phase: PilgrimagePackageManager.Phase,
    units: UnitSystem,
    alert: PilgrimageRouteAlert?,
    actions: PilgrimageRouteActions,
    modifier: Modifier = Modifier,
) {
    val page = (state as? PilgrimageRouteUiState.Ready)?.page
    val busy = page != null && PilgrimageRouteModel.isBusy(phase, page.actionInFlight)
    HonorSheetFrame(
        title = page?.entry?.name.orEmpty(),
        leading = {
            // iOS's back button, titled with the catalog it returns to (P4 §4.7).
            IconButton(onClick = actions.onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.pilgrimage_catalog_title),
                    tint = pilgrimColors.stone,
                )
            }
        },
        trailing = {
            if (page?.isInstalled == true) OverflowMenu(enabled = !busy, onRemove = actions.onRemove)
        },
        modifier = modifier,
        scrollable = false,
    ) {
        if (page == null) {
            // The catalog is read again after a process death; the sheet keeps its size meanwhile.
            Box(Modifier.fillMaxSize())
        } else {
            RouteSections(page, phase, units, busy, actions)
        }
    }
    if (page != null && alert != null) RouteAlert(alert, page, actions)
}

@Composable
private fun RouteSections(
    page: PilgrimageRoutePage,
    phase: PilgrimagePackageManager.Phase,
    units: UnitSystem,
    busy: Boolean,
    actions: PilgrimageRouteActions,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "header") { HeaderSection(page, phase, units, busy, actions.onDownload) }
        if (page.isInstalled) {
            item(key = "next") {
                Column {
                    Spacer(Modifier.height(PilgrimSpacing.normal))
                    HonorSheetSection(header = null) { NextRow(page, units, actions.onOpenNext) }
                }
            }
        }
        item(key = "stages-header") {
            Column {
                Spacer(Modifier.height(PilgrimSpacing.normal))
                HonorSheetSectionHeader(
                    stringResource(R.string.pilgrimage_route_stages_header),
                    Modifier.padding(bottom = PilgrimSpacing.xs),
                )
            }
        }
        val stagesFailure = page.stagesFailure
        when {
            page.stages.isNotEmpty() -> itemsIndexed(page.stages, key = { position, stage -> "stage:$position:${stage.index}" }) { position, stage ->
                HonorSheetGroupRow(isFirst = position == 0, isLast = position == page.stages.lastIndex) {
                    StageRow(stage, page.ledger, units, onClick = { actions.onOpenStage(stage.index) })
                }
            }
            page.isLoadingStages -> item(key = "reaching") {
                HonorSheetGroupRow(isFirst = true, isLast = true) { ReachingForStages() }
            }
            stagesFailure != null -> item(key = "stages-failure") {
                HonorSheetGroupRow(isFirst = true, isLast = true) { StagesFailure(stagesFailure, actions.onRetryStages) }
            }
        }
    }
}

/**
 * The summary (or "<Tradition> · <region>"), the sparse note, the card
 * line (no badge: the button says it), and the button, 8 apart; under the
 * group, the status lines.
 */
@Composable
private fun HeaderSection(
    page: PilgrimageRoutePage,
    phase: PilgrimagePackageManager.Phase,
    units: UnitSystem,
    busy: Boolean,
    onDownload: () -> Unit,
) {
    val resources = LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
        HonorSheetSection(header = null) {
            Column(
                modifier = Modifier.padding(vertical = PilgrimSpacing.small),
                verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
            ) {
                PilgrimageRouteModel.summary(resources, page)?.let {
                    Text(text = it, style = pilgrimType.body, color = pilgrimColors.ink)
                }
                PilgrimageCatalogModel.sparseNote(resources, page.entry)?.let {
                    Text(text = it, style = pilgrimType.caption, color = pilgrimColors.fog.copy(alpha = SPARSE_ALPHA))
                }
                Text(
                    text = PilgrimageCatalogModel.card(resources, page.entry, page.ledger, page.isInstalled, units),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                )
                DownloadButton(page, busy, onDownload)
            }
        }
        StatusLines(page, phase)
    }
}

/**
 * "Download", "Update" or "On your phone", never "Downloading…": the
 * lines under it carry the progress. Fog and held while busy or current.
 */
@Composable
private fun DownloadButton(page: PilgrimageRoutePage, busy: Boolean, onDownload: () -> Unit) {
    Button(
        onClick = onDownload,
        enabled = !busy && !(page.isInstalled && !page.hasUpdate),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PilgrimCornerRadius.normal),
        contentPadding = PaddingValues(vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = pilgrimColors.stone,
            contentColor = pilgrimColors.parchment,
            disabledContainerColor = pilgrimColors.fog,
            disabledContentColor = pilgrimColors.parchment,
        ),
    ) {
        Text(
            text = PilgrimageRouteModel.buttonLabel(LocalResources.current, page.isInstalled, page.hasUpdate),
            style = pilgrimType.button,
            color = pilgrimColors.parchment,
        )
    }
}

/**
 * Any download's "stage d of n" from the manager's phase; this page's own
 * failure in rust; the redraw notice once it has shown, for the page's
 * life (pilgrim-ios #121, matched). A failed phase is never drawn.
 */
@Composable
private fun StatusLines(page: PilgrimageRoutePage, phase: PilgrimagePackageManager.Phase) {
    val resources = LocalResources.current
    val lines = buildList {
        PilgrimageRouteModel.downloadProgress(resources, phase)?.let { add(it to false) }
        page.failure?.let { add(resources.getString(PilgrimageCopy.line(it)) to true) }
        if (page.showRedrawNotice) add(PilgrimageRouteModel.redrawNotice(resources) to false)
    }
    if (lines.isEmpty()) return
    Column(
        modifier = Modifier.padding(horizontal = PilgrimSpacing.small),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
    ) {
        lines.forEach { (line, isFailure) ->
            Text(text = line, style = pilgrimType.caption, color = if (isFailure) pilgrimColors.rust else pilgrimColors.fog)
        }
    }
}

/** One button, the row's words over the progress line: "next: stage 2, stage 2 of 33 · 24.2 km walked". */
@Composable
private fun NextRow(page: PilgrimageRoutePage, units: UnitSystem, onClick: () -> Unit) {
    val resources = LocalResources.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = null, role = Role.Button, onClick = onClick)
            .padding(vertical = PilgrimSpacing.small),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = PilgrimageRouteModel.nextRow(resources, page.ledger, page.entry.stageCount),
            style = pilgrimType.body,
            color = pilgrimColors.ink,
        )
        Text(
            text = PilgrimageLedger.progressLine(resources, page.ledger, page.entry.stageCount) { StageFormat.distance(it, units) },
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}

/**
 * The circle, filled once the ledger has the stage completed, else hollow,
 * and never spoken (pilgrim-ios #121, matched); "1. <name>" over the facts
 * line. One button, live during a download as iOS's rows are.
 */
@Composable
private fun StageRow(stage: PilgrimageRouteStage, ledger: PilgrimageLedger?, units: UnitSystem, onClick: () -> Unit) {
    val resources = LocalResources.current
    val walked = ledger?.stages?.get(stage.index.toString())?.completed == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (walked) Icons.Filled.Circle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = pilgrimColors.stone,
            modifier = Modifier
                .padding(top = 4.dp)
                .size(CIRCLE_SIZE),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = PilgrimageRouteModel.stageTitle(resources, stage), style = pilgrimType.body, color = pilgrimColors.ink)
            Text(text = PilgrimageRouteModel.stageLine(resources, stage, units), style = pilgrimType.caption, color = pilgrimColors.fog)
        }
    }
}

@Composable
private fun ReachingForStages() {
    Row(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = pilgrimColors.stone, strokeWidth = 2.dp)
        Text(text = stringResource(R.string.pilgrimage_route_reaching), style = pilgrimType.caption, color = pilgrimColors.fog)
    }
}

/** A failed preview in fog, not rust, so it never reads as a failed download; "try again" in caption stone. */
@Composable
private fun StagesFailure(error: PilgrimageError, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
    ) {
        Text(text = stringResource(PilgrimageCopy.line(error)), style = pilgrimType.caption, color = pilgrimColors.fog)
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 44.dp), contentPadding = PaddingValues(0.dp)) {
            Text(text = stringResource(R.string.honor_overview_try_again), style = pilgrimType.caption, color = pilgrimColors.stone)
        }
    }
}

/** iOS's `ellipsis` menu, its one item "Remove" in rust; held while busy. */
@Composable
private fun OverflowMenu(enabled: Boolean, onRemove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.MoreHoriz,
                contentDescription = stringResource(R.string.pilgrimage_route_more_a11y),
                tint = if (enabled) pilgrimColors.stone else pilgrimColors.stone.copy(alpha = DISABLED_ALPHA),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = pilgrimColors.parchment,
        ) {
            DropdownMenuItem(
                text = {
                    Text(text = stringResource(R.string.pilgrimage_route_remove), style = pilgrimType.body, color = pilgrimColors.rust)
                },
                onClick = {
                    expanded = false
                    onRemove()
                },
            )
        }
    }
}

/**
 * iOS's three alerts (P4 §4.7): "Replace?" names the route being let go,
 * the installed `route.json`'s name; "Remove?" names this page's entry;
 * "Download this route first?" runs the button's own gate. The destructive
 * buttons in rust, "Download" in stone.
 */
@Composable
internal fun RouteAlert(alert: PilgrimageRouteAlert, page: PilgrimageRoutePage, actions: PilgrimageRouteActions) {
    val resources = LocalResources.current
    when (alert) {
        PilgrimageRouteAlert.REPLACE -> RouteAlertDialog(
            title = stringResource(R.string.pilgrimage_replace_title),
            message = PilgrimagePackageManager.replaceConfirmation(
                resources,
                page.installed?.route?.name ?: stringResource(R.string.pilgrimage_replace_fallback_name),
            ),
            confirm = stringResource(R.string.pilgrimage_replace),
            confirmColor = pilgrimColors.rust,
            onConfirm = actions.onConfirmReplace,
            dismiss = stringResource(R.string.honor_card_keep),
            onDismiss = actions.onDismissAlert,
        )
        PilgrimageRouteAlert.REMOVE -> RouteAlertDialog(
            title = stringResource(R.string.pilgrimage_remove_title),
            message = PilgrimagePackageManager.removeConfirmation(resources, page.entry.name),
            confirm = stringResource(R.string.pilgrimage_route_remove),
            confirmColor = pilgrimColors.rust,
            onConfirm = actions.onConfirmRemove,
            dismiss = stringResource(R.string.honor_card_keep),
            onDismiss = actions.onDismissAlert,
        )
        PilgrimageRouteAlert.DOWNLOAD_FIRST -> RouteAlertDialog(
            title = stringResource(R.string.pilgrimage_download_first_title),
            message = stringResource(R.string.pilgrimage_download_first_message),
            confirm = stringResource(R.string.pilgrimage_route_download),
            confirmColor = pilgrimColors.stone,
            onConfirm = actions.onConfirmDownloadFirst,
            dismiss = stringResource(R.string.pilgrimage_not_now),
            onDismiss = actions.onDismissAlert,
        )
    }
}

@Composable
private fun RouteAlertDialog(
    title: String,
    message: String,
    confirm: String,
    confirmColor: Color,
    onConfirm: () -> Unit,
    dismiss: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = pilgrimType.heading) },
        text = { Text(message, style = pilgrimType.body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = confirmColor) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss, color = pilgrimColors.stone) } },
        containerColor = pilgrimColors.parchment,
        titleContentColor = pilgrimColors.ink,
        textContentColor = pilgrimColors.ink,
    )
}

/**
 * The route page as a second sheet over the catalog (owner decision 8, P4
 * A-1): system Back and the leading control slide it down and return to
 * the catalog; a swipe closes both, as iOS's swipe takes the one sheet
 * holding both ([onClosed]). A stage with its Way slides the page down and
 * opens the stage's overview. A route the catalog no longer lists closes
 * back to the catalog.
 */
@Composable
fun PilgrimageRouteSheet(
    onBack: () -> Unit,
    onClosed: () -> Unit,
    onOpenStage: (wayId: String) -> Unit,
    viewModel: PilgrimageRouteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    val alert by viewModel.alert.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val openStage by rememberUpdatedState(onOpenStage)
    val gone = state == PilgrimageRouteUiState.Gone
    HonorSheetHost(onDismissed = onClosed, onBack = onBack) { hideThen ->
        LaunchedEffect(viewModel) {
            viewModel.opened.collect { wayId -> hideThen { openStage(wayId) } }
        }
        LaunchedEffect(gone) {
            if (gone) hideThen(back)
        }
        PilgrimageRouteContent(
            state = state,
            phase = phase,
            units = units,
            alert = alert,
            actions = PilgrimageRouteActions(
                onBack = { hideThen(back) },
                onDownload = viewModel::onDownloadTapped,
                onOpenNext = viewModel::openNext,
                onOpenStage = viewModel::open,
                onRetryStages = viewModel::retryStages,
                onRemove = viewModel::onRemoveTapped,
                onConfirmReplace = viewModel::confirmReplace,
                onConfirmRemove = viewModel::confirmRemove,
                onConfirmDownloadFirst = viewModel::confirmDownloadFirst,
                onDismissAlert = viewModel::dismissAlert,
            ),
        )
    }
}

/** iOS's caption-sized circle. */
private val CIRCLE_SIZE = 12.dp

private const val SPARSE_ALPHA = 0.7f
