// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.debug.honor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.honor.HonorCardStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorDao
import org.walktalkmeditate.pilgrim.data.honor.HonorMomentStateEntity
import org.walktalkmeditate.pilgrim.data.honor.dismissedAt
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.domain.honor.OwnWalkWayBuilder
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.honor.BeginHonorWalk
import org.walktalkmeditate.pilgrim.honor.HonorWayChoice
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks
import org.walktalkmeditate.pilgrim.walk.HonorSettings
import org.walktalkmeditate.pilgrim.walk.WalkActionPublisher
import org.walktalkmeditate.pilgrim.walk.honor.HonorCommand

/**
 * adb commands for walking an Honor walk from a desk (plan U19; U20's device
 * proof runs on them). Debug-only and DUMP-protected, so only adb can send
 * them and release has none of it. Everything logs under `HonorDebug`, and
 * the replay under `WayReplayer`, as local row ids, counts, fracs, and
 * phases only: never titles, coordinates, transcripts, or share ids.
 *
 * `<walk>` is a walk's Room id or its uuid. `-p` addresses the app, so a
 * command reaches it even while it is in the background.
 *
 * Setup, once per install: let the debug app mock locations (or pick it in
 * Developer options → Select mock location app) and hold fine location.
 * ```
 * adb shell appops set org.walktalkmeditate.pilgrim.debug android:mock_location allow
 * adb shell pm grant org.walktalkmeditate.pilgrim.debug android.permission.ACCESS_FINE_LOCATION
 * ```
 *
 * List finished walks to honor, newest first:
 * ```
 * adb shell am broadcast -p org.walktalkmeditate.pilgrim.debug -a org.walktalkmeditate.pilgrim.debug.HONOR_LIST
 * ```
 *
 * Walk a walk's Way on the phone: start the replay first, so the walk's first
 * fix is already on the Way, then Begin with the app on screen (Android
 * refuses a location foreground service started from the background). Begin
 * is the production use case, [BeginHonorWalk], with the Sounds switch read
 * as the walk screen reads it; the screen's weather fetch and greeting don't run.
 * ```
 * adb shell am broadcast -p org.walktalkmeditate.pilgrim.debug -a org.walktalkmeditate.pilgrim.debug.HONOR_REPLAY_START --es walk <walk>
 * adb shell monkey -p org.walktalkmeditate.pilgrim.debug 1
 * adb shell am broadcast -p org.walktalkmeditate.pilgrim.debug -a org.walktalkmeditate.pilgrim.debug.HONOR_BEGIN --es walk <walk>
 * adb shell am broadcast -p org.walktalkmeditate.pilgrim.debug -a org.walktalkmeditate.pilgrim.debug.HONOR_REPLAY_STOP
 * ```
 * Mock mode goes off when the replay stops or plays out, so the next real
 * fix places the walker back at the desk: finish the walk first.
 *
 * Dump the Honor state of the walk in progress, or of any walk:
 * ```
 * adb shell am broadcast -p org.walktalkmeditate.pilgrim.debug -a org.walktalkmeditate.pilgrim.debug.HONOR_DUMP [--es walk <walk>]
 * ```
 *
 * Send the honor walk in progress a chip or card command, as U22's chip
 * will, through [WalkActionPublisher]: `toggle` (pause or resume the held
 * voice), `skip`, `rate` (1x → 1.25x → 1.5x → 2x), or `reply` (play the
 * earlier reply to a voice). `toggle` and `reply` act on the voice the
 * session holds unless `--es moment <voice-n>` names one.
 * ```
 * adb shell am broadcast -p org.walktalkmeditate.pilgrim.debug -a org.walktalkmeditate.pilgrim.debug.HONOR_COMMAND --es cmd toggle|skip|rate|reply [--es moment <voice-n>]
 * ```
 *
 * Export a walk's Way as GPX for the emulator, then load the file in its
 * Extended controls → Location and play it:
 * ```
 * adb shell am broadcast -p org.walktalkmeditate.pilgrim.debug -a org.walktalkmeditate.pilgrim.debug.HONOR_EXPORT_GPX --es walk <walk>
 * adb pull /sdcard/Android/data/org.walktalkmeditate.pilgrim.debug/files/honor-gpx/walk-<uuid>.gpx
 * ```
 *
 * Read the results with `adb logcat -s HonorDebug WayReplayer`.
 */
