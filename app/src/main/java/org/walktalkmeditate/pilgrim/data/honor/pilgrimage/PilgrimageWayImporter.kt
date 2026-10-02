// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import java.nio.charset.CharacterCodingException
import kotlin.math.abs
import kotlinx.serialization.DeserializationStrategy
import org.walktalkmeditate.pilgrim.data.honor.WayImporter
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.honor.OwnWalkWayBuilder
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry
import org.walktalkmeditate.pilgrim.domain.honor.WayMark
import org.walktalkmeditate.pilgrim.domain.honor.WayMarkKind
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WayStage
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours
import org.walktalkmeditate.pilgrim.domain.honor.WayStagePlace
import org.walktalkmeditate.pilgrim.domain.honor.prefixCharacters
import org.walktalkmeditate.pilgrim.domain.honor.swiftCompareTo
import org.walktalkmeditate.pilgrim.domain.honor.trimmingWhitespacesAndNewlines

/**
 * The sibling of [WayImporter] for the dataset's packaged stages: iOS
 * `PilgrimageWayImporter` (`PilgrimageWayImporter.swift:50-370@7c200bf`,
 * P1 §5–§6). It decodes the build's wire format and hands back the [Way]
 * the engine already walks. Every number is range-checked before any
 * `toInt()`, since Kotlin saturates where Swift traps, and every string is
 * cut before anything reaches a view.
 *
 * A stage is checked only against the request it was fetched for.
 * `route.json` against the catalog entry, and a stage's count and name
 * against `route.json`, are the route preview's and the package manager's
 * checks, as on iOS (P1 C3, §11).
 *
 * Nothing is logged: not a file, not an id, not a decode error, whose
 * kotlinx message quotes the input.
 */
object PilgrimageWayImporter {

    const val MAX_STAGE_BYTES = 2 * 1024 * 1024
    const val MAX_ROUTE_BYTES = 512 * 1024
    const val MAX_MARKS = 400
    const val MAX_THEME_CHARACTERS = 80
    const val MAX_NARRATIVE_CHARACTERS = 2_000
    const val MAX_CLOSING_CHARACTERS = 400
    const val MAX_WARNING_CHARACTERS = 300
    const val MAX_STAGE_NAME_CHARACTERS = 120
    const val MAX_MARK_NAME_CHARACTERS = 80
    const val MAX_SUMMARY_CHARACTERS = 600
    const val MAX_WARNINGS = 20
    const val MAX_LOCAL_NAMES = 20
    const val MAX_DISTANCE_KM = 10_000.0
    const val MAX_STAGE_COUNT = 200
    const val MAX_GAIN_METERS = 30_000.0
    const val MAX_HOURS = 100.0

    /** A literal in iOS's `validate` (`PilgrimageWayImporter.swift:329@7c200bf`). */
    const val MAX_OFF_LINE_METERS = 100_000.0

    private const val WAYPOINT_KIND = "waypoint"
    private val LANGUAGE_CODE = Regex("[a-z]{2,3}")

    /** Swift's `WayMarkKind(rawValue:)`: the six serial names exactly, never the Kotlin names. */
    private val MARK_KINDS: Map<String, WayMarkKind> = WayMarkKind.serializer().descriptor.let { descriptor ->
        WayMarkKind.entries.associateBy { descriptor.getElementName(it.ordinal) }
    }

