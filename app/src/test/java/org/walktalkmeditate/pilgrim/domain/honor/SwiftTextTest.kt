// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiftTextTest {

    @Test
    fun `a decomposed accent compares equal to its composed form, as Swift's strings do`() {
        assertEquals(0, "é".swiftCompareTo("é"))
    }

    @Test
    fun `a decomposed accent sorts where its composed form sorts, not among the plain letters`() {
        assertTrue("éa".swiftCompareTo("éb") < 0)
        assertTrue("éb".compareTo("éa") < 0)
    }

    @Test
    fun `a character beyond the BMP sorts by its scalar, not by its surrogates`() {
        assertTrue("￿".swiftCompareTo("😀") < 0)
        assertTrue("😀".compareTo("￿") < 0)
    }

    @Test
    fun `a prefix sorts first, and ASCII sorts as plain compareTo does`() {
        assertTrue("wp".swiftCompareTo("wp-a") < 0)
        assertTrue("wp-orisson".swiftCompareTo("wp-saint-jean") < 0)
        assertEquals(0, "wp-a".swiftCompareTo("wp-a"))
    }
}
