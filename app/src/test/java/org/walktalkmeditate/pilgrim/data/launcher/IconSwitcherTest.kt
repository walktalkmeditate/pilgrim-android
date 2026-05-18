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
    fun switchTo_keeps_running_alias_enabled_and_disables_the_rest() {
        switcher.switchTo(IconVariant.Dark)

        assertTrue("target enabled", isEnabled(IconVariant.Dark))
        // Default is the cold-install running alias; switchTo must NOT
        // disable it (that tears the live task down → eviction).
        assertFalse(
            "running alias must stay enabled",
            isDisabled(IconVariant.Default),
        )
        // Every alias that is neither target nor running is disabled.
        assertTrue(isDisabled(IconVariant.Breeze))
        assertTrue(isDisabled(IconVariant.Stone))
        assertEquals(IconVariant.Dark, switcher.persistedTarget())
    }

    @Test
    fun reconcile_disables_stale_non_target_aliases() {
        switcher.switchTo(IconVariant.Dark)
        // Default (the previous running alias) is still enabled here.
        assertFalse(isDisabled(IconVariant.Default))

        switcher.reconcile()

        assertTrue("target stays enabled", isEnabled(IconVariant.Dark))
        assertTrue("stale running alias reaped", isDisabled(IconVariant.Default))
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
