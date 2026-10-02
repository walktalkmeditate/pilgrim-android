// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * The Mapbox and OpenStreetMap credits in About's Data Sources section,
 * iOS parity `AboutView.swift:306-363@7c200bf`: a maps paragraph and two
 * rows between the weather row and the routes paragraph, each opening
 * iOS's page in a Custom Tab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DataSourcesSectionTest {

    @get:Rule val composeRule = createComposeRule()

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        composeRule.setContent {
            activity = checkNotNull(LocalActivity.current)
            PilgrimTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DataSourcesSection()
                }
            }
        }
    }

    private fun top(text: String): Float =
        composeRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    private fun launchedUriAfterTapping(text: String): Uri? {
        composeRule.onNodeWithText(text).performScrollTo().performClick()
        composeRule.waitForIdle()
        val started: Intent? = shadowOf(activity).nextStartedActivity
        assertNotNull("expected tapping \"$text\" to launch a page", started)
        return started!!.data
    }

    @Test
    fun `the map credits sit between the weather row and the routes row, in iOS order`() {
        val weather = top("Open-Meteo — Attribution & terms")
        val maps = top(MAPS_BODY)
        val mapbox = top(MAPBOX)
        val osm = top(OSM)
        val routes = top("Pilgrimage routes — open-pilgrimages")

        assertTrue("maps paragraph follows the weather row", weather < maps)
        assertTrue("Mapbox row follows the maps paragraph", maps < mapbox)
        assertTrue("OpenStreetMap row follows the Mapbox row", mapbox < osm)
        assertTrue("routes row follows the OpenStreetMap row", osm < routes)
    }

    @Test
    fun `tapping the Mapbox credit opens Mapbox's maps attribution page`() {
        assertEquals("https://www.mapbox.com/about/maps/".toUri(), launchedUriAfterTapping(MAPBOX))
    }

    @Test
    fun `tapping the OpenStreetMap credit opens OSM's copyright page`() {
        assertEquals("https://www.openstreetmap.org/copyright".toUri(), launchedUriAfterTapping(OSM))
    }

    private companion object {
        const val MAPS_BODY = "The maps you walk on are drawn by Mapbox from OpenStreetMap, whose roads and " +
            "paths are surveyed and kept current by people who walk them."
        const val MAPBOX = "© Mapbox"
        const val OSM = "© OpenStreetMap contributors"
    }
}
