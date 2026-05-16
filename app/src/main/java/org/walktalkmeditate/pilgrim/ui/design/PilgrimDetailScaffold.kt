// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Shared chrome for every pushed Settings detail screen. Mirrors the
 * iOS detail pattern: an inline nav bar with the title CENTERED
 * (`ToolbarItem(placement: .principal)` on iOS) over a parchment
 * background, with the platform back affordance leading.
 *
 * iOS reference: the repeated
 * `.navigationBarTitleDisplayMode(.inline)` +
 * `.toolbar { ToolbarItem(.principal) { Text(title) } }` block in
 * `AppearanceView.swift` / `AboutView.swift` etc. (heading type, ink).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PilgrimDetailScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backContentDescription: String =
        stringResource(R.string.settings_back_content_description),
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = pilgrimColors.parchment,
        // The nav-host's outer Scaffold already consumes system-bar
        // insets; without this, a nested Scaffold re-applies the top
        // inset and the content sits with a double-counted gap.
        contentWindowInsets = WindowInsets(0),
        snackbarHost = snackbarHost,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = title,
                        style = pilgrimType.heading,
                        color = pilgrimColors.ink,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            // iOS uses a chevron back, not a Material arrow.
                            painter = painterResource(R.drawable.ic_sf_chevron_left),
                            contentDescription = backContentDescription,
                            tint = pilgrimColors.ink,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = pilgrimColors.parchment,
                ),
            )
        },
        content = content,
    )
}