    /**
     * iOS `way(from:routeId:stageIndex:)` (`PilgrimageWayImporter.swift:168-203@7c200bf`),
     * its checks in iOS's order (P1 §5.1): the request before the bytes,
     * the length cap before the decode, the file's identity against the
     * request, every bound, the departure date, then the line's length.
     *
     * @throws PilgrimageException [PilgrimageError.NOT_WALKABLE], for every refusal.
     */
    fun way(from: ByteArray, routeId: String, stageIndex: Int): Way {
        if (!WayStore.isValidRouteId(routeId) || stageIndex !in 0 until MAX_STAGE_COUNT) throw notWalkable()
        if (from.size > MAX_STAGE_BYTES) throw notWalkable()
        val file = decoded(StageFile.serializer(), from)
        val expectedId = WayStore.stageWayId(routeId, stageIndex)
        if (file.id != expectedId || file.stage.routeId != routeId || file.stage.index != stageIndex.toLong()) {
            throw notWalkable()
        }
        if (!validate(file)) throw notWalkable()
        val departed = WayImporter.isoDate(file.departedAt) ?: throw notWalkable()

        val route = file.route.map { WayPoint(lat = it.lat, lon = it.lon, alt = it.alt, t = it.t) }
        val geometry = WayGeometry(route)
        // iOS guards with `>=`, so a NaN length (two antipodal points) is refused too.
        if (!(geometry.totalMeters >= OwnWalkWayBuilder.MIN_LENGTH_METERS)) throw notWalkable()

        return Way(
            id = expectedId,
            source = WaySource.Pilgrimage(routeId = routeId, stageIndex = stageIndex),
            title = file.title.prefixCharacters(MAX_STAGE_NAME_CHARACTERS),
            departedAt = departed,
            tzIdentifier = file.tzIdentifier?.prefixCharacters(WayImporter.MAX_LABEL_CHARACTERS),
            expires = null,
            route = route,
            totalDistanceMeters = geometry.totalMeters,
            theirActiveSeconds = file.theirActiveSeconds,
            moments = moments(file.moments),
            weather = null,
            marks = marks(file.marks),
            stage = stage(file.stage),
        )
    }

    /**
     * iOS `route(from:)` (`PilgrimageWayImporter.swift:257-290@7c200bf`, P1 §6).
     * Whether `stageCount` equals the rows' count, and the route against
     * the catalog entry, are for the callers to check.
     *
     * @throws PilgrimageException [PilgrimageError.NOT_WALKABLE], for every refusal.
     */
    fun route(from: ByteArray): PilgrimageRoute {
        if (from.size > MAX_ROUTE_BYTES) throw notWalkable()
        val file = decoded(RouteFile.serializer(), from)
        val sane = WayStore.isValidRouteId(file.id) &&
            isSaneDistance(file.distanceKm) &&
            file.stageCount in 1L..MAX_STAGE_COUNT &&
            file.stages.size <= MAX_STAGE_COUNT &&
            file.stages.all(::isSaneStageRow)
        if (!sane) throw notWalkable()
        // Stages are saved by position and a tapped row is keyed on its own
        // index, so anything but the exact run 0 until count (1-based, a
        // duplicate, a gap) would open a different stage than the row names.
        if (file.stages.map { it.index }.sorted() != List(file.stages.size) { it.toLong() }) throw notWalkable()
        return PilgrimageRoute(
            id = file.id,
            name = file.name.prefixCharacters(MAX_STAGE_NAME_CHARACTERS),
            names = localNames(file.names).orEmpty(),
            country = file.country?.prefixCharacters(WayImporter.MAX_LABEL_CHARACTERS),
            region = file.region?.prefixCharacters(WayImporter.MAX_LABEL_CHARACTERS),
            distanceKm = file.distanceKm,
            stageCount = file.stageCount.toInt(),
            tradition = file.tradition?.prefixCharacters(WayImporter.MAX_LABEL_CHARACTERS),
            summary = trimmed(file.summary, MAX_SUMMARY_CHARACTERS),
            stages = file.stages.sortedBy { it.index }.map { row ->
                PilgrimageRouteStage(
                    index = row.index.toInt(),
                    name = row.name.prefixCharacters(MAX_STAGE_NAME_CHARACTERS),
                    distanceKm = row.distanceKm,
                    gainMeters = row.gainMeters,
                    hours = WayStageHours(min = row.hours.min, max = row.hours.max),
                    difficulty = row.difficulty.prefixCharacters(WayImporter.MAX_LABEL_CHARACTERS),
                )
            },
        )
    }

    private fun notWalkable() = PilgrimageException(PilgrimageError.NOT_WALKABLE)

    /** The shared wire decode; a failure goes no further than "not walkable", its message dropped. */
    private fun <T> decoded(deserializer: DeserializationStrategy<T>, data: ByteArray): T = try {
        WayImporter.decodeWire(deserializer, data)
    } catch (e: IllegalArgumentException) {
        throw notWalkable()
    } catch (e: CharacterCodingException) {
        throw notWalkable()
    }

