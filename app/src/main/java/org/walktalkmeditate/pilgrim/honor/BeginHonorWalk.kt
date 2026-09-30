// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.util.Log
import java.io.IOException
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.honor.OwnWalkWayBuilder
import org.walktalkmeditate.pilgrim.walk.HonorSettings
import org.walktalkmeditate.pilgrim.walk.HonorStart
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.WalkStartRequest

/**
 * The walk screen's Start on an honor walk of one of the walker's own
 * walks (plan U17, parity spec correction 1: iOS's overview Begin only
 * navigates, and the walk starts at Start). It mints the new walk's uuid,
 * builds the own-walk Way from the source walk's rows, stages it under
 * that uuid, and starts the walk through the existing chain with the
 * uuid, the Way id, and the preferences frozen for the walk. iOS builds
 * nothing here and stages nothing (`startRecording`, D §3.1); the uuid
 * and the staging are Android's, so `:tracker` can rebuild the session
 * from files and Room alone.
 *
 * A staging write that fails refuses the start. A start that fails after
 * staging (the tracker's 5 s wait, or its refusal) leaves the staging for
 * the launch sweep, since a slow tracker may still take the walk up.
 */
class BeginHonorWalk internal constructor(
    private val repository: WalkRepository,
    private val wayStore: WayStore,
    private val walkController: WalkController,
    private val recordingFiles: VoiceRecordingFileSystem,
    private val releaseFlags: ReleaseFlags,
    private val ioDispatcher: CoroutineDispatcher,
    private val mintWalkUuid: () -> String,
    private val zone: () -> ZoneId,
    private val locale: () -> Locale,
) {
    @Inject
    constructor(
        repository: WalkRepository,
        wayStore: WayStore,
        walkController: WalkController,
        recordingFiles: VoiceRecordingFileSystem,
        releaseFlags: ReleaseFlags,
    ) : this(
        repository = repository,
        wayStore = wayStore,
        walkController = walkController,
        recordingFiles = recordingFiles,
        releaseFlags = releaseFlags,
        ioDispatcher = Dispatchers.IO,
        mintWalkUuid = { UUID.randomUUID().toString() },
        zone = ZoneId::systemDefault,
        locale = Locale::getDefault,
    )

    /** [settings] are the preferences read at Start: see [HonorSettings.atStart]. */
    data class Request(
        val sourceWalkId: Long,
        val intention: String?,
        val settings: HonorSettings,
    )

    sealed interface Result {
        data class Started(val walk: Walk) : Result
        data class Refused(val reason: Refusal) : Result
    }

    enum class Refusal {
        /** The release flag is off. */
        DISABLED,

        /** The walk to honor is gone. */
        SOURCE_MISSING,

        /** Too little route to follow (iOS's nil build: fewer than 2 samples, under 20 m, or no uuid). */
        NOT_WALKABLE,

        STAGING_FAILED,
    }

    /**
     * @throws IllegalStateException when the chain refuses or times out the
     *   start, as [WalkController.startWalk] does for every walk.
     */
    suspend operator fun invoke(request: Request): Result {
        if (!releaseFlags.honor) return Result.Refused(Refusal.DISABLED)
        val source = repository.getWalk(request.sourceWalkId) ?: return Result.Refused(Refusal.SOURCE_MISSING)
        val input = OwnWalkWayBuilder.Input.fromRows(
            walk = source,
            samples = repository.locationSamplesFor(source.id),
            recordings = repository.voiceRecordingsFor(source.id),
            photos = repository.photosFor(source.id),
            waypoints = repository.waypointsFor(source.id),
            events = repository.eventsFor(source.id),
        )
        val way = withContext(ioDispatcher) {
            OwnWalkWayBuilder.make(input, ::recordingIsPresent, zone(), locale())
        } ?: return Result.Refused(Refusal.NOT_WALKABLE)
        val walkUuid = mintWalkUuid()
        val staged = withContext(ioDispatcher) {
            try {
                wayStore.stage(walkUuid, way)
                true
            } catch (e: IOException) {
                Log.w(TAG, "staging for an honor walk failed (${e::class.simpleName})")
                false
            }
        }
        if (!staged) return Result.Refused(Refusal.STAGING_FAILED)
        val walk = walkController.startWalk(
            WalkStartRequest(
                intention = request.intention,
                mode = WalkMode.Honor,
                walkUuid = walkUuid,
                honor = HonorStart(wayId = way.id, settings = request.settings),
            ),
        )
        return Result.Started(walk)
    }

    /** As iOS probes (`OwnWalkWayBuilder.swift:38-45@7c200bf`): the file is there and holds something. */
    private fun recordingIsPresent(recording: VoiceRecording): Boolean =
        recordingFiles.fileSizeBytes(recording.fileRelativePath) > 0

    private companion object {
        const val TAG = "BeginHonorWalk"
    }
}
