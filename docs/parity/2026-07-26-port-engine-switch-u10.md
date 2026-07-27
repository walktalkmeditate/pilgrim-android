# Engine Switch, Model Swap, and Upgrade Path (U10) — iOS → Android Port Spec

> **Plan:** `docs/plans/2026-07-23-001-feat-ios-v190-parity-port-plan.md` (U10) · **Requirements:** R6, R7, R8
> **iOS pin:** `pilgrim-ios` @ `9a418e4` (tag v1.9.0). All Swift quotes cite `file@9a418e4`. WhisperKit
> quotes cite the exact revision iOS pins in `Package.resolved` @ `9a418e4`:
> `argmaxinc/WhisperKit @ 3199d8f6` (v0.16.0).
> **Authority:** SHIPPED Swift (and the shipped WhisperKit revision's actual defaults) over plan docs.
> Where the plan *expected* one behavior and the shipped dependency does another, the shipped behavior
> wins — see L2, which reverses the plan's "multilingual auto-detect expected" note.
> **Android files:** `audio/WhisperCppEngine.kt`, `audio/TranscriptionRunner.kt`,
> `audio/model/WhisperModelStore.kt` (`onBaseVerified` goes live), `app/src/main/cpp/whisper-jni.cpp`
> (JNI symbol owner moves to the extracted native seam; language pin documented in place);
> DELETED: `audio/WhisperModelInstaller.kt`, `app/src/main/assets/models/ggml-tiny.en.bin`,
> the `noCompress += "bin"` packaging entry.
> **Upstream specs:** U8 `docs/parity/2026-07-26-port-model-state-u8.md` (resolution semantics, D3
> transitional tiny), U9 `docs/parity/2026-07-26-port-model-download-u9.md` (C6 success ordering: the
> sha marker lands, THEN `onBaseVerified()` fires).

## L1. Corrupt-model recovery and sibling purge — what the tiny-delete mirrors

**iOS** (`Pilgrim/Models/TranscriptionService.swift@9a418e4`, `makeEngine()`):

```swift
let config = WhisperKitConfig(
    modelFolder: modelURL.path,
    load: true,
    download: false
)
do {
    let engine = try await WhisperKit(config)
    if freshDownload {
        savedModelPath = modelURL
        Self.purgeStaleModels(around: modelURL)
    }
    return engine
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

```swift
/// Reclaims other variants' model folders (~150 MB for `tiny`) once a
/// fresh model has downloaded AND loaded. Deleting siblings of the
/// verified folder — instead of trusting a stored path — survives
/// container relocation and never removes the working model before its
/// replacement is proven.
static func purgeStaleModels(around modelURL: URL) {
    let parent = modelURL.deletingLastPathComponent()
    let siblings = (try? FileManager.default.contentsOfDirectory(
        at: parent, includingPropertiesForKeys: nil
    )) ?? []
    for sibling in siblings where sibling.lastPathComponent != modelURL.lastPathComponent {
        try? FileManager.default.removeItem(at: sibling)
    }
}
```

Pinned by `UnitTests/TranscriptionServiceModelVariantTests.swift@9a418e4`
(`testPurgeStaleModels_removesSiblingVariantsOnly`: *"the freshly verified model must never be
purged"*).

**Android claims:**

- **Purge-after-proven ⇒ `WhisperModelStore.onBaseVerified()`.** iOS purges siblings only after the
  replacement "has downloaded AND loaded" (proof = WhisperKit constructed from the folder). Android's
  proof is stronger and earlier: the U9 worker verified the full-file SHA-256 against the pinned
  digest and wrote the marker LAST — `onBaseVerified()` fires only after that. The hook re-probes
  `baseVerified()` before deleting, so even a misordered future caller can never delete the only
  working model — the direct analogue of "never removes the working model before its replacement is
  proven". Sequencing invariant: **verified base exists on disk BEFORE the tiny is deleted; there is
  never a no-model window** (better than iOS's D3 gap, where a pre-base install is model-less during
  the entire download).
- **The delete routes through `WhisperModelConfig.legacyTinyPath` — the same path function the
  resolver reads** (write/delete coupling, Stage 5-D lesson). iOS gets the equivalent property by
  deleting *siblings of the verified folder* instead of trusting a stored path; Android has no stored
  path at all (U8 D2), so config-fn coupling is the whole mechanism.
- **Corrupt-model recovery ⇒ two Android places** (carried from U8 L5): the U9 worker's
  checksum-fail → delete-partial → retry (cap → `FailedChecksum`), and this unit's
  `TranscriptionRunner` model-absent pre-check — when `readyModelPath()` is null the runner calls
  `scheduler.ensureEnqueued()` (KEEP — dedupes onto pending work, respects the FAILED/SUCCEEDED
  gate) and returns the existing `ModelLoadFailed` retry signal. The blind "spin on ModelLoadFailed
  until the 5 h backoff exhausts" path becomes self-healing, which is iOS's "must never wedge
  transcription permanently" property. A *genuine* load failure of a model that IS present (native
  init returns 0) still escalates `ModelLoadFailed` unchanged — WorkManager backs off and retries.
- **Load identity is path-keyed.** iOS constructs a fresh WhisperKit per `modelURL`; a variant change
  invalidates the UserDefaults record and forces a new engine. Android's engine keeps one native
  context across a batch, so the loaded state is keyed on the *resolved path*: when
  `readyModelPath()` returns a different path than the one loaded (tiny → base after the switch),
  the engine frees the stale context and loads the new one under the same `nativeLock` that covers
  the JNI call (Stage 2-D lock lesson — the monitor spans ensureLoaded AND nativeTranscribe).
- **The installer dies with the asset.** `WhisperModelInstaller` + the bundled
  `assets/models/ggml-tiny.en.bin` are deleted in the same change, so no call path can throw on the
  missing asset. `WhisperModelConfig.LEGACY_TINY_EXPECTED_BYTES` (77 704 715) is the remaining
  ground truth for the transitional exact-size probe (U8 D3). The `noCompress += "bin"` packaging
  entry existed solely so the installer's `openFd()` could read the bundled asset's real length; no
  other `.bin` ships in assets or res, so the entry goes too.

## L2. Language: iOS forces English on the multilingual base — NOT auto-detect

The plan flagged this as deferred research ("whisper.cpp language parameter for the multilingual
model — pin to iOS's WhisperKit behavior; auto-detect vs forced English", and the U10 approach line
"multilingual auto-detect expected"). The shipped code answers it, and the answer is **forced
English**.

**iOS passes nothing** (`Pilgrim/Models/TranscriptionService.swift@9a418e4`, the only transcribe
call site — no `DecodingOptions`, no language, anywhere in the app; verified by grep over the
`9a418e4` tree for `DecodingOptions` / `language:`):

```swift
func transcribeAudio(atPath path: String) async throws -> TranscriptionOutput {
    let results = try await transcribe(audioPath: path)
    ...
}
```

**What WhisperKit @ `3199d8f6` (v0.16.0, the revision `Package.resolved@9a418e4` pins) does with
nothing:**

`Sources/WhisperKit/Core/TranscribeTask.swift:68` — nil options become the default struct:

```swift
var options = decodeOptions ?? DecodingOptions()
```

`Sources/WhisperKit/Core/Configurations.swift` (init, defaults) — **`detectLanguage` defaults to
`false` because `usePrefillPrompt` defaults to `true`**:

```swift
language: String? = nil,
...
usePrefillPrompt: Bool = true,
usePrefillCache: Bool = true,
detectLanguage: Bool? = nil,
...
self.detectLanguage = detectLanguage ?? !usePrefillPrompt // If prefill is false, detect language by default
```

`Sources/WhisperKit/Core/TranscribeTask.swift:311-313` — the detection pass is gated on that flag,
so it **never runs**:

```swift
// For a multilingual model, if language is not passed and detectLanguage is true, detect language and set in options
if textDecoder.isModelMultilingual, options.language == nil, options.detectLanguage {
    let languageDecodingResult: DecodingResult? = try? await textDecoder.detectLanguage(
```

`Sources/WhisperKit/Core/TextDecoder.swift:321-325` (`prefillDecoderInputs`) +
`Sources/WhisperKit/Core/Models.swift:1539` — the prefill prompt for a multilingual model with nil
language is the **English token**:

```swift
if isModelMultilingual {
    // Set languageToken
    let languageTokenString = "<|\(options.language ?? Constants.defaultLanguageCode)|>"
```

```swift
public static let defaultLanguageCode: String = "en"
```

So iOS v1.9.0's multilingual `base` decodes every recording with the forced prompt
`<|en|>` `<|transcribe|>` — exactly what a caller passing `language: "en"` would get. The
"WhisperKit defaults to auto-detect if nothing is passed" assumption is **false at this revision**:
auto-detect only activates when a caller passes `usePrefillPrompt: false` or
`detectLanguage: true`, and iOS passes neither.

**Android claims:**

- **The JNI wrapper's existing `wparams.language = "en"` is already the parity-correct value and is
  unchanged.** `app/src/main/cpp/whisper-jni.cpp` sets `wparams.translate = false;
  wparams.language = "en";` — the whisper.cpp equivalent of WhisperKit's `<|en|>` +
  `<|transcribe|>` prefill (whisper.cpp builds the same forced prompt from `params.language` for
  multilingual models). No Kotlin/JNI parameter plumbing is needed; the pin is documented at the
  assignment in the C++ so a future "multilingual model, surely auto-detect" impulse hits this
  spec.
- For the record, whisper.cpp's auto-detect trigger exists and is deliberately NOT used
  (`app/src/main/cpp/whisper/src/whisper.cpp`, `whisper_full_with_state`):

  ```c
  // auto-detect language if not specified
  if (params.language == nullptr || strlen(params.language) == 0 || strcmp(params.language, "auto") == 0 || params.detect_language) {
  ```

  Flipping to `"auto"` would (a) diverge from shipped iOS, and (b) be actively broken during the
  transitional window: the legacy `tiny.en` is English-only, its vocab has no trained language
  tokens, and the vendored auto-detect path has **no multilingual guard** — it would rank garbage
  logits and can poison the decode. Forced `"en"` is correct for both models the engine can load.
- If a future iOS release opts into auto-detect, that is a new headline behavior change requiring
  its own re-triage — not a U10 follow-up.

## L3. Single-flight load/unload — the U8 L6 mapping goes live

U8 L6 mapped iOS's `ensureModelReady` (single-flight load, AF31) and `unloadModel` (AF33 release)
onto the existing `WhisperCppEngine.ensureLoaded()` / `unloadModel()` and deferred the rewiring to
U10. What changes here:

- `ensureLoaded()` no longer calls the installer; the caller resolves
  `WhisperModelStore.readyModelPath()` (suspend, IO) **before** entering the monitor — suspending
  while holding `nativeLock` is forbidden — then the monitor covers load-if-needed + the
  `nativeTranscribe` JNI call as one critical section, exactly the Stage 2-D discipline.
- `readyModelPath() == null` → `Result.failure(WhisperError.ModelLoadFailed)` from the engine (the
  existing contract), but in practice the runner's pre-check fires first and pairs the failure with
  a download re-enqueue (L1).
- Benign race, accepted: a resolve can return the tiny path immediately before `onBaseVerified()`
  deletes it. If the tiny was already loaded, the in-memory context keeps working (the file is not
  re-read after init); if the load races the delete, native init fails → `ModelLoadFailed` →
  WorkManager backoff → the retry resolves base. One-shot, self-healing, not worth cross-locking
  the store and the engine.
- `unloadModel()` additionally clears the loaded-path key so the next batch re-resolves fresh.

## Divergences (conscious) and resolved ambiguities

- **D1 (purge trigger):** iOS purges siblings inside `makeEngine()` after a fresh download *loads*;
  Android purges inside `onBaseVerified()` after the download *cryptographically verifies* (U9 C4).
  Both fire exactly once per delivery, both only after the replacement is proven; Android's proof
  does not require an engine load because the SHA-256 already proves byte identity.
- **D2 (purge scope):** iOS deletes *all* sibling folders under the models parent; Android deletes
  exactly the one legacy artifact that can exist (`whisper-model/ggml-tiny.en.bin`). The Android
  layout (U8 D1) has no other siblings by construction — the base lives in its own variant
  directory the tiny never occupied.
- **D3 (language pin source):** iOS's forced-English is an *implicit* consequence of WhisperKit
  0.16.0 defaults; Android's is an *explicit* `wparams.language = "en"`. Same decode prompt. The
  plan's "auto-detect expected" note is superseded by this spec (shipped-code authority).
- **D4 (no-model behavior):** iOS blocks transcription on `downloadingModel` state until WhisperKit
  delivers (foreground download); Android returns `Result.retry` through the existing
  `transcribe-walk-<id>` work while the constrained background download proceeds, and the U9 C6
  re-kick with REPLACE breaks the backoff the moment the model lands. Upgraders additionally keep
  transcribing on the tiny the whole time (U8 D3) — a window iOS spends model-less.

## Test parity map

| iOS test / property @9a418e4 | Android test |
|---|---|
| `testPurgeStaleModels_removesSiblingVariantsOnly` ("freshly verified model must never be purged") | `WhisperModelStoreTest`: `onBaseVerified deletes the legacy tiny through the config path` + `onBaseVerified without a verified base never deletes the tiny` |
| `makeEngine()` corrupt-model → re-download routing | `TranscriptionRunnerTest`: model absent + pending → `ensureEnqueued()` recorded (KEEP gate) + `ModelLoadFailed` retry result; present-model load failure → `ModelLoadFailed` with NO re-enqueue |
| resolution feeds the engine (U8 L2/L6) | `WhisperCppEngineTest`: base Ready → native init with base path; tiny-only → tiny path; both absent → `ModelLoadFailed`, native never touched |
| fresh engine per model identity | `WhisperCppEngineTest`: path change frees the stale context and re-inits; same path re-used across batches loads once |
| AF33 unload | `WhisperCppEngineTest`: unload frees + next transcribe reloads (existing runner unload tests unchanged) |
| upgrade path (plan AE2) | `ModelUpgradePathTest`: flat-path tiny, no bundled asset → batch transcribes on tiny → base verifies → tiny gone → next batch on base; first batch's rows intact |
| forced-English pin (L2) | documented at `wparams.language = "en"` in `whisper-jni.cpp` + this spec; no Kotlin surface exists to unit-test (native param, device QA covers decode) |
| no installer remains | compile proof: `WhisperModelInstaller.kt` deleted, all references removed — any survivor fails the build |
