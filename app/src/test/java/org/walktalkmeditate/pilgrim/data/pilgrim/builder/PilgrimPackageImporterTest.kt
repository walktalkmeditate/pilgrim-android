// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim.builder

import android.app.Application
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimWalk

/**
 * AF28 (iOS PR #45): an archive's `walks/` directory can contain a file
 * that fails to decode (truncated export, schema drift, corruption). The
 * import must skip it and continue — but it must also COUNT the skip so
 * the result can be reported honestly instead of a silent partial import.
 *
 * Robolectric only for `android.util.Log` in [decodeWalkFiles]; the logic
 * under test is pure (temp files + JSON).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimPackageImporterTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val tempDir: File = Files.createTempDirectory("importer-decode-test").toFile()

    @After fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun validWalkJson(): String {
        val bundle = WalkExportBundle(
            walk = Walk(
                id = 1,
                uuid = UUID.randomUUID().toString(),
                startTimestamp = 1_000L,
                endTimestamp = 100_000L,
            ),
            routeSamples = emptyList(),
            altitudeSamples = emptyList(),
            walkEvents = emptyList(),
            activityIntervals = emptyList(),
            waypoints = emptyList(),
            voiceRecordings = emptyList(),
            walkPhotos = emptyList(),
        )
        val walk: PilgrimWalk = PilgrimPackageConverter.convert(bundle, includePhotos = false).walk
        return json.encodeToString(walk)
    }

    @Test
    fun `decodeWalkFiles counts undecodable files as skipped`() {
        val good = File(tempDir, "good.json").apply { writeText(validWalkJson()) }
        val bad = File(tempDir, "bad.json").apply { writeText("{ not valid pilgrim json") }

        val result = decodeWalkFiles(listOf(good, bad), json)

        assertEquals("only the good walk decodes", 1, result.walks.size)
        assertEquals("the corrupt file is counted, not silently dropped", 1, result.decodeFailures)
    }

    @Test
    fun `decodeWalkFiles reports zero failures when every file decodes`() {
        val a = File(tempDir, "a.json").apply { writeText(validWalkJson()) }
        val b = File(tempDir, "b.json").apply { writeText(validWalkJson()) }

        val result = decodeWalkFiles(listOf(a, b), json)

        assertEquals(2, result.walks.size)
        assertEquals(0, result.decodeFailures)
    }
}
