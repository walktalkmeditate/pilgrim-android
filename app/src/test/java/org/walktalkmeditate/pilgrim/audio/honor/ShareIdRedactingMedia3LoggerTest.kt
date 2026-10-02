// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.honor

import android.app.Application
import androidx.media3.common.util.Log
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** Media3's own log lines, as its "Playback error" writes one for a shared voice that fails to open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShareIdRedactingMedia3LoggerTest {

    @After
    fun tearDown() {
        Log.setLogger(Log.Logger.DEFAULT)
    }

    @Test
    fun `a playback error names no share id, written plain or escaped, and keeps the rest of its line`() {
        ShareIdRedactingMedia3Logger.install()
        val path = "/data/user/0/org.walktalkmeditate.pilgrim/no_backup/Ways/share:Qoi4YmPHLN/media/audio/1.m4a"
        val cause = FileNotFoundException("$path: open failed: ENOENT (No such file or directory)")

        Log.e(TAG, "Playback error", IOException("file:///data/Ways/share%3AQoi4YmPHLN/media/audio/1.m4a", cause))

        val logged = ShadowLog.getLogsForTag(TAG).single().msg
        assertFalse(logged, logged.contains("Qoi4YmPHLN"))
        assertTrue(logged.startsWith("Playback error"))
        assertTrue(logged.contains("Ways/share:<id>/media/audio/1.m4a: open failed: ENOENT"))
    }

    @Test
    fun `every level is redacted, and a line with no share id is Media3's own`() {
        ShareIdRedactingMedia3Logger.install()

        Log.w(TAG, "share:Qoi4YmPHLN")
        Log.i(TAG, "Init 1.10.1")

        assertEquals(listOf("share:<id>", "Init 1.10.1"), ShadowLog.getLogsForTag(TAG).map { it.msg })
    }

    private companion object {
        const val TAG = "ExoPlayerImplInternal"
    }
}
