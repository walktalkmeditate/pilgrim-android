# Stage 5-B Implementation Plan — Temple bell

Spec: `docs/superpowers/specs/2026-04-20-stage-5b-temple-bell-design.md`.

`BellPlayer` (MediaPlayer wrapper) + `MeditationBellObserver` (@Singleton state observer) + sox-generated `bell.ogg`. No UI changes.

---

## Task 1 — Generate `bell.ogg` via sox + commit

From the worktree root:

```bash
mkdir -p app/src/main/res/raw
sox -n -r 44100 -c 1 app/src/main/res/raw/bell.ogg \
    synth 3.0 pluck C5 pluck G5 pluck E6 \
    gain -3 \
    fade 0.005 3.0 0.5 \
    compand 0.0,1.0 6:-70,-60,-20 -5 -90 0.2
file app/src/main/res/raw/bell.ogg    # verify: "Ogg data, Vorbis audio, mono, 44100 Hz, ..."
ls -lh app/src/main/res/raw/bell.ogg  # verify size 30-80 KB
```

**Verify:** file exists, is decoded by `file` as OGG Vorbis, size in the 30-80 KB range. Commit the binary.

If generated size exceeds 80 KB, retry with `-r 22050` (half sample rate; fine for a bell). If under 20 KB, something went wrong — inspect sox output.

---

## Task 2 — Extend `AudioFocusCoordinator` with `requestBellDucking`

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/AudioFocusCoordinator.kt`

Refactor the existing private `request(usage, onLossListener)` to also accept `contentType` and `gainMode`, defaulting to existing behavior. Add a new public `requestBellDucking(onLossListener): Boolean`.

```kotlin
/**
 * Request transient focus for a short audible cue (bell, earcon).
 * Uses AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK so concurrent media
 * playback (music, podcast) is briefly attenuated rather than
 * paused. USAGE_MEDIA + CONTENT_TYPE_SONIFICATION routes the
 * sound through speakers (not earpiece) and marks it as a
 * non-speech, non-music audible notification.
 */
fun requestBellDucking(onLossListener: (() -> Unit)? = null): Boolean =
    request(
        usage = AudioAttributes.USAGE_MEDIA,
        contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
        gainMode = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        onLossListener = onLossListener,
    )

private fun request(
    usage: Int,
    contentType: Int = AudioAttributes.CONTENT_TYPE_SPEECH,
    gainMode: Int = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
    onLossListener: (() -> Unit)? = null,
): Boolean {
    abandonIfHeld()
    val attrs = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(contentType)
        .build()
    val builder = AudioFocusRequest.Builder(gainMode)
        .setAudioAttributes(attrs)
        .setWillPauseWhenDucked(false)
        .setAcceptsDelayedFocusGain(false)
    // (rest unchanged)
    ...
}
```

Existing `requestTransient()` / `requestMediaPlayback()` call sites continue to work — they use the default `contentType = CONTENT_TYPE_SPEECH` and `gainMode = AUDIOFOCUS_GAIN_TRANSIENT`.

**Verify:** `./gradlew :app:compileDebugKotlin`.

---

## Task 3 — `MeditationBellScope.kt` qualifier + scope

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/MeditationBellScope.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope] that
 * backs [MeditationBellObserver]'s state-transition collection.
 *
 * Separate from `viewModelScope` / other short-lived scopes because
 * the observer must live for the entire app process — it subscribes
 * to `WalkController.state` on app creation and fires bells on every
 * Meditating transition across the app's lifetime. Provided with
 * `SupervisorJob()` so one failed emission doesn't cancel the whole
 * scope, and `Dispatchers.Default` since bell-trigger work is pure
 * Flow collection + occasional MediaPlayer creation (no Main-thread
 * requirement). Same shape as `HemisphereRepositoryScope` (Stage 3-D).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MeditationBellScope
```

---

## Task 4 — `BellPlayer.kt`

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/BellPlayer.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.R

