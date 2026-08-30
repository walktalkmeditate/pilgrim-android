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
 * [analysisVersion] defaults to the [UNVERSIONED] sentinel, NOT to
 * [ANALYSIS_VERSION], and every write site passes the current version
 * explicitly. The store's `Json` runs with `encodeDefaults = false`, so a
 * property whose value equals its declared default is dropped from the
 * encoded file — defaulting to the current version would mean the key
 * never reaches disk at all, and every file already on disk would then
 * decode as whatever version is current at read time. That defeats every
 * future bump silently: nothing looks stale, so nothing re-analyzes. A
 * sentinel no real version equals keeps both halves honest — the key is
 * always encoded, and a file that genuinely lacks it reads as stale.
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
    val analysisVersion: Int = UNVERSIONED,
) {
    companion object {
        /**
         * Stands for "this file predates versioned analysis, or was
         * written by a build that failed to record its version" — see
         * this class's KDoc for why the default is a sentinel. Never a
         * value [ANALYSIS_VERSION] may take, so a sentinel-versioned
         * context can only ever read as stale.
         */
        const val UNVERSIONED = 0

        /**
         * Bump in lockstep with any change to theme/marker derivation
         * (stoplists, tokenizer rules, lexicons) — see this class's KDoc.
         *
         * v2 (iOS schema v4->v5 fold-in, 2026-08-28): [SpokenStoplist]
         * gained [SpokenStoplist.filler] plus `time`/`person`/`app` in
         * [SpokenStoplist.lightNouns], making a v1 file's themes wrong
         * rather than merely coarse — its stored themes may name filler
         * ('yeah') or the new light nouns ('time', 'person', 'app'). The
         * bump forces every stored recording to re-analyze under the
         * tightened stoplists. (iOS's v5 also covers its letterCore
         * punctuation repair; this substrate's tokenizer never had that
         * bug, so only the stoplist half applies here.) No moon-line
         * re-arm accompanies this bump, same as iOS.
         *
         * v3 (U12 device field read, 2026-08-30): Android-only.
         * [SpokenStoplist.androidHomographNounSuppression] gained "over",
         * "out" and "here" after all three threaded as themes on the first
         * corpus exercising ordinary reflective phrasing. A v2 file's
         * themes can name any of them, so the bump forces re-analysis. Like
         * V5->V6 on iOS this narrows theme extraction only, so again no
         * moon-line re-arm. Nothing on the prompt-time side changed:
         * [SpokenStoplist.nonContentLemmas] already unioned this set, and
         * those consumers cache nothing.
         */
        const val ANALYSIS_VERSION = 3

        fun hashTranscript(transcript: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
