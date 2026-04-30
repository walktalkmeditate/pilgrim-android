// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.audio.BellPlaying
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode
import org.walktalkmeditate.pilgrim.data.appearance.AppearancePreferencesRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveMilestone
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveStats
import org.walktalkmeditate.pilgrim.data.collective.MilestoneSurface
import org.walktalkmeditate.pilgrim.data.practice.PracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoicePreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.ui.settings.voice.VoiceCardState

/**
 * Stage 8-B: ViewModel for the Settings screen surfaces — currently
 * just the collective-counter display + opt-in toggle. Passthrough
 * to [CollectiveRepository]; no UI-only state lives here.
 *
 * Stage 9.5-E adds the appearance-mode passthrough to
 * [AppearancePreferencesRepository] so the Atmosphere card can render
 * the persisted preference and write user changes back.
 *
 * Stage 10-C Chunk D adds the practice + units passthroughs powering
 * the new PracticeCard:
 *  - [beginWithIntention], [celestialAwarenessEnabled], [zodiacSystem],
 *    [walkReliquaryEnabled] proxy [PracticePreferencesRepository].
 *  - [distanceUnits] proxies [UnitsPreferencesRepository] (used by
 *    PracticeSummaryHeader for unit-aware distance formatting).
 *  - The "Walk with the collective" toggle in PracticeCard reuses the
 *    existing [optIn] / [setOptIn] pair — no separate pref needed.
 *  - The photos-denied note is transient screen-level UI state owned
 *    by the SettingsScreen composable (rememberSaveable), not the VM.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val collectiveRepository: CollectiveRepository,
    private val appearancePreferences: AppearancePreferencesRepository,
    private val soundsPreferences: SoundsPreferencesRepository,
    private val practicePreferences: PracticePreferencesRepository,
    private val unitsPreferences: UnitsPreferencesRepository,
    private val voicePreferences: VoicePreferencesRepository,
    private val walkRepository: WalkRepository,
    private val voiceRecordingFileSystem: VoiceRecordingFileSystem,
    private val milestoneSurface: MilestoneSurface,
    private val bellPlayer: BellPlaying,
) : ViewModel() {

    val stats: StateFlow<CollectiveStats?> = collectiveRepository.stats
    val optIn: StateFlow<Boolean> = collectiveRepository.optIn
    val appearanceMode: StateFlow<AppearanceMode> = appearancePreferences.appearanceMode
    val soundsEnabled: StateFlow<Boolean> = soundsPreferences.soundsEnabled

    /**
     * Passthrough so [PracticeSummaryHeader] can format community
     * totals in the user's preferred units, and so the Practice card
     * can drive its segmented Units row + caption.
     */
    val distanceUnits: StateFlow<UnitSystem> = unitsPreferences.distanceUnits

    /**
     * Stage 11-B Task 15: pending sacred-number milestone published by
     * the [MilestoneSurface] (concrete: `CollectiveMilestoneDetector`)
     * after the collective repository's stats fetch crosses an unseen
     * threshold. Direct passthrough so [PracticeSummaryHeader]'s overlay
     * mirrors the detector exactly — `null` while no milestone is
     * pending or after [dismissMilestone] clears it.
     */
    val milestone: StateFlow<CollectiveMilestone?> = milestoneSurface.milestone

    val beginWithIntention: StateFlow<Boolean> = practicePreferences.beginWithIntention
    val celestialAwarenessEnabled: StateFlow<Boolean> = practicePreferences.celestialAwarenessEnabled
    val zodiacSystem: StateFlow<ZodiacSystem> = practicePreferences.zodiacSystem
    val walkReliquaryEnabled: StateFlow<Boolean> = practicePreferences.walkReliquaryEnabled

    fun setOptIn(value: Boolean) {
        viewModelScope.launch { collectiveRepository.setOptIn(value) }
    }

    fun setAppearanceMode(mode: AppearanceMode) {
        viewModelScope.launch {
            // DataStore writes can throw on disk-full / corrupt-prefs / IO
            // failure. viewModelScope uses a SupervisorJob, so an unhandled
            // throw from this child coroutine routes to the thread's
            // uncaught exception handler — on Main, that crashes the
            // process. Swallow + log: the optimistic UI selection (driven
            // by collectAsStateWithLifecycle on the StateFlow) will revert
            // to the persisted value on next emission, signaling the
            // failure to the user without crashing.
            runCatching { appearancePreferences.setAppearanceMode(mode) }
                .onFailure { Log.w(TAG, "failed to persist appearance mode", it) }
        }
    }

    fun setSoundsEnabled(value: Boolean) {
        viewModelScope.launch {
            // Same swallow-and-log pattern as setAppearanceMode: on a
            // DataStore write failure, the UI's optimistic checked state
            // reverts to the persisted value via the StateFlow re-emit.
            runCatching { soundsPreferences.setSoundsEnabled(value) }
                .onFailure { Log.w(TAG, "failed to persist sounds toggle", it) }
        }
    }

    fun setBeginWithIntention(value: Boolean) {
        viewModelScope.launch {
            runCatching { practicePreferences.setBeginWithIntention(value) }
                .onFailure { Log.w(TAG, "failed to persist beginWithIntention", it) }
        }
    }

    fun setCelestialAwarenessEnabled(value: Boolean) {
        viewModelScope.launch {
            runCatching { practicePreferences.setCelestialAwarenessEnabled(value) }
                .onFailure { Log.w(TAG, "failed to persist celestialAwarenessEnabled", it) }
        }
    }

    fun setZodiacSystem(value: ZodiacSystem) {
        viewModelScope.launch {
            runCatching { practicePreferences.setZodiacSystem(value) }
                .onFailure { Log.w(TAG, "failed to persist zodiacSystem", it) }
        }
    }

    fun setDistanceUnits(value: UnitSystem) {
        viewModelScope.launch {
            runCatching { unitsPreferences.setDistanceUnits(value) }
                .onFailure { Log.w(TAG, "failed to persist distanceUnits", it) }
        }
    }

    fun setWalkReliquaryEnabled(value: Boolean) {
        viewModelScope.launch {
            runCatching { practicePreferences.setWalkReliquaryEnabled(value) }
                .onFailure { Log.w(TAG, "failed to persist walkReliquaryEnabled", it) }
        }
    }

    /**
     * Stage 10-D: aggregate count + total bytes of every stored
     * voice recording. The aggregation hops onto [Dispatchers.IO]
     * because [VoiceRecordingFileSystem.fileSizeBytes] does a
     * `File.length()` syscall per row — running that on Main during
     * recompose trips StrictMode in tests and risks ANRs on device
     * once a user has accumulated dozens of recordings.
     */
    val recordingsAggregate: StateFlow<RecordingsAggregate> =
        walkRepository.observeAllVoiceRecordings()
            .map { recs ->
                RecordingsAggregate(
                    count = recs.size,
                    sizeBytes = recs.sumOf { voiceRecordingFileSystem.fileSizeBytes(it.fileRelativePath) },
                )
            }
            .flowOn(Dispatchers.IO)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = RecordingsAggregate(0, 0L),
            )

    /**
     * Stage 10-D: snapshot driving [VoiceCard]. Combines the two
     * voice prefs with the recordings aggregate — any of the three
     * changing re-emits a fresh [VoiceCardState].
     *
     * `WhileSubscribed(5_000)` is fine here: the only consumer is
     * the SettingsScreen Compose tree, which stays subscribed via
     * `collectAsStateWithLifecycle` while Settings is composed.
     * The 5-second tail covers config-change recompositions
     * (rotation) without a cold-restart. The `voiceGuideEnabled`
     * upstream is `Eagerly`-shared so its `.value` reads from
     * background contexts (orchestrator, walk-finalize observer)
     * are unaffected by this VM-side caching policy.
     */
    val voiceCardState: StateFlow<VoiceCardState> = combine(
        voicePreferences.voiceGuideEnabled,
        voicePreferences.autoTranscribe,
        recordingsAggregate,
    ) { vge, at, agg ->
        VoiceCardState(
            voiceGuideEnabled = vge,
            autoTranscribe = at,
            recordingsCount = agg.count,
            recordingsSizeBytes = agg.sizeBytes,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = VoiceCardState(
            voiceGuideEnabled = false,
            autoTranscribe = false,
            recordingsCount = 0,
            recordingsSizeBytes = 0L,
        ),
    )

    fun setVoiceGuideEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { voicePreferences.setVoiceGuideEnabled(enabled) }
                .onFailure { Log.w(TAG, "failed to persist voiceGuideEnabled", it) }
        }
    }

    fun setAutoTranscribe(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { voicePreferences.setAutoTranscribe(enabled) }
                .onFailure { Log.w(TAG, "failed to persist autoTranscribe", it) }
        }
    }

    fun fetchOnAppear() {
        viewModelScope.launch { collectiveRepository.fetchIfStale() }
    }

    /**
     * Called once per milestone *number* by [PracticeSummaryHeader] when
     * its overlay first composes — fires the temple bell at 0.4 × the
     * user's bellVolume preference, matching iOS's milestone-bell volume
     * envelope. Multiplicative scaling preserves the muted-user invariant
     * (bellVolume=0 stays silent because `0 × 0.4 = 0`).
     */
    fun onMilestoneShown(milestone: CollectiveMilestone) {
        bellPlayer.play(scale = MILESTONE_BELL_SCALE)
    }

    /**
     * Dismiss action — nulls the detector's StateFlow. Routed by
     * [PracticeSummaryHeader]'s 8-second auto-dismiss `LaunchedEffect`
     * once the overlay has been visible for the full duration. Mirrors
     * iOS behaviour: no tap-to-dismiss surface — the overlay clears
     * itself after 8 seconds and stays gone for the rest of the
     * session unless the next sacred number crosses.
     */
    fun dismissMilestone() {
        milestoneSurface.clear()
    }

    /**
     * Per-user aggregate driving the [PracticeSummaryHeader] stats whisper.
     *
     * Stage 11-A: reads the `Walk.distanceMeters` + `Walk.meditationSeconds`
     * cache cols populated at finalize-time (Task 5) and drained for stale
     * rows by [WalkMetricsBackfillCoordinator] (Task 6). Drops the previous
     * per-walk N+1 (`locationSamplesFor` + `activityIntervalsFor`) to zero
     * queries per emission — Settings tab opens immediately even with
     * thousands of walks. A `null` cache col means "not yet computed" and
     * contributes 0 to the running sum until the backfill catches up.
     */
    val practiceSummary: StateFlow<PracticeSummaryStats> = walkRepository.observeAllWalks()
        .map { walks ->
            val finished = walks.filter { it.endTimestamp != null }
            if (finished.isEmpty()) return@map PracticeSummaryStats.Empty
            PracticeSummaryStats(
                walkCount = finished.size,
                totalDistanceMeters = finished.sumOf { it.distanceMeters ?: 0.0 },
                totalMeditationSeconds = finished.sumOf { it.meditationSeconds ?: 0L },
                firstWalkInstant = Instant.ofEpochMilli(finished.minOf { it.startTimestamp }),
            )
        }
        .catch { emit(PracticeSummaryStats.Empty) }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = PracticeSummaryStats.Empty,
        )

    private companion object {
        const val TAG = "SettingsViewModel"
        const val MILESTONE_BELL_SCALE = 0.4f
    }
}

/**
 * Aggregate stats powering [PracticeSummaryHeader]. `Empty` is the
 * initial-load state and the post-error fallback (matches AboutStats
 * pattern).
 */
data class PracticeSummaryStats(
    val walkCount: Int,
    val totalDistanceMeters: Double,
    val totalMeditationSeconds: Long,
    val firstWalkInstant: Instant?,
) {
    companion object {
        val Empty = PracticeSummaryStats(
            walkCount = 0,
            totalDistanceMeters = 0.0,
            totalMeditationSeconds = 0L,
            firstWalkInstant = null,
        )
    }
}

/**
 * Stage 10-D: shape of the voice-recording aggregation that powers
 * the Recordings nav row's `X recordings • Y.Y MB` caption. Stored
 * as a small immutable snapshot so [SettingsViewModel.voiceCardState]
 * can compose it with the voice prefs via `combine`.
 */
data class RecordingsAggregate(val count: Int, val sizeBytes: Long)
