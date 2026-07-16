// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.seek

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.core.celestial.MoonCalc
import org.walktalkmeditate.pilgrim.core.celestial.turningMarkerForEpochMillis
import org.walktalkmeditate.pilgrim.data.practice.PracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.seek.SeekPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.isInProgress
import org.walktalkmeditate.pilgrim.domain.seek.SeekChainGenerator
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint
import org.walktalkmeditate.pilgrim.domain.seek.SeekSeed
import org.walktalkmeditate.pilgrim.domain.seek.SeekSeededGenerator
import org.walktalkmeditate.pilgrim.domain.seek.SeekSky
import org.walktalkmeditate.pilgrim.domain.seek.SeekTint
import org.walktalkmeditate.pilgrim.location.LocationSource
import org.walktalkmeditate.pilgrim.ui.theme.forHemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereStore
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.seek.SeekPendingSession
import org.walktalkmeditate.pilgrim.walk.seek.SeekSessionStore

/**
 * Stage machine for the seek setup ritual: accuracy gate → duration
 * question → required intention → breath transition → ready. Wander
 * walks are born [SeekSetupStage.Ready] and none of the advance methods
 * engage. iOS `SeekSetupStage`
 * (`ActiveWalkViewModel.swift:666-679@c1745e8`).
 */
sealed class SeekSetupStage {
    data object VerifyingAccuracy : SeekSetupStage()
    data object DurationQuestion : SeekSetupStage()
    data object Intention : SeekSetupStage()
    data object Transition : SeekSetupStage()
    data object Ready : SeekSetupStage()
    data class Cancelled(val reason: SeekSetupCancelReason) : SeekSetupStage()
}

enum class SeekSetupCancelReason { UserDismissed, AccuracyDeclined, GpsTimeout }

/**
 * Seam over the fine-location check so the stage machine is testable
 * without a Context (iOS `SeekAccuracyProviding`,
 * `ActiveWalkViewModel.swift:685-688@c1745e8`). Seek hard-gates on
 * precise location: approximate (coarse-only) fixes make 80-120 m
 * clearing regions physically undetectable — there is no degrade path.
 * The *request* half of iOS's protocol lives on the screen (permission
 * launchers are Activity-scoped); results return via
 * [SeekSetupViewModel.onAccuracyResult].
 */
fun interface SeekAccuracyChecking {
    fun hasPreciseLocation(): Boolean
}

/**
 * Seam over [org.walktalkmeditate.pilgrim.audio.seek.SeekHaptics.breathIn]
 * so the gateway's one haptic can ride this VM without dragging a real
 * [android.os.Vibrator] into JVM tests (iOS fires
 * `HapticPattern.seekBreathIn` directly in `SeekGatewayView.swift:79`).
 */
fun interface SeekBreathHaptic {
    fun fire()
}

/**
 * Owns the seek setup ritual for one active-walk surface. Collapses
 * iOS's `init(mode:)` + `beginSeekSetup()` pair into [beginSetup]
 * (Hilt VMs are argless; the mode arrives as a nav argument — port
 * spec D1). All transitions keep iOS's exact stage guards
 * (`ActiveWalkViewModel.swift:715-769@c1745e8`).
 *
 * The GPS-lock hold (iOS `beginSeekGPSLock`,
 * `ActiveWalkViewModel+Seek.swift:51-70@c1745e8`) starts WITH the
 * breath transition; the first fix with horizontal accuracy ≤ 50 m
 * seeds and generates the clearing chain, publishing it to
 * [SeekSessionStore] for the U9 orchestrator. Only the transition may
 * time out (20 s): once the stage reached Ready a late timeout stays
 * silent and the armed fix collection simply locks the chain when the
 * sky finally answers. A real timeout bumps the generation so a late
 * accurate fix can never lock a chain into a cancelled setup.
 */
