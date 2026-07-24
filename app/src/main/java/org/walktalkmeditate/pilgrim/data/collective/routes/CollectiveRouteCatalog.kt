// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective.routes

import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.walktalkmeditate.pilgrim.data.units.UnitSystem

/**
 * The decoded collective-route artifact, plus the daily selection and the
 * phrasing both surfaces read from. Ports iOS
 * `CollectiveRouteCatalog.swift@9a418e4`; parity spec
 * `docs/parity/2026-07-23-port-route-catalog-u2.md`.
 */
class CollectiveRouteCatalog {

    /**
     * Content-derived, so it carries no ordering — callers compare it for
     * inequality rather than `>` so a rollback to an earlier artifact also
     * reaches devices.
     */
    val version: String

    /**
     * The selection pool in canonical order: routes by identifier ascending,
     * then horizons as the artifact lists them — re-derived, never trusted
     * from the wire.
     */
    val entries: List<CollectiveRoute>

    constructor(version: String, entries: List<CollectiveRoute>) {
        this.version = version
        this.entries = canonicallyOrdered(entries)
    }

    /**
     * The decode path, which keeps the artifact's two arrays apart. Which
     * array an entry arrived in is the contract, not its decoded kind: the web
     * sorts `pilgrimages` and appends `horizons` untouched, so a mis-filed
     * cosmic entry among the pilgrimages would sort here and not there,
     * desyncing every date.
     */
    private constructor(
        version: String,
        pilgrimages: List<CollectiveRoute>,
        horizons: List<CollectiveRoute>,
    ) {
        this.version = version
        this.entries = sortedById(pilgrimages) + horizons
    }

    /**
     * The single entry every pilgrim on earth sees for this UTC day, weighted
     * by season. Without the scramble, consecutive dates walk runs of the same
     * entry.
     */
    fun entry(epochMillis: Long): CollectiveRoute? {
        val day = CollectiveRouteSeed.utcDay(epochMillis)

        var totalWeight = 0
        for (entry in entries) {
            totalWeight += entry.weight(day.month)
        }
        if (totalWeight <= 0) return null

        val scrambled = CollectiveRouteSeed.hash(day.seed)
        var remaining = (scrambled % totalWeight.toUInt()).toInt()
        for (entry in entries) {
            remaining -= entry.weight(day.month)
            if (remaining < 0) return entry
        }
        return entries.last()
    }

    fun dailyLine(epochMillis: Long, collectiveKm: Double?, units: UnitSystem): String? =
        entry(epochMillis)?.dailyLine(collectiveKm, units)

    /**
     * Anchored to the walk's own date, so reopening an old walk shows what it
     * showed the day it ended.
     */
    fun contributionLine(epochMillis: Long, walkKm: Double, units: UnitSystem): String? =
        entry(epochMillis)?.contributionLine(walkKm, units)

    override fun equals(other: Any?): Boolean =
        other is CollectiveRouteCatalog && other.version == version && other.entries == entries

    override fun hashCode(): Int = 31 * version.hashCode() + entries.hashCode()

    @Serializable
    private data class ArtifactEnvelope(
        val version: String,
        val pilgrimages: List<JsonElement> = emptyList(),
        val horizons: List<JsonElement> = emptyList(),
    )

    @Serializable
    private data class RouteEntryDto(
        val id: String,
        val kind: String,
        val km: Double,
        val companyLine: String,
        val nameEn: String? = null,
        val preposition: String? = null,
        val body: String? = null,
        val bestMonths: List<Int> = emptyList(),
        val peakMonths: List<Int> = emptyList(),
    )

