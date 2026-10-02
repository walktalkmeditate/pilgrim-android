// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.ui.recordings.WaveformBar
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * What the preview shows of a voice's player. Null [waveform] is the
 * placeholder bar, before the samples are read.
 */
class WayVoicePreview(
    val isPlaying: Boolean,
    val positionSeconds: Double,
    val totalSeconds: Double,
    val speed: Float,
    val waveform: FloatArray?,
) {
    val progress: Float
        get() = if (totalSeconds > 0) (positionSeconds / totalSeconds).toFloat().coerceIn(0f, 1f) else 0f
}

/**
 * iOS `WayMomentPreview` (`WayMomentPreview.swift:25-174@7c200bf`, parity
 * spec F §14) as a sheet over the overview, with its drag handle and no
 * title bar. Playback stops when it closes ([onDismiss]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WayMomentPreviewSheet(
    way: Way,
    moment: WayMoment,
    units: UnitSystem,
    voice: WayVoicePreview?,
    photoUri: String?,
    onTogglePlay: () -> Unit,
    onCycleSpeed: () -> Unit,
    onSeek: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = pilgrimColors.parchment,
    ) {
        WayMomentPreviewContent(
            way = way,
            moment = moment,
            units = units,
            voice = voice,
            photoUri = photoUri,
            onTogglePlay = onTogglePlay,
            onCycleSpeed = onCycleSpeed,
            onSeek = onSeek,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

/**
 * [voice] is null for a voice with no local file, and for every other
 * kind. [photoUri] is null for a photo not on the phone, which shows the
 * plate's parchment stand-in above the same caption (S4 §9.3).
 */
@Composable
fun WayMomentPreviewContent(
    way: Way,
    moment: WayMoment,
    units: UnitSystem,
    voice: WayVoicePreview?,
    photoUri: String?,
    onTogglePlay: () -> Unit,
    onCycleSpeed: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PilgrimSpacing.normal),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        WayMomentHeader(way = way, moment = moment, units = units)
        when (val kind = moment.kind) {
            is WayMomentKind.Voice -> VoiceBody(moment, kind, voice, onTogglePlay, onCycleSpeed, onSeek)
            is WayMomentKind.Photo -> Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
                WayPhotoPlate(photoUri = photoUri, maxHeight = PREVIEW_PHOTO_MAX_HEIGHT)
                Caption(stringResource(R.string.honor_moment_photo_caption))
            }
            is WayMomentKind.Waypoint -> Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
                Body(WayMomentCopy.placeCopy(resources, moment))
                val sitMinutes = moment.sitMinutes
                if (sitMinutes != null && sitMinutes > 0) {
                    Caption(stringResource(R.string.honor_moment_waypoint_sit_offer, count(sitMinutes)))
                } else {
                    Caption(stringResource(R.string.honor_moment_waypoint_rises))
                }
            }
            is WayMomentKind.Rest -> Body(stringResource(R.string.honor_moment_rest_body))
            is WayMomentKind.Meditation -> Body(stringResource(R.string.honor_moment_sit_body))
        }
    }
}

@Composable
private fun VoiceBody(
    moment: WayMoment,
    kind: WayMomentKind.Voice,
    voice: WayVoicePreview?,
    onTogglePlay: () -> Unit,
    onCycleSpeed: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    val ambient = kind.kind == VoiceKind.AMBIENT
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
        moment.transcript?.let {
            Text(
                text = stringResource(R.string.honor_moment_transcript, it),
                style = pilgrimType.body,
                fontStyle = FontStyle.Italic,
                color = pilgrimColors.ink,
                modifier = Modifier.padding(bottom = PilgrimSpacing.xs),
            )
        }
        if (voice != null) {
            VoicePlayer(kind, ambient, voice, onTogglePlay, onCycleSpeed, onSeek)
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
            ) {
                WaveformSlashGlyph(tint = pilgrimColors.fog)
                Text(
                    text = stringResource(R.string.honor_moment_voice_missing),
                    style = pilgrimType.body,
                    color = pilgrimColors.fog,
                )
            }
        }
        Caption(
            stringResource(if (ambient) R.string.honor_moment_ambient_footer else R.string.honor_moment_spoken_footer),
        )
    }
}

