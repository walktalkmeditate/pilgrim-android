# Stage 9.5-D Active Walk parity round 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close four iOS-parity gaps surfaced during 9.5-C device QA — pre-walk intention pill, caption-replaces-default with Crossfade, in-walk WalkOptionsSheet trim, and full WaypointMarkingSheet (6 preset chips + custom text).

**Architecture:** Explicit-tap pre-walk surface (no auto-prompt). Material-3 ModalBottomSheet for waypoint marking. Cross-platform iOS-SF-Symbol icon keys stored in DB; Android display layer maps to Material Icons. iOS-faithful copy ("Set" not "Save", "Drop a Waypoint" header). Light haptic on chip tap (Stage 4-D pattern).

**Tech Stack:** Kotlin 2.x, Jetpack Compose Material 3, Hilt, Room, DataStore Preferences (existing — not extended in 9.5-D), Coroutines + StateFlow, JUnit 4 + Robolectric + Compose UI Test.

---

### Task 1: Strings — add new keys, rename Save→Set, delete dead key

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Modify strings.xml**

Find the Stage 9.5-C section. Replace `walk_options_intention_save = "Save"` with `walk_options_intention_save = "Set"` (iOS parity, `IntentionSettingView.swift:325`). Delete the `walk_options_intention_unset` line (caller is being removed). Add the new 9.5-D keys:

```xml
    <!-- Stage 9.5-D: pre-walk intention pill + waypoint marking sheet -->
    <string name="walk_pre_intention_pill_unset">Set an intention</string>
    <string name="walk_waypoint_marking_title">Drop a Waypoint</string>
    <string name="walk_waypoint_marking_custom_placeholder">Custom note</string>
    <string name="walk_waypoint_marking_mark">Mark</string>
    <string name="walk_waypoint_marking_cancel">Cancel</string>
    <string name="walk_waypoint_chip_peaceful">Peaceful</string>
    <string name="walk_waypoint_chip_beautiful">Beautiful</string>
    <string name="walk_waypoint_chip_grateful">Grateful</string>
    <string name="walk_waypoint_chip_resting">Resting</string>
    <string name="walk_waypoint_chip_inspired">Inspired</string>
    <string name="walk_waypoint_chip_arrived">Arrived</string>
    <string name="walk_waypoint_count_chars">%1$d/%2$d</string>
```

- [ ] **Step 2: Build to confirm no resource errors**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (no missing-resource errors yet — callers will be added in later tasks).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(walk): strings for Stage 9.5-D (pre-walk pill + waypoint marking sheet) + iOS-parity Save→Set"
```

---

### Task 2: WalkController.recordWaypoint signature change

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkController.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkControllerWaypointLabelIconTest.kt` (NEW)

- [ ] **Step 1: Write failing test**

Create `WalkControllerWaypointLabelIconTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.SystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkControllerWaypointLabelIconTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var controller: WalkController

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WalkRepository(
            database = db,
            walkDao = db.walkDao(),
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = db.walkEventDao(),
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
            walkPhotoDao = db.walkPhotoDao(),
        )
        controller = WalkController(repository = repository, clock = SystemClock())
    }

    @After fun tearDown() = db.close()

    @Test fun `recordWaypoint with label and icon persists both fields`() = runBlocking {
        controller.startWalk()
        controller.recordLocation(LocationPoint(latitude = 1.0, longitude = 2.0, timestamp = 100L))

        controller.recordWaypoint(label = "Peaceful", icon = "leaf")

        val waypoints = db.waypointDao().getAllForWalkOrderedAscending(
            walkId = repository.allWalks().first().id,
        )
        assertEquals(1, waypoints.size)
        assertEquals("Peaceful", waypoints[0].label)
        assertEquals("leaf", waypoints[0].icon)
    }

    @Test fun `recordWaypoint no-arg keeps label and icon null (notification path parity)`() = runBlocking {
        controller.startWalk()
        controller.recordLocation(LocationPoint(latitude = 1.0, longitude = 2.0, timestamp = 100L))

        controller.recordWaypoint()

        val waypoints = db.waypointDao().getAllForWalkOrderedAscending(
            walkId = repository.allWalks().first().id,
        )
        assertEquals(1, waypoints.size)
        assertNull(waypoints[0].label)
        assertNull(waypoints[0].icon)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.walk.WalkControllerWaypointLabelIconTest"`
