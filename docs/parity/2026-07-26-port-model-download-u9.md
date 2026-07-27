# Whisper Model Download Worker + Scheduler (U9) — Delivery Contract

> **Plan:** `docs/plans/2026-07-23-001-feat-ios-v190-parity-port-plan.md` (U9) · **Requirements:** R7, R13
> **iOS pin:** `pilgrim-ios` @ `9a418e4` — **no Swift surface exists to quote**: iOS delegates the entire
> model transfer to `WhisperKit.download(variant:from:progressCallback:)` (U8 spec L3/L5). U9 is an
> Android-original delivery mechanism, so this addendum records the **binding delivery contract**
> instead of per-lens Swift parity claims. The observable *state* semantics it feeds are pinned by U8
> (`docs/parity/2026-07-26-port-model-state-u8.md`).
> **Android files:** `audio/model/WhisperModelDownloadWorker.kt` (+ `WhisperModelDownloadSpec`,
> `ModelDownloadFiles`, `FreeSpaceProbe`, `PendingTranscriptionWalkSource` seams),
> `audio/model/WhisperModelDownloadScheduler.kt`, bindings in `di/TranscriptionModule.kt`, dedicated
> client in `di/NetworkModule.kt`, trigger in `MainActivity`; tests
> `audio/model/WhisperModelDownloadWorkerTest.kt`, `audio/model/WorkManagerWhisperModelDownloadSchedulerTest.kt`.
> **CDN facts (probed live against `https://cdn.pilgrimapp.org/models/ggml-base.bin`):**
> `accept-ranges: bytes`, real 206 on `Range` requests, stable non-weak `ETag`. Resume is therefore a
> first-class path, not an optimistic one; ETag-change handling stays only for object republishes
> (U17 re-pins the digest/size against the published object at release time).

## C1. Trigger — first foreground Activity resume

- Enqueued from `MainActivity.onResume`, once per process (`AtomicBoolean` guard). **Not**
  `Application.onCreate`: widget, broadcast, and service process starts must never schedule a
  148 MB transfer.
- `ensureEnqueued()` is a gated KEEP enqueue on unique work `whisper-model-download`:
  - latest record **FAILED** → no-op. Both terminals await an explicit user `retry()` (REPLACE);
    auto-re-enqueue on every app open would burn 3 × 148 MB per launch against a mispublished
    object — exactly the risk the checksum attempt cap exists to bound.
  - latest record **SUCCEEDED** → no-op. Delivery happened; the filesystem probe (U8 L4) is the
    ongoing source of truth, and U10's transcription-path pre-check is the re-heal for exotic
    desyncs (D2D artifacts). The worker also early-returns success if the verified model is
    already on disk, so a stale-record re-enqueue is harmless.
  - anything else (no record, ENQUEUED/RUNNING/BLOCKED — KEEP dedupes; CANCELLED — resume) → enqueue.
- Single-flight download = unique work + KEEP, the AF31 analogue mapped in U8 spec L6.

## C2. Constraint policy

- Default `NetworkType.UNMETERED`. Sticky override flag `modelDownloadCellularOverride`
  (DataStore, `voice_preferences` file — beside `autoTranscribe`, the transcription pref namespace)
  flips the constraint to `CONNECTED`.
- `setCellularOverride(enabled)`: persist the flag, then re-enqueue with **REPLACE** — but only when
  unfinished work exists (a settings flip months after delivery must not schedule network work).
  REPLACE is lossless because of the resume protocol (C3).
- **No `STORAGE_NOT_LOW` constraint** (divergence from the voice-guide scheduler): it would hold the
  job ENQUEUED with no user-visible reason. The worker-start StatFs precheck produces the
  user-actionable `FailedStorage` terminal instead (C5).
- Plain `CoroutineWorker`: not expedited (expedited quota is for seconds-scale work), no foreground
  service. WorkManager's default backoff covers transient-retry pacing.

## C3. Resume protocol

- Layout: `<base>/ggml-base.bin.part` + `<base>/ggml-base.bin.etag` beside the final model path —
  **all** paths from `WhisperModelConfig` path functions, so writes, reads, and deletes can never
  decouple (U8 D1/D2, Stage 5-D coupling lesson).
- Request shape: when the partial is non-empty **and** an etag is stored →
  `Range: bytes=<part.length>-` + `If-Range: <etag>`. A partial without its etag has no object
  identity to resume against → discarded, restart from zero.
- Response shape:
  - `206` → append; the SHA-256 digest is seeded by streaming the existing partial prefix through
    it before the body bytes (C4).
  - `200` → the object changed under `If-Range` (or no resume was requested) → truncate to zero,
    fresh digest, persist the response's ETag **before** body streaming so a mid-stream kill leaves
    a partial/etag pair that self-describes its object version. No ETag on the response → the etag
    file is removed and the next interruption restarts from zero.
  - `416` → local partial is incoherent with the object → discard partial + etag, `retry`.
  - any other code → `retry` (C5; never terminal).
