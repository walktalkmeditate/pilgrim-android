// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.honor

import android.app.Application
import android.net.Uri
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.honor.ExoPlayerWayVoiceTrack
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind

/**
 * `:tracker`'s Way voice player takes a shared Way's voice from its
 * `media/audio/<n>.m4a` the moment it plays (iOS `localMediaURL`), so a
 * file that lands mid-walk from the UI's gather plays at its spot, and
 * one still missing is skipped silently (shared-walk spec S3 §11, S4 §10.3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorMediaFilesTest {

    @get:Rule val folder = TemporaryFolder()

    private val store by lazy { WayStore({ File(folder.root, "Ways") }, syncDirectory = { true }) }
    private val media by lazy { HonorMediaFiles({ File(folder.root, "files") }, store) }

    private fun voice(path: String) = WayMoment(
        id = "voice-1",
        frac = 0.5,
        at = null,
        kind = WayMomentKind.Voice(0.6, 40.0, VoiceKind.SPOKEN, WayMedia.File(path)),
    )

    @Test
    fun `a shared voice resolves to its landed file, and to nothing until it lands`() {
        assertNull(media.voiceFile(WAY_ID, voice("audio/1.m4a")))

        val landed = File(folder.root, "Ways/$WAY_ID/media/audio/1.m4a").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(8))
        }

        assertEquals(landed.canonicalFile, media.voiceFile(WAY_ID, voice("audio/1.m4a"))!!.canonicalFile)
        assertEquals(Uri.fromFile(landed), ExoPlayerWayVoiceTrack.wayVoiceMediaItem(landed).localConfiguration!!.uri)
    }

    @Test
    fun `a shared path that climbs out of the Way's media resolves to nothing`() {
        File(folder.root, "Ways/$WAY_ID/way.json").apply { parentFile!!.mkdirs() }.writeText("{}")

        assertNull(media.voiceFile(WAY_ID, voice("../way.json")))
    }

    private companion object {
        const val WAY_ID = "share:Qoi4YmPHLN"
    }
}
