// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.content.Intent
import android.os.Bundle
import android.util.Log
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.honor.WayError

/**
 * Where the app is, as the link routing reads it. The nav host reports
 * each change of its back stack.
 */
data class HonorLinkScreen(
    /** In front of the first arrival at the Path tab: the welcome, the permissions, or the breath. */
    val inSetup: Boolean,
    val atPath: Boolean,
    /** The walk screen from the moment it opens, before Start included, with anything over it. */
    val walkScreenUp: Boolean,
    /** The Ways sheet, with its "Walk again" picker over it or not. */
    val waysSheetUp: Boolean,
    /** A summary from any of its hosts: a fetched Way waits until it closes (owner decision 5). */
    val summaryUp: Boolean,
    val overviewUp: Boolean,
)

/** What the link toast says (S2 §7.1). The overview and the Ways sheet say the rest inline. */
sealed interface HonorLinkToast {
    data object FinishWalkFirst : HonorLinkToast

    data object Reaching : HonorLinkToast

    data class Failed(val error: WayError) : HonorLinkToast
}

/**
 * Honor links in the UI process: iOS's `PilgrimApp.route` stash, the
 * `MainTabView` drain and handler, and `MainCoordinator.openWay`'s
 * refusal and toast (`PilgrimApp.swift:54-64`, `MainTabView.swift:86-106`,
 * `MainCoordinatorView.swift:209-242,340-349@7c200bf`; shared-walk spec
 * S2 §2–§7). The fetch itself is [HonorImportCoordinator]'s, and where a
 * fetched Way lands is the nav host's.
 *
 * - A link waits in memory, the last one winning, until a nav host
 *   first stands past setup (correction 4). This singleton outlives an
 *   Activity's recreation and dies with the process, as iOS's static
 *   does. Nothing shows while it waits.
 * - The install referrer's id routes like a held tap, unless the walker
 *   tapped a link in this process: the tap wins.
 * - With the walk screen up, or `:tracker` walking with no walk screen
 *   yet (a cold start), the link is answered "finish this walk first"
 *   and dropped (correction 3).
 * - Otherwise the tab switches to Path, unless a summary, the Ways sheet,
 *   or an overview is up (they keep their place: the summary parks the
 *   Way, the sheet takes it, the overview is replaced by it); the toast
 *   says "reaching for the walk…" unless the Ways sheet is up; and the
 *   import starts (correction 2).
 * - The walk screen opening cancels an import in flight, silently.
 *
 * Main thread only, as iOS's `@MainActor` coordinator is.
 */
