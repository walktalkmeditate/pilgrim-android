# Stage 9.5-E: Settings Atmosphere card — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Settings → Atmosphere card with a 3-option Auto / Light / Dark appearance picker; persist via DataStore Preferences and apply app-wide via `PilgrimTheme`.

**Architecture:** New `AppearancePreferencesRepository` (Hilt `@Singleton`, mirrors `VoiceGuideSelectionRepository`). `PilgrimTheme` accepts `appearanceMode: AppearanceMode = System` and overrides `isSystemInDarkTheme()`. `MainActivity` reads `.value` synchronously at `setContent` so the first composition picks up the persisted preference. `SettingsScreen` renders a new `AtmosphereCard` at the top of its existing column. Single PR.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3 (`SingleChoiceSegmentedButtonRow`), Hilt, DataStore Preferences, JUnit 4 + Robolectric + Turbine + Compose UI test.

**Spec:** `docs/superpowers/specs/2026-04-27-stage-9-5-e-settings-atmosphere-design.md`

---

### Task 1: AppearanceMode enum + repository contract

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/appearance/AppearanceMode.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/appearance/AppearancePreferencesRepository.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/appearance/AppearancePreferencesScope.kt`

- [ ] **Step 1: Create the enum + qualifier + repository interface.**

`AppearanceMode.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.appearance

/**
 * User-selectable app appearance. Mirrors the iOS `appearanceMode`
 * preference (`UserPreferences.appearanceMode`):
 *
 * - [System]: respect `isSystemInDarkTheme()` (default for fresh installs).
 * - [Light]: force light theme regardless of system setting.
 * - [Dark]: force dark theme regardless of system setting.
 *
 * Stored in DataStore as the lowercase string form of [name]
 * (`"system" / "light" / "dark"`) for cross-platform parity with
 * iOS's `UserDefaults` storage.
 */
enum class AppearanceMode {
    System,
    Light,
    Dark;

    /** Lowercase storage form (matches iOS string keys). */
    fun storageValue(): String = name.lowercase()

    companion object {
        /** Default for fresh installs: respect system. */
        val DEFAULT: AppearanceMode = System

        /**
         * Decode a stored string back into an [AppearanceMode]. Unknown
         * values fall back to [DEFAULT] — guards against manual DataStore
         * edits and forward-compat (a future build adding e.g. `"sepia"`
         * shouldn't crash on downgrade).
         */
        fun fromStorageValue(stored: String?): AppearanceMode = when (stored) {
            "system" -> System
            "light" -> Light
            "dark" -> Dark
            else -> DEFAULT
        }
    }
}
```

`AppearancePreferencesScope.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.appearance

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * that backs [AppearancePreferencesRepository.appearanceMode]'s
 * `stateIn` collection. Same pattern as `VoiceGuideSelectionScope`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppearancePreferencesScope
```

`AppearancePreferencesRepository.kt` (interface form so tests can substitute a fake without touching DataStore):
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.appearance

import kotlinx.coroutines.flow.StateFlow

/**
 * Persists the user's chosen [AppearanceMode]. Exposed as a
 * [StateFlow] so [org.walktalkmeditate.pilgrim.MainActivity]
 * can read the current value synchronously at `setContent` time
 * (`Eagerly` start strategy + `.value`) and the [SettingsViewModel]
 * can observe changes for picker rendering.
 *
 * Mirrors iOS's `UserPreferences.appearanceMode` key.
 */
interface AppearancePreferencesRepository {
    val appearanceMode: StateFlow<AppearanceMode>
    suspend fun setAppearanceMode(mode: AppearanceMode)
}
```

- [ ] **Step 2: Build to verify the new files compile.**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/appearance/
git commit -m "feat(appearance): add AppearanceMode enum + repository interface"
```

---

### Task 2: DataStore implementation + Hilt binding

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/appearance/DataStoreAppearancePreferencesRepository.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/di/AppearanceModule.kt`

- [ ] **Step 1: Implement the DataStore-backed repository.**

