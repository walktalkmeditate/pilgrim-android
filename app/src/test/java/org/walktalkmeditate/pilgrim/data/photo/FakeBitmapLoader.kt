// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.photo

import android.graphics.Bitmap
import android.net.Uri

/**
 * Test fake for [BitmapLoader]. Returns [bitmap] for every URI unless
 * [unreadableUris] contains it (then null — simulating revoked grant
 * / deleted source photo).
 */
class FakeBitmapLoader(
    var bitmap: Bitmap? = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888),
    val unreadableUris: MutableSet<String> = mutableSetOf(),
) : BitmapLoader {
    val loadedUris: MutableList<Uri> = mutableListOf()

    override suspend fun load(uri: Uri): Bitmap? {
        loadedUris += uri
        return if (uri.toString() in unreadableUris) null else bitmap
    }
}
