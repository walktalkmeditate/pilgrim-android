// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import android.content.res.Resources
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.honor.WayDateSerializer
import org.walktalkmeditate.pilgrim.domain.honor.WayJson
import org.walktalkmeditate.pilgrim.domain.honor.WayStage
import org.walktalkmeditate.pilgrim.domain.honor.countString
import org.walktalkmeditate.pilgrim.domain.honor.digits
import org.walktalkmeditate.pilgrim.domain.honor.swiftCompareTo

/**
 * What the engine had to say about a stage when the walk ended (iOS
 * `HonorStageOutcome`, `PilgrimageLedger.swift:3-8@7c200bf`): where along
 * the stage the walker stood, and whether they arrived. A walk the engine
 * never anchored on its Way has none.
 */
data class HonorStageOutcome(val progressFrac: Double, val arrived: Boolean)

/**
 * A stage as its route's ledger knows it: the route and index it is filed
 * under, and the name and kilometres that are its identity across an
 * Update. iOS reads these from the Way's stage block; Android's finalize
 * reads the copy its session row took at Start (P2 A-7), so a record never
 * needs the stage's `way.json`.
 */
data class PilgrimageStageIdentity(val routeId: String, val index: Int, val name: String, val distanceKm: Double) {
    constructor(stage: WayStage) : this(stage.routeId, stage.index, stage.name, stage.distanceKm)
}

/**
 * iOS `PilgrimageLedger` (`PilgrimageLedger.swift:10-125@7c200bf`, P2 §8):
 * the per-route record of stages walked. It outlives the package: Replace
 * and Remove take the stages, never this file, so a route that comes back
 * finds its record. [stages] is keyed by stage index as a string, the
 * file's shape. [carriedKm] holds the kilometres of entries a redraw
 * dropped, so the total never shrinks under the walker, and
 * [redrawNoticePending] is set by [reconciled] for the route page to say
 * so once.
 *
 * The file is `way.json`'s format ([WayJson]): keys sorted at every level,
 * nulls omitted, whole-second UTC dates. As Swift's synthesized `Codable`
 * does, a missing key that isn't optional fails the whole ledger.
 */
