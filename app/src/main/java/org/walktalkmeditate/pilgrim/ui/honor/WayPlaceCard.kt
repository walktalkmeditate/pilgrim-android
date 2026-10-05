// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.ui.recordings.WaveformBar
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.HonorCardsUi
import org.walktalkmeditate.pilgrim.ui.walk.HonorPlaceCard

/** What the walker can do on a place card; each one the card's own button already counted as a touch. */
class WayPlaceCardActions(
    val onFly: () -> Unit,
    val onTouch: () -> Unit,
    val onDismiss: () -> Unit,
    val onPlayPause: () -> Unit,
    val onSeek: (Float) -> Unit,
    val onCycleRate: () -> Unit,
    val onPlayReply: () -> Unit,
    val onReply: () -> Unit,
    val onStopReply: () -> Unit,
    val onSit: (minutes: Int) -> Unit,
)

/**
 * iOS `WayPlaceCard` (`WayPlaceCard.swift:47-271@7c200bf`, parity spec E
 * §8): the header that flies the map to the place, the queue's dots, the ×,
 * then one body per kind. A sideways swipe past 80 dp dismisses it too.
 * Any tap on the card, and every control but the ×, touches it, so a voice
 * card the walker answered never retires on its own. Cards come and go
 * with no transition, as on iOS.
 */
@Composable
fun WayPlaceCard(
    card: HonorPlaceCard,
    units: UnitSystem,
    isRecordingReply: Boolean,
    actions: WayPlaceCardActions,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val dismissPx = with(density) { SWIPE_TO_DISMISS.toPx() }
    val fadePx = with(density) { SWIPE_FADE_DISTANCE.toPx() }
    val offset = remember(card.moment.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dragState = rememberDraggableState { delta -> scope.launch { offset.snapTo(offset.value + delta) } }
    val currentOnTouch by rememberUpdatedState(actions.onTouch)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(offset.value.roundToInt(), 0) }
            .alpha(1f - min(MAX_SWIPE_FADE, abs(offset.value) / fadePx))
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStopped = {
                    if (abs(offset.value) > dismissPx) {
                        actions.onDismiss()
                    } else {
                        offset.animateTo(0f, tween(durationMillis = SPRING_BACK_MS, easing = EaseOut))
                    }
                },
            )
            .touchesOnAnyTap { currentOnTouch() }
            .clip(RoundedCornerShape(CARD_CORNER))
            .background(pilgrimColors.parchmentSecondary)
            .padding(PilgrimSpacing.normal),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.weight(1f)) {
                CardHeaderButton(card, units, onFly = { actions.onTouch(); actions.onFly() })
            }
            Spacer(Modifier.width(PilgrimSpacing.small))
            if (card.pendingCount > 0) QueuePips(card.pendingCount)
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = TAP_TARGET, minHeight = TAP_TARGET)
                    .labelledButton(stringResource(R.string.honor_card_dismiss), actions.onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = pilgrimColors.fog,
                    modifier = Modifier.size(GLYPH_BODY),
                )
            }
        }
        when (val kind = card.moment.kind) {
            is WayMomentKind.Voice -> VoiceBody(card, kind, isRecordingReply, actions)
            is WayMomentKind.Photo -> WayPhotoPlate(photoUri = card.media?.photoUri, maxHeight = CARD_PHOTO_MAX_HEIGHT)
            is WayMomentKind.Rest -> Text(
                text = stringResource(R.string.honor_card_rest_body),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
            is WayMomentKind.Meditation -> SitRow(kind.minutes, actions)
            is WayMomentKind.Waypoint -> Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
                Text(
                    text = WayMomentCopy.placeCopy(LocalResources.current, card.moment, isStage = card.isStage),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                    maxLines = PLACE_COPY_LINES,
                )
                card.moment.sitMinutes?.takeIf { it > 0 }?.let { SitRow(it, actions) }
            }
        }
    }
}

