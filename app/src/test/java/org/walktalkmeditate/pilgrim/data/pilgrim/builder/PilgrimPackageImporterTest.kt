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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.threads.FakeThreadsPreferencesRepository
import org.walktalkmeditate.pilgrim.core.threads.TranscriptContextStore
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.pilgrim.FakeArchivedWalkRegistry
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimActivity
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimArchivedWalk
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimManifest
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimModification
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimPhoto
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimPreferences
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimSchema
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimVoiceRecording
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimWalk
import org.walktalkmeditate.pilgrim.data.walk.WalkDistanceCalculator
import org.walktalkmeditate.pilgrim.data.walk.WalkMetricsBackfillCoordinator
import org.walktalkmeditate.pilgrim.data.walk.WalkMetricsCache
import org.walktalkmeditate.pilgrim.data.walk.deriveActivityIntervals
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.ui.settings.data.WalksSource

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
    private lateinit var threadsStore: TranscriptContextStore
    private lateinit var threadsPreferences: FakeThreadsPreferencesRepository

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        File(context.filesDir, "transcript_contexts").deleteRecursively()
        threadsStore = TranscriptContextStore(context, json)
        threadsPreferences = FakeThreadsPreferencesRepository()
        importer = PilgrimPackageImporter(
            db,
            json,
            context,
            FakeArchivedWalkRegistry(),
            threadsStore,
            threadsPreferences,
            WalkMetricsCache(repository(), db.walkDao(), db.walkEventDao()),
        )
    }

    @After fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
        File(context.filesDir, "transcript_contexts").deleteRecursively()
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

    // ---- #223: package sittings land as walk_events ----

    @Test
    fun `imported meditation activities become events that derive the same sittings`() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        val uri = buildArchive(
            tended = false,
            walks = mapOf("walk.json" to json.encodeToString(walkWithActivities(uuid, statsMeditateSeconds = 999.0))),
        )

        val summary = importer.import(uri)

        assertEquals(1, summary.added)
        val walk = db.walkDao().getByUuid(uuid)!!
        val events = db.walkEventDao().getForWalk(walk.id)
        assertEquals(
            listOf(
                WalkEventType.MEDITATION_START to 10_000L,
                WalkEventType.MEDITATION_END to 40_000L,
                WalkEventType.MEDITATION_START to 60_000L,
                WalkEventType.MEDITATION_END to 90_000L,
            ),
            events.map { it.eventType to it.timestamp },
        )
        // The summary and share derive sittings exactly like this.
        assertEquals(
            listOf(10_000L to 40_000L, 60_000L to 90_000L),
            deriveActivityIntervals(events, walk.id, closeAt = walk.endTimestamp)
                .map { it.startTimestamp to it.endTimestamp },
        )
        assertEquals(
            "the iOS \"unknown\" activity stays a WALKING row; no MEDITATING row is stored",
            listOf(Triple(ActivityType.WALKING, 45_000L, 50_000L)),
            db.activityIntervalDao().getForWalk(walk.id)
                .map { Triple(it.activityType, it.startTimestamp, it.endTimestamp) },
        )
    }

    @Test
    fun `an imported walk's meditation total is recomputed from its events, not copied from stats`() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        val uri = buildArchive(
            tended = false,
            walks = mapOf("walk.json" to json.encodeToString(walkWithActivities(uuid, statsMeditateSeconds = 999.0))),
        )
        importer.import(uri)
        val walk = db.walkDao().getByUuid(uuid)!!
        assertNull("left for the backfill", walk.meditationSeconds)

        WalkMetricsCache(repository(), db.walkDao(), db.walkEventDao()).computeAndPersist(walk.id)

        assertEquals(60L, db.walkDao().getById(walk.id)!!.meditationSeconds)
    }

    @Test
    fun `a package from Android 1_5_0 or earlier imports with no sittings and no invented events`() = runBlocking {
        // Native walks exported `activities: []` before #223.
        val uuid = UUID.randomUUID().toString()
        val uri = buildArchive(
            tended = false,
            walks = mapOf("walk.json" to json.encodeToString(goodWalk(uuid))),
        )

        importer.import(uri)

        val walk = db.walkDao().getByUuid(uuid)!!
        assertEquals(emptyList<Any>(), db.walkEventDao().getForWalk(walk.id))
        assertEquals(emptyList<Any>(), db.activityIntervalDao().getForWalk(walk.id))
    }

    @Test
    fun `a walk that fails to insert leaves none of its sitting events behind`() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        val broken = walkWithActivities(uuid, statsMeditateSeconds = 0.0).copy(
            voiceRecordings = walkWithBadVoiceRecording(uuid).voiceRecordings,
        )
        val uri = buildArchive(tended = false, walks = mapOf("walk.json" to json.encodeToString(broken)))

        val summary = importer.import(uri)

        assertEquals(1, summary.skipped)
        assertNull(db.walkDao().getByUuid(uuid))
        assertEquals(0L, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM walk_events").use { c ->
            c.moveToFirst()
            c.getLong(0)
        })
    }

    // ---- #238: the archive strip keeps an uncached walk's stats ----

    @Test
    fun `archiving a walk with NULL caches keeps the stats its children gave`() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        val walkId = db.walkDao().insert(Walk(uuid = uuid, startTimestamp = 1_000L, endTimestamp = 3_601_000L))
        db.routeDataSampleDao().insertAll(
            listOf(
                RouteDataSample(walkId = walkId, timestamp = 2_000L, latitude = 0.0, longitude = 0.0),
                RouteDataSample(walkId = walkId, timestamp = 3_000L, latitude = 0.0, longitude = 0.001),
            ),
        )
        db.walkEventDao().insert(WalkEvent(walkId = walkId, timestamp = 601_000L, eventType = WalkEventType.MEDITATION_START))
        db.walkEventDao().insert(WalkEvent(walkId = walkId, timestamp = 1_201_000L, eventType = WalkEventType.MEDITATION_END))
        val childDistance = WalkDistanceCalculator.computeDistanceMeters(db.routeDataSampleDao().getForWalk(walkId))
        assertTrue("the fixture's route must have a real distance", childDistance > 100.0)

        importer.import(buildArchive(tended = false, walks = emptyMap(), archived = listOf(archivedEntry(uuid))))

        assertEquals("the strip ran", emptyList<Any>(), db.routeDataSampleDao().getForWalk(walkId))
        assertEquals("the strip ran", emptyList<Any>(), db.walkEventDao().getForWalk(walkId))
        val stripped = db.walkDao().getById(walkId)!!
        assertEquals(childDistance, stripped.distanceMeters!!, 0.0)
        assertEquals(600L, stripped.meditationSeconds)

        runBackfillOnce()

        val afterBackfill = db.walkDao().getById(walkId)!!
        assertEquals(
            stripped.distanceMeters to stripped.meditationSeconds,
            afterBackfill.distanceMeters to afterBackfill.meditationSeconds,
        )
    }

    /** One pass of the real [WalkMetricsBackfillCoordinator] over the walks as they are now. */
    private suspend fun runBackfillOnce() = coroutineScope {
        val snapshot = object : WalksSource {
            override fun observeAllWalks(): Flow<List<Walk>> = flow { emit(db.walkDao().getAll()) }
        }
        val cache = WalkMetricsCache(repository(), db.walkDao(), db.walkEventDao())
        WalkMetricsBackfillCoordinator(snapshot, cache, this).start()
    }

    private fun archivedEntry(uuid: String) = PilgrimArchivedWalk(
        id = uuid,
        startDate = 1.0,
        endDate = 3_601.0,
        archivedAt = 1_700_000_000.0,
        stats = PilgrimArchivedWalk.Stats(
            distance = 0.0,
            activeDuration = 3_600.0,
            talkDuration = 0.0,
            meditateDuration = 0.0,
        ),
    )

    // ---- Phase 20 U5: threads clearTombstones + importGeneration hygiene ----

    // Android's .pilgrim wire format carries no recording identity
    // (PilgrimVoiceRecording has no uuid field — PilgrimPackageConverter
    // mints a fresh random VoiceRecording.uuid on import), so there is no
    // way to pre-arrange a KNOWN uuid collision with an existing
    // tombstone. This test instead spies on the store to prove the call
    // actually happens with a non-empty uuid list whenever the imported
    // archive contains voice recordings — RecordingTranscriptContextStore
    // still delegates to the real implementation, so this is not a bare
    // no-op double.
    @Test
    fun `a successful import with voice recordings clears tombstones for the minted uuids`() = runBlocking {
        val spyStore = RecordingTranscriptContextStore(context, json)
        val spyPreferences = FakeThreadsPreferencesRepository()
        val importerWithSpy = PilgrimPackageImporter(
            db,
            json,
            context,
            FakeArchivedWalkRegistry(),
            spyStore,
            spyPreferences,
            WalkMetricsCache(repository(), db.walkDao(), db.walkEventDao()),
        )
        val uri = buildArchive(
            tended = false,
            walks = mapOf("walk.json" to json.encodeToString(walkWithVoiceRecording(UUID.randomUUID().toString()))),
        )

        val summary = importerWithSpy.import(uri)

        assertEquals(1, summary.added)
        assertEquals(1, spyStore.clearTombstonesCalls.size)
        assertEquals(1, spyStore.clearTombstonesCalls.single().size)
    }

    @Test
    fun `a successful import bumps importGeneration exactly once, even with no voice recordings`() = runBlocking {
        val spyPreferences = FakeThreadsPreferencesRepository()
        val importerWithSpy = PilgrimPackageImporter(
            db,
            json,
            context,
            FakeArchivedWalkRegistry(),
            TranscriptContextStore(context, json),
            spyPreferences,
            WalkMetricsCache(repository(), db.walkDao(), db.walkEventDao()),
        )
        val before = spyPreferences.importGeneration.value
        val uri = buildArchive(
            tended = false,
            walks = mapOf("walk.json" to json.encodeToString(goodWalk())),
        )

        importerWithSpy.import(uri)

        assertEquals(before + 1, spyPreferences.importGeneration.value)
    }

    @Test
    fun `a failed import (invalid package) does not bump importGeneration`() = runBlocking {
        val spyPreferences = FakeThreadsPreferencesRepository()
        val importerWithSpy = PilgrimPackageImporter(
            db,
            json,
            context,
            FakeArchivedWalkRegistry(),
            TranscriptContextStore(context, json),
            spyPreferences,
            WalkMetricsCache(repository(), db.walkDao(), db.walkEventDao()),
        )
        val badUri = Uri.parse("content://test/does-not-exist-${UUID.randomUUID()}")
        shadowOf(context.contentResolver).registerInputStream(badUri, ByteArrayInputStream(ByteArray(0)))

        try {
            importerWithSpy.import(badUri)
        } catch (expected: PilgrimPackageError) {
            // expected — the point is that the generation must not bump.
        }

        assertEquals(0, spyPreferences.importGeneration.value)
    }

    private class RecordingTranscriptContextStore(context: Context, json: Json) :
        TranscriptContextStore(context, json) {
        val clearTombstonesCalls = mutableListOf<List<String>>()
        override suspend fun clearTombstones(uuids: List<String>) {
            clearTombstonesCalls += uuids
            super.clearTombstones(uuids)
        }
    }

    private fun walkWithVoiceRecording(uuid: String): PilgrimWalk =
        goodWalk(uuid).copy(
            voiceRecordings = listOf(
                PilgrimVoiceRecording(
                    startDate = Instant.ofEpochMilli(0L),
                    endDate = Instant.ofEpochMilli(5_000L),
                    duration = 5.0,
                    transcription = "a transcript",
                    isEnhanced = false,
                ),
            ),
        )

    // ---- helpers ----

    /**
     * An iOS-shaped walk: two "meditation" activities (60 s in all), one
     * "unknown", and a stats total that disagrees with them.
     */
    private fun walkWithActivities(uuid: String, statsMeditateSeconds: Double): PilgrimWalk {
        val walk = goodWalk(uuid)
        return walk.copy(
            stats = walk.stats.copy(meditateDuration = statsMeditateSeconds),
            activities = listOf(
                PilgrimActivity("meditation", Instant.ofEpochMilli(10_000L), Instant.ofEpochMilli(40_000L)),
                PilgrimActivity("unknown", Instant.ofEpochMilli(45_000L), Instant.ofEpochMilli(50_000L)),
                PilgrimActivity("meditation", Instant.ofEpochMilli(60_000L), Instant.ofEpochMilli(90_000L)),
            ),
        )
    }

    private fun repository() = WalkRepository(
        database = db,
        walkDao = db.walkDao(),
        routeDao = db.routeDataSampleDao(),
        altitudeDao = db.altitudeSampleDao(),
        walkEventDao = db.walkEventDao(),
        activityIntervalDao = db.activityIntervalDao(),
        waypointDao = db.waypointDao(),
        voiceRecordingDao = db.voiceRecordingDao(),
        walkPhotoDao = db.walkPhotoDao(),
    )

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

    private fun manifestJson(walkCount: Int, tended: Boolean, archived: List<PilgrimArchivedWalk>?): String =
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
                archived = archived,
                modifications = if (tended) listOf(PilgrimModification(op = "edit", walkId = "x")) else null,
            ),
        )

    private fun buildArchive(
        tended: Boolean,
        walks: Map<String, String>,
        archived: List<PilgrimArchivedWalk>? = null,
    ): Uri {
        val bytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifestJson(walkCount = walks.size, tended = tended, archived = archived).toByteArray())
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
