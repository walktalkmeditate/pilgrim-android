// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.FakePreferencesDataStore

/** The referrer's record on disk (R19): each step of a first launch's handoff, as a later launch reads it. */
class DataStoreInstallReferrerStoreTest {

    private val dataStore = FakePreferencesDataStore()

    /** A fresh store over the same file, as a later launch opens it. */
    private fun relaunched() = DataStoreInstallReferrerStore(dataStore)

    @Test
    fun `a fresh install has nothing read`() = runTest {
        assertEquals(InstallReferrerRecord(consumed = false, pendingShareId = null), relaunched().read())
    }

    @Test
    fun `a first read begun in setup is remembered until the referrer is read`() = runTest {
        relaunched().markFirstReadInSetup()
        assertEquals(InstallReferrerRecord(consumed = false, pendingShareId = null, firstReadInSetup = true), relaunched().read())

        relaunched().consume(pendingShareId = ID)

        assertEquals(InstallReferrerRecord(consumed = true, pendingShareId = ID), relaunched().read())
    }

    @Test
    fun `the waiting id goes once the routing takes it, and the referrer stays read`() = runTest {
        relaunched().consume(pendingShareId = ID)

        relaunched().clearPending()

        assertEquals(InstallReferrerRecord(consumed = true, pendingShareId = null), relaunched().read())
    }

    private companion object {
        const val ID = "Qoi4YmPHLN"
    }
}
