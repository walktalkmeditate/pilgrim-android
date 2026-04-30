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
import org.walktalkmeditate.pilgrim.data.pilgrim.builder.AndroidPilgrimPhotoEmbedder
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
    private val photoEmbedder: AndroidPilgrimPhotoEmbedder,
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

        // iOS-parity gate: photos in the journey viewer respect the
        // user's Photo Reliquary preference. When disabled, photos
        // are excluded from both the converter output and the
        // enrichment loop. JourneyViewerView.swift:68-82.
        val reliquaryEnabled = practicePreferences.walkReliquaryEnabled.value

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
            PilgrimPackageConverter.convert(bundle, includePhotos = reliquaryEnabled).walk
        }

        val enrichedWalks = if (reliquaryEnabled) {
            pilgrimWalks.map { walk ->
                val photos = walk.photos ?: return@map walk
                val enrichedPhotos = photos.map { photo ->
                    // Intentional divergence from iOS: when encodeAsDataUrl returns
                    // null (URI gone, permission revoked, encode failed), iOS drops
                    // the photo via compactMap so neither marker nor thumbnail
                    // appears. Android keeps the photo with inlineUrl=null — the JS
                    // viewer renders it as a marker without thumbnail, preserving
                    // the "this walk had a photo here" signal even when the bytes
                    // are gone.
                    val dataUrl = try {
                        photoEmbedder.encodeAsDataUrl(photo.localIdentifier)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    }
                    if (dataUrl != null) photo.copy(inlineUrl = dataUrl) else photo
                }
                walk.copy(photos = enrichedPhotos)
            }
        } else {
            pilgrimWalks
        }

        val manifest = PilgrimPackageConverter.buildManifest(
            appVersion = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
            walkCount = enrichedWalks.size,
            distanceUnits = unitsPreferences.distanceUnits.value,
            celestialAwareness = practicePreferences.celestialAwarenessEnabled.value,
            zodiacSystem = practicePreferences.zodiacSystem.value.storageValue(),
            beginWithIntention = practicePreferences.beginWithIntention.value,
            exportInstant = Instant.now(),
        )

        val walksJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PilgrimWalk.serializer()),
            enrichedWalks,
        )
        val manifestJson = json.encodeToString(PilgrimManifest.serializer(), manifest)

        JourneyState.Ready(walksJson = walksJson, manifestJson = manifestJson)
    }
}
