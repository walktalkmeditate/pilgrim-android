// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.collective.routes.CollectiveRoute
import org.walktalkmeditate.pilgrim.data.collective.routes.CollectiveRouteCatalog
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme
import org.walktalkmeditate.pilgrim.ui.walk.summary.RevealPhase

/**
 * Ports iOS `UnitTests/CollectiveTrailSectionTests.swift@9a418e4`
 * one-for-one (parity spec
 * `docs/parity/2026-07-23-port-collective-trail-u6.md`): the render
 * gate as a pure function, the walk-date anchor, and the curator
 * render budget. The line's *content* belongs to
 * [org.walktalkmeditate.pilgrim.data.collective.routes.CollectiveRouteCatalogTest];
 * what is owned here is whether it appears. iOS's third test class
 * (`CollectiveContributionLogTests`) was ported in U4
 * (`ContributionLedgerTest`) and is not re-ported.
 *
 * The compose cases at the bottom are Android-only: iOS leaves the
 * view wiring untested, Android pins that a gate-null body emits no
 * node (so the summary's spacedBy Column inserts no gap).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CollectiveTrailSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val walkDay = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli()
    private val reopenedOn = Instant.parse("2026-10-12T12:00:00Z").toEpochMilli()

    private val parityCatalog: CollectiveRouteCatalog by lazy {
        val stream = checkNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "collective/collective-routes-parity-fixture.json",
            ),
        ) { "missing test resource collective/collective-routes-parity-fixture.json" }
        CollectiveRouteCatalog.decode(stream.bufferedReader().readText())
    }

    private val line: String by lazy {
        checkNotNull(parityCatalog.contributionLine(walkDay, walkKm = 4.2, units = UnitSystem.Metric))
    }

    // --- The render gate ------------------------------------------------

    // AE1. A pilgrim who keeps their walks to themselves is told nothing
    // about a counter they never moved.
    @Test
    fun `gate - a walk that was not contributed renders nothing`() {
        assertNull(collectiveTrailRenderedLine(wasContributed = false, contributionLine = line))
    }

    @Test
    fun `gate - contributed with a loaded catalog renders the line unchanged`() {
        assertEquals(
            line,
            collectiveTrailRenderedLine(wasContributed = true, contributionLine = line),
        )
    }

    // The catalog is EMPTY for the first frames of every summary — the
    // load is detached — and stays EMPTY if the artifact failed to
    // decode. Half a line is worse than none. (iOS models "not loaded"
    // as nil; Android's U3 service publishes EMPTY pre-load.)
    @Test
    fun `gate - catalog not yet loaded renders nothing`() {
        val resolved = CollectiveRouteCatalog.EMPTY
            .contributionLine(walkDay, walkKm = 4.2, units = UnitSystem.Metric)
        assertNull(collectiveTrailRenderedLine(wasContributed = true, contributionLine = resolved))
    }

    @Test
    fun `gate - empty catalog renders nothing`() {
        val resolved = CollectiveRouteCatalog("v1", emptyList())
            .contributionLine(walkDay, walkKm = 4.2, units = UnitSystem.Metric)
        assertNull(collectiveTrailRenderedLine(wasContributed = true, contributionLine = resolved))
    }

    // The Settings line states the collective's progress and cannot
    // invent it; this line states the walk's own distance against a
    // fixed route length and never needs a total at all. That asymmetry
    // is what lets a walk that ended on day twelve with no signal still
    // say something true.
    @Test
    fun `gate - is independent of the collective total`() {
        assertNotNull(
            "The walk-summary line must survive a collective total that never arrived",
            collectiveTrailRenderedLine(wasContributed = true, contributionLine = line),
        )
        assertNull(
            "The Settings line is the surface that does suppress itself without a total",
            parityCatalog.dailyLine(walkDay, collectiveKm = null, units = UnitSystem.Metric),
        )
    }

    // --- Date anchor ------------------------------------------------------
    // The summary is presented for any walk opened from the journal, not
    // only for one that just ended. Anchoring to now() would hand an old
    // walk a different route every time it was reopened.

    @Test
    fun `line resolves the walks own UTC day not another`() {
        // Pinned so a reshuffle of the selection cannot quietly turn this
        // into a comparison of one route against itself.
        assertEquals("camino-primitivo", parityCatalog.entry(walkDay)?.id)
        assertEquals("around-earth", parityCatalog.entry(reopenedOn)?.id)

        val walkDayLine = checkNotNull(
            parityCatalog.contributionLine(walkDay, walkKm = 4.2, units = UnitSystem.Metric),
        )
        assertTrue(walkDayLine.contains("Camino Primitivo"))
        assertNotEquals(
            walkDayLine,
            parityCatalog.contributionLine(reopenedOn, walkKm = 4.2, units = UnitSystem.Metric),
        )
    }

    // Midnight and one second to midnight UTC straddle the local-day
    // boundary everywhere on earth, so agreeing across both means the
    // walk's own UTC day is what decides, whatever the pilgrim's zone.
    @Test
    fun `line holds across the whole UTC day of the walk`() {
        val dayStart = Instant.parse("2026-10-07T00:00:00Z").toEpochMilli()
        val dayEnd = Instant.parse("2026-10-07T23:59:59Z").toEpochMilli()
        assertEquals(
            parityCatalog.contributionLine(dayStart, walkKm = 4.2, units = UnitSystem.Metric),
            parityCatalog.contributionLine(dayEnd, walkKm = 4.2, units = UnitSystem.Metric),
        )
    }

    // The longest string the feature can produce, and the budget the
    // section's line limit and scale floor were sized against. Measured
    // against the bundled artifact rather than the fixture above:
    // company sentences are curator-editable after ship — which is the
    // entire reason this exists — so measuring a frozen transcription of
    // them means a 300-character sentence could reach a pilgrim's screen
    // with this still green.
    @Test
    fun `line longest phrasing stays within the render budget`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shipped = CollectiveRouteCatalog.decode(
            context.assets.open("collective/collective-routes-bootstrap.json")
                .bufferedReader()
                .use { it.readText() },
        )
        val longest = checkNotNull(
            shipped.entries
                .map { it.contributionLine(walkKm = 12.34, units = UnitSystem.Metric) }
                .maxByOrNull { it.length },
        )
        assertTrue(
            "Re-tune maxLines and the autoSize floor before letting this grow (${longest.length})",
            longest.length <= 130,
        )
    }

    // Plan U6: every entry kind phrases — a horizon day still earns its line.
    @Test
    fun `horizon entries produce a line`() {
        val horizonOnly = CollectiveRouteCatalog(
            "v1",
            listOf(
                CollectiveRoute(
                    id = "around-earth",
                    kind = CollectiveRoute.Kind.Cosmic("around", "the Earth"),
                    km = 40_075.0,
                    companyLine = "No one has walked it alone.",
                ),
            ),
        )
        val resolved = horizonOnly.contributionLine(walkDay, walkKm = 4.2, units = UnitSystem.Metric)
        assertNotNull(
            collectiveTrailRenderedLine(wasContributed = true, contributionLine = resolved),
        )
    }

    // --- Composition wiring (Android-only) --------------------------------

    @Test
    fun `section emits nothing for a non-contributed walk`() {
        composeRule.setContent {
            PilgrimTheme {
                CollectiveTrailSection(
                    contributionLine = line,
                    wasContributed = false,
                    revealPhase = RevealPhase.Revealed,
                    reduceMotion = true,
                )
            }
        }
        composeRule.onNodeWithText(line).assertDoesNotExist()
    }

    @Test
    fun `section shows the resolved line once revealed`() {
        composeRule.setContent {
            PilgrimTheme {
                CollectiveTrailSection(
                    contributionLine = line,
                    wasContributed = true,
                    revealPhase = RevealPhase.Revealed,
                    reduceMotion = true,
                )
            }
        }
        composeRule.onNodeWithText(line).assertIsDisplayed()
    }
}