@Serializable
data class PilgrimageLedger(
    val routeId: String,
    val stages: Map<String, Entry>,
    val carriedKm: Double? = null,
    val redrawNoticePending: Boolean? = null,
) {

    constructor(routeId: String) : this(routeId, emptyMap())

    /** [name] and [distanceKm] are the stage's identity across an Update ([reconciled]). */
    @Serializable
    data class Entry(
        val name: String,
        val distanceKm: Double,
        @Serializable(with = WayDateSerializer::class) val walkedAt: Instant,
        val kmWalked: Double,
        val completed: Boolean,
        val stoppedAtFrac: Double? = null,
    )

    /** The stage to walk next, and where it stopped: null for a stage never begun. */
    data class Next(val index: Int, val resumeFrac: Double?)

    /** Finite figures only: a ledger read off disk is as untrusted as any other file. */
    val totalKmWalked: Double
        get() = stages.values.map { it.kmWalked }.filter { it.isFinite() }.sum() +
            (carriedKm?.takeIf { it.isFinite() } ?: 0.0)

    val completedCount: Int get() = stages.values.count { it.completed }

    /**
     * iOS `record` (`PilgrimageLedger.swift:59-71@7c200bf`), the keep-best
     * merge (P2 §8, C-15). The fraction is made finite and clamped to
     * [0, 1], and the distance made finite and non-negative. Completion is
     * sticky. A completed stage is credited its whole [distanceKm], a
     * partial one `distanceKm × frac`, a position rather than a distance
     * walked (pilgrim-ios #120 item 1, matched as shipped), and `kmWalked`
     * keeps the larger. `name`, `distanceKm`, `walkedAt` and
     * `stoppedAtFrac` are the last record's.
     *
     * Android adds the order-safe branch (P2 A-5, C-8): a record dated
     * before the entry's `walkedAt`, compared in whole seconds as the file
     * stores them, is an older walk the launch retry replayed after a newer
     * one. It applies only the sticky completion and the kilometre max, and
     * keeps the newer walk's `walkedAt`, `stoppedAtFrac`, `name` and
     * `distanceKm`. iOS records walks in the order they end and has none.
     */
    fun recorded(
        stageIndex: Int,
        name: String,
        distanceKm: Double,
        outcome: HonorStageOutcome,
        at: Instant,
    ): PilgrimageLedger {
        val frac = swiftMin(swiftMax(if (outcome.progressFrac.isFinite()) outcome.progressFrac else 0.0, 0.0), 1.0)
        val km = if (distanceKm.isFinite()) swiftMax(0.0, distanceKm) else 0.0
        val key = stageIndex.toString()
        val existing = stages[key]
        val entry = if (existing != null && at.epochSecond < existing.walkedAt.epochSecond) {
            val completed = existing.completed || outcome.arrived
            existing.copy(
                kmWalked = swiftMax(if (completed) km else km * frac, existing.kmWalked),
                completed = completed,
                stoppedAtFrac = if (completed) null else existing.stoppedAtFrac,
            )
        } else {
            val completed = outcome.arrived || existing?.completed == true
            Entry(
                name = name,
                distanceKm = km,
                walkedAt = at,
                kmWalked = swiftMax(if (completed) km else km * frac, existing?.kmWalked ?: 0.0),
                completed = completed,
                stoppedAtFrac = if (completed) null else frac,
            )
        }
        return copy(stages = stages + (key to entry))
    }

    /**
     * The first stage in `0 until stageCount` without a completed entry,
     * with where it stopped (`PilgrimageLedger.swift:78-84@7c200bf`). Null
     * when every stage is walked, or there are none. The route page's
     * next-row words are its own (U37, P2 C-1).
     */
    fun next(stageCount: Int): Next? {
        if (stageCount <= 0) return null
        for (index in 0 until stageCount) {
            val entry = stages[index.toString()]
            if (entry?.completed != true) return Next(index, entry?.stoppedAtFrac)
        }
        return null
    }

    /**
     * iOS `reconciled(against:)` (`PilgrimageLedger.swift:98-124@7c200bf`):
     * after an Update, an entry survives only where the new package has a
     * stage at its index with the same name and a `distanceKm` within
     * [IDENTITY_TOLERANCE_RATIO] of the old one, inclusive and relative to
     * the old distance. A kept entry keeps its old name and distance. What
     * is dropped leaves its kilometres in [carriedKm] and raises the redraw
     * notice; a drop with no kilometres changes neither. The name match is
     * Swift's `==`, canonical equivalence, so both names are compared in
     * NFC (P1 A10).
     */
    fun reconciled(newStages: List<PilgrimageRouteStage>): PilgrimageLedger {
        val byIndex = newStages.distinctBy { it.index }.associateBy { it.index }
        val kept = LinkedHashMap<String, Entry>()
        var dropped = 0.0
        for ((key, entry) in stages) {
            val fresh = swiftInt(key)?.let(byIndex::get)
            val holds = fresh != null && fresh.name.swiftCompareTo(entry.name) == 0 &&
                entry.distanceKm > 0 && fresh.distanceKm.isFinite() &&
                abs(fresh.distanceKm - entry.distanceKm) / entry.distanceKm <= IDENTITY_TOLERANCE_RATIO
            if (holds) {
                kept[key] = entry
            } else {
                dropped += if (entry.kmWalked.isFinite()) entry.kmWalked else 0.0
            }
        }
        return if (dropped > 0) {
            copy(stages = kept, carriedKm = (carriedKm ?: 0.0) + dropped, redrawNoticePending = true)
        } else {
            copy(stages = kept)
        }
    }

    companion object {

        const val IDENTITY_TOLERANCE_RATIO = 0.05

        /**
         * iOS `progressLine` (`PilgrimageLedger.swift:86-96@7c200bf`):
         * "stage 5 of 33 · 112 km walked", "you have walked the whole way ·
         * 764 km", or, with nothing recorded and nothing carried, "33
         * stages". [distance] spells metres in the walker's unit: the stage
         * surfaces pass `StageFormat.distance` (owner decision 7).
         */
        fun progressLine(
            resources: Resources,
            ledger: PilgrimageLedger?,
            stageCount: Int,
            distance: (meters: Double) -> String,
        ): String {
            val walking = ledger?.takeIf { it.stages.isNotEmpty() || (it.carriedKm ?: 0.0) > 0 }
                ?: return stageCountLine(resources, stageCount)
            val walked = distance(walking.totalKmWalked * 1000)
            val next = walking.next(stageCount)
                ?: return resources.getString(R.string.pilgrimage_progress_whole_way, walked)
            return resources.getString(
                R.string.pilgrimage_progress_stage_of,
                digits(next.index + 1),
                digits(stageCount),
                walked,
            )
        }

        /** "1 stage" or "33 stages": the singular only at 1, as iOS picks it, whatever the phone's plural rules. */
        fun stageCountLine(resources: Resources, stageCount: Int): String =
            resources.countString(stageCount, R.string.pilgrimage_stage_count_one, R.string.pilgrimage_stage_count)
    }
}

