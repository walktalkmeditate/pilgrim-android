// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.path

import android.app.Application
import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimLightColors

/** The Path tab's mode slots with the release flag on and off (parity spec F §3, AE12). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkStartModeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun text(id: Int) = context.getString(id)

    @Test
    fun `AE12 - with the flag off the Honor slot keeps the Together look and coming soon`() {
        val copy = pathModeCopy(WalkMode.Honor, honorEnabled = false)

        assertEquals("TOGETHER", text(copy.label))
        assertEquals("coming soon", text(copy.subtitle))
        assertEquals("Walk Together", text(copy.button))
        assertEquals(R.array.path_quotes_together, copy.quotes)
        assertNull("1.5.0 read the visible label", copy.talkBackLabel)
        assertFalse(WalkMode.Honor.isAvailable(honorEnabled = false))
    }

    @Test
    fun `with the flag on the Honor slot reads as iOS's`() {
        val copy = pathModeCopy(WalkMode.Honor, honorEnabled = true)

        assertEquals("HONOR", text(copy.label))
        assertEquals("walk in their steps", text(copy.subtitle))
        assertEquals("Honor", text(copy.button))
        assertEquals("honor", text(copy.talkBackLabel!!))
        assertTrue(WalkMode.Honor.isAvailable(honorEnabled = true))
    }

    @Test
    fun `the Honor quotes are iOS's three`() {
        val quotes = context.resources.getStringArray(pathModeCopy(WalkMode.Honor, honorEnabled = true).quotes).toList()

        assertEquals(
            listOf("Where they walked,\nyou walk", "Two traveling together", "Their steps\nare still warm"),
            quotes,
        )
        assertTrue(pickRandomQuote(context, WalkMode.Honor, Random(7), honorEnabled = true) in quotes)
    }

    @Test
    fun `with the flag on TalkBack names every mode by its lowercase raw value`() {
        assertEquals(
            listOf("wander", "honor", "seek"),
            WalkMode.entries.map { text(pathModeCopy(it, honorEnabled = true).talkBackLabel!!) },
        )
        assertTrue(WalkMode.entries.all { pathModeCopy(it, honorEnabled = false).talkBackLabel == null })
    }

    @Test
    fun `the Honor button opens the Ways sheet, every other mode the walk screen`() {
        assertEquals(PathButtonAction.ChooseWay, pathButtonAction(WalkMode.Honor, honorEnabled = true))
        assertEquals(PathButtonAction.EnterWalk, pathButtonAction(WalkMode.Wander, honorEnabled = true))
        assertEquals(PathButtonAction.EnterWalk, pathButtonAction(WalkMode.Seek, honorEnabled = true))
        assertEquals(PathButtonAction.EnterWalk, pathButtonAction(WalkMode.Honor, honorEnabled = false))
    }

    @Test
    fun `an unselected mode label is fog at 0_55, as iOS moved to in cbd24fc`() {
        assertEquals(0.55f, UNSELECTED_MODE_LABEL_ALPHA)
    }

    @Test
    fun `the Honor atmosphere is stone at 0_015, and 1_5_0's dawn with the flag off`() {
        val colors = pilgrimLightColors()

        assertEquals(colors.stone.copy(alpha = 0.015f), modeAtmosphere(WalkMode.Honor, honorEnabled = true, colors))
        assertEquals(colors.dawn.copy(alpha = 0.01f), modeAtmosphere(WalkMode.Honor, honorEnabled = false, colors))
    }

    @Test
    fun `the staff leans from 0_65 to 0_35 with a crossbar tilted by an absolute amount`() {
        val (shaft, crossbar) = staffGlyphLines(width = 10f, height = 34f, tilt = 2f)

        assertEquals(Offset(6.5f, 0f) to Offset(3.5f, 34f), shaft)
        assertEquals(Offset(0f, 34f * 0.18f + 2f) to Offset(10f, 34f * 0.18f - 2f), crossbar)
    }
}
