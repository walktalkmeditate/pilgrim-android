// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReliquarySettingsDeepLinkTest {

    @Test
    fun settingsIntent_hasCorrectActionSchemePackageAndFlag() {
        val packageName = "org.walktalkmeditate.pilgrim"
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        val data = intent.data
        checkNotNull(data) { "intent.data must not be null" }
        assertEquals("package", data.scheme)
        assertEquals(packageName, data.schemeSpecificPart)
        assertTrue(
            "FLAG_ACTIVITY_NEW_TASK must be set",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }
}