`DataStoreAppearancePreferencesRepository.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.appearance

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * DataStore-backed [AppearancePreferencesRepository]. Eagerly starts
 * the StateFlow so [org.walktalkmeditate.pilgrim.MainActivity]'s
 * `setContent` can read `.value` at first composition without
 * missing the persisted preference (a `WhileSubscribed` start
 * strategy would return the default until a subscriber attaches,
 * causing a one-frame light/dark flash on cold launch).
 *
 * Reuses the shared `pilgrim_prefs` DataStore — one more string key
 * (`appearance_mode`) alongside the existing voice-guide / soundscape /
 * recovery keys.
 */
@Singleton
class DataStoreAppearancePreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @AppearancePreferencesScope private val scope: CoroutineScope,
) : AppearancePreferencesRepository {

    override val appearanceMode: StateFlow<AppearanceMode> = dataStore.data
        .catch { t ->
            // Disk read failures (corrupt prefs, transient I/O)
            // shouldn't crash the theme — emit empty so we fall back
            // to the default. Same resilience pattern as
            // `VoiceGuideSelectionRepository` (Stage 5-D).
            Log.w(TAG, "appearance datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { AppearanceMode.fromStorageValue(it[KEY_MODE]) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, AppearanceMode.DEFAULT)

    override suspend fun setAppearanceMode(mode: AppearanceMode) {
        dataStore.edit { it[KEY_MODE] = mode.storageValue() }
    }

    private companion object {
        const val TAG = "AppearancePrefs"
        val KEY_MODE = stringPreferencesKey("appearance_mode")
    }
}
```

`AppearanceModule.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.walktalkmeditate.pilgrim.data.appearance.AppearancePreferencesRepository
import org.walktalkmeditate.pilgrim.data.appearance.AppearancePreferencesScope
import org.walktalkmeditate.pilgrim.data.appearance.DataStoreAppearancePreferencesRepository

/**
 * Hilt bindings for the appearance-preferences layer. `@Binds` for
 * the interface → DataStore impl; `@Provides` for the long-lived
 * [CoroutineScope] backing the StateFlow's `stateIn`. Same shape as
 * `VoiceGuideModule` — abstract class + nested companion object.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppearanceModule {

    @Binds
    @Singleton
    abstract fun bindAppearancePreferencesRepository(
        impl: DataStoreAppearancePreferencesRepository,
    ): AppearancePreferencesRepository

    companion object {
        @Provides
        @Singleton
        @AppearancePreferencesScope
        fun provideAppearancePreferencesScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
```

- [ ] **Step 2: Build to verify Hilt graph still resolves.**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/appearance/DataStoreAppearancePreferencesRepository.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/di/AppearanceModule.kt
git commit -m "feat(appearance): DataStore-backed repository + Hilt module"
```

---

### Task 3: Robolectric repository test

**Files:**
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/data/appearance/DataStoreAppearancePreferencesRepositoryTest.kt`

