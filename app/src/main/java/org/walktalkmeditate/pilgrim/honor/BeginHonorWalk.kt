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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.honor.HonorDao
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.walk.HonorSettings
import org.walktalkmeditate.pilgrim.walk.HonorStart
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.WalkStartRequest

/**
 * The walk screen's Start on an honor walk (plan U17, parity spec
 * correction 1: iOS's overview Begin only navigates, and the walk starts
 * at Start). It mints the new walk's uuid and starts the walk through the
 * existing chain with the uuid, the Way id, and the preferences frozen
 * for the walk. An own walk's Way is built from the source walk's rows
 * and staged under that uuid; a shared Way is read back from the store,
 * where it has been listed since its acceptance, and staged nowhere. iOS
 * builds nothing here and stages nothing (`startRecording`, D §3.1); the
 * uuid and the staging are Android's, so `:tracker` can rebuild the
 * session from files and Room alone.
 *
 * A staging write that fails refuses the start. A start that fails after
 * staging (the tracker's 5 s wait, or its refusal) leaves the staging for
 * the launch sweep, since a slow tracker may still take the walk up.
 */
class BeginHonorWalk internal constructor(
    repository: WalkRepository,
    private val wayStore: WayStore,
    private val walkController: WalkController,
    recordingFiles: VoiceRecordingFileSystem,
    private val releaseFlags: ReleaseFlags,
    private val ioDispatcher: CoroutineDispatcher,
    private val mintWalkUuid: () -> String,
    zone: () -> ZoneId,
    locale: () -> Locale,
    private val begins: HonorBeginsInFlight = HonorBeginsInFlight(),
    /** Returns once the walk's live session row exists; [invoke] bounds the wait. */
    private val awaitSessionRow: suspend (walkId: Long) -> Unit = {},
) {
    private val ownWalkWays = OwnWalkWays(repository, recordingFiles, ioDispatcher, zone, locale)

    @Inject
    constructor(
        repository: WalkRepository,
        wayStore: WayStore,
        walkController: WalkController,
        recordingFiles: VoiceRecordingFileSystem,
        releaseFlags: ReleaseFlags,
        begins: HonorBeginsInFlight,
        honorDao: HonorDao,
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
        begins = begins,
        awaitSessionRow = { walkId -> honorDao.observeSession(walkId).first { it != null } },
    )

    /** [settings] are the preferences read at Start: see [HonorSettings.atStart]. */
    data class Request(
        val way: HonorWayChoice,
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

        /** The walk to honor is gone, or the listed Way is. */
        SOURCE_MISSING,

        /** Too little route to follow (iOS's nil build: fewer than 2 samples, under 20 m, or no uuid). */
        NOT_WALKABLE,

        STAGING_FAILED,
    }

    /**
     * A listed Way is held from Start until its walk's live session row
     * exists, so the expiry sweep can't take it between the read and that
     * row (shared-walk spec correction 9). `:tracker` writes the row just
     * after the walk row the start waits for, in a transaction of its own,
     * so the hold outlasts the start by that much; a row that never comes
     * (its write failed, and the walk went on as a wander) is waited for
     * [SESSION_ROW_WAIT_MILLIS] at most.
     *
     * @throws IllegalStateException when the chain refuses or times out the
     *   start, as [WalkController.startWalk] does for every walk.
     */
    suspend operator fun invoke(request: Request): Result {
        if (!releaseFlags.honor) return Result.Refused(Refusal.DISABLED)
        val choice = request.way as? HonorWayChoice.Stored ?: return begin(request)
        return begins.holding(choice.wayId) {
            begin(request).also { result ->
                if (result is Result.Started) {
                    withTimeoutOrNull(SESSION_ROW_WAIT_MILLIS) { awaitSessionRow(result.walk.id) }
                }
            }
        }
    }

    private suspend fun begin(request: Request): Result {
        val way = when (val choice = request.way) {
            is HonorWayChoice.OwnWalk -> when (val built = ownWalkWays.build(choice.sourceWalkId)) {
                is OwnWalkWays.Built.Ready -> built.way
                OwnWalkWays.Built.SourceMissing -> return Result.Refused(Refusal.SOURCE_MISSING)
                OwnWalkWays.Built.NotWalkable -> return Result.Refused(Refusal.NOT_WALKABLE)
            }
            // Listed since its acceptance, and `:tracker` reads a share from the store: nothing to stage.
            is HonorWayChoice.Stored -> withContext(ioDispatcher) { wayStore.load(choice.wayId) }
                ?: return Result.Refused(Refusal.SOURCE_MISSING)
        }
        val walkUuid = mintWalkUuid()
        if (request.way is HonorWayChoice.OwnWalk && !stage(walkUuid, way)) {
            return Result.Refused(Refusal.STAGING_FAILED)
        }
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

    private suspend fun stage(walkUuid: String, way: Way): Boolean = withContext(ioDispatcher) {
        try {
            wayStore.stage(walkUuid, way)
            true
        } catch (e: IOException) {
            Log.w(TAG, "staging for an honor walk failed (${e::class.simpleName})")
            false
        }
    }

    internal companion object {
        private const val TAG = "BeginHonorWalk"

        /** The start's own wait for `:tracker` (`UiWalkController`'s), spent again at most. */
        const val SESSION_ROW_WAIT_MILLIS = 5_000L
    }
}
