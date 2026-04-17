// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Android's foreground-service-type=location contract is *supposed* to
 * guarantee our walk tracking survives Doze. Several OEMs
 * (Samsung, Xiaomi, Huawei, OPPO) still kill the service without the
 * app being battery-optimization-exempt. This helper launches the
 * system prompt and exposes OEM detection so the UI can tailor copy.
 */
object BatteryExemption {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Intent that opens the system prompt asking the user to whitelist us.
     * Needs the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission or, per
     * Google policy, only for apps with a legitimate need (foreground
     * service with an ongoing notification qualifies).
     */
    @Suppress("BatteryLife") // foreground service justifies the direct exemption request
    fun requestIgnoreBatteryOptimizationsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }

    /**
     * Falls back to the system-wide list if the direct request is blocked
     * (some OEM ROMs, some enterprise profiles).
     */
    fun batteryOptimizationsSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** OEM fingerprint for tailoring the rationale copy. */
    enum class Oem { SAMSUNG, XIAOMI, HUAWEI, OTHER }

    fun detectOem(): Oem {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            "samsung" in manufacturer -> Oem.SAMSUNG
            "xiaomi" in manufacturer || "redmi" in manufacturer || "poco" in manufacturer -> Oem.XIAOMI
            "huawei" in manufacturer || "honor" in manufacturer -> Oem.HUAWEI
            else -> Oem.OTHER
        }
    }
}
