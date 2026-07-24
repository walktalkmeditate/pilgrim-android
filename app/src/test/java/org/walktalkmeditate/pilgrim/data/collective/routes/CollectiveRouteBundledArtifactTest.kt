// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective.routes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What keeps the parity vectors honest. Ports iOS
 * `UnitTests/CollectiveRouteBundledArtifactTests.swift@9a418e4`.
 *
 * Every vector in [CollectiveRouteCatalogTest] is pinned against the parity
 * fixture, a transcription. The file the app actually reads is the bundled
 * bootstrap asset — a verbatim copy of iOS's artifact (plan R4). Nothing
 * forces the two to agree, so a re-copied artifact with an eighth entry
 * changes the shipped pool, every date it resolves, and every line a pilgrim
 * reads, while all 62 vectors keep passing against a copy that no longer
 * describes anything.
 *
 * These compare only what selection consumes. Company sentences are
 * curator-editable by design and must be free to change without failing here;
 * ids, distances, seasons and provenance are not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CollectiveRouteBundledArtifactTest {

    private val bundled: CollectiveRouteCatalog by lazy {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val text = context.assets.open("collective/collective-routes-bootstrap.json")
            .bufferedReader()
            .use { it.readText() }
        CollectiveRouteCatalog.decode(text)
    }

    private val fixture: CollectiveRouteCatalog by lazy { loadParityCatalog() }

    // The cheapest guard here: a bake that emits an envelope with nothing in
    // it decodes cleanly — both arrays are optional and every element decodes
    // lossily — so an empty catalog is not a decode failure. It would ship as
    // a silent, permanent absence of the line.
    @Test
    fun `bundled artifact decodes through the production path into entries`() {
        assertFalse(
            "The shipped bootstrap decoded to nothing — a bad copy reached the assets",
            bundled.entries.isEmpty(),
        )
        assertFalse(
            "The app compares versions to decide when to refresh",
            bundled.version.isEmpty(),
        )
    }

    @Test
    fun `bundled artifact selects the same entries in the same order as the parity fixture`() {
        assertEquals(DRIFT_ADVICE, fixture.entries.map { it.id }, bundled.entries.map { it.id })
    }

    @Test
    fun `bundled artifact carries the same distances as the parity fixture`() {
        assertEquals(DRIFT_ADVICE, fixture.entries.map { it.km }, bundled.entries.map { it.km })
    }

    // Weight is what a month's selection is built from, so a curator widening
    // one route's season re-resolves dates that route never claimed.
    @Test
    fun `bundled artifact carries the same seasons as the parity fixture`() {
        assertEquals(DRIFT_ADVICE, fixture.entries.map { it.bestMonths }, bundled.entries.map { it.bestMonths })
        assertEquals(DRIFT_ADVICE, fixture.entries.map { it.peakMonths }, bundled.entries.map { it.peakMonths })
    }

    // Which array an entry shipped in decides where it lands in the pool, and
    // a cosmic entry mis-filed among the pilgrimages splits Android from the
    // web without either side complaining. Kind equality also carries
    // nameEn/preposition/body, so a re-copied bootstrap that renames a route
    // fails here instead of shipping phrasing the vectors never pinned.
    @Test
    fun `bundled artifact files each entry under the same kind as the parity fixture`() {
        assertEquals(DRIFT_ADVICE, fixture.entries.map { it.kind }, bundled.entries.map { it.kind })
    }

    private companion object {
        const val DRIFT_ADVICE =
            "— the bundled artifact and the parity fixture have diverged. Every webPicks/webLines " +
                "vector is generated from the fixture, so they now pin a pool the app does not use. " +
                "Re-copy the artifact from pilgrim-ios, regenerate the vectors " +
                "(../pilgrim-landing/js/collective-routes.js) and update the fixture together."
    }
}
