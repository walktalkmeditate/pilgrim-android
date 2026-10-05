// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import androidx.compose.runtime.Immutable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import org.walktalkmeditate.pilgrim.data.honor.HonorCardStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorMomentStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.dismissedAt
import org.walktalkmeditate.pilgrim.data.honor.HonorVoiceEnd
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind

/*
 * The walk's Honor cards as pure state (parity spec E §7–§11, D §5–§6),
 * rebuilt from what `:tracker` persists and the UI's own card rows, so a
 * restarted UI draws the same queue (AE1). iOS keeps it all in memory on
 * one view model.
 */

/**
 * The card-state row behind the arrival card's "continue". No Way moment
 * id has this shape (`voice-n`, `photo-n`, …), so it never names a card.
 */
const val HONOR_ARRIVAL_CARD_ID = "arrival"

/** iOS `cardRetireSeconds` (`ActiveWalkViewModel+Honor.swift:305-306@7c200bf`). */
const val CARD_RETIRE_MILLIS = 20_000L

/**
 * What the walker did to cards in this UI process: when a pin was tapped,
 * which only this process orders against the moment rows, and the
 * dismissals and touches it made, kept here until their rows land.
 */
@Immutable
data class CardTouches(
    val taps: Map<String, Long> = emptyMap(),
    val dismissals: Map<String, Long> = emptyMap(),
    val touched: Set<String> = emptySet(),
)

/** The place cards in queue order, front first; only the front one shows. */
@Immutable
data class HonorCardQueue(
    val momentIds: List<String>,
    /** When a voice card next retires on its own; null with none due. */
    val nextChangeAtMillis: Long?,
) {
    val top: String? get() = momentIds.firstOrNull()

    /** iOS `pendingCount: max(0, honorCards.count - 1)`. */
    val pendingCount: Int get() = (momentIds.size - 1).coerceAtLeast(0)
}

object HonorCards {

    /**
     * iOS's queue (D §5, correction 15), from the rows that record each of
     * its events:
     * - a reached place (any non-voice moment) is appended, at `reached_at`;
     * - a voice's card goes to the front when the voice starts, at
     *   `voice_started_at` (rewritten by a replay, which only ever starts
     *   from the card already in front);
     * - a tapped pin goes to the front, at its tap.
     *
     * So the queue is the fronted cards, latest first, then the appended
     * ones in reach order. A dismissal takes the card out until something
     * raises it after the dismissal: its voice starting, its pin tapped, or
     * its place reached, which iOS appends whatever the walker did with it
     * before; a pin tapped earlier is spent by the dismissal. Dismissals
     * keep their time in Room, so a restarted UI orders them the same.
     *
     * A voice card retires 20 s after its voice ended on its own or failed,
     * unless touched or playing again; skipped, dropped, replaced, and
     * reply-interrupted voices never retire, nor does any other card
     * (pilgrim-ios #106, matched). An engine voice the player refused at
     * its start sits above the voice it handed its turn to, as iOS's
     * synchronous start failure raises the failed card last (pilgrim-ios
     * #106, matched); any other failure leaves the card where it was.
     */
    fun queue(
        way: Way,
        rows: List<HonorMomentStateEntity>,
        cardRows: List<HonorCardStateEntity>,
        local: CardTouches,
        playingMomentId: String?,
        nowMillis: Long,
    ): HonorCardQueue {
        val rowById = rows.associateBy { it.momentId }
        val cardById = cardRows.associateBy { it.momentId }
        val fronted = mutableListOf<Pair<FrontKey, Int>>()
        val appended = mutableListOf<Pair<Long, Int>>()
        var nextChange: Long? = null
        way.moments.forEachIndexed { index, moment ->
            val row = rowById[moment.id]
            val dismissedAt = listOfNotNull(local.dismissals[moment.id], cardById[moment.id]?.dismissedAt).maxOrNull()
            val tappedAt = local.taps[moment.id]?.takeIf { dismissedAt == null || it > dismissedAt }
            val front = frontKey(moment, row, tappedAt)
            val appendAt = if (moment.isVoice) null else row?.reachedAt
            val raisedAt = listOfNotNull(front?.raisedAt, appendAt).maxOrNull() ?: return@forEachIndexed
            if (dismissedAt != null && raisedAt <= dismissedAt) return@forEachIndexed
            val touched = moment.id in local.touched || cardById[moment.id]?.touched == true
            val retireAt = retireAt(moment, row, touched, playingMomentId)
            if (retireAt != null) {
                val retappedSince = tappedAt != null && tappedAt >= retireAt
                if (nowMillis >= retireAt && !retappedSince) return@forEachIndexed
                if (nowMillis < retireAt) nextChange = minOf(nextChange ?: retireAt, retireAt)
            }
            if (front != null) {
                fronted += front to index
            } else if (appendAt != null) {
                appended += appendAt to index
            }
        }
        val order = fronted
            .sortedWith(compareByDescending<Pair<FrontKey, Int>> { it.first.atMillis }.thenByDescending { it.first.rank }.thenBy { it.second })
            .map { it.second } +
            appended.sortedWith(compareBy<Pair<Long, Int>> { it.first }.thenBy { it.second }).map { it.second }
        return HonorCardQueue(order.map { way.moments[it].id }, nextChange)
    }

