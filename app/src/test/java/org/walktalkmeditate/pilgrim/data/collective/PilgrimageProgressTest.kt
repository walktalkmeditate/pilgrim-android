// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import org.junit.Assert.assertEquals
import org.junit.Test

class PilgrimageProgressTest {
    @Test fun `under 10km says path is beginning`() {
        assertEquals("The path is beginning.", PilgrimageProgress.from(5.0).message)
    }

    @Test fun `between 10 and 40km says first steps`() {
        assertEquals("The first steps, taken together.", PilgrimageProgress.from(25.0).message)
    }

    @Test fun `40km picks Kumano Kodo once`() {
        assertEquals("Together, one Kumano Kodo complete.", PilgrimageProgress.from(40.0).message)
    }

    @Test fun `100km picks Via Francigena stage once`() {
        assertEquals("Together, one Via Francigena stage complete.", PilgrimageProgress.from(100.0).message)
    }

    @Test fun `2x Kumano Kodo expands the times count`() {
        assertEquals("Together, the Kumano Kodo walked 2 times.", PilgrimageProgress.from(80.0).message)
    }

    @Test fun `walking the Moon`() {
        val msg = PilgrimageProgress.from(384_400.0).message
        assertEquals("Together, one the Moon complete.", msg)
    }

    @Test fun `2x walking the Moon`() {
        // iOS-verbatim: route name is literally "the Moon" and the
        // factory wraps it as "Together, the {name} walked N times.",
        // producing the article collision below. Singular branch
        // ("Together, one {name} complete.") doesn't collide.
        val msg = PilgrimageProgress.from(768_800.0).message
        assertEquals("Together, the the Moon walked 2 times.", msg)
    }
}
