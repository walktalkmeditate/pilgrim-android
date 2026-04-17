// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.onboarding

import android.Manifest
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks
import org.walktalkmeditate.pilgrim.permissions.PermissionsViewModel
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

@Composable
fun PermissionsScreen(
    onComplete: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    // Refresh live-check state whenever the screen resumes (user may
    // return from system Settings with permissions flipped).
    var locationGranted by remember { mutableStateOf(PermissionChecks.isFineLocationGranted(context)) }
    var notificationGranted by remember { mutableStateOf(PermissionChecks.isNotificationGranted(context)) }
    var activityGranted by remember { mutableStateOf(PermissionChecks.isActivityRecognitionGranted(context)) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationGranted = PermissionChecks.isFineLocationGranted(context)
                notificationGranted = PermissionChecks.isNotificationGranted(context)
                activityGranted = PermissionChecks.isActivityRecognitionGranted(context)
            }
        }
        lifecycle.addObserver(observer)
    }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationGranted = granted }

    val activityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> activityGranted = granted }

    val canContinue = locationGranted && notificationGranted

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

        PermissionCard(
            title = stringResource(R.string.permission_location_title),
            rationale = stringResource(R.string.permission_location_rationale),
            granted = locationGranted,
            onRequest = {
                locationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
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

        PermissionCard(
            title = stringResource(R.string.permission_activity_title),
            rationale = stringResource(R.string.permission_activity_rationale),
            granted = activityGranted,
            optional = true,
            onRequest = {
                activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            },
        )
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
