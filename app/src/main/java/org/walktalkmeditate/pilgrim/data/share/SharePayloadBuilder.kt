// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.forSouthernHemisphere
import org.walktalkmeditate.pilgrim.core.celestial.turningMarkerForEpochMillis
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Stage 8-A: inputs collected by `WalkShareViewModel` from the repo
 * (pulls match Stage 7-C `composeEtegamiSpec` pattern). Passed to
 * [SharePayloadBuilder.build] as a single aggregate so the builder
 * stays pure + independently testable.
 */
data class ShareInputs(
    val walk: Walk,
    val routePoints: List<LocationPoint>,
    val altitudeSamples: List<AltitudeSample>,
    val activityIntervals: List<ActivityInterval>,
    val voiceRecordings: List<VoiceRecording>,
    val waypoints: List<Waypoint>,
    val distanceMeters: Double,
    val activeDurationSeconds: Double,
    val meditateDurationSeconds: Double,
    val talkDurationSeconds: Double,
    val elevationAscentMeters: Double,
    val elevationDescentMeters: Double,
    val steps: Int?,
    /** Pinned reliquary photos for this walk (every `walk_photos` row IS a pin). */
    val pinnedPhotos: List<WalkPhoto> = emptyList(),
    /**
     * Phase 19: the walk's paused stretches, for the interactive-share
     * `pauses` field. Source of truth is
     * [org.walktalkmeditate.pilgrim.data.walk.WalkMetricsMath.pauseSpans]
     * — the same PAUSED/RESUMED automaton `core.prompt.PauseContext`
     * maps from. Declared locally (rather than importing that
     * `internal` type across packages) so this pure payload layer has
     * no dependency on the walk-metrics package; a caller maps
     * `WalkMetricsMath.PauseSpan` → [PauseSpan] at the same seam
     * `PromptsCoordinator` already uses for `PauseContext`.
     */
    val pauseSpans: List<PauseSpan> = emptyList(),
    /**
     * Phase 19: per-recording artifact info for [TourBuilder], keyed
     * by [VoiceRecording.uuid]. A recording with no entry reads as
     * unavailable ("audio removed") — see [RecordingArtifact]. Empty
     * until a later unit's transcode/artifact store supplies real
     * values.
     */
    val recordingArtifacts: Map<String, RecordingArtifact> = emptyMap(),
)

/** User-selected share options surfaced by the modal. */
data class WalkShareOptions(
    val expiry: ExpiryOption,
    val journal: String,
    val includeDistance: Boolean,
    val includeDuration: Boolean,
    val includeElevation: Boolean,
    val includeActivityBreakdown: Boolean,
    val includeSteps: Boolean,
    val includeWaypoints: Boolean,
    val includePhotos: Boolean = false,
    /** Phase 19: "Walk with Me" interactive share — attaches `tour` + `pauses`, gates route trim. */
    val interactive: Boolean = false,
    /** Phase 19: only takes effect when [interactive] is also true (iOS `WalkShareViewModel.swift:471`). */
    val trimEnabled: Boolean = false,
    /** Phase 19: recordings the walker excluded from the tour — a user choice, not an unavailability. */
    val excludedRecordingUuids: Set<String> = emptySet(),
    /**
     * Phase 19: per-recording spoken/ambient flips, keyed by
     * `VoiceRecording.uuid` — the other half of the walker's per-row
     * choices (iOS `flipKind`, `WalkShareViewModel.swift:236-241@3f9f9e8`).
     * See [TourBuilder.candidates]' `kindOverrides` parameter.
     */
    val kindOverrides: Map<String, TourRecordingKind> = emptyMap(),
    /**
     * Fold-in (iOS PR #61/#62, `TourBuilder.swift:96-110@2ee1185`): the
     * walker's selected soundscape, already resolved to its public CDN
     * URL by [TourBuilder.soundscapeUrl]. Resolution needs the
     * soundscape preference + manifest, neither of which this pure
     * builder depends on — the ViewModel resolves it and threads the
     * result through here like every other interactive-only value.
     * Rides inside [SharePayload.Tour]; a classic share never reads it.
     */
    val soundscapeUrl: String? = null,
)