@HiltViewModel
class SeekSetupViewModel @Inject constructor(
    private val seekPreferences: SeekPreferencesRepository,
    private val practicePreferences: PracticePreferencesRepository,
    private val hemisphereStore: HemisphereStore,
    private val locationSource: LocationSource,
    private val sessionStore: SeekSessionStore,
    private val walkController: WalkController,
    private val clock: Clock,
    private val accuracyChecker: SeekAccuracyChecking,
    private val breathHaptic: SeekBreathHaptic,
    private val qaFlags: SeekQaFlags,
) : ViewModel() {

    private val _stage = MutableStateFlow<SeekSetupStage>(SeekSetupStage.Ready)
    val stage: StateFlow<SeekSetupStage> = _stage.asStateFlow()

    /**
     * One-shot ask for the screen to launch the ACCESS_FINE_LOCATION
     * upgrade request (coarse-only grant). Buffered(1) + tryEmit: the
     * collector lives on the same screen that calls [beginSetup].
     */
    private val _accuracyUpgradeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val accuracyUpgradeRequests: SharedFlow<Unit> = _accuracyUpgradeRequests.asSharedFlow()

    /**
     * Captured once at setup start so the caption doesn't vanish
     * mid-flow when `safetyShown` flips during this same setup (iOS
     * `ActiveWalkViewModel.swift:69-71@c1745e8`).
     */
    var showsSafetyCaption: Boolean = false
        private set

    /**
     * The sky's mark on this seek (turning or full moon), fixed once at
     * setup start, gated on celestial awareness. Marker is
     * hemisphere-corrected per house precedent PR #169/#170 (conscious
     * divergence from iOS's raw northern-named marker — port spec D4).
     */
    var tint: SeekTint? = null
        private set

    var durationMinutes: Int? = null
        private set

    private var mode: WalkMode = WalkMode.Wander
    private var begun = false
    private var capturedIntention: String? = null
    private var chainLocked = false
    private var gpsGeneration = 0
    private var gpsJob: Job? = null
    private var timeoutJob: Job? = null

    /** Idempotent; iOS init + `beginSeekSetup` collapsed (spec D1). */
    fun beginSetup(mode: WalkMode) {
        if (begun) return
        begun = true
        this.mode = mode
        if (mode != WalkMode.Seek) return
        sessionStore.clear()
        showsSafetyCaption = !seekPreferences.safetyShown.value
        _stage.value = SeekSetupStage.VerifyingAccuracy
        computeTintOnce()
        if (accuracyChecker.hasPreciseLocation()) {
            _stage.value = SeekSetupStage.DurationQuestion
        } else {
            _accuracyUpgradeRequests.tryEmit(Unit)
        }
    }

    /**
     * Completion of the screen-launched fine-location request. Carries
     * iOS's completion-handler guard: a result landing after the stage
     * moved on (user already cancelled) is dropped
     * (`ActiveWalkViewModel.swift:729-732@c1745e8`).
     */
    fun onAccuracyResult(granted: Boolean) {
        if (mode != WalkMode.Seek || _stage.value != SeekSetupStage.VerifyingAccuracy) return
        _stage.value = if (granted) {
            SeekSetupStage.DurationQuestion
        } else {
            SeekSetupStage.Cancelled(SeekSetupCancelReason.AccuracyDeclined)
        }
    }

    /** Snapshot for the duration sheet's preselection (iOS
     *  `SeekDurationView.init` reading `seekLastDurationMinutes`). */
    fun lastUsedDurationMinutes(): Int = seekPreferences.lastDurationMinutes.value

    /**
     * Preset row tap — persists the choice immediately, before Begin
     * (iOS `SeekDurationView.swift:76-79@c1745e8`).
     */
    fun rememberDurationSelection(minutes: Int) {
        if (mode != WalkMode.Seek) return
        viewModelScope.launch { seekPreferences.setLastDurationMinutes(minutes) }
    }

    /** Begin tapped on the duration sheet. */
    fun advanceDuration(minutes: Int) {
        if (mode != WalkMode.Seek || _stage.value != SeekSetupStage.DurationQuestion) return
        durationMinutes = minutes
        viewModelScope.launch {
            seekPreferences.setLastDurationMinutes(minutes)
            seekPreferences.setSafetyShown(true)
        }
        _stage.value = SeekSetupStage.Intention
    }

    /**
     * Intention committed. The text is one voice in the chain's seed
     * (`SeekSeed.make(intention:fix:)`,
     * `ActiveWalkViewModel+Seek.swift:79-81@c1745e8`); blank collapses
     * to null (unseeded-by-intention, same as iOS's nil intention).
     * Entering Transition arms the GPS lock.
     */
    fun advanceIntentionSet(intention: String?) {
        if (mode != WalkMode.Seek || _stage.value != SeekSetupStage.Intention) return
        capturedIntention = intention?.trim()?.takeIf { it.isNotBlank() }
        _stage.value = SeekSetupStage.Transition
        beginGpsLock()
    }

    /** Gateway overlay finished its ~6.2 s (or reduce-motion 2.6 s) run. */
    fun advanceTransitionComplete() {
        if (mode != WalkMode.Seek || _stage.value != SeekSetupStage.Transition) return
        _stage.value = SeekSetupStage.Ready
    }

    /**
     * User dismissal (duration-sheet swipe-down / Cancel). Ignored once
     * Ready; never overwrites an earlier cancel reason
     * (`ActiveWalkViewModel.swift:753-757@c1745e8`).
     */
    fun cancelSetup() {
        if (mode != WalkMode.Seek) return
        val current = _stage.value
        if (current == SeekSetupStage.Ready || current is SeekSetupStage.Cancelled) return
        gpsGeneration += 1
        gpsJob?.cancel()
        timeoutJob?.cancel()
        sessionStore.clear()
        _stage.value = SeekSetupStage.Cancelled(SeekSetupCancelReason.UserDismissed)
    }

    /** Gateway timeline hook — the one breath at t=2.0 s (spec B5/D8). */
    fun fireGatewayBreath() {
        breathHaptic.fire()
    }

    private fun computeTintOnce() {
        if (!practicePreferences.celestialAwarenessEnabled.value) return
        val now = clock.now()
        val marker = turningMarkerForEpochMillis(now)
            .forHemisphere(hemisphereStore.hemisphere.value)
        val isFullMoon =
            MoonCalc.moonPhase(Instant.ofEpochMilli(now)).name == FULL_MOON_PHASE_NAME
        tint = SeekSky.tint(marker = marker, isFullMoon = isFullMoon)
    }

    private fun beginGpsLock() {
        gpsGeneration += 1
        val generation = gpsGeneration
        gpsJob = viewModelScope.launch {
            val fix = try {
                // Raw feed, not the 20 m-gated pipeline flow: fixes in
                // the (20, 50] band — the normal urban-canyon regime —
                // must lock the chain, so [qualifiesForChainLock]'s
                // ≤50 m check is the only accuracy gate (iOS's
                // pre-recording feed is ungated; same starvation
                // reasoning as the engine feed, U9 spec D2).
                locationSource.rawLocationFlow()
                    .filter { qualifiesForChainLock(it) }
                    .first()
            } catch (ce: CancellationException) {
                throw ce
            } catch (se: SecurityException) {
                // Fine location revoked between the accuracy gate and the
                // transition (Settings mid-flow). No fix will ever come;
                // the 20 s timeout resolves the stage.
                return@launch
            }
            if (gpsGeneration != generation) return@launch
            lockChain(fix)
        }
        timeoutJob = viewModelScope.launch {
            delay(GPS_LOCK_TIMEOUT_MS)
            if (gpsGeneration != generation) return@launch
            failGpsLock()
        }
    }

    private fun lockChain(fix: LocationPoint) {
        if (chainLocked) return
        chainLocked = true
        // Mirrors iOS `startSeekEngine`'s generation bump
        // (`ActiveWalkViewModel+Seek.swift:74@c1745e8`): the armed
        // timeout becomes a no-op — a locked chain after Ready must
        // never be followed by a stray cancel.
        gpsGeneration += 1
        val moment = clock.now()
        val seed = SeekSeed.make(
            intention = capturedIntention,
            momentEpochMillis = moment,
            fix = fix,
        )
        val duration = durationMinutes ?: seekPreferences.lastDurationMinutes.value
        val start = SeekPoint(latitude = fix.latitude, longitude = fix.longitude)
        val generated = SeekChainGenerator.generate(
            durationMinutes = duration,
            start = start,
            rng = SeekSeededGenerator(seed),
        )
        val chain = when (val qaMode = qaFlags.nearClearingsMode()) {
            0 -> generated
            else -> SeekQaOverrides.compressTowardOrigin(generated, start, qaMode)
        }
        sessionStore.set(
            SeekPendingSession(
                chain = chain,
                durationMinutes = duration,
                tint = tint,
                seededAtEpochMillis = moment,
                intention = capturedIntention,
            ),
        )
    }

    /**
     * iOS `failSeekSetupGPSLock`
     * (`ActiveWalkViewModel.swift:765-769@c1745e8`): only the breath
     * transition may time out; after Ready the walk may already be
     * recording, so a late timeout stays silent and the armed fix
     * collection keeps waiting.
     */
    private fun failGpsLock() {
        if (mode != WalkMode.Seek || chainLocked ||
            _stage.value != SeekSetupStage.Transition
        ) {
            return
        }
        gpsGeneration += 1
        gpsJob?.cancel()
        sessionStore.clear()
        _stage.value = SeekSetupStage.Cancelled(SeekSetupCancelReason.GpsTimeout)
    }

    /**
     * Pre-walk back-out leaves no walk to consume the pending session —
     * clear it so it can't leak into a later walk. A live walk keeps its
     * session (the store outlives this VM; terminal transitions clear it
     * via WalkLifecycleObserver). Extracted internal so the JVM test can
     * exercise the disposal contract ([ViewModel.onCleared] is
     * `protected`).
     */
    internal fun clearPendingSessionIfUnconsumed() {
        if (!walkController.state.value.isInProgress) {
            sessionStore.clear()
        }
    }

    override fun onCleared() {
        clearPendingSessionIfUnconsumed()
    }

    internal companion object {
        /** iOS `seekGPSLockTimeoutSeconds = 20` (`ActiveWalkViewModel.swift:713`). */
        const val GPS_LOCK_TIMEOUT_MS = 20_000L

        /** iOS `seekChainFixAccuracyMeters = 50.0` (`ActiveWalkViewModel+Seek.swift:36`). */
        const val CHAIN_FIX_ACCURACY_METERS = 50f

        /** [org.walktalkmeditate.pilgrim.core.celestial.MoonCalc] bucket name. */
        const val FULL_MOON_PHASE_NAME = "Full Moon"

        /**
         * iOS filter `hAcc >= 0 && hAcc <= 50`
         * (`ActiveWalkViewModel+Seek.swift:58@c1745e8`); Android's
         * unknown-accuracy fixes carry null, which — like iOS's
         * negative sentinel — never qualifies.
         */
        fun qualifiesForChainLock(fix: LocationPoint): Boolean {
            val accuracy = fix.horizontalAccuracyMeters ?: return false
            return accuracy >= 0f && accuracy <= CHAIN_FIX_ACCURACY_METERS
        }
    }
}
