// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.collective.CollectiveStats
import org.walktalkmeditate.pilgrim.data.collective.routes.CollectiveRoute
import org.walktalkmeditate.pilgrim.data.collective.routes.CollectiveRouteCatalog
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * U5 render-condition tests for the day's-entry line in
 * [PracticeSummaryHeader] (parity spec
 * `docs/parity/2026-07-23-port-settings-line-u5.md`). iOS leaves these
 * conditions untested (view-body logic); Android pins them:
 *  - catalog + stats present → the day's entry renders, phrasing owned
 *    by the U2 model
 *  - stats unknown → NOTHING renders — never the beginning line (a
 *    fabricated zero would claim the path is beginning while the
 *    collective is hundreds of kilometres in)
 *  - catalog arriving after first composition re-resolves in place
 *  - unit toggle reaches the cached string without leaving the screen
 *  - screen re-entry re-resolves for the current UTC day (iOS
 *    `.onAppear` — there is no midnight timer on either platform)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PracticeSummaryHeaderDailyLineTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val fixedEpoch = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli()

    private val kumano = CollectiveRoute(
        id = "kumano-kodo",
        kind = CollectiveRoute.Kind.Route("Kumano Kodo"),
        km = 39.0,
        companyLine = "Pilgrims have walked it for a thousand years.",
    )
    private val camino = CollectiveRoute(
        id = "camino-primitivo",
        kind = CollectiveRoute.Kind.Route("Camino Primitivo"),
        km = 321.0,
        companyLine = "The oldest road to Santiago.",
    )
    private val aroundEarth = CollectiveRoute(
        id = "around-earth",
        kind = CollectiveRoute.Kind.Cosmic("around", "the Earth"),
        km = 40_075.0,
        companyLine = "No one has walked it alone.",
    )

    private val singleRouteCatalog = CollectiveRouteCatalog("v1", listOf(kumano))
    private val horizonCatalog = CollectiveRouteCatalog("v1", listOf(aroundEarth))
    private val twoRouteCatalog = CollectiveRouteCatalog("v1", listOf(kumano, camino))

    private fun stats(totalDistanceKm: Double) = CollectiveStats(
        totalWalks = 17,
        totalDistanceKm = totalDistanceKm,
        totalMeditationMin = 0,
        totalTalkMin = 0,
    )

    private fun setHeader(
        collectiveStats: CollectiveStats?,
        routeCatalog: () -> CollectiveRouteCatalog,
        distanceUnits: () -> UnitSystem = { UnitSystem.Metric },
        visible: () -> Boolean = { true },
        clock: Clock = Clock { fixedEpoch },
    ) {
        composeRule.setContent {
            PilgrimTheme {
                if (visible()) {
                    PracticeSummaryHeader(
                        walkCount = 1,
                        totalDistanceMeters = 0.0,
                        totalMeditationSeconds = 0L,
                        firstWalkInstant = null,
                        distanceUnits = distanceUnits(),
                        collectiveStats = collectiveStats,
                        routeCatalog = routeCatalog(),
                        clock = clock,
                    )
                }
            }
        }
    }

    @Test
    fun dailyLineRendersTheDaysEntryAgainstTheCollectiveTotal() {
        // 12.6 / 39 = 32.3% — pinned against the U2 model's phrasing for
        // the same inputs, hard-coded so a model regression cannot hide
        // behind a derived expectation.
        val pinned = "We are 32% of the way to one Kumano Kodo."
        setHeader(collectiveStats = stats(12.6), routeCatalog = { singleRouteCatalog })

        composeRule.onNodeWithText(pinned).assertIsDisplayed()
        // Positioned inside the collective block: the walks·distance
        // sibling iOS kept renders alongside it.
        composeRule.onNodeWithText(walksDistanceLine()).assertIsDisplayed()
    }

    @Test
    fun statsUnknownRendersNothingNotTheBeginningLine() {
        // Fresh offline install: no counter fetch has ever landed. iOS
        // renders nothing — the nil total must never be coerced to zero.
        setHeader(collectiveStats = null, routeCatalog = { singleRouteCatalog })

        composeRule.onNodeWithText("The path is beginning.").assertDoesNotExist()
        composeRule.onNodeWithText(
            singleRouteCatalog.dailyLine(fixedEpoch, 12.6, UnitSystem.Metric)!!,
        ).assertDoesNotExist()
        composeRule.onNodeWithText(walksDistanceLine()).assertDoesNotExist()
    }

    @Test
    fun lineAppearsWhenTheCatalogLoads() {
        val catalog = mutableStateOf(CollectiveRouteCatalog.EMPTY)
        val expected = singleRouteCatalog.dailyLine(fixedEpoch, 12.6, UnitSystem.Metric)!!
        setHeader(collectiveStats = stats(12.6), routeCatalog = { catalog.value })

        // Stats known, catalog still loading: the walks·distance line
        // renders, the daily line does not.
        composeRule.onNodeWithText(walksDistanceLine()).assertIsDisplayed()
        composeRule.onNodeWithText(expected).assertDoesNotExist()

        catalog.value = singleRouteCatalog
        composeRule.waitForIdle()
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun unitToggleUpdatesTheLineWithoutLeavingTheScreen() {
        // The sub-one-percent horizon branch is the only dailyLine branch
        // that carries a raw distance, so the only one a unit toggle can
        // change.
        val units = mutableStateOf(UnitSystem.Metric)
        val metricLine = "39,975 km around the Earth."
        val imperialLine = horizonCatalog.dailyLine(fixedEpoch, 100.0, UnitSystem.Imperial)!!
        assertNotEquals(metricLine, imperialLine)
        setHeader(
            collectiveStats = stats(100.0),
            routeCatalog = { horizonCatalog },
            distanceUnits = { units.value },
        )

        composeRule.onNodeWithText(metricLine).assertIsDisplayed()

        units.value = UnitSystem.Imperial
        composeRule.waitForIdle()
        composeRule.onNodeWithText(imperialLine).assertIsDisplayed()
        composeRule.onNodeWithText(metricLine).assertDoesNotExist()
    }

    @Test
    fun reentryReResolvesForTheCurrentUtcDay() {
        val dayA = fixedEpoch
        val dayB = generateSequence(dayA + DAY_MILLIS) { it + DAY_MILLIS }
            .take(30)
            .first { twoRouteCatalog.entry(it) != twoRouteCatalog.entry(dayA) }
        val lineA = twoRouteCatalog.dailyLine(dayA, 12.6, UnitSystem.Metric)!!
        val lineB = twoRouteCatalog.dailyLine(dayB, 12.6, UnitSystem.Metric)!!
        assertNotEquals(lineA, lineB)

        var now = dayA
        val visible = mutableStateOf(true)
        setHeader(
            collectiveStats = stats(12.6),
            routeCatalog = { twoRouteCatalog },
            visible = { visible.value },
            clock = Clock { now },
        )
        composeRule.onNodeWithText(lineA).assertIsDisplayed()

        // Leave Settings, cross UTC midnight, come back: iOS re-resolves
        // on `.onAppear`; the Compose analogue is the fresh composition.
        visible.value = false
        composeRule.waitForIdle()
        now = dayB
        visible.value = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText(lineB).assertIsDisplayed()
        composeRule.onNodeWithText(lineA).assertDoesNotExist()
    }

    private fun walksDistanceLine(): String =
        ApplicationProvider.getApplicationContext<Application>().resources.getQuantityString(
            R.plurals.practice_summary_walks_distance_metric,
            17,
            17,
            "13",
        )

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
