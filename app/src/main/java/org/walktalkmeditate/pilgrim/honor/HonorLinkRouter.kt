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
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError

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
    /** A route page a stage link opened: a second link swaps it in place, as iOS swaps its route sheet. */
    val routeSheetUp: Boolean,
)

/** What the link toast says (S2 §7.1; stage-links spec, Copy). The overview and the Ways sheet say the rest inline. */
sealed interface HonorLinkToast {
    data object FinishWalkFirst : HonorLinkToast

    data object Reaching : HonorLinkToast

    data class Failed(val error: WayError) : HonorLinkToast

    /** "reaching for the route…", before each catalog load a stage link makes. */
    data object ReachingForRoute : HonorLinkToast

    /** "this route isn't in Pilgrim yet": the catalog, forced once, still lacks the route. */
    data object RouteNotInPilgrim : HonorLinkToast

    /** "this link needs a newer Pilgrim": an honor-host path this build can't read. */
    data object NeedsNewerPilgrim : HonorLinkToast

    /** The catalog load threw: its pilgrimage line. */
    data class CatalogFailed(val error: PilgrimageError) : HonorLinkToast
}

/**
 * Honor links in the UI process: iOS's `PilgrimApp.route` stash, the
 * `MainTabView` drain and handler, and `MainCoordinator.open`'s refusal
 * and toast (`PilgrimApp.swift:35,47-64`, `MainTabView.swift:93-107,172-175`,
 * `MainCoordinatorView.swift:227-242,274-275@3b78728`; shared-walk spec
 * S2 §2–§7). The fetch and the stage resolution are
 * [HonorImportCoordinator]'s, and where their results land is the nav host's.
 *
 * - A link waits in memory, the last one winning, until a nav host
 *   first stands past setup (correction 4). This singleton outlives an
 *   Activity's recreation and dies with the process, as iOS's static
 *   does. Nothing shows while it waits.
 * - The install referrer's id routes like a held tap, unless the walker
 *   tapped a link in this process: the tap wins.
 * - An unrecognised link only says "this link needs a newer Pilgrim",
 *   mid-walk too; it switches no tab and cancels nothing.
 * - With the walk screen up, or `:tracker` walking with no walk screen
 *   yet (a cold start), a share or stage link is answered "finish this
 *   walk first" and dropped (correction 3).
 * - Otherwise the tab switches to Path, unless a summary, the Ways sheet,
 *   an overview, or a link's route page is up (they keep their place: the
 *   summary parks what comes, the sheet takes a Way, the overview and the
 *   page are replaced by it). A share says "reaching for the walk…" unless
 *   the Ways sheet is up and starts its import; a stage starts its
 *   resolution, which says its own lines (correction 2).
 * - The walk screen opening cancels either in flight, silently.
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
        val link = honorLinkOf(intent, restored) ?: return
        if (!honorEnabled) return
        linkTapped = true
        route(link)
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
        route(HonorLinkDestination.Share(shareId), released)
    }

    /**
     * iOS `route`: [link] waits for setup if it has to, then routes.
     * [released] runs once it stops waiting, routed or replaced by a newer
     * link. An unrecognised link leaves a share's walk probe running, as
     * iOS's `open(.unrecognised)` leaves the import.
     */
    fun route(link: HonorLinkDestination, released: () -> Unit = {}) {
        if (!honorEnabled) return
        if (link != HonorLinkDestination.Unrecognised) routing?.cancel()
        held?.released?.invoke()
        held = HeldLink(link, released)
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
        dispatch(link.destination)
    }

    private fun dispatch(link: HonorLinkDestination) {
        if (link == HonorLinkDestination.Unrecognised) {
            showToast(HonorLinkToast.NeedsNewerPilgrim)
            return
        }
        routing = scope.launch {
            val walking = screen?.walkScreenUp == true || trackerWalking()
            val now = screen
            if (now == null) {
                held = HeldLink(link) {}
                return@launch
            }
            if (walking || now.walkScreenUp) {
                showToast(HonorLinkToast.FinishWalkFirst)
                return@launch
            }
            if (!now.atPath && !now.keepsItsPlace()) _pathSwitch.value = true
            when (link) {
                is HonorLinkDestination.Share -> {
                    if (!now.waysSheetUp) showToast(HonorLinkToast.Reaching)
                    imports.openWay(link.id)
                }
                is HonorLinkDestination.Stage -> imports.openStage(link.routeId, link.index)
                HonorLinkDestination.Unrecognised -> Unit
            }
        }
    }

    private fun answer(outcome: HonorImportOutcome) {
        showToast(linkToastFor(outcome, waysSheetUp = screen?.waysSheetUp == true))
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

    private fun HonorLinkScreen.keepsItsPlace(): Boolean = summaryUp || waysSheetUp || overviewUp || routeSheetUp

    private class HeldLink(val destination: HonorLinkDestination, val released: () -> Unit)

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
 * The destination a launch or a new intent carries: a VIEW intent's data,
 * parsed as iOS parses a link the OS hands it. A task the system rebuilds
 * carries none, so R18 loses a waiting link with the process, as iOS does
 * (S2 open question 3, resolved): an Activity rebuilt from saved state,
 * whose intent is the task's original one, replayed after a process death
 * the app's own [Intent] edits didn't survive; and a task Recents starts
 * again from its first intent after a reboot or a force stop, with no
 * saved state. A configuration change rebuilds from saved state too, but
 * its intent was already consumed.
 */
internal fun honorLinkOf(intent: Intent?, restored: Boolean): HonorLinkDestination? {
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

/**
 * iOS's `showLinkToast` for each way an import or a stage link ends
 * (`MainCoordinatorView.swift:250-266,296-328@3b78728`): a listed Way or a
 * landed route clears the toast; a share's failure shows unless the Ways
 * sheet says it inline; a stage link's lines show wherever the walker is,
 * the sheet included.
 */
internal fun linkToastFor(outcome: HonorImportOutcome, waysSheetUp: Boolean): HonorLinkToast? = when (outcome) {
    HonorImportOutcome.Listed -> null
    is HonorImportOutcome.Failed -> HonorLinkToast.Failed(outcome.error).takeUnless { waysSheetUp }
    HonorImportOutcome.ReachingForRoute -> HonorLinkToast.ReachingForRoute
    HonorImportOutcome.RouteNotInPilgrim -> HonorLinkToast.RouteNotInPilgrim
    is HonorImportOutcome.CatalogFailed -> HonorLinkToast.CatalogFailed(outcome.error)
}
