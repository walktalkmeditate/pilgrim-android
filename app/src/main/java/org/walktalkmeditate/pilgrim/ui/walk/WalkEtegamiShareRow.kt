// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing

/**
 * Stage 7-D: Share + Save action row slotted below WalkEtegamiCard.
 *
 * Mirrors iOS `WalkSharingButtons` image-share row — two compact
 * outlined buttons, labeled text, disabled-during-work via
 * [busy]. Snackbar feedback is wired by the caller via a VM-level
 * event SharedFlow (see `WalkSummaryScreen`); this row just
 * renders the buttons.
 *
 * On API 28 only, Save-to-Photos requires WRITE_EXTERNAL_STORAGE.
 * The permission launcher is `remember`-pinned on its contract per
 * the Stage 7-A lesson about `rememberLauncherForActivityResult` +
 * DisposableEffect contract identity — without the remember wrapper,
 * a parent recomposition unregisters + re-registers the launcher
 * mid-request and drops the result.
 */
@Composable
fun WalkEtegamiShareRow(
    busyAction: WalkSummaryViewModel.EtegamiBusyAction?,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onSavePermissionDenied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val anyBusy = busyAction != null
    val sharing = busyAction == WalkSummaryViewModel.EtegamiBusyAction.Share
    val saving = busyAction == WalkSummaryViewModel.EtegamiBusyAction.Save
    // Hoisted stringResource reads — lint's LocalContextGetResourceValueCall
    // fires when context.getString is called from inside a Modifier
    // lambda (which isn't a Composable context).
    val shareDesc = stringResource(R.string.etegami_share_button_content_description)
    val saveDesc = stringResource(R.string.etegami_save_button_content_description)

    val legacyPermissionContract = remember { ActivityResultContracts.RequestPermission() }
    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = legacyPermissionContract,
    ) { granted ->
        // API 28 only: if the user denies the WRITE_EXTERNAL_STORAGE
        // request, surface a snackbar via [onSavePermissionDenied]
        // rather than silently dropping the tap.
        if (granted) onSave() else onSavePermissionDenied()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = PilgrimSpacing.small),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        // Share button: spinner only when THIS action is in-flight.
        // The Save button stays disabled-idle (icon + text, greyed)
        // so the user sees exactly which action is running rather
        // than both buttons spinning simultaneously.
        OutlinedButton(
            onClick = { if (!anyBusy) onShare() },
            enabled = !anyBusy,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = shareDesc },
        ) {
            if (sharing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Outlined.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(PilgrimSpacing.small))
                Text(stringResource(R.string.etegami_share_button))
            }
        }
        OutlinedButton(
            onClick = {
                if (anyBusy) return@OutlinedButton
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    onSave()
                } else {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) onSave()
                    else legacyPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            },
            enabled = !anyBusy,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = saveDesc },
        ) {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(PilgrimSpacing.small))
                Text(stringResource(R.string.etegami_save_button))
            }
        }
    }
}