/**
 * Plays the bundled temple-bell asset once per [play] call. Used by
 * [MeditationBellObserver] at the boundaries of a meditation session.
 *
 * Implementation: creates a fresh [MediaPlayer] per play (~50-150ms
 * setup overhead — imperceptible at tap-paced bell moments),
 * requests ducking audio focus via [AudioFocusCoordinator] so a
 * concurrent music/podcast briefly attenuates rather than pauses,
 * releases the player and abandons focus on completion. A safety-net
 * timeout guarantees cleanup even if the completion callback doesn't
 * fire (MediaPlayer's reliability on some devices is uneven).
 *
 * See `docs/superpowers/specs/2026-04-20-stage-5b-temple-bell-design.md`.
 */
@Singleton
class BellPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioFocus: AudioFocusCoordinator,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Fire the bell. No-op if audio focus is denied (phone call,
     * another app has exclusive focus, etc.) — the user simply doesn't
     * hear the bell, no crash, no silent error.
     */
    fun play() {
        val granted = audioFocus.requestBellDucking(onLossListener = {
            Log.i(TAG, "bell focus lost")
        })
        if (!granted) {
            Log.w(TAG, "bell focus denied; skipping play")
            return
        }
        val player = try {
            MediaPlayer.create(context, R.raw.bell) ?: run {
                Log.w(TAG, "MediaPlayer.create returned null")
                audioFocus.abandon()
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaPlayer creation failed", t)
            audioFocus.abandon()
            return
        }
        // Route through media stream (speakers, not earpiece).
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        // Single completion path — both the natural callback and the
        // safety-net timeout route through `cleanup` which is guarded
        // against double-release.
        var cleanedUp = false
        val cleanup = {
            if (!cleanedUp) {
                cleanedUp = true
                try {
                    player.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "MediaPlayer release failed", t)
                }
                audioFocus.abandon()
            }
        }
        player.setOnCompletionListener { cleanup() }
        player.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
            cleanup()
            true
        }
        mainHandler.postDelayed({ cleanup() }, SAFETY_NET_MS)
        try {
            player.start()
        } catch (t: Throwable) {
            Log.w(TAG, "MediaPlayer start failed", t)
            cleanup()
        }
    }

    private companion object {
        const val TAG = "BellPlayer"
        // Bell asset is 3.0s; 5000ms provides generous safety margin
        // for the natural onCompletion callback. If it doesn't fire by
        // then, we force-release.
        const val SAFETY_NET_MS = 5_000L
    }
}
```

**Verify:** `./gradlew :app:compileDebugKotlin`.

---

## Task 5 — `MeditationBellObserver.kt`

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/MeditationBellObserver.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.walk.WalkController

/**
 * Subscribes to [WalkController.state] and fires [BellPlayer.play] on
 * every Meditating boundary transition:
 *  - Active → Meditating (start bell)
 *  - Meditating → Active (end bell)
 *  - Meditating → Finished (end bell — walk finished during meditation)
 *
 * Discards its first collection — observing a [WalkController]'s
 * CURRENT state at app-singleton-init time is not a transition (it's
 * cold-process Idle, or a restored Meditating session). Only real,
 * observed transitions ring the bell.
 *
 * Instantiated eagerly at app start via [PilgrimApp.onCreate]'s
 * `@Inject` reference — without the reference, Hilt is lazy and the
 * observer's `init` block never runs.
 *
 * Same bell asset fires for both directions (start and end), matching
 * iOS. See the design spec for the single-asset rationale.
 */
@Singleton
class MeditationBellObserver @Inject constructor(
    controller: WalkController,
    private val bellPlayer: BellPlayer,
    @MeditationBellScope scope: CoroutineScope,
) {
    init {
        scope.launch {
            var lastStateClass: KClass<out WalkState>? = null
            controller.state.collect { state ->
                val curr = state::class
                val prev = lastStateClass
                lastStateClass = curr
                // First emission is the CURRENT state of a @Singleton
                // controller at app init; not a transition. Skip.
                if (prev == null) return@collect
                val wasMeditating = prev == WalkState.Meditating::class
                val isMeditating = curr == WalkState.Meditating::class
                if (wasMeditating != isMeditating) {
                    bellPlayer.play()
                }
            }
        }
    }
}
```

