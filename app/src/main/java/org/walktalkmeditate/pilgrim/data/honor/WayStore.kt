// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayDateSerializer
import org.walktalkmeditate.pilgrim.domain.honor.WayJson

/**
 * A walk's tie to the Way it honored (iOS `WayLink`, `WayStore.swift:3-9@7c200bf`).
 * The two seconds are the engine's arrival numbers, null when the walk
 * ended before the end of the Way.
 */
@Serializable
data class WayLink(
    val wayId: String,
    val theirSeconds: Double? = null,
    val yourSeconds: Double? = null,
)

/** The engine's arrival numbers: the companion's timeline and the walker's own, in seconds. */
data class WayArrival(val theirSeconds: Double, val yourSeconds: Double)

/** A Way's `way.json` as a reader's cache sees it: the file changed when either differs. */
data class WayFileStamp(val lastModifiedMillis: Long, val length: Long)

/** A staged Way (an own walk's, or a stage's), by the uuid of the walk that is honoring it. */
data class StagedWay(val walkUuid: String, val stagedAtMillis: Long)

/**
 * A staging folder as the launch sweep sees it: [complete] when its
 * `way.json` landed, else what a write killed midway left behind.
 * [lastTouchedMillis] is the newest modification inside it.
 */
data class StagingFolder(val walkUuid: String, val lastTouchedMillis: Long, val complete: Boolean)

/**
 * The Ways store, a port of iOS `WayStore.swift@7c200bf` rooted in
 * `noBackupFilesDir`, which the platform never backs up or transfers.
 *
 * ```
 * <base>/<way id>/way.json        the Way
 * <base>/<way id>/accepted.json   {"acceptedAt": …}, written once
 * <base>/<way id>/replies.json    {"<n>": "<recording path>"}
 * <base>/<way id>/media/          shared walks only
 * <base>/<way id>/.media-<path>.download.tmp   a media file still gathering (Android)
 * <base>/links/<walk uuid>.json   one WayLink per walk (Android)
 * <base>/staging/<walk uuid>/way.json   an own-walk or stage Way while it is walked (Android)
 * <base>/pilgrimage/replacing.txt                a Replace's swap marker
 * <base>/pilgrimage/<route>/route.json           a downloaded route's package
 * <base>/pilgrimage/<route>/release.txt          the release it is pinned to
 * <base>/pilgrimage/<route>/ledger.json          the stages walked, kept through Remove
 * <base>/pilgrimage/<route>/ledger.json.lock     the ledger's file lock (Android)
 * ```
 *
 * iOS keeps every link in one `index.json`, which one bad read followed by
 * a write empties (pilgrim-ios #107); here each walk's link is its own
 * file, so no write touches another walk's link. `links`, `staging` and
 * `pilgrimage` are not valid Way ids, so [list] steps over them as iOS's
 * steps over `pilgrimage` and `index.json`.
 *
 * Plain blocking file I/O on the caller's thread, as iOS's store is:
 * callers hop to an IO dispatcher. Building the store touches no file
 * (the base is resolved on first use), so a Hilt singleton costs nothing
 * on the thread that happens to construct it. Both processes use the
 * store, so every write lands through a unique temp file, fsync, an
 * atomic rename, and an fsync of the folder that holds it (so the rename
 * itself survives a power loss: the Honor marker written after a link
 * promises the link is there), and readers only ever open the final
 * names. Nothing here logs: ids and titles are shared content.
 *
 * A Way's media is the one thing two threads of one process change at
 * once: the media worker gathers into a Way while a Settings delete or a
 * sweep removes it, all in the UI process (iOS runs both on main). So a
 * partial's opening ([holdMediaPartial]), its landing ([landMedia]), and
 * every removal of a Way or its media take one lock, and the Way must
 * still load inside it: nothing lands in, and no partial is made in, a
 * folder whose `way.json` has gone.
 */
