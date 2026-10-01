// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * What TalkBack reads of the summary's Honor section (parity spec G §11):
 * iOS sets no grouping, header, or label, so VoiceOver reads each row's
 * text on its own, in visual order, and there is nothing to tap. The
 * stage-only reply button ("Play your reply to this stage") never shows
 * on an own or shared walk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorSummarySemanticsTest {

    @get:Rule val composeRule = createComposeRule()

    private fun show(data: HonorSummaryData) {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 600.dp)) { HonorSummarySection(data = data) }
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

    @Test
    fun `a removed Way reads its fallback title with no delta or counts`() {
        show(HonorSummaryData(wayTitle = null, arrivedBeforeTheirsSeconds = null, voicesAlongTheWay = 0, repliesMade = 0))
        composeRule.onNodeWithText("in their steps").fetchSemanticsNode()
        composeRule.onNodeWithText("a way that has been removed").fetchSemanticsNode()
        composeRule.onAllNodesWithText("arrived", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("along the way", substring = true).assertCountEquals(0)
    }
}
