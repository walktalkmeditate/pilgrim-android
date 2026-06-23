// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim.builder

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.pilgrim.FakeArchivedWalkRegistry
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimManifest
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimModification
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimPhoto
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimPreferences
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimSchema
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimVoiceRecording
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimWalk

/**
 * AF28 (iOS PR #45) + the import data-loss fix.
 *
 * `decodeWalkFiles` tests cover the decode-skip counting in isolation.
 * The `import()` integration tests build a real `.pilgrim` archive against
 * an in-memory Room DB to cover the two harder paths the review surfaced:
 *  - a walk that decodes but fails to insert is COUNTED (not silently
 *    dropped) — AF28 insert-path honesty.
 *  - a failed tended re-insert PRESERVES the user's original walk instead
 *    of deleting it and reporting success — the data-loss fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimPackageImporterTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val tempDir: File = Files.createTempDirectory("importer-decode-test").toFile()

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var importer: PilgrimPackageImporter

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        importer = PilgrimPackageImporter(db, json, context, FakeArchivedWalkRegistry())
    }

    @After fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    // ---- decodeWalkFiles unit coverage (no archive / no DB) ----

    @Test
    fun `decodeWalkFiles counts undecodable files as skipped`() {
        val good = File(tempDir, "good.json").apply { writeText(json.encodeToString(goodWalk())) }
        val bad = File(tempDir, "bad.json").apply { writeText("{ not valid pilgrim json") }

        val result = decodeWalkFiles(listOf(good, bad), json)

        assertEquals("only the good walk decodes", 1, result.walks.size)
        assertEquals("the corrupt file is counted, not silently dropped", 1, result.decodeFailures)
    }

    @Test
    fun `decodeWalkFiles reports zero failures when every file decodes`() {
        val a = File(tempDir, "a.json").apply { writeText(json.encodeToString(goodWalk())) }
        val b = File(tempDir, "b.json").apply { writeText(json.encodeToString(goodWalk())) }

        val result = decodeWalkFiles(listOf(a, b), json)

        assertEquals(2, result.walks.size)
        assertEquals(0, result.decodeFailures)
    }

    // ---- import() integration coverage ----

    @Test
    fun `import lands good walks with zero skipped`() = runBlocking {
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val uri = buildArchive(
            tended = false,
            walks = mapOf(
                "a.json" to json.encodeToString(goodWalk(uuid = a)),
                "b.json" to json.encodeToString(goodWalk(uuid = b)),
            ),
        )

        val summary = importer.import(uri)

        assertEquals(2, summary.added)
        assertEquals(0, summary.skipped)
        assertNotNull(db.walkDao().getByUuid(a))
        assertNotNull(db.walkDao().getByUuid(b))
    }

    @Test
    fun `import counts an undecodable file as skipped and lands the good walk`() = runBlocking {
        val good = UUID.randomUUID().toString()
        val uri = buildArchive(
            tended = false,
            walks = mapOf(
                "good.json" to json.encodeToString(goodWalk(uuid = good)),
                "bad.json" to "{ truncated",
            ),
        )

        val summary = importer.import(uri)

        assertEquals(1, summary.added)
        assertEquals(1, summary.skipped)
        assertNotNull("the decodable walk must land", db.walkDao().getByUuid(good))
    }

    // THE P0 regression test: a walk that fails to insert must NOT roll back
    // the walks that already imported in the same archive. With one batch
    // transaction (framework SQLite has no per-walk savepoint) the bad walk
    // dooms the whole batch and the good walk silently vanishes while still
    // being reported as `added` — verified to fail before the per-walk-
    // transaction fix. This asserts DB CONTENT, not just the summary count.
    @Test
    fun `a failed walk does not roll back its successfully-imported siblings`() = runBlocking {
        val goodUuid = UUID.randomUUID().toString()
        val badUuid = UUID.randomUUID().toString()
        val uri = buildArchive(
            tended = false,
            walks = mapOf(
                "good.json" to json.encodeToString(goodWalk(uuid = goodUuid, intention = "keep")),
                "bad.json" to json.encodeToString(walkWithBadVoiceRecording(badUuid)),
            ),
        )

        val summary = importer.import(uri)

        assertEquals(1, summary.added)
        assertEquals("the bad walk is counted, not silently dropped", 1, summary.skipped)
        val survivor = db.walkDao().getByUuid(goodUuid)
        assertNotNull("the good walk must survive the sibling's failure", survivor)
        assertEquals("keep", survivor!!.intention)
        assertNull("the bad walk must not have landed", db.walkDao().getByUuid(badUuid))
    }

    // A child-entity failure AFTER the walk row is inserted must roll the
    // walk row back too — no orphan walk. The bad photo (keptAt→pinnedAt=0)
    // trips WalkPhoto's `require(pinnedAt > 0)` inside insertChildEntities,
    // which runs after walkDao.insert returned a real id.
    @Test
    fun `a child failure after the walk row insert leaves no orphan walk`() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        val uri = buildArchive(
            tended = false,
            walks = mapOf("walk.json" to json.encodeToString(walkWithBadPhoto(uuid))),
        )

        val summary = importer.import(uri)

        assertEquals(0, summary.added)
        assertEquals(1, summary.skipped)
        assertNull("the walk row must roll back when a child insert fails", db.walkDao().getByUuid(uuid))
    }

    // A uuid repeated within one archive is handled once (benign skip), not
    // double-processed. Exactly one row, and the first occurrence wins.
    @Test
    fun `an in-archive duplicate uuid is imported once`() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        val uri = buildArchive(
            tended = false,
            walks = mapOf(
                "first.json" to json.encodeToString(goodWalk(uuid = uuid, intention = "first")),
                "second.json" to json.encodeToString(goodWalk(uuid = uuid, intention = "second")),
            ),
        )

        val summary = importer.import(uri)

        assertEquals(1, summary.added)
        assertEquals(0, summary.skipped)
        assertEquals(1, db.walkDao().getAllUuids().count { it == uuid })
        assertEquals("first occurrence wins", "first", db.walkDao().getByUuid(uuid)!!.intention)
    }

    // Re-importing a walk already in the DB via a NON-tended archive is a
    // silent idempotent skip — not counted as added or skipped.
    @Test
    fun `re-importing an existing walk non-tended is a silent skip`() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        db.walkDao().insert(
            Walk(uuid = uuid, startTimestamp = 1_000L, endTimestamp = 100_000L, intention = "original"),
        )

        val uri = buildArchive(
            tended = false,
            walks = mapOf("walk.json" to json.encodeToString(goodWalk(uuid = uuid, intention = "edited"))),
        )

        val summary = importer.import(uri)

        assertEquals(0, summary.added)
        assertEquals(0, summary.replaced)
        assertEquals(0, summary.skipped)
        assertEquals("non-tended import must not overwrite", "original", db.walkDao().getByUuid(uuid)!!.intention)
    }

    // Happy-path tended replace: a SUCCESSFUL tended re-import replaces the
    // existing walk in place (exactly one row, edited content).
    @Test
    fun `successful tended re-import replaces the existing walk`() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        db.walkDao().insert(
            Walk(uuid = uuid, startTimestamp = 1_000L, endTimestamp = 100_000L, intention = "original"),
        )

        val uri = buildArchive(
            tended = true,
            walks = mapOf("walk.json" to json.encodeToString(goodWalk(uuid = uuid, intention = "edited"))),
        )

        val summary = importer.import(uri)

        assertEquals(1, summary.replaced)
        assertEquals(0, summary.added)
        assertEquals(0, summary.skipped)
        assertEquals("edited", db.walkDao().getByUuid(uuid)!!.intention)
        assertEquals("replace must leave exactly one row", 1, db.walkDao().getAllUuids().count { it == uuid })
    }

    // The data-loss fix: a tended re-import whose new payload fails to
    // insert must NOT delete the user's existing walk.
    @Test
    fun `failed tended re-insert preserves the original walk`() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        // Seed the user's existing walk.
        db.walkDao().insert(
            Walk(uuid = uuid, startTimestamp = 1_000L, endTimestamp = 100_000L, intention = "original"),
        )

        // Tended archive re-importing the same uuid, but with a voice
        // recording whose endDate < startDate — convertToImport builds a
        // VoiceRecording that throws on its endTimestamp >= startTimestamp
        // invariant, failing the re-insert.
        val uri = buildArchive(
            tended = true,
            walks = mapOf("walk.json" to json.encodeToString(walkWithBadVoiceRecording(uuid))),
        )

        val summary = importer.import(uri)

        assertEquals("the bad re-insert is reported, not silently succeeded", 0, summary.replaced)
        assertEquals(1, summary.skipped)
        val survivor = db.walkDao().getByUuid(uuid)
        assertNotNull("the original walk must survive a failed tended re-insert", survivor)
        assertEquals("original", survivor!!.intention)
    }

    // ---- helpers ----

    private fun goodWalk(
        uuid: String = UUID.randomUUID().toString(),
        intention: String? = null,
    ): PilgrimWalk {
        val bundle = WalkExportBundle(
            walk = Walk(
                id = 1,
                uuid = uuid,
                startTimestamp = 1_000L,
                endTimestamp = 100_000L,
                intention = intention,
            ),
            routeSamples = emptyList(),
            altitudeSamples = emptyList(),
            walkEvents = emptyList(),
            activityIntervals = emptyList(),
            waypoints = emptyList(),
            voiceRecordings = emptyList(),
            walkPhotos = emptyList(),
        )
        return PilgrimPackageConverter.convert(bundle, includePhotos = false).walk
    }

    private fun walkWithBadVoiceRecording(uuid: String): PilgrimWalk =
        goodWalk(uuid).copy(
            voiceRecordings = listOf(
                PilgrimVoiceRecording(
                    startDate = Instant.ofEpochMilli(50_000L),
                    endDate = Instant.ofEpochMilli(40_000L), // end < start → invariant throws on import
                    duration = -10.0,
                    isEnhanced = false,
                ),
            ),
        )

    // keptAt → WalkPhoto.pinnedAt = 0, which trips WalkPhoto's
    // require(pinnedAt > 0) inside insertChildEntities — i.e. AFTER the walk
    // row has been inserted, exercising the orphan-walk-rollback path.
    private fun walkWithBadPhoto(uuid: String): PilgrimWalk =
        goodWalk(uuid).copy(
            photos = listOf(
                PilgrimPhoto(
                    localIdentifier = "photo-1",
                    capturedAt = Instant.ofEpochMilli(1_000L),
                    capturedLat = 0.0,
                    capturedLng = 0.0,
                    keptAt = Instant.ofEpochMilli(0L),
                ),
            ),
        )

    private fun manifestJson(walkCount: Int, tended: Boolean): String =
        json.encodeToString(
            PilgrimManifest(
                schemaVersion = PilgrimSchema.VERSION,
                exportDate = Instant.ofEpochSecond(1_700_000_000L),
                appVersion = "test",
                walkCount = walkCount,
                preferences = PilgrimPreferences(
                    distanceUnit = "km",
                    altitudeUnit = "m",
                    speedUnit = "kmh",
                    energyUnit = "kcal",
                    celestialAwareness = false,
                    zodiacSystem = "western",
                    beginWithIntention = false,
                ),
                customPromptStyles = emptyList(),
                intentions = emptyList(),
                events = emptyList(),
                archived = null,
                modifications = if (tended) listOf(PilgrimModification(op = "edit", walkId = "x")) else null,
            ),
        )

    private fun buildArchive(tended: Boolean, walks: Map<String, String>): Uri {
        val bytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifestJson(walkCount = walks.size, tended = tended).toByteArray())
                zip.closeEntry()
                walks.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry("walks/$name"))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
            baos.toByteArray()
        }
        val uri = Uri.parse("content://test/archive-${UUID.randomUUID()}")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }
}
