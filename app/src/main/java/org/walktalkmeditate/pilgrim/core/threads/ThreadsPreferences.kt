// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.walktalkmeditate.pilgrim.di.ThreadsPreferencesDataStore

/**
 * U6 backfill progress marker: how far [ThreadsBackfillRunner] got through
 * the current sweep attempt, so a mid-run WorkManager kill resumes rather
 * than restarts. [watermark] is the LAST uuid of the clean prefix of the
 * snapshot sorted by uuid ascending (matching [TranscriptContextStore.loadAll]'s
 * own determinism convention), `null` when no batch has come back clean
 * yet — a resume starts at the first snapshot uuid strictly greater. A
 * uuid survives recordings being deleted between attempts, where an
 * integer prefix LENGTH would reshape under the sort and let a resume
 * skip never-analyzed items or stamp completion without attempting the
 * remainder. It only ever advances past a batch whose every item was
 * confirmed accounted for, so a resume never skips a still-failing item.
 * [forImportGeneration] pins the checkpoint to the import epoch it was
 * computed against: a mismatch against the CURRENT
 * [ThreadsPreferencesRepository.importGeneration] means an import landed
 * since, and the checkpoint must be discarded rather than trusted (a
 * fresh, differently-shaped snapshot may not agree on what "the clean
 * prefix" means). [atAnalysisVersion] pins it the same way to the
 * [TranscriptContext.ANALYSIS_VERSION] it was computed against: a mismatch
 * means the accounting rules themselves changed since this checkpoint was
 * written, so the prefix it counts as "already accounted for" may still
 * hold stale-version contexts that a resume would otherwise never
 * revisit — this must invalidate the checkpoint exactly like an
 * import-generation mismatch does, not be trusted just because the
 * generation still matches.
 */
data class BackfillCheckpoint(
    val watermark: String?,
    val forImportGeneration: Int,
    val atAnalysisVersion: Int,
) {
    companion object {
        val EMPTY = BackfillCheckpoint(watermark = null, forImportGeneration = 0, atAnalysisVersion = 0)
    }
}

/**
 * Thought Threads preferences (R12 toggle sovereignty + import/backfill
 * hygiene). All threads-feature units gate on [threadsAfterWalks] before
 * doing any analysis, backfill, or dossier work.
 *
 * Eagerly-started StateFlows (matching [org.walktalkmeditate.pilgrim.data.voice.VoicePreferencesRepository])
 * so the transcription runner and analyzer can read `.value` synchronously
 * from background contexts with no UI subscriber.
 */
interface ThreadsPreferencesRepository {
    val threadsAfterWalks: StateFlow<Boolean>

    /**
     * Bumped once per successful `.pilgrim` import (U5). U6's backfill
     * sweep compares this against its own last-seen value to decide
     * whether imported recordings need a fresh pass — the counter is the
     * whole contract; this unit does not consume it.
     */
    val importGeneration: StateFlow<Int>

    suspend fun setThreadsAfterWalks(enabled: Boolean)

    suspend fun bumpImportGeneration()

    /**
     * Clears the moon-line-last-reported key (U9 owns reading/writing it
     * during normal operation). Part of the internal full-wipe API's
     * three-step order — see [ThreadsFullWipe].
     */
    suspend fun clearMoonLineIndex()

    /**
     * U9: the last lunation index the moon line was actually SHOWN for —
     * `null` means "never shown". Absent must read as `null`, never
     * 0-defaulted (a real lunationIndex of 0 would otherwise collide
     * with "never reported"). [ThreadsDossierBuilder] reads this fresh
     * on every build and folds it into the memo key as `moonState`.
     */
    suspend fun moonLineLastLunationIndex(): Int?

    /**
     * Written ONLY when [DossierSenses.lines]'s `reportedLunationIndex`
     * is non-null — strictly when the moon line fired AND survived
     * cap/dedup into the emitted block. Writing on mere eligibility
     * would burn the once-per-lunation budget on a line that never
     * actually shipped.
     */
    suspend fun setMoonLineLastLunationIndex(index: Int)