class WayStore(
    resolveBaseDirectory: () -> File,
    private val clock: Clock = Clock.System,
    private val syncDirectory: (File) -> Boolean = ::fsyncDirectoryBestEffort,
    private val allocatedBytes: (File) -> Long = ::allocatedBytesOf,
    private val decodeWay: (String) -> Way = { WayJson.decode(it) },
) {

    val baseDirectory: File by lazy(resolveBaseDirectory)

    private val deletionCount = MutableStateFlow(0L)

    private val mediaLock = Any()

    /** Open partials by path, with how many fetches hold each: the temp sweep leaves them be. */
    private val heldPartials = HashMap<String, Int>()

    /**
     * How many Ways this process has [delete]d, or swept or retired whole.
     * Files raise no invalidation, so a surface holding a Way's line re-reads on it.
     */
    val deletions: StateFlow<Long> = deletionCount.asStateFlow()

    fun save(way: Way) {
        saveEncoded(way.id, WayJson.encode(way))
    }

    /**
     * [save] for a Way already in the store's encoding: [wayJson] is
     * [WayJson.encode] of the Way [id] names, written as it stands. The
     * package commit's write, whose temp set holds every stage that way.
     */
    fun saveEncoded(id: String, wayJson: String) {
        val dir = directory(id)
        ensureDirectory(dir)
        writeAtomically(File(dir, WAY_FILE), wayJson)
        val accepted = File(dir, ACCEPTED_FILE)
        if (!accepted.exists()) {
            val now = Instant.ofEpochMilli(clock.now())
            writeAtomically(accepted, WayJson.encode(Accepted.serializer(), Accepted(now)))
        }
    }

    fun load(id: String): Way? {
        if (!isValidId(id)) return null
        return readWay(File(directory(id), WAY_FILE))
    }

    /** Null when the Way has no `way.json`. */
    fun wayFileStamp(id: String): WayFileStamp? {
        if (!isValidId(id)) return null
        val file = File(directory(id), WAY_FILE)
        return if (file.isFile) WayFileStamp(file.lastModified(), file.length()) else null
    }

    fun acceptedAt(id: String): Instant? {
        if (!isValidId(id)) return null
        val text = readText(File(directory(id), ACCEPTED_FILE)) ?: return null
        return decodeOrNull { WayJson.decode(Accepted.serializer(), text).acceptedAt }
    }

    /**
     * Every readable Way, newest acceptance first; one never accepted sorts
     * last. A stage Way is stepped over before its `way.json` is read (P2
     * A-11): iOS decodes every stage here, up to 50 MB of JSON on each
     * opening of the Ways sheet, and every caller then drops it again.
     * [stageWayIds] is the stages' own listing.
     */
    fun list(): List<Way> {
        val names = baseDirectory.list().orEmpty()
        return names.filter { isValidId(it) && !isStageWayId(it) }.mapNotNull(::load)
            .sortedByDescending { acceptedAt(it.id) ?: Instant.MIN }
    }

    /**
     * Every stage Way with a `way.json` on disk, of every route, read
     * without decoding: what Settings → Ways' footer counts (P2 §11, C-14).
     * That includes the walked stages a Replace or Remove kept from routes
     * no longer installed, as iOS's `all.count - ways.count` counts them
     * (pilgrim-ios #120 item 6, matched as shipped). iOS counts only the
     * stages that decode, so a stage whose `way.json` doesn't is counted
     * here alone (A-11). Unsorted.
     */
    fun stageWayIds(): List<String> =
        baseDirectory.list().orEmpty().filter { id ->
            isValidId(id) && isStageWayId(id) && File(File(baseDirectory, id), WAY_FILE).isFile
        }

    /** Removes the Way's folder and every walk's link to it (`WayStore.swift:123-129@7c200bf`). */
    fun delete(id: String) {
        if (!isValidId(id)) return
        synchronized(mediaLock) { directory(id).deleteRecursively() }
        linkFiles().forEach { file ->
            if (readLink(file)?.wayId == id) file.delete()
        }
        deletionCount.update { it + 1 }
    }

    /**
     * No validity check beyond [directory]'s: as on iOS, only callers holding
     * an id loaded from disk or a stored link reach the media helpers.
     */
    fun mediaDirectory(id: String): File = File(directory(id), MEDIA_DIRECTORY)

    /**
     * Null for a [relative] path that would leave the Way's `media/` folder.
     * iOS's importer only writes `audio/<n>.m4a` and `photos/<n>.jpg`
     * (`WayImporter.swift:151-156@7c200bf`), so this refuses nothing valid.
     */
    fun mediaFile(id: String, relative: String): File? {
        val root = mediaDirectory(id)
        val file = File(root, relative)
        return file.takeIf { it.canonicalPath.startsWith(root.canonicalPath + File.separator) }
    }

    /**
     * Any entry in `media/`, folders included, without recursing: iOS's
     * test, which reads a share never gathered as returned to the trail
     * (pilgrim-ios #109, matched as shipped).
     */
    fun hasMedia(id: String): Boolean {
        if (!isValidId(id)) return false
        return !mediaDirectory(id).list().isNullOrEmpty()
    }

    /** `media/`, and the files still gathering into it: what an expired, walked share loses. */
    fun deleteMedia(id: String) {
        if (!isValidId(id)) return
        synchronized(mediaLock) {
            mediaDirectory(id).deleteRecursively()
            directory(id).listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith(PARTIAL_PREFIX) && it.name.endsWith(PARTIAL_SUFFIX) }
                .forEach { it.delete() }
        }
    }

    /**
     * Where [relative] gathers before it lands: a temp in the Way's own
     * folder, outside `media/` so [hasMedia] never counts it, named for its
     * path so a later run resumes it, and swept like every other temp by
     * [sweepTempFiles] once no fetch holds it. Null for a path [mediaFile] refuses.
     */
    fun mediaPartialFile(id: String, relative: String): File? {
        mediaFile(id, relative) ?: return null
        return File(directory(id), PARTIAL_PREFIX + relative.replace('/', '-') + PARTIAL_SUFFIX)
    }

    /**
     * Opens [partial] (a [mediaPartialFile]) for one fetch through [open],
     * only while the Way still loads, under the media lock: a Way deleted
     * or swept whole gets no partial made in its folder. Until
     * [releaseMediaPartial], [sweepTempFiles] leaves it however old.
     *
     * @return null when the Way is gone.
     * @throws IOException when [open] fails, a full disk among the causes.
     */
    fun <T> holdMediaPartial(id: String, partial: File, open: (File) -> T): T? = synchronized(mediaLock) {
        if (load(id) == null) return null
        open(partial).also { heldPartials.merge(partial.path, 1) { held, more -> held + more } }
    }

    /** Ends [holdMediaPartial]'s hold; a partial its fetch left empty goes with it. */
    fun releaseMediaPartial(partial: File) {
        synchronized(mediaLock) {
            val left = (heldPartials[partial.path] ?: 1) - 1
            if (left > 0) {
                heldPartials[partial.path] = left
                return
            }
            heldPartials.remove(partial.path)
            if (partial.isFile && partial.length() == 0L) partial.delete()
        }
    }

    /**
     * Lands a fully gathered [partial] at [relative] (iOS `deliver`'s guard
     * and `move`, `WayMediaDownloader.swift:270-296@7c200bf`). The Way must
     * still load just before the rename, and `media/` is made one folder at
     * a time under the Way's folder, never with it: a Way deleted or swept
     * whole meanwhile gets nothing back. The check, the folders, and the
     * rename hold the media lock, so no delete runs between them.
     *
     * @return false, the partial removed, when the Way is gone.
     * @throws IOException when a folder or the rename fails, a full disk
     *   among them, or when [partial] is no longer there to land.
     */
    fun landMedia(id: String, relative: String, partial: File): Boolean = synchronized(mediaLock) {
        val target = mediaFile(id, relative)
        if (target == null || load(id) == null) {
            partial.delete()
            return false
        }
        val wayDir = directory(id)
        val folders = generateSequence(target.parentFile) { it.parentFile }
            .takeWhile { it != wayDir }
            .toList()
            .asReversed()
        try {
            folders.forEach { createFolder(it) }
        } catch (e: NoSuchFileException) {
            if (load(id) == null) {
                partial.delete()
                return false
            }
            throw e
        }
        try {
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        syncDirectory(target.parentFile ?: return true)
        return true
    }

    /**
     * Allocated bytes of every file in the Way's folder, as iOS's
     * `totalFileAllocatedSize` counts them (owner decision 4); a file still
     * being written doesn't count. An own walk's recordings live elsewhere.
     */
    fun diskUsage(id: String): Long {
        if (!isValidId(id)) return 0
        return directory(id).walkTopDown()
            .filter { it.isFile && !isTempName(it.name) }
            .sumOf(allocatedBytes)
    }

    fun diskUsage(ways: List<Way>): Long = ways.sumOf { diskUsage(it.id) }

    /** Reply recordings by the `n` of the voice they answer; keys that aren't integers are dropped. */
    fun replies(id: String): Map<Int, String> {
        if (!isValidId(id)) return emptyMap()
        val text = readText(File(directory(id), REPLIES_FILE)) ?: return emptyMap()
        val map = decodeOrNull { WayJson.decode(REPLIES_SERIALIZER, text) } ?: return emptyMap()
        return map.mapNotNull { (key, value) -> key.toIntOrNull()?.let { it to value } }.toMap()
    }

    /**
     * Files a reply into the Way's own folder, replacing an earlier reply
     * to the same voice. Never creates the folder, as iOS's doesn't
     * (`WayStore.swift:172-179@7c200bf`): on the first honoring of an own
     * walk there is no folder yet and the write fails (pilgrim-ios #98).
     *
     * @throws IllegalArgumentException for an invalid Way id.
     * @throws IOException when the folder is missing or the write fails.
     */
    fun setReply(wayId: String, originN: Int, relativePath: String) {
        require(isValidId(wayId)) { INVALID_WAY_ID }
        val map = replies(wayId) + (originN to relativePath)
        val encodable = map.mapKeys { (key, _) -> key.toString() }
        writeAtomically(File(directory(wayId), REPLIES_FILE), WayJson.encode(REPLIES_SERIALIZER, encodable))
    }

    /**
     * Writes (or overwrites) [walkUuid]'s link. iOS swallows a failed index
     * write; this throws it so the finalize step can retry.
     *
     * @throws IllegalArgumentException for an invalid Way id or walk uuid.
     * @throws IOException when the write fails.
     */
    fun link(walkUuid: String, wayId: String, arrival: WayArrival?) {
        require(isValidId(wayId)) { INVALID_WAY_ID }
        val file = linkFile(walkUuid)
        ensureDirectory(linksDirectory)
        val link = WayLink(wayId, arrival?.theirSeconds, arrival?.yourSeconds)
        writeAtomically(file, WayJson.encode(WayLink.serializer(), link))
    }

    fun wayLink(walkUuid: String): WayLink? {
        if (!isValidWalkUuid(walkUuid)) return null
        return readLink(linkFile(walkUuid))
    }

    fun wayId(walkUuid: String): String? = wayLink(walkUuid)?.wayId

    fun way(walkUuid: String): Way? = wayId(walkUuid)?.let(::load)

    /**
     * Holds the Way [walkUuid] is honoring until that walk finalizes. Not
     * listed, and never a home for replies (parity spec correction 3).
     *
     * @throws IllegalArgumentException for an invalid Way id or walk uuid.
     * @throws IOException when the write fails.
     */
    fun stage(walkUuid: String, way: Way) {
        require(isValidId(way.id)) { INVALID_WAY_ID }
        val dir = stagingDirectory(walkUuid)
        ensureDirectory(dir)
        writeAtomically(File(dir, WAY_FILE), WayJson.encode(way))
    }

    fun staged(walkUuid: String): Way? {
        if (!isValidWalkUuid(walkUuid)) return null
        return readWay(File(stagingDirectory(walkUuid), WAY_FILE))
    }

    /**
     * The copy of [wayId] a session of [kind] walks, which `:tracker`'s
     * session and the walk screen both read: the one staged under
     * [walkUuid] when [kind] is staged per walk and that copy is [wayId]'s,
     * else the listed or installed one. A stage whose staged copy is
     * missing reads its package, which the package guard holds still while
     * the session's live row exists (pilgrimage-stage spec P2 §2, A-1).
     */
    fun sessionWay(kind: HonorSourceKind, walkUuid: String, wayId: String): Way? {
        val staged = if (kind.isStagedPerWalk) staged(walkUuid) else null
        return staged?.takeIf { it.id == wayId } ?: load(wayId)
    }

    /**
     * Lists the staged Way the way iOS's walk-end save does
     * (`MainCoordinatorView.swift:109-110@7c200bf`): [save] overwrites
     * `way.json` and keeps the first `accepted.json` (parity spec
     * correction 2), then the staging goes. Repeating it after a crash
     * between the two writes the same content again.
     *
     * @return the promoted Way, or null when nothing is staged.
     * @throws IOException when the listed write fails; the staging is kept.
     */
    fun promoteStaged(walkUuid: String): Way? {
        val way = staged(walkUuid) ?: return null
        save(way)
        discardStaged(walkUuid)
        return way
    }

    fun discardStaged(walkUuid: String) {
        if (!isValidWalkUuid(walkUuid)) return
        stagingDirectory(walkUuid).deleteRecursively()
    }

    /** Every staged Way on disk, with when it was staged, for the launch sweep. */
    fun listStaged(): List<StagedWay> =
        stagingRoot.list().orEmpty().filter(::isValidWalkUuid).mapNotNull { uuid ->
            val file = File(stagingDirectory(uuid), WAY_FILE)
            if (file.isFile) StagedWay(uuid, file.lastModified()) else null
        }

    /** Every staging folder, finished writing or not: what [listStaged] can't see is swept too. */
    fun listStagingFolders(): List<StagingFolder> =
        stagingRoot.list().orEmpty().filter(::isValidWalkUuid).mapNotNull { uuid ->
            val dir = stagingDirectory(uuid)
            if (!dir.isDirectory) return@mapNotNull null
            val newest = dir.listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: 0L
            StagingFolder(
                walkUuid = uuid,
                lastTouchedMillis = maxOf(dir.lastModified(), newest),
                complete = File(dir, WAY_FILE).isFile,
            )
        }

    /**
     * Every downloaded route's folder, with the Replace marker beside them
     * rather than inside one, so no route's removal can take it
     * (`WayStore.swift:71-74@7c200bf`).
     */
    val pilgrimageRoot: File get() = File(baseDirectory, PILGRIMAGE_DIRECTORY)

    /**
     * A route's package folder, beside its stage Ways and never inside one,
     * so Replace and Remove can take the stages and leave the ledger. Null
     * for an id the slug rule refuses, before any path is built
     * (`WayStore.swift:76-83@7c200bf`).
     */
    fun pilgrimageDirectory(routeId: String): File? =
        if (isValidRouteId(routeId)) File(pilgrimageRoot, routeId) else null

    /**
     * Route ids with a package folder, in the file system's order, as
     * iOS's are (`WayStore.swift:85-91@7c200bf`): a folder holding only its
     * ledger is listed too, and the package manager decides what counts as
     * installed. `replacing.txt` never passes the slug rule.
     */
    fun pilgrimageRouteIds(): List<String> = pilgrimageRoot.list().orEmpty().filter(::isValidRouteId)

    fun routeFile(routeId: String): File? = pilgrimageDirectory(routeId)?.let { File(it, ROUTE_FILE) }

    fun releaseFile(routeId: String): File? = pilgrimageDirectory(routeId)?.let { File(it, RELEASE_FILE) }

    fun ledgerFile(routeId: String): File? = pilgrimageDirectory(routeId)?.let { File(it, LEDGER_FILE) }

    /** The ledger's cross-process lock. Not a temp name, so [sweepTempFiles] never takes it. */
    fun ledgerLockFile(routeId: String): File? = pilgrimageDirectory(routeId)?.let { File(it, LEDGER_LOCK_FILE) }

    val replacingFile: File get() = File(pilgrimageRoot, REPLACING_FILE)

    /**
     * Writes one of the pilgrimage tree's own files ([routeFile],
     * [releaseFile], [ledgerFile], [replacingFile]) the way every store
     * write lands, making its folder first.
     *
     * @throws IllegalArgumentException for any other file.
     * @throws IOException when the folder or the write fails.
     */
    fun writePilgrimageFile(file: File, bytes: ByteArray) {
        require(isPilgrimageFile(file)) { NOT_A_PILGRIMAGE_FILE }
        ensureDirectory(file.parentFile!!)
        writeAtomically(file, bytes)
    }

    /**
     * Deletes the temp files a kill left behind once older than
     * [olderThanMillis] (a younger one may be another process's write in
     * flight): a write's between its create and its rename, in `links/`,
     * each Way's folder, each staging folder a walk still needs,
     * `pilgrimage/` and each route's folder in it (the ledger's lock file is
     * no temp, and stays); and a media file still gathering in its Way's
     * folder, unless a fetch holds it ([holdMediaPartial]): a gather resumed
     * after a day offline keeps the bytes it is appending to. Staging
     * folders no walk needs go whole, through [discardStaged].
     *
     * @return how many were deleted.
     */
    fun sweepTempFiles(olderThanMillis: Long): Int {
        val folders = listOf(linksDirectory, pilgrimageRoot) +
            baseDirectory.list().orEmpty().filter(::isValidId).map { File(baseDirectory, it) } +
            stagingRoot.list().orEmpty().filter(::isValidWalkUuid).map { File(stagingRoot, it) } +
            pilgrimageRouteIds().map { File(pilgrimageRoot, it) }
        return synchronized(mediaLock) {
            folders.sumOf { folder ->
                folder.listFiles().orEmpty().count { file ->
                    file.isFile && isTempName(file.name) && file.lastModified() < olderThanMillis &&
                        file.path !in heldPartials && file.delete()
                }
            }
        }
    }

    /**
     * iOS `sweepExpired(now:)` (`WayStore.swift:194-235@7c200bf`, shared-walk
     * spec S3 §12): every Way whose `expires` is at or before [now] retires.
     * One no walk ever linked goes whole; one a link names (a deleted walk's
     * link still counts) keeps `way.json`, `accepted.json`, its replies and
     * its links, and loses its media. Own-walk Ways carry no expiry, so they
     * never go. A Way in [held] (a live Honor session's, or a Begin's in
     * flight) is skipped entirely, media and all (spec correction 9): iOS
     * can never sweep during a walk.
     *
     * @return the ids retired, an already-retired walked Way included, so
     *   every sweep can cancel their gathers.
     */
    fun sweepExpired(now: Instant, held: Set<String>): List<String> {
        val walked = walkedWayIds()
        val touched = mutableListOf<String>()
        for (way in list()) {
            val expires = way.expires ?: continue
            if (expires.isAfter(now) || way.id in held) continue
            retire(way.id, walked)
            touched += way.id
        }
        return touched
    }

    /**
     * iOS `retireMany(ids:)` (`WayStore.swift:212-235@7c200bf`): the expiry
     * sweep's rule for the stages a route no longer carries. A walked id
     * keeps `way.json`, `accepted.json`, its replies and its links, and
     * loses only its media (a stage has none, so its folder stays whole);
     * any other goes whole, under the media lock, counted in [deletions]
     * (P2 A-10). Invalid ids are skipped, and no link file is written.
     *
     * Walked means a link file names it, or a live Honor session does
     * ([liveSessionWayIds], P2 A-1): a stage whose walk still waits for its
     * finalize keeps the `way.json` its link is about to name.
     */
    fun retireMany(ids: Iterable<String>, liveSessionWayIds: Set<String>) {
        val walked = walkedWayIds().apply { addAll(liveSessionWayIds) }
        for (id in ids) {
            if (isValidId(id)) retire(id, walked)
        }
    }

    private fun walkedWayIds(): MutableSet<String> = linkFiles().mapNotNullTo(HashSet()) { readLink(it)?.wayId }

    /** iOS `retire(id:walked:)`, with Android's deletion count for a folder that went. */
    private fun retire(id: String, walked: Set<String>) {
        if (id in walked) {
            deleteMedia(id)
            return
        }
        val existed = synchronized(mediaLock) {
            val dir = directory(id)
            dir.exists().also { dir.deleteRecursively() }
        }
        if (existed) deletionCount.update { it + 1 }
    }

    private val linksDirectory: File get() = File(baseDirectory, LINKS_DIRECTORY)

    private val stagingRoot: File get() = File(baseDirectory, STAGING_DIRECTORY)

    /** The Way's folder; refuses an invalid id, as iOS's `precondition` does. */
    private fun directory(id: String): File {
        require(isValidId(id)) { INVALID_WAY_ID }
        return File(baseDirectory, id)
    }

    private fun linkFile(walkUuid: String): File {
        require(isValidWalkUuid(walkUuid)) { INVALID_WALK_UUID }
        return File(linksDirectory, walkUuid + LINK_SUFFIX)
    }

    private fun stagingDirectory(walkUuid: String): File {
        require(isValidWalkUuid(walkUuid)) { INVALID_WALK_UUID }
        return File(stagingRoot, walkUuid)
    }

    /** Committed link files only: a temp file never matches `<uuid>.json`. */
    private fun linkFiles(): List<File> =
        linksDirectory.listFiles().orEmpty().filter { file ->
            file.isFile && file.name.endsWith(LINK_SUFFIX) &&
                isValidWalkUuid(file.name.removeSuffix(LINK_SUFFIX))
        }

    private fun readLink(file: File): WayLink? {
        val text = readText(file) ?: return null
        return decodeOrNull { WayJson.decode(WayLink.serializer(), text) }
    }

    private fun readWay(file: File): Way? {
        val text = readText(file) ?: return null
        return decodeOrNull { decodeWay(text) }
    }

    private fun isStageWayId(id: String): Boolean = id.startsWith(STAGE_ID_PREFIX)

    private fun isPilgrimageFile(file: File): Boolean {
        val folder = file.parentFile ?: return false
        if (folder == pilgrimageRoot) return file.name == REPLACING_FILE
        return folder.parentFile == pilgrimageRoot && isValidRouteId(folder.name) && file.name in PACKAGE_FILES
    }

    private fun readText(file: File): String? = try {
        if (file.isFile) file.readText() else null
    } catch (e: IOException) {
        null
    }

    /** An unreadable file reads as absent, as iOS's `try?` decodes do. */
    private inline fun <T> decodeOrNull(decode: () -> T): T? = try {
        decode()
    } catch (e: SerializationException) {
        null
    }

    private fun ensureDirectory(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("could not create a Ways store folder")
        }
    }

    /** One folder, never its parents: a missing parent throws [NoSuchFileException]. */
    private fun createFolder(dir: File) {
        if (dir.isDirectory) return
        try {
            Files.createDirectory(dir.toPath())
        } catch (e: FileAlreadyExistsException) {
            if (!dir.isDirectory) throw e
        }
    }

    private fun isTempName(name: String): Boolean = name.startsWith(".") && name.endsWith(TEMP_SUFFIX)

    /**
     * A temp file unique to this write, fsynced, then renamed over [target].
     * The temp sits beside the target, so a missing folder fails the write
     * rather than creating it.
     */
    private fun writeAtomically(target: File, text: String) = writeAtomically(target, text.toByteArray(Charsets.UTF_8))

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}$TEMP_SUFFIX")
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
        syncDirectory(target.parentFile ?: return)
    }

    @Serializable
    private class Accepted(
        @Serializable(with = WayDateSerializer::class) val acceptedAt: Instant,
    )

    companion object {

        /**
         * iOS's allow-list, verbatim (`WayStore.swift:52-59@7c200bf`):
         * `\A(share:[A-Za-z0-9_-]{10}|walk:[0-9A-Fa-f-]{36}|pilgrimage:[a-z0-9-]{1,64}:[0-9]{1,3})\z`.
         * [Regex.matches] anchors the whole input as `\A…\z` does. Applied
         * before any id becomes a path, so no id can reach outside the store.
         */
        fun isValidId(id: String): Boolean = WAY_ID.matches(id)

        /**
         * A walk uuid used as a file name: the 36 characters the `walk:` id
         * rule allows, in either case, since iOS imports keep their
         * uppercase uuids and a link is keyed by the walk row's string verbatim.
         */
        fun isValidWalkUuid(uuid: String): Boolean = WALK_UUID.matches(uuid)

        /**
         * The dataset's route slug, `\A[a-z0-9-]{1,64}\z` with literal ASCII
         * classes (`WayStore.swift:61-65@7c200bf`), checked before a route id
         * reaches any path or URL.
         */
        fun isValidRouteId(id: String): Boolean = ROUTE_ID.matches(id)

        /** A stage Way's id, the index in plain decimal (`WayStore.swift:67-69@7c200bf`). */
        fun stageWayId(routeId: String, stageIndex: Int): String = "$STAGE_ID_PREFIX$routeId:$stageIndex"

        private val WAY_ID =
            Regex("share:[A-Za-z0-9_-]{10}|walk:[0-9A-Fa-f-]{36}|pilgrimage:[a-z0-9-]{1,64}:[0-9]{1,3}")
        private val WALK_UUID = Regex("[0-9A-Fa-f-]{36}")
        private val ROUTE_ID = Regex("[a-z0-9-]{1,64}")

        private val REPLIES_SERIALIZER = MapSerializer(String.serializer(), String.serializer())

        private const val WAY_FILE = "way.json"
        private const val ACCEPTED_FILE = "accepted.json"
        private const val REPLIES_FILE = "replies.json"
        private const val MEDIA_DIRECTORY = "media"
        private const val LINKS_DIRECTORY = "links"
        private const val STAGING_DIRECTORY = "staging"
        private const val PILGRIMAGE_DIRECTORY = "pilgrimage"
        /** Internal so the tiles' region prefix is built from it and can't drift from [stageWayId]. */
        internal const val STAGE_ID_PREFIX = "pilgrimage:"
        private const val ROUTE_FILE = "route.json"
        private const val RELEASE_FILE = "release.txt"
        private const val LEDGER_FILE = "ledger.json"
        private const val LEDGER_LOCK_FILE = "ledger.json.lock"
        private const val REPLACING_FILE = "replacing.txt"
        private val PACKAGE_FILES = setOf(ROUTE_FILE, RELEASE_FILE, LEDGER_FILE)
        private const val LINK_SUFFIX = ".json"
        private const val TEMP_SUFFIX = ".tmp"
        private const val PARTIAL_PREFIX = ".media-"
        private const val PARTIAL_SUFFIX = ".download$TEMP_SUFFIX"
        private const val INVALID_WAY_ID = "not a valid Way id"
        private const val INVALID_WALK_UUID = "not a valid walk uuid"
        private const val NOT_A_PILGRIMAGE_FILE = "not one of the pilgrimage tree's files"
    }
}

