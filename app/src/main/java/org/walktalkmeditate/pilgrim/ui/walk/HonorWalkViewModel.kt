// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.audio.WaveformGenerator
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.honor.HonorCardStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorDao
import org.walktalkmeditate.pilgrim.data.honor.HonorMomentStateEntity
import org.walktalkmeditate.pilgrim.data.honor.dismissedAt
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.isStagedPerWalk
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.honor.HonorPersistence
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase
import org.walktalkmeditate.pilgrim.domain.honor.HonorTuning
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.wgs84MidLatitudeMeters
import org.walktalkmeditate.pilgrim.honor.HonorReplies
import org.walktalkmeditate.pilgrim.honor.HonorStageHandoff
import org.walktalkmeditate.pilgrim.honor.HonorWayChoice
import org.walktalkmeditate.pilgrim.honor.OwnWalkWays
import org.walktalkmeditate.pilgrim.ui.honor.COMMAND_CONFIRM_WINDOW_MILLIS
import org.walktalkmeditate.pilgrim.ui.honor.CardTouches
import org.walktalkmeditate.pilgrim.ui.honor.DeviceHeading
import org.walktalkmeditate.pilgrim.ui.honor.HONOR_ARRIVAL_CARD_ID
import org.walktalkmeditate.pilgrim.ui.honor.HonorArrival
import org.walktalkmeditate.pilgrim.ui.honor.HonorArrivalSummary
import org.walktalkmeditate.pilgrim.ui.honor.HonorCardQueue
import org.walktalkmeditate.pilgrim.ui.honor.HonorCards
import org.walktalkmeditate.pilgrim.ui.honor.HonorVoiceView
import org.walktalkmeditate.pilgrim.ui.honor.PendingVoiceCommand
import org.walktalkmeditate.pilgrim.ui.honor.WayRelation
import org.walktalkmeditate.pilgrim.ui.honor.heldOver
import org.walktalkmeditate.pilgrim.ui.honor.softTapCaptionMeters
import org.walktalkmeditate.pilgrim.ui.honor.voiceDurationSeconds
import org.walktalkmeditate.pilgrim.ui.walk.map.HonorWayLine
import org.walktalkmeditate.pilgrim.ui.walk.map.WayPin
import org.walktalkmeditate.pilgrim.ui.walk.map.wayPins
import org.walktalkmeditate.pilgrim.walk.WalkActionPublisher
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.honor.HonorCommand
import org.walktalkmeditate.pilgrim.walk.honor.HonorMediaFiles
import org.walktalkmeditate.pilgrim.walk.honor.honorEngineSeconds
import org.walktalkmeditate.pilgrim.walk.honor.nextVoiceRate
import org.walktalkmeditate.pilgrim.walk.honor.voiceOriginIndex

/** What the walk map draws of the Way: built from the source walk before Start, read from Room after. */
@Immutable
data class HonorWalkUiState(
    val way: Way,
    val line: HonorWayLine,
    val pins: List<WayPin>,
    /** The walk's Honor session; null on the pre-walk screen, before Start. */
    val session: HonorLiveSession?,
)

/** The live rows `:tracker` writes for the walk in progress, as the walk screen reads them (plan U17). */
@Immutable
data class HonorLiveSession(
    val walkId: Long,
    val anchor: HonorAnchor,
    val progressFrac: Double,
    val reachedMomentIds: Set<String>,
    /** Marked when a voice is handed to the player, before any sound (parity spec C §9). */
    val heardVoiceIds: Set<String>,
    /** The voice the player holds (iOS `activeVoice`); null while nothing plays, or a reply does. */
    val playingMomentId: String?,
    val voicePaused: Boolean,
    val arrived: Boolean,
)

/** Where the engine joined the walker to the Way; the companion's clock runs from here. */
@Immutable
data class HonorAnchor(
    /** Null until the first accepted fix anchors the walker. */
    val startFrac: Double?,
    /** Begin found no Way within 60 m: frac 0 until an on-Way fix re-anchors. */
    val anchoredByFallback: Boolean,
    val anchorActiveSeconds: Double,
    val companionT0Seconds: Double,
)

/**
 * The files behind the top card, resolved once each time a card comes to
 * the top and again after each saved recording and each reply filed (iOS
 * `resolveFiles(for:)`, `ActiveWalkView+Honor.swift:113-133@7c200bf`): any
 * other file that lands while the card shows appears only when it next
 * comes to the top.
 */
class HonorCardMedia(
    val momentId: String,
    /** The voice's bars; null for a voice whose file is missing or won't read, which shows the placeholder bar. */
    val waveform: FloatArray?,
    /** The photo to load; null for a shared photo not on this phone, which shows the parchment plate. */
    val photoUri: String?,
    /** An earlier reply to this voice, filed under this Way, whose recording is still here. */
    val hasEarlierReply: Boolean,
)

/** The top place card (iOS `WayPlaceCard`'s inputs, `ActiveWalkView+Honor.swift:76-105@7c200bf`). */
@Immutable
data class HonorPlaceCard(
    val moment: WayMoment,
    val pendingCount: Int,
    val isStage: Boolean,
    /**
     * A waypoint without a label keeps iOS's empty kicker line on any Way
     * but the walker's own, whose label-less waypoints drop it (owner
     * decision 4; shared spec S4 §10.2).
     */
    val keepsEmptyKicker: Boolean,
    /** Straight-line metres from the walker's last fix; null before the first fix. */
    val distanceMeters: Double?,
    /** Degrees from the walker's heading to the place; null without both a fix and a settled compass. */
    val tick: Double?,
    /** Any focus, as iOS reads it, not this card's (pilgrim-ios #108, matched). */
    val isFocused: Boolean,
    val isPlaying: Boolean,
    val isPaused: Boolean,
    /** The player's clock, shown only on the card whose voice is the one held. */
    val elapsedSeconds: Double,
    val rate: Float,
    /** Null until this card's files are resolved. */
    val media: HonorCardMedia?,
)

