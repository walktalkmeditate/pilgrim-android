// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.R
import org.xmlpull.v1.XmlPullParser

/**
 * Resource-parse guard (parity spec BEH-21/DAT-45): `transcript_contexts/`
 * must stay excluded from backup in BOTH `data_extraction_rules.xml`
 * domains AND the legacy `fullBackupContent` document, so the rule cannot
 * silently regress under a future edit to either file. Parses the REAL
 * compiled resources (not a copy of the XML text), so this fails the
 * moment the shipped rule actually changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupRulesTest {

    private fun excludesByDomainTag(resId: Int, domainTags: Set<String>): Map<String, List<Pair<String, String>>> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val excludes = mutableMapOf<String, MutableList<Pair<String, String>>>()
        var currentTag: String? = null
        val parser = context.resources.getXml(resId)
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name in domainTags) {
                            currentTag = parser.name
                        } else if (parser.name == "exclude") {
                            val domain = parser.getAttributeValue(null, "domain").orEmpty()
                            val path = parser.getAttributeValue(null, "path").orEmpty()
                            currentTag?.let { excludes.getOrPut(it) { mutableListOf() } += domain to path }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name in domainTags) currentTag = null
                    }
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return excludes
    }

    @Test
    fun `transcript_contexts is excluded under file domain in BOTH data_extraction_rules domains`() {
        val excludes = excludesByDomainTag(
            R.xml.data_extraction_rules,
            domainTags = setOf("cloud-backup", "device-transfer"),
        )

        for (domainTag in listOf("cloud-backup", "device-transfer")) {
            assertTrue(
                "expected file-domain exclude for transcript_contexts/ inside <$domainTag>, " +
                    "found: ${excludes[domainTag]}",
                excludes[domainTag].orEmpty().any { (domain, path) -> domain == "file" && path == "transcript_contexts/" },
            )
        }
    }

    @Test
    fun `transcript_contexts is excluded under file domain in the legacy fullBackupContent document`() {
        val excludes = excludesByDomainTag(R.xml.backup_rules, domainTags = setOf("full-backup-content"))

        assertTrue(
            "expected file-domain exclude for transcript_contexts/ inside <full-backup-content>, " +
                "found: ${excludes["full-backup-content"]}",
            excludes["full-backup-content"].orEmpty()
                .any { (domain, path) -> domain == "file" && path == "transcript_contexts/" },
        )
    }

    @Test
    fun `the pre-existing share_device_token exclude precedent is still present in device-transfer`() {
        val excludes = excludesByDomainTag(R.xml.data_extraction_rules, domainTags = setOf("device-transfer"))

        assertTrue(
            excludes["device-transfer"].orEmpty()
                .any { (domain, path) -> domain == "file" && path == "datastore/share_device_token.preferences_pb" },
        )
    }
}
