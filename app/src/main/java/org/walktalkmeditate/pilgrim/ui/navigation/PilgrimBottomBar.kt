// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

/**
 * The 3-tab bottom navigation: Path / Journal / Settings.
 *
 * Mirrors iOS `MainTabView`'s tab order, label strings, and tint
 * (`.tint(.stone)` → selected tab uses `pilgrimColors.stone`).
 *
 * `containerColor = parchmentSecondary` provides visual separation
 * from the screen content above (which renders on `parchment`).
 * `tonalElevation = 0.dp` lets the color difference do the work
 * instead of M3's default tonal shadow.
 */
@Composable
fun PilgrimBottomBar(
    currentRoute: String?,
    onSelectTab: (String) -> Unit,
) {
    NavigationBar(
        containerColor = pilgrimColors.parchmentSecondary,
        contentColor = pilgrimColors.stone,
        tonalElevation = 0.dp,
    ) {
        TabItem(
            route = Routes.PATH,
            currentRoute = currentRoute,
            label = R.string.tab_path,
            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
            onSelect = onSelectTab,
        )
        TabItem(
            route = Routes.HOME,
            currentRoute = currentRoute,
            label = R.string.tab_journal,
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            onSelect = onSelectTab,
        )
        TabItem(
            route = Routes.SETTINGS,
            currentRoute = currentRoute,
            label = R.string.tab_settings,
            icon = Icons.Outlined.Settings,
            onSelect = onSelectTab,
        )
    }
}

@Composable
private fun RowScope.TabItem(
    route: String,
    currentRoute: String?,
    @StringRes label: Int,
    icon: ImageVector,
    onSelect: (String) -> Unit,
) {
    NavigationBarItem(
        selected = currentRoute == route,
        onClick = { onSelect(route) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(stringResource(label)) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = pilgrimColors.stone,
            unselectedIconColor = pilgrimColors.fog,
            selectedTextColor = pilgrimColors.stone,
            unselectedTextColor = pilgrimColors.fog,
            indicatorColor = pilgrimColors.parchment,
        ),
    )
}
