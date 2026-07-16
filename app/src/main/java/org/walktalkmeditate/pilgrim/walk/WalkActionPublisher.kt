// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.seek.SeekGlanceState
import org.walktalkmeditate.pilgrim.service.WalkTrackingService

/**
 * Cross-process bridge: every user-initiated walk action in the UI
 * process travels through here as a service intent so it lands at the
 * `:tracker` process's [org.walktalkmeditate.pilgrim.walk.WalkControllerImpl].
 *
 * Without this indirection, UI's in-app buttons (Pause / Resume /
 * Finish / etc.) would mutate the UI process's WalkController singleton,
 * which under the manifest split is a different object than the
 * tracker's controller — the GPS pipeline would keep recording on a
 * paused walk, finish would not stop the tracker, etc.
 *
 * Notification-button taps already follow this exact path
 * ([android.app.PendingIntent.getService]); this class exposes the
 * same channel to UI code.
 */
@Singleton
class WalkActionPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Begin a new walk. Uses `startForegroundService` because the
     * service is not running yet on the first start; the service's
     * onStartCommand promotes to FG before the API 31+ deadline.
     */
    fun start(intention: String?, mode: WalkMode = WalkMode.Wander) {
        val intent = baseIntent(WalkTrackingService.ACTION_START).apply {
            putExtra(WalkTrackingService.EXTRA_FRESH_START, true)
            putExtra(WalkTrackingService.EXTRA_WALK_MODE, mode.name)
            if (intention != null) putExtra(WalkTrackingService.EXTRA_INTENTION, intention)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun pause() = fireService(WalkTrackingService.ACTION_PAUSE)

    fun resume() = fireService(WalkTrackingService.ACTION_RESUME)

    fun startMeditation() = fireService(WalkTrackingService.ACTION_START_MEDITATION)

    /**
     * @param endMillis explicit Done-tap timestamp; null lets the
     *   service use its own clock. iOS parity
     *   `MeditationView.swift:609-615@db4196e` — the closing ceremony
     *   that plays after Done must not inflate the recorded interval.
     */
    fun endMeditation(endMillis: Long?) {
        val intent = baseIntent(WalkTrackingService.ACTION_END_MEDITATION).apply {
            if (endMillis != null) {
                putExtra(WalkTrackingService.EXTRA_END_MILLIS, endMillis)
            }
        }
        context.startService(intent)
    }

    fun finish() = fireService(WalkTrackingService.ACTION_FINISH)

    fun discard() = fireService(WalkTrackingService.ACTION_DISCARD)

    fun markWaypoint(label: String?, icon: String?) {
        val intent = baseIntent(WalkTrackingService.ACTION_MARK_WAYPOINT).apply {
            if (label != null) putExtra(WalkTrackingService.EXTRA_WAYPOINT_LABEL, label)
            if (icon != null) putExtra(WalkTrackingService.EXTRA_WAYPOINT_ICON, icon)
        }
        context.startService(intent)
    }

    fun setIntention(text: String) {
        val intent = baseIntent(WalkTrackingService.ACTION_SET_INTENTION).apply {
            putExtra(WalkTrackingService.EXTRA_INTENTION, text)
        }
        context.startService(intent)
    }

    /**
     * Toggle the walk-long soundscape (iOS parity
     * `SoundManagement.toggleSoundscape`). Routed to `:tracker` because
     * the soundscape player lives there now and `pilgrim_prefs` is
     * single-process — a UI-side flag wouldn't reach the player.
     */
    fun setSoundscapeEnabled(on: Boolean) {
        val intent = baseIntent(WalkTrackingService.ACTION_SET_SOUNDSCAPE).apply {
            putExtra(WalkTrackingService.EXTRA_SOUNDSCAPE_ON, on)
        }
        safeStartService(intent, WalkTrackingService.ACTION_SET_SOUNDSCAPE)
    }

    /**
     * Pick a soundscape mid-walk (iOS parity `onSelectSoundscape`).
     * Carries the id to `:tracker` so the player switches immediately;
     * the UI also persists the selection to DataStore for next time.
     */
    fun selectSoundscape(assetId: String) {
        val intent = baseIntent(WalkTrackingService.ACTION_SELECT_SOUNDSCAPE).apply {
            putExtra(WalkTrackingService.EXTRA_SOUNDSCAPE_ID, assetId)
        }
        safeStartService(intent, WalkTrackingService.ACTION_SELECT_SOUNDSCAPE)
    }

    /**
     * Mid-walk explicit deselect (user tapped the currently-selected row
     * in the soundscape picker). Tells `:tracker`'s orchestrator to set
     * `selectionOverride` to `Selection.cleared = true` AND clear the
     * manual toggle, so the Meditating auto-play predicate (which is
     * insensitive to [setSoundscapeEnabled]) actually stops playback.
     * Pairs with `SoundscapeCatalogRepository.deselect()` on the UI side
     * for the persisted next-walk read; the Intent is what reaches the
     * live session, since `pilgrim_prefs` DataStore is single-process.
     */
    fun clearSoundscapeSelection() {
        safeStartService(
            baseIntent(WalkTrackingService.ACTION_CLEAR_SOUNDSCAPE_SELECTION),
            WalkTrackingService.ACTION_CLEAR_SOUNDSCAPE_SELECTION,
        )
    }

    /**
     * Carry the seek glance to `:tracker`'s notification renderer (U10).
     * The orchestrator pre-throttles to value changes, so this fires at
     * most once per 100 m bucket / hint flip / completion; `null` ≙ iOS
     * `seek: nil` and clears the tracker's stored glance. Uses
     * [safeStartService] — a glance dropped by the background-start
     * window self-heals on the next change. Port spec:
     * `docs/parity/2026-07-14-port-seek-glance-u10.md` B3.
     */
    fun publishSeekGlance(glance: SeekGlanceState?) {
        val intent = baseIntent(WalkTrackingService.ACTION_UPDATE_SEEK_GLANCE).apply {
            putExtra(WalkTrackingService.EXTRA_SEEK_GLANCE_PRESENT, glance != null)
            if (glance != null) {
                putExtra(WalkTrackingService.EXTRA_SEEK_GLANCE_BUCKET, glance.distanceBucketMeters)
                putExtra(WalkTrackingService.EXTRA_SEEK_GLANCE_COMPLETE, glance.isComplete)
                glance.directionHint?.let {
                    putExtra(WalkTrackingService.EXTRA_SEEK_GLANCE_DIRECTION, it.name)
                }
            }
        }
        safeStartService(intent, WalkTrackingService.ACTION_UPDATE_SEEK_GLANCE)
    }

    private fun fireService(action: String) {
        safeStartService(baseIntent(action), action)
    }

    /**
     * `context.startService` from a background context throws
     * [IllegalStateException] on API 26+ (and the API 31+ subtype
     * `ForegroundServiceStartNotAllowedException`). The soundscape picker
     * is reachable from Settings while the app is foregrounded, but a
     * task switch right before the tap can land us in the background-
     * start window. Log and swallow rather than crash the UI process —
     * the orchestrator will resync from DataStore on the next walk start
     * for selection actions, and the user can retry for toggle actions.
     */
    private fun safeStartService(intent: Intent, actionForLog: String) {
        try {
            context.startService(intent)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: IllegalStateException) {
            android.util.Log.w(
                "WalkActionPublisher",
                "startService($actionForLog) rejected — likely background-start restriction",
                e,
            )
        }
    }

    private fun baseIntent(action: String): Intent =
        Intent(context, WalkTrackingService::class.java).apply { this.action = action }
}
