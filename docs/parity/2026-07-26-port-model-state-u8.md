# Whisper Model State Store + Variant Resolver (U8) — iOS → Android Port Spec

> **Plan:** `docs/plans/2026-07-23-001-feat-ios-v190-parity-port-plan.md` (U8) · **Requirements:** R6, R7
> **iOS pin:** `pilgrim-ios` @ `9a418e4` (tag v1.9.0, 2026-07-21). All Swift quotes cite `file@9a418e4`.
> **Authority:** SHIPPED Swift over plan docs. iOS's model-state surface lives inside `TranscriptionService` (WhisperKit + UserDefaults path bookkeeping); Android's lives in a dedicated probe-based store (whisper.cpp + filesystem). Parity here is **behavioral, not architectural** (origin scope boundary): the semantics that must survive the translation are (1) resolution is *variant-keyed*, (2) a pre-base install resolves to *no usable base* and routes through a fresh download, (3) download progress is an *observable state* the UI can render, (4) presence claims are verified against the real filesystem, never trusted from a stored flag alone.
> **Android files:** `audio/model/WhisperModelState.kt`, `audio/model/WhisperModelConfig.kt`, `audio/model/WhisperModelStore.kt` (+ seam types `ModelDownloadWork`/`ModelDownloadWorkSource`/`UnmeteredNetworkProbe` in the store file), `audio/model/WhisperModelScope.kt`, provider/bindings in `di/TranscriptionModule.kt`; test `audio/model/WhisperModelStoreTest.kt`.
> **Android templates:** `data/voiceguide/VoiceGuidePackState.kt` + `VoiceGuideCatalogRepository` (filesystem × WorkInfo × selection composition), `audio/WhisperModelInstaller.kt` (probe = file + expected size; superseded by this store in U10).

## L1. The shipped variant is `base`, and resolution is keyed on it

**iOS** (`Pilgrim/Models/TranscriptionService.swift@9a418e4`):

```swift
static let modelVariant = "base"
static let modelPathDefaultsKey = "whisperModelPath"
static let modelVariantDefaultsKey = "whisperModelVariant"
```

Pinned independently by test (`UnitTests/TranscriptionServiceModelVariantTests.swift@9a418e4`):

```swift
func testShippedVariant_isBase() {
    XCTAssertEqual(TranscriptionService.modelVariant, "base")
}
```

**Android claims:**
- `WhisperModelConfig.VARIANT = "base"`, pinned by test the same way.
- The model is the **multilingual** `ggml-base.bin` (iOS's WhisperKit `"base"` variant is `openai_whisper-base`, the multilingual model — not `base.en`), matching plan R6.
- iOS keys the *UserDefaults record* on the variant; Android keys the *storage layout*: `filesDir/whisper-model/base/ggml-base.bin` + `filesDir/whisper-model/base/ggml-base.bin.sha256` marker. A future variant bump changes the directory, so a stale model can never satisfy the new variant's probe — the same property `modelVariantDefaultsKey` provides on iOS (D1).
- The two UserDefaults key literals themselves are **not** ported: they exist to make a stored path self-describing, and Android stores no path (L4/D2).

## L2. Pre-base installs resolve to nil and route through a fresh download

**iOS** (`TranscriptionService.swift@9a418e4`, doc comment + body of the resolver):

```swift
/// A saved model only counts when it was downloaded for the variant the
/// app currently ships. Installs that predate the variant key (pre-base
/// installs on `tiny`) resolve to nil, which routes them through a fresh
/// download instead of silently staying on the old model. Paths are
/// stored relative to Documents because iOS may relocate the app
/// container between launches — an absolute path would strand the model
/// after every update. Absolute values (pre-relative installs) still
/// resolve as-is.
static func resolvedModelPath(defaults: UserDefaults, variant: String) -> URL? {
    guard let path = defaults.string(forKey: modelPathDefaultsKey),
          defaults.string(forKey: modelVariantDefaultsKey) == variant else { return nil }
    let url = path.hasPrefix("/")
        ? URL(fileURLWithPath: path)
        : documentsDirectory.appendingPathComponent(path)
    guard FileManager.default.fileExists(atPath: url.path) else { return nil }
    return url
}
```

And the download routing in `makeEngine()`:

```swift
if let existing = savedModelPath {
    modelURL = existing
    freshDownload = false
} else {
    modelURL = try await downloadModel()
    freshDownload = true
}
```

**Android claims:**
- A v1.2.0 install has exactly one artifact: the installed tiny at the flat path `filesDir/whisper-model/ggml-tiny.en.bin` (the literal `WhisperModelInstaller` writes today: `DIR = "whisper-model"`, `FILE = "ggml-tiny.en.bin"`). The base probe (`base/ggml-base.bin` + size + sha marker) fails on that install → state is not `Ready(Base)` → the download path is taken. Same routing decision as iOS's nil.
- **Deliberate divergence (D3):** on iOS, a pre-base install has *no usable model at all* during the base download — `resolvedModelPath` rejects the tiny record outright and transcription waits on `downloadingModel`. Android's `readyModelPath()` **transitionally accepts the legacy tiny file** (exact-size probe against `77704715L`, the bundled asset's byte size — recorded in config because U10 deletes the asset the installer compared against) so upgraders keep transcribing their backlog during the 148 MB download. This is the plan's "zero no-model window" upgrade decision (Key Technical Decisions; AE2), knowingly *better than* iOS behavior here. The transitional acceptance never satisfies the *base* variant: state reads `Ready(LegacyTiny)`, distinct from `Ready(Base)`, and U10's atomic switch + tiny delete ends the window.

