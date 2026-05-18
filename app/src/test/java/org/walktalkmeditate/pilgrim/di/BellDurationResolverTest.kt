// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import android.app.Application
import android.media.MediaPlayer
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import org.walktalkmeditate.pilgrim.audio.BellDurationResolver
import org.walktalkmeditate.pilgrim.audio.BellFileResolver
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository

/**
 * CLAUDE.md platform-object-builder rule: the [BellDurationResolver]
 * default impl constructs a real [MediaPlayer], calls `setDataSource`
 * + `prepare()` + reads `duration`, then releases. That builder path
 * must be exercised by a Robolectric test that drives the production
 * provider — faking it would hide a runtime rejection that only
 * manifests on-device.
 *
 * BUG 4: soundscape masked the meditation-start bell because the
 * orchestrator used a fixed 800ms delay. The resolver supplies the
 * real bell length (iOS parity `SoundManagement.swift:68-78`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BellDurationResolverTest {

    private lateinit var context: Application

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After fun tearDown() {
        ShadowMediaPlayer.resetStaticState()
    }

    private fun resolver(
        meditationStartBellId: String? = null,
        bellFileResolver: BellFileResolver = BellFileResolver { null },
    ): BellDurationResolver = AudioModule.provideBellDurationResolver(
        context = context,
        soundsPreferences = FakeSoundsPreferencesRepository(
            initialMeditationStartBellId = meditationStartBellId,
        ),
        bellFileResolver = bellFileResolver,
    )

    @Test fun `resolves the bundled bell duration through the real MediaPlayer path`() {
        // Report a 3200ms duration for whatever data source the
        // production code prepares (bundled R.raw.bell here).
        ShadowMediaPlayer.setMediaInfoProvider {
            ShadowMediaPlayer.MediaInfo(3_200, 0)
        }
        val ms = resolver(meditationStartBellId = null).meditationStartBellDurationMs()
        assertEquals(3_200L, ms)
    }

    @Test fun `resolves a downloaded bell asset file duration`() {
        val bellFile = File(context.cacheDir, "echo-chime.aac").apply {
            writeBytes(ByteArray(64))
        }
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(bellFile.absolutePath),
            ShadowMediaPlayer.MediaInfo(5_000, 0),
        )
        val ms = resolver(
            meditationStartBellId = "echo-chime",
            bellFileResolver = { id -> if (id == "echo-chime") bellFile else null },
        ).meditationStartBellDurationMs()
        assertEquals(5_000L, ms)
    }

    @Test fun `falls back to bundled length when prepare throws`() {
        // Default Robolectric behavior: prepare() throws for an
        // unregistered data source. The provider must catch and fall
        // back rather than crash the soundscape session loop.
        val ms = resolver(meditationStartBellId = null).meditationStartBellDurationMs()
        assertEquals(BellDurationResolver.BUNDLED_BELL_MS, ms)
    }

    @Test fun `falls back to bundled length when duration is non-positive`() {
        ShadowMediaPlayer.setMediaInfoProvider {
            ShadowMediaPlayer.MediaInfo(0, 0)
        }
        val ms = resolver(meditationStartBellId = null).meditationStartBellDurationMs()
        assertEquals(BellDurationResolver.BUNDLED_BELL_MS, ms)
    }

    @Test fun `bundled fallback constant is a sane positive value`() {
        assertTrue(BellDurationResolver.BUNDLED_BELL_MS > 0L)
    }
}
