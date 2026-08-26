// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * Derived, recomputable linguistic context for one voice recording.
 * File-persisted JSON via [TranscriptContextStore] — never a Room entity,
 * never exported, never transmitted (DAT-1).
 *
 * [analysisVersion] is the single freshness discriminant (BEH-14/DAT-2):
 * bump it whenever a change to HOW context is derived (extractor filters,
 * marker lexicons, tokenizer rules, etc.) makes existing stored files
 * semantically stale — not only when this class's shape changes. Three
 * visibility rules every reader must share:
 *  - [TranscriptContextStore.loadAll] hides stale-version files entirely.
 *  - [TranscriptContextStore.loadAllIncludingStaleVersions] still sees them
 *    (the stale-orphan sweep needs to find and clean them up).
 *  - A stale-version file reads as absent to any "is this current"
 *    existence check.
 *
 * Android keeps its own independent version counter — no schema import
 * from iOS (project convention) — but the three visibility rules above are
 * pinned parity regardless of the number.
 *
 * [transcriptHash] is SHA-256 over the transcript's UTF-8 bytes, lowercase
 * hex, no separator or prefix (EDG-39) — computed via [hashTranscript].
 * [markers] is never null for a WRITTEN context: the English-only gate
 * (R5) lives at [TranscriptContextAnalyzer], which writes nothing at all
 * for a non-English recording rather than a context with a null
 * `markers` field (an Android-original tightening — see that class's
 * KDoc for why this diverges from iOS, which computes themes regardless
 * of language and only nulls the markers field).
 */
@Serializable
data class TranscriptContext(
    val uuid: String,
    val languageCode: String?,
    val wordCount: Int,
    val themes: List<Theme>,
    val markers: TranscriptMarkers,
    val transcriptHash: String,
    val analysisVersion: Int = ANALYSIS_VERSION,
) {
    companion object {
        /**
         * Bump in lockstep with any change to theme/marker derivation
         * (stoplists, tokenizer rules, lexicons) — see this class's KDoc.
         */
        const val ANALYSIS_VERSION = 1

        fun hashTranscript(transcript: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
