// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Wraps [CustomTabsIntent] with a same-task fallback to a vanilla
 * `ACTION_VIEW` browser intent. Some devices ship without a Custom
 * Tabs-capable browser (rare, but happens on stripped AOSP builds);
 * the fallback keeps the user moving rather than swallowing the tap.
 */
object CustomTabs {

    fun launch(context: Context, uri: Uri) {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        try {
            intent.launchUrl(context, uri)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: ActivityNotFoundException) {
                // No browser at all on the device; best we can do is
                // silently no-op. The user can long-press the row label
                // (Compose default semantics give a copy action via
                // accessibility) if they really need the URL.
            }
        }
    }
}
