// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import android.content.Context
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.core.prompt.voices.ContemplativeVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.CreativeVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.CustomPromptStyleVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.GratitudeVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.JournalingVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.PhilosophicalVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.ReflectiveVoice
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition

/**
 * Resolves an [ActivityContext] + [PromptStyle] (or [CustomPromptStyle])
 * into a fully-rendered [GeneratedPrompt]. Verbatim port of iOS
 * `PromptGenerator.swift`'s ActivityContext API:
 *
 *  - `generate(style, context)` — single built-in style
 *  - `generateCustom(customStyle, context)` — single user-defined style
 *  - `generateAll(context)` — one prompt per `PromptStyle.entries` (six)
 *
 * iOS's legacy parameter-spreading variants do not port — every Android
 * caller (Task 10 PromptsCoordinator) builds an [ActivityContext]
 * directly, so the older flat-arg overloads would be dead code.
 *
 * **Display-field resolution divergence from iOS.** iOS
 * `GeneratedPrompt` exposes `title` / `subtitle` / `icon` as computed
 * properties (`customStyle?.title ?? style?.title ?? ""` at access
 * time). Android [GeneratedPrompt] (Task 5) instead stores those as
 * pre-resolved fields, so this generator is responsible for resolving
 * them at construction time:
 *  - built-in styles: `Context.getString(style.titleRes / descRes)`
 *    + `style.icon` (Material `ImageVector` baked into the enum)
 *  - custom styles: `customStyle.title` / `customStyle.instruction`
 *    verbatim, plus an icon-key string passed through a caller-supplied
 *    [customIconResolver] lambda. The 20-icon Material map lives in
 *    Task 16 (`CustomPromptEditorDialog`) — keeping the lookup as a
 *    lambda parameter avoids dragging the table into `core/prompt/`.
 *
 * **Why a class with `@ApplicationContext`** (vs an `object`): the
 * default [weatherLabel] resolves a [WeatherCondition] enum to a
 * localized label via `context.getString(it.labelRes)` for the
 * recent-walks block. A `@Singleton` Hilt-injected class is the cheapest
 * way to bind that dependency; Task 10 (PromptsCoordinator) injects this
 * class directly.
 */
@Singleton
class PromptGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Generate a single [GeneratedPrompt] for a built-in [PromptStyle]. */
    fun generate(
        style: PromptStyle,
        activityContext: ActivityContext,
        imperial: Boolean,
        weatherLabel: (WeatherCondition) -> String = { context.getString(it.labelRes) },
        zone: ZoneId = ZoneId.systemDefault(),
    ): GeneratedPrompt {
        val voice = style.voiceFor()
        val text = PromptAssembler.assemble(
            context = activityContext,
            voice = voice,
            imperial = imperial,
            weatherLabel = weatherLabel,
            zone = zone,
        )
        return GeneratedPrompt(
            style = style,
            customStyle = null,
            title = context.getString(style.titleRes),
            subtitle = context.getString(style.descRes),
            text = text,
            icon = style.icon,
        )
    }

    /** Generate a single [GeneratedPrompt] for a user-defined [CustomPromptStyle]. */
    fun generateCustom(
        customStyle: CustomPromptStyle,
        activityContext: ActivityContext,
        imperial: Boolean,
        customIconResolver: (String) -> ImageVector,
        weatherLabel: (WeatherCondition) -> String = { context.getString(it.labelRes) },
        zone: ZoneId = ZoneId.systemDefault(),
    ): GeneratedPrompt {
        val voice = CustomPromptStyleVoice(customStyle)
        val text = PromptAssembler.assemble(
            context = activityContext,
            voice = voice,
            imperial = imperial,
            weatherLabel = weatherLabel,
            zone = zone,
        )
        return GeneratedPrompt(
            style = null,
            customStyle = customStyle,
            title = customStyle.title,
            subtitle = customStyle.instruction,
            text = text,
            icon = customIconResolver(customStyle.icon),
        )
    }

    /**
     * Generate one [GeneratedPrompt] per built-in [PromptStyle], in
     * `PromptStyle.entries` declaration order — Contemplative,
     * Reflective, Creative, Gratitude, Philosophical, Journaling.
     * Custom styles are NOT included here; Task 10 iterates the
     * [CustomPromptStyleStore] separately and calls [generateCustom]
     * for each.
     */
    fun generateAll(
        activityContext: ActivityContext,
        imperial: Boolean,
        weatherLabel: (WeatherCondition) -> String = { context.getString(it.labelRes) },
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<GeneratedPrompt> = PromptStyle.entries.map { style ->
        generate(
            style = style,
            activityContext = activityContext,
            imperial = imperial,
            weatherLabel = weatherLabel,
            zone = zone,
        )
    }
}

private fun PromptStyle.voiceFor(): WalkPromptVoice = when (this) {
    PromptStyle.Contemplative -> ContemplativeVoice
    PromptStyle.Reflective -> ReflectiveVoice
    PromptStyle.Creative -> CreativeVoice
    PromptStyle.Gratitude -> GratitudeVoice
    PromptStyle.Philosophical -> PhilosophicalVoice
    PromptStyle.Journaling -> JournalingVoice
}
