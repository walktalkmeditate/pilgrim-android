// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.path

import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Unit tests for [pickRandomQuote]. The full Composable is exercised
 * via instrumentation/manual QA; the Compose host's hiltViewModel()
 * injection point and Hilt-in-unit-tests friction make a full screen
 * test cost-prohibitive for the value (covered indirectly by the
 * spec verification plan).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkStartScreenTest {

    private lateinit var context: Context

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // AF58: the mode tab conveys selection only by color/underline; it must
    // expose Role.Tab + the selected state so TalkBack announces it.
    @Test
    fun `selected mode tab reports Tab role and selected state`() {
        composeRule.setContent {
            PilgrimTheme {
                ModeButton(
                    mode = WalkMode.Wander,
                    selected = true,
                    footprintActive = false,
                    onClick = {},
                )
            }
        }
        composeRule.onNode(isSelectable())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertIsSelected()
    }

    @Test
    fun `unselected mode tab reports not-selected`() {
        composeRule.setContent {
            PilgrimTheme {
                ModeButton(
                    mode = WalkMode.Seek,
                    selected = false,
                    footprintActive = false,
                    onClick = {},
                )
            }
        }
        composeRule.onNode(isSelectable()).assertIsNotSelected()
    }

    @Test
    fun `pickRandomQuote returns a wander quote for Wander mode`() {
        val all = context.resources.getStringArray(R.array.path_quotes_wander).toList()
        val quote = pickRandomQuote(context, WalkMode.Wander, Random(42))
        assertTrue("$quote should be one of the wander quotes", quote in all)
    }

    @Test
    fun `pickRandomQuote returns a together quote for Together mode`() {
        val all = context.resources.getStringArray(R.array.path_quotes_together).toList()
        val quote = pickRandomQuote(context, WalkMode.Together, Random(42))
        assertTrue("$quote should be one of the together quotes", quote in all)
    }

    @Test
    fun `pickRandomQuote returns a seek quote for Seek mode`() {
        val all = context.resources.getStringArray(R.array.path_quotes_seek).toList()
        val quote = pickRandomQuote(context, WalkMode.Seek, Random(42))
        assertTrue("$quote should be one of the seek quotes", quote in all)
    }

    @Test
    fun `pickRandomQuote is deterministic with seeded Random`() {
        val q1 = pickRandomQuote(context, WalkMode.Wander, Random(42))
        val q2 = pickRandomQuote(context, WalkMode.Wander, Random(42))
        assertEquals(q1, q2)
    }

    @Test
    fun `wander corpus has 6 entries`() {
        val arr = context.resources.getStringArray(R.array.path_quotes_wander)
        assertEquals(6, arr.size)
    }

    @Test
    fun `together and seek corpora have 3 entries each`() {
        assertEquals(3, context.resources.getStringArray(R.array.path_quotes_together).size)
        assertEquals(3, context.resources.getStringArray(R.array.path_quotes_seek).size)
    }
}