**Verify:** `./gradlew :app:compileDebugKotlin`.

---

## Task 6 — Extend `AudioModule` with the `@MeditationBellScope` provider

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/di/AudioModule.kt`

Add to the `companion object`:

```kotlin
@Provides
@Singleton
@MeditationBellScope
fun provideMeditationBellScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

Imports:

```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.walktalkmeditate.pilgrim.audio.MeditationBellScope
```

**Verify:** `./gradlew :app:compileDebugKotlin`.

---

## Task 7 — Inject `MeditationBellObserver` into `PilgrimApp` for eager instantiation

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApp.kt`

Add the field:

```kotlin
@Inject lateinit var meditationBellObserver: MeditationBellObserver
```

And in `onCreate`, reference it (after super.onCreate, before other work) to ensure Hilt instantiates it:

```kotlin
override fun onCreate() {
    super.onCreate()
    // Force Hilt to instantiate MeditationBellObserver so its init
    // block subscribes to WalkController.state for the entire app
    // lifetime. Without this reference, @Singleton binding is lazy.
    meditationBellObserver.hashCode()

    MapboxOptions.accessToken = BuildConfig.MAPBOX_ACCESS_TOKEN
    orphanSweeperScheduler.scheduleDaily()
}
```

Import: `org.walktalkmeditate.pilgrim.audio.MeditationBellObserver`.

**Verify:** `./gradlew :app:compileDebugKotlin`.

---

## Task 8 — `BellPlayerTest.kt` (Robolectric)

**Create:** `app/src/test/java/org/walktalkmeditate/pilgrim/audio/BellPlayerTest.kt`

Use a `FakeAudioFocusCoordinator` fake (or a spy on the real one via Robolectric's shadow).

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BellPlayerTest {

    private lateinit var context: Application
    private lateinit var audioFocus: SpyAudioFocusCoordinator
    private lateinit var player: BellPlayer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val audioManager = context.getSystemService(AudioManager::class.java)
        audioFocus = SpyAudioFocusCoordinator(audioManager)
        player = BellPlayer(context = context, audioFocus = audioFocus)
    }

    @Test
    fun `play - requests bell-ducking focus`() {
        audioFocus.grantNextRequest = true
        player.play()
        assertEquals(1, audioFocus.requestBellDuckingCount)
    }

    @Test
    fun `play - when focus denied, abandons nothing and does not crash`() {
        audioFocus.grantNextRequest = false
        player.play()
        assertEquals(1, audioFocus.requestBellDuckingCount)
        // When focus is denied, no player was created, so no abandon
        // needed. The coordinator itself guards against abandoning a
        // request that wasn't granted.
        assertEquals(0, audioFocus.abandonCount)
    }
}

/**
 * Minimal spy over [AudioFocusCoordinator] that records
 * [requestBellDucking] calls without actually contacting the system
 * AudioManager (Robolectric's shadow grants focus by default, but
 * we want explicit control over the grant decision for test clarity).
 *
 * Extends the real class so the `request` pipeline + AtomicReference
 * behavior remain identical to production. Override only the public
 * methods exercised in tests.
 */
private class SpyAudioFocusCoordinator(audioManager: AudioManager) :
    AudioFocusCoordinator(audioManager) {
    var grantNextRequest: Boolean = true
    var requestBellDuckingCount: Int = 0
    var abandonCount: Int = 0

    override fun requestBellDucking(onLossListener: (() -> Unit)?): Boolean {
        requestBellDuckingCount += 1
        return grantNextRequest
    }

    override fun abandon() {
        abandonCount += 1
    }
}
```

Note: Robolectric stubs `MediaPlayer.create` — it may return a `MediaPlayer` whose state is unknown. The `play - requests bell-ducking focus` test doesn't assert playback completion; it only asserts the focus-request side of the API. That's the testable surface.

