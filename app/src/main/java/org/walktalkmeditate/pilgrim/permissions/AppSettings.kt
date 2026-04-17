// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Deep-link helper for sending the user to this app's permission page in
 * system Settings — the only path forward when a permission is
 * permanently denied or when the user chose Approximate location and we
 * need precise.
 */
object AppSettings {
    fun openDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
