// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.mlkit.common.sdkinternal.MlKitContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ML Kit's on-device model doesn't run under Robolectric/the JVM, so only
 * the builder-rule test below constructs the real [MlKitLanguageIdClient]
 * (proving `LanguageIdentificationOptions.Builder().build()` +
 * `LanguageIdentification.getClient()` don't crash — the house platform-
 * object-builder rule). Every other test substitutes
 * [FakeLanguageIdentifierGateway] to exercise the 0.5 confidence gate
 * (parity spec EDG-2), which is [MlKitLanguageIdClient]'s own Kotlin code,
 * not ML Kit's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MlKitLanguageIdClientTest {

    @Before
    fun setUp() {
        // On a real device, ML Kit's own manifest-declared content
        // provider (MlKitInitProvider) does this automatically at process
        // start; Robolectric's test harness doesn't run it, so the
        // real-construction test below would otherwise fail with
        // "MlKitContext has not been initialized" before ever reaching
        // this class's own code.
        MlKitContext.initializeIfNeeded(ApplicationProvider.getApplicationContext<Application>())
    }

    @Test
    fun `constructs the real ML Kit client without crashing`() {
        MlKitLanguageIdClient()
    }

    @Test
    fun `detect returns the language at exactly the 0point5 confidence floor`() = runTest {
        val client = fakeClient(LanguageGuess("en", 0.5f))
        assertEquals("en", client.detect("hello"))
    }

    @Test
    fun `detect returns null just below the 0point5 confidence floor`() = runTest {
        val client = fakeClient(LanguageGuess("en", 0.4999999f))
        assertNull(client.detect("hello"))
    }

    @Test
    fun `detect picks the highest-confidence guess among several candidates`() = runTest {
        val client = fakeClient(LanguageGuess("fr", 0.6f), LanguageGuess("en", 0.9f))
        assertEquals("en", client.detect("hello"))
    }

    @Test
    fun `detect returns null when only the undetermined tag is reported`() = runTest {
        val client = fakeClient(LanguageGuess("und", 0f))
        assertNull(client.detect("..."))
    }

    @Test
    fun `detect returns null when the gateway reports no guesses at all`() = runTest {
        val client = fakeClient()
        assertNull(client.detect(""))
    }

    private fun fakeClient(vararg guesses: LanguageGuess): MlKitLanguageIdClient =
        MlKitLanguageIdClient(FakeLanguageIdentifierGateway(guesses.toList()))

    private class FakeLanguageIdentifierGateway(
        private val guesses: List<LanguageGuess>,
    ) : LanguageIdentifierGateway {
        override suspend fun identifyPossibleLanguages(text: String): List<LanguageGuess> = guesses
    }
}
