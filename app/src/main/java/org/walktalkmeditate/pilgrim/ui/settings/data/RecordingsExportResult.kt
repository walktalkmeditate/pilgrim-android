// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import java.io.File

/** Outcome of [DataSettingsViewModel.exportRecordings]. */
sealed interface RecordingsExportResult {
    data class Success(val file: File) : RecordingsExportResult
    object Empty : RecordingsExportResult
    data class Failed(val message: String) : RecordingsExportResult
}
