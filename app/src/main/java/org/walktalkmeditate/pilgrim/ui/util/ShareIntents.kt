// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.util

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
}
