// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.share.CachedShare
import org.walktalkmeditate.pilgrim.data.share.CachedShareStore
import org.walktalkmeditate.pilgrim.data.share.ExpiryOption
import org.walktalkmeditate.pilgrim.data.share.PauseSpan
import org.walktalkmeditate.pilgrim.data.share.PreparedRoute
import org.walktalkmeditate.pilgrim.data.share.PrepState
import org.walktalkmeditate.pilgrim.data.share.RecordingArtifact
import org.walktalkmeditate.pilgrim.data.share.ShareConfig
import org.walktalkmeditate.pilgrim.data.share.ShareError
import org.walktalkmeditate.pilgrim.data.share.ShareInputs
import org.walktalkmeditate.pilgrim.data.share.SharePayload
import org.walktalkmeditate.pilgrim.data.share.SharePayloadBuilder
import org.walktalkmeditate.pilgrim.data.share.SharePhotoEncoder
import org.walktalkmeditate.pilgrim.data.share.SharePrepStore
import org.walktalkmeditate.pilgrim.data.share.ShareRepairStore
import org.walktalkmeditate.pilgrim.data.share.ShareService
import org.walktalkmeditate.pilgrim.data.share.TourBuilder
import org.walktalkmeditate.pilgrim.data.share.TourPhoto
import org.walktalkmeditate.pilgrim.data.share.TourPhotoExportResult
import org.walktalkmeditate.pilgrim.data.share.TourPhotoExporter
import org.walktalkmeditate.pilgrim.data.share.TourRecordingCandidate
import org.walktalkmeditate.pilgrim.data.share.TourRecordingKind
import org.walktalkmeditate.pilgrim.data.share.WalkShareOptions
import org.walktalkmeditate.pilgrim.data.share.computeInteractiveRoute
import org.walktalkmeditate.pilgrim.data.share.prepareRoute
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.walk.WalkMetricsMath
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.replayWalkEventTotals
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters

/**
 * Stage 8-A / Phase 19 U8: modal VM for the "Share Journey" flow. Takes
 * walkId via SavedStateHandle and re-fetches walk + route + altitude +
 * intervals + recordings + waypoints + photos + events from the repo
 * independently of [org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryViewModel].
 *
 * ## The interactive share state machine (Phase 19)
 *
 * Ports iOS `WalkShareViewModel` + its `+ShareOrchestration` extension
 * (`@3f9f9e8`). [shareCardState] reproduces all 8 `ShareState` vertices
 * (`WalkShareViewModel.swift:110-119@3f9f9e8`) and every transition
 * between them:
 *
 * ```
 * Idle ──share()──► Uploading ──(interactive+photos)──► PreparingPhotos
 *                       │                                    │
 *                       │                    export short ───┴──► PhotosDropped
 *                       │                                            │  │
 *                       │      ◄──continueShareWithoutDroppedPhotos()─┘  └─cancelDroppedPhotoShare()─► Idle
 *                       ▼
 *                 POST lands ──interactive──► UploadingMedia ──► Success | Partial
 *                       └──────classic────────────────────────► Success
 *                       └──────throw─────────────────────────► Error ──share()──► …
 *
 * Partial ──retryFailedMedia()──► UploadingMedia ──► Success | Partial(+repairUnavailable)
 * ```
 *
 * Three invariants the pin is emphatic about, and where they live here:
 *
 * - **The single lock.** `beginShare`, `continueShareWithoutDroppedPhotos`
 *   and `beginRetry` all guard on ONE `shareTask == nil`
 *   (`WalkShareViewModel+ShareOrchestration.swift:9-15,79-88,219-225@3f9f9e8`)
 *   — "a port that gives each action its own independent Job would allow
 *   a retry to start while a fresh share is somehow still finishing,
 *   which the single-lock design structurally prevents" (spec BEH-64).
 *   Here that is [isSharing], claimed by [claimShareLock]'s
 *   `compareAndSet` BEFORE any `launch` (house rule, Stage 5-C).
 * - **The locking state is claimed synchronously**, before the coroutine
 *   is even spawned, so the buttons a state change removes are gone
 *   within the same frame (`:71-88@3f9f9e8`, spec BEH-55).
 * - **Cancellation is honored at exactly one checkpoint**, before
 *   anything exists server-side (`:51-55,101-111@3f9f9e8`, spec BEH-50/53):
 *   once the POST has landed, a cancel must NOT abort in-flight media
 *   PUTs — the page is live and those uploads need to complete or fail
 *   into a repairable [ShareCardState.Partial].
 */