- [ ] **Step 1: Write the failing test.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.appearance

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DataStoreAppearancePreferencesRepositoryTest {

    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "appearance-test-${System.nanoTime()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun `default is System when no key written`() = runTest(dispatcher) {
        val repo = DataStoreAppearancePreferencesRepository(dataStore, scope)
        repo.appearanceMode.test {
            assertEquals(AppearanceMode.System, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setAppearanceMode persists across new repository instance`() = runTest(dispatcher) {
        val repo1 = DataStoreAppearancePreferencesRepository(dataStore, scope)
        repo1.setAppearanceMode(AppearanceMode.Dark)

        val repo2 = DataStoreAppearancePreferencesRepository(dataStore, scope)
        repo2.appearanceMode.test {
            assertEquals(AppearanceMode.Dark, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unknown stored value falls back to System`() = runTest(dispatcher) {
        // Simulate a forward-compat scenario: a future build wrote
        // "sepia" but this version only knows system/light/dark.
        dataStore.edit { it[stringPreferencesKey("appearance_mode")] = "sepia" }

        val repo = DataStoreAppearancePreferencesRepository(dataStore, scope)
        repo.appearanceMode.test {
            assertEquals(AppearanceMode.System, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `appearanceMode emits new value after setAppearanceMode`() = runTest(dispatcher) {
        val repo = DataStoreAppearancePreferencesRepository(dataStore, scope)
        repo.appearanceMode.test {
            assertEquals(AppearanceMode.System, awaitItem())
            repo.setAppearanceMode(AppearanceMode.Light)
            assertEquals(AppearanceMode.Light, awaitItem())
            repo.setAppearanceMode(AppearanceMode.Dark)
            assertEquals(AppearanceMode.Dark, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Run the test.**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.data.appearance.DataStoreAppearancePreferencesRepositoryTest"`
Expected: PASS (4/4).

- [ ] **Step 3: Commit.**

```bash
git add app/src/test/java/org/walktalkmeditate/pilgrim/data/appearance/DataStoreAppearancePreferencesRepositoryTest.kt
git commit -m "test(appearance): Robolectric DataStore round-trip + invalid-value guard"
```

---

### Task 4: Theme override

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Theme.kt`

- [ ] **Step 1: Update PilgrimTheme to accept an AppearanceMode and resolve darkTheme accordingly.**

Replace the `darkTheme` parameter with `appearanceMode`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode

@Composable
fun PilgrimTheme(
    appearanceMode: AppearanceMode = AppearanceMode.System,
    content: @Composable () -> Unit,
) {
    // Resolve appearance preference -> dark/light flag. `System` defers
    // to the platform via `isSystemInDarkTheme()`; `Light`/`Dark` force
    // the theme regardless of system setting.
    val darkTheme = when (appearanceMode) {
        AppearanceMode.System -> isSystemInDarkTheme()
        AppearanceMode.Light -> false
        AppearanceMode.Dark -> true
    }

    val colors = if (darkTheme) pilgrimDarkColors() else pilgrimLightColors()
    // Cache the PilgrimTypography instance across recompositions. Without this,
    // every PilgrimTheme recomposition would allocate 12 fresh TextStyle instances
    // AND — more importantly — invalidate every typography consumer, because
    // LocalPilgrimTypography is a staticCompositionLocalOf (reference-equality).
    val type = remember { pilgrimTypography() }

    val m3 = if (darkTheme) {
        darkColorScheme(
            primary = colors.stone,
            onPrimary = colors.parchment,
            background = colors.parchment,
            onBackground = colors.ink,
            surface = colors.parchmentSecondary,
            onSurface = colors.ink,
            surfaceVariant = colors.parchmentTertiary,
            outline = colors.stone.copy(alpha = 0.4f),
            error = colors.rust,
        )
    } else {
        lightColorScheme(
            primary = colors.stone,
            onPrimary = colors.parchment,
            background = colors.parchment,
            onBackground = colors.ink,
            surface = colors.parchmentSecondary,
            onSurface = colors.ink,
            surfaceVariant = colors.parchmentTertiary,
            outline = colors.stone.copy(alpha = 0.4f),
            error = colors.rust,
        )
    }

    val m3Typography = MaterialTheme.typography.copy(
        displayLarge = type.displayLarge,
        displayMedium = type.displayMedium,
        titleLarge = type.heading,
        bodyLarge = type.body,
        bodyMedium = type.body,
        labelLarge = type.button,
        labelMedium = type.caption,
        labelSmall = type.micro,
    )

    CompositionLocalProvider(
        LocalPilgrimColors provides colors,
        LocalPilgrimTypography provides type,
    ) {
        MaterialTheme(
            colorScheme = m3,
            typography = m3Typography,
            content = content,
        )
    }
}
```

- [ ] **Step 2: Audit existing PilgrimTheme call sites.**

Run: `grep -rn "PilgrimTheme(" app/src --include="*.kt"`

Existing callers either pass no arguments (use the default — still works) or pass `darkTheme = ...` for previews/tests. For each `darkTheme = true/false` caller, switch to `appearanceMode = AppearanceMode.Dark/Light`. Expected previews to update: `WalkSummaryPreview`, `JournalPreview`, etc. — find and adjust.

For each found caller passing `darkTheme = true`, replace with `appearanceMode = AppearanceMode.Dark`.
For each found caller passing `darkTheme = false`, replace with `appearanceMode = AppearanceMode.Light`.

- [ ] **Step 3: Build.**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Theme.kt
# Plus any preview call sites that needed updating.
git commit -m "feat(theme): PilgrimTheme honors AppearanceMode override"
```

---

### Task 5: Theme override Compose test

**Files:**
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/theme/PilgrimThemeAppearanceTest.kt`

- [ ] **Step 1: Write the test.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimThemeAppearanceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `light forces light colors`() {
        var captured: PilgrimColors? = null
        composeRule.setContent {
            PilgrimTheme(appearanceMode = AppearanceMode.Light) {
                val c = pilgrimColors
                SideEffect { captured = c }
            }
        }
        composeRule.runOnIdle {
            assertEquals(pilgrimLightColors().parchment, captured!!.parchment)
        }
    }

    @Test
    fun `dark forces dark colors`() {
        var captured: PilgrimColors? = null
        composeRule.setContent {
            PilgrimTheme(appearanceMode = AppearanceMode.Dark) {
                val c = pilgrimColors
                SideEffect { captured = c }
            }
        }
        composeRule.runOnIdle {
            assertEquals(pilgrimDarkColors().parchment, captured!!.parchment)
        }
    }

    @Test
    fun `light and dark resolve to different palettes`() {
        // Sanity: the test would silently pass if both palettes were
        // accidentally identical. Verifying they differ ensures the
        // assertions above mean what they say.
        assertNotEquals(pilgrimLightColors().parchment, pilgrimDarkColors().parchment)
    }
}
```

- [ ] **Step 2: Run the test.**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.theme.PilgrimThemeAppearanceTest"`
Expected: PASS (3/3).

- [ ] **Step 3: Commit.**

```bash
git add app/src/test/java/org/walktalkmeditate/pilgrim/ui/theme/PilgrimThemeAppearanceTest.kt
git commit -m "test(theme): appearance-mode override resolves correct palette"
```

---

### Task 6: Wire MainActivity to read the persisted preference

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/MainActivity.kt`

- [ ] **Step 1: Inject the repository and pass to PilgrimTheme.**

Add the field:
```kotlin
@Inject lateinit var appearancePreferences: AppearancePreferencesRepository
```

In `setContent`, observe the StateFlow with `collectAsStateWithLifecycle()`:
```kotlin
setContent {
    val appearanceMode by appearancePreferences.appearanceMode.collectAsStateWithLifecycle()
    PilgrimTheme(appearanceMode = appearanceMode) {
        val deepLink by pendingDeepLink
        PilgrimNavHost(
            pendingDeepLink = deepLink,
            onDeepLinkConsumed = { ... },
        )
    }
}
```

Required imports:
```kotlin
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.data.appearance.AppearancePreferencesRepository
```

`Eagerly` start strategy on the StateFlow guarantees the first frame uses the persisted value rather than the default — no dark-flash-then-light-snap on cold launch.

- [ ] **Step 2: Build.**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/MainActivity.kt
git commit -m "feat(theme): MainActivity applies persisted appearance preference"
```

---

### Task 7: Strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add the 6 new strings near the existing `settings_*` block.**

Insert after `settings_voice_guides_subtitle` (line ~92), before the `voice_guide_picker_title` block:

```xml
<string name="settings_atmosphere_title">Atmosphere</string>
<string name="settings_atmosphere_subtitle">Look and feel</string>
<string name="settings_appearance_label">Appearance</string>
<string name="settings_appearance_auto">Auto</string>
<string name="settings_appearance_light">Light</string>
<string name="settings_appearance_dark">Dark</string>
```

- [ ] **Step 2: Build.**

Run: `./gradlew :app:processDebugResources`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(strings): atmosphere card + appearance picker labels"
```

---

### Task 8: AtmosphereCard composable

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/AtmosphereCard.kt`

- [ ] **Step 1: Implement the card.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Settings → Atmosphere card. Currently surfaces a 3-option appearance
 * picker; the iOS counterpart also has a Sounds master toggle, which
 * is deferred to a later stage (gating every audio call site is a
 * larger scope). Visually mirrors iOS's flat card with subtle ink-stroke
 * border.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtmosphereCard(
    currentMode: AppearanceMode,
    onSelectMode: (AppearanceMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(pilgrimColors.parchmentSecondary.copy(alpha = 0.5f))
            .border(1.dp, pilgrimColors.fog.copy(alpha = 0.2f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.settings_atmosphere_title),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
            )
            Text(
                text = stringResource(R.string.settings_atmosphere_subtitle),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.settings_appearance_label),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            APPEARANCE_OPTIONS.forEachIndexed { index, option ->
                val selected = option.mode == currentMode
                SegmentedButton(
                    selected = selected,
                    onClick = { onSelectMode(option.mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = APPEARANCE_OPTIONS.size,
                    ),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = pilgrimColors.stone,
                        activeContentColor = pilgrimColors.parchment,
                        inactiveContainerColor = pilgrimColors.parchmentSecondary.copy(alpha = 0f),
                        inactiveContentColor = pilgrimColors.fog,
                        activeBorderColor = pilgrimColors.stone,
                        inactiveBorderColor = pilgrimColors.fog.copy(alpha = 0.3f),
                    ),
                    label = { Text(stringResource(option.labelRes)) },
                )
            }
        }
    }
}

private data class AppearanceOption(val mode: AppearanceMode, val labelRes: Int)

private val APPEARANCE_OPTIONS: List<AppearanceOption> = listOf(
    AppearanceOption(AppearanceMode.System, R.string.settings_appearance_auto),
    AppearanceOption(AppearanceMode.Light, R.string.settings_appearance_light),
    AppearanceOption(AppearanceMode.Dark, R.string.settings_appearance_dark),
)
```

- [ ] **Step 2: Build.**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/AtmosphereCard.kt
git commit -m "feat(settings): AtmosphereCard composable with appearance picker"
```

---

### Task 9: AtmosphereCard Compose test

**Files:**
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/AtmosphereCardTest.kt`

- [ ] **Step 1: Write the test.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AtmosphereCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders all three segments`() {
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.System,
                    onSelectMode = {},
                )
            }
        }
        composeRule.onNodeWithText("Auto").assertExists()
        composeRule.onNodeWithText("Light").assertExists()
        composeRule.onNodeWithText("Dark").assertExists()
    }

    @Test
    fun `current selection is marked selected`() {
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.Dark,
                    onSelectMode = {},
                )
            }
        }
        composeRule.onNodeWithText("Dark").assertIsSelected()
    }

    @Test
    fun `tapping a segment fires onSelectMode with the right value`() {
        var picked: AppearanceMode? = null
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.System,
                    onSelectMode = { picked = it },
                )
            }
        }
        composeRule.onNodeWithText("Light").performClick()
        composeRule.runOnIdle {
            assertEquals(AppearanceMode.Light, picked)
        }
    }
}
```

- [ ] **Step 2: Run the test.**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.settings.AtmosphereCardTest"`
Expected: PASS (3/3).