- The partial **survives cancellation** — REPLACE (constraint flip, user retry), OEM kills, and
  `Result.retry()` all resume from it. Deliberate divergence from the voice-guide worker's
  tmp-delete-on-cancel: WorkManager stoppage is routine for a transfer this size, and
  148-MB-from-zero loops never converge on aggressive OEMs (plan risk table).
- Writer serialization: a process-local `Mutex` (`ModelDownloadFiles`, `@Singleton`). The whole
  write → verify → rename critical section runs under the lock, and the partial's length is
  re-probed **after** acquisition — a REPLACE-cancelled writer fully unwinds (streams closed,
  buffers flushed) before its replacement reads the length it resumes from.

## C4. Verify protocol

- SHA-256 is streamed during the write; on resume the existing prefix is hashed first, then the
  digest continues over appended bytes. The **full-file** digest must equal
  `WhisperModelConfig.EXPECTED_SHA256`; the digest/size pair is injected as
  `WhisperModelDownloadSpec` so tests drive the identical protocol with small payloads.
- Bounded write: a `Content-Length` that would put the file past `EXPECTED_BYTES`, or cumulative
  streamed bytes exceeding it, aborts **before filling storage** → terminal Checksum-class failure
  with partial + etag deleted (a mispublished object cannot be fixed by retrying; U17 re-pins).
- A short stream (fewer bytes than expected, or an IO error mid-body) is never verified — the
  partial is kept and the attempt returns `retry` for a later resume.
- Delivery ordering, all under the writer mutex: digest compare → atomic rename onto
  `baseModelPath` → **sha marker written LAST** (U8 L4: "marker present + file missing" must probe
  Absent, so the inverse crash window — model present, marker pending — is the only one allowed) →
  etag cleanup → success side-effects (C6).

## C5. Failure taxonomy

| Condition | Result | Disk effect |
|---|---|---|
| StatFs precheck: `available < 160 MiB − partial.length` (headroom over what the partial already holds) | terminal `failure(reason=storage)`, **before any network I/O** | partial + etag kept |
| Checksum mismatch, `runAttemptCount < 3` | `retry` | partial + etag deleted (restart from zero) |
| Checksum mismatch, `runAttemptCount >= 3` | terminal `failure(reason=checksum)` | partial + etag deleted |
| Oversize (`Content-Length` or streamed bytes past expected) | terminal `failure(reason=checksum)` | partial + etag deleted |
| IO/network error, non-2xx, 416, short stream | `retry` — transient failures are **never** a terminal state and never surface in `WhisperModelState` (U8 D4) | partial + etag kept (416: deleted) |

- Terminal reasons travel via `outputData` (`failure_reason` = `checksum` \| `storage`) — the only
  channel U8's store accepts failure states from (a deleted partial makes the filesystem read
  Absent).
- Progress travels via `setProgress`: `bytes_downloaded` + `total_bytes` + `verifying` flag,
  matching the U8 `ModelDownloadWork` mapping (RUNNING → `Downloading(bytes, total)` \|
  `Verifying`).

## C6. Success side-effects (in order, after the marker lands)

1. `store.onBaseVerified()` — the U10 seam: the atomic engine switch + legacy-tiny delete are
   implemented behind this hook; in U9 it is a no-op.
2. `store.invalidate()` — re-probe so `state` flips to `Ready(Base)` without waiting for a work
   emission.
3. Transcription re-kick, gated on the auto-transcribe preference read via
   `awaitAutoTranscribe()` (the migration-aware read `WalkFinalizationObserver` uses): every walk
   holding a null-transcription recording (`SELECT DISTINCT walk_id … WHERE transcription IS NULL`)
   is re-enqueued as `transcribe-walk-<id>` with **REPLACE** — superseding any half-elapsed 5 h
   backoff a model-load failure left behind. A re-kick failure logs and does not fail the
   delivered download.

## C7. HTTP client

Dedicated `@ModelDownloadHttpClient` (`NetworkModule`): the shared client's 45 s **call** timeout
would abort a 148 MB body on every real-world connection. The download client bounds connect (10 s)
and per-read socket inactivity (30 s) only — total transfer time is unbounded by design.

## Test map

| Contract point | Test |
|---|---|
| C1 gate: KEEP dedupe, FAILED no-op, SUCCEEDED no-op, retry REPLACE | `WorkManagerWhisperModelDownloadSchedulerTest` |
| C2: UNMETERED default, override → CONNECTED, flip REPLACEs pending work only | same |
| U8 WorkInfo mapping incl. outputData reason round-trip through real WorkManager | same |
| C3: Range/If-Range from partial length, 200-restart on ETag change, no-etag restart, mutex handoff re-probe | `WhisperModelDownloadWorkerTest` |
| C4: full + resumed downloads verify; marker-last (no marker on any failed verify; model+marker present, partial gone at hook time); oversize both flavors abort | same |
| C5: precheck fails with zero requests; checksum retry → cap terminal; short stream retries keeping the partial | same |
| C6: re-kick REPLACE gated on auto-transcribe; hook + invalidate invoked | same |
