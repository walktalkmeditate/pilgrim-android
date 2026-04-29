// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.settings.CardHeader
import org.walktalkmeditate.pilgrim.ui.settings.SettingNavRow
import org.walktalkmeditate.pilgrim.ui.settings.SettingsAction
import org.walktalkmeditate.pilgrim.ui.settings.settingsCard

@Composable
fun DataCard(
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
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
    }
}
