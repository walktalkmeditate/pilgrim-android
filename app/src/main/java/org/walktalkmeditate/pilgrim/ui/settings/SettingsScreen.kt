// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

/**
 * Settings scaffold. Currently surfaces audio-catalog pickers (voice
 * guides + soundscapes); Phase 10 will add more rows (weather opt-in,
 * haptics, hemisphere override, export/import). Minimal M3 styling to
 * match the app's soft, text-forward aesthetic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenVoiceGuides: () -> Unit,
    onOpenSoundscapes: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val optIn by viewModel.optIn.collectAsStateWithLifecycle()
    val appearanceMode by viewModel.appearanceMode.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.fetchOnAppear() }
    Scaffold(
        // Stage 9.5-A: PilgrimNavHost's outer Scaffold already consumed
        // the system bar insets; nesting this Scaffold with the default
        // contentWindowInsets = WindowInsets.safeDrawing would re-apply
        // them and add a visible gap above the bottom nav + below the
        // status bar. Pass WindowInsets(0) so the inner Scaffold doesn't
        // double-count.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = stringResource(
                                    R.string.settings_back_content_description,
                                ),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pilgrimColors.parchment,
                    titleContentColor = pilgrimColors.ink,
                    navigationIconContentColor = pilgrimColors.ink,
                ),
            )
        },
        containerColor = pilgrimColors.parchment,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(top = 8.dp),
        ) {
            AtmosphereCard(
                currentMode = appearanceMode,
                onSelectMode = viewModel::setAppearanceMode,
            )
            Spacer(Modifier.height(12.dp))
            CollectiveStatsCard(stats = stats)
            CollectiveOptInRow(checked = optIn, onCheckedChange = viewModel::setOptIn)
            SettingsRow(
                icon = Icons.Default.GraphicEq,
                title = stringResource(R.string.settings_voice_guides_row),
                subtitle = stringResource(R.string.settings_voice_guides_subtitle),
                onClick = onOpenVoiceGuides,
            )
            SettingsRow(
                icon = Icons.Default.Spa,
                title = stringResource(R.string.settings_soundscapes_row),
                subtitle = stringResource(R.string.settings_soundscapes_subtitle),
                onClick = onOpenSoundscapes,
            )
        }
    }
}

@Composable
private fun CollectiveOptInRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.collective_opt_in_label)) },
        supportingContent = { Text(stringResource(R.string.collective_opt_in_description)) },
        leadingContent = { Icon(Icons.Default.People, contentDescription = null) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = pilgrimColors.parchment,
                    checkedTrackColor = pilgrimColors.moss,
                    uncheckedThumbColor = pilgrimColors.fog,
                    uncheckedTrackColor = pilgrimColors.parchmentTertiary,
                ),
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = pilgrimColors.ink,
            supportingColor = pilgrimColors.fog,
            leadingIconColor = pilgrimColors.ink,
        ),
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = pilgrimColors.ink,
            supportingColor = pilgrimColors.fog,
            leadingIconColor = pilgrimColors.ink,
            trailingIconColor = pilgrimColors.fog,
        ),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
