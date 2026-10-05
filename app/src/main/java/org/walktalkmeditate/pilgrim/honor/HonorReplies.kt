// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.walk.honor.voiceOriginIndex

/** The voice a walk's next completed recording answers (iOS `pendingReplyOrigin`). */
data class PendingReply(
    val walkId: Long,
    val wayId: String,
    val momentId: String,
    /**
     * Whether the Way had its own listed folder when "reply here" was
     * tapped. A reply files only into that folder, never into staging, so a
     * first honoring of an own walk loses its replies, the one still
     * recording at walk end included, though the finalize step lists the Way
     * moments before that last one lands (pilgrim-ios #98, matched). A share
     * is listed from acceptance, and a stage with its package, so their
     * first honorings keep them.
     */
    val listed: Boolean,
)

/**
 * "reply here" (parity spec D §7, A §21, correction 3), in the UI process:
 * the origin the card arms, and the mapping filed into the Ways store at
 * each place a recording is saved: the walker's stop, a focus-loss
 * interruption, and the walk-end auto-stop. iOS files from one listener on
 * its recording list, the newest recording, after the recording completes;
 * each insert site here files right after its row lands. Blocking: call on
 * an IO dispatcher.
 *
 * With the release flag off nothing arms an origin, so nothing is filed.
 */
@Singleton
class HonorReplies internal constructor(
    private val isListed: (wayId: String) -> Boolean,
    private val setReply: (wayId: String, originN: Int, relativePath: String) -> Unit,
) {
    @Inject
    constructor(wayStore: WayStore) : this(
        isListed = { wayStore.load(it) != null },
        setReply = wayStore::setReply,
    )

    private val _pending = MutableStateFlow<PendingReply?>(null)
    val pending: StateFlow<PendingReply?> = _pending.asStateFlow()

    private val _filed = MutableStateFlow(0L)

    /** Counts the replies written into a Way's folder, so the card showing their voice reads its reply again. */
    val filed: StateFlow<Long> = _filed.asStateFlow()

    /** iOS `replyHere(to:)`'s first step. An ordinary take already recording becomes the reply (pilgrim-ios #99, matched). */
    fun arm(walkId: Long, wayId: String, momentId: String) {
        _pending.value = PendingReply(walkId, wayId, momentId, listed = isListed(wayId))
    }

    /** The recorder never started, so no completed recording will come to take the origin. */
    fun disarm(walkId: Long) {
        _pending.value?.takeIf { it.walkId == walkId }?.let { _pending.compareAndSet(it, null) }
    }

    /** A discarded walk files no reply (iOS `cancel()`); it was the only walk that could hold one. */
    fun clear() {
        _pending.value = null
    }

    /**
     * iOS `recordReplyIfPending`: files [recording] under the origin voice's
     * own `n`, or a stage's closing line's reserved −1 (pilgrimage-stage spec
     * P3 §13), clearing the origin first. A write into a missing folder fails
     * silently, as iOS's `try?` does. A take that never saved files nothing
     * and leaves the origin armed for the walk's next recording (pilgrim-ios
     * #99, matched): no caller reaches here without a saved row.
     */
    fun fileIfPending(recording: VoiceRecording) {
        val origin = _pending.value ?: return
        if (origin.walkId != recording.walkId) return
        val n = voiceOriginIndex(origin.momentId) ?: return
        if (!_pending.compareAndSet(origin, null)) return
        if (!origin.listed) return
        try {
            setReply(origin.wayId, n, recording.fileRelativePath)
            _filed.update { it + 1 }
        } catch (_: IOException) {
            // iOS `try?`: the reply's recording stays an ordinary walk recording.
        } catch (_: IllegalArgumentException) {
            // An id the store refuses can't name a folder to file into.
        }
    }

    companion object {
        /** Arms nothing it can file: for callers built outside Hilt with no Honor in play. */
        fun inert() = HonorReplies(isListed = { false }, setReply = { _, _, _ -> })
    }
}

/**
 * The minutes a card's "Sit?" offers the meditation screen's caption (iOS
 * `suggestedMeditationMinutes`, parity spec E §12). Only "Sit?" offers
 * them, so a sitting started from the sheet shows no caption; the offer is
 * for the first sitting that begins after it on the same walk, and is
 * withdrawn when any sitting ends, from the screen or the notification
 * (iOS `finalizeMeditation`, `ActiveWalkViewModel.swift:502-503@7c200bf`).
 */
@Singleton
class TheirSitting @Inject constructor() {

    data class Offer(val walkId: Long, val minutes: Int, val offeredAtMillis: Long)

    private val _offer = MutableStateFlow<Offer?>(null)
    val offer: StateFlow<Offer?> = _offer.asStateFlow()

    fun offer(walkId: Long, minutes: Int, nowMillis: Long) {
        _offer.value = Offer(walkId, minutes, nowMillis)
    }

    fun withdraw() {
        _offer.value = null
    }
}
