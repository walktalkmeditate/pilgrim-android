// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Which media family a repair slot belongs to. Wire path segments (`"photos"`/`"audio"`) live in [ShareService], not here — this enum is Android-internal bookkeeping only. */
enum class SlotKind { PHOTO, AUDIO }

/** Whether a slot's bytes have been confirmed landed on the worker. */
enum class SlotStatus { PENDING, UPLOADED }

/**
 * Stable per-slot identity, verified before a repair PUT can land bytes
 * under a previously-recorded `(kind, n)` again. Port of iOS
 * `FailedMediaItem`'s identity fields
 * (`Pilgrim/Models/Share/ShareService.swift:294-300@3f9f9e8`): "`n` alone
 * isn't safe to retry against later: the local candidate list an index
 * was drawn from can shift... so the caller resolving this cache must
 * verify identity... before uploading anything under `n` again."
 *
 * [Audio.recordingUuid] stands in for iOS's `startTs`
 * (`ShareService.swift:296@3f9f9e8`, matched via
 * `recording.startTs == item.audioStartTs`,
 * `WalkShareViewModel+ShareOrchestration.swift:340@3f9f9e8`). iOS falls
 * back to a truncated-second timestamp because its
 * `TourRecordingCandidate` carries no stable identifier at that layer —
 * its own comment concedes the match is safe only because "startTs
 * collisions are structurally impossible" (a probabilistic argument, not
 * a guarantee). Android's [org.walktalkmeditate.pilgrim.data.entity.VoiceRecording.uuid]
 * is a genuine Room-backed unique key already threaded through
 * [SharePrepStore.artifactFile] (`<walkUuid>/<recordingUuid>.m4a`) — a
 * strictly stronger identity than a timestamp, so this is a deliberate
 * upgrade over the pin, not a gap.
 *
 * [Photo.sourceUri] + [Photo.ts] mirror iOS's compound key exactly
 * (`sourceLocalIdentifier == item.photoLocalID && meta.ts == item.photoTs`,
 * `WalkShareViewModel+ShareOrchestration.swift:348-350@3f9f9e8`) — BOTH
 * fields must match, not just one (the parity spec's edge-cases EDG-118
 * flags this as deliberate: "the compound key is deliberate, not an
 * oversight, and dropping either half of it changes the
 * false-positive-match rate for photo retries"). [Photo.sourceUri] is
 * exactly [TourPhoto.sourceUri], already documented at that call site as
 * the Android parity mapping for `sourceLocalIdentifier`
 * (`TourPhotoExporter.kt` `TourPhoto` doc, U5); [Photo.ts] is epoch
 * seconds, matching [TourPhoto.meta]'s `SharePayload.Photo.ts`.
 */
sealed interface SlotIdentity {
    data class Photo(val sourceUri: String, val ts: Long) : SlotIdentity
    data class Audio(val recordingUuid: String) : SlotIdentity
}

/**
 * One worker upload slot: `(kind, n)` is the PUT path's coordinates
 * (`/api/share/{shareId}/{photos|audio}/{n}`, 1-based); [identity] is the
 * stable source-file identity [ShareRepairStore] verifies before ever
 * landing bytes under this `(kind, n)` again; [status] is
 * [SlotStatus.PENDING] until a 2xx PUT response confirms otherwise.
 */
data class RepairSlot(
    val kind: SlotKind,
    val n: Int,
    val identity: SlotIdentity,
    val status: SlotStatus,
)

/**
 * A share's whole repair record: which slots [shareId] still expects,
 * and which have already landed. [ShareRepairStore] keys one of these
 * per walk (mirrors [CachedShareStore]) — [shareId] is recorded IN the
 * value (not just the DataStore key) so a reader can detect a stale
 * record from an earlier share attempt without a second lookup (see
 * [ShareRepairStore.prePopulate]'s replace-vs-merge rule).
 */
data class RepairRecord(
    val shareId: String,
    val slots: List<RepairSlot>,
)

