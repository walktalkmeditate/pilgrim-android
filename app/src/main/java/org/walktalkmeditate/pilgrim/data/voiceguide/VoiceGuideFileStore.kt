// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Filesystem source-of-truth for voice-guide prompt downloads.
 * Matches iOS's design: the disk is the authoritative state, and a
 * pack is "downloaded" iff every walk + meditation prompt file
 * exists and has the expected size. This is self-healing — if the
 * user clears app data or the OS offloads files, the next
 * filesystem check recomputes the correct state.
 *
 * Layout: `filesDir/voice_guide_prompts/<r2Key>`. The `r2Key`
 * string from the manifest is used verbatim as the relative path
 * (e.g. `"morning-walk/p1.aac"`). That makes it format-agnostic —
 * if iOS ever ships `.opus` or a new directory scheme, we inherit
 * it automatically instead of reconstructing the URL from packId +
 * promptId + hardcoded extension.
 *
 * [invalidations] broadcasts when [deletePack] completes so observers
 * (e.g. `VoiceGuideCatalogRepository`) can re-derive pack state.
 * `replay = 0` + `DROP_OLDEST` because missing an invalidation is
 * harmless — the next filesystem check will reconcile.
 */
@Singleton
class VoiceGuideFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val promptsRoot: File by lazy {
        File(context.filesDir, PROMPTS_DIR).also { it.mkdirs() }
    }

    private val _invalidations = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val invalidations: SharedFlow<Unit> = _invalidations.asSharedFlow()

    /** Absolute file for a prompt's r2Key. Creates parent dirs. */
    fun fileForPrompt(r2Key: String): File {
        val file = File(promptsRoot, r2Key)
        file.parentFile?.mkdirs()
        return file
    }

    /**
     * True iff the file exists AND its length matches the manifest's
     * [VoiceGuidePrompt.fileSizeBytes]. Size check (not just existence)
     * catches truncated downloads without needing a hash.
     */
    fun isPromptAvailable(prompt: VoiceGuidePrompt): Boolean {
        val f = fileForPrompt(prompt.r2Key)
        return f.exists() && f.length() == prompt.fileSizeBytes
    }

    /** Walk prompts + meditation prompts (if any). */
    fun allPrompts(pack: VoiceGuidePack): List<VoiceGuidePrompt> =
        pack.prompts + (pack.meditationPrompts ?: emptyList())

    /** Prompts that are missing or size-mismatched and need to be (re)fetched. */
    fun missingPrompts(pack: VoiceGuidePack): List<VoiceGuidePrompt> =
        allPrompts(pack).filterNot { isPromptAvailable(it) }

    fun isPackDownloaded(pack: VoiceGuidePack): Boolean =
        allPrompts(pack).all { isPromptAvailable(it) }

    /**
     * Delete every prompt file the pack owns by reusing [fileForPrompt]
     * (same function that placed them there). A prior version deleted
     * `File(promptsRoot, pack.id)` recursively, which ONLY worked
     * because `r2Key` happens to be `"<packId>/<name>"` under iOS's
     * current CDN layout. The class comment explicitly promises any
     * `r2Key` scheme is fine — if iOS reorganizes (e.g. shared prefix
     * like `"audio/<packId>/…"` or flat `"<promptId>.opus"`), the
     * directory-delete would silently no-op and the files would leak
     * forever with no user-accessible cleanup path. Driving delete
     * from the same `fileForPrompt` mapping the worker uses for
     * writes eliminates the drift.
     *
     * After deleting prompt files, also prune now-empty parent
     * directories up to `promptsRoot` — keeps the cache tidy without
     * assuming any particular layout.
     *
     * Best-effort — ignores per-file delete failures. Emits an
     * invalidation so observers re-derive state regardless of outcome.
     */
    fun deletePack(pack: VoiceGuidePack) {
        allPrompts(pack).forEach { prompt ->
            fileForPrompt(prompt.r2Key).delete()
        }
        pruneEmptyDirs()
        _invalidations.tryEmit(Unit)
    }

    /**
     * Walk children of [promptsRoot] and delete any directory that is
     * now empty after a pack delete. Only one level deep — matches
     * iOS's flat-per-pack layout and avoids accidentally removing
     * directories still owned by other packs.
     */
    private fun pruneEmptyDirs() {
        promptsRoot.listFiles()?.forEach { child ->
            if (child.isDirectory && child.list()?.isEmpty() == true) {
                child.delete()
            }
        }
    }

    private companion object {
        const val PROMPTS_DIR = "voice_guide_prompts"
    }
}
