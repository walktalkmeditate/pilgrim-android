// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim.builder

/**
 * Failure modes for `.pilgrim` package build / import. Mirrors iOS
 * `PilgrimPackageError` — same variants, same semantics. Inheriting
 * from `Exception` lets callers `try { ... } catch (e: PilgrimPackageError)`.
 */
sealed class PilgrimPackageError : Exception() {
    object NoWalksFound : PilgrimPackageError() {
        private fun readResolve(): Any = NoWalksFound
    }
    data class EncodingFailed(override val cause: Throwable?) : PilgrimPackageError()
    data class ZipFailed(override val cause: Throwable) : PilgrimPackageError()
    data class FileSystemError(override val cause: Throwable) : PilgrimPackageError()
    object InvalidPackage : PilgrimPackageError() {
        private fun readResolve(): Any = InvalidPackage
    }
    data class DecodingFailed(override val cause: Throwable) : PilgrimPackageError()
    data class UnsupportedSchemaVersion(val version: String) : PilgrimPackageError() {
        override val message: String get() = "Unsupported schema version: $version"
    }
}
