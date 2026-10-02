// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.WayError

/**
 * Port of iOS `HonorImportReducerTests.swift@7c200bf`, plus every line of
 * the copy table exactly (shared-walk spec S1 §6.1): Robolectric only for
 * the strings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorImportReducerTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun transitions() {
        val id = "share:abc"
        assertEquals(
            HonorImportState.Gathering(progress = 0.4),
            HonorImportReducer.state(id, progress = mapOf(id to 0.4), active = setOf(id), failures = emptyMap(), diskFull = emptySet()),
        )
        assertEquals(
            HonorImportState.Ready,
            HonorImportReducer.state(id, progress = mapOf(id to 1.0), active = emptySet(), failures = emptyMap(), diskFull = emptySet()),
        )
        assertEquals(
            HonorImportState.MediaMissing(listOf("audio/2.m4a")),
            HonorImportReducer.state(
                id,
                progress = mapOf(id to 1.0),
                active = emptySet(),
                failures = mapOf(id to listOf("audio/2.m4a")),
                diskFull = emptySet(),
            ),
        )
        assertEquals(
            "disk full outranks everything",
            HonorImportState.Failed(WayError.DISK_FULL),
            HonorImportReducer.state(id, progress = emptyMap(), active = setOf(id), failures = emptyMap(), diskFull = setOf(id)),
        )
    }

    @Test
    fun `gathering with no progress yet reads 0, and an empty failure list is ready`() {
        val id = "share:abc"
        assertEquals(
            HonorImportState.Gathering(progress = 0.0),
            HonorImportReducer.state(id, progress = emptyMap(), active = setOf(id), failures = emptyMap(), diskFull = emptySet()),
        )
        assertEquals(
            HonorImportState.Ready,
            HonorImportReducer.state(id, progress = emptyMap(), active = emptySet(), failures = mapOf(id to emptyList()), diskFull = emptySet()),
        )
        assertEquals(
            "another Way's sets say nothing about this one",
            HonorImportState.Ready,
            HonorImportReducer.state(
                id,
                progress = emptyMap(),
                active = setOf("share:other"),
                failures = mapOf("share:other" to listOf("audio/1.m4a")),
                diskFull = setOf("share:other"),
            ),
        )
    }

    @Test
    fun `copy names every failure`() {
        assertEquals(
            "not enough space on this phone to save these voices",
            line(HonorImportState.Failed(WayError.DISK_FULL)),
        )
        assertNotNull(line(HonorImportState.Failed(WayError.NOT_FOUND)))
        assertNull(line(HonorImportState.Ready))
    }

    @Test
    fun `copy gathering rounds to a whole percent`() {
        assertEquals("gathering their voices · 46%", line(HonorImportState.Gathering(progress = 0.456)))
        assertEquals("half away from zero, not to even", "gathering their voices · 13%", line(HonorImportState.Gathering(progress = 0.125)))
        assertEquals("gathering their voices · 100%", line(HonorImportState.Gathering(progress = 1.0)))
    }

    @Test
    fun `copy media missing`() {
        assertEquals("some voices didn't arrive", line(HonorImportState.MediaMissing(listOf("audio/2.m4a"))))
    }

    @Test
    fun `copy idle is silent`() {
        assertNull(line(HonorImportState.Idle))
    }

    @Test
    fun `every line is iOS's, character for character`() {
        assertEquals("reaching for the walk" + Char(0x2026), line(HonorImportState.Fetching))
        assertEquals(
            "couldn't find that walk. Check the link, or it may have returned to the trail.",
            line(HonorImportState.Failed(WayError.NOT_FOUND)),
        )
        assertEquals("This walk has returned to the trail", line(HonorImportState.Failed(WayError.RETURNED_TO_TRAIL)))
        assertEquals("couldn't reach the walk", line(HonorImportState.Failed(WayError.UNAVAILABLE)))
        assertEquals(Char(0x00B7), line(HonorImportState.Gathering(0.5))!!["gathering their voices ".length])
    }

    private fun line(state: HonorImportState) = HonorImportCopy.line(resources, state)
}
