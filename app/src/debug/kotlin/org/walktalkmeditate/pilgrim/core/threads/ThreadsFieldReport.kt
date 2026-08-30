// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.dao.AltitudeSampleDao
import org.walktalkmeditate.pilgrim.data.dao.RouteDataSampleDao
import org.walktalkmeditate.pilgrim.data.dao.VoiceRecordingDao
import org.walktalkmeditate.pilgrim.data.dao.WalkDao
import org.walktalkmeditate.pilgrim.data.dao.WalkPhotoDao
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

/**
 * DEBUG-only ship-gate diagnostic (parity spec
 * `docs/parity/2026-08-26-threads-senses-port.md`, field-report section):
 * iterates every walk with a transcribed recording, evaluates EVERY
 * sense UNCAPPED with an EMPTY suppressed set and `moonState = null`, and
 * prints per-sense firing rates plus each emitted line, so a human can
 * judge degeneration (fires on nearly every walk) and dead senses
 * (nearly never) against REAL device history.
 *
 * **QA-interpretation caveat**: this report's firing-rate table
 * intentionally OVER-STATES production frequency on two independent
 * axes — no 3-line cap (every qualifying sense is counted, not just the
 * top 3 that would reach the real `**Noticed:**` block) and no lemma
 * suppression, and `moonState = null` makes moonLine look near-every-
 * walk instead of once-per-lunation. Judge DEGENERATION/DEADNESS from
 * these rates, never production frequency.
 *
 * **Write behavior**: preferences are never touched — this class never
 * calls [ThreadsPreferencesRepository.setMoonLineLastLunationIndex] or
 * any other preference write, so the device's real once-per-lunation
 * budget is safe. It is NOT fully read-only, though: [resolveReportContexts]
 * self-heals a missing/stale stored context via
 * [TranscriptContextAnalyzer.analyzeAndStore], which writes
 * `transcript_contexts/` files and bumps
 * [TranscriptContextStore.changeCount] exactly like production analysis —
 * invalidating any production dossier memo built over the previous
 * count. Those writes are the same ones normal use would eventually
 * make; the harness just makes them early.
 *
 * **Android divergence (planned, not drift)**: iOS gates this harness
 * behind a `--senses-field-report` launch argument; Android has no
 * launch-arg convention, so [ThreadsFieldReportReceiver] is the explicit
 * developer trigger instead — invoke with:
 * ```
 * adb shell am broadcast -a org.walktalkmeditate.pilgrim.debug.RUN_SENSES_FIELD_REPORT
 * ```
 * and read the report via `adb logcat -s ThreadsFieldReport`. Compiled
 * ONLY into the `debug` source set (this file's own location) — R12's
 * "not merely runtime-flagged" requirement is satisfied structurally:
 * the class does not exist in a release build at all.
 */
