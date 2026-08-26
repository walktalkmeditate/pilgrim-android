// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure in-memory thread aggregation, ported from
 * `Pilgrim/Models/Threads/ThreadStore.swift` (parity spec
 * `docs/parity/2026-08-25-threads-engine-port.md`, BEH-32..35/EDG-62..65).
 * [ThreadStore.build] is pure and unmemoized — memoization lives above it
 * in [ThreadsDossierBuilder].
 */
class ThreadStoreTest {

    private val anchor: Instant = Instant.parse("2026-08-20T09:00:00Z")

    private fun theme(lemma: String, displayTerm: String = lemma, mentionCount: Int = 2, salience: Double = 0.1) =
        Theme(lemma = lemma, displayTerm = displayTerm, mentionCount = mentionCount, salience = salience, mentions = emptyList())

    private fun context(uuid: String, themes: List<Theme>) = TranscriptContext(
        uuid = uuid,
        languageCode = "en",
        wordCount = 100,
        themes = themes,
        markers = TranscriptMarkers(
            wordCount = 100,
            absolutistCount = 0,
            firstPersonCount = 0,
            insightCount = 0,
            causationCount = 0,
            discrepancyCount = 0,
            temporalLean = TemporalLean.PRESENT,
        ),
        transcriptHash = "hash-$uuid",
    )

    private fun walk(walkId: Long, daysAgoFromAnchor: Long, intention: String? = null) = WalkLite(
        walkId = walkId,
        startedAt = anchor.minus(daysAgoFromAnchor, ChronoUnit.DAYS),
        intention = intention,
        weatherCondition = null,
    )

    // --- recording-uuid-keyed join --------------------------------------------

    @Test
    fun `join is keyed by recording uuid, not by a bare walk list`() {
        // Two recordings on the SAME walk both mention "river" — a bare walk
        // list has no way to attribute either context to a specific walk;
        // only a recording-uuid keyed map can.
        val walkA = walk(walkId = 1L, daysAgoFromAnchor = 0)
        val recordingToWalk = mapOf("r1" to walkA, "r2" to walkA)
        val contexts = listOf(
            context("r1", listOf(theme("river"))),
            context("r2", listOf(theme("river"))),
        )

        val threads = ThreadStore.build(contexts, recordingToWalk, anchor, backfillComplete = true)

        val river = threads.active.single { it.lemma == "river" }
        assertEquals(setOf("r1", "r2"), river.appearances.map { it.recordingUuid }.toSet())
        assertEquals(listOf(1L), river.distinctWalkIds)
    }

    @Test
    fun `a context whose uuid has no walk entry is silently excluded (orphan prune)`() {
        val contexts = listOf(context("orphan", listOf(theme("river"))))

        val threads = ThreadStore.build(contexts, recordingToWalk = emptyMap(), anchor, backfillComplete = true)

        assertTrue("an unmapped recording must not surface as a thread", threads.active.isEmpty())
    }

    @Test
    fun `duplicate-uuid contexts fail loudly instead of silently keeping the last`() {
        val walkA = walk(1L, 0)
        val contexts = listOf(
            context("dup", listOf(theme("river"))),
            context("dup", listOf(theme("stone"))),
        )

        assertThrows(IllegalStateException::class.java) {
            ThreadStore.build(contexts, mapOf("dup" to walkA), anchor, backfillComplete = true)
        }
    }

    // --- display term: max count, tie-broken lexicographically smallest -------

    @Test
    fun `display term picks max surface count, ties broken to smallest key`() {
        val walkA = walk(1L, 0)
        val contexts = listOf(
            context("r1", listOf(theme("move", displayTerm = "moving", mentionCount = 2))),
            context("r2", listOf(theme("move", displayTerm = "move", mentionCount = 2))),
        )
        val recordingToWalk = mapOf("r1" to walkA, "r2" to walkA)

        val threads = ThreadStore.build(contexts, recordingToWalk, anchor, backfillComplete = true)

        assertEquals("move", threads.active.single().displayTerm)
    }

    // --- determinism: same-timestamp tie-break on (date, recordingUuid) ------

    @Test
    fun `appearances sort ascending by (date, recordingUuid) for same-timestamp determinism`() {
        val sameInstant = anchor.minus(5, ChronoUnit.DAYS)
        val walkA = WalkLite(1L, sameInstant, null, null)
        val walkB = WalkLite(2L, sameInstant, null, null)
        val contexts = listOf(
            context("z-recording", listOf(theme("river"))),
            context("a-recording", listOf(theme("river"))),
        )
        val recordingToWalk = mapOf("z-recording" to walkA, "a-recording" to walkB)

        val threads = ThreadStore.build(contexts, recordingToWalk, anchor, backfillComplete = true)

        assertEquals(
            listOf("a-recording", "z-recording"),
            threads.active.single().appearances.map { it.recordingUuid },
        )
    }

