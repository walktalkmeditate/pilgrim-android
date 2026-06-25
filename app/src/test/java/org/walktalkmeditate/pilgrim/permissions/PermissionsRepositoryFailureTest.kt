// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric (so the production `Log.w` resolves) failure-path coverage for
 * the #43 grant ritual: a DataStore IO failure during the atomic consume must
 * fail closed on the bell, NOT crash onboarding — `celebrateGrant` runs this
 * in a `viewModelScope.launch` with no exception handler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PermissionsRepositoryFailureTest {

    @Test
    fun `consumeBellGrant returns false instead of throwing when the write fails`() = runTest {
        val repository = PermissionsRepository(FailingDataStore)

        assertFalse(
            repository.consumeBellGrant(PermissionRitual.Permission.Location, soundsEnabled = true),
        )
    }

    private object FailingDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("read boom") }

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences = throw IOException("write boom")
    }
}
