// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshotCalc
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.practice.PracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.share.CachedShare
import org.walktalkmeditate.pilgrim.data.share.CachedShareStore
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.walk.WalkMetricsMath
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters
import org.walktalkmeditate.pilgrim.ui.design.seals.SealColorPalette
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec
import org.walktalkmeditate.pilgrim.ui.design.seals.toSealSpec
import org.walktalkmeditate.pilgrim.ui.etegami.EtegamiSealBitmapRenderer
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * Stage 14 rewrite — observes the full walk list, share cache, units, and
 * celestial-awareness pref, and emits a [JournalUiState] containing
 * pre-built per-walk [WalkSnapshot]s + a roll-up [JourneySummary]. CPU
 * work happens inside `withContext(defaultDispatcher)` (Stage 13-XZ B2
 * lesson); IO-side per-walk DAO reads run on `ioDispatcher`.
 */
@HiltViewModel
class HomeViewModel internal constructor(
    @ApplicationContext private val context: Context,
    private val repository: WalkRepository,
    private val clock: Clock,
    hemisphereRepository: HemisphereRepository,
    unitsPreferences: UnitsPreferencesRepository,
    private val cachedShareStore: CachedShareStore,
    private val practicePreferences: PracticePreferencesRepository,
    private val archivedRegistry: org.walktalkmeditate.pilgrim.data.pilgrim.ArchivedWalkRegistry,
    private val defaultDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        repository: WalkRepository,
        clock: Clock,
        hemisphereRepository: HemisphereRepository,
        unitsPreferences: UnitsPreferencesRepository,
        cachedShareStore: CachedShareStore,
        practicePreferences: PracticePreferencesRepository,
        archivedRegistry: org.walktalkmeditate.pilgrim.data.pilgrim.ArchivedWalkRegistry,
    ) : this(
        context = context,
        repository = repository,
        clock = clock,
        hemisphereRepository = hemisphereRepository,
        unitsPreferences = unitsPreferences,
        cachedShareStore = cachedShareStore,
        practicePreferences = practicePreferences,
        archivedRegistry = archivedRegistry,
        defaultDispatcher = Dispatchers.Default,
        ioDispatcher = Dispatchers.IO,
    )

    val hemisphere: StateFlow<Hemisphere> = hemisphereRepository.hemisphere
    val distanceUnits: StateFlow<UnitSystem> = unitsPreferences.distanceUnits

    private val _expandedSnapshotId = MutableStateFlow<Long?>(null)
    val expandedSnapshotId: StateFlow<Long?> = _expandedSnapshotId.asStateFlow()

    private val _expandedCelestialSnapshot = MutableStateFlow<CelestialSnapshot?>(null)
    val expandedCelestialSnapshot: StateFlow<CelestialSnapshot?> =
        _expandedCelestialSnapshot.asStateFlow()
    private var celestialJob: Job? = null

    private val _latestSealBitmap = MutableStateFlow<ImageBitmap?>(null)
    val latestSealBitmap: StateFlow<ImageBitmap?> = _latestSealBitmap.asStateFlow()

    private val _latestSealSpec = MutableStateFlow<SealSpec?>(null)
    val latestSealSpec: StateFlow<SealSpec?> = _latestSealSpec.asStateFlow()

    // Active theme (dark?), pushed from HomeScreen composition. The FAB
    // seal ink resolves from the favicon-family palette (iOS
    // SealColorPalette / SealGenerator), which picks a light/dark variant.
    private val _fabSealDark = MutableStateFlow(false)
    fun setFabSealDark(isDark: Boolean) {
        if (_fabSealDark.value == isDark) return
        _fabSealDark.value = isDark
        // Invalidate cache when the theme changes — otherwise the cached
        // light-mode bitmap renders against dark parchment.
        synchronized(sealCache) { sealCache.clear() }
        // Re-render the current spec against the new theme variant.
        _latestSealSpec.value?.let { spec ->
            val ink = SealColorPalette.sealInk(spec, isDark)
            val reinked = spec.copy(ink = ink)
            _latestSealSpec.value = reinked
            val sizePx = (44 * context.resources.displayMetrics.density).toInt()
            sealRenderJob?.cancel()
            sealRenderJob = viewModelScope.launch(defaultDispatcher) {
                try {
                    val bmp = EtegamiSealBitmapRenderer.renderToBitmap(reinked, ink, sizePx, context)
                    val img = bmp.asImageBitmap()
                    synchronized(sealCache) {
                        sealCache[reinked to sizePx] = img
                    }
                    _latestSealBitmap.value = img
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    android.util.Log.w("HomeViewModel", "seal re-render on theme change failed", t)
                    // Cache was cleared above; drop the stale wrong-theme
                    // bitmap so the FAB falls back to the compass rather than
                    // showing a light seal on dark parchment (matches
                    // scheduleSealRender's failure path).
                    _latestSealBitmap.value = null
                }
            }
        }
    }

    // SynchronizedMap because reads happen on the upstream combine's
    // ioDispatcher (via scheduleSealRender) while writes happen on
    // defaultDispatcher (inside the launch). Without the wrapper a
    // cancel-and-restart race against a still-rendering job risked
    // ConcurrentModificationException on rapid emissions.
    private val sealCache: MutableMap<Pair<SealSpec, Int>, ImageBitmap> =
        java.util.Collections.synchronizedMap(
            LinkedHashMap<Pair<SealSpec, Int>, ImageBitmap>(8, 0.75f, true),
        )
    // @Volatile: written from both Main (setFabSealDark) and the IO/Default
    // combine frame (scheduleSealRender); without it a stale read could miss
    // a cancel and let two render coroutines race on _latestSealBitmap.
    @Volatile
    private var sealRenderJob: Job? = null

    // celestialAwarenessEnabled is intentionally NOT in the combine —
    // it doesn't affect any per-walk numbers, and re-running the full
    // per-walk DAO fan-out on every pref toggle was wasteful. The
    // expand-sheet path reads it via `practicePreferences.value`
    // directly at the moment of expansion.
    val journalState: StateFlow<JournalUiState> = combine(
        repository.observeAllWalks(),
        unitsPreferences.distanceUnits,
        cachedShareStore.observeAll(),
        archivedRegistry.archivedRegistry,
    ) { walks, units, shareCache, archivedMap ->
        val finished = walks.filter { it.endTimestamp != null }
        if (finished.isEmpty()) {
            JournalUiState.Empty
        } else {
            buildSnapshots(finished, units, shareCache, archivedMap.keys, clock.now())
        }
    }
        .flowOn(ioDispatcher)
        .stateIn(viewModelScope, SharingStarted.Eagerly, JournalUiState.Loading)

    private suspend fun buildSnapshots(
        walks: List<Walk>,
        units: UnitSystem,
        shareCache: Map<String, CachedShare>,
        archivedUuids: Set<String>,
        nowMs: Long,
    ): JournalUiState.Loaded {
        // IO: per-walk DAO reads on ioDispatcher.
        data class WalkInputs(
            val walk: Walk,
            val distanceM: Double,
            val activeDurSec: Long,
            val talkSec: Long,
            val meditateSec: Long,
        )
        // Parallelize per-walk DAO reads — kaijutsu PR #86 review caught
        // sequential N reads stuttering at 100+ walks. Each walk's
        // locationSamplesFor + walkEventsFor + activitySumsFor are
        // independent, so async them via coroutineScope { }; same
        // ioDispatcher (parent context) but I/O parallelism wins on
        // multi-walk emissions. Stage 13-XZ PromptsCoordinator.buildContext
        // pattern.
        val perWalk = coroutineScope {
            walks.sortedBy { it.startTimestamp }.map { walk ->
                async {
                    val samples = if (walk.distanceMeters == null) {
                        repository.locationSamplesFor(walk.id).map {
                            LocationPoint(
                                timestamp = it.timestamp,
                                latitude = it.latitude,
                                longitude = it.longitude,
                            )
                        }
                    } else {
                        emptyList()
                    }
                    val events = repository.walkEventsFor(walk.id)
                    val (talkSec, meditateSec) = repository.activitySumsFor(walk.id, walk)
                    val distanceM = walk.distanceMeters ?: walkDistanceMeters(samples)
                    val activeDur = WalkMetricsMath.computeActiveDurationSeconds(walk, events)
                    WalkInputs(walk, distanceM, activeDur, talkSec, meditateSec)
                }
            }.awaitAll()
        }

        // Default: CPU-only reduce + format.
        val loaded = withContext(defaultDispatcher) {
            var cumulative = 0.0
            val oldestFirstSnapshots = perWalk.map { input ->
                cumulative += input.distanceM
                WalkSnapshot(
                    id = input.walk.id,
                    uuid = input.walk.uuid,
                    startMs = input.walk.startTimestamp,
                    distanceM = input.distanceM,
                    durationSec = input.activeDurSec.toDouble(),
                    averagePaceSecPerKm = if (input.distanceM > 1.0) {
                        input.activeDurSec.toDouble() / (input.distanceM / 1000.0)
                    } else {
                        0.0
                    },
                    cumulativeDistanceM = cumulative,
                    talkDurationSec = input.talkSec,
                    meditateDurationSec = input.meditateSec,
                    favicon = input.walk.favicon,
                    isShared = shareCache[input.walk.uuid]?.isExpiredAt(nowMs) == false,
                    weatherCondition = input.walk.weatherCondition,
                    isArchived = input.walk.uuid in archivedUuids,
                )
            }
            val newestFirst = oldestFirstSnapshots.reversed()
            val summary = JourneySummary(
                totalDistanceM = cumulative,
                totalTalkSec = newestFirst.sumOf { it.talkDurationSec },
                totalMeditateSec = newestFirst.sumOf { it.meditateDurationSec },
                talkerCount = newestFirst.count { it.hasTalk },
                meditatorCount = newestFirst.count { it.hasMeditate },
                walkCount = newestFirst.size,
                firstWalkStartMs = perWalk.firstOrNull()?.walk?.startTimestamp ?: 0L,
            )
            JournalUiState.Loaded(newestFirst, summary)
        }

        scheduleSealRender(walks, units)
        return loaded
    }

    fun setExpandedSnapshotId(id: Long?) {
        _expandedSnapshotId.value = id
        celestialJob?.cancel()
        if (id == null) {
            _expandedCelestialSnapshot.value = null
            return
        }
        if (!practicePreferences.celestialAwarenessEnabled.value) return
        val snap = (journalState.value as? JournalUiState.Loaded)
            ?.snapshots?.firstOrNull { it.id == id } ?: return
        celestialJob = viewModelScope.launch(defaultDispatcher) {
            try {
                val zodiac = practicePreferences.zodiacSystem.value
                val cs = CelestialSnapshotCalc.snapshot(
                    atEpochMillis = snap.startMs,
                    zoneId = ZoneId.systemDefault(),
                    system = zodiac,
                )
                _expandedCelestialSnapshot.value = cs
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                _expandedCelestialSnapshot.value = null
            }
        }
    }

    private suspend fun scheduleSealRender(
        walks: List<Walk>,
        units: UnitSystem,
    ) {
        // Filter to FINISHED walks before pickup — Walk.toSealSpec
        // requires non-null endTimestamp. An in-progress walk at the
        // top of `walks` would throw, the catch silently swallows,
        // and the FAB stays on the compass fallback. iOS GoshuinFAB
        // also reads `viewModel.walks.first` which is finished-only.
        val newest = walks
            .filter { it.endTimestamp != null }
            .maxByOrNull { it.endTimestamp ?: it.startTimestamp } ?: run {
            _latestSealSpec.value = null
            _latestSealBitmap.value = null
            return
        }
        val distance = newest.distanceMeters ?: 0.0
        val label = WalkFormat.distanceLabel(distance, units)
        // iOS GoshuinFAB renders the seal thumbnail via SealGenerator →
        // the favicon-family palette + turning override (SealColorPalette).
        // The walk-location hemisphere comes from its first route coordinate
        // (iOS `routePoints.first`), not the device; the light/dark variant
        // from the theme pushed in via [setFabSealDark]. Resolve against the
        // placeholder spec (the seal hash ignores ink), then bake the result.
        val firstLat = repository.firstLocationSampleFor(newest.id)?.latitude ?: 0.0
        val spec0 = newest.toSealSpec(
            distanceMeters = distance,
            ink = Color.Transparent,
            displayDistance = label.value,
            unitLabel = label.unit,
            southernHemisphere = Hemisphere.fromLatitude(firstLat) == Hemisphere.Southern,
        )
        val ink = SealColorPalette.sealInk(spec0, _fabSealDark.value)
        val spec = spec0.copy(ink = ink)
        _latestSealSpec.value = spec

        val sizePx = (44 * context.resources.displayMetrics.density).toInt()
        val key = spec to sizePx
        sealCache[key]?.let {
            _latestSealBitmap.value = it
            return
        }
        sealRenderJob?.cancel()
        sealRenderJob = viewModelScope.launch(defaultDispatcher) {
            try {
                val bmp = EtegamiSealBitmapRenderer.renderToBitmap(spec, ink, sizePx, context)
                val img = bmp.asImageBitmap()
                // LRU eviction + insert MUST be atomic — without the
                // synchronized block, two coroutines racing here would
                // see the same `size > 4` and both evict + insert,
                // corrupting access-order semantics.
                synchronized(sealCache) {
                    if (sealCache.size > 4) sealCache.remove(sealCache.keys.first())
                    sealCache[key] = img
                }
                _latestSealBitmap.value = img
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.w("HomeViewModel", "seal bitmap render failed", t)
                _latestSealBitmap.value = null
            }
        }
    }
}
