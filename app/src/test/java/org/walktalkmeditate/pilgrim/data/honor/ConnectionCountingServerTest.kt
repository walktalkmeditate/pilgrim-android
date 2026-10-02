// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Test

/** The redirect tests' witness: it must see a connection that never sends a byte. */
class ConnectionCountingServerTest {

    @Test
    fun `a connection that sends nothing is counted, and the count is exact`() {
        ConnectionCountingServer().use { server ->
            val url = server.url("/")
            assertEquals(0, server.connectionsSoFar())

            Socket(url.host, url.port).use { assertEquals(1, server.connectionsSoFar()) }
            Socket(url.host, url.port).close()

            assertEquals("its own probes are never counted", 2, server.connectionsSoFar())
        }
    }
}
