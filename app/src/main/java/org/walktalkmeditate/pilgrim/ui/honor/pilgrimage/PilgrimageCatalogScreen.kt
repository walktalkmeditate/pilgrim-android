// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalog
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCopy
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedger
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.honor.HonorSheetGroupRow
import org.walktalkmeditate.pilgrim.ui.honor.HonorSheetHost
import org.walktalkmeditate.pilgrim.ui.honor.HonorSheetScaffold
import org.walktalkmeditate.pilgrim.ui.honor.HonorSheetSectionHeader
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS `PilgrimageCatalogView` ("Pilgrimages", `PilgrimageCatalogView.swift:52-235@7c200bf`,
 * pilgrimage-stage spec P4 §3): the routes the dataset says are walkable,
 * grouped under their pilgrimages, the one on the phone marked by a glyph.
 * One of three faces fills the sheet under its bar: a stone spinner while
 * nothing is held, the list, or the unreachable copy over "try again".
 */
@Composable
fun PilgrimageCatalogContent(
    catalog: PilgrimageCatalog?,
    state: PilgrimageCatalogUiState,
    units: UnitSystem,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (routeId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    HonorSheetScaffold(
        title = stringResource(R.string.pilgrimage_catalog_title),
        onClose = onClose,
        modifier = modifier,
        scrollable = false,
    ) {
        when (val face = PilgrimageCatalogModel.face(state.isLoading, catalog, state.failure)) {
            PilgrimageCatalogFace.Spinner -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = pilgrimColors.stone)
            }
            is PilgrimageCatalogFace.Listing -> CatalogListing(face, state, units, onOpen)
            is PilgrimageCatalogFace.Unreachable -> Unreachable(face.error, onRetry)
        }
    }
}

/**
 * One section per group in the catalog's order, a group no pilgrimage
 * claims under no header. The rust line over it is the only place a
 * reload that threw while the list showed reaches the walker: the frame's
 * 16 in from the edge and 8 under the bar, as iOS's; the 8 under it stands
 * in for the `List`'s own top inset. Rows are keyed by position and entry
 * id, never by a group's id, which can repeat (P1 A6).
 */
