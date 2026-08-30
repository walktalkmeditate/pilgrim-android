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
        directives: List<String>? = null,
        detectedLanguageName: String? = null,
    ): GeneratedPrompt {
        val voice = style.voiceFor()
        val text = PromptAssembler.assemble(
            context = activityContext,
            voice = voice,
            imperial = imperial,
            weatherLabel = weatherLabel,
            zone = zone,
            directives = directives,
            detectedLanguageName = detectedLanguageName,
        )
        return GeneratedPrompt(
            style = style,
            customStyle = null,
            title = context.getString(style.titleRes),
            subtitle = context.getString(style.descRes),
            text = text,
            icon = style.icon,
            hasThreadsDossier = activityContext.threadsDossier != null,
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
        directives: List<String>? = null,
        detectedLanguageName: String? = null,
    ): GeneratedPrompt {
        val voice = CustomPromptStyleVoice(customStyle)
        val text = PromptAssembler.assemble(
            context = activityContext,
            voice = voice,
            imperial = imperial,
            weatherLabel = weatherLabel,
            zone = zone,
            directives = directives,
            detectedLanguageName = detectedLanguageName,
        )
        return GeneratedPrompt(
            style = null,
            customStyle = customStyle,
            title = customStyle.title,
            subtitle = customStyle.instruction,
            text = text,
            icon = customIconResolver(customStyle.icon),
            hasThreadsDossier = activityContext.threadsDossier != null,
        )
    }

    /**
     * The single resolution point for the derivations a prompt-list build
     * needs: one language detection feeds both the display name and the
     * [AttentionDirectives] echo detector (which then skips its own
     * detection), and one directives pass serves every style —
     * [generateAll] computes this ONCE and fans it out rather than
     * re-running the lemmatization pass per style (U7/BEH-77).
     *
     * @param detectedLanguageCode The transcript's detected ISO language
     *   code, or `null` when nothing has detected it yet (today's only
     *   production case — Android has no synchronous on-device detector
     *   to call inline here the way iOS's `PromptAssembler.detectedLanguageCode`
     *   does; a future async-aware caller that already ran ML Kit
     *   detection can pass its result through this parameter).
     */
    fun resolvedDerivations(
        activityContext: ActivityContext,
        detectedLanguageCode: String? = null,
    ): ResolvedPromptDerivations = ResolvedPromptDerivations(
        directives = AttentionDirectives.detect(activityContext, detectedLanguageCode),
        languageName = detectedLanguageCode?.let { PromptAssembler.languageName(it) },
    )

    /**
     * Generate one [GeneratedPrompt] per built-in [PromptStyle], in
     * `PromptStyle.entries` declaration order — Contemplative,
     * Reflective, Creative, Gratitude, Philosophical, Journaling.
     * Custom styles are NOT included here; Task 10 iterates the
     * [CustomPromptStyleStore] separately and calls [generateCustom]
     * for each.
     *
     * @param directives Precomputed directives shared across every style,
     *   or `null` to compute via [resolvedDerivations] once here. Passing
     *   directives without [detectedLanguageName] is a valid override
     *   (e.g. a caller that only wants to skip directive recomputation).
     */
    fun generateAll(
        activityContext: ActivityContext,
        imperial: Boolean,
        weatherLabel: (WeatherCondition) -> String = { context.getString(it.labelRes) },
        zone: ZoneId = ZoneId.systemDefault(),
        directives: List<String>? = null,
        detectedLanguageName: String? = null,
    ): List<GeneratedPrompt> {
        val resolved = if (directives != null) {
            ResolvedPromptDerivations(directives, detectedLanguageName)
        } else {
            resolvedDerivations(activityContext)
        }
        return PromptStyle.entries.map { style ->
            generate(
                style = style,
                activityContext = activityContext,
                imperial = imperial,
                weatherLabel = weatherLabel,
                zone = zone,
                directives = resolved.directives,
                detectedLanguageName = resolved.languageName,
            )
        }
    }
}

/** [PromptGenerator.resolvedDerivations]'s result — see that function's KDoc. */
data class ResolvedPromptDerivations(val directives: List<String>, val languageName: String?)

private fun PromptStyle.voiceFor(): WalkPromptVoice = when (this) {
    PromptStyle.Contemplative -> ContemplativeVoice
    PromptStyle.Reflective -> ReflectiveVoice
    PromptStyle.Creative -> CreativeVoice
    PromptStyle.Gratitude -> GratitudeVoice
    PromptStyle.Philosophical -> PhilosophicalVoice
    PromptStyle.Journaling -> JournalingVoice
}
