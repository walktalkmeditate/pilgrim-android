// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeepLinkTargetTest {

    @Test
    fun `parse returns null for null intent`() {
        assertNull(DeepLinkTarget.parse(null))
    }

    @Test
    fun `parse returns null for empty intent`() {
        assertNull(DeepLinkTarget.parse(Intent()))
    }

    @Test
    fun `parse returns null for unknown deep-link string`() {
        val intent = Intent().putExtra(DeepLinkTarget.EXTRA_DEEP_LINK, "unknown")
        assertNull(DeepLinkTarget.parse(intent))
    }

    @Test
    fun `parse returns WalkSummary for valid walk_summary intent`() {
        val intent = Intent()
            .putExtra(DeepLinkTarget.EXTRA_DEEP_LINK, DeepLinkTarget.DEEP_LINK_WALK_SUMMARY)
            .putExtra(DeepLinkTarget.EXTRA_WALK_ID, 42L)
        assertEquals(DeepLinkTarget.WalkSummary(42L), DeepLinkTarget.parse(intent))
    }

    @Test
    fun `parse returns null for walk_summary with missing walk id`() {
        val intent = Intent()
            .putExtra(DeepLinkTarget.EXTRA_DEEP_LINK, DeepLinkTarget.DEEP_LINK_WALK_SUMMARY)
        assertNull(DeepLinkTarget.parse(intent))
    }

    @Test
    fun `parse returns null for walk_summary with non-positive walk id`() {
        val intent = Intent()
            .putExtra(DeepLinkTarget.EXTRA_DEEP_LINK, DeepLinkTarget.DEEP_LINK_WALK_SUMMARY)
            .putExtra(DeepLinkTarget.EXTRA_WALK_ID, 0L)
        assertNull(DeepLinkTarget.parse(intent))
    }

    @Test
    fun `parse returns Home for home deep link`() {
        val intent = Intent().putExtra(DeepLinkTarget.EXTRA_DEEP_LINK, DeepLinkTarget.DEEP_LINK_HOME)
        assertEquals(DeepLinkTarget.Home, DeepLinkTarget.parse(intent))
    }
}