Note the `open class` requirement: to allow the spy override, `AudioFocusCoordinator` must either be `open` or we use a test-local fake that mirrors the interface. For minimum-invasive change, I'll keep `AudioFocusCoordinator` as-is and use a test fake via composition rather than inheritance. Let me revise:

Actually — cleaner approach: introduce a small `AudioFocusRequester` interface that `AudioFocusCoordinator` implements, and have `BellPlayer` depend on the interface. Over-engineered for Stage 5-B. Use a different approach:

Make `AudioFocusCoordinator` methods `open` in the production class (explicit override marker). Single-line diff. Test spy then works cleanly.

Alternative: mark `AudioFocusCoordinator` as a class BellPlayer depends on directly, and test BellPlayer with a REAL AudioFocusCoordinator + Robolectric's ShadowAudioManager (which handles focus grants). That's the closest-to-production test. Use this approach.

Revised `BellPlayerTest`:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BellPlayerTest {

    private lateinit var context: Application
    private lateinit var audioFocus: AudioFocusCoordinator
    private lateinit var player: BellPlayer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val audioManager = context.getSystemService(AudioManager::class.java)
        audioFocus = AudioFocusCoordinator(audioManager)
        player = BellPlayer(context = context, audioFocus = audioFocus)
    }

    @Test
    fun `play - does not crash`() {
        // Robolectric's ShadowAudioManager grants focus by default;
        // MediaPlayer.create under Robolectric is stubbed. This test
        // verifies the full code path doesn't throw — actual audio is
        // device-QA territory.
        player.play()
    }
}
```

Scope BellPlayerTest to the minimal "doesn't throw" assertion; VM/observer tests carry the logic weight.

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*BellPlayerTest"`.

---

## Task 9 — `MeditationBellObserverTest.kt`

**Create:** `app/src/test/java/org/walktalkmeditate/pilgrim/audio/MeditationBellObserverTest.kt`

Use a `FakeBellPlayer` counting `play()` calls and a `FakeWalkController` exposing a `MutableStateFlow<WalkState>`.

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

@OptIn(ExperimentalCoroutinesApi::class)
class MeditationBellObserverTest {

    private val acc = WalkAccumulator(walkId = 1L, startedAt = 1_000L)