/**
 * The shipped route, the trim actually applied, and the timestamp
 * window that trim kept. Port of iOS `computeInteractiveRoute()`'s
 * tuple (`WalkShareViewModel.swift:469-482@3f9f9e8`), including its
 * "report the trim by OUTCOME, not intent" rule — [trimM] is 0 and
 * [keptWindow] null whenever [RouteTrimmer] silently no-opped on a
 * route too short to trim.
 */
internal data class InteractiveRoute(
    val route: List<SharePayload.RoutePoint>,
    val trimM: Int,
    /** Epoch-SECOND bounds, INCLUSIVE at both ends — Kotlin's `LongRange` matches Swift's `ClosedRange`. */
    val keptWindow: LongRange?,
)

/**
 * The route work that depends on nothing a walker can change: the RDP
 * downsample, and the trim eligibility that follows from it (iOS
 * `canTrimRoute`, `WalkShareViewModel.swift:61-64@3f9f9e8`).
 *
 * Both are functions of [ShareInputs] alone, which is immutable once
 * loaded — so they are computed ONCE, off the main thread, and every
 * later read (trim eligibility for the disclosure, the photo-export
 * window, the totals label) answers from here. Recomputing them per
 * UI emission put up to 32 RDP passes on the Main-confined transform
 * of a `stateIn`, for every toggle, on every share.
 */
internal data class PreparedRoute(
    val downsampled: List<SharePayload.RoutePoint>,
    val canTrim: Boolean,
)

/** Computes [PreparedRoute]; callers run this off Main (it is the expensive half). */
internal fun prepareRoute(inputs: ShareInputs): PreparedRoute {
    val downsampled = downsampleRoute(inputs)
    return PreparedRoute(
        downsampled = downsampled,
        canTrim = RouteTrimmer.canTrim(downsampled, ShareConfig.INTERACTIVE_TRIM_METERS.toDouble()),
    )
}

private fun downsampleRoute(inputs: ShareInputs): List<SharePayload.RoutePoint> {
    val altitudeByTs = inputs.altitudeSamples.associateBy { it.timestamp }
    return RouteDownsampler.downsample(
        inputs.routePoints.map { p ->
            SharePayload.RoutePoint(
                lat = p.latitude,
                lon = p.longitude,
                alt = altitudeByTs[p.timestamp]?.altitudeMeters ?: 0.0,
                // Epoch-MILLIS → epoch-SECONDS per iOS wire parity.
                ts = p.timestamp / 1_000L,
            )
        },
    )
}

/**
 * Single source of truth for the trimmed route, shared by
 * [SharePayloadBuilder.build] and the ViewModel's photo-export window
 * (iOS `interactiveKeptWindow()`, `WalkShareViewModel.swift:245-247@3f9f9e8`)
 * — exactly iOS's reason for factoring it out: "`buildPayload` and
 * `interactiveKeptWindow()` must never compute this independently, or
 * the payload's route and the filter window could drift apart"
 * (`WalkShareViewModel.swift:467-468@3f9f9e8`).
 */
internal fun computeInteractiveRoute(inputs: ShareInputs, options: WalkShareOptions): InteractiveRoute =
    computeInteractiveRoute(downsampleRoute(inputs), options)

/**
 * The same computation over an ALREADY-downsampled route
 * ([PreparedRoute.downsampled]). Taking the downsampled points rather
 * than the inputs is what makes it impossible for a per-emission caller
 * to smuggle an RDP pass back into the UI path — there is no route to
 * downsample in this signature.
 */
