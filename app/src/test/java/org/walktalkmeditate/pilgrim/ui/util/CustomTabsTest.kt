// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.util

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Exercises the real [androidx.browser.customtabs.CustomTabsIntent]
 * builder path — the CLAUDE.md "platform-object builder tests" rule
 * requires a Robolectric test that calls `.build()` on the production
 * class so a runtime-rejected builder surfaces in CI rather than only
 * on-device.
 *
 * Uses a real Activity context (matching the Compose call site, which
 * always launches from an Activity) so `launchUrl` does not trip the
 * FLAG_ACTIVITY_NEW_TASK requirement that Application contexts impose.
 * Robolectric registers no Custom Tabs-capable browser, so `launchUrl`
 * records a plain `ACTION_VIEW` start; we assert the launched intent
 * carries the requested URI as data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CustomTabsTest {

    @Test
    fun `launch builds a Custom Tabs intent and starts it with the uri`() {
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val uri = "https://walk.pilgrimapp.org/share/abc123".toUri()

        CustomTabs.launch(activity, uri)

        val started: Intent? = shadowOf(activity).nextStartedActivity
        assertNotNull("expected an activity to be launched", started)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals(uri, started.data)
    }
}