/** One card at a time: the arrival card while it is up, else the top place card. */
@Immutable
data class HonorCardsUi(
    val walkId: Long,
    val wayId: String,
    val arrival: HonorArrivalSummary?,
    val place: HonorPlaceCard?,
) {
    /** iOS `isShowingHonorCard`: with neither, every touch belongs to the map. */
    val isShowingCard: Boolean get() = arrival != null || place != null
}

/** The listening chip (iOS `HonorListeningChip`): only while a Way voice is the one held. */
@Immutable
data class HonorListening(val elapsedSeconds: Double, val paused: Boolean)

/** The minimized sheet's Honor parts (parity spec E §10). */
@Immutable
data class HonorSheetStats(
    /** The Way's distance left; null before Begin, which reads "--". */
    val remainingMeters: Double?,
    /** The soft tap's caption, borrowing the stat's slot for 20 s; dark while nothing sets the preference (pilgrim-ios #109). */
    val softTapMeters: Long?,
    val listening: HonorListening?,
)

/**
 * The walk screen's Honor state in the UI process (plan U22, parity spec
 * E §1–§14): the Way, the companion, the pins, the fly-to, the card queue
 * and its cards, the listening chip, the Remaining stat, the soft-tap
 * caption, and the arrival card. Before Start the Way is built from the
 * walk being honored, or read from the store for a shared one, as iOS
 * draws it from the moment the walk screen appears; once the walk runs,
 * everything comes from Room (the session row `:tracker` writes, its
 * moment rows, the UI's own card rows) and from the Ways store, so a
 * restarted UI process draws the same screen (AE1).
 * iOS keeps all of it on its view model in one process.
 *
 * The walker's voice controls go to `:tracker` as commands, never
 * redelivered; each one's result shows at once and gives way to whatever
 * Room says next, or to the persisted state when Room says nothing within
 * [org.walktalkmeditate.pilgrim.ui.honor.COMMAND_CONFIRM_WINDOW_MILLIS].
 * The UI never plays a Way voice itself.
 *
 * With the release flag off nothing here touches Room, the store, or the
 * compass, and no command is sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HonorWalkViewModel internal constructor(
    private val controller: WalkController,
    private val honorDao: HonorDao,
    private val repository: WalkRepository,
    private val wayStore: WayStore,
    private val ownWalkWays: OwnWalkWays,
    private val mediaFiles: HonorMediaFiles,
    private val replies: HonorReplies,
    private val headings: (place: () -> WayCoordinate?) -> Flow<Double?>,
    private val sendCommand: (HonorCommand) -> Unit,
    releaseFlags: ReleaseFlags,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher,
    /** iOS's walk duration tick, the companion's and the player clock's read cadence. */
    private val tickMillis: Long,
    private val loadWaveform: suspend (File) -> FloatArray? = ::cardWaveform,
    private val stageHandoff: HonorStageHandoff = HonorStageHandoff(),
) : ViewModel() {

    @Inject
    constructor(
        controller: WalkController,
        honorDao: HonorDao,
        repository: WalkRepository,
        wayStore: WayStore,
        ownWalkWays: OwnWalkWays,
        mediaFiles: HonorMediaFiles,
        replies: HonorReplies,
        heading: DeviceHeading,
        publisher: WalkActionPublisher,
        releaseFlags: ReleaseFlags,
        clock: Clock,
        stageHandoff: HonorStageHandoff,
    ) : this(
        controller, honorDao, repository, wayStore, ownWalkWays, mediaFiles, replies,
        headings = heading::headings,
        sendCommand = publisher::sendHonorCommand,
        releaseFlags = releaseFlags,
        clock = clock,
        ioDispatcher = Dispatchers.IO,
        tickMillis = COMPANION_TICK_MILLIS,
        stageHandoff = stageHandoff,
    )

    private val enabled = releaseFlags.honor

    private val previewState = MutableStateFlow<HonorWalkUiState?>(null)
    private var previewWay: HonorWayChoice? = null

    @Volatile
    private var latestLive: LiveHonor? = null

    @Volatile
    private var latestQueue: HonorCardQueue? = null

    private val live: Flow<LiveHonor?> = if (!enabled) {
        flowOf(null)
    } else {
        controller.state.map { it.inProgressWalkId() }.distinctUntilChanged()
            .flatMapLatest { walkId -> if (walkId == null) flowOf(null) else observeLive(walkId) }
            .onEach { latestLive = it }
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), replay = 1)
    }

    /**
     * The Way on this walk screen, or null for one with no Way. The
     * session's Way wins once it is in Room; until then (the pre-walk
     * screen, and the moment between Start and its rows landing) the
     * source walk's build stands in.
     */
    val state: StateFlow<HonorWalkUiState?> = if (!enabled) {
        MutableStateFlow(null)
    } else {
        combine(live, previewState) { live, preview -> live?.state ?: preview }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), null)
    }

    /**
     * The companion (parity spec E §3): where the Way's walker stood at this
     * walk's engine time, from Start until the walk ends, never on a stage.
     * It moves in jumps at most every 2 s; in a pause it stands still, as
     * the engine clock does (owner decision 1); while meditating it isn't
     * moved at all, and on the way back it jumps to where the clock, which
     * counts the sitting, puts it. A backgrounded screen stops collecting,
     * so it isn't moved then either.
     */
    val companion: StateFlow<WayCoordinate?> = if (!enabled) {
        MutableStateFlow(null)
    } else {
        val engineClocks = controller.state.map { it.engineClockOnly() }.distinctUntilChanged()
        combine(companionSources(), engineClocks) { source, walk -> source?.let { CompanionInputs(it, walk) } }
            .distinctUntilChanged()
            .flatMapLatest { inputs ->
                when {
                    inputs == null -> flowOf(null)
                    inputs.walk is WalkState.Meditating -> emptyFlow()
                    else -> ticks().map { now -> inputs.coordinateAt(now) }
                }
            }
            .companionJumps(clock::now, COMPANION_JUMP_MILLIS)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), null)
    }

    private val _focus = MutableStateFlow<WayCoordinate?>(null)

    /** A moment the walker asked the map to show (iOS `honorFocus`); null lets the map follow them. */
    val focus: StateFlow<WayCoordinate?> = _focus.asStateFlow()

    private val touches = MutableStateFlow(WalkTouches(walkId = null, CardTouches()))
    private val pendingCommand = MutableStateFlow<PendingVoiceCommand?>(null)
    private val commandMutex = Mutex()
    private var scrubJob: Job? = null
    private var heldScrub: Scrub? = null

    private val queue: Flow<HonorCardQueue?> = if (!enabled) {
        flowOf(null)
    } else {
        queueFlow()
            .onEach { latestQueue = it }
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), replay = 1)
    }

    private val voiceNow: Flow<VoiceNow?> = if (!enabled) {
        flowOf(null)
    } else {
        voiceFlow().shareIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), replay = 1)
    }

    /** The arrival card or the top place card, on a begun honor walk; null on every other screen. */
    val cards: StateFlow<HonorCardsUi?> = if (!enabled) {
        MutableStateFlow(null)
    } else {
        val placement = combine(here(), headingFlow(), _focus, touches) { here, heading, focus, touches ->
            Placement(here, heading, focus, touches)
        }
        combine(live, queue, voiceNow, mediaFlow(), placement) { live, queue, voice, media, placement ->
            live?.let { cardsUi(it, queue, voice, media, placement) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), null)
    }

    /** The minimized sheet's Remaining stat, soft-tap caption, and listening chip on an honor walk screen. */
    val sheet: StateFlow<HonorSheetStats?> = if (!enabled) {
        MutableStateFlow(null)
    } else {
        combine(state, live, voiceNow, softTapMeters()) { state, live, voice, softTap ->
            if (state == null) return@combine null
            HonorSheetStats(
                remainingMeters = live?.let { (1 - it.session.progressFrac) * it.loaded.geometry.totalMeters },
                softTapMeters = softTap,
                listening = voice?.view?.takeIf { it.playingMomentId != null }
                    ?.let { HonorListening(elapsedSeconds = voice.elapsedSeconds, paused = it.paused) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), null)
    }

    /** The voice a reply is being recorded to on this walk (iOS `pendingReplyOrigin`), for the card's recording row. */
    val replyingToMomentId: StateFlow<String?> = if (!enabled) {
        MutableStateFlow(null)
    } else {
        combine(replies.pending, live) { pending, live -> pending?.takeIf { it.walkId == live?.walkId }?.momentId }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), null)
    }

    /**
     * The Way [choice] names, for the pre-walk screen, once per choice: an
     * own walk's built from its source walk, a shared one read from the
     * store, and a stage as its overview handed it over, the copy Start
     * stages (owner decision 2), or else read from its package.
     */
    fun showWay(choice: HonorWayChoice) {
        if (!enabled || previewWay == choice) return
        previewWay = choice
        viewModelScope.launch {
            val way = when (choice) {
                is HonorWayChoice.OwnWalk -> (ownWalkWays.build(choice.sourceWalkId) as? OwnWalkWays.Built.Ready)?.way
                is HonorWayChoice.Stored -> stageHandoff.stage(choice.wayId)
                    ?: withContext(ioDispatcher) { wayStore.load(choice.wayId) }
            } ?: return@launch
            previewState.value = withContext(ioDispatcher) {
                HonorWalkUiState(way, HonorWayLine.of(way), wayPins(way, heardVoiceIds = emptySet()), session = null)
            }
        }
    }

    /**
     * iOS `showWayCard(for:)` (`ActiveWalkView+Honor.swift:26-34@7c200bf`):
     * a tapped pin's card jumps the queue; a tap before Start does nothing,
     * and nor does one on a moment the Way doesn't carry.
     */
    fun onWayPinTap(momentId: String) {
        val honor = state.value ?: return
        val walkId = honor.session?.walkId ?: return
        if (honor.way.moments.none { it.id == momentId }) return
        updateTouches(walkId) { it.copy(taps = it.taps + (momentId to clock.now())) }
    }

    /**
     * A card header's tap (iOS `toggleFocus(on:)`, `ActiveWalkViewModel+Honor.swift:289-303@7c200bf`):
     * the map flies to the moment, or home when it is already there.
     */
    fun flyTo(moment: WayMoment) {
        val there = focusTarget(moment) ?: return
        _focus.value = if (_focus.value == there) null else there
    }

    /** A dismissed card takes the map home (iOS `dismissTopCard`). */
    fun clearFocus() {
        _focus.value = null
    }

    /** Any deliberate touch keeps an otherwise self-retiring voice card around (iOS `touchCard`). */
    fun touch(momentId: String) {
        val walkId = latestLive?.walkId ?: return
        updateTouches(walkId) { it.copy(touched = it.touched + momentId) }
        viewModelScope.launch(ioDispatcher) { honorDao.markCardTouched(walkId, momentId) }
    }

    /** iOS `dismissTopCard`: the front card goes, wherever its voice stands, and the map comes home. */
    fun dismissTopCard() {
        clearFocus()
        val walkId = latestLive?.walkId ?: return
        val top = latestQueue?.top ?: return
        dismiss(walkId, top)
    }

    /** "continue": the only way out of the arrival card, and nothing brings it back. */
    fun dismissArrival() {
        val walkId = latestLive?.walkId ?: return
        dismiss(walkId, HONOR_ARRIVAL_CARD_ID)
    }

    /**
     * iOS `togglePlayback(of:)` (`ActiveWalkViewModel+Honor.swift:384-403@7c200bf`):
     * pause or resume the held voice, or play another one outside the queue.
     * A voice whose file isn't on this phone does nothing, as iOS's `guard`
     * returns before anything is touched.
     */
    fun togglePlayback(moment: WayMoment) {
        voiceCommand(
            command = { shown ->
                if (shown.playingMomentId == moment.id) {
                    HonorCommand.PauseResume(moment.id)
                } else {
                    HonorCommand.TogglePlayback(moment.id)
                }
            },
            mustPlay = moment,
        ) { shown, now ->
            if (shown.playingMomentId == moment.id) shown.pausedOrResumedAt(now) else shown.playing(moment.id, now)
        }
    }

    /** The chip's pause or resume, of whatever voice is held. */
    fun toggleListening() {
        voiceCommand(command = { shown -> shown.playingMomentId?.let(HonorCommand::PauseResume) }, mustPlay = null) { shown, now ->
            shown.pausedOrResumedAt(now)
        }
    }

    /**
     * iOS `seekVoice` (`ActiveWalkViewModel+Honor.swift:405-411@7c200bf`):
     * a scrub on a voice that isn't held starts it there. The waveform
     * reports every move of a drag; the position shows at each one, and the
     * command goes out at most every [SCRUB_SEND_MILLIS], the last one always.
     */
    fun scrub(moment: WayMoment, fraction: Float) {
        if (!enabled) return
        val clamped = fraction.toDouble().coerceIn(0.0, MAX_SCRUB_FRACTION)
        val offsetMillis = (clamped * moment.voiceDurationSeconds * MILLIS_PER_SECOND).roundToLong()
        val scrub = Scrub(HonorCommand.Scrub(moment.id, clamped), offsetMillis)
        if (scrubJob?.isActive == true) {
            heldScrub = scrub
            showOptimistically(scrub::applyTo)
            return
        }
        heldScrub = null
        scrubJob = viewModelScope.launch {
            voiceCommandNow({ scrub.command }, mustPlay = moment, expect = scrub::applyTo)
            delay(SCRUB_SEND_MILLIS)
            val last = heldScrub ?: return@launch
            heldScrub = null
            voiceCommandNow({ last.command }, mustPlay = null, expect = last::applyTo)
        }
    }

    /** The chip's skip (iOS `skipVoice`): nothing without a held voice. */
    fun skipVoice() {
        voiceCommand(command = { shown -> shown.playingMomentId?.let(HonorCommand::Skip) }, mustPlay = null) { shown, _ ->
            shown.released()
        }
    }

    /** iOS `cycleVoiceRate`: 1× → 1.25× → 1.5× → 2× → 1×, the pill reading the walk's own rate, 1× at every Start. */
    fun cycleRate() {
        voiceCommand(command = { HonorCommand.CycleRate }, mustPlay = null) { shown, now ->
            val rebased = if (shown.playingMomentId != null && !shown.paused) {
                shown.movedTo(shown.positionMillis(now), now)
            } else {
                shown
            }
            rebased.copy(rate = nextVoiceRate(shown.rate))
        }
    }

    /**
     * "your reply" (iOS `playReply(url:)`, parity spec C §8): the held voice
     * is given up and the earlier reply plays through the Way voice player,
     * neither held nor heard, so the chip hides while it plays.
     */
    fun playReply(moment: WayMoment) {
        voiceCommand(command = { HonorCommand.PlayReply(moment.id) }, mustPlay = null) { shown, _ -> shown.released() }
    }

    /**
     * "your reply" on a stage's arrival card (iOS `playReply(url:)` of
     * `stageReflectionReplyURL()`, `ActiveWalkView+Honor.swift:62-75@7c200bf`):
     * the walker's reply to the closing line, through the same command
     * under the reflection's reserved id. Nothing on a Way that isn't a stage.
     */
    fun playStageReflectionReply() {
        val stage = latestLive?.loaded?.way?.stage ?: return
        playReply(HonorPersistence.stageReflectionMoment(stage))
    }

    private fun voiceCommand(
        command: (shown: HonorVoiceView) -> HonorCommand?,
        mustPlay: WayMoment?,
        expect: (shown: HonorVoiceView, nowMillis: Long) -> HonorVoiceView,
    ) {
        if (!enabled) return
        viewModelScope.launch { voiceCommandNow(command, mustPlay, expect) }
    }

    /**
     * One command at a time, so commands leave in the order they were made
     * and each one's result builds on the last. [command] is built from the
     * voice as the walker sees it, so it names the voice they saw held.
     */
    private suspend fun voiceCommandNow(
        command: (shown: HonorVoiceView) -> HonorCommand?,
        mustPlay: WayMoment?,
        expect: (shown: HonorVoiceView, nowMillis: Long) -> HonorVoiceView,
    ) = commandMutex.withLock {
        val live = latestLive ?: return@withLock
        val shown = shownVoice(live, clock.now())
        val next = command(shown) ?: return@withLock
        val starting = mustPlay?.takeIf { shown.playingMomentId != it.id }
        if (starting != null && withContext(ioDispatcher) { mediaFiles.voiceFile(live.loaded.way.id, starting) } == null) {
            return@withLock
        }
        sendCommand(next)
        showOptimistically(expect)
    }

    private fun showOptimistically(expect: (shown: HonorVoiceView, nowMillis: Long) -> HonorVoiceView) {
        val live = latestLive ?: return
        val now = clock.now()
        val persisted = HonorVoiceView.of(live.session)
        val shown = pendingCommand.value.heldOver(persisted, now)?.expected ?: persisted
        pendingCommand.value = PendingVoiceCommand(expected = expect(shown, now), baseline = persisted, sentAtMillis = now)
    }

    private fun shownVoice(live: LiveHonor, nowMillis: Long): HonorVoiceView {
        val persisted = HonorVoiceView.of(live.session)
        return pendingCommand.value.heldOver(persisted, nowMillis)?.expected ?: persisted
    }

    private fun dismiss(walkId: Long, cardId: String) {
        val now = clock.now()
        updateTouches(walkId) { it.copy(dismissals = it.dismissals + (cardId to now)) }
        viewModelScope.launch(ioDispatcher) { honorDao.markCardDismissed(walkId, cardId, now) }
    }

    private fun updateTouches(walkId: Long, change: (CardTouches) -> CardTouches) {
        touches.update { current ->
            val base = if (current.walkId == walkId) current.touches else CardTouches()
            WalkTouches(walkId, change(base))
        }
    }

    /**
     * iOS `coordinate(of:)`: the moment's own place, else the line at its
     * frac; for the latter, nothing before Start, as iOS needs its engine's
     * geometry. Not the pin's `pin`, which only a stage sets.
     */
    private fun focusTarget(moment: WayMoment): WayCoordinate? {
        moment.at?.let { return it }
        val honor = state.value?.takeIf { it.session != null } ?: return null
        return WayGeometry(honor.way.route).coordinate(atFrac = moment.frac)
    }

    private fun observeLive(walkId: Long): Flow<LiveHonor?> {
        val sessions = honorDao.observeSession(walkId).distinctUntilChanged()
        val loaded = sessions
            .map { session -> session?.let { WayKey(it.wayId, it.sourceKind) } }
            .distinctUntilChanged()
            .mapLatest { key -> key?.let { loadWay(walkId, it) } }
        val drawn = combine(loaded, honorDao.observeMomentStates(walkId).distinctUntilChanged()) { way, rows ->
            way?.let { DrawnWay.of(it, rows) }
        }
        val arrivedAt = sessions
            .map { it?.phase == HonorPhase.ARRIVED }
            .distinctUntilChanged()
            .mapLatest { arrived -> if (arrived) arrivalTime(walkId) else null }
        val cardRows = honorDao.observeCardStates(walkId).distinctUntilChanged()
        // The session row changes with every fix; the pins are rebuilt only with a moment row.
        return combine(sessions, drawn, cardRows, arrivedAt) { session, way, cards, arrived ->
            if (session == null || way == null) null else LiveHonor.of(walkId, session, way, cards, arrived)
        }.flowOn(ioDispatcher)
    }

    /**
     * The staged Way an own-walk or stage session follows, else the listed
     * one, as `:tracker`'s session loads it. A stage without its staged copy
     * reads its package, which the package guard holds still while this
     * session's live row exists (see `HonorSession.prepare`).
     */
    private suspend fun loadWay(walkId: Long, key: WayKey): LoadedWay? = withContext(ioDispatcher) {
        val walkUuid = repository.getWalk(walkId)?.uuid ?: return@withContext null
        val staged = if (key.sourceKind.isStagedPerWalk) wayStore.staged(walkUuid) else null
        val way = staged?.takeIf { it.id == key.wayId } ?: wayStore.load(key.wayId) ?: return@withContext null
        LoadedWay(way, HonorWayLine.of(way), WayGeometry(way.route))
    }

    /** HONOR_ARRIVAL lands in arrival's own transaction, with the phase flip. */
    private suspend fun arrivalTime(walkId: Long): Long? =
        repository.eventsFor(walkId).lastOrNull { it.eventType == WalkEventType.HONOR_ARRIVAL }?.timestamp

    private fun companionSources(): Flow<CompanionSource?> = live
        .map { live ->
            val session = live?.state?.session ?: return@map null
            if (live.loaded.way.isPilgrimageStage) return@map null
            CompanionSource(live.loaded.way.id, live.loaded.geometry, session.anchor)
        }
        .distinctUntilChanged()

    /** The queue, rebuilt when its rows or touches change, and again as a voice card's 20 s runs out. */
    private fun queueFlow(): Flow<HonorCardQueue?> =
        combine(live, touches) { live, touches ->
            live?.let { QueueInputs(it.loaded, it.rows, it.cardRows, touches.forWalk(it.walkId), it.session.playingMomentId) }
        }
            .distinctUntilChanged()
            .flatMapLatest { inputs ->
                if (inputs == null) return@flatMapLatest flowOf<HonorCardQueue?>(null)
                flow<HonorCardQueue?> {
                    while (true) {
                        val now = clock.now()
                        val built = HonorCards.queue(
                            way = inputs.loaded.way,
                            rows = inputs.rows,
                            cardRows = inputs.cardRows,
                            local = inputs.touches,
                            playingMomentId = inputs.playingMomentId,
                            nowMillis = now,
                        )
                        emit(built)
                        val next = built.nextChangeAtMillis ?: break
                        delay((next - now).coerceAtLeast(1L))
                    }
                }
            }
            .distinctUntilChanged()

    /**
     * The held voice as the walker sees it: the persisted voice columns,
     * under a command's result while it holds. The clock ticks once a
     * second while the voice sounds, as iOS's player clock does, stands
     * still while `:tracker` holds it behind a prompt or a call, and never
     * reads past the voice's recorded length.
     */
    private fun voiceFlow(): Flow<VoiceNow?> {
        val persisted = live
            .map { live -> live?.let { PersistedVoice(HonorVoiceView.of(it.session), it.loaded.way) } }
            .distinctUntilChanged { a, b -> a?.view == b?.view && a?.way === b?.way }
        return combine(persisted, pendingCommand) { voice, pending -> voice?.let { it to pending } }
            .flatMapLatest { inputs ->
                if (inputs == null) return@flatMapLatest flowOf<VoiceNow?>(null)
                val (voice, pending) = inputs
                flow<VoiceNow?> {
                    while (true) {
                        val now = clock.now()
                        val held = pending.heldOver(voice.view, now)
                        val shown = held?.expected ?: voice.view
                        emit(VoiceNow(shown, voice.elapsedSeconds(shown, now)))
                        val ticking = shown.playingMomentId != null && !shown.paused && shown.startedAtMillis != null
                        val untilExpiry = held?.let { it.sentAtMillis + COMMAND_CONFIRM_WINDOW_MILLIS - now }
                        val wait = listOfNotNull(untilExpiry, tickMillis.takeIf { ticking }).minOrNull() ?: break
                        delay(wait.coerceAtLeast(1L))
                    }
                }
            }
            .distinctUntilChanged()
    }

    /**
     * A reply is filed after its recording's row lands, so the card reads
     * its files again on both: iOS sets the count and files the reply in
     * one main-queue turn before the card's lookup re-runs
     * (`ActiveWalkViewModel.swift:600-603@7c200bf`).
     */
    private fun mediaFlow(): Flow<HonorCardMedia?> {
        val recordingCounts = live.map { it?.walkId }.distinctUntilChanged().flatMapLatest { walkId ->
            if (walkId == null) flowOf(0) else repository.observeVoiceRecordings(walkId).map { it.size }
        }.distinctUntilChanged()
        val tops = combine(live.map { it?.loaded }.distinctUntilChanged { a, b -> a === b }, queue.map { it?.top }) { loaded, top ->
            loaded?.let { l -> top?.let { id -> l.way.moments.firstOrNull { it.id == id } }?.let { l.way to it } }
        }.distinctUntilChanged { a, b -> a?.first === b?.first && a?.second?.id == b?.second?.id }
        return combine(tops, recordingCounts, replies.filed) { top, _, _ -> top }
            .mapLatest { top -> top?.let { (way, moment) -> withContext(ioDispatcher) { resolveMedia(way, moment) } } }
            .onStart { emit(null) }
    }

    private suspend fun resolveMedia(way: Way, moment: WayMoment): HonorCardMedia {
        val waveform = if (moment.isVoice) mediaFiles.voiceFile(way.id, moment)?.let { loadWaveform(it) } else null
        val photoUri = when (val media = (moment.kind as? WayMomentKind.Photo)?.media) {
            is WayMedia.PhotoAsset -> media.localIdentifier
            is WayMedia.File -> wayStore.mediaFile(way.id, media.path)?.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() }
            is WayMedia.Recording, null -> null
        }
        val earlierReply = moment.isVoice && voiceOriginIndex(moment.id)
            ?.let { wayStore.replies(way.id)[it] }
            ?.let(mediaFiles::recordingFile) != null
        return HonorCardMedia(moment.id, waveform, photoUri, earlierReply)
    }

    private fun here(): Flow<WayCoordinate?> =
        controller.state.map { it.lastFix() }.distinctUntilChanged()

    /** The compass lives as long as the walk's session (iOS starts it with the engine and stops it at teardown). */
    private fun headingFlow(): Flow<Double?> =
        live.map { it != null }.distinctUntilChanged().flatMapLatest { running ->
            if (!running) flowOf(null) else headings { controller.state.value.lastFix() }.onStart { emit(null) }
        }

    private fun cardsUi(
        live: LiveHonor,
        queue: HonorCardQueue?,
        voice: VoiceNow?,
        media: HonorCardMedia?,
        placement: Placement,
    ): HonorCardsUi {
        val way = live.loaded.way
        val touches = placement.touches.forWalk(live.walkId)
        val arrivalDismissed = HONOR_ARRIVAL_CARD_ID in touches.dismissals ||
            live.cardRows.any { it.momentId == HONOR_ARRIVAL_CARD_ID && it.dismissedAt != null }
        val arrival = if (live.session.phase == HonorPhase.ARRIVED && !arrivalDismissed) {
            HonorArrival.summary(way, live.rows, live.arrivedAtMillis)
        } else {
            null
        }
        val top = queue?.top?.let { id -> way.moments.firstOrNull { it.id == id } }
        val place = top?.let { moment ->
            val there = moment.at ?: live.loaded.geometry.coordinate(atFrac = moment.frac)
            val shown = voice?.view
            val playing = shown?.playingMomentId == moment.id
            HonorPlaceCard(
                moment = moment,
                pendingCount = queue.pendingCount,
                isStage = way.isPilgrimageStage,
                keepsEmptyKicker = way.source !is WaySource.OwnWalk,
                distanceMeters = placement.here?.let { wgs84MidLatitudeMeters(it.lat, it.lon, there.lat, there.lon) },
                tick = WayRelation.tick(placement.here, placement.heading, there),
                isFocused = placement.focus != null,
                isPlaying = playing,
                isPaused = playing && shown?.paused == true,
                elapsedSeconds = if (playing) voice?.elapsedSeconds ?: 0.0 else 0.0,
                rate = shown?.rate ?: 1f,
                media = media?.takeIf { it.momentId == moment.id },
            )
        }
        return HonorCardsUi(live.walkId, way.id, arrival = arrival, place = place.takeIf { arrival == null })
    }

    /**
     * The soft tap (iOS `showSoftTapCaption`): the session row disarms as the
     * tap fires; the caption shows how far off the Way the walker stood,
     * measured as the engine measures it, then retires itself 20 s later. A
     * second tap's caption doesn't restart the first one's 20 s, as on iOS.
     * Nothing sets the preference, so this never shows (pilgrim-ios #109).
     */
    private fun softTapMeters(): Flow<Long?> = channelFlow {
        send(null)
        var watching: Long? = null
        var armed: Boolean? = null
        live.collect { current ->
            val session = current?.session?.takeIf { it.softTapEnabled && !current.loaded.way.isPilgrimageStage }
            if (session == null || watching != current.walkId) {
                watching = current?.walkId
                armed = session?.softTapArmed
                return@collect
            }
            val was = armed
            armed = session.softTapArmed
            if (was != true || session.softTapArmed) return@collect
            val meters = offWayMeters(current, controller.state.value.lastFix()) ?: return@collect
            send(softTapCaptionMeters(meters))
            launch {
                delay(SOFT_TAP_CAPTION_MILLIS)
                send(null)
            }
        }
    }.distinctUntilChanged()

    /** The engine's windowed nearest point (`HonorEngine.track`), from the session's persisted progress. */
    private fun offWayMeters(live: LiveHonor, here: WayCoordinate?): Double? {
        here ?: return null
        val geometry = live.loaded.geometry
        val progress = live.session.progressFrac
        val windowSpan = if (geometry.totalMeters > 0) HonorTuning.WINDOW_METERS / geometry.totalMeters else 1.0
        val window = max(0.0, progress - HonorTuning.BACKWARD_TOLERANCE)..min(1.0, progress + windowSpan)
        return geometry.nearest(to = here, within = window).meters
    }

    private fun ticks(): Flow<Long> = flow {
        while (true) {
            emit(clock.now())
            delay(tickMillis)
        }
    }

    private data class WayKey(val wayId: String, val sourceKind: HonorSourceKind)

    private class LoadedWay(val way: Way, val line: HonorWayLine, val geometry: WayGeometry)

    private data class WalkTouches(val walkId: Long?, val touches: CardTouches) {
        fun forWalk(id: Long): CardTouches = if (walkId == id) touches else CardTouches()
    }

    private data class QueueInputs(
        val loaded: LoadedWay,
        val rows: List<HonorMomentStateEntity>,
        val cardRows: List<HonorCardStateEntity>,
        val touches: CardTouches,
        val playingMomentId: String?,
    )

    private data class VoiceNow(val view: HonorVoiceView, val elapsedSeconds: Double)

    /** The session's voice columns, and the Way whose moments give each voice its length. */
    private class PersistedVoice(val view: HonorVoiceView, val way: Way) {
        /** iOS's player clock, `currentTime`, never passes the file's end. */
        fun elapsedSeconds(shown: HonorVoiceView, nowMillis: Long): Double {
            val elapsed = shown.positionMillis(nowMillis) / MILLIS_PER_SECOND
            val length = way.moments.firstOrNull { it.id == shown.playingMomentId }?.voiceDurationSeconds ?: 0.0
            return if (length > 0) min(elapsed, length) else elapsed
        }
    }

    private data class Placement(
        val here: WayCoordinate?,
        val heading: Double?,
        val focus: WayCoordinate?,
        val touches: WalkTouches,
    )

    /** What the pins and moment sets need, rebuilt only when a moment row changes, not on every fix. */
    private class DrawnWay(
        val loaded: LoadedWay,
        val rows: List<HonorMomentStateEntity>,
        val reached: Set<String>,
        val heard: Set<String>,
        val pins: List<WayPin>,
    ) {
        companion object {
            fun of(loaded: LoadedWay, rows: List<HonorMomentStateEntity>): DrawnWay {
                val heard = rows.filter { it.heard }.mapTo(mutableSetOf()) { it.momentId }
                return DrawnWay(
                    loaded = loaded,
                    rows = rows,
                    reached = rows.filter { it.reachedAt != null }.mapTo(mutableSetOf()) { it.momentId },
                    heard = heard,
                    pins = wayPins(loaded.way, heard),
                )
            }
        }
    }

    private class LiveHonor(
        val walkId: Long,
        val loaded: LoadedWay,
        val session: HonorSessionEntity,
        val rows: List<HonorMomentStateEntity>,
        val cardRows: List<HonorCardStateEntity>,
        val arrivedAtMillis: Long?,
        val state: HonorWalkUiState,
    ) {
        companion object {
            fun of(
                walkId: Long,
                session: HonorSessionEntity,
                drawn: DrawnWay,
                cardRows: List<HonorCardStateEntity>,
                arrivedAtMillis: Long?,
            ) = LiveHonor(
                walkId = walkId,
                loaded = drawn.loaded,
                session = session,
                rows = drawn.rows,
                cardRows = cardRows,
                arrivedAtMillis = arrivedAtMillis,
                state = HonorWalkUiState(
                    way = drawn.loaded.way,
                    line = drawn.loaded.line,
                    pins = drawn.pins,
                    session = HonorLiveSession(
                        walkId = walkId,
                        anchor = HonorAnchor(
                            startFrac = session.startFrac,
                            anchoredByFallback = session.anchoredByFallback,
                            anchorActiveSeconds = session.anchorActiveSeconds,
                            companionT0Seconds = session.companionT0Seconds,
                        ),
                        progressFrac = session.progressFrac,
                        reachedMomentIds = drawn.reached,
                        heardVoiceIds = drawn.heard,
                        playingMomentId = session.playingMomentId,
                        voicePaused = session.voicePaused,
                        arrived = session.phase == HonorPhase.ARRIVED,
                    ),
                ),
            )
        }
    }

    /** The geometry is the loaded Way's own instance, so equality follows the Way id and the anchor. */
    private data class CompanionSource(val wayId: String, val geometry: WayGeometry, val anchor: HonorAnchor)

    private data class CompanionInputs(val source: CompanionSource, val walk: WalkState) {
        fun coordinateAt(nowMillis: Long): WayCoordinate? {
            val seconds = honorEngineSeconds(walk, nowMillis) ?: return null
            return source.geometry.coordinate(atFrac = companionFrac(source.geometry, source.anchor, seconds))
        }
    }

    private companion object {
        const val COMPANION_TICK_MILLIS = 1_000L

        /** iOS `companionUpdateInterval` (`PilgrimMapView+HonorWay.swift:67@7c200bf`). */
        const val COMPANION_JUMP_MILLIS = 2_000L
        const val SUBSCRIBER_GRACE_MS = 5_000L

        /** iOS `softTapCaptionSeconds`. */
        const val SOFT_TAP_CAPTION_MILLIS = 20_000L

        /** A drag's scrubs leave at most this often. */
        const val SCRUB_SEND_MILLIS = 150L

        /** iOS's player lands a seek at most at 0.999 of the file (`WayVoicePlayer.swift:117@7c200bf`). */
        const val MAX_SCRUB_FRACTION = 0.999
        const val MILLIS_PER_SECOND = 1_000.0
    }
}

