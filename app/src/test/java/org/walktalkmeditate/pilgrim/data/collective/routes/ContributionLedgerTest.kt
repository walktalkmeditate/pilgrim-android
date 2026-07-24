// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective.routes

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android port of `CollectiveContributionLogTests@9a418e4` (see
 * `docs/parity/2026-07-23-port-contribution-ledger-u4.md`). The stored
 * key + value shape are asserted against literals rather than read off
 * the type: a renamed key orphans every already-recorded walk on every
 * installed device, so these should fail rather than follow it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContributionLedgerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val listSerializer = ListSerializer(String.serializer())
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var ledger: ContributionLedger

    @Before
    fun setUp() {
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = createDataStore(uniqueFile())
        ledger = ContributionLedger(dataStore, json)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    private fun uniqueFile() =
        File(context.filesDir, "datastore/test_${UUID.randomUUID()}.preferences_pb")

    /**
     * Mirrors `CollectiveModule.provideContributionLedgerDataStore` so the
     * corruption test below exercises the production configuration.
     */
    private fun createDataStore(file: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = dataStoreScope,
            produceFile = { file },
        )

    private suspend fun storedUuids(): List<String>? =
        dataStore.data.first()[ContributionLedger.KEY_CONTRIBUTED_WALK_UUIDS]
            ?.let { json.decodeFromString(listSerializer, it) }

    private suspend fun seed(uuids: List<String>) {
        dataStore.edit { prefs ->
            prefs[ContributionLedger.KEY_CONTRIBUTED_WALK_UUIDS] =
                json.encodeToString(listSerializer, uuids)
        }
    }

    @Test
    fun `wasContributed is false for an unrecorded walk`() = runBlocking {
        assertFalse(ledger.wasContributed("walk-1"))
    }

    @Test
    fun `record survives a round trip through a new instance`() = runBlocking {
        ledger.record("walk-1")
        assertTrue(ContributionLedger(dataStore, json).wasContributed("walk-1"))
    }

    @Test
    fun `record does not vouch for walks it never saw`() = runBlocking {
        ledger.record("walk-1")
        assertFalse(ledger.wasContributed("walk-2"))
    }

    @Test
    fun `record is idempotent and stores a JSON string-array under the verbatim iOS key`() =
        runBlocking {
            ledger.record("walk-1")
            ledger.record("walk-1")
            assertEquals(
                "collectiveContributedWalkUUIDs",
                ContributionLedger.KEY_CONTRIBUTED_WALK_UUIDS.name,
            )
            assertEquals(listOf("walk-1"), storedUuids())
        }

    @Test
    fun `record at capacity drops the oldest identifier`() = runBlocking {
        seed((0 until ContributionLedger.CAPACITY).map { "walk-$it" })

        ledger.record("walk-newest")

        assertEquals(ContributionLedger.CAPACITY, storedUuids()?.size)
        assertFalse(
            "the oldest walk is the one that falls off",
            ledger.wasContributed("walk-0"),
        )
        assertTrue(ledger.wasContributed("walk-${ContributionLedger.CAPACITY - 1}"))
        assertTrue(ledger.wasContributed("walk-newest"))
    }

    @Test
    fun `record at capacity repeating a known walk evicts nothing`() = runBlocking {
        val atCapacity = (0 until ContributionLedger.CAPACITY).map { "walk-$it" }
        seed(atCapacity)

        ledger.record("walk-500")

        assertEquals(atCapacity, storedUuids())
    }

    @Test
    fun `corrupted datastore file is replaced with empty preferences without crashing`() =
        runBlocking {
            val file = uniqueFile()
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(0x00, 0x2A, 0x7F, -1, 0x13))
            val recovering = ContributionLedger(createDataStore(file), json)

            assertFalse(recovering.wasContributed("walk-1"))
            recovering.record("walk-1")
            assertTrue(recovering.wasContributed("walk-1"))
        }

    @Test
    fun `garbage value under the key reads as empty and is recoverable`() = runBlocking {
        dataStore.edit { it[ContributionLedger.KEY_CONTRIBUTED_WALK_UUIDS] = "not json" }

        assertFalse(ledger.wasContributed("walk-1"))
        ledger.record("walk-1")
        assertEquals(listOf("walk-1"), storedUuids())
    }
}