/**
 * Phase 19 U6: DataStore-backed per-walk repair record — sibling of
 * [CachedShareStore], same encode/decode/key conventions. Schema and
 * lifecycle port iOS's failed-media cache
 * (`Pilgrim/Models/Share/ShareService.swift:285-320@3f9f9e8`,
 * `FailedMediaItem` + `cacheFailedMedia`/`failedMedia`), adapted for
 * Android's synchronous-suspend-loop upload path
 * ([ShareService.uploadMedia] runs everything in one coroutine, so
 * there's no unstructured-Task race between an incremental per-slot
 * write and a final authoritative one — iOS's three-write lifecycle
 * (pre-populate, per-item prune, final overwrite —
 * `WalkShareViewModel+ShareOrchestration.swift:125-155@3f9f9e8`)
 * collapses to two here: [prePopulate] then [markUploaded], with no
 * final reconciling write needed because nothing races it).
 *
 * Kill-safe by construction: [prePopulate] durably marks every slot this
 * attempt owns as [SlotStatus.PENDING] BEFORE [ShareService.uploadMedia]
 * attempts a single PUT; [markUploaded] flips one slot the instant its
 * PUT is confirmed. A process death at any point between those calls
 * leaves [load] returning an accurate picture — nothing is ever marked
 * [SlotStatus.UPLOADED] before it truly is, which is also how a
 * coroutine cancellation mid-batch is handled: the untried tail is
 * already sitting at [SlotStatus.PENDING] from [prePopulate], so there is
 * no separate "mark the tail" step to race against cancellation — see
 * [ShareService.uploadMedia]'s class doc.
 */