## L3. Download progress is an observable state, not a side channel

**iOS** (`TranscriptionService.swift@9a418e4`):

```swift
enum State: Equatable {
    case idle
    case downloadingModel(progress: Double)
    case transcribing(current: Int, total: Int)
    case completed
    case failed(String)
    ...
}

@MainActor @Published var state: State = .idle
```

fed by:

```swift
private func downloadModel() async throws -> URL {
    try await WhisperKit.download(
        variant: Self.modelVariant,
        from: "argmaxinc/whisperkit-coreml",
        progressCallback: { [weak self] progress in
            Task { @MainActor in
                self?.state = .downloadingModel(progress: progress.fractionCompleted)
            }
        }
    )
}
```

**Android claims:**
- `WhisperModelStore.state: StateFlow<WhisperModelState>` is the `@Published state` analogue — one observable surface the UI (U11) and Settings row read.
- `downloadingModel(progress: Double)` maps to `Downloading(bytesDownloaded: Long, totalBytes: Long)` — a superset: R7 requires *byte* progress surfaced, and the fraction iOS publishes is derivable (`fraction` accessor on the state). The bytes come from the U9 worker's `setProgress`, delivered through the `ModelDownloadWorkSource` seam.
- Android's state machine is **richer than iOS's**, because delivery is a constrained background WorkManager job rather than an immediate foreground URLSession (D4):
  - `Enqueued` / `WaitingUnmetered` — no iOS analogue; WorkManager holds the job until constraints are met, and `WaitingUnmetered` vs `Enqueued` is disambiguated by a `ConnectivityManager` unmetered probe (WorkInfo `ENQUEUED` alone can't distinguish "waiting for Wi-Fi" from "about to start").
  - `Verifying` — no iOS analogue; Android streams SHA-256 against a pinned digest (L5) where iOS trusts WhisperKit's hosted download.
  - `FailedChecksum` / `FailedStorage` — iOS folds every failure into `.failed(String)` prose; Android types the two *user-actionable terminals* (retry vs free-up-space). These arrive **via the work source's outputs** (the U9 worker's outputData), never from the filesystem probe — a checksum failure deletes the partial, so the filesystem alone reads indistinguishable from Absent.
  - Transient network failures are **deliberately not a state** (plan state machine): the worker returns `Result.retry`, WorkManager backs off internally, and the composed flow re-presents `Enqueued`/`WaitingUnmetered` until bytes flow again. iOS has the same shape — a thrown download error clears `modelLoadTask` and the next `ensureModelReady` retries; no dedicated "network failed" case exists in the iOS enum either.
- iOS separates transcription phases (`transcribing/completed`) in the same enum; Android keeps those on the existing `TranscriptionWorker`/pending-row surface — `WhisperModelState` covers only the model-delivery half. The U11 substate matrix joins the two.

## L4. Presence is verified against the filesystem, and never a stored flag

**iOS** (`TranscriptionService.swift@9a418e4`):

```swift
var isModelDownloaded: Bool {
    guard let modelDir = savedModelPath else { return false }
    let files = (try? FileManager.default.contentsOfDirectory(at: modelDir, includingPropertiesForKeys: nil)) ?? []
    return !files.isEmpty
}
```

