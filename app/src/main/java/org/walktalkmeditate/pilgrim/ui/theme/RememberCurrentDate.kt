// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.delay

/**
 * Live-updating `LocalDate.now()` used to key the seasonal palette
 * (see `pilgrimSeasonalColors`). Refreshes the held state at:
 *   1. composition entry (one-shot)
 *   2. every `ON_RESUME` (Activity returns to foreground)
 *   3. the next midnight boundary (LaunchedEffect schedules a delay to
 *      that moment + 1s buffer, then re-emits and re-keys the effect
 *      to wait for the FOLLOWING midnight)
 *
 * Capturing once in `setContent` (the previous approach) left the
 * palette pinned to yesterday's date for any long-lived Activity that
 * stayed resumed past midnight — a real on-device case for a user who
 * starts a walk before midnight and finishes after.
 */
@Composable
fun rememberCurrentDate(): MutableState<LocalDate> {
    val state = remember { mutableStateOf(LocalDate.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.value = LocalDate.now()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Re-key on the current date so when midnight ticks the date changes
    // and this effect restarts, scheduling a delay to the next midnight.
    LaunchedEffect(state.value) {
        val now = LocalDateTime.now()
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
        val millisUntil = Duration.between(now, nextMidnight).toMillis() + 1_000L
        if (millisUntil > 0L) delay(millisUntil)
        state.value = LocalDate.now()
    }
    return state
}