- [ ] **Step 3: Commit.**

```bash
git add app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/AtmosphereCardTest.kt
git commit -m "test(settings): AtmosphereCard renders + selects + fires callback"
```

---

### Task 10: SettingsViewModel surfaces appearance mode

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt`

- [ ] **Step 1: Inject the repo and expose the state + setter.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode
import org.walktalkmeditate.pilgrim.data.appearance.AppearancePreferencesRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveStats

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val collectiveRepository: CollectiveRepository,
    private val appearancePreferences: AppearancePreferencesRepository,
) : ViewModel() {

    val stats: StateFlow<CollectiveStats?> = collectiveRepository.stats
    val optIn: StateFlow<Boolean> = collectiveRepository.optIn
    val appearanceMode: StateFlow<AppearanceMode> = appearancePreferences.appearanceMode

    fun setOptIn(value: Boolean) {
        viewModelScope.launch { collectiveRepository.setOptIn(value) }
    }

    fun setAppearanceMode(mode: AppearanceMode) {
        viewModelScope.launch { appearancePreferences.setAppearanceMode(mode) }
    }

    fun fetchOnAppear() {
        viewModelScope.launch { collectiveRepository.fetchIfStale() }
    }
}
```

- [ ] **Step 2: Build.**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt
git commit -m "feat(settings): SettingsViewModel exposes appearance state + setter"
```

---

### Task 11: SettingsViewModel test

**Files:**
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/data/appearance/FakeAppearancePreferencesRepository.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModelAppearanceTest.kt`

