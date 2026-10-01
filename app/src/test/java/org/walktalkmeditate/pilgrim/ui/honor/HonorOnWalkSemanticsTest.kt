// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.data.sounds.BreathRhythm
import org.walktalkmeditate.pilgrim.ui.meditation.MeditationScreenContent
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme
import org.walktalkmeditate.pilgrim.ui.walk.HonorCardMedia
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
            HonorArrivalCard(summary = HonorArrivalSummary("Obradoiro → Rúa do Franco", 0, 1), onContinue = {})
        }

        composeRule.onNodeWithText("one place passed").assertIsDisplayed()
    }

    // ---- The minimized stats (E §10) -----------------------------------------

    @Test
    fun `on an honor walk the minimized stats are one Walk stats element naming Remaining`() {
        showSheet(HonorSheetStats(remainingMeters = 650.0, softTapMeters = null, listening = null))

        val value = "${WalkFormat.duration(90_000L)}, ${WalkFormat.distance(250.0)}, ${WalkFormat.distance(650.0)} remaining"
        composeRule.onNodeWithContentDescription("Walk stats")
            .assert(isButton())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
    }

    @Test
    fun `the Walk stats value leads with the intention`() {
        showSheet(HonorSheetStats(remainingMeters = 650.0, softTapMeters = null, listening = null), intention = "for her")

        val value = "for her. ${WalkFormat.duration(90_000L)}, ${WalkFormat.distance(250.0)}, " +
            "${WalkFormat.distance(650.0)} remaining"
        composeRule.onNodeWithContentDescription("Walk stats")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
    }

    @Test
    fun `the bar shows the intention above the stats while no voice is held`() {
        showSheet(HonorSheetStats(remainingMeters = 650.0, softTapMeters = null, listening = null), intention = "for her")

        composeRule.onNodeWithText("for her", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a held voice's chip takes the intention's place`() {
        showSheet(
            HonorSheetStats(remainingMeters = 650.0, softTapMeters = null, listening = HonorListening(5.0, false)),
            intention = "for her",
        )

        composeRule.onAllNodesWithText("for her", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the third stat reads Remaining`() {
        showSheet(HonorSheetStats(remainingMeters = 650.0, softTapMeters = null, listening = null))

        composeRule.onNodeWithText("Remaining", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `before Begin Remaining reads two dashes`() {
        showSheet(HonorSheetStats(remainingMeters = null, softTapMeters = null, listening = null))

        composeRule.onNodeWithText("--", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a soft tap's caption takes the third stat's place`() {
        showSheet(HonorSheetStats(remainingMeters = 650.0, softTapMeters = 250L, listening = null))

        composeRule.onNodeWithText("off the way · 250 m", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the chip sits in the minimized bar while a voice is held, apart from the stats`() {
        showSheet(HonorSheetStats(remainingMeters = 650.0, softTapMeters = null, listening = HonorListening(5.0, false)))

        composeRule.onNodeWithContentDescription("Skip this voice").assert(isButton())
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

    private fun showSheet(honor: HonorSheetStats, intention: String? = null) = show {
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
            units = UnitSystem.Metric,
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

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private companion object {
        const val ARRIVAL_TAG = "arrival-card"
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
