// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageRoute
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageRouteStage
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * The focused stage's scroll (stage-links spec Q11; iOS
 * `proxy.scrollTo(target, anchor: .center)`, `PilgrimageRouteView.swift:117-125@3b78728`):
 * the row's list position below the header items, the centring, and the
 * route page scrolling once and handing the target back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp")
class PilgrimageRouteScrollTest {

    @get:Rule val composeRule = createComposeRule()

    private val entry = PilgrimageCatalogEntry(
        id = ROUTE_ID,
        name = "Camino de Santiago (Francés)",
        names = emptyMap(),
        country = "ES",
        region = "Europe",
        distanceKm = 764.0,
        tradition = "christian",
        stageCount = STAGES,
        bytes = 1_000_000,
        placesPerStage = 0.0,
        sparse = false,
    )

    private val route = PilgrimageRoute(
        id = ROUTE_ID,
        name = entry.name,
        names = emptyMap(),
        country = "ES",
        region = "Europe",
        distanceKm = 764.0,
        stageCount = STAGES,
        tradition = "christian",
        summary = null,
        stages = (0 until STAGES).map { PilgrimageRouteStage(it, "stage $it", 20.0, 300.0, WayStageHours(5.0, 6.0), "moderate") },
    )

    private fun page(installed: Boolean) = PilgrimageRoutePage(
        entry = entry,
        release = RELEASE,
        installed = if (installed) PilgrimagePackageManager.Installed(ROUTE_ID, RELEASE, route) else null,
        route = route,
    )

    @Test
    fun `a stage's row sits below the header, the next row on the installed route, and the stages header`() {
        assertEquals(2, PilgrimageRouteModel.stageListPosition(page(installed = false), stageIndex = 0))
        assertEquals(27, PilgrimageRouteModel.stageListPosition(page(installed = false), stageIndex = 25))
        assertEquals(3, PilgrimageRouteModel.stageListPosition(page(installed = true), stageIndex = 0))
        assertNull(PilgrimageRouteModel.stageListPosition(page(installed = false), stageIndex = STAGES))
    }

    @Test
    fun `the centring puts the row's centre on the viewport's, and stops where the list ends`() {
        val state = LazyListState()
        lateinit var scope: CoroutineScope
        composeRule.setContent {
            scope = rememberCoroutineScope()
            LazyColumn(state = state, modifier = Modifier.height(300.dp)) {
                items(40) { Box(Modifier.fillMaxWidth().height(50.dp)) }
            }
        }

        composeRule.runOnIdle { scope.launch { state.animateScrollToCentre(20) } }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val info = state.layoutInfo
            val row = info.visibleItemsInfo.first { it.index == 20 }
            val off = row.offset + row.size / 2 - (info.viewportStartOffset + info.viewportEndOffset) / 2
            assertTrue("off centre by $off px", abs(off) <= 1)
        }

        composeRule.runOnIdle { scope.launch { state.animateScrollToCentre(39) } }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertFalse("the last row clamps at the end, as `.center` does", state.canScrollForward) }
    }

    @Test
    fun `a focused stage is scrolled into view once, and the target is handed back`() {
        var consumed = 0
        composeRule.setContent {
            PilgrimTheme {
                PilgrimageRouteContent(
                    state = PilgrimageRouteUiState.Ready(page(installed = false)),
                    phase = PilgrimagePackageManager.Phase.Idle,
                    tilesPhase = PilgrimageTilesManager.Phase.Idle,
                    mapsRow = null,
                    units = UnitSystem.Metric,
                    alert = null,
                    actions = PilgrimageRouteActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}),
                    scrollTarget = 25,
                    onScrollConsumed = { consumed++ },
                )
            }
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithText("26. stage 25").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, consumed) }
    }

    private companion object {
        const val ROUTE_ID = "camino-frances"
        const val RELEASE = "v1.12.0"
        const val STAGES = 30
    }
}