internal fun computeInteractiveRoute(
    downsampled: List<SharePayload.RoutePoint>,
    options: WalkShareOptions,
): InteractiveRoute {
    if (!options.interactive || !options.trimEnabled) return InteractiveRoute(downsampled, 0, null)

    val trimmed = RouteTrimmer.trim(downsampled, ShareConfig.INTERACTIVE_TRIM_METERS.toDouble())
    val didTrim = trimmed.size < downsampled.size
    return InteractiveRoute(
        route = trimmed,
        trimM = if (didTrim) ShareConfig.INTERACTIVE_TRIM_METERS else 0,
        // "Trim's promise covers everything with a coordinate: waypoints
        // and photo metadata outside the kept route window are excluded
        // too — a doorstep photo must not pin the doorstep trim just
        // hid." (`WalkShareViewModel.swift:477@3f9f9e8`)
        keptWindow = if (didTrim && trimmed.size >= 2) trimmed.first().ts..trimmed.last().ts else null,
    )
}

/**
 * One paused stretch of the walk, in epoch millis. See [ShareInputs.pauseSpans].
 */
data class PauseSpan(val startMs: Long, val durationMillis: Long)

internal object SharePayloadBuilder {

    /**
     * Pure mapper. Thread-safe (no shared state). Callers run this
     * on `Dispatchers.Default` since CPU cost is list mapping + RDP +
     * JSON encoding downstream.
     */
    fun build(
        inputs: ShareInputs,
        options: WalkShareOptions,
        // Photo bytes are I/O — the VM pre-encodes pinned photos to
        // base64 JPEG off the main thread and passes them in so this
        // builder stays pure. iOS encodes inline in `buildPayload`
        // (synchronous PhotoKit); Android keeps build() side-effect-free.
        photos: List<SharePayload.Photo>? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): SharePayload {
        // Phase 19: trim runs on the downsampled route (matches iOS
        // `computeInteractiveRoute()` — downsample happens first, trim
        // second) so map/og/tour all inherit the same trimmed points;
        // there is no separate "trimmed for the tour" copy.
        val interactiveRoute = computeInteractiveRoute(inputs, options)
        val finalRoute = interactiveRoute.route
        val trimM = interactiveRoute.trimM

        // The walker's per-row choices resolved ONCE, then read by the
        // tour, the talk intervals, and the talk-duration clamp alike —
        // iOS's `tourCandidates` array plays the same single-source role
        // for those three consumers (`WalkShareViewModel.swift:343-365,443@3f9f9e8`).
        val candidates = if (options.interactive) {
            TourBuilder.candidates(
                recordings = inputs.voiceRecordings,
                artifacts = inputs.recordingArtifacts,
                excludedUuids = options.excludedRecordingUuids,
                kindOverrides = options.kindOverrides,
            )
        } else {
            emptyList()
        }
        val includedTalkCandidates = candidates.filter { it.includeInShare && it.unavailableReason == null }

        val intervals = buildList {
            inputs.activityIntervals
                .filter { it.activityType == ActivityType.MEDITATING }
                .forEach {
                    add(
                        SharePayload.ActivityIntervalPayload(
                            type = "meditation",
                            startTs = it.startTimestamp / MILLIS_PER_SECOND,
                            endTs = it.endTimestamp / MILLIS_PER_SECOND,
                        ),
                    )
                }
            // "Consent follows the checkbox: an excluded recording
            // leaves no trace — no talk interval, no rust on the route,
            // no minutes in the total."
            // (`WalkShareViewModel.swift:343-347@3f9f9e8`). The classic
            // branch reads the recordings directly and is unaffected by
            // exclusions, exactly as iOS's is.
            if (options.interactive) {
                includedTalkCandidates.forEach {
                    add(SharePayload.ActivityIntervalPayload(type = "talk", startTs = it.startTs, endTs = it.endTs))
                }
            } else {
                inputs.voiceRecordings.forEach {
                    add(
                        SharePayload.ActivityIntervalPayload(
                            type = "talk",
                            startTs = it.startTimestamp / MILLIS_PER_SECOND,
                            endTs = it.endTimestamp / MILLIS_PER_SECOND,
                        ),
                    )
                }
            }
        }

        val toggledStats = buildList {
            if (options.includeDistance) add("distance")
            if (options.includeDuration) add("duration")
            if (options.includeElevation) add("elevation")
            if (options.includeActivityBreakdown) add("activity_breakdown")
            if (options.includeSteps) add("steps")
        }

        // Toggle semantics — DISPLAY level, not data-transmission level
        // (iOS `WalkShareViewModel.swift:261-262` parity). The
        // `toggled_stats` list tells the server which fields to render
        // on the generated HTML page; the raw values still ride in the
        // payload regardless of toggle state. This is intentional for
        // distance / activeDuration / meditateDuration / talkDuration —
        // they're foundational walk metrics. `elevationAscent /
        // elevationDescent / steps` are nulled when their toggles are
        // off because they were always optional in the wire format
        // (the JSON encoder will omit them via `explicitNulls = false`)
        // and there's no display-only path on the server for them.
        // Future stages that add a "raw data" privacy toggle should
        // implement it as a separate flag, not by repurposing these
        // display toggles.
        val stats = SharePayload.Stats(
            distance = inputs.distanceMeters.takeIf { it > 0.0 },
            activeDuration = inputs.activeDurationSeconds.takeIf { it > 0.0 },
            elevationAscent = if (options.includeElevation) {
                inputs.elevationAscentMeters.takeIf { it > 1.0 }
            } else null,
            elevationDescent = if (options.includeElevation) {
                inputs.elevationDescentMeters.takeIf { it > 1.0 }
            } else null,
            steps = if (options.includeSteps) inputs.steps?.takeIf { it > 0 } else null,
            meditateDuration = inputs.meditateDurationSeconds.takeIf { it > 0.0 },
            // "Recordings outrun active time by design (a talk can run
            // through a pause); NewWalk clamps talkDuration to
            // activeDuration for the same reason, and the worker 400s on
            // meditate+talk > active — clamp the included-candidate sum
            // the same way." (`WalkShareViewModel.swift:364-365@3f9f9e8`).
            // Interactive branch only: the classic branch trusts the
            // walk's own total, unclamped by this rule.
            talkDuration = if (options.interactive) {
                minOf(includedTalkCandidates.sumOf { it.duration }, inputs.talkDurationSeconds)
                    .takeIf { it > 0.0 }
            } else {
                inputs.talkDurationSeconds.takeIf { it > 0.0 }
            },
            // Stage 12: weather rides through if the walk captured it
            // (Stage 12-A added the 4 cols on Walk). Already-nullable
            // wire fields keep the format wire-compatible with iOS.
            weatherCondition = inputs.walk.weatherCondition,
            weatherTemperature = inputs.walk.weatherTemperature,
        )

        val waypointsPayload = if (options.includeWaypoints && inputs.waypoints.isNotEmpty()) {
            inputs.waypoints.filter { wp ->
                // Trim's promise covers everything with a coordinate
                // (`WalkShareViewModel.swift:409-422,477@3f9f9e8`); the
                // window is inclusive at both ends, matching Swift's
                // `ClosedRange.contains`.
                interactiveRoute.keptWindow?.contains(wp.timestamp / MILLIS_PER_SECOND) ?: true
            }.map {
                SharePayload.Waypoint(
                    lat = it.latitude,
                    lon = it.longitude,
                    // Room entity allows null label/icon; backend
                    // rejects non-strings. Empty string is safe (≤
                    // MAX_WAYPOINT_LABEL_LEN on the server).
                    label = it.label.orEmpty(),
                    icon = it.icon.orEmpty(),
                    ts = it.timestamp / MILLIS_PER_SECOND,
                )
            }
        } else null

        // Phase 19: tour + pauses attach only on an interactive share.
        // A zero-candidate / zero-pause interactive walk still gets a
        // (empty) Tour and an empty pauses list, never null — matching
        // iOS's `applyInteractiveTourAndPauses`, which runs
        // unconditionally once `interactive` is true.
        val tour = if (options.interactive) {
            TourBuilder.tourItems(candidates = candidates, trimM = trimM, soundscapeUrl = options.soundscapeUrl).tour
        } else {
            null
        }
        val pauses = if (options.interactive) buildPauses(inputs.pauseSpans) else null

        return SharePayload(
            stats = stats,
            route = finalRoute,
            activityIntervals = intervals,
            journal = options.journal.takeIf { it.isNotBlank() },
            expiryDays = options.expiry.days,
            units = ShareConfig.DEFAULT_UNITS,
            // `ISO_OFFSET_DATE_TIME` with `Locale.ROOT` — avoids the
            // Arabic/Persian/Hindi non-ASCII-digit trap (Stage 6-B
            // lesson) and matches iOS `ISO8601DateFormatter`'s default
            // output shape `yyyy-MM-ddTHH:mm:ssZ`.
            startDate = ISO.format(Instant.ofEpochMilli(inputs.walk.startTimestamp).atZone(zoneId)),
            tzIdentifier = zoneId.id,
            toggledStats = toggledStats,
            placeStart = null,
            placeEnd = null,
            mark = null,
            waypoints = waypointsPayload,
            photos = if (options.includePhotos) photos else null,
            // iOS WalkShareViewModel.swift:327-343 — turning code from the
            // walk's start date, hemisphere-corrected from the walk's FIRST
            // ROUTE COORDINATE (iOS `turning(for:at:firstCoord)`), not the
            // device. No route → northern by convention.
            turningDay = run {
                val southern = (inputs.routePoints.firstOrNull()?.latitude ?: 0.0) < 0.0
                turningDayCode(
                    turningMarkerForEpochMillis(inputs.walk.startTimestamp)
                        .let { if (southern) it?.forSouthernHemisphere() else it },
                )
            },
            tour = tour,
            pauses = pauses,
        )
    }

