// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * iOS parity `UIApplication.setAlternateIconName(_:)` — swap the
 * launcher icon among the 9 declared `<activity-alias>` entries in
 * AndroidManifest. Only one alias is enabled at a time; the others
 * have their component-enabled-setting flipped to DISABLED. The
 * Default alias is the cold-install state.
 *
 * Implementation notes:
 *  - `setComponentEnabledSetting` is a synchronous call but Android
 *    may delay the launcher refresh by a few hundred ms — the user
 *    sees the new icon after the next homescreen redraw, same as
 *    iOS's setAlternateIconName.
 *  - Disabling EVERY component is dangerous (no launcher entry left)
 *    so [switchTo] disables the CURRENT alias only AFTER enabling
 *    the new one. Reordering the two calls would leave the app
 *    momentarily un-launchable.
 *  - `DONT_KILL_APP` flag prevents the system from restarting the
 *    process on the toggle (otherwise the activity-host process
 *    would be killed mid-tap on the About screen).
 */
@Singleton
open class IconSwitcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Detect which alias is currently enabled; null if none (cold start). */
    open fun currentVariant(): IconVariant {
        val pm = context.packageManager
        return IconVariant.entries.firstOrNull { variant ->
            val component = ComponentName(context, qualifiedAliasName(variant))
            pm.getComponentEnabledSetting(component) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: IconVariant.Default
    }

    /**
     * Enable [target]'s alias and disable every other alias. Idempotent
     * — re-applying the current variant is a no-op (no state change,
     * no launcher refresh).
     */
    open fun switchTo(target: IconVariant) {
        val pm = context.packageManager
        val current = currentVariant()
        if (current == target) return
        // Enable target first to avoid a momentary "no launcher" gap.
        setEnabled(pm, target, true)
        for (variant in IconVariant.entries) {
            if (variant != target) setEnabled(pm, variant, false)
        }
    }

    private fun setEnabled(pm: PackageManager, variant: IconVariant, enabled: Boolean) {
        val component = ComponentName(context, qualifiedAliasName(variant))
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        pm.setComponentEnabledSetting(
            component,
            newState,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun qualifiedAliasName(variant: IconVariant): String =
        "${context.packageName.removeSuffix(".debug")}.${variant.aliasName}"
}

/**
 * The 9 alternate launcher icons. iOS-parity `AppIconDefault` /
 * `AppIconDark` plus 7 voice-guide-themed variants. The `aliasName`
 * field MUST match the AndroidManifest `<activity-alias android:name>`
 * value (unqualified — IconSwitcher prepends the package).
 */
enum class IconVariant(val aliasName: String) {
    // Reviewer-flagged: `aliasName` is the UN-dotted short name. The
    // dot prefix is added by IconSwitcher's qualifier — having a dot
    // BOTH here and in the qualifier produces a double-dot
    // `org.walktalkmeditate.pilgrim..IconDefault` that PackageManager
    // silently rejects (no exception; just a no-op). Every switch
    // would silently no-op.
    Default("IconDefault"),
    Dark("IconDark"),
    Breeze("IconBreeze"),
    Drift("IconDrift"),
    Dusk("IconDusk"),
    Ember("IconEmber"),
    River("IconRiver"),
    Sage("IconSage"),
    Stone("IconStone");

    companion object {
        /**
         * iOS parity `PilgrimLogoView.appIconName(for:)@db4196e` — map
         * a voice-guide pack id to the matching launcher variant.
         * Returns null for unknown ids (no themed icon available).
         */
        fun forGuideId(guideId: String?): IconVariant? = when (guideId) {
            "breeze" -> Breeze
            "drift" -> Drift
            "dusk" -> Dusk
            "ember" -> Ember
            "river" -> River
            "sage" -> Sage
            "stone" -> Stone
            else -> null
        }
    }
}