    /**
     * U6: the [TranscriptContext.ANALYSIS_VERSION] the backfill sweep last
     * completed at, or `null` if it never has — the single-key analogue of
     * iOS's `threadsBackfillCompletedV6`-style rename ladder (no legacy
     * keys ever existed on Android, so there is nothing to migrate). A
     * version bump makes this stale automatically: the runner compares it
     * against the CURRENT [TranscriptContext.ANALYSIS_VERSION] rather than
     * trusting a bare boolean.
     */
    suspend fun backfillCompletedAtVersion(): Int?

    /**
     * U6: the [importGeneration] value as of the last completed sweep.
     * Paired with [backfillCompletedAtVersion] — completion is trusted
     * only when BOTH match the current version AND the current
     * generation; either mismatch re-arms the sweep. This is the
     * mechanism by which a `.pilgrim` import (which bumps
     * [importGeneration] but never analyzes on write — U5) re-arms the
     * backfill without needing iOS's in-memory `generation` counter,
     * which cannot survive the WorkManager process-death boundary.
     */
    suspend fun backfillCompletedAtImportGeneration(): Int

    suspend fun setBackfillCompleted(version: Int, atImportGeneration: Int)

    /** Re-arms the sweep: called by `setEnabled(true)` on toggle re-enable. */
    suspend fun clearBackfillCompleted()

    /** See [BackfillCheckpoint]. */
    suspend fun backfillCheckpoint(): BackfillCheckpoint

    suspend fun setBackfillCheckpoint(checkpoint: BackfillCheckpoint)

    suspend fun clearBackfillCheckpoint()
}