/** A scrub and its result: the voice held moves to [offsetMillis]; a voice not held starts there. */
private class Scrub(val command: HonorCommand.Scrub, val offsetMillis: Long) {
    fun applyTo(shown: HonorVoiceView, nowMillis: Long): HonorVoiceView =
        if (shown.playingMomentId == command.momentId) {
            shown.movedTo(offsetMillis, nowMillis)
        } else {
            shown.playing(command.momentId, nowMillis, offsetMillis)
        }
}

/**
 * iOS's card waveform: 150 bars, each its stretch's peak, scaled so the
 * loudest reaches the top (`WaveformGenerator.generateSamples`,
 * `WaveformGenerator.swift:5-43@7c200bf`); a file that doesn't read as
 * audio, or reads as silence, shows the placeholder bar.
 */
private suspend fun cardWaveform(file: File): FloatArray? =
    WaveformGenerator.generate(file, CARD_WAVEFORM_BARS)?.takeIf { samples -> samples.any { it > 0f } }

private const val CARD_WAVEFORM_BARS = 150

/**
 * iOS `HonorEngine.companionFrac` (`HonorEngine.swift:124-177@7c200bf`),
 * from the anchor `:tracker` persists: the Way's first point until the
 * first fix anchors the walker, its anchor while Begin's fallback stands,
 * then the Way's own timing advanced by the engine time since the anchor.
 */
