// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import android.app.Application
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Visibility
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.celestial.MoonPhase
import org.walktalkmeditate.pilgrim.core.prompt.voices.ContemplativeVoice

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PromptGeneratorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val generator = PromptGenerator(context)

    private val nyZone: ZoneId = ZoneId.of("America/New_York")
    private val testStartTimestamp: Long = LocalDateTime.of(2026, 5, 4, 9, 41)
        .atZone(nyZone)
        .toInstant()
        .toEpochMilli()
    private val testLunar: MoonPhase = MoonPhase(
        name = "Waxing Crescent",
        illumination = 0.32,
        ageInDays = 4.5,
    )

    private fun fixtureContext(
        recordings: List<RecordingContext> = emptyList(),
        routeSpeeds: List<Double> = emptyList(),
    ): ActivityContext = ActivityContext(
        recordings = recordings,
        meditations = emptyList(),
        durationSeconds = 1800L,
        distanceMeters = 2_000.0,
        startTimestamp = testStartTimestamp,
        placeNames = emptyList(),
        routeSpeeds = routeSpeeds,
        recentWalkSnippets = emptyList(),
        intention = null,
        waypoints = emptyList(),
        weather = null,
        lunarPhase = testLunar,
        celestial = null,
        photoContexts = emptyList(),
        narrativeArc = NarrativeArc.EMPTY,
    )

    @Test
    fun generateAll_returns6PromptsInPromptStyleOrder() {
        val prompts = generator.generateAll(
            activityContext = fixtureContext(),
            imperial = false,
            zone = nyZone,
        )
        assertEquals(6, prompts.size)
        assertEquals(PromptStyle.Contemplative, prompts[0].style)
        assertEquals(PromptStyle.Reflective, prompts[1].style)
        assertEquals(PromptStyle.Creative, prompts[2].style)
        assertEquals(PromptStyle.Gratitude, prompts[3].style)
        assertEquals(PromptStyle.Philosophical, prompts[4].style)
        assertEquals(PromptStyle.Journaling, prompts[5].style)
    }

    @Test
    fun generateAll_eachPromptHasCorrectIcon() {
        val prompts = generator.generateAll(
            activityContext = fixtureContext(),
            imperial = false,
            zone = nyZone,
        )
        assertEquals(Icons.Outlined.Spa, prompts[0].icon)
        assertEquals(Icons.Outlined.Visibility, prompts[1].icon)
        assertEquals(Icons.Outlined.Brush, prompts[2].icon)
        assertEquals(Icons.Outlined.Favorite, prompts[3].icon)
        assertEquals(Icons.AutoMirrored.Outlined.MenuBook, prompts[4].icon)
        assertEquals(Icons.Outlined.Edit, prompts[5].icon)
    }

    @Test
    fun generateAll_eachPromptTextNonEmpty_andHasContemplativePreamble() {
        val prompts = generator.generateAll(
            activityContext = fixtureContext(),
            imperial = false,
            zone = nyZone,
        )
        prompts.forEach { p -> assertTrue("text empty for ${p.style}", p.text.isNotEmpty()) }
        val expectedPreamble = ContemplativeVoice.preamble(hasSpeech = false)
        assertTrue(
            "Contemplative prompt missing its silent-walk preamble",
            prompts[0].text.contains(expectedPreamble),
        )
    }

    @Test
    fun generateAll_titleResolvedFromR_string() {
        val prompts = generator.generateAll(
            activityContext = fixtureContext(),
            imperial = false,
            zone = nyZone,
        )
        assertEquals("Contemplative", prompts[0].title)
        assertEquals("Reflective", prompts[1].title)
        assertEquals("Creative", prompts[2].title)
        assertEquals("Gratitude", prompts[3].title)
        assertEquals("Philosophical", prompts[4].title)
        assertEquals("Journaling", prompts[5].title)
    }

    @Test
    fun generate_singleStyle_buildsExpectedPrompt() {
        val prompt = generator.generate(
            style = PromptStyle.Contemplative,
            activityContext = fixtureContext(),
            imperial = false,
            zone = nyZone,
        )
        assertEquals(PromptStyle.Contemplative, prompt.style)
        assertNull(prompt.customStyle)
        assertEquals("Contemplative", prompt.title)
        assertEquals("Sit with what emerged from movement", prompt.subtitle)
        assertEquals(Icons.Outlined.Spa, prompt.icon)
        assertTrue(prompt.text.contains(ContemplativeVoice.preamble(hasSpeech = false)))
    }

    @Test
    fun generateCustom_usesCustomVoice_titleSubtitleFromCustomStyle() {
        val customStyle = CustomPromptStyle(
            title = "My Voice",
            icon = "flame",
            instruction = "Reply like an old friend.",
        )
        val prompt = generator.generateCustom(
            customStyle = customStyle,
            activityContext = fixtureContext(),
            imperial = false,
            customIconResolver = { Icons.Filled.LocalFireDepartment },
            zone = nyZone,
        )
        assertNull(prompt.style)
        assertEquals(customStyle, prompt.customStyle)
        assertEquals("My Voice", prompt.title)
        assertEquals("Reply like an old friend.", prompt.subtitle)
        assertTrue(
            "custom prompt should append the user's instruction at the tail",
            prompt.text.contains("Reply like an old friend."),
        )
    }

    @Test
    fun generateCustom_iconResolvedViaResolver() {
        val customStyle = CustomPromptStyle(
            title = "Fire",
            icon = "flame",
            instruction = "Burn brightly.",
        )
        var resolverInvokedWith: String? = null
        val prompt = generator.generateCustom(
            customStyle = customStyle,
            activityContext = fixtureContext(),
            imperial = false,
            customIconResolver = { key ->
                resolverInvokedWith = key
                Icons.Filled.LocalFireDepartment
            },
            zone = nyZone,
        )
        assertEquals("flame", resolverInvokedWith)
        assertEquals(Icons.Filled.LocalFireDepartment, prompt.icon)
    }

    @Test
    fun generateAll_imperialPropagatesToFormatPace() {
        val routeSpeeds = List(20) { 1.4 }
        val imperialPrompts = generator.generateAll(
            activityContext = fixtureContext(routeSpeeds = routeSpeeds),
            imperial = true,
            zone = nyZone,
        )
        val metricPrompts = generator.generateAll(
            activityContext = fixtureContext(routeSpeeds = routeSpeeds),
            imperial = false,
            zone = nyZone,
        )
        assertNotNull(imperialPrompts.firstOrNull { it.text.contains("min/mi") })
        assertNotNull(metricPrompts.firstOrNull { it.text.contains("min/km") })
    }
}
