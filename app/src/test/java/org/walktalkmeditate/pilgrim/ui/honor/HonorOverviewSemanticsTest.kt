// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.test.assertIsEnabled
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
import androidx.compose.ui.text.TextLayoutResult
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
import org.robolectric.annotation.GraphicsMode
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.honor.WayError
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WayStage
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours
import org.walktalkmeditate.pilgrim.domain.honor.WayStagePlace
import org.walktalkmeditate.pilgrim.domain.honor.WayWeather
import org.walktalkmeditate.pilgrim.honor.HonorImportState
import org.walktalkmeditate.pilgrim.honor.HonorStartRefusal
import org.walktalkmeditate.pilgrim.honor.HonorWayChoice
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
    fun `the Ways sheet shows iOS's own-walk section, its pilgrimage section, and its empty shared section`() {
        var opened = false
        show {
            HonorWaysSheetContent(
                shared = SharedWaysUiState.Loaded(emptyList()),
                importState = HonorImportState.Idle,
                onClose = {},
                onChooseShared = {},
                onWalkOneOfYours = { opened = true },
                onWalkAPilgrimage = {},
                onOpenPasted = {},
            )
        }

        listOf(
            "Choose a way",
            "Close",
            "Shared with you",
            "no ways yet. Accept a shared walk, or walk one of yours again.",
            "Your own walks",
            "A pilgrimage",
            "From a shared walk",
        ).forEach { composeRule.onNodeWithText(it).assertExists() }

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

    // Shared-walk spec S4 §8.3: the import line, and Begin held only while something could still land.

    @Test
    fun `a second link's fetch shows its line under the counts and holds Begin`() {
        show {
            HonorOverviewCard(
                overview = overview(voices = 1),
                units = UnitSystem.Metric,
                voicesEnabled = true,
                onVoicesEnabledChange = {},
                onBegin = {},
                importState = HonorImportState.Fetching,
            )
        }

        composeRule.onNodeWithText("reaching for the walk…").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Begin honoring this way").assertIsNotEnabled()
    }

    @Test
    fun `a failure, or missing voices, shows its line and leaves Begin to the walker`() {
        val state = mutableStateOf<HonorImportState>(HonorImportState.Failed(WayError.UNAVAILABLE))
        show {
            HonorOverviewCard(
                overview = overview(voices = 1),
                units = UnitSystem.Metric,
                voicesEnabled = true,
                onVoicesEnabledChange = {},
                onBegin = {},
                importState = state.value,
            )
        }

        mapOf(
            HonorImportState.Failed(WayError.UNAVAILABLE) to "couldn't reach the walk",
            HonorImportState.Failed(WayError.DISK_FULL) to "not enough space on this phone to save these voices",
            HonorImportState.MediaMissing(listOf("audio/2.m4a")) to "some voices didn't arrive",
        ).forEach { (importState, line) ->
            state.value = importState
            composeRule.onNodeWithText(line).assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Begin honoring this way").assertIsEnabled()
        }
        state.value = HonorImportState.Ready
        composeRule.onAllNodesWithText("couldn't reach the walk").assertCountEquals(0)
    }

    // S4 §8.3: the gathering line rounds half away from zero and holds Begin.
    @Test
    fun `a gather shows its percentage and holds Begin, with no buttons`() {
        show {
            HonorOverviewCard(
                overview = overview(voices = 1),
                units = UnitSystem.Metric,
                voicesEnabled = true,
                onVoicesEnabledChange = {},
                onBegin = {},
                importState = HonorImportState.Gathering(0.456),
            )
        }

        composeRule.onNodeWithText("gathering their voices · 46%").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Begin honoring this way").assertIsNotEnabled()
        composeRule.onAllNodesWithText("try again").assertCountEquals(0)
    }

    // Spec correction 6: missing voices never hold Begin; the two buttons are the walker's choice.
    @Test
    fun `missing voices offer try again and walk without them, two buttons, with Begin enabled`() {
        var retries = 0
        var walkedWithout = 0
        show {
            HonorOverviewCard(
                overview = overview(voices = 1),
                units = UnitSystem.Metric,
                voicesEnabled = true,
                onVoicesEnabledChange = {},
                onBegin = {},
                importState = HonorImportState.MediaMissing(listOf("audio/2.m4a")),
                onRetryMedia = { retries++ },
                onWalkWithoutMissing = { walkedWithout++ },
            )
        }

        composeRule.onNodeWithContentDescription("Begin honoring this way").assertIsEnabled()
        composeRule.onNodeWithText("try again").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        composeRule.onNodeWithText("walk without the missing voices").assertHasClickAction().performClick()

        assertEquals(1, retries)
        assertEquals(1, walkedWithout)
        composeRule.onAllNodesWithText("audio/2.m4a", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a full disk names the problem, offers no button, and leaves Begin to the walker`() {
        show {
            HonorOverviewCard(
                overview = overview(voices = 1),
                units = UnitSystem.Metric,
                voicesEnabled = true,
                onVoicesEnabledChange = {},
                onBegin = {},
                importState = HonorImportState.Failed(WayError.DISK_FULL),
            )
        }

        composeRule.onNodeWithText("not enough space on this phone to save these voices").assertIsDisplayed()
        composeRule.onAllNodesWithText("try again").assertCountEquals(0)
        composeRule.onAllNodesWithText("walk without the missing voices").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Begin honoring this way").assertIsEnabled()
    }

    // Shared-walk spec S4 §9.

    @Test
    fun `a shared Way's card offers its voices whether or not their files are here`() {
        show {
            HonorOverviewCard(
                overview = overview(voices = 0, way = sharedWay()),
                units = UnitSystem.Metric,
                voicesEnabled = true,
                onVoicesEnabledChange = {},
                onBegin = {},
                importState = HonorImportState.Ready,
            )
        }

        composeRule.onNodeWithText("Rúa do Franco → Obradoiro").assertIsDisplayed()
        composeRule.onNodeWithText("1 voice · 1 photo").assertIsDisplayed()
        composeRule.onNodeWithText("walk with their voice").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Begin honoring this way").assertIsEnabled()
    }

    @Test
    fun `a shared voice's preview names its street, and says its voice is still on its way`() {
        val way = sharedWay()
        show {
            WayMomentPreviewContent(
                way = way,
                moment = way.moments.first { it.isVoice },
                units = UnitSystem.Metric,
                voice = null,
                photoUri = null,
                onTogglePlay = {},
                onCycleSpeed = {},
                onSeek = {},
            )
        }

        composeRule.onNodeWithText(" · Rúa do Franco", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("their voice is still on its way here").assertIsDisplayed()
    }

    @Test
    fun `a shared photo not on the phone shows its stand-in above the caption, with nothing to enlarge`() {
        val way = sharedWay()
        show {
            WayMomentPreviewContent(
                way = way,
                moment = way.moments.first { it.kind is WayMomentKind.Photo },
                units = UnitSystem.Metric,
                voice = null,
                photoUri = null,
                onTogglePlay = {},
                onCycleSpeed = {},
                onSeek = {},
            )
        }

        composeRule.onNodeWithText("tap the photo to see it whole").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Enlarge photo").assertCountEquals(0)
    }

    // Pilgrimage-stage spec P4 §8: no hour on a stage, the local name under
    // the kicker in the full header too, and the stage's own place copy.
    @Test
    fun `a stage waypoint's preview reads along the stage, its local name, and a place on the way`() {
        val way = stageWay(text = null)
        show {
            WayMomentPreviewContent(
                way = way,
                moment = way.moments.single(),
                units = UnitSystem.Metric,
                voice = null,
                photoUri = null,
                onTogglePlay = {},
                onCycleSpeed = {},
                onSeek = {},
            )
        }

        composeRule.onNodeWithText("Vierge d'Orisson").assertIsDisplayed()
        composeRule.onNodeWithText("Orissongo Ama Birjina").assertIsDisplayed()
        composeRule.onNodeWithText("0.3 km along the stage").assertIsDisplayed()
        composeRule.onNodeWithText("A place on the way.").assertIsDisplayed()
        composeRule.onNodeWithText("When you walk it, the way will offer you 5 minutes of sitting here.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("building columns").assertIsDisplayed()
    }

    @Test
    fun `a stage waypoint's preview reads the dataset's own words when it has them`() {
        val way = stageWay(text = "A shepherd carried this Madonna up from Lourdes.")
        show {
            WayMomentPreviewContent(
                way = way,
                moment = way.moments.single(),
                units = UnitSystem.Metric,
                voice = null,
                photoUri = null,
                onTogglePlay = {},
                onCycleSpeed = {},
                onSeek = {},
            )
        }

        composeRule.onNodeWithText("A shepherd carried this Madonna up from Lourdes.").assertIsDisplayed()
        composeRule.onAllNodesWithText("A place on the way.").assertCountEquals(0)
    }

    // Pilgrimage-stage spec P5 §5.6, C6: the dataset's icons, not a pin, read by their symbols' names.
    @Test
    fun `a stage's lodge, seal, columns and book headers wear their own glyphs`() {
        val way = stageWay(text = null)
        val moments = listOf("house.lodge", "seal", "building.columns", "book.closed").mapIndexed { i, icon ->
            WayMoment(id = "wp-$i", frac = 0.1, at = null, kind = WayMomentKind.Waypoint(label = "a place", icon = icon))
        }
        show { Column { moments.forEach { WayMomentHeader(way = way, moment = it, units = UnitSystem.Metric) } } }

        listOf("house lodge", "seal", "building columns", "book closed")
            .forEach { composeRule.onNodeWithContentDescription(it).assertExists() }
        composeRule.onAllNodesWithContentDescription("mappin").assertCountEquals(0)
    }

    // iOS `.lineLimit(1)` (`WayMomentHeader.swift:25-30@7c200bf`) cuts the name's end with "…".
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a local name too long for its line ends in an ellipsis`() {
        val name = "Catedral de Santa María la Real de Pamplona, Iglesia Catedral Metropolitana"
        val way = stageWay(text = null)
        val moment = way.moments.single().copy(names = mapOf("es" to name))
        show { Box(Modifier.size(240.dp, 400.dp)) { WayMomentHeader(way = way, moment = moment, units = UnitSystem.Metric) } }

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(name).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)

        assertTrue(layouts.single().isLineEllipsized(0))
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
                photoUri = null,
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
                photoUri = null,
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
                photoUri = null,
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

    /** iOS's `stageWay()`: a 1 km stage with the Orisson waypoint at 0.3, its names and a sitting. */
    private fun stageWay(text: String?) = Way(
        id = "pilgrimage:camino-frances:0",
        source = WaySource.Pilgrimage(routeId = "camino-frances", stageIndex = 0),
        title = "Saint-Jean-Pied-de-Port to Roncesvalles",
        departedAt = Instant.ofEpochSecond(1_000_000),
        tzIdentifier = "Europe/Madrid",
        expires = null,
        route = (0..10).map { WayPoint(lat = 0.0, lon = it * 0.000898, alt = null, t = it * 60.0) },
        totalDistanceMeters = 1000.0,
        theirActiveSeconds = 600.0,
        moments = listOf(
            WayMoment(
                id = "wp-orisson",
                frac = 0.3,
                at = WayCoordinate(lat = 0.0, lon = 300.0 / 111_320),
                kind = WayMomentKind.Waypoint(label = "Vierge d'Orisson", icon = "building.columns"),
                text = text,
                names = mapOf("eu" to "Orissongo Ama Birjina", "fr" to "Vierge d'Orisson"),
                sitMinutes = 5,
            ),
        ),
        weather = null,
        marks = emptyList(),
        stage = WayStage(
            routeId = "camino-frances", index = 0, count = 33,
            name = "Saint-Jean-Pied-de-Port to Roncesvalles", theme = "Initiation",
            narrative = "The Pyrenees are the first question the way asks.",
            closing = "You crossed a border on foot.", warnings = emptyList(),
            distanceKm = 24.2, gainMeters = 1419.0, hours = WayStageHours(7.0, 9.0), difficulty = "hard",
            start = WayStagePlace("Saint-Jean-Pied-de-Port", WayCoordinate(0.0, 0.0)),
            end = WayStagePlace("Roncesvalles", WayCoordinate(0.0, 0.00898)),
        ),
    )

    /** A share as the importer builds one: a voice with its street, a photo, file media not on the phone. */
    private fun sharedWay() = Way(
        id = "share:Qoi4YmPHLN",
        source = WaySource.Share(id = "Qoi4YmPHLN", pageUrl = "https://walk.pilgrimapp.org/Qoi4YmPHLN"),
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
                place = "Rúa do Franco",
            ),
            WayMoment(id = "photo-1", frac = 0.8, at = null, kind = WayMomentKind.Photo(WayMedia.File("photos/1.jpg"))),
        ),
        weather = null,
        spans = emptyList(),
    )

    private fun overview(
        voices: Int,
        distanceToStart: Double? = null,
        weather: WayWeather? = null,
        way: Way? = null,
    ): HonorOverview {
        val built = way ?: Way(
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
            choice = if (way == null) HonorWayChoice.OwnWalk(1L) else HonorWayChoice.Stored(built.id),
            way = built,
            line = HonorWayLine.of(built),
            pins = wayPins(built, emptySet()),
            bounds = HonorOverviewModel.bounds(built),
            playableVoices = emptyMap(),
            photoUris = emptyMap(),
            distanceToStartMeters = distanceToStart,
        )
    }

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private companion object {
        const val PICKER_EMPTY = "walk somewhere first. Any walk with a route can be walked again."
    }
}
