// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.celestial.MoonPhase
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EtegamiBitmapRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val emptySeal = SealSpec(
        uuid = "seed",
        startMillis = 1_700_000_000_000L,
        distanceMeters = 1_234.0,
        durationSeconds = 3_600.0,
        displayDistance = "1.2",
        unitLabel = "km",
        ink = Color.Black,
    )

    private fun spec(
        routePoints: List<LocationPoint> = emptyList(),
        moonPhase: MoonPhase? = null,
        topText: String? = null,
        activityMarkers: List<ActivityMarker> = emptyList(),
        startedAtEpochMs: Long = 1_700_000_000_000L,
        hourOfDay: Int = 10,
    ) = EtegamiSpec(
        walkUuid = "w-uuid",
        startedAtEpochMs = startedAtEpochMs,
        hourOfDay = hourOfDay,
        routePoints = routePoints,
        sealSpec = emptySeal,
        moonPhase = moonPhase,
        distanceMeters = 1_234.0,
        durationMillis = 3_600_000L,
        elevationGainMeters = 42.0,
        topText = topText,
        activityMarkers = activityMarkers,
        units = org.walktalkmeditate.pilgrim.data.units.UnitSystem.Metric,
    )

    @Test
    fun `render produces 1080x1920 bitmap on the full-featured spec`() = runBlocking {
        val route = (0..9).map {
            LocationPoint(
                timestamp = 1_700_000_000_000L + it * 60_000L,
                latitude = 45.0 + it * 0.0001,
                longitude = -70.0 + it * 0.0001,
            )
        }
        val bitmap = EtegamiBitmapRenderer.render(
            spec = spec(
                routePoints = route,
                moonPhase = MoonPhase(name = "First Quarter", illumination = 0.5, ageInDays = 7.4),
                topText = "stepping out of the clock",
                activityMarkers = listOf(
                    ActivityMarker(ActivityMarker.Kind.Meditation, 1_700_000_180_000L),
                    ActivityMarker(ActivityMarker.Kind.Voice, 1_700_000_300_000L),
                ),
            ),
            context = context,
        )
        assertNotNull(bitmap)
        assertEquals(EtegamiBitmapRenderer.WIDTH_PX, bitmap.width)
        assertEquals(EtegamiBitmapRenderer.HEIGHT_PX, bitmap.height)
    }

    @Test
    fun `render with empty route skips route layers but still produces a bitmap`() = runBlocking {
        val bitmap = EtegamiBitmapRenderer.render(
            spec = spec(routePoints = emptyList(), topText = "quiet morning"),
            context = context,
        )
        assertEquals(EtegamiBitmapRenderer.WIDTH_PX, bitmap.width)
        assertEquals(EtegamiBitmapRenderer.HEIGHT_PX, bitmap.height)
    }

    @Test
    fun `render without moonPhase omits the moon layer without crashing`() = runBlocking {
        val bitmap = EtegamiBitmapRenderer.render(
            spec = spec(moonPhase = null),
            context = context,
        )
        assertNotNull(bitmap)
    }

    @Test
    fun `render without topText produces a bitmap (text layer skipped)`() = runBlocking {
        val bitmap = EtegamiBitmapRenderer.render(
            spec = spec(topText = null),
            context = context,
        )
        assertNotNull(bitmap)
    }

    @Test
    fun `render with night hour uses inverted palette — no exception`() = runBlocking {
        // Hour 3 falls in the night band (outside 5..19).
        val bitmap = EtegamiBitmapRenderer.render(
            spec = spec(hourOfDay = 3),
            context = context,
        )
        assertNotNull(bitmap)
    }

    @Test
    fun `render handles a single-point route by skipping route layers`() = runBlocking {
        val bitmap = EtegamiBitmapRenderer.render(
            spec = spec(
                routePoints = listOf(
                    LocationPoint(timestamp = 0L, latitude = 45.0, longitude = -70.0),
                ),
            ),
            context = context,
        )
        assertNotNull(bitmap)
    }

}
