// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.flags

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.BuildConfig

class ReleaseFlagsTest {

    // Runs in both unit-test variants: debug proves the flag on, the
    // release-variant CI job proves it off.
    @Test
    fun `Honor is on in debug builds and off in release builds`() {
        assertEquals(BuildConfig.DEBUG, BuildConfigReleaseFlags().honor)
    }
}