@HiltViewModel
class WalkShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WalkRepository,
    private val shareService: ShareService,
    private val cachedShareStore: CachedShareStore,
    private val photoEncoder: SharePhotoEncoder,
    private val sharePrepStore: SharePrepStore,
    private val tourPhotoExporter: TourPhotoExporter,
    private val shareRepairStore: ShareRepairStore,
    unitsPreferences: UnitsPreferencesRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val walkId: Long = requireNotNull(savedStateHandle.get<Long>(ARG_WALK_ID)) {
        "WalkShareViewModel requires a `walkId` nav arg"
    }

    /**
     * Distance-unit preference, passed straight through from the repo's
     * hot StateFlow. The modal formats its per-row stat previews
     * ([ShareStatFormat]) at display time — storage stays metric.
     */
    val distanceUnits: StateFlow<UnitSystem> = unitsPreferences.distanceUnits

    private val _uiState = MutableStateFlow<WalkShareUiState>(WalkShareUiState.Loading)
    val uiState: StateFlow<WalkShareUiState> = _uiState.asStateFlow()

    private val _journal = MutableStateFlow("")
    val journal: StateFlow<String> = _journal.asStateFlow()

    private val _selectedExpiry = MutableStateFlow(ExpiryOption.Season)
    val selectedExpiry: StateFlow<ExpiryOption> = _selectedExpiry.asStateFlow()

    private val _includeDistance = MutableStateFlow(true)
    val includeDistance: StateFlow<Boolean> = _includeDistance.asStateFlow()
    private val _includeDuration = MutableStateFlow(true)
    val includeDuration: StateFlow<Boolean> = _includeDuration.asStateFlow()
    private val _includeElevation = MutableStateFlow(true)
    val includeElevation: StateFlow<Boolean> = _includeElevation.asStateFlow()
    private val _includeActivityBreakdown = MutableStateFlow(true)
    val includeActivityBreakdown: StateFlow<Boolean> = _includeActivityBreakdown.asStateFlow()
    private val _includeSteps = MutableStateFlow(false)
    val includeSteps: StateFlow<Boolean> = _includeSteps.asStateFlow()
    private val _includeWaypoints = MutableStateFlow(false)
    val includeWaypoints: StateFlow<Boolean> = _includeWaypoints.asStateFlow()
    private val _includePhotos = MutableStateFlow(false)
    val includePhotos: StateFlow<Boolean> = _includePhotos.asStateFlow()

    /**
     * The single in-flight lock shared by [share], [continueShareWithoutDroppedPhotos]
     * and [retryFailedMedia] — iOS's one `shareTask` (see the class doc).
     * Named `isSharing` since Stage 8-A; its role has widened, not changed.
     */
    private val _isSharing = MutableStateFlow(false)
    val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()

    private val _events = MutableSharedFlow<WalkShareEvent>(replay = 0, extraBufferCapacity = 1)
    val events: SharedFlow<WalkShareEvent> = _events.asSharedFlow()

    /** Observer for the per-walk cached share. Re-emits on store writes. */
    private val _cachedShare = MutableStateFlow<CachedShare?>(null)
    val cachedShare: StateFlow<CachedShare?> = _cachedShare.asStateFlow()

    // ---- Phase 19: interactive share -----------------------------------

    private val _interactiveEnabled = MutableStateFlow(false)
    val interactiveEnabled: StateFlow<Boolean> = _interactiveEnabled.asStateFlow()

    /** iOS `trimEnabled`, default true and only consulted when interactive (`WalkShareViewModel.swift:34,471@3f9f9e8`). */
    private val _trimEnabled = MutableStateFlow(true)
    val trimEnabled: StateFlow<Boolean> = _trimEnabled.asStateFlow()

    private val _excludedRecordingUuids = MutableStateFlow<Set<String>>(emptySet())
    private val _kindOverrides = MutableStateFlow<Map<String, TourRecordingKind>>(emptyMap())

    /**
     * Every transcoded size this VM has ever seen land, kept across a
     * walker exclusion (which deletes the artifact) so an excluded row
     * keeps rendering [RecordingAvailability.Available] and stays
     * re-includable — see [recordingAvailability]'s doc.
     */
    private val _knownArtifactSizes = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Count of prep launches in flight; > 0 gates [canShare] (port plan Decision 2). */
    private val _prepInFlight = MutableStateFlow(0)

    private val _shareCardState = MutableStateFlow<ShareCardState>(ShareCardState.Idle)
    internal val shareCardState: StateFlow<ShareCardState> = _shareCardState.asStateFlow()

    /**
     * iOS `repairUnavailable` — set only where a repair pass resolved
     * nothing carryable, so `ShareStatusSection` swaps the retry button
     * for an explanation instead of looping forever
     * (`WalkShareViewModel+ShareOrchestration.swift:268-282@3f9f9e8`).
     */
    private val _repairUnavailable = MutableStateFlow(false)
    val repairUnavailable: StateFlow<Boolean> = _repairUnavailable.asStateFlow()

    /** iOS `pendingTourPhotos` — kept across the consent pause so "Share without them" never re-exports (`:76-77,957@3f9f9e8`). */
    @Volatile
    private var pendingTourPhotos: List<TourPhoto> = emptyList()

    private var shareJob: Job? = null

    /**
     * The loaded walk's uuid, as a flow rather than a plain field
     * because [prepStates] joins on it: `loadInputs` runs off Main and
     * `sharePrepStore.state` may already have emitted (and may never
     * emit again) by the time the uuid lands — a plain read inside the
     * `map` would leave the join permanently stale-empty.
     */
    private val _walkUuid = MutableStateFlow<String?>(null)
    private val walkUuid: String? get() = _walkUuid.value

    @Volatile
    private var recordingsByUuid: Map<String, VoiceRecording> = emptyMap()

    /**
     * The downsampled route + its trim eligibility, computed ONCE by
     * [loadInputs] on its background dispatcher and published before
     * [WalkShareUiState.Loaded] is — so every reader that runs on Main
     * ([interactiveSection]'s transform, [interactivePhotoExportList])
     * finds it already there and never re-runs the RDP passes.
     */
    @Volatile
    internal var preparedRoute: PreparedRoute? = null
        private set

    /** iOS `didAutoEnablePhotos` — per-VM-instance, never persisted (`WalkShareViewModel.swift:66@3f9f9e8`, spec BEH-80). */
    private var didAutoEnablePhotos = false

    /** Guards the cached-share restore path from re-running over its own writes. */
    private var didRestoreFromCache = false

    private val prepStates: StateFlow<Map<String, PrepState>> =
        combine(sharePrepStore.state, _walkUuid) { all, uuid -> uuid?.let { all[it] }.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * The walker's per-row choices resolved into candidates — the same
     * [TourBuilder.candidates] call [SharePayloadBuilder] makes, with
     * the same three inputs, so the disclosure and the payload can never
     * disagree about what is being carried.
     */
    internal val tourCandidates: StateFlow<List<TourRecordingCandidate>> = combine(
        _uiState,
        _knownArtifactSizes,
        _excludedRecordingUuids,
        _kindOverrides,
    ) { _, _, _, _ -> candidatesNow() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The candidate derivation itself, read straight off the source
     * flows rather than off [tourCandidates].
     *
     * A derived `stateIn`/`combine` value settles a dispatch AFTER the
     * source it derives from — fine for rendering, wrong for a decision
     * taken in the same frame as the mutation that should have changed
     * it. Every imperative caller here ([share]'s gate, the payload
     * build, the interactive readiness check) therefore recomputes from
     * source; [tourCandidates] exists only so Compose has something to
     * observe, and calls this same function so the two can never differ
     * by anything but a frame.
     */
    private fun candidatesNow(): List<TourRecordingCandidate> {
        val loaded = _uiState.value as? WalkShareUiState.Loaded ?: return emptyList()
        return TourBuilder.candidates(
            recordings = loaded.inputs.voiceRecordings,
            artifacts = artifactsNow(),
            excludedUuids = _excludedRecordingUuids.value,
            kindOverrides = _kindOverrides.value,
        )
    }

    private fun artifactsNow(): Map<String, RecordingArtifact> =
        _knownArtifactSizes.value.mapValues { (_, bytes) -> RecordingArtifact(sizeBytes = bytes, fileExists = true) }

    internal val interactiveSection: StateFlow<InteractiveShareSectionState> = combine(
        combine(_interactiveEnabled, _trimEnabled, tourCandidates, prepStates, _prepInFlight, ::SectionSources),
        _knownArtifactSizes,
        _includePhotos,
        _uiState,
        _shareCardState,
    ) { sources, sizes, wantsPhotos, _, card ->
        val rows = sources.candidates.map { candidate ->
            TourRecordingRowState(
                id = candidate.id,
                durationSeconds = candidate.duration.toInt(),
                startEpochSeconds = candidate.startTs,
                transcriptionPreview = candidate.transcription,
                effectiveKind = candidate.effectiveKind,
                includeInShare = candidate.includeInShare,
                availability = recordingAvailability(
                    candidate = candidate,
                    prepState = sources.prepStates[candidate.recordingUuid],
                    knownSizeBytes = sizes[candidate.recordingUuid],
                    prepBusy = sources.prepInFlight > 0,
                ),
            )
        }
        InteractiveShareSectionState(
            interactiveEnabled = sources.interactiveEnabled,
            rows = rows,
            totalsLabel = tourTotalsLabel(
                context = context,
                candidates = sources.candidates,
                // Only computed when the label can actually show a photo
                // clause. The export list applies the trim to the
                // ALREADY-downsampled [preparedRoute] (iOS's
                // `tourTotalsLabel` reaches for the same shared window,
                // `WalkShareViewModel.swift:47-59@3f9f9e8`).
                photoCount = if (wantsPhotos && sources.interactiveEnabled) interactivePhotoExportList().size else 0,
            ),
            // The SAME TourBuilder.validationError call the Share gate
            // uses, so the copy and the gate can never disagree (U7's
            // InteractiveShareSectionState contract).
            validationErrorText = if (sources.interactiveEnabled) {
                TourBuilder.validationError(sources.candidates)
            } else {
                null
            },
            trimEnabled = sources.trimEnabled,
            canTrim = preparedRoute?.canTrim == true,
            inputLocked = isShareInFlight(card),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, InteractiveShareSectionState())

    /**
     * Whether an interactive share may proceed. iOS gates on the
     * aggregate caps alone (`canShare`, `WalkShareViewModel.swift:44-45@3f9f9e8`)
     * because its candidates resolve synchronously off disk; Android has
     * to wait for the transcodes first (port plan Decision 2).
     *
     * The wait is expressed against [_knownArtifactSizes] — the SAME map
     * the payload's artifacts are built from — rather than against the
     * "is a prep coroutine running" counter alone. Those two settle
     * independently (the counter decrements on the prep coroutine's own
     * thread; the size map updates through a Main-confined collector),
     * and gating on the counter alone let `canShare` go true in the
     * window between them — long enough for a tapped Share to build a
     * tour that declared NO recordings, because every candidate still
     * read as "audio removed". Gating on the map closes that by
     * construction: Share cannot be enabled until the VM knows each
     * recording's outcome through the exact map it will then ship.
     */
    private val interactiveShareReady: StateFlow<Boolean> = combine(
        _interactiveEnabled,
        _prepInFlight,
        tourCandidates,
        prepStates,
        _knownArtifactSizes,
    ) { _, _, _, _, _ -> interactiveReadyNow() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Source-read twin of [interactiveShareReady] — see [candidatesNow]
     * for why the imperative path must not read the derived flow.
     */
    private fun interactiveReadyNow(): Boolean {
        if (!_interactiveEnabled.value) return true
        if (_prepInFlight.value > 0) return false
        val states = walkUuid?.let { sharePrepStore.state.value[it] }.orEmpty()
        val candidates = candidatesNow()
        val unresolved = candidates.count { candidate ->
            candidate.recordingUuid !in _knownArtifactSizes.value && states[candidate.recordingUuid] != PrepState.Failed
        }
        return unresolved == 0 && TourBuilder.validationError(candidates) == null
    }

    private data class SectionSources(
        val interactiveEnabled: Boolean,
        val trimEnabled: Boolean,
        val candidates: List<TourRecordingCandidate>,
        val prepStates: Map<String, PrepState>,
        val prepInFlight: Int,
    )

    init {
        viewModelScope.launch(Dispatchers.Default) { loadInputs() }
        viewModelScope.launch {
            // Walk UUID comes from the loaded inputs — wait for Loaded
            // before starting the observer. `collectLatest` cancels the
            // previous observer if uiState transitions (only ever once:
            // Loading → Loaded), so no observer leaks.
            _uiState.collectLatest { state ->
                if (state is WalkShareUiState.Loaded) {
                    cachedShareStore.observe(state.inputs.walk.uuid).collect { cached ->
                        // Restore FIRST, publish second. `Idle` renders a
                        // live Share button and the screen counts a
                        // non-expired cached share as `isShared`
                        // (`WalkShareScreen.isShared`), so publishing
                        // ahead of the restore's own store read puts a
                        // working Share button on top of a page that
                        // already exists — one tap there is a second POST
                        // over the live one.
                        restoreFromCache(cached)
                        _cachedShare.value = cached
                    }
                }
            }
        }
        viewModelScope.launch {
            // Remember every size a transcode produces. Never unlearned
            // by an exclusion — see _knownArtifactSizes' doc.
            prepStates.collect { states ->
                val ready = states.mapNotNull { (uuid, s) -> (s as? PrepState.Ready)?.let { uuid to it.sizeBytes } }
                if (ready.isNotEmpty()) _knownArtifactSizes.update { it + ready }
            }
        }
    }

    fun updateJournal(next: String) {
        // Silent truncation at ShareConfig.JOURNAL_MAX_LEN — iOS
        // `WalkShareView.swift:225-228` parity. Drop overflow chars
        // rather than rejecting input (feels better with fast typing).
        _journal.value = if (next.length <= ShareConfig.JOURNAL_MAX_LEN) {
            next
        } else {
            next.substring(0, ShareConfig.JOURNAL_MAX_LEN)
        }
    }

    fun updateExpiry(option: ExpiryOption) { _selectedExpiry.value = option }
    fun toggleDistance(on: Boolean) { _includeDistance.value = on }
    fun toggleDuration(on: Boolean) { _includeDuration.value = on }
    fun toggleElevation(on: Boolean) { _includeElevation.value = on }
    fun toggleActivityBreakdown(on: Boolean) { _includeActivityBreakdown.value = on }
    fun toggleSteps(on: Boolean) { _includeSteps.value = on }
    fun toggleWaypoints(on: Boolean) { _includeWaypoints.value = on }
    fun togglePhotos(on: Boolean) { _includePhotos.value = on }
    fun toggleTrim(on: Boolean) { _trimEnabled.value = on }

    /**
     * The Interactive toggle. Toggling ON runs iOS's `prepareInteractive()`
     * (`WalkShareViewModel.swift:217-228@3f9f9e8`) — its photo auto-enable
     * latch fires exactly once ever, "the walker can still switch them off
     * afterwards and we never re-flip" — and additionally starts Android's
     * own WAV→AAC prep pass (port plan Decision 2), which is what makes a
     * recording's shareable size knowable at all. Toggling OFF cancels
     * that pass and deletes its artifacts.
     */
    fun setInteractiveEnabled(on: Boolean) {
        if (_interactiveEnabled.value == on) return
        _interactiveEnabled.value = on
        if (on) {
            if (!didAutoEnablePhotos && hasPinnedPhotos()) {
                didAutoEnablePhotos = true
                _includePhotos.value = true
            }
            startPrep(prepareableRecordings(), asToggleTransition = true)
        } else {
            val uuid = walkUuid ?: return
            // The artifacts are gone, so their sizes are unknown again —
            // but the walker's per-row choices are NOT reset. iOS leaves
            // `tourCandidates` untouched across a toggle-off/on
            // (`WalkShareViewModel.swift:217-228@3f9f9e8`), so an
            // accidental tap can never silently re-include something
            // the walker chose to leave behind.
            _knownArtifactSizes.value = emptyMap()
            prepLifecycleJob = launchPrepWork(previous = prepLifecycleJob) {
                sharePrepStore.cancelAndCleanupWalk(uuid)
            }
        }
    }

    /**
     * The Interactive toggle's single-file lifecycle chain: the pass this
     * toggle's most recent transition launched, which the NEXT transition
     * cancels and joins before doing anything of its own.
     *
     * Launched as free-running coroutines the two halves interleave. A
     * fast off→on double tap lets the toggle-off's
     * [SharePrepStore.cancelAndCleanupWalk] land INSIDE the toggle-on's
     * transcode pass, cancelling its encodes and clearing their state —
     * and a [PrepState] cleared by cancellation is neither Ready nor
     * Failed, so [interactiveReadyNow] counts it unresolved forever and
     * the row is stranded on "audio removed" with the Share gate shut.
     * Where the same race instead leaves [PrepState.Failed] — which
     * [interactiveReadyNow] counts as RESOLVED — the gate opens on a tour
     * quietly missing that recording, which is the worse of the two.
     *
     * Chaining rather than a mutex so a toggle-off still PREEMPTS the
     * pass it is cancelling instead of queueing behind every remaining
     * encode; [CoroutineStart.ATOMIC] in [launchPrepWork] is what keeps
     * the [_prepInFlight] bookkeeping honest across that preemption.
     * Main-confined (both writers are UI callbacks) but marked volatile
     * for the same reason [shareJob] is.
     */
    @Volatile
    private var prepLifecycleJob: Job? = null

    /**
     * iOS `toggleInclude` (`WalkShareViewModel.swift:230-234@3f9f9e8`):
     * a no-op for any candidate that is unavailable — "an unavailable
     * candidate can never be toggled on by the user". Excluding also
     * cancels that row's encode and drops its artifact (port plan
     * Decision 3); re-including re-encodes it.
     */
    fun toggleRowInclude(candidateId: Int) {
        val candidate = candidatesNow().firstOrNull { it.id == candidateId } ?: return
        if (candidate.unavailableReason != null) return
        val recordingUuid = candidate.recordingUuid
        val excluding = recordingUuid !in _excludedRecordingUuids.value
        _excludedRecordingUuids.update { if (excluding) it + recordingUuid else it - recordingUuid }
        val uuid = walkUuid ?: return
        if (excluding) {
            launchPrepWork { sharePrepStore.cancelRecording(uuid, recordingUuid) }
        } else {
            recordingsByUuid[recordingUuid]?.let { startPrep(listOf(it)) }
        }
    }

    /**
     * iOS `flipKind` (`WalkShareViewModel.swift:236-241@3f9f9e8`): flip
     * the EFFECTIVE kind, and normalize the override away when the flip
     * lands back on the candidate's own `autoKind`. The normalization
     * itself lives in [TourBuilder.candidates] so the derived list and
     * the payload apply one rule.
     */
    fun flipRowKind(candidateId: Int) {
        val candidate = candidatesNow().firstOrNull { it.id == candidateId } ?: return
        val flipped = if (candidate.effectiveKind == TourRecordingKind.SPOKEN) {
            TourRecordingKind.AMBIENT
        } else {
            TourRecordingKind.SPOKEN
        }
        _kindOverrides.update { it + (candidate.recordingUuid to flipped) }
    }

    // ---- share orchestration -------------------------------------------

    /**
     * Entry point for the "Share" button — iOS `beginShare()` +
     * `share()` (`WalkShareViewModel+ShareOrchestration.swift:9-69@3f9f9e8`).
     * The lock is claimed by `compareAndSet` and the locking card state
     * synchronously, both BEFORE the coroutine is spawned, so a
     * same-frame double tap has nothing to hit.
     */
    fun share() {
        // Read from source, never from the [canShare] flow: that flow is
        // one dispatch behind its inputs, so a Share tap landing in the
        // same frame as an Interactive toggle would decide on a stale
        // answer in EITHER direction — a stale `true` ships a tour that
        // declares no recordings, a stale `false` silently swallows a
        // legitimate tap.
        if (!canShareNow()) return
        if (!claimShareLock()) return
        _repairUnavailable.value = false
        _shareCardState.value = ShareCardState.Uploading
        launchShareAttempt { runShare() }
    }

    /**
     * "Share without them" — iOS `continueShareWithoutDroppedPhotos()`
     * (`:71-88@3f9f9e8`). Claims [ShareCardState.Uploading] synchronously
     * "so the prompt's buttons vanish immediately instead of staying
     * tappable through the geocode+POST that follows".
     */
    fun continueShareWithoutDroppedPhotos() {
        if (_shareCardState.value !is ShareCardState.PhotosDropped) return
        if (!claimShareLock()) return
        _shareCardState.value = ShareCardState.Uploading
        val photos = pendingTourPhotos
        launchShareAttempt {
            completeShare(photos)
            pendingTourPhotos = emptyList()
        }
    }

    /**
     * "Don't share yet" — iOS `cancelDroppedPhotoShare()` (`:90-99@3f9f9e8`).
     * Nothing is server-side during the pause itself, but a "Share
     * without them" resume may already be running (a fast tap on that
     * button followed by this one) — cancelling is what makes the
     * decline actually mean no.
     */
    fun cancelDroppedPhotoShare() {
        shareJob?.cancel()
        pendingTourPhotos = emptyList()
        _shareCardState.value = ShareCardState.Idle
    }

    /**
     * "Carry the missing files" — iOS `beginRetry()` + `retryFailedMedia()`
     * (`:212-305@3f9f9e8`), through the SAME lock as [share].
     */
    fun retryFailedMedia() {
        if (_shareCardState.value !is ShareCardState.Partial) return
        if (!claimShareLock()) return
        _repairUnavailable.value = false
        launchShareAttempt { runRepair() }
    }

    /**
     * Claims the one in-flight slot. `compareAndSet` before `launch`
     * (Stage 5-C: "three rapid same-thread calls all pass a
     * read-then-write sync guard").
     */
    private fun claimShareLock(): Boolean = _isSharing.compareAndSet(expect = false, update = true)

    /**
     * Spawns the one attempt the caller has just claimed the lock for,
     * and names it so [cancelDroppedPhotoShare] can cancel it.
     *
     * [CoroutineStart.ATOMIC] is the faithful analogue of the pin's
     * `Task { await share(); shareTask = nil }` (`:11-14@3f9f9e8`): a
     * Swift Task always runs its body and observes cancellation at its
     * own checkpoints. Under Kotlin's default start, a cancel landing
     * between this `launch` and the IO dispatch skips the body
     * altogether — and with it [runGuarded]'s `finally`, stranding the
     * lock claimed just above and disabling Share for the life of this
     * ViewModel. ATOMIC makes that `finally` an actual guarantee; the
     * body runs, hits the pre-POST checkpoint, and lands on Idle.
     *
     * A body that finishes before the assignment below can leave a
     * COMPLETED Job in [shareJob] (its own `finally` already nulled the
     * field). That is inert: cancelling a completed Job is a no-op,
     * which is exactly right — there is no attempt left to cancel — and
     * the next attempt overwrites it.
     */
    private fun launchShareAttempt(body: suspend () -> Unit) {
        shareJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) { runGuarded(body) }
    }

    /**
     * The shared body wrapper for all three entry points: releases the
     * lock exactly once, and translates a cancellation that arrived
     * BEFORE anything landed server-side back to
     * [ShareCardState.Idle] — never after, since by then the page is
     * live and its state must survive (`:17-23@3f9f9e8`, spec BEH-50).
     */
    private suspend fun runGuarded(body: suspend () -> Unit) {
        try {
            body()
        } catch (ce: CancellationException) {
            if (!postLanded) _shareCardState.value = ShareCardState.Idle
            throw ce
        } catch (t: Throwable) {
            // [completeShare]'s own ladder covers the request and the
            // media PUTs. Everything OUTSIDE it can throw too — the
            // photo export list, the base64 encode, the payload build,
            // and the whole repair pass' store reads and re-exports —
            // and from a `viewModelScope.launch` that reaches the
            // default uncaught handler, i.e. takes the process down.
            // The walker gets the same card any ShareError produces.
            Log.w(TAG, "share attempt failed outside the request", t)
            val message = context.getString(UNKNOWN_MESSAGE)
            _shareCardState.value = ShareCardState.Error(message)
            _events.tryEmit(WalkShareEvent.Failed(message))
        } finally {
            postLanded = false
            shareJob = null
            _isSharing.value = false
        }
    }

    /** True once this attempt's POST has returned a live page — see [runGuarded]. */
    @Volatile
    private var postLanded = false

    /**
     * iOS `share()` (`:25-69@3f9f9e8`): optional hi-res photo export,
     * the one cancellation checkpoint, the short-export consent pause,
     * then the shared [completeShare] choke point.
     */
    private suspend fun runShare() {
        val interactive = _interactiveEnabled.value
        val exportList = if (interactive && _includePhotos.value) interactivePhotoExportList() else emptyList()

        var exported: TourPhotoExportResult? = null
        if (exportList.isNotEmpty()) {
            val uuid = walkUuid
            if (uuid != null) {
                // Primed synchronously before the first tick, exactly as
                // iOS does — otherwise the phase never becomes
                // PreparingPhotos and every progress tick is rejected by
                // the phase gate below (`:36-40@3f9f9e8`).
                _shareCardState.value = ShareCardState.PreparingPhotos(completed = 0, total = exportList.size)
                exported = tourPhotoExporter.export(uuid, exportList) { done, total ->
                    applyPreparingPhotosProgress(done, total)
                }
            }
        }

        // Nothing exists server-side yet — cancellation is clean up to
        // the POST (`:51-55@3f9f9e8`).
        if (!currentCoroutineContext().isActive) {
            _shareCardState.value = ShareCardState.Idle
            return
        }

        val tourPhotos = exported?.photos.orEmpty()
        val dropped = (exported?.requested ?: 0) - tourPhotos.size
        if (dropped > 0) {
            // Pause for consent before anything POSTs, rather than
            // silently shipping a page short of photos it promised
            // (`:57-66@3f9f9e8`).
            pendingTourPhotos = tourPhotos
            _shareCardState.value = ShareCardState.PhotosDropped(prepared = tourPhotos.size, dropped = dropped)
            return
        }

        completeShare(tourPhotos)
    }

    /**
     * iOS `completeShare` (`:101-167@3f9f9e8`) — "the locking state at
     * the single choke point: from here through the POST a live page may
     * exist — every caller gets the dismiss-lock, no caller can forget
     * it."
     */
    private suspend fun completeShare(tourPhotos: List<TourPhoto>) {
        _shareCardState.value = ShareCardState.Uploading
        if (!currentCoroutineContext().isActive) {
            _shareCardState.value = ShareCardState.Idle
            return
        }
        val loaded = _uiState.value as? WalkShareUiState.Loaded ?: run {
            _shareCardState.value = ShareCardState.Error(context.getString(NOT_LOADED_MESSAGE))
            _events.tryEmit(WalkShareEvent.Failed(context.getString(NOT_LOADED_MESSAGE)))
            return
        }
        val walkUuidValue = loaded.inputs.walk.uuid
        val interactive = _interactiveEnabled.value

        // Resolve the artifacts BEFORE the payload is built, so
        // `tour.recordings` can only declare recordings whose bytes are
        // actually queued — see planInteractiveAudioUploads' doc.
        val plan = if (interactive) {
            planInteractiveAudioUploadsResolving(walkUuidValue, candidatesNow())
        } else {
            InteractiveUploadPlan(emptyList(), _excludedRecordingUuids.value)
        }

        val options = shareOptions(interactive, plan.effectiveExcludedUuids)
        val photoMeta = photoPayloadFor(interactive, loaded.inputs.pinnedPhotos, tourPhotos)
        val inputs = loaded.inputs.copy(recordingArtifacts = artifactsNow())
        val payload = withContext(Dispatchers.Default) {
            SharePayloadBuilder.build(inputs, options, photos = photoMeta)
        }

        try {
            val result = shareService.share(payload)
            postLanded = true
            val nowMs = Instant.now().toEpochMilli()
            cachedShareStore.put(
                walkUuid = walkUuidValue,
                share = CachedShare(
                    url = result.url,
                    id = result.id,
                    expiryEpochMs = nowMs + options.expiry.days * MILLIS_PER_DAY,
                    shareDateEpochMs = nowMs,
                    expiryOption = options.expiry,
                ),
            )

            if (interactive) {
                val photoSlots = planPhotoUploads(tourPhotos)
                // Primed synchronously, no dispatch hop to race the
                // first tick (`:133-136@3f9f9e8`).
                _shareCardState.value = ShareCardState.UploadingMedia(
                    completed = 0,
                    total = photoSlots.size + plan.audioSlots.size,
                )
                // uploadMedia pre-populates the repair record before its
                // first PUT and clears it once every slot lands — the
                // kill-safety iOS spells out as cacheFailedMedia(
                // expectedFailureRecords(...)) (`:125-131@3f9f9e8`).
                val outcome = shareService.uploadMedia(
                    walkUuid = walkUuidValue,
                    shareId = result.id,
                    shareUrl = result.url,
                    photos = photoSlots,
                    audio = plan.audioSlots,
                    onProgress = ::applyMediaProgress,
                )
                if (outcome.failedCount == 0) {
                    _shareCardState.value = ShareCardState.Success(result.url)
                    _events.tryEmit(WalkShareEvent.Success(result.url))
                    cleanUpArtifacts(walkUuidValue)
                } else {
                    // The link is revealed anyway — the page IS live —
                    // and the repair record stays for "Carry the missing
                    // files" (`:153-155@3f9f9e8`). Artifacts stay too:
                    // the repair pass uploads from them.
                    //
                    // No Success event: that event auto-presents the page
                    // in a Custom Tab, which would pull the walker off
                    // the very card carrying "Carry the missing files".
                    // iOS guards its reveal on `.success` alone
                    // (`WalkShareView.swift:361@3f9f9e8`).
                    _shareCardState.value = ShareCardState.Partial(result.url, outcome.failedCount)
                }
            } else {
                // "A fresh share must never inherit a previous share's
                // failed-media record — this walk may have had a
                // `.partial` share before." (`:157-163@3f9f9e8`)
                shareRepairStore.clear(walkUuidValue)
                _shareCardState.value = ShareCardState.Success(result.url)
                _events.tryEmit(WalkShareEvent.Success(result.url))
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: ShareError.RateLimited) {
            _shareCardState.value = ShareCardState.Error(context.getString(RATE_LIMITED_MESSAGE))
            _events.tryEmit(WalkShareEvent.RateLimited)
        } catch (e: ShareError) {
            Log.w(TAG, "share failed", e)
            val message = e.message.orEmpty().ifBlank { context.getString(UNKNOWN_MESSAGE) }
            _shareCardState.value = ShareCardState.Error(message)
            _events.tryEmit(WalkShareEvent.Failed(message))
        } catch (t: Throwable) {
            Log.w(TAG, "share failed with unexpected throwable", t)
            val message = context.getString(UNKNOWN_MESSAGE)
            _shareCardState.value = ShareCardState.Error(message)
            _events.tryEmit(WalkShareEvent.Failed(message))
        }
    }

    /**
     * iOS `retryFailedMedia` (`:234-305@3f9f9e8`). Leaves
     * [ShareCardState.Partial] synchronously (the retry button is gone
     * before the possibly-multi-second photo re-export starts), resolves
     * every still-pending slot by IDENTITY against current data, and
     * ends on the repair-unavailable explanation rather than looping
     * back to the same button forever.
     */
    private suspend fun runRepair() {
        // Each of these three is "there is nothing left to repair
        // against" — the page expired, the walk is gone, the record was
        // cleared. Returning silently would leave the retry button
        // exactly where it was, inviting the same tap forever; the
        // repair-unavailable explanation is what iOS shows instead
        // (`:268-282@3f9f9e8`).
        val cached = _cachedShare.value?.takeIf { !it.isExpiredAt() } ?: return explainRepairUnavailable()
        val uuid = walkUuid ?: return explainRepairUnavailable()
        val record = shareRepairStore.load(uuid) ?: return explainRepairUnavailable()
        if (!isRepairRecordCurrent(record.shareId, cached.id)) {
            shareRepairStore.clear(uuid)
            _shareCardState.value = ShareCardState.Success(cached.url)
            return
        }
        val pending = pendingSlots(record.slots)
        if (pending.isEmpty()) return

        _shareCardState.value = ShareCardState.UploadingMedia(completed = 0, total = pending.size)

        try {
            uploadRepairSlots(uuid, cached, record, pending)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Everything from here up was store I/O, re-encodes,
            // re-exports and PUT bookkeeping — all of it able to throw.
            // Letting that unwind to [runGuarded] would swap the repair
            // offer for a generic Error card whose "Try Again" calls
            // [share], i.e. a SECOND POST over the page this pass was
            // repairing. The page is live either way, so the walker stays
            // on the repair card with whatever the record still owes.
            Log.w(TAG, "repair pass failed", t)
            _shareCardState.value = ShareCardState.Partial(cached.url, pendingCountOrElse(uuid, pending.size))
        }
    }

    /**
     * The repair pass proper, split out so [runRepair] can contain every
     * throw it can produce without burying the guard clauses that decide
     * whether there is anything to repair at all.
     */
    private suspend fun uploadRepairSlots(
        uuid: String,
        cached: CachedShare,
        record: org.walktalkmeditate.pilgrim.data.share.RepairRecord,
        pending: List<org.walktalkmeditate.pilgrim.data.share.RepairSlot>,
    ) {
        val resolution = resolveRepairSlots(
            pending = pending,
            audioArtifacts = ensureArtifactsFor(uuid, pending),
            photos = reExportPhotosFor(uuid, pending),
        )

        if (!resolution.hasUploadable) {
            _repairUnavailable.value = true
            _shareCardState.value = ShareCardState.Partial(cached.url, resolution.unresolved.size)
            return
        }

        _shareCardState.value = ShareCardState.UploadingMedia(
            completed = 0,
            total = resolution.photoSlots.size + resolution.audioSlots.size,
        )
        val outcome = shareService.uploadMedia(
            walkUuid = uuid,
            shareId = record.shareId,
            shareUrl = cached.url,
            photos = resolution.photoSlots,
            audio = resolution.audioSlots,
            onProgress = ::applyMediaProgress,
        )
        val remaining = outcome.failedCount + resolution.unresolved.size
        if (remaining == 0) {
            _shareCardState.value = ShareCardState.Success(cached.url)
            // A repair that lands everything is a success like any other
            // — iOS reveals the page on `.uploadingMedia -> .success`
            // exactly as it does on `.uploading -> .success`
            // (`WalkShareView.swift:361-366@3f9f9e8`), and this pass is
            // the only way to reach the first of those two.
            _events.tryEmit(WalkShareEvent.Success(cached.url))
            cleanUpArtifacts(uuid)
        } else {
            if (outcome.failedCount == 0) {
                // uploadMedia clears the whole record when ITS batch is
                // clean, which would take the unresolvable slots with it
                // — iOS keeps them ("stillFailed + remainingAfterResolve",
                // `:300-304@3f9f9e8`), so re-establish them here.
                shareRepairStore.prePopulate(uuid, record.shareId, resolution.unresolved)
            }
            _shareCardState.value = ShareCardState.Partial(cached.url, remaining)
        }
    }

    /**
     * What the repair record still owes, counted the way
     * [restoreFromCacheIfIdle] counts it. Falls back to [fallback] — the
     * count this pass started from — when the record cannot be read at
     * all, so a failed pass never understates what is still missing.
     */
    private suspend fun pendingCountOrElse(uuid: String, fallback: Int): Int = try {
        shareRepairStore.load(uuid)?.slots?.let(::pendingSlots)?.size ?: fallback
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Log.w(TAG, "repair pending-count read failed for $uuid", t)
        fallback
    }

    /**
     * Swaps the retry button for the static explanation
     * ([ShareStatusSection]'s `PartialFailureBlock`). The card itself is
     * left alone: every caller is inside a pass that started from
     * [ShareCardState.Partial] and has not moved off it yet.
     */
    private fun explainRepairUnavailable() {
        _repairUnavailable.value = true
    }

    /**
     * Resolves each pending AUDIO slot's artifact, re-encoding one the
     * cache evicted ([SharePrepStore.ensureArtifact], port plan
     * Decision 3 — "A repair retry that finds a missing artifact
     * re-encodes from the WAV rather than failing"). A recording the
     * walk no longer has simply never lands in the map, and its slot
     * falls out as unresolved.
     */
    private suspend fun ensureArtifactsFor(
        uuid: String,
        pending: List<org.walktalkmeditate.pilgrim.data.share.RepairSlot>,
    ): Map<String, File> {
        val resolved = mutableMapOf<String, File>()
        for (slot in pending) {
            val identity = slot.identity as? org.walktalkmeditate.pilgrim.data.share.SlotIdentity.Audio ?: continue
            if (identity.recordingUuid in resolved) continue
            val recording = recordingsByUuid[identity.recordingUuid] ?: continue
            val artifact = try {
                sharePrepStore.ensureArtifact(uuid, recording)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "repair artifact resolution failed for ${identity.recordingUuid}", t)
                null
            }
            if (artifact != null) resolved[identity.recordingUuid] = artifact
        }
        return resolved
    }

    /**
     * Re-exports ONLY the photos a repair still needs, matched by
     * identity — "not the whole (up to 20-photo) export list again"
     * (`:258-266@3f9f9e8`).
     */
    private suspend fun reExportPhotosFor(uuid: String, pending: List<org.walktalkmeditate.pilgrim.data.share.RepairSlot>):
        List<TourPhoto> {
        val wanted = pending.mapNotNull { (it.identity as? org.walktalkmeditate.pilgrim.data.share.SlotIdentity.Photo) }
        if (wanted.isEmpty()) return emptyList()
        val loaded = _uiState.value as? WalkShareUiState.Loaded ?: return emptyList()
        val candidates = loaded.inputs.pinnedPhotos.filter { photo ->
            wanted.any { it.sourceUri == photo.photoUri && it.ts == (photo.takenAt ?: 0L) / MILLIS_PER_SECOND }
        }
        if (candidates.isEmpty()) return emptyList()
        return tourPhotoExporter.export(uuid, candidates).photos
    }

    /**
     * Resolves each included candidate's transcode artifact, re-encoding
     * one the cache evicted ([SharePrepStore.ensureArtifact], port plan
     * Decision 3).
     */
    private suspend fun planInteractiveAudioUploadsResolving(
        uuid: String,
        candidates: List<TourRecordingCandidate>,
    ): InteractiveUploadPlan {
        val resolved = mutableMapOf<String, File>()
        for (candidate in TourBuilder.includedCandidates(candidates)) {
            val recording = recordingsByUuid[candidate.recordingUuid] ?: continue
            val artifact = try {
                sharePrepStore.ensureArtifact(uuid, recording)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "artifact resolution failed for ${candidate.recordingUuid}", t)
                null
            }
            if (artifact != null) resolved[candidate.recordingUuid] = artifact
        }
        return planInteractiveAudioUploads(
            candidates = candidates,
            walkerExcludedUuids = _excludedRecordingUuids.value,
            artifactFor = { resolved[it.recordingUuid] },
        )
    }

    /**
     * iOS `applyMediaProgress` (`:378-388@3f9f9e8`): only applies while
     * the phase is still [ShareCardState.UploadingMedia], so a tick that
     * arrives after a terminal state cannot flip the UI backward — or
     * re-lock a form the walker already backed out of.
     */
    private fun applyMediaProgress(completed: Int, total: Int) {
        _shareCardState.update { current ->
            if (current is ShareCardState.UploadingMedia) ShareCardState.UploadingMedia(completed, total) else current
        }
    }

    /** iOS `applyPreparingPhotosProgress` (`:390-397@3f9f9e8`) — same late-arrival guard, export phase. */
    private fun applyPreparingPhotosProgress(completed: Int, total: Int) {
        _shareCardState.update { current ->
            if (current is ShareCardState.PreparingPhotos) ShareCardState.PreparingPhotos(completed, total) else current
        }
    }

    /**
     * [restoreFromCacheIfIdle] with its store failures contained. One
     * throw inside the observer's `collect { }` ends that observer for
     * the life of the ViewModel (Stage 5-D house rule), taking every
     * later cached-share write with it — and the restore's repair-record
     * reads are real DataStore I/O.
     *
     * A record that cannot be read is also no reason to leave an
     * actionable [ShareCardState.Idle] over a live page: a live page
     * with no readable record is exactly the [ShareCardState.Success]
     * the no-record path restores to, so the failure falls into it
     * rather than into the state that offers to share again.
     */
    private suspend fun restoreFromCache(cached: CachedShare?) {
        try {
            restoreFromCacheIfIdle(cached)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "cached-share restore failed", t)
            val active = cached?.takeIf { !it.isExpiredAt() } ?: return
            if (!_isSharing.value && _shareCardState.value == ShareCardState.Idle) {
                didRestoreFromCache = true
                _shareCardState.value = ShareCardState.Success(active.url)
            }
        }
    }

    /**
     * iOS's restore-at-init (`WalkShareViewModel.swift:203-212@3f9f9e8`):
     * a non-expired cached share puts the modal straight into its shared
     * card, and un-landed media restores [ShareCardState.Partial] —
     * "not a quiet .success" — so "Carry the missing files" survives
     * leaving and returning. Android reads the cache through an observer
     * rather than a constructor call, so this also carries the
     * stale-record clearing iOS gets from its unconditional write sites.
     */
    private suspend fun restoreFromCacheIfIdle(cached: CachedShare?) {
        if (didRestoreFromCache) return
        if (_isSharing.value || _shareCardState.value != ShareCardState.Idle) return
        val uuid = walkUuid ?: return
        val active = cached?.takeIf { !it.isExpiredAt() }
        if (active == null) {
            // No live page: any record left behind belongs to a share
            // that no longer exists (or expired out).
            if (cached != null || shareRepairStore.load(uuid) != null) shareRepairStore.clear(uuid)
            return
        }
        didRestoreFromCache = true
        val record = shareRepairStore.load(uuid)
        val pending = record?.slots?.let(::pendingSlots).orEmpty()
        _shareCardState.value = if (isRepairRecordCurrent(record?.shareId, active.id) && pending.isNotEmpty()) {
            ShareCardState.Partial(active.url, pending.size)
        } else {
            if (record != null) shareRepairStore.clear(uuid)
            ShareCardState.Success(active.url)
        }
    }

    /** Success is terminal for this walk's prep cache — nothing left to repair from. */
    private suspend fun cleanUpArtifacts(uuid: String) {
        try {
            sharePrepStore.cancelAndCleanupWalk(uuid)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "share-prep cleanup failed for $uuid", t)
        }
    }

    private fun shareOptions(interactive: Boolean, excluded: Set<String>) = WalkShareOptions(
        expiry = _selectedExpiry.value,
        journal = _journal.value,
        includeDistance = _includeDistance.value,
        includeDuration = _includeDuration.value,
        includeElevation = _includeElevation.value,
        includeActivityBreakdown = _includeActivityBreakdown.value,
        includeSteps = _includeSteps.value,
        includeWaypoints = _includeWaypoints.value,
        includePhotos = _includePhotos.value,
        interactive = interactive,
        trimEnabled = _trimEnabled.value,
        excludedRecordingUuids = excluded,
        kindOverrides = _kindOverrides.value,
    )

    /**
     * iOS `photoPayload` (`WalkShareViewModel.swift:424-440@3f9f9e8`).
     * On the interactive branch metadata comes ONLY from the export —
     * "Never map pinnedPhotos here — a failed export would orphan map
     * markers." The classic branch keeps embedding base64 JPEGs.
     */
    private fun photoPayloadFor(
        interactive: Boolean,
        pinned: List<WalkPhoto>,
        tourPhotos: List<TourPhoto>,
    ): List<SharePayload.Photo>? {
        if (!_includePhotos.value || pinned.isEmpty()) return null
        if (interactive) return tourPhotos.map { it.meta }.ifEmpty { null }
        return pinned.mapNotNull { photo ->
            val data = photoEncoder.encodeBase64(photo.photoUri) ?: return@mapNotNull null
            SharePayload.Photo(
                lat = photo.capturedLat ?: 0.0,
                lon = photo.capturedLng ?: 0.0,
                ts = (photo.takenAt ?: 0L) / MILLIS_PER_SECOND,
                data = data,
            )
        }
    }

    /**
     * iOS `interactivePhotoExportList` (`:399-414@3f9f9e8`): the pinned
     * photos inside the trim's kept window, capped at 20 — reused by the
     * export, the retry, and the totals label so all three agree on
     * which photos are in scope.
     */
    internal fun interactivePhotoExportList(): List<WalkPhoto> {
        val loaded = _uiState.value as? WalkShareUiState.Loaded ?: return emptyList()
        if (loaded.inputs.pinnedPhotos.isEmpty()) return emptyList()
        val prepared = preparedRoute ?: return emptyList()
        val window = computeInteractiveRoute(
            prepared.downsampled,
            shareOptions(_interactiveEnabled.value, _excludedRecordingUuids.value),
        ).keptWindow
        return loaded.inputs.pinnedPhotos
            .filter { window?.contains((it.takenAt ?: 0L) / MILLIS_PER_SECOND) ?: true }
            .take(TourPhotoExporter.MAX_PHOTOS)
    }

    private fun hasPinnedPhotos(): Boolean =
        (_uiState.value as? WalkShareUiState.Loaded)?.inputs?.pinnedPhotos?.isNotEmpty() == true

    /** Every recording with a source file — the disclosure needs a transcoded size for each before it can render one. */
    private fun prepareableRecordings(): List<VoiceRecording> =
        (_uiState.value as? WalkShareUiState.Loaded)?.inputs?.voiceRecordings
            ?.filter { it.fileRelativePath.isNotEmpty() && it.endTimestamp > it.startTimestamp }
            .orEmpty()

    /**
     * Launches a transcode pass over [recordings], gating [canShare] for
     * its whole duration.
     *
     * [asToggleTransition] threads the pass onto [prepLifecycleJob]'s
     * chain. The per-row re-include path leaves it false: re-including
     * one recording must not preempt the pass preparing the rest.
     */
    private fun startPrep(recordings: List<VoiceRecording>, asToggleTransition: Boolean = false) {
        val uuid = walkUuid ?: return
        if (recordings.isEmpty()) return
        _prepInFlight.update { it + 1 }
        val job = launchPrepWork(
            previous = if (asToggleTransition) prepLifecycleJob else null,
            onFinish = { _prepInFlight.update { remaining -> (remaining - 1).coerceAtLeast(0) } },
        ) { sharePrepStore.prepare(uuid, recordings) }
        if (asToggleTransition) prepLifecycleJob = job
    }

    /**
     * Fire-and-forget prep-store I/O off Main, with the house CE
     * discipline. [previous], when given, is cancelled and joined before
     * [block] runs at all — see [prepLifecycleJob].
     *
     * [CoroutineStart.ATOMIC] for the same reason [launchShareAttempt]
     * uses it: a cancellation landing between this `launch` and the IO
     * dispatch would otherwise skip the body — and with it [onFinish],
     * stranding the [_prepInFlight] increment its caller already made and
     * shutting the Share gate for the life of this ViewModel. ATOMIC
     * makes the `finally` an actual guarantee.
     */
    private fun launchPrepWork(
        previous: Job? = null,
        onFinish: () -> Unit = {},
        block: suspend () -> Unit,
    ): Job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
        try {
            // The CANCEL is prompt — a toggle-off still preempts the pass
            // it is replacing rather than queueing behind its remaining
            // encodes. Only the WAIT is [NonCancellable], and it has to
            // be: a transition cancelled while joining here would release
            // the next one to start while its own predecessor was still
            // unwinding, and a cleanup finishing after the final prep is
            // precisely the interleaving this chain exists to forbid.
            // Non-cancellable joins make each link imply all the ones
            // before it, so the chain is a total order rather than a
            // suggestion.
            if (previous != null) withContext(NonCancellable) { previous.cancelAndJoin() }
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "share prep work failed", t)
        } finally {
            onFinish()
        }
    }

    private suspend fun loadInputs() {
        val walk = try {
            repository.getWalk(walkId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Transient Room error → surface as NotFound so the UI
            // doesn't stay stuck in Loading forever. User can dismiss +
            // re-open; the modal is re-navigable.
            Log.w(TAG, "getWalk($walkId) threw", t)
            _uiState.value = WalkShareUiState.NotFound
            return
        }
        if (walk == null) {
            _uiState.value = WalkShareUiState.NotFound
            return
        }
        val endTs = walk.endTimestamp ?: walk.startTimestamp
        val samples: List<org.walktalkmeditate.pilgrim.data.entity.RouteDataSample>
        val altitudes: List<org.walktalkmeditate.pilgrim.data.entity.AltitudeSample>
        val events: List<org.walktalkmeditate.pilgrim.data.entity.WalkEvent>
        val intervals: List<org.walktalkmeditate.pilgrim.data.entity.ActivityInterval>
        val recordings: List<VoiceRecording>
        val waypoints: List<org.walktalkmeditate.pilgrim.data.entity.Waypoint>
        val pinnedPhotos: List<WalkPhoto>
        try {
            samples = repository.locationSamplesFor(walkId)
            altitudes = repository.altitudeSamplesFor(walkId)
            events = repository.eventsFor(walkId)
            intervals = repository.activityIntervalsFor(walkId)
            recordings = repository.voiceRecordingsFor(walkId)
            waypoints = repository.waypointsFor(walkId)
            pinnedPhotos = repository.photosFor(walkId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "walk-related DAO read threw for walk $walkId", t)
            _uiState.value = WalkShareUiState.NotFound
            return
        }

        val points = samples.map {
            LocationPoint(
                timestamp = it.timestamp,
                latitude = it.latitude,
                longitude = it.longitude,
            )
        }
        val totals = replayWalkEventTotals(events = events, closeAt = endTs)
        val totalElapsedMs = endTs - walk.startTimestamp
        val activeWalkingMs = (totalElapsedMs - totals.totalPausedMillis - totals.totalMeditatedMillis)
            .coerceAtLeast(0)
        val distance = walkDistanceMeters(points)

        // Elevation: sum of positive deltas on the timestamp-sorted
        // altitude series (mirrors Stage 7-C composeEtegamiSpec). The
        // descent side is the negated version of the same scan.
        val sortedAlts = altitudes.sortedBy { it.timestamp }
        var ascent = 0.0
        var descent = 0.0
        for (i in 1 until sortedAlts.size) {
            val delta = sortedAlts[i].altitudeMeters - sortedAlts[i - 1].altitudeMeters
            if (delta.isFinite()) {
                if (delta > 0) ascent += delta else descent += -delta
            }
        }

        val meditateSeconds = intervals
            .filter { it.activityType == ActivityType.MEDITATING }
            .sumOf { (it.endTimestamp - it.startTimestamp) / 1_000.0 }
        val talkSeconds = recordings.sumOf { (it.endTimestamp - it.startTimestamp) / 1_000.0 }

        val inputs = ShareInputs(
            walk = walk,
            routePoints = points,
            altitudeSamples = altitudes,
            activityIntervals = intervals,
            voiceRecordings = recordings,
            waypoints = waypoints,
            distanceMeters = distance,
            activeDurationSeconds = activeWalkingMs / 1_000.0,
            meditateDurationSeconds = meditateSeconds,
            talkDurationSeconds = talkSeconds,
            elevationAscentMeters = ascent,
            elevationDescentMeters = descent,
            // Steps not yet tracked on Android (Phase 1/2 scope gap);
            // always null for 8-A. Backend accepts null.
            steps = null,
            pinnedPhotos = pinnedPhotos,
            // Phase 19: the payload's `pauses`. WalkMetricsMath.PauseSpan
            // is field-identical to the share layer's own (startMs +
            // durationMillis, no endMs — buildPauses reconstructs the end
            // as startMs + durationMillis, which is exactly what the
            // automaton stored). Mapped at this seam rather than shared
            // as one type so the pure payload layer keeps no dependency
            // on the walk-metrics package — the same seam
            // PromptsCoordinator already uses for PauseContext.
            pauseSpans = WalkMetricsMath.pauseSpans(walk, events)
                .map { PauseSpan(startMs = it.startMs, durationMillis = it.durationMillis) },
        )
        recordingsByUuid = recordings.associateBy { it.uuid }
        _walkUuid.value = walk.uuid
        // Published BEFORE Loaded: the UI transform that reads it is
        // woken by exactly this assignment to _uiState.
        preparedRoute = prepareRoute(inputs)
        _uiState.value = WalkShareUiState.Loaded(inputs = inputs)
    }

    // Backed by private MSFs + exposed as read-only StateFlow.
    private val _toggledStatsCount = MutableStateFlow(0)
    val toggledStatsCount: StateFlow<Int> = _toggledStatsCount.asStateFlow()

    private val _canShare = MutableStateFlow(false)
    val canShare: StateFlow<Boolean> = _canShare.asStateFlow()

    /**
     * Whether a share may start right now: not already in flight, at
     * least one stat toggle on, the loaded walk has >= 2 route points,
     * and — when Interactive is on — [interactiveReadyNow].
     *
     * iOS gates on the tour caps alone (`canShare`,
     * `WalkShareViewModel.swift:44-45@3f9f9e8`); the toggle/route
     * conditions are Android's, carried forward from Stage 8-A, and the
     * prep condition is the port plan's Decision 2. Source-read for the
     * reason [candidatesNow] documents; [canShare] republishes it for
     * the button's `enabled`.
     */
    private fun canShareNow(): Boolean {
        if (_isSharing.value) return false
        val loaded = _uiState.value as? WalkShareUiState.Loaded ?: return false
        if (loaded.inputs.routePoints.size < ShareConfig.ROUTE_MIN_POINTS) return false
        if (toggledStatsNow() == 0) return false
        return interactiveReadyNow()
    }

    private fun toggledStatsNow(): Int = listOf(
        _includeDistance,
        _includeDuration,
        _includeElevation,
        _includeActivityBreakdown,
        _includeSteps,
    ).count { it.value }

    init {
        viewModelScope.launch {
            combine(
                _includeDistance,
                _includeDuration,
                _includeElevation,
                _includeActivityBreakdown,
                _includeSteps,
            ) { _, _, _, _, _ -> toggledStatsNow() }.collect { _toggledStatsCount.value = it }
        }
        viewModelScope.launch {
            combine(
                _isSharing,
                _toggledStatsCount,
                _uiState,
                interactiveShareReady,
            ) { _, _, _, _ -> canShareNow() }.collect { _canShare.value = it }
        }
    }

    companion object {
        const val ARG_WALK_ID = "walkId"
        private const val TAG = "WalkShareVM"
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
        private val RATE_LIMITED_MESSAGE = org.walktalkmeditate.pilgrim.R.string.share_modal_error_rate_limited
        private val UNKNOWN_MESSAGE = org.walktalkmeditate.pilgrim.R.string.share_modal_error_unknown
        private val NOT_LOADED_MESSAGE = org.walktalkmeditate.pilgrim.R.string.share_modal_not_found
    }
}

sealed interface WalkShareUiState {
    data object Loading : WalkShareUiState
    data object NotFound : WalkShareUiState
    data class Loaded(val inputs: ShareInputs) : WalkShareUiState
}

sealed interface WalkShareEvent {
    data class Success(val url: String) : WalkShareEvent
    data object RateLimited : WalkShareEvent
    data class Failed(val message: String) : WalkShareEvent
}
