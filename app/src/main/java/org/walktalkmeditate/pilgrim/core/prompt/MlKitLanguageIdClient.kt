// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

/**
 * One (language tag, confidence) reading from the underlying language-id
 * client, decoupled from ML Kit's own `IdentifiedLanguage` type so
 * [MlKitLanguageIdClient]'s confidence gate is testable without it.
 */
internal data class LanguageGuess(val languageTag: String, val confidence: Float)

/**
 * Seam over ML Kit's language-identification client. Real inference needs
 * the on-device model and doesn't run on the JVM, so
 * [MlKitLanguageIdClientTest]'s confidence-gate tests substitute a fake
 * here — the same shape as [org.walktalkmeditate.pilgrim.audio.WhisperNative]
 * seams whisper.cpp. The confidence threshold below is deliberately 0f (ML
 * Kit's own gate disabled) so every guess ML Kit can produce reaches
 * [MlKitLanguageIdClient.detect], which applies its own 0.5 floor —
 * otherwise that floor would be untestable, applied entirely inside
 * Google's closed-source model.
 */
internal interface LanguageIdentifierGateway {
    suspend fun identifyPossibleLanguages(text: String): List<LanguageGuess>
}

private class RealLanguageIdentifierGateway : LanguageIdentifierGateway {

    private val identifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0f)
            .build(),
    )

    override suspend fun identifyPossibleLanguages(text: String): List<LanguageGuess> =
        identifier.identifyPossibleLanguages(text).await()
            .map { LanguageGuess(languageTag = it.languageTag, confidence = it.confidence) }
}

/**
 * ML Kit-backed language detector mirroring iOS's `NLLanguageRecognizer`
 * confidence gate (parity spec EDG-2): [detect] returns `null` unless the
 * best-guess language clears [CONFIDENCE_THRESHOLD], matching the 0.5
 * floor iOS uses — though ML Kit's confidence scale is not the same
 * instrument as `NLLanguageRecognizer`'s, so this is a deliberate reuse of
 * the number, not a claim of numeric equivalence (Android implementation
 * notes, EDG-2).
 */
@Singleton
class MlKitLanguageIdClient internal constructor(
    private val gateway: LanguageIdentifierGateway,
) {

    @Inject
    constructor() : this(RealLanguageIdentifierGateway())

    suspend fun detect(text: String): String? {
        val best = gateway.identifyPossibleLanguages(text)
            .filter { it.languageTag != UNDETERMINED_LANGUAGE_TAG }
            .maxByOrNull { it.confidence }
            ?: return null
        return best.languageTag.takeIf { best.confidence >= CONFIDENCE_THRESHOLD }
    }

    private companion object {
        const val CONFIDENCE_THRESHOLD = 0.5f
        const val UNDETERMINED_LANGUAGE_TAG = "und"
    }
}