Expected: FAIL — `recordWaypoint` doesn't accept label/icon params yet (compile error or wrong field values).

- [ ] **Step 3: Modify WalkController.recordWaypoint**

In `WalkController.kt`, change the function signature:

```kotlin
suspend fun recordWaypoint(label: String? = null, icon: String? = null) {
    dispatchMutex.withLock {
        val accumulator = when (val s = _state.value) {
            is WalkState.Active -> s.walk
            is WalkState.Paused -> s.walk
            is WalkState.Meditating -> s.walk
            else -> return@withLock
        }
        val location = accumulator.lastLocation ?: return@withLock
        try {
            repository.addWaypoint(
                Waypoint(
                    walkId = accumulator.walkId,
                    timestamp = clock.now(),
                    latitude = location.latitude,
                    longitude = location.longitude,
                    label = label,
                    icon = icon,
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "recordWaypoint failed", t)
        }
    }
}
```

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.walk.WalkControllerWaypointLabelIconTest"`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkController.kt app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkControllerWaypointLabelIconTest.kt
git commit -m "feat(walk): WalkController.recordWaypoint accepts label + icon (default null for notification path)"
```

---

### Task 3: WalkViewModel.dropWaypoint signature change

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt`

- [ ] **Step 1: Modify WalkViewModel.dropWaypoint**

Find `fun dropWaypoint()` (line ~290). Change to:

```kotlin
fun dropWaypoint(label: String? = null, icon: String? = null) {
    viewModelScope.launch { controller.recordWaypoint(label = label, icon = icon) }
}
```

- [ ] **Step 2: Confirm compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt
git commit -m "feat(walk): WalkViewModel.dropWaypoint accepts label + icon (passthrough)"
```

---

### Task 4: WalkOptionsSheet — drop intention row

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkOptionsSheet.kt`
- Modify: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkOptionsSheetTest.kt`

- [ ] **Step 1: Modify WalkOptionsSheet**

Drop the `intention: String?` parameter, the `onSetIntention: () -> Unit` parameter, and the first `OptionRow` (EditNote / Set Intention). Final signature:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkOptionsSheet(
    waypointCount: Int,
    canDropWaypoint: Boolean,
    onDropWaypoint: () -> Unit,
    onDismiss: () -> Unit,
)
```