@Singleton
class HonorLinkRouter internal constructor(
    private val honorEnabled: Boolean,
    private val imports: HonorImportCoordinator,
    private val trackerWalking: suspend () -> Boolean,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(releaseFlags: ReleaseFlags, imports: HonorImportCoordinator, walks: Provider<WalkRepository>) : this(
        honorEnabled = releaseFlags.honor,
        imports = imports,
        trackerWalking = { hasActiveWalk(walks.get()) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val _toast = MutableStateFlow<HonorLinkToast?>(null)

    /** One toast at a time, each for [TOAST_MILLIS], replaced or cleared by the next. */
    val toast: StateFlow<HonorLinkToast?> = _toast.asStateFlow()

    private val _pathSwitch = MutableStateFlow(false)

    /** A switch to the Path tab the nav host owes, until it reports [pathSwitchTaken]. */
    val pathSwitch: StateFlow<Boolean> = _pathSwitch.asStateFlow()

    /**
     * Each live nav host's back stack, the last reported last: that one is
     * the screen showing. Another Activity's host going leaves the others'.
     */
    private val screens = LinkedHashMap<Any, HonorLinkScreen>()
    private val screen: HonorLinkScreen? get() = screens.values.lastOrNull()

    @Volatile private var anyWalkScreenUp = false

    /**
     * Whether any live nav host shows the walk screen, pre-Start included:
     * iOS's `isWalkActive` for the pilgrimage package guard (pilgrimage-stage
     * spec P2 §2), which reads it off Main. Always false with the release
     * flag off, when no screen is reported.
     */
    val walkScreenUp: Boolean get() = anyWalkScreenUp

    private var held: HeldLink? = null
    private var linkTapped = false
    private var routing: Job? = null
    private var toastExpiry: Job? = null

    init {
        if (honorEnabled) scope.launch { imports.outcomes.collect(::answer) }
    }

    /** A launch's intent, or a new one; [restored] for an Activity rebuilt from saved state. */
    fun open(intent: Intent?, restored: Boolean) {
        val shareId = honorLinkOf(intent, restored) ?: return
        if (!honorEnabled) return
        linkTapped = true
        route(shareId)
    }

    /**
     * The install referrer's id, routed as a tap held through setup would
     * be. A link the walker tapped in this process wins: the referrer is
     * then [released] at once, unrouted (R19's "once" either way).
     */
    fun routeReferrer(shareId: String, released: () -> Unit) {
        if (!honorEnabled) return
        if (linkTapped) {
            released()
            return
        }
        route(shareId, released)
    }

    /**
     * iOS `route`: [shareId] waits for setup if it has to, then routes.
     * [released] runs once the id stops waiting, routed or replaced by a
     * newer link.
     */
    fun route(shareId: String, released: () -> Unit = {}) {
        if (!honorEnabled) return
        routing?.cancel()
        held?.released?.invoke()
        held = HeldLink(shareId, released)
        drainIfReady()
    }

    /** The nav host's back stack, from [owner]: one nav host per Activity. */
    fun screenChanged(now: HonorLinkScreen, owner: Any) {
        if (!honorEnabled) return
        val before = screens.remove(owner)
        screens[owner] = now
        anyWalkScreenUp = screens.values.any { it.walkScreenUp }
        // iOS `startWalk`: the observation goes, the transfers stay, the state is left as it was.
        if (now.walkScreenUp && before?.walkScreenUp != true) imports.cancelImport()
        // iOS `chooseWay`: the sheet opens on no toast; its own model resets the import line.
        if (now.waysSheetUp && before?.waysSheetUp != true) showToast(null)
        drainIfReady()
    }

    /**
     * [owner]'s nav host left with its Activity. The host reported before
     * it, if one still lives, is the screen again; with none, links wait
     * until the next one stands past setup.
     */
    fun screenGone(owner: Any) {
        if (screens.remove(owner) == null) return
        anyWalkScreenUp = screens.values.any { it.walkScreenUp }
        drainIfReady()
    }

    fun pathSwitchTaken() {
        _pathSwitch.value = false
    }

    private fun drainIfReady() {
        val now = screen ?: return
        if (now.inSetup) return
        val link = held ?: return
        held = null
        link.released()
        dispatch(link.shareId)
    }

    private fun dispatch(shareId: String) {
        routing = scope.launch {
            val walking = screen?.walkScreenUp == true || trackerWalking()
            val now = screen
            if (now == null) {
                held = HeldLink(shareId) {}
                return@launch
            }
            if (walking || now.walkScreenUp) {
                showToast(HonorLinkToast.FinishWalkFirst)
                return@launch
            }
            if (!now.atPath && !now.keepsItsPlace()) _pathSwitch.value = true
            if (!now.waysSheetUp) showToast(HonorLinkToast.Reaching)
            imports.openWay(shareId)
        }
    }

    /** iOS `openWay`'s two answers: a listed Way clears the toast; a failure shows unless the sheet says it inline. */
    private fun answer(outcome: HonorImportOutcome) {
        val sheetUp = screen?.waysSheetUp == true
        showToast(
            when (outcome) {
                HonorImportOutcome.Listed -> null
                is HonorImportOutcome.Failed -> HonorLinkToast.Failed(outcome.error).takeUnless { sheetUp }
            },
        )
    }

    /** iOS `showLinkToast`: each text cancels the pending expiry and starts its own; null clears at once. */
    private fun showToast(toast: HonorLinkToast?) {
        toastExpiry?.cancel()
        _toast.value = toast
        if (toast == null) return
        toastExpiry = scope.launch {
            delay(TOAST_MILLIS)
            _toast.value = null
        }
    }

    private fun HonorLinkScreen.keepsItsPlace(): Boolean = summaryUp || waysSheetUp || overviewUp

    private class HeldLink(val shareId: String, val released: () -> Unit)

    companion object {
        const val TOAST_MILLIS = 5_000L
        private const val TAG = "HonorLinkRouter"

        /**
         * `:tracker` walking with no walk screen yet: a walk row still
         * open, the launch's recovery having finished any a dead tracker
         * left. A failed read routes the link; the walk screen, once it
         * opens, cancels the import.
         */
        private suspend fun hasActiveWalk(walks: WalkRepository): Boolean = try {
            walks.getActiveWalk() != null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.w(TAG, "the active-walk probe failed; routing the link as if no walk runs", e)
            false
        }
    }
}

/**
 * The share id a launch or a new intent carries: a VIEW intent's data,
 * parsed as iOS parses a link the OS hands it. A task the system rebuilds
 * carries none, so R18 loses a waiting link with the process, as iOS does
 * (S2 open question 3, resolved): an Activity rebuilt from saved state,
 * whose intent is the task's original one, replayed after a process death
 * the app's own [Intent] edits didn't survive; and a task Recents starts
 * again from its first intent after a reboot or a force stop, with no
 * saved state. A configuration change rebuilds from saved state too, but
 * its intent was already consumed.
 */
internal fun honorLinkOf(intent: Intent?, restored: Boolean): String? {
    if (restored || intent == null || !carriesLinkData(intent)) return null
    if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
    return intent.data?.let(HonorLink::parse)
}

/**
 * Whether [intent] carries link data: a VIEW intent's, which routes only as
 * a link, never through the widget's extras. The widget's own clicks carry
 * data too, Glance's `glance-action:` id for each click, but no action.
 */
internal fun carriesLinkData(intent: Intent?): Boolean = intent?.action == Intent.ACTION_VIEW && intent.data != null

/** [intent] with its link data and every extra gone, so a recreated Activity routes neither. */
internal fun linkConsumed(intent: Intent): Intent = Intent(intent).apply {
    data = null
    replaceExtras(Bundle())
}
