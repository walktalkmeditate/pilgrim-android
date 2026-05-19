// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reliquary requests photo access on enable (iOS parity). The
 * requested set must track the same API split as
 * [isPhotosPermissionGranted] or the request and the check disagree
 * (request one permission, check another -> permanent PermissionDenied,
 * the exact bug this fixes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PhotoPermissionsToRequestTest {

    @Test
    @Config(sdk = [34])
    fun api34_requests_full_and_partial_media_images() {
        assertEquals(
            listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            ),
            photoPermissionsToRequest().toList(),
        )
    }

    @Test
    @Config(sdk = [33])
    fun api33_requests_read_media_images_only() {
        assertEquals(
            listOf(android.Manifest.permission.READ_MEDIA_IMAGES),
            photoPermissionsToRequest().toList(),
        )
    }

    @Test
    @Config(sdk = [30])
    fun pre33_requests_read_external_storage() {
        assertEquals(
            listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
            photoPermissionsToRequest().toList(),
        )
    }
}