    @Test
    fun `first emission Idle does not fire bell`() = runTest {
        val scenario = newScenario(WalkState.Idle)
        scenario.new(initial = WalkState.Idle)
        advanceUntilIdle()
        assertEquals(0, scenario.player.playCount)
        scenario.scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `Active then Meditating fires one bell`() = runTest {
        val scenario = newScenario(WalkState.Active(acc))
        scenario.new(initial = WalkState.Active(acc))
        advanceUntilIdle()
        scenario.stateFlow.value = WalkState.Meditating(acc, meditationStartedAt = 2_000L)
        advanceUntilIdle()
        assertEquals(1, scenario.player.playCount)
        scenario.scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `Meditating then Active fires one bell`() = runTest {
        val scenario = newScenario(WalkState.Meditating(acc, meditationStartedAt = 2_000L))
        scenario.new(initial = WalkState.Meditating(acc, meditationStartedAt = 2_000L))
        advanceUntilIdle()
        scenario.stateFlow.value = WalkState.Active(acc)
        advanceUntilIdle()
        // First emission (Meditating) skipped; second (Active) fires.
        assertEquals(1, scenario.player.playCount)
        scenario.scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `Meditating then Finished fires one bell`() = runTest {
        val scenario = newScenario(WalkState.Meditating(acc, meditationStartedAt = 2_000L))
        scenario.new(initial = WalkState.Meditating(acc, meditationStartedAt = 2_000L))
        advanceUntilIdle()
        scenario.stateFlow.value = WalkState.Finished(acc, endedAt = 3_000L)
        advanceUntilIdle()
        assertEquals(1, scenario.player.playCount)
        scenario.scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `full sequence Idle Active Meditating Active Meditating Finished fires 3 bells`() = runTest {
        val scenario = newScenario(WalkState.Idle)
        scenario.new(initial = WalkState.Idle)
        advanceUntilIdle()
        // Idle → Active: not a meditation transition, 0 bells
        scenario.stateFlow.value = WalkState.Active(acc)
        advanceUntilIdle()
        assertEquals(0, scenario.player.playCount)
        // Active → Meditating: 1 bell
        scenario.stateFlow.value = WalkState.Meditating(acc, meditationStartedAt = 2_000L)
        advanceUntilIdle()
        assertEquals(1, scenario.player.playCount)
        // Meditating → Active: 1 bell (total 2)
        scenario.stateFlow.value = WalkState.Active(acc)
        advanceUntilIdle()
        assertEquals(2, scenario.player.playCount)
        // Active → Meditating: 1 bell (total 3)
        scenario.stateFlow.value = WalkState.Meditating(acc, meditationStartedAt = 4_000L)
        advanceUntilIdle()
        assertEquals(3, scenario.player.playCount)
        // Meditating → Finished: 1 bell (total 4)
        scenario.stateFlow.value = WalkState.Finished(acc, endedAt = 5_000L)
        advanceUntilIdle()
        assertEquals(4, scenario.player.playCount)
        scenario.scope.coroutineContext[Job]?.cancel()
    }

    // ----- scaffolding -------------------------------------------------

    private data class Scenario(
        val stateFlow: MutableStateFlow<WalkState>,
        val player: FakeBellPlayer,
        val scope: CoroutineScope,
    ) {
        fun new(initial: WalkState) {
            // Observer's init block starts collecting. Scenario is
            // freshly created per test so there's no cross-test leakage.
        }
    }

    private fun TestScope.newScenario(initial: WalkState): Scenario {
        val stateFlow = MutableStateFlow(initial)
        val fakePlayer = FakeBellPlayer()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val fakeController = FakeWalkController(stateFlow)
        MeditationBellObserver(fakeController, fakePlayer, scope)
        return Scenario(stateFlow, fakePlayer, scope)
    }
}

private class FakeBellPlayer : BellPlayer(
    context = org.robolectric.RuntimeEnvironment.getApplication(),
    audioFocus = AudioFocusCoordinator(
        org.robolectric.RuntimeEnvironment.getApplication()
            .getSystemService(android.media.AudioManager::class.java),
    ),
) {
    var playCount = 0
    override fun play() { playCount += 1 }
}
```

...Hmm, `BellPlayer` needs to be `open` for `override fun play()`. Alternative: introduce a small interface:

```kotlin
interface BellPlaying { fun play() }
class BellPlayer @Inject constructor(...) : BellPlaying { override fun play() {...} }
```

Then `MeditationBellObserver` depends on `BellPlaying`, and the test fake implements it without extending the real class. Cleaner; opens the door for Stage 5-D to swap in a voice-guide-aware player.

Let me revise Task 5 to depend on the interface, not the concrete class. Task 5 gets `@Binds BellPlayer → BellPlaying` in AudioModule.

Actually simpler: make `BellPlayer.play` `open` via `open class BellPlayer`. Single-line change, no new interface. For MVP this is fine; if Stage 5-D needs an abstraction later, refactor then.

**Revised Task 4 (BellPlayer):** declare as `open class`:

```kotlin
@Singleton
open class BellPlayer @Inject constructor(...) {
    open fun play() { ... }
}
```

And the FakeBellPlayer in the test simply extends `BellPlayer` overriding `play`.

For the test to construct the fake, it needs a real BellPlayer constructor call. That means also needing a real AudioFocusCoordinator. Provide minimal fakes via Robolectric application context.

Wait this is getting tangled. Let me use a different approach: use `@Config` Robolectric + directly construct real AudioFocusCoordinator for the fake's parent. This compiles but is ugly.

Alternative: `MeditationBellObserver` takes a `BellPlaying` interface. `BellPlayer` implements it. Test fake implements it directly. Cleaner.

**Going with the interface approach.** Added to plan:

New file `BellPlaying.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

/**
 * Minimal bell-playing abstraction so [MeditationBellObserver] tests
 * can inject a counting fake without constructing a real [BellPlayer]
 * (which requires a Robolectric Application context + AudioManager).
 * Stage 5-D may later introduce a voice-guide-aware player that also
 * implements this interface.
 */
interface BellPlaying {
    fun play()
}
```

`BellPlayer` implements `BellPlaying`. `MeditationBellObserver` depends on `BellPlaying`. AudioModule adds `@Binds @Singleton fun bindBellPlayer(impl: BellPlayer): BellPlaying`.

Update tests accordingly:

```kotlin
private class FakeBellPlayer : BellPlaying {
    var playCount = 0
    override fun play() { playCount += 1 }
}
```

Much cleaner. 

---

## Revised Task 4 — `BellPlayer.kt` + `BellPlaying.kt`

Update Task 4 to create both files:

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/BellPlaying.kt` (interface).

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/BellPlayer.kt` (implements `BellPlaying`).

The concrete `BellPlayer` class is shown above; just add `: BellPlaying` to the class declaration and `override` on `play()`.

---

## Revised Task 5 — `MeditationBellObserver` depends on `BellPlaying`

Change the constructor parameter type from `BellPlayer` → `BellPlaying`.

---

## Revised Task 6 — `AudioModule` also binds `BellPlaying`

Add `@Binds @Singleton fun bindBellPlayer(impl: BellPlayer): BellPlaying` to the abstract `AudioModule` class (not the companion).

---

## Task 10 — Fake `WalkController` for observer test

Since `WalkController` is a concrete class and `MeditationBellObserver` takes it by type, the test needs a way to substitute. Options:

- Make `WalkController` implement an interface `WalkControllerContract` exposing only `state: StateFlow<WalkState>`. Observer depends on the interface.
- Use a real `WalkController` in the test with mocked dependencies. Heavy.
- Make the observer take `StateFlow<WalkState>` directly (simpler — it only reads state).

**Going with the third option** — `MeditationBellObserver` takes a `StateFlow<WalkState>` directly, with a `@Provides` in AudioModule that extracts it from `WalkController.state`:

```kotlin
@Provides
@Singleton
@MeditationObservedState
fun provideMeditationObservedState(controller: WalkController): StateFlow<WalkState> =
    controller.state
```

Observer constructor:
```kotlin
@Singleton
class MeditationBellObserver @Inject constructor(
    @MeditationObservedState private val walkState: StateFlow<WalkState>,
    private val bellPlayer: BellPlaying,
    @MeditationBellScope scope: CoroutineScope,
) {
    init {
        scope.launch {
            // ... same logic, using `walkState` instead of `controller.state`
        }
    }
}
```

Tests can supply any StateFlow<WalkState>. Clean.

But now I have TWO qualifiers (`@MeditationBellScope` and `@MeditationObservedState`) and a pure StateFlow dependency. Slight overkill.

Alternative: keep observer's dependency as concrete `WalkController`, but test via the STATE FLOW the controller already exposes. Tests build a minimal test controller by extending `WalkController` or use a `MutableStateFlow` passed via a different constructor. But WalkController has many @Inject deps.

Cleanest-and-simplest: let the observer take the `StateFlow<WalkState>` directly via a custom qualifier. Small amount of plumbing, clean testability. Going with that.

Update plan accordingly.

---

## Task 11 — Build + test

```bash
export PATH="$HOME/.asdf/shims:$PATH" JAVA_HOME="$(asdf where java 2>/dev/null)"
cd <worktree>
./gradlew :app:testDebugUnitTest :app:lintDebug
```

**Verify:** all green. ~6 new test cases pass.

---

## Task 12 — Pre-commit audit

- `git diff --stat` — 6 new + 3 modified files (plus the `bell.ogg` binary)
- Verify SPDX on all new `.kt` files
- Verify `bell.ogg` is in `res/raw/` and < 100 KB
- No new lint warnings
- No OutRun references
