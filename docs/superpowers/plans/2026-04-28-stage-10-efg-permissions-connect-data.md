# Stage 10-EFG Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land three iOS-parity Settings cards (Permissions, Connect+Feedback, Data+RecordingsExport) plus stubs for the deferred Walks export / Journey viewer (Stage 10-I).

**Architecture:** All three cards reuse the existing `Modifier.settingsCard()` chrome + `CardHeader` + `SettingNavRow` toolkit. New ViewModels follow the established `WhileSubscribed(5_000) + StateFlow + viewModelScope.launch + runCatching` pattern. FeedbackService decorates the project-wide `OkHttpClient` from `NetworkModule` via a `@FeedbackHttpClient` qualifier (matches `CollectiveModule` / `ShareModule`). Recordings export writes a ZIP under `cacheDir/recordings_export/` and shares via the existing FileProvider authority. The full `.pilgrim` walks builder/importer is **deferred to Stage 10-I**.

**Tech Stack:** Kotlin 2.x, Jetpack Compose, Material 3, Hilt, DataStore Preferences, OkHttp 5, kotlinx-serialization, Room (read-only here via existing repos), `androidx.browser` (new — Custom Tabs).

**Source spec:** `docs/superpowers/specs/2026-04-28-stage-10-efg-permissions-connect-data-design.md`.

---

## Task ordering

The plan is grouped into three clusters (A = Permissions, B = Connect+Feedback, C = Data+Recordings). Inside each cluster tasks are TDD-ordered: small typed primitives first, services next, ViewModels, then composables, then wiring. Each cluster ends with a `SettingsScreen.kt` integration task. Clusters are independent; an implementer can run them sequentially or interleave provided the integration tasks land last.

---

## Cluster A — PermissionsCard (Stage 10-E)

### Task A1: Add `PermissionStatus` enum

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/permissions/PermissionStatus.kt`

- [ ] **Step 1: Write the file**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

/**
 * iOS-faithful 4-state permission summary used by the Settings
 * Permissions card. Granted / NotDetermined / Denied / Restricted
 * mirrors `PermissionStatusViewModel.PermissionState` on iOS.
 *
 * Android has no first-class `notDetermined` signal — we approximate
 * via [PermissionAskedStore]: ungranted + never-asked → NotDetermined,
 * ungranted + asked → Denied. `Restricted` has no Android equivalent
 * for our three permissions; the branch is included for symmetry but
 * is unreachable in production.
 */
enum class PermissionStatus { Granted, NotDetermined, Denied, Restricted }
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/permissions/PermissionStatus.kt
git commit -m "feat(permissions): add PermissionStatus enum for Settings card"
```

---

### Task A2: Add `PermissionAskedStore` (DataStore-backed flags)

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/permissions/PermissionAskedStore.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/permissions/PermissionAskedStoreTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PermissionAskedStoreTest {

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var file: File
    private lateinit var store: PermissionAskedStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        file = File(ctx.filesDir, "datastore/perm-asked-${UUID.randomUUID()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        store = PermissionAskedStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun `defaults to false for every permission`() = runTest {
        assertFalse(store.askedFlow(PermissionAskedStore.Key.Location).first())
        assertFalse(store.askedFlow(PermissionAskedStore.Key.Microphone).first())
        assertFalse(store.askedFlow(PermissionAskedStore.Key.Motion).first())
    }

    @Test
    fun `markAsked persists per key`() = runTest {
        store.markAsked(PermissionAskedStore.Key.Microphone)
        assertFalse(store.askedFlow(PermissionAskedStore.Key.Location).first())
        assertTrue(store.askedFlow(PermissionAskedStore.Key.Microphone).first())
        assertFalse(store.askedFlow(PermissionAskedStore.Key.Motion).first())
    }
}
```

- [ ] **Step 2: Run test (expect compile failure)**

Run: `./gradlew :app:testDebugUnitTest --tests "*PermissionAskedStoreTest*"`
Expected: FAIL — `PermissionAskedStore` not defined.

- [ ] **Step 3: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists a boolean per permission: "have we ever asked the system
 * dialog for this one?" Used by [PermissionsCardViewModel] to
 * disambiguate Android's two-state `checkSelfPermission` (granted /
 * not granted) into iOS's three pre-restricted states (Granted /
 * NotDetermined / Denied).
 *
 * Set to true after a successful [androidx.activity.result.ActivityResultLauncher]
 * callback regardless of grant outcome — once the system dialog has
 * dismissed, "not determined" no longer applies.
 */
@Singleton
class PermissionAskedStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    enum class Key(internal val storeKey: String) {
        Location("permissions_asked_location"),
        Microphone("permissions_asked_microphone"),
        Motion("permissions_asked_motion"),
    }

    fun askedFlow(key: Key): Flow<Boolean> =
        dataStore.data.map { it[booleanPreferencesKey(key.storeKey)] ?: false }

    suspend fun markAsked(key: Key) {
        dataStore.edit { it[booleanPreferencesKey(key.storeKey)] = true }
    }
}
```

- [ ] **Step 4: Run test (expect pass)**

Run: `./gradlew :app:testDebugUnitTest --tests "*PermissionAskedStoreTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/permissions/PermissionAskedStore.kt app/src/test/java/org/walktalkmeditate/pilgrim/permissions/PermissionAskedStoreTest.kt
git commit -m "feat(permissions): add PermissionAskedStore for not-determined detection"
```

---