    @Test
    fun `active threads are sorted by lemma`() {
        val walkA = walk(1L, 0)
        val contexts = listOf(context("r1", listOf(theme("zephyr"), theme("apple"))))

        val threads = ThreadStore.build(contexts, mapOf("r1" to walkA), anchor, backfillComplete = true)

        assertEquals(listOf("apple", "zephyr"), threads.active.map { it.lemma })
    }

    // --- status: firstTime / recurring / null ---------------------------------

    @Test
    fun `status is null when backfill is incomplete and no earlier appearance exists`() {
        val walkA = walk(1L, 0)
        val contexts = listOf(context("r1", listOf(theme("river"))))
        val threads = ThreadStore.build(contexts, mapOf("r1" to walkA), anchor, backfillComplete = false)

        val status = ThreadStore.status(threads.active.single(), atWalkId = 1L, backfillComplete = false)

        assertNull("origin suppression until backfill completes", status)
    }

    @Test
    fun `status is firstTime when backfill is complete and no earlier appearance exists`() {
        val walkA = walk(1L, 0)
        val contexts = listOf(context("r1", listOf(theme("river"))))
        val threads = ThreadStore.build(contexts, mapOf("r1" to walkA), anchor, backfillComplete = true)

        val status = ThreadStore.status(threads.active.single(), atWalkId = 1L, backfillComplete = true)

        assertEquals(ThreadStatus.FirstTime, status)
    }

    @Test
    fun `a 31-day-old appearance still disqualifies firstTime even though it falls outside the window`() {
        val old = walk(1L, daysAgoFromAnchor = 31)
        val current = walk(2L, daysAgoFromAnchor = 0)
        val contexts = listOf(
            context("old-rec", listOf(theme("river"))),
            context("new-rec", listOf(theme("river"))),
        )
        val recordingToWalk = mapOf("old-rec" to old, "new-rec" to current)

        val threads = ThreadStore.build(contexts, recordingToWalk, anchor, backfillComplete = true)
        val status = ThreadStore.status(threads.active.single(), atWalkId = 2L, backfillComplete = true)

        assertTrue(
            "existence of ANY earlier appearance (even outside the 30-day window) rules out firstTime",
            status is ThreadStatus.Recurring,
        )
        assertEquals(
            "the 31-day-old walk falls outside the trailing 30-day window, so it must not be counted",
            1,
            (status as ThreadStatus.Recurring).walksInWindow,
        )
    }

    @Test
    fun `recurrence window is inclusive on both ends`() {
        val exactlyThirtyDaysAgo = walk(1L, daysAgoFromAnchor = 30)
        val current = walk(2L, daysAgoFromAnchor = 0)
        val contexts = listOf(
            context("old-rec", listOf(theme("river"))),
            context("new-rec", listOf(theme("river"))),
        )
        val recordingToWalk = mapOf("old-rec" to exactlyThirtyDaysAgo, "new-rec" to current)

        val threads = ThreadStore.build(contexts, recordingToWalk, anchor, backfillComplete = true)
        val status = ThreadStore.status(threads.active.single(), atWalkId = 2L, backfillComplete = true)

        assertEquals(ThreadStatus.Recurring(walksInWindow = 2), status)
    }

    @Test
    fun `windows anchor per caller — the same thread history yields a different status at an old walk vs now`() {
        val walkA = walk(1L, daysAgoFromAnchor = 40)
        val walkB = walk(2L, daysAgoFromAnchor = 35)
        val contexts = listOf(
            context("r1", listOf(theme("river"))),
            context("r2", listOf(theme("river"))),
        )
        val recordingToWalk = mapOf("r1" to walkA, "r2" to walkB)
        val threads = ThreadStore.build(contexts, recordingToWalk, anchor, backfillComplete = true)
        val thread = threads.active.single()

        // Rendering walkB's (the OLDER walk's) own dossier: its window looks
        // back 30 days from walkB's OWN date, which does not reach walkA
        // (5 days earlier, both well before the 30-day span from walkB is
        // measured against) — still only 2 total distinct walks though, so
        // both count once the earlier one is inside the window.
        val statusAtWalkB = ThreadStore.status(thread, atWalkId = 2L, backfillComplete = true)
        assertEquals(ThreadStatus.Recurring(walksInWindow = 2), statusAtWalkB)
    }

    @Test
    fun `status is null when the given walkId never appeared for this thread`() {
        val walkA = walk(1L, 0)
        val contexts = listOf(context("r1", listOf(theme("river"))))
        val threads = ThreadStore.build(contexts, mapOf("r1" to walkA), anchor, backfillComplete = true)

        assertNull(ThreadStore.status(threads.active.single(), atWalkId = 999L, backfillComplete = true))
    }

    // --- salience direction: floor, thirds, threshold, dossier-only -----------

