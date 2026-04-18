# Stage 2-E Implementation Plan — Walk Summary voice-recording surface + orphan-WAV sweeper

**Spec:** [2026-04-17-stage-2e-summary-voice-list-and-sweeper-design.md](../specs/2026-04-17-stage-2e-summary-voice-list-and-sweeper-design.md)

**Test command:**
```bash
JAVA_HOME=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8 ANDROID_HOME=/Users/rubberduck/Library/Android/sdk PATH=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8/bin:$PATH ./gradlew assembleDebug lintDebug testDebugUnitTest
```

Tasks 1 → 15. Run full CI gate after each Kotlin-touching task that compiles.

---

## Task 1 — Add media3-exoplayer dependency

**File:** `gradle/libs.versions.toml`

```toml
[versions]
media3 = "1.5.1"

[libraries]
androidx-media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
```

**File:** `app/build.gradle.kts` (dependencies block)

```kotlin
implementation(libs.androidx.media3.exoplayer)
```

Verify: `./gradlew assembleDebug` succeeds.

---

## Task 2 — VoicePlaybackController interface + types

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/VoicePlaybackController.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import kotlinx.coroutines.flow.StateFlow
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

interface VoicePlaybackController {
    val state: StateFlow<PlaybackState>
    fun play(recording: VoiceRecording)
    fun pause()
    fun stop()
    fun release()
}

sealed class PlaybackState {
    data object Idle : PlaybackState()
    data class Playing(val recordingId: Long) : PlaybackState()
    data class Paused(val recordingId: Long) : PlaybackState()
    data class Error(val recordingId: Long, val message: String) : PlaybackState()
}
```

---

## Task 3 — ExoPlayerVoicePlaybackController production impl

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/ExoPlayerVoicePlaybackController.kt` (new)

Lazy-create ExoPlayer on first `play()`. Listen to `Player.Listener.onPlaybackStateChanged` to transition Playing→Idle on STATE_ENDED. Disable ExoPlayer's built-in audio focus (use AudioFocusCoordinator). MainLooper required for ExoPlayer construction & calls — wrap in `withContext(Dispatchers.Main)` if invoked off-main, but for our @Singleton we'll only call from the VM (Main thread already).

Sketch:
```kotlin
@Singleton
class ExoPlayerVoicePlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioFocus: AudioFocusCoordinator,
) : VoicePlaybackController {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var currentRecordingId: Long? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                audioFocus.abandon()
                currentRecordingId = null
                _state.value = PlaybackState.Idle
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            val id = currentRecordingId ?: return
            audioFocus.abandon()
            _state.value = PlaybackState.Error(id, error.message ?: "playback failed")
        }
    }

    override fun play(recording: VoiceRecording) {
        mainHandler.post {
            val granted = audioFocus.requestTransient()
            if (!granted) {
                _state.value = PlaybackState.Error(recording.id, "audio focus denied")
                return@post
            }
            val p = player ?: createPlayer().also { player = it }
            currentRecordingId = recording.id
            val absolutePath = context.filesDir.toPath().resolve(recording.fileRelativePath)
            p.setMediaItem(MediaItem.fromUri(absolutePath.toUri()))
            p.prepare()
            p.play()
            _state.value = PlaybackState.Playing(recording.id)
        }
    }

    override fun pause() {
        mainHandler.post {
            val p = player ?: return@post
            val id = currentRecordingId ?: return@post
            p.pause()
            _state.value = PlaybackState.Paused(id)
        }
    }

    override fun stop() {
        mainHandler.post {
            player?.stop()
            audioFocus.abandon()
            currentRecordingId = null
            _state.value = PlaybackState.Idle
        }
    }

    override fun release() {
        mainHandler.post {
            player?.release()
            player = null
            audioFocus.abandon()
            currentRecordingId = null
            _state.value = PlaybackState.Idle
        }
    }

    private fun createPlayer(): ExoPlayer {
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_VOICE_COMMUNICATION)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        return ExoPlayer.Builder(context)
            .setAudioAttributes(attrs, /* handleAudioFocus = */ false)
            .build()
            .also { it.addListener(listener) }
    }
}
```

(Note: `setAudioAttributes` accepts `media3.common.AudioAttributes` not `android.media`; pull from `androidx.media3.common.C`.)

---

## Task 4 — OrphanRecordingSweeper

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/OrphanRecordingSweeper.kt` (new)

```kotlin
@Singleton
class OrphanRecordingSweeper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WalkRepository,
    private val transcriptionScheduler: TranscriptionScheduler,
) {
    suspend fun sweep(walkId: Long): SweepResult { ... }
    suspend fun sweepAll(): SweepResult { ... }
}

