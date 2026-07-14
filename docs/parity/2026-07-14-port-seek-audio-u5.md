# Port spec — Seek audio, haptics, and preferences (U5)

- **iOS anchor:** `pilgrim-ios` @ `c1745e88494d7677c4be8770ab6ceed1a61f3f6f` (c1745e8)
- **Plan:** `docs/plans/2026-07-14-001-feat-seek-mode-journal-scenery-plan.md` § U5
- **Scope boundary:** the sonar/bowl sound player + its suppression gate seam,
  the four-part (plus one dormant) seek haptic vocabulary, and the seek
  preferences family. Engine wiring (who calls `playPing`/`playBowl`/haptics
  and when) is U9's; U5 exposes event-shaped entry points driven only by a
  `closeness: Float` and booleans.

## 1. Assets (verbatim per plan R7)

| iOS bundle file | Android `res/raw` | container | sha256 |
|---|---|---|---|
| `Pilgrim/Support Files/seek-ping.aac` | `seek_ping.aac` | MPEG ADTS, AAC v2 LC, 44.1 kHz mono | `ac5e2e59…c56abb7` (byte-identical) |
| `Pilgrim/Support Files/seek-bowl.aac` | `seek_bowl.aac` | MPEG ADTS, AAC v2 LC, 44.1 kHz mono | `99ba5bf8…d4d8b40` (byte-identical) |

Both are ADTS AAC streams (checked with `file`), so the `.aac` extension is
kept; only the resource-name dash→underscore rename applies. **Provenance:**
CC0 third-party assets — ping = freesound.org/s/701071 (0.7 s short variant),
bowl = freesound.org/s/150453 (iOS plan
`pilgrim-ios/docs/plans/2026-07-06-001-feat-seek-mode-plan.md:127`). CC0 → no
Settings → About attribution required; provenance recorded here and at the
resource-id reference in `SeekSoundPlayer.kt`.

## 2. Sound player — `SeekSoundPlayer`

iOS class doc (`Pilgrim/Models/Audio/SeekSoundPlayer.swift:3-11@c1745e8`):

```swift
/// Pocket guidance channel for Seek: the sonar ping and the reveal bowl,
/// played through the dedicated "seekPing" audio consumer (`.playback` +
/// `.mixWithOthers` — never a mic-capable mode, never `duckOthers`). The
/// consumer stays active from `prepare()` to `stop()` so sub-second pings
/// don't churn the session; there is deliberately no silent keep-alive bed.
///
/// Pings skip — never queue, never duck — while a whisper or voice-guide
/// prompt is speaking, and while the session is in a mic-capable mode so an
/// active talk recording is never perturbed.
```

### 2.1 Session/focus lifecycle

iOS (`SeekSoundPlayer.swift:66-71,185-199@c1745e8`): `prepare()` activates the
`"seekPing"` consumer (`.playbackOnly`) and arms both `AVAudioPlayer`s;
`stop()` releases players and deactivates. If nothing could be armed the
consumer is released immediately (`deactivateIfNothingArmed`,
`:178-181`).