/**
 * fsyncs a folder so a rename inside it is durable. The JVM's `FileChannel`
 * can't open a folder on Android, so this goes through `android.system.Os`.
 * Best effort: a filesystem that refuses folder fsync, or a JVM test
 * runtime with no working `Os`, costs only the durability the write
 * already had, so the failure is reported as false and not thrown.
 */
internal fun fsyncDirectoryBestEffort(dir: File): Boolean = try {
    val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(fd)
    } finally {
        Os.close(fd)
    }
    true
} catch (e: ErrnoException) {
    false
} catch (e: RuntimeException) {
    false
} catch (e: LinkageError) {
    false
}

/**
 * A file's allocated size, `st_blocks × 512`, as iOS's
 * `totalFileAllocatedSize` reports it (shared-walk spec S4 §2.3). A file
 * the filesystem holds in no block of its own (inline data), or one a
 * runtime with no working `Os` can't stat, counts its length instead.
 */
internal fun allocatedBytesOf(file: File): Long = try {
    val blocks = Os.stat(file.path).st_blocks
    if (blocks > 0) blocks * BLOCK_BYTES else file.length()
} catch (e: ErrnoException) {
    file.length()
} catch (e: RuntimeException) {
    file.length()
} catch (e: LinkageError) {
    file.length()
}

private const val BLOCK_BYTES = 512L
