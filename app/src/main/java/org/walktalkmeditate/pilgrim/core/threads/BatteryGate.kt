// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * The single low-battery gate for deferrable background work (auto-
 * transcription, Threads backfill) — parity spec BEH-53/EDG-50.
 *
 * `registerReceiver(null, filter)` reads the last sticky
 * `ACTION_BATTERY_CHANGED` broadcast without installing a persistent
 * receiver (EDG-51/BEH-54): iOS's `allowsBackgroundWork` toggles
 * `isBatteryMonitoringEnabled` around the read for the same reason —
 * avoid a standing battery-monitoring cost — and this is Android's
 * no-registration equivalent of that scoping.
 */
object BatteryGate {

    private const val LOW_BATTERY_THRESHOLD_PERCENT = 20

    /**
     * Unknown level (no sticky intent yet, or a malformed level/scale
     * pair) ALLOWS — an unreliable-battery device/emulator must never
     * silently lose all background transcription/backfill. The boundary
     * is exclusive: exactly 20% BLOCKS.
     */
    fun allowsBackgroundWork(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return true

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level < 0 || scale <= 0) -1 else (level * 100) / scale

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val chargingOrFull = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        return percent < 0 || percent > LOW_BATTERY_THRESHOLD_PERCENT || chargingOrFull
    }
}
