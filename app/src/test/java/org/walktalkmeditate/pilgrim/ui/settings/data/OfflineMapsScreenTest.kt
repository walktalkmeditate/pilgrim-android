// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.FakeTileRegionLoader
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.FakeWalkSignals
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.InMemoryTilesCalibration
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.recordingHandler
import org.walktalkmeditate.pilgrim.ui.settings.SettingsAction
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Settings → Maps and the Data card's Maps row on screen (offline-maps spec
 * D C4 §3.1, §3.3): the row's place and its three details, the screen's
 * three states, the screen leaving mid-walk, and "Delete maps?" with
 * iOS's words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineMapsScreenTest {

    @get:Rule val composeRule = createComposeRule()

    @get:Rule val folder = TemporaryFolder()

    private val saved = OfflineMapsUiState.Saved("Camino de Santiago (Francés)", bytes = 26_100_000, savedStages = 12, totalStages = 33)

    private var deletes = 0
    private var confirms = 0
    private var cancels = 0

    private fun showScreen(state: OfflineMapsUiState, confirmingDelete: Boolean = false) {
        composeRule.setContent {
            PilgrimTheme {
                OfflineMapsContent(
                    state = state,
                    confirmingDelete = confirmingDelete,
                    onDelete = { deletes++ },
                    onConfirmDelete = { confirms++ },
                    onCancelDelete = { cancels++ },
                )
            }
        }
    }

    // ---- The Data card's row (C4 §3.1) -------------------------------------------

    @Test
    fun `the Data card shows Maps after Ways with the route and its bytes, and opens the screen`() {
        val actions = mutableListOf<SettingsAction>()
        composeRule.setContent {
            PilgrimTheme {
                DataCard(
                    onAction = { actions += it },
                    showsWays = true,
                    waysTotals = WaysTotals(2, 3_400_000),
                    showsMaps = true,
                    mapsDetail = saved,
                )
            }
        }

        val ways = composeRule.onNodeWithText("Ways").fetchSemanticsNode().boundsInRoot
        val maps = composeRule.onNodeWithText("Maps").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Maps comes after Ways", maps.top >= ways.bottom)
        composeRule.onNodeWithText("Camino de Santiago (Francés) · 26 MB").assertIsDisplayed()
        composeRule.onNodeWithText("Maps").performClick()

        assertEquals(listOf<SettingsAction>(SettingsAction.OpenMaps), actions)
    }

    @Test
    fun `the row's detail is blank before its first read, then none saved`() {
        var detail by mutableStateOf<OfflineMapsUiState>(OfflineMapsUiState.Loading)
        composeRule.setContent {
            PilgrimTheme { DataCard(onAction = {}, showsMaps = true, mapsDetail = detail) }
        }
        composeRule.onNodeWithText("Maps").assertIsDisplayed()
        composeRule.onAllNodesWithText("none saved").assertCountEquals(0)

        detail = OfflineMapsUiState.Empty

        composeRule.onNodeWithText("none saved").assertIsDisplayed()
    }

    @Test
    fun `with Maps unavailable the Data card has no Maps row`() {
        composeRule.setContent {
            PilgrimTheme { DataCard(onAction = {}, showsWays = true, showsMaps = false, mapsDetail = saved) }
        }

        composeRule.onNodeWithText("Ways").assertIsDisplayed()
        composeRule.onAllNodesWithText("Maps").assertCountEquals(0)
    }

    // ---- The Maps screen (C4 §3.3) ------------------------------------------------

    // C4 A10: the screen never opens on "no maps saved" before its first read.
    @Test
    fun `before its first read the screen draws nothing`() {
        showScreen(OfflineMapsUiState.Loading)

        composeRule.onAllNodesWithText("no maps saved").assertCountEquals(0)
        composeRule.onAllNodesWithText("Delete maps").assertCountEquals(0)
    }

    @Test
    fun `with nothing saved the screen reads no maps saved, with no Delete and no way to a save`() {
        showScreen(OfflineMapsUiState.Empty)

        composeRule.onNodeWithText("no maps saved").assertIsDisplayed()
        composeRule.onAllNodesWithText("Delete maps").assertCountEquals(0)
        composeRule.onAllNodesWithText("Save", substring = true).assertCountEquals(0)
    }

    @Test
    fun `saved maps read the route over its bytes and stages, then Delete maps`() {
        showScreen(saved)

        val name = composeRule.onNodeWithText("Camino de Santiago (Francés)").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val line = composeRule.onNodeWithText("26 MB · 12 of 33 stages").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val delete = composeRule.onNodeWithText("Delete maps")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .fetchSemanticsNode().boundsInRoot
        assertTrue(line.top >= name.bottom && delete.top >= line.bottom)
        composeRule.onNodeWithText("Delete maps").performClick()

        assertEquals(1, deletes)
    }

    // Owner decision 6: the screen pops itself once a walk starts, so no Delete can blank a walk's basemap.
    @Test
    fun `the Maps screen leaves once a walk starts`() {
        val shown = MutableStateFlow(true)
        val tilesScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + recordingHandler(CopyOnWriteArrayList()))
        val tiles = PilgrimageTilesManager(
            FakeTileRegionLoader(), InMemoryTilesCalibration(), FakeWalkSignals(), Dispatchers.Main.immediate, tilesScope,
        )
        val nothingInstalled = InstalledMaps(
            installed = { null },
            wayStore = WayStore({ folder.root }, syncDirectory = { true }),
            tiles = Provider { tiles },
            ioDispatcher = Dispatchers.Main.immediate,
            tilesDispatcher = Dispatchers.Main.immediate,
        )
        val vm = OfflineMapsViewModel(WaysAvailability(shown, shownAtFirst = true), nothingInstalled)
        var backs = 0
        try {
            composeRule.setContent { PilgrimTheme { OfflineMapsScreen(onBack = { backs++ }, viewModel = vm) } }
            composeRule.onNodeWithText("no maps saved").assertIsDisplayed()
            assertEquals(0, backs)

            shown.value = false
            composeRule.waitForIdle()

            assertEquals(1, backs)
        } finally {
            vm.viewModelScope.cancel()
            tilesScope.cancel()
        }
    }

    @Test
    fun `Delete maps asks with iOS's title, message and buttons`() {
        showScreen(saved, confirmingDelete = true)

        composeRule.onNodeWithText("Delete maps?").assertIsDisplayed()
        composeRule.onNodeWithText("Removes the saved basemap. The route's stages stay on your phone.").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(1 to 0, cancels to confirms)

        composeRule.onNodeWithText("Delete").performClick()
        assertEquals(1, confirms)
    }
}