/**
 * iOS `HonorCardHost` (`ActiveWalkView+Honor.swift:52-111@7c200bf`, parity
 * spec E §7): the arrival card while it is up, else the top place card,
 * each card with state of its own (iOS `.id(moment.id)`).
 * [actionsFor] builds the controls of the card on top.
 */
@Composable
fun HonorCardLayer(
    cards: HonorCardsUi,
    units: UnitSystem,
    replyingToMomentId: String?,
    isRecording: Boolean,
    actionsFor: (WayMoment) -> WayPlaceCardActions,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val arrival = cards.arrival
    val place = cards.place
    when {
        arrival != null -> HonorArrivalCard(summary = arrival, onContinue = onContinue, modifier = modifier)
        place != null -> key(place.moment.id) {
            WayPlaceCard(
                card = place,
                units = units,
                isRecordingReply = isRecording && replyingToMomentId == place.moment.id,
                actions = actionsFor(place.moment),
                modifier = modifier,
            )
        }
    }
}

/**
 * The header as one button whose label replaces everything it shows: the
 * kicker, distance, and street name are never read (pilgrim-ios #108,
 * matched), and "Back to where you are" follows any focus, not this card's.
 */
@Composable
private fun CardHeaderButton(card: HonorPlaceCard, units: UnitSystem, onFly: () -> Unit) {
    val resources = LocalResources.current
    val label = stringResource(if (card.isFocused) R.string.honor_card_back_to_you else R.string.honor_card_show_place)
    val subline = WayRelation.subline(
        distanceMeters = card.distanceMeters,
        place = card.moment.place,
        units = units,
        here = stringResource(R.string.honor_card_here),
        away = { resources.getString(R.string.honor_card_away, it) },
    )
    Box(modifier = Modifier.labelledButton(label, onFly)) {
        WayMomentCompactHeader(
            moment = card.moment,
            subline = subline,
            tick = card.tick,
            keepsEmptyKicker = card.keepsEmptyKicker,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** Up to four 5 dp dots, one per waiting card; TalkBack reads the whole count. */
@Composable
private fun QueuePips(pendingCount: Int) {
    val label = stringResource(R.string.honor_card_more_waiting, count(pendingCount))
    Row(
        modifier = Modifier
            .padding(top = PilgrimSpacing.small)
            .clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(min(pendingCount, MAX_PIPS)) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(pilgrimColors.stone.copy(alpha = 0.45f)),
            )
        }
    }
}

@Composable
private fun VoiceBody(
    card: HonorPlaceCard,
    kind: WayMomentKind.Voice,
    isRecordingReply: Boolean,
    actions: WayPlaceCardActions,
) {
    if (isRecordingReply) {
        RecordingReplyRow(actions.onStopReply)
        return
    }
    card.moment.transcriptLine?.let {
        Text(
            text = stringResource(R.string.honor_moment_transcript, it),
            style = pilgrimType.body,
            fontStyle = FontStyle.Italic,
            color = pilgrimColors.ink,
            maxLines = TRANSCRIPT_LINES,
        )
    }
    TransportRow(card, kind.duration, actions)
    ReplyRow(hasEarlierReply = card.media?.hasEarlierReply == true, actions)
}

@Composable
private fun TransportRow(card: HonorPlaceCard, durationSeconds: Double, actions: WayPlaceCardActions) {
    val playing = card.isPlaying && !card.isPaused
    val playLabel = stringResource(if (playing) R.string.honor_moment_pause else R.string.honor_moment_play)
    val speed = WayMomentCopy.speedLabel(card.rate)
    val speedLabel = stringResource(R.string.honor_moment_speed_a11y, speed)
    val progress = if (durationSeconds > 0) min(1.0, card.elapsedSeconds / durationSeconds).toFloat() else 0f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Icon(
            imageVector = if (playing) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
            contentDescription = null,
            tint = pilgrimColors.stone,
            modifier = Modifier
                .size(GLYPH_DISPLAY)
                .labelledButton(playLabel) { actions.onTouch(); actions.onPlayPause() },
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
            val waveform = card.media?.waveform
            if (waveform != null) {
                // iOS frames its 32 pt bars in a 28 pt slot, so they reach 2 pt past it each way.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WAVEFORM_HEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    WaveformBar(
                        samples = waveform,
                        progress = progress,
                        inactiveColor = pilgrimColors.fog.copy(alpha = 0.4f),
                        activeColor = pilgrimColors.stone,
                        onSeek = { actions.onTouch(); actions.onSeek(it) },
                        contentDescription = stringResource(R.string.honor_card_waveform_a11y),
                        accessibilityValue = stringResource(
                            R.string.honor_moment_position_value,
                            count((progress * PERCENT).roundToInt()),
                        ),
                        accessibilitySteps = WAVEFORM_ADJUST_STEPS,
                        modifier = Modifier
                            .fillMaxWidth()
                            .requiredHeight(WAVEFORM_BAR_HEIGHT),
                    )
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(WAVEFORM_HEIGHT)
                        .clip(RoundedCornerShape(4.dp))
                        .background(pilgrimColors.fog.copy(alpha = 0.15f)),
                )
            }
            Text(
                text = stringResource(
                    R.string.honor_card_clock,
                    WayMomentCopy.clock(card.elapsedSeconds),
                    WayMomentCopy.clock(durationSeconds),
                ),
                style = pilgrimType.caption.copy(fontFeatureSettings = "tnum"),
                color = pilgrimColors.fog,
            )
        }
        val fast = card.rate > 1f
        Box(
            modifier = Modifier
                .sizeIn(minWidth = TAP_TARGET, minHeight = TAP_TARGET)
                .labelledButton(speedLabel) { actions.onTouch(); actions.onCycleRate() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = speed,
                style = pilgrimType.caption,
                color = if (fast) pilgrimColors.parchment else pilgrimColors.stone,
                modifier = Modifier
                    .clearAndSetSemantics {}
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (fast) pilgrimColors.stone else pilgrimColors.stone.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * "reply here", or once a reply to this voice is filed under the Way,
 * "record again" (asking first) and "your reply" (parity spec E §8).
 */
@Composable
private fun ReplyRow(hasEarlierReply: Boolean, actions: WayPlaceCardActions) {
    var confirmReplace by rememberSaveable { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        if (!hasEarlierReply) {
            ReplyPill(
                title = stringResource(R.string.honor_card_reply_here),
                label = stringResource(R.string.honor_card_reply_here_a11y),
                onClick = { actions.onTouch(); actions.onReply() },
            )
        } else {
            ReplyPill(
                title = stringResource(R.string.honor_card_record_again),
                label = stringResource(R.string.honor_card_record_again_a11y),
                onClick = { actions.onTouch(); confirmReplace = true },
            )
            Spacer(Modifier.weight(1f))
            val yourReply = stringResource(R.string.honor_card_your_reply_a11y)
            Row(
                modifier = Modifier
                    .heightIn(min = TAP_TARGET)
                    .labelledButton(yourReply) { actions.onTouch(); actions.onPlayReply() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PlayCircle,
                    contentDescription = null,
                    tint = pilgrimColors.stone,
                    modifier = Modifier.size(GLYPH_CAPTION),
                )
                Text(
                    text = stringResource(R.string.honor_card_your_reply),
                    style = pilgrimType.caption,
                    color = pilgrimColors.stone,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(stringResource(R.string.honor_card_replace_title)) },
            confirmButton = {
                TextButton(onClick = { confirmReplace = false; actions.onReply() }) {
                    Text(stringResource(R.string.honor_card_replace), color = pilgrimColors.rust)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.honor_card_keep)) }
            },
            containerColor = pilgrimColors.parchment,
            titleContentColor = pilgrimColors.ink,
        )
    }
}

/** `mic` and the title in a stone capsule outlined at 0.5; the 44 dp height is the hit area. */
@Composable
private fun ReplyPill(title: String, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = TAP_TARGET)
            .labelledButton(label, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clearAndSetSemantics {}
                .border(1.dp, pilgrimColors.stone.copy(alpha = 0.5f), CircleShape)
                .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        ) {
            Icon(
                imageVector = Icons.Outlined.Mic,
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(GLYPH_CAPTION),
            )
            Text(text = title, style = pilgrimType.caption, color = pilgrimColors.stone)
        }
    }
}

