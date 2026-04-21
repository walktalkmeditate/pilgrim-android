// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

/**
 * Settings scaffold. Stage 5-D ships it with a single "Voice guides"
 * row; Phase 10 will add more rows (weather opt-in, haptics,
 * hemisphere override, export/import). Minimal M3 styling to match
 * the app's soft, text-forward aesthetic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenVoiceGuides: () -> Unit,
    onOpenSoundscapes: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(
                                R.string.settings_back_content_description,
                            ),
                        )
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
