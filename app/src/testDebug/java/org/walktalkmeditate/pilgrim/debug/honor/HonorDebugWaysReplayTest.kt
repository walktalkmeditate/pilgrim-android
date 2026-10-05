// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.debug.honor

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.debug.honor.WayReplayTimeline.Timing
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness

/**
 * What HONOR_REPLAY_START plays, read from its extras by [HonorDebugWays.replay]:
 * the receiver hands whatever it returns straight to the [WayReplayer].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorDebugWaysReplayTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = UnconfinedTestDispatcher()
    private val storeDirectory = File(context.filesDir, "replay-ways")
    private val store = WayStore({ storeDirectory }, syncDirectory = { true })
    private val stage = HonorHarness.stageWay()
    private lateinit var db: PilgrimDatabase
    private lateinit var ways: HonorDebugWays
    private var walkId = 0L

    @Before
    fun setUp(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .build()
        val repository = WalkRepository(
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
        walkId = db.walkDao().insert(
            Walk(uuid = WALK_UUID, startTimestamp = WALK_START, endTimestamp = WALK_START + 600_000L),
        )
        WALK_LATITUDES.forEachIndexed { i, lat ->
            db.routeDataSampleDao().insert(
                RouteDataSample(walkId = walkId, timestamp = WALK_START + i * 60_000L, latitude = lat, longitude = -8.0),
            )
        }
        store.save(stage)
        ways = HonorDebugWays(repository, VoiceRecordingFileSystem(context), store)
    }

    @After
    fun tearDown() {
        db.close()
        storeDirectory.deleteRecursively()
    }

    @Test
    fun `a way extra replays the stored Way's route`() = runTest(dispatcher) {
        val replay = ways.replay(start { putExtra(HonorDebugReceiver.EXTRA_WAY, stage.id) })

        assertEquals(stage.route, replay?.route)
        assertEquals("way ${stage.id}", replay?.name)
    }

    @Test
    fun `a walk extra replays the walk's own route`() = runTest(dispatcher) {
        val replay = ways.replay(start { putExtra(HonorDebugReceiver.EXTRA_WALK, walkId.toString()) })

        assertEquals(WALK_LATITUDES, replay?.route?.map { it.lat })
        assertEquals("walk $walkId", replay?.name)
    }

    @Test
    fun `a walk named by its uuid replays its own route too`() = runTest(dispatcher) {
        val replay = ways.replay(start { putExtra(HonorDebugReceiver.EXTRA_WALK, WALK_UUID) })

        assertEquals(WALK_LATITUDES, replay?.route?.map { it.lat })
    }

    @Test
    fun `naming both a walk and a way replays neither, and says why`() = runTest(dispatcher) {
        val replay = ways.replay(
            start {
                putExtra(HonorDebugReceiver.EXTRA_WALK, walkId.toString())
                putExtra(HonorDebugReceiver.EXTRA_WAY, stage.id)
            },
        )

        assertNull(replay)
        assertTrue(ShadowLog.getLogsForTag(HonorDebugReceiver.TAG).any { it.msg.startsWith("replay refused") })
    }

    @Test
    fun `naming neither a walk nor a way replays nothing`() = runTest(dispatcher) {
        assertNull(ways.replay(start { }))
    }

    @Test
    fun `a way the store doesn't hold replays nothing`() = runTest(dispatcher) {
        assertNull(ways.replay(start { putExtra(HonorDebugReceiver.EXTRA_WAY, "pilgrimage:camino-frances:9") }))
    }

    @Test
    fun `without pace or window extras the replay plays the whole route at its recorded pace`() = runTest(dispatcher) {
        val replay = ways.replay(start { putExtra(HonorDebugReceiver.EXTRA_WAY, stage.id) })

        assertEquals(Timing(), replay?.timing)
    }

    @Test
    fun `the pace and window extras become the replay's timing`() = runTest(dispatcher) {
        val replay = ways.replay(
            start {
                putExtra(HonorDebugReceiver.EXTRA_WAY, stage.id)
                putExtra(HonorDebugReceiver.EXTRA_PACE, 6f)
                putExtra(HonorDebugReceiver.EXTRA_FROM, 0.4f)
                putExtra(HonorDebugReceiver.EXTRA_TO, 1f)
            },
        )

        assertEquals(Timing(paceMetersPerSecond = 6.0, fromFrac = 0.4f.toDouble(), toFrac = 1.0), replay?.timing)
    }

    @Test
    fun `a pace sent as an int rather than a float is refused`() = runTest(dispatcher) {
        val replay = ways.replay(
            start {
                putExtra(HonorDebugReceiver.EXTRA_WAY, stage.id)
                putExtra(HonorDebugReceiver.EXTRA_PACE, 6)
            },
        )

        assertNull(replay)
    }

    @Test
    fun `a window out of order is refused`() = runTest(dispatcher) {
        val replay = ways.replay(
            start {
                putExtra(HonorDebugReceiver.EXTRA_WALK, walkId.toString())
                putExtra(HonorDebugReceiver.EXTRA_FROM, 0.6f)
                putExtra(HonorDebugReceiver.EXTRA_TO, 0.4f)
            },
        )

        assertNull(replay)
    }

    private fun start(extras: Intent.() -> Unit) = Intent(HonorDebugReceiver.ACTION_REPLAY_START).apply(extras)

    private companion object {
        const val WALK_UUID = "5b0c4f7e-1d2a-4e3b-9c8d-7a6f5e4d3c2b"
        const val WALK_START = 1_700_000_000_000L
        val WALK_LATITUDES = (0..10).map { 42.0 + it * 0.001 }
    }
}