@AndroidEntryPoint
class HonorDebugReceiver : BroadcastReceiver() {

    @Inject lateinit var ways: HonorDebugWays

    @Inject lateinit var repository: WalkRepository

    @Inject lateinit var beginHonorWalk: BeginHonorWalk

    @Inject lateinit var soundsPreferences: SoundsPreferencesRepository

    @Inject lateinit var honorDao: HonorDao

    @Inject lateinit var wayStore: WayStore

    @Inject lateinit var actionPublisher: WalkActionPublisher

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val reference = intent.getStringExtra(EXTRA_WALK)
        when (intent.action) {
            ACTION_LIST -> runCommand { list() }
            ACTION_BEGIN -> runCommand { begin(appContext, reference) }
            ACTION_EXPORT_GPX -> runCommand { exportGpx(appContext, reference) }
            ACTION_DUMP -> runCommand { dump(reference) }
            ACTION_COMMAND -> runCommand { command(intent) }
        }
    }

    private suspend fun command(intent: Intent) {
        val command = when (val name = intent.getStringExtra(EXTRA_COMMAND)) {
            "toggle" -> HonorCommand.TogglePlayback(heldOrNamedVoice(intent) ?: return)
            "skip" -> HonorCommand.Skip(heldOrNamedVoice(intent) ?: return)
            "rate" -> HonorCommand.CycleRate
            "reply" -> HonorCommand.PlayReply(heldOrNamedVoice(intent) ?: return)
            else -> {
                Log.w(TAG, "command: unknown '$name'; use toggle, skip, rate, or reply")
                return
            }
        }
        actionPublisher.sendHonorCommand(command)
        Log.i(TAG, "command: sent ${command::class.simpleName}")
    }

    private suspend fun heldOrNamedVoice(intent: Intent): String? {
        intent.getStringExtra(EXTRA_MOMENT)?.let { return it }
        val walk = repository.getActiveWalk()
        if (walk == null) {
            Log.w(TAG, "command: no walk in progress")
            return null
        }
        val held = honorDao.getSession(walk.id)?.playingMomentId
        if (held == null) Log.w(TAG, "command: no voice is held; name one with --es $EXTRA_MOMENT <voice-n>")
        return held
    }

    private suspend fun list() {
        val finished = repository.allWalks().filter { it.endTimestamp != null }.take(LIST_LIMIT)
        if (finished.isEmpty()) Log.i(TAG, "list: no finished walks")
        finished.forEach { Log.i(TAG, "list: walk ${it.id} ${it.uuid}") }
    }

    private suspend fun begin(context: Context, reference: String?) {
        val source = ways.walk(reference) ?: return
        if (!PermissionChecks.isFineLocationGranted(context)) {
            Log.w(TAG, "begin refused: fine location is not granted")
            return
        }
        val settings = HonorSettings.atStart(
            honorVoicesEnabled = true,
            soundsEnabled = soundsPreferences.soundsEnabled.value,
        )
        val result = try {
            beginHonorWalk(BeginHonorWalk.Request(HonorWayChoice.OwnWalk(source.id), intention = null, settings = settings))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "begin: the start failed (${e::class.simpleName}); is the app on screen?")
            return
        }
        when (result) {
            is BeginHonorWalk.Result.Started -> Log.i(TAG, "begin: walk ${result.walk.id} honors walk ${source.id}")
            is BeginHonorWalk.Result.Refused -> Log.w(TAG, "begin refused: ${result.reason}")
        }
    }

    private suspend fun exportGpx(context: Context, reference: String?) {
        val walk = ways.walk(reference) ?: return
        val way = ways.ownWalkWay(walk) ?: run {
            Log.w(TAG, "export: walk ${walk.id} has too little route to honor")
            return
        }
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, GPX_DIRECTORY)
            directory.mkdirs()
            File(directory, way.id.replace(':', '-') + ".gpx").apply { writeText(WayGpxExporter.gpx(way)) }
        }
        Log.i(TAG, "export: ${way.route.size} points to ${file.absolutePath}")
    }

    private suspend fun dump(reference: String?) {
        val walk = if (reference == null) repository.getActiveWalk() else ways.walk(reference)
        if (walk == null) {
            if (reference == null) Log.i(TAG, "dump: no walk in progress")
            return
        }
        val lines = mutableListOf("dump: walk ${walk.id}, ${if (walk.endTimestamp == null) "in progress" else "finished"}")
        val session = honorDao.getSession(walk.id)
        lines += if (session != null) sessionLines(session) else listOf("  no live session")
        val staged = withContext(Dispatchers.IO) { wayStore.staged(walk.uuid) }
        val link = withContext(Dispatchers.IO) { wayStore.wayLink(walk.uuid) }
        val way = staged ?: link?.let { withContext(Dispatchers.IO) { wayStore.load(it.wayId) } }
        lines += momentLines(way, honorDao.getMomentStates(walk.id), honorDao.getCardStates(walk.id))
        lines += "  staged way: ${if (staged != null) "yes" else "no"}"
        lines += "  link: " + when {
            link == null -> "none"
            link.theirSeconds != null -> "yes, with arrival"
            else -> "yes, no arrival"
        }
        lines += "  marker: ${honorDao.getMarker(walk.uuid)?.finishKind ?: "none"}"
        lines.forEach { Log.i(TAG, it) }
    }

    private fun sessionLines(session: HonorSessionEntity): List<String> {
        val way = if (session.sourceKind == HonorSourceKind.OWN_WALK) " way=${session.wayId}" else ""
        return listOf(
            "  session: phase=${session.phase} source=${session.sourceKind}$way " +
                "voices=${session.voicesEnabled} softTap=${session.softTapEnabled} finish=${session.finishKind}",
            "  anchor: startFrac=${frac(session.startFrac)} fallback=${session.anchoredByFallback}",
            "  progress: frac=${frac(session.progressFrac)} highWater=${frac(session.progressHighWater)} " +
                "walked=${frac(session.walkedFrac)} offWay=${session.offWaySince != null} " +
                "arrivalInsideFixes=${session.arrivalInsideFixes}",
            "  voice: playing=${session.playingMomentId} paused=${session.voicePaused} rate=${session.voiceRate}",
            "  gates: generation=${session.gateGeneration} lastCommandSeq=${session.lastCommandSeq}",
        )
    }

    /** The Way's moments in order with their fracs when it loads; else the state rows alone. */
    private fun momentLines(
        way: Way?,
        states: List<HonorMomentStateEntity>,
        cards: List<HonorCardStateEntity>,
    ): List<String> {
        val stateById = states.associateBy { it.momentId }
        val cardById = cards.associateBy { it.momentId }
        val moments = way?.moments?.map { it.id to it.frac } ?: states.map { it.momentId to null }
        return moments.map { (id, momentFrac) ->
            val parts = mutableListOf("  moment $id")
            if (momentFrac != null) parts += "@${frac(momentFrac)}"
            val state = stateById[id]
            parts += if (state?.reachedAt != null) "reached" else "ahead"
            state?.queuePosition?.let { parts += "queued#$it" }
            when {
                state?.voiceEnd != null -> parts += "voice=${state.voiceEnd}"
                state?.voiceStartedAt != null -> parts += "voice=STARTED"
            }
            if (state?.heard == true) parts += "heard"
            cardById[id]?.let { card ->
                if (card.dismissedAt != null) parts += "dismissed"
                if (card.touched) parts += "touched"
            }
            parts.joinToString(" ")
        }
    }

    private fun frac(value: Double?): String = value?.let { String.format(Locale.US, "%.3f", it) } ?: "none"

    companion object {
        const val ACTION_LIST = "org.walktalkmeditate.pilgrim.debug.HONOR_LIST"
        const val ACTION_BEGIN = "org.walktalkmeditate.pilgrim.debug.HONOR_BEGIN"
        const val ACTION_EXPORT_GPX = "org.walktalkmeditate.pilgrim.debug.HONOR_EXPORT_GPX"
        const val ACTION_DUMP = "org.walktalkmeditate.pilgrim.debug.HONOR_DUMP"
        const val ACTION_COMMAND = "org.walktalkmeditate.pilgrim.debug.HONOR_COMMAND"
        const val ACTION_REPLAY_START = "org.walktalkmeditate.pilgrim.debug.HONOR_REPLAY_START"
        const val ACTION_REPLAY_STOP = "org.walktalkmeditate.pilgrim.debug.HONOR_REPLAY_STOP"
        const val EXTRA_WALK = "walk"
        const val EXTRA_COMMAND = "cmd"
        const val EXTRA_MOMENT = "moment"
        internal const val TAG = "HonorDebug"
        private const val GPX_DIRECTORY = "honor-gpx"
        private const val LIST_LIMIT = 20
    }
}