### Task A3: Add `PermissionsCardViewModel`

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCardViewModel.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCardViewModelTest.kt`

The VM owns: a `StateFlow<PermissionsCardState>` derived from live `PermissionChecks` + the `PermissionAskedStore` flags + a `refresh()` trigger that recomputes after a permission request returns. The composable owns the launchers; the VM only persists `markAsked` + recomputes.

- [ ] **Step 1: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.permissions

import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.permissions.PermissionAskedStore
import org.walktalkmeditate.pilgrim.permissions.PermissionStatus

class PermissionsCardViewModelTest {

    @Test
    fun `granted permission emits Granted regardless of asked flag`() = runTest {
        val checks = FakePermissionChecks(
            location = true, microphone = true, motion = true,
        )
        val asked = FakeAskedStore(asked = setOf())
        val vm = PermissionsCardViewModel(checks, asked)

        vm.state.test(timeout = 5.seconds) {
            val state = awaitItem()
            assertEquals(PermissionStatus.Granted, state.location)
            assertEquals(PermissionStatus.Granted, state.microphone)
            assertEquals(PermissionStatus.Granted, state.motion)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ungranted + never-asked = NotDetermined`() = runTest {
        val checks = FakePermissionChecks(
            location = false, microphone = false, motion = false,
        )
        val asked = FakeAskedStore(asked = setOf())
        val vm = PermissionsCardViewModel(checks, asked)

        vm.state.test(timeout = 5.seconds) {
            val state = awaitItem()
            assertEquals(PermissionStatus.NotDetermined, state.location)
            assertEquals(PermissionStatus.NotDetermined, state.microphone)
            assertEquals(PermissionStatus.NotDetermined, state.motion)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ungranted + asked = Denied`() = runTest {
        val checks = FakePermissionChecks(
            location = false, microphone = false, motion = false,
        )
        val asked = FakeAskedStore(
            asked = setOf(
                PermissionAskedStore.Key.Location,
                PermissionAskedStore.Key.Microphone,
                PermissionAskedStore.Key.Motion,
            ),
        )
        val vm = PermissionsCardViewModel(checks, asked)

        vm.state.test(timeout = 5.seconds) {
            val state = awaitItem()
            assertEquals(PermissionStatus.Denied, state.location)
            assertEquals(PermissionStatus.Denied, state.microphone)
            assertEquals(PermissionStatus.Denied, state.motion)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `markAsked + refresh flips not-determined to denied`() = runTest {
        val checks = FakePermissionChecks(
            location = false, microphone = false, motion = false,
        )
        val asked = FakeAskedStore(asked = setOf())
        val vm = PermissionsCardViewModel(checks, asked)

        vm.state.test(timeout = 5.seconds) {
            assertEquals(PermissionStatus.NotDetermined, awaitItem().location)
            vm.onPermissionResult(PermissionAskedStore.Key.Location)
            val next = awaitItem()
            assertEquals(PermissionStatus.Denied, next.location)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh re-reads live state without changing asked flags`() = runTest {
        val checks = FakePermissionChecks(
            location = false, microphone = false, motion = false,
        )
        val asked = FakeAskedStore(asked = setOf())
        val vm = PermissionsCardViewModel(checks, asked)

        vm.state.test(timeout = 5.seconds) {
            awaitItem()
            checks.location = true
            vm.refresh()
            val next = awaitItem()
            assertEquals(PermissionStatus.Granted, next.location)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

private class FakePermissionChecks(
    var location: Boolean,
    var microphone: Boolean,
    var motion: Boolean,
) : LivePermissionChecks {
    override fun isLocationGranted(): Boolean = location
    override fun isMicrophoneGranted(): Boolean = microphone
    override fun isMotionGranted(): Boolean = motion
}

private class FakeAskedStore(
    asked: Set<PermissionAskedStore.Key>,
) : AskedFlagSource {
    private val state = MutableStateFlow(asked)
    override fun isAsked(key: PermissionAskedStore.Key): Boolean = key in state.value
    override suspend fun markAsked(key: PermissionAskedStore.Key) {
        state.value = state.value + key
    }
}
```

- [ ] **Step 2: Run test (expect compile failure)**

Run: `./gradlew :app:testDebugUnitTest --tests "*PermissionsCardViewModelTest*"`
Expected: FAIL — `PermissionsCardViewModel`, `LivePermissionChecks`, `AskedFlagSource` not defined.

- [ ] **Step 3: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.permissions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.permissions.PermissionAskedStore
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks
import org.walktalkmeditate.pilgrim.permissions.PermissionStatus

/**
 * Wraps the runtime check helpers as an injectable seam so unit
 * tests can provide deterministic state without spinning up
 * Robolectric.
 */
interface LivePermissionChecks {
    fun isLocationGranted(): Boolean
    fun isMicrophoneGranted(): Boolean
    fun isMotionGranted(): Boolean
}

internal class PermissionChecksAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
) : LivePermissionChecks {
    override fun isLocationGranted(): Boolean = PermissionChecks.isFineLocationGranted(context)
    override fun isMicrophoneGranted(): Boolean = PermissionChecks.isMicrophoneGranted(context)
    override fun isMotionGranted(): Boolean = PermissionChecks.isActivityRecognitionGranted(context)
}

/**
 * Sync-friendly view of [PermissionAskedStore] so the VM can compute
 * status without suspending. The Hilt-bound implementation eagerly
 * snapshots the asked set into memory at VM construction; subsequent
 * `markAsked` writes flip both the in-memory snapshot and the
 * persisted Flow.
 */
interface AskedFlagSource {
    fun isAsked(key: PermissionAskedStore.Key): Boolean
    suspend fun markAsked(key: PermissionAskedStore.Key)
}

data class PermissionsCardState(
    val location: PermissionStatus,
    val microphone: PermissionStatus,
    val motion: PermissionStatus,
)

@HiltViewModel
class PermissionsCardViewModel @Inject constructor(
    private val checks: LivePermissionChecks,
    private val askedFlags: AskedFlagSource,
) : ViewModel() {

    private val _state = MutableStateFlow(compute())
    val state: StateFlow<PermissionsCardState> = _state.asStateFlow()

    /**
     * Re-read live permission state. Called from the composable on
     * `Lifecycle.Event.ON_RESUME` (mirrors iOS's
     * `willEnterForegroundNotification`) — the user can revoke from
     * system Settings at any time and we always need the live
     * snapshot.
     */
    fun refresh() {
        _state.value = compute()
    }

    /**
     * Called from the composable's permission-request callback. Marks
     * the key as asked and recomputes — flips a NotDetermined row to
     * Granted (if the user granted) or Denied (if the user denied).
     */
    fun onPermissionResult(key: PermissionAskedStore.Key) {
        viewModelScope.launch {
            askedFlags.markAsked(key)
            _state.value = compute()
        }
    }

    private fun compute(): PermissionsCardState = PermissionsCardState(
        location = resolve(checks.isLocationGranted(), PermissionAskedStore.Key.Location),
        microphone = resolve(checks.isMicrophoneGranted(), PermissionAskedStore.Key.Microphone),
        motion = resolve(checks.isMotionGranted(), PermissionAskedStore.Key.Motion),
    )

    private fun resolve(granted: Boolean, key: PermissionAskedStore.Key): PermissionStatus =
        when {
            granted -> PermissionStatus.Granted
            askedFlags.isAsked(key) -> PermissionStatus.Denied
            else -> PermissionStatus.NotDetermined
        }
}
```

Add a separate Hilt module to bind the adapter:

Create: `app/src/main/java/org/walktalkmeditate/pilgrim/di/PermissionsCardModule.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.walktalkmeditate.pilgrim.permissions.PermissionAskedStore
import org.walktalkmeditate.pilgrim.ui.settings.permissions.AskedFlagSource
import org.walktalkmeditate.pilgrim.ui.settings.permissions.LivePermissionChecks
import org.walktalkmeditate.pilgrim.ui.settings.permissions.PermissionChecksAdapter

@Module
@InstallIn(SingletonComponent::class)
abstract class PermissionsCardModule {

    @Binds
    @Singleton
    abstract fun bindLiveChecks(impl: PermissionChecksAdapter): LivePermissionChecks

    @Binds
    @Singleton
    abstract fun bindAskedFlags(impl: PermissionAskedStoreAdapter): AskedFlagSource
}

/**
 * Sync-snapshot adapter over [PermissionAskedStore]. Hot reads via
 * `runBlocking { askedFlow(key).first() }` are acceptable because:
 *   - `askedFlow` is a DataStore flow with a tiny payload (3 booleans
 *     total); first-emission latency is sub-millisecond.
 *   - The adapter is only consumed inside [PermissionsCardViewModel.compute]
 *     which already runs on the calling coroutine context (Main during
 *     refresh, viewModelScope after a request callback).
 *   - markAsked re-uses the suspending DataStore write directly.
 */
import javax.inject.Inject
class PermissionAskedStoreAdapter @Inject constructor(
    private val store: PermissionAskedStore,
) : AskedFlagSource {
    override fun isAsked(key: PermissionAskedStore.Key): Boolean =
        runBlocking { store.askedFlow(key).first() }

    override suspend fun markAsked(key: PermissionAskedStore.Key) =
        store.markAsked(key)
}
```

> NOTE TO IMPLEMENTER: Move the inner `import javax.inject.Inject` + class to a separate file (`PermissionAskedStoreAdapter.kt`) so the file structure stays one-class-per-file. Inline above only for brevity in the plan.

- [ ] **Step 4: Run test (expect pass)**

Run: `./gradlew :app:testDebugUnitTest --tests "*PermissionsCardViewModelTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/ app/src/main/java/org/walktalkmeditate/pilgrim/di/PermissionsCardModule.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/
git commit -m "feat(permissions): add PermissionsCardViewModel with iOS-faithful 4-state mapping"
```

---

### Task A4: Add `PermissionsCard` composable

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCard.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add strings**

Append to `strings.xml` (just before `</resources>`):

```xml
<!-- Stage 10-E: PermissionsCard -->
<string name="settings_permissions_title">Permissions</string>
<string name="settings_permissions_subtitle">What Pilgrim can access</string>
<string name="settings_permissions_location_label">Location</string>
<string name="settings_permissions_location_caption">Track your route</string>
<string name="settings_permissions_microphone_label">Microphone</string>
<string name="settings_permissions_microphone_caption">Record reflections</string>
<string name="settings_permissions_motion_label">Motion</string>
<string name="settings_permissions_motion_caption">Count your steps</string>
<string name="settings_permissions_action_grant">Grant</string>
<string name="settings_permissions_action_settings">Settings</string>
<string name="settings_permissions_status_restricted">Restricted</string>
<string name="settings_permissions_status_granted_content_description">Permission granted</string>
```

- [ ] **Step 2: Implement composable**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.permissions

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.permissions.PermissionAskedStore
import org.walktalkmeditate.pilgrim.permissions.PermissionStatus
import org.walktalkmeditate.pilgrim.ui.settings.CardHeader
import org.walktalkmeditate.pilgrim.ui.settings.SettingsAction
import org.walktalkmeditate.pilgrim.ui.settings.settingsCard
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS-parity Permissions card. Three rows (Location / Microphone /
 * Motion) with per-row state dot + trailing action. Refreshes on
 * `Lifecycle.Event.ON_RESUME` so a permission revoked from system
 * Settings reflects without restart.
 */
@Composable
fun PermissionsCard(
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PermissionsCardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Refresh-on-resume mirrors iOS's
    // `willEnterForegroundNotification.onReceive { permissionVM.refresh() }`.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.onPermissionResult(PermissionAskedStore.Key.Location) }

    val microphoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.onPermissionResult(PermissionAskedStore.Key.Microphone) }

    val motionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.onPermissionResult(PermissionAskedStore.Key.Motion) }

    Column(
        modifier = modifier.fillMaxWidth().settingsCard(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardHeader(
            title = stringResource(R.string.settings_permissions_title),
            subtitle = stringResource(R.string.settings_permissions_subtitle),
        )

        PermissionRow(
            label = stringResource(R.string.settings_permissions_location_label),
            caption = stringResource(R.string.settings_permissions_location_caption),
            status = state.location,
            onGrant = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
            onOpenSettings = { onAction(SettingsAction.OpenAppPermissionSettings) },
        )
        PermissionRow(
            label = stringResource(R.string.settings_permissions_microphone_label),
            caption = stringResource(R.string.settings_permissions_microphone_caption),
            status = state.microphone,
            onGrant = { microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            onOpenSettings = { onAction(SettingsAction.OpenAppPermissionSettings) },
        )
        PermissionRow(
            label = stringResource(R.string.settings_permissions_motion_label),
            caption = stringResource(R.string.settings_permissions_motion_caption),
            status = state.motion,
            // ACTIVITY_RECOGNITION became a runtime permission in API 29.
            // Pre-29 the row should always show as Granted (handled by
            // PermissionChecks.isActivityRecognitionGranted), so the
            // launcher is only ever invoked on API 29+.
            onGrant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    motionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            },
            onOpenSettings = { onAction(SettingsAction.OpenAppPermissionSettings) },
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    caption: String,
    status: PermissionStatus,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor(status)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = pilgrimType.body, color = pilgrimColors.ink)
            Text(caption, style = pilgrimType.caption, color = pilgrimColors.fog)
        }
        when (status) {
            PermissionStatus.Granted -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(
                    R.string.settings_permissions_status_granted_content_description,
                ),
                tint = pilgrimColors.moss,
                modifier = Modifier.size(20.dp),
            )
            PermissionStatus.NotDetermined -> TextButton(onClick = onGrant) {
                Text(
                    text = stringResource(R.string.settings_permissions_action_grant),
                    style = pilgrimType.button,
                    color = pilgrimColors.stone,
                )
            }
            PermissionStatus.Denied -> TextButton(onClick = onOpenSettings) {
                Text(
                    text = stringResource(R.string.settings_permissions_action_settings),
                    style = pilgrimType.button,
                    color = pilgrimColors.stone,
                )
            }
            PermissionStatus.Restricted -> Text(
                text = stringResource(R.string.settings_permissions_status_restricted),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}

@Composable
private fun dotColor(status: PermissionStatus): Color = when (status) {
    PermissionStatus.Granted -> pilgrimColors.moss
    PermissionStatus.NotDetermined -> pilgrimColors.dawn
    PermissionStatus.Denied -> pilgrimColors.rust
    PermissionStatus.Restricted -> pilgrimColors.fog
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCard.kt app/src/main/res/values/strings.xml
git commit -m "feat(permissions): add PermissionsCard composable with refresh-on-resume"
```

---

### Task A5: Wire `OpenAppPermissionSettings` action + integrate into SettingsScreen

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Wire action handler**

In `PilgrimNavHost.kt`'s `handleSettingsAction`, replace the `OpenAppPermissionSettings` line in the catch-all block with a real handler:

```kotlin
SettingsAction.OpenAppPermissionSettings ->
    context.startActivity(AppSettings.openDetailsIntent(context))
```

Add the import: `import org.walktalkmeditate.pilgrim.permissions.AppSettings`. Remove `OpenAppPermissionSettings` from the catch-all `Log.w` branch above.

- [ ] **Step 2: Add card to SettingsScreen**

In `SettingsScreen.kt`, add a new `item` block between the existing `AtmosphereCard` item and the transitional Soundscapes wrapper:

```kotlin
item {
    PermissionsCard(onAction = onAction)
}
```

Add import: `import org.walktalkmeditate.pilgrim.ui.settings.permissions.PermissionsCard`.

- [ ] **Step 3: Build + test**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt
git commit -m "feat(settings): wire PermissionsCard into SettingsScreen + system Settings deep-link"
```

---

## Cluster B — ConnectCard + FeedbackScreen (Stage 10-F)

### Task B1: Add `androidx.browser` dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add catalog entries**

In `libs.versions.toml`, add a `browser` version under `[versions]` and a `androidx-browser` library under `[libraries]`:

```toml
# in [versions]
androidxBrowser = "1.8.0"

# in [libraries]
androidx-browser = { group = "androidx.browser", name = "browser", version.ref = "androidxBrowser" }
```

In `app/build.gradle.kts`, add the dependency:

```kotlin
implementation(libs.androidx.browser)
```

- [ ] **Step 2: Sync + build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "chore(deps): add androidx.browser for Custom Tabs"
```

---

### Task B2: Add `CustomTabs` helper

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/util/CustomTabs.kt`

- [ ] **Step 1: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Wraps [CustomTabsIntent] with a same-task fallback to a vanilla
 * `ACTION_VIEW` browser intent. Some devices ship without a Custom
 * Tabs-capable browser (rare, but happens on stripped AOSP builds);
 * the fallback keeps the user moving rather than swallowing the tap.
 */
object CustomTabs {

    fun launch(context: Context, uri: Uri) {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        try {
            intent.launchUrl(context, uri)
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: ActivityNotFoundException) {
                // No browser at all — best we can do is silently no-op.
                // The card row itself remains tappable; the user can
                // copy the URL via long-press if they care.
            }
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/util/CustomTabs.kt
git commit -m "feat(util): add CustomTabs helper with fallback to ACTION_VIEW"
```

---

### Task B3: Add `FeedbackCategory`, `FeedbackError`, `FeedbackRequest`

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/feedback/FeedbackCategory.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/feedback/FeedbackError.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/feedback/FeedbackRequest.kt`

- [ ] **Step 1: Implement category**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.feedback

/**
 * Three iOS-faithful feedback buckets. The wire `apiValue` is what
 * the server's `/api/feedback` endpoint expects — note that
 * `Thought` posts `"feedback"`, not `"thought"`. iOS preserves this
 * legacy mapping; we follow.
 */
enum class FeedbackCategory(val apiValue: String) {
    Bug("bug"),
    Feature("feature"),
    Thought("feedback"),
}
```

- [ ] **Step 2: Implement error**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.feedback

sealed class FeedbackError : Exception() {
    object RateLimited : FeedbackError()
    data class ServerError(val statusCode: Int) : FeedbackError()
    data class NetworkError(override val message: String) : FeedbackError()
}
```

- [ ] **Step 3: Implement request body**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.feedback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for the POST body. iOS uses raw `JSONSerialization` over
 * a `[String: String]` dict — we use `kotlinx.serialization` against a
 * matched-shape data class. `deviceInfo` is omitted (not nulled) when
 * the user opts out, mirroring iOS's conditional-key behavior.
 */
@Serializable
internal data class FeedbackRequest(
    @SerialName("category") val category: String,
    @SerialName("message") val message: String,
    @SerialName("deviceInfo") val deviceInfo: String? = null,
)
```

- [ ] **Step 4: Compile + commit**

Run: `./gradlew :app:compileDebugKotlin`

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/feedback/
git commit -m "feat(feedback): add FeedbackCategory, FeedbackError, FeedbackRequest"
```

---

### Task B4: Add `FeedbackService` + MockWebServer test

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/feedback/FeedbackService.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/data/feedback/FeedbackServiceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.feedback

import java.net.SocketTimeoutException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore

class FeedbackServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: FeedbackService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().build()
        val tokenStore = FakeDeviceTokenStore(token = "test-token-uuid")
        service = FeedbackService(
            httpClient = client,
            deviceTokenStore = tokenStore,
            json = Json { ignoreUnknownKeys = true; explicitNulls = false },
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `success path posts JSON body with X-Device-Token header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        service.submit(
            category = "bug",
            message = "Hello from Android",
            deviceInfo = "Android 14 · Pixel 8 · v0.1.0",
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/feedback", recorded.path)
        assertEquals("test-token-uuid", recorded.getHeader("X-Device-Token"))
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"category\":\"bug\""))
        assertTrue(body.contains("\"message\":\"Hello from Android\""))
        assertTrue(body.contains("\"deviceInfo\":\"Android 14 · Pixel 8 · v0.1.0\""))
    }

    @Test
    fun `omits deviceInfo when null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        service.submit(category = "feature", message = "msg", deviceInfo = null)

        val body = server.takeRequest().body.readUtf8()
        assertTrue("body should not contain deviceInfo: $body", !body.contains("deviceInfo"))
    }

    @Test(expected = FeedbackError.RateLimited::class)
    fun `429 maps to RateLimited`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        service.submit(category = "bug", message = "m", deviceInfo = null)
    }

    @Test
    fun `5xx maps to ServerError with status code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            service.submit(category = "bug", message = "m", deviceInfo = null)
            error("should have thrown")
        } catch (e: FeedbackError.ServerError) {
            assertEquals(503, e.statusCode)
        }
    }

    @Test(expected = FeedbackError.NetworkError::class)
    fun `IOException maps to NetworkError`() = runTest {
        server.shutdown() // forces ConnectException
        service.submit(category = "bug", message = "m", deviceInfo = null)
    }
}

private class FakeDeviceTokenStore(private val token: String) : DeviceTokenStore(
    // The real DeviceTokenStore takes @ApplicationContext context.
    // For tests we replace with the inline subclass below — see
    // SubclassNoteForImplementer.
    context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
) {
    override suspend fun getToken(): String = token
}
```

> **NOTE TO IMPLEMENTER:** `DeviceTokenStore` is a `final` Singleton class — `open` it (or extract a `DeviceTokenSource` interface backed by it) so the test can substitute. Pick whichever is cleaner — the interface route is preferred (matches the `LivePermissionChecks` pattern used in Cluster A). If interface route, add `interface DeviceTokenSource { suspend fun getToken(): String }` and `class DeviceTokenStore : DeviceTokenSource`.

- [ ] **Step 2: Run test (expect compile failure)**

Run: `./gradlew :app:testDebugUnitTest --tests "*FeedbackServiceTest*"`
Expected: FAIL — `FeedbackService` not defined.

- [ ] **Step 3: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.feedback

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenSource
import org.walktalkmeditate.pilgrim.di.FeedbackHttpClient

/**
 * iOS-parity "Trail Note" feedback POST.
 *
 * Reuses [DeviceTokenSource] (and therefore the same persistent UUID
 * the share path uses) for the X-Device-Token header. Server hashes
 * the token with a salt for rate-limiting only — not a secret.
 */
@Singleton
class FeedbackService @Inject constructor(
    @FeedbackHttpClient private val httpClient: OkHttpClient,
    private val deviceTokenStore: DeviceTokenSource,
    private val json: Json,
    @FeedbackBaseUrl private val baseUrl: String,
) {

    /**
     * @throws FeedbackError.RateLimited when server returns 429.
     * @throws FeedbackError.ServerError for any other non-2xx.
     * @throws FeedbackError.NetworkError for transport failures.
     */
    suspend fun submit(category: String, message: String, deviceInfo: String?) {
        val body = json.encodeToString(
            FeedbackRequest.serializer(),
            FeedbackRequest(category, message, deviceInfo),
        )
        val token = deviceTokenStore.getToken()

        val request = Request.Builder()
            .url("$baseUrl/api/feedback")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("X-Device-Token", token)
            .build()

        withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).awaitResponse().use { response ->
                    when {
                        response.code == 429 -> throw FeedbackError.RateLimited
                        !response.isSuccessful -> throw FeedbackError.ServerError(response.code)
                    }
                }
            } catch (e: FeedbackError) {
                throw e
            } catch (e: IOException) {
                throw FeedbackError.NetworkError(e.message ?: "Network error")
            }
        }
    }

    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { cont ->
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) =
                    cont.resumeWith(Result.success(response))

                override fun onFailure(call: Call, e: IOException) =
                    cont.resumeWith(Result.failure(e))
            })
            cont.invokeOnCancellation { runCatching { cancel() } }
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
```

Add a sibling `DeviceTokenSource.kt` interface in `data/share/`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

interface DeviceTokenSource {
    suspend fun getToken(): String
}
```

Make `DeviceTokenStore` implement it:

```kotlin
class DeviceTokenStore @Inject constructor(...) : DeviceTokenSource {
    override suspend fun getToken(): String { ... existing impl ... }
}
```

Also add the `@FeedbackHttpClient` and `@FeedbackBaseUrl` qualifiers (see Task B5).

- [ ] **Step 4: Run test**

Run: `./gradlew :app:testDebugUnitTest --tests "*FeedbackServiceTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/feedback/FeedbackService.kt app/src/main/java/org/walktalkmeditate/pilgrim/data/share/DeviceTokenSource.kt app/src/main/java/org/walktalkmeditate/pilgrim/data/share/DeviceTokenStore.kt app/src/test/java/org/walktalkmeditate/pilgrim/data/feedback/FeedbackServiceTest.kt
git commit -m "feat(feedback): add FeedbackService + DeviceTokenSource interface"
```

---

### Task B5: Add `FeedbackModule` (Hilt qualifiers + provider)

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/di/FeedbackModule.kt`

- [ ] **Step 1: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenSource
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore

/**
 * Stage 10-F: feedback POST configuration. Decorates the project-wide
 * `OkHttpClient` from [NetworkModule] with a 15-second call timeout
 * (matches iOS `request.timeoutInterval = 15`). Same pattern as
 * `CollectiveModule` / `ShareModule`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FeedbackHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FeedbackBaseUrl

@Module
@InstallIn(SingletonComponent::class)
object FeedbackModule {

    @Provides
    @Singleton
    @FeedbackHttpClient
    fun provideFeedbackHttpClient(default: OkHttpClient): OkHttpClient =
        default.newBuilder()
            .callTimeout(FEEDBACK_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @FeedbackBaseUrl
    fun provideFeedbackBaseUrl(): String = FEEDBACK_BASE_URL

    private const val FEEDBACK_CALL_TIMEOUT_SEC = 15L
    // Matches iOS FeedbackService.baseURL.
    private const val FEEDBACK_BASE_URL = "https://walk.pilgrimapp.org"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FeedbackBindingsModule {
    @Binds
    @Singleton
    abstract fun bindDeviceTokenSource(impl: DeviceTokenStore): DeviceTokenSource
}
```

- [ ] **Step 2: Build + commit**

Run: `./gradlew :app:assembleDebug`

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/di/FeedbackModule.kt
git commit -m "feat(di): add FeedbackModule with @FeedbackHttpClient qualifier"
```

---

### Task B6: Add `FeedbackViewModel`

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackViewModel.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackUiState.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackViewModelTest.kt`

- [ ] **Step 1: Define UI state**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.connect

import org.walktalkmeditate.pilgrim.data.feedback.FeedbackCategory

data class FeedbackUiState(
    val selectedCategory: FeedbackCategory? = null,
    val message: String = "",
    val includeDeviceInfo: Boolean = true,
    val isSubmitting: Boolean = false,
    val showConfirmation: Boolean = false,
    val errorMessage: String? = null,
) {
    val canSubmit: Boolean
        get() = selectedCategory != null &&
            message.trim().isNotEmpty() &&
            !isSubmitting
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.connect

import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.feedback.FeedbackCategory
import org.walktalkmeditate.pilgrim.data.feedback.FeedbackError

class FeedbackViewModelTest {

    @Test
    fun `canSubmit requires both category and non-empty message`() = runTest {
        val vm = FeedbackViewModel(FakeService(), deviceInfoProvider = { "Android 14" })

        vm.state.test(timeout = 5.seconds) {
            assertFalse(awaitItem().canSubmit)

            vm.selectCategory(FeedbackCategory.Bug)
            assertFalse(awaitItem().canSubmit) // message still empty

            vm.updateMessage("hello")
            assertTrue(awaitItem().canSubmit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `submit success transitions to confirmation`() = runTest {
        val service = FakeService()
        val vm = FeedbackViewModel(service, deviceInfoProvider = { "Android 14" })
        vm.selectCategory(FeedbackCategory.Bug)
        vm.updateMessage("test")

        vm.submit()

        vm.state.test(timeout = 5.seconds) {
            // Initial item — already shows showConfirmation=true after the
            // launch coroutine completed under runTest's eager dispatcher.
            val final = awaitItem()
            assertTrue(final.showConfirmation)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("bug", service.lastCategory)
        assertEquals("test", service.lastMessage)
        assertEquals("Android 14", service.lastDeviceInfo)
    }

    @Test
    fun `device info omitted when toggle off`() = runTest {
        val service = FakeService()
        val vm = FeedbackViewModel(service, deviceInfoProvider = { "Android 14" })
        vm.selectCategory(FeedbackCategory.Feature)
        vm.updateMessage("ok")
        vm.toggleIncludeDeviceInfo(false)

        vm.submit()
        vm.state.test(timeout = 5.seconds) {
            assertTrue(awaitItem().showConfirmation)
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(service.lastDeviceInfo)
    }

    @Test
    fun `rate-limited error surfaces specific message`() = runTest {
        val service = FakeService(throwOnNextSubmit = FeedbackError.RateLimited)
        val vm = FeedbackViewModel(service, deviceInfoProvider = { "Android 14" })
        vm.selectCategory(FeedbackCategory.Thought)
        vm.updateMessage("rate me out")

        vm.submit()
        vm.state.test(timeout = 5.seconds) {
            val final = awaitItem()
            assertFalse(final.showConfirmation)
            assertEquals("Too many submissions today.", final.errorMessage)
            assertFalse(final.isSubmitting)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `network error surfaces generic message`() = runTest {
        val service = FakeService(throwOnNextSubmit = FeedbackError.NetworkError("offline"))
        val vm = FeedbackViewModel(service, deviceInfoProvider = { "Android 14" })
        vm.selectCategory(FeedbackCategory.Bug)
        vm.updateMessage("hello")

        vm.submit()
        vm.state.test(timeout = 5.seconds) {
            val final = awaitItem()
            assertEquals("Couldn't send — please try again", final.errorMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

private class FakeService(
    private var throwOnNextSubmit: Throwable? = null,
) : FeedbackSubmitter {
    var lastCategory: String? = null
    var lastMessage: String? = null
    var lastDeviceInfo: String? = null

    override suspend fun submit(category: String, message: String, deviceInfo: String?) {
        throwOnNextSubmit?.let { throwOnNextSubmit = null; throw it }
        lastCategory = category
        lastMessage = message
        lastDeviceInfo = deviceInfo
    }
}
```

- [ ] **Step 3: Run test (expect compile failure)**

Run: `./gradlew :app:testDebugUnitTest --tests "*FeedbackViewModelTest*"`
Expected: FAIL — `FeedbackViewModel`, `FeedbackSubmitter` not defined.

- [ ] **Step 4: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.feedback.FeedbackCategory
import org.walktalkmeditate.pilgrim.data.feedback.FeedbackError
import org.walktalkmeditate.pilgrim.data.feedback.FeedbackService

/**
 * Test seam over [FeedbackService] — the suspend fun the VM cares
 * about, without dragging Hilt + OkHttp into unit tests.
 */
interface FeedbackSubmitter {
    suspend fun submit(category: String, message: String, deviceInfo: String?)
}

@HiltViewModel
class FeedbackViewModel @Inject constructor(
    private val submitter: FeedbackSubmitter,
    private val deviceInfoProvider: DeviceInfoProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(FeedbackUiState())
    val state: StateFlow<FeedbackUiState> = _state.asStateFlow()

    fun selectCategory(category: FeedbackCategory) {
        _state.update { it.copy(selectedCategory = category, errorMessage = null) }
    }

    fun updateMessage(text: String) {
        _state.update { it.copy(message = text, errorMessage = null) }
    }

    fun toggleIncludeDeviceInfo(include: Boolean) {
        _state.update { it.copy(includeDeviceInfo = include) }
    }

    fun submit() {
        val snapshot = _state.value
        val category = snapshot.selectedCategory ?: return
        if (snapshot.message.trim().isEmpty() || snapshot.isSubmitting) return

        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                submitter.submit(
                    category = category.apiValue,
                    message = snapshot.message.trim(),
                    deviceInfo = if (snapshot.includeDeviceInfo) deviceInfoProvider.deviceInfo() else null,
                )
                _state.update { it.copy(isSubmitting = false, showConfirmation = true) }
            } catch (e: FeedbackError.RateLimited) {
                _state.update { it.copy(isSubmitting = false, errorMessage = "Too many submissions today.") }
            } catch (e: FeedbackError.ServerError) {
                _state.update { it.copy(isSubmitting = false, errorMessage = "Couldn't send — please try again") }
            } catch (e: FeedbackError.NetworkError) {
                _state.update { it.copy(isSubmitting = false, errorMessage = "Couldn't send — please try again") }
            }
        }
    }
}

interface DeviceInfoProvider {
    fun deviceInfo(): String
}
```

Constructor companion: `FeedbackViewModel(service: FeedbackSubmitter, deviceInfoProvider: () -> String)` for test convenience — wrap the lambda into the interface inline. Or keep the interface and provide a `lambda-as-interface` adapter in the test file.

> **NOTE TO IMPLEMENTER:** Add a `FeedbackSubmitterImpl @Inject constructor(private val service: FeedbackService) : FeedbackSubmitter` adapter so Hilt can wire the VM cleanly without making `FeedbackService` itself implement the interface. Bind via `FeedbackBindingsModule.bindFeedbackSubmitter`.

- [ ] **Step 5: Run test**

Run: `./gradlew :app:testDebugUnitTest --tests "*FeedbackViewModelTest*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackViewModel.kt app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackUiState.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackViewModelTest.kt
git commit -m "feat(feedback): add FeedbackViewModel with iOS-faithful error mapping"
```

---

### Task B7: Add `FeedbackScreen` composable

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add strings**

```xml
<!-- Stage 10-F: ConnectCard / FeedbackScreen -->
<string name="settings_connect_title">Connect</string>
<string name="settings_connect_subtitle">Share the path</string>
<string name="settings_connect_podcast">Pilgrim on the Path</string>
<string name="settings_connect_feedback">Leave a Trail Note</string>
<string name="settings_connect_rate">Rate Pilgrim</string>
<string name="settings_connect_share">Share Pilgrim</string>
<string name="feedback_title">Trail Note</string>
<string name="feedback_principal_title">Leave a Trail Note</string>
<string name="feedback_category_bug">Something\'s broken</string>
<string name="feedback_category_feature">I wish it could&#8230;</string>
<string name="feedback_category_thought">A thought</string>
<string name="feedback_message_placeholder">What\'s on your mind?</string>
<string name="feedback_include_device_info">Include device info</string>
<string name="feedback_send">Send</string>
<string name="feedback_error_rate_limited">Too many submissions today.</string>
<string name="feedback_error_generic">Couldn\'t send — please try again</string>
<string name="feedback_confirmation_line1">Your note has been\nleft on the path.</string>
<string name="feedback_confirmation_line2">Thank you.</string>
<string name="settings_share_pilgrim_body">I\'ve been walking with Pilgrim — it tracks your walks, records voice notes, and even has a meditation mode. No accounts, no tracking, everything stays on your phone. Free and open source.</string>
<string name="settings_share_pilgrim_url">https://plgr.im/share</string>
```

- [ ] **Step 2: Implement composable**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.EmojiNature
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.feedback.FeedbackCategory
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    onBack: () -> Unit,
    viewModel: FeedbackViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.showConfirmation) {
        if (state.showConfirmation) {
            // 2500ms matches iOS FeedbackView.submit auto-dismiss
            delay(CONFIRMATION_DISMISS_MS)
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.feedback_principal_title),
                        style = pilgrimType.heading,
                        color = pilgrimColors.ink,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = pilgrimColors.ink,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pilgrimColors.parchment,
                ),
            )
        },
        containerColor = pilgrimColors.parchment,
    ) { padding ->
        if (state.showConfirmation) {
            ConfirmationOverlay(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            FormContent(
                state = state,
                onSelectCategory = viewModel::selectCategory,
                onUpdateMessage = viewModel::updateMessage,
                onToggleDeviceInfo = viewModel::toggleIncludeDeviceInfo,
                onSubmit = viewModel::submit,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
private fun FormContent(
    state: FeedbackUiState,
    onSelectCategory: (FeedbackCategory) -> Unit,
    onUpdateMessage: (String) -> Unit,
    onToggleDeviceInfo: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Category cards
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CategoryCard(
                category = FeedbackCategory.Bug,
                icon = Icons.Outlined.BugReport,
                label = stringResource(R.string.feedback_category_bug),
                selected = state.selectedCategory == FeedbackCategory.Bug,
                onClick = { onSelectCategory(FeedbackCategory.Bug) },
            )
            CategoryCard(
                category = FeedbackCategory.Feature,
                icon = Icons.Outlined.AutoAwesome,
                label = stringResource(R.string.feedback_category_feature),
                selected = state.selectedCategory == FeedbackCategory.Feature,
                onClick = { onSelectCategory(FeedbackCategory.Feature) },
            )
            CategoryCard(
                category = FeedbackCategory.Thought,
                icon = Icons.Outlined.EmojiNature,
                label = stringResource(R.string.feedback_category_thought),
                selected = state.selectedCategory == FeedbackCategory.Thought,
                onClick = { onSelectCategory(FeedbackCategory.Thought) },
            )
        }

        // Message editor
        OutlinedTextField(
            value = state.message,
            onValueChange = onUpdateMessage,
            placeholder = {
                Text(
                    text = stringResource(R.string.feedback_message_placeholder),
                    style = pilgrimType.body,
                    color = pilgrimColors.fog.copy(alpha = 0.5f),
                )
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = pilgrimColors.parchmentSecondary,
                unfocusedContainerColor = pilgrimColors.parchmentSecondary,
                focusedTextColor = pilgrimColors.ink,
                unfocusedTextColor = pilgrimColors.ink,
            ),
            keyboardOptions = KeyboardOptions.Default,
        )

        // Device info toggle + preview
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.feedback_include_device_info),
                    style = pilgrimType.body,
                    color = pilgrimColors.ink,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.includeDeviceInfo,
                    onCheckedChange = onToggleDeviceInfo,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = pilgrimColors.parchment,
                        checkedTrackColor = pilgrimColors.stone,
                        uncheckedThumbColor = pilgrimColors.fog,
                        uncheckedTrackColor = pilgrimColors.parchmentTertiary,
                    ),
                )
            }
            // Preview line. The VM owns the deviceInfo string itself
            // (provided via DeviceInfoProvider) — surface it here only
            // when the toggle is on so it cosmetically matches iOS.
            // Implementer can fetch a preview via a remembered value
            // from a CompositionLocal or pass it down through the VM.
        }

        // Inline error
        if (state.errorMessage != null) {
            Text(
                text = state.errorMessage,
                style = pilgrimType.caption,
                color = pilgrimColors.rust,
            )
        }

        // Send button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (state.canSubmit) pilgrimColors.stone
                    else pilgrimColors.fog.copy(alpha = 0.2f),
                )
                .clickable(enabled = state.canSubmit && !state.isSubmitting) { onSubmit() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    color = pilgrimColors.parchment,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = stringResource(R.string.feedback_send),
                    style = pilgrimType.button,
                    color = pilgrimColors.parchment,
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: FeedbackCategory,
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) pilgrimColors.stone.copy(alpha = 0.08f)
                else pilgrimColors.parchmentSecondary,
            )
            .let { if (selected) it.border(1.dp, pilgrimColors.stone, RoundedCornerShape(12.dp)) else it }
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = pilgrimColors.stone,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = pilgrimType.body,
            color = pilgrimColors.ink,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = pilgrimColors.moss,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ConfirmationOverlay(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(pilgrimColors.parchment).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(80.dp).clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = pilgrimColors.moss,
                modifier = Modifier.size(56.dp),
            )
        }
        Spacer(Modifier.heightIn(min = 16.dp))
        Text(
            text = stringResource(R.string.feedback_confirmation_line1),
            style = pilgrimType.body,
            color = pilgrimColors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.heightIn(min = 8.dp))
        Text(
            text = stringResource(R.string.feedback_confirmation_line2),
            style = pilgrimType.body.copy(fontStyle = FontStyle.Italic),
            color = pilgrimColors.fog,
            textAlign = TextAlign.Center,
        )
    }
}

private const val CONFIRMATION_DISMISS_MS = 2500L
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackScreen.kt app/src/main/res/values/strings.xml
git commit -m "feat(feedback): add FeedbackScreen composable with confirmation overlay"
```

---

### Task B8: Add `ConnectCard` composable + intent helpers

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/ConnectCard.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/util/PlayStore.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/util/ShareIntents.kt`

- [ ] **Step 1: Add Play Store helper**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.walktalkmeditate.pilgrim.BuildConfig

/**
 * Best-effort deep link to Pilgrim's Play Store listing. Strips the
 * `.debug` applicationId suffix so debug builds resolve to the
 * production listing path (the listing is what users see on
 * release; the `.debug` listing doesn't exist).
 *
 * Until the Play Store listing publishes, this will resolve to
 * "Item not found" — accepted per spec open question #4.
 */
object PlayStore {

    fun openListing(context: Context) {
        val productionId = BuildConfig.APPLICATION_ID.removeSuffix(".debug")
        val marketUri = Uri.parse("market://details?id=$productionId")
        val webUri = Uri.parse("https://play.google.com/store/apps/details?id=$productionId")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, marketUri))
        } catch (_: ActivityNotFoundException) {
            CustomTabs.launch(context, webUri)
        }
    }
}
```

- [ ] **Step 2: Add Share helper**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.util

import android.content.Context
import android.content.Intent
import org.walktalkmeditate.pilgrim.R

object ShareIntents {

    fun sharePilgrim(context: Context) {
        val body = context.getString(R.string.settings_share_pilgrim_body)
        val url = context.getString(R.string.settings_share_pilgrim_url)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$body $url")
        }
        context.startActivity(Intent.createChooser(send, null))
    }
}
```

- [ ] **Step 3: Implement ConnectCard**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.settings.CardHeader
import org.walktalkmeditate.pilgrim.ui.settings.SettingNavRow
import org.walktalkmeditate.pilgrim.ui.settings.SettingsAction
import org.walktalkmeditate.pilgrim.ui.settings.settingsCard

@Composable
fun ConnectCard(
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().settingsCard(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CardHeader(
            title = stringResource(R.string.settings_connect_title),
            subtitle = stringResource(R.string.settings_connect_subtitle),
        )
        SettingNavRow(
            label = stringResource(R.string.settings_connect_podcast),
            leadingIcon = Icons.Filled.GraphicEq,
            onClick = { onAction(SettingsAction.OpenPodcast) },
        )
        SettingNavRow(
            label = stringResource(R.string.settings_connect_feedback),
            leadingIcon = Icons.Outlined.Edit,
            onClick = { onAction(SettingsAction.OpenFeedback) },
        )
        SettingNavRow(
            label = stringResource(R.string.settings_connect_rate),
            leadingIcon = Icons.Outlined.FavoriteBorder,
            external = true,
            onClick = { onAction(SettingsAction.OpenPlayStoreReview) },
        )
        SettingNavRow(
            label = stringResource(R.string.settings_connect_share),
            leadingIcon = Icons.Outlined.Share,
            onClick = { onAction(SettingsAction.SharePilgrim) },
        )
    }
}
```

- [ ] **Step 4: Compile + commit**

Run: `./gradlew :app:assembleDebug`

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/util/ app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/ConnectCard.kt
git commit -m "feat(connect): add ConnectCard composable + Play Store / Share helpers"
```

---

### Task B9: Wire navigation + integrate ConnectCard

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Add Routes.FEEDBACK**

In `PilgrimNavHost.kt`'s `Routes` object, add:

```kotlin
const val FEEDBACK = "feedback"
```

- [ ] **Step 2: Add composable destination**

Add this `composable` block alongside the existing ones (e.g. after `SOUND_SETTINGS`):

```kotlin
composable(Routes.FEEDBACK) {
    org.walktalkmeditate.pilgrim.ui.settings.connect.FeedbackScreen(
        onBack = { navController.popBackStack() },
    )
}
```

- [ ] **Step 3: Wire 4 SettingsActions**

In `handleSettingsAction`, replace the `OpenFeedback`, `OpenPodcast`, `OpenPlayStoreReview`, `SharePilgrim` entries in the Log.w catch-all with real handlers:

```kotlin
SettingsAction.OpenFeedback ->
    navController.navigate(Routes.FEEDBACK) { launchSingleTop = true }
SettingsAction.OpenPodcast ->
    org.walktalkmeditate.pilgrim.ui.util.CustomTabs.launch(
        context,
        android.net.Uri.parse("https://podcast.pilgrimapp.org"),
    )
SettingsAction.OpenPlayStoreReview ->
    org.walktalkmeditate.pilgrim.ui.util.PlayStore.openListing(context)
SettingsAction.SharePilgrim ->
    org.walktalkmeditate.pilgrim.ui.util.ShareIntents.sharePilgrim(context)
```

Remove these four from the catch-all `Log.w` branch.

- [ ] **Step 4: Add ConnectCard to SettingsScreen**

Insert a new `item { ConnectCard(onAction = onAction) }` right after the PermissionsCard item from Task A5 (final order — this stage — leaves Connect right after Permissions; Data follows in Cluster C's wrap-up).

Add import: `import org.walktalkmeditate.pilgrim.ui.settings.connect.ConnectCard`.

- [ ] **Step 5: Build + commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt
git commit -m "feat(connect): wire ConnectCard + FeedbackScreen + Custom Tabs / Play Store / Share"
```

---

## Cluster C — DataCard + DataSettingsScreen + Recordings export (Stage 10-G)

### Task C1: Add `BackupTimeCode` formatter

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/export/BackupTimeCode.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/data/export/BackupTimeCodeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.export

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupTimeCodeTest {

    @Test
    fun `formats UTC instant with stable zero-padded layout`() {
        val instant = Instant.parse("2026-04-28T15:23:09Z")
        assertEquals(
            "20260428-152309",
            BackupTimeCode.format(instant, ZoneId.of("UTC")),
        )
    }

    @Test
    fun `respects supplied zone`() {
        val instant = Instant.parse("2026-01-01T00:30:00Z")
        // Asia/Tokyo is UTC+9
        assertEquals(
            "20260101-093000",
            BackupTimeCode.format(instant, ZoneId.of("Asia/Tokyo")),
        )
    }
}
```

- [ ] **Step 2: Run test (expect fail)**

Run: `./gradlew :app:testDebugUnitTest --tests "*BackupTimeCodeTest*"`
Expected: FAIL — `BackupTimeCode` not defined.

- [ ] **Step 3: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `yyyyMMdd-HHmmss` time code used in backup filenames. Mirrors iOS
 * `CustomDateFormatting.backupTimeCode(forDate:)` so a `.pilgrim` or
 * recordings ZIP file produced on Android sorts side-by-side with
 * iOS exports in a Files browser.
 *
 * Pinned to `Locale.ROOT` so non-Gregorian / non-ASCII locales never
 * leak into a filename byte sequence.
 */
object BackupTimeCode {

    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    fun format(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        FORMATTER.format(instant.atZone(zone))
}
```

- [ ] **Step 4: Run test (expect pass)**

Run: `./gradlew :app:testDebugUnitTest --tests "*BackupTimeCodeTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/export/BackupTimeCode.kt app/src/test/java/org/walktalkmeditate/pilgrim/data/export/BackupTimeCodeTest.kt
git commit -m "feat(export): add BackupTimeCode formatter for backup filenames"
```

---

### Task C2: Add `RecordingsExporter`

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/export/RecordingsExporter.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/data/export/RecordingsExporterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.export

import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordingsExporterTest {

    private lateinit var workspace: File

    @Before
    fun setUp() {
        workspace = File(System.getProperty("java.io.tmpdir"), "rec-export-${UUID.randomUUID()}")
        workspace.mkdirs()
    }

    @After
    fun tearDown() {
        workspace.deleteRecursively()
    }

    @Test
    fun `empty source dir returns null and writes nothing`() = runTest {
        val source = File(workspace, "src").also { it.mkdirs() }
        val target = File(workspace, "out")

        val result = RecordingsExporter.export(
            sourceDir = source,
            targetDir = target,
            now = Instant.parse("2026-04-28T12:00:00Z"),
        )

        assertNull("empty source dir should return null", result)
        assertFalse("target dir should not be created", target.exists())
    }

    @Test
    fun `multi-file export produces valid ZIP with all entries`() = runTest {
        val source = File(workspace, "src").also { it.mkdirs() }
        File(source, "a.wav").writeBytes(ByteArray(1024) { it.toByte() })
        File(source, "b.wav").writeBytes(ByteArray(2048) { (it / 2).toByte() })
        val target = File(workspace, "out")

        val result = RecordingsExporter.export(
            sourceDir = source,
            targetDir = target,
            now = Instant.parse("2026-04-28T12:00:00Z"),
        )

        assertNotNull(result)
        val zip = ZipFile(result!!)
        val names = zip.entries().toList().map { it.name }.sorted()
        assertEquals(listOf("a.wav", "b.wav"), names)
        zip.close()
        assertTrue(result.name.startsWith("pilgrim-recordings-"))
        assertTrue(result.name.endsWith(".zip"))
    }

    @Test
    fun `nested files are flattened by default`() = runTest {
        val source = File(workspace, "src").also { it.mkdirs() }
        val nested = File(source, "walks/123").also { it.mkdirs() }
        File(nested, "voice.wav").writeBytes(ByteArray(64))
        val target = File(workspace, "out")

        val result = RecordingsExporter.export(
            sourceDir = source,
            targetDir = target,
            now = Instant.parse("2026-04-28T12:00:00Z"),
        )

        assertNotNull(result)
        val zip = ZipFile(result!!)
        val names = zip.entries().toList().map { it.name }
        // Nested layout preserved as relative paths to keep walk
        // attribution discoverable in the exported archive.
        assertEquals(listOf("walks/123/voice.wav"), names)
        zip.close()
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.export

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a `.zip` of every regular file under [sourceDir]. Entry
 * names preserve the relative path from `sourceDir`, so a recordings
 * tree like `walks/<id>/<uuid>.wav` survives the round trip.
 *
 * Returns `null` when the source dir is empty (nothing to export);
 * the caller should surface a "no recordings yet" UI state rather
 * than producing an empty ZIP.
 *
 * Stage 10-G: `targetDir` should be `cacheDir/recordings_export/`
 * (declared in `res/xml/file_paths.xml`); files there are
 * FileProvider-shareable and auto-cleaned by the OS on storage
 * pressure.
 */
object RecordingsExporter {

    fun export(sourceDir: File, targetDir: File, now: Instant = Instant.now()): File? {
        val files = sourceDir.walkTopDown()
            .filter { it.isFile }
            .toList()
        if (files.isEmpty()) return null

        targetDir.mkdirs()
        val timeCode = BackupTimeCode.format(now)
        val out = File(targetDir, "pilgrim-recordings-$timeCode.zip")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
            for (file in files) {
                val entryName = file.relativeTo(sourceDir).invariantSeparatorsPath
                val entry = ZipEntry(entryName)
                zip.putNextEntry(entry)
                FileInputStream(file).use { input ->
                    input.copyTo(zip, bufferSize = COPY_BUFFER_BYTES)
                }
                zip.closeEntry()
            }
        }
        return out
    }

    private const val COPY_BUFFER_BYTES = 8 * 1024
}
```

- [ ] **Step 3: Run test (expect pass)**

Run: `./gradlew :app:testDebugUnitTest --tests "*RecordingsExporterTest*"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/export/RecordingsExporter.kt app/src/test/java/org/walktalkmeditate/pilgrim/data/export/RecordingsExporterTest.kt
git commit -m "feat(export): add RecordingsExporter ZIP builder with relative-path entries"
```

---

### Task C3: Add FileProvider config update

**Files:**
- Modify: `app/src/main/res/xml/file_paths.xml`

- [ ] **Step 1: Add cache-path entry**

```xml
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <cache-path name="etegami_cache" path="etegami/" />
    <!-- Stage 10-G: shared recordings ZIPs from Settings → Data → Audio export. -->
    <cache-path name="recordings_export" path="recordings_export/" />
</paths>
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/res/xml/file_paths.xml
git commit -m "chore(fileprovider): map recordings_export/ for Stage 10-G audio export"
```

---

### Task C4: Add `DataSettingsViewModel`

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsViewModel.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsViewModelTest.kt`

The VM exposes:
- `recordingCount: StateFlow<Int>` — passthrough of `WalkRepository.observeAllVoiceRecordings().map { it.size }`.
- `exportState: StateFlow<ExportState>` — `Idle | Exporting | Failed`.
- `exportRecordings()` — runs `RecordingsExporter` on Dispatchers.IO, emits a `RecordingsExportResult` via a `Channel<RecordingsExportResult>` (or `SharedFlow<RecordingsExportResult>`) that the screen collects to launch the share intent.

- [ ] **Step 1: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import app.cash.turbine.test
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

class DataSettingsViewModelTest {

    private lateinit var workspace: File
    private lateinit var sourceDir: File
    private lateinit var targetDir: File

    @Before
    fun setUp() {
        workspace = File(System.getProperty("java.io.tmpdir"), "ds-vm-${UUID.randomUUID()}")
        sourceDir = File(workspace, "rec").also { it.mkdirs() }
        targetDir = File(workspace, "out")
    }

    @After
    fun tearDown() {
        workspace.deleteRecursively()
    }

    @Test
    fun `recordingCount mirrors repository size`() = runTest {
        val recordings = flowOf(listOf(stubRecording(1), stubRecording(2)))
        val vm = DataSettingsViewModel(
            recordingsFlow = recordings,
            sourceDirProvider = { sourceDir },
            targetDirProvider = { targetDir },
        )
        vm.recordingCount.test(timeout = 5.seconds) {
            assertEquals(2, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `exportRecordings emits Success with file URI`() = runTest {
        File(sourceDir, "a.wav").writeBytes(ByteArray(64))
        val vm = DataSettingsViewModel(
            recordingsFlow = flowOf(listOf(stubRecording(1))),
            sourceDirProvider = { sourceDir },
            targetDirProvider = { targetDir },
        )

        vm.exportRecordings()

        vm.exportEvents.test(timeout = 5.seconds) {
            val event = awaitItem()
            assertTrue(event is RecordingsExportResult.Success)
            val file = (event as RecordingsExportResult.Success).file
            assertTrue(file.exists())
            assertTrue(file.name.startsWith("pilgrim-recordings-"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `exportRecordings emits Empty when source dir is empty`() = runTest {
        val vm = DataSettingsViewModel(
            recordingsFlow = flowOf(emptyList()),
            sourceDirProvider = { sourceDir },
            targetDirProvider = { targetDir },
        )

        vm.exportRecordings()

        vm.exportEvents.test(timeout = 5.seconds) {
            assertEquals(RecordingsExportResult.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun stubRecording(id: Long) = VoiceRecording(
        id = id,
        walkId = 1L,
        startTimestamp = 0L,
        endTimestamp = 1000L,
        durationMillis = 1000L,
        fileRelativePath = "walks/1/$id.wav",
    )
}
```

- [ ] **Step 2: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.export.RecordingsExporter
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem

sealed interface RecordingsExportResult {
    data class Success(val file: File) : RecordingsExportResult
    object Empty : RecordingsExportResult
    data class Failed(val message: String) : RecordingsExportResult
}

@HiltViewModel
class DataSettingsViewModel @Inject constructor(
    walkRepository: WalkRepository,
    @ApplicationContext context: Context,
    private val voiceRecordingFileSystem: VoiceRecordingFileSystem,
) : ViewModel() {

    /** Test-friendly secondary constructor — see [DataSettingsViewModelTest]. */
    internal constructor(
        recordingsFlow: Flow<List<VoiceRecording>>,
        sourceDirProvider: () -> File,
        targetDirProvider: () -> File,
    ) : this(
        recordingsFlow = recordingsFlow,
        sourceDirProvider = sourceDirProvider,
        targetDirProvider = targetDirProvider,
        injected = false,
    )

    // Hilt-bound primary path.
    private constructor(
        recordingsFlow: Flow<List<VoiceRecording>>,
        sourceDirProvider: () -> File,
        targetDirProvider: () -> File,
        injected: Boolean,
    ) : super() {
        this.recordingsFlow = recordingsFlow
        this.sourceDirProvider = sourceDirProvider
        this.targetDirProvider = targetDirProvider
    }

    private val recordingsFlow: Flow<List<VoiceRecording>>
    private val sourceDirProvider: () -> File
    private val targetDirProvider: () -> File

    init {
        // Hilt path resolves the providers from injected deps; the
        // test-friendly secondary constructor sets them directly.
        // Implementer note: the explicit secondary-constructor pattern
        // here is to avoid forcing a full DI graph in unit tests.
        // Equivalent simpler approach: take both providers as
        // injectable params via a small `DataSettingsEnv` data class.
    }

    val recordingCount: StateFlow<Int> = recordingsFlow
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    private val _exportEvents = MutableSharedFlow<RecordingsExportResult>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val exportEvents: SharedFlow<RecordingsExportResult> = _exportEvents.asSharedFlow()

    fun exportRecordings() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val source = sourceDirProvider()
                val target = targetDirProvider()
                runCatching {
                    val zip = RecordingsExporter.export(source, target, Instant.now())
                    if (zip == null) RecordingsExportResult.Empty
                    else RecordingsExportResult.Success(zip)
                }.getOrElse { e ->
                    RecordingsExportResult.Failed(e.message ?: "Export failed")
                }
            }
            _exportEvents.emit(result)
        }
    }
}
```

> **NOTE TO IMPLEMENTER:** The dual-constructor pattern above is finicky. Replace with a cleaner construction: wrap `sourceDirProvider` + `targetDirProvider` in a small `DataSettingsEnv` data class injected via Hilt, and let tests construct one directly. Alternatively skip the secondary constructor and let tests construct the VM via Hilt-style dependency injection of fakes for `WalkRepository` + `VoiceRecordingFileSystem`. Pick whichever is cleaner — the goal is unit-testable construction without spinning up Hilt.

- [ ] **Step 3: Run test**

Run: `./gradlew :app:testDebugUnitTest --tests "*DataSettingsViewModelTest*"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsViewModel.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsViewModelTest.kt
git commit -m "feat(data): add DataSettingsViewModel with audio recordings export"
```

---

### Task C5: Add `DataSettingsScreen` composable

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add strings**

```xml
<!-- Stage 10-G: DataCard / DataSettingsScreen -->
<string name="settings_data_title">Data</string>
<string name="settings_data_subtitle">Your walk archive</string>
<string name="settings_data_export_import">Export &amp; Import</string>
<string name="data_screen_title">Data</string>
<string name="data_section_walks_header">Walks</string>
<string name="data_action_export">Export My Data</string>
<string name="data_action_import">Import Data</string>
<string name="data_walks_footer">Export creates a .pilgrim archive with all your walks, transcriptions, and settings. Import restores walks from a .pilgrim file.</string>
<string name="data_action_journey">View My Journey</string>
<string name="data_journey_footer">Opens view.pilgrimapp.org and renders all your walks in the browser. Your data stays on your device — nothing is uploaded.</string>
<string name="data_section_audio_header">Audio</string>
<string name="data_action_export_recordings">Export Recordings</string>
<string name="data_audio_footer">Exports all voice recording audio files as a zip archive. These are not included in the data export.</string>
<string name="data_export_empty">No recordings yet to export.</string>
<string name="data_export_failed">Couldn\'t create the archive — please try again.</string>
<string name="data_coming_soon">Coming in a future release.</string>
```

- [ ] **Step 2: Implement composable**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.settings.SettingNavRow
import org.walktalkmeditate.pilgrim.ui.settings.settingsCard
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSettingsScreen(
    onBack: () -> Unit,
    viewModel: DataSettingsViewModel = hiltViewModel(),
) {
    val recordingCount by viewModel.recordingCount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val authority = remember(context) { "${context.packageName}.fileprovider" }

    val emptyMsg = stringResource(R.string.data_export_empty)
    val failedMsg = stringResource(R.string.data_export_failed)
    val comingSoon = stringResource(R.string.data_coming_soon)

    LaunchedEffect(Unit) {
        viewModel.exportEvents.collect { event ->
            when (event) {
                is RecordingsExportResult.Success -> {
                    val uri = FileProvider.getUriForFile(context, authority, event.file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, null))
                }
                RecordingsExportResult.Empty -> {
                    scope.launch { snackbarHostState.showSnackbar(emptyMsg) }
                }
                is RecordingsExportResult.Failed -> {
                    scope.launch { snackbarHostState.showSnackbar(failedMsg) }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.data_screen_title),
                        style = pilgrimType.heading,
                        color = pilgrimColors.ink,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = pilgrimColors.ink,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pilgrimColors.parchment,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = pilgrimColors.parchment,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Walks section (stub)
            item { SectionHeader(stringResource(R.string.data_section_walks_header)) }
            item {
                Column(modifier = Modifier.fillMaxWidth().settingsCard()) {
                    SettingNavRow(
                        label = stringResource(R.string.data_action_export),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch { snackbarHostState.showSnackbar(comingSoon) }
                        },
                    )
                    SettingNavRow(
                        label = stringResource(R.string.data_action_import),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch { snackbarHostState.showSnackbar(comingSoon) }
                        },
                    )
                }
            }
            item { SectionFooter(stringResource(R.string.data_walks_footer)) }

            // Journey section (stub)
            item {
                Column(modifier = Modifier.fillMaxWidth().settingsCard()) {
                    SettingNavRow(
                        label = stringResource(R.string.data_action_journey),
                        modifier = Modifier.fillMaxWidth(),
                        external = true,
                        onClick = {
                            scope.launch { snackbarHostState.showSnackbar(comingSoon) }
                        },
                    )
                }
            }
            item { SectionFooter(stringResource(R.string.data_journey_footer)) }

            // Audio section (only when count > 0)
            if (recordingCount > 0) {
                item { SectionHeader(stringResource(R.string.data_section_audio_header)) }
                item {
                    Column(modifier = Modifier.fillMaxWidth().settingsCard()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SettingNavRow(
                                label = stringResource(R.string.data_action_export_recordings),
                                detail = recordingCount.toString(),
                                modifier = Modifier.weight(1f),
                                onClick = { viewModel.exportRecordings() },
                            )
                        }
                    }
                }
                item { SectionFooter(stringResource(R.string.data_audio_footer)) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = pilgrimType.caption,
        color = pilgrimColors.fog,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}

@Composable
private fun SectionFooter(text: String) {
    Text(
        text = text,
        style = pilgrimType.caption,
        color = pilgrimColors.fog,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}
```

