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
 *
 * Saved maps never leave it either (Stage 21-3, spec D C3 §10.4): Mapbox's
 * folder, `files/.mapbox/`, holds the tile store and the map's cache, and
 * each section that could carry `files/` excludes it by name. These stand
 * in for iOS's store-folder test, whose iCloud exclusion is the SDK's.
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

    /**
     * Each `<exclude>` as (domain, path), under the section it sits in:
     * `cloud-backup` or `device-transfer` in the API 31+ rules, the root
     * `full-backup-content` in the older ones.
     */
    private fun excludesBySection(resId: Int): Map<String, Set<Pair<String, String>>> {
        val sections = mutableMapOf<String, MutableSet<Pair<String, String>>>()
        val parser = context.resources.getXml(resId)
        try {
            var section: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if (parser.name in SECTIONS) section = parser.name
                    if (parser.name == "exclude") {
                        val excluded = parser.getAttributeValue(null, "domain").orEmpty() to parser.getAttributeValue(null, "path").orEmpty()
                        sections.getOrPut(checkNotNull(section) { "an exclude outside any section" }) { mutableSetOf() } += excluded
                    }
                }
                if (event == XmlPullParser.END_TAG && parser.name == section) section = null
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return sections
    }

    @Test
    fun `a device transfer leaves Mapbox's folder behind`() {
        val excluded = excludesBySection(R.xml.data_extraction_rules).getValue("device-transfer")

        assertTrue("excludes $excluded", MAPBOX_FOLDER in excluded)
    }

    @Test
    fun `cloud backup excludes Mapbox's folder by name, not only by the blanket exclude`() {
        val excluded = excludesBySection(R.xml.data_extraction_rules).getValue("cloud-backup")

        assertTrue("excludes $excluded", MAPBOX_FOLDER in excluded)
    }

    /** API 28–30 read these for both cloud backup and a device transfer. */
    @Test
    fun `the older backup rules exclude Mapbox's folder by name`() {
        val excluded = excludesBySection(R.xml.backup_rules).getValue("full-backup-content")

        assertTrue("excludes $excluded", MAPBOX_FOLDER in excluded)
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

    private companion object {
        val SECTIONS = setOf("cloud-backup", "device-transfer", "full-backup-content")
        val MAPBOX_FOLDER = "file" to ".mapbox/"
    }
}
