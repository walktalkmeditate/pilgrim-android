// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.permissions.BatteryExemption
import org.walktalkmeditate.pilgrim.permissions.PermissionsViewModel
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Home-screen surface asking the user to whitelist Pilgrim from battery
 * optimization. Hidden once the user has either granted the exemption
 * (live PowerManager check) or has previously dismissed/answered the
 * prompt (persisted via [PermissionsViewModel.batteryExemptionAsked]).
 *
 * The DataStore-backed flag is the visibility gate rather than an
 * ephemeral local state so a process kill after "Later" doesn't bring
 * the card back, overriding the user's explicit dismiss.
 *
 * Accepts the ViewModel as a required parameter — not a default
 * `hiltViewModel()` — so that all onboarding surfaces share the same
 * activity-scoped instance that [org.walktalkmeditate.pilgrim.ui.navigation.PilgrimNavHost]
 * hoists from its entry point.
 */
@Composable
fun BatteryExemptionCard(
    viewModel: PermissionsViewModel,
) {
    val context = LocalContext.current
    val asked by viewModel.batteryExemptionAsked.collectAsState()
    var exemptNow by remember { mutableStateOf(BatteryExemption.isIgnoringBatteryOptimizations(context)) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exemptNow = BatteryExemption.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    if (exemptNow || asked) return

    val oem = remember { BatteryExemption.detectOem() }
    val bodyText = when (oem) {
        BatteryExemption.Oem.SAMSUNG -> stringResource(R.string.battery_exemption_body_samsung)
        BatteryExemption.Oem.XIAOMI -> stringResource(R.string.battery_exemption_body_xiaomi)
        else -> stringResource(R.string.battery_exemption_body)
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
                text = stringResource(R.string.battery_exemption_title),
                style = pilgrimType.heading,
            )
            Spacer(Modifier.height(PilgrimSpacing.small))
            Text(text = bodyText, style = pilgrimType.body, color = pilgrimColors.fog)
            Spacer(Modifier.height(PilgrimSpacing.small))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { viewModel.markBatteryExemptionAsked() }) {
                    Text(stringResource(R.string.battery_exemption_later))
                }
                TextButton(onClick = {
                    viewModel.markBatteryExemptionAsked()
                    val intent = BatteryExemption.requestIgnoreBatteryOptimizationsIntent(context.packageName)
                    runCatching { context.startActivity(intent) }
                        .onFailure {
                            context.startActivity(BatteryExemption.batteryOptimizationsSettingsIntent())
                        }
                    // exemptNow is refreshed by the ON_RESUME observer
                    // once the user returns from the system prompt.
                }) {
                    Text(stringResource(R.string.battery_exemption_action))
                }
            }
        }
    }
}
