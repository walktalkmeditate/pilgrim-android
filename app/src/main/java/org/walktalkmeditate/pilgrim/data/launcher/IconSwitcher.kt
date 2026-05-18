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
 * launcher icon among the declared `<activity-alias>` entries in
 * AndroidManifest. Only one alias is enabled at a time; the others
 * have their component-enabled-setting flipped to DISABLED. The
 * Default alias is the cold-install state.
 *
 * Eviction-to-launcher bug (E2): disabling the alias that roots the
 * live MainActivity task tears the task down even with
 * `DONT_KILL_APP`, kicking the user back to the home screen. iOS's
 * `setAlternateIconName` swaps in place and the user stays put
 * (`AboutView.swift:411-417@v1.6.0`). To match that, [switchTo]
 * enables `target`, persists it, and disables every alias EXCEPT
 * `target` AND EXCEPT the currently-running alias. The stale running
 * alias is reaped on the next cold start by [reconcile], called from
 * `PilgrimApp.onCreate`. Two LAUNCHER aliases briefly coexisting is
 * harmless — the launcher dedupes by `targetActivity`.
 *
 * Implementation notes:
 *  - `setComponentEnabledSetting` is a synchronous call but Android
 *    may delay the launcher refresh by a few hundred ms — the user
 *    sees the new icon after the next homescreen redraw, same as
 *    iOS's setAlternateIconName.
 *  - `DONT_KILL_APP` flag keeps the process alive on the toggle, but
 *    it does NOT preserve the activity task when the alias rooting it
 *    is disabled — that is exactly the bug this class works around.
 */
@Singleton
open class IconSwitcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Detect which alias is currently enabled; Default if none (cold start). */
    open fun currentVariant(): IconVariant {
        val pm = context.packageManager
        return IconVariant.entries.firstOrNull { variant ->
            val component = ComponentName(context, qualifiedAliasName(variant))
            pm.getComponentEnabledSetting(component) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: IconVariant.Default
    }

    /** The variant the user last asked for; Default until first switch. */
    open fun persistedTarget(): IconVariant {
        val raw = prefs.getString(KEY_TARGET, null) ?: return IconVariant.Default
        return IconVariant.entries.firstOrNull { it.name == raw } ?: IconVariant.Default
    }

    /**
     * Enable [target]'s alias and disable every other alias EXCEPT the
     * one rooting the live MainActivity task (`current`). Leaving
     * `current` enabled keeps the running task alive; the stale alias
     * is disabled on the next cold start by [reconcile]. Idempotent —
     * re-applying the current variant is a no-op.
     */
    open fun switchTo(target: IconVariant) {
        val pm = context.packageManager
        val current = currentVariant()
        prefs.edit().putString(KEY_TARGET, target.name).apply()
        if (current == target) return
        setEnabled(pm, target, true)
        for (variant in IconVariant.entries) {
            if (variant != target && variant != current) setEnabled(pm, variant, false)
        }
    }

    /**
     * Cold-start reconcile: disable any still-enabled alias that is not
     * the persisted target. Called from `PilgrimApp.onCreate`, where
     * there is no live activity task yet, so disabling the previously
     * running alias is safe.
     */
    open fun reconcile() {
        val pm = context.packageManager
        val target = persistedTarget()
        setEnabled(pm, target, true)
        for (variant in IconVariant.entries) {
            // Disable explicitly rather than gating on the current
            // setting: a manifest-default-enabled alias (IconDefault)
            // reports COMPONENT_ENABLED_STATE_DEFAULT, not ENABLED, so
            // an `== ENABLED` filter would never reap it.
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

    private companion object {
        const val PREFS_NAME = "icon_switcher_prefs"
        const val KEY_TARGET = "target_variant"
    }
}

/**
 * The launcher icon variants. iOS-parity `AppIconDefault` /
 * `AppIconDark` / `AppIconConstellation` plus 7 voice-guide-themed
 * variants. The `aliasName` field MUST match the AndroidManifest
 * `<activity-alias android:name>` value (unqualified — IconSwitcher
 * prepends the package).
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
    Stone("IconStone"),
    Constellation("IconConstellation");

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