    /**
     * Where a fronted card sorts ([atMillis], then [rank]), and [raisedAt],
     * when iOS raised it, which a dismissal is weighed against: a failed
     * start sorts at its failure but was raised with its voice.
     */
    private data class FrontKey(val atMillis: Long, val rank: Int, val raisedAt: Long = atMillis)

    private fun frontKey(moment: WayMoment, row: HonorMomentStateEntity?, tappedAt: Long?): FrontKey? {
        val voiceKey = row?.takeIf { moment.isVoice }?.voiceKey()
        val tapKey = tappedAt?.let { FrontKey(it, rank = 0) }
        return listOfNotNull(voiceKey, tapKey).maxWithOrNull(compareBy<FrontKey> { it.atMillis }.thenBy { it.rank })
    }

    private fun HonorMomentStateEntity.voiceKey(): FrontKey? {
        val start = voiceStartedAt ?: return null
        val failedAt = voiceEndedAt?.takeIf { voiceEnd == HonorVoiceEnd.FAILED_AT_START }
        return if (failedAt != null) FrontKey(maxOf(start, failedAt), rank = 1, raisedAt = start) else FrontKey(start, rank = 0)
    }

    /** iOS `retireCardLater`, scheduled only from `onFinished`: a natural end or a failure. */
    private fun retireAt(moment: WayMoment, row: HonorMomentStateEntity?, touched: Boolean, playingMomentId: String?): Long? {
        if (!moment.isVoice || row == null || touched || playingMomentId == moment.id) return null
        if (row.voiceEnd !in RETIRING_ENDS) return null
        return row.voiceEndedAt?.plus(CARD_RETIRE_MILLIS)
    }

    private val RETIRING_ENDS = setOf(HonorVoiceEnd.FINISHED, HonorVoiceEnd.FAILED, HonorVoiceEnd.FAILED_AT_START)
}

/**
 * The voice the player holds, as the session row records it (iOS
 * `activeVoice`, `isVoicePaused`, `voiceRate`): null [playingMomentId]
 * while nothing plays, or while a reply does. With no [startedAtMillis]
 * the voice is held still, behind a guide prompt or a call, and not paused.
 */
