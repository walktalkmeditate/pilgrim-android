// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.honor

import androidx.annotation.OptIn
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi

/**
 * Media3's own logging, with every share id taken out. Shared voices play
 * from `Ways/share:<id>/media/audio/<n>.m4a`, in the UI process's preview
 * and `:tracker`'s walk alike, and when such a file fails to open Media3
 * logs the failure itself ("Playback error"), its cause naming the path.
 * Each line is built as Media3's default logger builds it, then the id
 * goes, written plain or percent-encoded; nothing else changes. Installed
 * at every process start, before any player exists.
 */
@OptIn(UnstableApi::class)
object ShareIdRedactingMedia3Logger : Log.Logger {

    fun install() {
        Log.setLogger(this)
    }

    override fun d(tag: String, message: String, throwable: Throwable?) {
        android.util.Log.d(tag, redacted(message, throwable))
    }

    override fun i(tag: String, message: String, throwable: Throwable?) {
        android.util.Log.i(tag, redacted(message, throwable))
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        android.util.Log.w(tag, redacted(message, throwable))
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        android.util.Log.e(tag, redacted(message, throwable))
    }

    internal fun redacted(message: String, throwable: Throwable?): String =
        SHARE_ID.replace(Log.appendThrowableString(message, throwable), REDACTED)

    /** A Way id's `share:` form (`WayStore.isValidId`), its colon plain or escaped as a URI writes it. */
    private val SHARE_ID = Regex("share(:|%3[Aa])[A-Za-z0-9_-]{10}")
    private const val REDACTED = "share:<id>"
}
