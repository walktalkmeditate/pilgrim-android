// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.walktalkmeditate.pilgrim.data.honor.WayStore

/**
 * The ledger's two lock layers (P2 A-3), in a plain JVM: the in-process
 * lock lets threads of one process queue for the file lock, and the file
 * lock makes another process's writer wait. Robolectric runs in one JVM,
 * so the second layer is proven with a child JVM started on this test's
 * classpath ([LedgerLockHolder]). Real threads and a real process, so
 * every wait has a generous budget.
 */
class PilgrimageLedgerLockTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var base: File
    private lateinit var ledgers: PilgrimageLedgerStore

    @Before
    fun setUp() {
        base = File(folder.root, "Ways")
        ledgers = newStore()
    }

    private fun newStore() = PilgrimageLedgerStore(WayStore({ base }, syncDirectory = { true }))

    private fun identity(index: Int) =
        PilgrimageStageIdentity(routeId = ROUTE, index = index, name = "stage $index", distanceKm = 20.0)

    private val ledgerFile get() = File(base, "pilgrimage/$ROUTE/ledger.json")

    @Test
    fun `two writers recording different stages at once, one per thread, both land`() {
        val stores = listOf(ledgers, newStore())
        val together = CyclicBarrier(stores.size)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val writers = stores.mapIndexed { offset, store ->
            Thread {
                try {
                    repeat(ROUNDS) { round ->
                        together.await(BUDGET_MILLIS, TimeUnit.MILLISECONDS)
                        store.record(identity(stores.size * round + offset), HonorStageOutcome(0.5, arrived = false), END)
                    }
                } catch (e: Throwable) {
                    failures += e
                    together.reset()
                }
            }.apply { start() }
        }

        writers.forEach { it.join(BUDGET_MILLIS) }

        assertEquals("no writer failed", emptyList<String>(), failures.map { it.toString() })
        assertTrue("every writer finished", writers.none { it.isAlive })
        assertEquals(
            (0 until stores.size * ROUNDS).map { it.toString() }.toSet(),
            ledgers.load(ROUTE)!!.stages.keys,
        )
    }

    @Test
    fun `the same record written twice, from either store, leaves a byte-identical file`() {
        ledgers.record(identity(0), HonorStageOutcome(0.58, arrived = false), END)
        val first = ledgerFile.readBytes()

        newStore().record(identity(0), HonorStageOutcome(0.58, arrived = false), END)

        assertArrayEquals(first, ledgerFile.readBytes())
    }

    @Test
    fun `a second JVM holding the file lock makes the writer wait, and both records land`() {
        val errors = File(folder.root, "holder-stderr.txt")
        val holder = startHolder(errors)
        try {
            val fromHolder = holder.inputStream.bufferedReader()
            assertEquals("the other JVM holds the lock: ${errors.readTextOrEmpty()}", LOCKED, lineWithin(fromHolder))

            val failure = AtomicReference<Throwable?>()
            val writer = Thread {
                try {
                    ledgers.record(identity(0), HonorStageOutcome(1.0, arrived = true), END)
                } catch (e: Throwable) {
                    failure.set(e)
                }
            }.apply { start() }
            writer.join(HELD_MILLIS)
            assertTrue("the writer waits while the other JVM holds the lock", writer.isAlive)

            holder.outputStream.bufferedWriter().apply {
                write("go\n")
                flush()
            }
            writer.join(BUDGET_MILLIS)

            assertFalse("the writer finishes once the lock is let go", writer.isAlive)
            assertNull(failure.get())
            assertTrue("the other JVM exits", holder.waitFor(BUDGET_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals("the other JVM: ${errors.readTextOrEmpty()}", 0, holder.exitValue())
            assertEquals("each saw the other's record", setOf("0", "1"), ledgers.load(ROUTE)!!.stages.keys)
        } finally {
            holder.destroyForcibly()
        }
    }

    /**
     * The test's own classpath goes in an argument file: an Android unit
     * test's classpath can outgrow a single command-line argument.
     */
    private fun startHolder(errors: File): Process {
        val java = File(File(System.getProperty("java.home")), "bin/java")
        val classpath = System.getProperty("java.class.path").replace("\\", "\\\\").replace("\"", "\\\"")
        val arguments = File(folder.root, "holder.args").apply { writeText("-cp\n\"$classpath\"\n") }
        return try {
            ProcessBuilder(java.path, "-Xmx128m", "@${arguments.path}", LedgerLockHolder::class.java.name, base.path, ROUTE)
                .redirectError(errors)
                .start()
        } catch (e: IOException) {
            Assume.assumeNoException("a child JVM can't start here, so the cross-process lock goes unproven", e)
            throw e
        }
    }

    private fun lineWithin(reader: BufferedReader): String? =
        CompletableFuture.supplyAsync { reader.readLine() }.get(BUDGET_MILLIS, TimeUnit.MILLISECONDS)

    private fun File.readTextOrEmpty(): String = if (isFile) readText() else ""

    internal companion object {
        const val ROUTE = "camino-frances"
        const val LOCKED = "locked"
        val END: Instant = Instant.ofEpochSecond(1_800_000_000)

        const val ROUNDS = 25

        /** How long the writer is given to finish while the other JVM holds the lock: it never may. */
        const val HELD_MILLIS = 1_000L
        const val BUDGET_MILLIS = 60_000L
    }
}

/**
 * The other process: takes the ledger's two locks through the store's own
 * path, says so, holds them until a line arrives on stdin, then records
 * stage 1 over what it read on entry and lets go. Had the parent's writer
 * not waited, this write would drop its record.
 */
object LedgerLockHolder {

    @JvmStatic
    fun main(args: Array<String>) {
        val (base, route) = args
        val ledgers = PilgrimageLedgerStore(WayStore({ File(base) }, syncDirectory = { true }))
        ledgers.update(route) { current ->
            println(PilgrimageLedgerLockTest.LOCKED)
            System.out.flush()
            readlnOrNull()
            (current ?: PilgrimageLedger(route))
                .recorded(1, "stage 1", 20.0, HonorStageOutcome(0.58, arrived = false), PilgrimageLedgerLockTest.END)
        }
    }
}
