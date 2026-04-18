// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.isInProgress
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Circular record button with an animated radial level-meter ring and
 * a transient error banner. Lives on ActiveWalkScreen between the
 * StatRow and the pause/finish Controls.
 *
 * The mic-permission launcher is scoped to this composable rather
 * than the ViewModel so the ViewModel stays platform-agnostic (a
 * future Wear OS control could use a different permission idiom).
 */
@Composable
fun RecordControl(
    walkState: WalkState,
    recorderState: VoiceRecorderUiState,
    audioLevel: Float,
    recordingsCount: Int,
    onToggle: () -> Unit,
    onPermissionDenied: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val enabled = walkState.isInProgress
    val isRecording = recorderState is VoiceRecorderUiState.Recording

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onToggle() else onPermissionDenied()
    }

    // Transient banner auto-dismisses after 4s. Keyed on the error
    // instance so re-emitting the same kind resets the timer.
    val err = recorderState as? VoiceRecorderUiState.Error
    LaunchedEffect(err) {
        if (err != null && err.kind != VoiceRecorderUiState.Kind.Cancelled) {
            delay(ERROR_BANNER_DURATION_MS)
            onDismissError()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (err != null && err.kind != VoiceRecorderUiState.Kind.Cancelled) {
            ErrorBanner(message = err.message, onDismiss = onDismissError)
            Spacer(Modifier.height(PilgrimSpacing.normal))
        }

        // Ring + button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(BUTTON_PLUS_RING_DP.dp),
        ) {
            // Animated level ring: 0f → no ring, 1f → full 24dp extra radius.
            val ringFraction by animateFloatAsState(
                targetValue = if (isRecording) audioLevel.coerceIn(0f, 1f) else 0f,
                animationSpec = tween(durationMillis = LEVEL_RING_TWEEN_MS),
                label = "recordRingRadius",
            )
            if (isRecording) {
                val ringSize = BUTTON_DP + (ringFraction * RING_MAX_EXTRA_DP * 2).toInt()
                Box(
                    modifier = Modifier
                        .size(ringSize.dp)
                        .clip(CircleShape)
                        .background(pilgrimColors.rust.copy(alpha = 0.2f)),
                )
            }
            // The button itself
            Box(
                modifier = Modifier
                    .size(BUTTON_DP.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isRecording -> pilgrimColors.rust
                            enabled -> pilgrimColors.stone
                            else -> pilgrimColors.fog
                        },
                    )
                    .then(
                        if (enabled) Modifier.clickable {
                            if (isRecording || PermissionChecks.isMicrophoneGranted(context)) {
                                onToggle()
                            } else {
                                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isRecording) "STOP" else "REC",
                    style = pilgrimType.statLabel,
                    color = pilgrimColors.parchment,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.height(PilgrimSpacing.small))
        Text(
            text = "$recordingsCount recordings saved",
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.rust.copy(alpha = 0.15f),
            contentColor = pilgrimColors.ink,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PilgrimSpacing.normal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = message,
                style = pilgrimType.body,
                color = pilgrimColors.ink,
                modifier = Modifier.padding(end = PilgrimSpacing.normal),
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = pilgrimColors.rust)
            }
        }
    }
}

private const val BUTTON_DP = 56
private const val RING_MAX_EXTRA_DP = 24
private const val BUTTON_PLUS_RING_DP = BUTTON_DP + RING_MAX_EXTRA_DP * 2
private const val LEVEL_RING_TWEEN_MS = 80
private const val ERROR_BANNER_DURATION_MS = 4_000L
