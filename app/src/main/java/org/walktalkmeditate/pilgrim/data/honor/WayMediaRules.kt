// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia

/**
 * iOS `WayMediaDownloader`'s statics (`WayMediaDownloader.swift:44-98@7c200bf`,
 * shared-walk spec S3 §2–§3): which files a shared Way asks for, the byte
 * cap each one gets, and the per-kind ceilings applied to everything the
 * Way declares.
 */
object WayMediaRules {

    /** iOS `maxAudioFilesPerWay`: the sharing side ships at most 12 recordings. */
    const val MAX_AUDIO_FILES = 12

    /** iOS `maxPhotoFilesPerWay`. */
    const val MAX_PHOTO_FILES = 20

    /** `15 * 1024 * 1024`: a file is refused only when strictly larger. */
    const val AUDIO_BYTE_CAP = 15L * 1024 * 1024

    /** `2 * 1024 * 1024`, for every path that doesn't start with `audio/`. */
    const val PHOTO_BYTE_CAP = 2L * 1024 * 1024

    private const val AUDIO_FOLDER = "audio/"

    /** iOS `mediaPathPattern`, `\A(?:audio/[0-9]{1,5}\.m4a|photos/[0-9]{1,5}\.jpg)\z`; [Regex.matchEntire] anchors it. */
    private val MEDIA_PATH = Regex("(audio)/([0-9]{1,5})\\.m4a|(photos)/([0-9]{1,5})\\.jpg")

    /** The files the moments name, each once, in moment order (iOS `mediaFiles(for:)`). */
    fun mediaFiles(way: Way): List<String> =
        way.moments.mapNotNull { (it.media as? WayMedia.File)?.path }.distinct()

    fun byteCap(relative: String): Long = if (relative.startsWith(AUDIO_FOLDER)) AUDIO_BYTE_CAP else PHOTO_BYTE_CAP

    data class Split(val accepted: List<String>, val refused: List<String>)

    /**
     * iOS `withinCeilings`: the 13th and later audio paths and the 21st and
     * later photo paths, in order, are refused, so the same manifest always
     * refuses the same files.
     */
    fun withinCeilings(files: List<String>): Split {
        val accepted = mutableListOf<String>()
        val refused = mutableListOf<String>()
        var audio = 0
        var photos = 0
        for (relative in files) {
            val isAudio = relative.startsWith(AUDIO_FOLDER)
            val room = if (isAudio) audio < MAX_AUDIO_FILES else photos < MAX_PHOTO_FILES
            if (!room) {
                refused += relative
                continue
            }
            if (isAudio) audio++ else photos++
            accepted += relative
        }
        return Split(accepted, refused)
    }

    /**
     * The path rebuilt from its integer index, or null for anything but
     * the importer's two shapes: a percent-escape, a `..`, a stray folder
     * or extension, or a leading zero the importer never writes. Checked
     * before every fetch, where iOS checks only a relaunch rebuild (S3 §2,
     * an R6 addition).
     */
    fun canonicalPath(relative: String): String? {
        val match = MEDIA_PATH.matchEntire(relative) ?: return null
        val (audio, audioIndex, _, photoIndex) = match.destructured
        val rebuilt = if (audio.isNotEmpty()) {
            "audio/${audioIndex.toInt()}.m4a"
        } else {
            "photos/${photoIndex.toInt()}.jpg"
        }
        return rebuilt.takeIf { it == relative }
    }
}