/** While the walker answers this voice, the transport and reply rows give way to this one. */
@Composable
private fun RecordingReplyRow(onStopReply: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        RecordingPulse()
        Text(
            text = stringResource(R.string.honor_card_recording_reply),
            style = pilgrimType.body,
            color = pilgrimColors.ink,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.StopCircle,
            contentDescription = null,
            tint = pilgrimColors.rust,
            modifier = Modifier
                .size(GLYPH_DISPLAY)
                .labelledButton(stringResource(R.string.honor_card_stop_reply), onStopReply),
        )
    }
}

/** A 10 dp rust dot breathing between 0.85× at 0.6 and 1.25× at full, every 0.9 s; TalkBack never reads it. */
@Composable
private fun RecordingPulse() {
    val transition = rememberInfiniteTransition(label = "reply-pulse")
    val swell by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = EaseInOut), RepeatMode.Reverse),
        label = "reply-pulse-swell",
    )
    Box(
        Modifier
            .size(10.dp)
            .scale(0.85f + 0.4f * swell)
            .alpha(0.6f + 0.4f * swell)
            .clip(CircleShape)
            .background(pilgrimColors.rust)
            .clearAndSetSemantics {},
    )
}

/**
 * "Sit?" on a stone pill, then the soundscape line. The minutes feed only
 * the meditation screen's caption: nothing ends the sitting at them.
 */
