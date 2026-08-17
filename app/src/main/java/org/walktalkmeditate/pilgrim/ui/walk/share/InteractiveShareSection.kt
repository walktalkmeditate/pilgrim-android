// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.share.TourRecordingKind
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

// ---- Magic values the parity spec explicitly marks as untokenized -------
// (docs/parity/2026-08-15-walk-share-interactive-port.md §3, cited per use).

/** UI-25: row opacity for an excluded-but-available recording. */
private const val ROW_EXCLUDED_ALPHA = 0.6f

/** UI-25: row opacity shared by both unavailable reasons AND (Android-original) [RecordingAvailability.Preparing] — see that type's doc. */
private const val ROW_UNAVAILABLE_ALPHA = 0.45f

/** UI-32: the kind chip's OWN opacity, nested inside (multiplying with) the row's own opacity in [TourRecordingRow] — do not flatten these into one value. */
private const val CHIP_EXCLUDED_ALPHA = 0.35f

/** UI-30: numerically equals `PilgrimOpacity.LIGHT` (0.12) but the spec's token_lookup for this finding is `—` — kept local per spec classification, not force-mapped to the token. */
private const val KIND_CHIP_BACKGROUND_ALPHA = 0.12f

private val KIND_CHIP_H_PADDING = 10.dp
private val KIND_CHIP_V_PADDING = 4.dp
private val KIND_CHIP_CORNER_RADIUS = 4.dp

/** UI-27/EDG-90: iOS's 44pt HIG minimum becomes Android's 48dp Material minimum — NOT a literal 44dp port. */
private val MIN_TAP_TARGET = 48.dp

/**
 * iOS `WalkShareViewModel.trimMeters` (`WalkShareViewModel.swift:36@3f9f9e8`) = 150.
 * The trim-toggle subtitle hardcodes "150 m" as a literal in Swift (spec UI-15/EDG-120
 * flag the manual-sync risk between this constant and that literal); Android
 * interpolates from this constant instead of repeating the number a second time.
 */
private const val TRIM_METERS = 150

/**
 * The uppercased, tracked micro-label used above every WalkShare section
 * ("Walk with me", "Share these details", "Reflection", "This walk lives
 * for"). iOS parity: `ShareSectionLabel` (`InteractiveShareSection.swift:94-103@3f9f9e8`)
 * — a single source [WalkShareScreen]'s own section headers forward to as
 * well, so the two files can't drift apart style-wise (UI-20).
 */
@Composable
internal fun ShareSectionLabel(text: String) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = pilgrimType.micro,
        color = pilgrimColors.fog,
        letterSpacing = 1.5.sp,
    )
}

/**
 * A recording row's resolved availability. [Available]/[AudioRemoved]/[TooLargeToCarry]
 * port iOS's `unavailableReason: String?` tri-state (`TourBuilder.swift:56-63@3f9f9e8`,
 * spec EDG-68/UI-74) as a closed type instead of an optional string.
 *
 * [Preparing] is Android-original — no iOS reference. iOS's `TourBuilder.candidates(for:)`
 * resolves every row's availability SYNCHRONOUSLY off disk before any row ever
 * renders (spec UI-22: "There is NO separate 'preparing' row state... a
 * spinner/loading row state does not exist at this pin and should not be
 * invented on Android" — true of the iOS PORT, not of Android's own pipeline).
 * Android resolves a recording's shareable size via an async WAV→AAC-LC
 * transcode ([org.walktalkmeditate.pilgrim.data.share.SharePrepStore.PrepState],
 * U4) — so between "Interactive turned on" and "this recording's transcode
 * finishes," a real third window exists on Android that iOS never has. This
 * case renders with the SAME dimmed, non-interactive, controls-hidden
 * treatment as [AudioRemoved]/[TooLargeToCarry] ([rowOpacity], [rowShowsControls]):
 * like them, neither the include-toggle nor the kind chip is actionable while
 * the outcome is still unknown. A transcode that terminates in
 * [org.walktalkmeditate.pilgrim.data.share.SharePrepStore.PrepState.Failed] maps
 * to [AudioRemoved] at this layer (U8) rather than a sixth row state — "no
 * usable audio" is the correct user-facing story either way.
 */
@Immutable
internal sealed interface RecordingAvailability {
    data class Available(val sizeBytes: Long) : RecordingAvailability
    data object AudioRemoved : RecordingAvailability
    data object TooLargeToCarry : RecordingAvailability

    /** Android-original — see [RecordingAvailability] doc. */
    data object Preparing : RecordingAvailability
}