    private val ISO: DateTimeFormatter =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withLocale(Locale.ROOT)

    private const val MILLIS_PER_SECOND = 1_000L
}

/**
 * Maps a turning marker to the share-wire code string. Cross-quarter and
 * non-turning markers carry no code. iOS `WalkShareViewModel.turningDayCode()`.
 */
internal fun turningDayCode(marker: SeasonalMarker?): String? = when (marker) {
    SeasonalMarker.SpringEquinox -> "spring-equinox"
    SeasonalMarker.SummerSolstice -> "summer-solstice"
    SeasonalMarker.AutumnEquinox -> "autumn-equinox"
    SeasonalMarker.WinterSolstice -> "winter-solstice"
    else -> null
}

/**
 * Port of iOS `applyInteractiveTourAndPauses`'s pause assembly
 * (`WalkShareViewModel.swift:442-452`): truncates each span to
 * epoch-second start/end (matching the worker's truncated-integer
 * validation), drops any span whose truncated end doesn't exceed its
 * truncated start, and caps at [PAUSE_CAP] — applied AFTER truncation
 * and AFTER the drop-filter, so a truncated-to-zero-length pause can
 * never crowd out a legitimate 201st pause.
 *
 * Pure and independently testable; [SharePayloadBuilder.build] calls
 * this only when `options.interactive` is true.
 */
internal fun buildPauses(spans: List<PauseSpan>): List<SharePayload.Pause> =
    spans
        .map { span ->
            // Epoch-MILLIS -> epoch-SECONDS, same truncation as SharePayloadBuilder's own route/interval timestamps.
            val startTs = span.startMs / 1_000L
            val endTs = (span.startMs + span.durationMillis) / 1_000L
            startTs to endTs
        }
        .filter { (start, end) -> end > start }
        .take(PAUSE_CAP)
        .map { (start, end) -> SharePayload.Pause(startTs = start, endTs = end) }

private const val PAUSE_CAP = 200