@Composable
private fun SitRow(minutes: Int, actions: WayPlaceCardActions) {
    val label = stringResource(R.string.honor_card_sit_a11y, count(minutes))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(CARD_CORNER))
                .background(pilgrimColors.stone)
                .labelledButton(label) { actions.onTouch(); actions.onSit(minutes) }
                .padding(horizontal = PilgrimSpacing.big, vertical = PilgrimSpacing.small),
        ) {
            Text(
                text = stringResource(R.string.honor_card_sit),
                style = pilgrimType.button,
                color = pilgrimColors.parchment,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        Text(
            text = stringResource(R.string.honor_card_sit_caption),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}

/**
 * iOS's `.simultaneousGesture(TapGesture())`: a tap anywhere on the card
 * the card's own controls didn't take, so it never fires with a button's
 * tap or a drag.
 */
private fun Modifier.touchesOnAnyTap(onTouch: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        if (waitForUpOrCancellation(pass = PointerEventPass.Final) != null) onTouch()
    }
}

/** A button whose [label] is all TalkBack reads; its content is cleared or carries no semantics. */
internal fun Modifier.labelledButton(label: String, onClick: () -> Unit): Modifier =
    clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }

private fun count(n: Int): String = String.format(Locale.US, "%d", n)

private val SWIPE_TO_DISMISS = 80.dp
private val SWIPE_FADE_DISTANCE = 240.dp
private const val MAX_SWIPE_FADE = 0.6f
private const val SPRING_BACK_MS = 200
private val CARD_CORNER = 12.dp
private val TAP_TARGET = 44.dp

/** The SF symbol sizes the fonts give iOS's glyphs: body 17, caption 12, displayMedium 28. */
private val GLYPH_BODY = 17.dp
private val GLYPH_CAPTION = 12.dp
private val GLYPH_DISPLAY = 28.dp
private val WAVEFORM_HEIGHT = 28.dp
private val WAVEFORM_BAR_HEIGHT = 32.dp
private val CARD_PHOTO_MAX_HEIGHT = 110.dp
private const val MAX_PIPS = 4
private const val TRANSCRIPT_LINES = 2
private const val PLACE_COPY_LINES = 4
private const val PULSE_MS = 900
private const val PERCENT = 100

/** Nine steps, ten intervals: each TalkBack swipe moves the voice 10 %. */
private const val WAVEFORM_ADJUST_STEPS = 9
