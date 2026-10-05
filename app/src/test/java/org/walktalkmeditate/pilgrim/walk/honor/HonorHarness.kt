// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.honor

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
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
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedgerStore
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayMark
import org.walktalkmeditate.pilgrim.domain.honor.WayMarkKind
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WayStage
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours
import org.walktalkmeditate.pilgrim.domain.honor.WayStagePlace
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
    val ledgers = PilgrimageLedgerStore(store)
    val filesRoot = File(folder, "files")
    val finalizer = HonorFinalizer(db, store, clock, Dispatchers.IO, ledgers = ledgers)
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
        sessionDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
        sessionDispatcher = sessionDispatcher,
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

        const val STAGE_ID = "pilgrimage:camino-frances:4"

        /** On-way water [offLine] metres north of the line at [lon]. */
        fun water(id: String, lon: Double, offLine: Double = 10.0) = WayMark(
            id = id,
            kind = WayMarkKind.WATER,
            name = "Fuente $id",
            at = WayCoordinate(lat = offLine / 111_320, lon = lon),
            frac = lon / END_LON,
            offLineMeters = offLine,
        )

        /**
         * A pilgrimage stage on [way]'s line, as its package installs it:
         * water at 0.3 and 0.8 and a waypoint at 0.5, and no voices.
         */
        fun stage(
            marks: List<WayMark> = listOf(water("wp-osm-water-node1", 0.003), water("wp-osm-water-node2", 0.008)),
            title: String = "Larrasoaña to Pamplona",
        ) = way(moments = listOf(waypoint(1, 0.005)), title = title).copy(
            id = STAGE_ID,
            source = WaySource.Pilgrimage(routeId = "camino-frances", stageIndex = 4),
            marks = marks,
            stage = WayStage(
                routeId = "camino-frances", index = 4, count = 33, name = title, theme = "The city",
                narrative = "The way crosses the river into Pamplona.", closing = "You walked into a city on foot.",
                warnings = emptyList(), distanceKm = 15.6, gainMeters = 210.0,
                hours = WayStageHours(min = 4.0, max = 5.0), difficulty = "moderate",
                start = WayStagePlace(name = "Larrasoaña", at = WayCoordinate(lat = 0.0, lon = 0.0)),
                end = WayStagePlace(name = "Pamplona", at = WayCoordinate(lat = 0.0, lon = END_LON)),
            ),
        )

        /**
         * iOS `PilgrimageStageWalkTests.stageWay(index:)`: a 1 km stage east
         * along the equator, so every distance is arithmetic (0.000898° of
         * longitude is 100 m), with one waypoint at 0.3 that carries words,
         * names, a sitting and a pin; 24.2 km of the Camino Francés on paper.
         */
        fun stageWay(index: Int = 0, departedAt: Instant = Instant.ofEpochSecond(1_000_000)): Way {
            val orisson = WayMoment(
                id = "wp-orisson",
                frac = 0.3,
                at = WayCoordinate(lat = 0.0, lon = 300.0 / 111_320),
                kind = WayMomentKind.Waypoint(label = "Vierge d'Orisson", icon = "building.columns"),
                text = "A shepherd carried this Madonna up from Lourdes.",
                names = mapOf("eu" to "Orissongo Ama Birjina", "fr" to "Vierge d'Orisson"),
                sitMinutes = 5,
                pin = WayCoordinate(lat = 0.0002, lon = 300.0 / 111_320),
            )
            return Way(
                id = WayStore.stageWayId("camino-frances", index),
                source = WaySource.Pilgrimage(routeId = "camino-frances", stageIndex = index),
                title = "Saint-Jean-Pied-de-Port to Roncesvalles",
                departedAt = departedAt,
                tzIdentifier = "Europe/Madrid",
                expires = null,
                route = (0..10).map { WayPoint(lat = 0.0, lon = it * 0.000898, alt = null, t = it * 60.0) },
                totalDistanceMeters = 1000.0,
                theirActiveSeconds = 600.0,
                moments = listOf(orisson),
                weather = null,
                marks = emptyList(),
                stage = WayStage(
                    routeId = "camino-frances", index = index, count = 33,
                    name = "Saint-Jean-Pied-de-Port to Roncesvalles", theme = "Initiation",
                    narrative = "The Pyrenees are the first question the way asks.",
                    closing = "You crossed a border on foot.",
                    warnings = listOf("The Napoleon Route closes in winter."),
                    distanceKm = 24.2, gainMeters = 1419.0, hours = WayStageHours(min = 7.0, max = 9.0), difficulty = "hard",
                    start = WayStagePlace(name = "Saint-Jean-Pied-de-Port", at = WayCoordinate(lat = 0.0, lon = 0.0)),
                    end = WayStagePlace(name = "Roncesvalles", at = WayCoordinate(lat = 0.0, lon = 0.00898)),
                ),
            )
        }

        fun fix(
            lon: Double,
            atMillis: Long,
            accuracy: Float = 5f,
            speed: Float? = 1.2f,
            lat: Double = 0.0,
        ) = LocationPoint(
            timestamp = atMillis,
            latitude = lat,
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

    /** Runs inside [HonorHapticsPort.waterAhead]: what the rows say as the water haptic plays. */
    var onWaterAhead: () -> Unit = {}

    /** Runs as each haptic plays, with the name its call line carries ("water", "arrival", …). */
    var onHaptic: (String) -> Unit = {}

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
        override fun momentReached() = haptic("moment")

        override fun softTap() = haptic("softTap")

        override fun waterAhead() {
            onWaterAhead()
            haptic("water")
        }

        override fun arrival() = haptic("arrival")

        override val arrivalMillis = ARRIVAL_MILLIS
    }

    private fun haptic(name: String) {
        onHaptic(name)
        calls += "haptic $name"
    }

    val gates = object : HonorGatePort {
        override val gates: StateFlow<HonorExternalGates> = gateFlow
    }

    fun voiceCalls(): List<String> = calls.filter { !it.startsWith("haptic") }

    fun finishLatest() = listeners.last().onFinished()

    fun failLatest(atHandOff: Boolean = false) = listeners.last().onFailed(atHandOff)

    /** The headphones going, as the arbiter passes on the player's pause. */
    fun pauseLatestForRoute() = listeners.last().onPausedForRoute()

    /** What the arbiter says of the voice handed over: [held] silent behind a prompt or a call. */
    fun holdVoice(held: Boolean) {
        gateFlow.value = gateFlow.value.copy(wayVoiceHeld = held)
    }

    fun clear() = calls.clear()

    companion object {
        /** The fake arrival's length, as long as the real one's. */
        const val ARRIVAL_MILLIS = 370L
    }
}