@Composable
private fun CatalogListing(
    face: PilgrimageCatalogFace.Listing,
    state: PilgrimageCatalogUiState,
    units: UnitSystem,
    onOpen: (routeId: String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        face.failure?.let { failure ->
            Text(
                text = stringResource(PilgrimageCopy.line(failure)),
                style = pilgrimType.caption,
                color = pilgrimColors.rust,
                modifier = Modifier.padding(top = PilgrimSpacing.small, bottom = PilgrimSpacing.small),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            face.catalog.groups.forEachIndexed { groupIndex, group ->
                if (groupIndex > 0) item(key = "gap:$groupIndex") { Spacer(Modifier.height(PilgrimSpacing.normal)) }
                group.name?.let { name ->
                    item(key = "header:$groupIndex") {
                        HonorSheetSectionHeader(name, Modifier.padding(bottom = PilgrimSpacing.xs))
                    }
                }
                itemsIndexed(group.entries, key = { index, entry -> "route:$groupIndex:$index:${entry.id}" }) { index, entry ->
                    HonorSheetGroupRow(isFirst = index == 0, isLast = index == group.entries.lastIndex) {
                        CatalogRow(
                            entry = entry,
                            installed = state.installed,
                            catalogRelease = face.catalog.release,
                            ledger = state.ledgers[entry.id],
                            units = units,
                            onClick = { onOpen(entry.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One button, read as one element: the name, the badge's words, the card
 * line and the sparse note, the plate left out ("Camino de Santiago
 * (Frances), on your phone, ES · 764 km · stage 5 of 33 · 112 km walked,
 * few places marked yet, button"; P4 §3.3). No click label, as iOS's
 * button has no hint.
 */
@Composable
internal fun CatalogRow(
    entry: PilgrimageCatalogEntry,
    installed: PilgrimagePackageManager.Installed?,
    catalogRelease: String?,
    ledger: PilgrimageLedger?,
    units: UnitSystem,
    onClick: () -> Unit,
) {
    val resources = LocalResources.current
    val isInstalled = PilgrimageCatalogModel.isInstalled(installed, entry)
    val badge = PilgrimageCatalogModel.installBadge(
        isInstalled = isInstalled,
        hasUpdate = PilgrimageCatalogModel.hasUpdate(installed, entry, catalogRelease),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        verticalAlignment = Alignment.Top,
    ) {
        CoverPlate(entry.name)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.name,
                    style = pilgrimType.body,
                    color = pilgrimColors.ink,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Icon(
                        imageVector = badge.glyph,
                        contentDescription = stringResource(badge.label),
                        tint = badgeTint(badge),
                        modifier = Modifier.size(BADGE_SIZE),
                    )
                }
            }
            Text(
                text = PilgrimageCatalogModel.card(resources, entry, ledger, isInstalled, units),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
            PilgrimageCatalogModel.sparseNote(resources, entry)?.let {
                Text(text = it, style = pilgrimType.caption, color = pilgrimColors.fog.copy(alpha = SPARSE_ALPHA))
            }
        }
    }
}

/**
 * Where a cover will stand once the dataset ships one: the name's first
 * grapheme in stone on a 44 dp plate, out of TalkBack's reach. Parchment,
 * where iOS's is parchment-secondary on a white row, so it keeps its
 * contrast on Android's parchment-secondary rows (P4 A-2).
 */
@Composable
private fun CoverPlate(name: String) {
    Box(
        modifier = Modifier
            .size(PLATE_SIZE)
            .clip(RoundedCornerShape(PilgrimCornerRadius.small))
            .background(pilgrimColors.parchment)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(text = PilgrimageCatalogModel.initial(name), style = pilgrimType.heading, color = pilgrimColors.stone)
    }
}

@Composable
private fun badgeTint(badge: InstallBadge): Color = when (badge) {
    InstallBadge.UPDATE_READY -> pilgrimColors.stone
    InstallBadge.ON_YOUR_PHONE -> pilgrimColors.moss
}

/** The line in body fog, centred, and "try again" in the button face, the one forced load. */
@Composable
private fun Unreachable(error: PilgrimageError, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(PilgrimSpacing.big),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        ) {
            Text(
                text = stringResource(PilgrimageCopy.line(error)),
                style = pilgrimType.body,
                color = pilgrimColors.fog,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(
                    text = stringResource(R.string.honor_overview_try_again),
                    style = pilgrimType.button,
                    color = pilgrimColors.stone,
                )
            }
        }
    }
}

/**
 * The catalog as a route over the Ways sheet. Close and a swipe return to
 * the Ways sheet; a row slides the catalog down and opens its route page.
 * Coming back on top as the route page closes reloads, the first time on
 * top excepted (iOS's `onAppear` after `hasAppearedOnce`).
 */
@Composable
fun PilgrimageCatalogSheet(
    onClosed: () -> Unit,
    onOpenRoute: (routeId: String) -> Unit,
    viewModel: PilgrimageCatalogViewModel = hiltViewModel(),
) {
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.resumed()
        onPauseOrDispose {}
    }
    HonorSheetHost(onDismissed = onClosed) { hideThen ->
        PilgrimageCatalogContent(
            catalog = catalog,
            state = state,
            units = units,
            onClose = { hideThen(onClosed) },
            onRetry = { viewModel.load(force = true) },
            onOpen = { routeId -> hideThen { onOpenRoute(routeId) } },
        )
    }
}

private val PLATE_SIZE = 44.dp

/** iOS's `.font(.system(size: 13))`: a fixed size, as a glyph doesn't follow the font scale. */
private val BADGE_SIZE = 13.dp

private const val SPARSE_ALPHA = 0.7f