/**
 * One recording row's UI state. Mirrors iOS `TourRecordingCandidate`
 * (`TourBuilder.swift:5-20@3f9f9e8`) field-for-field except: [durationSeconds]
 * is truncated to `Int` up front (iOS's `TourRecordingRow.durationLabel`
 * truncates a `Double` at render time — doing it in the state instead costs
 * nothing and keeps the row a pure function of already-display-ready data),
 * and [availability] folds iOS's optional `unavailableReason: String?` plus
 * the Android-original preparing window into one closed type — see
 * [RecordingAvailability].
 */
@Immutable
internal data class TourRecordingRowState(
    val id: Int,
    val durationSeconds: Int,
    val startEpochSeconds: Long,
    val transcriptionPreview: String?,
    val effectiveKind: TourRecordingKind,
    val includeInShare: Boolean,
    val availability: RecordingAvailability,
)

/**
 * [InteractiveShareSection]'s full render state. [totalsLabel] and
 * [validationErrorText] are pre-formatted by the caller (U8's ViewModel;
 * tests fake it) rather than derived here — both need domain-layer inputs
 * this section doesn't own ([totalsLabel] also folds in the sibling Photos
 * toggle's count, `WalkShareViewModel.swift:47-59@3f9f9e8`; [validationErrorText]
 * should come from the SAME `TourBuilder.validationError` call U8's
 * Share-button gate uses, so the copy and the gate can never disagree).
 * Everything else here is a pure function of [rows] and is computed locally
 * (see [hasAudibleVoices]) — no reason to push trivial, section-local
 * derivations up to a caller.
 */
@Immutable
internal data class InteractiveShareSectionState(
    val interactiveEnabled: Boolean = false,
    val rows: List<TourRecordingRowState> = emptyList(),
    val totalsLabel: String = "",
    val validationErrorText: String? = null,
    val trimEnabled: Boolean = true,
    val canTrim: Boolean = true,
    /** iOS `isShareInFlight` (`WalkShareView.swift:31-36@3f9f9e8`) — see [InteractiveShareSection]'s drift-hazard doc. */
    val inputLocked: Boolean = false,
)

/** UI-12: the "Voices will be audible..." notice needs a candidate BOTH included AND available — not just "any recordings exist". */
internal fun hasAudibleVoices(rows: List<TourRecordingRowState>): Boolean =
    rows.any { it.includeInShare && it.availability is RecordingAvailability.Available }

/** UI-25: row opacity — unavailable/preparing rows always dim to [ROW_UNAVAILABLE_ALPHA] regardless of their stored include flag. */
internal fun rowOpacity(row: TourRecordingRowState): Float = when (row.availability) {
    is RecordingAvailability.Available -> if (row.includeInShare) 1f else ROW_EXCLUDED_ALPHA
    RecordingAvailability.AudioRemoved,
    RecordingAvailability.TooLargeToCarry,
    RecordingAvailability.Preparing,
    -> ROW_UNAVAILABLE_ALPHA
}

/**
 * UI-32: the kind chip's OWN opacity — takes the row's include flag
 * directly (not the whole [TourRecordingRowState]; nothing else about the
 * row affects the chip's own opacity). Applied on top of (nested inside)
 * [rowOpacity]'s Modifier in [TourRecordingRow], so an excluded-but-available
 * row's chip renders at [ROW_EXCLUDED_ALPHA] × [CHIP_EXCLUDED_ALPHA] ≈ 0.21,
 * matching SwiftUI's multiplicative opacity compounding — never flatten
 * these into one pre-multiplied constant (a future change to either tier
 * would silently desync from the other).
 */
internal fun chipOpacity(includeInShare: Boolean): Float = if (includeInShare) 1f else CHIP_EXCLUDED_ALPHA

/** UI-24: both the include button and the kind chip are hidden entirely (not merely disabled) for any non-[RecordingAvailability.Available] row. */
internal fun rowShowsControls(row: TourRecordingRowState): Boolean = row.availability is RecordingAvailability.Available

/** iOS `TourRecordingRow.durationLabel` (`InteractiveShareSection.swift:119-122@3f9f9e8`): `%d:%02d`, minutes NOT zero-padded. */
internal fun formatRowDuration(seconds: Int): String =
    String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

private val rowStartTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withDecimalStyle(DecimalStyle.STANDARD)

/** iOS `TourRecordingRow.startLabel` (`InteractiveShareSection.swift:124-126@3f9f9e8`): `DateFormatter.timeStyle = .short`, locale-dependent (e.g. "9:41 AM"). */
internal fun formatRowStartTime(epochSeconds: Long): String =
    rowStartTimeFormatter
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))

/** iOS `TourRecordingRow.sizeLabel` (`InteractiveShareSection.swift:128-130@3f9f9e8`): float MB with one decimal — a DIFFERENT MB-rounding rule than `TourBuilder`'s cap-exceeded copy (EDG-70/EDG-121); do not unify. */
internal fun formatRowSizeMb(sizeBytes: Long): String =
    String.format(Locale.US, "%.1f MB", sizeBytes / 1_048_576.0)

