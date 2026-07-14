// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.seek

import android.app.Application
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Looper
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.walktalkmeditate.pilgrim.data.seek.FakeSeekPreferencesRepository
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository

/**
 * Robolectric tests for [SeekSoundPlayer]. Per the CLAUDE.md builder
 * rule the REAL `AudioFocusRequest.Builder().build()` + submit path
 * and the real manual-MediaPlayer construction path are exercised —
 * the injected factory returns real [MediaPlayer] subclasses that only
 * count `start()` calls (the iOS test suite's `CountingPlayer`
 * pattern), so the production decision logic runs unchanged.
 *
 * Haptic assertions use the real [SeekHaptics] over Robolectric's
 * ShadowVibrator: with no primitive support configured, a tick is a
 * 30 ms one-shot and an aligned pair is a waveform — "some vibration
 * was recorded" is the coupled-haptic signal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SeekSoundPlayerTest {

    private class CountingMediaPlayer : MediaPlayer() {
        var startCount = 0
            private set

        override fun start() {
            startCount++
            super.start()
        }
    }

    private lateinit var context: Application
    private lateinit var audioManager: AudioManager
    private lateinit var vibrator: Vibrator
    private lateinit var seekPreferences: FakeSeekPreferencesRepository
    private lateinit var soundsPreferences: FakeSoundsPreferencesRepository

    private val players = mutableListOf<CountingMediaPlayer>()
    private var whisperPlaying = false
    private var voiceGuidePlaying = false
    private var talkRecording = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(AudioManager::class.java)
        vibrator = context.getSystemService(Vibrator::class.java)
        shadowOf(vibrator).setHasVibrator(true)
        // Let prepare() succeed for the raw-fd data source (BellPlayerTest
        // convention) so the real arm path runs under Robolectric.
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo() }
        players.clear()
        whisperPlaying = false
        voiceGuidePlaying = false
        talkRecording = false
        seekPreferences = FakeSeekPreferencesRepository()
        soundsPreferences = FakeSoundsPreferencesRepository()
    }

    @After
    fun tearDown() {
        ShadowMediaPlayer.resetStaticState()
    }

    private fun makePlayer(
        doublePingGapMs: Long = SeekSoundPlayer.DOUBLE_PING_GAP_MS,
        factory: () -> MediaPlayer = {
            CountingMediaPlayer().also { players += it }
        },
    ): SeekSoundPlayer = SeekSoundPlayer(
        context = context,
        audioManager = audioManager,
        seekPreferences = seekPreferences,
        soundsPreferences = soundsPreferences,
        gate = SeekPingGate(
            isWhisperPlaying = { whisperPlaying },
            isVoiceGuidePlaying = { voiceGuidePlaying },
            isTalkRecordingActive = { talkRecording },
        ),
        haptics = SeekHaptics(vibrator),
        doublePingGapMs = doublePingGapMs,
        playerFactory = factory,
    )

    private val totalPlays: Int get() = players.sumOf { it.startCount }

    private fun idleFor(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun focusListener() = shadowOf(audioManager).lastAudioFocusRequest.listener

    private fun assertNoHapticFired() {
        assertEquals("no one-shot haptic", 0L, shadowOf(vibrator).milliseconds)
        assertNull("no waveform haptic", shadowOf(vibrator).pattern)
        assertTrue("no primitive haptic", shadowOf(vibrator).primitiveEffects!!.isEmpty())
    }

    private fun assertHapticFired() {
        val shadow = shadowOf(vibrator)
        assertTrue(
            "expected a coupled haptic",
            shadow.milliseconds != 0L || shadow.pattern != null ||
                shadow.primitiveEffects!!.isNotEmpty(),
        )
    }

    // ─── Lifecycle ────────────────────────────────────────────────────

    @Test
    fun `prepare requests real focus and arms both players without starting them`() {
        val player = makePlayer()

        player.prepare()

        // CLAUDE.md builder rule: the REAL AudioFocusRequest.build()
        // was submitted with the earcon-family gain mode.
        val request = shadowOf(audioManager).lastAudioFocusRequest
        assertNotNull("focus request built and submitted", request)
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            request.audioFocusRequest.focusGain,
        )
        assertEquals("both players armed", 2, players.size)
        assertEquals("armed, not played", 0, totalPlays)
    }

    @Test
    fun `stop abandons focus and drops players`() {
        val player = makePlayer()
        player.prepare()

        player.stop()
        idle()

        assertNotNull(
            "focus abandoned",
            shadowOf(audioManager).lastAbandonedAudioFocusRequest,
        )
    }

    // ─── Ping volume (iOS: sonarVolume × (0.55 + 0.45 × closeness)) ──

    @Test
    fun `ping at closeness zero plays at the volume floor times pref`() {
        val player = makePlayer()
        player.prepare()

        player.playPing(aligned = false, closeness = 0f)

        assertEquals(1, players[0].startCount)
        // 0.5 (default pref) × 0.55 = 0.275
        assertEquals(0.275f, shadowOf(players[0] as MediaPlayer).leftVolume, 0.001f)
    }

    @Test
    fun `ping at closeness one plays at the pref volume`() {
        seekPreferences = FakeSeekPreferencesRepository(initialSonarVolume = 0.8f)
        val player = makePlayer()
        player.prepare()

        player.playPing(aligned = false, closeness = 1f)

        // 0.8 × (0.55 + 0.45 × 1) = 0.8
        assertEquals(0.8f, shadowOf(players[0] as MediaPlayer).leftVolume, 0.001f)
    }

    @Test
    fun `ping guards a NaN volume pref as silence`() {
        seekPreferences = FakeSeekPreferencesRepository(initialSonarVolume = Float.NaN)
        val player = makePlayer()
        player.prepare()

        player.playPing(aligned = false, closeness = 1f)

        assertEquals(0f, shadowOf(players[0] as MediaPlayer).leftVolume, 0.001f)
    }

    // ─── Toggles ─────────────────────────────────────────────────────

    @Test
    fun `sonar toggle off skips ping but the pulse haptic still fires`() {
        seekPreferences = FakeSeekPreferencesRepository(initialSonarEnabled = false)
        val player = makePlayer()

        player.playPing(aligned = true, closeness = 1f)
        idleFor(SeekSoundPlayer.DOUBLE_PING_GAP_MS + 50)

        assertEquals("no play attempt, no arm", 0, players.size)
        // iOS fires the pulse haptic irrespective of the sound decision
        // (`ActiveWalkViewModel+Seek.swift:137-138@c1745e8`) — the
        // silent-sonar guidance channel keeps working.
        assertHapticFired()
    }

    @Test
    fun `master sounds off suppresses ping and bowl`() {
        soundsPreferences = FakeSoundsPreferencesRepository(initialSoundsEnabled = false)
        val player = makePlayer()
        player.prepare()

        player.playPing(aligned = true, closeness = 1f)
        player.playBowl()
        idleFor(SeekSoundPlayer.DOUBLE_PING_GAP_MS + 50)

        assertEquals("the master Sounds toggle silences all seek audio", 0, totalPlays)
    }

    @Test
    fun `disable between double-ping halves silences the pending second`() {
        val player = makePlayer()
        player.prepare()

        player.playPing(aligned = true, closeness = 1f)
        assertEquals(1, totalPlays)
        seekPreferences.setSonarEnabledNow(false)
        idleFor(SeekSoundPlayer.DOUBLE_PING_GAP_MS + 50)

        assertEquals(1, totalPlays)
    }

    // ─── Suppression matrix (skip, never queue) ──────────────────────

    @Test
    fun `whisper playing skips ping, plays after clear`() {
        val player = makePlayer()
        player.prepare()

        whisperPlaying = true
        player.playPing(aligned = false, closeness = 1f)
        assertEquals(0, totalPlays)
        assertHapticFired()

        whisperPlaying = false
        idleFor(1_000)
        assertEquals("skipped pings never queue", 0, totalPlays)

        player.playPing(aligned = false, closeness = 1f)
        assertEquals(1, totalPlays)
    }

    @Test
    fun `voice guide playing skips ping, plays after clear`() {
        val player = makePlayer()
        player.prepare()

        voiceGuidePlaying = true
        player.playPing(aligned = false, closeness = 1f)
        assertEquals(0, totalPlays)

        voiceGuidePlaying = false
        player.playPing(aligned = false, closeness = 1f)
        assertEquals(1, totalPlays)
    }

    @Test
    fun `talk recording skips ping, plays after recording ends`() {
        val player = makePlayer()
        player.prepare()

        talkRecording = true
        player.playPing(aligned = false, closeness = 1f)
        assertEquals("pings must never perturb an active talk recording", 0, totalPlays)

        talkRecording = false
        player.playPing(aligned = false, closeness = 1f)
        assertEquals(1, totalPlays)
    }

    @Test
    fun `bowl ignores the suppression gate`() {
        val player = makePlayer()
        player.prepare()

        whisperPlaying = true
        voiceGuidePlaying = true
        talkRecording = true
        player.playBowl()

        // players[1] is the bowl (armed second in prepare).
        assertEquals(1, players[1].startCount)
    }

    // ─── Double ping ─────────────────────────────────────────────────

    @Test
    fun `aligned ping plays twice after the gap`() {
        val player = makePlayer()
        player.prepare()

        player.playPing(aligned = true, closeness = 1f)
        assertEquals(1, totalPlays)

        idleFor(SeekSoundPlayer.DOUBLE_PING_GAP_MS + 50)
        assertEquals(2, totalPlays)
        assertEquals("both plays reuse the armed ping player", 2, players[0].startCount)
    }

    @Test
    fun `stop between double-ping halves cancels the pending second`() {
        val player = makePlayer()
        player.prepare()

        player.playPing(aligned = true, closeness = 1f)
        player.stop()
        idleFor(SeekSoundPlayer.DOUBLE_PING_GAP_MS + 50)

        assertEquals(1, totalPlays)
        assertNotNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `newer request supersedes the pending second`() {
        val player = makePlayer()
        player.prepare()

        player.playPing(aligned = true, closeness = 1f)
        player.playPing(aligned = false, closeness = 1f)
        assertEquals(2, totalPlays)

        idleFor(SeekSoundPlayer.DOUBLE_PING_GAP_MS + 50)
        assertEquals("the stale aligned second must not fire after a newer request", 2, totalPlays)
    }

    // ─── Bowl ────────────────────────────────────────────────────────

    @Test
    fun `bowl plays when the sonar toggle is off`() {
        seekPreferences = FakeSeekPreferencesRepository(
            initialSonarEnabled = false,
            initialSonarVolume = 0.6f,
        )
        val player = makePlayer()
        player.prepare()

        player.playBowl()

        assertEquals("the reveal bowl belongs to the ritual, not the sonar toggle", 1, totalPlays)
        assertEquals(1, players[1].startCount)
        // Bowl volume = sonarVolume × 1 (iOS `play(player)` default scale).
        assertEquals(0.6f, shadowOf(players[1] as MediaPlayer).leftVolume, 0.001f)
    }

    // ─── Focus denial (Android-only failure mode) ────────────────────

    @Test
    fun `focus denied skips ping playback and the coupled haptic`() {
        shadowOf(audioManager).setNextFocusRequestResponse(
            AudioManager.AUDIOFOCUS_REQUEST_FAILED,
        )
        val player = makePlayer()

        player.playPing(aligned = false, closeness = 1f)

        assertEquals("no arm after denial", 0, players.size)
        // No phantom buzz on denied focus (BellPlayer precedent) — the
        // platform rejected an actual attempt.
        assertNoHapticFired()
    }

    @Test
    fun `focus denied skips the bowl`() {
        shadowOf(audioManager).setNextFocusRequestResponse(
            AudioManager.AUDIOFOCUS_REQUEST_FAILED,
        )
        val player = makePlayer()

        player.playBowl()

        assertEquals(0, players.size)
    }

    @Test
    fun `focus denial recovers on the next ping once focus is grantable`() {
        shadowOf(audioManager).setNextFocusRequestResponse(
            AudioManager.AUDIOFOCUS_REQUEST_FAILED,
        )
        val player = makePlayer()
        player.playPing(aligned = false, closeness = 1f)
        assertEquals(0, totalPlays)

        shadowOf(audioManager).setNextFocusRequestResponse(
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
        )
        player.playPing(aligned = false, closeness = 1f)
        assertEquals(1, totalPlays)
    }

    // ─── Interruptions (drop + lazy re-arm, never auto-resume) ───────

    @Test
    fun `transient focus loss drops players and gain re-arms lazily`() {
        val player = makePlayer()
        player.prepare()
        val armedAtPrepare = players.size

        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idle()
        player.playPing(aligned = false, closeness = 1f)
        assertEquals("state must stay honest while another app owns audio", 0, totalPlays)

        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        player.playPing(aligned = false, closeness = 1f)

        assertEquals("the first ping after GAIN succeeds without manual help", 1, totalPlays)
        assertTrue("re-arm recreates the dropped player", players.size > armedAtPrepare)
    }

    @Test
    fun `transient focus loss cancels the pending double-ping second`() {
        val player = makePlayer()
        player.prepare()

        player.playPing(aligned = true, closeness = 1f)
        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idleFor(SeekSoundPlayer.DOUBLE_PING_GAP_MS + 50)

        assertEquals(1, totalPlays)
    }

    @Test
    fun `bowl skips during interruption and plays after gain`() {
        val player = makePlayer()
        player.prepare()

        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idle()
        player.playBowl()
        assertEquals("a bowl must never fight another app for audio", 0, totalPlays)

        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        player.playBowl()
        assertEquals("the first bowl after GAIN plays without manual help", 1, totalPlays)
    }

    @Test
    fun `permanent focus loss releases the consumer and the next ping reactivates`() {
        val player = makePlayer()
        player.prepare()

        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        idle()
        assertNotNull(
            "permanent takeover releases focus instead of muting forever",
            shadowOf(audioManager).lastAbandonedAudioFocusRequest,
        )

        player.playPing(aligned = false, closeness = 1f)
        assertEquals("a fresh focus request re-activates the channel", 1, totalPlays)
    }

    // ─── Completion release (iOS seekCompleteSoundStopDelay = 4.5 s) ─

    @Test
    fun `completion bowl rings then releases the consumer after the window`() {
        val player = makePlayer()
        player.prepare()

        player.playCompletionBowl()

        assertEquals(1, players[1].startCount)
        assertNull(
            "the consumer holds through the ring window",
            shadowOf(audioManager).lastAbandonedAudioFocusRequest,
        )

        idleFor(SeekSoundPlayer.COMPLETION_RELEASE_DELAY_MS + 50)
        assertNotNull(
            "focus released once the bowl has rung",
            shadowOf(audioManager).lastAbandonedAudioFocusRequest,
        )
    }

    @Test
    fun `stop before the release window stays idempotent`() {
        val player = makePlayer()
        player.prepare()

        player.playCompletionBowl()
        player.stop()
        idleFor(SeekSoundPlayer.COMPLETION_RELEASE_DELAY_MS + 50)

        assertNotNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)
    }

    // ─── Error paths ─────────────────────────────────────────────────

    @Test
    fun `player factory failure on prepare leaves the consumer released`() {
        val player = makePlayer(factory = { error("boom") })

        player.prepare()

        assertNotNull(
            "nothing armed → consumer released",
            shadowOf(audioManager).lastAbandonedAudioFocusRequest,
        )
    }

    @Test
    fun `player factory failure on ping releases the consumer and skips the haptic`() {
        val player = makePlayer(factory = { error("boom") })

        player.playPing(aligned = false, closeness = 1f)

        assertNotNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)
        assertNoHapticFired()
    }
}
