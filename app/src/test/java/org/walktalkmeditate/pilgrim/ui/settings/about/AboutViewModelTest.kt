// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import app.cash.turbine.test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AboutViewModelTest {

    // Reviewer-flagged: the 3 icon-variant tests call VM methods that
    // `viewModelScope.launch(Dispatchers.IO)`. Without `setMain`,
    // `viewModelScope` would dispatch through the real Android Main
    // Looper that `runTest` cannot drain; the IO continuations would
    // be wall-clock-dependent and could flake on slow CI runners.
    // Pattern matches `SettingsViewModelTest`,
    // `SoundSettingsViewModelTest`, and every other settings VM test.
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `no walks yields hasWalks=false`() = runTest {
        val source = FakeWalkSource(flowOf(emptyList()))
        val vm = AboutViewModel(source, FakeUnits(), FakeIconSwitcher())

        vm.stats.test(timeout = 10.seconds) {
            var current = awaitItem()
            while (current.hasWalks) current = awaitItem()
            assertFalse(current.hasWalks)
            assertEquals(0, current.walkCount)
            assertEquals(0.0, current.totalDistanceMeters, 0.0)
            assertNull(current.firstWalkInstant)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multiple walks aggregate from cache cols`() = runTest {
        val walks = listOf(
            walk(id = 1, start = 1_000, distanceMeters = 1500.0),
            walk(id = 2, start = 5_000, distanceMeters = 2200.0),
        )
        val source = FakeWalkSource(flowOf(walks))
        val vm = AboutViewModel(source, FakeUnits(), FakeIconSwitcher())

        vm.stats.test(timeout = 10.seconds) {
            var current = awaitItem()
            while (current.walkCount != 2) current = awaitItem()
            assertEquals(2, current.walkCount)
            assertEquals(3700.0, current.totalDistanceMeters, 0.001)
            assertEquals(Instant.ofEpochMilli(1_000), current.firstWalkInstant)
            assertTrue(current.hasWalks)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unfinished walks are excluded from stats`() = runTest {
        val walks = listOf(
            Walk(id = 1, startTimestamp = 1_000, endTimestamp = 2_000, distanceMeters = 500.0),
            Walk(id = 2, startTimestamp = 5_000, endTimestamp = null, distanceMeters = 999.0),
        )
        val source = FakeWalkSource(flowOf(walks))
        val vm = AboutViewModel(source, FakeUnits(), FakeIconSwitcher())

        vm.stats.test(timeout = 10.seconds) {
            var current = awaitItem()
            while (current.walkCount != 1) current = awaitItem()
            assertEquals(1, current.walkCount)
            assertEquals(500.0, current.totalDistanceMeters, 0.001)
            assertEquals(Instant.ofEpochMilli(1_000), current.firstWalkInstant)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `null cache cols sum to zero (no per-walk scan)`() = runTest {
        // The AboutWalkSource seam no longer exposes per-walk readers,
        // so a regression that re-introduces the N+1 scan would fail to
        // compile. This test guards the value semantics: a `null`
        // distance cache col contributes 0 to the running sum without
        // any fallback recomputation.
        val walks = listOf(
            Walk(id = 1, startTimestamp = 1_000, endTimestamp = 2_000, distanceMeters = null),
            Walk(id = 2, startTimestamp = 5_000, endTimestamp = 6_000, distanceMeters = 1234.0),
        )
        val source = FakeWalkSource(flowOf(walks))
        val vm = AboutViewModel(source, FakeUnits(), FakeIconSwitcher())

        vm.stats.test(timeout = 10.seconds) {
            var current = awaitItem()
            while (current.walkCount != 2) current = awaitItem()
            assertEquals(2, current.walkCount)
            assertEquals(1234.0, current.totalDistanceMeters, 0.001)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setIconVariant happy path updates iconVariant`() = runTest {
        val source = FakeWalkSource(flowOf(emptyList()))
        val fake = RecordingIconSwitcher()
        val vm = AboutViewModel(source, FakeUnits(), fake)

        vm.setIconVariant(org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Sage)

        vm.iconVariant.test(timeout = 5.seconds) {
            var current = awaitItem()
            while (current != org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Sage) {
                current = awaitItem()
            }
            assertEquals(
                org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Sage,
                current,
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Sage,
            fake.lastSwitchTo,
        )
    }

    @Test
    fun `setIconVariant restarts launcher for OEM refresh when no walk active`() = runTest {
        val source = FakeWalkSource(flowOf(emptyList()), walkActive = false)
        val fake = RecordingIconSwitcher()
        val vm = AboutViewModel(source, FakeUnits(), fake)

        vm.setIconVariant(org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Sage)

        vm.iconVariant.test(timeout = 5.seconds) {
            var current = awaitItem()
            while (current != org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Sage) {
                current = awaitItem()
            }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Sage,
            fake.lastSwitchTo,
        )
        assertEquals("launcher restart fired once", 1, fake.restartCount)
    }

    @Test
    fun `setIconVariant skips launcher restart while a walk is active`() = runTest {
        val source = FakeWalkSource(flowOf(emptyList()), walkActive = true)
        val fake = RecordingIconSwitcher()
        val vm = AboutViewModel(source, FakeUnits(), fake)

        vm.setIconVariant(org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Sage)

        vm.iconVariant.test(timeout = 5.seconds) {
            var current = awaitItem()
            while (current != org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Sage) {
                current = awaitItem()
            }
            cancelAndIgnoreRemainingEvents()
        }
        // Switch still applied; restart suppressed so the active walk's
        // foreground tracking service is not torn down.
        assertEquals(
            org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Sage,
            fake.lastSwitchTo,
        )
        assertEquals("restart suppressed mid-walk", 0, fake.restartCount)
    }

    @Test
    fun `refreshIconVariant re-reads currentVariant and updates flow`() = runTest {
        val source = FakeWalkSource(flowOf(emptyList()))
        // Returns Default on VM init, then Sage on every subsequent
        // call — refreshIconVariant must reach the Sage emission.
        val fake = SteppedIconSwitcher(
            org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Default,
            org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Sage,
        )
        val vm = AboutViewModel(source, FakeUnits(), fake)

        vm.refreshIconVariant()

        vm.iconVariant.test(timeout = 5.seconds) {
            var current = awaitItem()
            while (current != org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Sage) {
                current = awaitItem()
            }
            assertEquals(
                org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Sage,
                current,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setIconVariant catch path re-syncs from currentVariant on throw`() = runTest {
        val source = FakeWalkSource(flowOf(emptyList()))
        val fake = ThrowingIconSwitcher()
        val vm = AboutViewModel(source, FakeUnits(), fake)

        vm.setIconVariant(org.walktalkmeditate.pilgrim.data.launcher.IconVariant.River)

        vm.iconVariant.test(timeout = 5.seconds) {
            // Initial value is Default; ThrowingIconSwitcher.switchTo throws;
            // catch re-reads currentVariant() which returns Dark.
            var current = awaitItem()
            while (current != org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Dark) {
                current = awaitItem()
            }
            assertEquals(
                org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Dark,
                current,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun walk(id: Long, start: Long, distanceMeters: Double) = Walk(
        id = id,
        startTimestamp = start,
        endTimestamp = start + 60_000,
        distanceMeters = distanceMeters,
    )
}

private class FakeWalkSource(
    private val flow: Flow<List<Walk>>,
    private val walkActive: Boolean = false,
) : AboutWalkSource {
    override fun observeAllWalks(): Flow<List<Walk>> = flow
    override fun isWalkActive(): Boolean = walkActive
}

private class FakeUnits : UnitsPreferencesRepository {
    private val _distanceUnits = MutableStateFlow(UnitSystem.Metric)
    override val distanceUnits = _distanceUnits
    override suspend fun setDistanceUnits(value: UnitSystem) {
        _distanceUnits.value = value
    }
}

private class FakeIconSwitcher : org.walktalkmeditate.pilgrim.data.launcher.IconSwitcher(
    context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
) {
    override fun currentVariant() =
        org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Default
    override fun switchTo(target: org.walktalkmeditate.pilgrim.data.launcher.IconVariant) = Unit
}

private class RecordingIconSwitcher :
    org.walktalkmeditate.pilgrim.data.launcher.IconSwitcher(
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
    ) {
    var lastSwitchTo: org.walktalkmeditate.pilgrim.data.launcher.IconVariant? = null
    var restartCount = 0
    override fun currentVariant() =
        org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Default
    override fun switchTo(target: org.walktalkmeditate.pilgrim.data.launcher.IconVariant) {
        lastSwitchTo = target
    }
    override fun restartForLauncherIconRefresh() {
        restartCount++
    }
}

private class SteppedIconSwitcher(
    private val firstCall: org.walktalkmeditate.pilgrim.data.launcher.IconVariant,
    private val subsequentCalls: org.walktalkmeditate.pilgrim.data.launcher.IconVariant,
) : org.walktalkmeditate.pilgrim.data.launcher.IconSwitcher(
    context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
) {
    private var callCount = 0
    override fun currentVariant() =
        if (callCount++ == 0) firstCall else subsequentCalls
    override fun switchTo(target: org.walktalkmeditate.pilgrim.data.launcher.IconVariant) = Unit
}

private class ThrowingIconSwitcher :
    org.walktalkmeditate.pilgrim.data.launcher.IconSwitcher(
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
    ) {
    private var callCount = 0
    // Reviewer-flagged: a single-value `currentVariant()` would make
    // the catch-path test pass for the wrong reason — VM init reads
    // the same value the catch re-sync reads, so the StateFlow emits
    // nothing and the test observes only the initial value.
    // Returning Default on the first call (VM init) and Dark on
    // every subsequent call (catch re-sync) forces an observable
    // Default → Dark transition that's only reachable if the catch
    // block executed.
    override fun currentVariant() =
        if (callCount++ == 0) {
            org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Default
        } else {
            org.walktalkmeditate.pilgrim.data.launcher.IconVariant.Dark
        }
    override fun switchTo(target: org.walktalkmeditate.pilgrim.data.launcher.IconVariant) {
        throw SecurityException("ROM blocked alias toggle")
    }
}