/**
 * The Interactive toggle and its disclosure: recordings with per-item
 * include/kind controls, totals, trim. iOS parity: `InteractiveShareSection.swift@3f9f9e8`.
 *
 * Drift hazard (spec UI-56): iOS's `Group { ... }.disabled(isShareInFlight)`
 * freezes this whole section with NO visual dimming for its plain-styled
 * include/kind row controls — only the two real `Toggle`s (Interactive,
 * Trim) pick up SwiftUI's default disabled dimming. [state]'s
 * [InteractiveShareSectionState.inputLocked] is ported the same way here:
 * the [Switch]es use `enabled =`, which DOES dim (Compose's Switch mirrors
 * SwiftUI's Toggle default styling — correct parity); the row controls are
 * gated via plain `Modifier.clickable(enabled = ...)` with no Material
 * button wrapper, so they grey out NOTHING — also correct parity, not an
 * oversight.
 *
 * Motion scope cut: iOS wraps the recordings/trim disclosure and the
 * voices-warning notice in `.animation(.easeInOut(duration: 0.2), value:
 * interactiveEnabled)` (UI-18) — scoped so a checkbox tap does NOT
 * re-trigger it (UI-13's drift-risk note: "a literal Android port using a
 * single blanket animated-visibility flag would over-animate this
 * transition on every candidate checkbox tap"). Replicating that exact
 * single-trigger scoping is deferred rather than risk the over-animation
 * bug the spec explicitly warns about; this renders as plain conditional
 * content with no transition.
 */
@Composable
internal fun InteractiveShareSection(
    state: InteractiveShareSectionState,
    onInteractiveEnabledChange: (Boolean) -> Unit,
    onToggleRowInclude: (Int) -> Unit,
    onFlipRowKind: (Int) -> Unit,
    onTrimEnabledChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
        ShareSectionLabel(stringResource(R.string.share_interactive_section_label))

        InteractiveToggleRow(
            checked = state.interactiveEnabled,
            enabled = !state.inputLocked,
            onCheckedChange = onInteractiveEnabledChange,
        )

        if (state.interactiveEnabled) {
            if (state.rows.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
                    state.rows.forEach { row ->
                        TourRecordingRow(
                            row = row,
                            inputLocked = state.inputLocked,
                            onToggleInclude = { onToggleRowInclude(row.id) },
                            onFlipKind = { onFlipRowKind(row.id) },
                        )
                    }
                }
                Text(state.totalsLabel, style = pilgrimType.caption, color = pilgrimColors.fog)
                if (hasAudibleVoices(state.rows)) {
                    Text(
                        text = stringResource(R.string.share_interactive_voices_warning),
                        style = pilgrimType.caption,
                        color = pilgrimColors.fog,
                        modifier = Modifier.padding(horizontal = PilgrimSpacing.normal),
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.share_interactive_no_recordings),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                )
            }

            state.validationErrorText?.let { error ->
                Text(error, style = pilgrimType.caption, color = pilgrimColors.rust)
            }

            TrimToggleRow(
                checked = state.trimEnabled,
                enabled = !state.inputLocked && state.canTrim,
                canTrim = state.canTrim,
                onCheckedChange = onTrimEnabledChange,
            )
        }
    }
}

@Composable
private fun InteractiveToggleRow(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("interactive-toggle-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.share_interactive_toggle_title),
                style = pilgrimType.body,
                color = pilgrimColors.ink,
            )
            Text(
                text = stringResource(R.string.share_interactive_toggle_subtitle),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("interactive-toggle"),
            colors = SwitchDefaults.colors(
                checkedTrackColor = pilgrimColors.moss,
                checkedThumbColor = pilgrimColors.parchment,
            ),
        )
    }
}

@Composable
private fun TrimToggleRow(checked: Boolean, enabled: Boolean, canTrim: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("trim-toggle-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.share_interactive_trim_title),
                style = pilgrimType.body,
                color = pilgrimColors.ink,
            )
            Text(
                text = if (canTrim) {
                    stringResource(R.string.share_interactive_trim_subtitle_can_trim, TRIM_METERS)
                } else {
                    stringResource(R.string.share_interactive_trim_subtitle_too_short)
                },
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("trim-toggle"),
            colors = SwitchDefaults.colors(
                checkedTrackColor = pilgrimColors.moss,
                checkedThumbColor = pilgrimColors.parchment,
            ),
        )
    }
}

