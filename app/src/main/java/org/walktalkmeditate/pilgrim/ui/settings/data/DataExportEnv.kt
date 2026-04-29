// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import java.io.File

/**
 * Filesystem env handed to [DataSettingsViewModel]. Production
 * resolves the providers against the canonical recordings dir +
 * `cacheDir/recordings_export/`; tests substitute tmpdirs.
 */
data class DataExportEnv(
    val sourceDir: () -> File,
    val targetDir: () -> File,
)