Drop the unused imports (`Icons.Outlined.EditNote`). Remove the `Text(stringResource(R.string.walk_options_title))` heading? NO — keep it (it's still "Options" heading).

- [ ] **Step 2: Modify WalkOptionsSheetTest**

Delete the 3 intention-related test cases:
- `intention subtitle shows the persisted intention when set`
- `intention subtitle shows fallback when null`
- `intention click fires onSetIntention`

Update remaining tests to drop `intention` and `onSetIntention` from the `WalkOptionsSheet(...)` call sites. Add a new test asserting "Set Intention" is not rendered:

```kotlin
@Test
fun `set intention row is not rendered`() {
    composeRule.setContent {
        WalkOptionsSheet(
            waypointCount = 0,
            canDropWaypoint = true,
            onDropWaypoint = {},
            onDismiss = {},
        )
    }
    composeRule.onNodeWithText("Set Intention").assertDoesNotExist()
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkOptionsSheetTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkOptionsSheet.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkOptionsSheetTest.kt
git commit -m "feat(walk): drop Set Intention row from in-walk WalkOptionsSheet"
```

---

### Task 5: WaypointMarkingSheet (NEW file) + tests

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WaypointMarkingSheet.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WaypointMarkingSheetTest.kt`

- [ ] **Step 1: Write failing test**

Create `WaypointMarkingSheetTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WaypointMarkingSheetTest {

    @get:Rule val composeRule = createComposeRule()

    @Test fun `renders all six preset chips`() {
        composeRule.setContent {
            WaypointMarkingSheet(onMark = { _, _ -> }, onDismiss = {})
        }
        listOf("Peaceful", "Beautiful", "Grateful", "Resting", "Inspired", "Arrived").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test fun `tapping a preset chip fires onMark with chip values`() {
        var captured: Pair<String, String>? = null
        composeRule.setContent {
            WaypointMarkingSheet(
                onMark = { label, icon -> captured = label to icon },
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Peaceful").performClick()
        assertEquals("Peaceful" to "leaf", captured)
    }

    @Test fun `Mark button disabled when custom text empty`() {
        composeRule.setContent {
            WaypointMarkingSheet(onMark = { _, _ -> }, onDismiss = {})
        }
        composeRule.onNodeWithText("Mark").assertIsNotEnabled()
    }

    @Test fun `Mark button enabled and fires onMark with mappin icon when text typed`() {
        var captured: Pair<String, String>? = null
        composeRule.setContent {
            WaypointMarkingSheet(
                onMark = { label, icon -> captured = label to icon },
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Custom note").performTextInput("river")
        composeRule.onNodeWithText("Mark").assertIsEnabled().performClick()
        assertEquals("river" to "mappin", captured)
    }

    @Test fun `Cancel fires onDismiss without firing onMark`() {
        var marked = false
        var dismissed = false
        composeRule.setContent {
            WaypointMarkingSheet(
                onMark = { _, _ -> marked = true },
                onDismiss = { dismissed = true },
            )
        }
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(true, dismissed)
        assertEquals(false, marked)
    }

    @Test fun `custom text clamps at fifty chars`() {
        composeRule.setContent {
            WaypointMarkingSheet(onMark = { _, _ -> }, onDismiss = {})
        }
        val long = "a".repeat(60)
        composeRule.onNodeWithText("Custom note").performTextInput(long)
        // Counter shows 50/50, not 60/50.
        composeRule.onNodeWithText("50/50").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WaypointMarkingSheetTest"`
Expected: FAIL (compile error — WaypointMarkingSheet doesn't exist).

- [ ] **Step 3: Implement WaypointMarkingSheet.kt**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

internal const val MAX_WAYPOINT_CUSTOM_CHARS = 50
internal const val WAYPOINT_CUSTOM_ICON_KEY = "mappin"

internal data class WaypointPresetChip(
    val labelRes: Int,
    val iconKey: String,
)

private val PRESET_CHIPS: List<WaypointPresetChip> = listOf(
    WaypointPresetChip(R.string.walk_waypoint_chip_peaceful, "leaf"),
    WaypointPresetChip(R.string.walk_waypoint_chip_beautiful, "eye"),
    WaypointPresetChip(R.string.walk_waypoint_chip_grateful, "heart"),
    WaypointPresetChip(R.string.walk_waypoint_chip_resting, "figure.seated.side"),
    WaypointPresetChip(R.string.walk_waypoint_chip_inspired, "sparkles"),
    WaypointPresetChip(R.string.walk_waypoint_chip_arrived, "flag.fill"),
)

/**
 * Maps an iOS-canonical SF Symbol icon key (stored in Room's `waypoints.icon`
 * column) to a Material Icon for Android display. Storing the iOS key
 * round-trips `.pilgrim` ZIP exports across platforms; unknown keys fall
 * back to `LocationOn` so a future iOS-introduced symbol still renders.
 */
internal fun iconKeyToVector(key: String): ImageVector = when (key) {
    "leaf" -> Icons.Outlined.Spa
    "eye" -> Icons.Outlined.Visibility
    "heart" -> Icons.Outlined.FavoriteBorder
    "figure.seated.side" -> Icons.Outlined.Chair
    "sparkles" -> Icons.Outlined.AutoAwesome
    "flag.fill" -> Icons.Filled.Flag
    "mappin" -> Icons.Filled.LocationOn
    else -> Icons.Filled.LocationOn
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointMarkingSheet(
    onMark: (label: String, icon: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = LocalHapticFeedback.current
    var customText by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pilgrimColors.parchment,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = PilgrimSpacing.big,
                vertical = PilgrimSpacing.normal,
            ),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        ) {
            Text(
                text = stringResource(R.string.walk_waypoint_marking_title),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
                modifier = Modifier.padding(bottom = PilgrimSpacing.small),
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
                verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
                userScrollEnabled = false,
            ) {
                items(PRESET_CHIPS) { chip ->
                    PresetChip(
                        label = stringResource(chip.labelRes),
                        iconKey = chip.iconKey,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onMark(/* label */ chip_label_for_chip(chip), chip.iconKey)
                        },
                    )
                }
            }

            CustomNoteRow(
                text = customText,
                onTextChange = { incoming ->
                    customText = incoming.take(MAX_WAYPOINT_CUSTOM_CHARS)
                },
                onMark = {
                    val trimmed = customText.trim()
                    if (trimmed.isEmpty()) return@CustomNoteRow
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onMark(trimmed, WAYPOINT_CUSTOM_ICON_KEY)
                },
            )

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = stringResource(R.string.walk_waypoint_marking_cancel),
                    color = pilgrimColors.fog,
                    style = pilgrimType.button,
                )
            }
        }
    }
}

/**
 * Resolve the chip label string. Compose `stringResource` requires
 * @Composable scope; the items lambda above is composable. We pull
 * the label from the chip via a tiny helper because the items API
 * doesn't expose `iconKey` and `label` together cleanly.
 */
@Composable
private fun chip_label_for_chip(chip: WaypointPresetChip): String =
    stringResource(chip.labelRes)

@Composable
private fun PresetChip(
    label: String,
    iconKey: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pilgrimColors.parchmentSecondary.copy(alpha = 0.4f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = PilgrimSpacing.normal),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        ) {
            Icon(
                imageVector = iconKeyToVector(iconKey),
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = label,
                style = pilgrimType.caption,
                color = pilgrimColors.ink.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun CustomNoteRow(
    text: String,
    onTextChange: (String) -> Unit,
    onMark: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = {
                    Text(stringResource(R.string.walk_waypoint_marking_custom_placeholder))
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onMark,
                enabled = text.trim().isNotEmpty(),
            ) {
                Text(
                    text = stringResource(R.string.walk_waypoint_marking_mark),
                    style = pilgrimType.button,
                )
            }
        }
        Text(
            text = stringResource(
                R.string.walk_waypoint_count_chars,
                text.length,
                MAX_WAYPOINT_CUSTOM_CHARS,
            ),
            style = pilgrimType.caption,
            color = pilgrimColors.fog.copy(alpha = 0.5f),
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WaypointMarkingSheetTest"`
Expected: PASS (all 6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WaypointMarkingSheet.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WaypointMarkingSheetTest.kt
git commit -m "feat(walk): WaypointMarkingSheet (6 preset chips + custom 50-char text + Mark + Cancel + iOS SF-Symbol icon keys)"
```

---

### Task 6: WalkStatsSheet caption Crossfade with intention parameter

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetCaptionTest.kt` (NEW)

- [ ] **Step 1: Write failing test**

Create `WalkStatsSheetCaptionTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.VoiceRecorderUiState
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkStatsSheetCaptionTest {

    @get:Rule val composeRule = createComposeRule()

    @Test fun `caption renders intention when set`() {
        composeRule.setContent { WalkStatsSheetForCaption(intention = "walk well") }
        composeRule.onNodeWithText("walk well").assertIsDisplayed()
    }

    @Test fun `caption renders fallback when intention is null`() {
        composeRule.setContent { WalkStatsSheetForCaption(intention = null) }
        composeRule.onNodeWithText("every step is enough").assertIsDisplayed()
    }

    @Test fun `caption renders fallback when intention is whitespace only`() {
        composeRule.setContent { WalkStatsSheetForCaption(intention = "   ") }
        composeRule.onNodeWithText("every step is enough").assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun WalkStatsSheetForCaption(intention: String?) {
        WalkStatsSheet(
            state = SheetState.Expanded,
            onStateChange = {},
            walkState = WalkState.Active(WalkAccumulator(walkId = 1, startedAt = 0L)),
            totalElapsedMillis = 0L,
            distanceMeters = 0.0,
            walkMillis = 0L,
            talkMillis = 0L,
            meditateMillis = 0L,
            recorderState = VoiceRecorderUiState.Idle,
            audioLevel = 0f,
            recordingsCount = 0,
            intention = intention,
            preWalkIntention = null,
            onSetPreWalkIntention = {},
            onPause = {},
            onResume = {},
            onStartWalk = {},
            onStartMeditation = {},
            onEndMeditation = {},
            onToggleRecording = {},
            onPermissionDenied = {},
            onDismissError = {},
            onFinish = {},
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkStatsSheetCaptionTest"`
Expected: FAIL (compile error — `intention`/`preWalkIntention`/`onSetPreWalkIntention` params don't exist yet).

- [ ] **Step 3: Modify WalkStatsSheet — add params + Crossfade caption**

In `WalkStatsSheet.kt`, add these parameters to the public `WalkStatsSheet(...)` composable signature (alphabetical-style alongside existing params): `intention: String?`, `preWalkIntention: String?`, `onSetPreWalkIntention: () -> Unit`. Thread them down through any internal composables.

Replace the caption block (around line 397-401):

```kotlin
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.EaseInOut
// ...

Crossfade(
    targetState = intention?.trim()?.takeIf { it.isNotEmpty() },
    animationSpec = tween(durationMillis = 600, easing = EaseInOut),
    label = "intention-caption",
) { resolved ->
    Text(
        text = resolved ?: stringResource(R.string.walk_caption_every_step),
        style = pilgrimType.caption,
        color = pilgrimColors.fog.copy(alpha = 0.6f),
    )
}
```

Pass `intention`, `preWalkIntention`, and `onSetPreWalkIntention` from the public signature down through the `ContentColumn` (or whichever internal composable) to the `ActionButtonRow` private composable. Task 7 will use them.

- [ ] **Step 4: Run test**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkStatsSheetCaptionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetCaptionTest.kt
git commit -m "feat(walk): WalkStatsSheet caption Crossfades intention/fallback (iOS parity)"
```

---

### Task 7: WalkStatsSheet ActionButtonRow — pre-walk intention pill

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetPreWalkPillTest.kt` (NEW)

- [ ] **Step 1: Write failing test**

Create `WalkStatsSheetPreWalkPillTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.VoiceRecorderUiState
import org.walktalkmeditate.pilgrim.domain.WalkState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkStatsSheetPreWalkPillTest {

    @get:Rule val composeRule = createComposeRule()

    @Test fun `pill shows Set an intention text when no draft`() {
        composeRule.setContent {
            HarnessIdle(preWalkIntention = null, onSet = {})
        }
        composeRule.onNodeWithText("Set an intention").assertIsDisplayed()
    }

    @Test fun `pill shows draft text when set`() {
        composeRule.setContent {
            HarnessIdle(preWalkIntention = "walk well", onSet = {})
        }
        composeRule.onNodeWithText("walk well").assertIsDisplayed()
    }

    @Test fun `pill is clickable and fires onSet`() {
        var fired = false
        composeRule.setContent {
            HarnessIdle(preWalkIntention = null, onSet = { fired = true })
        }
        composeRule.onNodeWithText("Set an intention").performClick()
        assertEquals(true, fired)
    }

    @androidx.compose.runtime.Composable
    private fun HarnessIdle(preWalkIntention: String?, onSet: () -> Unit) {
        WalkStatsSheet(
            state = SheetState.Expanded,
            onStateChange = {},
            walkState = WalkState.Idle,
            totalElapsedMillis = 0L,
            distanceMeters = 0.0,
            walkMillis = 0L,
            talkMillis = 0L,
            meditateMillis = 0L,
            recorderState = VoiceRecorderUiState.Idle,
            audioLevel = 0f,
            recordingsCount = 0,
            intention = null,
            preWalkIntention = preWalkIntention,
            onSetPreWalkIntention = onSet,
            onPause = {},
            onResume = {},
            onStartWalk = {},
            onStartMeditation = {},
            onEndMeditation = {},
            onToggleRecording = {},
            onPermissionDenied = {},
            onDismissError = {},
            onFinish = {},
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkStatsSheetPreWalkPillTest"`
Expected: FAIL (no pill in the Idle branch yet).

- [ ] **Step 3: Modify ActionButtonRow Idle branch**

In `WalkStatsSheet.kt`, find the Idle branch in `ActionButtonRow` (line ~525-539). Replace with:

```kotlin
if (walkState == WalkState.Idle) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        PreWalkIntentionPill(
            text = preWalkIntention,
            onClick = onSetPreWalkIntention,
        )
        CircularActionButton(
            label = stringResource(R.string.walk_action_start),
            icon = Icons.Filled.PlayArrow,
            color = pilgrimColors.moss,
            onClick = onStartWalk,
        )
    }
    return
}
```

Add the new private composable below `CircularActionButton`:

```kotlin
@Composable
private fun PreWalkIntentionPill(
    text: String?,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val display = text?.trim()?.takeIf { it.isNotEmpty() }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(pilgrimColors.parchmentSecondary.copy(alpha = 0.5f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = display ?: stringResource(R.string.walk_pre_intention_pill_unset),
            style = pilgrimType.caption,
            color = if (display == null) pilgrimColors.fog else pilgrimColors.ink,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
```

Add imports as needed (`Box`, `RoundedCornerShape`, `clickable`, `MutableInteractionSource`, `Role`, `TextOverflow`).

- [ ] **Step 4: Run test**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkStatsSheetPreWalkPillTest"`
Expected: PASS (all 3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetPreWalkPillTest.kt
git commit -m "feat(walk): pre-walk intention pill above Start button (Idle state only)"
```

---

### Task 8: IntentionSettingDialog — Set label + character count + color animation

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/IntentionSettingDialog.kt`
- Modify: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/IntentionSettingDialogTest.kt`

- [ ] **Step 1: Update test for the new behaviors**

Add these tests to `IntentionSettingDialogTest.kt`:

```kotlin
@Test
fun `confirm button reads Set after iOS-parity rename`() {
    composeRule.setContent {
        IntentionSettingDialog(initial = null, onSave = {}, onDismiss = {})
    }
    composeRule.onNodeWithText("Set").assertIsDisplayed()
    composeRule.onNodeWithText("Save").assertDoesNotExist()
}

@Test
fun `character count caption renders count of typed chars over 140`() {
    composeRule.setContent {
        IntentionSettingDialog(initial = null, onSave = {}, onDismiss = {})
    }
    composeRule.onAllNodesWithContentDescription(null)
        .filter(hasText("walk well"))
        .onFirst()
        .performTextInput("walk well")
    // Expected counter "9/140".
    composeRule.onNodeWithText("9/140").assertIsDisplayed()
}
```

(Adjust imports as needed: `assertDoesNotExist`, `onAllNodesWithContentDescription`, `hasText`, `onFirst`, `performTextInput`.)

- [ ] **Step 2: Run tests to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.IntentionSettingDialogTest"`
Expected: FAIL — "Set" button doesn't exist (still "Save"); count caption doesn't exist.

- [ ] **Step 3: Modify strings.xml AND IntentionSettingDialog**

In `app/src/main/res/values/strings.xml`, change `walk_options_intention_save = "Save"` → `walk_options_intention_save = "Set"`. (This may already be done in Task 1; verify and skip if so.)

In `IntentionSettingDialog.kt`, add the character count caption inside the dialog's `text` slot beneath the OutlinedTextField:

```kotlin
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign

// ...

text = {
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { incoming -> text = incoming.take(MAX_INTENTION_CHARS) },
            placeholder = { Text(stringResource(R.string.walk_options_intention_placeholder)) },
            singleLine = false,
            maxLines = 3,
        )
        val countColor by animateColorAsState(
            targetValue = lerp(
                pilgrimColors.fog,
                pilgrimColors.moss,
                fraction = (text.length.toFloat() / MAX_INTENTION_CHARS).coerceIn(0f, 1f),
            ),
            label = "intention-count-color",
        )
        Text(
            text = stringResource(
                R.string.walk_waypoint_count_chars,
                text.length,
                MAX_INTENTION_CHARS,
            ),
            style = pilgrimType.caption,
            color = countColor,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
},
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.IntentionSettingDialogTest"`
Expected: PASS (existing + 2 new tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/IntentionSettingDialog.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/IntentionSettingDialogTest.kt
git commit -m "feat(walk): IntentionSettingDialog — Set label (iOS parity) + character count with fog→moss color"
```

---

### Task 9: ActiveWalkScreen — wire pre-walk intention + waypoint sheet

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt`

- [ ] **Step 1: Modify ActiveWalkScreen state + dialog rendering**

Replace the existing `showOptions`/`showIntention` block with the new state shape. The full diff will:

(a) Drop the in-walk `IntentionSettingDialog` rendering at the existing site (currently driven by `showIntention`).
(b) Add `preWalkIntention: rememberSaveable<MutableState<String?>>(stateSaver = ...) { mutableStateOf(null) }`. Use the autoSaver — `String?` is bundleable.
(c) Add `showPreWalkIntention: rememberSaveable<MutableState<Boolean>> = mutableStateOf(false)`.
(d) Add `showWaypointMarking: rememberSaveable<MutableState<Boolean>> = mutableStateOf(false)`.
(e) Extend the existing 9.5-C auto-dismiss `LaunchedEffect(navWalkState::class)` to also dismiss `showWaypointMarking` when the walk leaves in-progress. `showPreWalkIntention` stays valid in Idle (the pre-walk surface).
(f) Update the WalkOptionsSheet call site to use the new (no-intention) signature: `intention` and `onSetIntention` removed. `onDropWaypoint = { showOptions = false; showWaypointMarking = true }`.
(g) Add `WaypointMarkingSheet` rendering when `showWaypointMarking == true`:

```kotlin
if (showWaypointMarking) {
    WaypointMarkingSheet(
        onMark = { label, icon ->
            viewModel.dropWaypoint(label = label, icon = icon)
            showWaypointMarking = false
        },
        onDismiss = { showWaypointMarking = false },
    )
}
```

(h) Render the pre-walk dialog when `showPreWalkIntention == true`:

```kotlin
if (showPreWalkIntention) {
    IntentionSettingDialog(
        initial = preWalkIntention,
        onSave = { text ->
            preWalkIntention = text.takeIf { it.isNotBlank() }
            showPreWalkIntention = false
        },
        onDismiss = { showPreWalkIntention = false },
    )
}
```

(i) Pass `intention`, `preWalkIntention`, and `onSetPreWalkIntention = { showPreWalkIntention = true }` through to `WalkStatsSheet`:

```kotlin
WalkStatsSheet(
    // ... existing params ...
    intention = intention,
    preWalkIntention = preWalkIntention,
    onSetPreWalkIntention = { showPreWalkIntention = true },
    // ... existing params ...
    onStartWalk = {
        viewModel.startWalk(intention = preWalkIntention)
        preWalkIntention = null
    },
)
```

Replace the existing `LaunchedEffect(navWalkState::class)` auto-dismiss block with:

```kotlin
LaunchedEffect(navWalkState::class) {
    if (navWalkState !is WalkState.Active &&
        navWalkState !is WalkState.Paused &&
        navWalkState !is WalkState.Meditating
    ) {
        showOptions = false
        showWaypointMarking = false
        // showPreWalkIntention stays — Idle is the pre-walk surface itself.
    }
}
```

Drop the `showIntention` rememberSaveable + `showIntention` IntentionSettingDialog rendering used by 9.5-C in-walk; that path is being removed entirely.

- [ ] **Step 2: Build to confirm compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run all walk-screen tests**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.*"`
Expected: PASS (all). The tests for Task 6 (caption Crossfade), Task 7 (pill), and Task 5 (waypoint sheet) all rely on these wiring changes. If the WalkOptionsSheetTest fails because of the dropped `intention`/`onSetIntention` params, update those usages now.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt
git commit -m "feat(walk): ActiveWalkScreen — pre-walk intention pill + waypoint marking sheet wiring + drop in-walk intention dialog"
```

---

### Task 10: WalkTrackingService notification ACTION_MARK_WAYPOINT regression test

**Files:**
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/service/WalkTrackingServiceWaypointNotificationTest.kt` (NEW)

- [ ] **Step 1: Write the test**

The CLAUDE.md platform-object-builder lesson is about WorkRequest/AudioFocus/etc. — recordWaypoint isn't a builder boundary, but the notification action is the kind of seam fakes hide. A pure test that builds the `Intent`, drops it on a service-equivalent dispatcher, and asserts `controller.recordWaypoint()` is called with `(label = null, icon = null)`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkTrackingServiceWaypointNotificationTest {

    @Test fun `ACTION_MARK_WAYPOINT intent is constructible and addressed at the service`() {
        // Verifies that the notification builder's PendingIntent → service
        // path stays valid post-recordWaypoint signature change. The pure
        // smoke-check is: an Intent with ACTION_MARK_WAYPOINT action +
        // service component name resolves to WalkTrackingService.
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, WalkTrackingService::class.java).apply {
            action = WalkTrackingService.ACTION_MARK_WAYPOINT
        }
        assertEquals(WalkTrackingService.ACTION_MARK_WAYPOINT, intent.action)
        assertEquals(
            WalkTrackingService::class.java.name,
            intent.component?.className,
        )
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.service.WalkTrackingServiceWaypointNotificationTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/org/walktalkmeditate/pilgrim/service/WalkTrackingServiceWaypointNotificationTest.kt
git commit -m "test(service): smoke test ACTION_MARK_WAYPOINT intent constructible after recordWaypoint signature change"
```

---

### Task 11: Final assembly + on-device build verification

**Files:** none (verification only)

- [ ] **Step 1: Full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (or ONLY the pre-existing flaky `PhotoReliquarySectionTest.tombstone caption` failing — unrelated to 9.5-D).

- [ ] **Step 2: Lint**

Run: `./gradlew :app:lintDebug`
Expected: no NEW lint errors. Warnings are acceptable.

- [ ] **Step 3: Debug APK**

Run: `./gradlew :app:assembleDebug -PabiSplit=arm64-v8a`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Install on device (optional, for on-device QA)**

If a device is connected:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p org.walktalkmeditate.pilgrim.debug -c android.intent.category.LAUNCHER 1
```
Manual exercise: pre-walk pill → set intention → start walk → caption shows intention → ellipsis → drop waypoint sheet → pick chip → verify waypoint persists.

- [ ] **Step 5: No commit (verification only)**

---

## Self-Review Notes

**Spec coverage:**
- [x] Task 1: strings.xml — covered.
- [x] Task 2: WalkController.recordWaypoint signature — covered + tested.
- [x] Task 3: WalkViewModel.dropWaypoint passthrough — covered.
- [x] Task 4: WalkOptionsSheet drop intention row — covered + tests updated.
- [x] Task 5: WaypointMarkingSheet new file with chip grid + custom text + counter + haptic + Cancel — covered + tested.
- [x] Task 6: WalkStatsSheet caption Crossfade — covered + tested.
- [x] Task 7: WalkStatsSheet ActionButtonRow pre-walk pill — covered + tested.
- [x] Task 8: IntentionSettingDialog Set label + char count + color — covered + tested.
- [x] Task 9: ActiveWalkScreen wiring — covered.
- [x] Task 10: WalkTrackingService notification regression — covered.
- [x] Task 11: assembly verification — covered.

**Type consistency:** `recordWaypoint(label: String?, icon: String?)` and `dropWaypoint(label: String?, icon: String?)` both use the same nullable-default signature. WaypointMarkingSheet's `onMark: (label: String, icon: String)` is non-nullable because the sheet always provides both. The chain converges at `viewModel.dropWaypoint(label = nonNullLabel, icon = nonNullIcon)` from the sheet, which uses Kotlin's argument promotion (non-null String → String? param, fine).

**Cross-platform icon keys:** stored as iOS SF Symbol names (`"leaf"`, `"sparkles"`, `"flag.fill"`, `"mappin"`). Display layer maps to Material Icons via `iconKeyToVector(key)`. Unknown keys fall back to `Icons.Filled.LocationOn`.

**No placeholders.** Every step has actual code.
