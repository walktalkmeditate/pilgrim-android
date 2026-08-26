// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production install seam for the two bare-`object` singletons the
 * analysis pipeline depends on ([TranscriptNlp], [VaderSentiment]).
 * Both require an explicit `install(...)` call before their first real
 * use, and neither can be a Hilt injection target itself (a Kotlin
 * `object` has no constructor for Dagger to call) — [TranscriptContextAnalyzer]
 * depends on this class and calls [ensureInstalled] before its first
 * analysis.
 *
 * Lazy, off-main, exactly once: this class's own constructor does no
 * I/O ([WordNetLexicon] injected here is itself I/O-free until its first
 * query — see that class's KDoc), and [ensureInstalled] only loads the
 * VADER lexicon asset and wires both installs on the FIRST call, off
 * [Dispatchers.IO]. The double-checked [installed] flag plus [mutex]
 * means a second caller racing the first one suspends until the first
 * install finishes rather than proceeding against a half-installed
 * [TranscriptNlp]/[VaderSentiment].
 */
@Singleton
class ThreadsAnalysisEnvironment @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wordNetLexicon: WordNetLexicon,
) {
    private val mutex = Mutex()

    @Volatile
    private var installed = false

    suspend fun ensureInstalled() {
        if (installed) return
        mutex.withLock {
            if (installed) return@withLock
            withContext(Dispatchers.IO) {
                TranscriptNlp.install(wordNetLexicon)
                VaderSentiment.install(loadVaderLexicon())
            }
            installed = true
        }
    }

    /**
     * The VADER lexicon asset is a plain `word<TAB>meanSentiment` line
     * format (already trimmed to the two columns this port needs — see
     * `tools/threads/derive_nlp_assets.py`'s provenance), gzip'd under
     * the same `.gzip` asset-merge-safe naming [WordNetLexicon] uses.
     */
    private fun loadVaderLexicon(): Map<String, Double> =
        GZIPInputStream(context.assets.open("$ASSET_ROOT/$VADER_LEXICON_ASSET")).bufferedReader().use { reader ->
            reader.lineSequence()
                .filter { it.isNotEmpty() }
                .associate { line ->
                    val (word, score) = line.split("\t", limit = 2)
                    word to score.toDouble()
                }
        }

    private companion object {
        const val ASSET_ROOT = "threads"
        const val VADER_LEXICON_ASSET = "vader-lexicon.txt.gzip"
    }
}
