// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ThreadsAnalysisEnvironment.ensureInstalled]'s once-only memoization —
 * success AND failure. A corrupt bundled asset is deterministic for the
 * life of the APK, so the first failure must be remembered and rethrown
 * fast rather than re-paying the decompress+parse on every later call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThreadsAnalysisEnvironmentTest {

    private lateinit var context: Application
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private class CountingEnvironment(
        context: Application,
        json: Json,
        private val onInstall: () -> Unit,
    ) : ThreadsAnalysisEnvironment(context, WordNetLexicon(context, json)) {
        var installCalls = 0
            private set

        override fun installNow() {
            installCalls++
            onInstall()
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `a failing install runs exactly once and later calls fail fast with the same failure`() = runTest {
        val environment = CountingEnvironment(context, json) { throw IllegalStateException("corrupt asset") }

        val first = try {
            environment.ensureInstalled()
            null
        } catch (t: Throwable) {
            t
        }
        val second = try {
            environment.ensureInstalled()
            null
        } catch (t: Throwable) {
            t
        }

        assertTrue("first attempt must surface the install failure", first is IllegalStateException)
        assertSame("later calls must rethrow the memoized failure, not a fresh attempt's", first, second)
        assertEquals("the expensive install must run exactly once", 1, environment.installCalls)
    }

    @Test
    fun `a successful install runs exactly once across repeated calls`() = runTest {
        val environment = CountingEnvironment(context, json) { }

        environment.ensureInstalled()
        environment.ensureInstalled()

        assertEquals(1, environment.installCalls)
    }
}