/**
 * iOS `PilgrimageLedgerWriter` (`PilgrimageLedger.swift:127-138@7c200bf`):
 * the one place that decides whether a walk earned an entry. None without
 * an outcome: the engine never anchored on the Way, so the walker was
 * still approaching, and an approach is not a stage walked.
 */
object PilgrimageLedgerWriter {

    data class Written(val index: Int, val name: String, val distanceKm: Double, val outcome: HonorStageOutcome)

    fun entry(stage: PilgrimageStageIdentity, outcome: HonorStageOutcome?): Written? =
        outcome?.let { Written(stage.index, stage.name, stage.distanceKm, it) }
}

/**
 * iOS `PilgrimageLedgerStore` (`PilgrimageLedger.swift:140-187@7c200bf`):
 * each route's [WayStore.ledgerFile], in `noBackupFilesDir` with the rest
 * of the Ways tree, so it never travels to a new phone (P2 §10 item 1).
 *
 * iOS writes on main only and needs no lock. Here `:tracker`'s finalize,
 * the UI's launch retry and recovery, the `.pilgrim` importer, Update's
 * reconcile and the route page's notice clear can all write, from two
 * processes and several threads (P2 A-3). So every write reads, changes
 * and writes under two locks: this process's lock for the ledger, then a
 * `FileChannel` lock on [WayStore.ledgerLockFile] for the other process,
 * released in reverse order. A JVM file lock belongs to the whole process,
 * and a second thread asking for it throws
 * [java.nio.channels.OverlappingFileLockException] instead of waiting, so
 * the first layer is what lets threads queue for the second. Writes land
 * through the store's atomic write, so a plain [load] needs no lock.
 *
 * Blocking file I/O on the caller's thread: callers hop to an IO
 * dispatcher. Nothing here reads the clock (records carry the walk's end
 * time, P2 A-4), and nothing is logged.
 */
@Singleton
class PilgrimageLedgerStore @Inject constructor(private val wayStore: WayStore) {

    /**
     * Null when there is no ledger, the route id is refused, or the file
     * doesn't read or decode, as iOS's `try?` reads it. The next record then
     * starts afresh over it (pilgrim-ios #120 item 5, matched as shipped).
     */
    fun load(routeId: String): PilgrimageLedger? = wayStore.ledgerFile(routeId)?.let(::read)

    /**
     * Writes [ledger] whole, into its own route's folder, under both locks.
     * Nothing for a route id the slug rule refuses: a ledger that can never
     * be written counts as written, as an invalid link does (P2 C-7).
     *
     * @throws IOException when the folder, the lock or the write fails.
     *   iOS swallows it; the finalize must see it to retry (P2 C-7).
     */
    fun save(ledger: PilgrimageLedger) {
        update(ledger.routeId) { ledger }
    }