- [ ] **Step 1: Build the fake.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.appearance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory test double for [AppearancePreferencesRepository]. Used by
 * `SettingsViewModelAppearanceTest` and any future surface that needs
 * to vary the mode without spinning up a real DataStore.
 */
class FakeAppearancePreferencesRepository(
    initial: AppearanceMode = AppearanceMode.System,
) : AppearancePreferencesRepository {

    private val _appearanceMode = MutableStateFlow(initial)
    override val appearanceMode: StateFlow<AppearanceMode> = _appearanceMode.asStateFlow()

    override suspend fun setAppearanceMode(mode: AppearanceMode) {
        _appearanceMode.value = mode
    }
}
```

- [ ] **Step 2: Write the VM test.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode
import org.walktalkmeditate.pilgrim.data.appearance.FakeAppearancePreferencesRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveStats

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelAppearanceTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `appearanceMode reflects repo value`() = runTest(dispatcher) {
        val repo = FakeAppearancePreferencesRepository(initial = AppearanceMode.Dark)
        val vm = SettingsViewModel(
            collectiveRepository = stubCollectiveRepository(),
            appearancePreferences = repo,
        )
        vm.appearanceMode.test {
            assertEquals(AppearanceMode.Dark, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setAppearanceMode delegates to repo`() = runTest(dispatcher) {
        val repo = FakeAppearancePreferencesRepository()
        val vm = SettingsViewModel(
            collectiveRepository = stubCollectiveRepository(),
            appearancePreferences = repo,
        )
        vm.appearanceMode.test {
            assertEquals(AppearanceMode.System, awaitItem())
            vm.setAppearanceMode(AppearanceMode.Light)
            assertEquals(AppearanceMode.Light, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun stubCollectiveRepository(): CollectiveRepository {
        val mock = mockk<CollectiveRepository>(relaxed = true)
        coEvery { mock.stats } returns MutableStateFlow<CollectiveStats?>(null)
        coEvery { mock.optIn } returns MutableStateFlow(false)
        return mock
    }
}
```

If the project lacks `mockk`, switch the stub to a hand-rolled fake (subclass `CollectiveRepository` with no-op overrides). Audit existing `SettingsViewModel` tests in this repo first to see which mocking approach is conventional.

- [ ] **Step 3: Run.**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.settings.SettingsViewModelAppearanceTest"`
Expected: PASS (2/2).

- [ ] **Step 4: Commit.**

```bash
git add app/src/test/java/org/walktalkmeditate/pilgrim/data/appearance/FakeAppearancePreferencesRepository.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModelAppearanceTest.kt
git commit -m "test(settings): VM exposes appearance mode + delegates setter to repo"
```

---

### Task 12: Render AtmosphereCard at top of SettingsScreen

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Add the card to the column above CollectiveStatsCard.**

In the `Column` body, before `CollectiveStatsCard`:

```kotlin
val appearanceMode by viewModel.appearanceMode.collectAsStateWithLifecycle()
AtmosphereCard(
    currentMode = appearanceMode,
    onSelectMode = viewModel::setAppearanceMode,
)
Spacer(Modifier.height(12.dp))
CollectiveStatsCard(stats = stats)
```

Add the imports:
```kotlin
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
```

- [ ] **Step 2: Build.**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt
git commit -m "feat(settings): render AtmosphereCard at top of Settings"
```

---

### Task 13: Full test + lint suite

- [ ] **Step 1: Run the full unit-test suite.**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — no regressions.

- [ ] **Step 2: Run lint.**

Run: `./gradlew :app:lintDebug`
Expected: no NEW lint issues introduced by Stage 9.5-E.

- [ ] **Step 3: If anything fails, fix in place. No commit step here unless fixes are needed.**

---

## Self-review checklist

- [ ] Spec coverage: every "Files to CREATE / MODIFY" entry in the spec has at least one task above
- [ ] No placeholders: no "TBD", "TODO", "implement later"
- [ ] Type consistency: `AppearanceMode` enum + `AppearancePreferencesRepository` signatures match across all tasks
- [ ] String IDs match exactly between strings.xml and the composables that reference them
- [ ] Tests cover: default, persistence, invalid value, theme override, segmented row, VM passthrough
