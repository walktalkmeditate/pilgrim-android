// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.domain.honor.OwnWalkWayBuilder
import org.walktalkmeditate.pilgrim.domain.honor.Way

/**
 * Builds the own-walk Way for one of the walker's walks from its Room rows,
 * as iOS's `OwnWalkWayBuilder.make(from:)` does from the walk object. The
 * doors (the picker row, "walk this again"), the overview, and the walk
 * screen's Start all build through here, so they agree on what the Way is.
 */
class OwnWalkWays internal constructor(
    private val repository: WalkRepository,
    private val recordingFiles: VoiceRecordingFileSystem,
    private val ioDispatcher: CoroutineDispatcher,
    private val zone: () -> ZoneId,
    private val locale: () -> Locale,
) {
    @Inject
    constructor(
        repository: WalkRepository,
        recordingFiles: VoiceRecordingFileSystem,
    ) : this(
        repository = repository,
        recordingFiles = recordingFiles,
        ioDispatcher = Dispatchers.IO,
        zone = ZoneId::systemDefault,
        locale = Locale::getDefault,
    )

    sealed interface Built {
        /** [recordings] are the source walk's rows, so a preview can play a voice by its row. */
        data class Ready(val source: Walk, val way: Way, val recordings: List<VoiceRecording>) : Built

        data object SourceMissing : Built

        /** iOS's nil build: fewer than 2 route samples, a route under 20 m, or no uuid. */
        data object NotWalkable : Built
    }

    suspend fun build(sourceWalkId: Long): Built {
        val source = repository.getWalk(sourceWalkId) ?: return Built.SourceMissing
        val recordings = repository.voiceRecordingsFor(source.id)
        val input = OwnWalkWayBuilder.Input.fromRows(
            walk = source,
            samples = repository.locationSamplesFor(source.id),
            recordings = recordings,
            photos = repository.photosFor(source.id),
            waypoints = repository.waypointsFor(source.id),
            events = repository.eventsFor(source.id),
        )
        // The builder probes every recording file, as iOS stats them.
        val way = withContext(ioDispatcher) {
            OwnWalkWayBuilder.make(input, ::isPresent, zone(), locale())
        } ?: return Built.NotWalkable
        return Built.Ready(source, way, recordings)
    }

    /** As iOS probes (`OwnWalkWayBuilder.swift:38-45@7c200bf`): the file is there and holds something. */
    fun isPresent(recording: VoiceRecording): Boolean =
        recordingFiles.fileSizeBytes(recording.fileRelativePath) > 0
}