    /**
     * iOS `validate` (`PilgrimageWayImporter.swift:304-341@7c200bf`), its
     * rows in iOS's order (P1 §5.2). Moments and marks of every kind are
     * counted and checked, the kinds the build then skips included.
     */
    private fun validate(file: StageFile): Boolean {
        fun inLat(v: Double) = v.isFinite() && v in -90.0..90.0
        fun inLon(v: Double) = v.isFinite() && v in -180.0..180.0
        fun inFrac(v: Double) = v.isFinite() && v in 0.0..1.0
        fun onEarth(at: StageFile.Coordinate) = inLat(at.lat) && inLon(at.lon)

        val counted = file.route.size >= 2 && file.route.size <= WayImporter.MAX_ROUTE_POINTS &&
            file.moments.size <= WayImporter.MAX_ENCOUNTERS &&
            file.marks.size <= MAX_MARKS
        if (!counted) return false
        val pointsInRange = file.route.all { point ->
            inLat(point.lat) && inLon(point.lon) &&
                point.t.isFinite() && point.t in 0.0..WayImporter.MAX_ACTIVE_DURATION_SECONDS &&
                (point.alt?.let { it.isFinite() && abs(it) < WayImporter.MAX_ALTITUDE_METERS } ?: true)
        }
        if (!pointsInRange) return false
        if ((1 until file.route.size).any { i -> file.route[i].t < file.route[i - 1].t }) return false
        val totalsInRange = file.totalDistanceMeters.isFinite() && file.totalDistanceMeters >= 0 &&
            file.theirActiveSeconds.isFinite() &&
            file.theirActiveSeconds in 0.0..WayImporter.MAX_ACTIVE_DURATION_SECONDS
        if (!totalsInRange) return false

        for (moment in file.moments) {
            if (!inFrac(moment.frac)) return false
            if (moment.at != null && !onEarth(moment.at)) return false
            if (moment.pin != null && !onEarth(moment.pin)) return false
            if (moment.sitMinutes != null && moment.sitMinutes !in 0..WayImporter.MAX_REST_MINUTES) return false
        }
        for (mark in file.marks) {
            val inRange = inFrac(mark.frac) && onEarth(mark.at) &&
                mark.offLineMeters.isFinite() && mark.offLineMeters in 0.0..MAX_OFF_LINE_METERS
            if (!inRange) return false
        }

        val stage = file.stage
        return stage.index in 0L until MAX_STAGE_COUNT &&
            stage.count in 1L..MAX_STAGE_COUNT &&
            stage.index < stage.count &&
            isSaneDistance(stage.distanceKm) && isSaneGain(stage.gainMeters) && isSaneHours(stage.hours) &&
            stage.warnings.size <= MAX_WARNINGS &&
            onEarth(stage.start.at) && onEarth(stage.end.at)
    }

    private fun isSaneStageRow(row: RouteFile.Stage): Boolean =
        row.index in 0L until MAX_STAGE_COUNT &&
            isSaneDistance(row.distanceKm) &&
            isSaneGain(row.gainMeters) &&
            isSaneHours(row.hours)

    private fun isSaneDistance(km: Double): Boolean = km.isFinite() && km in 0.0..MAX_DISTANCE_KM

    private fun isSaneGain(meters: Double): Boolean = meters.isFinite() && meters in 0.0..MAX_GAIN_METERS

    private fun isSaneHours(hours: StageFile.Hours): Boolean =
        hours.min.isFinite() && hours.max.isFinite() &&
            hours.min in 0.0..MAX_HOURS && hours.max in 0.0..MAX_HOURS && hours.max >= hours.min

    /**
     * Only waypoints are kept; any other kind is skipped once it has been
     * counted and checked, so a stage that packaged only unknown kinds is a
     * quiet stage, not a broken one (`PilgrimageWayImporter.swift:208-226@7c200bf`).
     */
    private fun moments(raw: List<StageFile.Moment>): List<WayMoment> =
        raw.filter { it.kind == WAYPOINT_KIND }.map { entry ->
            WayMoment(
                id = entry.id.prefixCharacters(WayImporter.MAX_LABEL_CHARACTERS),
                frac = entry.frac,
                at = entry.at?.let { WayCoordinate(lat = it.lat, lon = it.lon) },
                // Cut, never trimmed; the icon falls back only when absent (S1 §9 traps 7–8),
                // and an absent label is an empty kicker (pilgrim-ios #123 item 10, matched).
                kind = WayMomentKind.Waypoint(
                    label = entry.label.orEmpty().prefixCharacters(WayImporter.MAX_LABEL_CHARACTERS),
                    icon = (entry.icon ?: WayImporter.DEFAULT_WAYPOINT_ICON).prefixCharacters(WayImporter.MAX_ICON_CHARACTERS),
                ),
                text = trimmed(entry.text, WayMoment.MAX_TRANSCRIPT_CHARACTERS),
                names = localNames(entry.names),
                sitMinutes = entry.sitMinutes?.toInt(),
                pin = entry.pin?.let { WayCoordinate(lat = it.lat, lon = it.lon) },
            )
        }.sortedWith(WayImporter.BY_FRAC_THEN_ID)

