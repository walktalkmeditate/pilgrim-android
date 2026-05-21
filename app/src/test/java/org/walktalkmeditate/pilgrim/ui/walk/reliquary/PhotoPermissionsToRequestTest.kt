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
    fun api34_requests_full_partial_media_images_and_location() {
        assertEquals(
            listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
                "android.permission.ACCESS_MEDIA_LOCATION",
            ),
            photoPermissionsToRequest().toList(),
        )
    }

    @Test
    @Config(sdk = [33])
    fun api33_requests_read_media_images_and_location() {
        assertEquals(
            listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                "android.permission.ACCESS_MEDIA_LOCATION",
            ),
            photoPermissionsToRequest().toList(),
        )
    }

    @Test
    @Config(sdk = [30])
    fun q_to_pre33_requests_read_external_storage_and_location() {
        assertEquals(
            listOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                "android.permission.ACCESS_MEDIA_LOCATION",
            ),
            photoPermissionsToRequest().toList(),
        )
    }

    @Test
    @Config(sdk = [28])
    fun pre_q_requests_read_external_storage_only() {
        assertEquals(
            listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
            photoPermissionsToRequest().toList(),
        )
    }
}