data class SweepResult(
    val orphanFilesDeleted: Int = 0,
    val orphanRowsDeleted: Int = 0,
    val zombieRowsDeleted: Int = 0,
    val rescheduledWalks: Int = 0,
)
```

Implementation steps for `sweep(walkId)`:
1. Read `walk = repository.getWalk(walkId) ?: return SweepResult()`.
2. Read `rows = repository.voiceRecordingsFor(walkId)`.
3. Compute `walkDir = filesDir/recordings/<walk.uuid>/`.
4. List files in walkDir (if exists) → `diskFiles`.
5. Compute `rowFilenames = rows.map { Path.of(it.fileRelativePath).fileName.toString() }`.
6. **Case (a):** for each file in diskFiles whose name not in rowFilenames → safeDelete (path guard).
7. **Case (b):** for each row whose absolute path doesn't exist on disk → `repository.deleteVoiceRecording(row)`.
8. **Case (c):** for each row with `transcription == null`, read 4 bytes at WAV offset 40 → if 0 → delete row + delete file.
9. **Case (d):** if any row passes (transcription==null, dataSize>0, not (c)) → `transcriptionScheduler.scheduleForWalk(walkId)` (called once, not per row); add 1 to `rescheduledWalks`.

`sweepAll()` iterates all walks via a new `repository.allWalks()` query (or use existing observe-all + first()).

Path-guard helper:
```kotlin
private fun safeDeleteOrphanFile(file: Path): Boolean {
    val recordingsRoot = context.filesDir.toPath().resolve("recordings").toAbsolutePath().normalize()
    val candidate = file.toAbsolutePath().normalize()
    if (!candidate.startsWith(recordingsRoot)) return false
    if (candidate.fileName.toString().lowercase().substringAfterLast('.') != "wav") return false
    if (!Files.isRegularFile(candidate)) return false
    Files.delete(candidate)
    return true
}
```

WAV dataSize read:
```kotlin
private fun wavDataSizeOrNull(file: Path): Long? = try {
    Files.newByteChannel(file).use { ch ->
        ch.position(40)
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        if (ch.read(buf) < 4) return null
        buf.rewind()
        buf.int.toLong() and 0xFFFFFFFFL
    }
} catch (_: Throwable) { null }
```

---

## Task 5 — OrphanSweeperWorker (HiltWorker)

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/OrphanSweeperWorker.kt` (new)

```kotlin
@HiltWorker
class OrphanSweeperWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sweeper: OrphanRecordingSweeper,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        runCatching { sweeper.sweepAll() }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
}
```

---

## Task 6 — OrphanSweeperScheduler interface + WorkManager impl

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/OrphanSweeperScheduler.kt` (new)

```kotlin
interface OrphanSweeperScheduler { fun scheduleDaily() }

