// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.launcher

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IconSwitcherTest {

    private lateinit var context: Context
    private lateinit var switcher: IconSwitcher

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        switcher = IconSwitcher(context)
    }

    private fun enabledSetting(variant: IconVariant): Int {
        val pkg = context.packageName.removeSuffix(".debug")
        val component = ComponentName(context, "$pkg.${variant.aliasName}")
        return context.packageManager.getComponentEnabledSetting(component)
    }

    private fun isEnabled(variant: IconVariant): Boolean =
        enabledSetting(variant) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun isDisabled(variant: IconVariant): Boolean =
        enabledSetting(variant) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    @Test
    fun switchTo_enables_only_target_and_disables_every_other_alias() {
        switcher.switchTo(IconVariant.Dark)

        assertTrue("target enabled", isEnabled(IconVariant.Dark))
        // Exactly one enabled LAUNCHER alias must remain = target.
        // Two enabled aliases sharing targetActivity make the launcher
        // dedupe to the original and show no visible change, so the
        // previously-enabled Default alias must be disabled too. No
        // eviction occurs because target is enabled BEFORE Default is
        // disabled — a valid LAUNCHER component for MainActivity exists
        // throughout the transition.
        assertTrue(
            "previously-enabled alias disabled",
            isDisabled(IconVariant.Default),
        )
        assertTrue(isDisabled(IconVariant.Breeze))
        assertTrue(isDisabled(IconVariant.Stone))
        assertEquals(IconVariant.Dark, switcher.currentVariant())
        assertEquals(IconVariant.Dark, switcher.persistedTarget())
    }

    @Test
    fun reconcile_keeps_target_and_disables_non_target_aliases() {
        switcher.switchTo(IconVariant.Dark)

        switcher.reconcile()

        assertTrue("target stays enabled", isEnabled(IconVariant.Dark))
        assertTrue(isDisabled(IconVariant.Default))
        assertEquals(IconVariant.Dark, switcher.currentVariant())
    }

    @Test
    fun switchTo_persists_target_across_instances() {
        switcher.switchTo(IconVariant.River)

        val freshInstance = IconSwitcher(context)
        assertEquals(IconVariant.River, freshInstance.persistedTarget())
    }

    @Test
    fun constellation_variant_exists_and_switches() {
        val constellation = IconVariant.entries.firstOrNull {
            it.name == "Constellation"
        }
        assertEquals(IconVariant.Constellation, constellation)
        assertEquals("IconConstellation", IconVariant.Constellation.aliasName)
        // Not a voice-guide pack — must not map from any guide id.
        assertFalse(
            IconVariant.entries.any {
                IconVariant.forGuideId(it.name.lowercase()) == IconVariant.Constellation
            },
        )

        switcher.switchTo(IconVariant.Constellation)

        assertTrue(isEnabled(IconVariant.Constellation))
        assertEquals(IconVariant.Constellation, switcher.persistedTarget())
    }
}
