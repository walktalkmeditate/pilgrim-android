// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.honor.WayMediaDownloadScheduler
import org.walktalkmeditate.pilgrim.data.honor.WayMediaReport
import org.walktalkmeditate.pilgrim.data.honor.WayMediaRules
import org.walktalkmeditate.pilgrim.data.honor.WayMediaWork
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WaySource

/** iOS `WayMediaDownloader`'s four published sets (`WayMediaDownloader.swift:13-17@7c200bf`), by Way id. */
data class WayGathers(
    val progress: Map<String, Double> = emptyMap(),
    val failures: Map<String, List<String>> = emptyMap(),
    val active: Set<String> = emptySet(),
    val diskFull: Set<String> = emptySet(),
) {
    /** iOS `cancel(wayId:)`'s clearing of every per-Way entry. */
    fun without(wayId: String) = WayGathers(progress - wayId, failures - wayId, active - wayId, diskFull - wayId)

    fun state(wayId: String): HonorImportState = HonorImportReducer.state(wayId, progress, active, failures, diskFull)
}

/**
 * iOS `WayMediaDownloader` in the UI process (shared-walk spec S3 §6,
 * §11): the overview's [download] and "try again" ([retry]), every delete
 * and sweep's [cancel], and the sets the overview's state reduces from.
 * The transfers themselves are [WayMediaDownloadScheduler]'s work, which
 * outlives this process as iOS's background session does; the sets are
 * rebuilt at each gather, from the files on disk and the work's reports,
 * and are never persisted (S3 open question 5).
 */
@Singleton
class WayMediaDownloader internal constructor(
    private val store: WayStore,
    private val scheduler: WayMediaDownloadScheduler,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(store: WayStore, scheduler: WayMediaDownloadScheduler) :
        this(store, scheduler, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), Dispatchers.IO)

    private val _gathers = MutableStateFlow(WayGathers())
    val gathers: StateFlow<WayGathers> = _gathers.asStateFlow()

    private val rounds = ConcurrentHashMap<String, Job>()
    private val starting = Mutex()

    /**
     * iOS `download(_:)`: a share's missing files are gathered, its
     * ceilings refused at once, and its progress seeded from the files
     * already on disk, so a reopened overview resumes at its old
     * percentage. A Way still gathering is left as it is, its disk-full
     * mark included (S3 §16). Settled before it returns, so the
     * overview's first frame already shows it.
     */
    suspend fun download(way: Way) = start(way, replace = false)

    /** iOS `retry(_:)`: unconditional, even while the Way still gathers; every file gets a fresh retry. */
    suspend fun retry(way: Way) {
        cancel(way.id)
        start(way, replace = true)
    }

    /**
     * iOS `cancel(wayId:)`: every per-Way entry cleared at once, and the
     * transfers stopped. Called before every delete and after every sweep.
     */
    fun cancel(wayId: String) {
        rounds.remove(wayId)?.cancel()
        _gathers.update { it.without(wayId) }
        scheduler.cancel(wayId)
    }

    private suspend fun start(way: Way, replace: Boolean) = starting.withLock {
        if (way.source !is WaySource.Share) return@withLock
        if (way.id in _gathers.value.active) return@withLock
        val (accepted, refused) = WayMediaRules.withinCeilings(WayMediaRules.mediaFiles(way))
        val missing = withContext(ioDispatcher) {
            accepted.filterNot { store.mediaFile(way.id, it)?.exists() == true }
        }
        _gathers.update { sets ->
            val failures = if (refused.isEmpty()) sets.failures - way.id else sets.failures + (way.id to refused)
            val seeded = if (missing.isEmpty()) 1.0 else 1.0 - missing.size.toDouble() / accepted.size
            sets.copy(
                progress = sets.progress + (way.id to seeded),
                failures = failures,
                active = if (missing.isEmpty()) sets.active else sets.active + way.id,
                diskFull = sets.diskFull - way.id,
            )
        }
        if (missing.isEmpty()) return@withLock
        val round = Round(way.id, missing + refused)
        // Registered before it runs, so its first report already counts as the current round's.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            scheduler.gather(way.id, replace)
                .takeWhile { round.isCurrent }
                .collect { work -> if (work != null) round.apply(work) }
        }
        round.job = job
        rounds[way.id] = job
        job.start()
    }

    /** One gather round: [lost] is what a failed run leaves missing. */
    private inner class Round(private val wayId: String, private val lost: List<String>) {
        var job: Job? = null

        /** Until it finishes, or a cancel or a newer round replaces it. */
        val isCurrent: Boolean get() = job != null && rounds[wayId] === job

        fun apply(work: WayMediaWork) {
            when (work.state) {
                WayMediaWork.State.WAITING, WayMediaWork.State.RUNNING -> work.report?.let(::report)
                WayMediaWork.State.SUCCEEDED -> {
                    work.report?.let(::report)
                    finish()
                }
                WayMediaWork.State.FAILED -> {
                    _gathers.update { it.copy(progress = it.progress + (wayId to 1.0), failures = it.failures + (wayId to lost)) }
                    finish()
                }
                WayMediaWork.State.CANCELLED -> {
                    _gathers.update { it.without(wayId) }
                    finish()
                }
            }
        }

        private fun report(report: WayMediaReport) {
            _gathers.update { sets ->
                sets.copy(
                    progress = sets.progress + (wayId to report.progress),
                    failures = if (report.failures.isEmpty()) sets.failures - wayId else sets.failures + (wayId to report.failures),
                    diskFull = if (report.diskFull) sets.diskFull + wayId else sets.diskFull,
                )
            }
        }

        private fun finish() {
            rounds.remove(wayId, job)
            _gathers.update { it.copy(active = it.active - wayId) }
        }
    }
}
