// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import android.app.Application
import android.content.Context
import android.location.Address
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PromptGeocoderTest {

    private val testDispatcher = StandardTestDispatcher()
    private var calls = 0
    private var stubResult: List<Address> = emptyList()
    private var stubThrow: Throwable? = null

    private val fakeClient = object : PromptGeocoder.GeocoderClient {
        override suspend fun getFromLocation(
            latitude: Double,
            longitude: Double,
            maxResults: Int,
        ): List<Address> {
            calls++
            stubThrow?.let { throw it }
            return stubResult
        }
    }

    private fun makeGeocoder(): PromptGeocoder {
        val context: Context = ApplicationProvider.getApplicationContext()
        return PromptGeocoder(
            context = context,
            ioDispatcher = testDispatcher,
            geocoderFactory = { _, _ -> fakeClient },
            localeProvider = { Locale.US },
        )
    }

    private fun makeAddress(name: String? = null, locality: String? = null): Address =
        Address(Locale.US).apply {
            featureName = name
            this.locality = locality
        }

    @Test
    fun `geocodeStart success returns PlaceContext`() = runTest(testDispatcher) {
        stubResult = listOf(makeAddress(name = "Park", locality = "Brooklyn"))

        val coord = LatLng(40.0, -73.0)
        val place = makeGeocoder().geocodeStart(coord)

        assertEquals(PlaceContext("Park, Brooklyn", coord, PlaceRole.Start), place)
        assertEquals(1, calls)
    }

    @Test
    fun `geocodeStart empty addresses returns null`() = runTest(testDispatcher) {
        stubResult = emptyList()

        val place = makeGeocoder().geocodeStart(LatLng(40.0, -73.0))

        assertNull(place)
        assertEquals(1, calls)
    }

    @Test
    fun `geocodeStart no feature nor locality returns null`() = runTest(testDispatcher) {
        stubResult = listOf(makeAddress(name = null, locality = null))

        val place = makeGeocoder().geocodeStart(LatLng(40.0, -73.0))

        assertNull(place)
    }

    @Test
    fun `geocodeStart only locality returns locality only`() = runTest(testDispatcher) {
        stubResult = listOf(makeAddress(name = null, locality = "Brooklyn"))

        val coord = LatLng(40.0, -73.0)
        val place = makeGeocoder().geocodeStart(coord)

        assertEquals(PlaceContext("Brooklyn", coord, PlaceRole.Start), place)
    }

    @Test
    fun `geocodeStart IOException returns null`() = runTest(testDispatcher) {
        stubThrow = IOException("offline")

        val place = makeGeocoder().geocodeStart(LatLng(40.0, -73.0))

        assertNull(place)
        assertEquals(1, calls)
    }

    @Test
    fun `geocodeStart cancellation propagates`() = runTest(testDispatcher) {
        stubThrow = CancellationException("cancelled")

        try {
            makeGeocoder().geocodeStart(LatLng(40.0, -73.0))
            fail("expected CancellationException")
        } catch (ce: CancellationException) {
            assertNotNull(ce)
        }
    }

    @Test
    fun `geocodeEnd under gate returns null without calling geocoder`() = runTest(testDispatcher) {
        stubResult = listOf(makeAddress(name = "End", locality = "Queens"))

        val place = makeGeocoder().geocodeEnd(LatLng(40.5, -73.5), distanceFromStartMeters = 400.0)

        assertNull(place)
        assertEquals("geocoder must not be called when under gate", 0, calls)
    }

    @Test
    fun `geocodeEnd over gate calls with 1100ms delay`() = runTest(testDispatcher) {
        stubResult = listOf(makeAddress(name = "End", locality = "Queens"))
        val geocoder = makeGeocoder()

        val deferred = async {
            geocoder.geocodeEnd(LatLng(40.5, -73.5), distanceFromStartMeters = 600.0)
        }

        advanceTimeBy(1099)
        runCurrent()
        assertEquals("geocoder must not fire before 1.1s", 0, calls)

        advanceTimeBy(2)
        runCurrent()

        val place = deferred.await()
        assertEquals(1, calls)
        assertNotNull(place)
        assertEquals("End, Queens", place?.name)
        assertEquals(PlaceRole.End, place?.role)
    }

    @Test
    fun `geocodeEnd at exactly 500 returns null`() = runTest(testDispatcher) {
        stubResult = listOf(makeAddress(name = "End", locality = "Queens"))

        val place = makeGeocoder().geocodeEnd(LatLng(40.5, -73.5), distanceFromStartMeters = 500.0)

        assertNull(place)
        assertEquals("strict greater-than gate: 500 must NOT pass", 0, calls)
    }
}
