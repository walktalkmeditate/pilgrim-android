// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError
import org.walktalkmeditate.pilgrim.honor.HonorLinkToast
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/** The stage-link lines, verbatim from iOS (`MainCoordinatorView.swift:231,275,299,307,327@3b78728`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorLinkToastViewTest {

    @get:Rule val composeRule = createComposeRule()

    private val toast = mutableStateOf<HonorLinkToast?>(null)

    @Test
    fun `each stage-link line is iOS's, verbatim`() {
        composeRule.setContent { PilgrimTheme { HonorLinkToastView(toast = toast.value) } }

        listOf(
            HonorLinkToast.NeedsNewerPilgrim to "this link needs a newer Pilgrim",
            HonorLinkToast.ReachingForRoute to "reaching for the route\u2026",
            HonorLinkToast.RouteNotInPilgrim to "this route isn't in Pilgrim yet",
            HonorLinkToast.CatalogFailed(PilgrimageError.CATALOG_UNREACHABLE) to "the routes are out of reach right now",
            HonorLinkToast.FinishWalkFirst to "finish this walk first",
        ).forEach { (line, text) ->
            composeRule.runOnIdle { toast.value = line }
            composeRule.waitUntil(WAIT_MILLIS) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
