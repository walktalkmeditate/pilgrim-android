// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.seek

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestDispatcher
import org.walktalkmeditate.pilgrim.audio.seek.SeekSoundPlaying
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.seek.SeekSessionEntity
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.seek.SeekChain
import org.walktalkmeditate.pilgrim.domain.seek.SeekChainGenerator
import org.walktalkmeditate.pilgrim.domain.seek.SeekClearing
import org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint
import org.walktalkmeditate.pilgrim.domain.seek.SeekPowerTier
import org.walktalkmeditate.pilgrim.location.LocationSource
import org.walktalkmeditate.pilgrim.sensor.fakeStepCounter
import org.walktalkmeditate.pilgrim.walk.WalkControllerImpl
import org.walktalkmeditate.pilgrim.walk.WalkStartRequest

/**
 * One seek walk's world for the `:tracker` tests (plan U25): an in-memory
 * Room and its executors on the test [dispatcher], so writes drain under
 * `runCurrent` while the engines' pulse and stillness clocks run on virtual
 * time; a [clock] that reads that same virtual time; the real repository and
 * controller; and spies for the senses. The chain runs north from [home].
 */
internal class SeekTrackerHarness(val dispatcher: TestDispatcher) {

    val clock = Clock { BASE_MILLIS + dispatcher.scheduler.currentTime }
    val db: PilgrimDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        PilgrimDatabase::class.java,
    )
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

    /** What every sense did, in order, across both processes' spies. */
    val ops: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun newController(honorEnabled: Boolean = true) =
        WalkControllerImpl(repository, clock, fakeStepCounter(), FixedReleaseFlags(honor = honorEnabled))

    /** A fresh `:tracker` process's seek session, fed by [fixes]. */
    fun newSession(
        controller: WalkControllerImpl,
        sound: SpySeekSound,
        fixes: Flow<LocationPoint>,
        honorEnabled: Boolean = true,
    ) = SeekTrackerSession(
        database = db,
        locationSource = locationSource(fixes),
        powerTiers = MutableSharedFlow<SeekPowerTier>(),
        writer = controller,
        senses = senses(sound, label = sound.name),
        soundSettings = TrackerSeekSoundSettings(),
        releaseFlags = FixedReleaseFlags(honor = honorEnabled),
        clock = clock,
        arrivalLabel = { ordinal -> "Clearing $ordinal" },
        sessionDispatcher = dispatcher,
    )

    /** The arrival haptic snapshots what Room already holds, the persist-before-ritual probe. */
    fun senses(sound: SpySeekSound, label: String) = SeekSenses(
        soundPlayer = sound,
        arrivalHaptic = {
            ops += "$label haptic:arrival(events=${countSeekArrivalEvents()},waypoints=${countArrivalWaypoints()})"
        },
        breathInHaptic = { ops += "$label haptic:breathIn" },
        pickRevealWhisper = { null },
        playWhisper = {},
    )

    fun locationSource(fixes: Flow<LocationPoint>) = object : LocationSource {
        override fun locationFlow(): Flow<LocationPoint> = fixes
        override suspend fun lastKnownLocation(): LocationPoint? = null
    }

    /** Begin's hand-off, as the UI's orchestrator would build it from a staged [chain]. */
    fun handOff(
        chain: SeekChain,
        nextPulseDueAtMillis: Long? = null,
        sonar: SeekSonarSettings = SeekSonarSettings(sonarEnabled = true, sonarVolume = 0.5f, soundsEnabled = true),
    ) = SeekStart(
        chain = chain,
        activeIndex = 0,
        durationMinutes = 30,
        tintHex = null,
        seed = SEED,
        seededAtEpochMillis = BASE_MILLIS,
        intention = "find the river",
        nextPulseDueAtMillis = nextPulseDueAtMillis,
        sonar = sonar,
    )

    /** The tracker's Start with Begin's hand-off riding it, under a uuid minted at Begin. */
    suspend fun startSeekWalk(controller: WalkControllerImpl, start: SeekStart): Walk =
        controller.startWalk(
            WalkStartRequest(mode = WalkMode.Seek, walkUuid = UUID.randomUUID().toString(), seek = start),
        )

    suspend fun row(walkId: Long): SeekSessionEntity = db.seekDao().getSession(walkId)!!

    fun countSeekArrivalEvents(): Int = rawCount("SELECT COUNT(*) FROM walk_events WHERE event_type = 'SEEK_ARRIVAL'")

    fun countArrivalWaypoints(): Int =
        rawCount("SELECT COUNT(*) FROM waypoints WHERE icon = '${SeekPersistence.ARRIVAL_WAYPOINT_ICON}'")

    /** Synchronous, so a sense can read Room without waiting on the test executor. */
    private fun rawCount(sql: String): Int =
        db.query(sql, null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    fun fix(at: SeekPoint, accuracy: Float = 10f): LocationPoint = LocationPoint(
        timestamp = clock.now(),
        latitude = at.latitude,
        longitude = at.longitude,
        horizontalAccuracyMeters = accuracy,
    )

    companion object {
        const val BASE_MILLIS = 1_700_000_000_000L
        const val SEED = -6_148_914_691_236_517_206L

        val home = SeekPoint(latitude = 42.8782, longitude = -8.5448)

        /** Clearings [spacingMeters] apart due north of [home], 50 m across. */
        fun chain(clearingCount: Int, spacingMeters: Double = 300.0) = SeekChain(
            clearings = (1..clearingCount).map { index ->
                SeekClearing(
                    center = SeekChainGenerator.destination(
                        from = home,
                        bearingDegrees = 0.0,
                        distanceMeters = spacingMeters * index,
                    ),
                    radiusMeters = 50.0,
                )
            },
            budgetMeters = 5_000.0,
        )
    }
}

/** Records every sonar call with the time it came, named for the process that made it. */
internal class SpySeekSound(val name: String, private val clock: Clock, private val ops: MutableList<String>) : SeekSoundPlaying {
    val pingTimes: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    var prepareCount = 0
    var bowlCount = 0
    var completionBowlCount = 0
    var stopCount = 0

    override fun prepare() {
        prepareCount++
    }

    override fun playPing(aligned: Boolean, closeness: Float) {
        pingTimes += clock.now()
        ops += "$name ping"
    }

    override fun playBowl() {
        bowlCount++
        ops += "$name bowl"
    }

    override fun playCompletionBowl() {
        completionBowlCount++
        ops += "$name completionBowl"
    }

    override fun stop() {
        stopCount++
    }
}