    /**
     * iOS `record(stage:outcome:at:)`: the one place a stage walk reaches
     * its route's ledger, from a clean finish and a recovery alike. Nothing
     * at all, not even an empty file, without an [outcome]. [at] is the
     * walk's end time, so a retry in either process writes the same bytes.
     *
     * @throws IOException as [save] does.
     */
    fun record(stage: PilgrimageStageIdentity, outcome: HonorStageOutcome?, at: Instant) {
        val written = PilgrimageLedgerWriter.entry(stage, outcome) ?: return
        update(stage.routeId) { current ->
            (current ?: PilgrimageLedger(stage.routeId))
                .recorded(written.index, written.name, written.distanceKm, written.outcome, at)
        }
    }

    /**
     * Update's step (`PilgrimagePackageManager.swift:269-271@7c200bf`): a
     * ledger on disk is reconciled against [newStages] and written back;
     * with none, nothing is written.
     *
     * @throws IOException as [save] does.
     */
    fun reconcile(routeId: String, newStages: List<PilgrimageRouteStage>) {
        if (!hasLedger(routeId)) return
        update(routeId) { current -> current?.reconciled(newStages) }
    }

    /**
     * iOS `clearRedrawNotice(routeId:)`: written only while a notice is pending.
     *
     * @throws IOException as [save] does.
     */
    fun clearRedrawNotice(routeId: String) {
        if (!hasLedger(routeId)) return
        update(routeId) { current ->
            current?.takeIf { it.redrawNoticePending == true }?.copy(redrawNoticePending = null)
        }
    }

    /**
     * Every write's read-modify-write, under both locks, released in
     * reverse. [change] gets the ledger as it stands (null when absent or
     * unreadable) and returns what to write, or null for nothing. It must
     * not call back into the store: the file lock is the whole process's,
     * so a nested write would throw instead of waiting.
     *
     * @throws IOException when the folder, the lock or the write fails.
     */
    internal fun update(routeId: String, change: (PilgrimageLedger?) -> PilgrimageLedger?) {
        val file = wayStore.ledgerFile(routeId) ?: return
        val lockFile = wayStore.ledgerLockFile(routeId) ?: return
        processLock(lockFile).withLock {
            Files.createDirectories(lockFile.parentFile!!.toPath())
            FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val changed = change(read(file)) ?: return
                    val text = WayJson.encode(PilgrimageLedger.serializer(), changed)
                    wayStore.writePilgrimageFile(file, text.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    /**
     * Whether a ledger file exists, checked before a change that only ever
     * rewrites one, so it creates no route folder or lock file where iOS
     * would write nothing (its callers check first).
     */
    private fun hasLedger(routeId: String): Boolean = wayStore.ledgerFile(routeId)?.isFile == true

    private fun read(file: File): PilgrimageLedger? {
        val text = try {
            if (file.isFile) file.readText() else null
        } catch (e: IOException) {
            null
        } ?: return null
        return try {
            WayJson.decode(PilgrimageLedger.serializer(), text)
        } catch (e: SerializationException) {
            null
        }
    }

    private companion object {

        /** One per ledger file for the whole process, whichever store instance asks. */
        private val processLocks = ConcurrentHashMap<String, ReentrantLock>()

        fun processLock(lockFile: File): ReentrantLock =
            processLocks.computeIfAbsent(lockFile.absolutePath) { ReentrantLock() }
    }
}

/**
 * Swift's `max(x, y)`, `y >= x ? y : x`: a NaN on the right gives way,
 * where Kotlin's `maxOf` returns it (P2 §8).
 */
private fun swiftMax(x: Double, y: Double): Double = if (y >= x) y else x

/** Swift's `min(x, y)`, `y < x ? y : x`. */
private fun swiftMin(x: Double, y: Double): Double = if (y < x) y else x

/** Swift's `Int(String)`: an optional sign and ASCII digits, where Kotlin's parse takes any Unicode digit. */
private fun swiftInt(text: String): Int? = if (SWIFT_INT.matches(text)) text.toIntOrNull() else null

private val SWIFT_INT = Regex("[+-]?[0-9]+")
