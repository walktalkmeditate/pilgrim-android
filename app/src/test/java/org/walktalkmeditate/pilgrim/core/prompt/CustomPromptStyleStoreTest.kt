// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CustomPromptStyleStoreTest {

    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private val dispatcher = UnconfinedTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "custom-prompt-styles-${System.nanoTime()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    private fun newStore(): CustomPromptStyleStore =
        CustomPromptStyleStore(dataStore, json, scope)

    private fun style(
        id: String = java.util.UUID.randomUUID().toString(),
        title: String = "Title",
        icon: String = "icon.name",
        instruction: String = "Walk gently.",
    ): CustomPromptStyle = CustomPromptStyle(id = id, title = title, icon = icon, instruction = instruction)

    private suspend fun CustomPromptStyleStore.awaitStyles(
        timeout: kotlin.time.Duration = 10.seconds,
        predicate: (List<CustomPromptStyle>) -> Boolean,
    ): List<CustomPromptStyle> = withTimeout(timeout) { styles.first(predicate) }

    @Test
    fun `default styles is empty list`() = runTest(dispatcher) {
        val store = newStore()
        assertEquals(emptyList<CustomPromptStyle>(), store.styles.value)
    }

    @Test
    fun `save adds style and persists across new store instance`() = runTest(dispatcher) {
        val store1 = newStore()
        val saved = style(title = "Lookback", icon = "rewind", instruction = "Trace the day backward.")
        store1.save(saved)

        val current = store1.awaitStyles { it.size == 1 }
        assertEquals(saved, current.single())

        val store2 = newStore()
        val loaded = store2.awaitStyles { it.size == 1 }
        assertEquals(saved, loaded.single())
    }

    @Test
    fun `save appends up to three styles in insertion order`() = runTest(dispatcher) {
        val store = newStore()
        val a = style(title = "A")
        val b = style(title = "B")
        val c = style(title = "C")
        store.save(a)
        store.save(b)
        store.save(c)

        val current = store.awaitStyles { it.size == 3 }
        assertEquals(listOf(a, b, c), current)
    }

    @Test
    fun `save silently drops fourth style beyond cap`() = runTest(dispatcher) {
        val store = newStore()
        val a = style(title = "A")
        val b = style(title = "B")
        val c = style(title = "C")
        val d = style(title = "D")
        store.save(a)
        store.save(b)
        store.save(c)
        store.awaitStyles { it.size == 3 }

        store.save(d)

        val current = store.styles.value
        assertEquals(3, current.size)
        assertEquals(listOf(a, b, c), current)
        assertTrue("4th style must be silently dropped", current.none { it.id == d.id })
    }

    @Test
    fun `save replaces existing entry when ids match`() = runTest(dispatcher) {
        val store = newStore()
        val original = style(title = "Original", instruction = "v1")
        store.save(original)
        store.awaitStyles { it.size == 1 }

        val edited = original.copy(title = "Edited", instruction = "v2")
        store.save(edited)

        val current = store.awaitStyles { it.singleOrNull()?.title == "Edited" }
        assertEquals(1, current.size)
        assertEquals("Edited", current.single().title)
        assertEquals("v2", current.single().instruction)
        assertEquals(original.id, current.single().id)
    }

    @Test
    fun `delete removes style by id`() = runTest(dispatcher) {
        val store = newStore()
        val keep = style(title = "Keep")
        val drop = style(title = "Drop")
        store.save(keep)
        store.save(drop)
        store.awaitStyles { it.size == 2 }

        store.delete(drop)

        val current = store.awaitStyles { it.size == 1 }
        assertEquals(keep, current.single())
    }

    @Test
    fun `delete is a no-op for unknown id`() = runTest(dispatcher) {
        val store = newStore()
        val saved = style(title = "Only")
        store.save(saved)
        store.awaitStyles { it.size == 1 }

        store.delete(style(title = "Phantom"))

        val current = store.styles.value
        assertEquals(1, current.size)
        assertEquals(saved, current.single())
    }

    @Test
    fun `corrupt persisted json recovers to empty list`() = runTest(dispatcher) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("custom_prompt_styles")] = "{not valid json"
        }

        val store = newStore()
        val current = store.awaitStyles { it.isEmpty() }
        assertEquals(emptyList<CustomPromptStyle>(), current)
    }
}