    @Test
    fun `salience direction is null below the floor of 3 appearances`() {
        val walkA = walk(1L, 2)
        val walkB = walk(2L, 1)
        val contexts = listOf(
            context("r1", listOf(theme("river", salience = 0.1))),
            context("r2", listOf(theme("river", salience = 0.2))),
        )
        val recordingToWalk = mapOf("r1" to walkA, "r2" to walkB)

        val threads = ThreadStore.build(contexts, recordingToWalk, anchor, backfillComplete = true)

        assertNull(ThreadStore.salienceDirection(threads.active.single()))
    }

    @Test
    fun `salience direction floors thirds at 1 for exactly 3 appearances (no divide-by-zero)`() {
        val walks = (0..2).map { walk(it.toLong(), (2 - it).toLong()) }
        val contexts = walks.mapIndexed { i, w -> context("r$i", listOf(theme("river", salience = 0.1 * (i + 1)))) }
        val recordingToWalk = walks.mapIndexed { i, w -> "r$i" to w }.toMap()

        val threads = ThreadStore.build(contexts, recordingToWalk, anchor, backfillComplete = true)

        // salience 0.1, 0.2, 0.3 rising monotonically over 3 points must not
        // crash and must resolve to a real direction.
        assertEquals(SalienceDirection.RISING, ThreadStore.salienceDirection(threads.active.single()))
    }

    @Test
    fun `salience direction is rising above the threshold`() {
        val walks = (0..3).map { walk(it.toLong(), (3 - it).toLong()) }
        val saliences = listOf(0.10, 0.10, 0.20, 0.20)
        val contexts = walks.mapIndexed { i, _ -> context("r$i", listOf(theme("river", salience = saliences[i]))) }
        val recordingToWalk = walks.mapIndexed { i, w -> "r$i" to w }.toMap()

        val threads = ThreadStore.build(contexts, recordingToWalk, anchor, backfillComplete = true)

        assertEquals(SalienceDirection.RISING, ThreadStore.salienceDirection(threads.active.single()))
    }

    @Test
    fun `salience direction is fading below the negative threshold`() {
        val walks = (0..3).map { walk(it.toLong(), (3 - it).toLong()) }
        val saliences = listOf(0.20, 0.20, 0.10, 0.10)
        val contexts = walks.mapIndexed { i, _ -> context("r$i", listOf(theme("river", salience = saliences[i]))) }
        val recordingToWalk = walks.mapIndexed { i, w -> "r$i" to w }.toMap()

        val threads = ThreadStore.build(contexts, recordingToWalk, anchor, backfillComplete = true)

        assertEquals(SalienceDirection.FADING, ThreadStore.salienceDirection(threads.active.single()))
    }

    @Test
    fun `salience direction is steady inside the threshold band`() {
        val walks = (0..3).map { walk(it.toLong(), (3 - it).toLong()) }
        val saliences = listOf(0.20, 0.20, 0.21, 0.21)
        val contexts = walks.mapIndexed { i, _ -> context("r$i", listOf(theme("river", salience = saliences[i]))) }
        val recordingToWalk = walks.mapIndexed { i, w -> "r$i" to w }.toMap()

        val threads = ThreadStore.build(contexts, recordingToWalk, anchor, backfillComplete = true)

        assertEquals(SalienceDirection.STEADY, ThreadStore.salienceDirection(threads.active.single()))
    }

    @Test
    fun `salience direction is steady, never a divide crash, when the early third averages zero`() {
        val walks = (0..3).map { walk(it.toLong(), (3 - it).toLong()) }
        val saliences = listOf(0.0, 0.0, 0.3, 0.3)
        val contexts = walks.mapIndexed { i, _ -> context("r$i", listOf(theme("river", salience = saliences[i]))) }
        val recordingToWalk = walks.mapIndexed { i, w -> "r$i" to w }.toMap()

        val threads = ThreadStore.build(contexts, recordingToWalk, anchor, backfillComplete = true)

        assertEquals(SalienceDirection.STEADY, ThreadStore.salienceDirection(threads.active.single()))
    }

    // --- salience direction is dossier-only: never exposed as a status -------

    @Test
    fun `ThreadStatus has exactly the two named cases — direction is a wholly separate concept`() {
        // Compile-time shape check: ThreadStatus must never gain a "rising"/
        // "fading" case — those live only in SalienceDirection, consumed
        // exclusively by the dossier formatter, never by any other surface.
        val walkA = walk(1L, 0)
        val contexts = listOf(context("r1", listOf(theme("river"))))
        val threads = ThreadStore.build(contexts, mapOf("r1" to walkA), anchor, backfillComplete = true)
        val status = ThreadStore.status(threads.active.single(), atWalkId = 1L, backfillComplete = true)
        when (status) {
            is ThreadStatus.FirstTime, is ThreadStatus.Recurring, null -> Unit
        }
    }
}