@Immutable
data class HonorVoiceView(
    val playingMomentId: String?,
    val paused: Boolean,
    val startedAtMillis: Long?,
    val startOffsetMillis: Long?,
    val pauseOffsetMillis: Long?,
    val rate: Float,
) {
    /** Where in its file the voice stands, estimated from its last start or resume at [rate]. */
    fun positionMillis(nowMillis: Long): Long {
        if (playingMomentId == null) return 0L
        pauseOffsetMillis?.let { return it }
        val started = startedAtMillis ?: return startOffsetMillis ?: 0L
        return (startOffsetMillis ?: 0L) + ((nowMillis - started).coerceAtLeast(0) * rate).roundToLong()
    }

    fun pausedAt(nowMillis: Long) =
        copy(paused = true, startedAtMillis = null, startOffsetMillis = null, pauseOffsetMillis = positionMillis(nowMillis))

    fun resumedAt(nowMillis: Long) =
        copy(paused = false, startedAtMillis = nowMillis, startOffsetMillis = positionMillis(nowMillis), pauseOffsetMillis = null)

    fun pausedOrResumedAt(nowMillis: Long) = if (paused) resumedAt(nowMillis) else pausedAt(nowMillis)

    fun playing(momentId: String, nowMillis: Long, offsetMillis: Long = 0L) = copy(
        playingMomentId = momentId,
        paused = false,
        startedAtMillis = nowMillis,
        startOffsetMillis = offsetMillis,
        pauseOffsetMillis = null,
    )

    /** A voice held still moves and stays held, as `:tracker`'s does. */
    fun movedTo(offsetMillis: Long, nowMillis: Long) = if (paused) {
        copy(pauseOffsetMillis = offsetMillis)
    } else {
        copy(startedAtMillis = startedAtMillis?.let { nowMillis }, startOffsetMillis = offsetMillis)
    }

    fun released() = HonorVoiceView(null, paused = false, null, null, null, rate)

    companion object {
        fun of(session: HonorSessionEntity) = HonorVoiceView(
            playingMomentId = session.playingMomentId,
            paused = session.voicePaused,
            startedAtMillis = session.voiceStartedAt,
            startOffsetMillis = session.voiceStartOffsetMillis,
            pauseOffsetMillis = session.voicePauseOffsetMillis,
            rate = session.voiceRate.toFloat(),
        )
    }
}

/**
 * A command's result shown at once: iOS applies each on its main thread,
 * Android sends it to `:tracker`. It holds only while the session row
 * still reads as it did when the command left ([baseline]): any change Room
 * brings is the tracker's answer and wins, and with none inside the window
 * the persisted state comes back.
 */
@Immutable
data class PendingVoiceCommand(
    val expected: HonorVoiceView,
    val baseline: HonorVoiceView,
    val sentAtMillis: Long,
)

/** How long a command's result is shown before an unanswered command gives way to the persisted state. */
const val COMMAND_CONFIRM_WINDOW_MILLIS = 3_000L

fun PendingVoiceCommand?.heldOver(persisted: HonorVoiceView, nowMillis: Long): PendingVoiceCommand? =
    this?.takeIf { it.baseline == persisted && nowMillis < it.sentAtMillis + COMMAND_CONFIRM_WINDOW_MILLIS }

/** What the arrival card counts (iOS `HonorArrivalCard`, `ActiveWalkViewModel+Honor.swift:16-36@7c200bf`). */
@Immutable
data class HonorArrivalSummary(
    val wayTitle: String,
    val voicesHeard: Int,
    val placesPassed: Int,
    /** A pilgrimage stage's name; the card then speaks of the stage rather than of another walker. */
    val stageName: String? = null,
    /**
     * The engine's along-Way metres at the instant arrival fired, frozen in
     * Room beside the arrival's numbers (pilgrimage-stage spec P5 §8.2, A4);
     * only a stage's card shows them.
     */
    val distanceWalkedMeters: Double = 0.0,
    /** The stage's closing line; a Way that isn't a stage has none. */
    val closing: String? = null,
) {
    val isStage: Boolean get() = stageName != null
}

object HonorArrival {

