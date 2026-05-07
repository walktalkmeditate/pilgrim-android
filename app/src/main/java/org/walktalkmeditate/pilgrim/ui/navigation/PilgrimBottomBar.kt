// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS-parity floating pill tab bar. Three tabs (Path / Journal /
 * Settings) inside a parchmentSecondary capsule, padded inward from
 * the screen edges. The selected tab gets a lighter parchment pill
 * behind its icon + label. No M3 NavigationBar — that surface sits
 * flush edge-to-edge, which clashes with the iOS floating-capsule
 * idiom shown on the Home/Settings screens.
 */
@Composable
fun PilgrimBottomBar(
    currentRoute: String?,
    onSelectTab: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 48.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = pilgrimColors.parchment,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillTabItem(
                    route = Routes.PATH,
                    currentRoute = currentRoute,
                    label = R.string.tab_path,
                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    onSelect = onSelectTab,
                )
                PillTabItem(
                    route = Routes.HOME,
                    currentRoute = currentRoute,
                    label = R.string.tab_journal,
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    onSelect = onSelectTab,
                )
                PillTabItem(
                    route = Routes.SETTINGS,
                    currentRoute = currentRoute,
                    label = R.string.tab_settings,
                    icon = Icons.Outlined.Settings,
                    onSelect = onSelectTab,
                )
            }
        }
    }
}

@Composable
private fun RowScope.PillTabItem(
    route: String,
    currentRoute: String?,
    @StringRes label: Int,
    icon: ImageVector,
    onSelect: (String) -> Unit,
) {
    val selected = currentRoute == route
    val interactionSource = remember { MutableInteractionSource() }
    val labelText = stringResource(label)
    val tint = if (selected) pilgrimColors.stone else pilgrimColors.ink
    val pillBg = if (selected) pilgrimColors.parchmentSecondary else androidx.compose.ui.graphics.Color.Transparent
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(CircleShape)
            .background(pillBg)
            .selectable(
                selected = selected,
                onClick = { onSelect(route) },
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            )
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = labelText,
            style = pilgrimType.caption,
            color = tint,
        )
    }
}
