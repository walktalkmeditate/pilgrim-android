// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition
import org.walktalkmeditate.pilgrim.data.weather.WeatherSnapshot
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WayStage
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours
import org.walktalkmeditate.pilgrim.domain.honor.WayStagePlace
import org.walktalkmeditate.pilgrim.honor.HonorWayChoice
import org.walktalkmeditate.pilgrim.ui.honor.HonorOverview
import org.walktalkmeditate.pilgrim.ui.honor.HonorOverviewCard
import org.walktalkmeditate.pilgrim.ui.honor.HonorOverviewModel
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme
import org.walktalkmeditate.pilgrim.ui.walk.HonorWalkUiState
import org.walktalkmeditate.pilgrim.ui.walk.StageDaySheet
import org.walktalkmeditate.pilgrim.ui.walk.map.HonorWayLine
import org.walktalkmeditate.pilgrim.ui.walk.map.wayPins

/**
 * A stage's morning card (pilgrimage-stage spec P4 §6.1–§6.2, §7; P5 §10):
 * its lines, its warnings and their spoken triangle, the weather line's
 * forms, the button's face and label, and the two ways it opens: the
 * overview's Begin, where "walk" is what begins and a swipe down leaves
 * the overview as it was, and the walk's "the day", where "close" only
 * closes it and a stage that no longer loads shows nothing. Then the maps
 * line (offline-maps spec D C4 §2): iOS's line test from
 * `PilgrimageMapsRowTests.swift`, its place after the weather, and "the
 * day" asking for its read once its card is up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp")
class StageMorningCardTest {

    @get:Rule val composeRule = createComposeRule()

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun showCard(
        stage: WayStage = stage(),
        weather: WeatherSnapshot? = null,
        units: UnitSystem = UnitSystem.Metric,
        mapsLine: String? = null,
        action: StageMorningCardAction = StageMorningCardAction.WALK,
        onAction: () -> Unit = {},
    ) = show {
        StageMorningCardContent(
            stage = stage,
            weather = weather,
            units = units,
            mapsLine = mapsLine,
            action = action,
            onAction = onAction,
        )
    }

    // ---- The card's lines (P4 §7.1–§7.2) ---------------------------------

    @Test
    fun `the card reads the stage's theme, narrative and facts, in the dataset's words`() {
        showCard()

        composeRule.onNodeWithText("Initiation").assertIsDisplayed()
        composeRule.onNodeWithText("The Pyrenees are the first question the way asks.").assertIsDisplayed()
        composeRule.onNodeWithText("24.2 km · 1,419 m up · 7 to 9 hours · hard").assertIsDisplayed()
    }

    @Test
    fun `each warning is its own row behind a triangle read by its symbol's name`() {
        showCard(stage = stage(warnings = listOf("The Napoleon Route closes in winter.", "No water for 17 km.")))

        composeRule.onAllNodesWithContentDescription("exclamationmark triangle").assertCountEquals(2)
        composeRule.onNodeWithText("The Napoleon Route closes in winter.").assertIsDisplayed()
        composeRule.onNodeWithText("No water for 17 km.").assertIsDisplayed()
    }

    @Test
    fun `a stage with no warnings draws no triangle`() {
        showCard(stage = stage(warnings = emptyList()))

        composeRule.onAllNodesWithContentDescription("exclamationmark triangle").assertCountEquals(0)
    }

    @Test
    fun `the weather line is the lower-cased condition and the temperature in the walker's unit`() {
        showCard(weather = snapshot(WeatherCondition.CLEAR, 9.0))

        composeRule.onNodeWithText("clear, 9°C").assertIsDisplayed()
    }

    @Test
    fun `a miles walker reads Fahrenheit, and the facts in miles and feet`() {
        showCard(weather = snapshot(WeatherCondition.PARTLY_CLOUDY, 9.0), units = UnitSystem.Imperial)

        composeRule.onNodeWithText("partly cloudy, 48°F").assertIsDisplayed()
        composeRule.onNodeWithText("15.04 mi · 4,655.51 ft up · 7 to 9 hours · hard").assertIsDisplayed()
    }

    @Test
    fun `fog reads foggy, as iOS's label does`() {
        assertEquals(
            "foggy, 12°C",
            StageMorningCardModel.weatherLine(resources, snapshot(WeatherCondition.FOG, 12.0), UnitSystem.Metric, Locale.US),
        )
    }

    @Test
    fun `without a snapshot there is no weather line at all`() {
        showCard(weather = null)

        composeRule.onAllNodesWithText("°", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("weather", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    // ---- The maps line (offline-maps spec D C4 §2.1) ----------------------

    // iOS `PilgrimageMapsRowTests.testTheMorningCardSaysWhetherTodayIsSaved`.
    @Test
    fun `the morning card says whether today is saved`() {
        assertEquals("maps saved for today", StageMorningCardModel.mapsLine(resources, saved = true))
        assertEquals("no offline maps for today \u2014 save on wifi", StageMorningCardModel.mapsLine(resources, saved = false))
    }

    @Test
    fun `the maps line is the scroll's last line, after the weather, and the button stays under it`() {
        showCard(weather = snapshot(WeatherCondition.CLEAR, 9.0), mapsLine = StageMorningCardModel.mapsLine(resources, saved = true))

        val weather = composeRule.onNodeWithText("clear, 9°C").fetchSemanticsNode().boundsInRoot
        val maps = composeRule.onNodeWithText("maps saved for today").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val button = composeRule.onNodeWithContentDescription("Begin walking this stage").fetchSemanticsNode().boundsInRoot
        assertTrue("after the weather line", maps.top >= weather.bottom)
        assertTrue("above the pinned button", maps.bottom <= button.top)
    }

    @Test
    fun `a stage's warnings and no weather still put the maps line last`() {
        showCard(weather = null, mapsLine = StageMorningCardModel.mapsLine(resources, saved = false))

        val warning = composeRule.onNodeWithText("The Napoleon Route closes in winter.").fetchSemanticsNode().boundsInRoot
        val maps = composeRule.onNodeWithText("no offline maps for today — save on wifi").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(maps.top >= warning.bottom)
    }

    @Test
    fun `no maps line draws nothing in its place`() {
        showCard(mapsLine = null)

        composeRule.onAllNodesWithText("maps", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the overview's card reads the overview's saved flag`() {
        show { StageOverviewCard(overview = stageOverview().copy(mapsSaved = false)) }
        composeRule.onNodeWithContentDescription("Walk this stage").performClick()

        composeRule.onNodeWithText("no offline maps for today — save on wifi").assertIsDisplayed()
    }

    @Test
    fun `the overview's card has no maps line while its read is pending`() {
        show { StageOverviewCard(overview = stageOverview().copy(mapsSaved = null)) }
        composeRule.onNodeWithContentDescription("Walk this stage").performClick()

        composeRule.onNodeWithText("Initiation").assertIsDisplayed()
        composeRule.onAllNodesWithText("maps", substring = true).assertCountEquals(0)
    }

    // ---- The button (P4 §7.2, correction 13) -----------------------------

    @Test
    fun `walk is read Begin walking this stage, never its face`() {
        var acted = 0
        showCard(action = StageMorningCardAction.WALK, onAction = { acted++ })

        composeRule.onNodeWithContentDescription("Begin walking this stage")
            .assert(isButton())
            .assertHasClickAction()
            .performClick()
        composeRule.onAllNodesWithText("walk").assertCountEquals(0)
        composeRule.onAllNodesWithText("walk", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(1, acted)
    }

    @Test
    fun `close is read Close the day's words, with an ASCII apostrophe`() {
        showCard(action = StageMorningCardAction.CLOSE)

        composeRule.onNodeWithContentDescription("Close the day's words").assert(isButton()).assertHasClickAction()
        composeRule.onAllNodesWithText("close", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the two faces are iOS's titles`() {
        assertEquals("walk", resources.getString(StageMorningCardAction.WALK.title))
        assertEquals("close", resources.getString(StageMorningCardAction.CLOSE.title))
    }

    // ---- From the overview's Begin (P4 §6.1–§6.2) ------------------------

    @Test
    fun `a stage's overview reads its stage line, has no voice to walk with, and Begin is read Walk this stage`() {
        show { StageOverviewCard(overview = stageOverview(offlineNote = R.string.honor_overview_offline_note)) }

        composeRule.onNodeWithText("stage 1 of 33 · 24.2 km · hard").assertIsDisplayed()
        composeRule.onNodeWithText("map tiles need a connection; the way itself is on your phone.").assertIsDisplayed()
        composeRule.onAllNodesWithText("walk with their voice").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Walk this stage").assert(isButton())
        composeRule.onAllNodesWithContentDescription("Begin honoring this way").assertCountEquals(0)
    }

    // pilgrim-ios #122 item 6, matched: the build's clock, no counts, and the line's own length.
    @Test
    fun `a stage's stats row shows the synthesized clock and a quiet way beside its line's own length`() {
        show { StageOverviewCard(overview = stageOverview()) }

        composeRule.onNodeWithText("8h 0m").assertIsDisplayed()
        composeRule.onNodeWithText("a quiet way").assertIsDisplayed()
        composeRule.onNodeWithText("23.83 km").assertIsDisplayed()
    }

    @Test
    fun `Begin on a stage opens the morning card and begins nothing`() {
        var begun = 0
        show { StageOverviewCard(overview = stageOverview(), onBegin = { begun++ }) }

        composeRule.onNodeWithContentDescription("Walk this stage").performClick()

        composeRule.onNodeWithText("Initiation").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Begin walking this stage").assertIsDisplayed()
        assertEquals(0, begun)
    }

    @Test
    fun `walk slides the card away, then begins`() {
        var begun = 0
        show { StageOverviewCard(overview = stageOverview(), onBegin = { begun++ }) }
        composeRule.onNodeWithContentDescription("Walk this stage").performClick()

        composeRule.onNodeWithContentDescription("Begin walking this stage").performClick()
        composeRule.waitForIdle()

        assertEquals(1, begun)
        composeRule.onAllNodesWithText("Initiation").assertCountEquals(0)
    }

    @Test
    fun `a swipe down closes the card and leaves the overview as it was`() {
        var begun = 0
        show { StageOverviewCard(overview = stageOverview(), onBegin = { begun++ }) }
        composeRule.onNodeWithContentDescription("Walk this stage").performClick()
        composeRule.onNodeWithText("Initiation").assertIsDisplayed()

        composeRule.onNodeWithText("Initiation").performTouchInput { swipeDown(endY = bottom + 1_500f) }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Initiation").assertCountEquals(0)
        composeRule.onNodeWithText("Saint-Jean-Pied-de-Port to Roncesvalles").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Walk this stage").assertIsDisplayed()
        assertEquals(0, begun)
    }

    // P4 A-3: iOS restores nothing; Android keeps the card open across a recreation.
    @Test
    fun `the morning card opened from Begin is open again after the activity is recreated`() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            PilgrimTheme {
                Box(Modifier.fillMaxSize()) { StageOverviewCard(overview = stageOverview()) }
            }
        }
        composeRule.onNodeWithContentDescription("Walk this stage").performClick()
        composeRule.onNodeWithText("Initiation").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Initiation").assertIsDisplayed()
    }

    @Test
    fun `the morning card reads the overview's own weather`() {
        show {
            StageOverviewCard(overview = stageOverview(todayWeather = snapshot(WeatherCondition.LIGHT_RAIN, 11.6)))
        }
        composeRule.onNodeWithContentDescription("Walk this stage").performClick()

        composeRule.onNodeWithText("light rain, 12°C").assertIsDisplayed()
    }

    // ---- "the day" (P5 §10, gap 12) --------------------------------------

    @Test
    fun `the day shows the walk's own stage and weather, and close only closes`() {
        var closes = 0
        show {
            StageDaySheet(
                honor = walkScreen(stageWay()),
                weather = snapshot(WeatherCondition.OVERCAST, 7.0),
                units = UnitSystem.Metric,
                mapsLine = "maps saved for today",
                onShown = {},
                onClose = { closes++ },
            )
        }

        composeRule.onNodeWithText("Initiation").assertIsDisplayed()
        composeRule.onNodeWithText("overcast, 7°C").assertIsDisplayed()
        composeRule.onNodeWithText("maps saved for today").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close the day's words").performClick()
        composeRule.waitForIdle()

        assertEquals(1, closes)
    }

    @Test
    fun `the day on a Way that loads with no stage closes and shows nothing`() {
        var closes = 0
        show {
            StageDaySheet(
                honor = walkScreen(stageWay().copy(stage = null)),
                weather = null,
                units = UnitSystem.Metric,
                mapsLine = null,
                onShown = {},
                onClose = { closes++ },
            )
        }
        composeRule.waitForIdle()

        assertEquals(1, closes)
        composeRule.onAllNodesWithContentDescription("Close the day's words").assertCountEquals(0)
    }

    @Test
    fun `the day restored before its Way has loaded shows nothing yet and stays open`() {
        var closed = false
        var shown = 0
        show {
            StageDaySheet(
                honor = null,
                weather = null,
                units = UnitSystem.Metric,
                mapsLine = null,
                onShown = { shown++ },
                onClose = { closed = true },
            )
        }
        composeRule.waitForIdle()

        assertFalse(closed)
        composeRule.onAllNodesWithText("Initiation").assertCountEquals(0)
        assertEquals("no card on screen asks for no read", 0, shown)
    }

    // Spec D C4 §2.3, A5: a sheet restored open had no tap, so it asks for its read once its card is up.
    @Test
    fun `the day says it is on screen once its card shows, and once only, its Way landing after a restore included`() {
        var shown = 0
        var honor by mutableStateOf<HonorWalkUiState?>(null)
        show {
            StageDaySheet(
                honor = honor,
                weather = null,
                units = UnitSystem.Metric,
                mapsLine = null,
                onShown = { shown++ },
                onClose = {},
            )
        }
        composeRule.waitForIdle()
        assertEquals(0, shown)

        honor = walkScreen(stageWay())
        composeRule.waitForIdle()
        honor = walkScreen(stageWay().copy(title = "the session's copy"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Initiation").assertIsDisplayed()
        assertEquals(1, shown)
    }

    @Test
    fun `the day's maps line is the one it is given, and none draws nothing`() {
        var line by mutableStateOf<String?>(null)
        show {
            StageDaySheet(honor = walkScreen(stageWay()), weather = null, units = UnitSystem.Metric, mapsLine = line, onShown = {}, onClose = {})
        }
        composeRule.onNodeWithText("Initiation").assertIsDisplayed()
        composeRule.onAllNodesWithText("maps", substring = true).assertCountEquals(0)

        line = "no offline maps for today — save on wifi"

        composeRule.onNodeWithText("no offline maps for today — save on wifi").assertIsDisplayed()
    }

    @Composable
    private fun StageOverviewCard(overview: HonorOverview, onBegin: () -> Unit = {}) {
        HonorOverviewCard(
            overview = overview,
            units = UnitSystem.Metric,
            voicesEnabled = true,
            onVoicesEnabledChange = {},
            onBegin = onBegin,
        )
    }

    private fun stageOverview(todayWeather: WeatherSnapshot? = null, offlineNote: Int? = null): HonorOverview {
        val way = stageWay()
        return HonorOverview(
            choice = HonorWayChoice.Stored(way.id),
            way = way,
            line = HonorWayLine.of(way),
            pins = wayPins(way, emptySet()),
            bounds = HonorOverviewModel.bounds(way),
            playableVoices = emptyMap(),
            photoUris = emptyMap(),
            todayWeather = todayWeather,
            offlineNote = offlineNote,
        )
    }

    private fun walkScreen(way: Way) =
        HonorWalkUiState(way = way, line = HonorWayLine.of(way), pins = wayPins(way, emptySet()), session = null)

    private fun snapshot(condition: WeatherCondition, celsius: Double) =
        WeatherSnapshot(condition, temperatureCelsius = celsius, humidityFraction = 0.4, windSpeedMps = 1.0)

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun stage(warnings: List<String> = listOf("The Napoleon Route closes in winter.")) = WayStage(
        routeId = "camino-frances", index = 0, count = 33,
        name = "Saint-Jean-Pied-de-Port to Roncesvalles", theme = "Initiation",
        narrative = "The Pyrenees are the first question the way asks.",
        closing = "You crossed a border on foot.",
        warnings = warnings,
        distanceKm = 24.2, gainMeters = 1419.0, hours = WayStageHours(min = 7.0, max = 9.0), difficulty = "hard",
        start = WayStagePlace(name = "Saint-Jean-Pied-de-Port", at = WayCoordinate(lat = 0.0, lon = 0.0)),
        end = WayStagePlace(name = "Roncesvalles", at = WayCoordinate(lat = 0.0, lon = 0.2142)),
    )

    /**
     * The Francés stage 1 as the importer builds it: the line's own
     * 23,825.8 m, the synthesized 8 hours (the midpoint of 7 to 9), and one
     * waypoint, so the stats row counts no one.
     */
    private fun stageWay() = Way(
        id = "pilgrimage:camino-frances:0",
        source = WaySource.Pilgrimage(routeId = "camino-frances", stageIndex = 0),
        title = "Saint-Jean-Pied-de-Port to Roncesvalles",
        departedAt = Instant.ofEpochSecond(1_000_000),
        tzIdentifier = "Europe/Madrid",
        expires = null,
        route = (0..10).map { WayPoint(lat = 0.0, lon = it * 0.02142, alt = null, t = it * 2_880.0) },
        totalDistanceMeters = 23_825.8,
        theirActiveSeconds = 28_800.0,
        moments = listOf(
            WayMoment(
                id = "wp-orisson",
                frac = 0.3,
                at = WayCoordinate(lat = 0.0, lon = 0.0643),
                kind = WayMomentKind.Waypoint(label = "Vierge d'Orisson", icon = "building.columns"),
            ),
        ),
        weather = null,
        marks = emptyList(),
        stage = stage(),
    )
}