    /** In file order, never sorted; a kind iOS doesn't know drops the mark (`PilgrimageWayImporter.swift:228-236@7c200bf`). */
    private fun marks(raw: List<StageFile.Mark>): List<WayMark> = raw.mapNotNull { entry ->
        val kind = MARK_KINDS[entry.kind] ?: return@mapNotNull null
        WayMark(
            id = entry.id.prefixCharacters(WayImporter.MAX_LABEL_CHARACTERS),
            kind = kind,
            name = entry.name.prefixCharacters(MAX_MARK_NAME_CHARACTERS),
            at = WayCoordinate(lat = entry.at.lat, lon = entry.at.lon),
            frac = entry.frac,
            offLineMeters = entry.offLineMeters,
        )
    }

    private fun stage(raw: StageFile.Stage): WayStage = WayStage(
        routeId = raw.routeId,
        index = raw.index.toInt(),
        count = raw.count.toInt(),
        name = raw.name.prefixCharacters(MAX_STAGE_NAME_CHARACTERS),
        theme = raw.theme.prefixCharacters(MAX_THEME_CHARACTERS),
        narrative = raw.narrative.prefixCharacters(MAX_NARRATIVE_CHARACTERS),
        closing = raw.closing.prefixCharacters(MAX_CLOSING_CHARACTERS),
        warnings = raw.warnings.take(MAX_WARNINGS).map { it.prefixCharacters(MAX_WARNING_CHARACTERS) },
        distanceKm = raw.distanceKm,
        gainMeters = raw.gainMeters,
        hours = WayStageHours(min = raw.hours.min, max = raw.hours.max),
        difficulty = raw.difficulty.prefixCharacters(WayImporter.MAX_LABEL_CHARACTERS),
        start = place(raw.start),
        end = place(raw.end),
    )

    private fun place(raw: StageFile.Place): WayStagePlace = WayStagePlace(
        name = raw.name.prefixCharacters(MAX_STAGE_NAME_CHARACTERS),
        at = WayCoordinate(lat = raw.at.lat, lon = raw.at.lon),
    )

    /** Swift's whitespace trim, nil when nothing is left, then the cut. */
    private fun trimmed(raw: String?, maxCharacters: Int): String? {
        val trimmed = raw?.trimmingWhitespacesAndNewlines()
        if (trimmed.isNullOrEmpty()) return null
        return trimmed.prefixCharacters(maxCharacters)
    }

    /**
     * iOS `localNames` (`PilgrimageWayImporter.swift:359-369@7c200bf`), in
     * its order: sort the raw pairs by key, cut to the first
     * [MAX_LOCAL_NAMES], then drop a key that isn't `[a-z]{2,3}` or a value
     * that trims to nothing (P1 §5.3, C4). The cut comes first, so invalid
     * keys that sort early push valid ones out (pilgrim-ios #123 item 9, matched).
     * Keys sort as Swift's `<` compares them (A10): otherwise a decomposed
     * key would sort among the ASCII ones and take a slot.
     */
    private fun localNames(raw: Map<String, String>?): Map<String, String>? {
        if (raw.isNullOrEmpty()) return null
        val pairs = raw.entries
            .sortedWith { a, b -> a.key.swiftCompareTo(b.key) }
            .take(MAX_LOCAL_NAMES)
            .mapNotNull { (key, value) ->
                if (!LANGUAGE_CODE.matches(key)) return@mapNotNull null
                val name = trimmed(value, MAX_STAGE_NAME_CHARACTERS) ?: return@mapNotNull null
                key to name
            }
        return if (pairs.isEmpty()) null else pairs.toMap()
    }
}
