// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ModeEdit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.settings.CardHeader
import org.walktalkmeditate.pilgrim.ui.settings.SettingNavRow
import org.walktalkmeditate.pilgrim.ui.settings.SettingsAction
import org.walktalkmeditate.pilgrim.ui.settings.settingsCard

@Composable
fun ConnectCard(
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().settingsCard(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CardHeader(
            title = stringResource(R.string.settings_connect_title),
            subtitle = stringResource(R.string.settings_connect_subtitle),
        )
        SettingNavRow(
            label = stringResource(R.string.settings_connect_podcast),
            leadingIcon = Icons.Filled.GraphicEq,
            modifier = Modifier.fillMaxWidth(),
            onClick = { onAction(SettingsAction.OpenPodcast) },
        )
        SettingNavRow(
            label = stringResource(R.string.settings_connect_feedback),
            leadingIcon = Icons.Outlined.ModeEdit,
            modifier = Modifier.fillMaxWidth(),
            onClick = { onAction(SettingsAction.OpenFeedback) },
        )
        SettingNavRow(
            label = stringResource(R.string.settings_connect_rate),
            leadingIcon = Icons.Outlined.FavoriteBorder,
            external = true,
            modifier = Modifier.fillMaxWidth(),
            onClick = { onAction(SettingsAction.OpenPlayStoreReview) },
        )
        SettingNavRow(
            label = stringResource(R.string.settings_connect_share),
            leadingIcon = Icons.Outlined.Share,
            modifier = Modifier.fillMaxWidth(),
            onClick = { onAction(SettingsAction.SharePilgrim) },
        )
    }
}
