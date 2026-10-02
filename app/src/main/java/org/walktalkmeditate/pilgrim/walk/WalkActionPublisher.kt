// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGateKind
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.seek.SeekChainCodec
import org.walktalkmeditate.pilgrim.domain.seek.SeekGlanceState
import org.walktalkmeditate.pilgrim.service.WalkTrackingService
import org.walktalkmeditate.pilgrim.walk.honor.HonorCommand
import org.walktalkmeditate.pilgrim.walk.seek.SeekSonarSettings
import org.walktalkmeditate.pilgrim.walk.seek.SeekStart

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
class WalkActionPublisher internal constructor(
    private val context: Context,
    private val honorCommandSequence: HonorCommandSequence,
    private val bootNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, HonorCommandSequence(context))

    private val seekSequenceLock = Any()
    private var lastSeekSeq = 0L

    /**
     * Begin a new walk. Uses `startForegroundService` because the
     * service is not running yet on the first start; the service's
     * onStartCommand promotes to FG before the API 31+ deadline.
     */
    fun start(intention: String?, mode: WalkMode = WalkMode.Wander) =
        start(WalkStartRequest(intention = intention, mode = mode))

    /**
     * An honor start also carries the Begin-minted walk uuid, the Way id,
     * the settings frozen at Start, and the units its glance keeps for the
     * walk ([honorGlanceUnits]), so `:tracker` rebuilds the session from
     * the intent and Room alone.
     */
    fun start(request: WalkStartRequest, honorGlanceUnits: UnitSystem? = null) {
        val intent = baseIntent(WalkTrackingService.ACTION_START).apply {
            putExtra(WalkTrackingService.EXTRA_FRESH_START, true)
            putExtra(WalkTrackingService.EXTRA_WALK_MODE, request.mode.name)
            request.intention?.let { putExtra(WalkTrackingService.EXTRA_INTENTION, it) }
            request.walkUuid?.let { putExtra(WalkTrackingService.EXTRA_WALK_UUID, it) }
            request.honor?.let { honor ->
                putExtra(WalkTrackingService.EXTRA_HONOR_WAY_ID, honor.wayId)
                putExtra(WalkTrackingService.EXTRA_HONOR_VOICES_ENABLED, honor.settings.voicesEnabled)
                putExtra(WalkTrackingService.EXTRA_HONOR_SOFT_TAP_ENABLED, honor.settings.softTapEnabled)
                honorGlanceUnits?.let { putExtra(WalkTrackingService.EXTRA_HONOR_GLANCE_UNITS, it.name) }
            }
            request.seek?.let { putSeekStart(it) }
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * "Seek anew" for the seek walk's session in `:tracker` (plan U25).
     * Fire-and-forget: the service drops it with no live pipeline, or when
     * the OS redelivers it, and the session applies each number once.
     */
    fun sendSeekAnew() {
        safeStartService(seekAnewIntent(nextSeekSeq()), WalkTrackingService.ACTION_SEEK_ANEW)
    }

    internal fun seekAnewIntent(seq: Long): Intent =
        baseIntent(WalkTrackingService.ACTION_SEEK_ANEW).apply {
            putExtra(WalkTrackingService.EXTRA_SEEK_SEQ, seq)
        }

    /** The walker's sonar settings for the seek session in `:tracker`, which can't read them itself. */
    fun publishSeekPreferences(settings: SeekSonarSettings) {
        safeStartService(seekPreferencesIntent(settings, nextSeekSeq()), WalkTrackingService.ACTION_SEEK_PREFERENCES)
    }

    internal fun seekPreferencesIntent(settings: SeekSonarSettings, seq: Long): Intent =
        baseIntent(WalkTrackingService.ACTION_SEEK_PREFERENCES).apply {
            putExtra(WalkTrackingService.EXTRA_SEEK_SEQ, seq)
            putSeekSonar(settings)
        }

    /**
     * A seek session whose chain locked after its walk started, for
     * `:tracker` to attach to the seek walk in progress. Fire-and-forget,
     * and dropped when redelivered, like the other seek intents.
     */
    fun handOffSeekSession(start: SeekStart) {
        safeStartService(seekSessionIntent(start), WalkTrackingService.ACTION_SEEK_SESSION)
    }

    internal fun seekSessionIntent(start: SeekStart): Intent =
        baseIntent(WalkTrackingService.ACTION_SEEK_SESSION).apply { putSeekStart(start) }

    /** Rising across UI restarts: the boot clock both processes share is the floor. */
    private fun nextSeekSeq(): Long = synchronized(seekSequenceLock) {
        lastSeekSeq = maxOf(lastSeekSeq + 1, bootNanos())
        lastSeekSeq
    }

    private fun Intent.putSeekStart(start: SeekStart) {
        putExtra(WalkTrackingService.EXTRA_SEEK_CHAIN, SeekChainCodec.encode(start.chain))
        putExtra(WalkTrackingService.EXTRA_SEEK_ACTIVE_INDEX, start.activeIndex)
        putExtra(WalkTrackingService.EXTRA_SEEK_DURATION_MINUTES, start.durationMinutes)
        start.tintHex?.let { putExtra(WalkTrackingService.EXTRA_SEEK_TINT_HEX, it) }
        putExtra(WalkTrackingService.EXTRA_SEEK_SEED, start.seed)
        putExtra(WalkTrackingService.EXTRA_SEEK_SEEDED_AT, start.seededAtEpochMillis)
        start.intention?.let { putExtra(WalkTrackingService.EXTRA_SEEK_INTENTION, it) }
        start.nextPulseDueAtMillis?.let { putExtra(WalkTrackingService.EXTRA_SEEK_PULSE_DUE_AT, it) }
        putSeekSonar(start.sonar)
    }

    private fun Intent.putSeekSonar(settings: SeekSonarSettings) {
        putExtra(WalkTrackingService.EXTRA_SEEK_SONAR_ENABLED, settings.sonarEnabled)
        putExtra(WalkTrackingService.EXTRA_SEEK_SONAR_VOLUME, settings.sonarVolume)
        putExtra(WalkTrackingService.EXTRA_SEEK_SOUNDS_ENABLED, settings.soundsEnabled)
    }

    /**
     * A card's or the chip's command to the honor walk's session in
     * `:tracker`. Fire-and-forget, like every other walk action: the
     * service drops it with no live pipeline, or when the OS redelivers it.
     */
    fun sendHonorCommand(command: HonorCommand) {
        val intent = baseIntent(WalkTrackingService.ACTION_HONOR_COMMAND).apply {
            putExtra(WalkTrackingService.EXTRA_HONOR_COMMAND_SEQ, honorCommandSequence.next())
            when (command) {
                is HonorCommand.TogglePlayback -> {
                    putExtra(WalkTrackingService.EXTRA_HONOR_COMMAND, WalkTrackingService.HONOR_COMMAND_TOGGLE_PLAYBACK)
                    putExtra(WalkTrackingService.EXTRA_HONOR_MOMENT_ID, command.momentId)
                }
                is HonorCommand.PauseResume -> {
                    putExtra(WalkTrackingService.EXTRA_HONOR_COMMAND, WalkTrackingService.HONOR_COMMAND_PAUSE_RESUME)
                    putExtra(WalkTrackingService.EXTRA_HONOR_MOMENT_ID, command.momentId)
                }
                is HonorCommand.Scrub -> {
                    putExtra(WalkTrackingService.EXTRA_HONOR_COMMAND, WalkTrackingService.HONOR_COMMAND_SCRUB)
                    putExtra(WalkTrackingService.EXTRA_HONOR_MOMENT_ID, command.momentId)
                    putExtra(WalkTrackingService.EXTRA_HONOR_SCRUB_FRACTION, command.fraction)
                }
                is HonorCommand.Skip -> {
                    putExtra(WalkTrackingService.EXTRA_HONOR_COMMAND, WalkTrackingService.HONOR_COMMAND_SKIP)
                    putExtra(WalkTrackingService.EXTRA_HONOR_MOMENT_ID, command.momentId)
                }
                HonorCommand.CycleRate ->
                    putExtra(WalkTrackingService.EXTRA_HONOR_COMMAND, WalkTrackingService.HONOR_COMMAND_CYCLE_RATE)
                is HonorCommand.PlayReply -> {
                    putExtra(WalkTrackingService.EXTRA_HONOR_COMMAND, WalkTrackingService.HONOR_COMMAND_PLAY_REPLY)
                    putExtra(WalkTrackingService.EXTRA_HONOR_MOMENT_ID, command.momentId)
                }
            }
        }
        safeStartService(intent, WalkTrackingService.ACTION_HONOR_COMMAND)
    }

    /**
     * One of the UI's two audio gates, for `:tracker`'s walk audio arbiter
     * (plan U18). A start carries [token], a Binder made for it, in a
     * Bundle, so `:tracker` can link to its death. Fire-and-forget: the
     * service drops it with no live pipeline, or when the OS redelivers it.
     */
    fun publishUiAudioGate(kind: UiAudioGateKind, held: Boolean, seq: Long, token: IBinder?) {
        safeStartService(uiAudioGateIntent(kind, held, seq, token), WalkTrackingService.ACTION_UI_AUDIO_GATE)
    }

    internal fun uiAudioGateIntent(kind: UiAudioGateKind, held: Boolean, seq: Long, token: IBinder?): Intent =
        baseIntent(WalkTrackingService.ACTION_UI_AUDIO_GATE).apply {
            putExtra(WalkTrackingService.EXTRA_UI_AUDIO_GATE, kind.wireName)
            putExtra(WalkTrackingService.EXTRA_UI_AUDIO_GATE_HELD, held)
            putExtra(WalkTrackingService.EXTRA_UI_AUDIO_GATE_SEQ, seq)
            if (token != null) {
                val bundle = Bundle().apply { putBinder(WalkTrackingService.UI_AUDIO_GATE_TOKEN_KEY, token) }
                putExtra(WalkTrackingService.EXTRA_UI_AUDIO_GATE_TOKEN, bundle)
            }
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

/**
 * The Honor commands' sequence numbers, which must keep rising across UI
 * restarts: the session applies a number only past the last one it applied,
 * so a number reused after a restart would drop a real tap. Kept in the UI
 * process's own preferences file; `:tracker` never reads it.
 */
internal class HonorCommandSequence(
    private val context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /**
     * The wall clock is a floor under the stored number, so a write that an
     * `apply()` never flushed before a kill still can't hand out a number
     * already used: the clock has moved past it by the next launch.
     */
    @Synchronized
    fun next(): Long {
        val next = maxOf(prefs.getLong(KEY_LAST, 0L) + 1, nowMillis())
        prefs.edit { putLong(KEY_LAST, next) }
        return next
    }

    private companion object {
        const val PREFS_NAME = "honor_commands"
        const val KEY_LAST = "last_seq"
    }
}
