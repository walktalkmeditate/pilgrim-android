// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Localized long form ("May 21, 2026"), shared by the Share modal's
 * "Expires …" line ([WalkShareScreen]) and the Walk Summary's inline
 * shared-state block
 * ([org.walktalkmeditate.pilgrim.ui.walk.summary.WalkSharingBlock]).
 *
 * iOS parity: `WalkSharingButtons.expiryFormatter`
 * (`dateStyle = .long`, `WalkSharingButtons.swift:30-34@2ee1185`),
 * reused for both the modal's expiry line AND the summary's "Returns
 * to the trail on …" caption (`:224@2ee1185` uses the same
 * `expiryFormatter`).
 *
 * Digits forced to ASCII via `DecimalStyle.STANDARD` so Arabic /
 * Persian / Hindi locales don't mix non-ASCII digits into the
 * Latin-surround copy (Stage 6-B lesson).
 *
 * Extracted from the now-removed `WalkShareJourneyRow.kt` (Stage 8-A)
 * so both `ui.walk.share` and `ui.walk.summary` callers can reach it
 * without a cross-feature dependency on a single screen's file.
 */
private val expiryDateLongFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
        .withLocale(Locale.getDefault())
        .withDecimalStyle(DecimalStyle.STANDARD)

internal fun formatExpiryDateLong(epochMs: Long): String =
    expiryDateLongFormatter
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