    /**
     * iOS snapshots its live sets as arrival lands
     * (`ActiveWalkViewModel+Honor.swift:253-259@7c200bf`): every voice handed
     * to the player (a failed start included, a missing file never), and
     * every place the engine reached (not pin taps). Here the rows say when
     * each happened, so what came after [arrivedAtMillis] is left out; with
     * the arrival's time unknown, everything counts. A stage adds its name,
     * its closing line, and [walkedMeters], the engine's credit at arrival.
     */
    fun summary(
        way: Way,
        rows: List<HonorMomentStateEntity>,
        arrivedAtMillis: Long?,
        walkedMeters: Double = 0.0,
    ): HonorArrivalSummary {
        val (voices, places) = way.moments.partition { it.isVoice }
        val voiceIds = voices.mapTo(mutableSetOf()) { it.id }
        val placeIds = places.mapTo(mutableSetOf()) { it.id }
        fun before(at: Long?) = at != null && (arrivedAtMillis == null || at < arrivedAtMillis)
        return HonorArrivalSummary(
            wayTitle = way.title,
            voicesHeard = rows.count { it.momentId in voiceIds && it.heard && before(it.voiceStartedAt) },
            placesPassed = rows.count { it.momentId in placeIds && before(it.reachedAt) },
            stageName = way.stage?.name,
            distanceWalkedMeters = walkedMeters,
            closing = way.stage?.closing,
        )
    }
}

/** iOS `WayDistance` and the card's subline (`WayMomentHeader.swift:94-118@7c200bf`). */
object WayRelation {

    /** Under this, the subline reads "here". */
    const val HERE_METERS = 30.0

    /**
     * Metres up to a kilometre, feet up to a tenth of a mile, then one
     * decimal. Not localized on iOS; `%.1f` is pinned to [Locale.US].
     */
    fun distance(meters: Double, units: UnitSystem): String {
        val clamped = meters.coerceAtLeast(0.0)
        return when (units) {
            UnitSystem.Imperial -> {
                val miles = clamped / METERS_PER_MILE
                if (miles < 0.1) {
                    "${(clamped * FEET_PER_METER).roundToLong()} ft"
                } else {
                    String.format(Locale.US, "%.1f mi", miles)
                }
            }
            UnitSystem.Metric ->
                if (clamped < 1000) "${clamped.roundToLong()} m" else String.format(Locale.US, "%.1f km", clamped / 1000)
        }
    }

    /**
     * "here" or "<distance> away", then a shared walk's street name after
     * " · "; null when neither exists, before the first fix with no place.
     * [here] and [away] are the resolved copy.
     */
    fun subline(
        distanceMeters: Double?,
        place: String?,
        units: UnitSystem,
        here: String,
        away: (String) -> String,
    ): String? {
        val parts = buildList {
            distanceMeters?.let { add(if (it < HERE_METERS) here else away(distance(it, units))) }
            place?.takeIf { it.isNotEmpty() }?.let(::add)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /**
     * iOS `relativeBearing(to:)`: degrees clockwise from the walker's heading
     * to the moment, 0 straight ahead, in `[0, 360)`; null until both a fix
     * and a settled compass exist. The `+ 360` keeps the remainder positive.
     */
    fun tick(here: WayCoordinate?, headingDegrees: Double?, there: WayCoordinate?): Double? {
        if (here == null || headingDegrees == null || there == null) return null
        return (WayGeometry.bearing(from = here, to = there) - headingDegrees + 360) % 360
    }

    private const val METERS_PER_MILE = 1609.344
    private const val FEET_PER_METER = 3.28084
}

/**
 * iOS `showSoftTapCaption` (`ActiveWalkViewModel+Honor.swift:236-245@7c200bf`):
 * whole metres, truncated, capped at 999,999, always metres whatever the
 * walker's units (pilgrim-ios #109, matched).
 */
fun softTapCaptionMeters(meters: Double): Long = (if (meters.isFinite()) meters else 0.0).coerceAtMost(999_999.0).toLong()

/** The heading filter iOS's `HeadingProvider` sets (`headingFilter = 3`): a new value only once it has turned this far. */
class HeadingFilter(private val minDegrees: Double = 3.0) {
    private var last: Double? = null

    /** The heading to publish for [degrees], or null to keep the last one. */
    fun next(degrees: Double): Double? {
        val previous = last
        if (previous != null && angularDistance(previous, degrees) < minDegrees) return null
        last = degrees
        return degrees
    }

    fun reset() {
        last = null
    }

    private fun angularDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}

/** The voice's length as the Way recorded it, which the card's clock and progress use; 0 for a share that sent none. */
val WayMoment.voiceDurationSeconds: Double
    get() = (kind as? WayMomentKind.Voice)?.duration ?: 0.0
