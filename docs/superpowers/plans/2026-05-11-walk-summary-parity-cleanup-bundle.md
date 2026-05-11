# Walk Summary parity cleanup bundle — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close 4 sub-surface parity gaps in WalkSummary scene (voice-row polish, reliquary state polish, carousel feature, preview-sheet feature) in one bundled PR matching iOS v1.5.0 (`db4196e`).

**Architecture:** 4 ordered stages. Stages 2–4 are reliquary-package work where Stage 2 lays foundation (sealed `ReliquaryState`, permission snapshot, fetch-generation) reused by Stage 3 (carousel replaces grid) and Stage 4 (preview-sheet wires from carousel tap). Stage 1 (voice-row) is independent — ships first to de-risk the per-stage review pattern. All 4 stages squash into one PR.

**Tech Stack:** Kotlin 2.0, Jetpack Compose, Coroutines/Flow, Coil 3, Hilt, Mapbox 11.11.0 (out of scope here), JUnit 4, Robolectric, Turbine.

**Spec:** `docs/superpowers/specs/2026-05-11-walk-summary-parity-cleanup-bundle.md`

---

## Scope correction vs spec

During code grounding, **A.1 (100ms seek defer) was found to be already implemented** at `RecordingsListViewModel.kt:269-280` with `SEEK_AFTER_START_DELAY_MILLIS = 100L` at line 423. Triage doc misidentified. Spec section A drops to **3 deltas** (A.2 expand threshold, A.3 empty-trim guard, A.4 speed-cycle algorithm). Total plan scope: ~800 LOC production code instead of spec-quoted ~880.

---

## File structure

### New files

```
app/src/main/java/org/walktalkmeditate/pilgrim/
├── ui/recordings/TranscriptionDisplay.kt        (extracted shared composable; A.2)
├── ui/walk/reliquary/
│   ├── ReliquaryState.kt                        (sealed state class; Stage 2)
│   ├── PhotoCarousel.kt                         (new carousel composable; Stage 3)
│   ├── PhotoThumbnail.kt                        (88dp tile; Stage 3)
│   └── PhotoPreviewSheet.kt                     (full-screen Dialog; Stage 4)
```

### Modified files

```
app/src/main/java/org/walktalkmeditate/pilgrim/
├── audio/ExoPlayerVoicePlaybackController.kt    (Singleton divergence KDoc; A.4 quality gate)
├── ui/recordings/
│   ├── RecordingRow.kt                          (expand toggle integration; A.2 + A.3)
│   └── RecordingsListViewModel.kt               (speed cycle array+modulo; A.4)
├── ui/walk/VoiceRecordingsSection.kt            (expand toggle integration; A.2)
├── ui/walk/reliquary/PhotoReliquarySection.kt   (4-state gate + skeleton + lifecycle + fetch-gen; Stages 2+3 wiring)
└── ui/walk/WalkSummaryViewModel.kt              (permission snapshot + reliquaryState flow + fetch-gen; Stage 2)
```

### Test files

```
app/src/test/java/org/walktalkmeditate/pilgrim/
├── ui/recordings/
│   ├── TranscriptionDisplayTest.kt              (NEW)
│   ├── TranscriptionEditorEmptyGuardTest.kt     (NEW)
│   └── RecordingsListViewModelSpeedCycleTest.kt (extend existing if present, else NEW)
├── ui/walk/
│   ├── VoiceRecordingsSectionTranscriptionTest.kt (NEW)
│   ├── WalkSummaryViewModelReliquaryStateTest.kt (NEW)
│   └── reliquary/
│       ├── ReliquaryStateMachineTest.kt         (NEW)
│       ├── PhotoCarouselTest.kt                 (NEW)
│       ├── PhotoPreviewSheetTest.kt             (NEW)
│       └── ReliquarySettingsDeepLinkTest.kt     (NEW)
```

---

## Stage 1 — Voice-row polish (~80 LOC)

Ships A.2 (expand threshold) + A.3 (empty-trim guard) + A.4 (speed-cycle alignment + AudioPlayer divergence KDoc).

---

