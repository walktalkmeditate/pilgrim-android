// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.settings.CardHeader
import org.walktalkmeditate.pilgrim.ui.settings.SettingNavRow
import org.walktalkmeditate.pilgrim.ui.settings.SettingsAction
import org.walktalkmeditate.pilgrim.ui.settings.settingsCard

/**
 * iOS `DataCard` (shared-walk spec S4 §2, offline-maps spec D C4 §3.1):
 * "Export & Import", then "Ways" with its `N way(s) · N.N MB` detail, then
 * "Maps" with "none saved" or "<route> · N MB". [showsWays] and [showsMaps]
 * are false with the release flag off, and while a walk or its Honor step
 * is pending (an R6 addition); otherwise the rows show, `0 ways · 0.0 MB`
 * and "none saved" included. Each detail is blank until its first read
 * lands, as iOS's starts `""`.
 */
@Composable
fun DataCard(
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
    showsWays: Boolean = false,
    waysTotals: WaysTotals? = null,
    showsMaps: Boolean = false,
    mapsDetail: OfflineMapsUiState = OfflineMapsUiState.Loading,
) {
    Column(modifier = modifier.fillMaxWidth().settingsCard()) {
        CardHeader(
            title = stringResource(R.string.settings_data_title),
            subtitle = stringResource(R.string.settings_data_subtitle),
        )
        SettingNavRow(
            label = stringResource(R.string.settings_data_export_import),
            modifier = Modifier.fillMaxWidth(),
            onClick = { onAction(SettingsAction.OpenExportImport) },
        )
        if (showsWays) {
            SettingNavRow(
                label = stringResource(R.string.settings_data_ways),
                modifier = Modifier.fillMaxWidth(),
                detail = waysTotals?.let { WaysListModel.rowDetail(LocalResources.current, it) }.orEmpty(),
                onClick = { onAction(SettingsAction.OpenWays) },
            )
        }
        if (showsMaps) {
            val resources = LocalResources.current
            SettingNavRow(
                label = stringResource(R.string.settings_data_maps),
                modifier = Modifier.fillMaxWidth(),
                detail = when (mapsDetail) {
                    OfflineMapsUiState.Loading -> ""
                    OfflineMapsUiState.Empty -> OfflineMapsModel.rowDetail(resources, saved = null)
                    is OfflineMapsUiState.Saved -> OfflineMapsModel.rowDetail(resources, mapsDetail)
                },
                onClick = { onAction(SettingsAction.OpenMaps) },
            )
        }
    }
}
