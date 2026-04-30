// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.data.export.RecordingsExporter

/**
 * Test seam over the static `RecordingsExporter.export(...)` call.
 * Lets [DataSettingsViewModelTest] substitute a controllable fake
 * for the in-flight guard test (which can't reliably exercise the
 * compareAndSet path through the real Dispatchers.IO + ZIP build
 * under runTest virtual time + CI runner contention).
 *
 * Production binding: [DefaultRecordingsExporterGateway] forwards
 * directly to the static call.
 */
interface RecordingsExporterGateway {
    suspend fun export(sourceDir: File, targetDir: File, now: Instant): File?
}

@Singleton
class DefaultRecordingsExporterGateway @Inject constructor() : RecordingsExporterGateway {
    override suspend fun export(sourceDir: File, targetDir: File, now: Instant): File? =
        RecordingsExporter.export(sourceDir, targetDir, now)
}