`resolvedModelPath` likewise ends in `FileManager.default.fileExists(atPath:)` (L2 quote), pinned by `testResolvedModelPath_missingFolder_isNil@9a418e4` — a defaults record whose folder is gone resolves nil.

**Android claims:**
- Android goes further in the same direction (D2): there is **no persisted record at all**. Presence = `base/ggml-base.bin` exists ∧ its byte size equals `147951465L` ∧ the `ggml-base.bin.sha256` marker's content equals the pinned digest. The marker is written by the U9 worker *after* the atomic rename, so "marker present + file missing" (a partial D2D transfer / restore artifact) probes Absent, exactly as iOS's missing-folder case resolves nil.
- Never a DataStore flag — device-to-device transfer and partial restores deliver inconsistent halves (plan Key Technical Decision, carried from the installer's probe philosophy: *"The 'is install needed' probe compares the bundled asset's uncompressed length against the on-disk size"* — `WhisperModelInstaller.kt`).
- The marker holds the digest rather than re-hashing on probe: hashing 148 MB on every state composition is prohibitive; the digest is computed once, streamed during the U9 download, and the marker + exact-size pair makes a truncated or swapped file fail the probe.
- iOS's relative-path storage (container-relocation defense, L2 quote; `testResolvedModelPath_relativePath_resolvesAgainstDocuments@9a418e4`) has the same Android property by construction: nothing is persisted — every probe resolves `WhisperModelConfig` path functions against the *current* `filesDir`.
- iOS's non-empty-directory check maps to the exact-size + digest check — strictly stronger; WhisperKit models are multi-file folders, whisper.cpp is a single `.bin`.

## L5. Where the model comes from, and what "verified" means

**iOS** downloads from WhisperKit's hosted repo (L3 quote: `from: "argmaxinc/whisperkit-coreml"`) and treats a *load* failure as the corruption signal (`makeEngine()@9a418e4`):

```swift
} catch {
    // A model that downloaded but cannot load must never wedge
    // transcription permanently: clear the saved path and remove the
    // folder so the next attempt re-downloads. Transient load
    // failures cost a re-download; a corrupt model costs everything.
    try? FileManager.default.removeItem(at: modelURL)
    savedModelPath = nil
    throw error
}
```

**Android claims (D5):**
- Source: `https://cdn.pilgrimapp.org/models/ggml-base.bin` — the project's own R2 mirror (plan Key Technical Decision; single full URL literal in config per the voice-guide double-path-404 lesson).
- Verification is up-front and cryptographic: expected byte size `147951465L` and SHA-256 `60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe`, read from the upstream `ggerganov/whisper.cpp` Hugging Face repo's Git-LFS pointer for `ggml-base.bin` (`oid sha256:…`, `size 147951465`). U17's release step re-verifies these constants against the actual CDN object at publish time.
- The iOS "corrupt model must never wedge transcription" property lands in two Android places: the U9 worker's checksum-fail → delete-partial → retry (cap → `FailedChecksum` with explicit user retry), and U10's `TranscriptionRunner` model-absent pre-check that re-enqueues the download instead of spinning on `ModelLoadFailed`.

## L6. Single-flight load / unload — mapped, not ported here

**iOS** (`TranscriptionService.swift@9a418e4`):

```swift
/// Single-flight model load (AF31): the post-walk auto-transcription
/// batch and VoiceCard's settings toggle can call this concurrently —
/// the first caller starts the load, every other caller awaits the same
/// task. A failed load clears the gate so the next call retries.
func ensureModelReady() async throws {
    let task = await MainActor.run { () -> Task<Void, Error>? in
        if whisperKit != nil { return nil }
        if let inFlight = modelLoadTask { return inFlight }
        let load = Task { try await self.loadModel() }
        modelLoadTask = load
        return load
    }
    guard let task else { return }
    try await task.value
}
```

```swift
/// Releases the WhisperKit pipeline once a transcription flow drains
/// (AF33) — tens of MB of CoreML state would otherwise stay resident
/// through multi-hour walks. No-op while a batch is active, and leaves
/// `state` untouched so completion/failure UI isn't reset.
@MainActor
func unloadModel() {
    guard !isTranscribing else { return }
    guard let kit = whisperKit else { return }
    whisperKit = nil
    modelLoadTask = nil
    Task.detached { await kit.unloadModels() }
}
```

**Android mapping (no U8 code):**
- `ensureModelReady`'s single-flight *load* is already `WhisperCppEngine.ensureLoaded()` under `nativeLock` (existing); U10 rewires its path resolution through `WhisperModelStore.readyModelPath()`.
- The single-flight *download* half maps to WorkManager unique work with KEEP (U9) — concurrent triggers coalesce onto one worker, exactly the AF31 shape.
- `unloadModels` (AF33 memory release) is the existing engine release path; untouched by U8, revisited in U10.
- `purgeStaleModels(around:)` — iOS's delete-siblings-only-after-the-replacement-is-proven (`testPurgeStaleModels_removesSiblingVariantsOnly@9a418e4`: *"the freshly verified model must never be purged"*) — is U10's tiny-delete-after-verified-base. U8 only guarantees the resolver prefers verified base the moment it exists, and that deletes route through the same `WhisperModelConfig` path functions the probe reads (Stage 5-D write/delete coupling lesson).

## Divergences (conscious) and resolved ambiguities

- **D1 (variant keying):** iOS keys a UserDefaults path record on `modelVariantDefaultsKey`; Android keys the storage directory (`whisper-model/base/`). Same invariant — a model saved for another variant can never satisfy the shipped variant — pinned by the same-shaped tests.
- **D2 (probe over record):** iOS stores a path + variant and verifies the folder exists; Android stores nothing and probes file + exact size + sha marker. Strictly stronger, per the plan's D2D/restore rationale and the installer's precedent.
- **D3 (transitional tiny):** Android serves the legacy tiny (`Ready(LegacyTiny)`, exact-size probe `77704715L` at the flat pre-U8 path) until base verifies; iOS leaves pre-base installs model-less during the download. Deliberate upgrade-path improvement (plan Key Technical Decision, AE2). Never satisfies `Ready(Base)`.
- **D4 (state-machine breadth):** Android adds `Enqueued`/`WaitingUnmetered`/`Verifying`/`FailedChecksum`/`FailedStorage` because delivery is constraint-gated background work with pinned-digest verification; iOS's foreground WhisperKit download needs only `downloadingModel(progress:)` + `.failed(String)`. Transient network failure is not a state on either platform.
- **D5 (hosting + verification):** own CDN + pinned SHA-256/size instead of WhisperKit's hosted repo + load-failure-as-corruption-signal. Digest/size sourced from the upstream Hugging Face LFS pointer; U17 re-pins against the CDN object.
- **D6 (progress unit):** bytes (Long pair) instead of fraction (Double) — R7's byte-progress requirement; fraction derivable.
- **D7 (surface split):** iOS's one enum spans download + transcription phases; Android's `WhisperModelState` covers model delivery only, joined to transcription state by U11's substate matrix.

## Test parity map

| iOS test @9a418e4 | Android test (`WhisperModelStoreTest`) |
|---|---|
| `testShippedVariant_isBase` | `config pins the base variant, published size, and sha` |
| `testResolvedModelPath_matchingVariant_returnsSavedPath` | `verified base file probes Ready Base` + `readyModelPath prefers verified base` |
| `testResolvedModelPath_legacyPathWithoutVariantKey_isNil` | `legacy tiny alone never reads Ready Base` (Android reads `Ready(LegacyTiny)` — D3) |
| `testResolvedModelPath_differentVariant_isNil` | same test — the tiny file cannot satisfy the base probe |
| `testResolvedModelPath_missingFolder_isNil` | `sha marker without the model file probes Absent` |
| `testResolvedModelPath_relativePath_resolvesAgainstDocuments` | by construction (no persisted paths); `readyModelPath` asserted under the current `filesDir` |
| — (WhisperKit hosted download trusted) | `model file with mismatched size probes Absent`, `sha marker mismatch probes Absent` |
| `downloadingModel(progress:)` observability | `Downloading work passes byte progress through`, `Enqueued work with/without unmetered network`, `Verifying`, `Failed(Checksum/Storage)` mapping |
| `testPurgeStaleModels_removesSiblingVariantsOnly` | U10 (tiny delete after atomic switch) |
| `isModelDownloaded` empty-dir false | `clear-app-storage equivalence: empty dir probes Absent with no stuck state` |