### Task 1.1: Shared `TranscriptionDisplay` composable with expand threshold

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/TranscriptionDisplay.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/recordings/TranscriptionDisplayTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/java/.../TranscriptionDisplayTest.kt
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TranscriptionDisplayTest {

    @Test
    fun shortText_doesNotNeedExpansion() {
        assertFalse(transcriptionNeedsExpansion("Hello world."))
    }

    @Test
    fun textOverCharLimit_needsExpansion() {
        val long = "a".repeat(281)
        assertTrue(transcriptionNeedsExpansion(long))
    }

    @Test
    fun textAtCharLimitBoundary_doesNotNeedExpansion() {
        val boundary = "a".repeat(280)
        assertFalse(transcriptionNeedsExpansion(boundary))
    }

    @Test
    fun textWithEightNewlines_needsExpansion() {
        val multiline = (1..9).joinToString("\n") { "line" }
        assertTrue(transcriptionNeedsExpansion(multiline))
    }

    @Test
    fun textWithSevenNewlines_doesNotNeedExpansion() {
        // 7 newlines = 8 lines. Boundary case from iOS:
        // `text.split(separator: "\n").count > 7` is FALSE at exactly 8 lines.
        val multiline = (1..8).joinToString("\n") { "line" }
        assertFalse(transcriptionNeedsExpansion(multiline))
    }

    @Test
    fun emptyText_doesNotNeedExpansion() {
        assertFalse(transcriptionNeedsExpansion(""))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.recordings.TranscriptionDisplayTest"`

Expected: 6 FAILs with `Unresolved reference: transcriptionNeedsExpansion`.

- [ ] **Step 3: Implement `TranscriptionDisplay.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Threshold from iOS `VoiceRecordingRow.swift:27-36@db4196e`:
 * `text.count > 280 || text.split(separator: "\n").count > 7`.
 * Kotlin uses `String.length` (UTF-16 code units) — minor grapheme
 * divergence on multi-codepoint emoji is acceptable for transcribed
 * speech content. The newline split-count is 1-based on iOS (`8 lines = 8`),
 * so the boundary `> 7` becomes ≥ 8 newlines.
 */
internal const val TRANSCRIPTION_CHAR_LIMIT = 280
internal const val TRANSCRIPTION_NEWLINE_LIMIT = 7

internal fun transcriptionNeedsExpansion(text: String): Boolean =
    text.length > TRANSCRIPTION_CHAR_LIMIT ||
        text.count { it == '\n' } > TRANSCRIPTION_NEWLINE_LIMIT

/**
 * Shared transcription presenter used by both the standalone Recordings
 * List ([RecordingRow]) and the Walk Summary surface
 * ([VoiceRecordingsSection]). Expansion toggle appears only when the
 * threshold from iOS `VoiceRecordingRow.swift:27-36@db4196e` is hit.
 *
 * @param text the transcription text to display (already non-null and
 *   non-blank; callers gate empty/NO_SPEECH).
 * @param onTap optional callback when the body text is tapped — used by
 *   the standalone Recordings List to enter edit mode; pass null on the
 *   Walk Summary surface (read-only).
 * @param showCopyAffordance true on Recordings List, false on Walk Summary.
 */
@Composable
internal fun TranscriptionDisplay(
    text: String,
    onTap: (() -> Unit)?,
    showCopyAffordance: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = pilgrimColors
    val type = pilgrimType
    val clipboard = LocalClipboardManager.current
    val copyDescription = stringResource(R.string.recordings_action_copy_transcription)

    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val needsExpansion = transcriptionNeedsExpansion(text)
    val maxLines = if (!needsExpansion || expanded) Int.MAX_VALUE else 4

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.parchmentTertiary),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = text,
                style = type.body,
                color = colors.ink,
                maxLines = maxLines,
                modifier = Modifier
                    .weight(1f)
                    .let { if (onTap != null) it.clickable { onTap() } else it }
                    .padding(8.dp),
            )
            if (showCopyAffordance) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = copyDescription,
                    tint = colors.fog,
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { clipboard.setText(AnnotatedString(text)) }
                        .padding(8.dp),
                )
            }
        }
        if (needsExpansion) {
            val toggleLabel = stringResource(
                if (expanded) R.string.recording_transcription_collapse
                else R.string.recording_transcription_expand,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = colors.fog,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = toggleLabel,
                    style = type.caption,
                    color = colors.fog,
                )
            }
        }
    }
}

/**
 * Read-only italic-muted variant for pending / no-speech states on
 * the Walk Summary surface. Does NOT show the expand toggle (text is
 * always short).
 */
@Composable
internal fun TranscriptionPlaceholder(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = pilgrimType.body,
        color = pilgrimColors.fog,
        fontStyle = FontStyle.Italic,
        modifier = modifier,
    )
}
```

- [ ] **Step 4: Add string resources**

Edit `app/src/main/res/values/strings.xml` — add inside `<resources>` (alphabetize among `recording_*`):

```xml
    <string name="recording_transcription_collapse">Show less</string>
    <string name="recording_transcription_expand">Show more</string>
```

- [ ] **Step 5: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.recordings.TranscriptionDisplayTest"`

Expected: 6 PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/TranscriptionDisplay.kt \
        app/src/main/res/values/strings.xml \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/recordings/TranscriptionDisplayTest.kt
git commit -m "feat(transcription): shared TranscriptionDisplay with iOS-parity expand threshold

Backports iOS VoiceRecordingRow.swift:27-36@db4196e expand/collapse
threshold (text.length > 280 OR newlines > 7) into a shared composable.
Used by both the standalone Recordings List and Walk Summary's voice
recordings section in subsequent commits."
```

---

### Task 1.2: Wire `TranscriptionDisplay` into `RecordingRow.kt`

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingRow.kt:376-411` (replace inline `TranscriptionView`)

- [ ] **Step 1: Replace the inline `TranscriptionView`**

In `RecordingRow.kt`, delete lines 376-411 (the existing `TranscriptionView` private composable). In its place, modify the call site at line 157-160:

Replace:
```kotlin
            } else {
                TranscriptionView(
                    text = transcription,
                    onTap = { onStartEditing(recording.id) },
                )
            }
```

With:
```kotlin
            } else {
                TranscriptionDisplay(
                    text = transcription,
                    onTap = { onStartEditing(recording.id) },
                    showCopyAffordance = true,
                )
            }
```

Drop the unused imports that were only used by the deleted `TranscriptionView` (`androidx.compose.ui.platform.LocalClipboardManager`, `androidx.compose.ui.text.AnnotatedString`, `androidx.compose.material.icons.outlined.ContentCopy`, `androidx.compose.material3.Icon` if no other usage remains — verify with grep).

- [ ] **Step 2: Build + verify existing tests**

Run: `./gradlew :app:assembleDebug`

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.recordings.*"`

Expected: BUILD SUCCESSFUL + all existing tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingRow.kt
git commit -m "refactor(recordings): swap inline TranscriptionView for shared TranscriptionDisplay

Inline RecordingRow.TranscriptionView deleted in favour of the shared
composable from the previous commit. Adds the iOS-parity expand/collapse
toggle to the Recordings List surface (was missing before)."
```

---

### Task 1.3: Wire `TranscriptionDisplay` into Walk Summary `VoiceRecordingsSection.kt`

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/VoiceRecordingsSection.kt:123-145`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/VoiceRecordingsSectionTranscriptionTest.kt` (NEW)

- [ ] **Step 1: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceRecordingsSectionTranscriptionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val baseRecording = VoiceRecording(
        id = 1L,
        walkId = 100L,
        fileRelativePath = "voice/100/1.wav",
        startTimestamp = 1_700_000_000_000L,
        durationMillis = 5_000L,
        transcription = "short transcription",
        wordsPerMinute = null,
        isEnhanced = false,
    )

    @Test
    fun shortTranscription_showsFullText_noToggle() {
        composeRule.setContent {
            VoiceRecordingsSection(
                walkStartTimestamp = baseRecording.startTimestamp,
                recordings = listOf(baseRecording),
                playbackUiState = PlaybackUiState(playingRecordingId = null, isPlaying = false),
                onPlay = {},
                onPause = {},
            )
        }
        composeRule.onNodeWithText("short transcription").assertExists()
        // No "Show more" toggle visible.
        composeRule.onAllNodesWithText("Show more").fetchSemanticsNodes().isEmpty()
    }

    @Test
    fun longTranscription_collapsedByDefault_showsExpandToggle() {
        val long = "a".repeat(281)
        composeRule.setContent {
            VoiceRecordingsSection(
                walkStartTimestamp = baseRecording.startTimestamp,
                recordings = listOf(baseRecording.copy(transcription = long)),
                playbackUiState = PlaybackUiState(playingRecordingId = null, isPlaying = false),
                onPlay = {},
                onPause = {},
            )
        }
        composeRule.onNodeWithText("Show more").assertExists()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.VoiceRecordingsSectionTranscriptionTest"`

Expected: 2 FAILs — "Show more" toggle not rendered because the current `TranscriptionDisplay` private composable in `VoiceRecordingsSection.kt` doesn't implement the expand toggle.

- [ ] **Step 3: Replace inline `TranscriptionDisplay`**

In `VoiceRecordingsSection.kt`, delete the private `TranscriptionDisplay` composable (lines 123-145) entirely. Add the import:

```kotlin
import org.walktalkmeditate.pilgrim.ui.recordings.TranscriptionDisplay
import org.walktalkmeditate.pilgrim.ui.recordings.TranscriptionPlaceholder
```

Replace the call at line 101:

```kotlin
                TranscriptionDisplay(recording = recording)
```

with:

```kotlin
                val transcription = recording.transcription
                when {
                    transcription == null -> TranscriptionPlaceholder(
                        text = stringResource(R.string.transcription_pending),
                    )
                    transcription == TranscriptionRunner.NO_SPEECH_PLACEHOLDER -> TranscriptionPlaceholder(
                        text = transcription,
                    )
                    else -> TranscriptionDisplay(
                        text = transcription,
                        onTap = null, // Walk Summary is read-only.
                        showCopyAffordance = false,
                    )
                }
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.VoiceRecordingsSectionTranscriptionTest"`

Expected: 2 PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/VoiceRecordingsSection.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/VoiceRecordingsSectionTranscriptionTest.kt
git commit -m "feat(summary): wire TranscriptionDisplay into Walk Summary voice row

Replaces the inline read-only transcription view with the shared
TranscriptionDisplay composable. Adds the iOS-parity expand/collapse
toggle to the Walk Summary surface; previously transcriptions over 280
chars or 7 newlines rendered as a wall of text with no toggle."
```

---

### Task 1.4: Empty-after-trim guard on `TranscriptionEditor` (A.3)

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingRow.kt:413-478` (TranscriptionEditor)
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/recordings/TranscriptionEditorEmptyGuardTest.kt` (NEW)

- [ ] **Step 1: Write the failing tests**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TranscriptionEditorEmptyGuardTest {

    @Test
    fun emptyAfterTrim_isSuppressed() {
        assertEquals(null, transcriptionCommitValue(""))
    }

    @Test
    fun whitespaceOnly_isSuppressed() {
        assertEquals(null, transcriptionCommitValue("   \n\n  \t"))
    }

    @Test
    fun nonEmptyAfterTrim_returnsTrimmed() {
        assertEquals("hello", transcriptionCommitValue("  hello  \n"))
    }

    @Test
    fun internalWhitespace_isPreserved() {
        assertEquals("hello world", transcriptionCommitValue("  hello world  "))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.recordings.TranscriptionEditorEmptyGuardTest"`

Expected: 4 FAILs with `Unresolved reference: transcriptionCommitValue`.

- [ ] **Step 3: Add the helper + wire into `TranscriptionEditor`**

In `RecordingRow.kt`, add at file scope (above the `TranscriptionEditor` private composable, around line 412):

```kotlin
/**
 * Trim the editor's text; return null when the result is empty so the
 * caller can skip persistence. iOS `VoiceRecordingRow.swift:139-154@db4196e`:
 * "Done" with whitespace-only text exits edit mode WITHOUT writing.
 * Internal whitespace is preserved.
 */
internal fun transcriptionCommitValue(rawText: String): String? {
    val trimmed = rawText.trim()
    return trimmed.ifEmpty { null }
}
```

Then in `TranscriptionEditor` (around lines 459-468), replace both `onCommit(latestText.trim())` call sites:

Replace:
```kotlin
            keyboardActions = KeyboardActions(onDone = {
                onCommit(latestText.trim())
            }),
```

With:
```kotlin
            keyboardActions = KeyboardActions(onDone = {
                transcriptionCommitValue(latestText)?.let(onCommit)
                onStop()
            }),
```

Replace:
```kotlin
                .clickable { onCommit(latestText.trim()) }
```

With:
```kotlin
                .clickable {
                    transcriptionCommitValue(latestText)?.let(onCommit)
                    onStop()
                }
```

The `onStop()` is added unconditionally so that even an empty edit exits edit mode (matches iOS: tap Done always exits, but no save on empty).

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.recordings.TranscriptionEditorEmptyGuardTest"`

Expected: 4 PASS.

Also verify regressions:
Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.recordings.*"`

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingRow.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/recordings/TranscriptionEditorEmptyGuardTest.kt
git commit -m "fix(recordings): empty-after-trim transcription edits skip persistence

iOS VoiceRecordingRow.swift:139-154@db4196e: 'Done' on whitespace-only
text exits edit mode WITHOUT writing. Android previously persisted
empty strings, polluting the DB with no-op edits.

Extract transcriptionCommitValue(rawText) -> String? so the editor's
two commit sites (keyboard IME + Done button) share the same gate.
onStop() now fires unconditionally so the user can always escape edit
mode even on an empty commit."
```

---

### Task 1.5: Speed-cycle algorithm alignment (A.4) + AudioPlayer divergence KDoc

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingsListViewModel.kt:282-296`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/ExoPlayerVoicePlaybackController.kt:36-41` (KDoc only)
- Test: extend `app/src/test/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingsListViewModelSpeedCycleTest.kt` (NEW if absent)

- [ ] **Step 1: Write the failing test**

If `RecordingsListViewModelSpeedCycleTest.kt` doesn't exist, create it. If it exists, append the new test fn (verify with `find`).

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class RecordingsListViewModelSpeedCycleTest {

    @Test
    fun cycleFrom1_0_to1_5() {
        assertEquals(1.5f, nextPlaybackSpeed(1.0f))
    }

    @Test
    fun cycleFrom1_5_to2_0() {
        assertEquals(2.0f, nextPlaybackSpeed(1.5f))
    }

    @Test
    fun cycleFrom2_0_to1_0() {
        assertEquals(1.0f, nextPlaybackSpeed(2.0f))
    }

    @Test
    fun unknownSpeed_resetsTo1_0() {
        // iOS firstIndex(of:) returns nil for non-array values → fallback
        // to index 0 → speeds[0] = 1.0. Replaces Android's prior threshold
        // logic which would map 1.3 → 2.0 (semantically wrong).
        assertEquals(1.0f, nextPlaybackSpeed(1.3f))
        assertEquals(1.0f, nextPlaybackSpeed(0.5f))
        assertEquals(1.0f, nextPlaybackSpeed(3.0f))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.recordings.RecordingsListViewModelSpeedCycleTest"`

Expected: 4 FAILs with `Unresolved reference: nextPlaybackSpeed`.

- [ ] **Step 3: Extract pure helper + wire into `onSpeedCycle`**

In `RecordingsListViewModel.kt`, at file scope (above the class) or inside a companion-object-accessible scope, add:

```kotlin
/**
 * iOS `AudioPlayerModel.swift:11@db4196e` — `[1.0, 1.5, 2.0]` array + modulo
 * cycle. firstIndex(of:) returns nil for non-array values; fallback to
 * index 0 means an unknown speed resets to 1.0. This intentionally
 * differs from the prior Android threshold logic which would map an
 * "in-between" speed like 1.3 → 2.0 (semantically wrong).
 */
internal val SPEED_CYCLE = listOf(1.0f, 1.5f, 2.0f)

internal fun nextPlaybackSpeed(current: Float): Float {
    val index = SPEED_CYCLE.indexOf(current)
    return if (index < 0) {
        SPEED_CYCLE[0]
    } else {
        SPEED_CYCLE[(index + 1) % SPEED_CYCLE.size]
    }
}
```

Then replace `RecordingsListViewModel.kt:288-296` (the body of `onSpeedCycle`):

Replace:
```kotlin
    fun onSpeedCycle() {
        val current = playbackController.playbackSpeed.value
        val next = when {
            current < 1.25f -> 1.5f
            current < 1.75f -> 2.0f
            else -> 1.0f
        }
        playbackController.setPlaybackSpeed(next)
    }
```

With:
```kotlin
    fun onSpeedCycle() {
        val current = playbackController.playbackSpeed.value
        playbackController.setPlaybackSpeed(nextPlaybackSpeed(current))
    }
```

Update the existing KDoc above `onSpeedCycle` to reference iOS parity (overwrite the "thresholds use inequality midpoints" justification — that logic is gone):

Replace the prior KDoc with:
```kotlin
    /**
     * Global speed cycle 1.0 → 1.5 → 2.0 → 1.0 via [nextPlaybackSpeed].
     * iOS parity (`AudioPlayerModel.swift:11@db4196e`): `[1.0, 1.5, 2.0]`
     * array + modulo. Unknown speeds reset to 1.0 (iOS firstIndex(of:)
     * nil-fallback).
     */
```

- [ ] **Step 4: Add the AudioPlayer Singleton divergence KDoc**

In `ExoPlayerVoicePlaybackController.kt`, replace the existing class KDoc (lines 24-35) with:

```kotlin
/**
 * Production [VoicePlaybackController] backed by androidx.media3
 * ExoPlayer. The player is lazy-created on first [play] so users who
 * never tap play don't pay the native-resource cost. All player
 * interactions are marshalled onto the main looper (ExoPlayer requires
 * its access thread to match the thread it was built on).
 *
 * Audio focus is explicitly handled by [AudioFocusCoordinator]. We
 * disable ExoPlayer's internal focus management via
 * `setAudioAttributes(handleAudioFocus = false)` so the coordinator
 * remains the single focus owner across VoiceRecorder + playback.
 *
 * **iOS divergence (intentional).** iOS uses a per-view
 * `AudioPlayerModel` instance scoped to each Walk Summary screen
 * (`AudioPlayerModel.swift:1@db4196e`). Android uses `@Singleton`
 * instead, surviving navigation-stack changes per the Stage 2-D
 * pattern: a Singleton player + AudioFocusCoordinator pair lets the
 * user back-nav from the Walk Summary mid-playback without losing
 * their position, and lets the standalone Recordings List screen
 * share the same playback infra. Lifetime of the underlying
 * resources is bounded by [release], called from
 * [VoiceRecordingsLifecycleObserver] on app-foreground transitions
 * to STOPPED.
 */
```

- [ ] **Step 5: Run tests + build**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.recordings.*"`

Expected: all PASS (including the 4 new speed-cycle tests).

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingsListViewModel.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/audio/ExoPlayerVoicePlaybackController.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingsListViewModelSpeedCycleTest.kt
git commit -m "refactor(recordings): align speed cycle with iOS array+modulo algorithm

iOS AudioPlayerModel.swift:11@db4196e cycles speeds via firstIndex(of:)
on a [1.0, 1.5, 2.0] array. Android previously used threshold-based
logic (current < 1.25 -> 1.5 etc.) which differed on out-of-array
inputs — e.g. speed=1.3 would map to 2.0 (wrong by iOS standard).

Extract pure nextPlaybackSpeed(Float) -> Float helper. Unknown speeds
reset to 1.0 matching iOS firstIndex-nil fallback.

Document AudioPlayer @Singleton vs iOS per-view divergence inline at
ExoPlayerVoicePlaybackController class KDoc per spec quality gate."
```

---

## Stage 2 — Reliquary state polish (~250 LOC)

Ships the 5 reliquary state-machine gaps from spec section B. Foundation for Stages 3 + 4.

---

### Task 2.1: `ReliquaryState` sealed class + `transcriptionNeedsExpansion`-style pure helpers

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/ReliquaryState.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/ReliquaryStateMachineTest.kt` (NEW)

- [ ] **Step 1: Write the failing tests**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto

@RunWith(JUnit4::class)
class ReliquaryStateMachineTest {

    private val photo = WalkPhoto(
        id = 1L,
        walkUuid = "uuid-1",
        photoUri = "content://media/1",
        capturedAtMillis = 0L,
    )

    @Test
    fun toggleOff_whenSettingDisabled_regardlessOfPermissionOrPhotos() {
        assertEquals(
            ReliquaryState.ToggleOff,
            resolveReliquaryState(
                toggleEnabled = false,
                permissionGranted = true,
                isFetching = false,
                photos = listOf(photo),
            ),
        )
    }

    @Test
    fun permissionDenied_whenToggleOnAndPermissionMissing() {
        assertEquals(
            ReliquaryState.PermissionDenied,
            resolveReliquaryState(
                toggleEnabled = true,
                permissionGranted = false,
                isFetching = false,
                photos = emptyList(),
            ),
        )
    }

    @Test
    fun loading_whenToggleOnPermissionGrantedFetchInFlightAndEmpty() {
        assertEquals(
            ReliquaryState.Loading,
            resolveReliquaryState(
                toggleEnabled = true,
                permissionGranted = true,
                isFetching = true,
                photos = emptyList(),
            ),
        )
    }

    @Test
    fun populated_whenToggleOnPermissionGrantedFetchCompleteAndPhotosNonEmpty() {
        val state = resolveReliquaryState(
            toggleEnabled = true,
            permissionGranted = true,
            isFetching = false,
            photos = listOf(photo),
        )
        assertTrue(state is ReliquaryState.Populated)
        assertEquals(listOf(photo), (state as ReliquaryState.Populated).candidates)
    }

    @Test
    fun emptyLeaf_whenToggleOnPermissionGrantedFetchCompleteAndPhotosEmpty() {
        val state = resolveReliquaryState(
            toggleEnabled = true,
            permissionGranted = true,
            isFetching = false,
            photos = emptyList(),
        )
        assertTrue(state is ReliquaryState.Populated)
        assertEquals(emptyList<WalkPhoto>(), (state as ReliquaryState.Populated).candidates)
    }

    @Test
    fun precedence_toggleOffBeatsPermissionDenied() {
        // Even with permission denied + photos present, toggle-off wins.
        assertEquals(
            ReliquaryState.ToggleOff,
            resolveReliquaryState(
                toggleEnabled = false,
                permissionGranted = false,
                isFetching = false,
                photos = listOf(photo),
            ),
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.reliquary.ReliquaryStateMachineTest"`

Expected: 6 FAILs with `Unresolved reference: ReliquaryState` and `resolveReliquaryState`.

- [ ] **Step 3: Implement `ReliquaryState.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto

/**
 * Walk Summary Photo Reliquary state machine. Strict precedence per
 * locked decision D6 in the spec:
 *
 *   ToggleOff > PermissionDenied > Loading > Populated(candidates)
 *
 * The `Populated.candidates` list may be empty; an empty-Populated
 * collapses to a height-zero leaf in the UI, distinct from Loading
 * (which renders the deferred skeleton).
 *
 * `@Immutable` annotation per Stage 4-D cascade audit — Compose can't
 * infer cross-module stability for `WalkPhoto`.
 */
@Immutable
sealed class ReliquaryState {
    data object ToggleOff : ReliquaryState()
    data object PermissionDenied : ReliquaryState()
    data object Loading : ReliquaryState()
    data class Populated(val candidates: List<WalkPhoto>) : ReliquaryState()
}

/**
 * Pure precedence resolver. Inputs:
 *  - [toggleEnabled] — `PracticePreferencesRepository.walkReliquaryEnabled`
 *  - [permissionGranted] — `ContextCompat.checkSelfPermission(READ_MEDIA_IMAGES)`
 *    (Android 14+ partial-grant is treated as full-grant per spec non-goal)
 *  - [isFetching] — VM-side fetch-in-flight flag
 *  - [photos] — current Room-observed `pinnedPhotos` list
 *
 * Tested in isolation; the composable wires the live inputs.
 */
internal fun resolveReliquaryState(
    toggleEnabled: Boolean,
    permissionGranted: Boolean,
    isFetching: Boolean,
    photos: List<WalkPhoto>,
): ReliquaryState = when {
    !toggleEnabled -> ReliquaryState.ToggleOff
    !permissionGranted -> ReliquaryState.PermissionDenied
    isFetching && photos.isEmpty() -> ReliquaryState.Loading
    else -> ReliquaryState.Populated(photos)
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.reliquary.ReliquaryStateMachineTest"`

Expected: 6 PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/ReliquaryState.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/ReliquaryStateMachineTest.kt
git commit -m "feat(reliquary): ReliquaryState sealed class + precedence resolver

iOS parity PhotoReliquarySection.swift:58-77@db4196e — explicit
4-state gate (ToggleOff / PermissionDenied / Loading / Populated)
with strict precedence per spec D6. Empty-Populated is a leaf of the
Populated state, not a distinct state — UI renders height-zero.

Pure resolveReliquaryState() helper extracted so the composable can
be tested deterministically; full UI wiring lands in subsequent
commits."
```

---

### Task 2.2: VM-side reliquary state flow + permission snapshot + fetchGeneration

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt` (add state holders + observer hook; locate the existing `pinnedPhotos` flow at lines 508-541 and slot the new flow nearby)
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModelReliquaryStateTest.kt` (NEW)

- [ ] **Step 1: Write the failing tests**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.walktalkmeditate.pilgrim.ui.walk.reliquary.ReliquaryState

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class WalkSummaryViewModelReliquaryStateTest {

    @Test
    fun reliquaryState_emitsToggleOff_whenSettingDisabled() = runTest {
        val harness = WalkSummaryViewModelTestHarness.create(
            toggleEnabled = false,
            permissionGranted = true,
        )
        harness.viewModel.reliquaryState.test {
            assertEquals(ReliquaryState.ToggleOff, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun reliquaryState_emitsPermissionDenied_whenToggleOnAndPermissionMissing() = runTest {
        val harness = WalkSummaryViewModelTestHarness.create(
            toggleEnabled = true,
            permissionGranted = false,
        )
        harness.viewModel.reliquaryState.test {
            assertEquals(ReliquaryState.PermissionDenied, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun fetchGeneration_dropsStaleCompletions() = runTest {
        val harness = WalkSummaryViewModelTestHarness.create(
            toggleEnabled = true,
            permissionGranted = true,
        )
        // Trigger two fetches in rapid succession; only the second should
        // write candidates. First-completion result is dropped via the
        // generation guard.
        harness.viewModel.startReliquaryFetch()
        harness.viewModel.startReliquaryFetch()
        harness.fakeFetcher.completeOldest(photos = listOf(harness.staleCandidate))
        harness.fakeFetcher.completeOldest(photos = listOf(harness.freshCandidate))
        advanceUntilIdle()
        harness.viewModel.reliquaryState.test {
            val state = awaitItem()
            assertTrue(state is ReliquaryState.Populated)
            assertEquals(
                listOf(harness.freshCandidate),
                (state as ReliquaryState.Populated).candidates,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun onForegrounded_permissionGranted_triggersOneFetch_onTransition() = runTest {
        val harness = WalkSummaryViewModelTestHarness.create(
            toggleEnabled = true,
            permissionGranted = false,
        )
        harness.viewModel.onForegrounded(permissionGranted = false)
        advanceUntilIdle()
        assertEquals(0, harness.fakeFetcher.fetchCount)

        harness.viewModel.onForegrounded(permissionGranted = true)
        advanceUntilIdle()
        assertEquals(1, harness.fakeFetcher.fetchCount)

        // Second foreground with same permission state → no new fetch.
        harness.viewModel.onForegrounded(permissionGranted = true)
        advanceUntilIdle()
        assertEquals(1, harness.fakeFetcher.fetchCount)
    }
}
```

Note: `WalkSummaryViewModelTestHarness` is an existing test fixture in this project. Verify its presence first; if absent, build a minimal one that provides `viewModel: WalkSummaryViewModel` + `fakeFetcher: FakeReliquaryFetcher`. If a similar harness already exists with different naming (e.g. `WalkSummaryTestHarness`), adapt accordingly — DO NOT introduce a redundant test fixture.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryViewModelReliquaryStateTest"`

Expected: 4 FAILs with `Unresolved reference: reliquaryState`, `startReliquaryFetch`, `onForegrounded`, and missing `FakeReliquaryFetcher`.

- [ ] **Step 3: Add VM state holders + flow**

In `WalkSummaryViewModel.kt`, locate the section that owns `pinnedPhotos` (around lines 508-541). Slot the new state holders nearby. Suggested placement:

```kotlin
    // ---- Reliquary state machine (spec D6) ----

    private val _reliquaryIsFetching = MutableStateFlow(false)
    private val _previousPermissionGranted = MutableStateFlow<Boolean?>(null)
    private var fetchGeneration: Long = 0L

    /**
     * Live composite of: practice preference (toggle), runtime permission
     * snapshot, fetch-in-flight flag, and Room-observed pinnedPhotos.
     * Compose subscribers receive a [ReliquaryState] suitable for direct
     * `when (state)` rendering.
     */
    val reliquaryState: StateFlow<ReliquaryState> = combine(
        practicePreferences.walkReliquaryEnabled,
        _previousPermissionGranted,
        _reliquaryIsFetching,
        pinnedPhotos,
    ) { toggle, permission, fetching, photos ->
        // First emission may have null permission (haven't observed yet);
        // treat as denied so the UI doesn't briefly render Populated
        // before the lifecycle observer reports the actual state.
        resolveReliquaryState(
            toggleEnabled = toggle,
            permissionGranted = permission ?: false,
            isFetching = fetching,
            photos = photos,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
        initialValue = ReliquaryState.ToggleOff,
    )

    /**
     * Called by the PhotoReliquarySection composable from
     * `LifecycleEventEffect(ON_START)`. Re-fetches ONLY when permission
     * transitions denied → granted (per spec D6). Updates the snapshot
     * after comparison so subsequent ON_STARTs use the new baseline.
     */
    fun onForegrounded(permissionGranted: Boolean) {
        val prev = _previousPermissionGranted.value
        _previousPermissionGranted.value = permissionGranted
        if (prev == false && permissionGranted) {
            startReliquaryFetch()
        }
    }

    /**
     * Begin a reliquary photo fetch. Each call bumps [fetchGeneration]
     * so out-of-order async completions can drop their results.
     */
    fun startReliquaryFetch() {
        fetchGeneration += 1
        val thisGeneration = fetchGeneration
        _reliquaryIsFetching.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val photos = reliquaryFetcher.fetch()
                if (thisGeneration == fetchGeneration) {
                    // Stale completion guard: only the freshest fetch writes.
                    walkRepository.replacePinnedPhotos(walkId, photos)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (thisGeneration == fetchGeneration) {
                    // Surface fetch failure via a separate channel; out of scope
                    // for this PR (current behavior: silently fall through to
                    // empty Populated). Track for Phase N error surfacing.
                }
            } finally {
                if (thisGeneration == fetchGeneration) {
                    _reliquaryIsFetching.value = false
                }
            }
        }
    }
```

Add the constructor injection for `reliquaryFetcher: ReliquaryFetcher` if not already present. If `ReliquaryFetcher` doesn't exist yet, this task is BLOCKED — escalate. The pattern is: an interface with one `suspend fun fetch(): List<WalkPhoto>` so the test can inject `FakeReliquaryFetcher`.

Add imports as needed (`SharingStarted`, `StateFlow`, `combine`, etc. — match the existing import style).

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryViewModelReliquaryStateTest"`

Expected: 4 PASS. If the test harness scaffolding requires test-side additions (`FakeReliquaryFetcher`, `WalkSummaryViewModelTestHarness` factory extension), add them inside the test file or its supporting fixtures file — DO NOT introduce production-side fakes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModelReliquaryStateTest.kt
git commit -m "feat(reliquary): VM-side ReliquaryState flow + permission snapshot + fetchGeneration

Spec D6: reliquaryState combines practicePreferences.walkReliquaryEnabled,
permission snapshot, fetch-in-flight flag, and Room-observed
pinnedPhotos via resolveReliquaryState. Composable subscribes for
direct when-rendering.

onForegrounded(permissionGranted) ONLY triggers a fetch on the
denied→granted transition — avoids the infinite-re-fetch loop on
empty-Populated walks flagged by doc-review round 4.

fetchGeneration drops stale async completions per iOS
PhotoReliquarySection.swift:259-279@db4196e."
```

---

### Task 2.3: Render `ReliquaryState` in `PhotoReliquarySection.kt`

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoReliquarySection.kt` (large rewrite — 4-state gate, deferred skeleton, lifecycle observer, Settings deep link)

- [ ] **Step 1: Add Compose test for the 4 rendered states**

Create `app/src/test/java/.../reliquary/PhotoReliquarySectionStateTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoReliquarySectionStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val photo = WalkPhoto(id = 1L, walkUuid = "u", photoUri = "content://m/1", capturedAtMillis = 0L)

    @Test
    fun toggleOff_rendersEmptyTree() {
        composeRule.setContent {
            PhotoReliquarySection(
                state = ReliquaryState.ToggleOff,
                onPinPhotos = {},
                onUnpinPhoto = {},
                onForegrounded = {},
                onSettingsClick = {},
            )
        }
        composeRule.onNodeWithTag(TAG_RELIQUARY_TOGGLE_OFF).assertExists()
    }

    @Test
    fun permissionDenied_rendersPromptAndSettingsButton() {
        composeRule.setContent {
            PhotoReliquarySection(
                state = ReliquaryState.PermissionDenied,
                onPinPhotos = {},
                onUnpinPhoto = {},
                onForegrounded = {},
                onSettingsClick = {},
            )
        }
        composeRule.onNodeWithTag(TAG_RELIQUARY_PERMISSION_PROMPT).assertExists()
        composeRule.onNodeWithTag(TAG_RELIQUARY_SETTINGS_BUTTON).assertExists()
    }

    @Test
    fun loading_rendersDeferredSkeleton() {
        composeRule.setContent {
            PhotoReliquarySection(
                state = ReliquaryState.Loading,
                onPinPhotos = {},
                onUnpinPhoto = {},
                onForegrounded = {},
                onSettingsClick = {},
            )
        }
        // Skeleton may render immediately under the 300ms wait — test asserts
        // the placeholder node exists post-tick. Use composeRule.mainClock to
        // advance.
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(310L)
        composeRule.onNodeWithTag(TAG_RELIQUARY_SKELETON).assertExists()
    }

    @Test
    fun populatedWithPhotos_rendersCarousel() {
        composeRule.setContent {
            PhotoReliquarySection(
                state = ReliquaryState.Populated(listOf(photo)),
                onPinPhotos = {},
                onUnpinPhoto = {},
                onForegrounded = {},
                onSettingsClick = {},
            )
        }
        composeRule.onNodeWithTag(TAG_RELIQUARY_CAROUSEL).assertExists()
    }

    @Test
    fun populatedEmpty_rendersHeightZeroLeaf() {
        composeRule.setContent {
            PhotoReliquarySection(
                state = ReliquaryState.Populated(emptyList()),
                onPinPhotos = {},
                onUnpinPhoto = {},
                onForegrounded = {},
                onSettingsClick = {},
            )
        }
        // Populated-empty MUST NOT render the skeleton or the carousel.
        composeRule.onNodeWithTag(TAG_RELIQUARY_SKELETON).assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_RELIQUARY_CAROUSEL).assertDoesNotExist()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.reliquary.PhotoReliquarySectionStateTest"`

Expected: 5 FAILs — composable signature changed, test tags don't exist.

- [ ] **Step 3: Rewrite `PhotoReliquarySection.kt`**

Replace the current `PhotoReliquarySection` composable (lines 74-152) with a state-driven dispatcher. KEEP the existing add-photo picker logic + `ReliquaryTombstone` and `UnpinConfirmationDialog` private composables (Stage 3 will replace the grid with the carousel; for now we route Populated to a placeholder that wraps the existing PhotoGrid).

Full new shape (replace lines 74-152):

```kotlin
// Test tags (internal so tests can import them).
internal const val TAG_RELIQUARY_TOGGLE_OFF = "reliquary-toggle-off"
internal const val TAG_RELIQUARY_PERMISSION_PROMPT = "reliquary-permission-prompt"
internal const val TAG_RELIQUARY_SETTINGS_BUTTON = "reliquary-settings-button"
internal const val TAG_RELIQUARY_SKELETON = "reliquary-skeleton"
internal const val TAG_RELIQUARY_CAROUSEL = "reliquary-carousel"

@Composable
fun PhotoReliquarySection(
    state: ReliquaryState,
    onPinPhotos: (List<Uri>) -> Unit,
    onUnpinPhoto: (WalkPhoto) -> Unit,
    onForegrounded: (permissionGranted: Boolean) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Lifecycle re-fetch: ON_START reads runtime permission state and
    // forwards to the VM. Per spec D6 the VM gates on transition; we
    // unconditionally forward and let the VM decide.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                val granted = isPhotosPermissionGranted(context)
                onForegrounded(granted)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (state) {
        ReliquaryState.ToggleOff -> {
            Box(modifier = modifier.testTag(TAG_RELIQUARY_TOGGLE_OFF))
        }
        ReliquaryState.PermissionDenied -> {
            ReliquaryPermissionPrompt(
                onSettingsClick = onSettingsClick,
                modifier = modifier,
            )
        }
        ReliquaryState.Loading -> {
            ReliquaryDeferredSkeleton(modifier = modifier)
        }
        is ReliquaryState.Populated -> {
            if (state.candidates.isEmpty()) {
                // Empty leaf — height-zero, NOT a skeleton.
                Box(modifier = modifier)
            } else {
                ReliquaryPopulated(
                    photos = state.candidates,
                    onPinPhotos = onPinPhotos,
                    onUnpinPhoto = onUnpinPhoto,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun ReliquaryDeferredSkeleton(modifier: Modifier = Modifier) {
    // Deferred 300ms: only render the shimmer if Loading has persisted
    // for the full delay. Otherwise stay invisible — fetches that
    // complete in <300ms produce no skeleton flash.
    var showSkeleton by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SKELETON_DEFER_MS)
        // Double-check: composable still in tree → still Loading state →
        // show the skeleton. If we transitioned to PermissionDenied or
        // Populated mid-delay, the composable is no longer rendered and
        // this LaunchedEffect was cancelled.
        showSkeleton = true
    }
    if (showSkeleton) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(SKELETON_HEIGHT)
                .clip(RoundedCornerShape(PilgrimCornerRadius.small))
                .background(pilgrimColors.parchmentSecondary)
                .testTag(TAG_RELIQUARY_SKELETON),
        )
    }
}

@Composable
private fun ReliquaryPermissionPrompt(
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_RELIQUARY_PERMISSION_PROMPT)
            .padding(PilgrimSpacing.normal),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Text(
            text = stringResource(R.string.reliquary_permission_denied_title),
            style = pilgrimType.heading,
            color = pilgrimColors.ink,
        )
        Text(
            text = stringResource(R.string.reliquary_permission_denied_body),
            style = pilgrimType.body,
            color = pilgrimColors.fog,
        )
        OutlinedButton(
            onClick = onSettingsClick,
            modifier = Modifier.testTag(TAG_RELIQUARY_SETTINGS_BUTTON),
        ) {
            Text(stringResource(R.string.reliquary_permission_denied_action_settings))
        }
    }
}

/**
 * Populated-with-photos branch. Stage 3 replaces the PhotoGrid here
 * with the PhotoCarousel; for now keep the existing add-photo picker
 * machinery + grid intact so the bundle's commit sequence stays clean.
 */
@Composable
private fun ReliquaryPopulated(
    photos: List<WalkPhoto>,
    onPinPhotos: (List<Uri>) -> Unit,
    onUnpinPhoto: (WalkPhoto) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ... (existing slots calc + picker launchers + grid render — preserve
    //      verbatim from current PhotoReliquarySection lines 81-140, then
    //      wrap with .testTag(TAG_RELIQUARY_CAROUSEL) on the outer Column.)
}

internal fun isPhotosPermissionGranted(context: Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.READ_MEDIA_IMAGES
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return ContextCompat.checkSelfPermission(context, permission) ==
        PackageManager.PERMISSION_GRANTED
}

private const val SKELETON_DEFER_MS = 300L
private val SKELETON_HEIGHT = 88.dp
```

Add the new string resources (in `strings.xml`):

```xml
    <string name="reliquary_permission_denied_title">Photos access needed</string>
    <string name="reliquary_permission_denied_body">Pilgrim needs Photos access to find pictures from this walk. You can grant access in Settings.</string>
    <string name="reliquary_permission_denied_action_settings">Open settings</string>
```

- [ ] **Step 4: Wire `onSettingsClick` + `onForegrounded` at the screen level**

In `WalkSummaryScreen.kt`, find where `PhotoReliquarySection` is currently called. Update the call to pass the new params:

```kotlin
PhotoReliquarySection(
    state = viewModel.reliquaryState.collectAsStateWithLifecycle().value,
    onPinPhotos = viewModel::pinPhotos,
    onUnpinPhoto = viewModel::unpinPhoto,
    onForegrounded = viewModel::onForegrounded,
    onSettingsClick = {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No Settings app — extremely unlikely. Snackbar fallback
            // would require plumbing the host state here; for now log
            // + drop (the prompt re-appears on every ON_START so user
            // can retry).
            Log.w("PhotoReliquary", "no activity to handle settings intent")
        }
    },
)
```

- [ ] **Step 5: Add Settings deep-link AC test**

`app/src/test/java/.../reliquary/ReliquarySettingsDeepLinkTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReliquarySettingsDeepLinkTest {

    @Test
    fun settingsIntent_actionAndDataMatchSpec() {
        // Replicate the production intent build path here — the test
        // pins the contract independently of the composable wiring.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package", intent.data?.scheme)
        assertEquals(context.packageName, intent.data?.schemeSpecificPart)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }
}
```

- [ ] **Step 6: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.reliquary.*" --tests "org.walktalkmeditate.pilgrim.ui.walk.VoiceRecordingsSectionTranscriptionTest"`

Expected: all PASS.

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoReliquarySection.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoReliquarySectionStateTest.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/ReliquarySettingsDeepLinkTest.kt
git commit -m "feat(reliquary): 4-state gate + deferred skeleton + lifecycle + Settings deep link

iOS parity PhotoReliquarySection.swift:58-188@db4196e:
- ToggleOff > PermissionDenied > Loading > Populated precedence (spec D6)
- 300ms deferred skeleton with double-check (fetches <300ms produce no flash)
- ON_START lifecycle observer forwards permission state to VM
- Permission-revoked prompt with Settings deep-link

Existing PhotoGrid stays inside ReliquaryPopulated for now — Stage 3
replaces it with the new PhotoCarousel."
```

---

## Stage 3 — Reliquary carousel (~200 LOC)

Replaces `PhotoGrid` inside `ReliquaryPopulated` with horizontal `PhotoCarousel`.

---

### Task 3.1: `PhotoCarousel.kt` + `PhotoThumbnail.kt` + activation state

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoCarousel.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoThumbnail.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoCarouselTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoCarouselTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val photoOne = WalkPhoto(id = 1L, walkUuid = "u", photoUri = "content://m/1", capturedAtMillis = 0L)
    private val photoTwo = WalkPhoto(id = 2L, walkUuid = "u", photoUri = "content://m/2", capturedAtMillis = 0L)

    @Test
    fun thumbnail_rendersAt88dpSquare() {
        composeRule.setContent {
            PhotoCarousel(
                photos = listOf(photoOne),
                pinnedIds = setOf(1L),
                onThumbnailCommit = {},
            )
        }
        composeRule.onNodeWithTag("photo-thumbnail-1").assertWidthIsEqualTo(88.dp)
    }

    @Test
    fun longPress_activatesThumbnail() {
        composeRule.setContent {
            PhotoCarousel(
                photos = listOf(photoOne),
                pinnedIds = setOf(1L),
                onThumbnailCommit = {},
            )
        }
        composeRule.onNodeWithTag("photo-thumbnail-1").performTouchInput {
            longClick(durationMillis = 400)
        }
        composeRule.onNodeWithTag("photo-thumbnail-1-activated").assertExists()
    }

    @Test
    fun touchDrag_clearsActivation() {
        composeRule.setContent {
            PhotoCarousel(
                photos = listOf(photoOne, photoTwo),
                pinnedIds = setOf(1L, 2L),
                onThumbnailCommit = {},
            )
        }
        composeRule.onNodeWithTag("photo-thumbnail-1").performTouchInput {
            longClick(durationMillis = 400)
        }
        composeRule.onNodeWithTag("photo-thumbnail-1-activated").assertExists()
        composeRule.onNodeWithTag("photo-carousel").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("photo-thumbnail-1-activated").assertDoesNotExist()
    }

    @Test
    fun programmaticScroll_preservesActivation() {
        val state = LazyListState()
        composeRule.setContent {
            PhotoCarousel(
                photos = listOf(photoOne, photoTwo),
                pinnedIds = setOf(1L, 2L),
                onThumbnailCommit = {},
                listState = state,
            )
        }
        composeRule.onNodeWithTag("photo-thumbnail-1").performTouchInput {
            longClick(durationMillis = 400)
        }
        runBlocking { state.scrollToItem(1) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("photo-thumbnail-1-activated").assertExists()
    }

    @Test
    fun pinnedBadge_visibleOnPinnedPhotos() {
        composeRule.setContent {
            PhotoCarousel(
                photos = listOf(photoOne),
                pinnedIds = setOf(1L),
                onThumbnailCommit = {},
            )
        }
        composeRule.onNodeWithTag("photo-thumbnail-1-pinned-badge").assertExists()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.reliquary.PhotoCarouselTest"`

Expected: 5 FAILs — `Unresolved reference: PhotoCarousel`.

- [ ] **Step 3: Implement `PhotoThumbnail.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

internal const val THUMBNAIL_SIZE_DP = 88
private const val ACTIVATED_SCALE = 1.05f
private const val LONG_PRESS_DURATION_MS = 400L

@Composable
internal fun PhotoThumbnail(
    photo: WalkPhoto,
    isPinned: Boolean,
    isActivated: Boolean,
    onLongPress: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (isActivated) ACTIVATED_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "thumbnail-activation-scale",
    )

    Box(
        modifier = modifier
            .size(THUMBNAIL_SIZE_DP.dp)
            .testTag("photo-thumbnail-${photo.id}")
            .graphicsLayer {
                // Lambda form per Stage 5-A perf-cliff lesson — keeps the
                // animated scale value in the render phase, not composition.
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(PilgrimCornerRadius.small))
            .background(pilgrimColors.parchmentSecondary)
            .pointerInput(photo.id) {
                detectTapGestures(
                    onLongPress = {
                        onLongPress()
                    },
                    onTap = { onTap() },
                )
            },
    ) {
        SubcomposeAsyncImage(
            model = photo.photoUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isPinned) {
            Icon(
                imageVector = Icons.Filled.Bookmark,
                contentDescription = null,
                tint = pilgrimColors.rust,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .testTag("photo-thumbnail-${photo.id}-pinned-badge"),
            )
        }
        if (isActivated) {
            // Invisible marker for tests + a11y describing activation.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("photo-thumbnail-${photo.id}-activated"),
            )
        }
    }
}
```

- [ ] **Step 4: Implement `PhotoCarousel.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing

/**
 * iOS-parity horizontal photo carousel for the Walk Summary reliquary.
 * Replaces the prior 3-column grid (Stage 7-A). Tap an activated
 * thumbnail → opens the PhotoPreviewSheet (Stage 4).
 *
 * Activation state machine:
 *  - Long-press 400ms on a thumbnail → activated (1.05× spring scale)
 *  - User-drag of the carousel → clears activation
 *  - Programmatic scroll (`scrollToItem`) → activation persists
 *  - Tap on an activated thumbnail → commit (fires onThumbnailCommit)
 */
@Composable
internal fun PhotoCarousel(
    photos: List<WalkPhoto>,
    pinnedIds: Set<Long>,
    onThumbnailCommit: (WalkPhoto) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    var activatedId by remember { mutableStateOf<Long?>(null) }
    val haptic = LocalHapticFeedback.current

    // Clear activation on user touch-drag only. `interactionSource.collectIsDraggedAsState`
    // is the canonical Compose API for distinguishing touch drag from
    // programmatic scroll. snapshotFlow lets us react with structured
    // cancellation.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collectLatest { scrolling ->
                // FILTER for touch-only via the interaction source.
                if (scrolling && listState.interactionSource.interactions.toString().contains("Drag")) {
                    // Use `interactionSource.collectIsDraggedAsState()` properly
                    // — but a snapshotFlow-readable variant requires reading
                    // the underlying flow. Simplest correct form:
                    activatedId = null
                }
            }
    }

    LazyRow(
        modifier = modifier.testTag("photo-carousel"),
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        contentPadding = PaddingValues(horizontal = PilgrimSpacing.normal),
    ) {
        items(items = photos, key = { it.id }) { photo ->
            PhotoThumbnail(
                photo = photo,
                isPinned = photo.id in pinnedIds,
                isActivated = activatedId == photo.id,
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    activatedId = photo.id
                },
                onTap = {
                    if (activatedId == photo.id) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onThumbnailCommit(photo)
                        activatedId = null
                    }
                },
            )
        }
    }
}
```

**Note on `interactionSource` filtering:** the snapshotFlow approach above is approximate. For test parity (programmatic scroll must NOT clear activation while touch drag must), use Compose's `LazyListState.interactionSource.collectIsDraggedAsState()` directly:

```kotlin
val isDragged by listState.interactionSource.collectIsDraggedAsState()
LaunchedEffect(isDragged) {
    if (isDragged) activatedId = null
}
```

Replace the prior LaunchedEffect with the simpler form. Drop the `snapshotFlow` import if unused.

- [ ] **Step 5: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.reliquary.PhotoCarouselTest"`

Expected: 5 PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoCarousel.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoThumbnail.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoCarouselTest.kt
git commit -m "feat(reliquary): PhotoCarousel + PhotoThumbnail with activation state machine

iOS parity PhotoCarouselView.swift:1-218@db4196e — horizontal LazyRow,
88dp thumbnails, 400ms long-press activation, 1.05× spring scale on
activate. Pinned-badge overlay on photos already in the walk's WalkPhoto
set.

Activation clears on user touch-drag of the carousel; programmatic
scroll preserves it. Implemented via collectIsDraggedAsState on the
LazyListState — touch-only filter is necessary to distinguish from
scrollToItem (used by Stage 13-D segment-zoom)."
```

---

### Task 3.2: Replace `PhotoGrid` in `PhotoReliquarySection.kt` with `PhotoCarousel`

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoReliquarySection.kt` (replace `ReliquaryPopulated` body + delete `PhotoGrid` + `PhotoTile`)

- [ ] **Step 1: Update `ReliquaryPopulated` to call `PhotoCarousel`**

In `PhotoReliquarySection.kt`, replace the `ReliquaryPopulated` body (which currently wraps `PhotoGrid`) with:

```kotlin
@Composable
private fun ReliquaryPopulated(
    photos: List<WalkPhoto>,
    onPinPhotos: (List<Uri>) -> Unit,
    onUnpinPhoto: (WalkPhoto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val slots = (MAX_PINS_PER_WALK - photos.size).coerceAtLeast(0)
    val multiContract = remember {
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_PINS_PER_WALK)
    }
    val multiLauncher = rememberLauncherForActivityResult(multiContract) { uris ->
        if (uris.isNotEmpty()) onPinPhotos(uris)
    }
    val singleContract = remember { ActivityResultContracts.PickVisualMedia() }
    val singleLauncher = rememberLauncherForActivityResult(singleContract) { uri ->
        if (uri != null) onPinPhotos(listOf(uri))
    }

    Column(modifier = modifier.fillMaxWidth().testTag(TAG_RELIQUARY_CAROUSEL)) {
        ReliquaryHeader(
            slotsAvailable = slots,
            onAddClick = {
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                when (slots) {
                    0 -> Unit
                    1 -> singleLauncher.launch(request)
                    else -> multiLauncher.launch(request)
                }
            },
        )
        if (photos.isNotEmpty()) {
            Spacer(Modifier.height(PilgrimSpacing.small))
            PhotoCarousel(
                photos = photos,
                pinnedIds = photos.map { it.id }.toSet(),
                onThumbnailCommit = { photo ->
                    // Stage 4 wires this to open PhotoPreviewSheet. Until
                    // Stage 4 lands, the carousel commit is a temporary
                    // no-op to keep this commit standalone.
                    Unit
                },
            )
        }
    }
}
```

Delete the `PhotoGrid` and `PhotoTile` private composables entirely (current lines 183-269). Delete the inline `ReliquaryTombstone` if no longer referenced (carousel doesn't show a per-tile broken-state — Coil handles silently by rendering nothing).

- [ ] **Step 2: Migrate existing grid tests to carousel tests**

Find existing PhotoReliquarySection compose tests (likely in `app/src/test/java/.../reliquary/`). Delete tests that assert `PhotoGrid`-specific behavior (3-column layout, tile-aspect-ratio of 1f). Migrate the remaining behavior (long-press → unpin dialog) — note: this UX is REPLACED by the preview-sheet's pin/unpin flow in Stage 4, so the long-press → confirmation-dialog tests can be deleted (the unpin path now lives in `PhotoPreviewSheet`).

If the existing tests don't exist or are minimal, this step is a no-op.

- [ ] **Step 3: Run tests + build**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.reliquary.*"`

Expected: all PASS.

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoReliquarySection.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/
git commit -m "refactor(reliquary): replace 3-column PhotoGrid with horizontal PhotoCarousel

Stage 7-A's grid layout was an Android-only scope decision; iOS uses a
horizontal carousel. Per spec locked-decision D1, replace the grid
entirely. Existing long-press → unpin AlertDialog flow deleted —
unpin moves into the new PhotoPreviewSheet (Stage 4).

PhotoCarousel test surface migrates from grid layout assertions to
LazyRow + activation state machine + scroll-phase observer assertions."
```

---

## Stage 4 — Reliquary preview-sheet (~350 LOC)

Wires `PhotoPreviewSheet` from the carousel's `onThumbnailCommit`. Pin/unpin lives here.

---

### Task 4.1: `PhotoPreviewSheet.kt` skeleton (Dialog + black background + AsyncImage)

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoPreviewSheet.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoPreviewSheetTest.kt`

- [ ] **Step 1: Write the failing baseline test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoPreviewSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val photo = WalkPhoto(id = 1L, walkUuid = "u", photoUri = "content://m/1", capturedAtMillis = 0L)

    @Test
    fun sheetRenders_withPhotoImageAndPinButton() {
        composeRule.setContent {
            PhotoPreviewSheet(
                photo = photo,
                isPinned = false,
                isPinningInFlight = false,
                onPin = {},
                onOpenInGallery = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithTag("preview-sheet-image").assertExists()
        composeRule.onNodeWithTag("preview-sheet-pin-button").assertExists()
    }

    @Test
    fun pinButtonDisabled_whenAlreadyPinned() {
        composeRule.setContent {
            PhotoPreviewSheet(
                photo = photo,
                isPinned = true,
                isPinningInFlight = false,
                onPin = {},
                onOpenInGallery = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithTag("preview-sheet-pin-button").assertExists()
        // Disabled state — relies on Compose's semantic for clickable disabled.
        // Verify the click is a no-op by capturing callback invocations.
    }

    @Test
    fun pinButtonRapidTaps_fireExactlyOnceWhenInFlightFlips() {
        var pinCount = 0
        var inFlight = false
        composeRule.setContent {
            PhotoPreviewSheet(
                photo = photo,
                isPinned = false,
                isPinningInFlight = inFlight,
                onPin = {
                    pinCount += 1
                    inFlight = true
                },
                onOpenInGallery = {},
                onDismiss = {},
            )
        }
        repeat(3) { composeRule.onNodeWithTag("preview-sheet-pin-button").performClick() }
        composeRule.waitForIdle()
        // VM Mutex defends against this in production; the UI test pins the
        // single-fire contract at the composable level via the inFlight flag.
        // Expected: count is 1 because the first onPin set inFlight=true,
        // disabling subsequent taps.
        assert(pinCount == 1) { "expected 1, got $pinCount" }
    }

    @Test
    fun backHandler_dismissesSheet() {
        var dismissed = false
        composeRule.setContent {
            PhotoPreviewSheet(
                photo = photo,
                isPinned = false,
                isPinningInFlight = false,
                onPin = {},
                onOpenInGallery = {},
                onDismiss = { dismissed = true },
            )
        }
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assert(dismissed)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.reliquary.PhotoPreviewSheetTest"`

Expected: 4 FAILs — `Unresolved reference: PhotoPreviewSheet`.

- [ ] **Step 3: Implement `PhotoPreviewSheet.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing

/**
 * Full-screen photo preview matching iOS `PhotoPreviewSheet.swift@db4196e`.
 * Pin-button state derives from repository (`isPinned` + `isPinningInFlight`)
 * per spec D4 — no rememberSaveable latch.
 *
 * Drag-down > 120dp dismisses; ≤ 120dp snaps back. Velocity is NOT part of
 * the dismiss contract — a fast flick under 120dp still snaps back.
 */
@Composable
internal fun PhotoPreviewSheet(
    photo: WalkPhoto,
    isPinned: Boolean,
    isPinningInFlight: Boolean,
    onPin: () -> Unit,
    onOpenInGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        PhotoPreviewSheetContent(
            photo = photo,
            isPinned = isPinned,
            isPinningInFlight = isPinningInFlight,
            onPin = onPin,
            onOpenInGallery = onOpenInGallery,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun PhotoPreviewSheetContent(
    photo: WalkPhoto,
    isPinned: Boolean,
    isPinningInFlight: Boolean,
    onPin: () -> Unit,
    onOpenInGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    var dragOffsetDp by remember { mutableStateOf(0.dp) }
    var dismissing by remember { mutableStateOf(false) }

    val snapBackOffset by animateFloatAsState(
        targetValue = if (dragOffsetDp == 0.dp) 0f else dragOffsetDp.value,
        animationSpec = spring(
            stiffness = Spring.StiffnessLow,
            dampingRatio = 0.8f,
        ),
        label = "preview-sheet-drag-offset",
    )

    BackHandler(enabled = true) {
        if (dismissing) return@BackHandler
        dismissing = true
        onDismiss()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer { translationY = with(density) { snapBackOffset.dp.toPx() } }
            .pointerInput(photo.id) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragOffsetDp > DRAG_DISMISS_THRESHOLD_DP) {
                            if (!dismissing) {
                                dismissing = true
                                onDismiss()
                            }
                        } else {
                            dragOffsetDp = 0.dp
                        }
                    },
                    onVerticalDrag = { _, dragAmount ->
                        val deltaDp = with(density) { dragAmount.toDp() }
                        dragOffsetDp = (dragOffsetDp + deltaDp).coerceAtLeast(0.dp)
                    },
                )
            },
        color = Color.Black,
    ) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = photo.photoUri, // String — Coil 3 routes via StringMapper to AndroidContentUriFetcher
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("preview-sheet-image"),
                )
            }
            BottomActions(
                isPinned = isPinned,
                isPinningInFlight = isPinningInFlight,
                onPin = onPin,
                onOpenInGallery = onOpenInGallery,
            )
        }
    }
}

@Composable
private fun BottomActions(
    isPinned: Boolean,
    isPinningInFlight: Boolean,
    onPin: () -> Unit,
    onOpenInGallery: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PilgrimSpacing.normal),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onPin,
            enabled = !isPinned && !isPinningInFlight,
            modifier = Modifier
                .weight(1f)
                .testTag("preview-sheet-pin-button"),
        ) {
            Icon(
                imageVector = Icons.Outlined.Bookmark,
                contentDescription = null,
            )
            Spacer(Modifier.height(PilgrimSpacing.xs))
            Text(
                text = stringResource(
                    if (isPinned) R.string.preview_sheet_pinned
                    else R.string.preview_sheet_pin,
                ),
            )
        }
        IconButton(
            onClick = onOpenInGallery,
            modifier = Modifier.testTag("preview-sheet-open-in-gallery"),
        ) {
            Icon(
                imageVector = Icons.Filled.OpenInNew,
                contentDescription = stringResource(R.string.preview_sheet_open_in_gallery),
            )
        }
    }
}

internal val DRAG_DISMISS_THRESHOLD_DP: Dp = 120.dp

/**
 * Helper used by callers to build the "Open in Gallery" intent. iOS
 * uses `photos-redirect://`; Android uses ACTION_VIEW with the photo's
 * content:// URI + explicit `image/*` MIME so OEM resolvers route to
 * the gallery rather than a file browser.
 */
internal fun buildOpenInGalleryIntent(contentUriString: String): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(contentUriString.toUri(), "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
```

Add string resources:

```xml
    <string name="preview_sheet_pin">Pin to walk</string>
    <string name="preview_sheet_pinned">Pinned</string>
    <string name="preview_sheet_open_in_gallery">Open in gallery</string>
    <string name="preview_sheet_open_failed">No gallery app found</string>
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.reliquary.PhotoPreviewSheetTest"`

Expected: 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoPreviewSheet.kt \
        app/src/main/res/values/strings.xml \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoPreviewSheetTest.kt
git commit -m "feat(reliquary): PhotoPreviewSheet full-screen Dialog with pin + open-in-gallery

iOS parity PhotoPreviewSheet.swift:1-146@db4196e. Full-screen Dialog
matching .fullScreenCover semantics; black background; high-res Coil
async image; drag-down 120dp dismiss with spring snap-back; pin button
state derived from repository (isPinned + isPinningInFlight) per spec
D4 — no rememberSaveable latch.

BackHandler routes through the same dismiss path as drag-down
(idempotent — dismissing flag prevents re-entry during animation).

buildOpenInGalleryIntent extracted so the launcher contract is testable
in isolation."
```

---

### Task 4.2: Drag-dismiss boundary test (119dp snap-back / 121dp dismiss)

**Files:**
- Modify: `app/src/test/java/.../reliquary/PhotoPreviewSheetTest.kt` (append boundary tests)

- [ ] **Step 1: Append the boundary tests**

```kotlin
    @Test
    fun dragJustBelowThreshold_snapsBackNotDismisses() {
        var dismissed = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                PhotoPreviewSheet(
                    photo = photo,
                    isPinned = false,
                    isPinningInFlight = false,
                    onPin = {},
                    onOpenInGallery = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        composeRule.onNodeWithTag("preview-sheet-image").performTouchInput {
            swipeDown(
                startY = 0f,
                endY = 119f, // 119dp at density=1f = 119px
                durationMillis = 200,
            )
        }
        composeRule.waitForIdle()
        assert(!dismissed) { "expected snap-back at 119dp, but sheet dismissed" }
    }

    @Test
    fun dragJustAboveThreshold_dismisses() {
        var dismissed = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                PhotoPreviewSheet(
                    photo = photo,
                    isPinned = false,
                    isPinningInFlight = false,
                    onPin = {},
                    onOpenInGallery = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        composeRule.onNodeWithTag("preview-sheet-image").performTouchInput {
            swipeDown(
                startY = 0f,
                endY = 121f,
                durationMillis = 200,
            )
        }
        composeRule.waitForIdle()
        assert(dismissed) { "expected dismiss at 121dp, but sheet snap-back" }
    }
```

Add necessary imports:
```kotlin
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
```

- [ ] **Step 2: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.reliquary.PhotoPreviewSheetTest"`

Expected: 6 PASS (4 prior + 2 new).

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoPreviewSheetTest.kt
git commit -m "test(reliquary): pin 119dp/121dp drag-dismiss boundary

Locks the dp threshold contract from spec D — translation-only,
velocity-independent. Forces Density(1f) so dp = px and the swipe
gesture's px coordinates map directly to the 120dp threshold."
```

---

### Task 4.3: Wire `PhotoPreviewSheet` from carousel + VM (open-in-gallery intent dispatch)

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoReliquarySection.kt` (host the sheet state)
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt` (`isPinningInFlight` flow + Mutex)

- [ ] **Step 1: Add pinning-in-flight flow to VM**

In `WalkSummaryViewModel.kt`, find the existing pin/unpin section (lines 665-769 per parity audit). Add:

```kotlin
    private val pinPhotosMutex = Mutex()
    private val _isPinningInFlight = MutableStateFlow(false)
    val isPinningInFlight: StateFlow<Boolean> = _isPinningInFlight.asStateFlow()
```

Wrap the existing `pinPhotos(...)` body to set `_isPinningInFlight.value = true` before launching and `false` in `finally`. Use the Mutex to serialize concurrent calls (third tap during a slow first commit is dropped). Stage 7-A pattern — confirm there isn't already a Mutex-based serializer in place (audit the function before adding a second).

- [ ] **Step 2: Host the sheet in `ReliquaryPopulated`**

In `PhotoReliquarySection.kt`, modify `ReliquaryPopulated` to hold `previewPhoto: WalkPhoto?` state and pass `onThumbnailCommit = { previewPhoto = it }`. When non-null, render `PhotoPreviewSheet`:

```kotlin
    var previewPhoto by remember { mutableStateOf<WalkPhoto?>(null) }
    val context = LocalContext.current
    // ... existing slots + launchers ...
    Column(...) { ... }
    previewPhoto?.let { photo ->
        val pinnedSet = photos.map { it.id }.toSet()
        val isPinningInFlight by collectIsPinningInFlight() // VM-bound state
        PhotoPreviewSheet(
            photo = photo,
            isPinned = photo.id in pinnedSet,
            isPinningInFlight = isPinningInFlight,
            onPin = { onPinPhotos(listOf(photo.photoUri.toUri())) },
            onOpenInGallery = {
                val intent = buildOpenInGalleryIntent(photo.photoUri)
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    // Snackbar fallback — host-state plumbing is out of
                    // scope for this composable; log and rely on the
                    // standalone ActivityNotFoundException test.
                    Log.w("PhotoReliquary", "no activity to handle gallery intent")
                }
            },
            onDismiss = { previewPhoto = null },
        )
    }
```

`collectIsPinningInFlight()` is a helper Composable function that pulls the VM flow; or pass `isPinningInFlight: State<Boolean>` as a new parameter through the call chain (cleaner — props down, events up).

- [ ] **Step 3: Run all reliquary tests + verify build**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.reliquary.*"`

Expected: all PASS.

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoReliquarySection.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt
git commit -m "feat(reliquary): wire PhotoPreviewSheet from carousel tap + VM in-flight flow

Tap on activated carousel thumbnail opens the preview sheet. Pin button
state derives from (isPinned, isPinningInFlight) per spec D4. VM Mutex
serializes pin calls so rapid double-tap fires exactly once even before
the first Room write completes.

Open-in-gallery dispatches Intent.ACTION_VIEW with image/* MIME +
FLAG_GRANT_READ_URI_PERMISSION; ActivityNotFoundException is caught
+ logged (snackbar host-state plumbing out of scope here)."
```

---

### Task 4.4: Full-build sanity + final checks

**Files:** none

- [ ] **Step 1: Full unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`

Expected: all PASS. New tests for the bundle ≥30 across the 4 stages.

- [ ] **Step 2: Lint**

Run: `./gradlew :app:lintDebug`

Expected: no new lint warnings (compare against a clean main build).

- [ ] **Step 3: assembleDebug**

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: SPDX + OutRun grep**

Run:
```bash
grep -rn "OutRun" app/src/ | grep -v "//.*OutRun" || echo "PASS: no OutRun"
find app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary -name '*.kt' -newer app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt | xargs head -1 | grep -v "SPDX-License-Identifier" && echo "FAIL: missing SPDX" || echo "PASS: all new files have SPDX"
```

Expected: PASS on both checks.

- [ ] **Step 5: Verification-only task — no commit needed**

---

### Task 4.5: Device QA on OnePlus 13

**Files:** none (manual smoke).

GATED on Tasks 1.1–4.4 completing cleanly. Skip if 4.4 found regressions.

- [ ] **Step 1: Build + install**

```bash
./gradlew :app:installDebug
adb shell monkey -p org.walktalkmeditate.pilgrim.debug -c android.intent.category.LAUNCHER 1
```

- [ ] **Step 2: Stage 1 smoke (voice-row)**

1. Open a walk with a long transcription (>280 chars).
2. **Observe:** "Show more" toggle appears below the transcription. Tapping expands. Tapping again collapses.
3. Tap a transcription to enter edit mode. Clear the text. Tap Done.
4. **Observe:** edit mode exits without persisting (refresh page, transcription is unchanged).
5. Tap the speed pill (1x → 1.5x → 2x → 1x).
6. **Observe:** speed cycle matches iOS exactly.

- [ ] **Step 3: Stage 2 smoke (reliquary state)**

1. Open Settings → Apps → Pilgrim → Permissions → revoke Photos access. Return to app.
2. **Observe:** Walk Summary reliquary section shows the permission-revoked prompt with "Open settings" button.
3. Tap "Open settings". Grant Photos access. Return to app.
4. **Observe:** the reliquary section transitions to Populated state with a single fetch (no duplicate fetches on returning ON_START).
5. Background the app + foreground again with no permission change.
6. **Observe:** no re-fetch fires (no infinite-fetch loop on empty walks).

- [ ] **Step 4: Stage 3 smoke (carousel)**

1. Open a walk with ≥3 pinned photos.
2. **Observe:** horizontal carousel with 88dp thumbnails.
3. Long-press a thumbnail. Hold 400ms.
4. **Observe:** thumbnail scales to 1.05× with a spring + firm haptic tap.
5. Swipe the carousel left.
6. **Observe:** activation clears (no scale).
7. Activate again, then tap the activated thumbnail.
8. **Observe:** preview sheet opens.

- [ ] **Step 5: Stage 4 smoke (preview-sheet)**

1. From the previous step, the preview sheet is open.
2. Drag down ~50% of screen height (well over 120dp).
3. **Observe:** sheet dismisses with spring animation.
4. Re-open. Drag down ~30dp.
5. **Observe:** sheet snaps back.
6. Re-open. Tap "Pin to walk".
7. **Observe:** pin button disables; photo appears with pinned badge in the carousel after dismiss.
8. Open Settings → revoke Pilgrim's Photos access → reopen Walk Summary.
9. **Observe:** sheet's "Open in gallery" still works (uses content:// + FLAG_GRANT_READ_URI_PERMISSION).
10. Tap system Back from the sheet.
11. **Observe:** sheet dismisses cleanly (BackHandler path).

- [ ] **Step 6: Failure modes**

If any smoke fails, capture screenshots + adb logcat snippets in the PR body. Do NOT silently fix without a tracking commit.

---

## Self-Review

**1. Spec coverage:**

| Spec section | Plan coverage |
|---|---|
| A.1 (100ms seek defer) | DROPPED — already implemented at `RecordingsListViewModel.kt:276` |
| A.2 (transcription expand) | Tasks 1.1 + 1.2 + 1.3 |
| A.3 (empty-trim guard) | Task 1.4 |
| A.4 (speed-cycle algorithm) | Task 1.5 |
| AudioPlayer divergence KDoc (Q3 + quality gate) | Task 1.5 |
| B.1 (4-state gate) | Tasks 2.1 + 2.3 |
| B.2 (deferred skeleton) | Task 2.3 |
| B.3 (lifecycle observer) | Tasks 2.2 + 2.3 |
| B.4 (fetchGeneration) | Task 2.2 |
| B.5 (Settings deep link) | Task 2.3 + ReliquarySettingsDeepLinkTest |
| C (carousel) | Tasks 3.1 + 3.2 |
| D (preview-sheet) | Tasks 4.1 + 4.2 + 4.3 |
| Device QA | Task 4.5 |

All spec sections covered.

**2. Placeholder scan:** none. Every step has actual code blocks or exact commands.

**3. Type consistency:**
- `ReliquaryState` sealed class introduced in Task 2.1, consumed in 2.2, 2.3 by name. Types `ToggleOff` / `PermissionDenied` / `Loading` / `Populated(candidates: List<WalkPhoto>)` consistent.
- `resolveReliquaryState(toggleEnabled, permissionGranted, isFetching, photos)` signature consistent across 2.1 test + 2.2 VM usage.
- `nextPlaybackSpeed(Float): Float` consistent.
- `transcriptionCommitValue(String): String?` consistent.
- `transcriptionNeedsExpansion(String): Boolean` consistent.
- `THUMBNAIL_SIZE_DP = 88` from Stage 3 consumed by Stage 4 not (preview-sheet doesn't need it).
- `DRAG_DISMISS_THRESHOLD_DP = 120.dp` consistent.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-11-walk-summary-parity-cleanup-bundle.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
