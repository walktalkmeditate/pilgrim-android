// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import java.util.UUID
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.FakeShareAudioTranscoder
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem

/**
 * Exercises [SharePrepStore] against a real Robolectric `cacheDir` and
 * [FakeShareAudioTranscoder]. Cancellation tests mirror
 * `TranscriptionRunnerTest`'s idiom: a real (non-virtual) delay in the
 * fake, `runBlocking` (not `runTest`), and busy-polling for proof the
 * fake actually started before cancelling — StateFlow is conflated, so
 * asserting an intermediate state via Turbine timing alone would race.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SharePrepStoreTest {

    private lateinit var context: Context
    private lateinit var fakeTranscoder: FakeShareAudioTranscoder
    private lateinit var fileSystem: VoiceRecordingFileSystem
    private lateinit var store: SharePrepStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fakeTranscoder = FakeShareAudioTranscoder()
        fileSystem = VoiceRecordingFileSystem(context)
        store = SharePrepStore(context, fakeTranscoder, fileSystem)
    }

    @Test
    fun `prepare takes a recording from Preparing to Ready with the real artifact size`() = runBlocking {
        val walkUuid = "walk-1"
        val rec = recording(walkUuid)
        fakeTranscoder.delaysMs[wavFileFor(rec)] = 200L
        fakeTranscoder.outputBytesFor[wavFileFor(rec)] = 2_048

        val prepJob = launch { store.prepare(walkUuid, listOf(rec)) }
        awaitState(walkUuid, rec.uuid) { it is PrepState.Preparing }
        prepJob.join()

        val finalState = store.state.value[walkUuid]?.get(rec.uuid)
        assertTrue(finalState is PrepState.Ready)
        assertEquals(2_048L, (finalState as PrepState.Ready).sizeBytes)
        assertEquals(2_048L, store.artifactFile(walkUuid, rec.uuid).length())
    }

    @Test
    fun `state starts empty and Turbine observes the settled Ready value`() = runBlocking {
        val walkUuid = "walk-turbine"
        val rec = recording(walkUuid)

        store.state.test {
            assertEquals(emptyMap<String, Map<String, PrepState>>(), awaitItem())
        }

        store.prepare(walkUuid, listOf(rec))

        store.state.test {
            val item = awaitItem()
            assertTrue(item[walkUuid]?.get(rec.uuid) is PrepState.Ready)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `prepare processes recordings sequentially in order`() = runBlocking {
        val walkUuid = "walk-seq"
        val recA = recording(walkUuid)
        val recB = recording(walkUuid)
        val recC = recording(walkUuid)

        store.prepare(walkUuid, listOf(recA, recB, recC))

        assertEquals(
            listOf(wavFileFor(recA), wavFileFor(recB), wavFileFor(recC)),
            fakeTranscoder.calls,
        )
    }

    @Test
    fun `cancelRecording during encode deletes the partial artifact and clears state`() = runBlocking {
        val walkUuid = "walk-cancel"
        val rec = recording(walkUuid)
        fakeTranscoder.delaysMs[wavFileFor(rec)] = 10_000L

        val prepJob = launch { store.prepare(walkUuid, listOf(rec)) }
        awaitFakeCalled(rec)
        store.cancelRecording(walkUuid, rec.uuid)
        prepJob.join()

        assertFalse(store.artifactFile(walkUuid, rec.uuid).exists())
        assertNull(store.state.value[walkUuid]?.get(rec.uuid))
    }

    @Test
    fun `exclusion mid-run cancels only that recording and prepare continues to the next`() = runBlocking {
        val walkUuid = "walk-exclude"
        val recA = recording(walkUuid)
        val recB = recording(walkUuid)
        fakeTranscoder.delaysMs[wavFileFor(recA)] = 10_000L

        val prepJob = launch { store.prepare(walkUuid, listOf(recA, recB)) }
        awaitFakeCalled(recA)
        store.cancelRecording(walkUuid, recA.uuid)
        prepJob.join()

        assertFalse("recA's partial artifact must be deleted", store.artifactFile(walkUuid, recA.uuid).exists())
        assertNull("recA must have no state entry after exclusion", store.state.value[walkUuid]?.get(recA.uuid))
        val recBState = store.state.value[walkUuid]?.get(recB.uuid)
        assertTrue("recB must still have been prepared to Ready", recBState is PrepState.Ready)
    }

    @Test
    fun `re-prepare reuses an existing Ready artifact without invoking the transcoder again`() = runBlocking {
        val walkUuid = "walk-reuse"
        val rec = recording(walkUuid)

        store.prepare(walkUuid, listOf(rec))
        assertEquals(1, fakeTranscoder.calls.size)

        store.prepare(walkUuid, listOf(rec))

        assertEquals("transcoder must not be invoked a second time", 1, fakeTranscoder.calls.size)
        assertTrue(store.state.value[walkUuid]?.get(rec.uuid) is PrepState.Ready)
    }

    @Test
    fun `ensureArtifact returns the existing file without re-encoding when present`() = runBlocking {
        val walkUuid = "walk-ensure-hit"
        val rec = recording(walkUuid)
        store.prepare(walkUuid, listOf(rec))
        assertEquals(1, fakeTranscoder.calls.size)

        val artifact = store.ensureArtifact(walkUuid, rec)

        assertEquals(store.artifactFile(walkUuid, rec.uuid), artifact)
        assertEquals(1, fakeTranscoder.calls.size)
    }

    @Test
    fun `ensureArtifact re-encodes when the artifact file was deleted out from under the store`() = runBlocking {
        val walkUuid = "walk-ensure-miss"
        val rec = recording(walkUuid)
        store.prepare(walkUuid, listOf(rec))
        assertEquals(1, fakeTranscoder.calls.size)
        assertTrue(store.artifactFile(walkUuid, rec.uuid).delete())

        val artifact = store.ensureArtifact(walkUuid, rec)

        assertEquals(2, fakeTranscoder.calls.size)
        assertTrue(artifact != null && artifact.exists())
    }

    @Test
    fun `ensureArtifact returns null when re-encoding fails`() = runBlocking {
        val walkUuid = "walk-ensure-fail"
        val rec = recording(walkUuid)
        fakeTranscoder.failures[wavFileFor(rec)] = IllegalStateException("boom")

        val artifact = store.ensureArtifact(walkUuid, rec)

        assertEquals(null, artifact)
        assertTrue(store.state.value[walkUuid]?.get(rec.uuid) is PrepState.Failed)
    }

    @Test
    fun `a transcode failure marks the recording Failed`() = runBlocking {
        val walkUuid = "walk-fail"
        val rec = recording(walkUuid)
        fakeTranscoder.failures[wavFileFor(rec)] = RuntimeException("encoder blew up")

        store.prepare(walkUuid, listOf(rec))

        assertTrue(store.state.value[walkUuid]?.get(rec.uuid) is PrepState.Failed)
        assertFalse(store.artifactFile(walkUuid, rec.uuid).exists())
    }

    @Test
    fun `cancelAndCleanupWalk cancels in-flight jobs, clears state, and removes the walk dir`() = runBlocking {
        val walkUuid = "walk-cleanup"
        val recA = recording(walkUuid)
        val recB = recording(walkUuid)
        fakeTranscoder.delaysMs[wavFileFor(recB)] = 10_000L

        val prepJob = launch { store.prepare(walkUuid, listOf(recA, recB)) }
        awaitFakeCalled(recB) // recA already landed Ready; recB is now mid-encode
        store.cancelAndCleanupWalk(walkUuid)
        prepJob.cancelAndJoin()

        assertTrue(store.state.value[walkUuid].isNullOrEmpty())
        assertFalse(store.artifactFile(walkUuid, recA.uuid).parentFile!!.exists())
    }

    @Test
    fun `cancelAndCleanupWalk refuses to delete a directory outside the share-prep root`() = runBlocking {
        val walkUuid = "walk-untouched"
        val rec = recording(walkUuid)
        store.prepare(walkUuid, listOf(rec))
        val sentinelDir = File(context.cacheDir, "escape-sentinel").apply { mkdirs() }
        // Named like a real artifact (.m4a) so this test isolates the
        // root-containment guard specifically, rather than incidentally
        // being saved by the (separate) extension guard.
        val sentinelFile = File(sentinelDir, "do-not-delete.m4a").apply { writeText("still here") }

        // ".." resolves one level above share-prep/, landing on a sibling
        // of the share-prep root itself — i.e. still inside cacheDir, but
        // outside the sandbox this store is allowed to touch.
        store.cancelAndCleanupWalk("../escape-sentinel")

        assertTrue("directory outside share-prep root must survive", sentinelDir.exists())
        assertTrue("file inside it must survive", sentinelFile.exists())
        // The legitimately-tracked walk must be untouched by the refused call.
        assertTrue(store.artifactFile(walkUuid, rec.uuid).exists())
    }

    @Test
    fun `sweepOrphans removes dirs not in the keep-set and spares kept ones`() = runBlocking {
        val kept = UUID.randomUUID().toString()
        val orphan = UUID.randomUUID().toString()
        seedArtifactFile(kept, "a")
        seedArtifactFile(orphan, "b")

        val removed = store.sweepOrphans(setOf(kept))

        assertEquals(1, removed)
        assertTrue(store.artifactFile(kept, "a").parentFile!!.exists())
        assertFalse(store.artifactFile(orphan, "b").parentFile!!.exists())
    }

    @Test
    fun `sweepOrphans is a no-op when the share-prep root does not exist yet`() = runBlocking {
        val removed = store.sweepOrphans(emptySet())

        assertEquals(0, removed)
    }

    private fun wavFileFor(recording: VoiceRecording): File = fileSystem.absolutePath(recording.fileRelativePath)

    private fun seedArtifactFile(walkUuid: String, recordingUuid: String) {
        val file = store.artifactFile(walkUuid, recordingUuid)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(16))
    }

    private suspend fun awaitFakeCalled(recording: VoiceRecording) {
        val wavFile = wavFileFor(recording)
        while (wavFile !in fakeTranscoder.calls) yield()
    }

    private suspend fun awaitState(walkUuid: String, recordingUuid: String, predicate: (PrepState?) -> Boolean) {
        while (!predicate(store.state.value[walkUuid]?.get(recordingUuid))) yield()
    }

    private fun recording(walkUuid: String, uuid: String = UUID.randomUUID().toString()): VoiceRecording {
        val start = 1_000L
        val end = 6_000L
        return VoiceRecording(
            walkId = 1L,
            uuid = uuid,
            startTimestamp = start,
            endTimestamp = end,
            durationMillis = end - start,
            fileRelativePath = "recordings/$walkUuid/$uuid.wav",
        )
    }
}
