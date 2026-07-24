// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective.routes

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.walktalkmeditate.pilgrim.data.FakePreferencesDataStore
import org.walktalkmeditate.pilgrim.data.collective.ContributionLedger

/**
 * Shared across every [bootstrapRouteCatalogService] call: the client is
 * never used (the URL resolves nowhere and no test calls `syncIfNeeded`),
 * so per-test instances only cost threads.
 */
private val sharedHttpClient = OkHttpClient()

/**
 * The U3 service over the bundled bootstrap asset — never fetches
 * (`syncIfNeeded` is not called and the URL resolves nowhere). Tests that
 * need the catalog await `initialLoad`. [bootstrapAssetPath] is overridable
 * for the missing-bootstrap path.
 */
fun bootstrapRouteCatalogService(
    context: Context,
    scope: CoroutineScope,
    bootstrapAssetPath: String = CollectiveRoutesConfig.BOOTSTRAP_ASSET_PATH,
): CollectiveRouteCatalogService = CollectiveRouteCatalogService(
    context = context,
    httpClient = sharedHttpClient,
    scope = scope,
    catalogUrl = "http://localhost/routes.json",
    bootstrapAssetPath = bootstrapAssetPath,
)

/** A ledger over a fresh in-memory store — no cross-test state. */
fun inMemoryContributionLedger(): ContributionLedger = ContributionLedger(
    FakePreferencesDataStore(),
    Json { ignoreUnknownKeys = true; explicitNulls = false },
)

/**
 * The production parity fixture — the transcription every webPicks/webLines
 * vector is generated from (see `CollectiveRouteBundledArtifactTest` for
 * what keeps it honest against the shipped bootstrap).
 */
fun loadParityCatalog(): CollectiveRouteCatalog {
    val stream = checkNotNull(
        object {}.javaClass.classLoader?.getResourceAsStream(PARITY_FIXTURE_RESOURCE),
    ) { "missing test resource $PARITY_FIXTURE_RESOURCE" }
    return CollectiveRouteCatalog.decode(stream.bufferedReader().readText())
}

private const val PARITY_FIXTURE_RESOURCE = "collective/collective-routes-parity-fixture.json"
