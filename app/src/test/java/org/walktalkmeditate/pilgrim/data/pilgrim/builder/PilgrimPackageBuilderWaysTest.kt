// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim.builder

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.di.PilgrimJsonModule
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.honor.HonorPersistence
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource

/**
 * A shared Way never leaves the phone in an export (plan U28, R11): a
 * `.pilgrim` made after importing and walking a share carries the honor
 * walk's own events and arrival waypoint, and no Way file, no media, and
 * no share id anywhere in its bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimPackageBuilderWaysTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private val storeRoot = File(context.noBackupFilesDir, "export-test-Ways")
    private val store = WayStore({ storeRoot }, syncDirectory = { true })

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java).allowMainThreadQueries().build()
        repository = WalkRepository(
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
    }

    @After
    fun tearDown() {
        db.close()
        storeRoot.deleteRecursively()
        File(context.cacheDir, "pilgrim_export").deleteRecursively()
    }

    private fun share() = Way(
        id = "share:$SHARE_ID",
        source = WaySource.Share(id = SHARE_ID, pageUrl = "https://walk.pilgrimapp.org/$SHARE_ID"),
        title = "Rúa do Franco → Obradoiro",
        departedAt = Instant.parse("2026-08-01T07:00:00Z"),
        tzIdentifier = "Europe/Madrid",
        expires = Instant.parse("2099-01-01T00:00:00Z"),
        route = listOf(WayPoint(42.88, -8.545, 250.0, 0.0), WayPoint(42.88, -8.540, 250.0, 400.0)),
        totalDistanceMeters = 408.0,
        theirActiveSeconds = 540.0,
        moments = listOf(
            WayMoment(
                id = "voice-1", frac = 0.5, at = null,
                kind = WayMomentKind.Voice(0.6, 40.0, VoiceKind.SPOKEN, WayMedia.File("audio/1.m4a")),
                transcript = "the bridge where we stopped",
            ),
        ),
        weather = null,
    )

    @Test
    fun `an export after importing and walking a shared Way carries no Way file and no share id`() = runBlocking {
        val way = share()
        store.save(way)
        File(storeRoot, "${way.id}/media/audio/1.m4a").apply { parentFile!!.mkdirs() }.writeText("their voice")
        val walkUuid = UUID.randomUUID().toString()
        store.link(walkUuid, way.id, arrival = null)
        store.setReply(way.id, originN = 1, relativePath = "recordings/$walkUuid/reply.wav")
        val walkId = db.walkDao().insert(Walk(uuid = walkUuid, startTimestamp = 1_000_000L, endTimestamp = 1_600_000L))
        (0..3).forEach { i ->
            db.routeDataSampleDao().insert(
                RouteDataSample(walkId = walkId, timestamp = 1_000_000L + i * 60_000L, latitude = 42.88, longitude = -8.545 + i * 0.001),
            )
        }
        db.walkEventDao().insert(WalkEvent(walkId = walkId, timestamp = 1_000_000L, eventType = WalkEventType.HONOR_MODE))
        db.walkEventDao().insert(WalkEvent(walkId = walkId, timestamp = 1_500_000L, eventType = WalkEventType.HONOR_ARRIVAL))
        db.waypointDao().insert(
            Waypoint(
                walkId = walkId,
                timestamp = 1_500_000L,
                latitude = 42.88,
                longitude = -8.540,
                label = "Walked their way: ${way.title}",
                icon = HonorPersistence.ARRIVAL_WAYPOINT_ICON,
            ),
        )
        val builder = PilgrimPackageBuilder(
            walkRepository = repository,
            walkPhotoDao = db.walkPhotoDao(),
            practicePreferences = FakePracticePreferencesRepository(),
            unitsPreferences = FakeUnitsPreferencesRepository(),
            photoEmbedder = AndroidPilgrimPhotoEmbedder(context),
            json = PilgrimJsonModule.providePilgrimJson(),
            context = context,
        )

        val exported = builder.build(includePhotos = true).file

        ZipFile(exported).use { zip ->
            val entries = zip.entries().toList()
            val names = entries.map { it.name }
            assertEquals(setOf("manifest.json", "schema.json", "walks/$walkUuid.json"), names.toSet())
            val contents = entries.associate { it.name to zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
            contents.forEach { (name, text) ->
                assertFalse("$name carries the share id", text.contains(SHARE_ID))
                assertFalse("$name carries a Way id", text.contains("share:"))
                assertFalse("$name carries the sharer's media", text.contains("their voice"))
                assertFalse("$name carries the sharer's transcript", text.contains("the bridge where we stopped"))
            }
            val walk = contents.getValue("walks/$walkUuid.json")
            assertTrue("the walk's own honor events still go", walk.contains("honorMode"))
            assertTrue("and its arrival waypoint", walk.contains(HonorPersistence.ARRIVAL_WAYPOINT_ICON))
        }
    }

    private companion object {
        const val SHARE_ID = "Qoi4YmPHLN"
    }
}
