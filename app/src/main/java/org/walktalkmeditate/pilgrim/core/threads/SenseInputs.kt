// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import java.time.Instant

/**
 * Platform-agnostic lat/lng pair — deliberately not coupled to
 * `android.location.Location` (a value type, not a platform object), so
 * [DossierSenses] stays free of any Android runtime dependency beyond
 * the pure geodesic-math call in [DossierSenses.distanceMeters].
 */
data class Coordinate(val latitude: Double, val longitude: Double)

/**
 * A resolved route fix near some reference instant. [gapSeconds] is
 * frozen at resolution time (the absolute distance from the reference
 * instant the fix was resolved against) — never recomputed later against
 * a different instant.
 */
data class RouteFix(val coordinate: Coordinate, val horizontalAccuracy: Double, val gapSeconds: Double)

/** One point of a walk's altitude series — sourced from barometric
 * [org.walktalkmeditate.pilgrim.data.entity.AltitudeSample] rows (the
 * same source [org.walktalkmeditate.pilgrim.data.walk.AltitudeCalculator]
 * already uses for ascent/descent elsewhere in the app), not GPS-derived
 * altitude. */
data class ElevationSample(val timestamp: Instant, val altitude: Double)

/** A pinned photo. [coordinate] is `null` when the photo has no GPS EXIF
 * — Android's schema already models "no location" as nullable
 * `capturedLat`/`capturedLng` columns, so unlike iOS there is no (-1,-1)
 * sentinel to translate at the boundary. */
data class PhotoPin(val capturedAt: Instant, val coordinate: Coordinate?)

/**
 * One recording on the CURRENT walk. [end] is always present — Android's
 * `VoiceRecording.endTimestamp` is non-nullable by schema invariant
 * (a recording is only persisted once finalized), so unlike iOS there is
 * no `endTimestamp ?? start` fallback to replicate.
 */
data class CurrentRecording(
    val uuid: String,
    val start: Instant,
    val end: Instant,
    val text: String,
    val wordCount: Int,
    val themes: List<Theme>,
)

/** One walk row in the senses' fetch window — `intention`/`weatherCondition`
 * are the raw stored values, not derived/typed forms. */
data class WalkSnapshotRow(
    val walkId: Long,
    val startDate: Instant,
    val intention: String?,
    val weatherCondition: String?,
)

/**
 * The moon line's entire world. [lunationIndex]/[moonName]/[start]/[end]
 * describe the most-recently-CLOSED lunation (never the open one);
 * [lastReportedIndex] is `null` when the moon line has never fired;
 * [allWalkDates]/[wordedWalkDates] are pre-filtered to the fetch bundle's
 * UNION range (30-day window ∪ closed lunation), not all-time.
 */
data class MoonInput(
    val lunationIndex: Int,
    val moonName: String,
    val start: Instant,
    val end: Instant,
    val lastReportedIndex: Int?,
    val currentWalkHasWords: Boolean,
    val allWalkDates: List<Instant>,
    val wordedWalkDates: List<Instant>,
)

/**
 * The pure sense engine's entire world (13 fields) — everything a sense
 * function may read, and nothing else. No sense function may reach for a
 * clock, a DAO, or a singleton; every fact arrives here as data (parity
 * spec purity contract, `Pilgrim/Models/Threads/DossierSenses.swift:4-8@0172e2b`).
 * [moon] is the only optional field — every other collection may be
 * empty, but is never itself absent.
 */
data class SenseInput(
    val currentWalkId: Long,
    val walkStart: Instant,
    val walkEnd: Instant,
    val totalAscent: Double,
    val elevationSeries: List<ElevationSample>,
    val photos: List<PhotoPin>,
    val currentRecordings: List<CurrentRecording>,
    val threads: List<ActiveThread>,
    val backfillComplete: Boolean,
    val walkSnapshots: List<WalkSnapshotRow>,
    val recordingTimestamps: Map<String, Instant>,
    val fixes: Map<String, RouteFix>,
    val moon: MoonInput?,
)

/** One sense's rendered line. [lemma] `null` means dedup-immune — it
 * never registers in the dispatcher's `used` set and can never be
 * suppressed by (or suppress) another sense. */
data class SenseLine(val text: String, val lemma: String?)

/**
 * [DossierSenses.lines]'s result. [reportedLunationIndex] is the SOLE
 * trigger for persisting `ThreadsPreferencesRepository`'s moon-line key —
 * set only when the moon line's own line survived BOTH the cap break and
 * the lemma-dedup check in the same dispatch pass, never merely because
 * the moon-line sense function itself returned non-null.
 */
data class SenseOutput(val lines: List<String>, val reportedLunationIndex: Int?)
