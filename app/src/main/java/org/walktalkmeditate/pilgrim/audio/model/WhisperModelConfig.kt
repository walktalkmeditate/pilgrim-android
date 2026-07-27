// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.model

import java.nio.file.Path

/**
 * Pinned identity of the shipped whisper model (iOS parity:
 * `TranscriptionService.modelVariant = "base"` @ 9a418e4 — the
 * multilingual base model) plus the storage layout every reader,
 * writer, and deleter must route through. Probes, the U9 download
 * worker, and U10's tiny cleanup all derive paths from these functions
 * so a layout change can never decouple writes from reads (Stage 5-D
 * lesson).
 *
 * [EXPECTED_BYTES] and [EXPECTED_SHA256] are the published upstream
 * values for `ggml-base.bin`, read from the `ggerganov/whisper.cpp`
 * Hugging Face repo's Git-LFS pointer (`oid sha256:…`, `size …`).
 * U17's release step re-verifies both against the object actually
 * published to the CDN before tagging.
 */
object WhisperModelConfig {

    const val VARIANT = "base"
    const val FILE_NAME = "ggml-base.bin"
    const val EXPECTED_BYTES = 147_951_465L
    const val EXPECTED_SHA256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"

    /** Single full literal — never assembled from base + path segments. */
    const val CDN_URL = "https://cdn.pilgrimapp.org/models/ggml-base.bin"

    const val LEGACY_TINY_FILE_NAME = "ggml-tiny.en.bin"

    /**
     * Exact byte size of the v1.2.0 bundled tiny asset. The installer
     * compared against the asset at runtime; once U10 removes the asset
     * from the APK, this constant is the only remaining ground truth
     * for the transitional exact-size probe.
     */
    const val LEGACY_TINY_EXPECTED_BYTES = 77_704_715L

    private const val MODEL_ROOT_DIR = "whisper-model"
    private const val SHA_MARKER_SUFFIX = ".sha256"

    fun baseModelPath(filesDir: Path): Path =
        filesDir.resolve(MODEL_ROOT_DIR).resolve(VARIANT).resolve(FILE_NAME)

    /**
     * Written by the download worker only after the verified model's
     * atomic rename, holding [EXPECTED_SHA256]. A marker without its
     * model (partial restore, D2D transfer) must probe Absent.
     */
    fun baseShaMarkerPath(filesDir: Path): Path =
        filesDir.resolve(MODEL_ROOT_DIR).resolve(VARIANT).resolve(FILE_NAME + SHA_MARKER_SUFFIX)

    /**
     * The flat pre-base path `WhisperModelInstaller` populated on
     * v1.2.0 installs — deliberately NOT variant-keyed, so existing
     * installs are found without migration.
     */
    fun legacyTinyPath(filesDir: Path): Path =
        filesDir.resolve(MODEL_ROOT_DIR).resolve(LEGACY_TINY_FILE_NAME)
}
