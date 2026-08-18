// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.share.CachedShare
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.util.ShareIntents
import org.walktalkmeditate.pilgrim.ui.walk.share.formatExpiryDateLong

private const val KANJI_WATERMARK_SIZE_SP = 120
private const val CHEVRON_ALPHA = 0.4f
internal const val COPY_TOAST_DURATION_MS = 2_000L

/**
 * iOS parity `WalkSharingButtons.activeShareSection(_:)`
 * (`WalkSharingButtons.swift:188-279@2ee1185`) — the Walk Summary's
 * inline replacement for the plain "Share Journey" button/footer once
 * a non-expired cached share exists for this walk (issue #222).
 * Rendered by [WalkSharingButtons] in place of `JourneyFooter`; the
 * parchmentSecondary card background + corner radius already belong
 * to the caller, so this composable paints no background of its own
 * (matches the Swift `ZStack` sitting directly inside the shared
 * `VStack` at `:36-46@2ee1185`, no extra container).
 */
@Composable
internal fun WalkSharingBlock(
    cachedShare: CachedShare,
    onOpenJourney: () -> Unit,
    onEngaged: () -> Unit,
    modifier: Modifier = Modifier,
    nowEpochMs: Long = Instant.now().toEpochMilli(),
) {
    val watermarkAlpha = watermarkOpacity(
        shareDateEpochMs = cachedShare.shareDateEpochMs,
        expiryEpochMs = cachedShare.expiryEpochMs,
        nowEpochMs = nowEpochMs,
    )

    Box(
        modifier = modifier.testTag("share-active-block"),
        contentAlignment = Alignment.Center,
    ) {
        // Watermark kanji BEHIND the content, iOS ZStack ordering
        // (`:189-194@2ee1185`). `FontFamily.Default` (not Pilgrim's
        // cormorantGaramond serif) matches this codebase's established
        // kanji-watermark convention — `WalkShareScreen.kt`'s
        // `ExpiryButton` (same `ExpiryOption.kanji`, "Cormorant
        // Garamond has no CJK coverage") and `CairnDetailSheet.kt`'s
        // tier-kanji hero (same 120sp / Thin / stone-alpha shape) both
        // deliberately choose the system default over the custom serif
        // for the same reason.
        cachedShare.expiryOption?.kanji?.let { kanji ->
            Text(
                text = kanji,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Thin,
                fontSize = KANJI_WATERMARK_SIZE_SP.sp,
                color = pilgrimColors.stone.copy(alpha = watermarkAlpha),
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("share-active-kanji"),
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        ) {
            // Expiry-option label, e.g. "1 CYCLE" — `:196-202@2ee1185`.
            // `.stone` + 1.5pt tracking (NOT `ShareSectionLabel`'s fog —
            // that shared component is for section headers elsewhere in
            // the Share modal and uses a different color).
            cachedShare.expiryOption?.label?.let { label ->
                Text(
                    text = label.uppercase(Locale.ROOT),
                    style = pilgrimType.micro,
                    color = pilgrimColors.stone,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.testTag("share-active-label"),
                )
            }

            UrlRow(url = cachedShare.url, onClick = onOpenJourney)

            // "Returns to the trail on {date}" — `:224-227@2ee1185`.
            Text(
                text = stringResource(
                    R.string.share_journey_returns_on,
                    formatExpiryDateLong(cachedShare.expiryEpochMs),
                ),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.testTag("share-active-returns"),
            )

            CopyShareRow(
                url = cachedShare.url,
                onEngaged = onEngaged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PilgrimSpacing.small),
            )
        }
    }
}

/**
 * The tappable URL row: truncation-middle text + trailing chevron,
 * opens the journey sheet (`:204-223@2ee1185`). Accessibility mirrors
 * `ShareStatusSection`'s own "open the shared page" row
 * (`share-status-open-preview`) — `contentDescription` for Swift's
 * `.accessibilityLabel("View shared walk page")`, a custom click
 * action label for `.accessibilityHint(...)`, both already-shipped
 * string resources reused verbatim rather than duplicated. The
 * chevron is `.semantics { invisibleToUser() }`, mirroring Swift's
 * `.accessibilityHidden(true)` (`:216@2ee1185`).
 */
@Composable
private fun UrlRow(url: String, onClick: () -> Unit) {
    val label = stringResource(R.string.share_status_view_page_a11y_label)
    val hint = stringResource(R.string.share_status_view_page_a11y_hint)
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                onClick(label = hint) { onClick(); true }
            }
            .testTag("share-active-url"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = url,
            style = pilgrimType.caption,
            color = pilgrimColors.stone,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = pilgrimColors.stone.copy(alpha = CHEVRON_ALPHA),
            modifier = Modifier
                .size(14.dp)
                .semantics { invisibleToUser() },
        )
    }
}

