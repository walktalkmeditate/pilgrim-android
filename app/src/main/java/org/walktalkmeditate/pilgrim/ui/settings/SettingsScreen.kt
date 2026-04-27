// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Settings scaffold. Card-based layout matching iOS exactly: no nav
 * bar, a centered "Settings" title at the top of the scroll content,
 * then cards spaced evenly down the page. Stage 10-A lands this
 * scaffolding plus AtmosphereCard + the existing CollectiveStats
 * card; subsequent stages absorb Voice Guides + Soundscapes into
 * proper cards (Voice card / Bells & Soundscapes card).
 *
 * Navigation is funneled through a single [SettingsAction] channel —
 * the host (PilgrimNavHost) routes each action to a navController
 * call or system Intent dispatch. New cards extend [SettingsAction]
 * without changing this signature.
 */
@Composable
fun SettingsScreen(
    onAction: (SettingsAction) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val optIn by viewModel.optIn.collectAsStateWithLifecycle()
    val appearanceMode by viewModel.appearanceMode.collectAsStateWithLifecycle()
    // rememberLazyListState wraps a rememberSaveable internally — without
    // it, rotating the device would yank the user back to the top of
    // Settings instead of preserving their scroll position.
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { viewModel.fetchOnAppear() }
    Scaffold(
        // Stage 9.5-A: PilgrimNavHost's outer Scaffold already consumed
        // the system bar insets; nesting this Scaffold with the default
        // contentWindowInsets = WindowInsets.safeDrawing would re-apply
        // them and add a visible gap above the bottom nav + below the
        // status bar. Pass WindowInsets(0) so the inner Scaffold doesn't
        // double-count.
        contentWindowInsets = WindowInsets(0),
        containerColor = pilgrimColors.parchment,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = listState,
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = pilgrimType.heading,
                    color = pilgrimColors.ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 16.dp),
                    textAlign = TextAlign.Center,
                )
            }
            item {
                CollectiveStatsCard(stats = stats)
            }
            item {
                CollectiveOptInRow(checked = optIn, onCheckedChange = viewModel::setOptIn)
            }
            item {
                AtmosphereCard(
                    currentMode = appearanceMode,
                    onSelectMode = viewModel::setAppearanceMode,
                )
            }
            // Voice Guides + Soundscapes are landed here as
            // SettingNavRow stand-ins inside a shared settingsCard
            // wrapper so they share AtmosphereCard's 32dp content
            // indent. Both rows will be absorbed into proper cards
            // (Voice card in 10-D, Bells & Soundscapes in 10-B) at
            // which point this transitional wrapper goes away.
            //
            // The divider has no extra padding — each SettingNavRow
            // already has its own 4dp vertical content padding (and a
            // 48dp min-height), so the iOS-faithful gap of "row pad +
            // 1dp line + row pad" is correct without piling on more.
            item {
                Column(modifier = Modifier.fillMaxWidth().settingsCard()) {
                    SettingNavRow(
                        label = stringResource(R.string.settings_voice_guides_row),
                        detail = stringResource(R.string.settings_voice_guides_subtitle),
                        onClick = { onAction(SettingsAction.OpenVoiceGuides) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SettingsDivider()
                    SettingNavRow(
                        label = stringResource(R.string.settings_soundscapes_row),
                        detail = stringResource(R.string.settings_soundscapes_subtitle),
                        onClick = { onAction(SettingsAction.OpenSoundscapes) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
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
