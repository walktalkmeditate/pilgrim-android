// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.walktalkmeditate.pilgrim.BuildConfig

/**
 * Best-effort deep link to Pilgrim's Play Store listing. Strips the
 * `.debug` applicationId suffix so debug builds resolve to the
 * production listing (the listing is what users see on release; the
 * `.debug` listing doesn't exist).
 *
 * Until the Play Store listing publishes, this will resolve to
 * "Item not found" — accepted per Stage 10-F open question #4.
 */
object PlayStore {

    fun openListing(context: Context) {
        val productionId = BuildConfig.APPLICATION_ID.removeSuffix(".debug")
        val marketUri = Uri.parse("market://details?id=$productionId")
        val webUri = Uri.parse("https://play.google.com/store/apps/details?id=$productionId")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, marketUri))
        } catch (_: ActivityNotFoundException) {
            CustomTabs.launch(context, webUri)
        }
    }
}