@Singleton
class ShareRepairStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    // Production (Hilt) path uses the process-wide named DataStore. The
    // primary constructor takes an injectable DataStore so tests pass a
    // per-test file instead of contending on the shared singleton
    // (mirrors CachedShareStore).
    @Inject
    constructor(
        @ApplicationContext context: Context,
        json: Json,
    ) : this(context.shareRepairDataStore, json)

    /** Current record for [walkUuid], or null if none exists (never started, or already cleared). */
    suspend fun load(walkUuid: String): RepairRecord? =
        dataStore.data.map { prefs -> prefs[keyFor(walkUuid)]?.let(::decode) }.first()

    /**
     * Every walk that currently holds a repair record — the raw input
     * to [sweepStale], which is what the daily
     * [org.walktalkmeditate.pilgrim.audio.OrphanSweeperWorker] pass
     * actually hands [SharePrepStore.sweepOrphans]: a walk with
     * un-landed slots is exactly the walk whose transcode artifacts a
     * repair pass may still need, PROVIDED the walk still exists. Key-scan
     * shape mirrors [CachedShareStore.observeAll]'s (same dash-stripped
     * key format, same reconstruction), as a one-shot rather than a Flow
     * because its only consumers are that once-a-day pass and the tests.
     */
    suspend fun walkUuidsWithRecords(): Set<String> =
        dataStore.data.map { prefs ->
            prefs.asMap().keys
                .asSequence()
                .map { it.name }
                .filter { it.startsWith(KEY_PREFIX) }
                .mapNotNull { reconstructUuid(it.removePrefix(KEY_PREFIX)) }
                .toSet()
        }.first()

    /**
     * Drops every record whose walk no longer exists and returns the
     * survivors — the keep set
     * [org.walktalkmeditate.pilgrim.audio.OrphanSweeperWorker] hands
     * [SharePrepStore.sweepOrphans].
     *
     * A record IS that keep set, so a record for a deleted walk pins
     * the walk's transcoded voice artifacts in cache against every
     * future sweep — for the lifetime of the install, since the only
     * other paths that clear a record run inside that walk's own share
     * screen, which a deleted walk no longer has. Deleting a walk is
     * the walker asking for its recordings to be gone, so the derived
     * copies have to go with them.
     *
     * A record whose walk is gone is also unrepairable by construction:
     * a repair pass resolves its slots against the walk's CURRENT
     * recordings and photos, so clearing it loses nothing that could
     * ever have been used.
     */
    suspend fun sweepStale(liveWalkUuids: Set<String>): Set<String> {
        val recorded = walkUuidsWithRecords()
        val stale = recorded - liveWalkUuids
        for (walkUuid in stale) clear(walkUuid)
        return recorded - stale
    }

    /**
     * Establishes [slots] (all forced to [SlotStatus.PENDING] unless
     * already-recorded-and-matching, see below) as the record
     * [ShareService.uploadMedia] is about to work through, and returns
     * the record actually in effect afterward — the caller reads
     * per-slot identity/status back FROM this return value, never
     * re-derives it, before attempting each PUT.
     *
     * - No existing record for [walkUuid], or its [RepairRecord.shareId]
     *   differs from [shareId]: REPLACE — the whole record becomes
     *   exactly [slots], all pending. Mirrors iOS's `expectedFailureRecords`
     *   pre-populate (`WalkShareViewModel+ShareOrchestration.swift:130-131@3f9f9e8`,
     *   "Pre-populate so a kill mid-upload restores a repairable .partial
     *   instead of a lying .success") and is also this store's
     *   stale-record-clearing mechanism: a new share attempt (new
     *   [shareId]) always wins over whatever an older, interrupted
     *   attempt left behind — there is never a window where a reader
     *   could see a record for a shareId that isn't this one.
     * - An existing record for the SAME [shareId]: UPSERT per `(kind, n)`
     *   — a slot [slots] doesn't mention is left untouched (a repair pass
     *   over a subset of slots must not lose the rest of the record); a
     *   slot with no prior entry is added as pending; a slot whose prior
     *   entry has the SAME [SlotIdentity] is left as-is (an
     *   already-[SlotStatus.UPLOADED] slot is not regressed back to
     *   pending just because a caller mentioned it again — a deliberate
     *   improvement over iOS's unconditional blast-to-pending on every
     *   attempt, safe here because there is no unstructured-Task race to
     *   guard against, see class doc); a slot whose prior entry has a
     *   DIFFERENT [SlotIdentity] is ALSO left as-is, silently refusing
     *   the proposed identity. This is the wrong-slot-impossible
     *   guarantee: [ShareService.uploadMedia] cross-checks its input
     *   against the RETURNED record and never PUTs a slot whose identity
     *   didn't survive this merge unchanged.
     */
    suspend fun prePopulate(walkUuid: String, shareId: String, slots: List<RepairSlot>): RepairRecord {
        var result: RepairRecord? = null
        dataStore.edit { prefs ->
            val current = prefs[keyFor(walkUuid)]?.let(::decode)
            val merged = mergeOrReplace(current, shareId, slots)
            result = merged
            prefs[keyFor(walkUuid)] = encode(merged)
        }
        return requireNotNull(result) { "prePopulate must always produce a record" }
    }

    /**
     * Flips one slot to [SlotStatus.UPLOADED] the instant its PUT is
     * confirmed — the kill-safe half of the two-write lifecycle (see
     * [prePopulate]). A no-op if [walkUuid] has no record, or no slot
     * matches `(kind, n)` — defensive; should not happen given
     * [ShareService.uploadMedia] only ever calls this for a slot it just
     * read out of its own [prePopulate] result.
     */
    suspend fun markUploaded(walkUuid: String, kind: SlotKind, n: Int) {
        dataStore.edit { prefs ->
            val current = prefs[keyFor(walkUuid)]?.let(::decode) ?: return@edit
            val updated = current.slots.map {
                if (it.kind == kind && it.n == n) it.copy(status = SlotStatus.UPLOADED) else it
            }
            prefs[keyFor(walkUuid)] = encode(current.copy(slots = updated))
        }
    }

    /**
     * Removes the whole record for [walkUuid] — called by
     * [ShareService.uploadMedia] once every slot lands, mirroring iOS's
     * "writing an empty array removes the key"
     * (`ShareService.swift:306-309@3f9f9e8`). Also available for a later
     * unit (U8) to wipe a stale record explicitly (e.g. a walk re-shared
     * non-interactively after a prior `.partial` interactive attempt —
     * iOS's `WalkShareViewModel+ShareOrchestration.swift:156-163@3f9f9e8`,
     * "A fresh share must never inherit a previous share's failed-media
     * record").
     */
    suspend fun clear(walkUuid: String) {
        dataStore.edit { prefs -> prefs.remove(keyFor(walkUuid)) }
    }

    private fun mergeOrReplace(current: RepairRecord?, shareId: String, proposed: List<RepairSlot>): RepairRecord {
        if (current == null || current.shareId != shareId) {
            return RepairRecord(shareId, proposed.map { it.copy(status = SlotStatus.PENDING) })
        }
        val bySlot = LinkedHashMap<Pair<SlotKind, Int>, RepairSlot>()
        for (slot in current.slots) bySlot[slot.kind to slot.n] = slot
        // A duplicate `(kind, n)` WITHIN one [proposed] list is a caller
        // bug, not a case to resolve: first wins, deterministically.
        for (slot in proposed) {
            val key = slot.kind to slot.n
            if (bySlot[key] == null) {
                bySlot[key] = slot.copy(status = SlotStatus.PENDING)
            }
            // A pre-existing entry (identity match OR mismatch) always
            // wins over the proposed one — see the KDoc above. Nothing to
            // do in either case: matched status/identity is already
            // correct, and a mismatched identity must not be written.
        }
        return RepairRecord(shareId, bySlot.values.toList())
    }

    private fun encode(record: RepairRecord): String =
        json.encodeToString(RepairRecordPrefs.serializer(), record.toPrefs())

    private fun decode(blob: String): RepairRecord? = try {
        json.decodeFromString(RepairRecordPrefs.serializer(), blob).toDomain()
    } catch (ce: CancellationException) {
        // Flow cancellation during collect would propagate through the
        // map operator's invocation of decode; kotlin stdlib's
        // runCatching{} swallows CE (Stage 5-C lesson), so this is a
        // plain try/catch with explicit CE re-throw (CachedShareStore
        // precedent).
        throw ce
    } catch (_: Throwable) {
        null
    }

    private fun keyFor(walkUuid: String) =
        stringPreferencesKey(KEY_PREFIX + walkUuid.replace("-", ""))

    /** Inverse of [keyFor]'s dash-stripping; null for anything that isn't a 32-hex-char UUID body. */
    private fun reconstructUuid(stripped: String): String? {
        if (stripped.length != UUID_HEX_LENGTH) return null
        return buildString {
            append(stripped, 0, 8).append('-')
            append(stripped, 8, 12).append('-')
            append(stripped, 12, 16).append('-')
            append(stripped, 16, 20).append('-')
            append(stripped, 20, 32)
        }
    }

    /**
     * JSON-persisted shape. A flat, all-nullable identity payload rather
     * than a polymorphic/sealed encoding — mirrors iOS's OWN choice for
     * `FailedMediaItem` (`audioStartTs: Int?`, `photoLocalID: String?`,
     * `photoTs: Int?` all on one Codable struct, selected by `kind`)
     * rather than reaching for kotlinx.serialization's polymorphic
     * support, which would need a `SerializersModule` for no behavioral
     * gain here.
     */
    @Serializable
    private data class RepairRecordPrefs(
        @SerialName("share_id") val shareId: String,
        val slots: List<RepairSlotPrefs>,
    ) {
        fun toDomain() = RepairRecord(shareId, slots.map { it.toDomain() })
    }

    @Serializable
    private data class RepairSlotPrefs(
        val kind: String,
        val n: Int,
        @SerialName("audio_recording_uuid") val audioRecordingUuid: String? = null,
        @SerialName("photo_source_uri") val photoSourceUri: String? = null,
        @SerialName("photo_ts") val photoTs: Long? = null,
        val uploaded: Boolean,
    ) {
        fun toDomain(): RepairSlot {
            val kindEnum = SlotKind.valueOf(kind)
            val identity: SlotIdentity = when (kindEnum) {
                SlotKind.AUDIO -> SlotIdentity.Audio(requireNotNull(audioRecordingUuid))
                SlotKind.PHOTO -> SlotIdentity.Photo(requireNotNull(photoSourceUri), requireNotNull(photoTs))
            }
            return RepairSlot(kindEnum, n, identity, if (uploaded) SlotStatus.UPLOADED else SlotStatus.PENDING)
        }
    }

    private companion object {
        const val KEY_PREFIX = "share_repair_"
        const val UUID_HEX_LENGTH = 32
    }

    private fun RepairRecord.toPrefs() = RepairRecordPrefs(shareId = shareId, slots = slots.map { it.toPrefs() })

    private fun RepairSlot.toPrefs(): RepairSlotPrefs = when (val id = identity) {
        is SlotIdentity.Audio -> RepairSlotPrefs(
            kind = kind.name,
            n = n,
            audioRecordingUuid = id.recordingUuid,
            uploaded = status == SlotStatus.UPLOADED,
        )
        is SlotIdentity.Photo -> RepairSlotPrefs(
            kind = kind.name,
            n = n,
            photoSourceUri = id.sourceUri,
            photoTs = id.ts,
            uploaded = status == SlotStatus.UPLOADED,
        )
    }
}

private val Context.shareRepairDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "share_repair",
)
