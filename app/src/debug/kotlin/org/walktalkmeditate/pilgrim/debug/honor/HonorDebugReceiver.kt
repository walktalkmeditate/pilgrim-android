// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.debug.honor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mapbox.common.TileStore
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.OfflineManager
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.MapboxTileRegionLoader
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesCalibration
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.domain.honor.OwnWalkWayBuilder
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
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
 * the replay under `WayReplayer`, as local row ids, stage and own-walk Way
 * ids, counts, fracs, byte sizes, and phases only: never titles,
 * coordinates, transcripts, or share ids.
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
 * Replay a stored Way in place of a walk's route with `--es way <way id>`:
 * a downloaded stage (`pilgrimage:<route>:<n>`) or any other Way the store
 * holds. Name exactly one of `walk` and `way`. HONOR_BEGIN begins an own
 * walk only, so begin a stage from its page in the app. Any replay takes
 * two more extras:
 * - `--ef pace <m/s>` re-times the route at that constant pace, one fix
 *   every 1–2 s, evenly spaced along the line. Without it the route plays
 *   at its recorded pace. A stage's is the dataset's synthesized clock
 *   (hours a stage), with points up to kilometres apart, so give a stage a
 *   pace.
 * - `--ef from <frac> --ef to <frac>` (each 0 to 1, from before to,
 *   defaulting to 0 and 1) play only that part of the line, from a fix
 *   exactly at `from` to one exactly at `to`: join a stage partway
 *   ("continue from where you stopped") or finish near its end (arrival).
 *   A replay dies with a killed `:tracker`; start it again from the
 *   progress frac HONOR_DUMP reports.
 * ```
 * adb shell am broadcast -p org.walktalkmeditate.pilgrim.debug -a org.walktalkmeditate.pilgrim.debug.HONOR_REPLAY_START --es way pilgrimage:camino-frances:0 --ef pace 6 --ef from 0.4 --ef to 1
 * ```
 * How fast: the walk takes every replayed fix at any pace. It has no speed
 * or jump filter, only the 20 m accuracy gate (a replayed fix claims 5 m),
 * and paced fixes come 1–2 s apart, never closer than the 1 s its location
 * request allows. Honor is what a fast pace outruns: a fix more than 300 m
 * past its progress reads as off the Way, a moment needs a fix within 42 m
 * (a voice) or 60 m, and arrival needs 3 fixes in a row within 30 m of the
 * end. With a fix every 2 s, arrival binds first: 7 m/s is the fastest
 * pace with every fix accepted and the arrival still counted
 * (`WayReplayPaceLimitTest`; real stages arrive at 7.4 and miss at 7.6).
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
 * Offline maps (Stage 21-3, spec D C3 §14). Clear both caches a stage can
 * draw from offline without a saved region: the map's own cache, which
 * online viewing fills, and the tile store's ambient cache, where a removed
 * region's tiles go. Run it before every airplane-mode check, so the check
 * proves the saved region. Refused while a map save runs, since clearing
 * blocks the store:
 * ```
 * adb shell am broadcast -p org.walktalkmeditate.pilgrim.debug -a org.walktalkmeditate.pilgrim.debug.HONOR_TILES_CLEAR_CACHE
 * ```
 * Report every saved region (its id, whether complete, its resource counts,
 * bytes, whether it expires, and the first 8 characters of its corridor
 * hash), every style pack, the summed region and pack bytes, the installed
 * route's measured bytes per pack, and the size on disk of `files/.mapbox/`
 * and each folder in it, the figure spec D2's measurement compares against:
 * ```
 * adb shell am broadcast -p org.walktalkmeditate.pilgrim.debug -a org.walktalkmeditate.pilgrim.debug.HONOR_TILES_REPORT
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

    @Inject lateinit var tiles: Provider<PilgrimageTilesManager>

    @Inject lateinit var packages: Provider<PilgrimagePackageManager>

    @Inject lateinit var tilesCalibration: Provider<PilgrimageTilesCalibration>

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val reference = intent.getStringExtra(EXTRA_WALK)
        when (intent.action) {
            ACTION_LIST -> runCommand { list() }
            ACTION_BEGIN -> runCommand { begin(appContext, reference) }
            ACTION_EXPORT_GPX -> runCommand { exportGpx(appContext, reference) }
            ACTION_DUMP -> runCommand { dump(reference) }
            ACTION_COMMAND -> runCommand { command(intent) }
            ACTION_TILES_CLEAR_CACHE -> runCommand { clearMapCaches() }
            ACTION_TILES_REPORT -> runCommand { tilesReport(appContext) }
        }
    }

    /**
     * Both on the main thread, which `clearData` requires; each result logged
     * as it lands. The save check runs again before the ambient clear, which
     * blocks the store (spec D C3 §14.2): a save can start while the map
     * cache clears.
     */
    private suspend fun clearMapCaches() = withContext(Dispatchers.Main) {
        if (refusedForASave("refused")) return@withContext
        val mapData = mapboxAnswer { done -> MapboxMap.clearData { done(it) } }
        Log.i(TAG, "tiles clear-cache: map cache " + if (mapData.isValue) "cleared" else "not cleared (${mapData.error})")
        if (refusedForASave("stopped before the ambient cache")) return@withContext
        val ambient = mapboxAnswer { done -> TileStore.create().clearAmbientCache { done(it) } }
        val cleared = ambient.value
        Log.i(TAG, "tiles clear-cache: ambient cache " + if (cleared != null) "cleared, $cleared bytes" else "not cleared (${ambient.error?.type})")
    }

    private fun refusedForASave(what: String): Boolean {
        val saving = tiles.get().isSaving
        if (saving) Log.w(TAG, "tiles clear-cache $what: a map save is running; cancel it first")
        return saving
    }

    /** The store's own answers, read on the main thread through the default store the loader and the map use. */
    private suspend fun tilesReport(context: Context) {
        val lines = withContext(Dispatchers.Main) { regionLines() + packLines() }
        val routeId = packages.get().installedRoute()?.routeId
        val bytesPerPack = routeId?.let { tilesCalibration.get().stored(it) }
        val disk = withContext(Dispatchers.IO) { diskLines(File(context.filesDir, MAPBOX_DIRECTORY)) }
        val calibration = when {
            routeId == null -> "no route installed"
            bytesPerPack == null -> "$routeId: none measured, the seed applies"
            else -> "$routeId: $bytesPerPack bytes per pack"
        }
        (lines + "  calibration: $calibration" + disk).forEach { Log.i(TAG, it) }
    }

    private suspend fun regionLines(): List<String> {
        val store = TileStore.create()
        val answer = mapboxAnswer { done -> store.getAllTileRegions { done(it) } }
        val regions = answer.value ?: return listOf("tiles report: the regions read failed (${answer.error?.type})")
        val lines = mutableListOf("tiles report: ${regions.size} regions")
        for (region in regions.sortedBy { it.id }) {
            val metadata = mapboxAnswer { done -> store.getTileRegionMetadata(region.id) { done(it) } }
            val summary = MapboxTileRegionLoader.summary(region, metadata)
            lines += "  region ${summary.id} complete=${summary.isComplete} " +
                "${summary.completedResourceCount}/${summary.requiredResourceCount} resources, " +
                "${summary.completedResourceSize} bytes, expires=${region.expires != null}, " +
                "hash=${summary.corridorHash.orEmpty().take(HASH_PREFIX).ifEmpty { "none" }}"
        }
        lines += "  regions total: ${regions.sumOf { it.completedResourceSize }} bytes"
        return lines
    }

    private suspend fun packLines(): List<String> {
        val answer = mapboxAnswer { done -> OfflineManager().getAllStylePacks { done(it) } }
        val packs = answer.value ?: return listOf("  the style packs read failed (${answer.error?.type})")
        return packs.map { pack ->
            val complete = MapboxTileRegionLoader.isComplete(pack.completedResourceCount, pack.requiredResourceCount)
            "  pack ${pack.styleURI} complete=$complete " +
                "${pack.completedResourceCount}/${pack.requiredResourceCount} resources, ${pack.completedResourceSize} bytes"
        } + "  packs total: ${packs.sumOf { it.completedResourceSize }} bytes"
    }

    /** What D2's verdict reads: the folder the store and the map cache share, and each folder in it. */
    private fun diskLines(mapbox: File): List<String> {
        fun bytes(file: File) = file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (!mapbox.isDirectory) return listOf("  disk: no files/$MAPBOX_DIRECTORY/ yet")
        val children = mapbox.listFiles().orEmpty().sortedBy { it.name }.map { "${it.name} ${bytes(it)}" }
        return listOf("  disk: files/$MAPBOX_DIRECTORY/ ${bytes(mapbox)} bytes; " + children.joinToString("; "))
    }

    /** A Mapbox callback's answer, which lands on a worker thread, resumed where the caller is. */
    private suspend fun <T> mapboxAnswer(call: (done: (T) -> Unit) -> Unit): T =
        suspendCancellableCoroutine { continuation -> call { continuation.resume(it) } }

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
        const val ACTION_TILES_CLEAR_CACHE = "org.walktalkmeditate.pilgrim.debug.HONOR_TILES_CLEAR_CACHE"
        const val ACTION_TILES_REPORT = "org.walktalkmeditate.pilgrim.debug.HONOR_TILES_REPORT"
        const val ACTION_REPLAY_START = "org.walktalkmeditate.pilgrim.debug.HONOR_REPLAY_START"
        const val ACTION_REPLAY_STOP = "org.walktalkmeditate.pilgrim.debug.HONOR_REPLAY_STOP"
        const val EXTRA_WALK = "walk"
        const val EXTRA_WAY = "way"
        const val EXTRA_PACE = "pace"
        const val EXTRA_FROM = "from"
        const val EXTRA_TO = "to"
        const val EXTRA_COMMAND = "cmd"
        const val EXTRA_MOMENT = "moment"
        internal const val TAG = "HonorDebug"
        private const val GPX_DIRECTORY = "honor-gpx"
        private const val LIST_LIMIT = 20
        private const val MAPBOX_DIRECTORY = ".mapbox"
        private const val HASH_PREFIX = 8
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
                val replay = ways.replay(intent) ?: return@runCommand
                replayer.start(replay.name, replay.route, replay.timing)
            }
            HonorDebugReceiver.ACTION_REPLAY_STOP -> runCommand { replayer.stop() }
        }
    }
}

/**
 * Finds a walk by Room id or uuid, and builds its own-walk Way as
 * [BeginHonorWalk] does; and finds the route a replay names.
 */
class HonorDebugWays @Inject constructor(
    private val repository: WalkRepository,
    private val recordingFiles: VoiceRecordingFileSystem,
    private val wayStore: WayStore,
) {

    /** What HONOR_REPLAY_START plays. [name] is the source as the log may print it. */
    data class Replay(val name: String, val route: List<WayPoint>, val timing: WayReplayTimeline.Timing)

    /**
     * The route [intent]'s extras name, a walk's own or a stored Way's,
     * with the pace and window they ask for. Null, with the reason logged,
     * when they name both or neither, nothing that loads, or a timing
     * [WayReplayTimeline.refusal] refuses.
     */
    suspend fun replay(intent: Intent): Replay? {
        val walkReference = intent.getStringExtra(HonorDebugReceiver.EXTRA_WALK)
        val wayId = intent.getStringExtra(HonorDebugReceiver.EXTRA_WAY)
        if ((walkReference == null) == (wayId == null)) {
            Log.w(
                HonorDebugReceiver.TAG,
                "replay refused: name one walk (--es ${HonorDebugReceiver.EXTRA_WALK} <id or uuid>) " +
                    "or one stored Way (--es ${HonorDebugReceiver.EXTRA_WAY} <way id>)",
            )
            return null
        }
        val timing = WayReplayTimeline.Timing(
            paceMetersPerSecond = intent.floatExtra(HonorDebugReceiver.EXTRA_PACE),
            fromFrac = intent.floatExtra(HonorDebugReceiver.EXTRA_FROM) ?: 0.0,
            toFrac = intent.floatExtra(HonorDebugReceiver.EXTRA_TO) ?: 1.0,
        )
        val replay = (if (wayId != null) storedWayReplay(wayId, timing) else ownWalkReplay(walkReference, timing))
            ?: return null
        val refusal = WayReplayTimeline.refusal(replay.route, timing)
        if (refusal != null) {
            Log.w(HonorDebugReceiver.TAG, "replay refused: $refusal")
            return null
        }
        return replay
    }

    private suspend fun ownWalkReplay(reference: String?, timing: WayReplayTimeline.Timing): Replay? {
        val walk = walk(reference) ?: return null
        val way = ownWalkWay(walk)
        if (way == null) {
            Log.w(HonorDebugReceiver.TAG, "replay: walk ${walk.id} has too little route to honor")
            return null
        }
        return Replay(name = "walk ${walk.id}", route = way.route, timing = timing)
    }

    /** The id is logged only once it has loaded as a Way that isn't a share's. */
    private suspend fun storedWayReplay(wayId: String, timing: WayReplayTimeline.Timing): Replay? {
        if (!WayStore.isValidId(wayId)) {
            Log.w(HonorDebugReceiver.TAG, "replay: --es ${HonorDebugReceiver.EXTRA_WAY} is not a Way id")
            return null
        }
        val way = withContext(Dispatchers.IO) { wayStore.load(wayId) }
        if (way == null) {
            Log.w(HonorDebugReceiver.TAG, "replay: the store holds no readable Way with that id")
            return null
        }
        val name = if (way.source is WaySource.Share) "a shared way" else "way ${way.id}"
        return Replay(name = name, route = way.route, timing = timing)
    }

    /** A `--ef` extra; one of another type reads as NaN, which the timing refuses. */
    private fun Intent.floatExtra(name: String): Double? =
        if (hasExtra(name)) getFloatExtra(name, Float.NaN).toDouble() else null

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
