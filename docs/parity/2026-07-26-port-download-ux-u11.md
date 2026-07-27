# Download UX + Pending-Transcription Substate (U11) — Contract Addendum

> **Plan:** `docs/plans/2026-07-23-001-feat-ios-v190-parity-port-plan.md` (U11) · **Requirements:** R7, R8
> **iOS pin:** `pilgrim-ios` @ `9a418e4` (tag v1.9.0). All Swift quotes cite `file@9a418e4`.
> **Origin scope:** MOSTLY ANDROID-ORIGINAL. iOS surfaces exactly two model-delivery UI elements
> (§1, §2): a progress row in Settings → Voice and the same progress banner atop the Walk Summary
> recordings section — both possible because iOS downloads in the foreground with the app open.
> Android's delivery is constraint-gated background WorkManager work (U9), so the richer surface —
> the per-row pending-substate matrix (§3), the cellular-override sheet (§4) — is Android-original
> per plan R8. Where iOS has a shape to quote, it is quoted and matched; everything else binds to
> this addendum, not to Swift.
> **Upstream contracts:** U8 `docs/parity/2026-07-26-port-model-state-u8.md` (state machine, D4/D7),
> U9 `docs/parity/2026-07-26-port-model-download-u9.md` (C2 override, C5 failure taxonomy),
> U10 `docs/parity/2026-07-26-port-engine-switch-u10.md` (Ready gating, L2 forced-English copy rule).
> **Android files:** `ui/walk/VoiceRecordingsSection.kt` (substate mapper + rendering),
> `ui/walk/ModelDownloadSheet.kt` (sheet + `ModelDownloadViewModel`),
> `audio/model/WhisperModelStore.kt` (Data Saver probe seam, beside the U8 unmetered probe),
> `ui/walk/WalkSummaryViewModel.kt` + `ui/recordings/RecordingsListViewModel.kt` (gates),
> `ui/recordings/RecordingsListScreen.kt` (swipe gate), `ui/settings/voice/VoiceCard.kt` +
> `ui/settings/SettingsScreen.kt` (Settings row + sheet entry), `di/TranscriptionModule.kt` (probe binding).

## 1. The iOS Settings row — the one shape Android matches directly

**iOS** (`Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift@9a418e4`, directly under the
Auto-transcribe toggle):

```swift
if case .downloadingModel(let progress) = transcriptionService.state {
    HStack(spacing: 8) {
        SwiftUI.ProgressView(value: progress)
            .progressViewStyle(.linear)
            .tint(.stone)
        Text("Downloading model \(Int(progress * 100))%")
            .font(Constants.Typography.caption)
            .foregroundColor(.fog)
            .minimumScaleFactor(0.7)
            .lineLimit(1)
    }
}
```

**Android claims:**

- `VoiceCard` gains a model row in the same position (below the Auto-transcribe toggle, above the
  divider that introduces the Recordings row). Downloading renders the iOS shape: linear progress
  tinted `stone` + caption `Downloading model N%` in `fog` (percent derived from the byte pair —
  U8 D6 keeps bytes canonical, fraction derivable).
- Android's state machine is wider than iOS's (U8 D4), so the row also renders one caption line for
  `Absent`/`Enqueued`/`Ready(LegacyTiny)` (waiting to download), `WaitingUnmetered` (waiting for
  Wi-Fi), `Verifying`, `FailedChecksum`, and `FailedStorage`. The row is hidden only at
  `Ready(Base)` — delivery complete, nothing to manage. iOS hides its row in every non-downloading
  state because its download is foreground-blocking and its failures surface in the Walk Summary
  banner instead.
- The whole row is tappable → the model download sheet (§4). This is the passive discovery surface;
  the VoiceCard doc comment's "transcription model provisioning is handled elsewhere" deferral note
  is deleted with this unit.

## 2. The iOS Walk Summary banner — matched in intent, not in shape

**iOS** (`Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift@9a418e4`):

```swift
@ViewBuilder
private var transcriptionStatusBanner: some View {
    switch transcriptionService.state {
    case .downloadingModel(let progress):
        HStack(spacing: 8) {
            SwiftUI.ProgressView(value: progress) ...
            Text("Downloading model \(Int(progress * 100))%")
        ...
    case .failed(let message):
        ...
            if hasUntranscribedRecordings {
                Button(action: { Task { await transcribeAll() } }) {
                    Text("Retry")
```

