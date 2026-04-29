// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.permissions

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.permissions.PermissionAskedStore
import org.walktalkmeditate.pilgrim.permissions.PermissionStatus
import org.walktalkmeditate.pilgrim.ui.settings.CardHeader
import org.walktalkmeditate.pilgrim.ui.settings.SettingsAction
import org.walktalkmeditate.pilgrim.ui.settings.settingsCard
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS-parity Permissions card. Three rows (Location / Microphone /
 * Motion) with per-row state dot + trailing action. Refreshes on
 * `Lifecycle.Event.ON_RESUME` so a permission revoked from system
 * Settings reflects without restart.
 */
@Composable
fun PermissionsCard(
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PermissionsCardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Hoist contracts so registry identity is stable across recompose;
    // Stage 7-A photo-picker race precedent.
    val locationContract = remember { ActivityResultContracts.RequestPermission() }
    val locationLauncher = rememberLauncherForActivityResult(locationContract) {
        viewModel.onPermissionResult(PermissionAskedStore.Key.Location)
    }

    val microphoneContract = remember { ActivityResultContracts.RequestPermission() }
    val microphoneLauncher = rememberLauncherForActivityResult(microphoneContract) {
        viewModel.onPermissionResult(PermissionAskedStore.Key.Microphone)
    }

    val motionContract = remember { ActivityResultContracts.RequestPermission() }
    val motionLauncher = rememberLauncherForActivityResult(motionContract) {
        viewModel.onPermissionResult(PermissionAskedStore.Key.Motion)
    }

    Column(
        modifier = modifier.fillMaxWidth().settingsCard(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardHeader(
            title = stringResource(R.string.settings_permissions_title),
            subtitle = stringResource(R.string.settings_permissions_subtitle),
        )

        PermissionRow(
            label = stringResource(R.string.settings_permissions_location_label),
            caption = stringResource(R.string.settings_permissions_location_caption),
            status = state.location,
            onGrant = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
            onOpenSettings = { onAction(SettingsAction.OpenAppPermissionSettings) },
        )
        PermissionRow(
            label = stringResource(R.string.settings_permissions_microphone_label),
            caption = stringResource(R.string.settings_permissions_microphone_caption),
            status = state.microphone,
            onGrant = { microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            onOpenSettings = { onAction(SettingsAction.OpenAppPermissionSettings) },
        )
        PermissionRow(
            label = stringResource(R.string.settings_permissions_motion_label),
            caption = stringResource(R.string.settings_permissions_motion_caption),
            status = state.motion,
            onGrant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    motionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
                // Pre-API 29 the row should always show as Granted via
                // PermissionChecks.isActivityRecognitionGranted; this
                // branch is unreachable in that case. No-op for safety.
            },
            onOpenSettings = { onAction(SettingsAction.OpenAppPermissionSettings) },
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    caption: String,
    status: PermissionStatus,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor(status)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = pilgrimType.body, color = pilgrimColors.ink)
            Text(caption, style = pilgrimType.caption, color = pilgrimColors.fog)
        }
        when (status) {
            PermissionStatus.Granted -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(
                    R.string.settings_permissions_status_granted_content_description,
                ),
                tint = pilgrimColors.moss,
                modifier = Modifier.size(20.dp),
            )
            PermissionStatus.NotDetermined -> TextButton(onClick = onGrant) {
                Text(
                    text = stringResource(R.string.settings_permissions_action_grant),
                    style = pilgrimType.button,
                    color = pilgrimColors.stone,
                )
            }
            PermissionStatus.Denied -> TextButton(onClick = onOpenSettings) {
                Text(
                    text = stringResource(R.string.settings_permissions_action_settings),
                    style = pilgrimType.button,
                    color = pilgrimColors.stone,
                )
            }
            PermissionStatus.Restricted -> Text(
                text = stringResource(R.string.settings_permissions_status_restricted),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}

@Composable
private fun dotColor(status: PermissionStatus): Color = when (status) {
    PermissionStatus.Granted -> pilgrimColors.moss
    PermissionStatus.NotDetermined -> pilgrimColors.dawn
    PermissionStatus.Denied -> pilgrimColors.rust
    PermissionStatus.Restricted -> pilgrimColors.fog
}
