// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.walktalkmeditate.pilgrim.BuildConfig
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.dao.WalkPhotoDao
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimManifest
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimWalk
import org.walktalkmeditate.pilgrim.data.pilgrim.builder.PilgrimPackageConverter
import org.walktalkmeditate.pilgrim.data.pilgrim.builder.WalkExportBundle
import org.walktalkmeditate.pilgrim.data.practice.PracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.di.PilgrimJson

sealed interface JourneyState {
    object Loading : JourneyState
    object NoWalks : JourneyState
    data class Error(val message: String) : JourneyState
    data class Ready(val walksJson: String, val manifestJson: String) : JourneyState
}

@HiltViewModel
class JourneyViewerViewModel @Inject constructor(
    private val walkRepository: WalkRepository,
    private val walkPhotoDao: WalkPhotoDao,
    private val practicePreferences: PracticePreferencesRepository,
    private val unitsPreferences: UnitsPreferencesRepository,
    @PilgrimJson private val json: Json,
) : ViewModel() {

    private val _state = MutableStateFlow<JourneyState>(JourneyState.Loading)
    val state: StateFlow<JourneyState> = _state.asStateFlow()

    init {
        loadJourney()
    }

    private fun loadJourney() {
        viewModelScope.launch {
            _state.value = try {
                buildPayload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                JourneyState.Error("Failed to load walks.")
            }
        }
    }

    private suspend fun buildPayload(): JourneyState = withContext(Dispatchers.IO) {
        val walks = walkRepository.allWalks().filter { it.endTimestamp != null }
        if (walks.isEmpty()) return@withContext JourneyState.NoWalks

        val pilgrimWalks = walks.map { walk ->
            val bundle = WalkExportBundle(
                walk = walk,
                routeSamples = walkRepository.locationSamplesFor(walk.id),
                altitudeSamples = walkRepository.altitudeSamplesFor(walk.id),
                walkEvents = walkRepository.eventsFor(walk.id),
                activityIntervals = walkRepository.activityIntervalsFor(walk.id),
                waypoints = walkRepository.waypointsFor(walk.id),
                voiceRecordings = walkRepository.voiceRecordingsFor(walk.id),
                walkPhotos = walkPhotoDao.getForWalk(walk.id),
            )
            PilgrimPackageConverter.convert(bundle, includePhotos = true).walk
        }

        val manifest = PilgrimPackageConverter.buildManifest(
            appVersion = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
            walkCount = pilgrimWalks.size,
            distanceUnits = unitsPreferences.distanceUnits.value,
            celestialAwareness = practicePreferences.celestialAwarenessEnabled.value,
            zodiacSystem = practicePreferences.zodiacSystem.value.storageValue(),
            beginWithIntention = practicePreferences.beginWithIntention.value,
            exportInstant = Instant.now(),
        )

        val walksJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PilgrimWalk.serializer()),
            pilgrimWalks,
        )
        val manifestJson = json.encodeToString(PilgrimManifest.serializer(), manifest)

        JourneyState.Ready(walksJson = walksJson, manifestJson = manifestJson)
    }
}