    companion object {
        val EMPTY = CollectiveRouteCatalog("", emptyList())

        /**
         * Owned here, not injected: Codable ignores unknown keys by nature,
         * kotlinx's default `Json` does not — and a strict decoder would
         * lossily drop every shipped entry (they all carry `reflections` /
         * `annual`), leaving an empty catalog wearing a successful decode.
         * iOS's parallel is the service holding "a stock JSONDecoder with no
         * configuration" (parity spec D6). U3's service must call [decode],
         * not roll its own.
         */
        private val artifactJson = Json { ignoreUnknownKeys = true }

        /** Kind stands in for provenance where a caller holds one flat list and the arrays are gone. */
        fun canonicallyOrdered(entries: List<CollectiveRoute>): List<CollectiveRoute> =
            sortedById(entries.filterNot { it.isCosmic }) + entries.filter { it.isCosmic }

        /**
         * UTF-16 code units, because that is what JavaScript's `<` compares —
         * Kotlin's `String.compareTo` compares `Char`s, which are exactly
         * that. No `Collator`, no locale.
         */
        private fun sortedById(entries: List<CollectiveRoute>): List<CollectiveRoute> =
            entries.sortedBy { it.id }

        /**
         * Throws when the envelope itself is unreadable (missing `version`,
         * malformed JSON); individual entries decode lossily per [decodeEntry].
         */
        fun decode(text: String): CollectiveRouteCatalog {
            val envelope = artifactJson.decodeFromString<ArtifactEnvelope>(text)
            return CollectiveRouteCatalog(
                version = envelope.version,
                pilgrimages = envelope.pilgrimages.mapNotNull(::decodeEntry),
                horizons = envelope.horizons.mapNotNull(::decodeEntry),
            )
        }

        /**
         * A dropped entry is damage limitation, not graceful degradation:
         * every entry feeds the day's total weight and the seed is taken
         * modulo it, so losing one silently re-resolves *every* date. A new
         * `kind` is therefore the worst case, not the safe one, and has to
         * reach the app before it reaches the artifact.
         *
         * Drops (returns null for): an element that fails DTO decode (missing
         * `id`/`kind`/`km`/`companyLine`), a zero or non-finite length (which
         * would divide by zero in the phrasing — rejected here so no call site
         * guards), an unrecognised kind, a route missing `nameEn`, a horizon
         * missing `preposition`/`body`.
         */
        private fun decodeEntry(element: JsonElement): CollectiveRoute? {
            val dto = runCatching { artifactJson.decodeFromJsonElement<RouteEntryDto>(element) }
                .getOrNull() ?: return null
            if (!dto.km.isFinite() || dto.km <= 0.0) return null

            val kind = when (dto.kind) {
                "route" -> dto.nameEn?.let { CollectiveRoute.Kind.Route(it) }
                "cosmic" -> if (dto.preposition != null && dto.body != null) {
                    CollectiveRoute.Kind.Cosmic(dto.preposition, dto.body)
                } else {
                    null
                }
                else -> null
            } ?: return null

            return CollectiveRoute(
                id = dto.id,
                kind = kind,
                km = dto.km,
                companyLine = dto.companyLine,
                bestMonths = dto.bestMonths,
                peakMonths = dto.peakMonths,
            )
        }
    }
}

/**
 * The deterministic generator behind the daily rotation, ported from the web's
 * `utcSeed` and `hashSeed` (`pilgrim-landing/js/collective-routes.js`).
 * A UTC day must resolve to the same entry forever on every platform, so
 * nothing here may ever change.
 */
object CollectiveRouteSeed {

    data class UtcDay(val seed: UInt, val month: Int)

    /**
     * Seed and month from one calendar lookup — selection needs both. The UTC
     * date packs as YYYYMMDD; `Int.toUInt()` reinterprets bits, mirroring
     * JavaScript's `>>> 0` and Swift's `UInt32(truncatingIfNeeded:)`.
     */
    fun utcDay(epochMillis: Long): UtcDay {
        val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val packed = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
        return UtcDay(seed = packed.toUInt(), month = date.monthValue)
    }

    /**
     * The fmix32 finalizer the web scrambles its date seed with. Kotlin `UInt`
     * multiplication wraps, matching JavaScript's `Math.imul` keeping the low
     * 32 bits (and Swift's `&*`); `shr` on `UInt` is logical, matching `>>>`.
     */
    fun hash(seed: UInt): UInt {
        val multiplier = 0x45d9f3bu
        var hashed = seed
        hashed = (hashed xor (hashed shr 16)) * multiplier
        hashed = (hashed xor (hashed shr 16)) * multiplier
        return hashed xor (hashed shr 16)
    }
}
