// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.permissions.PermissionsRepository

/**
 * What the install referrer left: whether it was read, the share id still
 * waiting for setup, if any, and whether a first read began during setup
 * and is still unanswered. Kept on disk, so the wait outlives a process
 * death during onboarding (R19).
 */
data class InstallReferrerRecord(
    val consumed: Boolean,
    val pendingShareId: String?,
    val firstReadInSetup: Boolean = false,
)

interface InstallReferrerStore {
    suspend fun read(): InstallReferrerRecord

    /** A first read begins during setup: a later launch finishes it, set up or not. */
    suspend fun markFirstReadInSetup()

    /** Marks the referrer read, with the share id it named waiting for setup, in one write. */
    suspend fun consume(pendingShareId: String?)

    /** The waiting share id has gone to the link routing. */
    suspend fun clearPending()
}

/**
 * The honor page's Play link, opened on a fresh install: R19, an Android
 * addition with no iOS counterpart (shared-walk spec S2 §10). Its outcome
 * mirrors a tapped link held through setup, which is what a fresh iOS
 * install from the honor page reaches with a second tap: once setup
 * finishes, the Path tab, "reaching for the walk…", then the overview or
 * the failure toast. Never an automatic Begin, and only once.
 *
 * - First read on a launch while setup is incomplete, and never with the
 *   release flag off. An install already set up at its first read (an
 *   updater) marks it read and opens nothing.
 * - The referrer is decoded once, then its `honor` value must be a whole
 *   share id ([HonorLink.shareId]); anything else opens nothing.
 * - "Read" and the waiting id are saved before the id is routed, and the
 *   id stays on disk until the routing takes it, so a process death in
 *   system Settings during onboarding still opens it after setup.
 * - Disconnected and unavailable are retried with a fresh client and a
 *   bounded backoff. A launch that never gets an answer leaves it unread,
 *   and the next launch reads again, even once setup is done: the first
 *   read began during setup, so this is no updater (AE5).
 * - A link the walker tapped in this process wins over the referrer,
 *   which is then marked read and opens nothing.
 */
@Singleton
class InstallReferrerHandoff internal constructor(
    private val honorEnabled: Boolean,
    private val client: InstallReferrerClientAdapter,
    private val store: InstallReferrerStore,
    private val setupComplete: suspend () -> Boolean,
    private val route: (shareId: String, released: () -> Unit) -> Unit,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        releaseFlags: ReleaseFlags,
        client: InstallReferrerClientAdapter,
        store: InstallReferrerStore,
        permissions: PermissionsRepository,
        router: HonorLinkRouter,
    ) : this(
        honorEnabled = releaseFlags.honor,
        client = client,
        store = store,
        setupComplete = { permissions.onboardingComplete.first() },
        route = router::routeReferrer,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val started = AtomicBoolean(false)

    /**
     * From an Activity: once per process, or again from a later Activity
     * when the Play Store never answered. A widget's or a worker's process
     * start never reads it.
     */
    fun start() {
        if (!honorEnabled || !started.compareAndSet(false, true)) return
        launchLogged("the install referrer handoff failed") {
            if (!handOff()) started.set(false)
        }
    }

    /** False when the Play Store never answered, so the referrer is still to read. */
    internal suspend fun handOff(): Boolean {
        val record = store.read()
        record.pendingShareId?.let {
            hand(it)
            return true
        }
        if (record.consumed) return true
        if (!record.firstReadInSetup) {
            if (setupComplete()) {
                store.consume(pendingShareId = null)
                return true
            }
            store.markFirstReadInSetup()
        }
        val answer = answered() ?: return false
        val shareId = answer.referrer?.let(::shareIdFromReferrer)
        store.consume(pendingShareId = shareId)
        shareId?.let(::hand)
        return true
    }

    private fun hand(shareId: String) {
        route(shareId) { launchLogged("clearing the install referrer's waiting id failed") { store.clearPending() } }
    }

    /** DataStore throws on a broken file: logged, and the referrer is left for the next launch. */
    private fun launchLogged(failure: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                Log.w(TAG, failure, e)
            }
        }
    }

    private suspend fun answered(): InstallReferrerRead.Answered? {
        for (attempt in 0..RETRY_BACKOFF_MILLIS.size) {
            val read = withTimeoutOrNull(ATTEMPT_TIMEOUT_MILLIS) { client.read() } ?: InstallReferrerRead.Retry
            if (read is InstallReferrerRead.Answered) return read
            RETRY_BACKOFF_MILLIS.getOrNull(attempt)?.let { delay(it) }
        }
        Log.i(TAG, "the Play Store never answered; the next launch reads again")
        return null
    }

    internal companion object {
        const val TAG = "InstallReferrer"

        /** Three retries after the first attempt: about seven seconds of waiting, all told. */
        val RETRY_BACKOFF_MILLIS = listOf(1_000L, 2_000L, 4_000L)

        /** A Play Store that binds and never answers counts as unavailable. */
        const val ATTEMPT_TIMEOUT_MILLIS = 10_000L
    }
}

private const val REFERRER_HONOR_KEY = "honor"

/**
 * The share id an honor page's Play link carried. The worker writes
 * `referrer=encodeURIComponent("honor=<id>")` (`honor-constants.ts:7-9@2a4f5d0`),
 * and the Play Store hands back the parameter's value. It is decoded once,
 * whether or not the store decoded it already, so `honor%3D<id>` and
 * `honor=<id>` both read, and a doubly encoded one doesn't. Exactly one
 * `honor` value, and it must be a whole share id: no trimming, so an
 * encoded newline fails as it does in a tapped link.
 */
internal fun shareIdFromReferrer(referrer: String): String? {
    val decoded = try {
        URLDecoder.decode(referrer.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        return null
    }
    val honor = decoded.split('&')
        .map { it.split('=', limit = 2) }
        .filter { it.size == 2 && it[0] == REFERRER_HONOR_KEY }
        .map { it[1] }
    return honor.singleOrNull()?.let(HonorLink::shareId)
}

/** The referrer's record in the app's DataStore, beside the onboarding flags it is read against. */
class DataStoreInstallReferrerStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : InstallReferrerStore {

    override suspend fun read(): InstallReferrerRecord {
        val prefs = dataStore.data.first()
        return InstallReferrerRecord(
            consumed = prefs[KEY_CONSUMED] ?: false,
            pendingShareId = prefs[KEY_PENDING_SHARE_ID],
            firstReadInSetup = prefs[KEY_FIRST_READ_IN_SETUP] ?: false,
        )
    }

    override suspend fun markFirstReadInSetup() {
        dataStore.edit { it[KEY_FIRST_READ_IN_SETUP] = true }
    }

    override suspend fun consume(pendingShareId: String?) {
        dataStore.edit { prefs ->
            prefs[KEY_CONSUMED] = true
            prefs.remove(KEY_FIRST_READ_IN_SETUP)
            if (pendingShareId == null) prefs.remove(KEY_PENDING_SHARE_ID) else prefs[KEY_PENDING_SHARE_ID] = pendingShareId
        }
    }

    override suspend fun clearPending() {
        dataStore.edit { it.remove(KEY_PENDING_SHARE_ID) }
    }

    private companion object {
        val KEY_CONSUMED = booleanPreferencesKey("honor_install_referrer_consumed")
        val KEY_PENDING_SHARE_ID = stringPreferencesKey("honor_install_referrer_pending_share_id")
        val KEY_FIRST_READ_IN_SETUP = booleanPreferencesKey("honor_install_referrer_first_read_in_setup")
    }
}
