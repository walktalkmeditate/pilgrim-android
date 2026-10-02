// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.R
import org.xmlpull.v1.XmlPullParser

/**
 * Ways never leave the phone (plan U28, R11): the store sits under
 * `noBackupFilesDir` (pinned by `WayStoreTest`), which the platform never
 * backs up or transfers, and neither backup document includes a domain
 * that reaches it (`root`, or an external one). Parses the real compiled
 * rules, so an edit that adds such an include fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WaysBackupRulesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun includedDomains(resId: Int): Set<String> {
        val domains = mutableSetOf<String>()
        val parser = context.resources.getXml(resId)
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "include") {
                    domains += parser.getAttributeValue(null, "domain").orEmpty()
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return domains
    }

    @Test
    fun `neither backup document includes a domain that reaches no_backup`() {
        val reaching = setOf("root", "device_root", "external", "device_external")

        listOf(R.xml.data_extraction_rules, R.xml.backup_rules).forEach { rules ->
            val included = includedDomains(rules)
            assertTrue("includes $included", included.none { it in reaching })
        }
        assertEquals(setOf("database", "file"), includedDomains(R.xml.data_extraction_rules))
    }
}
