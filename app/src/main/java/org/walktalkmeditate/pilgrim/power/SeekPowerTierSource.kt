// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.walktalkmeditate.pilgrim.domain.seek.SeekPowerTier

/**
 * Minimal power-tier producer for the seek pulse-clock floor: the user's
 * battery-saver switch maps to [SeekPowerTier.LOW], everything else is
 * [SeekPowerTier.NORMAL]. Observation only — GPS power is never touched
 * here (port spec D1, docs/parity/2026-07-14-port-seek-engine-u3.md; iOS
 * derives its tiers in `WalkSessionGuard.recalculateTier`).
 */
@Singleton
class SeekPowerTierSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Cold flow seeded with the current mode on collection; re-emits on
     * every `ACTION_POWER_SAVE_MODE_CHANGED` broadcast. Cancellation
     * unregisters the receiver.
     */
    val tiers: Flow<SeekPowerTier> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                trySend(currentTier())
            }
        }
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        trySend(currentTier())
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun currentTier(): SeekPowerTier {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return if (powerManager?.isPowerSaveMode == true) {
            SeekPowerTier.LOW
        } else {
            SeekPowerTier.NORMAL
        }
    }
}
