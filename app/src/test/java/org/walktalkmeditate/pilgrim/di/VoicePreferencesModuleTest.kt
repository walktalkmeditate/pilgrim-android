// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the `voice_preferences` corruption handler (WidgetModule /
 * CollectiveModule shape): a truncated or garbage preferences_pb
 * (mid-write OS kill) resets to empty preferences instead of failing
 * every read, and keys still round-trip after the reset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoicePreferencesModuleTest {

    @Test
    fun `corrupted preferences file resets to empty and keys round-trip afterward`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.preferencesDataStoreFile("voice_preferences")
        file.parentFile?.mkdirs()
        file.writeBytes("not-a-preferences-pb".toByteArray())

        val store = VoicePreferencesModule.provideVoicePreferencesDataStore(context)

        assertTrue(store.data.first().asMap().isEmpty())

        val key = booleanPreferencesKey("autoTranscribe")
        store.edit { it[key] = true }
        assertEquals(true, store.data.first()[key])
    }
}
