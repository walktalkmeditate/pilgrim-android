// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import javax.net.ServerSocketFactory
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockWebServer

/**
 * A MockWebServer that counts the connections it accepts, not only the
 * requests it reads: a client that connects and then sends nothing (a
 * refused redirect's target, say) still counts.
 */
class ConnectionCountingServer : AutoCloseable {

    /** The remote port of every connection accepted, in the order the listening socket handed them out. */
    private val accepted = CopyOnWriteArrayList<Int>()

    private val server = MockWebServer().apply {
        serverSocketFactory = object : ServerSocketFactory() {
            override fun createServerSocket(): ServerSocket = object : ServerSocket() {
                override fun accept(): Socket = super.accept().also { accepted += it.port }
            }

            override fun createServerSocket(port: Int): ServerSocket = unsupported()

            override fun createServerSocket(port: Int, backlog: Int): ServerSocket = unsupported()

            override fun createServerSocket(port: Int, backlog: Int, address: InetAddress): ServerSocket = unsupported()
        }
        start()
    }

    fun url(path: String): HttpUrl = server.url(path)

    /** The local ports of this class's own probes, which no count includes. */
    private val probes = CopyOnWriteArraySet<Int>()

    /**
     * How many connections reached the server before this call. A probe
     * connection of its own is accepted after every earlier one, since a
     * listening socket queues them in order, so the count is exact once
     * the probe shows, with no wait on a connection that never comes.
     */
    fun connectionsSoFar(): Int = Socket(server.hostName, server.port).use { probe ->
        probes += probe.localPort
        val deadline = System.nanoTime() + PROBE_BUDGET_NANOS
        while (probe.localPort !in accepted) {
            check(System.nanoTime() < deadline) { "the probe connection was never accepted" }
            Thread.sleep(POLL_MILLIS)
        }
        accepted.takeWhile { it != probe.localPort }.count { it !in probes }
    }

    override fun close() {
        server.shutdown()
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("MockWebServer binds an unbound socket")

    private companion object {
        const val PROBE_BUDGET_NANOS = 30_000_000_000L
        const val POLL_MILLIS = 10L
    }
}
