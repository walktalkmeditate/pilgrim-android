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
 * AndroidManifest. Exactly one alias is enabled at a time; the
 * others have their component-enabled-setting flipped to DISABLED.
 * The Default alias is the cold-install state.
 *
 * [switchTo] enables `target` FIRST, then disables every other
 * alias including the previously-enabled one. Because a valid
 * LAUNCHER component for MainActivity exists for the whole
 * transition (target is enabled before the old alias is disabled),
 * the live task is never left without a rooting alias, so there is
 * no eviction back to the home screen — iOS's `setAlternateIconName`
 * likewise swaps in place and the user stays put
 * (`AboutView.swift:411-417@v1.6.0`). The end state is a single
 * enabled LAUNCHER alias = `target`; two enabled aliases sharing the
 * same `targetActivity` make the launcher dedupe to the original and
 * show no visible change, which is why the old alias must be
 * disabled rather than left enabled.
 *
 * This makes [switchTo] structurally identical to [reconcile].
 *
 * Implementation notes:
 *  - `setComponentEnabledSetting` is a synchronous call but Android
 *    may delay the launcher refresh by a few hundred ms — the user
 *    sees the new icon after the next homescreen redraw, same as
 *    iOS's setAlternateIconName.
 *  - `DONT_KILL_APP` keeps the process alive across the toggles; the
 *    enable-target-before-disable-old ordering keeps the activity
 *    task rooted throughout, so the process is never torn down.
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
     * Enable [target]'s alias FIRST, then disable every other alias
     * including the previously-enabled one. The end state is exactly
     * one enabled LAUNCHER alias = [target], so the launcher shows
     * the new icon (two enabled aliases would dedupe to the original
     * and show no change). The enable-before-disable ordering keeps a
     * valid LAUNCHER component for MainActivity present throughout, so
     * the live task is never evicted. Idempotent — re-applying the
     * current variant is a no-op.
     */
    open fun switchTo(target: IconVariant) {
        val pm = context.packageManager
        val current = currentVariant()
        prefs.edit().putString(KEY_TARGET, target.name).apply()
        if (current == target) return
        setEnabled(pm, target, true)
        for (variant in IconVariant.entries) {
            if (variant != target) setEnabled(pm, variant, false)
        }
    }

    /**
     * Cold-start reconcile: disable any still-enabled alias that is not
     * the persisted target. Called from `PilgrimApp.onCreate`, where
     * there is no live activity task yet, so disabling the previously
     * running alias is safe.
     *
     * No-op when the currently-enabled alias already equals the
     * persisted target — the dominant case on every cold start AND
     * every mid-walk OS process restart. Without this guard, reconcile
     * fires 10 `setComponentEnabledSetting` calls and churns launcher
     * state on every single process start (including the OS reviving a
     * backgrounded walk's process), which is wasteful and destabilizing.
     * The enable-target-then-disable-others sequence only runs when the
     * two genuinely diverge (a previous in-place [switchTo] left the old
     * alias enabled and this is the first cold start since).
     */
    open fun reconcile() {
        if (currentVariant() == persistedTarget()) return
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