private fun companionFrac(geometry: WayGeometry, anchor: HonorAnchor, engineSeconds: Double): Double = when {
    anchor.startFrac == null -> 0.0
    anchor.anchoredByFallback -> geometry.frac(atElapsed = anchor.companionT0Seconds)
    else -> geometry.frac(atElapsed = anchor.companionT0Seconds + max(0.0, engineSeconds - anchor.anchorActiveSeconds))
}

/**
 * iOS rewrites the companion's point at most once every [intervalMillis]
 * (`PilgrimMapView+HonorWay.swift:238-245@7c200bf`), with no animation: a
 * first position and a removal go through at once, a move only once the
 * interval has passed since the last one.
 */
private fun Flow<WayCoordinate?>.companionJumps(nowMillis: () -> Long, intervalMillis: Long): Flow<WayCoordinate?> =
    flow {
        var emitted = false
        var last: WayCoordinate? = null
        var lastAt = 0L
        collect { next ->
            if (emitted && next == last) return@collect
            val now = nowMillis()
            val isMove = last != null && next != null
            if (isMove && now - lastAt < intervalMillis) return@collect
            emit(next)
            emitted = true
            last = next
            lastAt = now
        }
    }

private fun WalkState.inProgressWalkId(): Long? = when (this) {
    is WalkState.Active -> walk.walkId
    is WalkState.Paused -> walk.walkId
    is WalkState.Meditating -> walk.walkId
    WalkState.Idle, is WalkState.Finished -> null
}

/** The walker's last fix, as iOS's `currentLocation` feeds the card's distance and tick. */
private fun WalkState.lastFix(): WayCoordinate? {
    val walk = when (this) {
        is WalkState.Active -> walk
        is WalkState.Paused -> walk
        is WalkState.Meditating -> walk
        WalkState.Idle, is WalkState.Finished -> null
    }
    return walk?.lastLocation?.let { WayCoordinate(lat = it.latitude, lon = it.longitude) }
}

/** The walk state with only what the engine clock reads, so a fix that moves the walker isn't a clock change. */
private fun WalkState.engineClockOnly(): WalkState = when (this) {
    is WalkState.Active -> WalkState.Active(walk.clockOnly())
    is WalkState.Paused -> copy(walk = walk.clockOnly())
    is WalkState.Meditating -> copy(walk = walk.clockOnly())
    WalkState.Idle, is WalkState.Finished -> WalkState.Idle
}

private fun WalkAccumulator.clockOnly() =
    WalkAccumulator(walkId = walkId, startedAt = startedAt, totalPausedMillis = totalPausedMillis)