/**
 * The replay commands of [HonorDebugReceiver]'s KDoc. Declared in
 * `:tracker`, so the replay runs beside the walk that reads its fixes and
 * the [WayReplayer] here is the one the service's start hook reaches.
 */
@AndroidEntryPoint
class HonorReplayReceiver : BroadcastReceiver() {

    @Inject lateinit var ways: HonorDebugWays

    @Inject lateinit var replayer: WayReplayer

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            HonorDebugReceiver.ACTION_REPLAY_START -> runCommand {
                val walk = ways.walk(intent.getStringExtra(HonorDebugReceiver.EXTRA_WALK)) ?: return@runCommand
                val way = ways.ownWalkWay(walk)
                if (way == null) {
                    Log.w(HonorDebugReceiver.TAG, "replay: walk ${walk.id} has too little route to honor")
                } else {
                    replayer.start(walk.id, way.route)
                }
            }
            HonorDebugReceiver.ACTION_REPLAY_STOP -> runCommand { replayer.stop() }
        }
    }
}

/** Finds a walk by Room id or uuid, and builds its own-walk Way as [BeginHonorWalk] does. */
class HonorDebugWays @Inject constructor(
    private val repository: WalkRepository,
    private val recordingFiles: VoiceRecordingFileSystem,
) {

    /** Logs why when there is no such walk. */
    suspend fun walk(reference: String?): Walk? {
        val trimmed = reference?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            Log.w(HonorDebugReceiver.TAG, "name a walk: --es ${HonorDebugReceiver.EXTRA_WALK} <id or uuid>")
            return null
        }
        val walk = trimmed.toLongOrNull()?.let { repository.getWalk(it) } ?: repository.walkByUuid(trimmed)
        if (walk == null) Log.w(HonorDebugReceiver.TAG, "no walk $trimmed")
        return walk
    }

    suspend fun ownWalkWay(walk: Walk): Way? {
        val input = OwnWalkWayBuilder.Input.fromRows(
            walk = walk,
            samples = repository.locationSamplesFor(walk.id),
            recordings = repository.voiceRecordingsFor(walk.id),
            photos = repository.photosFor(walk.id),
            waypoints = repository.waypointsFor(walk.id),
            events = repository.eventsFor(walk.id),
        )
        return withContext(Dispatchers.IO) {
            OwnWalkWayBuilder.make(
                input,
                { recordingFiles.fileSizeBytes(it.fileRelativePath) > 0 },
                ZoneId.systemDefault(),
                Locale.getDefault(),
            )
        }
    }
}

/** Off the main thread and past `onReceive`'s return; a failure is logged, never thrown at the broadcast. */
private fun BroadcastReceiver.runCommand(command: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try {
            command()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(HonorDebugReceiver.TAG, "command failed (${e::class.simpleName})")
        } finally {
            pending.finish()
        }
    }
}