/**
 * Copy / Share button row (`:229-276@2ee1185`). Copy writes the
 * platform clipboard directly (Compose-idiomatic `LocalClipboardManager`,
 * matching `VoiceRecordingsSection`/`WalkLightReadingCard`/
 * `TranscriptionDisplay`) and flips its own label/icon to "Copied" via
 * [CopyToastState] (the [onEngaged] callback mirrors Swift's
 * `onShare?()`, fired on every Copy tap AND after a successful Share
 * dispatch — `:231,258-259@2ee1185` — matching the existing
 * `onWalkJourneyShare = { …; viewModel.markCurrentWalkShared() }`
 * wiring pattern for the plain button).
 */
@Composable
private fun CopyShareRow(url: String, onEngaged: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val copyToast = remember { CopyToastState() }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        OutlinedButton(
            onClick = {
                onEngaged()
                clipboard.setText(AnnotatedString(url))
                copyToast.trigger(scope)
            },
            modifier = Modifier
                .weight(1f)
                .testTag("share-active-copy"),
        ) {
            Icon(
                imageVector = if (copyToast.visible) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(PilgrimSpacing.small))
            Text(
                text = stringResource(
                    if (copyToast.visible) {
                        R.string.share_journey_copied
                    } else {
                        R.string.share_journey_copy
                    },
                ),
                style = pilgrimType.button,
                maxLines = 1,
            )
        }
        OutlinedButton(
            onClick = {
                ShareIntents.shareUrl(context, url)
                onEngaged()
            },
            modifier = Modifier
                .weight(1f)
                .testTag("share-active-share"),
        ) {
            Icon(
                imageVector = Icons.Outlined.IosShare,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(PilgrimSpacing.small))
            Text(
                text = stringResource(R.string.share_journey_share),
                style = pilgrimType.button,
                maxLines = 1,
            )
        }
    }
}

/**
 * Copy-toast generation guard — iOS parity `copiedToastGeneration`
 * (`WalkSharingButtons.swift:230-239@2ee1185`):
 * ```swift
 * copiedToastGeneration += 1
 * let gen = copiedToastGeneration
 * showCopiedToast = true
 * Task {
 *     try? await Task.sleep(for: .seconds(2))
 *     if copiedToastGeneration == gen { showCopiedToast = false }
 * }
 * ```
 * A second [trigger] call while the toast is showing bumps the
 * generation so the FIRST call's delayed reset (still in flight)
 * observes a stale generation and no-ops — the toast stays lit
 * through the SECOND call's own window instead of flickering off
 * partway through it. Kept as a small standalone state holder (not
 * inlined into the composable) so the timing behavior is testable
 * with `kotlinx.coroutines.test` virtual time, decoupled from
 * Compose's real-time recomposition clock — see
 * `WalkSharingBlockLogicTest`.
 */
internal class CopyToastState {
    var visible: Boolean by mutableStateOf(false)
        private set
    private var generation: Int by mutableIntStateOf(0)

    fun trigger(scope: CoroutineScope, durationMs: Long = COPY_TOAST_DURATION_MS) {
        generation += 1
        val thisGeneration = generation
        visible = true
        scope.launch {
            delay(durationMs)
            if (generation == thisGeneration) visible = false
        }
    }
}

/**
 * iOS parity `watermarkOpacity(_:)`
 * (`WalkSharingButtons.swift:281-288@2ee1185`), ported line-for-line:
 * ```swift
 * guard let shareDate = cached.shareDate else { return 0.05 }
 * let total = cached.expiry.timeIntervalSince(shareDate)
 * guard total > 0 else { return 0.025 }
 * let elapsed = Date().timeIntervalSince(shareDate)
 * let fraction = min(max(elapsed / total, 0), 1)
 * return 0.07 - (fraction * 0.045)
 * ```
 * [shareDateEpochMs] is nullable to mirror Swift's `Date?` even though
 * the current Android [CachedShare.shareDateEpochMs] field is
 * non-null — every record `CachedShareStore` persists carries a real
 * value (the JSON field has no default, so decode fails without one).
 * The null branch is therefore unreached via the store today but kept
 * for exact-formula parity and the defensive contract the Swift
 * signature makes.
 *
 * NOTE on the floor: at `fraction == 1` (now == expiry) this returns
 * **0.025**, not 0.05 — 0.05 is only the null-`shareDate` fallback.
 * `0.07 - (1 * 0.045) == 0.025` is what the Swift source actually
 * computes at expiry.
 */
internal fun watermarkOpacity(
    shareDateEpochMs: Long?,
    expiryEpochMs: Long,
    nowEpochMs: Long,
): Float {
    if (shareDateEpochMs == null) return 0.05f
    val total = expiryEpochMs - shareDateEpochMs
    if (total <= 0) return 0.025f
    val elapsed = nowEpochMs - shareDateEpochMs
    val fraction = (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    return 0.07f - (fraction * 0.045f)
}
