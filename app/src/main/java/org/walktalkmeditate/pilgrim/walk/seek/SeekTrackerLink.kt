// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.seek

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.seek.SeekDao
import org.walktalkmeditate.pilgrim.data.seek.SeekLiveSessionKey
import org.walktalkmeditate.pilgrim.data.seek.SeekPreferencesRepository
import org.walktalkmeditate.pilgrim.data.seek.SeekSessionEntity
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.walk.WalkActionPublisher

/**
 * How the UI process's [SeekOrchestrator] reaches a seek walk's session in
 * `:tracker` (plan U25): commands go by intent, and the session comes back
 * through Room, the tracker's only way to the UI.
 */
interface SeekTrackerLink {

    val placement: SeekPlacement

    /** The walker's sonar settings now, for Begin's hand-off. */
    fun sonarSettings(): SeekSonarSettings

    /** The walk's seek session row, as `:tracker` last wrote it. */
    fun session(walkId: Long): Flow<SeekSessionEntity?>

    fun seekAnew()

    /** A session staged after its walk started, for `:tracker` to attach. */
    fun handOffLate(start: SeekStart)

    companion object {
        /** The release flag off: Seek stays in the UI process and nothing crosses to `:tracker`. */
        val UiProcess: SeekTrackerLink = object : SeekTrackerLink {
            override val placement = SeekPlacement.UI_PROCESS
            override fun sonarSettings() = SeekSonarSettings(sonarEnabled = false, sonarVolume = 0f, soundsEnabled = false)
            override fun session(walkId: Long): Flow<SeekSessionEntity?> = flowOf(null)
            override fun seekAnew() = Unit
            override fun handOffLate(start: SeekStart) = Unit
        }
    }
}

/** The production link: the release flag picks the placement, Room carries the session back. */
class RoomSeekTrackerLink @Inject constructor(
    releaseFlags: ReleaseFlags,
    private val seekDao: SeekDao,
    private val walkActionPublisher: WalkActionPublisher,
    private val seekPreferences: SeekPreferencesRepository,
    private val soundsPreferences: SoundsPreferencesRepository,
) : SeekTrackerLink {

    override val placement: SeekPlacement = SeekPlacement.of(releaseFlags.honor)

    override fun sonarSettings() = SeekSonarSettings(
        sonarEnabled = seekPreferences.sonarEnabled.value,
        sonarVolume = seekPreferences.sonarVolume.value,
        soundsEnabled = soundsPreferences.soundsEnabled.value,
    )

    override fun session(walkId: Long): Flow<SeekSessionEntity?> = seekDao.observeSession(walkId)

    override fun seekAnew() = walkActionPublisher.sendSeekAnew()

    override fun handOffLate(start: SeekStart) = walkActionPublisher.handOffSeekSession(start)
}

/**
 * Sends the walker's sonar settings to a seek walk's session in `:tracker`
 * (plan U25), which can't read the UI's preferences: whenever they change
 * while a seek session is live, and again whenever its gate generation
 * moves, which `:tracker` bumps at each session start and revival. The
 * session keeps what it applies in its row, so a revival with no UI alive
 * still plays by the last settings it heard. With the release flag off
 * nothing is ever sent.
 */
@Singleton
class SeekPreferencesPublisher internal constructor(
    private val releaseFlags: ReleaseFlags,
    private val sessions: Flow<SeekLiveSessionKey?>,
    private val settings: Flow<SeekSonarSettings>,
    private val send: (SeekSonarSettings) -> Unit,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        releaseFlags: ReleaseFlags,
        seekDao: SeekDao,
        seekPreferences: SeekPreferencesRepository,
        soundsPreferences: SoundsPreferencesRepository,
        walkActionPublisher: WalkActionPublisher,
    ) : this(
        releaseFlags = releaseFlags,
        sessions = seekDao.observeLiveSessionKey(),
        settings = combine(
            seekPreferences.sonarEnabled,
            seekPreferences.sonarVolume,
            soundsPreferences.soundsEnabled,
            ::SeekSonarSettings,
        ),
        send = walkActionPublisher::publishSeekPreferences,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private val started = AtomicBoolean(false)

    /** Once per UI process; a no-op with the release flag off. */
    fun start() {
        if (SeekPlacement.of(releaseFlags.honor) != SeekPlacement.TRACKER) return
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(sessions.distinctUntilChanged(), settings.distinctUntilChanged(), ::Pair)
                .distinctUntilChanged()
                .collect { (session, current) -> if (session != null) send(current) }
        }
    }
}
