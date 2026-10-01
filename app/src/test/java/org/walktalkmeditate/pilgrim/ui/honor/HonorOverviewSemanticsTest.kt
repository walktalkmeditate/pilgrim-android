// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import java.time.Instant
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
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WayWeather
import org.walktalkmeditate.pilgrim.honor.HonorStartRefusal
import org.walktalkmeditate.pilgrim.ui.path.ModeButton
import org.walktalkmeditate.pilgrim.ui.path.PathStartButton
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme
import org.walktalkmeditate.pilgrim.ui.walk.map.HonorWayLine
import org.walktalkmeditate.pilgrim.ui.walk.map.wayPins

/** Every TalkBack label parity spec F §17 lists, on the slot, sheet, picker, door, overview, and preview. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorOverviewSemanticsTest {

    @get:Rule val composeRule = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 900.dp)) { content() }
            }
        }
    }

    // §17.1 — the Path tab slot.

    @Test
    fun `the Honor slot is a button read honor, selected, and not its visible caps`() {
        show {
            ModeButton(
                mode = WalkMode.Honor,
                selected = true,
                footprintActive = true,
                onClick = {},
                honorEnabled = true,
            )
        }

        composeRule.onNodeWithContentDescription("honor").assertIsSelected().assert(isButton())
        composeRule.onAllNodesWithText("HONOR", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `AE12 - with the flag off the slot still reads its 1_5_0 label`() {
        show {
            ModeButton(
                mode = WalkMode.Honor,
                selected = false,
                footprintActive = false,
                onClick = {},
                honorEnabled = false,
            )
        }

        composeRule.onNodeWithText("TOGETHER").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("honor").assertCountEquals(0)
    }

    @Test
    fun `the start button reads Begin your journey, never the mode`() {
        var tapped = false
        show { PathStartButton(label = "Honor", enabled = true, onClick = { tapped = true }) }

        composeRule.onNodeWithContentDescription("Begin your journey").assertHasClickAction().performClick()
        composeRule.onAllNodesWithText("Honor", useUnmergedTree = true).assertCountEquals(0)
        assertTrue(tapped)
    }

    // §17.2 — the Ways sheet.

    @Test
    fun `the Ways sheet shows iOS's own-walk section and its empty shared section`() {
        var opened = false
        show { HonorWaysSheetContent(onClose = {}, onWalkOneOfYours = { opened = true }) }

        listOf(
            "Choose a way",
            "Close",
            "Shared with you",
            "no ways yet. Accept a shared walk, or walk one of yours again.",
            "Your own walks",
        ).forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onAllNodesWithText("A pilgrimage").assertCountEquals(0)
        composeRule.onAllNodesWithText("From a shared walk").assertCountEquals(0)

        composeRule.onNodeWithText("Walk one of yours again")
            .assert(isButton())
            .assert(
                SemanticsMatcher("no click label, as iOS gives no hint") {
                    it.config[SemanticsActions.OnClick].label == null
                },
            )
            .performClick()
        assertTrue(opened)
    }

    // §17.3 — the picker.

    @Test
    fun `a picker row is one button read as its title then its distance`() {
        var picked: Long? = null
        val rows = listOf(OwnWalkPickerRow(walkId = 4L, title = "for her", distance = "1.10 km"))
        show { OwnWalkPickerContent(state = OwnWalkPickerUiState.Loaded(rows), onPick = { picked = it }, onClose = {}) }

        composeRule.onNodeWithText("Walk again").assertIsDisplayed()
        composeRule.onNodeWithText("for her").assert(isButton())
        composeRule.onNodeWithText("1.10 km").performClick()
        assertEquals(4L, picked)
    }

    @Test
    fun `the picker's empty copy shows once the list has loaded, never while it loads`() {
        show { OwnWalkPickerContent(state = OwnWalkPickerUiState.Loading, onPick = {}, onClose = {}) }
        composeRule.onAllNodesWithText(PICKER_EMPTY).assertCountEquals(0)
    }

    @Test
    fun `an empty walk list shows iOS's copy`() {
        show { OwnWalkPickerContent(state = OwnWalkPickerUiState.Loaded(emptyList()), onPick = {}, onClose = {}) }
        composeRule.onNodeWithText(PICKER_EMPTY).assertIsDisplayed()
    }

    @Test
    fun `an unwalkable walk raises iOS's alert, dismissed by OK`() {
        var dismissed = false
        show { UnwalkableAlert(onDismiss = { dismissed = true }) }

        composeRule.onNodeWithText("Can't walk this one again").assertIsDisplayed()
        composeRule.onNodeWithText(
            "This walk doesn't have enough of a route to follow. Try another.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()
        assertTrue(dismissed)
    }

    // Owner decision 5: the two Begin refusals.

    @Test
    fun `a staging failure and a vanished walk refuse Start in the decided copy`() {
        show {
            RefusalBody(HonorStartRefusal.CouldNotPrepare)
            RefusalBody(HonorStartRefusal.Gone)
        }

        composeRule.onAllNodesWithText("Can't walk this one again").assertCountEquals(2)
        composeRule.onNodeWithText("Pilgrim couldn't get this walk ready to follow. Try again.").assertIsDisplayed()
        composeRule.onNodeWithText("This walk isn't here to follow anymore. Try another.").assertIsDisplayed()
    }

    @Composable
    private fun RefusalBody(refusal: HonorStartRefusal) {
        HonorAlert(
            body = stringResource(
                when (refusal) {
                    HonorStartRefusal.CouldNotPrepare -> R.string.honor_start_refused_prepare
                    HonorStartRefusal.Gone -> R.string.honor_start_refused_gone
                },
            ),
            onDismiss = {},
        )
    }

    // §17.4 — the summary door.

    @Test
    fun `the summary door is a button read walk this again`() {
        var tapped = false
        show { WalkAgainDoor(onClick = { tapped = true }) }

        composeRule.onNodeWithText("walk this again").assertHasClickAction().performClick()
        assertTrue(tapped)
    }

    @Test
    fun `AE12 - the door needs the flag, a host that can open the overview, and two route points`() {
        assertTrue(walkAgainDoorShows(honorEnabled = true, hostOffersDoor = true, routePointCount = 2))
        assertFalse(walkAgainDoorShows(honorEnabled = false, hostOffersDoor = true, routePointCount = 200))
        assertFalse(
            "the Recordings list offers no door",
            walkAgainDoorShows(honorEnabled = true, hostOffersDoor = false, routePointCount = 200),
        )
        assertFalse(walkAgainDoorShows(honorEnabled = true, hostOffersDoor = true, routePointCount = 1))
    }

    // §17.5 — the overview.

    @Test
    fun `Begin reads Begin honoring this way, and the toggle walk with their voice with its state`() {
        var begun = false
        var toggledTo: Boolean? = null
        show {
            HonorOverviewCard(
                overview = overview(voices = 1, distanceToStart = 40.0),
                units = UnitSystem.Metric,
                voicesEnabled = true,
                onVoicesEnabledChange = { toggledTo = it },
                onBegin = { begun = true },
            )
        }

        composeRule.onNodeWithContentDescription("Begin honoring this way").assertHasClickAction().performClick()
        composeRule.onAllNodesWithText("Begin", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithText("walk with their voice").assertIsToggleable().assertIsOn().performClick()
        composeRule.onNodeWithText("you're on the way").assertIsDisplayed()
        assertTrue(begun)
        assertEquals(false, toggledTo)
    }

    @Test
    fun `a quiet way disables the toggle but keeps its stored value`() {
        show {
            HonorOverviewCard(
                overview = overview(voices = 0),
                units = UnitSystem.Metric,
                voicesEnabled = true,
                onVoicesEnabledChange = {},
                onBegin = {},
            )
        }

        composeRule.onNodeWithText("a quiet way").assertIsDisplayed()
        composeRule.onNodeWithText("walk with their voice").assertIsNotEnabled().assertIsOn()
    }

    // pilgrim-ios #108, matched: no grouping, so each "·" is its own element.
    @Test
    fun `the stats row's separators are their own elements`() {
        show {
            HonorOverviewCard(
                overview = overview(voices = 2),
                units = UnitSystem.Metric,
                voicesEnabled = true,
                onVoicesEnabledChange = {},
                onBegin = {},
            )
        }

        composeRule.onAllNodesWithText("·").assertCountEquals(2)
        composeRule.onNodeWithText("2 voices").assertIsDisplayed()
    }

    // pilgrim-ios #109, matched: an imperial walker still reads Celsius.
    @Test
    fun `the weather line prints Celsius with a bare degree for an imperial walker`() {
        show {
            HonorOverviewCard(
                overview = overview(voices = 0, weather = WayWeather("lightRain", 9.0)),
                units = UnitSystem.Imperial,
                voicesEnabled = true,
                onVoicesEnabledChange = {},
                onBegin = {},
            )
        }

        composeRule.onNodeWithText("they walked this in light rain at 9°.").assertIsDisplayed()
    }

    // §17.6 — the moment preview.

    @Test
    fun `a voice's controls read Play their voice, the speed in the label, and the position as N percent`() {
        val samples = FloatArray(64) { 0.5f }
        show {
            WayMomentPreviewContent(
                way = overview(voices = 1).way,
                moment = voiceMoment(),
                units = UnitSystem.Metric,
                voice = WayVoicePreview(
                    isPlaying = false,
                    positionSeconds = 12.0,
                    totalSeconds = 30.0,
                    speed = 1f,
                    waveform = samples,
                ),
                onTogglePlay = {},
                onCycleSpeed = {},
                onSeek = {},
            )
        }

        composeRule.onNodeWithContentDescription("Play their voice").assertHasClickAction()
        composeRule.onNodeWithContentDescription("Playback speed, 1x").assertHasClickAction()
        composeRule.onNodeWithContentDescription("Playback position")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "40 percent"))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(current = 0.4f, range = 0f..1f, steps = 9),
                ),
            )
    }

    @Test
    fun `a playing voice reads Pause their voice, and a faster one its speed`() {
        show {
            WayMomentPreviewContent(
                way = overview(voices = 1).way,
                moment = voiceMoment(),
                units = UnitSystem.Metric,
                voice = WayVoicePreview(
                    isPlaying = true,
                    positionSeconds = 0.0,
                    totalSeconds = 30.0,
                    speed = 1.5f,
                    waveform = null,
                ),
                onTogglePlay = {},
                onCycleSpeed = {},
                onSeek = {},
            )
        }

        composeRule.onNodeWithContentDescription("Pause their voice").assertHasClickAction()
        composeRule.onNodeWithContentDescription("Playback speed, 1.5x").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Playback position").assertCountEquals(0)
    }

    @Test
    fun `a voice with no file here says so`() {
        show {
            WayMomentPreviewContent(
                way = overview(voices = 1).way,
                moment = voiceMoment(),
                units = UnitSystem.Metric,
                voice = null,
                onTogglePlay = {},
                onCycleSpeed = {},
                onSeek = {},
            )
        }

        composeRule.onNodeWithText("their voice is still on its way here").assertIsDisplayed()
        composeRule.onNodeWithText("“the bridge where we stopped.”").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Play their voice").assertCountEquals(0)
        // pilgrim-ios #108, matched: iOS's `waveform.slash` is unlabelled, so it reads its name.
        composeRule.onNodeWithContentDescription("waveform slash").assertIsDisplayed()
    }

    // pilgrim-ios #108, matched: the header glyph is unlabelled on iOS, so
    // VoiceOver reads the SF symbol `WayMomentHeader.glyph(for:)` names.
    @Test
    fun `the header glyph reads its symbol's name for every kind of moment`() {
        val voice = voiceMoment()
        val moments = listOf(
            voice,
            voice.copy(id = "voice-2", kind = (voice.kind as WayMomentKind.Voice).copy(kind = VoiceKind.AMBIENT)),
            WayMoment(id = "photo-1", frac = 0.3, at = null, kind = WayMomentKind.Photo(WayMedia.PhotoAsset("p"))),
            WayMoment(id = "rest-1", frac = 0.4, at = null, kind = WayMomentKind.Rest(minutes = 5)),
            WayMoment(
                id = "sit-1", frac = 0.5, at = null,
                kind = WayMomentKind.Meditation(minutes = 10, isEstimate = false),
            ),
            WayMoment(
                id = "waypoint-1", frac = 0.6, at = null,
                kind = WayMomentKind.Waypoint(label = "rested", icon = "figure.seated.side"),
            ),
            WayMoment(
                id = "waypoint-2", frac = 0.7, at = null,
                kind = WayMomentKind.Waypoint(label = "", icon = "not.a.symbol.here"),
            ),
        )
        val way = overview(voices = 0).way
        show { Column { moments.forEach { WayMomentHeader(way = way, moment = it, units = UnitSystem.Metric) } } }

        listOf("waveform", "wind", "photo", "cup and saucer", "circle circle", "figure seated side", "mappin")
            .forEach { composeRule.onNodeWithContentDescription(it).assertExists() }
    }

    @Test
    fun `a loaded photo is a button read Enlarge photo`() {
        var enlarged = false
        show { LoadedWayPhotoPlate(maxHeight = 360.dp, onEnlarge = { enlarged = true }) { Box(Modifier.size(100.dp)) } }

        composeRule.onNodeWithContentDescription("Enlarge photo")
            .assert(isButton())
            .performClick()
        assertTrue(enlarged)
    }

    @Test
    fun `the photo viewer's image and its button both read Close photo`() {
        var closes = 0
        show { WayPhotoViewerContent(onClose = { closes++ }) { Box(Modifier.size(100.dp)) } }

        val nodes = composeRule.onAllNodesWithContentDescription("Close photo")
        nodes.assertCountEquals(2)
        nodes[0].assertHasClickAction()
        nodes[1].assertHasClickAction().performClick()
        assertEquals(1, closes)
    }

    private fun voiceMoment() = WayMoment(
        id = "voice-1",
        frac = 0.1,
        at = null,
        kind = WayMomentKind.Voice(
            endFrac = 0.2,
            duration = 30.0,
            kind = VoiceKind.SPOKEN,
            media = WayMedia.Recording("r.wav"),
        ),
        transcript = "the bridge where we stopped.",
    )

    private fun overview(
        voices: Int,
        distanceToStart: Double? = null,
        weather: WayWeather? = null,
    ): HonorOverview {
        val way = Way(
            id = "walk:0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50",
            source = WaySource.OwnWalk("0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50"),
            title = "the long way",
            departedAt = Instant.ofEpochMilli(1_700_000_000_000L),
            tzIdentifier = "UTC",
            expires = null,
            route = (0..10).map { i -> WayPoint(lat = 0.0, lon = i * 0.001, alt = null, t = i * 60.0) },
            totalDistanceMeters = 1_111.95,
            theirActiveSeconds = 600.0,
            moments = List(voices) { i -> voiceMoment().copy(id = "voice-${i + 1}") },
            weather = weather,
        )
        return HonorOverview(
            sourceWalkId = 1L,
            way = way,
            line = HonorWayLine.of(way),
            pins = wayPins(way, emptySet()),
            bounds = HonorOverviewModel.bounds(way),
            playableVoices = emptyMap(),
            distanceToStartMeters = distanceToStart,
        )
    }

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private companion object {
        const val PICKER_EMPTY = "walk somewhere first. Any walk with a route can be walked again."
    }
}
