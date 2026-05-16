// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode
import org.walktalkmeditate.pilgrim.ui.design.PilgrimDetailScaffold
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Settings → Appearance detail. iOS parity v1.6.0 — picker no longer
 * fits 4 modes (System / Light / Dark / Constellation) cleanly inside
 * the AtmosphereCard segmented row, so the picker is replaced by a
 * navigation row + dedicated detail screen with icon, label, and
 * description per mode.
 *
 * Mirrors `pilgrim-ios/Pilgrim/Scenes/Settings/AppearanceView.swift`.
 */
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val current by viewModel.appearanceMode.collectAsState()
    PilgrimDetailScaffold(
        title = stringResource(R.string.settings_appearance_screen_title),
        onBack = onBack,
        modifier = modifier,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(MODES) { entry ->
                AppearanceRow(
                    icon = entry.icon,
                    label = stringResource(entry.labelRes),
                    description = stringResource(entry.descriptionRes),
                    selected = current == entry.mode,
                    onClick = { viewModel.setAppearanceMode(entry.mode) },
                )
            }
        }
    }
}

private data class ModeEntry(
    val mode: AppearanceMode,
    val icon: ImageVector,
    val labelRes: Int,
    val descriptionRes: Int,
)

private val MODES: List<ModeEntry> = listOf(
    ModeEntry(
        AppearanceMode.System,
        Icons.Filled.Brightness6,
        R.string.settings_appearance_auto,
        R.string.settings_appearance_auto_description,
    ),
    ModeEntry(
        AppearanceMode.Light,
        Icons.Filled.LightMode,
        R.string.settings_appearance_light,
        R.string.settings_appearance_light_description,
    ),
    ModeEntry(
        AppearanceMode.Dark,
        Icons.Filled.Brightness4,
        R.string.settings_appearance_dark,
        R.string.settings_appearance_dark_description,
    ),
    ModeEntry(
        AppearanceMode.Constellation,
        Icons.Filled.AutoAwesome,
        R.string.settings_appearance_constellation,
        R.string.settings_appearance_constellation_description,
    ),
)

@Composable
private fun AppearanceRow(
    icon: ImageVector,
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pilgrimColors.parchmentSecondary)
            .clickable(role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = pilgrimColors.stone,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = pilgrimType.body,
                color = pilgrimColors.ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_sf_checkmark),
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