and the header's manual affordance:

```swift
if hasUntranscribedRecordings && !isTranscribing {
    Button(action: { Task { await transcribeAll() } }) {
        Label("Transcribe", systemImage: "text.badge.plus")
```

**Android claims:**

- iOS renders ONE section-level banner because its `TranscriptionService.State` is a single enum
  spanning download + transcription (U8 D7). Android splits the surfaces: `WhisperModelState`
  covers delivery, and each null-transcription row explains itself through the substate matrix
  (§3) — the row is where the user feels the pain, per the plan's goal statement.
- iOS's header "Transcribe" button (manual batch affordance) maps to the pref-OFF manual affordance
  on the pending row (§3, `ManualPending`): tapping schedules `transcribe-walk-<id>` for the walk
  without touching any existing transcription. Disabled pre-Ready, exactly as iOS's button hides
  while `isTranscribing` (which on iOS includes `downloadingModel`).
- iOS's `failed` banner Retry re-runs `transcribeAll()`; Android's failure rows carry the
  download-repair actions instead (retry = `scheduler.retry()`, U9 C1's explicit-user REPLACE
  path) because on Android the failure being surfaced is the *delivery* terminal, not a
  transcription error.

## 3. Substate matrix — the binding contract

Substate is a pure function `f(autoTranscribe pref × model state)` applied to rows whose
`transcription == null` (the row's existence is the "work pending" axis: U9 C6's re-kick contract
makes every null-transcription row schedulable work by definition). Implemented as the top-level
mapper `pendingTranscriptionSubstate(...)` in `VoiceRecordingsSection.kt` — unit-testable without
Compose.

| pref | model state | substate | row rendering |
|---|---|---|---|
| OFF | `Ready(Base)` / `Ready(LegacyTiny)` | `ManualPending(transcribeEnabled=true)` | "Not yet transcribed" + Transcribe affordance |
| OFF | any other state | `ManualPending(transcribeEnabled=false)` | same, affordance disabled — **never download language** (nothing was promised; "waiting on download" would be a lie) |
| ON | `Absent` | `WaitingOnDownload(Absent)` | "Waiting to download transcription model" → sheet |
| ON | `Enqueued` | `WaitingOnDownload(Enqueued)` | same copy → sheet |
| ON | `WaitingUnmetered` | `WaitingOnDownload(WaitingUnmetered)` | "Waiting for Wi-Fi to download transcription model" → sheet |
| ON | `Downloading(b,t)` | `WaitingOnDownload(Downloading)` | byte progress "Downloading model — X of Y MB" + linear progress → sheet |
| ON | `Verifying` | `WaitingOnDownload(Verifying)` | "Verifying transcription model" → sheet |
| ON | `Ready(Base)` / `Ready(LegacyTiny)` | `QueuedForProcessing` | "Queued for transcription…" (no action; the worker owns it) |
| ON | `FailedChecksum` | `DownloadFailedChecksum` | "Model download failed" + Retry action, row → sheet |
| ON | `FailedStorage` | `DownloadFailedStorage` | free-space copy, row → sheet |

- `Absent` and `Enqueued` share copy deliberately: `Absent` under pref ON is a transient
  pre-enqueue window (U9 C1 enqueues on the next foreground resume) — presenting it as a distinct
  state would surface WorkManager bookkeeping, not user truth.
- `Ready(LegacyTiny)` maps identically to `Ready(Base)` everywhere in this unit (U10 gating:
  "Ready for gating = Ready(Base) OR Ready(LegacyTiny)") — upgraders' pending rows are genuinely
  queued against the still-serving tiny (U8 D3).
- **Amendment (review fix):** the OFF rows key `transcribeEnabled` on model *usability*
  (`WhisperModelStore.modelUsable`: verified base OR exact-size transitional tiny on disk), not on
  the `Ready` display state — an upgrader's tiny keeps the manual affordance enabled while base
  delivery work is pending, so the matrix is `f(pref × model state × usability)` for those cells.

