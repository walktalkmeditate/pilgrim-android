// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.data.pilgrim.builder.PilgrimPackageBuildResult
import org.walktalkmeditate.pilgrim.data.pilgrim.builder.PilgrimPackageBuilder
import org.walktalkmeditate.pilgrim.data.pilgrim.builder.PilgrimPackageImporter

/**
 * Test seam over [PilgrimPackageBuilder.build] +
 * [PilgrimPackageImporter.import] so [DataSettingsViewModel] unit
 * tests can stub the heavy archive pipeline without spinning up
 * Room + the full converter graph.
 *
 * Production binds this to [DefaultPilgrimPackageGateway].
 */
interface PilgrimPackageGateway {
    suspend fun build(includePhotos: Boolean): PilgrimPackageBuildResult
    suspend fun import(uri: Uri): Int
}

@Singleton
class DefaultPilgrimPackageGateway @Inject constructor(
    private val builder: PilgrimPackageBuilder,
    private val importer: PilgrimPackageImporter,
) : PilgrimPackageGateway {
    override suspend fun build(includePhotos: Boolean): PilgrimPackageBuildResult =
        builder.build(includePhotos = includePhotos)

    override suspend fun import(uri: Uri): Int =
        importer.import(uri)
}
