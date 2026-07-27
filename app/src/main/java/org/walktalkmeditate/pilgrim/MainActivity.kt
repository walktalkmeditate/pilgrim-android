// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelDownloadScheduler
import org.walktalkmeditate.pilgrim.data.appearance.AppearancePreferencesRepository
import org.walktalkmeditate.pilgrim.data.onboarding.OnboardingPreferencesRepository
import org.walktalkmeditate.pilgrim.data.recovery.WalkRecoveryRepository
import org.walktalkmeditate.pilgrim.data.sounds.LocalBellHapticEnabled
import org.walktalkmeditate.pilgrim.data.sounds.LocalBreathRhythm
import org.walktalkmeditate.pilgrim.data.sounds.LocalSoundsEnabled
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.ui.navigation.PilgrimNavHost
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.widget.DeepLinkTarget

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Pending widget deep-link target. Read from [Intent] in onCreate +
     * onNewIntent, consumed by [PilgrimNavHost] once permissions are
     * cleared. Hoisted out of `setContent` so onNewIntent can update it
     * without triggering a full recomposition rebuild.
     */
    private val pendingDeepLink = mutableStateOf<DeepLinkTarget?>(null)

    @Inject lateinit var walkController: WalkController
    @Inject lateinit var walkRecoveryRepository: WalkRecoveryRepository
    @Inject lateinit var appearancePreferences: AppearancePreferencesRepository
    @Inject lateinit var onboardingPreferences: OnboardingPreferencesRepository
    @Inject lateinit var soundsPreferences: SoundsPreferencesRepository
    @Inject lateinit var voicePreferences:
        org.walktalkmeditate.pilgrim.data.voice.VoicePreferencesRepository
    @Inject lateinit var voiceGuideSelection:
        org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideSelectionRepository
    @Inject
    lateinit var hemisphereRepository:
        org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
    @Inject lateinit var modelDownloadScheduler: WhisperModelDownloadScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pin status + nav bar scrim to parchment (light) and dark
        // parchment (dark) so the system bars match the canvas — no
        // white band behind the floating bottom pill in light mode.
        val parchmentLight = 0xFFF5F0E8.toInt()
        val parchmentDark = 0xFF1C1914.toInt()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(parchmentLight, parchmentDark),
            navigationBarStyle = SystemBarStyle.auto(parchmentLight, parchmentDark),
        )
        // Stage 9-A: parse widget intent extras at first launch.
        pendingDeepLink.value = DeepLinkTarget.parse(intent)
        // iOS-parity recovery: catches the warm-launch-after-swipe case
        // that `PilgrimApp.onCreate.recoverStaleWalks` misses. Application
        // onCreate only fires on cold launch; if the user swipes the app
        // away and the process is cached (FGS may keep it warm depending
        // on the Android version + OEM), reopen is a warm launch — same
        // process, same WalkController @Singleton state, no recovery hook
        // unless we do it here.
        //
        // savedInstanceState == null discriminates fresh-Activity-creation
        // (warm launch after task remove) from configuration changes
        // (rotation; savedInstanceState != null) and OS-preserved state.
        // Idle controller state means recovery already ran via PilgrimApp
        // (cold launch); skip. Active/Paused/Meditating means a walk was
        // alive in-memory — by definition it persisted across the user's
        // task-remove gesture, which iOS-parity says should END the walk.
        if (savedInstanceState == null) {
            recoverIfStaleActiveWalk()
        }
        setContent {
            val appearanceMode by appearancePreferences.appearanceMode.collectAsStateWithLifecycle()
            val soundsEnabled by soundsPreferences.soundsEnabled.collectAsStateWithLifecycle()
            val bellHapticEnabled by soundsPreferences.bellHapticEnabled.collectAsStateWithLifecycle()
            val breathRhythmId by soundsPreferences.breathRhythm.collectAsStateWithLifecycle()
            val voiceGuideEnabled by voicePreferences.voiceGuideEnabled.collectAsStateWithLifecycle()
            val selectedVoiceGuidePackId by voiceGuideSelection.selectedPackId.collectAsStateWithLifecycle()
            // iOS parity `PilgrimLogoView.swift:17-22@db4196e` — the
            // active guide is null when voice guide is disabled OR no
            // pack selected. Collapsing both flags into one optional
            // string here lets every consumer (PilgrimLogo, future
            // app-icon switcher) read a single value.
            val activeVoiceGuideId = selectedVoiceGuidePackId?.takeIf { voiceGuideEnabled }
            // iOS parity Color.swift@db4196e — all palette tokens get a
            // SeasonalColorEngine shift applied throughout the app.
            // Hemisphere comes from HemisphereRepository (defaults
            // Northern); date is observed via rememberCurrentDate which
            // refreshes on every ON_RESUME and at each midnight boundary
            // so a long-resumed Activity doesn't pin the palette to
            // yesterday.
            val hemisphere by hemisphereRepository.hemisphere.collectAsStateWithLifecycle()
            val today by org.walktalkmeditate.pilgrim.ui.theme.rememberCurrentDate()
            PilgrimTheme(
                appearanceMode = appearanceMode,
                hemisphere = hemisphere,
                today = today,
            ) {
                CompositionLocalProvider(
                    LocalSoundsEnabled provides soundsEnabled,
                    LocalBellHapticEnabled provides bellHapticEnabled,
                    LocalBreathRhythm provides breathRhythmId,
                    org.walktalkmeditate.pilgrim.data.voiceguide.LocalActiveVoiceGuideId
                        provides activeVoiceGuideId,
                ) {
                    // Stage 9.5-A: PilgrimNavHost owns the only Scaffold in
                    // the chain. MainActivity's previous Scaffold was
                    // double-counting insets (parent + child both consuming
                    // status/nav-bar padding) and would have produced bottom-
                    // bar gaps above the gesture inset.
                    val deepLink by pendingDeepLink
                    val welcomeCompleted by onboardingPreferences
                        .welcomeCompleted.collectAsStateWithLifecycle()
                    PilgrimNavHost(
                        pendingDeepLink = deepLink,
                        onDeepLinkConsumed = {
                            pendingDeepLink.value = null
                            // Strip the deep-link extras from the attached
                            // intent so a config change (rotation, locale)
                            // doesn't re-parse + re-navigate them.
                            // setIntent persists the mutation across
                            // activity recreation.
                            val cleared = intent.apply {
                                removeExtra(DeepLinkTarget.EXTRA_DEEP_LINK)
                                removeExtra(DeepLinkTarget.EXTRA_WALK_ID)
                            }
                            setIntent(cleared)
                        },
                        welcomeCompleted = welcomeCompleted,
                    )
                }
            }
        }
    }

    /**
     * U9 model-download trigger: first *foreground Activity* resume —
     * deliberately not `Application.onCreate`, so widget/broadcast/
     * service process starts never schedule the 148 MB transfer. The
     * process-level flag keeps this to one WorkManager round-trip per
     * process; `ensureEnqueued` itself is KEEP-idempotent, so a rare
     * double-fire (resume racing a slow first call) is harmless.
     */
    override fun onResume() {
        super.onResume()
        if (!modelDownloadEnsureRequested.compareAndSet(false, true)) return
        lifecycleScope.launch {
            try {
                modelDownloadScheduler.ensureEnqueued()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "model download ensure failed", t)
            }
        }
    }

    /**
     * Stage 9-A: singleTop launch mode means widget taps land here for
     * a re-running activity. setIntent() first so subsequent
     * getIntent() reads the fresh intent (Android docs requirement),
     * then re-parse for the deep-link target.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink.value = DeepLinkTarget.parse(intent)
    }

    private fun recoverIfStaleActiveWalk() {
        // CRITICAL gate: if WalkTrackingService FGS is still alive, the
        // walk is genuinely in-progress in the `:tracker` process — the
        // user came BACK to it (notification tap, launcher icon while
        // in background). Do NOT finalize. Only finalize if FGS is
        // gone, which signals the user-initiated swipe-from-recents
        // path.
        //
        // Pre-:tracker-split versions also short-circuited on
        // `walkController.state.value` being Idle/Finished. Under the
        // split, UI's state derives from Room asynchronously and is
        // not guaranteed to be settled by the time MainActivity.onCreate
        // runs warm-launch recovery. Dropping the state-value gate
        // makes recoverStaleWalks the authoritative scan; it's a single
        // Room SELECT + 0-1 UPDATEs (a few ms) so the cost on a
        // no-stale-walk warm launch is negligible.
        if (org.walktalkmeditate.pilgrim.service.WalkTrackingService.isFgsAlive(this)) {
            Log.i(TAG, "warm-launch recovery: FGS alive, NOT finalizing")
            return
        }
        Log.i(TAG, "warm-launch recovery: FGS gone, scanning for stale walks")
        try {
            val recoveredId = runBlocking { walkController.recoverStaleWalks() }
            if (recoveredId != null) {
                walkRecoveryRepository.markRecoveredBlocking(recoveredId)
                Log.i(TAG, "warm-launch recovery armed banner for walk=$recoveredId")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "warm-launch recovery failed", t)
        }
    }

    private companion object {
        const val TAG = "MainActivity"
        val modelDownloadEnsureRequested = AtomicBoolean(false)
    }
}
