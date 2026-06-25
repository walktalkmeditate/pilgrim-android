// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.sounds.LocalSoundsEnabled
import org.walktalkmeditate.pilgrim.permissions.AppSettings
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks
import org.walktalkmeditate.pilgrim.permissions.PermissionRitual
import org.walktalkmeditate.pilgrim.permissions.PermissionsViewModel
import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

// #43 grant ritual: the granted card springs its checkmark to 1.15× and back.
private const val GRANT_PULSE_SCALE = 1.15f

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
    val haptic = LocalHapticFeedback.current
    // The activity-result launcher callbacks are remembered, so they capture
    // these CompositionLocals at first composition; rememberUpdatedState lets
    // them read the live value when a grant actually lands.
    val soundsEnabled = rememberUpdatedState(LocalSoundsEnabled.current)
    val reduceMotion = rememberUpdatedState(LocalReduceMotion.current)
    val locationPulse by viewModel.locationPulse.collectAsStateWithLifecycle()
    val microphonePulse by viewModel.microphonePulse.collectAsStateWithLifecycle()
    val activityPulse by viewModel.activityPulse.collectAsStateWithLifecycle()
    val celebrate: (PermissionRitual.Permission) -> Unit = { permission ->
        viewModel.celebrateGrant(
            permission = permission,
            soundsEnabled = soundsEnabled.value,
            reduceMotion = reduceMotion.value,
            onGrantHaptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
        )
    }

    var locationStatus by remember {
        mutableStateOf(
            if (PermissionChecks.isFineLocationGranted(context)) LocationStatus.Granted
            else LocationStatus.NotRequested,
        )
    }
    var notificationGranted by remember { mutableStateOf(PermissionChecks.isNotificationGranted(context)) }
    var microphoneGranted by remember { mutableStateOf(PermissionChecks.isMicrophoneGranted(context)) }
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
                microphoneGranted = PermissionChecks.isMicrophoneGranted(context)
                activityGranted = PermissionChecks.isActivityRecognitionGranted(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Hoist the contracts so their reference identity is stable across the
    // (now pulse-driven) recompositions of this screen — rememberLauncher's
    // registration keys on the contract instance, so a fresh one per recompose
    // would re-register and could orphan an in-flight permission dialog.
    val multiPermissionContract = remember { ActivityResultContracts.RequestMultiplePermissions() }
    val singlePermissionContract = remember { ActivityResultContracts.RequestPermission() }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = multiPermissionContract,
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
        if (fine) celebrate(PermissionRitual.Permission.Location)
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = singlePermissionContract,
    ) { granted -> notificationGranted = granted }

    val microphoneLauncher = rememberLauncherForActivityResult(
        contract = singlePermissionContract,
    ) { granted ->
        microphoneGranted = granted
        if (granted) celebrate(PermissionRitual.Permission.Microphone)
    }

    val activityLauncher = rememberLauncherForActivityResult(
        contract = singlePermissionContract,
    ) { granted ->
        activityGranted = granted
        if (granted) celebrate(PermissionRitual.Permission.Activity)
    }

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
            pulse = locationPulse,
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

        // Required permissions grouped first (Location above, then the
        // ongoing-notification), then the optional ones — required vs
        // optional is conveyed by the "(optional)" label, not by the
        // action style (every card uses the same "Allow" affordance).
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
            title = stringResource(R.string.permission_microphone_title),
            rationale = stringResource(R.string.permission_microphone_rationale),
            granted = microphoneGranted,
            pulse = microphonePulse,
            optional = true,
            onRequest = { microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )
        Spacer(Modifier.height(PilgrimSpacing.normal))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PermissionCard(
                title = stringResource(R.string.permission_activity_title),
                rationale = stringResource(R.string.permission_activity_rationale),
                granted = activityGranted,
                pulse = activityPulse,
                optional = true,
                onRequest = {
                    @Suppress("InlinedApi")
                    activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                },
            )
        }
        Spacer(Modifier.height(PilgrimSpacing.normal))

        // Long backgrounded walks are force-killed by OEM power managers
        // (OnePlus/OxygenOS, Xiaomi/MIUI, Samsung) unless the app is
        // battery-optimization-exempt — the single biggest determinant
        // of 45-90min walk survival (see CLAUDE.md "Long-session
        // reliability"). The card self-hides once the user is exempt or
        // has answered the prompt; it never gates [canContinue], so
        // onboarding is never blocked (soft / skippable).
        BatteryExemptionCard(viewModel = viewModel)
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
    pulse: Boolean = false,
) {
    val rationale = when (status) {
        LocationStatus.CoarseOnly -> stringResource(R.string.permission_location_coarse_only)
        LocationStatus.NeedsSettings -> stringResource(R.string.permission_location_needs_settings)
        else -> stringResource(R.string.permission_location_rationale)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status == LocationStatus.Granted) {
                pilgrimColors.moss.copy(alpha = 0.1f)
            } else {
                pilgrimColors.parchmentSecondary
            },
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
                    LocationStatus.Granted -> GrantedLabel(pulse = pulse)
                    LocationStatus.CoarseOnly, LocationStatus.NeedsSettings ->
                        TextButton(onClick = onOpenSettings) {
                            Text(stringResource(R.string.permissions_open_settings))
                        }
                    LocationStatus.NotRequested -> TextButton(onClick = onRequestPrompt) {
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
    pulse: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            // iOS: granted cards tint moss @ 0.1, else parchmentSecondary.
            containerColor = if (granted) {
                pilgrimColors.moss.copy(alpha = 0.1f)
            } else {
                pilgrimColors.parchmentSecondary
            },
            contentColor = pilgrimColors.ink,
        ),
    ) {
        Column(modifier = Modifier.padding(PilgrimSpacing.normal)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, style = pilgrimType.heading)
                if (optional) {
                    Spacer(Modifier.width(PilgrimSpacing.small))
                    // iOS: "(optional)" beside the title for non-required.
                    Text(
                        text = stringResource(R.string.permissions_optional),
                        style = pilgrimType.caption,
                        color = pilgrimColors.fog,
                    )
                }
            }
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
                // One consistent action for every card (required and
                // optional alike) — the "(optional)" label beside the
                // title carries the distinction, not the button weight.
                if (granted) {
                    GrantedLabel(pulse = pulse)
                } else {
                    TextButton(onClick = onRequest) {
                        Text(stringResource(R.string.permissions_grant))
                    }
                }
            }
        }
    }
}

/**
 * The "Granted" label, springing its scale when [pulse] flips true (#43 grant
 * ritual). A single bool drives an `animateFloatAsState` — the view-model
 * flips it on then off after 0.2s so the spring grows to 1.15× and settles
 * back to 1.0×. Reduce-motion keeps [pulse] false (the VM never sets it), so
 * the label simply appears.
 */
@Composable
private fun GrantedLabel(pulse: Boolean) {
    val scale by animateFloatAsState(
        targetValue = if (pulse) GRANT_PULSE_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "grant-pulse",
    )
    Text(
        text = stringResource(R.string.permissions_granted_label),
        style = pilgrimType.caption,
        color = pilgrimColors.moss,
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    )
}
