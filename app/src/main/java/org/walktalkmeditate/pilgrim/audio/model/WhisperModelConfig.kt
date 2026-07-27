// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.model

import java.io.IOException
import java.nio.file.Files
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
     * Exact byte size of the v1.2.0 bundled tiny asset. The v1.2.0
     * installer compared against the asset at runtime; with U10 the
     * asset and installer are gone, so this constant is the only
     * remaining ground truth for the transitional exact-size probe.
     */
    const val LEGACY_TINY_EXPECTED_BYTES = 77_704_715L

    private const val MODEL_ROOT_DIR = "whisper-model"
    private const val SHA_MARKER_SUFFIX = ".sha256"
    private const val PARTIAL_SUFFIX = ".part"
    private const val ETAG_SUFFIX = ".etag"

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
     * The U9 worker's resumable partial. Survives cancellation and
     * transient failures by design (U9 spec C3); renamed atomically
     * onto [baseModelPath] once the streamed SHA-256 verifies.
     */
    fun basePartialPath(filesDir: Path): Path =
        filesDir.resolve(MODEL_ROOT_DIR).resolve(VARIANT).resolve(FILE_NAME + PARTIAL_SUFFIX)

    /**
     * ETag of the CDN object the partial belongs to, persisted beside
     * it so a resumed transfer can send `If-Range` and never splice two
     * object versions. A partial without its etag restarts from zero.
     */
    fun baseEtagPath(filesDir: Path): Path =
        filesDir.resolve(MODEL_ROOT_DIR).resolve(VARIANT).resolve(FILE_NAME + ETAG_SUFFIX)

    /**
     * The one verified-delivery probe every reader routes through:
     * model file at its exact expected size plus a sha marker holding
     * the expected digest (marker-last ordering, U8 L4). The store
     * passes the pinned constants; the U9 worker passes its injected
     * spec so tests observe the probe. IO errors read as "not present".
     */
    fun verifiedModelPresent(
        filesDir: Path,
        expectedBytes: Long,
        expectedSha256: String,
    ): Boolean = try {
        val model = baseModelPath(filesDir)
        val marker = baseShaMarkerPath(filesDir)
        Files.exists(model) &&
            Files.size(model) == expectedBytes &&
            Files.exists(marker) &&
            String(Files.readAllBytes(marker), Charsets.UTF_8).trim() == expectedSha256
    } catch (_: IOException) {
        false
    }

    /**
     * The flat pre-base path the v1.2.0 asset installer populated —
     * deliberately NOT variant-keyed, so existing installs are found
     * without migration. U10's [org.walktalkmeditate.pilgrim.audio.model.WhisperModelStore.onBaseVerified]
     * deletes through this same function.
     */
    fun legacyTinyPath(filesDir: Path): Path =
        filesDir.resolve(MODEL_ROOT_DIR).resolve(LEGACY_TINY_FILE_NAME)
}
