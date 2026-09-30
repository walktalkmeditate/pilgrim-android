// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.honor

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind

/**
 * Resolves a Way voice to its file at the moment it plays (iOS
 * `localMediaURL(for:wayId:store:)`, `ActiveWalkViewModel+Honor.swift:354-377@7c200bf`):
 * an own walk's recording under the app's files, a shared walk's under the
 * Way's `media/`. A relative path comes from a Way's JSON, so one that
 * would climb out of its base resolves to nothing, and so does a file
 * that is gone: a recording deleted mid-walk reads as missing at its spot.
 */
class HonorMediaFiles internal constructor(
    private val filesRoot: () -> File,
    private val wayStore: WayStore,
) {
    @Inject
    constructor(@ApplicationContext context: Context, wayStore: WayStore) : this({ context.filesDir }, wayStore)

    fun voiceFile(wayId: String, moment: WayMoment): File? =
        when (val media = (moment.kind as? WayMomentKind.Voice)?.media) {
            is WayMedia.Recording -> recordingFile(media.relativePath)
            is WayMedia.File -> wayStore.mediaFile(wayId, media.path)?.takeIf { it.isFile }
            is WayMedia.PhotoAsset, null -> null
        }

    /** A recording by the `fileRelativePath` its row keeps, as a reply's mapping does too. */
    fun recordingFile(relativePath: String): File? {
        val root = filesRoot().canonicalFile
        val file = File(root, relativePath).canonicalFile
        return file.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
    }
}
