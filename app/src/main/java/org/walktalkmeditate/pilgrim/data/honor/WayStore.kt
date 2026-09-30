// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
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

/** A staged own-walk Way, by the uuid of the walk that is honoring it. */
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
 * <base>/links/<walk uuid>.json   one WayLink per walk (Android)
 * <base>/staging/<walk uuid>/way.json   an own-walk Way while it is walked (Android)
 * ```
 *
 * iOS keeps every link in one `index.json`, which one bad read followed by
 * a write empties (pilgrim-ios #107); here each walk's link is its own
 * file, so no write touches another walk's link. `links` and `staging`
 * are not valid Way ids, so [list] steps over them as iOS's steps over
 * `pilgrimage` and `index.json`.
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
 */
class WayStore(
    resolveBaseDirectory: () -> File,
    private val clock: Clock = Clock.System,
    private val syncDirectory: (File) -> Boolean = ::fsyncDirectoryBestEffort,
) {

    val baseDirectory: File by lazy(resolveBaseDirectory)

    fun save(way: Way) {
        val dir = directory(way.id)
        ensureDirectory(dir)
        writeAtomically(File(dir, WAY_FILE), WayJson.encode(way))
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

    fun acceptedAt(id: String): Instant? {
        if (!isValidId(id)) return null
        val text = readText(File(directory(id), ACCEPTED_FILE)) ?: return null
        return decodeOrNull { WayJson.decode(Accepted.serializer(), text).acceptedAt }
    }

    /** Every readable Way, newest acceptance first; one never accepted sorts last. */
    fun list(): List<Way> {
        val names = baseDirectory.list().orEmpty()
        return names.filter(::isValidId).mapNotNull(::load)
            .sortedByDescending { acceptedAt(it.id) ?: Instant.MIN }
    }

    /** Removes the Way's folder and every walk's link to it (`WayStore.swift:123-129@7c200bf`). */
    fun delete(id: String) {
        if (!isValidId(id)) return
        directory(id).deleteRecursively()
        linkFiles().forEach { file ->
            if (readLink(file)?.wayId == id) file.delete()
        }
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

    fun hasMedia(id: String): Boolean {
        if (!isValidId(id)) return false
        return !mediaDirectory(id).list().isNullOrEmpty()
    }

    fun deleteMedia(id: String) {
        if (!isValidId(id)) return
        mediaDirectory(id).deleteRecursively()
    }

    /** Bytes of every file in the Way's folder. An own walk's recordings live elsewhere and don't count. */
    fun diskUsage(id: String): Long {
        if (!isValidId(id)) return 0
        return directory(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
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
     * Deletes the temp files a write killed between its create and its
     * rename left in `links/` and in each Way's folder, once older than
     * [olderThanMillis]: a younger one may be another process's write in
     * flight. Staging folders go whole, through [discardStaged].
     *
     * @return how many were deleted.
     */
    fun sweepTempFiles(olderThanMillis: Long): Int {
        val folders = listOf(linksDirectory) +
            baseDirectory.list().orEmpty().filter(::isValidId).map { File(baseDirectory, it) }
        return folders.sumOf { folder ->
            folder.listFiles().orEmpty().count { file ->
                file.isFile && file.name.startsWith(".") && file.name.endsWith(TEMP_SUFFIX) &&
                    file.lastModified() < olderThanMillis && file.delete()
            }
        }
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
        return decodeOrNull { WayJson.decode(text) }
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

    /**
     * A temp file unique to this write, fsynced, then renamed over [target].
     * The temp sits beside the target, so a missing folder fails the write
     * rather than creating it.
     */
    private fun writeAtomically(target: File, text: String) {
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}$TEMP_SUFFIX")
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
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

        private val WAY_ID =
            Regex("share:[A-Za-z0-9_-]{10}|walk:[0-9A-Fa-f-]{36}|pilgrimage:[a-z0-9-]{1,64}:[0-9]{1,3}")
        private val WALK_UUID = Regex("[0-9A-Fa-f-]{36}")

        private val REPLIES_SERIALIZER = MapSerializer(String.serializer(), String.serializer())

        private const val WAY_FILE = "way.json"
        private const val ACCEPTED_FILE = "accepted.json"
        private const val REPLIES_FILE = "replies.json"
        private const val MEDIA_DIRECTORY = "media"
        private const val LINKS_DIRECTORY = "links"
        private const val STAGING_DIRECTORY = "staging"
        private const val LINK_SUFFIX = ".json"
        private const val TEMP_SUFFIX = ".tmp"
        private const val INVALID_WAY_ID = "not a valid Way id"
        private const val INVALID_WALK_UUID = "not a valid walk uuid"
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