@Singleton
class WorkManagerOrphanSweeperScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : OrphanSweeperScheduler {
    override fun scheduleDaily() {
        val request = PeriodicWorkRequestBuilder<OrphanSweeperWorker>(
            repeatInterval = 1, repeatIntervalTimeUnit = TimeUnit.DAYS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "orphan-recording-sweeper",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
```

---

## Task 7 — PlaybackModule DI

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/di/PlaybackModule.kt` (new)

```kotlin
@Module @InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    @Binds @Singleton
    abstract fun bindVoicePlaybackController(impl: ExoPlayerVoicePlaybackController): VoicePlaybackController

    @Binds @Singleton
    abstract fun bindOrphanSweeperScheduler(impl: WorkManagerOrphanSweeperScheduler): OrphanSweeperScheduler
}
```

---

## Task 8 — PilgrimApp wires sweeper scheduler

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApp.kt` (modify)

Add field-injected scheduler; call `scheduleDaily()` from onCreate after super.

```kotlin
@Inject lateinit var orphanSweeperScheduler: OrphanSweeperScheduler

override fun onCreate() {
    super.onCreate()
    MapboxOptions.accessToken = BuildConfig.MAPBOX_ACCESS_TOKEN
    orphanSweeperScheduler.scheduleDaily()
}
```

---

## Task 9 — WalkRepository.allWalks() helper (if missing)

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt` (modify)

Check if `allWalks()` or equivalent exists. If not, add:
```kotlin
suspend fun allWalks(): List<Walk> = walkDao.getAll()
```
And add the DAO method if needed.

---

## Task 10 — WalkSummaryViewModel extension

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt` (modify)

Add constructor params: `playback: VoicePlaybackController`, `sweeper: OrphanRecordingSweeper`.

Add:
```kotlin
val recordings: StateFlow<List<VoiceRecording>> = repository.observeVoiceRecordings(walkId)
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

val playbackUiState: StateFlow<PlaybackUiState> = playback.state
    .map { it.toUi() }
    .stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackUiState.IDLE)

fun playRecording(recording: VoiceRecording) = playback.play(recording)
fun pausePlayback() = playback.pause()
fun stopPlayback() = playback.stop()

init {
    viewModelScope.launch { sweeper.sweep(walkId) }
}

override fun onCleared() {
    playback.release()
    super.onCleared()
}
```

PlaybackUiState DTO:
```kotlin
data class PlaybackUiState(
    val playingRecordingId: Long?,
    val isPlaying: Boolean,
    val errorMessage: String?,
) {
    companion object { val IDLE = PlaybackUiState(null, false, null) }
}

private fun PlaybackState.toUi(): PlaybackUiState = when (this) {
    is PlaybackState.Idle -> PlaybackUiState.IDLE
    is PlaybackState.Playing -> PlaybackUiState(recordingId, true, null)
    is PlaybackState.Paused -> PlaybackUiState(recordingId, false, null)
    is PlaybackState.Error -> PlaybackUiState(recordingId, false, message)
}
```

---

## Task 11 — VoiceRecordingRow + VoiceRecordingsSection composables

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/VoiceRecordingsSection.kt` (new)

```kotlin
@Composable
fun VoiceRecordingsSection(
    walkStartTimestamp: Long,
    recordings: List<VoiceRecording>,
    playbackUiState: PlaybackUiState,
    onPlay: (VoiceRecording) -> Unit,
    onPause: () -> Unit,
) {
    if (recordings.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal)) {
        Text(
            text = stringResource(R.string.summary_recordings_header),
            style = pilgrimType.body,
            color = pilgrimColors.fog,
        )
        recordings.forEach { recording ->
            VoiceRecordingRow(
                recording = recording,
                walkStartTimestamp = walkStartTimestamp,
                isPlaying = playbackUiState.playingRecordingId == recording.id && playbackUiState.isPlaying,
                onPlay = { onPlay(recording) },
                onPause = onPause,
            )
        }
    }
}

@Composable
private fun VoiceRecordingRow(
    recording: VoiceRecording,
    walkStartTimestamp: Long,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
) {
    Card(...) {
        Row {
            Column(weight=1f) {
                Text("+${WalkFormat.duration(recording.startTimestamp - walkStartTimestamp)} · ${WalkFormat.duration(recording.durationMillis)}")
                TranscriptionDisplay(recording)
                if (recording.wordsPerMinute != null) {
                    Text("${recording.wordsPerMinute.toInt()} wpm", style = caption)
                }
            }
            IconButton(onClick = if (isPlaying) onPause else onPlay) {
                Icon(if (isPlaying) Pause else PlayArrow, ...)
            }
        }
    }
}

@Composable
private fun TranscriptionDisplay(recording: VoiceRecording) {
    val text = recording.transcription
    when {
        text == null -> Text(stringResource(R.string.transcription_pending), italic muted)
        text == TranscriptionRunner.NO_SPEECH_PLACEHOLDER -> Text(text, italic muted)
        else -> Text(text)
    }
}
```

Add strings:
- `R.string.summary_recordings_header` → "Voice notes"
- `R.string.transcription_pending` → "Transcribing…"

---

## Task 12 — Wire VoiceRecordingsSection into WalkSummaryScreen

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt` (modify)

Inside the `WalkSummaryUiState.Loaded` branch, after `SummaryStats`:

```kotlin
val recordings by viewModel.recordings.collectAsStateWithLifecycle()
val playbackUiState by viewModel.playbackUiState.collectAsStateWithLifecycle()

if (recordings.isNotEmpty()) {
    Spacer(Modifier.height(PilgrimSpacing.big))
    VoiceRecordingsSection(
        walkStartTimestamp = s.summary.walk.startTimestamp,
        recordings = recordings,
        playbackUiState = playbackUiState,
        onPlay = viewModel::playRecording,
        onPause = viewModel::pausePlayback,
    )
}
```

---

## Task 13 — FakeVoicePlaybackController

**File:** `app/src/test/java/org/walktalkmeditate/pilgrim/audio/FakeVoicePlaybackController.kt` (new)

```kotlin
class FakeVoicePlaybackController : VoicePlaybackController {
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    val playCalls = Collections.synchronizedList(mutableListOf<Long>())
    val pauseCalls = AtomicInteger(0)
    val stopCalls = AtomicInteger(0)
    val releaseCalls = AtomicInteger(0)

    override fun play(recording: VoiceRecording) {
        playCalls += recording.id
        _state.value = PlaybackState.Playing(recording.id)
    }
    override fun pause() {
        pauseCalls.incrementAndGet()
        val current = (_state.value as? PlaybackState.Playing)?.recordingId ?: return
        _state.value = PlaybackState.Paused(current)
    }
    override fun stop() {
        stopCalls.incrementAndGet()
        _state.value = PlaybackState.Idle
    }
    override fun release() {
        releaseCalls.incrementAndGet()
        _state.value = PlaybackState.Idle
    }

    fun completePlayback() { _state.value = PlaybackState.Idle }
}
```

---

## Task 14 — OrphanRecordingSweeperTest

**File:** `app/src/test/java/org/walktalkmeditate/pilgrim/audio/OrphanRecordingSweeperTest.kt` (new)

Cases:
- `case_a_orphan_wav_deleted_row_preserved`
- `case_b_orphan_row_deleted_when_file_missing`
- `case_c_zombie_null_transcription_zero_data_size_both_deleted`
- `case_d_null_transcription_with_valid_wav_reschedules`
- `mixed_a_b_c_d_in_one_sweep_counts_correctly`
- `path_guard_rejects_wav_outside_recordings_dir`
- `path_guard_rejects_non_wav_extension`
- `sweep_no_op_when_walk_does_not_exist`

Helpers:
- `writeWavWithDataSize(file: Path, dataBytes: Int)` — writes a 44-byte canonical header with dataSize set to `dataBytes`, plus that many zero bytes after.
- `insertRecording(walkId, transcription, fileRelativePath)` from Stage 2-D's pattern.

---

## Task 15 — WalkSummaryViewModelTest extension

**File:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModelTest.kt` (modify)

New tests:
- `recordings flow emits the walk's voice recordings`
- `init triggers sweeper for displayed walk`
- `playRecording delegates to controller`
- `playback state maps Playing into PlaybackUiState with id and isPlaying=true`
- `playback state Paused maps to isPlaying=false`
- `onCleared releases the controller`

Update the existing 5 tests' WalkSummaryViewModel construction with the new constructor params (FakeVoicePlaybackController + a real OrphanRecordingSweeper backed by in-memory Room and a tmpfs filesDir, OR a stub sweeper if construction is awkward).

---

## CI gate after each task that compiles

Tasks 1, 8, 9, 10, 11, 12 — should produce a buildable state.
Tasks 2-7, 13-15 — produce buildable state but only test-runnable after the matching production code lands.

Final verification:
```bash
JAVA_HOME=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8 ANDROID_HOME=/Users/rubberduck/Library/Android/sdk PATH=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8/bin:$PATH ./gradlew assembleDebug lintDebug testDebugUnitTest
```

Expected: 138 → ~150 tests, lint clean, debug APK ~165 MB (negligible delta from media3-exoplayer ~2-3 MB).

---

## Commit message

```
feat(audio): Stage 2-E — Walk Summary voice list + ExoPlayer + orphan sweeper

WalkSummaryScreen now shows voice recordings inline: relative offset
into the walk, duration, transcription (or "Transcribing…" placeholder
while Stage 2-D's worker hasn't finished, italic-muted for
NO_SPEECH_PLACEHOLDER), wpm caption, play/pause button. Playback via
media3 ExoPlayer (lazy-init, one-recording-at-a-time, audio focus
routed through the existing AudioFocusCoordinator with ExoPlayer's
built-in focus disabled).

Orphan sweeper handles four cases:
  (a) WAV on disk with no Room row → delete file (canonical-path guard)
  (b) Row pointing to missing WAV → delete row
  (c) Row with null transcription + zero-data-size WAV → delete both
  (d) Row with null transcription + valid WAV → reschedule transcription
      via Stage 2-D's TranscriptionScheduler (KEEP policy = no-op if
      already enqueued)

Triggered on WalkSummaryViewModel.init for the displayed walk; a daily
periodic OrphanSweeperWorker (HiltWorker, KEEP policy) handles the
global case for walks the user doesn't open.

Tests: NN/NN. Production ExoPlayer not unit-tested (needs the Android
media stack); Stage 2-F's instrumented test verifies real playback.
```