## 4. Model download sheet — Android-original

One sheet (`ModelDownloadSheet`, `ModalBottomSheet` per the Stage 10-B picker precedent), reachable
from (a) any `WaitingOnDownload`/failure pending row and (b) the Settings voice row. Contract:

- **Size:** one-time download line formatted from `WhisperModelConfig.EXPECTED_BYTES`
  (`147_951_465` → "about 148 MB", `Locale.US` ASCII digits).
- **Live progress:** byte pair from `WhisperModelState.Downloading` ("X of Y MB" + linear bar).
- **Waiting-for-Wi-Fi explanation:** shown at `WaitingUnmetered` — names the unmetered default
  (U9 C2) and points at the override toggle.
- **Sticky "Use mobile data" toggle:** reads `scheduler.observeCellularOverride()`, writes
  `scheduler.setCellularOverride(enabled)` — the U9 C2 REPLACE-if-unfinished semantics live in the
  scheduler; the sheet is a dumb switch.
- **Retry:** button on `FailedChecksum`/`FailedStorage` → `scheduler.retry()` (REPLACE — the U9 C1
  explicit-user path past a terminal). Checksum body explains re-download; storage body carries the
  free-space copy.
- **Data Saver note:** when `ConnectivityManager.getRestrictBackgroundStatus() ==
  RESTRICT_BACKGROUND_STATUS_ENABLED`, a note warns that background cellular is restricted.
  Probed through the injectable `BackgroundDataRestrictionProbe` seam (same shape as U8's
  `UnmeteredNetworkProbe`) so the sheet content is testable without a shadowed ConnectivityManager.
- Backing VM (`ModelDownloadViewModel`) exposes `modelState` as a direct hot passthrough of
  `WhisperModelStore.state` (Stage 5-G display-only pattern, as `SettingsViewModel.routeCatalog`)
  plus the composed `pendingSubstate` for the recordings surface.

## 5. Retranscribe gating — the destructive-affordance rule

`RecordingsListViewModel.onRetranscribe` and `WalkSummaryViewModel.retranscribeRecording` both
null the transcript BEFORE scheduling (`transcription = null` → `scheduleForWalk`). Pre-Ready that
is silent data loss: the scheduled work would `Result.retry` against a missing model (U10 L1) while
the user's text is already gone (review class A2).

- Both VMs expose `retranscribeEnabled: StateFlow<Boolean>` = `WhisperModelStore.modelUsable`
  (verified base OR exact-size transitional tiny on disk — the same probe `readyModelPath()`
  serves, so the gate stays open through the base download window; U10 gating) and **guard the
  action itself** — a stale-UI tap fails closed as a no-op before the destructive write.
- UI disables the affordances: the retranscribe icon on transcribed rows
  (`VoiceRecordingsSection`), and the StartToEnd retranscribe swipe on the recordings list.
- The manual pref-OFF Transcribe affordance (§3) is gated the same way for consistency, though it
  is non-destructive.

**Copy rule (binding, from U10 L2):** no string in this unit may promise language auto-detection —
the shipped decode is forced-English on both models. Copy therefore never mentions languages at
all. Numeric copy uses `Locale.US` ASCII digits (Stage 5-A/6-B locale rule); user-facing sizes use
MB = 10^6 bytes, matching the existing recordings-size captions.

## Test map

| Contract point | Test |
|---|---|
| §3 full matrix, every pref × state cell | `PendingTranscriptionSubstateTest` |
| §3 row rendering (manual affordance, byte progress, queued, failures, sheet tap) | `VoiceRecordingsSectionTranscriptionTest` |
| §1 Settings row shape (percent line, hidden at Ready(Base), tap opens sheet) | `VoiceCardTest` |
| §4 sheet content (size, Wi-Fi explanation, override toggle, retry, free-space, Data Saver note) | `ModelDownloadSheetContentTest` |
| §4 VM plumbing (override → scheduler recorded, retry → scheduler recorded, substate composition) | `ModelDownloadViewModelTest` |
| §5 gates (action no-op pre-Ready, enabled post-Ready, both VMs) | `RecordingsListViewModelTest`, `WalkSummaryViewModelTest` |
