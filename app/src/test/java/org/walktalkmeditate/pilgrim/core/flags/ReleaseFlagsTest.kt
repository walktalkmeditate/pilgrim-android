// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.flags

import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseFlagsTest {

    // Runs in both unit-test variants, so debug and the release-variant CI
    // job each prove the flag on since the 2.0.0 flip.
    @Test
    fun `Honor is on in every build since 2_0_0`() {
        assertTrue(BuildConfigReleaseFlags().honor)
    }
}
