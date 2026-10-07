// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesHarness
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager.Phase
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager.Status
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * The route page's maps row as drawn (spec D C4 §1.3): iOS's
 * `testTheRowsBodyReadsNoStore` (`PilgrimageMapsRowTests.swift:11-20@7c200bf`)
 * as a Compose test, then the faces the phase picks: saving with its
 * "cancel", a failure's line under the idle face for the status (the
 * saved face included), and a held row whose every control is disabled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimageMapsRowTest {

    @get:Rule val composeRule = createComposeRule()

    private val tiles = PilgrimageTilesHarness()

    @After
    fun tearDown() {
        tiles.close()
    }

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.width(400.dp)) { content() }
            }
        }
    }

    /**
     * The row is redrawn on every published change of the route page; a
     * body that hashed every stage's corridor and read the store would do
     * so on each of them. The status arrives already computed.
     */
    @Test
    fun `the row's body reads no store`() {
        var status by mutableStateOf<Status>(Status.None)
        show {
            val phase by tiles.manager.phase.collectAsState()
            PilgrimageMapsRow(
                estimateBytes = 0,
                status = status,
                phase = phase,
                enabled = true,
                onSave = { tiles.manager.save("camino-frances", emptyList()) },
                onCancel = tiles.manager::cancel,
            )
        }
        for (next in listOf(Status.Partial(saved = 1, of = 3), Status.Saved(bytes = 100))) {
            composeRule.waitForIdle()
            status = next
        }
        composeRule.waitForIdle()

        assertEquals(0, tiles.loader.regionsReadCount)
    }

    @Test
    fun `saving reads its stage count beside a cancel button`() {
        var cancels = 0
        show { MapsRow(Status.None, Phase.Saving(done = 14, total = 35), onCancel = { cancels++ }) }

        composeRule.onNodeWithText("maps · stage 12 of 33").assertIsDisplayed()
        composeRule.onNodeWithText("cancel").assert(isButton()).assertIsEnabled().performClick()
        assertEquals(1, cancels)
        composeRule.onAllNodesWithText("Save maps for the way", substring = true).assertCountEquals(0)
    }

    /** A save refused by a walk on a saved route: the saved face, and the refusal under it (spec D C4 §1.3). */
    @Test
    fun `a failure reads its line under the idle face for the status, the saved face included`() {
        show { MapsRow(Status.Saved(bytes = 26_100_000), Phase.Failed(PilgrimageError.WALK_IN_PROGRESS)) }

        val saved = composeRule.onNodeWithContentDescription("maps saved, 26 MB. Tap to save again")
        saved.assertIsDisplayed()
        val line = composeRule.onNodeWithText("finish your walk first")
        line.assertIsDisplayed()
        assertTrue(top(line) > top(saved))
    }

    /** pilgrim-ios #122 item 5, matched: a full disk names voices under "Save maps for the way". */
    @Test
    fun `a full disk reads the share importer's voices line`() {
        show { MapsRow(Status.Partial(saved = 1, of = 2), Phase.Failed(PilgrimageError.DISK_FULL)) }

        composeRule.onNodeWithText("Save maps for the way · 1 of 2 saved").assert(isButton()).assertIsEnabled()
        composeRule.onNodeWithText("not enough space on this phone to save these voices").assertIsDisplayed()
    }

    @Test
    fun `a held row keeps its words and takes no tap, cancel included`() {
        var taps = 0
        var phase by mutableStateOf<Phase>(Phase.Idle)
        show { MapsRow(Status.None, phase, enabled = false, onSave = { taps++ }, onCancel = { taps++ }, estimateBytes = 26_400_000) }

        composeRule.onNodeWithText("Save maps for the way · ~26 MB").assert(isButton()).assertIsNotEnabled().performClick()
        phase = Phase.Saving(done = 2, total = 4)
        composeRule.onNodeWithText("cancel").assertIsNotEnabled().performClick()

        assertEquals(0, taps)
    }

    @Composable
    private fun MapsRow(
        status: Status,
        phase: Phase,
        enabled: Boolean = true,
        onSave: () -> Unit = {},
        onCancel: () -> Unit = {},
        estimateBytes: Long = 0,
    ) = PilgrimageMapsRow(estimateBytes, status, phase, enabled, onSave, onCancel)

    private fun top(node: SemanticsNodeInteraction): Float = node.fetchSemanticsNode().boundsInRoot.top

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
}
