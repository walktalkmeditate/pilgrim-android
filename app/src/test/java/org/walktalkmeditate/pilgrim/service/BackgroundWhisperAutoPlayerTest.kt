// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.proximity.FakeGeoCacheService
import org.walktalkmeditate.pilgrim.data.proximity.FakeProximityDetectionService
import org.walktalkmeditate.pilgrim.data.proximity.ProximityEvent
import org.walktalkmeditate.pilgrim.data.proximity.ProximityTarget
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.whisper.CachedWhisper
import org.walktalkmeditate.pilgrim.data.whisper.FakeWhisperManifestService
import org.walktalkmeditate.pilgrim.data.whisper.FakeWhisperPlayer
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * Unit-tests [BackgroundWhisperAutoPlayer] — the `:tracker`-side
 * whisper auto-play pipeline. Uses the project proximity/geocache/
 * manifest/player fakes; an injected clock makes the geo-cache fetch
 * throttle deterministic.
 *
 * Auto-play gating mirrors `WalkViewModel` + iOS `handleProximityEvent`:
 * fires only for `Entered` whisper targets when both the per-practice
 * `autoPlayWhisperOnProximity` pref and the master `soundsEnabled`
 * toggle are on, and only when the encountered id resolves to a cached
 * whisper with a known category.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackgroundWhisperAutoPlayerTest {

    private val proximity = FakeProximityDetectionService()
    private val geoCache = FakeGeoCacheService()
    private val manifest = FakeWhisperManifestService()
    private val player = FakeWhisperPlayer()

    // Extension on TestScope so the session collectors run on a dispatcher
    // tied to this test's scheduler (production uses Dispatchers.Default).
    private fun TestScope.autoPlayer(
        practice: FakePracticePreferencesRepository = FakePracticePreferencesRepository(),
        sounds: FakeSoundsPreferencesRepository = FakeSoundsPreferencesRepository(),
        clock: () -> Long = { 0L },
    ) = BackgroundWhisperAutoPlayer(
        geoCacheService = geoCache,
        proximityService = proximity,
        whisperManifestService = manifest,
        whisperPlayer = player,
        practicePreferences = practice,
        soundsPreferences = sounds,
        currentTimeMillis = clock,
        sessionDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    private fun whisperEntered(cacheId: String) = ProximityEvent(
        target = ProximityTarget(
            id = ProximityTarget.whisperId(cacheId),
            latitude = 37.0,
            longitude = -122.0,
            radius = 42.0,
            type = ProximityTarget.Type.Whisper,
        ),
        distanceMeters = 10.0,
        direction = ProximityEvent.Direction.Entered,
    )

    private fun cachedWhisper(id: String, category: String = "presence") = CachedWhisper(
        id = id,
        latitude = 37.0,
        longitude = -122.0,
        whisperId = "ws-$id",
        category = category,
        expiresAt = "2099-01-01T00:00:00Z",
    )

    @Test
    fun `entered whisper with prefs on plays the resolved whisper`() = runTest(UnconfinedTestDispatcher()) {
        geoCache.setWhispers(listOf(cachedWhisper("w1")))
        val sut = autoPlayer()
        sut.start(this, emptyFlow())

        proximity.emit(whisperEntered("w1"))

        assertEquals(1, player.playCalls)
        sut.stop()
    }

    @Test
    fun `auto-play pref off suppresses play`() = runTest(UnconfinedTestDispatcher()) {
        geoCache.setWhispers(listOf(cachedWhisper("w1")))
        val sut = autoPlayer(
            practice = FakePracticePreferencesRepository(initialAutoPlayWhisperOnProximity = false),
        )
        sut.start(this, emptyFlow())

        proximity.emit(whisperEntered("w1"))

        assertEquals(0, player.playCalls)
        sut.stop()
    }

    @Test
    fun `sounds disabled suppresses play`() = runTest(UnconfinedTestDispatcher()) {
        geoCache.setWhispers(listOf(cachedWhisper("w1")))
        val sut = autoPlayer(
            sounds = FakeSoundsPreferencesRepository(initialSoundsEnabled = false),
        )
        sut.start(this, emptyFlow())

        proximity.emit(whisperEntered("w1"))

        assertEquals(0, player.playCalls)
        sut.stop()
    }

    @Test
    fun `cairn target is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val sut = autoPlayer()
        sut.start(this, emptyFlow())

        proximity.emit(
            ProximityEvent(
                target = ProximityTarget(
                    id = ProximityTarget.cairnId("c1"),
                    latitude = 37.0,
                    longitude = -122.0,
                    radius = 42.0,
                    type = ProximityTarget.Type.Cairn,
                ),
                distanceMeters = 10.0,
                direction = ProximityEvent.Direction.Entered,
            ),
        )

        assertEquals(0, player.playCalls)
        sut.stop()
    }

    @Test
    fun `unknown cache id is ignored`() = runTest(UnconfinedTestDispatcher()) {
        // No cached whisper seeded for this id.
        val sut = autoPlayer()
        sut.start(this, emptyFlow())

        proximity.emit(whisperEntered("missing"))

        assertEquals(0, player.playCalls)
        sut.stop()
    }

    @Test
    fun `exited event does not play`() = runTest(UnconfinedTestDispatcher()) {
        geoCache.setWhispers(listOf(cachedWhisper("w1")))
        val sut = autoPlayer()
        sut.start(this, emptyFlow())

        proximity.emit(whisperEntered("w1").copy(direction = ProximityEvent.Direction.Exited))

        assertEquals(0, player.playCalls)
        sut.stop()
    }

    @Test
    fun `start is idempotent — re-wiring does not double-play`() = runTest(UnconfinedTestDispatcher()) {
        geoCache.setWhispers(listOf(cachedWhisper("w1")))
        val sut = autoPlayer()
        sut.start(this, emptyFlow())
        // A second start must tear down the prior collectors so a single
        // event isn't handled twice (START_REDELIVER_INTENT re-entry).
        sut.start(this, emptyFlow())

        proximity.emit(whisperEntered("w1"))

        assertEquals(1, player.playCalls)
        sut.stop()
    }

    @Test
    fun `stop stops the detector and clears the geo-cache fetch marker`() = runTest(UnconfinedTestDispatcher()) {
        val sut = autoPlayer()
        sut.start(this, emptyFlow())

        sut.stop()

        assertEquals(1, proximity.stopListeningCalls)
        assertEquals(1, geoCache.invalidateCalls)
    }

    @Test
    fun `geo-cache fetch honours the throttle window`() = runTest(UnconfinedTestDispatcher()) {
        var nowMs = 1_000_000L
        val walkState = MutableStateFlow<WalkState>(WalkState.Idle)
        val sut = autoPlayer(clock = { nowMs })
        sut.start(this, walkState)

        // First sample: now - 0 >= throttle → fetch.
        walkState.value = activeAt(37.0, -122.0)
        assertEquals(1, geoCache.fetchCalls)

        // Within the 5-min window → throttled.
        nowMs += 100_000L
        walkState.value = activeAt(37.1, -122.1)
        assertEquals(1, geoCache.fetchCalls)

        // Past the window → fetch again.
        nowMs += 300_000L
        walkState.value = activeAt(37.2, -122.2)
        assertEquals(2, geoCache.fetchCalls)

        sut.stop()
    }

    private fun activeAt(lat: Double, lon: Double): WalkState.Active =
        WalkState.Active(
            WalkAccumulator(
                1L,
                0L,
                lastLocation = LocationPoint(timestamp = 0L, latitude = lat, longitude = lon),
            ),
        )
}
