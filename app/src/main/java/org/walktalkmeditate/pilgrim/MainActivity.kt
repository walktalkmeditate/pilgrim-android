// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.walktalkmeditate.pilgrim.data.recovery.WalkRecoveryRepository
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
            PilgrimTheme {
                // Stage 9.5-A: PilgrimNavHost owns the only Scaffold in
                // the chain. MainActivity's previous Scaffold was
                // double-counting insets (parent + child both consuming
                // status/nav-bar padding) and would have produced bottom-
                // bar gaps above the gesture inset.
                val deepLink by pendingDeepLink
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
                )
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
        val state = walkController.state.value
        val needsRecovery = state is org.walktalkmeditate.pilgrim.domain.WalkState.Active ||
            state is org.walktalkmeditate.pilgrim.domain.WalkState.Paused ||
            state is org.walktalkmeditate.pilgrim.domain.WalkState.Meditating
        if (!needsRecovery) {
            Log.i(TAG, "warm-launch recovery: controller=${state::class.simpleName}, no-op")
            return
        }
        // CRITICAL gate: if WalkTrackingService FGS is still alive, the
        // walk is genuinely in-progress in this same process — the user
        // came BACK to it (notification tap, launcher icon while in
        // background). Do NOT finalize. Only finalize if FGS is gone,
        // which signals the user-initiated swipe-from-recents path.
        if (org.walktalkmeditate.pilgrim.service.WalkTrackingService.isFgsAlive()) {
            Log.i(
                TAG,
                "warm-launch recovery: FGS is alive (controller=${state::class.simpleName}); " +
                    "user returned to a live walk, NOT finalizing",
            )
            return
        }
        Log.i(
            TAG,
            "warm-launch recovery: controller=${state::class.simpleName}, FGS gone, finalizing",
        )
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
    }
}
