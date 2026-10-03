// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.honor.HonorPersistence
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.data.sounds.BreathRhythm
import org.walktalkmeditate.pilgrim.ui.meditation.MeditationScreenContent
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme
import org.walktalkmeditate.pilgrim.ui.walk.HonorCaption
import org.walktalkmeditate.pilgrim.ui.walk.HonorCardMedia
import org.walktalkmeditate.pilgrim.ui.walk.HonorCardsUi
import org.walktalkmeditate.pilgrim.ui.walk.HonorListening
import org.walktalkmeditate.pilgrim.ui.walk.HonorPlaceCard
import org.walktalkmeditate.pilgrim.ui.walk.HonorSheetStats
import org.walktalkmeditate.pilgrim.ui.walk.SheetState
import org.walktalkmeditate.pilgrim.ui.walk.VoiceRecorderUiState
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat
import org.walktalkmeditate.pilgrim.ui.walk.WalkStatsSheet

/**
 * Every TalkBack label parity spec E §14 tabulates for the walk's Honor
 * surfaces: the place card, the listening chip, the arrival card, the
 * minimized stats, and the meditation caption, gaps included where iOS
 * ships them (pilgrim-ios #108, matched).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorOnWalkSemanticsTest {

    @get:Rule val composeRule = createComposeRule()

    private val touches = mutableListOf<String>()

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 900.dp)) { content() }
            }
        }
    }

    private fun showCard(card: HonorPlaceCard, isRecordingReply: Boolean = false) =
        show { WayPlaceCard(card = card, units = UnitSystem.Metric, isRecordingReply = isRecordingReply, actions = actions()) }

    // ---- The place card's header -----------------------------------------

    @Test
    fun `the header is one button read Show this place on the map`() {
        showCard(voiceCard())

        composeRule.onNodeWithContentDescription("Show this place on the map").assert(isButton())
    }

    @Test
    fun `with any focus set the header reads Back to where you are`() {
        showCard(voiceCard(isFocused = true))

        composeRule.onNodeWithContentDescription("Back to where you are").assert(isButton())
    }

    @Test
    fun `the header's label hides its kicker, distance, and street (pilgrim-ios #108, matched)`() {
        showCard(voiceCard(place = "Rúa do Franco", distance = 120.0))

        composeRule.onAllNodesWithText("spoken here", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Rúa do Franco", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the waiting cards read their full count`() {
        showCard(voiceCard(pendingCount = 6))

        composeRule.onNodeWithContentDescription("6 more waiting").assertIsDisplayed()
    }

    @Test
    fun `with nothing waiting there are no dots`() {
        showCard(voiceCard(pendingCount = 0))

        composeRule.onAllNodesWithContentDescription("more waiting", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the x is a button read Dismiss that dismisses without touching`() {
        var dismissed = false
        show {
            WayPlaceCard(
                card = voiceCard(),
                units = UnitSystem.Metric,
                isRecordingReply = false,
                actions = actions(onDismiss = { dismissed = true }),
            )
        }

        composeRule.onNodeWithContentDescription("Dismiss").assert(isButton()).performClick()

        assertEquals(true to emptyList<String>(), dismissed to touches)
    }

    @Test
    fun `the header's tap touches the card and flies`() {
        var flown = false
        show {
            WayPlaceCard(
                card = voiceCard(),
                units = UnitSystem.Metric,
                isRecordingReply = false,
                actions = actions(onFly = { flown = true }),
            )
        }

        composeRule.onNodeWithContentDescription("Show this place on the map").performClick()

        assertEquals(true to listOf("touch"), flown to touches)
    }

    // ---- The voice body ----------------------------------------------------

    @Test
    fun `the transcript's first sentence reads in curly quotes`() {
        showCard(voiceCard())

        composeRule.onNodeWithText("“The bells start here.”").assertIsDisplayed()
    }

    @Test
    fun `a voice not playing offers Play their voice`() {
        showCard(voiceCard())

        composeRule.onNodeWithContentDescription("Play their voice").assert(isButton())
    }

    @Test
    fun `a playing voice offers Pause their voice`() {
        showCard(voiceCard(playing = true))

        composeRule.onNodeWithContentDescription("Pause their voice").assert(isButton())
    }

    @Test
    fun `a paused voice offers Play their voice again`() {
        showCard(voiceCard(playing = true, paused = true))

        composeRule.onNodeWithContentDescription("Play their voice").assert(isButton())
    }

    @Test
    fun `the waveform reads Their voice and its percent`() {
        showCard(voiceCard(playing = true, elapsed = 10.0))

        composeRule.onNodeWithContentDescription("Their voice; drag to move through it")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "50 percent"))
    }

    @Test
    fun `the waveform's bars stand 32 dp tall, as iOS frames them`() {
        showCard(voiceCard())

        val height = composeRule.onNodeWithContentDescription("Their voice; drag to move through it").getBoundsInRoot().height
        assertEquals(32f, height.value, 0.5f)
    }

    @Test
    fun `before its bars are read the waveform is a silent placeholder`() {
        showCard(voiceCard(media = null))

        composeRule.onAllNodesWithContentDescription("Their voice; drag to move through it").assertCountEquals(0)
    }

    @Test
    fun `the clock reads the held voice's time over the Way's length`() {
        showCard(voiceCard(playing = true, elapsed = 10.0))

        composeRule.onNodeWithText("0:10 / 0:20").assertIsDisplayed()
    }

    @Test
    fun `a card whose voice isn't held shows no tick of the clock`() {
        showCard(voiceCard(playing = false, elapsed = 0.0))

        composeRule.onNodeWithText("0:00 / 0:20").assertIsDisplayed()
    }

    @Test
    fun `the rate pill reads Playback speed`() {
        showCard(voiceCard(rate = 1.25f))

        composeRule.onNodeWithContentDescription("Playback speed, 1.25x").assert(isButton())
    }

    @Test
    fun `with no earlier reply the voice offers Record a reply at this spot`() {
        showCard(voiceCard())

        composeRule.onNodeWithContentDescription("Record a reply at this spot").assert(isButton())
    }

    @Test
    fun `with an earlier reply it offers to record again and to play it`() {
        showCard(voiceCard(earlierReply = true))

        composeRule.onNodeWithContentDescription("Record a new reply, replacing your earlier one").assert(isButton())
        composeRule.onNodeWithContentDescription("Play your earlier reply").assert(isButton())
    }

    @Test
    fun `record again asks first`() {
        showCard(voiceCard(earlierReply = true))

        composeRule.onNodeWithContentDescription("Record a new reply, replacing your earlier one").performClick()

        composeRule.onNodeWithText("Replace your earlier reply?").assertIsDisplayed()
        composeRule.onNodeWithText("Replace").assertHasClickAction()
        composeRule.onNodeWithText("Keep it").assertHasClickAction()
    }

    @Test
    fun `recording a reply to this voice shows its row and stop button`() {
        showCard(voiceCard(), isRecordingReply = true)

        composeRule.onNodeWithText("recording your reply here").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop recording your reply").assert(isButton())
    }

    // ---- The other bodies --------------------------------------------------

    @Test
    fun `a rest reads its line`() {
        showCard(card(WayMoment("rest-1", 0.4, null, WayMomentKind.Rest(minutes = 4))))

        composeRule.onNodeWithText("A pause in their walk. The companion waits here with you.").assertIsDisplayed()
    }

    @Test
    fun `a sitting offers Sit here for N minutes`() {
        showCard(card(WayMoment("sit-1", 0.8, null, WayMomentKind.Meditation(minutes = 1, isEstimate = false))))

        composeRule.onNodeWithContentDescription("Sit here for 1 minutes").assert(isButton())
        composeRule.onNodeWithText("your soundscape holds while you sit").assertIsDisplayed()
    }

    @Test
    fun `Sit? touches the card and offers its minutes`() {
        var sat: Int? = null
        show {
            WayPlaceCard(
                card = card(WayMoment("sit-1", 0.8, null, WayMomentKind.Meditation(minutes = 6, isEstimate = false))),
                units = UnitSystem.Metric,
                isRecordingReply = false,
                actions = actions(onSit = { sat = it }),
            )
        }

        composeRule.onNodeWithContentDescription("Sit here for 6 minutes").performClick()

        assertEquals(6 to listOf("touch"), sat to touches)
    }

    @Test
    fun `a waypoint reads its place copy`() {
        showCard(card(WayMoment("waypoint-1", 0.3, null, WayMomentKind.Waypoint(label = "the gate", icon = "leaf"))))

        composeRule.onNodeWithText("A place they marked.").assertIsDisplayed()
    }

    @Test
    fun `a photo not on this phone is a plain plate, silent and not tappable`() {
        showCard(card(WayMoment("photo-1", 0.5, null, WayMomentKind.Photo(WayMedia.File("photos/0.jpg")))))

        composeRule.onAllNodesWithContentDescription("Enlarge photo").assertCountEquals(0)
    }

    // ---- The listening chip (E §10) ------------------------------------------

    @Test
    fun `the chip reads listening, its clock, and its two controls`() {
        show { ListeningChip(HonorListening(elapsedSeconds = 67.4, paused = false), onPauseResume = {}, onSkip = {}) }

        composeRule.onNodeWithText("listening").assertIsDisplayed()
        composeRule.onNodeWithText("1:07").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause their voice").assert(isButton())
        composeRule.onNodeWithContentDescription("Skip this voice").assert(isButton())
    }

    @Test
    fun `a paused chip reads paused and Resume their voice`() {
        show { ListeningChip(HonorListening(elapsedSeconds = 3.0, paused = true), onPauseResume = {}, onSkip = {}) }

        composeRule.onNodeWithText("paused").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Resume their voice").assert(isButton())
    }

    @Test
    fun `the chip's waveform has no label of its own, so the symbol's name is read`() {
        show { ListeningChip(HonorListening(elapsedSeconds = 3.0, paused = false), onPauseResume = {}, onSkip = {}) }

        composeRule.onNodeWithContentDescription("waveform").assertIsDisplayed()
    }

    // ---- The arrival card (E §11) --------------------------------------------

    @Test
    fun `the arrival card reads its three lines then continue`() {
        var continued = false
        show {
            HonorArrivalCard(
                summary = HonorArrivalSummary("the long way", voicesHeard = 1, placesPassed = 2),
                units = UnitSystem.Metric,
                onContinue = { continued = true },
            )
        }

        composeRule.onNodeWithText("you walked their way").assertIsDisplayed()
        composeRule.onNodeWithText("the long way").assertIsDisplayed()
        composeRule.onNodeWithText("one voice heard · 2 places passed").assertIsDisplayed()
        composeRule.onNodeWithText("continue").assert(isButton()).performClick()
        assertEquals(true, continued)
    }

    @Test
    fun `the arrival card is as wide as its widest line, not the screen`() {
        show {
            HonorArrivalCard(
                summary = HonorArrivalSummary("the long way", voicesHeard = 1, placesPassed = 2),
                units = UnitSystem.Metric,
                onContinue = {},
                modifier = Modifier.testTag(ARRIVAL_TAG),
            )
        }

        val width = composeRule.onNodeWithTag(ARRIVAL_TAG).getBoundsInRoot().width
        assertTrue("got $width of the 400 dp screen", width < 300.dp)
    }

    @Test
    fun `a share walked without its voices counts only the places passed`() {
        show {
            HonorArrivalCard(
                summary = HonorArrivalSummary("Obradoiro → Rúa do Franco", 0, 1),
                units = UnitSystem.Metric,
                onContinue = {},
            )
        }

        composeRule.onNodeWithText("one place passed").assertIsDisplayed()
    }

    // ---- The minimized stats (E §10) -----------------------------------------

    @Test
    fun `on an honor walk the minimized stats are one Walk stats element naming Remaining`() {
        showSheet(HonorSheetStats(remainingMeters = 650.0, caption = null, listening = null))

        val value = "${WalkFormat.duration(90_000L)}, ${WalkFormat.distance(250.0)}, ${WalkFormat.distance(650.0)} remaining"
        composeRule.onNodeWithContentDescription("Walk stats")
            .assert(isButton())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
    }

    @Test
    fun `the Walk stats value leads with the intention`() {
        showSheet(HonorSheetStats(remainingMeters = 650.0, caption = null, listening = null), intention = "for her")

        val value = "for her. ${WalkFormat.duration(90_000L)}, ${WalkFormat.distance(250.0)}, " +
            "${WalkFormat.distance(650.0)} remaining"
        composeRule.onNodeWithContentDescription("Walk stats")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
    }

    @Test
    fun `the bar shows the intention above the stats while no voice is held`() {
        showSheet(HonorSheetStats(remainingMeters = 650.0, caption = null, listening = null), intention = "for her")

        composeRule.onNodeWithText("for her", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a held voice's chip takes the intention's place`() {
        showSheet(
            HonorSheetStats(remainingMeters = 650.0, caption = null, listening = HonorListening(5.0, false)),
            intention = "for her",
        )

        composeRule.onAllNodesWithText("for her", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the third stat reads Remaining`() {
        showSheet(HonorSheetStats(remainingMeters = 650.0, caption = null, listening = null))

        composeRule.onNodeWithText("Remaining", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `before Begin Remaining reads two dashes`() {
        showSheet(HonorSheetStats(remainingMeters = null, caption = null, listening = null))

        composeRule.onNodeWithText("--", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a soft tap's caption takes the third stat's place`() {
        showSheet(HonorSheetStats(remainingMeters = 650.0, caption = HonorCaption.OffWay(250L), listening = null))

        composeRule.onNodeWithText("off the way · 250 m", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the chip sits in the minimized bar while a voice is held, apart from the stats`() {
        showSheet(HonorSheetStats(remainingMeters = 650.0, caption = null, listening = HonorListening(5.0, false)))

        composeRule.onNodeWithContentDescription("Skip this voice").assert(isButton())
    }

    // ---- A pilgrimage stage (pilgrimage-stage spec P5 §6–§8, §12, §16) --------

    @Test
    fun `a stage's arrival card reads the stage, its places and kilometres, its closing line, then continue`() {
        showStageArrival(placesPassed = 3, reply = replyRow())

        composeRule.onNodeWithText("you walked the stage").assertIsDisplayed()
        composeRule.onNodeWithText(STAGE_NAME).assertIsDisplayed()
        composeRule.onNodeWithText("3 places passed · 24.2 km").assertIsDisplayed()
        composeRule.onNodeWithText(CLOSING).assertIsDisplayed()
        composeRule.onNodeWithText("continue").assert(isButton())
    }

    @Test
    fun `one place passed reads in the singular`() {
        showStageArrival(placesPassed = 1)

        composeRule.onNodeWithText("one place passed · 24.2 km").assertIsDisplayed()
    }

    // So "the whole stage" can never show (P5 C7, pilgrim-ios #122, matched).
    @Test
    fun `with no place passed the kilometres stand alone`() {
        showStageArrival(placesPassed = 0)

        composeRule.onNodeWithText("24.2 km").assertIsDisplayed()
    }

    @Test
    fun `with no reply yet the row offers reply here, read Record a reply to this stage`() {
        showStageArrival(reply = replyRow())

        composeRule.onNodeWithContentDescription("Record a reply to this stage").assert(isButton()).performClick()
        composeRule.onAllNodesWithContentDescription("Play your reply").assertCountEquals(0)
        assertEquals(listOf("reply"), stageTaps)
    }

    @Test
    fun `with a reply on the phone record again starts at once, with no question, and your reply plays it`() {
        showStageArrival(reply = replyRow(hasReply = true))

        composeRule.onNodeWithContentDescription("Record a reply to this stage").assert(isButton()).performClick()
        composeRule.onAllNodesWithText("Replace your earlier reply?").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Play your reply").assert(isButton()).performClick()

        assertEquals(listOf("reply", "play"), stageTaps)
    }

    // The face changes, the label doesn't: TalkBack never hears "record again" (P5-D2).
    @Test
    fun `the reply button's face reads reply here, then record again once a reply is on the phone`() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources

        assertEquals(
            "reply here" to "record again",
            resources.getString(HonorArrivalCopy.replyTitle(hasReply = false)) to
                resources.getString(HonorArrivalCopy.replyTitle(hasReply = true)),
        )
    }

    @Test
    fun `while the reply records the row says so in a caption, with its stop button and nothing to start another`() {
        showStageArrival(reply = replyRow(isRecording = true))

        composeRule.onNodeWithText("recording your reply here").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop recording your reply").assert(isButton()).performClick()
        composeRule.onAllNodesWithContentDescription("Record a reply to this stage").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Play your reply").assertCountEquals(0)
        assertEquals(listOf("stop"), stageTaps)
    }

    @Test
    fun `an earlier reply stays playable while a new one records`() {
        showStageArrival(reply = replyRow(hasReply = true, isRecording = true))

        composeRule.onNodeWithContentDescription("Stop recording your reply").assert(isButton())
        composeRule.onNodeWithContentDescription("Play your reply").assert(isButton())
    }

    @Test
    fun `a stage's arrival card says nothing of them`() {
        showStageArrival(placesPassed = 2, reply = replyRow(hasReply = true))

        composeRule.onAllNodesWithText("their", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("they", substring = true).assertCountEquals(0)
    }

    @Test
    fun `an arrival on a Way that isn't a stage has no closing line and no reply row`() {
        show {
            HonorCardLayer(
                cards = HonorCardsUi(walkId = 1L, wayId = "walk:x", arrival = HonorArrivalSummary("the long way", 1, 2), place = null),
                units = UnitSystem.Metric,
                replyingToMomentId = null,
                isRecording = false,
                actionsFor = { actions() },
                onContinue = {},
                stageReplyActions = stageReplyActions(),
            )
        }

        composeRule.onAllNodesWithContentDescription("Record a reply to this stage").assertCountEquals(0)
    }

    @Test
    fun `the card layer shows the reply recording only while the take answers the closing line`() {
        var replyingTo by mutableStateOf<String?>("voice-1")
        show {
            HonorCardLayer(
                cards = HonorCardsUi(walkId = 1L, wayId = STAGE_ID, arrival = stageSummary(placesPassed = 1), place = null),
                units = UnitSystem.Metric,
                replyingToMomentId = replyingTo,
                isRecording = true,
                actionsFor = { actions() },
                onContinue = {},
                stageReplyActions = stageReplyActions(),
            )
        }
        composeRule.onNodeWithContentDescription("Record a reply to this stage").assertExists()

        replyingTo = HonorPersistence.STAGE_REFLECTION_MOMENT_ID

        composeRule.onNodeWithText("recording your reply here").assertIsDisplayed()
    }

    @Test
    fun `a stage's waypoint card reads the dataset's words`() {
        showCard(stageCard(text = "A shepherd carried this Madonna up from Lourdes."))

        composeRule.onNodeWithText("A shepherd carried this Madonna up from Lourdes.").assertIsDisplayed()
    }

    @Test
    fun `a stage's waypoint with no words reads A place on the way`() {
        showCard(stageCard(text = null))

        composeRule.onNodeWithText("A place on the way.").assertIsDisplayed()
        composeRule.onAllNodesWithText("A place they marked.").assertCountEquals(0)
    }

    // iOS `.lineLimit(4)` (`WayPlaceCard.swift:116-126@7c200bf`): the card doesn't scroll, so it shows what fits.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a stage's long words stop at four lines with an ellipsis`() {
        val words = "The way climbs out of the village past the last fountain and the stone cross. ".repeat(8).trim()
        showCard(stageCard(text = words))

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(words).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)

        assertEquals(4 to true, layouts.single().lineCount to layouts.single().isLineEllipsized(3))
    }

    @Test
    fun `a stage's local name stands under the kicker, the label never echoed`() {
        show {
            WayMomentCompactHeader(moment = stageMoment(text = null), subline = null, tick = null, keepsEmptyKicker = true)
        }

        composeRule.onNodeWithText("Vierge d'Orisson").assertIsDisplayed()
        composeRule.onNodeWithText("Orissongo Ama Birjina").assertIsDisplayed()
    }

    @Test
    fun `a stage's Sit? offers its 5 minutes and the line beside it`() {
        var sat: Int? = null
        show {
            WayPlaceCard(
                card = stageCard(text = null),
                units = UnitSystem.Metric,
                isRecordingReply = false,
                actions = actions(onSit = { sat = it }),
            )
        }

        composeRule.onNodeWithText("your soundscape holds while you sit").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Sit here for 5 minutes").assert(isButton()).performClick()
        assertEquals(5, sat)
    }

    // E-15: the one "they" a stage's walk shows, as iOS ships it (pilgrim-ios #122, matched).
    @Test
    fun `the sitting a stage's Sit? begins says they sat here 5 minutes`() {
        showMeditation(theirSittingMinutes = 5)

        composeRule.onNodeWithText("they sat here 5 minutes").assertExists()
    }

    @Test
    fun `water ahead takes the third stat's place, and the Walk stats value reads it`() {
        showSheet(HonorSheetStats(remainingMeters = 650.0, caption = HonorCaption.Water(280.0), listening = null))

        composeRule.onNodeWithText("water in 280 m", useUnmergedTree = true).assertExists()
        composeRule.onAllNodesWithText("Remaining", useUnmergedTree = true).assertCountEquals(0)
        val value = "${WalkFormat.duration(90_000L)}, ${WalkFormat.distance(250.0)}, water in 280 m"
        composeRule.onNodeWithContentDescription("Walk stats")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
    }

    @Test
    fun `a miles walker reads water in tenths of a mile`() {
        showSheet(HonorSheetStats(remainingMeters = 650.0, caption = HonorCaption.Water(280.0), listening = null), units = UnitSystem.Imperial)

        composeRule.onNodeWithText("water in 0.2 mi", useUnmergedTree = true).assertExists()
    }

    // ---- The meditation caption (E §12) --------------------------------------

    @Test
    fun `a card-started sitting reads they sat here N minutes under the timer`() {
        showMeditation(theirSittingMinutes = 12)

        composeRule.onNodeWithText("they sat here 12 minutes").assertExists()
    }

    @Test
    fun `one minute reads in the singular there`() {
        showMeditation(theirSittingMinutes = 1)

        composeRule.onNodeWithText("they sat here 1 minute").assertExists()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `zero reads in the plural even where the phone's rules count it as one`() {
        showMeditation(theirSittingMinutes = 0)

        composeRule.onNodeWithText("they sat here 0 minutes").assertExists()
    }

    @Test
    @Config(qualifiers = "ja")
    fun `one reads in the singular even where the phone's rules have no singular`() {
        showMeditation(theirSittingMinutes = 1)

        composeRule.onNodeWithText("they sat here 1 minute").assertExists()
    }

    @Test
    fun `a sitting begun anywhere else has no caption`() {
        showMeditation(theirSittingMinutes = null)

        composeRule.onAllNodesWithText("they sat here", substring = true).assertCountEquals(0)
    }

    // ---- Harness ----------------------------------------------------------

    private fun showSheet(honor: HonorSheetStats, intention: String? = null, units: UnitSystem = UnitSystem.Metric) = show {
        WalkStatsSheet(
            state = SheetState.Minimized,
            onStateChange = {},
            walkState = WalkState.Active(WalkAccumulator(1L, 0L, distanceMeters = 250.0)),
            totalElapsedMillis = 90_000L,
            distanceMeters = 250.0,
            walkMillis = 90_000L,
            talkMillis = 0L,
            meditateMillis = 0L,
            recorderState = VoiceRecorderUiState.Idle,
            audioLevelFlow = MutableStateFlow(0f),
            recordingsCount = 0,
            units = units,
            intention = intention,
            onStartWalk = {},
            onStartMeditation = {}, onEndMeditation = {},
            onToggleRecording = {}, onPermissionDenied = {}, onDismissError = {},
            onFinish = {},
            honor = honor,
        )
    }

    private fun showMeditation(theirSittingMinutes: Int?) {
        show {
            MeditationScreenContent(
                elapsedSeconds = 30,
                mossColor = Color(0xFF7A8B6F),
                theirSittingMinutes = theirSittingMinutes,
                enabled = true,
                onDone = {},
                breathRhythm = BreathRhythm.byId(6),
            )
        }
        composeRule.waitForIdle()
    }

    private val stageTaps = mutableListOf<String>()

    private fun stageReplyActions() = StageReplyActions(
        onReply = { stageTaps += "reply" },
        onStopReply = { stageTaps += "stop" },
        onPlayReply = { stageTaps += "play" },
    )

    private fun replyRow(hasReply: Boolean = false, isRecording: Boolean = false) =
        StageReplyRow(hasReply = hasReply, isRecording = isRecording, actions = stageReplyActions())

    private fun stageSummary(placesPassed: Int = 3) = HonorArrivalSummary(
        wayTitle = STAGE_NAME,
        voicesHeard = 0,
        placesPassed = placesPassed,
        stageName = STAGE_NAME,
        distanceWalkedMeters = 24_200.0,
        closing = CLOSING,
    )

    private fun showStageArrival(placesPassed: Int = 3, reply: StageReplyRow? = null) = show {
        HonorArrivalCard(summary = stageSummary(placesPassed), units = UnitSystem.Metric, onContinue = {}, stageReply = reply)
    }

    /** iOS `PilgrimageStageWalkTests.stageWay`'s waypoint: words, two local names, a sitting, a pin. */
    private fun stageMoment(text: String?) = WayMoment(
        id = "wp-orisson",
        frac = 0.3,
        at = WayCoordinate(lat = 0.0, lon = 300.0 / 111_320),
        kind = WayMomentKind.Waypoint(label = "Vierge d'Orisson", icon = "building.columns"),
        text = text,
        names = mapOf("eu" to "Orissongo Ama Birjina", "fr" to "Vierge d'Orisson"),
        sitMinutes = 5,
        pin = WayCoordinate(lat = 0.0002, lon = 300.0 / 111_320),
    )

    private fun stageCard(text: String?) = card(stageMoment(text)).copy(isStage = true, keepsEmptyKicker = true)

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private companion object {
        const val ARRIVAL_TAG = "arrival-card"
        const val STAGE_ID = "pilgrimage:camino-frances:0"
        const val STAGE_NAME = "Saint-Jean-Pied-de-Port to Roncesvalles"
        const val CLOSING = "You crossed a border on foot."
    }

    private fun actions(
        onFly: () -> Unit = {},
        onDismiss: () -> Unit = {},
        onSit: (Int) -> Unit = {},
    ) = WayPlaceCardActions(
        onFly = onFly,
        onTouch = { touches += "touch" },
        onDismiss = onDismiss,
        onPlayPause = {},
        onSeek = {},
        onCycleRate = {},
        onPlayReply = {},
        onReply = {},
        onStopReply = {},
        onSit = onSit,
    )

    private fun voiceCard(
        isFocused: Boolean = false,
        pendingCount: Int = 0,
        playing: Boolean = false,
        paused: Boolean = false,
        elapsed: Double = 0.0,
        rate: Float = 1f,
        earlierReply: Boolean = false,
        place: String? = null,
        distance: Double? = null,
        media: HonorCardMedia? = HonorCardMedia("voice-1", FloatArray(150) { 0.5f }, photoUri = null, hasEarlierReply = earlierReply),
    ) = HonorPlaceCard(
        moment = WayMoment(
            id = "voice-1", frac = 0.2, at = null,
            kind = WayMomentKind.Voice(0.25, 20.0, VoiceKind.SPOKEN, WayMedia.Recording("recordings/v1.wav")),
            place = place,
            transcript = "The bells start here. Then the road bends.",
        ),
        pendingCount = pendingCount,
        isStage = false,
        keepsEmptyKicker = false,
        distanceMeters = distance,
        tick = null,
        isFocused = isFocused,
        isPlaying = playing,
        isPaused = paused,
        elapsedSeconds = elapsed,
        rate = rate,
        media = media,
    )

    private fun card(moment: WayMoment) = HonorPlaceCard(
        moment = moment,
        pendingCount = 0,
        isStage = false,
        keepsEmptyKicker = false,
        distanceMeters = null,
        tick = null,
        isFocused = false,
        isPlaying = false,
        isPaused = false,
        elapsedSeconds = 0.0,
        rate = 1f,
        media = HonorCardMedia(moment.id, waveform = null, photoUri = null, hasEarlierReply = false),
    )
}
