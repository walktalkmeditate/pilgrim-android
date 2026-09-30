// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.honor

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.StateFlow
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.sensor.fakeStepCounter
import org.walktalkmeditate.pilgrim.walk.HonorSettings
import org.walktalkmeditate.pilgrim.walk.HonorStart
import org.walktalkmeditate.pilgrim.walk.WalkControllerImpl
import org.walktalkmeditate.pilgrim.walk.WalkStartRequest

/**
 * One honor walk's world for the `:tracker` tests: an in-memory Room, a
 * Ways store and a files root in [folder], the real repository, finalizer,
 * and controller, and fakes for the audio ports. The Way runs 1.1 km east
 * along the equator, a route point every 0.001° (about 111 m) a minute apart.
 */
internal class HonorHarness(private val folder: File) {

    val clock = MutableClock(1_700_000_000_000L)
    val db: PilgrimDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        PilgrimDatabase::class.java,
    ).allowMainThreadQueries().build()
    val store = WayStore({ File(folder, "Ways") }, clock)
    val filesRoot = File(folder, "files")
    val finalizer = HonorFinalizer(db, store, clock, Dispatchers.IO)
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
        wayStore = store,
        honorFinalizer = finalizer,
    )
    var controller = newController()
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun newController(honorEnabled: Boolean = true) =
        WalkControllerImpl(repository, clock, fakeStepCounter(), FixedReleaseFlags(honor = honorEnabled))

    fun newSession(
        ports: FakePorts = FakePorts(),
        honorEnabled: Boolean = true,
        arrivalRecorder: HonorArrivalRecorder = controller,
    ) = HonorSession(
        database = db,
        wayStore = store,
        arrivalRecorder = arrivalRecorder,
        voice = ports.voice,
        duck = ports.duck,
        haptics = ports.haptics,
        gatePort = ports.gates,
        media = HonorMediaFiles({ filesRoot }, store),
        releaseFlags = FixedReleaseFlags(honor = honorEnabled),
        clock = clock,
        arrivalLabel = { title -> "Walked their way: $title" },
        sessionDispatcher = Dispatchers.IO,
        tickMillis = 0,
    )

    /** Stages [way] under a fresh uuid and starts the walk through the controller, as Begin does. */
    suspend fun startHonorWalk(way: Way = way(), settings: HonorSettings = HonorSettings(true, false)): Walk {
        val uuid = UUID.randomUUID().toString()
        store.stage(uuid, way)
        return controller.startWalk(
            WalkStartRequest(mode = WalkMode.Honor, walkUuid = uuid, honor = HonorStart(way.id, settings)),
        )
    }

    fun writeRecording(relativePath: String): File =
        File(filesRoot, relativePath).apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(64) { 1 })
        }

    fun writeRecordings(way: Way) {
        way.moments.mapNotNull { (it.kind as? WayMomentKind.Voice)?.media as? WayMedia.Recording }
            .forEach { writeRecording(it.relativePath) }
    }

    /** Joins every session before the database closes, so no leaked query outlives the test. */
    fun close() {
        runBlocking { serviceScope.coroutineContext[Job]!!.cancelAndJoin() }
        db.close()
    }

    companion object {
        const val SOURCE_UUID = "0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50"
        const val WAY_ID = "walk:$SOURCE_UUID"
        const val END_LON = 0.01

        fun voice(n: Int, lon: Double, kind: VoiceKind = VoiceKind.SPOKEN) = WayMoment(
            id = "voice-$n",
            frac = lon / END_LON,
            at = WayCoordinate(lat = 0.0, lon = lon),
            kind = WayMomentKind.Voice(
                endFrac = lon / END_LON,
                duration = 30.0,
                kind = kind,
                media = WayMedia.Recording(relativePath = "recordings/$SOURCE_UUID/voice-$n.wav"),
            ),
        )

        fun waypoint(n: Int, lon: Double) = WayMoment(
            id = "waypoint-$n",
            frac = lon / END_LON,
            at = WayCoordinate(lat = 0.0, lon = lon),
            kind = WayMomentKind.Waypoint(label = "bench", icon = "leaf"),
        )

        /** A trailhead voice, a waypoint at 0.3, a voice at 0.5, and one at 0.8. */
        fun way(
            moments: List<WayMoment> = listOf(voice(1, 0.0), waypoint(1, 0.003), voice(2, 0.005), voice(3, 0.008)),
            title: String = "Morning loop",
        ) = Way(
            id = WAY_ID,
            source = WaySource.OwnWalk(uuid = SOURCE_UUID),
            title = title,
            departedAt = Instant.ofEpochSecond(1_600_000_000),
            tzIdentifier = null,
            expires = null,
            route = (0..10).map { WayPoint(lat = 0.0, lon = it * 0.001, alt = null, t = it * 60.0) },
            totalDistanceMeters = 1_113.0,
            theirActiveSeconds = 600.0,
            moments = moments,
            weather = null,
        )

        fun fix(lon: Double, atMillis: Long, accuracy: Float = 5f, speed: Float? = 1.2f) = LocationPoint(
            timestamp = atMillis,
            latitude = 0.0,
            longitude = lon,
            horizontalAccuracyMeters = accuracy,
            speedMetersPerSecond = speed,
        )
    }
}

internal class MutableClock(var millis: Long) : Clock {
    override fun now(): Long = millis
}

/** Records every port call, in order, as a short line; the test ends plays itself. */
internal class FakePorts {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val listeners = Collections.synchronizedList(mutableListOf<WayVoiceListener>())
    val gateFlow = MutableStateFlow(HonorExternalGates())

    /** Runs inside [WayVoicePort.play], before the call returns: what the rows say at the first sound. */
    var onPlay: (File) -> Unit = {}

    val voice = object : WayVoicePort {
        override fun play(file: File, gain: Float, listener: WayVoiceListener) {
            onPlay(file)
            listeners += listener
            calls += "play ${file.nameWithoutExtension} $gain"
        }

        override fun playReply(file: File, listener: WayVoiceListener) {
            listeners += listener
            calls += "reply ${file.nameWithoutExtension}"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun resume() {
            calls += "resume"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun seek(fraction: Double) {
            calls += "seek $fraction"
        }

        override fun setRate(rate: Float) {
            calls += "rate $rate"
        }
    }

    val duck = object : SoundscapeDuckPort {
        override fun duckForWayVoice() {
            calls += "duck"
        }

        override fun restoreAfterWayVoice() {
            calls += "restore"
        }
    }

    val haptics = object : HonorHapticsPort {
        override fun momentReached() {
            calls += "haptic moment"
        }

        override fun softTap() {
            calls += "haptic softTap"
        }

        override fun arrival() {
            calls += "haptic arrival"
        }
    }

    val gates = object : HonorGatePort {
        override val gates: StateFlow<HonorExternalGates> = gateFlow
    }

    fun voiceCalls(): List<String> = calls.filter { !it.startsWith("haptic") }

    fun finishLatest() = listeners.last().onFinished()

    fun failLatest() = listeners.last().onFailed()

    fun clear() = calls.clear()
}
