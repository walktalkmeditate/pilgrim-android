// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.cairn

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository

/**
 * iOS parity `StonePlayer.swift@db4196e`. Plays a bundled bell sample
 * scaled by the placed-stone tier on stone placement.
 *
 * Tier mapping (`CairnTier.forStoneCount`) — count → tier → soundTier:
 *
 * | count | tier      | soundTier | resource         |
 * |-------|-----------|-----------|------------------|
 * | 0–2   | faint     | 1         | stone_tier_1.m4a |
 * | 3–6   | small     | 2         | stone_tier_2.m4a |
 * | 7–11  | medium    | 3         | stone_tier_3.m4a |
 * | 12–41 | large     | 4         | stone_tier_4.m4a |
 * | 42–76 | great     | 5         | stone_tier_5.m4a |
 * | 77–107| sacred    | 6         | stone_tier_6.m4a |
 * | 108+  | eternal   | 7         | stone_tier_7.m4a |
 *
 * Note: `count` is the server-confirmed post-placement count, so a
 * fresh cairn → 1 → faint → tier 1. The 7 m4a files are AAC audio
 * shipped in `res/raw/`, copied verbatim from iOS bundle.
 *
 * Audio focus: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` — fire-and-forget,
 * ducks music apps for ~2s rather than pausing them.
 * Concurrency: a second `playForCount` while a bell is still ringing
 * stops + releases the prior MediaPlayer and starts the new one.
 * Volume: read from [SoundsPreferencesRepository.bellVolume] at play
 * time (matches iOS reading from UserPreferences at play time, not
 * cached at construction).
 */
@Singleton
open class StonePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val soundsPreferences: SoundsPreferencesRepository,
) {
    private val lock = Any()
    private var current: MediaPlayer? = null
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var focusRequest: AudioFocusRequest? = null

    /**
     * Play the bell appropriate for [stoneCount]. No-op when
     * `soundsEnabled` is false.
     */
    open fun playForCount(stoneCount: Int) {
        if (!soundsPreferences.soundsEnabled.value) return
        val tier = CairnTier.forStoneCount(stoneCount)
        val volume = soundsPreferences.bellVolume.value.coerceIn(0f, 1f)
        playResource(resourceFor(tier), volume)
    }

    private fun playResource(resId: Int, volume: Float) {
        synchronized(lock) {
            current?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            current = null
            // iOS parity `StonePlayer.swift:18-21@db4196e` — request
            // transient ducking focus so background music drops to
            // ~30% for the duration of the bell, then restores. No-op
            // when no music is playing.
            requestDuckFocus()
            val player = MediaPlayer().apply {
                setAudioAttributes(audioAttrs)
                setVolume(volume, volume)
                setOnCompletionListener { mp ->
                    // Callbacks fire on the MediaPlayer thread; the
                    // focusRequest field is mutated under `lock` in
                    // requestDuckFocus, so the abandon path must
                    // share the same lock or it can null a
                    // freshly-set request from a second rapid
                    // playForCount call. Reviewer-flagged.
                    synchronized(lock) {
                        if (current === mp) current = null
                        abandonDuckFocus()
                    }
                    runCatching { mp.release() }
                }
                setOnErrorListener { mp, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                    synchronized(lock) {
                        if (current === mp) current = null
                        abandonDuckFocus()
                    }
                    runCatching { mp.release() }
                    true
                }
            }
            try {
                context.resources.openRawResourceFd(resId).use { afd ->
                    player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                player.prepare()
                player.start()
                current = player
            } catch (e: Exception) {
                Log.w(TAG, "playForCount failed: ${e.message}")
                runCatching { player.release() }
                abandonDuckFocus()
            }
        }
    }

    private fun requestDuckFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            ).setAudioAttributes(audioAttrs).build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    private fun abandonDuckFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    private fun resourceFor(tier: CairnTier): Int = when (tier) {
        CairnTier.Faint -> R.raw.stone_tier_1
        CairnTier.Small -> R.raw.stone_tier_2
        CairnTier.Medium -> R.raw.stone_tier_3
        CairnTier.Large -> R.raw.stone_tier_4
        CairnTier.Great -> R.raw.stone_tier_5
        CairnTier.Sacred -> R.raw.stone_tier_6
        CairnTier.Eternal -> R.raw.stone_tier_7
    }

    private companion object {
        const val TAG = "StonePlayer"
    }
}
