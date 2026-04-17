// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.permissions.AppSettings
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks
import org.walktalkmeditate.pilgrim.permissions.PermissionsViewModel
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Location grant state. Progresses from [NotRequested] through the two
 * degraded paths ([CoarseOnly] / [NeedsSettings]) that Android's
 * permission dialog surfaces, to [Granted]. The degraded paths are the
 * common ways a user gets stuck without us noticing: picking
 * "Approximate" on the precision toggle (API 31+) or denying twice so
 * the system silently no-ops future prompts.
 */
private enum class LocationStatus { NotRequested, Granted, CoarseOnly, NeedsSettings }

@Composable
fun PermissionsScreen(
    onComplete: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    var locationStatus by remember {
        mutableStateOf(
            if (PermissionChecks.isFineLocationGranted(context)) LocationStatus.Granted
            else LocationStatus.NotRequested,
        )
    }
    var notificationGranted by remember { mutableStateOf(PermissionChecks.isNotificationGranted(context)) }
    var activityGranted by remember { mutableStateOf(PermissionChecks.isActivityRecognitionGranted(context)) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // A Settings trip or second-try prompt may have flipped any
                // of these. Recompute before the user sees stale state.
                locationStatus = when {
                    PermissionChecks.isFineLocationGranted(context) -> LocationStatus.Granted
                    // Downgrade-from-Granted: user just revoked or lowered to
                    // coarse-only in system Settings. Drop back to
                    // NotRequested so the Grant button reappears rather than
                    // lying that we still have precise location.
                    locationStatus == LocationStatus.Granted -> LocationStatus.NotRequested
                    locationStatus == LocationStatus.NotRequested -> LocationStatus.NotRequested
                    else -> locationStatus
                }
                notificationGranted = PermissionChecks.isNotificationGranted(context)
                activityGranted = PermissionChecks.isActivityRecognitionGranted(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        locationStatus = when {
            fine -> LocationStatus.Granted
            coarse -> LocationStatus.CoarseOnly
            else -> {
                // System auto-denied (permanent): launcher resolved instantly
                // and rationale is no longer available. Only a Settings trip
                // gets us out.
                val activity = context as? Activity
                val canPromptAgain = activity?.let {
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        it,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                } ?: true
                if (canPromptAgain) LocationStatus.NotRequested else LocationStatus.NeedsSettings
            }
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationGranted = granted }

    val activityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> activityGranted = granted }

    val canContinue = locationStatus == LocationStatus.Granted && notificationGranted

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PilgrimSpacing.big),
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = pilgrimType.displayMedium,
            color = pilgrimColors.ink,
        )
        Spacer(Modifier.height(PilgrimSpacing.small))
        Text(
            text = stringResource(R.string.onboarding_subtitle),
            style = pilgrimType.body,
            color = pilgrimColors.fog,
        )
        Spacer(Modifier.height(PilgrimSpacing.big))

        LocationPermissionCard(
            status = locationStatus,
            onRequestPrompt = {
                locationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
            onOpenSettings = { context.startActivity(AppSettings.openDetailsIntent(context)) },
        )
        Spacer(Modifier.height(PilgrimSpacing.normal))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionCard(
                title = stringResource(R.string.permission_notification_title),
                rationale = stringResource(R.string.permission_notification_rationale),
                granted = notificationGranted,
                onRequest = {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )
            Spacer(Modifier.height(PilgrimSpacing.normal))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PermissionCard(
                title = stringResource(R.string.permission_activity_title),
                rationale = stringResource(R.string.permission_activity_rationale),
                granted = activityGranted,
                optional = true,
                onRequest = {
                    @Suppress("InlinedApi")
                    activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                },
            )
        }
        Spacer(Modifier.height(PilgrimSpacing.breathingRoom))

        Button(
            onClick = {
                viewModel.markOnboardingComplete()
                onComplete()
            },
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.permissions_continue))
        }
    }
}

@Composable
private fun LocationPermissionCard(
    status: LocationStatus,
    onRequestPrompt: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val rationale = when (status) {
        LocationStatus.CoarseOnly -> stringResource(R.string.permission_location_coarse_only)
        LocationStatus.NeedsSettings -> stringResource(R.string.permission_location_needs_settings)
        else -> stringResource(R.string.permission_location_rationale)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.parchmentSecondary,
            contentColor = pilgrimColors.ink,
        ),
    ) {
        Column(modifier = Modifier.padding(PilgrimSpacing.normal)) {
            Text(
                text = stringResource(R.string.permission_location_title),
                style = pilgrimType.heading,
            )
            Spacer(Modifier.height(PilgrimSpacing.small))
            Text(text = rationale, style = pilgrimType.body, color = pilgrimColors.fog)
            Spacer(Modifier.height(PilgrimSpacing.normal))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                when (status) {
                    LocationStatus.Granted -> Text(
                        text = stringResource(R.string.permissions_granted_label),
                        style = pilgrimType.caption,
                        color = pilgrimColors.moss,
                    )
                    LocationStatus.CoarseOnly, LocationStatus.NeedsSettings -> Button(onClick = onOpenSettings) {
                        Text(stringResource(R.string.permissions_open_settings))
                    }
                    LocationStatus.NotRequested -> Button(onClick = onRequestPrompt) {
                        Text(stringResource(R.string.permissions_grant))
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    rationale: String,
    granted: Boolean,
    onRequest: () -> Unit,
    optional: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.parchmentSecondary,
            contentColor = pilgrimColors.ink,
        ),
    ) {
        Column(modifier = Modifier.padding(PilgrimSpacing.normal)) {
            Text(text = title, style = pilgrimType.heading)
            Spacer(Modifier.height(PilgrimSpacing.small))
            Text(
                text = rationale,
                style = pilgrimType.body,
                color = pilgrimColors.fog,
            )
            Spacer(Modifier.height(PilgrimSpacing.normal))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                when {
                    granted -> Text(
                        text = stringResource(R.string.permissions_granted_label),
                        style = pilgrimType.caption,
                        color = pilgrimColors.moss,
                    )
                    optional -> TextButton(onClick = onRequest) {
                        Text(stringResource(R.string.permissions_grant))
                    }
                    else -> Button(onClick = onRequest) {
                        Text(stringResource(R.string.permissions_grant))
                    }
                }
            }
        }
    }
}