@Composable
private fun VoicePlayer(
    kind: WayMomentKind.Voice,
    ambient: Boolean,
    voice: WayVoicePreview,
    onTogglePlay: () -> Unit,
    onCycleSpeed: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    val playLabel = stringResource(if (voice.isPlaying) R.string.honor_moment_pause else R.string.honor_moment_play)
    val speedLabel = WayMomentCopy.speedLabel(voice.speed)
    val speedDescription = stringResource(R.string.honor_moment_speed_a11y, speedLabel)
    val fast = voice.speed > 1f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Box(
            modifier = Modifier
                .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                .clickable(role = Role.Button, onClick = onTogglePlay)
                .semantics { contentDescription = playLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (voice.isPlaying) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(34.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(
                    if (ambient) R.string.honor_moment_as_it_sounded else R.string.honor_moment_in_their_voice,
                ),
                style = pilgrimType.body,
                color = pilgrimColors.ink,
            )
            Text(text = WayMomentCopy.clock(kind.duration), style = pilgrimType.caption, color = pilgrimColors.fog)
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                .clickable(role = Role.Button, onClick = onCycleSpeed)
                .semantics { contentDescription = speedDescription },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = speedLabel,
                style = pilgrimType.caption,
                color = if (fast) pilgrimColors.parchment else pilgrimColors.stone,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (fast) pilgrimColors.stone else pilgrimColors.stone.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
    val waveform = voice.waveform
    if (waveform != null) {
        val percent = (voice.progress * 100).roundToInt()
        WaveformBar(
            samples = waveform,
            progress = voice.progress,
            inactiveColor = pilgrimColors.fog.copy(alpha = 0.4f),
            activeColor = pilgrimColors.stone,
            onSeek = onSeek,
            contentDescription = stringResource(R.string.honor_moment_position),
            accessibilityValue = stringResource(R.string.honor_moment_position_value, count(percent)),
            accessibilitySteps = WAVEFORM_ADJUST_STEPS,
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(pilgrimColors.fog.copy(alpha = 0.15f)),
        )
    }
    // iOS `.monospacedDigit()`, so the ticking clock doesn't jitter.
    val clockStyle = pilgrimType.caption.copy(fontFeatureSettings = "tnum")
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = WayMomentCopy.clock(voice.positionSeconds), style = clockStyle, color = pilgrimColors.fog)
        Spacer(Modifier.weight(1f))
        Text(
            text = WayMomentCopy.clock(if (voice.totalSeconds > 0) voice.totalSeconds else kind.duration),
            style = clockStyle,
            color = pilgrimColors.fog,
        )
    }
}

/**
 * iOS `waveform.slash`: the waveform stand-in with a slash through it,
 * cut clear of the bars as the system's "off" glyphs are.
 */
@Composable
private fun WaveformSlashGlyph(tint: Color) {
    Icon(
        imageVector = Icons.Outlined.GraphicEq,
        contentDescription = WayMomentCopy.spokenSymbolName(MISSING_VOICE_SYMBOL),
        tint = tint,
        modifier = Modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                val start = Offset(size.width * SLASH_INSET, size.height * SLASH_INSET)
                val end = Offset(size.width * (1 - SLASH_INSET), size.height * (1 - SLASH_INSET))
                drawLine(Color.Black, start, end, strokeWidth = 4.dp.toPx(), blendMode = BlendMode.Clear)
                drawLine(tint, start, end, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            },
    )
}

@Composable
private fun Body(text: String) {
    Text(text = text, style = pilgrimType.body, color = pilgrimColors.ink)
}

@Composable
private fun Caption(text: String) {
    Text(text = text, style = pilgrimType.caption, color = pilgrimColors.fog)
}

private fun count(n: Int): String = String.format(Locale.US, "%d", n)

/** Nine steps, ten intervals: each TalkBack swipe moves the voice 10 %. */
private const val WAVEFORM_ADJUST_STEPS = 9

/** iOS `WayPhotoPlate(maxHeight: 360)` in the preview. */
private val PREVIEW_PHOTO_MAX_HEIGHT = 360.dp

private const val MISSING_VOICE_SYMBOL = "waveform.slash"

/** Where the slash starts and ends, as a fraction of the glyph's size in from each corner. */
private const val SLASH_INSET = 0.12f
