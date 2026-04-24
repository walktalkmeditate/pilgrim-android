// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.content.Context
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 7-D: prunes stale etegami PNGs from [EtegamiPngWriter.cacheRoot].
 *
 * Share intents outlive the calling composition — a user may tap
 * Share, back out to home, open Gmail, and finish the send several
 * minutes later. Keeping files for 24h is a generous upper bound;
 * Android also auto-clears cacheDir on storage pressure, so real
 * retention is typically shorter.
 *
 * Per-file delete exceptions are logged-and-skipped — the sweeper
 * is best-effort. Only [CancellationException] propagates.
 */
internal object EtegamiCacheSweeper {

    private const val TAG = "EtegamiCacheSweeper"

    suspend fun sweepStale(
        context: Context,
        olderThan: Duration = 24.hours,
        now: () -> Long = System::currentTimeMillis,
    ): Int = withContext(Dispatchers.IO) {
        val root = EtegamiPngWriter.cacheRoot(context)
        val cutoff = now() - olderThan.inWholeMilliseconds
        var deleted = 0
        root.listFiles()?.forEach { file ->
            try {
                if (file.lastModified() < cutoff && file.delete()) {
                    deleted++
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "failed to delete ${file.name}", t)
            }
        }
        deleted
    }
}