class ThreadsFieldReport @Inject constructor(
    private val walkDao: WalkDao,
    private val voiceRecordingDao: VoiceRecordingDao,
    private val routeDataSampleDao: RouteDataSampleDao,
    private val walkPhotoDao: WalkPhotoDao,
    private val altitudeSampleDao: AltitudeSampleDao,
    private val store: TranscriptContextStore,
    private val analyzer: TranscriptContextAnalyzer,
    private val preferences: ThreadsPreferencesRepository,
) {

    /**
     * Runs the report and returns the full text (also logged line-by-
     * line to Logcat — a single multi-thousand-character `Log.i` call
     * silently truncates on real devices). [now] is this report's ONE
     * wall-clock capture, shared by every walk it evaluates — a
     * deliberate DEBUG-only exception to the pure-module no-clock rule
     * (this prints a human-facing diagnostic, not a pure measurement).
     */
    suspend fun run(now: Instant = Instant.now()): String {
        val output = StringBuilder()
        output.append(BANNER)

        val allWalks = walkDao.getAll()
        if (allWalks.isEmpty()) {
            output.append(EMPTY_HISTORY).append(CLOSER)
            return output.toString().also(::logLines)
        }

        val eligible = allWalks
            .sortedBy { it.startTimestamp }
            .mapNotNull { walk ->
                val recordings = voiceRecordingDao.getForWalk(walk.id).filter {
                    !it.transcription.isNullOrEmpty() && it.transcription != VoiceRecording.NO_SPEECH_PLACEHOLDER
                }
                if (recordings.isEmpty()) null else walk.id to recordings
            }
        if (eligible.isEmpty()) {
            output.append(NO_TRANSCRIBED_WALKS).append(CLOSER)
            return output.toString().also(::logLines)
        }

        // Same freshness test as ThreadsDossierBuilder.isBackfillComplete —
        // duplicated here (not shared) since this DEBUG-only harness must
        // never depend on production internals changing shape underneath it.
        val backfillComplete = preferences.backfillCompletedAtVersion() == TranscriptContext.ANALYSIS_VERSION &&
            preferences.backfillCompletedAtImportGeneration() == preferences.importGeneration.value
        val recordingWalkIndex = voiceRecordingDao.recordingWalkLiteIndex().associate { it.uuid to it.toWalkLite() }
        val closedLunation = LunationCalendar.mostRecentClosed(asOf = now)

        val firing = linkedMapOf<DossierSenses.Sense, Int>()
        DossierSenses.Sense.entries.forEach { firing[it] = 0 }
        val buildSecondsPerWalk = mutableListOf<Double>()

        for ((walkId, recordings) in eligible) {
            val startNanos = System.nanoTime()
            val walkLite = walkDao.getWalkLite(walkId)?.toWalkLite() ?: continue
            val current = resolveReportContexts(recordings)
            if (current.isEmpty()) continue
            val allContexts = store.loadAll()
            val threads = ThreadStore.build(
                contexts = allContexts,
                recordingToWalk = recordingWalkIndex,
                anchor = walkLite.startedAt,
                backfillComplete = backfillComplete,
            )
            val senseInput = gatherSenseInput(
                walkDao = walkDao,
                voiceRecordingDao = voiceRecordingDao,
                routeDataSampleDao = routeDataSampleDao,
                walkPhotoDao = walkPhotoDao,
                altitudeSampleDao = altitudeSampleDao,
                walkId = walkId,
                walkLite = walkLite,
                closedLunation = closedLunation,
                // NEVER the real preference — see class KDoc.
                moonState = null,
                threads = threads,
                current = current,
                allContexts = allContexts,
                recordingWalkIndex = recordingWalkIndex,
                recordings = recordings,
                backfillComplete = backfillComplete,
                now = now,
            )

            output.append("\nWalk ${walkLite.startedAt}:\n")
            for (sense in DossierSenses.Sense.entries) {
                // Uncapped, empty suppressed set — every qualifying sense
                // fires and is counted, not just the top 3.
                val line = DossierSenses.evaluate(sense, senseInput, emptySet()) ?: continue
                firing[sense] = (firing[sense] ?: 0) + 1
                output.append("  [${sense.fieldReportName()}] ${line.text}\n")
            }
            val buildSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
            buildSecondsPerWalk += buildSeconds
            output.append(String.format(Locale.US, "  build: %.3fs\n", buildSeconds))
        }

        output.append("\nFiring rates over ${eligible.size} walks with words:\n")
        for (sense in DossierSenses.Sense.entries) {
            output.append("  ${sense.fieldReportName()}: ${firing[sense] ?: 0}/${eligible.size}\n")
        }
        val median = DossierSenses.median(buildSecondsPerWalk)
        val max = buildSecondsPerWalk.maxOrNull() ?: 0.0
        output.append(String.format(Locale.US, "\nBuild time — median: %.3fs, max: %.3fs\n", median, max))
        output.append(CLOSER)

        return output.toString().also(::logLines)
    }

    /**
     * Read-only where possible (hash-matched store hit); self-heals via
     * [analyzer] exactly like [ThreadsDossierBuilder]'s own
     * `resolveCurrentContexts` when a stored context is missing or
     * stale — this harness reads real device history, most of which
     * already has analysis from normal use or the backfill sweep.
     */
    private suspend fun resolveReportContexts(
        recordings: List<org.walktalkmeditate.pilgrim.data.entity.VoiceRecording>,
    ): List<Pair<TranscriptContext, Double?>> {
        val current = mutableListOf<Pair<TranscriptContext, Double?>>()
        for (recording in recordings) {
            val text = recording.transcription ?: continue
            val hash = TranscriptContext.hashTranscript(text)
            val stored = store.read(recording.uuid, hash)
            if (stored != null) {
                current += stored to recording.wordsPerMinute
                continue
            }
            analyzer.analyzeAndStore(recording.uuid, text)?.let { current += it to recording.wordsPerMinute }
        }
        return current
    }

    private fun logLines(text: String) {
        for (line in text.lines()) Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "ThreadsFieldReport"
        const val BANNER = "\n===== DOSSIER SENSES FIELD REPORT =====\n"
        const val EMPTY_HISTORY = "\n(no walk history on this device — nothing to report)\n"
        const val NO_TRANSCRIBED_WALKS = "\n(no walk carries a transcribed recording — nothing to report)\n"
        val CLOSER = "=".repeat(39) + "\n"
    }
}

/** Lowercase-first-letter Sense name, matching the iOS Swift case names
 * verbatim (e.g. `PLACE_RESONANCE` -> `"placeResonance"`) — the field
 * report's console format is meant to stay comparable with iOS's own
 * ship-gate output. */
private fun DossierSenses.Sense.fieldReportName(): String =
    name.split("_").mapIndexed { index, part ->
        val lower = part.lowercase(Locale.ROOT)
        if (index == 0) lower else lower.replaceFirstChar { it.uppercase(Locale.ROOT) }
    }.joinToString("")

/**
 * The explicit developer trigger (Android has no launch-arg convention
 * to mirror iOS's `--senses-field-report`) — see [ThreadsFieldReport]'s
 * KDoc for the `adb shell am broadcast` invocation. Fire-and-forget: the
 * report runs on [Dispatchers.Default] and logs as it goes, so a slow
 * device history never blocks the broadcast dispatch.
 */
@AndroidEntryPoint
class ThreadsFieldReportReceiver : BroadcastReceiver() {

    @Inject lateinit var report: ThreadsFieldReport

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                report.run()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
