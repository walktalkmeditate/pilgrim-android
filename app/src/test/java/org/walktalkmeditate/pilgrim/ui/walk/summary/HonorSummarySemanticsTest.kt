// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * What TalkBack reads of the summary's Honor section (parity spec G §11):
 * iOS sets no grouping, header, or label, so VoiceOver reads each row's
 * text on its own, in visual order, and there is nothing to tap. The
 * stage-only reply button ("Play your reply to this stage") never shows
 * on an own or shared walk; on a stage it is the one thing to tap, and
 * its label stays while its face says "pause" (pilgrimage-stage spec P5 §12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorSummarySemanticsTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(data: HonorSummaryData, reply: HonorSummaryReplyPlayer? = null) {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 600.dp)) {
                    HonorSummarySection(data = data, units = UnitSystem.Metric, reply = reply)
                }
            }
        }
    }

    private val arrived = HonorSummaryData(
        wayTitle = "Morning loop",
        arrivedBeforeTheirsSeconds = -150.0,
        voicesAlongTheWay = 3,
        repliesMade = 1,
    )

    @Test
    fun `each row is its own text, read as written, in visual order`() {
        show(arrived)
        val rows = listOf(
            "in their steps",
            "Morning loop",
            "they arrived 2 minutes before you",
            "3 voices along the way · 1 reply",
        )
        val tops = rows.map { text ->
            val node = composeRule.onNodeWithText(text).fetchSemanticsNode()
            assertNull("no label replaces \"$text\"", node.config.getOrNull(SemanticsProperties.ContentDescription))
            assertNull("no header trait on \"$text\"", node.config.getOrNull(SemanticsProperties.Heading))
            node.boundsInRoot.top
        }
        assertEquals("rows read top to bottom", tops.sorted(), tops)
    }

    @Test
    fun `the section links nowhere and has no reply button on an own or shared walk`() {
        show(arrived)
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(0)
        composeRule.onAllNodesWithText("your reply").assertCountEquals(0)
        composeRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Play your reply to this stage")),
        ).assertCountEquals(0)
    }

    // A pilgrimage stage (pilgrimage-stage spec P5 §11.3, §12).

    private val arrivedStage = HonorSummaryData(
        wayTitle = "Saint-Jean-Pied-de-Port to Roncesvalles",
        arrivedBeforeTheirsSeconds = null,
        voicesAlongTheWay = 0,
        repliesMade = 1,
        isPilgrimageStage = true,
        stageProgress = HonorStageProgress(kmWalked = 24.2, distanceKm = 24.2),
        closing = "You crossed a border on foot.",
        replyRelativePath = "recordings/stage-reply.wav",
    )

    private class Taps {
        var toggles = 0
        var stops = 0
        fun player(isPlaying: Boolean) = HonorSummaryReplyPlayer(isPlaying, toggle = { toggles++ }, stop = { stops++ })
    }

    private val replyLabel = SemanticsMatcher.expectValue(
        SemanticsProperties.ContentDescription,
        listOf("Play your reply to this stage"),
    )

    @Test
    fun `a stage's rows read kicker, title, progress, counts, then closing, each as written`() {
        show(arrivedStage, Taps().player(isPlaying = false))
        val rows = listOf(
            "the stage you walked",
            "Saint-Jean-Pied-de-Port to Roncesvalles",
            "24.2 km of 24.2 km of the stage",
            "1 reply",
            "You crossed a border on foot.",
        )
        val tops = rows.map { text ->
            val node = composeRule.onNodeWithText(text).fetchSemanticsNode()
            assertNull("no label replaces \"$text\"", node.config.getOrNull(SemanticsProperties.ContentDescription))
            assertNull("no header trait on \"$text\"", node.config.getOrNull(SemanticsProperties.Heading))
            node.boundsInRoot.top
        }
        assertEquals("rows read top to bottom", tops.sorted(), tops)
        val button = composeRule.onNode(replyLabel).fetchSemanticsNode()
        assertTrue("the reply comes last", button.boundsInRoot.top > tops.last())
        composeRule.onAllNodesWithText("they arrived", substring = true).assertCountEquals(0)
    }

    @Test
    fun `your reply plays on a tap, under the one label TalkBack reads`() {
        val taps = Taps()
        show(arrivedStage, taps.player(isPlaying = false))

        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
        composeRule.onNode(replyLabel).assertHasClickAction()
        composeRule.onNode(replyLabel).performClick()

        assertEquals(1, taps.toggles)
    }

    /** The face TalkBack doesn't read: the glyph's name, then the word, from the tags under the button. */
    private fun face(): List<String> =
        composeRule.onNode(replyLabel, useUnmergedTree = true).fetchSemanticsNode().children
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    @Test
    fun `at rest the button shows the play glyph and says your reply`() {
        show(arrivedStage, Taps().player(isPlaying = false))

        assertEquals(listOf(Icons.Outlined.PlayCircle.name, "your reply"), face())
    }

    /** pilgrim-ios #123 item 7, matched as shipped: the face says "pause", the label still says play. */
    @Test
    fun `while it plays the button shows the pause glyph and says pause`() {
        show(arrivedStage, Taps().player(isPlaying = true))

        assertEquals(listOf(Icons.Outlined.PauseCircle.name, "pause"), face())
    }

    @Test
    fun `while it plays TalkBack still reads the play label and never the face`() {
        show(arrivedStage, Taps().player(isPlaying = true))

        composeRule.onNode(replyLabel).assertHasClickAction()
        composeRule.onAllNodesWithText("pause").assertCountEquals(0)
    }

    @Test
    fun `no recording on the phone, no reply button`() {
        show(arrivedStage, reply = null)

        composeRule.onAllNodes(hasClickAction()).assertCountEquals(0)
        composeRule.onAllNodes(replyLabel).assertCountEquals(0)
    }

    @Test
    fun `the reply stops when the section leaves`() {
        val taps = Taps()
        var shown by mutableStateOf(true)
        composeRule.setContent {
            PilgrimTheme {
                if (shown) HonorSummarySection(data = arrivedStage, units = UnitSystem.Metric, reply = taps.player(isPlaying = true))
            }
        }
        assertEquals(0, taps.stops)

        shown = false
        composeRule.waitForIdle()

        assertEquals(1, taps.stops)
    }

    /** iOS's `.onDisappear` doesn't fire on a trait change: a dark/light flip recreates the activity, not the walk's summary. */
    @Test
    fun `the reply keeps playing through a configuration change`() {
        val taps = Taps()
        show(arrivedStage, taps.player(isPlaying = true))

        composeRule.activityRule.scenario.recreate()

        assertEquals(0, taps.stops)
    }

    @Test
    fun `a removed Way reads its fallback title with no delta or counts`() {
        show(HonorSummaryData(wayTitle = null, arrivedBeforeTheirsSeconds = null, voicesAlongTheWay = 0, repliesMade = 0))
        composeRule.onNodeWithText("in their steps").fetchSemanticsNode()
        composeRule.onNodeWithText("a way that has been removed").fetchSemanticsNode()
        composeRule.onAllNodesWithText("arrived", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("along the way", substring = true).assertCountEquals(0)
    }
}
