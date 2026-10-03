// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.HonorStageOutcome
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalog
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageGroup
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedger
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageRoute
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageRouteStage
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours
import org.walktalkmeditate.pilgrim.honor.HonorImportState
import org.walktalkmeditate.pilgrim.ui.honor.HonorWaysSheetContent
import org.walktalkmeditate.pilgrim.ui.honor.SharedWaysUiState
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * What TalkBack reads on the third door's screens (pilgrimage-stage spec
 * P4 §2, §3.3, §4.2–§4.7, §12): the Ways sheet's third section, a catalog
 * row as one button with the badge's words and no plate, the catalog's
 * three faces and its rust line's place, the route page's bar, button,
 * footer lines, next row and stage rows (the circle caption-sized), and
 * the three alerts with iOS's buttons. A window as tall as
 * the content, so the lazy lists compose every row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h1600dp")
class PilgrimageScreensSemanticsTest {

    @get:Rule val composeRule = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 1600.dp)) { content() }
            }
        }
    }

    // ---- P4 §2: the Ways sheet's third section ----

    @Test
    fun `the third section sits between your own walks and a shared walk, a heading over one button with no hint`() {
        var opened = 0
        show {
            HonorWaysSheetContent(
                shared = SharedWaysUiState.Loaded(emptyList()),
                importState = HonorImportState.Idle,
                onClose = {},
                onChooseShared = {},
                onWalkOneOfYours = {},
                onWalkAPilgrimage = { opened++ },
                onOpenPasted = {},
            )
        }

        composeRule.onNodeWithText("A pilgrimage").assert(isHeading())
        composeRule.onNodeWithText("A route from the open-pilgrimages dataset, walked one stage at a time.").assertExists()
        composeRule.onNodeWithText("Walk a pilgrimage").assert(isButton()).assert(noClickLabel()).performClick()
        assertEquals(1, opened)
        val tops = listOf("Your own walks", "A pilgrimage", "From a shared walk").map { top(composeRule.onNodeWithText(it)) }
        assertEquals("in iOS's order", tops.sorted(), tops)
    }

    // ---- P4 §3.3: the catalog row ----

    @Test
    fun `a catalog row is one button reading its name, the badge's words, its card line and sparse note, never its plate`() {
        var opened: String? = null
        showCatalog(installed = installed(release = RELEASE), ledgers = mapOf(ROUTE_ID to walkedOne()), onOpen = { opened = it })

        val row = composeRule.onNodeWithText(entry.name, substring = true)
        row.assert(isButton()).assert(noClickLabel())
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("on your phone")))
        row.assert(textIs(entry.name, "ES · 764 km · stage 2 of 33 · 24.2 km walked", "few places marked yet"))
        composeRule.onAllNodesWithText("on your phone", useUnmergedTree = true).assertCountEquals(0)
        // The plate's initial is still drawn, in the unmerged tree; TalkBack reads the merged one.
        composeRule.onAllNodesWithText("C").assertCountEquals(0)
        row.performClick()
        assertEquals(ROUTE_ID, opened)
    }

    @Test
    fun `the badge reads update ready for a release that differs, and a route not installed has none`() {
        showCatalog(installed = installed(release = "v1.6.0"), extra = listOf(entry.copy(id = "camino-norte", name = "Camino del Norte")))

        composeRule.onNodeWithContentDescription("update ready", useUnmergedTree = true).assertExists()
        composeRule.onAllNodesWithContentDescription("on your phone", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithText("Camino del Norte", substring = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }

    @Test
    fun `a group's name is a heading, and a group no pilgrimage claims has none`() {
        val catalog = PilgrimageCatalog(
            RELEASE,
            listOf(entry, entry.copy(id = "st-cuthberts-way", name = "St Cuthbert's Way")),
            listOf(
                PilgrimageGroup(id = "camino-de-santiago", name = "Camino de Santiago", entries = listOf(entry)),
                PilgrimageGroup(id = "", name = null, entries = listOf(entry.copy(id = "st-cuthberts-way", name = "St Cuthbert's Way"))),
            ),
        )
        show { CatalogContent(catalog, PilgrimageCatalogUiState(isLoading = false)) }

        composeRule.onNodeWithText("Camino de Santiago").assert(isHeading())
        composeRule.onAllNodes(isHeading()).assertCountEquals(2)
    }

    // ---- P4 §3.2: the catalog's three faces ----

    @Test
    fun `nothing held while loading is a spinner, and the bar is iOS's`() {
        show { CatalogContent(catalog = null, state = PilgrimageCatalogUiState(isLoading = true)) }

        composeRule.onNodeWithText("Pilgrimages").assert(isHeading())
        composeRule.onNodeWithText("Close").assertHasClickAction()
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
            .assertExists()
    }

    @Test
    fun `out of reach reads the line over a try again button`() {
        var retries = 0
        show {
            CatalogContent(
                catalog = null,
                state = PilgrimageCatalogUiState(isLoading = false, failure = PilgrimageError.CATALOG_UNREACHABLE),
                onRetry = { retries++ },
            )
        }

        composeRule.onNodeWithText("the routes are out of reach right now").assertIsDisplayed()
        composeRule.onNodeWithText("try again").assert(isButton()).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `a failed retry over a held list shows its line above the rows, with no try again`() {
        showCatalog(installed = null, failure = PilgrimageError.CATALOG_UNREACHABLE)

        composeRule.onNodeWithText("the routes are out of reach right now").assertIsDisplayed()
        composeRule.onAllNodesWithText("try again").assertCountEquals(0)
        assertTrue(
            top(composeRule.onNodeWithText("the routes are out of reach right now")) <
                top(composeRule.onNodeWithText(entry.name, substring = true)),
        )
    }

    /** iOS `PilgrimageCatalogView.swift:111-117@7c200bf`: `Padding.normal` from the edge, `Padding.small` above. */
    @Test
    fun `the failed retry's line stands 16 in from the sheet's edge and 8 under the bar`() {
        var failure by mutableStateOf<PilgrimageError?>(null)
        show {
            CatalogContent(
                catalog = PilgrimageCatalog(RELEASE, listOf(entry)),
                state = PilgrimageCatalogUiState(isLoading = false, failure = failure),
            )
        }
        val underTheBar = composeRule.onNodeWithText(entry.name, substring = true).getUnclippedBoundsInRoot().top

        failure = PilgrimageError.CATALOG_UNREACHABLE

        composeRule.onNodeWithText("the routes are out of reach right now")
            .assertLeftPositionInRootIsEqualTo(16.dp)
            .assertTopPositionInRootIsEqualTo(underTheBar + 8.dp)
    }

    // ---- P4 §4: the route page ----

    @Test
    fun `the bar's back control reads Pilgrimages, and only an installed route has the ellipsis`() {
        var backs = 0
        showRoute(page(), actions = actions(onBack = { backs++ }))

        composeRule.onNodeWithText(entry.name).assert(isHeading())
        composeRule.onNodeWithContentDescription("Pilgrimages").assertHasClickAction().performClick()
        assertEquals(1, backs)
        composeRule.onAllNodesWithContentDescription("ellipsis").assertCountEquals(0)
    }

    @Test
    fun `the ellipsis opens Remove`() {
        var removes = 0
        showRoute(page(installed = installed(release = RELEASE)), actions = actions(onRemove = { removes++ }))

        composeRule.onNodeWithContentDescription("ellipsis").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Remove").performClick()
        assertEquals(1, removes)
    }

    @Test
    fun `while any download runs the button is held, its label unchanged, and the footer counts stages`() {
        showRoute(page(installed = installed(release = RELEASE)), phase = PilgrimagePackageManager.Phase.Downloading(done = 2, total = 3))

        composeRule.onNodeWithText("On your phone").assert(isButton()).assertIsNotEnabled()
        composeRule.onNodeWithText("stage 1 of 2").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("ellipsis").assertIsNotEnabled()
    }

    @Test
    fun `Download is a button, a refusal reads in the footer, and so does the redraw notice`() {
        var downloads = 0
        showRoute(
            page(failure = PilgrimageError.WALK_IN_PROGRESS, showRedrawNotice = true),
            actions = actions(onDownload = { downloads++ }),
        )

        composeRule.onNodeWithText("Download").assert(isButton()).assertIsEnabled().performClick()
        assertEquals(1, downloads)
        composeRule.onNodeWithText("finish your walk first").assertIsDisplayed()
        composeRule.onNodeWithText("the route's stages were redrawn; your kilometres are kept.").assertIsDisplayed()
        composeRule.onNodeWithText("Christian · Europe").assertIsDisplayed()
    }

    @Test
    fun `the next row is one button, its words over the progress line, and a stage row one button with its circle unspoken`() {
        var nexts = 0
        var opened: Int? = null
        showRoute(
            page(installed = installed(release = RELEASE), ledger = walkedOne()),
            actions = actions(onOpenNext = { nexts++ }, onOpenStage = { opened = it }),
        )

        composeRule.onNodeWithText("next: stage 2", substring = true)
            .assert(isButton())
            .assert(noClickLabel())
            .assert(textIs("next: stage 2", "stage 2 of 33 · 24.2 km walked"))
            .performClick()
        assertEquals(1, nexts)
        composeRule.onNodeWithText("2. Roncesvalles to Zubiri", substring = true)
            .assert(isButton())
            .assert(textIs("2. Roncesvalles to Zubiri", "21.9 km · 532 m up · 5 to 6 hours · moderate"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            .performClick()
        assertEquals(1, opened)
        composeRule.onNodeWithText("Stages").assert(isHeading())
    }

    /** iOS `PilgrimageRouteView.swift:290-294@7c200bf`: a caption-font symbol, which Dynamic Type grows. */
    @Test
    fun `a stage row's circle is the caption's size, so it grows with the font scale`() {
        show {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                PilgrimageRouteContent(
                    state = PilgrimageRouteUiState.Ready(page()),
                    phase = PilgrimagePackageManager.Phase.Idle,
                    units = UnitSystem.Metric,
                    alert = null,
                    actions = actions(),
                )
            }
        }

        composeRule.onAllNodesWithTag(STAGE_CIRCLE_TAG, useUnmergedTree = true)[0]
            .assertWidthIsEqualTo(24.dp)
            .assertHeightIsEqualTo(24.dp)
    }

    @Test
    fun `the stages being reached for read so under Stages`() {
        showRoute(page(route = null, isLoadingStages = true))

        composeRule.onNodeWithText("reaching for the stages…").assertIsDisplayed()
    }

    @Test
    fun `a failed preview reads its line over try again`() {
        var retries = 0
        showRoute(page(route = null, stagesFailure = PilgrimageError.CATALOG_UNREACHABLE), actions = actions(onRetryStages = { retries++ }))

        composeRule.onNodeWithText("the routes are out of reach right now").assertIsDisplayed()
        composeRule.onNodeWithText("try again").assert(isButton()).performClick()
        assertEquals(1, retries)
    }

    // ---- P4 §4.7: the alerts ----

    @Test
    fun `Replace asks about the route being let go, with Replace and Keep it`() {
        var replaced = 0
        var kept = 0
        showRoute(
            page(installed = installed(release = RELEASE, routeId = "camino-norte", name = "Camino del Norte")),
            alert = PilgrimageRouteAlert.REPLACE,
            actions = actions(onConfirmReplace = { replaced++ }, onDismissAlert = { kept++ }),
        )

        composeRule.onNodeWithText("Replace?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Replace the Camino del Norte? Its stages leave your phone; what you've walked of it is remembered if it comes back. Walks in your journal stay.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Keep it").assert(isButton()).performClick()
        composeRule.onNodeWithText("Replace").assert(isButton()).performClick()
        assertEquals(1, replaced)
        assertEquals(1, kept)
    }

    @Test
    fun `Remove names this route, with Remove and Keep it`() {
        var removed = 0
        showRoute(
            page(installed = installed(release = RELEASE)),
            alert = PilgrimageRouteAlert.REMOVE,
            actions = actions(onConfirmRemove = { removed++ }),
        )

        composeRule.onNodeWithText("Remove?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Remove the Camino de Santiago (Francés)? Its stages leave your phone; what you've walked of it is remembered if it comes back. Walks in your journal stay.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Keep it").assert(isButton())
        composeRule.onAllNodesWithText("Remove").assertCountEquals(1)
        composeRule.onNodeWithText("Remove").assert(isButton()).performClick()
        assertEquals(1, removed)
    }

    @Test
    fun `Download this route first offers Download and Not now`() {
        var downloads = 0
        var notNow = 0
        showRoute(page(), alert = PilgrimageRouteAlert.DOWNLOAD_FIRST, actions = actions(onConfirmDownloadFirst = { downloads++ }, onDismissAlert = { notNow++ }))

        composeRule.onNodeWithText("Download this route first?").assertIsDisplayed()
        composeRule.onNodeWithText("Its stages have to be on your phone before you can walk one.").assertIsDisplayed()
        composeRule.onNodeWithText("Not now").assert(isButton()).performClick()
        composeRule.onNode(hasText("Download") and hasAnyAncestor(isDialog())).assert(isButton()).performClick()
        assertEquals(1, downloads)
        assertEquals(1, notNow)
    }

    @Test
    fun `a page still reading its catalog shows only its bar`() {
        show {
            PilgrimageRouteContent(
                state = PilgrimageRouteUiState.Resolving,
                phase = PilgrimagePackageManager.Phase.Idle,
                units = UnitSystem.Metric,
                alert = PilgrimageRouteAlert.REMOVE,
                actions = actions(),
            )
        }

        composeRule.onNodeWithContentDescription("Pilgrimages").assertExists()
        composeRule.onAllNodesWithText("Download").assertCountEquals(0)
        composeRule.onAllNodesWithText("Remove?").assertCountEquals(0)
    }

    // ---- Fixtures ----

    private val entry = PilgrimageCatalogEntry(
        id = ROUTE_ID,
        name = "Camino de Santiago (Francés)",
        names = emptyMap(),
        country = "ES",
        region = "Europe",
        distanceKm = 764.0,
        tradition = "christian",
        stageCount = 33,
        bytes = 2_140_000,
        placesPerStage = 0.4,
        sparse = true,
    )

    private val stages = listOf(
        PilgrimageRouteStage(0, "Saint-Jean-Pied-de-Port to Roncesvalles", 24.2, 1_419.0, WayStageHours(7.0, 9.0), "hard"),
        PilgrimageRouteStage(1, "Roncesvalles to Zubiri", 21.9, 532.0, WayStageHours(5.0, 6.0), "moderate"),
    )

    private fun route(name: String = entry.name, routeId: String = ROUTE_ID) = PilgrimageRoute(
        id = routeId,
        name = name,
        names = emptyMap(),
        country = "ES",
        region = "Europe",
        distanceKm = 764.0,
        stageCount = 33,
        tradition = "christian",
        summary = null,
        stages = stages,
    )

    private fun installed(release: String, routeId: String = ROUTE_ID, name: String = entry.name) =
        PilgrimagePackageManager.Installed(routeId = routeId, release = release, route = route(name, routeId))

    private fun walkedOne() = PilgrimageLedger(ROUTE_ID)
        .recorded(0, stages[0].name, 24.2, HonorStageOutcome(progressFrac = 1.0, arrived = true), Instant.ofEpochSecond(1_800_000_000))

    private fun page(
        installed: PilgrimagePackageManager.Installed? = null,
        route: PilgrimageRoute? = route(),
        ledger: PilgrimageLedger? = null,
        failure: PilgrimageError? = null,
        isLoadingStages: Boolean = false,
        stagesFailure: PilgrimageError? = null,
        showRedrawNotice: Boolean = false,
    ) = PilgrimageRoutePage(
        entry = entry,
        release = RELEASE,
        installed = installed,
        route = route,
        ledger = ledger,
        failure = failure,
        isLoadingStages = isLoadingStages,
        stagesFailure = stagesFailure,
        showRedrawNotice = showRedrawNotice,
    )

    private fun actions(
        onBack: () -> Unit = {},
        onDownload: () -> Unit = {},
        onOpenNext: () -> Unit = {},
        onOpenStage: (Int) -> Unit = {},
        onRetryStages: () -> Unit = {},
        onRemove: () -> Unit = {},
        onConfirmReplace: () -> Unit = {},
        onConfirmRemove: () -> Unit = {},
        onConfirmDownloadFirst: () -> Unit = {},
        onDismissAlert: () -> Unit = {},
    ) = PilgrimageRouteActions(
        onBack, onDownload, onOpenNext, onOpenStage, onRetryStages, onRemove,
        onConfirmReplace, onConfirmRemove, onConfirmDownloadFirst, onDismissAlert,
    )

    private fun showRoute(
        page: PilgrimageRoutePage,
        phase: PilgrimagePackageManager.Phase = PilgrimagePackageManager.Phase.Idle,
        alert: PilgrimageRouteAlert? = null,
        actions: PilgrimageRouteActions = actions(),
    ) = show {
        PilgrimageRouteContent(
            state = PilgrimageRouteUiState.Ready(page),
            phase = phase,
            units = UnitSystem.Metric,
            alert = alert,
            actions = actions,
        )
    }

    private fun showCatalog(
        installed: PilgrimagePackageManager.Installed?,
        ledgers: Map<String, PilgrimageLedger> = emptyMap(),
        failure: PilgrimageError? = null,
        extra: List<PilgrimageCatalogEntry> = emptyList(),
        onOpen: (String) -> Unit = {},
    ) = show {
        CatalogContent(
            catalog = PilgrimageCatalog(RELEASE, listOf(entry) + extra),
            state = PilgrimageCatalogUiState(isLoading = false, failure = failure, installed = installed, ledgers = ledgers),
            onOpen = onOpen,
        )
    }

    @Composable
    private fun CatalogContent(
        catalog: PilgrimageCatalog?,
        state: PilgrimageCatalogUiState,
        onRetry: () -> Unit = {},
        onOpen: (String) -> Unit = {},
    ) = PilgrimageCatalogContent(
        catalog = catalog,
        state = state,
        units = UnitSystem.Metric,
        onClose = {},
        onRetry = onRetry,
        onOpen = onOpen,
    )

    private fun top(node: SemanticsNodeInteraction): Float = node.fetchSemanticsNode().boundsInRoot.top

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun noClickLabel() = SemanticsMatcher("no click label, as iOS gives no hint") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == null
    }

    /** The merged element's texts, in reading order. */
    private fun textIs(vararg texts: String) = SemanticsMatcher("reads ${texts.toList()}") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } == texts.toList()
    }

    private companion object {
        const val ROUTE_ID = "camino-frances"
        const val RELEASE = "v1.7.0"
    }
}