Android: session-scoped **own `AudioFocusRequest`
(`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, `USAGE_MEDIA` +
`CONTENT_TYPE_SONIFICATION`) via direct `AudioManager`** — the
soundscape-player precedent for a session-long audio holder, and the
BellPlayer/StonePlayer earcon family's focus mode. Focus is requested at
`prepare()` (or lazily on the first play that needs it, mirroring
`activateSessionIfNeeded` on every play path) and abandoned at `stop()`.
Defensive `abandonFocus()` runs at the top of `requestFocus()` (Stage 5-F
house rule). Manual `MediaPlayer()` → `setAudioAttributes` →
`setDataSource(rawResourceFd)` → `prepare()` ordering — never
`MediaPlayer.create()` (BellPlayer.kt:45-52 rationale).

**Conscious divergences:**
- iOS `.mixWithOthers` neither ducks concurrent audio nor can be denied.
  Android has no non-ducking mix focus, so concurrent music ducks while the
  seek channel is active, and a focus **denial is possible** (e.g. phone
  call). Denial → the play attempt is skipped AND the coupled haptic is
  skipped (no phantom buzz — BellPlayer.kt:158-165 precedent; plan U5 error
  path). This is the same trade every earcon in the app already makes
  (`WhisperPlayer.kt:183-188`: "iOS uses `.mixWithOthers` instead — Android
  doesn't have an equivalent").
- Focus held `prepare()`→`stop()` rather than per-play: mirrors iOS's
  "consumer stays active … so sub-second pings don't churn the session";
  per-ping request/abandon would duck-pump the user's podcast at pulse
  cadence.

### 2.2 Ping — volume math + toggles

iOS (`SeekSoundPlayer.swift:78-90,141-148@c1745e8`):

```swift
func playPing(aligned: Bool, closeness: Double = 1) {
    pingGeneration += 1
    guard isEnabled, isSoundsEnabled else { return }
    let volumeScale = 0.55 + 0.45 * min(max(closeness, 0), 1)
    firePingIfAllowed(volumeScale: volumeScale)
    guard aligned else { return }
    let generation = pingGeneration
    DispatchQueue.main.asyncAfter(deadline: .now() + doublePingGap) { [weak self] in
        guard let self, generation == self.pingGeneration,
              self.isEnabled, self.isSoundsEnabled else { return }
        self.firePingIfAllowed(volumeScale: volumeScale)
    }
}
...
private func play(_ player: AVAudioPlayer, volumeScale: Double = 1) {
    player.volume = Float(UserPreferences.seekSonarVolume.value * volumeScale)
```

Android pins:
- effective volume = `seekSonarVolume.value × (0.55 + 0.45 × closeness.coerceIn(0, 1))`,
  NaN-guarded and clamped at the read site (BellPlayer.kt:375-388 precedent).
- checked **at play time** on every ping AND on the delayed second ping:
  sonar toggle (`seekSonarEnabled`, default true) and master sounds toggle
  (`soundsEnabled` — "silences seek audio the same way it silences bells and
  whispers … a mid-walk flip applies to the very next ping or bowl",
  `SeekSoundPlayer.swift:21-26`).
- `aligned` → double-ping with a 0.25 s gap (`doublePingGap = 0.25`,
  `SeekSoundPlayer.swift:46`), generation-guarded: any later `playPing`,
  `stop()`, or interruption bumps the generation and the pending second dies
  (`testNewRequestBetweenDoublePingPlays_supersedesPendingSecond`,
  `SeekSoundPlayerTests.swift:223-233`). The gap is constructor-injectable
  for tests (iOS injects it the same way, `:46`).
- generation bumps happen unconditionally at the top of `playPing` — even a
  policy-skipped ping supersedes a pending second (iOS `:79` runs before the
  guard).

### 2.3 Ping — suppression gate (skip, never queue, never duck)

iOS (`SeekSoundPlayer.swift:118-137@c1745e8`):

```swift
private func firePingIfAllowed(volumeScale: Double) {
    guard !isInterrupted, canPingOverCurrentAudio else { return }
    ...
}

private var canPingOverCurrentAudio: Bool {
    guard !isWhisperPlaying(), !isVoiceGuidePlaying() else { return false }
    switch coordinator.currentMode {
    case .recordingOnly, .recordAndPlay:
        return false
    case .idle, .playbackOnly:
        return true
    }
}
```

Android seam: `SeekPingGate` with three injected boolean providers, defaulting
to nothing in U5 — **U9 binds the real ones**:

| iOS source | iOS default closure | Android provider U9 must bind |
|---|---|---|
| whisper playing | `AudioPriorityQueue.shared.isPlayingWhisper` (`SeekSoundPlayer.swift:48`) | `data/whisper/WhisperPlayer` — **gap:** its public `isPlaying` StateFlow tracks the *preview* channel only (`WhisperPlayer.kt:63-65`); the main channel (`playPlayer`) has no public is-playing surface. U9 must expose a combined any-channel signal on `WhisperPlayer` (or equivalent) before binding. |
| voice-guide prompt playing | `VoiceGuidePlayer.shared.isPlaying` (`SeekSoundPlayer.swift:49`) | `audio/voiceguide/VoiceGuidePlayer.state` (`StateFlow<State>`, gate on `State.Playing`). |
| mic-capable session (active talk recording) | `coordinator.currentMode ∈ {recordingOnly, recordAndPlay}` (`:131-136`) | `ui/walk/WalkViewModel.voiceRecorderState` (`StateFlow<VoiceRecorderUiState>`, gate on `Recording`) — the same signal the walk UI uses; there is no recorder-level singleton flow today (`VoiceGuideOrchestrator.kt:70-74` notes the same gap). |

Suppressed pings **skip** — nothing is queued, nothing ducks the current
audio, and the generation was already bumped so no stale second sneaks out
later.

### 2.4 Bowl

iOS (`SeekSoundPlayer.swift:92-104@c1745e8`):

```swift
/// Reveal/completion tone. Part of the reveal ritual rather than the
/// sonar guidance channel, so it plays even when the sonar toggle is off —
/// only the master Sounds toggle silences it.
func playBowl() {
    guard isSoundsEnabled, !isInterrupted else { return }
    ...
}
```

- Bowl plays on `revealedNext` AND on `seekComplete`
  (`ActiveWalkViewModel+Seek.swift:149-155@c1745e8`) — both route to the same
  `playBowl()`; U9 calls it for both events.
- Ignores the sonar toggle (`testBowl_playsEvenWhenSonarDisabled`,
  `SeekSoundPlayerTests.swift:336-344`), respects master sounds and the
  interruption flag, and **does** use the sonar-volume pref (`play(player)`
  with `volumeScale = 1` → volume = `seekSonarVolume × 1`,
  `SeekSoundPlayer.swift:103,141-142`).
- The suppression gate does NOT apply to the bowl (iOS checks
  `canPingOverCurrentAudio` only in `firePingIfAllowed`).

### 2.5 Completion release (4.5 s)

iOS (`ActiveWalkViewModel+Seek.swift:26-29,159-172@c1745e8`):

```swift
/// Slightly longer than the completion bowl's ~4 s ring so releasing the
/// audio consumer never clips it.
var seekCompleteSoundStopDelay: TimeInterval = 4.5
...
private func scheduleSeekSoundRelease() {
    seekGeneration += 1
    let generation = seekGeneration
    DispatchQueue.main.asyncAfter(deadline: .now() + seekSenses.seekCompleteSoundStopDelay) { [weak self] in
        guard let self, self.seekGeneration == generation else { return }
        self.seekSound?.stop()
    }
}
```

Android: iOS keeps the schedule in the view model; Android moves it **into
the player** as `playCompletionBowl()` (bowl + generation-guarded delayed
`stop()`, delay constructor-injectable, default 4 500 ms) so U9 wires one
call per engine event and the release contract is testable in U5. After the
release fires, focus is abandoned and players are gone; the engine is
`complete` so no further pulses arrive (subsequent pings are impossible at
the source). Two generation counters mirror iOS exactly: `pingGeneration`
(player-owned, guards the pending aligned second) and `releaseGeneration`
(iOS keeps this one in the view model's `seekGeneration`) — so pings and
interruptions never strand a scheduled release, and the release never
swallows a legitimate pending second. `stop()` bumps both and stays
idempotent so U9's regular teardown at walk end is safe (iOS comment
`:163-164`).

### 2.6 Interruptions

iOS (`SeekSoundPlayer.swift:201-217@c1745e8`):

```swift
/// AVAudioPlayer never auto-resumes after an interruption (AF5); for
/// sub-second one-shots the honest re-arm is to drop the players and
/// recreate them on the next play.
private func handleInterruption(_ event: AudioSessionCoordinator.InterruptionEvent) {
    guard isSessionActive else { return }
    switch event {
    case .began:
        isInterrupted = true
        pingGeneration += 1
        pingPlayer?.stop()
        bowlPlayer?.stop()
        pingPlayer = nil
        bowlPlayer = nil
    case .ended:
        isInterrupted = false
    }
}
```

Android maps interruption events onto the focus-change listener of the
player's own request:

| focus change | iOS analogue | action |
|---|---|---|
| `AUDIOFOCUS_LOSS_TRANSIENT` | `.began` | `isInterrupted = true`, generation++, stop+release both players (drop, never pause-resume). Focus request stays held — the OS returns `GAIN` when the interrupter finishes. |
| `AUDIOFOCUS_GAIN` | `.ended` | `isInterrupted = false`. Players are lazily re-armed on the next play — never auto-resumed. |
| `AUDIOFOCUS_LOSS` | *(no iOS analogue — permanent takeover)* | full internal stop: drop players, abandon focus, session inactive, `isInterrupted = false`. Next play lazily re-activates with a fresh request; if the takeover is still live the request is denied and the ping skips. Rationale: Android never delivers `GAIN` after a permanent `LOSS`, so keeping `isInterrupted = true` would mute seek for the rest of the walk. |
| `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` | *(none)* | no-op — the OS auto-ducks (`setWillPauseWhenDucked(false)`; BellPlayer.kt:199-211 rationale). |

Player release from a focus/MediaPlayer callback is posted to the main
handler, never synchronous in-callback (Stage 5-F house rule).

### 2.7 Error paths

iOS: player-init failure → nil player → `deactivateIfNothingArmed()` releases
the consumer (`SeekSoundPlayerTests.swift:248-285`); `play()` returning false
→ `resetAfterFailure()` drops both players and deactivates
(`SeekSoundPlayer.swift:144-154`). Android mirrors: arm failure or
`start()` throw → release both players + abandon focus + session inactive;
`MediaPlayer` error listener routes to the same reset via `mainHandler.post`.

## 3. Haptics — `SeekHaptics`

iOS defines five seek patterns (`HapticManager.swift:92-96@c1745e8`):
`seekTick(closeness:)`, `seekAligned(closeness:)`, `seekArrival`,
`seekBreathIn`, `seekBreathOut`.

**`seekBreathOut` has no production call site at c1745e8** (repo grep: the
only firers are `ActiveWalkViewModel+Seek.swift:138,144,147` — tick/aligned,
arrival, breathIn — and `SeekGatewayView.swift:78` — breathIn). Android
mirrors the dormant vocabulary: `breathOut()` is defined, tested, and
uncalled.

### 3.1 Pattern shapes (Swift → `VibrationEffect`)

`playSeekTick` (`HapticManager.swift:229-240@c1745e8`):

```swift
let intensity = Float(0.3 + 0.25 * min(max(closeness, 0), 1))
... CHHapticEvent(eventType: .hapticTransient, ..., relativeTime: 0)  // sharpness 0.15
```
→ one soft transient. Android primary: composition of one `PRIMITIVE_TICK`
at scale `0.3 + 0.25 × closeness`. Fallback: 30 ms one-shot at
`intensity × 255` amplitude.

`playSeekAligned` (`:242-253`):

```swift
let intensity = Float(0.4 + 0.3 * min(max(closeness, 0), 1))
... relativeTime: 0),
... relativeTime: 0.18)
```
→ two soft transients 0.18 s apart. Android primary: two `PRIMITIVE_TICK`s
(second delayed 180 ms), both at scale `0.4 + 0.3 × closeness`. Fallback:
waveform `[30, 150, 30]` / `[a, 0, a]` (onsets 0 / 180 ms).

`playSeekArrival` (`:255-271`):

```swift
let steps: [(time: TimeInterval, intensity: Float)] = [(0, 0.4), (0.16, 0.55), (0.34, 0.7)]
```
→ three rising soft taps. Android primary: three `PRIMITIVE_TICK`s at scales
0.4 / 0.55 / 0.7, delays 160 / 180 ms. Fallback: waveform
`[30, 130, 30, 150, 30]` / `[102, 0, 140, 0, 179]` (onsets 0 / 160 / 340 ms).

`playSeekBreath` (`:273-287`):

```swift
let quiet = CHHapticEventParameter(parameterID: .hapticIntensity, value: 0.15)
let full = CHHapticEventParameter(parameterID: .hapticIntensity, value: 0.35)
let first = rising ? quiet : full
let second = rising ? full : quiet
let events = [
    CHHapticEvent(eventType: .hapticContinuous, ..., relativeTime: 0, duration: 0.9),
    CHHapticEvent(eventType: .hapticContinuous, ..., relativeTime: 0.9, duration: 0.9)
]
```
→ two 0.9 s continuous segments swelling 0.15 → 0.35 (breathIn) or falling
0.35 → 0.15 (breathOut). Android: amplitude waveform `[900, 900]` /
`[38, 89]` (in) or `[89, 38]` (out) — compositions cannot express a 1.8 s
envelope. Requires `hasAmplitudeControl()`; without it the honest fallback is
the same single soft tap iOS falls back to
(`UIImpactFeedbackGenerator(style: .soft)`, `HapticManager.swift:176-180`),
NOT a 1.8 s fixed-strength rattle.

Note: composition `delay` is measured from the end of the previous primitive
(tick primitives are ~10-20 ms), so primitive onsets land within ~2 hundredths
of the iOS `relativeTime`s — inaudible skew for a contemplative pattern; the
waveform fallbacks match onsets exactly.

### 3.2 Foreground gating — deliberate Android divergence

iOS gates every seek haptic on the app being active
(`ActiveWalkViewModel+Seek.swift:23-25,264-267@c1745e8`):

```swift
/// Haptics only render in the foreground (iOS discards background CoreHaptics);
/// the gate lives here so event routing can stay in the view model.
var isAppActive: () -> Bool = { UIApplication.shared.applicationState == .active }
```

That gate exists because *iOS discards background CoreHaptics* — a platform
limitation, not a design goal. Android `Vibrator` works with the screen off,
and screen-off pocket walks are the primary seek use case, so **`SeekHaptics`
fires regardless of foreground state** (plan U5 Key Decision). No lifecycle
dependency exists in the class.

### 3.3 Audio coupling

Where a haptic is coupled to a sound (tick/aligned ride the ping),
`SeekSoundPlayer` fires it and gates on the audio attempt's platform outcome
(BellPlayer.kt:158-165 precedent — no phantom buzz on denied focus):

- ping **played** → haptic fires (once per `playPing` call — iOS fires one
  haptic per pulse event, not per double-ping half,
  `ActiveWalkViewModel+Seek.swift:134-138`).
- ping **skipped by policy** (sonar toggle, master sounds, suppression gate,
  interruption flag) → haptic still fires — iOS parity: the view model fires
  the haptic unconditionally regardless of what `playPing` decided, so the
  silent-sonar guidance channel keeps working (`:137-138`).
- ping attempt **rejected by the platform** (focus denied, arm/start
  failure) → no haptic (Android-only failure mode, phantom-buzz rule).

`arrival()` and `breathIn()`/`breathOut()` have no coupled sound (the bowl
belongs to reveal, not arrival) and are fired directly by U9.

## 4. Preferences — `SeekPreferencesRepository`

iOS (`Pilgrim/Models/Preferences/UserPreferences.swift:72-75@c1745e8`):

```swift
static let seekSonarEnabled = UserPreference.Required<Bool>(key: "seekSonarEnabled", defaultValue: true)
static let seekSonarVolume = UserPreference.Required<Double>(key: "seekSonarVolume", defaultValue: 0.5)
static let seekLastDurationMinutes = UserPreference.Required<Int>(key: "seekLastDurationMinutes", defaultValue: 60)
static let seekSafetyShown = UserPreference.Required<Bool>(key: "seekSafetyShown", defaultValue: false)
```

Android: `data/seek/SeekPreferencesRepository` +
`DataStoreSeekPreferencesRepository` + `di/SeekPreferencesModule`, cloned
from the practice family (`data/practice/*`, Stage 10-C):

- storage keys verbatim (`seekSonarEnabled`, `seekSonarVolume`,
  `seekLastDurationMinutes`, `seekSafetyShown`) for `.pilgrim` settings
  parity; `Eagerly` StateFlows with `catch { emit(emptyPreferences()) }` +
  `distinctUntilChanged` so players can read `.value` at play time
  (the family carries no corruption handler — `di/DataStoreModule.kt:22-28`
  builds the shared store without one; mirrored).
- defaults: `true` / `0.5f` / `60` / `false`.
- `setSonarVolume` clamps to `[0, 1]` and maps NaN to the default (plan U5
  happy path; iOS `Double` prefs are unclamped but the only iOS writer is a
  bounded slider — Android clamps at the seam instead of trusting future
  callers).
- `seekLastDurationMinutes` is consumed by U8's gateway (iOS
  `ActiveWalkViewModel+Seek.swift:83` reads it as the fallback duration);
  `seekSafetyShown` by U8's one-time safety sheet. Persisted now, wired later
  (same "persisted-only until consumer lands" convention as
  `zodiacSystem` — `PracticePreferencesRepository.kt:32-42`).

## 5. Test parity map (`SeekSoundPlayerTests.swift@c1745e8` → Android)

| iOS test | Android test |
|---|---|
| `testPrepare_activatesConsumerAndArmsBothPlayersWithoutPlaying` (`:92-100`) | `prepare requests focus and arms both players without starting them` |
| `testStop_deactivatesConsumer` (`:102-109`) | `stop abandons focus` |
| `testDeinitWithoutStop_releasesConsumer` (`:111-120`) | *(no deinit on JVM — covered by idempotent `stop()` + completion release)* |
| `testDisabledPreference_producesNoPlayAttempt` (`:124-133`) | `sonar toggle off skips ping and pending second` |
| `testGlobalSoundsDisabled_suppressesPingAndBowl` (`:135-145`) | `master sounds off suppresses ping and bowl` |
| `testWhisperPlaying_skipsPing_playsAfterClear` (`:147-158`) | suppression-matrix test, whisper axis |
| `testVoiceGuidePlaying_skipsPing_playsAfterClear` (`:160-171`) | suppression-matrix test, voice-guide axis |
| `testMicCapableSessionMode_skipsPing_playsAfterRecordingEnds` (`:173-184`) | suppression-matrix test, talk-recording axis |
| `testVolumePreference_appliedOnNextPing` (`:186-196`) | volume tests at closeness 0 / 1 × pref |
| `testAlignedPing_playsTwice` (`:200-209`) | `aligned ping plays twice with the gap` |
| `testStopBetweenDoublePingPlays_cancelsPendingSecond` (`:211-221`) | `stop cancels the pending second ping` |
| `testNewRequestBetweenDoublePingPlays_supersedesPendingSecond` (`:223-233`) | `newer request supersedes the pending second` |
| `testDisableBetweenDoublePingPlays_silencesPendingSecond` (`:235-244`) | `disable between double-ping halves silences the second` |
| `testPlayerInitFailure_*` / `testPlayFailure_*` (`:248-285`) | *(partially portable — Robolectric's MediaPlayer stub can't throw on demand for raw-fd arm; the focus-denied test covers the platform-reject → reset path)* |
| `testInterruptionBegan_skipsPings_endedRearmsOnNextPing` (`:289-305`) | `transient focus loss drops players, gain re-arms lazily` |
| `testInterruptionBegan_cancelsPendingDoublePing` (`:307-317`) | `transient focus loss cancels pending second ping` |
| `testBowlDuringInterruption_skipsGracefully` (`:321-334`) | `bowl skips during interruption and plays after gain` |
| `testBowl_playsEvenWhenSonarDisabled` (`:336-344`) | `bowl plays when sonar toggle off` |
| `testSeekPreferences_defaults` / `_persistChanges` (`:348-365`) | `DataStoreSeekPreferencesRepositoryTest` defaults + round-trips |
| `testIsEnabled_roundTripsThroughPreference` (`:367-375`) | *(no property mirror on Android — the repository IS the surface)* |
| *(none — Android-only)* | focus denied → no playback + no haptic; completion release after 4.5 s; haptic pattern shapes; haptic foreground-independence; volume clamp |

## 6. Deliberate non-goals (U9 wires)

- No Hilt module for `SeekSoundPlayer`/`SeekPingGate` — the gate's real
  providers cross module seams that U9 owns (see § 2.3 table); U5 keeps them
  constructor-injected closures.
- No engine binding, no reveal-whisper scheduling
  (`scheduleSeekRevealWhisper` is VM orchestration, U9), no gateway
  breath-cycle UI (`SeekGatewayView` is U8).
- No settings UI for the new prefs (U8/U13).
- `SeekHaptics` is Hilt-injectable (`@Inject constructor(Vibrator)`) since
  `HapticsModule` already provides the `Vibrator`, but nothing requests it
  until U9.