@Composable
private fun TourRecordingRow(
    row: TourRecordingRowState,
    inputLocked: Boolean,
    onToggleInclude: () -> Unit,
    onFlipKind: () -> Unit,
) {
    val displayIndex = row.id + 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tour-recording-row-${row.id}")
            .alpha(rowOpacity(row)),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rowShowsControls(row)) {
            IncludeButton(
                displayIndex = displayIndex,
                included = row.includeInShare,
                enabled = !inputLocked,
                onClick = onToggleInclude,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.share_interactive_row_title,
                    displayIndex,
                    formatRowDuration(row.durationSeconds),
                    formatRowStartTime(row.startEpochSeconds),
                ),
                style = pilgrimType.body,
                color = pilgrimColors.ink,
            )
            RowDetailLine(row)
        }

        if (rowShowsControls(row)) {
            KindChip(
                displayIndex = displayIndex,
                kind = row.effectiveKind,
                included = row.includeInShare,
                enabled = !inputLocked,
                onClick = onFlipKind,
            )
        }
    }
}

/** UI-22's row-state machine, plus the Android-original [RecordingAvailability.Preparing] branch. */
@Composable
private fun RowDetailLine(row: TourRecordingRowState) {
    when (val availability = row.availability) {
        RecordingAvailability.AudioRemoved ->
            Text(
                stringResource(R.string.share_interactive_reason_audio_removed),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )

        RecordingAvailability.TooLargeToCarry ->
            Text(
                stringResource(R.string.share_interactive_reason_too_large),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )

        RecordingAvailability.Preparing -> Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = pilgrimColors.fog)
            Text(stringResource(R.string.share_interactive_row_preparing), style = pilgrimType.caption, color = pilgrimColors.fog)
        }

        is RecordingAvailability.Available -> {
            val preview = row.transcriptionPreview?.trim()
            if (!preview.isNullOrEmpty()) {
                Text(preview, style = pilgrimType.caption, color = pilgrimColors.fog, maxLines = 1)
            }
            Text(formatRowSizeMb(availability.sizeBytes), style = pilgrimType.caption, color = pilgrimColors.fog)
        }
    }
}

/**
 * UI-27/EDG-90: the min-tap-target box is the CLICKABLE/semantics node
 * (`enabled`, [MIN_TAP_TARGET], testTag, and the caller's `semantics` all
 * live here); [content] draws the small VISUAL glyph/chip centered inside
 * it. Putting `defaultMinSize` on the SAME node as a `background`/`clip`
 * would stretch the visible pill to the full 48dp box instead of leaving
 * it tight around its content (iOS's `.frame(minWidth:44,...).contentShape(Rectangle())`
 * wraps the image the same way — the enlarged region is tappable, not drawn).
 */
@Composable
private fun MinTapTarget(
    testTag: String,
    enabled: Boolean,
    onClick: () -> Unit,
    semantics: SemanticsPropertyReceiver.() -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = MIN_TAP_TARGET, minHeight = MIN_TAP_TARGET)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag)
            .semantics(properties = semantics),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun IncludeButton(displayIndex: Int, included: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val includeLabel = stringResource(R.string.share_interactive_include_a11y_label, displayIndex)
    val includedValue = stringResource(R.string.share_interactive_include_a11y_value_included)
    val excludedValue = stringResource(R.string.share_interactive_include_a11y_value_excluded)
    val hint = stringResource(R.string.share_interactive_include_a11y_hint)
    MinTapTarget(
        testTag = "tour-recording-include-${displayIndex - 1}",
        enabled = enabled,
        onClick = onClick,
        semantics = {
            role = Role.Checkbox
            contentDescription = includeLabel
            stateDescription = if (included) includedValue else excludedValue
            onClick(label = hint) { onClick(); true }
        },
    ) {
        Icon(
            imageVector = if (included) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (included) pilgrimColors.moss else pilgrimColors.fog,
        )
    }
}

@Composable
private fun KindChip(displayIndex: Int, kind: TourRecordingKind, included: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val kindLabel = stringResource(
        if (kind == TourRecordingKind.SPOKEN) R.string.share_interactive_kind_voice else R.string.share_interactive_kind_ambience,
    )
    val a11yLabel = stringResource(R.string.share_interactive_kind_a11y_label, displayIndex, kindLabel)
    val hint = stringResource(R.string.share_interactive_kind_a11y_hint)
    MinTapTarget(
        testTag = "tour-recording-kind-${displayIndex - 1}",
        enabled = enabled,
        onClick = onClick,
        semantics = {
            role = Role.Button
            contentDescription = a11yLabel
            stateDescription = kindLabel
            onClick(label = hint) { onClick(); true }
        },
    ) {
        Text(
            text = kindLabel,
            style = pilgrimType.caption,
            color = pilgrimColors.stone,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .alpha(chipOpacity(included))
                .clip(RoundedCornerShape(KIND_CHIP_CORNER_RADIUS))
                .background(pilgrimColors.stone.copy(alpha = KIND_CHIP_BACKGROUND_ALPHA))
                .padding(horizontal = KIND_CHIP_H_PADDING, vertical = KIND_CHIP_V_PADDING),
        )
    }
}