@Singleton
class DataStoreThreadsPreferencesRepository @Inject constructor(
    @ThreadsPreferencesDataStore private val dataStore: DataStore<Preferences>,
    @ThreadsPreferencesScope private val scope: CoroutineScope,
) : ThreadsPreferencesRepository {

    override val threadsAfterWalks: StateFlow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[THREADS_AFTER_WALKS] ?: DEFAULT_THREADS_AFTER_WALKS }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_THREADS_AFTER_WALKS)

    override val importGeneration: StateFlow<Int> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[IMPORT_GENERATION] ?: 0 }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    override suspend fun setThreadsAfterWalks(enabled: Boolean) {
        dataStore.edit { it[THREADS_AFTER_WALKS] = enabled }
    }

    override suspend fun bumpImportGeneration() {
        dataStore.edit { prefs -> prefs[IMPORT_GENERATION] = (prefs[IMPORT_GENERATION] ?: 0) + 1 }
    }

    override suspend fun clearMoonLineIndex() {
        dataStore.edit { it.remove(MOON_LINE_LAST_LUNATION_INDEX) }
    }

    override suspend fun moonLineLastLunationIndex(): Int? =
        dataStore.data.first()[MOON_LINE_LAST_LUNATION_INDEX]

    override suspend fun setMoonLineLastLunationIndex(index: Int) {
        dataStore.edit { it[MOON_LINE_LAST_LUNATION_INDEX] = index }
    }

    override suspend fun backfillCompletedAtVersion(): Int? =
        dataStore.data.first()[BACKFILL_COMPLETED_AT_VERSION]

    override suspend fun backfillCompletedAtImportGeneration(): Int =
        dataStore.data.first()[BACKFILL_COMPLETED_AT_IMPORT_GENERATION] ?: 0

    override suspend fun setBackfillCompleted(version: Int, atImportGeneration: Int) {
        dataStore.edit { prefs ->
            prefs[BACKFILL_COMPLETED_AT_VERSION] = version
            prefs[BACKFILL_COMPLETED_AT_IMPORT_GENERATION] = atImportGeneration
        }
    }

    override suspend fun clearBackfillCompleted() {
        dataStore.edit { prefs ->
            prefs.remove(BACKFILL_COMPLETED_AT_VERSION)
            prefs.remove(BACKFILL_COMPLETED_AT_IMPORT_GENERATION)
        }
    }

    override suspend fun backfillCheckpoint(): BackfillCheckpoint {
        val prefs = dataStore.data.first()
        // The generation key signals checkpoint presence — the watermark
        // itself is legitimately absent when no batch has come back clean.
        val forImportGeneration = prefs[BACKFILL_CHECKPOINT_IMPORT_GENERATION]
            ?: return BackfillCheckpoint.EMPTY
        // A checkpoint persisted without this key decodes with no
        // recorded version at all; NO_STORED_ANALYSIS_VERSION is
        // guaranteed to mismatch the current ANALYSIS_VERSION so such a
        // checkpoint is treated as stale rather than trusted.
        val atAnalysisVersion = prefs[BACKFILL_CHECKPOINT_ANALYSIS_VERSION] ?: NO_STORED_ANALYSIS_VERSION
        return BackfillCheckpoint(prefs[BACKFILL_CHECKPOINT_WATERMARK], forImportGeneration, atAnalysisVersion)
    }

    override suspend fun setBackfillCheckpoint(checkpoint: BackfillCheckpoint) {
        dataStore.edit { prefs ->
            val watermark = checkpoint.watermark
            if (watermark == null) {
                prefs.remove(BACKFILL_CHECKPOINT_WATERMARK)
            } else {
                prefs[BACKFILL_CHECKPOINT_WATERMARK] = watermark
            }
            prefs[BACKFILL_CHECKPOINT_IMPORT_GENERATION] = checkpoint.forImportGeneration
            prefs[BACKFILL_CHECKPOINT_ANALYSIS_VERSION] = checkpoint.atAnalysisVersion
        }
    }

    override suspend fun clearBackfillCheckpoint() {
        dataStore.edit { prefs ->
            prefs.remove(BACKFILL_CHECKPOINT_WATERMARK)
            prefs.remove(BACKFILL_CHECKPOINT_IMPORT_GENERATION)
            prefs.remove(BACKFILL_CHECKPOINT_ANALYSIS_VERSION)
        }
    }

    private companion object {
        // Verbatim iOS UserDefaults key name (UserPreferences.swift) —
        // naming discipline only; DataStore and UserDefaults never
        // interop directly.
        val THREADS_AFTER_WALKS = booleanPreferencesKey("threadsAfterWalks")

        // Android-original: iOS's ThreadsBackfill.generation is an
        // in-memory Int (meaningless across process death, Open question
        // 12). Android persists its own import-triggered epoch instead.
        val IMPORT_GENERATION = intPreferencesKey("threadsImportGeneration")

        // Verbatim iOS UserDefaults key name (ThreadsDossierBuilder.swift,
        // DAT-52) — reserved here (U5) so the full-wipe hygiene sweep has
        // a name to clear; U9 owns the read/write touch points.
        val MOON_LINE_LAST_LUNATION_INDEX = intPreferencesKey("threadsMoonLineLastLunationIndex")

        // U6: Android-original key names — no iOS UserDefaults counterpart
        // to mirror (iOS's completed-flag ladder and generation counter
        // don't survive the WorkManager process-death boundary Android's
        // backfill runs across).
        val BACKFILL_COMPLETED_AT_VERSION = intPreferencesKey("backfillCompletedAtVersion")
        val BACKFILL_COMPLETED_AT_IMPORT_GENERATION = intPreferencesKey("backfillCompletedAtImportGeneration")
        val BACKFILL_CHECKPOINT_WATERMARK = stringPreferencesKey("backfillCheckpointWatermark")
        val BACKFILL_CHECKPOINT_IMPORT_GENERATION = intPreferencesKey("backfillCheckpointImportGeneration")
        val BACKFILL_CHECKPOINT_ANALYSIS_VERSION = intPreferencesKey("backfillCheckpointAnalysisVersion")

        const val DEFAULT_THREADS_AFTER_WALKS = true

        // TranscriptContext.ANALYSIS_VERSION starts at 1 and only ever
        // increases, so -1 can never coincide with a real version — a
        // safe "always stale" default for a checkpoint stored before
        // BACKFILL_CHECKPOINT_ANALYSIS_VERSION existed.
        const val NO_STORED_ANALYSIS_VERSION = -1
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ThreadsPreferencesScope
