// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TranscriptionEditorEmptyGuardTest {

    @Test
    fun emptyAfterTrim_isSuppressed() {
        assertEquals(null, transcriptionCommitValue(""))
    }

    @Test
    fun whitespaceOnly_isSuppressed() {
        assertEquals(null, transcriptionCommitValue("   \n\n  \t"))
    }

    @Test
    fun nonEmptyAfterTrim_returnsTrimmed() {
        assertEquals("hello", transcriptionCommitValue("  hello  \n"))
    }

    @Test
    fun internalWhitespace_isPreserved() {
        assertEquals("hello world", transcriptionCommitValue("  hello world  "))
    }
}