- [ ] **Step 3: Compile + commit**

Run: `./gradlew :app:assembleDebug`

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsScreen.kt app/src/main/res/values/strings.xml
git commit -m "feat(data): add DataSettingsScreen with iOS-faithful 3-section layout"
```

---

### Task C6: Add `DataCard` composable

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataCard.kt`

- [ ] **Step 1: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.settings.CardHeader
import org.walktalkmeditate.pilgrim.ui.settings.SettingNavRow
import org.walktalkmeditate.pilgrim.ui.settings.SettingsAction
import org.walktalkmeditate.pilgrim.ui.settings.settingsCard

@Composable
fun DataCard(
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().settingsCard()) {
        CardHeader(
            title = stringResource(R.string.settings_data_title),
            subtitle = stringResource(R.string.settings_data_subtitle),
        )
        SettingNavRow(
            label = stringResource(R.string.settings_data_export_import),
            onClick = { onAction(SettingsAction.OpenExportImport) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
```

- [ ] **Step 2: Compile + commit**

Run: `./gradlew :app:compileDebugKotlin`

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataCard.kt
git commit -m "feat(data): add DataCard with Export & Import nav row"
```

---

### Task C7: Wire navigation + integrate DataCard

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Add Routes.DATA_SETTINGS**

In `PilgrimNavHost.kt`'s `Routes` object:

```kotlin
const val DATA_SETTINGS = "data_settings"
```

- [ ] **Step 2: Add composable destination**

```kotlin
composable(Routes.DATA_SETTINGS) {
    org.walktalkmeditate.pilgrim.ui.settings.data.DataSettingsScreen(
        onBack = { navController.popBackStack() },
    )
}
```

- [ ] **Step 3: Wire 2 SettingsActions**

In `handleSettingsAction`, replace the `OpenExportImport` and `OpenJourneyViewer` entries from the Log.w catch-all:

```kotlin
SettingsAction.OpenExportImport ->
    navController.navigate(Routes.DATA_SETTINGS) { launchSingleTop = true }
SettingsAction.OpenJourneyViewer ->
    Log.w(TAG_NAV, "Stage 10-I: Journey viewer not yet implemented")
```

(The `OpenJourneyViewer` action is fired from inside DataSettingsScreen's stub Snackbar today; it remains a no-op at the nav layer until 10-I lands.) Remove `OpenExportImport` from the catch-all.

- [ ] **Step 4: Add DataCard to SettingsScreen**

Insert `item { DataCard(onAction = onAction) }` between PermissionsCard and ConnectCard. Final card order in this file:

```
CollectiveStatsCard
PracticeCard
VoiceCard
AtmosphereCard
(Soundscapes transitional row)
PermissionsCard          ⬅ Stage 10-E
DataCard                 ⬅ Stage 10-G  (new)
ConnectCard              ⬅ Stage 10-F
```

Add import: `import org.walktalkmeditate.pilgrim.ui.settings.data.DataCard`.

- [ ] **Step 5: Build + test + commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt
git commit -m "feat(data): wire DataCard + DataSettingsScreen into Settings + navigation"
```

---

## Final integration check

### Task Z1: Full unit test sweep + lint

- [ ] **Step 1: Run everything**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Expected: BUILD SUCCESSFUL on all three; `lintDebug` should report no new errors.

- [ ] **Step 2: Verify card rendering on a debug build**

Manually:
- Install on device or emulator: `./gradlew :app:installDebug`
- Launch app, complete onboarding, navigate to Settings tab.
- Scroll: confirm Permissions, Data, Connect cards render in that order.
- Tap each row in each card — verify: permission Grant launches dialog, Settings opens app details; Podcast opens Custom Tab; Trail Note navigates to FeedbackScreen; Rate launches Play Store; Share launches chooser; Export & Import navigates to DataSettingsScreen; recordings export shares ZIP (only when recordings exist).

- [ ] **Step 3: Push branch**

```bash
git push -u origin feat/stage-10-efg-permissions-connect-data
```

---

## Summary of files

### Created (new)

```
app/src/main/java/org/walktalkmeditate/pilgrim/permissions/PermissionStatus.kt
app/src/main/java/org/walktalkmeditate/pilgrim/permissions/PermissionAskedStore.kt
app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCardViewModel.kt
app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionAskedStoreAdapter.kt
app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCard.kt
app/src/main/java/org/walktalkmeditate/pilgrim/di/PermissionsCardModule.kt

app/src/main/java/org/walktalkmeditate/pilgrim/data/feedback/FeedbackCategory.kt
app/src/main/java/org/walktalkmeditate/pilgrim/data/feedback/FeedbackError.kt
app/src/main/java/org/walktalkmeditate/pilgrim/data/feedback/FeedbackRequest.kt
app/src/main/java/org/walktalkmeditate/pilgrim/data/feedback/FeedbackService.kt
app/src/main/java/org/walktalkmeditate/pilgrim/data/share/DeviceTokenSource.kt
app/src/main/java/org/walktalkmeditate/pilgrim/di/FeedbackModule.kt
app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackUiState.kt
app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackViewModel.kt
app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackScreen.kt
app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/ConnectCard.kt
app/src/main/java/org/walktalkmeditate/pilgrim/ui/util/CustomTabs.kt
app/src/main/java/org/walktalkmeditate/pilgrim/ui/util/PlayStore.kt
app/src/main/java/org/walktalkmeditate/pilgrim/ui/util/ShareIntents.kt

app/src/main/java/org/walktalkmeditate/pilgrim/data/export/BackupTimeCode.kt
app/src/main/java/org/walktalkmeditate/pilgrim/data/export/RecordingsExporter.kt
app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsViewModel.kt
app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsScreen.kt
app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataCard.kt

app/src/test/java/org/walktalkmeditate/pilgrim/permissions/PermissionAskedStoreTest.kt
app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCardViewModelTest.kt
app/src/test/java/org/walktalkmeditate/pilgrim/data/feedback/FeedbackServiceTest.kt
app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackViewModelTest.kt
app/src/test/java/org/walktalkmeditate/pilgrim/data/export/BackupTimeCodeTest.kt
app/src/test/java/org/walktalkmeditate/pilgrim/data/export/RecordingsExporterTest.kt
app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsViewModelTest.kt
```

### Modified

```
app/build.gradle.kts                                          # androidx.browser dependency
gradle/libs.versions.toml                                     # browser version + library
app/src/main/java/org/walktalkmeditate/pilgrim/data/share/DeviceTokenStore.kt   # implements DeviceTokenSource
app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt    # +Permissions, Data, Connect cards
app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt  # +Routes.FEEDBACK, Routes.DATA_SETTINGS, 7 new action handlers
app/src/main/res/xml/file_paths.xml                           # +recordings_export/ cache-path
app/src/main/res/values/strings.xml                           # ~30 new strings
```
