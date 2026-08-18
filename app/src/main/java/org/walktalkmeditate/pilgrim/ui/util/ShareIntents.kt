// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import org.walktalkmeditate.pilgrim.R

object ShareIntents {

    fun sharePilgrim(context: Context) {
        val body = context.getString(R.string.settings_share_pilgrim_body)
        val url = context.getString(R.string.settings_share_pilgrim_url)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$body $url")
        }
        context.startActivity(Intent.createChooser(send, null))
    }

    /**
     * System share sheet for a plain URL string. iOS parity: the
     * "Share" button in `WalkSharingButtons.activeShareSection`
     * (`WalkSharingButtons.swift:257-274@2ee1185`) presents a
     * `ShareSheet` with the `URL` as the shared item; Android's
     * closest equivalent is `ACTION_SEND text/plain` with the URL as
     * the extra text (there is no distinct "share a link" system type
     * on Android). `FLAG_ACTIVITY_NEW_TASK` + the `ActivityNotFoundException`
     * guard mirror `PromptDetailDialog.kt`'s `launchShare` — the most
     * defensive existing precedent for this exact Intent shape.
     */
    fun shareUrl(context: Context, url: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        val chooser = Intent.createChooser(send, null).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            // ACTION_SEND text/plain is universally handled on real
            // devices. Defensive no-op for stripped-down emulators.
        }
    }
}
