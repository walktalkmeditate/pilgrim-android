# Parity Spec: walk-share-interactive

| field | value |
|---|---|
| **iOS pin** | `3f9f9e8` (main, per CLAUDE.md) |
| **Android HEAD** | `9bc3fa10` |
| **Generated** | 2026-08-15 |
| **Type** | port |
| **Generator** | ios-parity skill |
| **Fold-in** | body cites `3f9f9e8` (verified there; permanent ancestor); pin now `2ee1185` — delta covered in §9 |

**Finding provenance.** 437 lens findings across four lenses — behavior (101), ui-visual (80), data (74), edge-cases (182). Every row below is tagged `BEH-n` / `UI-n` / `DAT-n` / `EDG-n`. Rows tagged **[consensus: …]** were independently produced by more than one lens against the same citation or near-identical claim.

**Quote convention.** Quotes are verbatim. Inside markdown tables, a line break in the original is rendered as `⏎` and a literal `|` is escaped as `\|`; nothing else is altered.

---

## 1. iOS source map

- `Pilgrim/Models/Share/RouteDownsampler.swift` — 105 LOC — Ramer-Douglas-Peucker route simplification with epsilon binary search and a uniform-stride backstop, capping shared route geometry at 200 points.
- `Pilgrim/Models/Share/RouteTrimmer.swift` — 40 LOC — Shaves N metres of walked distance off each end of a route (doorstep privacy), plus a `canTrim` predicate defined by re-running `trim`.
- `Pilgrim/Models/Share/SharePayload.swift` — 131 LOC — Encodable-only wire model for the share POST body: stats, route, intervals, waypoints, photos, tour, pauses, with snake_case `CodingKeys`.
- `Pilgrim/Models/Share/ShareService.swift` — 421 LOC — Networking + local persistence layer: share POST, media PUTs, device token, cached-share record, failed-media repair record, background-assertion lifecycle, retry policy.
- `Pilgrim/Models/Share/TourBuilder.swift` — 116 LOC — Builds tour recording candidates from a walk's voice recordings (availability, auto-kind classification, caps validation) and collapses them into the wire `Tour` + a parallel upload file list.
- `Pilgrim/Models/Share/TourPhotoExporter.swift` — 140 LOC — Hi-res (1600px) PhotoKit export with a JPEG quality ladder under a 2 MB cap, bounded by a dual-deadline (20 s cancel + 2 s backstop) continuation.
- `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift` — 205 LOC — The "Walk with me" disclosure: Interactive toggle, per-recording include/kind rows, totals caption, validation error, trim toggle.
- `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift` — 223 LOC — The 8-state status/progress card: Share button, progress rows, dropped-photos consent prompt, shared card, partial/repair block, error state.
- `Pilgrim/Scenes/WalkShare/WalkShareView.swift` — 428 LOC — Screen scaffold: stat toggles, journal field, expiry picker, toolbar/dismiss gating, ritual reveal, podcast card reveal, preview cover lifecycle.
- `Pilgrim/Scenes/WalkShare/WalkShareViewModel+ShareOrchestration.swift` — 415 LOC — The whole share/retry orchestration: task dedup lock, photo export, cancellation checkpoint, POST, media upload, repair-record lifecycle, retry resolution.
- `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift` — 499 LOC — State + payload assembly: `ShareState`, `ExpiryOption`, cache restoration in `init`, candidate toggles, geocoding, classic photo path, route trim/downsample, stat formatting.
- `UnitTests/RouteTrimmerTests.swift` — 104 LOC — Pins the 4× floor, degenerate point counts, clustered-endpoint collision, and `canTrim`/`trim` agreement.
- `UnitTests/ShareMediaUploadTests.swift` — 71 LOC — Pins media PUT request shape/timeout, failed-media round-trip + prune, background-exhaustion skip accounting.
- `UnitTests/SharePayloadTourTests.swift` — 72 LOC — Pins snake_case wire names and the omit-key-when-nil encoding contract.
- `UnitTests/TourBuilderTests.swift` — 161 LOC — Pins classification thresholds, cap boundaries, dense renumbering, blip exclusion, candidate sorting.
- `UnitTests/TourPhotoExporterTests.swift` — 35 LOC — Pins the JPEG quality ladder's real-world size envelope with an adversarial 1600px image.
- `UnitTests/WalkShareInteractiveTests.swift` — 569 LOC — The behavioural spec for the whole slice: trim honesty, exclusion zero-trace, retry identity resolution, restoration paths, synchronous state claims.
- `UnitTests/Helpers/WalkDataFactory.swift` — 130 LOC — Shared fixture factory for walks and voice recordings (defaults are load-bearing traps — see EDG-181/EDG-182).

---

## 2. Behavior

101 behavior-lens findings, grouped by file. Cross-lens consensus and the edge-case findings that reinforce each row are tagged inline.

### 2.1 `Pilgrim/Models/Share/RouteTrimmer.swift`

**BEH-1 · state-machine — `RouteTrimmer.trim` only shortens a route when the total walked distance is at least 4× the requested trim distance; shorter walks share fully untrimmed.** `Pilgrim/Models/Share/RouteTrimmer.swift:5-15@3f9f9e8` — **[consensus: behavior + data (DAT-4) + edge-cases (EDG-17, EDG-18, EDG-20)]**
```swift
/// Shaves `meters` of walked distance off each end of the route so a
    /// shared page never reveals a doorstep. Walks shorter than 4x the trim
    /// distance share untrimmed — mid-walk geometry is all they have.
    static func trim(_ route: [SharePayload.RoutePoint], meters: Double) -> [SharePayload.RoutePoint] {
        guard meters > 0, route.count > 3 else { return route }
        var cumulative: [Double] = [0]
        for i in 1..<route.count {
            cumulative.append(cumulative[i - 1] + haversineMeters(route[i - 1], route[i]))
        }
        let total = cumulative[route.count - 1]
        guard total >= meters * 4 else { return route }
```
*Drift risk:* A port could parameterize trim without the 4x floor, or could compute canTrim/trim independently and let them disagree on borderline routes.

**BEH-2 · state-machine — `canTrim` is not an independent estimate; it re-runs `trim` and compares output length, guaranteeing it can never disagree with what `trim` actually does.** `Pilgrim/Models/Share/RouteTrimmer.swift:25-29@3f9f9e8` — **[consensus: behavior + data (DAT-5) + edge-cases (EDG-24, EDG-25)]**
```swift
/// Whether trim can actually apply to the route — the UI uses this to show
    /// "too short to trim" instead of silently promising protection.
    static func canTrim(_ route: [SharePayload.RoutePoint], meters: Double) -> Bool {
        trim(route, meters: meters).count < route.count
    }
```
*Drift risk:* A separate length/threshold heuristic in canTrim (instead of delegating to trim) would create exactly the two-different-arrays divergence class this file was written to avoid.

### 2.2 `Pilgrim/Models/Share/ShareService.swift`

**BEH-3 · async-sync-point — The share POST uses a flat 30-second request timeout.** `Pilgrim/Models/Share/ShareService.swift:44-50@3f9f9e8` — **[consensus: behavior + data (DAT-21) + edge-cases (EDG-35)]**
```swift
static func share(payload: SharePayload) async throws -> ShareResult {
        let url = URL(string: "\(baseURL)/api/share")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(deviceToken(), forHTTPHeaderField: "X-Device-Token")
        request.timeoutInterval = 30
```
*Drift risk:* An OkHttp/Retrofit client defaults to different connect/read/write timeout semantics than URLRequest.timeoutInterval; a literal 30s copy on the wrong timeout dimension changes failure behavior on slow networks.

**BEH-4 · transition — HTTP 429 on the share POST maps to a specific `rateLimited` error distinct from generic server errors.** `Pilgrim/Models/Share/ShareService.swift:69-71@3f9f9e8` — **[consensus: behavior + data (DAT-41) + edge-cases (EDG-36)]**
```swift
if httpResponse.statusCode == 429 {
            throw ShareError.rateLimited
        }
```
*Drift risk:* A generic non-2xx handler that doesn't special-case 429 would surface the wrong user-facing message ("You've shared too many walks today" vs a generic server error).

**BEH-5 · transition — Any non-2xx response outside 429 decodes an optional server error message and falls back to "Unknown error" if decoding fails.** `Pilgrim/Models/Share/ShareService.swift:73-77@3f9f9e8` — **[consensus: behavior + data (DAT-41)]**
```swift
guard (200...299).contains(httpResponse.statusCode) else {
            let message = (try? JSONDecoder().decode(ErrorResponse.self, from: data))?.error
                ?? "Unknown error"
            throw ShareError.serverError(httpResponse.statusCode, message)
        }
```
*Drift risk:* A port that throws on undecodable error bodies instead of falling back to a placeholder string would crash the error path instead of degrading gracefully.

**BEH-6 · restoration-path — `cachedShare(for:)` reads a UserDefaults dictionary keyed by walk UUID and reconstructs a `CachedShare` only if url, id, and a parseable ISO8601 expiry are all present; `shareDate` and `expiryOption` are optional.** `Pilgrim/Models/Share/ShareService.swift:98-117@3f9f9e8` — **[consensus: behavior + data (DAT-23)]**
```swift
static func cachedShare(for walkID: UUID) -> CachedShare? {
        guard let dict = UserDefaults.standard.dictionary(forKey: "share:\(walkID.uuidString)"),
              let url = dict["url"] as? String,
              let id = dict["id"] as? String,
              let expiryStr = dict["expiry"] as? String,
              let expiry = isoFormatter.date(from: expiryStr) else {
            return nil
        }

        let shareDate = (dict["shareDate"] as? String).flatMap { isoFormatter.date(from: $0) }
        let expiryOption = dict["expiryOption"] as? String

        return CachedShare(
            url: url,
            id: id,
            expiry: expiry,
            shareDate: shareDate,
            expiryOption: expiryOption
        )
    }
```
*Drift risk:* This is the read side of the restoration path WalkShareViewModel.init depends on; any missing/malformed field must fail closed (return nil, i.e. not-shared) rather than partially hydrate a CachedShare.

**BEH-7 · restoration-path — `cacheShare` computes expiry from `expiryDays` added to now and always writes `shareDate`; `expiryOption` is only written when non-nil.** `Pilgrim/Models/Share/ShareService.swift:119-132@3f9f9e8` — **[consensus: behavior + data (DAT-24), identical citation]**
```swift
static func cacheShare(_ result: ShareResult, walkID: UUID, expiryDays: Int, expiryOption: String?) {
        let now = Date()
        let expiry = Calendar.current.date(byAdding: .day, value: expiryDays, to: now) ?? now
        var dict: [String: String] = [
            "url": result.url,
            "id": result.id,
            "expiry": isoFormatter.string(from: expiry),
            "shareDate": isoFormatter.string(from: now),
        ]
        if let expiryOption {
            dict["expiryOption"] = expiryOption
        }
        UserDefaults.standard.set(dict, forKey: "share:\(walkID.uuidString)")
    }
```
*Drift risk:* The per-walk UserDefaults key namespace ("share:<uuid>") is the whole persistence contract for restoration; a differently-shaped Android DataStore key or schema breaks cross-launch share-state recall.

**BEH-8 · async-sync-point — Media PUT requests use a 30-second idle timeout, deliberately documented as resetting on bytes moving (not a whole-upload budget).** `Pilgrim/Models/Share/ShareService.swift:146-157@3f9f9e8` — **[consensus: behavior + data (DAT-25) + edge-cases (EDG-42)]**
```swift
static func mediaUploadRequest(shareID: String, kind: MediaKind, n: Int, contentLength: Int) -> URLRequest {
        let url = URL(string: "\(baseURL)/api/share/\(shareID)/\(kind.rawValue)/\(n)")!
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(kind == .audio ? "audio/mp4" : "image/jpeg", forHTTPHeaderField: "Content-Type")
        request.setValue("\(contentLength)", forHTTPHeaderField: "Content-Length")
        request.setValue(deviceToken(), forHTTPHeaderField: "X-Device-Token")
        // Idle timeout — resets on bytes moving, so slow uploads survive;
        // stalls fail fast so the repair path picks them up instead of
        // burning background time on a connection that's already dead.
        request.timeoutInterval = 30
        return request
    }
```
*Drift risk:* OkHttp's readTimeout is per-read-call idle-style (matches this contract) but writeTimeout/callTimeout are not — picking the wrong OkHttp timeout knob silently changes whether a slow-but-alive upload survives.

**BEH-9 · transition — Media upload ordering is a hard contract: photos upload before audio because photos gate the keepsake render window, and uploads are strictly sequential (not concurrent) to keep memory flat for large audio files.** `Pilgrim/Models/Share/ShareService.swift:160-166@3f9f9e8` — **[consensus: behavior + data (DAT-30) + edge-cases (EDG-43)]**
```swift
/// Sequential by contract: photos MUST land in index order (enrich
    /// HEADs only the last one), and one-at-a-time keeps memory flat for
    /// 15MB audio files. PHOTOS UPLOAD FIRST — they gate the keepsake
    /// render window; audio degrades gracefully to "voice unavailable".
    /// Each item gets one retry. Runs inside a background-task assertion
    /// so pocketing the phone doesn't kill the remaining PUTs. Returns
    /// the indices (1-based, per kind) that ultimately failed.
```
*Drift risk:* A coroutine port that fires photo and audio uploads concurrently (or in reverse order) for throughput would silently break the worker's index-ordering assumption and the keepsake-render gating.

**BEH-10 · transition — Before starting each photo PUT, `uploadAllMedia` checks background-time exhaustion; if exhausted it marks the whole remaining tail of that loop as failed without attempting a network call, and advances `completed` by exactly the skipped count (not the grand total) so the audio loop's own accounting isn't overshot.** `Pilgrim/Models/Share/ShareService.swift:184-194@3f9f9e8` — **[consensus: behavior + data (DAT-31) + edge-cases (EDG-45)]**
```swift
for (index, data) in photos.enumerated() {
                // Don't start a PUT the OS is about to kill mid-flight — the
                // repair record turns the untried tail into a Carry-the-
                // missing-files offer instead of a truncated upload.
                if await backgroundTimeExhausted() {
                    for remaining in index..<photos.count { failures.append((.photos, remaining + 1)) }
                    // Bounded to what THIS loop still owes, not the grand total — jumping straight to `total` would let `completed` overshoot if the app foregrounds before the audio loop below and that one finishes normally.
                    completed += photos.count - index
                    report()
                    break
                }
```
*Drift risk:* A port that jumps `completed` straight to `total` on exhaustion (instead of the bounded per-loop increment) will overshoot the progress count if the app foregrounds and the second loop later succeeds normally.

**BEH-11 · transition — Each photo gets one `putWithRetry` attempt; on success the caller is notified via `onItemSuccess` (which prunes the failed-media cache), on failure the item is appended to the failures list, and progress is reported after every item.** `Pilgrim/Models/Share/ShareService.swift:195-203@3f9f9e8` — **[consensus: behavior + data (DAT-31) + edge-cases (EDG-44)]**
```swift
let ok = await putWithRetry(shareID: shareID, kind: .photos, n: index + 1) { data }
                if ok {
                    onItemSuccess?(.photos, index + 1)
                } else {
                    failures.append((.photos, index + 1))
                }
                completed += 1
                report()
            }
```
*Drift risk:* onItemSuccess firing per-item (not just at the end) is what lets pruneFailedMedia shrink the repair record incrementally during a long upload — a batched success callback would lose that crash-safety property.

**BEH-12 · transition — The audio loop mirrors the photos loop's background-exhaustion short-circuit and bounded accounting exactly.** `Pilgrim/Models/Share/ShareService.swift:205-215@3f9f9e8` — **[consensus: behavior + data (DAT-32) + edge-cases (EDG-46)]**
```swift
for (index, fileURL) in audioFiles.enumerated() {
                if await backgroundTimeExhausted() {
                    for remaining in index..<audioFiles.count { failures.append((.audio, remaining + 1)) }
                    // Same reasoning as the photos loop above.
                    completed += audioFiles.count - index
                    report()
                    break
                }
                let ok = await putWithRetry(shareID: shareID, kind: .audio, n: index + 1) {
                    try Data(contentsOf: fileURL)
                }
```
*Drift risk:* Audio file bytes are read lazily inside the retry closure (try Data(contentsOf:)), not eagerly before the loop — a port that reads all audio files upfront changes memory behavior for large multi-recording walks.

**BEH-13 · dispatcher — The background-state check is an injectable seam typed as a MainActor-isolated closure, not a direct `UIApplication` read, specifically so tests can force background state deterministically.** `Pilgrim/Models/Share/ShareService.swift:260-267@3f9f9e8` — **[consensus: behavior + data (DAT-34) + edge-cases (EDG-47)]**
```swift
nonisolated(unsafe) static var backgroundStateProvider: @MainActor () -> (isBackground: Bool, remaining: TimeInterval) = {
        (UIApplication.shared.applicationState == .background, UIApplication.shared.backgroundTimeRemaining)
    }
```
*Drift risk:* Without an equivalent injectable seam, an Android port can't deterministically unit-test the app-backgrounded-with-low-time-remaining path; it also has no direct OS analogue to `backgroundTimeRemaining` (needs a different signal, e.g. a scheduled foreground-service teardown deadline).

**BEH-14 · async-sync-point — `backgroundTimeExhausted()` is true only once the app is backgrounded AND has under 10 seconds of background time remaining — deliberately not the full ~30 s grant, to abandon the last ~10 s rather than start a PUT that certainly gets killed mid-flight.** `Pilgrim/Models/Share/ShareService.swift:269-283@3f9f9e8` — **[consensus: behavior + data (DAT-34) + edge-cases (EDG-48)]**
```swift
/// True once the OS is about to suspend the app mid-background-task: a
    /// PUT started now could be killed with the connection half-open, which
    /// the worker would just see as a dropped upload — better to never start
    /// it and let the repair record ("Carry the missing files") offer it
    /// once Pilgrim is foreground again. iOS grants ~30s of background time
    /// total — a threshold at or above that grant is always-true the instant
    /// the app backgrounds, abandoning the usable ~25s before it. 10s lets
    /// small items still proceed and only stops near true exhaustion; the
    /// real fix is a background URLSession (scheduled fast-follow).
    private static func backgroundTimeExhausted() async -> Bool {
        await MainActor.run {
            let state = backgroundStateProvider()
            return state.isBackground && state.remaining < 10
        }
    }
```
*Drift risk:* The 10-second threshold and "only true while backgrounded" condition are both load-bearing magic numbers with no Android OS equivalent (foreground service with START_STICKY doesn't get killed the same way) — a naive port might drop the check entirely or misapply it to a WorkManager expedited-job budget.

**BEH-15 · restoration-path — A failed media item's identity is NOT just (kind, n) — it also carries the source file's stable identity (recording `startTs`, or photo `localIdentifier`+`ts`) so a later retry can verify it's about to upload the right bytes even if the local candidate list has shifted since the original share.** `Pilgrim/Models/Share/ShareService.swift:285-300@3f9f9e8` — **[consensus: behavior + data (DAT-28) + edge-cases (EDG-49)]**
```swift
/// A failed upload's slot (`kind`/`n`, the PUT index the worker is still
/// missing) plus the STABLE identity of the file it was meant to carry.
/// `n` alone isn't safe to retry against later: the local candidate list
/// an index was drawn from can shift (an export drop, an unpin) between
/// the original share and a retry, so the caller resolving this cache
/// must verify identity (recording `startTs`, or photo `localIdentifier`
/// + captured `ts`) before uploading anything under `n` again — see
/// `WalkShareViewModel.resolveRetryItems`. `kind` is a raw string, not
/// `MediaKind`, so this format doesn't depend on that enum's cases.
struct FailedMediaItem: Codable, Equatable {
    let kind: String
    let n: Int
    let audioStartTs: Int?
    let photoLocalID: String?
    let photoTs: Int?
}
```
*Drift risk:* A port that re-derives the retry item list by array index instead of matching stable identity fields could upload the wrong file's bytes under a stale slot number after the local candidate set changes.

**BEH-16 · restoration-path — The failed-media repair record is stored as its own UserDefaults JSON blob keyed by walk UUID, separate from the cached share; writing an empty array removes the key entirely rather than storing an empty array.** `Pilgrim/Models/Share/ShareService.swift:302-320@3f9f9e8` — **[consensus: behavior + data (DAT-29) + edge-cases (EDG-50)]**
```swift
static func cacheFailedMedia(_ failures: [FailedMediaItem], walkID: UUID) {
        let key = "share-failed-media:\(walkID.uuidString)"
        if failures.isEmpty {
            UserDefaults.standard.removeObject(forKey: key)
        } else if let data = try? JSONEncoder().encode(failures) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    static func failedMedia(for walkID: UUID) -> [FailedMediaItem] {
        let key = "share-failed-media:\(walkID.uuidString)"
        guard let data = UserDefaults.standard.data(forKey: key),
              let items = try? JSONDecoder().decode([FailedMediaItem].self, from: data) else { return [] }
        return items
    }
```
*Drift risk:* WalkShareViewModel.init determines .partial vs .success purely from `failedMedia(for:).count > 0` — an Android store that leaves an empty-array row instead of deleting it is harmless only if the count check treats both identically, but a differently-keyed store risks losing the record across app restarts.

**BEH-17 · dispatcher — `withBackgroundAssertion` begins a `UIApplication` background task on the MainActor, races a normal completion path against the OS expiration handler using a lock-protected one-shot guard, and ends the task exactly once from whichever side wins.** `Pilgrim/Models/Share/ShareService.swift:322-370@3f9f9e8` — **[consensus: behavior + data (DAT-36) + edge-cases (EDG-51)]**
```swift
static func withBackgroundAssertion<T: Sendable>(
        named name: String,
        _ body: () async -> T
    ) async -> T {
        let state = OSAllocatedUnfairLock(initialState: BackgroundAssertionState())

        func endOnce() -> UIBackgroundTaskIdentifier? {
            state.withLock { s in
                guard !s.ended, s.identifier != .invalid else { return nil }
                s.ended = true
                return s.identifier
            }
        }

        await MainActor.run {
            let identifier = UIApplication.shared.beginBackgroundTask(withName: name) {
                // Apple's documented contract: the expiration handler runs
                // on the main thread already, so assert isolation instead
                // of hopping through a Task — the app may already be
                // suspending by the time this fires.
                guard let idToEnd = endOnce() else { return }
                MainActor.assumeIsolated {
                    UIApplication.shared.endBackgroundTask(idToEnd)
                }
            }
            state.withLock { $0.identifier = identifier }
        }

        let result = await body()

        if let idToEnd = endOnce() {
            await MainActor.run {
                UIApplication.shared.endBackgroundTask(idToEnd)
            }
        }

        return result
    }
```
*Drift risk:* This is the whole background-assertion lifecycle (begin/end/expiration) the task explicitly calls out as a drift archetype. Android has no UIApplication.beginBackgroundTask analogue — a foreground service or expedited WorkManager job needs its own one-shot completion guard, and any port that forgets the double-completion race (normal finish vs OS-driven expiration) risks calling a service-stop equivalent twice or never.

**BEH-18 · async-sync-point — `putWithRetry` allows at most 2 attempts per media item, re-checks background exhaustion between attempts (not just once before the call), and sleeps 800 ms before the single retry.** `Pilgrim/Models/Share/ShareService.swift:377-411@3f9f9e8` — **[consensus: behavior + data (DAT-35), identical citation; + edge-cases EDG-52, EDG-53, EDG-54]**
```swift
private static func putWithRetry(
        shareID: String,
        kind: MediaKind,
        n: Int,
        body: () throws -> Data
    ) async -> Bool {
        var lastError: Error?
        for attempt in 0..<2 {
            do {
                let data = try body()
                let request = mediaUploadRequest(shareID: shareID, kind: kind, n: n, contentLength: data.count)
                let (_, response) = try await URLSession.shared.upload(for: request, from: data)
                if let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) {
                    return true
                }
            } catch {
                lastError = error
            }
            if attempt == 0 {
                // A single item's own attempt-plus-retry cycle can burn up to
                // ~60s of request timeouts on its own — re-check here, not
                // just once per item before this call, so a slow first
                // attempt can't blow through the remaining background grant
                // before the retry even starts.
                if await backgroundTimeExhausted() {
                    return false
                }
                try? await Task.sleep(nanoseconds: 800_000_000)
            }
        }
        return false
    }
```
*Drift risk:* The 800ms backoff and the mid-retry-cycle exhaustion re-check are both easy to drop in a straightforward "retry once" port, and the comment's ~60s worst-case-per-item math (2 attempts × up to 30s timeout) is a hidden dependency other timing constants must stay consistent with.

### 2.3 `Pilgrim/Models/Share/TourBuilder.swift`

**BEH-19 · state-machine — A tour recording candidate has exactly three unavailability states, computed once at candidate-build time: available (nil reason), "audio removed" (file missing or zero bytes), or "too large to carry" (over the 15 MB per-file cap).** `Pilgrim/Models/Share/TourBuilder.swift:55-77@3f9f9e8` — **[consensus: behavior + data (DAT-49), identical citation; + ui-visual UI-74; + edge-cases EDG-67, EDG-68]**
```swift
let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? Int
            let unavailableReason: String?
            if size == nil || size == 0 {
                unavailableReason = "audio removed"
            } else if let size, size > maxFileBytes {
                unavailableReason = "too large to carry"
            } else {
                unavailableReason = nil
            }
```
*Drift risk:* unavailableReason gates both includeInShare's default and whether toggleInclude/flipKind are allowed to touch the candidate at all — collapsing the three states into a boolean would lose the distinct copy shown in InteractiveShareSection's TourRecordingRow.

**BEH-20 · state-machine — The tour aggregate caps at the pin are 12 recordings, 15 MB/file, 60 MB total, and `maxTotalSeconds = 6480` (108 minutes).** `Pilgrim/Models/Share/TourBuilder.swift:24-27@3f9f9e8` — **[consensus: behavior + data (DAT-43), identical citation; + edge-cases EDG-56..EDG-59]**
```swift
static let maxRecordings = 12
static let maxFileBytes = 15 * 1024 * 1024
static let maxTotalBytes = 60 * 1024 * 1024
static let maxTotalSeconds: Double = 6480  // 108 minutes — the eternal cairn's number
```
*Drift risk:* The cap was 2700s until iOS PR #60 raised it to 6480s at this pin — an Android port copying an older figure (or the origin doc's 45-min text) would reject tours iOS accepts; derive copy and validation from one constant.

**BEH-21 · transition — `validationError` checks three independent caps in order (count > 12, bytes > 60 MB, seconds > 6480) and returns the first violation's message; this return value is what gates the Share button via `canShare`.** `Pilgrim/Models/Share/TourBuilder.swift:88-94@3f9f9e8` — **[consensus: behavior + data (DAT-50); + ui-visual UI-73; + edge-cases EDG-69, EDG-70]**
```swift
static func validationError(for candidates: [TourRecordingCandidate]) -> String? {
        let (count, bytes, seconds) = totals(of: candidates)
        if count > maxRecordings { return "A walk page carries at most \(maxRecordings) recordings — leave some out." }
        if bytes > maxTotalBytes { return "Recordings total \(bytes / 1_048_576) MB — the page carries at most 60 MB." }
        if seconds > maxTotalSeconds { return "Recordings total \(Int(seconds / 60)) minutes — the page carries at most \(Int(maxTotalSeconds / 60))." }
        return nil
    }
```
*Drift risk:* totals(of:) (not shown here) only sums candidates where includeInShare && unavailableReason == nil — a port that sums ALL candidates regardless of inclusion/availability would false-positive the cap on walks with many excluded or unavailable recordings.

**BEH-22 · transition — `tourItems` deliberately strips transcription to nil on every recording sent to the server and renumbers included candidates 1-based by their filtered position, not their original candidate id.** `Pilgrim/Models/Share/TourBuilder.swift:96-114@3f9f9e8` — **[consensus: behavior + data (upload-contract, TourBuilder.swift:96-115); + edge-cases EDG-71, EDG-72, EDG-73]**
```swift
static func tourItems(candidates: [TourRecordingCandidate], trimM: Int) -> (tour: SharePayload.Tour, files: [URL]) {
        let included = candidates.filter { $0.includeInShare && $0.unavailableReason == nil && $0.fileURL != nil }
        let recordings = included.enumerated().map { index, c in
            SharePayload.TourRecording(
                n: index + 1,
                startTs: c.startTs,
                endTs: c.endTs,
                duration: c.duration,
                kind: c.effectiveKind.rawValue,
                // Transcripts never leave the device: the page renders none, and
                // a 108-minute walk's transcripts would blow the 2MB POST budget.
                // Deliberate — do not wire c.transcription through.
                transcription: nil,
                wpm: c.wpm,
                sizeBytes: c.sizeBytes
            )
        }
        let files = included.compactMap(\.fileURL)
        return (SharePayload.Tour(recordings: recordings, trimM: trimM), files)
    }
```
*Drift risk:* `files` is built from the exact same `included` filter/order as `recordings`, guaranteeing index-for-index alignment between the tour manifest and the upload file list; a port that derives the two arrays from separately-filtered sources risks a manifest/upload mismatch.

### 2.4 `Pilgrim/Models/Share/TourPhotoExporter.swift`

**BEH-23 · async-sync-point — Each hi-res photo export is bounded by two independent deadlines: a 20-second PhotoKit-cancel nudge and a 22-second (20+2) hard backstop that force-resumes with nil even if PhotoKit never calls back at all.** `Pilgrim/Models/Share/TourPhotoExporter.swift:18-21@3f9f9e8` — **[consensus: behavior + data (DAT-51), identical citation; + edge-cases EDG-74, EDG-75, EDG-80]**
```swift
static let maxBytes = 2 * 1024 * 1024
    static let targetPixels: CGFloat = 1600
    static let perPhotoTimeout: TimeInterval = 20
    static let backstopGrace: TimeInterval = 2
```
*Drift risk:* Two separate literal timing constants (20s, 2s grace) with different jobs — a port that collapses them into one timeout loses the "nudge, then force-resume even if the nudge itself never resolves" guarantee against wedged iCloud requests.

**BEH-24 · dispatcher — `TourPhotoExporter.export`'s progress callback fires after every photo from whatever executor happens to be running (nonisolated async function), never guaranteed to be the main actor — explicitly unlike its sibling `WalkPhotoMatcher.findCandidates`, whose completion is always main-thread.** `Pilgrim/Models/Share/TourPhotoExporter.swift:35-41@3f9f9e8` — **[consensus: behavior + data (DAT-54) + edge-cases (EDG-77)]**
```swift
/// `progress` fires after every photo, off the main thread — `export` is a
/// nonisolated async function and reports from whatever executor happens to
/// be running when the current photo finishes, never the main actor. This
/// differs from the sibling `WalkPhotoMatcher.findCandidates`, whose
/// `completion` closure is always delivered on the main thread. Callers that
/// update UI from `progress` must hop to the MainActor themselves.
static func export(_ candidates: [PhotoCandidate], progress: @escaping (Int, Int) -> Void) async -> [TourPhoto] {
```
*Drift risk:* Callers (WalkShareViewModel+ShareOrchestration.share()) rely on this off-main contract and hop to MainActor themselves per tick — a Kotlin Flow/callback that emits progress already on the main dispatcher would make the caller's own hop redundant but harmless; the dangerous direction is a port that assumes main-thread delivery and mutates UI state directly from the callback without a dispatcher hop, which would only surface as a crash under specific timing.

**BEH-25 · transition — Cancellation of the whole export is only checked once per photo in the outer loop — `loadOne` itself is not cancellation-aware and is bounded independently by its own timeout/backstop.** `Pilgrim/Models/Share/TourPhotoExporter.swift:44-51@3f9f9e8` — **[consensus: behavior + data (DAT-54) + edge-cases (EDG-78)]**
```swift
for (i, candidate) in candidates.enumerated() {
            // A cancelled share() must stop within ~one photo, not run the
            // whole remaining list — loadOne itself isn't cancellation-aware
            // (its own timeout/backstop bound it independently), so this is
            // the only place that can act on it.
            if Task.isCancelled { break }
            if let photo = await loadOne(candidate) { out.append(photo) }
            progress(i + 1, candidates.count)
        }
```
*Drift risk:* A coroutine port using `ensureActive()` inside the per-photo suspend function itself would behave differently (mid-photo cancellation) than this one-check-per-photo contract; the guarantee here is bounded-by-one-photo, not immediate.

**BEH-26 · async-sync-point — `loadOne`'s two `DispatchWorkItem`s race against PhotoKit's own callback: whichever of (result handler, cancel nudge, backstop) fires first wins via a lock-protected one-shot resume, and the winner cancels the other pending work items.** `Pilgrim/Models/Share/TourPhotoExporter.swift:55-63@3f9f9e8` — **[consensus: behavior + data (DAT-55) + edge-cases (EDG-80, EDG-81)]**
```swift
// Guarantee: wall-clock time is bounded by timeout + grace, not unbounded.
// perPhotoTimeout cancels the underlying PHImageManager request itself
// (PhotoKit's documented contract is to invoke the result handler with a
// nil image once a request is cancelled), and an independent backstop at
// perPhotoTimeout + backstopGrace force-resumes with nil even if that
// callback never fires at all — wedged iCloud requests are a credible
// failure mode, so the bound cannot depend entirely on PhotoKit calling
// back. Either path resumes through the same one-shot lock, and whichever
// fires first cancels the other's pending DispatchWorkItem.
```
*Drift risk:* The Android/MediaStore equivalent has no PhotoKit-cancellation-with-guaranteed-callback contract at all — a port must independently invent both a cancel path AND a hard backstop, and it's easy to ship only one of the two (usually just a coroutine timeout, missing the explicit worst-case-wedge backstop).

**BEH-27 · async-sync-point — The PhotoKit cancel nudge is scheduled at exactly `perPhotoTimeout` (20 s) after the request starts.** `Pilgrim/Models/Share/TourPhotoExporter.swift:107-110,130@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-84)]**
```swift
// Deadline 1: nudge PhotoKit to give up on the fetch itself.
            let cancelItem = DispatchWorkItem {
                PHImageManager.default().cancelImageRequest(requestID)
            }

            ...
            DispatchQueue.global().asyncAfter(deadline: .now() + perPhotoTimeout, execute: cancelItem)
```
*Drift risk:* This is a literal asyncAfter deadline the task explicitly asks to flag; a coroutine `withTimeout(20_000)` is the natural analogue but only replicates the cancel-nudge half, not the backstop half below.

**BEH-28 · async-sync-point — The hard backstop that force-resumes with nil is scheduled at `perPhotoTimeout + backstopGrace` (22 s total), independent of whether the cancel nudge's own callback ever landed.** `Pilgrim/Models/Share/TourPhotoExporter.swift:112-123,131@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-84)]**
```swift
// Deadline 2 (backstop): force the continuation to resume even if
            // PhotoKit never calls the result handler at all — independent of
            // whether cancelImageRequest actually interrupted anything.
            let backstopItem = DispatchWorkItem {
                let shouldResume = state.withLock { box -> Bool in
                    guard !box.resumed else { return false }
                    box.resumed = true
                    return true
                }
                guard shouldResume else { return }
                continuation.resume(returning: nil)
            }
            ...
            DispatchQueue.global().asyncAfter(deadline: .now() + perPhotoTimeout + backstopGrace, execute: backstopItem)
```
*Drift risk:* This second, independent deadline is the piece most likely to be dropped in a port that only implements a single `withTimeout` — losing it reintroduces the exact wedged-iCloud-request hang this code was written to prevent.

**BEH-29 · observer-lifetime — The result-handler's one-shot resume lock explicitly cancels both the `cancelItem` and `backstopItem` `DispatchWorkItem`s the moment it wins the race, so a late-firing deadline becomes a harmless no-op instead of a double-resume crash.** `Pilgrim/Models/Share/TourPhotoExporter.swift:82-90@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-81)]**
```swift
let requestID = PHImageManager.default().requestImage(
                for: asset,
                targetSize: CGSize(width: targetPixels, height: targetPixels),
                contentMode: .aspectFit,
                options: options
            ) { image, _ in
                let shouldResume = state.withLock { box -> Bool in
                    guard !box.resumed else { return false }
                    box.resumed = true
                    box.cancelItem?.cancel()
                    box.backstopItem?.cancel()
                    return true
                }
                guard shouldResume else { return }
```
*Drift risk:* Swift's `withCheckedContinuation` crashes (or is documented UB) if resumed twice — a Kotlin `suspendCancellableCoroutine` port must replicate the same one-shot guard across all three completion paths (result callback, cancel nudge, backstop) or risk a double-resume crash under real timing races.

### 2.5 `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift`

**BEH-30 · transition — Toggling Interactive ON triggers `prepareInteractive()`; toggling it OFF does nothing (no explicit onChange branch for the false case).** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:25-27@3f9f9e8` — **[consensus: behavior + ui-visual (UI-8), identical citation]**
```swift
.onChange(of: viewModel.interactiveEnabled) { _, on in
                if on { viewModel.prepareInteractive() }
            }
```
*Drift risk:* prepareInteractive() runs on EVERY flip to true (not just the first), but internally guards its two side effects differently (tourCandidates population is idempotent via isEmpty check; photo auto-enable is a true once-ever latch) — a port that only calls the equivalent setup once per VM lifetime instead of on every toggle would miss re-populating tourCandidates if it had been cleared.

**BEH-31 · async-sync-point — The Interactive section's appearance/disappearance animates over 0.2 seconds.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:72@3f9f9e8` — **[consensus: behavior + ui-visual (UI-18) + edge-cases (EDG-86), identical citation — three-lens]**
```swift
.animation(.easeInOut(duration: 0.2), value: viewModel.interactiveEnabled)
```
*Drift risk:* A Compose AnimatedVisibility with a different default duration would change the felt pacing of the toggle even though functionally equivalent.

**BEH-32 · transition — The trim toggle's subtitle text switches between the 150 m-trim explanation and "too short to trim", and the toggle itself is disabled, based on `canTrimRoute`.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:56-69@3f9f9e8` — **[consensus: behavior + ui-visual (UI-15, UI-16) + edge-cases (EDG-85)]**
```swift
Toggle(isOn: $viewModel.trimEnabled) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Trim start & end")
                            .font(Constants.Typography.body)
                            .foregroundColor(.ink)
                        Text(viewModel.canTrimRoute
                            ? "Keeps the first and last 150 m off the shared map — including photos and waymarkers there."
                            : "This walk is too short to trim.")
                            .font(Constants.Typography.caption)
                            .foregroundColor(.fog)
                    }
                }
                .tint(.moss)
                .disabled(!viewModel.canTrimRoute)
```
*Drift risk:* trimEnabled itself defaults to true regardless of canTrimRoute — the toggle can be "on" with no effect on a short walk; a port must replicate this (disabled-but-still-true state), not force trimEnabled false when canTrimRoute is false.

### 2.6 `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift`

**BEH-33 · state-machine — `ShareStatusSection`'s body is a single exhaustive switch over the 8-case `ShareState` enum, each rendering entirely different UI and (for idle/error) different button actions.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:13-79@3f9f9e8` — **[consensus: behavior + ui-visual (UI-33)]**
```swift
switch viewModel.shareState {
        case .idle:
            primaryButton("Share Walk") {
                viewModel.beginShare()
            }

        case .uploading:
            progressRow("Sharing...", font: Constants.Typography.button)

        case .preparingPhotos(let done, let total):
            progressRow("Preparing photos… \(done)/\(total)")

        case .photosDropped(let prepared, let dropped):
            droppedPhotosPrompt(prepared: prepared, dropped: dropped)

        case .uploadingMedia(let completed, let total):
            progressRow(
                "Carrying your walk… \(completed)/\(total)",
                subtitle: "keep Pilgrim open while your walk uploads"
            )

        case .success(let url):
            sharedCard(url: url) { EmptyView() }

        case .partial(let url, let failedCount):
            sharedCard(url: url) { ... }

        case .error(let message):
            VStack(spacing: Constants.UI.Padding.small) {
                Text(message)
                    .font(Constants.Typography.caption)
                    .foregroundColor(.rust)
                    .multilineTextAlignment(.center)

                primaryButton("Try Again") {
                    viewModel.beginShare()
                }
            }
        }
```
*Drift risk:* This is the canonical, exhaustive vertex list for the whole feature's UI — any Android `when` over the equivalent sealed class that isn't exhaustive (or that collapses two of these into one visual state) changes user-observable behavior, not just internals.

**BEH-34 · transition — The `.uploadingMedia` progress row is the only one carrying a persistent subtitle reminding the user to keep the app open.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:29-33,85-89@3f9f9e8` — **[consensus: behavior + ui-visual (UI-37)]**
```swift
case .uploadingMedia(let completed, let total):
            progressRow(
                "Carrying your walk… \(completed)/\(total)",
                subtitle: "keep Pilgrim open while your walk uploads"
            )
```
*Drift risk:* This subtitle is the only user-facing hint that the media-PUT phase is vulnerable to backgrounding (ties to the ~10s background-exhaustion cutoff in ShareService) — easy to drop as "just copy" without realizing it's compensating for a real OS-lifecycle risk that needs an Android equivalent (foreground-service notification) regardless of exact wording.

**BEH-35 · transition — The `.partial` state's retry button is conditionally replaced by a static "can no longer be carried" message when `repairUnavailable` is true, rather than always offering "Carry the missing files".** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:38-65@3f9f9e8` — **[consensus: behavior + ui-visual (UI-38), identical citation]**
```swift
case .partial(let url, let failedCount):
            sharedCard(url: url) {
                VStack(spacing: Constants.UI.Padding.small) {
                    Text("\(failedCount) file\(failedCount == 1 ? "" : "s") didn't make it — they'll show as unavailable on the page.")
                        .font(Constants.Typography.caption)
                        .foregroundColor(.rust)
                        .multilineTextAlignment(.center)

                    if viewModel.repairUnavailable {
                        Text("These files can no longer be carried — the walk's recordings have changed.")
                            .font(Constants.Typography.caption)
                            .foregroundColor(.fog)
                            .multilineTextAlignment(.center)
                    } else {
                        Button {
                            viewModel.beginRetry()
                        } label: {
                            Text("Carry the missing files")
                                ...
                        }
                    }
                }
            }
```
*Drift risk:* repairUnavailable is state ORTHOGONAL to the ShareState enum itself (a plain @Published bool on the VM) — a port that folds it into a single sealed-class hierarchy instead of a separate flag must still reproduce the same "still .partial, but retry button replaced" combination, not a brand-new terminal state.

**BEH-36 · transition — The `.photosDropped` consent prompt offers two explicit choices — continue without the dropped photos, or decline — and frames this as still free because nothing has POSTed yet.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:110-137@3f9f9e8` — **[consensus: behavior + ui-visual (UI-43, UI-44, UI-45)]**
```swift
/// `.photosDropped`'s pre-POST consent pause: some requested photos
/// didn't export (iCloud-only assets that never downloaded, deletions
/// mid-export), so the walker chooses whether to proceed without them
/// or hold off — nothing has POSTed yet, so both choices are still free.
private func droppedPhotosPrompt(prepared: Int, dropped: Int) -> some View {
    VStack(spacing: Constants.UI.Padding.small) {
        Text("\(dropped) of \(prepared + dropped) photo\(dropped == 1 ? "" : "s") couldn't be prepared — they may still be waiting in iCloud.")
            ...
        primaryButton("Share without them") {
            viewModel.continueShareWithoutDroppedPhotos()
        }

        Button {
            viewModel.cancelDroppedPhotoShare()
        } label: { Text("Don't share yet") ... }
    }
}
```
*Drift risk:* This UI-level framing ("nothing has POSTed yet") is exactly matched by the ViewModel's isShareInFlight vs isDismissLocked split in WalkShareView — a port must keep both the prompt AND the weaker dismiss-lock together, not accidentally treat .photosDropped as fully in-flight.

### 2.7 `Pilgrim/Scenes/WalkShare/WalkShareView.swift`

**BEH-37 · state-machine — `isShareInFlight` (freezes the whole edit form) is broader than `isDismissLocked` (blocks sheet dismissal) — `.preparingPhotos` and `.photosDropped` freeze the form but do NOT lock dismissal, because nothing exists server-side yet during either.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:26-49@3f9f9e8` — **[consensus: behavior + ui-visual (UI-56, UI-57) + edge-cases (EDG-97, EDG-98)]**
```swift
private var isShareInFlight: Bool {
        switch viewModel.shareState {
        case .preparingPhotos, .photosDropped, .uploading, .uploadingMedia: return true
        default: return false
        }
    }

    /// Only `.uploading` (the POST has landed a live page) and
    /// `.uploadingMedia` (PUTs are streaming) have put anything server-side
    /// that abandoning the sheet would leave stranded — `.preparingPhotos`
    /// is a local, cancellable export and `.photosDropped` is a pre-POST
    /// consent pause, so neither locks the toolbar Cancel or interactive
    /// dismiss the way `isShareInFlight` locks the form.
    private var isDismissLocked: Bool {
        switch viewModel.shareState {
        case .uploading, .uploadingMedia: return true
        default: return false
        }
    }
```
*Drift risk:* Collapsing these into one gate (e.g. "any non-idle/non-terminal state blocks both form edits and back-navigation") would incorrectly let a user get stuck unable to back out during a purely local, cancellable photo-export phase — a real UX regression, not just an internal simplification.

**BEH-38 · transition — The entire edit form (stat toggles, interactive section, journal, expiry picker) is disabled as one `Group` based on `isShareInFlight`.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:72-79@3f9f9e8` — **[consensus: behavior + ui-visual (UI-56)]**
```swift
Group {
                            statToggles
                            InteractiveShareSection(viewModel: viewModel)
                            journalSection
                            expiryPicker
                        }
                        .disabled(isShareInFlight)
```
*Drift risk:* A single container-level disable is easy to lose in a Compose port that instead disables each control individually — any control added later that isn't wrapped in the equivalent container silently escapes the freeze.

**BEH-39 · transition — The toolbar Cancel button only renders (and only calls `cancelShare()`+`dismiss()`) when the share hasn't succeeded yet AND isn't dismiss-locked; interactive/swipe dismiss is separately gated by `isDismissLocked` via `interactiveDismissDisabled`.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:98-106,109@3f9f9e8` — **[consensus: behavior + ui-visual (UI-59)]**
```swift
ToolbarItem(placement: .navigationBarLeading) {
                    if !isShared && !isDismissLocked {
                        Button("Cancel") {
                            viewModel.cancelShare()
                            dismiss()
                        }
                        .foregroundColor(.stone)
                    }
                }
            }
        }
        .interactiveDismissDisabled(isDismissLocked)
```
*Drift risk:* Two independent dismiss surfaces (explicit button, system swipe/back gesture) must both honor the same lock — an Android back-press handler that isn't wired to the identical condition as an explicit close button would let a user escape mid-upload via the hardware back button.

**BEH-40 · first-emission — The podcast card's reveal is gated on `showPreview` transitioning from true to false (old→new via SwiftUI's onChange two-value closure), combined with a `ritualDidFire` latch that must have been set by a genuinely fresh share — not by a cache-hit re-entry.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:116-127@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-100)]**
```swift
.onChange(of: showPreview) { wasShowing, isShowing in
            // Only reveal after a FRESH-share modal dismiss (ritualDidFire).
            // Cache-hit re-entry via the walk summary's tappable URL also
            // dismisses the modal via showPreview true → false, and without
            // this gate the podcast card would spuriously appear on every
            // re-view of a walk that was shared weeks ago.
            guard wasShowing, !isShowing,
                  ritualDidFire,
                  !showPodcastCard,
                  isShared,
                  PodcastSubmissionService.shared.isEligible(walk: walk) else { return }
```
*Drift risk:* This is a textbook first-emission-style guard, but built from two SEPARATE mechanisms (an old/new comparison AND a manually-set/reset latch) rather than one — a port using only a StateFlow distinctUntilChanged equivalent without the extra ritualDidFire latch would resurface the exact spurious-reappearance bug this comment describes.

**BEH-41 · restoration-path — Re-opening the share sheet for an already-shared walk (via a tappable URL on the walk summary) dismisses the same preview modal the same way a fresh share's ritual does — the `ritualDidFire` latch is the only thing distinguishing a genuine first-share reveal from a cache-hit re-view.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:118-121@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-100)]**
```swift
// Cache-hit re-entry via the walk summary's tappable URL also
            // dismisses the modal via showPreview true → false, and without
            // this gate the podcast card would spuriously appear on every
            // re-view of a walk that was shared weeks ago.
```
*Drift risk:* An Android screen that re-derives "just shared" purely from ShareState == Success (without an equivalent one-shot latch reset on genuine transition) will replay the celebratory reveal every time a user reopens an already-shared walk's page.

**BEH-42 · async-sync-point — After a fresh share's ritual modal dismisses, the podcast card reveal is delayed by exactly 500 ms before fading in with a soft haptic.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:128-138@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-101)]**
```swift
podcastRevealTask = Task {
                try? await Task.sleep(for: .milliseconds(500))
                guard !Task.isCancelled else { return }
                await MainActor.run {
                    withAnimation(.easeOut(duration: 0.5)) {
                        showPodcastCard = true
                    }
                    UIImpactFeedbackGenerator(style: .soft).impactOccurred()
                }
            }
```
*Drift risk:* A literal 500ms delay plus a 500ms fade-out animation — dropping either half (the delay or the fade duration) changes the felt pacing of a two-stage reveal (ritual modal dismiss → beat → card fade-in) that was tuned to avoid colliding with the ritual's own reveal.

**BEH-43 · observer-lifetime — `onDisappear` cancels both `revealTask` and `podcastRevealTask` unconditionally, but only calls `cancelShare()` and clears the WebView loader when the preview cover is NOT currently showing — guarding against the parent's `onDisappear` firing while a child `fullScreenCover` is still presented.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:165-185@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-108)]**
```swift
.onDisappear {
            revealTask?.cancel()
            revealTask = nil
            podcastRevealTask?.cancel()
            podcastRevealTask = nil
            // Guard against iOS versions / scene configs where onDisappear
            // fires on the parent while the cover is still presented (e.g.,
            // app backgrounded with modal open, or presenting the cover
            // itself). Cancelling the share here would kill a live upload or
            // a "Carry the missing files" repair running underneath the
            // cover; clearing the loader mid-presentation would leave the
            // cover rendering an empty view.
            if !showPreview {
                // Swipe-to-dismiss during .preparingPhotos never runs the
                // toolbar Cancel button's action — without this, a sheet
                // closed that way would keep exporting photos and POSTing in
                // the background with no UI left to show it.
                viewModel.cancelShare()
                webViewLoaderHolder.clear()
            }
        }
```
*Drift risk:* Android has its own analogous but different lifecycle quirks (fragment/composable disposal ordering when a nested dialog/sheet is showing) — a straightforward port of onDisappear to DisposableEffect without the equivalent "is a child overlay still showing" guard could cancel a live upload the moment a nested preview screen opens, or leak an upload past navigation the swipe-dismiss path was meant to catch.

**BEH-44 · first-emission — The ritual (celebratory preview reveal) only fires when `shareState` transitions INTO `.success` FROM `.uploading` or `.uploadingMedia` specifically — any other prior state (including the VM's initial cache-restored `.success`) is rejected.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:357-366@3f9f9e8`
```swift
private func triggerRitualIfNeeded(
        old: WalkShareViewModel.ShareState,
        new: WalkShareViewModel.ShareState
    ) {
        guard case .success(let url) = new else { return }
        switch old {
        case .uploading, .uploadingMedia: break
        default: return
        }
        guard let parsedURL = URL(string: url) else { return }
```
*Drift risk:* Because WalkShareViewModel.init sets shareState directly to .success/.partial for a restored share (before this view's onChange observer ever attaches), this onChange never even fires for a cache-hit — the guard here is a second line of defense, not the only one; a port relying solely on this old/new check without ALSO restoring state pre-observer-attachment (i.e., firing an initial synthetic transition) could accidentally trigger the ritual on every reopen of an old shared walk.

**BEH-45 · async-sync-point — On a genuine transition into `.success`, the reveal (haptic + `showPreview=true`) is delayed by exactly 800 ms after eagerly creating the WebView loader, so the page has a head start loading during the beat.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:108-116,368-380@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-99, EDG-102)]**
```swift
// Reveal the podcast card after the ritual modal dismisses, not at
    // the moment of share success. The previous 800ms-after-success
    // trigger collided with the ritual's own reveal — the card animated
    // invisibly behind the modal, and its haptic doubled up with the
    // ritual's. Tying the reveal to `showPreview` going true → false
    // gives the card a visible fade-in and separates the two haptics.
    ...
        webViewLoaderHolder.create(url: parsedURL)
        previewURL = url
        ritualDidFire = true

        revealTask?.cancel()
        revealTask = Task {
            try? await Task.sleep(for: .milliseconds(800))
            guard !Task.isCancelled else { return }
            await MainActor.run {
                UIImpactFeedbackGenerator(style: .soft).impactOccurred()
                showPreview = true
            }
        }
    }
```
*Drift risk:* The 800ms constant and the eager-loader-creation-before-delay ordering are both load-bearing: a port that creates the WebView/loading surface only AFTER the delay loses the pre-warming effect, and a different delay value changes whether the two haptics (this one and the podcast card's 500ms-later one) feel doubled-up again — the exact bug this comment says was previously fixed.

**BEH-46 · transition — Tapping to open the preview manually cancels any pending ritual `revealTask` first, so a user-initiated open doesn't race with the delayed automatic reveal.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:343-355@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-103)]**
```swift
private func openPreview(url: String) {
        guard let parsedURL = URL(string: url) else { return }
        // If the user taps to open during the 800ms ritual beat, cancel the
        // pending reveal so its haptic + redundant showPreview assignment
        // don't fire on an already-open modal.
        revealTask?.cancel()
        revealTask = nil
        if webViewLoaderHolder.loader == nil {
            webViewLoaderHolder.create(url: parsedURL)
        }
        previewURL = url
        showPreview = true
    }
```
*Drift risk:* Without cancelling the pending automatic reveal task, a fast manual tap during the 800ms window would let the delayed task fire a redundant haptic + a no-op showPreview=true assignment against an already-open modal — a subtle double-haptic bug rather than a crash, easy to miss without device testing.

**BEH-47 · transition — The journal text field clamps to 140 characters reactively on every keystroke via `onChange`, not just at submit time.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:266-270@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-106)]**
```swift
.onChange(of: viewModel.journal) { _, newValue in
                        if newValue.count > 140 {
                            viewModel.journal = String(newValue.prefix(140))
                        }
                    }
```
*Drift risk:* A Compose TextField with only a maxLength-style visual hint (no reactive truncation of the backing state) would let the VM's journal value exceed 140 characters if paste or IME autocomplete inserts a large chunk at once.

**BEH-48 · dispatcher — The WebView loader holder is its own `@MainActor ObservableObject`, separate from `WalkShareViewModel`, created lazily and cleared on cover dismiss.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:386-397@3f9f9e8`
```swift
@MainActor
private final class WebViewLoaderHolder: ObservableObject {
    @Published var loader: WebViewLoader?

    func create(url: URL) {
        loader = WebViewLoader(url: url)
    }

    func clear() {
        loader = nil
    }
}
```
*Drift risk:* This holder's lifecycle is intentionally decoupled from the share ViewModel so the WebView survives view recompositions but not sheet dismissal — a port that folds this into the main ViewModel could tie WebView state to the wrong scope.

### 2.8 `Pilgrim/Scenes/WalkShare/WalkShareViewModel+ShareOrchestration.swift`

**BEH-49 · transition — `beginShare()` is a no-op if a `shareTask` is already running; otherwise it spawns a Task that self-clears `shareTask` to nil on completion.** `.../WalkShareViewModel+ShareOrchestration.swift:9-15@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-109)]**
```swift
func beginShare() {
        guard shareTask == nil else { return }
        shareTask = Task { [weak self] in
            await self?.share()
            self?.shareTask = nil
        }
    }
```
*Drift risk:* This is the completeShare dedup lock the task explicitly names as a drift archetype — an Android equivalent (Job? guarded by isActive) must replicate BOTH the pre-launch guard AND the self-clearing on completion; guarding only one half re-opens the double-tap race.

**BEH-50 · transition — `cancelShare()` is documented as safe to call anytime, but only has effect at the one checkpoint before anything exists server-side — once the POST has landed a live page, cancellation becomes a no-op in effect.** `.../WalkShareViewModel+ShareOrchestration.swift:17-23@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-110)]**
```swift
/// Safe to call any time: a no-op once nothing is running, and a no-op
    /// in EFFECT once the POST has landed — `share()` only honors
    /// cancellation at the one checkpoint before anything exists
    /// server-side.
    func cancelShare() {
        shareTask?.cancel()
    }
```
*Drift risk:* A coroutine port that checks `isActive`/`ensureActive()` at MULTIPLE points (e.g. also mid-media-upload) would change behavior — a cancel tap arriving after the POST must NOT abort in-flight media PUTs, since the page is already live and users need those uploads to complete or fail into a repairable .partial.

**BEH-51 · state-machine — `share()` gates entry on `canShare` (tour validation), resets `repairUnavailable`, and immediately (synchronously, before any await) claims `.uploading` as an optimistic lock state.** `.../WalkShareViewModel+ShareOrchestration.swift:25-29@3f9f9e8`
```swift
func share() async {
        guard canShare else { return }
        repairUnavailable = false
        shareState = .uploading
```
*Drift risk:* shareState becomes .uploading (which locks dismissal per WalkShareView.isDismissLocked) BEFORE the potentially-multi-second photo export even starts — a port that only claims the locking state after export completes would leave a window where a user can dismiss mid-export believing nothing is happening yet.

**BEH-52 · first-emission — Before the async photo export begins, `shareState` is primed synchronously to `.preparingPhotos(completed: 0, total: N)` — deliberately, because the first real progress tick only fires after the FIRST photo finishes loading, and without this priming the phase-gate in `applyPreparingPhotosProgress` would reject that first tick.** `.../WalkShareViewModel+ShareOrchestration.swift:32-45@3f9f9e8` — **[consensus: behavior + ui-visual (UI-69)]**
```swift
// Prime synchronously, same reason as .uploadingMedia below — the
            // first progress tick only fires after the first photo finishes
            // loading, so without this the phase would never actually become
            // .preparingPhotos and applyPreparingPhotosProgress's guard would
            // reject every tick.
            shareState = .preparingPhotos(completed: 0, total: exportList.count)
            tourPhotos = await ShareService.withBackgroundAssertion(named: "pilgrim.share.photo-export") {
                await TourPhotoExporter.export(exportList) { [weak self] done, total in
                    Task { @MainActor in self?.applyPreparingPhotosProgress(completed: done, total: total) }
                }
            }
```
*Drift risk:* This priming pattern (synchronous state claim before the first async tick, paired with a phase-gate that rejects stale/premature ticks) recurs 3 more times in this file — a port that treats each occurrence as an isolated detail instead of recognizing the shared idiom risks fixing one instance and missing the others.

**BEH-53 · transition — Cancellation is only honored at exactly one checkpoint: immediately after photo export, before anything has POSTed — if cancelled here, `shareState` resets to `.idle`.** `.../WalkShareViewModel+ShareOrchestration.swift:51-55@3f9f9e8`
```swift
// Nothing exists server-side yet — cancellation is clean up to the POST.
        if Task.isCancelled {
            shareState = .idle
            return
        }
```
*Drift risk:* This is the exact cancellation boundary the task's drift archetypes ask about — a port must not check cancellation again after this point (e.g. mid-upload), or it would abandon a live page's media uploads instead of letting them run to a repairable .partial.

**BEH-54 · state-machine — If the photo export came up short (fewer exported than requested), the flow pauses at `.photosDropped` rather than silently POSTing with fewer photos than promised — nothing has POSTed at this point.** `.../WalkShareViewModel+ShareOrchestration.swift:57-68@3f9f9e8` — **[consensus: behavior + ui-visual (UI-70) + edge-cases (EDG-111)]**
```swift
let dropped = exportCount - tourPhotos.count
        if dropped > 0 {
            pendingTourPhotos = tourPhotos
            shareState = .photosDropped(prepared: tourPhotos.count, dropped: dropped)
            return
        }

        await completeShare(tourPhotos: tourPhotos)
    }
```
*Drift risk:* pendingTourPhotos stores the SUCCESSFULLY exported photos so far, to be reused (not re-exported) if the user chooses to continue — a port that re-runs the whole export from scratch on "share without them" would duplicate work and could re-encounter transient failures on photos that already succeeded.

**BEH-55 · transition — `continueShareWithoutDroppedPhotos()` claims `.uploading` synchronously — before the Task is even spawned — specifically so the prompt's two buttons disappear immediately instead of remaining tappable through the geocode+POST that follows; it reuses the same `shareTask == nil` guard as `beginShare()`.** `.../WalkShareViewModel+ShareOrchestration.swift:71-88@3f9f9e8` — **[consensus: behavior + ui-visual (UI-71) + edge-cases (EDG-112)]**
```swift
/// "Share without them": resumes past the dropped-photo pause with
    /// whatever exported successfully. Goes through `shareTask` — not a bare
    /// `Task { }` — for the same reason `beginShare()` does: so a Cancel tap
    /// during the resumed upload has something to cancel, and so a
    /// same-runloop double-tap has nothing to hit. Claims `.uploading`
    /// SYNCHRONOUSLY, before the `Task` is even spawned, so the prompt's
    /// "Share without them" / "Don't share yet" buttons vanish immediately
    /// instead of staying tappable through the geocode+POST that follows.
    func continueShareWithoutDroppedPhotos() {
        guard shareTask == nil else { return }
        shareState = .uploading
        shareTask = Task { [weak self] in
            guard let self else { return }
            await self.completeShare(tourPhotos: self.pendingTourPhotos)
            self.pendingTourPhotos = []
            self.shareTask = nil
        }
    }
```
*Drift risk:* A port using a plain `viewModelScope.launch { }` without first synchronously updating the observed StateFlow value (i.e. updating state INSIDE the coroutine rather than before launching it) reopens the same-runloop double-tap window this pattern exists to close.

**BEH-56 · transition — Declining the dropped-photos prompt ("Don't share yet") must cancel any in-flight resume task, because a fast tap on "Share without them" followed immediately by "Don't share yet" could otherwise let the resume sail past the decline and share anyway.** `.../WalkShareViewModel+ShareOrchestration.swift:90-99@3f9f9e8`
```swift
/// "Don't share yet": nothing exists server-side during `.photosDropped`
    /// itself, but a "Share without them" resume may already be running (a
    /// fast tap on that button followed by this one) — cancelling
    /// `shareTask` is what makes "Don't share yet" actually mean no, rather
    /// than letting an in-flight resume sail past this and share anyway.
    func cancelDroppedPhotoShare() {
        shareTask?.cancel()
        pendingTourPhotos = []
        shareState = .idle
    }
```
*Drift risk:* This is exactly the kind of two-button race condition that only surfaces under real double-tap timing — a port that treats "decline" as purely a state reset (without also cancelling any competing in-flight job) would let a share slip through against explicit user intent.

**BEH-57 · state-machine — `completeShare` claims `.uploading` at a single choke point shared by both the happy path and the resume-after-dropped-photos path, specifically so every caller gets the dismiss-lock and no caller can forget it.** `.../WalkShareViewModel+ShareOrchestration.swift:101-111@3f9f9e8`
```swift
private func completeShare(tourPhotos: [TourPhoto]) async {
        // Claim the locking state at the single choke point: from here through the POST a live page may exist — every caller gets the dismiss-lock, no caller can forget it.
        shareState = .uploading
        guard !Task.isCancelled else {
            shareState = .idle
            return
        }
        let placeNames = await geocodeEndpoints()
```
*Drift risk:* This IS the "completeShare lock" the task's drift archetypes explicitly name — a port must funnel every code path that can reach the POST through one equivalent choke point that sets the locking state, rather than each caller setting it independently (which invites a forgotten caller that never locks dismissal).

**BEH-58 · restoration-path — On a successful POST, the share result is cached immediately (before any media upload starts) — this is what makes the walk look "shared" on next app launch even if media PUTs never complete.** `.../WalkShareViewModel+ShareOrchestration.swift:119-123@3f9f9e8`
```swift
let result = try await ShareService.share(payload: payload)
            if let uuid = walk.uuid {
                ShareService.cacheShare(result, walkID: uuid, expiryDays: selectedExpiry.rawValue, expiryOption: selectedExpiry.cacheKey)
            }
```
*Drift risk:* Caching happens right after the POST succeeds, not after the whole share() function returns — a port that defers persistence to the end of the whole flow would lose the live-page record if the process dies during the subsequent media upload phase.

**BEH-59 · restoration-path — Before any media PUT is attempted, the failed-media cache is pre-populated with a record covering EVERY recording and photo about to be uploaded (as if all had failed) — PUTs are idempotent so over-repairing is harmless, but this makes a process kill mid-upload restore into a repairable `.partial` instead of a lying `.success`.** `.../WalkShareViewModel+ShareOrchestration.swift:125-132@3f9f9e8` — **[consensus: behavior + data (DAT-67) + edge-cases (EDG-113)]**
```swift
if interactiveEnabled {
                let tourItems = TourBuilder.tourItems(candidates: tourCandidates, trimM: 0)
                let audioFiles = tourItems.files
                let audioRecordings = tourItems.tour.recordings
                if let uuid = walk.uuid {
                    // Pre-populate so a kill mid-upload restores a repairable .partial instead of a lying .success; PUTs are idempotent, over-repair is harmless.
                    ShareService.cacheFailedMedia(Self.expectedFailureRecords(recordings: audioRecordings, photos: tourPhotos), walkID: uuid)
                }
```
*Drift risk:* This is exactly the "what happens to untried uploads when [the background window] expires" drift archetype — a port that only writes the failed-media record AFTER the upload attempt (success or failure) instead of BEFORE it starts would restore a false .success on next launch if the process is killed mid-upload, silently losing the repair offer.

**BEH-60 · first-emission — `shareState` is primed synchronously to `.uploadingMedia(completed: 0, total: N)` immediately before the media upload call, with no unstructured-Task hop, so the first real progress tick lands on an already-correct phase.** `.../WalkShareViewModel+ShareOrchestration.swift:133-136@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-114), identical citation]**
```swift
// Prime the phase synchronously (no unstructured-Task hop to
                // race) so the FIRST progress tick already finds shareState
                // in .uploadingMedia — see applyMediaProgress.
                shareState = .uploadingMedia(completed: 0, total: audioFiles.count + tourPhotos.count)
```
*Drift risk:* The doc explicitly rules out an "unstructured-Task hop" for this assignment — a port that dispatches this initial state update via a separate coroutine launch (instead of a direct synchronous write before the upload call) reintroduces the exact race this comment warns about.

**BEH-61 · dispatcher — Both `onItemSuccess` and `progress` callbacks from `ShareService`'s media upload functions are nonisolated closures that hop to the MainActor via an unstructured Task before touching VM state.** `.../WalkShareViewModel+ShareOrchestration.swift:137-146@3f9f9e8`
```swift
let failures = await ShareService.uploadAllMedia(
                    shareID: result.id,
                    audioFiles: audioFiles,
                    photos: tourPhotos.map(\.jpegData),
                    onItemSuccess: { [weak self] kind, n in
                        Task { @MainActor in self?.pruneFailedMedia(kind: kind, n: n) }
                    }
                ) { [weak self] progress in
                    Task { @MainActor in self?.applyMediaProgress(progress) }
                }
```
*Drift risk:* The `[weak self]` capture combined with the Task hop means a callback firing after the VM is deallocated (e.g. the share sheet was dismissed and torn down) is silently dropped rather than crashing — a port using a strong reference or a non-cancellable callback channel could either leak the VM or crash on a late callback.

**BEH-62 · restoration-path — The non-interactive share path explicitly clears any previous failed-media record for the walk, because a fresh classic share must never inherit a stale `.partial` repair record from an earlier interactive share attempt.** `.../WalkShareViewModel+ShareOrchestration.swift:157-163@3f9f9e8` — **[consensus: behavior + data (DAT-68)]**
```swift
} else {
                if let uuid = walk.uuid {
                    // A fresh share must never inherit a previous share's failed-media
                    // record — this walk may have had a `.partial` share before.
                    ShareService.cacheFailedMedia([], walkID: uuid)
                }
                shareState = .success(url: result.url)
            }
```
*Drift risk:* A port that only writes the failed-media cache when there ARE failures (never explicitly clearing it on a clean/non-interactive share) would leave a stale record from a prior interactive attempt, making a brand new classic share incorrectly restore as .partial on next launch.

**BEH-63 · restoration-path — `expectedFailureRecords` builds one identity record per recording plus one per photo — the FULL set being uploaded, not just eventual failures — using the same per-slot identity mapping (`failedMediaItem`) that real failures use.** `.../WalkShareViewModel+ShareOrchestration.swift:190-210@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-116)]**
```swift
static func expectedFailureRecords(
        recordings: [SharePayload.TourRecording],
        photos: [TourPhoto]
    ) -> [ShareService.FailedMediaItem] {
        let audioItems = recordings.indices.map { index in
            failedMediaItem(for: (kind: .audio, n: index + 1), audioRecordings: recordings, tourPhotos: photos)
        }
        let photoItems = photos.indices.map { index in
            failedMediaItem(for: (kind: .photos, n: index + 1), audioRecordings: recordings, tourPhotos: photos)
        }
        return audioItems + photoItems
    }
```
*Drift risk:* This function is deliberately not private (unit-tested directly per its doc comment) — a port that inlines the equivalent logic without an independently testable seam makes it much harder to regression-test the kill-safety guarantee in isolation.

**BEH-64 · transition — `beginRetry()` reuses the identical `shareTask == nil` dedup guard as `beginShare()`, and the resulting `shareState` change to `.uploadingMedia` is what removes the retry button from the `.partial` view — the guard covers the same-runloop double-tap window before that UI removal has even rendered.** `.../WalkShareViewModel+ShareOrchestration.swift:212-225@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-109)]**
```swift
/// Routes "Carry the missing files" through the same `shareTask` guard as
    /// `beginShare()`. `retryFailedMedia()` flips `shareState` to
    /// `.uploadingMedia` synchronously as its first real action (see below),
    /// and that state change is what removes the retry button from
    /// `.partial`'s view (`ShareStatusSection` only renders it there) — the
    /// guard here is what covers the same-runloop double-tap before that
    /// removal has rendered.
    func beginRetry() {
        guard shareTask == nil else { return }
        shareTask = Task { [weak self] in
            await self?.retryFailedMedia()
            self?.shareTask = nil
        }
    }
```
*Drift risk:* Three distinct entry points (beginShare, continueShareWithoutDroppedPhotos, beginRetry) all share ONE lock variable (shareTask) — a port that gives each action its own independent Job would allow, e.g., a retry to start while a fresh share from the same VM instance is somehow still finishing, which the single-lock design structurally prevents.

**BEH-65 · transition — `retryFailedMedia()` resets `repairUnavailable` and bails out (leaving state untouched) if there's no cached share or no failed items — it does not clear state to idle or error in that case.** `.../WalkShareViewModel+ShareOrchestration.swift:234-238@3f9f9e8`
```swift
func retryFailedMedia() async {
        repairUnavailable = false
        guard let uuid = walk.uuid, let cached = ShareService.cachedShare(for: uuid) else { return }
        let failed = ShareService.failedMedia(for: uuid)
        guard !failed.isEmpty else { return }
```
*Drift risk:* An early return here leaves shareState exactly as it was (still .partial from before the call) — a port that instead sets some interim state before these guards would flash a transient UI change even when the retry can't proceed at all.

**BEH-66 · first-emission — `retryFailedMedia()` claims `.uploadingMedia` synchronously, before the possibly-multi-second photo re-export runs — otherwise the retry button would stay on-screen and tappable during that gap, risking a second overlapping retry of the same items.** `.../WalkShareViewModel+ShareOrchestration.swift:240-244@3f9f9e8`
```swift
// Leave .partial synchronously, before the possibly-multi-second photo
        // re-export below — otherwise "Carry the missing files" stays on
        // screen and tappable during that gap, and a second tap would start
        // an overlapping retry of the same items.
        shareState = .uploadingMedia(completed: 0, total: failed.count)
```
*Drift risk:* This initial claim uses `failed.count` (every cached failure) as `total`, but the state is re-primed further down with `uploadable.count` (only the subset that resolved by identity) — a port that only primes once with the original count would show a progress total that never matches the actual number of PUTs attempted whenever some cached failures fail identity resolution.

**BEH-67 · transition — The current recordings/audio-file arrays are only rebuilt from `TourBuilder` if the failed list actually contains an audio item — avoiding unnecessary work when only photos failed.** `.../WalkShareViewModel+ShareOrchestration.swift:246-256@3f9f9e8`
```swift
let currentRecordings: [SharePayload.TourRecording]
        let currentAudioFiles: [URL]
        if failed.contains(where: { $0.kind == ShareService.MediaKind.audio.rawValue }) {
            let candidates = tourCandidates.isEmpty ? TourBuilder.candidates(for: walk) : tourCandidates
            let tourItems = TourBuilder.tourItems(candidates: candidates, trimM: 0)
            currentRecordings = tourItems.tour.recordings
            currentAudioFiles = tourItems.files
        } else {
            currentRecordings = []
            currentAudioFiles = []
        }
```
*Drift risk:* Note the fallback `tourCandidates.isEmpty ? TourBuilder.candidates(for: walk) : tourCandidates` — a retry can run in a fresh VM instance (re-opened share sheet) where tourCandidates was never populated by prepareInteractive(); a port that assumes tourCandidates is always already populated during retry would silently retry against an empty candidate list.

**BEH-68 · transition — Photo retry only re-exports the SPECIFIC photos that failed, matched by `localIdentifier` against the pinned-photos list — not the full (up to 20-photo) export list again.** `.../WalkShareViewModel+ShareOrchestration.swift:258-266@3f9f9e8`
```swift
// Re-export only the specific photos that failed, by identity — not
        // the whole (up to 20-photo) export list again.
        let failedPhotoIDs = Set(failed.compactMap { $0.kind == ShareService.MediaKind.photos.rawValue ? $0.photoLocalID : nil })
        let photoCandidatesToReExport = pinnedPhotos.filter { failedPhotoIDs.contains($0.localIdentifier) }
        let currentPhotos: [TourPhoto] = photoCandidatesToReExport.isEmpty
            ? []
            : await ShareService.withBackgroundAssertion(named: "pilgrim.share.photo-export") {
                await TourPhotoExporter.export(photoCandidatesToReExport) { _, _ in }
            }
```
*Drift risk:* A port that re-runs the full export list on every retry (instead of filtering to just the failed subset) wastes bandwidth/time and re-risks the same photosDropped-style export failures on photos that already succeeded the first time.

**BEH-69 · restoration-path — If NONE of the cached failures resolve against current data, `retryFailedMedia` sets `repairUnavailable=true` and returns to `.partial` with the unresolved count — explicitly to avoid looping back to the same retry button forever with nothing actually retriable.** `.../WalkShareViewModel+ShareOrchestration.swift:268-282@3f9f9e8` — **[consensus: behavior + ui-visual (UI-72)]**
```swift
let (uploadable, remainingAfterResolve) = Self.resolveRetryItems(
            cached: failed,
            currentRecordings: currentRecordings,
            currentAudioFiles: currentAudioFiles,
            currentPhotos: currentPhotos
        )

        guard !uploadable.isEmpty else {
            ShareService.cacheFailedMedia(remainingAfterResolve, walkID: uuid)
            // None of the cached failures still match anything carryable —
            // don't silently loop back to the same retry button forever.
            repairUnavailable = true
            shareState = .partial(url: cached.url, failedCount: remainingAfterResolve.count)
            return
        }
```
*Drift risk:* This dead-end path is the ONLY place repairUnavailable becomes true — a port that treats "zero uploadable items" the same as "some uploadable items" (both just re-showing a retry button) would trap a user in an infinite dead retry loop with no explanation, since ShareStatusSection's copy switch on repairUnavailable is the only thing that breaks the loop.

**BEH-70 · restoration-path — After the specific retry upload, still-failed items are recovered back to their full identity record via a lookup into the original `failed` array (by kind+n), then merged with items that never resolved by identity at all, before the new cache write.** `.../WalkShareViewModel+ShareOrchestration.swift:294-304@3f9f9e8`
```swift
// Recover each still-failed item's full identity from the cache we
        // resolved it from, so a THIRD attempt can still verify it too.
        let stillFailed = stillFailedRaw.compactMap { raw in
            failed.first { $0.kind == raw.kind.rawValue && $0.n == raw.n }
        }

        let remaining = stillFailed + remainingAfterResolve
        ShareService.cacheFailedMedia(remaining, walkID: uuid)
        shareState = remaining.isEmpty
            ? .success(url: cached.url)
            : .partial(url: cached.url, failedCount: remaining.count)
```
*Drift risk:* uploadSpecific's return type only carries (kind, n) — not the full identity fields — so this lookup-back-into-`failed` step is what preserves the stable identity (startTs / localIdentifier+ts) for a THIRD retry attempt; a port that caches the raw (kind, n) pair directly instead of resolving it back to a full identity record would lose that identity on the next retry, reintroducing the exact index-drift bug resolveRetryItems exists to prevent.

**BEH-71 · restoration-path — `resolveRetryItems` is a pure, non-isolated function that matches each cached failure to CURRENT data purely by stable identity — audio by `startTs` equality, photos by `localIdentifier`+`ts` equality — never by array position, and always uploads under the CACHED slot number `n`.** `.../WalkShareViewModel+ShareOrchestration.swift:318-360@3f9f9e8` — **[consensus: behavior + data (DAT-69), identical citation; + edge-cases EDG-117, EDG-118]**
```swift
nonisolated static func resolveRetryItems(
        cached: [ShareService.FailedMediaItem],
        currentRecordings: [SharePayload.TourRecording],
        currentAudioFiles: [URL],
        currentPhotos: [TourPhoto]
    ) -> (uploadable: [(kind: ShareService.MediaKind, n: Int, data: () throws -> Data)], remaining: [ShareService.FailedMediaItem]) {
        ...
        for item in cached {
            guard let kind = ShareService.MediaKind(rawValue: item.kind) else {
                remaining.append(item)
                continue
            }
            switch kind {
            case .audio:
                guard let index = currentRecordings.firstIndex(where: { $0.startTs == item.audioStartTs }),
                      currentAudioFiles.indices.contains(index) else {
                    remaining.append(item)
                    continue
                }
                let fileURL = currentAudioFiles[index]
                uploadable.append((kind: .audio, n: item.n, data: { try Data(contentsOf: fileURL) }))
            case .photos:
                guard let match = currentPhotos.first(where: {
                    $0.sourceLocalIdentifier == item.photoLocalID && $0.meta.ts == item.photoTs
                }) else {
                    remaining.append(item)
                    continue
                }
                let data = match.jpegData
                uploadable.append((kind: .photos, n: item.n, data: { data }))
            }
        }

        return (uploadable, remaining)
    }
```
*Drift risk:* This is the exact logic the task's WalkShareInteractiveTests exercise heavily (shifted-index, missing-identity, unrecognized-kind cases) — a port using array-index lookups instead of a linear identity search would pass simple tests but fail the moment the local candidate set has shifted since the original share, exactly the bug class this function exists to close.

**BEH-72 · restoration-path — `pruneFailedMedia` removes exactly one (kind, n) pair from the persisted failed-media cache the instant its PUT succeeds — wired as `onItemSuccess` from both upload paths — so a crash mid-upload restores `.partial` counting only what's ACTUALLY still missing.** `.../WalkShareViewModel+ShareOrchestration.swift:362-376@3f9f9e8` — **[consensus: behavior + data (DAT-70)]**
```swift
/// Prunes one just-uploaded item from the failed-media cache the moment
    /// its PUT lands — wired as `onItemSuccess` from both `uploadAllMedia`
    /// (in `completeShare`) and `uploadSpecific` (in `retryFailedMedia`), off
    /// the MainActor, hence the `Task { @MainActor in ... }` hop at each call
    /// site. A kill mid-upload (crash, force-quit, backgrounding past the OS
    /// budget) now restores `.partial` counting only what's ACTUALLY still
    /// missing, not everything the upload started with — the final
    /// `cacheFailedMedia` write at the end of a normal run still supersedes
    /// this, so it only matters for the interrupted case.
    private func pruneFailedMedia(kind: ShareService.MediaKind, n: Int) {
        guard let uuid = walk.uuid else { return }
        var remaining = ShareService.failedMedia(for: uuid)
        remaining.removeAll { $0.kind == kind.rawValue && $0.n == n }
        ShareService.cacheFailedMedia(remaining, walkID: uuid)
    }
```
*Drift risk:* This is a read-modify-write against persisted storage on EVERY successful item, not a batched update — a port that only writes the failed-media record once at the end of the whole upload would lose all of this incremental crash-safety, restoring the pre-populated "everything failed" record after a process kill instead of the true partial progress.

**BEH-73 · first-emission — `applyMediaProgress` only updates `shareState` while it is still exactly `.uploadingMedia` — a stale progress tick arriving after `share()`/`retryFailedMedia()` has already set a terminal state is silently dropped instead of clobbering it.** `.../WalkShareViewModel+ShareOrchestration.swift:378-388@3f9f9e8`
```swift
/// Applies a media-upload progress tick only while `shareState` is still
    /// `.uploadingMedia`. Progress hops from `uploadAllMedia`/`uploadSpecific`
    /// run as unstructured `Task`s off their (nonisolated) progress closure,
    /// so a hop can still be queued on the MainActor after `share()` or
    /// `retryFailedMedia()` has already set a terminal state and run after
    /// it. Gating on the phase makes a late hop a no-op instead of
    /// clobbering `.success`/`.partial`/`.error`.
    private func applyMediaProgress(_ progress: ShareService.MediaProgress) {
        guard case .uploadingMedia = shareState else { return }
        shareState = .uploadingMedia(completed: progress.completed, total: progress.total)
    }
```
*Drift risk:* This is the mirror of the classic Combine "transitioning AWAY from X needs first-emission protection" pattern, but for STALE ASYNC CALLBACKS racing a state machine forward — a Kotlin StateFlow.update{} that unconditionally overwrites the current value from a background progress callback (instead of checking the current value is still the expected phase first) would let a late callback resurrect .uploadingMedia after the flow has already reached .success, flipping the UI backward.

**BEH-74 · first-emission — `applyPreparingPhotosProgress` applies the identical late-arrival guard as `applyMediaProgress`, but for the photo-export phase — a stale tick must not clobber a terminal state, and must not re-lock dismissal by flipping `shareState` back into an `isShareInFlight` case.** `.../WalkShareViewModel+ShareOrchestration.swift:390-397@3f9f9e8`
```swift
/// Same late-arrival guard as `applyMediaProgress`, for the photo-export
    /// phase: a stale tick landing after a terminal state must not clobber
    /// `.success`/`.partial`/`.error`, and must not re-lock dismissal by
    /// flipping `shareState` back into an `isShareInFlight` case.
    private func applyPreparingPhotosProgress(completed: Int, total: Int) {
        guard case .preparingPhotos = shareState else { return }
        shareState = .preparingPhotos(completed: completed, total: total)
    }
```
*Drift risk:* The doc explicitly calls out a SECOND consequence beyond state-clobbering: re-locking the dismiss/form-freeze gates. A port that guards only against overwriting a terminal display value but not against re-triggering the derived UI-lock booleans could re-freeze a form the user had already successfully backed out of.

**BEH-75 · transition — The photo export candidate list is capped at 20 photos and filtered to the interactive-trim kept window; this exact list is reused by both the original share's export and `retryFailedMedia`'s re-export, and by the pre-share totals label, so all three agree on which photos are in scope.** `.../WalkShareViewModel+ShareOrchestration.swift:399-414@3f9f9e8` — **[consensus: behavior + data (DAT-66) + edge-cases (EDG-119)]**
```swift
func interactivePhotoExportList() -> [PhotoCandidate] {
        guard hasPinnedPhotos else { return [] }
        let window = interactiveKeptWindow()
        return Array(
            pinnedPhotos
                .filter { window?.contains(Int($0.capturedAt.timeIntervalSince1970)) ?? true }
                .prefix(20)
        )
    }
```
*Drift risk:* The 20-photo cap and trim-window filter are computed FRESH each call (not cached) — if trimEnabled or interactiveEnabled changes between the original share and a later retry, this list can silently shift; the doc on retryFailedMedia's caller explicitly flags this as an assumption ("assuming interactiveEnabled and trimEnabled haven't changed since") that a port must also either preserve or explicitly re-verify.

### 2.9 `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift`

**BEH-76 · state-machine — `ShareState` has exactly 8 vertices, each documented inline with its meaning; only `success` and `partial` represent a live page.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:110-119@3f9f9e8` — **[consensus: behavior + ui-visual (UI-66) + data (DAT-58) — three-lens]**
```swift
enum ShareState: Equatable {
        case idle
        case preparingPhotos(completed: Int, total: Int) // hi-res export (pre-POST)
        case photosDropped(prepared: Int, dropped: Int)  // export done short; pre-POST consent pause
        case uploading                                   // POST phase
        case uploadingMedia(completed: Int, total: Int)  // PUT phase
        case success(url: String)
        case partial(url: String, failedCount: Int)      // page live, some media missing
        case error(message: String)
    }
```
*Drift risk:* This is the authoritative vertex list every other finding in this lens refers back to — a Kotlin sealed class/interface must reproduce all 8 cases with the same associated data shapes (Int/Int pairs, String url, Int failedCount, String message), not collapse or rename any of them, since view code and tests both pattern-match on these exact cases.

**BEH-77 · state-machine — `isShared` is true for both `.success` and `.partial` — deliberately testable without SwiftUI, since the view mirrors this logic locally rather than importing it directly.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:121-129@3f9f9e8` — **[consensus: behavior + data (DAT-58)]**
```swift
/// VM-level source of truth for "this walk has a live page" — `.partial`
    /// counts the same as `.success`. Exists so the distinction is testable
    /// without SwiftUI (the view mirrors this locally).
    var isShared: Bool {
        switch shareState {
        case .success, .partial: return true
        default: return false
        }
    }
```
*Drift risk:* The comment explicitly notes WalkShareView.isShared is a SEPARATE local computed property mirroring this one, not a direct passthrough — a port that has the equivalent Compose screen call straight into a single shared isShared function is fine, but must confirm neither copy silently diverges (e.g. if the enum gains a new case later, both copies need updating in lockstep, which the iOS split has visibly NOT enforced structurally).

**BEH-78 · state-machine — `hasExistingShare` is a separate accessor from `isShared`, explicitly re-deriving from the cache (not from the VM's own `shareState`) and additionally checking non-expiry.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:150-154@3f9f9e8`
```swift
var hasExistingShare: Bool {
        guard let uuid = walk.uuid else { return false }
        guard let cached = ShareService.cachedShare(for: uuid) else { return false }
        return !cached.isExpired
    }
```
*Drift risk:* This re-reads the cache live rather than trusting shareState/cachedExpiryDate captured at init time — likely used by a different screen (e.g. walk summary) that constructs a fresh check without instantiating the full ViewModel; a port must decide whether this needs its own lightweight query path or can piggyback on the VM's restore logic.

**BEH-79 · restoration-path — `WalkShareViewModel`'s initializer directly restores `shareState` to `.partial` or `.success` (bypassing the whole normal state-machine transition path) whenever a non-expired cached share exists for this walk, choosing `.partial` specifically when un-landed media failures are also cached.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:195-213@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-130)]**
```swift
init(
        walk: WalkInterface,
        pinnedPhotos: [PhotoCandidate] = [],
        isPhotosGranted: @escaping () -> Bool = { PermissionManager.standard.isPhotosGranted }
    ) {
        self.walk = walk
        self.pinnedPhotos = pinnedPhotos
        self.isPhotosGranted = isPhotosGranted
        if let uuid = walk.uuid, let cached = ShareService.cachedShare(for: uuid), !cached.isExpired {
            cachedExpiryDate = cached.expiry
            // A share with un-landed media PUTs still has a live page —
            // restore .partial so "Carry the missing files" survives
            // leaving and returning here, not a quiet .success.
            let failedCount = ShareService.failedMedia(for: uuid).count
            shareState = failedCount > 0
                ? .partial(url: cached.url, failedCount: failedCount)
                : .success(url: cached.url)
        }
    }
```
*Drift risk:* This is the single most important restoration-path finding in the whole slice: an Android ViewModel constructed (or re-created by Hilt/SavedStateHandle) for an already-shared walk must perform the SAME two-part check (cached share exists AND not expired, THEN separately check failed-media count) at construction time, not default to Idle and only discover the cached state on some later side effect — otherwise a reopened share sheet for an already-shared walk would incorrectly show the initial 'Share Walk' button instead of the shared card, and would lose the 'Carry the missing files' option entirely if failedCount was ignored.

**BEH-80 · first-emission — `prepareInteractive()` has two independently-guarded side effects: `tourCandidates` is populated lazily only if currently empty (effectively idempotent), while `includePhotos` auto-enable fires exactly once ever via the `didAutoEnablePhotos` latch, and never re-fires even if the user later toggles `includePhotos` back off.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:217-228@3f9f9e8` — **[consensus: behavior + ui-visual (UI-64), identical citation; + edge-cases EDG-131, EDG-132]**
```swift
func prepareInteractive() {
        if tourCandidates.isEmpty {
            tourCandidates = TourBuilder.candidates(for: walk)
        }
        // Interactive means "carry the media": the first enable brings photos
        // along automatically (the spec's auto-enable); the walker can still
        // switch them off afterwards and we never re-flip.
        if hasPinnedPhotos && !didAutoEnablePhotos {
            didAutoEnablePhotos = true
            includePhotos = true
        }
    }
```
*Drift risk:* didAutoEnablePhotos is plain instance state (not persisted), so it resets whenever a fresh VM is constructed — a port using a single top-level "has auto-enabled" flag stored per-walk (rather than per-VM-instance) would behave differently across re-opens of the same share sheet within one app session vs. across app launches.

**BEH-81 · transition — `toggleInclude` silently no-ops for any candidate whose `unavailableReason` is non-nil — an unavailable candidate can never be toggled on by the user.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:230-234@3f9f9e8`
```swift
func toggleInclude(candidateID: Int) {
        guard let i = tourCandidates.firstIndex(where: { $0.id == candidateID }),
              tourCandidates[i].unavailableReason == nil else { return }
        tourCandidates[i].includeInShare.toggle()
    }
```
*Drift risk:* A port using a plain toggle bound directly to a checkbox's onCheckedChange without this same unavailability guard would let the UI (if not also disabling the control) desync includeInShare state for a candidate that TourBuilder.tourItems will filter out anyway.

**BEH-82 · transition — `flipKind` normalizes `kindOverride` back to nil whenever the flip lands back on the candidate's own `autoKind`, rather than storing a redundant explicit override that happens to match the default.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:236-241@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-133), identical citation]**
```swift
func flipKind(candidateID: Int) {
        guard let i = tourCandidates.firstIndex(where: { $0.id == candidateID }) else { return }
        let current = tourCandidates[i].effectiveKind
        let flipped: TourRecordingKind = current == .spoken ? .ambient : .spoken
        tourCandidates[i].kindOverride = flipped == tourCandidates[i].autoKind ? nil : flipped
    }
```
*Drift risk:* A port that always sets an explicit override on any flip (never normalizing back to null/None when it matches auto) would still produce the correct effectiveKind, but would break any downstream logic that distinguishes "user explicitly chose the auto-detected kind" from "user never touched this candidate" — e.g. analytics or a future UI badge showing manual overrides.

**BEH-83 · dispatcher — `geocodeEndpoints()` reverse-geocodes the start and end coordinates concurrently using Swift's `async let` structured concurrency, not sequentially.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:250-262@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-134, EDG-135)]**
```swift
func geocodeEndpoints() async -> (start: String?, end: String?) {
        guard let anchors = geocodeAnchorPoints() else { return (nil, nil) }

        let startLoc = CLLocation(latitude: anchors.start.lat, longitude: anchors.start.lon)
        let endLoc = CLLocation(latitude: anchors.end.lat, longitude: anchors.end.lon)

        async let startName = geocodeSingle(geocoder: CLGeocoder(), location: startLoc)
        async let endName = geocodeSingle(geocoder: CLGeocoder(), location: endLoc)

        let (s, e) = await (startName, endName)
        if s != nil && e != nil && s == e { return (s, nil) }
        return (s, e)
    }
```
*Drift risk:* A Kotlin port using two sequential `suspend fun` calls instead of `coroutineScope { async { } }` × 2 would roughly double the geocoding latency users experience before the POST fires — a real (if minor) performance regression, not just a style difference. Also note: if both geocode to the SAME place name, placeEnd collapses to nil (only placeStart is kept) — an easy behavior to drop.

**BEH-84 · dispatcher — The classic (non-interactive) share photo path uses a fully synchronous `PHImageManager` request (`isSynchronous = true`, `isNetworkAccessAllowed = false`), documented as blocking the calling thread for roughly 10-50 ms per local photo — a deliberate contrast to the async `TourPhotoExporter` used by the interactive path.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:275-317@3f9f9e8` — **[consensus: behavior + data (DAT-62) + edge-cases (EDG-136, EDG-137, EDG-138)]**
```swift
/// Loads a pinned photo as a low-res base64 JPEG for the share
    /// payload. Synchronous (blocks main ~10-50ms per local photo).
    /// Returns nil for deleted or iCloud-only photos, which are
    /// silently dropped from the share.
    private static func loadSharePhoto(
        localIdentifier: String,
        lat: Double,
        lon: Double,
        capturedAt: Date
    ) -> SharePayload.Photo? {
        let fetchResult = PHAsset.fetchAssets(
            withLocalIdentifiers: [localIdentifier],
            options: nil
        )
        guard let asset = fetchResult.firstObject else { return nil }

        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = false
        options.isSynchronous = true
        options.resizeMode = .exact
```
*Drift risk:* Since this is called from `photoPayload`, called from `buildPayload`, called from `completeShare` — all on the @MainActor-isolated WalkShareViewModel — this deliberately blocks the MAIN THREAD for up to tens of milliseconds PER PHOTO in the classic share path. A naive Kotlin port might either (a) dispatch this to IO by default per the project's established 'viewModelScope.launch defaults to Main' caution, introducing concurrency the iOS code doesn't have to handle, or (b) block the main thread on MediaStore access without iOS's explicit isNetworkAccessAllowed=false guarantee (local-only, no iCloud wait), risking real ANRs on the classic share path — this needs deliberate handling either way, not an accidental copy of only half the behavior.

**BEH-85 · transition — Reverse-geocoding failures are silently absorbed to nil rather than propagated — a geocode error never surfaces as a `ShareState.error`.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:319-326@3f9f9e8`
```swift
private func geocodeSingle(geocoder: CLGeocoder, location: CLLocation) async -> String? {
        do {
            let placemarks = try await geocoder.reverseGeocodeLocation(location)
            return placemarks.first?.locality ?? placemarks.first?.subLocality ?? placemarks.first?.name
        } catch {
            return nil
        }
    }
```
*Drift risk:* A port that lets a geocoding exception propagate up into the same catch block that produces ShareState.error would incorrectly fail an entire share (which has already claimed .uploading / locked dismissal) over a purely cosmetic place-name lookup failure.

**BEH-86 · transition — The interactive path's talk duration is clamped to `walk.talkDuration` (never allowed to exceed it), mirroring the same clamp NewWalk applies elsewhere and the worker's own 400 rejection rule for meditate+talk exceeding active time.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:364-365@3f9f9e8` — **[consensus: behavior + data (DAT-60) + edge-cases (EDG-142)]**
```swift
// Recordings outrun active time by design (a talk can run through a pause); NewWalk clamps talkDuration to activeDuration for the same reason, and the worker 400s on meditate+talk > active — clamp the included-candidate sum the same way.
            talkDuration: interactive ? min(includedTalkCandidates.reduce(0) { $0 + $1.duration }, walk.talkDuration) : walk.talkDuration,
```
*Drift risk:* This clamp only applies on the interactive branch (summing included candidates); the classic branch trusts walk.talkDuration directly and is unclamped by this line — a port that applies the min() clamp universally, or omits it entirely on the interactive branch, would either wrongly shrink classic-share numbers or risk the worker's 400 rejection on interactive shares with generous recording overlap.

**BEH-87 · state-machine — `computeInteractiveRoute` reports the trim outcome by what actually happened, not by what was requested — if `RouteTrimmer` silently no-ops (route too short), `trimM` is reported as 0 and `keptWindow` as nil, never the requested 150 m.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:469-481@3f9f9e8` — **[consensus: behavior + ui-visual (UI-68) + data (DAT-64) + edge-cases (EDG-146) — four-lens]**
```swift
// Report the trim by OUTCOME, not intent: RouteTrimmer silently no-ops on a route too short to trim, so trimM/keptWindow must reflect what actually happened — never claim a 150m trim while shipping the full, untrimmed route.
        let trimmed = RouteTrimmer.trim(downsampled, meters: Double(Self.trimMeters))
        let didTrim = trimmed.count < downsampled.count
        let trimM = didTrim ? Self.trimMeters : 0
        // Trim's promise covers everything with a coordinate: waypoints and photo metadata outside the kept route window are excluded too — a doorstep photo must not pin the doorstep trim just hid.
        let keptWindow: ClosedRange<Int>? = (didTrim && trimmed.count >= 2)
            ? trimmed.first!.ts...trimmed.last!.ts
            : nil
        return (trimmed, trimM, keptWindow)
```
*Drift risk:* A port that reports trimM based purely on the trimEnabled flag (intent) rather than comparing actual output length to input length (outcome) would falsely claim a privacy-protecting trim on short walks that RouteTrimmer left completely untouched — a real privacy-honesty bug, not just a display glitch, since keptWindow also gates which waypoints/photos are excluded from the payload.

### 2.10 `UnitTests/ShareMediaUploadTests.swift`

**BEH-88 · async-sync-point — `ShareMediaUploadTests` pins the exact media-PUT timeout as 30 seconds and documents it as an idle timeout, not a whole-upload budget.** `UnitTests/ShareMediaUploadTests.swift:6-14@3f9f9e8` — **[consensus: behavior + data (DAT-71) + edge-cases (EDG-155)]**
```swift
func testRequestShapeMatchesWorkerContract() {
        let req = ShareService.mediaUploadRequest(shareID: "abc123defg", kind: .audio, n: 3, contentLength: 12345)
        XCTAssertEqual(req.url?.absoluteString, "https://walk.pilgrimapp.org/api/share/abc123defg/audio/3")
        XCTAssertEqual(req.httpMethod, "PUT")
        XCTAssertEqual(req.value(forHTTPHeaderField: "Content-Type"), "audio/mp4")
        XCTAssertEqual(req.value(forHTTPHeaderField: "Content-Length"), "12345")
        XCTAssertNotNil(req.value(forHTTPHeaderField: "X-Device-Token"))
        XCTAssertEqual(req.timeoutInterval, 30, "an idle timeout, not a whole-upload one — it resets on bytes moving")
    }
```
*Drift risk:* This test also pins the exact PUT URL shape (/api/share/{shareID}/{kind}/{n}) and required headers — a port's OkHttp/Retrofit request builder must match this path template and header set exactly for the worker to accept the request.

**BEH-89 · transition — When the app is backgrounded with only 2 seconds of background time remaining (under the 10 s threshold), `uploadAllMedia` fails every item without attempting any network call at all — including never reading the nonexistent audio file's bytes — and the final progress report lands exactly on (total, total).** `UnitTests/ShareMediaUploadTests.swift:57-70@3f9f9e8` — **[consensus: behavior + data (DAT-73), identical citation; + edge-cases EDG-158]**
```swift
func testUploadAllMediaSkipsNetworkWhenBackgroundExhausted() async {
        ShareService.backgroundStateProvider = { (true, 2) } // background, well under the 10s threshold

        let audioFiles = [URL(fileURLWithPath: "/tmp/pilgrim-share-media-upload-tests-nonexistent.m4a")]
        let photos = [Data([0xAA]), Data([0xBB])]
        var lastProgress: ShareService.MediaProgress?

        let failures = await ShareService.uploadAllMedia(shareID: "test-share-id", audioFiles: audioFiles, photos: photos) { progress in
            lastProgress = progress
        }

        XCTAssertEqual(failures.count, audioFiles.count + photos.count, "background-exhausted from the very first item of each loop must fail everything without attempting a PUT — the nonexistent audio fileURL never gets read")
        XCTAssertEqual(lastProgress, ShareService.MediaProgress(completed: audioFiles.count + photos.count, total: audioFiles.count + photos.count), "the per-loop skip accounting must still land exactly on (total, total)")
    }
```
*Drift risk:* This confirms the exhaustion check happens BEFORE any file I/O is attempted (a nonexistent file path never throws) — a port that checks exhaustion only around the network call itself (after already reading the file into memory) would do wasted work and could throw a different kind of error for a missing file.

**BEH-90 · restoration-path — The failed-media cache supports round-trip persistence, per-item pruning that leaves siblings untouched, and writing an empty array fully clears the stored record.** `UnitTests/ShareMediaUploadTests.swift:22-42@3f9f9e8` — **[consensus: behavior + data (DAT-72), identical citation; + edge-cases EDG-156, EDG-157]**
```swift
ShareService.cacheFailedMedia(failures, walkID: walkID)
        let reloaded = ShareService.failedMedia(for: walkID)
        XCTAssertEqual(reloaded, failures, "round-trip through JSON must preserve identity fields, not just kind/n")

        let afterOnePruned = ShareService.failedMedia(for: walkID).filter { !($0.kind == "audio" && $0.n == 1) }
        ShareService.cacheFailedMedia(afterOnePruned, walkID: walkID)
        XCTAssertEqual(ShareService.failedMedia(for: walkID), [failures[0]], "pruning the completed item must remove exactly it, leaving the other cached failure untouched")

        ShareService.cacheFailedMedia([], walkID: walkID)
        XCTAssertTrue(ShareService.failedMedia(for: walkID).isEmpty)
```
*Drift risk:* "Round-trip through JSON must preserve identity fields, not just kind/n" is an explicit test intent — a port that persists only a subset of FailedMediaItem's fields (e.g. dropping photoTs) would pass a naive equality-on-kind/n test but break resolveRetryItems' identity matching in production.

### 2.11 `UnitTests/WalkShareInteractiveTests.swift`

**BEH-91 · transition — Cancelling a share before the POST always returns `shareState` to `.idle`, verified end-to-end through beginShare/cancelShare/await.** `UnitTests/WalkShareInteractiveTests.swift:519-526@3f9f9e8`
```swift
func testShareCancelledBeforePostReturnsToIdle() async {
        let walk = WalkDataFactory.makeWalk(voiceRecordings: [WalkDataFactory.makeVoiceRecording()])
        let vm = WalkShareViewModel(walk: walk)
        vm.beginShare()
        vm.cancelShare()
        await vm.shareTask?.value
        XCTAssertEqual(vm.shareState, .idle, "cancelling before the POST must never leave a live-looking state behind")
    }
```
*Drift risk:* "Must never leave a live-looking state behind" is the exact invariant a port's cancellation path must preserve — any intermediate state (e.g. stuck on .uploading or .preparingPhotos) left behind after a genuine cancel would strand the UI.

**BEH-92 · first-emission — `continueShareWithoutDroppedPhotos` claims `.uploading` synchronously within the same runloop turn (before any await), and a same-runloop double-tap is a true no-op that does not spawn a second resume task.** `UnitTests/WalkShareInteractiveTests.swift:542-557@3f9f9e8` — **[consensus: behavior + ui-visual (UI-71) + edge-cases (EDG-179)]**
```swift
func testContinueShareClaimsUploadingSynchronously() async {
        let vm = WalkShareViewModel(walk: WalkDataFactory.makeWalk())
        vm.shareState = .photosDropped(prepared: 1, dropped: 1)

        vm.continueShareWithoutDroppedPhotos()
        XCTAssertEqual(vm.shareState, .uploading, "the prompt's buttons must vanish within the same runloop turn, before any await")
        XCTAssertNotNil(vm.shareTask, "a resume task must be running")

        vm.continueShareWithoutDroppedPhotos()
        XCTAssertEqual(vm.shareState, .uploading, "a same-runloop double-tap must be a no-op — the shareTask guard covers it, not a second resume task")
        XCTAssertNotNil(vm.shareTask, "the no-op call must not have cleared the original task")

        vm.cancelShare()
        await vm.shareTask?.value
        XCTAssertEqual(vm.shareState, .idle, "cleanup: the cancelled resume must still land on idle via completeShare's pre-POST checkpoint")
    }
```
*Drift risk:* This test is a direct behavioral spec for the Android equivalent of the shareTask dedup lock — a StateFlow-based Kotlin port must guarantee the SAME synchronous, same-frame state update before any suspend point, verifiable with an equivalent test that doesn't advance a test dispatcher between the two calls.

**BEH-93 · transition — Declining the dropped-photos prompt while a "Share without them" resume is already in flight cancels that resume and lands on `.idle`, never letting the decline be silently overtaken by the in-progress share.** `UnitTests/WalkShareInteractiveTests.swift:559-568@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-180)]**
```swift
func testDeclineCancelsInFlightResume() async {
        let vm = WalkShareViewModel(walk: WalkDataFactory.makeWalk())
        vm.shareState = .photosDropped(prepared: 2, dropped: 1)

        vm.continueShareWithoutDroppedPhotos()
        vm.cancelDroppedPhotoShare()

        await vm.shareTask?.value
        XCTAssertEqual(vm.shareState, .idle, "declining while a resume is in flight must cancel it — completeShare's pre-POST checkpoint returns idle before geocoding or POSTing ever run")
    }
```
*Drift risk:* This is a race-condition test with no timing control needed (both calls happen synchronously back-to-back) — a port's equivalent test must be able to express the same race deterministically, which requires the SAME synchronous-cancel-before-first-suspend-point guarantee in the Kotlin implementation.

**BEH-94 · restoration-path — Both `.success` and `.partial` are exercised by constructing a fresh `WalkShareViewModel` against pre-populated `ShareService` cache state (cacheShare + optionally cacheFailedMedia) — proving the init-time restoration path, not just the in-memory state machine.** `UnitTests/WalkShareInteractiveTests.swift:320-358@3f9f9e8`
```swift
let successWalk = WalkDataFactory.makeWalk(uuid: successID)
        ShareService.cacheShare(
            ShareService.ShareResult(url: "https://walk.pilgrimapp.org/success1", id: "success1"),
            walkID: successID,
            expiryDays: 90,
            expiryOption: "season"
        )
        let successVM = WalkShareViewModel(walk: successWalk)
        XCTAssertTrue(successVM.isShared)
        guard case .success = successVM.shareState else {
            return XCTFail("expected .success when no media failed")
        }
        ...
        ShareService.cacheFailedMedia(
            [ShareService.FailedMediaItem(kind: "audio", n: 1, audioStartTs: 100, photoLocalID: nil, photoTs: nil)],
            walkID: partialID
        )
        let partialVM = WalkShareViewModel(walk: partialWalk)
        XCTAssertTrue(partialVM.isShared, ".partial must count as shared — the page is already live")
        guard case .partial(_, let failedCount) = partialVM.shareState else {
            return XCTFail("expected .partial when media failed")
        }
        XCTAssertEqual(failedCount, 1)
```
*Drift risk:* This test constructs the VM AFTER writing cache state, exactly mirroring the real "app restart / reopen share sheet" scenario — a port's equivalent test must construct its ViewModel (via Hilt or manually) fresh against pre-seeded DataStore/Room state, not just assert on in-memory StateFlow transitions, or it will miss regressions in the actual restoration code path.

**BEH-95 · restoration-path — `resolveRetryItems` finds an audio recording by `startTs` even when its array position has shifted due to an earlier candidate dropping out, and uploads it under the CACHED slot number, not its new array index.** `UnitTests/WalkShareInteractiveTests.swift:449-463@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-175), identical citation]**
```swift
func testResolveRetryItemsAudioFoundAtShiftedIndex() {
        // The cached failure originally pointed at slot n=3, whose recording had startTs 500. Since then an earlier recording dropped out of the candidate set, so that SAME recording (still startTs 500) now sits at index 0 — an index-locked lookup (index 2) would miss it entirely.
        let cached = [ShareService.FailedMediaItem(kind: "audio", n: 3, audioStartTs: 500, photoLocalID: nil, photoTs: nil)]
        let recordings = [
            SharePayload.TourRecording(n: 1, startTs: 500, endTs: 560, duration: 60, kind: "spoken", transcription: nil, wpm: nil, sizeBytes: 1_000),
            SharePayload.TourRecording(n: 2, startTs: 600, endTs: 660, duration: 60, kind: "spoken", transcription: nil, wpm: nil, sizeBytes: 1_000)
        ]
        let audioFiles = [URL(fileURLWithPath: "/tmp/shifted-0.m4a"), URL(fileURLWithPath: "/tmp/shifted-1.m4a")]

        let (uploadable, remaining) = WalkShareViewModel.resolveRetryItems(cached: cached, currentRecordings: recordings, currentAudioFiles: audioFiles, currentPhotos: [])

        XCTAssertTrue(remaining.isEmpty)
        XCTAssertEqual(uploadable.first?.n, 3, "must upload under the CACHED slot n, not the recording's shifted array position")
        XCTAssertEqual(uploadable.first?.kind, .audio)
    }
```
*Drift risk:* This is the single clearest proof-by-example of the identity-not-index invariant — any Android unit test suite for the equivalent retry-resolution function should include this exact shifted-index scenario, since it's precisely the case a naive index-based port would get wrong while still passing simpler tests.

**BEH-96 · restoration-path — A cached photo failure whose `localIdentifier` no longer appears anywhere in the current export (because it was unpinned) is carried forward unchanged in `remaining`, never silently dropped from the repair record.** `UnitTests/WalkShareInteractiveTests.swift:414-423@3f9f9e8`
```swift
func testResolveRetryItemsPhotoMissingIdentityGoesToRemaining() {
        // "photo-gone" was unpinned between the original share and this retry — it no longer appears anywhere in the current export.
        let cached = [ShareService.FailedMediaItem(kind: "photos", n: 1, audioStartTs: nil, photoLocalID: "photo-gone", photoTs: 500)]
        let photos = [TourPhoto(meta: SharePayload.Photo(lat: 0, lon: 0, ts: 999, data: nil), jpegData: Data([0xAA]), sourceLocalIdentifier: "photo-other")]

        let (uploadable, remaining) = WalkShareViewModel.resolveRetryItems(cached: cached, currentRecordings: [], currentAudioFiles: [], currentPhotos: photos)

        XCTAssertTrue(uploadable.isEmpty)
        XCTAssertEqual(remaining, cached, "an unresolved item must be carried forward unchanged, not dropped")
    }
```
*Drift risk:* "Carried forward unchanged, not dropped" means the failedCount shown in .partial's UI stays accurate even for permanently-unresolvable items — a port that filters out unmatched items instead of preserving them would undercount failures and could eventually let repairUnavailable logic never trigger since remainingAfterResolve would look artificially small.

**BEH-97 · restoration-path — `expectedFailureRecords` produces one record per recording PLUS one per photo — the full set about to be uploaded, not merely a placeholder — with correct per-kind identity fields (audio gets `audioStartTs` only, photos get `photoLocalID`+`photoTs` only).** `UnitTests/WalkShareInteractiveTests.swift:479-505@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-176), identical citation]**
```swift
func testExpectedFailureRecordsMatchIdentityMapping() {
        let recordings = [
            SharePayload.TourRecording(n: 1, startTs: 100, endTs: 160, duration: 60, kind: "spoken", transcription: nil, wpm: nil, sizeBytes: 1_000),
            SharePayload.TourRecording(n: 2, startTs: 200, endTs: 260, duration: 60, kind: "ambient", transcription: nil, wpm: nil, sizeBytes: 2_000)
        ]
        let photos = [TourPhoto(meta: SharePayload.Photo(lat: 1, lon: 2, ts: 999, data: nil), jpegData: Data([0xAA]), sourceLocalIdentifier: "photo-1")]

        let records = WalkShareViewModel.expectedFailureRecords(recordings: recordings, photos: photos)

        XCTAssertEqual(records.count, 3, "one record per recording plus one per photo — the FULL upload, not just failures")
```
*Drift risk:* "The FULL upload, not just failures" — a port that only ever writes failure records for items that actually failed (never pre-populating the full expected set before upload starts) reopens the kill-mid-upload-restores-false-success gap this whole mechanism exists to close.

**BEH-98 · first-emission — The photo auto-enable latch fires exactly once: enabling Interactive auto-turns-on `includePhotos` the first time, but if the user then turns it back off and `prepareInteractive()` runs again, it stays off.** `UnitTests/WalkShareInteractiveTests.swift:56-66@3f9f9e8` — **[consensus: behavior + ui-visual (UI-79), identical citation; + edge-cases EDG-131]**
```swift
func testInteractiveAutoEnablesPhotosOnce() {
        UserPreferences.walkReliquaryEnabled.value = true
        let walk = WalkDataFactory.makeWalk()
        let vm = WalkShareViewModel(walk: walk, pinnedPhotos: [PhotoCandidate.fixture()], isPhotosGranted: { true })
        vm.interactiveEnabled = true
        vm.prepareInteractive()
        XCTAssertTrue(vm.includePhotos)
        vm.includePhotos = false
        vm.prepareInteractive()
        XCTAssertFalse(vm.includePhotos, "auto-enable happens once; the walker's off stays off")
    }
```
*Drift risk:* This proves prepareInteractive() is safely re-entrant (called on every toggle-to-true per InteractiveShareSection's onChange) without re-triggering the auto-enable — a port must gate the auto-enable on a latch that survives repeated calls to the equivalent "prepare" function within the same ViewModel instance, not just on interactiveEnabled's own value.

**BEH-101 · transition — A route too short to actually trim reports `trimM` as 0 in the payload even when `trimEnabled` is on, and leaves waypoints completely unfiltered — proving the outcome-not-intent reporting end-to-end through `buildPayload`.** `UnitTests/WalkShareInteractiveTests.swift:225-247@3f9f9e8` — **[consensus: behavior + ui-visual (UI-80), identical citation; + edge-cases EDG-171]**
```swift
func testShortRouteTrimIsHonestAndLeavesWaypointsUnfiltered() {
        let route = longRoute(points: 4) // ~333m total — well under the 4x-150m trim threshold
        ...
        vm.interactiveEnabled = true
        vm.trimEnabled = true
        vm.includeWaypoints = true
        vm.prepareInteractive()

        let payload = vm.testBuildPayload()

        XCTAssertEqual(payload.tour?.trimM, 0, "a route too short to actually trim must report trimM 0, not the requested 150 — RouteTrimmer silently no-ops on it")
        let labels = payload.waypoints?.map(\.label) ?? []
        XCTAssertTrue(labels.contains("Before the first fix"), "no real trim happened, so nothing should be filtered out — not even a waypoint before the first GPS fix")
    }
```
*Drift risk:* The waypoint even predates the route's first GPS fix (timestamp before route[0]) and is STILL included, because no real trim window exists to filter against — a port that always applies a window filter whenever trimEnabled is true (rather than only when a trim actually occurred) would wrongly exclude legitimate waypoints on short walks.

### 2.12 `UnitTests/TourBuilderTests.swift`

**BEH-99 · state-machine — A voice recording whose start/end truncate to the same integer second (a sub-second blip) is excluded from candidates entirely — it does not even appear as an unavailable candidate.** `UnitTests/TourBuilderTests.swift:133-141@3f9f9e8` — **[consensus: behavior + edge-cases (EDG-164)]**
```swift
func testCandidates_subSecondBlipExcluded() {
        let start = DateFactory.makeDate(2024, 6, 15, 9, 5, 0)
        let recording = WalkDataFactory.makeVoiceRecording(startDate: start, endDate: start.addingTimeInterval(0.4))
        let walk = WalkDataFactory.makeWalk(voiceRecordings: [recording])

        let candidates = TourBuilder.candidates(for: walk)

        XCTAssertTrue(candidates.isEmpty, "a recording whose start/end truncate to the same Int second must not appear as a candidate at all — not even an unavailable one")
    }
```
*Drift risk:* This is stricter than the file-missing/too-large unavailability states — this candidate is REMOVED from the list, not marked unavailable-but-present. A port that always produces one UI row per underlying recording (just toggling an available/unavailable flag) would show a phantom row for a blip the server would reject outright (end_ts <= start_ts after truncation).

**BEH-100 · state-machine — Validation cap tests pin the boundary: 5×14 MB (70 MB) over bytes cap; 7×1000 s (7000 s) over the 6480 s cap; 6×1000 s (6000 s) fits under 108 min and passes.** `UnitTests/TourBuilderTests.swift:74-83@3f9f9e8`
```swift
let heavy = (0..<5).map { candidate(id: $0, bytes: 14_000_000) }   // 70MB
XCTAssertNotNil(TourBuilder.validationError(for: heavy))
let long = (0..<7).map { candidate(id: $0, seconds: 1000) }        // 7000s > 6480
XCTAssertNotNil(TourBuilder.validationError(for: long))
let contemplative = (0..<6).map { candidate(id: $0, seconds: 1000) } // 6000s fits in 108 min
XCTAssertNil(TourBuilder.validationError(for: contemplative))
```
*Drift risk:* This test's exact numbers are version-sensitive (they change to 7 × 1000s plus a new 6-candidate under-cap assertion in a later iOS commit past the parity pin) — an Android test suite generated by reading the WRONG iOS revision would encode the wrong cap and wrong test boundary, exactly the class of error this lens's git-verification step exists to catch. **Note the internal tension flagged in §7.**

---

## 3. UI / Visual

80 ui-visual-lens findings. `token` = the `Constants.*` symbol the value resolves through (or `—` when the value is a bare literal with no token).

### 3.1 Layout

**UI-1 · Root Interactive section container is a leading-aligned VStack spaced by `Constants.UI.Padding.small`.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:11@3f9f9e8` · token `Constants.UI.Padding.small`
```swift
VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
```
*Drift risk:* Padding.small = 8 (Constants.swift:11); an Android port using a different vertical rhythm here would misalign every child element of the section.

**UI-4 · Toggle label stack uses a magic 4pt spacing, not a Constants token.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:15@3f9f9e8` · token `—`
```swift
VStack(alignment: .leading, spacing: 4) {
```
*Drift risk:* 4 happens to equal Constants.UI.Padding.xs's value but is written as a literal — the same pattern recurs at lines 57 (trim toggle label stack) and 142 (row title stack uses 2, not 4). Easy to conflate all of these as one token.

**UI-19 · Recordings list container spacing uses `Constants.UI.Padding.xs`.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:76@3f9f9e8` · token `Constants.UI.Padding.xs`
```swift
VStack(alignment: .leading, spacing: Constants.UI.Padding.xs) {
```
*Drift risk:* xs = 4 (Constants.swift:10) — the tightest of the four Padding tokens; used specifically to pack recording rows close together, distinct from the section's own spacing.small (8) gap.

**UI-41 · Shared `progressRow` helper: outer `VStack(spacing: 4)` + inner `HStack(spacing: Padding.small)` housing the spinner and label.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:90-102@3f9f9e8` · token `Constants.UI.Padding.small`
```swift
VStack(spacing: 4) {
            HStack(spacing: Constants.UI.Padding.small) {
                SwiftUI.ProgressView()
                    .tint(.parchment)
                Text(text)
                    .font(font)
                    .foregroundColor(.parchment)
            }
            if let subtitle {
```
*Drift risk:* Outer spacing 4 is magic (candidate token xs); the spinner uses SwiftUI's native ProgressView tinted parchment, and its label text is ALSO parchment — both render as light-on-dark against the stone-tinted background (see UI-42), the inverse of the app's usual ink-on-parchment text convention.

**UI-46 · `sharedCard`: the success/partial container — thumbnail+chevron, Shared badge, expiry note, `extra()` slot, View scroll link.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:144-193@3f9f9e8` · token `Constants.UI.Padding.normal`
```swift
private func sharedCard(url: String, @ViewBuilder extra: () -> some View) -> some View {
        VStack(spacing: Constants.UI.Padding.normal) {
```
*Drift risk:* `extra()` is EmptyView() for .success and the failed-files block for .partial — the surrounding thumbnail/badge/expiry/View-scroll chrome is byte-for-byte identical between the two states; only the middle insert differs.

**UI-55 · Screen root VStack uses the largest Padding token (`big`).** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:54@3f9f9e8` · token `Constants.UI.Padding.big`
```swift
VStack(spacing: Constants.UI.Padding.big) {
```
*Drift risk:* big = 24 (Constants.swift:13) — the top-level section-to-section gap (statToggles, InteractiveShareSection, journalSection, expiryPicker, ShareStatusSection all separated by this), distinct from the small(8) gaps used WITHIN each section.

### 3.2 Dimensions

**UI-25 · Three-state row opacity system driven by unavailability and inclusion.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:171@3f9f9e8` · token `—` — **[consensus: ui-visual + edge-cases (EDG-91)]**
```swift
.opacity(candidate.unavailableReason != nil ? 0.45 : (candidate.includeInShare ? 1 : 0.6))
```
*Drift risk:* Three magic opacity values, none matching Constants.UI.Opacity (subtle 0.06 / light 0.12 / medium 0.3): unavailable=0.45, included=1.0 (full), excluded-but-available=0.6. Also note "audio removed" and "too large to carry" render IDENTICALLY at the visual level (both just 0.45) — the copy text is the only differentiator between those two unavailable sub-states.

**UI-27 · Include button and kind chip both enforce a 44×44pt minimum tap target.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:178,196@3f9f9e8` · token `—` — **[consensus: ui-visual + edge-cases (EDG-90)]**
```swift
.frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
```
*Drift risk:* 44pt is Apple's HIG minimum tap target, not a Constants token — appears identically at both the include-button (178) and kind-chip (196) sites. Android's equivalent minimum (48dp Material) is a DIFFERENT numeric value on a different density system — don't literally port "44".

**UI-30 · Kind chip's padding, background opacity, and corner radius are all magic literals distinct from any Constants token.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:192-195@3f9f9e8` · token `—`
```swift
.padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(Color.stone.opacity(0.12))
                .cornerRadius(4)
```
*Drift risk:* horizontal 10 doesn't match any Padding token (xs4/small8/normal16/big24) — closest is small(8) but not equal, risk of silent rounding to 8dp on Android. vertical 4 numerically equals Padding.xs but is a literal. background opacity 0.12 numerically equals Constants.UI.Opacity.light (Constants.swift:31) but again is a raw literal, not the token. cornerRadius 4 is smaller than even CornerRadius.small (8) — this chip has a UNIQUE, more-square corner radius not used anywhere else cited in this slice.

**UI-32 · Kind chip's own opacity (1 or 0.35) compounds multiplicatively with the row-level opacity when the row is excluded-but-available.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:203@3f9f9e8` · token `—` — **[consensus: ui-visual + edge-cases (EDG-92)]**
```swift
.opacity(candidate.includeInShare ? 1 : 0.35)
```
*Drift risk:* SwiftUI opacity modifiers compound: an excluded-but-available row already renders at 0.6 opacity (line 171), and its kindChip is nested inside that same view tree with its own 0.35 opacity — net visible chip opacity is 0.6 × 0.35 = 0.21, not a flat 0.35. Android alpha values applied as independent (non-nested/non-multiplying) view properties would need to explicitly multiply these, or the excluded chip will look far more visible (35%) than iOS actually renders it (~21%).

**UI-39 · "Carry the missing files" retry button style.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:55-61@3f9f9e8` · token `Constants.UI.CornerRadius.small`
```swift
Text("Carry the missing files")
                                .font(Constants.Typography.button)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                                .background(Color.stone)
                                .foregroundColor(.parchment)
                                .cornerRadius(Constants.UI.CornerRadius.small)
```
*Drift risk:* padding-vertical 12 is a magic literal (not any Padding token); cornerRadius uses the small token (8, Constants.swift:18) — NOT the normal token (12) used by the main Share/Try Again button (Constants.UI.CornerRadius.normal) or the sharedCard container. This secondary button is deliberately less-rounded than the primary CTA.

**UI-42 · `progressRow` container padding and background opacity are magic literals not matching any `Constants.UI.Opacity` value.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:104-107@3f9f9e8` · token `Constants.UI.CornerRadius.normal` — **[consensus: ui-visual + edge-cases (EDG-95)]**
```swift
.frame(maxWidth: .infinity)
        .padding(.vertical, 14)
        .background(Color.stone.opacity(0.6))
        .cornerRadius(Constants.UI.CornerRadius.normal)
```
*Drift risk:* padding-vertical 14 (matches primaryButton's own 14 at line 200, but not a Constants token). background opacity 0.6 is nowhere near Constants.UI.Opacity's subtle/light/medium (0.06/0.12/0.3) — an easy transcription slip (e.g. mistaking it for 0.06) would make the progress chip nearly invisible on Android.

**UI-52 · `sharedCard` container padding, background, and corner radius.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:190-192@3f9f9e8` · token `Constants.UI.CornerRadius.normal`
```swift
.padding(Constants.UI.Padding.normal)
        .background(Color.parchmentSecondary)
        .cornerRadius(Constants.UI.CornerRadius.normal)
```
*Drift risk:* Both tokens resolve to 16 (padding) and 12 (corner radius) — parchmentSecondary is the same background used by ShareRouteThumbnail and StatToggleRow, establishing it as the standard "card on canvas" surface color.

**UI-53 · Primary button (Share Walk / Try Again) style, and its disabled gate.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:196-205@3f9f9e8` · token `Constants.UI.CornerRadius.normal`
```swift
.padding(.vertical, 14)
            .background(Color.stone)
            .foregroundColor(.parchment)
            .cornerRadius(Constants.UI.CornerRadius.normal)
    }
    .disabled(!viewModel.canShare)
```
*Drift risk:* padding-vertical 14 is magic (matches progressRow's own 14 at line 105, likely not coincidence but still unnamed). canShare (WalkShareViewModel.swift:44-45) is `tourValidationError == nil` — this is the direct link between TourBuilder's cap validation and the Share button's enabled state; an Android port must wire the SAME validation result to button enablement, not a separate/looser check.

**UI-54 · Route thumbnail: fixed 200pt height, shared between pre-share preview and post-share card.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:217-222@3f9f9e8` · token `Constants.UI.CornerRadius.normal`
```swift
struct ShareRouteThumbnail: View {
    let routeData: [RouteDataSampleInterface]

    var body: some View {
        RouteShapeView(routeData: routeData)
            .frame(height: 200)
            .background(Color.parchmentSecondary)
            .cornerRadius(Constants.UI.CornerRadius.normal)
    }
}
```
*Drift risk:* 200pt is a high-visual-impact magic number with no Constants token — this exact component is reused unchanged both while sharing (WalkShareView's routePreview) and once shared (ShareStatusSection's sharedCard), so a single Android composable should own this dimension to avoid the two call sites drifting apart independently.

### 3.3 Colors

**UI-7 · Interactive toggle tint is `moss`.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:24@3f9f9e8` · token `—`
```swift
.tint(.moss)
```
*Drift risk:* Same tint used by the trim toggle (line 68) and every StatToggleRow toggle (WalkShareView.swift:421) — establishes moss as the canonical "on" toggle color app-wide; a mismatched Android switch color would stand out immediately.

**UI-26 · Include-button icon/color flips between `checkmark.circle.fill`/moss (included) and `circle`/fog (excluded).** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:176-177@3f9f9e8` · token `—`
```swift
Image(systemName: candidate.includeInShare ? "checkmark.circle.fill" : "circle")
                .foregroundColor(candidate.includeInShare ? .moss : .fog)
```
*Drift risk:* moss = the same "on"/affirmative color used by every toggle tint in this slice; fog = the neutral/off color. Android's checkbox iconography should follow this same semantic mapping, not a generic Material checkbox tri-state.

### 3.4 Typography

**UI-20 · `ShareSectionLabel`: the shared uppercased/tracked micro-label style used by every WalkShare section header.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:97-102@3f9f9e8` · token `Constants.Typography.micro`
```swift
Text(text.uppercased())
            .font(Constants.Typography.micro)
            .foregroundColor(.fog)
            .tracking(1.5)
```
*Drift risk:* micro = Lato-Regular 9pt (Constants.swift:71); tracking 1.5 is a magic literal (no Constants token for letter-spacing exists) — Android needs an explicit letterSpacing value since Compose does not infer it from anywhere else.

**UI-35 · The `.uploading` progress row overrides `progressRow`'s default font to `Constants.Typography.button`, unlike every other `progressRow` caller.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:21@3f9f9e8` · token `Constants.Typography.button`
```swift
progressRow("Sharing...", font: Constants.Typography.button)
```
*Drift risk:* button = Lato-Bold 17pt (Constants.swift:68) vs the default caption = Lato-Regular 12pt (Constants.swift:69) used by preparingPhotos/uploadingMedia. This makes "Sharing..." render visibly larger/bolder than "Preparing photos… X/Y" or "Carrying your walk… X/Y" — an easy detail to lose if Android builds one generic progress-row composable with a single font param default.

**UI-47 · Chevron and checkmark decorative icons use SwiftUI's system `.caption` text style, NOT the app's `Constants.Typography.caption` custom font.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:152,167@3f9f9e8` · token `—`
```swift
Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundColor(.fog.opacity(0.4))
...
                Image(systemName: "checkmark")
                    .font(.caption)
                    .foregroundColor(.moss)
```
*Drift risk:* `.font(.caption)` here is SwiftUI's built-in Dynamic Type caption style (used because these are SF Symbols, not custom-font text), distinct from `Constants.Typography.caption` (Lato-Regular 12) used everywhere else in these two files. They are numerically close (~12pt) but conceptually different tokens — Android should size these icons independently, not by reusing whatever composable represents "caption text".

### 3.5 Motion

**UI-3 · The Interactive toggle's binding carries an implicit SwiftUI animation with no explicit duration/curve.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:14@3f9f9e8` · token `—`
```swift
Toggle(isOn: $viewModel.interactiveEnabled.animation()) {
```
*Drift risk:* `.animation()` with no args uses SwiftUI's default implicit animation (not the explicit 0.2s easeInOut used at line 72) — a literal Android port could either miss animating the toggle knob itself or copy the wrong duration from the disclosure's .animation(value:) modifier.

**UI-18 · The Interactive section's standard disclosure animation: 0.2 s easeInOut, scoped only to `interactiveEnabled` changes.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:72@3f9f9e8` · token `—` — **[consensus: ui-visual + behavior (BEH-31) + edge-cases (EDG-86) — three-lens]**
```swift
.animation(.easeInOut(duration: 0.2), value: viewModel.interactiveEnabled)
```
*Drift risk:* This exact 0.2s/easeInOut pairing is reused verbatim for the photos-notice disclosure in WalkShareView.swift:247 (value: viewModel.includePhotos) — establishes a project-wide standard for toggle-triggered disclosure animations that Android should match for both surfaces identically.

### 3.6 Conditional render

**UI-9 · The entire recordings/trim disclosure is gated on `interactiveEnabled`.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:29@3f9f9e8`
```swift
if viewModel.interactiveEnabled {
```
*Drift risk:* Everything from the recordings list through the trim toggle is inside this one block — a partial Android port could accidentally show the trim toggle even when Interactive is off.

**UI-10 · Two-branch state: recordings list shown when `hasRecordings`, else a no-recordings message.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:30,44-48@3f9f9e8`
```swift
if viewModel.hasRecordings {
                    recordingsList

                    Text(viewModel.tourTotalsLabel)
...
                } else {
                    Text("No recordings on this walk — the page will carry your route, photos, and moments.")
                        .font(Constants.Typography.caption)
                        .foregroundColor(.fog)
                }
```
*Drift risk:* hasRecordings is `!tourCandidates.isEmpty` (WalkShareViewModel.swift:38) — quote the else-branch text exactly; it is the only copy shown when a walk has zero voice recordings.

**UI-12 · "Voices will be audible" notice only renders if at least one candidate is BOTH included AND available.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:37@3f9f9e8`
```swift
if viewModel.tourCandidates.contains(where: { $0.includeInShare && $0.unavailableReason == nil }) {
```
*Drift risk:* This is a compound condition (both flags), not just "any recordings exist" — a walk with recordings that are all excluded or all unavailable must NOT show this notice. Easy to under-specify to `hasRecordings` on the Android side.

**UI-14 · Validation error text (caps copy) shown only when `tourValidationError` is non-nil.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:50-54@3f9f9e8`
```swift
if let error = viewModel.tourValidationError {
                    Text(error)
                        .font(Constants.Typography.caption)
                        .foregroundColor(.rust)
                }
```
*Drift risk:* rust is the only warning/error color used anywhere in this slice (also used for the .partial failed-files text and the .error message in ShareStatusSection) — see TourBuilder.swift for the exact PIN-accurate cap strings this renders.

**UI-22 · The per-row state branch: unavailable reason text OR (optional transcription preview + size label).** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:147-161@3f9f9e8`
```swift
if let reason = candidate.unavailableReason {
                    Text(reason)
                        .font(Constants.Typography.caption)
                        .foregroundColor(.fog)
                } else {
                    if let preview = candidate.transcription?.trimmingCharacters(in: .whitespacesAndNewlines), !preview.isEmpty {
                        Text(preview)
                            .font(Constants.Typography.caption)
                            .foregroundColor(.fog)
                            .lineLimit(1)
                    }
                    Text(sizeLabel)
                        .font(Constants.Typography.caption)
                        .foregroundColor(.fog)
                }
```
*Drift risk:* This is the true row-state machine: (a) unavailable → reason text only ("audio removed" / "too large to carry", sourced from TourBuilder.swift), no preview/size; (b) available + has non-empty transcription → single-line-truncated preview THEN size label below; (c) available + no transcription → size label only. There is NO separate "preparing" row state — TourBuilder.candidates() resolves availability synchronously from disk before any row ever renders, so a spinner/loading row state does not exist at this pin and should not be invented on Android.

**UI-24 · `includeButton` and `kindChip` are both hidden entirely (not just disabled) for unavailable recordings.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:138-140,167-169@3f9f9e8`
```swift
if candidate.unavailableReason == nil {
                includeButton
            }
...
            if candidate.unavailableReason == nil {
                kindChip
            }
```
*Drift risk:* Same guard condition repeated at both sites — an unavailable row has NO tappable controls at all, only text. A naive Android port disabling-but-still-showing these controls for unavailable rows would diverge visually (extra icon/chip clutter) and interactively (dead-looking but present controls).

**UI-33 · Full 8-state switch driving the Share button through progress, success, partial, and error.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:14-79@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-33)]**
```swift
switch viewModel.shareState {
        case .idle:
...
        case .error(let message):
```
*Drift risk:* Mirrors WalkShareViewModel.ShareState exactly (WalkShareViewModel.swift:110-119) — these are the definitive 8 UI states for the status/progress card: idle, uploading, preparingPhotos, photosDropped, uploadingMedia, success, partial, error. An Android state machine missing any one of these (e.g. collapsing preparingPhotos into uploading) would lose a distinct progress copy string.

**UI-38 · Partial state renders the shared card PLUS an extra failed-files block with two sub-states gated by `repairUnavailable`.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:38-65@3f9f9e8` · token `Constants.UI.Padding.small` — **[consensus: ui-visual + behavior (BEH-35), identical citation]**
```swift
case .partial(let url, let failedCount):
            sharedCard(url: url) {
                VStack(spacing: Constants.UI.Padding.small) {
                    Text("\(failedCount) file\(failedCount == 1 ? "" : "s") didn't make it — they'll show as unavailable on the page.")
...
                    if viewModel.repairUnavailable {
                        Text("These files can no longer be carried — the walk's recordings have changed.")
...
                    } else {
                        Button {
                            viewModel.beginRetry()
                        } label: {
                            Text("Carry the missing files")
```
*Drift risk:* This IS the "partial/repair card" the port must reproduce precisely: failedCount pluralization ("file"/"files") always shown in rust, THEN either a static fog-colored explanation (repairUnavailable=true, no retry possible) OR a stone/parchment retry button reading "Carry the missing files" (repairUnavailable=false). Losing the repairUnavailable branch would let users tap a dead retry button forever.

**UI-43 · `droppedPhotosPrompt` is an inline card-state substitution within the scrollable form, NOT a native alert/dialog/sheet.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:114-137@3f9f9e8` · token `Constants.UI.Padding.small`
```swift
private func droppedPhotosPrompt(prepared: Int, dropped: Int) -> some View {
        VStack(spacing: Constants.UI.Padding.small) {
```
*Drift risk:* Despite the lens task's shorthand "consent dialog", this is a plain VStack that the @ViewBuilder switch renders IN PLACE of the normal button — same visual layer as the rest of the ScrollView content, not a modal overlay. Implementing it as an Android AlertDialog/BottomSheet would diverge from how it actually presents on iOS (inline, scrollable, no dimmed backdrop).

**UI-56 · Top-level screen layout flips entirely between the shared and editing states, and the whole editing form is disabled while a share is in flight.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:31-36,55-80@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-37, BEH-38) + edge-cases (EDG-97)]**
```swift
private var isShareInFlight: Bool {
        switch viewModel.shareState {
        case .preparingPhotos, .photosDropped, .uploading, .uploadingMedia: return true
        default: return false
        }
    }
...
                        Group {
                            statToggles
                            InteractiveShareSection(viewModel: viewModel)
                            journalSection
                            expiryPicker
                        }
                        .disabled(isShareInFlight)
```
*Drift risk:* isShareInFlight covers FOUR of the eight ShareState cases (preparingPhotos, photosDropped, uploading, uploadingMedia) — the entire form INCLUDING the Interactive section (all recording checkboxes, kind chips, trim toggle) becomes non-interactive during all four. Critically, none of InteractiveShareSection's controls read `@Environment(\.isEnabled)` to change their own appearance — the default Toggle style will visually dim, but the `.buttonStyle(.plain)` includeButton/kindChip Buttons will NOT automatically gray out under SwiftUI's default disabled-styling behavior, so recording rows may look fully interactive while actually being frozen. An Android Compose port using default `Modifier.clickable(enabled = false)` styling (which often auto-dims) could visually diverge from this iOS behavior in the opposite direction.

**UI-57 · `isDismissLocked` is a narrower gate than `isShareInFlight` — only covers uploading/uploadingMedia, deliberately excluding preparingPhotos/photosDropped.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:44-49,109@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-37) + edge-cases (EDG-98)]**
```swift
private var isDismissLocked: Bool {
        switch viewModel.shareState {
        case .uploading, .uploadingMedia: return true
        default: return false
        }
    }
...
        .interactiveDismissDisabled(isDismissLocked)
```
*Drift risk:* Two similar-but-different gates exist side by side (isShareInFlight for form-disable, isDismissLocked for swipe-to-dismiss/Cancel-button-visibility) — an Android port collapsing these into a single flag would either let users swipe away a live upload (if using the wider flag) or block the toolbar Cancel button during a merely-local, cancellable photo export (if using the narrower flag in the wrong place).

**UI-59 · Toolbar Done/Cancel buttons are mutually exclusive and independently gated.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:92-106@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-39)]**
```swift
if isShared {
                        Button("Done") { dismiss() }
                            .foregroundColor(.stone)
                    }
...
                    if !isShared && !isDismissLocked {
                        Button("Cancel") {
                            viewModel.cancelShare()
                            dismiss()
                        }
                        .foregroundColor(.stone)
                    }
```
*Drift risk:* Both buttons use stone foreground (no bordered/system tint override). Cancel's tap handler calls cancelShare() BEFORE dismiss() — order matters so the in-flight task is actually cancelled, not just the sheet dismissed out from under a running Task.

**UI-65 · `tourValidationError` only computes (and thus only ever renders) while `interactiveEnabled` is true.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:40-42@3f9f9e8`
```swift
var tourValidationError: String? {
        interactiveEnabled ? TourBuilder.validationError(for: tourCandidates) : nil
    }
```
*Drift risk:* This is the guard behind InteractiveShareSection.swift:50's `if let error = viewModel.tourValidationError` — even if tourCandidates happens to be over-cap, no error text or Share-button-disable occurs while Interactive is off, since the classic (non-interactive) share path doesn't ship a tour payload at all.

**UI-66 · The canonical 8-case `ShareState` enum that `ShareStatusSection`'s `@ViewBuilder` switch renders against.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:110-119@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-76) + data (DAT-58) — three-lens]**
```swift
enum ShareState: Equatable {
        case idle
        case preparingPhotos(completed: Int, total: Int) // hi-res export (pre-POST)
        case photosDropped(prepared: Int, dropped: Int)  // export done short; pre-POST consent pause
        case uploading                                   // POST phase
        case uploadingMedia(completed: Int, total: Int)  // PUT phase
        case success(url: String)
        case partial(url: String, failedCount: Int)      // page live, some media missing
        case error(message: String)
    }
```
*Drift risk:* This is the definitive state list an Android sealed class / enum must mirror one-to-one — each case's associated values (completed/total, prepared/dropped, failedCount) are exactly what the dynamic copy strings interpolate.

**UI-67 · `hasPinnedPhotos` requires three conditions ANDed together, gating both the Reliquary Photos row's visibility and the auto-enable eligibility.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:26-30@3f9f9e8`
```swift
var hasPinnedPhotos: Bool {
        pinnedPhotoCount > 0
            && UserPreferences.walkReliquaryEnabled.value
            && isPhotosGranted()
    }
```
*Drift risk:* All three must be true: photos actually pinned, the Reliquary feature preference enabled, AND Photos permission currently granted. A walk with pinned photos but a since-revoked Photos permission (or the Reliquary preference off) must hide the row entirely, not show it disabled — losing any one of these three checks changes which walks show the Photos toggle at all.

**UI-68 · Trim outcome (trimM/keptWindow) is reported by ACTUAL RESULT, never by intent — a route too short to trim always reports trimM=0 even when trimEnabled=true.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:454-457,469-482@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-87) + data (DAT-64) + edge-cases (EDG-145, EDG-146) — four-lens]**
```swift
// Report the trim by OUTCOME, not intent: RouteTrimmer silently no-ops on a route too short to trim, so trimM/keptWindow must reflect what actually happened — never claim a 150m trim while shipping the full, untrimmed route.
        let trimmed = RouteTrimmer.trim(downsampled, meters: Double(Self.trimMeters))
        let didTrim = trimmed.count < downsampled.count
        let trimM = didTrim ? Self.trimMeters : 0
```
*Drift risk:* canTrimRoute (which drives InteractiveShareSection's ternary copy and the trim toggle's disabled state) and computeInteractiveRoute() are DELIBERATELY required to read the identical downsampledRoutePoints() — confirmed by WalkShareInteractiveTests.swift:225-247 (testShortRouteTrimIsHonestAndLeavesWaypointsUnfiltered). Android must not let the "is this walk trimmable" check and the "actually trim it" step diverge onto different route arrays, or the UI could show "too short to trim" while the payload silently trims anyway (or vice versa).

**UI-69 · The pre-POST photo-export progress state is primed synchronously with completed:0 before the async export loop starts.** `.../WalkShareViewModel+ShareOrchestration.swift:35-40@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-52)]**
```swift
// Prime synchronously, same reason as .uploadingMedia below — the
            // first progress tick only fires after the first photo finishes
            // loading, so without this the phase would never actually become
            // .preparingPhotos and applyPreparingPhotosProgress's guard would
            // reject every tick.
            shareState = .preparingPhotos(completed: 0, total: exportList.count)
```
*Drift risk:* Without this synchronous priming, the progress card would stay on a blank/idle frame until the first photo finishes exporting, instead of immediately showing "Preparing photos… 0/N". An Android ViewModel emitting its first progress state only from within the async loop's callback would reproduce this exact same UI-blank-flash bug.

**UI-70 · The dropped-photos consent pause triggers only when the export comes up strictly short of what was requested.** `.../WalkShareViewModel+ShareOrchestration.swift:61-66@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-54) + edge-cases (EDG-111)]**
```swift
let dropped = exportCount - tourPhotos.count
        if dropped > 0 {
            pendingTourPhotos = tourPhotos
            shareState = .photosDropped(prepared: tourPhotos.count, dropped: dropped)
            return
        }
```
*Drift risk:* dropped is a DERIVED count (requested minus actually-exported), not a flag the exporter itself sets — any exporter behavior change that silently returns fewer items without the ViewModel comparing counts would never trigger this consent pause on Android.

**UI-71 · `continueShareWithoutDroppedPhotos()` claims `.uploading` SYNCHRONOUSLY before spawning its Task, so the consent prompt's buttons vanish within the same runloop turn.** `.../WalkShareViewModel+ShareOrchestration.swift:79-82@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-55, BEH-92) + edge-cases (EDG-112, EDG-179)]**
```swift
func continueShareWithoutDroppedPhotos() {
        guard shareTask == nil else { return }
        shareState = .uploading
        shareTask = Task { [weak self] in
```
*Drift risk:* Confirmed by WalkShareInteractiveTests.swift:542-557 (testContinueShareClaimsUploadingSynchronously) — a double-tap on "Share without them" within the same frame must be a no-op, not a second concurrent share. If Android's equivalent flips state asynchronously (e.g. inside the coroutine instead of before launching it), the buttons would remain tappable for one extra frame, opening a double-tap window this test specifically guards against.

**UI-72 · `repairUnavailable` is set true, switching the `.partial` card into its non-retryable sub-state, only when NO cached failure resolves to anything uploadable.** `.../WalkShareViewModel+ShareOrchestration.swift:275-281@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-69)]**
```swift
guard !uploadable.isEmpty else {
            ShareService.cacheFailedMedia(remainingAfterResolve, walkID: uuid)
            // None of the cached failures still match anything carryable —
            // don't silently loop back to the same retry button forever.
            repairUnavailable = true
            shareState = .partial(url: cached.url, failedCount: remainingAfterResolve.count)
            return
        }
```
*Drift risk:* This is what flips ShareStatusSection's .partial branch from showing the "Carry the missing files" button to showing the static "These files can no longer be carried" text (ShareStatusSection.swift:46-63) — an Android retry flow that doesn't distinguish "nothing resolved" from "some things still failed" would keep offering a retry button that can never succeed.

**UI-75 · `classify()`: the default spoken-vs-ambient auto-kind rule shown as the initial kindChip label before any user flip.** `Pilgrim/Models/Share/TourBuilder.swift:34-41@3f9f9e8` — **[consensus: ui-visual + data (DAT-46), identical citation; + edge-cases EDG-60..EDG-63]**
```swift
static func classify(transcription: String?) -> TourRecordingKind {
        guard let text = transcription?.trimmingCharacters(in: .whitespacesAndNewlines) else {
            return .spoken
        }
        let wordCount = text.split(whereSeparator: \.isWhitespace).count
        if wordCount < 8 { return .ambient }
        return .spoken
    }
```
*Drift risk:* A missing/nil transcription defaults to .spoken (shown as "voice"), NOT .ambient — only a transcription that EXISTS but has fewer than 8 words is classified ambient. Deliberately no words-per-minute gate (per the doc comment above this function) — an Android port adding a wpm-based heuristic would misclassify slow contemplative speech as ambience, contrary to the explicit design intent stated in source.

**UI-80 · Test-pinned contract: a route too short to actually trim must report trimM=0, not the requested 150, even with trimEnabled=true — and must leave ALL waypoints unfiltered, including ones before the walk's own start time.** `UnitTests/WalkShareInteractiveTests.swift:225-247@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-101), identical citation; + edge-cases EDG-171]**
```swift
XCTAssertEqual(payload.tour?.trimM, 0, "a route too short to actually trim must report trimM 0, not the requested 150 — RouteTrimmer silently no-ops on it")
...
        XCTAssertTrue(labels.contains("Before the first fix"), "no real trim happened, so nothing should be filtered out — not even a waypoint before the first GPS fix")
```
*Drift risk:* Confirms the "too short to trim" copy branch in InteractiveShareSection.swift's ternary (line 63) must correspond EXACTLY to trimM ending up 0 in the payload — an Android UI that shows "too short to trim" text while still applying a partial/best-effort trim (or vice versa) would break this honesty invariant that iOS enforces end-to-end.

### 3.7 Copy strings

**UI-2 · Section header text for the Interactive block.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:12@3f9f9e8`
```swift
ShareSectionLabel(text: "Walk with me")
```
*Drift risk:* Rendered uppercased with 1.5pt tracking by ShareSectionLabel (line 98-101) — a literal-string port that skips the uppercasing/tracking would look visually inconsistent even with correct text.

**UI-5 · Interactive toggle title text, body font, ink color.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:16-18@3f9f9e8` · token `Constants.Typography.body`
```swift
Text("Interactive")
                        .font(Constants.Typography.body)
                        .foregroundColor(.ink)
```
*Drift risk:* Typography.body = CormorantGaramond-Regular 17pt (Constants.swift:67); .ink defined Color.swift:18-20.

**UI-6 · Interactive toggle's full subtitle/explainer copy, caption font, fog color.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:19-21@3f9f9e8` · token `Constants.Typography.caption`
```swift
Text("Viewers walk your route on a living map — your recordings play where you made them, photos appear where you took them. Recordings and full-size photos upload over your connection.")
                        .font(Constants.Typography.caption)
                        .foregroundColor(.fog)
```
*Drift risk:* Long-form copy with two em-dash clauses — easy to lose exact phrasing ("where you made them" / "where you took them") in translation to an Android string resource.

**UI-11 · Dynamic totals caption below the recordings list.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:33-35@3f9f9e8` · token `Constants.Typography.caption`
```swift
Text(viewModel.tourTotalsLabel)
                        .font(Constants.Typography.caption)
                        .foregroundColor(.fog)
```
*Drift risk:* tourTotalsLabel's exact wording rules (singular "recording", MB/min formatting, photo count) live in WalkShareViewModel.swift:47-59 — see UI-63 for the full format contract.

**UI-13 · Voices-audible privacy notice text, with dimension and motion detail.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:38-43@3f9f9e8` · token `Constants.UI.Padding.normal`
```swift
Text("Voices will be audible to anyone with the link.")
                            .font(Constants.Typography.caption)
                            .foregroundColor(.fog)
                            .padding(.horizontal, Constants.UI.Padding.normal)
                            .transition(.opacity)
```
*Drift risk:* The horizontal padding (16, Constants.swift:12) is applied ONLY to this notice line, not to sibling text in the same leading-aligned VStack — it renders visually indented/recessed relative to the totals caption above it. The `.transition(.opacity)` is only actually animated by the outer `.animation(.easeInOut(duration: 0.2), value: viewModel.interactiveEnabled)` at line 72, which is scoped to interactiveEnabled — toggling individual recording checkboxes (which can also flip this condition true/false) will NOT be animated by that modifier, since its `value:` only watches interactiveEnabled. A literal Android port using a single blanket animated-visibility flag would over-animate this transition on every candidate checkbox tap.

**UI-15 · Trim toggle title and conditional subtitle (`canTrimRoute` ternary).** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:58,61-63@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-32) + edge-cases (EDG-85)]**
```swift
Text("Trim start & end")
...
                        Text(viewModel.canTrimRoute
                            ? "Keeps the first and last 150 m off the shared map — including photos and waymarkers there."
                            : "This walk is too short to trim.")
```
*Drift risk:* The "150 m" in this copy is a HARDCODED literal — it does not interpolate `WalkShareViewModel.trimMeters` (WalkShareViewModel.swift:36, also 150). If trimMeters is ever tuned, this string goes stale silently; an Android string resource should be flagged as needing the same manual-sync discipline (or better, should interpolate the actual constant, which iOS itself does not do).

**UI-21 · Per-recording row title line: index, duration (M:SS), and clock-time start.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:143@3f9f9e8` — **[consensus: ui-visual + edge-cases (EDG-87, EDG-89)]**
```swift
Text("Recording \(candidate.id + 1) · \(durationLabel) · \(startLabel)")
```
*Drift risk:* durationLabel is `String(format: "%d:%02d", seconds / 60, seconds % 60)` (line 121) — minutes are NOT zero-padded (e.g. "2:05", not "02:05"); startLabel uses DateFormatter.timeStyle = .short (locale-dependent, e.g. "9:41 AM"). Both formatting rules must be replicated exactly, including the " · " separators (space-middot-space).

**UI-29 · Kind chip label maps the domain enum to lowercase display words "voice"/"ambience".** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:132-134@3f9f9e8` — **[consensus: ui-visual + edge-cases (EDG-88)]**
```swift
private var kindLabel: String {
        candidate.effectiveKind == .spoken ? "voice" : "ambience"
    }
```
*Drift risk:* The underlying enum cases are `.spoken`/`.ambient` (TourBuilder.swift:3) but the UI never shows those words — it shows "voice"/"ambience". A port that surfaces the raw enum rawValue string would show the wrong word ("spoken"/"ambient") to users.

**UI-34 · Idle-state Share button label.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:16@3f9f9e8`
```swift
primaryButton("Share Walk") {
```
*Drift risk:* Exact capitalization "Share Walk" (title case, two words).

**UI-36 · Photo-export progress copy with fractional count and single-character ellipsis.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:24@3f9f9e8`
```swift
progressRow("Preparing photos… \(done)/\(total)")
```
*Drift risk:* "…" is the single Unicode ellipsis character (U+2026), not three ASCII periods — a naive Android string resource using "..." would render with different kerning/width than iOS.

**UI-37 · Media-upload progress copy with a secondary de-emphasized subtitle.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:29-33@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-34)]**
```swift
progressRow(
                "Carrying your walk… \(completed)/\(total)",
                subtitle: "keep Pilgrim open while your walk uploads"
            )
```
*Drift risk:* The subtitle is lowercase, no trailing period, no app-icon/branding punctuation — an easy detail to "fix" into Title Case or add a period during translation, which would look like a typo relative to the source.

**UI-40 · Error-state message and retry button.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:69-76@3f9f9e8`
```swift
Text(message)
                    .font(Constants.Typography.caption)
                    .foregroundColor(.rust)
                    .multilineTextAlignment(.center)

                primaryButton("Try Again") {
```
*Drift risk:* `message` is `error.localizedDescription` — a raw system/network error string, not app-authored copy; Android's equivalent error surface will produce DIFFERENT text for the same failure class (different exception message), so exact string parity is impossible here — only the rust/centered/caption STYLING is portable, not the text content itself.

**UI-44 · Dropped-photos consent copy, pluralized denominator.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:116@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-36) + edge-cases (EDG-96)]**
```swift
Text("\(dropped) of \(prepared + dropped) photo\(dropped == 1 ? "" : "s") couldn't be prepared — they may still be waiting in iCloud.")
```
*Drift risk:* Pluralization keys off `dropped` (not the total) — "1 of 3 photos couldn't be prepared" is still plural ("photos") because dropped=1 pluralizes the denominator noun too; only exactly-1-dropped-of-1-total would read "1 of 1 photo". Precise plural-rule replication matters for Android string resources with quantity variants.

**UI-45 · Dropped-photos prompt's two action labels.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:121-134@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-36)]**
```swift
primaryButton("Share without them") {
                viewModel.continueShareWithoutDroppedPhotos()
            }

            Button {
                viewModel.cancelDroppedPhotoShare()
            } label: {
                Text("Don't share yet")
```
*Drift risk:* "Share without them" is the primary (stone-filled) button; "Don't share yet" is a plain fog-colored text button below it, padding-vertical 12 (same recurring magic value as the retry button and "View scroll" link) — establishes a visual hierarchy (filled=proceed, plain=decline) Android must preserve, not two equally-weighted buttons.

**UI-49 · "Shared" badge row: text + checkmark icon.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:162-169@3f9f9e8` · token `Constants.Typography.body`
```swift
HStack(spacing: 6) {
                Text("Shared")
                    .font(Constants.Typography.body)
                    .foregroundColor(.stone)
                Image(systemName: "checkmark")
                    .font(.caption)
                    .foregroundColor(.moss)
            }
```
*Drift risk:* HStack spacing 6 is a magic literal matching no Constants token. "Shared" text is stone-colored (not ink, not moss) while the checkmark icon next to it IS moss — a subtle two-tone detail.

**UI-50 · Expiry restatement on the shared card, italicized.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:171-174@3f9f9e8` · token `Constants.Typography.caption`
```swift
Text("Returns to the trail on \(viewModel.formattedExpiry)")
                .font(Constants.Typography.caption)
                .foregroundColor(.fog)
                .italic()
```
*Drift risk:* The ONLY italicized text found across all seven files — Android must apply an italic style explicitly since Compose text does not default to italic for caption-role text. formattedExpiry uses DateFormatter.dateStyle = .long (locale-dependent, e.g. "November 12, 2026").

**UI-51 · "View scroll" link — same visual pattern (fog, padding-vertical 12, full-width, plain) as "Don't share yet".** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:181-188@3f9f9e8` — **[consensus: ui-visual + edge-cases (EDG-94)]**
```swift
Text("View scroll")
                .font(Constants.Typography.caption)
                .foregroundColor(.fog)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
```
*Drift risk:* padding-vertical 12 recurs a third time in this file (retry button, Don't-share-yet, View-scroll) without ever being a named token — strong candidate for Android to define as an explicit "plainButtonVerticalPadding" constant even though iOS itself never named it.

**UI-58 · Toolbar principal title flips between "Share Walk" and "Walk Shared".** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:88-91@3f9f9e8` · token `Constants.Typography.heading`
```swift
Text(isShared ? "Walk Shared" : "Share Walk")
                        .font(Constants.Typography.heading)
                        .foregroundColor(.ink)
```
*Drift risk:* heading = CormorantGaramond-SemiBold 17pt (Constants.swift:63) — note the word order flips ("Share Walk" → "Walk Shared"), not just a suffix/prefix change; a lazy find-replace could get this backwards.

**UI-63 · `tourTotalsLabel`: the full format contract for the recordings/photos totals caption.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:47-59@3f9f9e8` — **[consensus: ui-visual + edge-cases (EDG-121, EDG-122)]**
```swift
if count > 0 {
            let mb = Double(bytes) / 1_048_576
            parts.append("\(count) recording\(count == 1 ? "" : "s") · \(String(format: "%.1f", mb)) MB · \(Int(seconds / 60)) min")
        }
        if photoCount > 0 {
            parts.append("\(photoCount) hi-res photo\(photoCount == 1 ? "" : "s")")
        }
        return parts.isEmpty ? "no recordings included" : parts.joined(separator: " · ")
```
*Drift risk:* Three independently-pluralized clauses joined by " · " (space-middot-space, same separator as the row title format): "N recording(s) · X.X MB · Y min" and "N hi-res photo(s)", falling back to the literal string "no recordings included" when both parts are empty — confirmed verbatim by WalkShareInteractiveTests.swift:268. photoCount comes from interactivePhotoExportList().count (capped at 20, WalkShareViewModel+ShareOrchestration.swift:406-414), NOT the raw pinnedPhotoCount — confirmed by WalkShareInteractiveTests.swift:287-288.

**UI-73 · Aggregate caps copy derives its minutes from `maxTotalSeconds` (6480 s = 108 min) — the string has no hardcoded minute figure at the pin.** `Pilgrim/Models/Share/TourBuilder.swift:24-27,90-92@3f9f9e8` · token `TourBuilder.maxTotalSeconds = 6480`
```swift
if seconds > maxTotalSeconds { return "Recordings total \(Int(seconds / 60)) minutes — the page carries at most \(Int(maxTotalSeconds / 60))." }
```
*Drift risk:* Hardcoding 108 (or the old 45) in the Android string instead of deriving from the constant silently drifts the copy when the cap next changes. **Note:** EDG-69 flags that the sibling BYTES message does hardcode "60 MB" — see §7.

**UI-74 · The two unavailable-recording reason strings rendered verbatim in `TourRecordingRow`.** `Pilgrim/Models/Share/TourBuilder.swift:57-61@3f9f9e8` · token `TourBuilder.maxFileBytes` — **[consensus: ui-visual + behavior (BEH-19) + data (DAT-49) + edge-cases (EDG-68)]**
```swift
if size == nil || size == 0 {
                unavailableReason = "audio removed"
            } else if let size, size > maxFileBytes {
                unavailableReason = "too large to carry"
            } else {
```
*Drift risk:* Both lowercase, no trailing punctuation, both 2-3 words. "too large to carry" is gated on maxFileBytes = 15 * 1024 * 1024 (15 MB per-file, distinct from the 60 MB TOTAL cap) — an Android port must apply the per-file cap check to the right threshold, not accidentally reuse the total-bytes cap.

**UI-76 · Test-pinned exact wording for the empty-recordings totals label.** `UnitTests/WalkShareInteractiveTests.swift:266-268@3f9f9e8`
```swift
let emptyVM = WalkShareViewModel(walk: WalkDataFactory.makeWalk())
        XCTAssertEqual(emptyVM.tourTotalsLabel, "no recordings included")
```
*Drift risk:* Locks the exact fallback string an Android string resource must match character-for-character when a walk has interactiveEnabled with zero eligible recordings and no photos.

**UI-77 · Test-pinned singular-form contract for the totals label (no trailing 's' on a count of 1).** `UnitTests/WalkShareInteractiveTests.swift:274-275@3f9f9e8`
```swift
XCTAssertTrue(singularVM.tourTotalsLabel.hasPrefix("1 recording ·"), "singular wording must not add a trailing s")
        XCTAssertFalse(singularVM.tourTotalsLabel.contains("1 recordings"))
```
*Drift risk:* An Android string-plurals resource must explicitly define the "one" quantity form as "recording" (no s) — a lazy implementation using string concatenation with a hardcoded "s" suffix would fail this exact case.

**UI-78 · Test-pinned 20-photo export cap reflected in the totals label wording, not the raw pinned-photo count.** `UnitTests/WalkShareInteractiveTests.swift:287-288@3f9f9e8` — **[consensus: ui-visual + edge-cases (EDG-173)]**
```swift
XCTAssertEqual(cappedVM.interactivePhotoExportList().count, 20, "the export list itself caps at 20")
        XCTAssertTrue(cappedVM.tourTotalsLabel.contains("20 hi-res photos"), "the label's photo count must cap at the export list's count, not the full pinned count")
```
*Drift risk:* With 25 pinned photos, the totals caption must read "20 hi-res photos", not "25" — the cap (interactivePhotoExportList's `.prefix(20)`, WalkShareViewModel+ShareOrchestration.swift:406-414) must feed the DISPLAYED count, not just the upload behavior, or the pre-share caption would overpromise what actually uploads.

### 3.8 Toggle interactions

**UI-8 · Turning Interactive on calls `prepareInteractive()`, which is the entry point for the photos auto-enable side effect.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:25-27@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-30), identical citation]**
```swift
.onChange(of: viewModel.interactiveEnabled) { _, on in
                if on { viewModel.prepareInteractive() }
            }
```
*Drift risk:* The trigger lives in the View (onChange), but the actual auto-enable logic lives in the ViewModel (WalkShareViewModel.swift:217-228) — an Android port that only inspects the ViewModel could miss that this must fire on toggle-ON specifically, not on VM init or on every recomposition.

**UI-16 · Trim toggle is disabled when the route is too short to trim.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:68-69@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-32)]**
```swift
.tint(.moss)
                .disabled(!viewModel.canTrimRoute)
```
*Drift risk:* canTrimRoute (WalkShareViewModel.swift:61-64) is computed from RouteTrimmer.canTrim over the SAME downsampled points computeInteractiveRoute() would trim — a divergent computation on Android could disable the toggle for a route that would actually trim, or vice versa.

**UI-17 · `trimEnabled` defaults to true independently of the Interactive toggle — it is a default published value, not an onChange-driven auto-enable.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:34@3f9f9e8`
```swift
@Published var trimEnabled = true
```
*Drift risk:* Unlike includePhotos (which flips on via a one-shot side effect inside prepareInteractive()), trimEnabled simply STARTS true and is only ever surfaced to the user once interactiveEnabled is also true (the toggle lives inside the `if viewModel.interactiveEnabled` block). There is no "Interactive turning on flips trim on" wiring to replicate — Android only needs a default-true state, not an event handler.

**UI-60 · Reliquary Photos toggle row and its own "visible to anyone" disclosure notice mirror the Interactive section's own pattern exactly, including the identical 0.2 s animation.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:232-248@3f9f9e8` · token `Constants.UI.Padding.normal`
```swift
if viewModel.hasPinnedPhotos {
                VStack(alignment: .leading, spacing: 4) {
                    StatToggleRow(
                        title: "Reliquary Photos",
                        value: "\(viewModel.pinnedPhotoCount) \(viewModel.pinnedPhotoCount == 1 ? "photo" : "photos") you pinned",
                        isOn: $viewModel.includePhotos
                    )
                    if viewModel.includePhotos {
                        Text("Photos will be visible to anyone with the link.")
                            .font(Constants.Typography.caption)
                            .foregroundColor(.fog)
                            .padding(.horizontal, Constants.UI.Padding.normal)
                            .transition(.opacity)
                    }
                }
                .animation(.easeInOut(duration: 0.2), value: viewModel.includePhotos)
            }
```
*Drift risk:* This is the toggle that Interactive's prepareInteractive() flips ON automatically (WalkShareViewModel.swift:224-227) — visible-only when hasPinnedPhotos is true. The "Photos will be visible to anyone with the link." phrasing is a near-exact template match to Interactive's own "Voices will be audible to anyone with the link." (InteractiveShareSection.swift:38) — same caption/fog/padding-normal/transition-opacity/0.2s-easeInOut recipe. An Android implementation should treat these as ONE reusable disclosure-notice component, not two independently-styled ones, to avoid the two drifting apart on a future edit.

**UI-62 · `interactiveEnabled` and `includePhotos` both default to false; `trimEnabled` alone defaults to true.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:17,32@3f9f9e8`
```swift
@Published var interactiveEnabled = false
    @Published var tourCandidates: [TourRecordingCandidate] = []
    @Published var trimEnabled = true
```
*Drift risk:* A fresh WalkShareView always opens with Interactive OFF (user must opt in explicitly) — Android must not default this on, even though trimEnabled (a sibling published property) defaults ON.

**UI-64 · `prepareInteractive()`: the canonical Interactive-to-photos auto-enable wiring, gated by a one-shot latch.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:217-228@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-80), identical citation; + edge-cases EDG-131, EDG-132]**
```swift
func prepareInteractive() {
        if tourCandidates.isEmpty {
            tourCandidates = TourBuilder.candidates(for: walk)
        }
        // Interactive means "carry the media": the first enable brings photos
        // along automatically (the spec's auto-enable); the walker can still
        // switch them off afterwards and we never re-flip.
        if hasPinnedPhotos && !didAutoEnablePhotos {
            didAutoEnablePhotos = true
            includePhotos = true
        }
    }
```
*Drift risk:* The auto-enable is ONE-SHOT (didAutoEnablePhotos latch, declared private at line 66) — turning Interactive off then on again within the same session must NOT re-flip includePhotos back on if the user had manually turned it off, confirmed by WalkShareInteractiveTests.swift:56-66 (testInteractiveAutoEnablesPhotosOnce). An Android port using a derived/computed default instead of a persisted one-shot flag would re-trigger the auto-enable every time Interactive is toggled, fighting the user's explicit off choice. **See §7 for the "persisted" wording conflict with BEH-80.**

**UI-79 · Test-pinned behavioral contract: photos auto-enable fires exactly once; a manual off after that is never re-flipped by a later `prepareInteractive()` call.** `UnitTests/WalkShareInteractiveTests.swift:56-66@3f9f9e8` — **[consensus: ui-visual + behavior (BEH-98), identical citation]**
```swift
vm.interactiveEnabled = true
        vm.prepareInteractive()
        XCTAssertTrue(vm.includePhotos)
        vm.includePhotos = false
        vm.prepareInteractive()
        XCTAssertFalse(vm.includePhotos, "auto-enable happens once; the walker's off stays off")
```
*Drift risk:* Directly corroborates WalkShareViewModel.swift:224-227's one-shot latch — this is the exact repro an Android ViewModel unit test should also assert: toggle Interactive on (photos auto-enable), manually turn Photos off, toggle Interactive again (e.g. off then on, or just call the equivalent prepare function again) — Photos must STAY off.

### 3.9 Accessibility

**UI-23 · Row's title+reason/preview+size texts are combined into one VoiceOver element.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:163@3f9f9e8`
```swift
.accessibilityElement(children: .combine)
```
*Drift risk:* Without an equivalent Compose `mergeDescendants`/semantics grouping, TalkBack would read each Text as a separate stop instead of one combined announcement.

**UI-28 · Include button's full VoiceOver contract: label, value, hint.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:182-184@3f9f9e8`
```swift
.accessibilityLabel("Include recording \(candidate.id + 1)")
        .accessibilityValue(candidate.includeInShare ? "included" : "excluded")
        .accessibilityHint("Double tap to toggle")
```
*Drift risk:* Three distinct a11y strings (label/value/hint) — an Android contentDescription-only port would collapse these into one string and lose the state announcement ("included"/"excluded") that VoiceOver reads separately from the label.

**UI-31 · Kind chip's full VoiceOver contract: label, value, hint.** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:200-202@3f9f9e8`
```swift
.accessibilityLabel("Recording \(candidate.id + 1) kind, \(kindLabel)")
        .accessibilityValue(candidate.effectiveKind == .spoken ? "voice" : "ambience")
        .accessibilityHint("Double tap to switch")
```
*Drift risk:* accessibilityValue re-derives "voice"/"ambience" independently from kindLabel (a second inline ternary on the same condition) rather than reusing the kindLabel property — cosmetically identical today but a future edit to kindLabel's wording could silently desync from this accessibilityValue if only one site is updated.

**UI-48 · Decorative chevron is explicitly hidden from accessibility; the containing Button carries its own label/hint instead.** `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift:154-160@3f9f9e8`
```swift
.padding(8)
                    .accessibilityHidden(true)
...
        .accessibilityLabel("View shared walk page")
        .accessibilityHint("Opens the scroll of your shared walk")
```
*Drift risk:* Chevron padding(8) matches Padding.small numerically but is a literal. Without accessibilityHidden equivalence on Android, TalkBack would double-announce ("chevron right" AND "View shared walk page") for the same tappable region.

**UI-61 · `StatToggleRow`'s Toggle has an empty/hidden label and is NOT wrapped in a combined accessibility element with its title Text, unlike `TourRecordingRow`'s equivalent grouping.** `Pilgrim/Scenes/WalkShare/WalkShareView.swift:406-428@3f9f9e8` · token `Constants.UI.CornerRadius.small`
```swift
Toggle("", isOn: $isOn)
                .labelsHidden()
                .tint(.moss)
        }
        .padding(.horizontal, Constants.UI.Padding.normal)
        .padding(.vertical, 10)
        .background(Color.parchmentSecondary)
        .cornerRadius(Constants.UI.CornerRadius.small)
```
*Drift risk:* TourRecordingRow explicitly combines its title+detail texts with `.accessibilityElement(children: .combine)` (InteractiveShareSection.swift:163), but StatToggleRow has no equivalent grouping — VoiceOver may announce the row's title Text and the empty-labeled Toggle as two separate stops ("Distance" … "toggle, on") instead of one coherent "Distance, toggle, on" announcement. Worth verifying on-device before assuming Android needs the same ungrouped structure; this may be an existing iOS accessibility gap rather than an intended design, and padding-vertical 10 here is a magic literal distinct from every other padding value cataloged in this slice.

---

## 4. Data

74 data-lens findings.

### 4.1 Entities

#### DAT-6 · `SharePayload` — top-level Encodable-only wire model
`Pilgrim/Models/Share/SharePayload.swift:3-19@3f9f9e8`

| Swift field | type | wire name |
|---|---|---|
| `stats` | `Stats` | `stats` |
| `route` | `[RoutePoint]` | `route` |
| `activityIntervals` | `[ActivityIntervalPayload]` | `activity_intervals` |
| `journal` | `String?` | `journal` |
| `expiryDays` | `Int` | `expiry_days` |
| `units` | `String` | `units` |
| `startDate` | `String` | `start_date` |
| `tzIdentifier` | `String?` | `tz_identifier` |
| `toggledStats` | `[String]` | `toggled_stats` |
| `placeStart` | `String?` | `place_start` |
| `placeEnd` | `String?` | `place_end` |
| `mark` | `String?` | `mark` |
| `waypoints` | `[Waypoint]?` | `waypoints` |
| `photos` | `[Photo]?` | `photos` |
| `turningDay` | `String? = nil` (var) | `turning_day` |
| `tour` | `Tour? = nil` (var, line 117) | `tour` |
| `pauses` | `[Pause]? = nil` (var, line 118) | `pauses` |

```swift
struct SharePayload: Encodable {

    let stats: Stats
    let route: [RoutePoint]
    let activityIntervals: [ActivityIntervalPayload]
    let journal: String?
    let expiryDays: Int
    let units: String
    let startDate: String
    let tzIdentifier: String?
    let toggledStats: [String]
    let placeStart: String?
    let placeEnd: String?
    let mark: String?
    let waypoints: [Waypoint]?
    let photos: [Photo]?
    var turningDay: String? = nil
```
*Drift risk:* Encodable-only means SharePayload is never round-tripped through decode on-device; a Kotlin @Serializable data class that adds decode support could accidentally require fields the server never echoes back.

#### DAT-7 · `SharePayload.CodingKeys` — mixed verbatim / snake_case mapping
`Pilgrim/Models/Share/SharePayload.swift:120-130@3f9f9e8` — **[consensus: data + edge-cases (EDG-30)]**

| verbatim (no rename) | renamed |
|---|---|
| `stats`, `route`, `journal`, `units`, `waypoints`, `mark`, `photos`, `tour`, `pauses` | `activity_intervals`, `expiry_days`, `start_date`, `tz_identifier`, `toggled_stats`, `place_start`, `place_end`, `turning_day` |

```swift
enum CodingKeys: String, CodingKey {
        case stats, route, journal, units, waypoints, mark, photos, tour, pauses
        case activityIntervals = "activity_intervals"
        case expiryDays = "expiry_days"
        case startDate = "start_date"
        case tzIdentifier = "tz_identifier"
        case toggledStats = "toggled_stats"
        case placeStart = "place_start"
        case placeEnd = "place_end"
        case turningDay = "turning_day"
```
*Drift risk:* Any @SerialName mismatch on the Android side (e.g. using camelCase or a different snake_case spelling) causes the worker to silently ignore or 400 on that field.

#### DAT-8 · `SharePayload.Stats` — toggle-able walk metrics block
`Pilgrim/Models/Share/SharePayload.swift:21-43@3f9f9e8`

| Swift field | type | wire name |
|---|---|---|
| `distance` | `Double?` | `distance` |
| `activeDuration` | `Double?` | `active_duration` |
| `elevationAscent` | `Double?` | `elevation_ascent` |
| `elevationDescent` | `Double?` | `elevation_descent` |
| `steps` | `Int?` | `steps` |
| `meditateDuration` | `Double?` | `meditate_duration` |
| `talkDuration` | `Double?` | `talk_duration` |
| `weatherCondition` | `String?` | `weather_condition` |
| `weatherTemperature` | `Double?` | `weather_temperature` |

```swift
struct Stats: Encodable {
        let distance: Double?
        let activeDuration: Double?
        let elevationAscent: Double?
        let elevationDescent: Double?
        let steps: Int?
        let meditateDuration: Double?
        let talkDuration: Double?
        let weatherCondition: String?
        let weatherTemperature: Double?

        enum CodingKeys: String, CodingKey {
            case distance
            case activeDuration = "active_duration"
            case elevationAscent = "elevation_ascent"
            case elevationDescent = "elevation_descent"
            case steps
            case meditateDuration = "meditate_duration"
            case talkDuration = "talk_duration"
            case weatherCondition = "weather_condition"
            case weatherTemperature = "weather_temperature"
        }
    }
```
*Drift risk:* Every field here is Optional and auto-synthesized Encodable omits nil fields via encodeIfPresent — a Kotlin data class with non-optional defaults or explicit null-encoding would send extra keys the worker doesn't expect.

#### DAT-9 · `SharePayload.RoutePoint` — no CodingKeys override
`Pilgrim/Models/Share/SharePayload.swift:45-50@3f9f9e8`

| Swift field | type | wire name |
|---|---|---|
| `lat` | `Double` | `lat` |
| `lon` | `Double` | `lon` |
| `alt` | `Double` | `alt` |
| `ts` | `Int` | `ts` |

```swift
struct RoutePoint: Encodable {
        let lat: Double
        let lon: Double
        let alt: Double
        let ts: Int
    }
```
*Drift risk:* All fields non-optional and always encoded (never omitted) — a nullable Kotlin equivalent would change wire shape even when values happen to be present.

#### DAT-10 · `SharePayload.ActivityIntervalPayload` — one meditation or talk interval
`Pilgrim/Models/Share/SharePayload.swift:52-62@3f9f9e8`

| Swift field | type | wire name |
|---|---|---|
| `type` | `String` | `type` |
| `startTs` | `Int` | `start_ts` |
| `endTs` | `Int` | `end_ts` |

```swift
struct ActivityIntervalPayload: Encodable {
        let type: String
        let startTs: Int
        let endTs: Int

        enum CodingKeys: String, CodingKey {
            case type
            case startTs = "start_ts"
            case endTs = "end_ts"
        }
    }
```
*Drift risk:* type is a free-form String ('meditation' or 'talk', set by callers, not an enum here) — an Android enum serialized with different casing would silently diverge.

#### DAT-11 · `SharePayload.Waypoint` — no CodingKeys override
`Pilgrim/Models/Share/SharePayload.swift:64-70@3f9f9e8`

| Swift field | type | wire name |
|---|---|---|
| `lat` | `Double` | `lat` |
| `lon` | `Double` | `lon` |
| `label` | `String` | `label` |
| `icon` | `String` | `icon` |
| `ts` | `Int` | `ts` |

```swift
struct Waypoint: Encodable {
        let lat: Double
        let lon: Double
        let label: String
        let icon: String
        let ts: Int
    }
```
*Drift risk:* icon is a free-form String sourced from the waypoint model, not an enum — the exact icon vocabulary lives elsewhere and isn't visible in this slice.

#### DAT-12 · `SharePayload.Photo` — dual-purpose (classic base64 vs interactive PUT)
`Pilgrim/Models/Share/SharePayload.swift:72-77@3f9f9e8`

| Swift field | type | wire name |
|---|---|---|
| `lat` | `Double` | `lat` |
| `lon` | `Double` | `lon` |
| `ts` | `Int` | `ts` |
| `data` | `String?` | `data` (base64 in classic path; always nil/omitted in interactive path) |

```swift
struct Photo: Encodable {
        let lat: Double
        let lon: Double
        let ts: Int
        let data: String?
    }
```
*Drift risk:* The dual meaning of `data` (populated base64 vs always-nil placeholder) is a behavioral contract, not visible from the struct shape alone — see the two call sites in WalkShareViewModel.

#### DAT-13 · `SharePayload.Pause` — one walk pause interval
`Pilgrim/Models/Share/SharePayload.swift:79-87@3f9f9e8`

| Swift field | type | wire name |
|---|---|---|
| `startTs` | `Int` | `start_ts` |
| `endTs` | `Int` | `end_ts` |

```swift
struct Pause: Encodable {
        let startTs: Int
        let endTs: Int

        enum CodingKeys: String, CodingKey {
            case startTs = "start_ts"
            case endTs = "end_ts"
        }
    }
```
*Drift risk:* Identical shape to ActivityIntervalPayload's start/end pair minus the type field — easy to conflate the two during a port.

#### DAT-14 · `SharePayload.Tour` — recordings + applied trim distance
`Pilgrim/Models/Share/SharePayload.swift:89-97@3f9f9e8`

| Swift field | type | wire name |
|---|---|---|
| `recordings` | `[TourRecording]` | `recordings` |
| `trimM` | `Int` | `trim_m` |

```swift
struct Tour: Encodable {
        let recordings: [TourRecording]
        let trimM: Int

        enum CodingKeys: String, CodingKey {
            case recordings
            case trimM = "trim_m"
        }
    }
```
*Drift risk:* trimM is the OUTCOME of trimming (0 if no trim actually happened), not the configured trim distance — see WalkShareViewModel.computeInteractiveRoute's didTrim logic.

#### DAT-15 · `SharePayload.TourRecording` — per-recording tour entry
`Pilgrim/Models/Share/SharePayload.swift:99-115@3f9f9e8`

| Swift field | type | wire name | note |
|---|---|---|---|
| `n` | `Int` | `n` | 1-based, dense over included candidates |
| `startTs` | `Int` | `start_ts` | — |
| `endTs` | `Int` | `end_ts` | — |
| `duration` | `Double` | `duration` | — |
| `kind` | `String` | `kind` | `spoken` \| `ambient` |
| `transcription` | `String?` | `transcription` | **contractually always nil** |
| `wpm` | `Double?` | `wpm` | DOES travel to the server |
| `sizeBytes` | `Int` | `size_bytes` | — |

```swift
struct TourRecording: Encodable {
        let n: Int
        let startTs: Int
        let endTs: Int
        let duration: Double
        let kind: String
        let transcription: String?
        let wpm: Double?
        let sizeBytes: Int

        enum CodingKeys: String, CodingKey {
            case n, duration, kind, transcription, wpm
            case startTs = "start_ts"
            case endTs = "end_ts"
            case sizeBytes = "size_bytes"
        }
    }
```
*Drift risk:* transcription is structurally present but contractually always nil (see TourBuilder.swift:105-108) — a naive port might wire real transcript text through, exceeding the worker's payload budget.

#### DAT-16 · `tour` / `pauses` as post-construction mutable vars + the `encodeIfPresent` contract
`Pilgrim/Models/Share/SharePayload.swift:117-118@3f9f9e8` — **[consensus: data + edge-cases (EDG-28, EDG-29)]**

| Swift field | type | wire behavior |
|---|---|---|
| `tour` | `Tour? = nil` (var) | key OMITTED entirely when nil, never `null` |
| `pauses` | `[Pause]? = nil` (var) | key OMITTED entirely when nil, never `null` |

```swift
var tour: Tour? = nil
    var pauses: [Pause]? = nil
```
*Drift risk:* **HIGHEST DRIFT RISK:** kotlinx.serialization's default behavior for a nullable field is to ENCODE explicit JSON null unless the property has a default value AND encodeDefaults=false (or @EncodeDefault(NEVER) is applied) — Android must replicate 'omit key when absent' for every Optional field across SharePayload/Stats/Photo/etc., not just tour/pauses, or the worker will reject/mishandle literal nulls per the doc's stated contract.

#### DAT-27 · `ShareService.MediaKind` — raw values interpolated into the PUT URL path
`Pilgrim/Models/Share/ShareService.swift:144@3f9f9e8`

| case | raw value | URL segment |
|---|---|---|
| `audio` | `"audio"` | `/api/share/{id}/audio/{n}` |
| `photos` | `"photos"` | `/api/share/{id}/photos/{n}` |

```swift
enum MediaKind: String { case audio, photos }
```
*Drift risk:* These exact lowercase strings are load-bearing for the URL path — an Android enum with different serialized names (e.g. 'AUDIO') would 404 against the worker.

#### DAT-28 · `ShareService.FailedMediaItem` — the locally-persisted repair record
`Pilgrim/Models/Share/ShareService.swift:294-300@3f9f9e8` — **[consensus: data + behavior (BEH-15) + edge-cases (EDG-49)]**

| field | type | role |
|---|---|---|
| `kind` | `String` | upload slot kind (raw string, deliberately NOT `MediaKind`) |
| `n` | `Int` | upload slot index (1-based, per kind) |
| `audioStartTs` | `Int?` | audio identity key |
| `photoLocalID` | `String?` | photo identity key (half of a compound key) |
| `photoTs` | `Int?` | photo identity key (other half) |

```swift
struct FailedMediaItem: Codable, Equatable {
        let kind: String
        let n: Int
        let audioStartTs: Int?
        let photoLocalID: String?
        let photoTs: Int?
    }
```
*Drift risk:* kind is a raw String (not MediaKind) specifically so this on-disk format doesn't depend on the enum's cases per the doc comment — an Android port coupling this record's kind field directly to an enum type risks silent decode breaks on future enum changes.

#### DAT-37 · `ShareService.ShareResult` — the POST success payload
`Pilgrim/Models/Share/ShareService.swift:30-33@3f9f9e8`

| field | type | role |
|---|---|---|
| `url` | `String` | public share page URL |
| `id` | `String` | opaque shareID used to build every media PUT URL |

```swift
struct ShareResult {
    let url: String
    let id: String
}
```
*Drift risk:* id is the shareID used to build every subsequent media PUT URL — must be treated as an opaque server-issued string, not reformatted/validated client-side.

#### DAT-38 · `ShareService.CachedShare` — reconstructed from the UserDefaults dictionary
`Pilgrim/Models/Share/ShareService.swift:35-42@3f9f9e8`

| field | type | role |
|---|---|---|
| `url` | `String` | required for reconstruction |
| `id` | `String` | required for reconstruction |
| `expiry` | `Date` | required; ISO8601-parsed |
| `shareDate` | `Date?` | optional |
| `expiryOption` | `String?` | optional (`moon` \| `season` \| `cycle`) |
| `isExpired` | `Bool` (computed) | `expiry <= Date()` |

```swift
struct CachedShare {
        let url: String
        let id: String
        let expiry: Date
        let shareDate: Date?
        let expiryOption: String?
        var isExpired: Bool { expiry <= Date() }
    }
```
*Drift risk:* isExpired uses <= (expiry exactly now counts as expired) — an off-by-one on the comparison operator changes behavior only in the single-instant edge case but is worth matching exactly for test parity.

#### DAT-39 · `ShareService.MediaProgress` — progress tick shape
`Pilgrim/Models/Share/ShareService.swift:139-142@3f9f9e8`

| field | type |
|---|---|
| `completed` | `Int` |
| `total` | `Int` |

```swift
struct MediaProgress: Equatable {
        let completed: Int
        let total: Int
    }
```
*Drift risk:* Equatable conformance is relied upon directly in tests (XCTAssertEqual(lastProgress, MediaProgress(...))) — an Android data class needs equals()/hashCode() (automatic) to support equivalent test assertions.

#### DAT-40 · `ShareService.ShareError` — four cases with exact user-facing copy
`Pilgrim/Models/Share/ShareService.swift:10-28@3f9f9e8`

| case | payload | `errorDescription` |
|---|---|---|
| `encodingFailed` | — | `"Failed to prepare walk data."` |
| `networkError` | `String` | `"Network error: \(message)"` |
| `serverError` | `Int, String` | `"Server error (\(code)): \(message)"` |
| `rateLimited` | — | `"You've shared too many walks today. Try again tomorrow."` |

```swift
enum ShareError: LocalizedError {
        case encodingFailed
        case networkError(String)
        case serverError(Int, String)
        case rateLimited

        var errorDescription: String? {
            switch self {
            case .encodingFailed:
                return "Failed to prepare walk data."
            case .networkError(let message):
                return "Network error: \(message)"
            case .serverError(let code, let message):
                return "Server error (\(code)): \(message)"
            case .rateLimited:
                return "You've shared too many walks today. Try again tomorrow."
            }
        }
    }
```
*Drift risk:* rateLimited is triggered specifically on HTTP 429 (see ShareService.swift:69-71) — an Android sealed class must special-case 429 the same way rather than folding it into a generic serverError bucket, or the distinct user copy is lost.

#### DAT-42 · Private wire-decode shapes for the share POST responses
`Pilgrim/Models/Share/ShareService.swift:414-421@3f9f9e8`

| struct | fields |
|---|---|
| `SuccessResponse` | `url: String`, `id: String` |
| `ErrorResponse` | `error: String` |

```swift
private struct SuccessResponse: Decodable {
    let url: String
    let id: String
}

private struct ErrorResponse: Decodable {
    let error: String
}
```
*Drift risk:* These are the only two response shapes read from the worker in this slice — any additional response fields (rate-limit reset time, etc.) are simply not modeled/ignored.

#### DAT-44 · `TourRecordingKind` — wire value AND UI classification
`Pilgrim/Models/Share/TourBuilder.swift:3@3f9f9e8`

| case | raw (wire) value | UI display word |
|---|---|---|
| `spoken` | `"spoken"` | `voice` |
| `ambient` | `"ambient"` | `ambience` |

```swift
enum TourRecordingKind: String { case spoken, ambient }
```
*Drift risk:* Raw value strings 'spoken'/'ambient' are sent literally as SharePayload.TourRecording.kind — case must match exactly.

#### DAT-45 · `TourRecordingCandidate` — client-only, never serialized
`Pilgrim/Models/Share/TourBuilder.swift:5-20@3f9f9e8`

| field | type | note |
|---|---|---|
| `id` | `Int` | plain pre-filter enumeration index; NOT dense, NOT the wire `n` |
| `startTs` | `Int` | truncated seconds |
| `endTs` | `Int` | truncated seconds |
| `duration` | `Double` | — |
| `sizeBytes` | `Int` | — |
| `transcription` | `String?` | never sent to server |
| `wpm` | `Double?` | IS sent to server |
| `autoKind` | `TourRecordingKind` | from `classify()` |
| `includeInShare` | `Bool` (var) | default true when available |
| `kindOverride` | `TourRecordingKind?` (var) | nil when matching autoKind |
| `fileURL` | `URL?` (var) | — |
| `unavailableReason` | `String?` | nil \| `"audio removed"` \| `"too large to carry"` |
| `effectiveKind` | computed | `kindOverride ?? autoKind` |

```swift
struct TourRecordingCandidate: Identifiable, Equatable {
    let id: Int
    let startTs: Int
    let endTs: Int
    let duration: Double
    let sizeBytes: Int
    let transcription: String?
    let wpm: Double?
    let autoKind: TourRecordingKind
    var includeInShare: Bool
    var kindOverride: TourRecordingKind?
    var fileURL: URL?
    let unavailableReason: String?

    var effectiveKind: TourRecordingKind { kindOverride ?? autoKind }
}
```
*Drift risk:* id is a plain array index (from candidates()'s .enumerated()), not the underlying recording's persistent ID — reordering the source walk's voiceRecordings between reads would reassign these ids.

#### DAT-53 · `TourPhoto` — client-only export result
`Pilgrim/Models/Share/TourPhotoExporter.swift:5-14@3f9f9e8`

| field | type | note |
|---|---|---|
| `meta` | `SharePayload.Photo` | `meta.data` is ALWAYS nil at this stage |
| `jpegData` | `Data` | the actual bytes; PUT separately |
| `sourceLocalIdentifier` | `String` | never sent to server; used for retry identity |

```swift
struct TourPhoto {
    let meta: SharePayload.Photo
    let jpegData: Data
    let sourceLocalIdentifier: String
}
```
*Drift risk:* meta.data is always nil here (see the construction site below) — the actual bytes travel via jpegData and are PUT separately, not embedded in meta; an Android model must keep this same separation or risk double-sending photo bytes.

#### DAT-57 · `ExpiryOption` — Int-raw-valued, rawValue IS the wire `expiryDays`
`Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:80-108@3f9f9e8` — **[consensus: data + edge-cases (EDG-123, EDG-124)]**

| case | rawValue (= `expiry_days`) | `label` | `kanji` | `cacheKey` |
|---|---|---|---|---|
| `moon` | `30` | `"1 moon"` | `U+6708` 月 | `"moon"` |
| `season` | `90` | `"1 season"` | `U+5B63` 季 | `"season"` |
| `cycle` | `365` | `"1 cycle"` | `U+5DE1` 巡 | `"cycle"` |

```swift
enum ExpiryOption: Int, CaseIterable {
        case moon = 30
        case season = 90
        case cycle = 365

        var label: String {
            switch self {
            case .moon: return "1 moon"
            case .season: return "1 season"
            case .cycle: return "1 cycle"
            }
        }

        var kanji: String {
            switch self {
            case .moon: return "\u{6708}"
            case .season: return "\u{5B63}"
            case .cycle: return "\u{5DE1}"
            }
        }

        var cacheKey: String {
            switch self {
            case .moon: return "moon"
            case .season: return "season"
            case .cycle: return "cycle"
            }
        }
    }
```
*Drift risk:* rawValue (30/90/365) IS the wire expiryDays sent in SharePayload directly (selectedExpiry.rawValue) — an Android enum with a separate 'days' property that drifts from its ordinal/rawValue equivalent would send the wrong expiry.

#### DAT-58 · `ShareState` — client-only orchestration state machine (never serialized)
`Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:110-129@3f9f9e8` — **[consensus: data + behavior (BEH-76) + ui-visual (UI-66) — three-lens]**

| case | associated values | live page? |
|---|---|---|
| `idle` | — | no |
| `preparingPhotos` | `completed: Int, total: Int` | no (pre-POST) |
| `photosDropped` | `prepared: Int, dropped: Int` | no (pre-POST consent pause) |
| `uploading` | — | POST phase |
| `uploadingMedia` | `completed: Int, total: Int` | yes (PUT phase) |
| `success` | `url: String` | yes |
| `partial` | `url: String, failedCount: Int` | yes |
| `error` | `message: String` | no |

```swift
enum ShareState: Equatable {
        case idle
        case preparingPhotos(completed: Int, total: Int) // hi-res export (pre-POST)
        case photosDropped(prepared: Int, dropped: Int)  // export done short; pre-POST consent pause
        case uploading                                   // POST phase
        case uploadingMedia(completed: Int, total: Int)  // PUT phase
        case success(url: String)
        case partial(url: String, failedCount: Int)      // page live, some media missing
        case error(message: String)
    }

    var isShared: Bool {
        switch shareState {
        case .success, .partial: return true
        default: return false
        }
    }
```
*Drift risk:* isShared conflating success+partial is the VM-level source of truth for 'this walk has a live page' per its doc comment — an Android StateFlow consumer that instead checks `state is Success` alone (excluding Partial) would misreport a partially-uploaded walk as not-yet-shared.

#### DAT-61 · `mark` — walk favicon/mood mapped to three distinct wire strings
`Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:370-377@3f9f9e8` — **[consensus: data + edge-cases (EDG-143)]**

| `WalkFavicon` case | wire `mark` value |
|---|---|
| `.flame` | `"transformative"` |
| `.leaf` | `"peaceful"` |
| `.star` | `"extraordinary"` |
| unparseable / nil | key omitted entirely |

```swift
let markValue: String? = {
            guard let faviconStr = walk.favicon, let fav = WalkFavicon(rawValue: faviconStr) else { return nil }
            switch fav {
            case .flame: return "transformative"
            case .leaf:  return "peaceful"
            case .star:  return "extraordinary"
            }
        }()
```
*Drift risk:* These three wire strings are unrelated lexically to the internal enum case names (flame/leaf/star) — an Android port must hardcode this exact three-way mapping table, not derive it from the enum name.

### 4.2 Persistence ops

**DAT-19 · `ShareService`'s backend base URL constant.** `Pilgrim/Models/Share/ShareService.swift:7@3f9f9e8` · value `https://walk.pilgrimapp.org` — **[consensus: data + edge-cases (EDG-33)]**
```swift
private static let baseURL = "https://walk.pilgrimapp.org"
```
*Drift risk:* Matches the already-documented base host in pilgrim-android/CLAUDE.md; endpoints built from it below extend under /api/share, not the CLAUDE.md-documented /share/* wildcard — worth a cross-check (see §8).

**DAT-22 · `deviceToken()` reads a cached UUID string, else mints and persists a new UUIDv4-style string via UserDefaults.** `Pilgrim/Models/Share/ShareService.swift:87-94@3f9f9e8`
```swift
private static func deviceToken() -> String {
        if let existing = UserDefaults.standard.string(forKey: deviceTokenKey) {
            return existing
        }
        let token = UUID().uuidString
        UserDefaults.standard.set(token, forKey: deviceTokenKey)
        return token
    }
```
*Drift risk:* UUID().uuidString produces uppercase hyphenated UUID text (e.g. '4A2B…'); a Kotlin java.util.UUID.toString() produces lowercase — if the worker does any case-sensitive comparison/storage of this token, casing mismatches could matter.

**DAT-24 · `cacheShare` computes expiry via `Calendar.current.date(byAdding:.day…)` from 'now' and writes a 4-5 key dictionary to UserDefaults keyed by walk UUID.** `Pilgrim/Models/Share/ShareService.swift:119-132@3f9f9e8` — **[consensus: data + behavior (BEH-7), identical citation; + edge-cases EDG-38, EDG-39]**
```swift
static func cacheShare(_ result: ShareResult, walkID: UUID, expiryDays: Int, expiryOption: String?) {
        let now = Date()
        let expiry = Calendar.current.date(byAdding: .day, value: expiryDays, to: now) ?? now
        var dict: [String: String] = [
            "url": result.url,
            "id": result.id,
            "expiry": isoFormatter.string(from: expiry),
            "shareDate": isoFormatter.string(from: now),
        ]
        if let expiryOption {
            dict["expiryOption"] = expiryOption
        }
        UserDefaults.standard.set(dict, forKey: "share:\(walkID.uuidString)")
    }
```
*Drift risk:* Uses the DEVICE's current Calendar (locale/timezone-sensitive) for day-adding, not a fixed UTC offset — a Kotlin java.time.LocalDate/Instant.plus(Duration.ofDays()) computation could disagree near DST transitions.

### 4.3 Network endpoints

| method | URL template | headers | timeout |
|---|---|---|---|
| POST | `https://walk.pilgrimapp.org/api/share` | `Content-Type: application/json`, `X-Device-Token` | 30 s |
| PUT | `https://walk.pilgrimapp.org/api/share/{shareID}/{audio\|photos}/{n}` | `Content-Type: audio/mp4` or `image/jpeg`, `Content-Length`, `X-Device-Token` | 30 s (idle) |

**DAT-21 · The share POST endpoint, method, headers, and 30 s timeout.** `Pilgrim/Models/Share/ShareService.swift:44-56@3f9f9e8` — **[consensus: data + behavior (BEH-3) + edge-cases (EDG-35)]**
```swift
static func share(payload: SharePayload) async throws -> ShareResult {
        let url = URL(string: "\(baseURL)/api/share")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(deviceToken(), forHTTPHeaderField: "X-Device-Token")
        request.timeoutInterval = 30

        let encoder = JSONEncoder()
        guard let body = try? encoder.encode(payload) else {
            throw ShareError.encodingFailed
        }
        request.httpBody = body
```
*Drift risk:* Uses default JSONEncoder (no custom key strategy, no date strategy, no outputFormatting) — an OkHttp/Retrofit + kotlinx.serialization Json instance must match encodeIfPresent-style omission (see DAT-16), not just field names.

**DAT-25 · Media PUT endpoint construction, method, per-kind Content-Type, Content-Length, and the same 30 s idle timeout as the share POST.** `Pilgrim/Models/Share/ShareService.swift:146-158@3f9f9e8` — **[consensus: data + behavior (BEH-8) + edge-cases (EDG-40, EDG-41, EDG-42, EDG-55)]**
```swift
static func mediaUploadRequest(shareID: String, kind: MediaKind, n: Int, contentLength: Int) -> URLRequest {
        let url = URL(string: "\(baseURL)/api/share/\(shareID)/\(kind.rawValue)/\(n)")!
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(kind == .audio ? "audio/mp4" : "image/jpeg", forHTTPHeaderField: "Content-Type")
        request.setValue("\(contentLength)", forHTTPHeaderField: "Content-Length")
        request.setValue(deviceToken(), forHTTPHeaderField: "X-Device-Token")
        request.timeoutInterval = 30
        return request
    }
```
*Drift risk:* Content-Type for audio is hardcoded 'audio/mp4' regardless of the recording's actual container/codec — if Android's voice recordings are a different container (the CLAUDE.md AVFoundation→AudioRecord/ExoPlayer mapping implies a possible format difference), sending the same literal Content-Type could mislabel the bytes.

**DAT-26 · Cross-check against documented pilgrim ecosystem URLs: this slice's endpoints live under `/api/share/*`, not the `/share/*` wildcard CLAUDE.md documents for the "share worker".** `Pilgrim/Models/Share/ShareService.swift:45,147@3f9f9e8`
```swift
let url = URL(string: "\(baseURL)/api/share")!
... 
let url = URL(string: "\(baseURL)/api/share/\(shareID)/\(kind.rawValue)/\(n)")!
```
*Drift risk:* pilgrim-android/CLAUDE.md's backend URL table lists 'Share worker: https://walk.pilgrimapp.org/share/*' — the actual iOS paths are under /api/share, mirroring the /api/counter collective-counter prefix. Flagging so the Android implementation targets /api/share/* literally rather than /share/*.

**DAT-41 · HTTP 429 is special-cased to `rateLimited` before the general 200-299 success-range check; all other non-2xx codes decode an optional `{error: String}` body for the message, defaulting to "Unknown error".** `Pilgrim/Models/Share/ShareService.swift:69-77@3f9f9e8` — **[consensus: data + behavior (BEH-4, BEH-5) + edge-cases (EDG-36, EDG-37)]**
```swift
if httpResponse.statusCode == 429 {
            throw ShareError.rateLimited
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            let message = (try? JSONDecoder().decode(ErrorResponse.self, from: data))?.error
                ?? "Unknown error"
            throw ShareError.serverError(httpResponse.statusCode, message)
        }
```
*Drift risk:* ErrorResponse decode failure is silently swallowed via try? — an Android client that instead throws/crashes on malformed error bodies would behave differently from this deliberately lenient fallback.

**DAT-59 · `buildPayload` assembles the full SharePayload; `startDate` is ISO8601-formatted via a fresh `ISO8601DateFormatter` (default options, distinct instance from ShareService's cached `isoFormatter`), `tzIdentifier` is the device's current IANA zone name.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:379-396@3f9f9e8`
```swift
let formatter = ISO8601DateFormatter()

        var payload = SharePayload(
            stats: stats,
            route: finalRoute,
            activityIntervals: intervals,
            journal: journal.isEmpty ? nil : journal,
            expiryDays: selectedExpiry.rawValue,
            units: isMetric ? "metric" : "imperial",
            startDate: formatter.string(from: walk.startDate),
            tzIdentifier: TimeZone.current.identifier,
```
*Drift risk:* Default ISO8601DateFormatter emits second-precision UTC ('yyyy-MM-dd'T'HH:mm:ss'Z''), no fractional seconds, no timezone offset — a java.time.Instant.toString() on Android emits a similar but not byte-identical format depending on precision; must match the worker's expected startDate parsing exactly. Note `journal.isEmpty` collapses to nil (key omitted); `units` is the literal `"metric"` or `"imperial"`.

**DAT-62 · The `photos` array is populated via two mutually-exclusive code paths depending on `interactiveEnabled`: interactive mode passes through the export's own metadata array unchanged (data always nil, uploaded separately via PUT); classic mode synchronously loads each pinned photo at 600×600 and embeds it as base64 JPEG (quality 0.5) directly in the JSON body.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:279-317,424-440@3f9f9e8` — **[consensus: data + behavior (BEH-84) + edge-cases (EDG-136..EDG-141)]**
```swift
private static func loadSharePhoto(
        localIdentifier: String,
        lat: Double,
        lon: Double,
        capturedAt: Date
    ) -> SharePayload.Photo? {
...
        options.isNetworkAccessAllowed = false
        options.isSynchronous = true
        options.resizeMode = .exact

        let targetSize = CGSize(width: 600, height: 600)
...
            guard let image = image,
                  let jpegData = image.jpegData(compressionQuality: 0.5) else { return }
            let base64 = jpegData.base64EncodedString()
            result = SharePayload.Photo(
                lat: lat,
                lon: lon,
                ts: Int(capturedAt.timeIntervalSince1970),
                data: base64
            )
...
    private func photoPayload(interactive: Bool, tourPhotoMeta: [SharePayload.Photo]) -> [SharePayload.Photo]? {
        guard includePhotos, hasPinnedPhotos else { return nil }
        if interactive {
            return tourPhotoMeta.isEmpty ? nil : tourPhotoMeta
        }
        return pinnedPhotos.compactMap { photo in
            Self.loadSharePhoto(
```
*Drift risk:* The classic path is EXPLICITLY SYNCHRONOUS and documented as blocking main ~10-50ms per photo (see doc comment lines 275-278) — this is a deliberate main-thread block on iOS; an Android Compose/coroutines port should almost certainly NOT replicate main-thread blocking and needs an explicit design decision here, not a literal translation. Also note isNetworkAccessAllowed=false means iCloud-only (non-downloaded) photos are silently skipped in classic mode, unlike the interactive TourPhotoExporter path which explicitly allows network access (isNetworkAccessAllowed = true).

**DAT-63 · `geocodeAnchorPoints` picks the coordinate pair to reverse-geocode: the post-trim interactive route's first/last points when interactive, else the walk's raw (untrimmed, undownsampled) route's first/last points.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:265-273@3f9f9e8`
```swift
func geocodeAnchorPoints() -> (start: SharePayload.RoutePoint, end: SharePayload.RoutePoint)? {
        if interactiveEnabled {
            let route = computeInteractiveRoute().route
            guard let first = route.first, let last = route.last else { return nil }
            return (first, last)
        }
        guard let first = walk.routeData.first, let last = walk.routeData.last else { return nil }
        return (routePoint(first), routePoint(last))
    }
```
*Drift risk:* Classic mode geocodes off the RAW route endpoints, not the downsampled ones sent in the payload's route field — since downsampling can drop/move the literal first or last point in edge cases (though RDP always preserves true endpoints), an Android implementation must source geocoding coordinates from the same pre/post-processing stage per mode, not a single shared 'the route' variable.

**DAT-71 · Unit test pins the exact literal request shape (URL string, method, headers) for both audio and photo media PUTs, independent of the doc comments.** `UnitTests/ShareMediaUploadTests.swift:6-20@3f9f9e8` — **[consensus: data + behavior (BEH-88) + edge-cases (EDG-155)]**
```swift
func testRequestShapeMatchesWorkerContract() {
        let req = ShareService.mediaUploadRequest(shareID: "abc123defg", kind: .audio, n: 3, contentLength: 12345)
        XCTAssertEqual(req.url?.absoluteString, "https://walk.pilgrimapp.org/api/share/abc123defg/audio/3")
        XCTAssertEqual(req.httpMethod, "PUT")
        XCTAssertEqual(req.value(forHTTPHeaderField: "Content-Type"), "audio/mp4")
        XCTAssertEqual(req.value(forHTTPHeaderField: "Content-Length"), "12345")
        XCTAssertNotNil(req.value(forHTTPHeaderField: "X-Device-Token"))
        XCTAssertEqual(req.timeoutInterval, 30, "an idle timeout, not a whole-upload one — it resets on bytes moving")
    }

    func testPhotoRequestUsesJpegContentType() {
        let req = ShareService.mediaUploadRequest(shareID: "abc123defg", kind: .photos, n: 1, contentLength: 500)
        XCTAssertEqual(req.url?.absoluteString, "https://walk.pilgrimapp.org/api/share/abc123defg/photos/1")
        XCTAssertEqual(req.value(forHTTPHeaderField: "Content-Type"), "image/jpeg")
    }
```
*Drift risk:* This test is the authoritative literal spec for the media PUT URL shape — an Android Retrofit/OkHttp equivalent test should assert the identical URL string, not just 'contains the right path segments', to catch subtle path-building bugs (extra/missing slashes, wrong segment order).

### 4.4 File I/O (and IO dispatchers)

**DAT-47 · `candidates()` resolves each recording's audio file path by joining the app's Documents directory with the recording's stored relative path.** `Pilgrim/Models/Share/TourBuilder.swift:43-49@3f9f9e8` — **[consensus: data + edge-cases (EDG-64, EDG-65)]**
```swift
static func candidates(for walk: WalkInterface) -> [TourRecordingCandidate] {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let sorted = walk.voiceRecordings.sorted { $0.startDate < $1.startDate }
        return sorted.enumerated().compactMap { index, rec in
            guard !rec.fileRelativePath.isEmpty else { return nil }
            let url = docs.appendingPathComponent(rec.fileRelativePath)
```
*Drift risk:* Recordings are re-sorted by startDate here (not trusted to already be in order from the walk model) — an Android equivalent skipping this sort could ship recordings out of chronological order.

**DAT-55 · `loadOne` bounds wall-clock time via two independent deadlines: `perPhotoTimeout` cancels the underlying `PHImageManager` request, and `perPhotoTimeout+backstopGrace` force-resumes the continuation with nil even if PhotoKit's callback never fires — guarded by a one-shot lock so whichever fires first cancels the other pending `DispatchWorkItem`.** `Pilgrim/Models/Share/TourPhotoExporter.swift:64-133@3f9f9e8` — **[consensus: data + behavior (BEH-26, BEH-27, BEH-28, BEH-29) + edge-cases (EDG-80, EDG-81, EDG-84)]**
```swift
let cancelItem = DispatchWorkItem {
                PHImageManager.default().cancelImageRequest(requestID)
            }

            let backstopItem = DispatchWorkItem {
                let shouldResume = state.withLock { box -> Bool in
                    guard !box.resumed else { return false }
                    box.resumed = true
                    return true
                }
                guard shouldResume else { return }
                continuation.resume(returning: nil)
            }

            state.withLock {
                $0.cancelItem = cancelItem
                $0.backstopItem = backstopItem
            }

            DispatchQueue.global().asyncAfter(deadline: .now() + perPhotoTimeout, execute: cancelItem)
            DispatchQueue.global().asyncAfter(deadline: .now() + perPhotoTimeout + backstopGrace, execute: backstopItem)
```
*Drift risk:* This whole dual-deadline continuation pattern exists because PhotoKit's cancellation contract is 'best effort, not guaranteed to call back' — Android's MediaStore/ContentResolver photo access has different cancellation semantics entirely, so this specific 20s+2s bound is iOS-PhotoKit-shaped, not a generic timeout to port verbatim.

**DAT-36 · `withBackgroundAssertion` begins and ends the UIBackgroundTask assertion on the MainActor, but the wrapped `body` closure itself runs on whatever executor it was already running on (not necessarily MainActor) — a one-shot lock (`OSAllocatedUnfairLock`) guards against double-ending the same assertion.** `Pilgrim/Models/Share/ShareService.swift:333-370@3f9f9e8` — **[consensus: data + behavior (BEH-17) + edge-cases (EDG-51)]**
```swift
static func withBackgroundAssertion<T: Sendable>(
        named name: String,
        _ body: () async -> T
    ) async -> T {
        let state = OSAllocatedUnfairLock(initialState: BackgroundAssertionState())

        func endOnce() -> UIBackgroundTaskIdentifier? {
            state.withLock { s in
                guard !s.ended, s.identifier != .invalid else { return nil }
                s.ended = true
                return s.identifier
            }
        }

        await MainActor.run {
            let identifier = UIApplication.shared.beginBackgroundTask(withName: name) {
                guard let idToEnd = endOnce() else { return }
                MainActor.assumeIsolated {
                    UIApplication.shared.endBackgroundTask(idToEnd)
                }
            }
            state.withLock { $0.identifier = identifier }
        }

        let result = await body()

        if let idToEnd = endOnce() {
            await MainActor.run {
                UIApplication.shared.endBackgroundTask(idToEnd)
            }
        }

        return result
    }
```
*Drift risk:* This is the iOS UIBackgroundTask primitive with no Android equivalent — CLAUDE.md maps long-running work to a foreground service instead. This whole assertion pattern is a candidate for 'doesn't port literally', not a 1:1 translation target.

**DAT-54 · `export()` is documented as a nonisolated async function whose progress callback fires off the main thread after each photo — differing from the sibling `WalkPhotoMatcher.findCandidates` whose completion is always main-thread-delivered; callers must hop to MainActor themselves.** `Pilgrim/Models/Share/TourPhotoExporter.swift:35-53@3f9f9e8` — **[consensus: data + behavior (BEH-24, BEH-25) + edge-cases (EDG-77, EDG-78, EDG-79)]**
```swift
/// `progress` fires after every photo, off the main thread — `export` is a
    /// nonisolated async function and reports from whatever executor happens to
    /// be running when the current photo finishes, never the main actor. This
    /// differs from the sibling `WalkPhotoMatcher.findCandidates`, whose
    /// `completion` closure is always delivered on the main thread. Callers that
    /// update UI from `progress` must hop to the MainActor themselves.
    static func export(_ candidates: [PhotoCandidate], progress: @escaping (Int, Int) -> Void) async -> [TourPhoto] {
        var out: [TourPhoto] = []
        for (i, candidate) in candidates.enumerated() {
            if Task.isCancelled { break }
            if let photo = await loadOne(candidate) { out.append(photo) }
            progress(i + 1, candidates.count)
        }
        return out
    }
```
*Drift risk:* Task.isCancelled is checked only BETWEEN photos, not during a single photo's load — a cancel during a long-running single photo fetch still lets that one photo finish; an Android coroutine port using ensureActive() inside the per-photo suspend function instead would cancel mid-photo, a behavioral difference worth an explicit decision.

### 4.5 UserDefaults keys

| key template | value shape | written by | read by |
|---|---|---|---|
| `pilgrim.share.device-token` | `String` (uppercase UUID) | `deviceToken()` | `deviceToken()` — also reused by feedback submission |
| `share:{walkID.uuidString}` | `[String: String]` dict — `url`, `id`, `expiry`, `shareDate`, `expiryOption?` | `cacheShare` | `cachedShare(for:)` |
| `share-failed-media:{walkID.uuidString}` | JSON `Data` of `[FailedMediaItem]`; key REMOVED when list is empty | `cacheFailedMedia` | `failedMedia(for:)` |

**DAT-20 · The persisted per-device share identity token's UserDefaults key literal.** `Pilgrim/Models/Share/ShareService.swift:8@3f9f9e8` — **[consensus: data + edge-cases (EDG-34)]**
```swift
private static let deviceTokenKey = "pilgrim.share.device-token"
```
*Drift risk:* This same token is reused for feedback submission (deviceTokenForFeedback()) — an Android DataStore key that diverges between the share flow and any existing feedback flow would silently create two device identities.

**DAT-23 · `cachedShare(for:)` reads a per-walk cached share dictionary keyed by walk UUID string, requiring url/id/expiry (ISO8601) to be present or the whole read fails closed.** `Pilgrim/Models/Share/ShareService.swift:96-117@3f9f9e8` — **[consensus: data + behavior (BEH-6) + edge-cases (EDG-39)]**
```swift
private static let isoFormatter = ISO8601DateFormatter()

    static func cachedShare(for walkID: UUID) -> CachedShare? {
        guard let dict = UserDefaults.standard.dictionary(forKey: "share:\(walkID.uuidString)"),
              let url = dict["url"] as? String,
              let id = dict["id"] as? String,
              let expiryStr = dict["expiry"] as? String,
              let expiry = isoFormatter.date(from: expiryStr) else {
            return nil
        }

        let shareDate = (dict["shareDate"] as? String).flatMap { isoFormatter.date(from: $0) }
        let expiryOption = dict["expiryOption"] as? String
```
*Drift risk:* ISO8601DateFormatter() with default options (no fractional seconds) must be matched exactly by whatever DateTimeFormatter/Instant parsing Android uses, or a cached expiry string written by one code path could fail to parse and silently look absent.

**DAT-29 · Failed-media repair records are cached under a per-walk key, JSON-encoded; an empty list removes the key rather than storing an empty array.** `Pilgrim/Models/Share/ShareService.swift:306-319@3f9f9e8` — **[consensus: data + behavior (BEH-16) + edge-cases (EDG-50)]**
```swift
static func cacheFailedMedia(_ failures: [FailedMediaItem], walkID: UUID) {
        let key = "share-failed-media:\(walkID.uuidString)"
        if failures.isEmpty {
            UserDefaults.standard.removeObject(forKey: key)
        } else if let data = try? JSONEncoder().encode(failures) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    static func failedMedia(for walkID: UUID) -> [FailedMediaItem] {
        let key = "share-failed-media:\(walkID.uuidString)"
        guard let data = UserDefaults.standard.data(forKey: key),
              let items = try? JSONDecoder().decode([FailedMediaItem].self, from: data) else { return [] }
        return items
    }
```
*Drift risk:* A DataStore Preferences port that always writes (even an empty JSON array '[]') instead of removing the key is functionally equivalent for reads, but diverges from the exact on-disk behavior this test suite pins.

### 4.6 Caps & validation

| constant / guard | value | source |
|---|---|---|
| Route point cap | `200` (default param) | `RouteDownsampler.swift:7` |
| RDP epsilon search bounds | `low = 0.0`, `high = 0.01` (degrees) | `RouteDownsampler.swift:90-91` |
| RDP epsilon iterations | `20` | `RouteDownsampler.swift:93` |
| Trim guards | `meters > 0`, `route.count > 3`, `total >= meters * 4` | `RouteTrimmer.swift:9,15` |
| Trim distance | `trimMeters = 150` | `WalkShareViewModel.swift:36` |
| Max recordings per tour | `12` | `TourBuilder.swift:24` |
| Max single audio file | `15 * 1024 * 1024` (15 MiB) | `TourBuilder.swift:25` |
| Max total tour bytes | `60 * 1024 * 1024` (60 MiB) | `TourBuilder.swift:26` |
| Max total tour seconds | `6480.0` (108 min) | `TourBuilder.swift:27` |
| Ambient/spoken word threshold | `wordCount < 8` | `TourBuilder.swift:39` |
| Sub-second blip exclusion | `Int(endDate) > Int(startDate)` | `TourBuilder.swift:54` |
| Per-photo byte cap (interactive) | `2 * 1024 * 1024` (2 MiB) | `TourPhotoExporter.swift:18` |
| Photo target dimension (interactive) | `1600` px | `TourPhotoExporter.swift:19` |
| Photo target dimension (classic) | `600 × 600` px | `WalkShareViewModel.swift:297` |
| Per-photo PhotoKit timeout | `20.0` s | `TourPhotoExporter.swift:20` |
| Backstop grace | `2.0` s | `TourPhotoExporter.swift:21` |
| JPEG quality ladder | `[0.8, 0.65, 0.5, 0.35, 0.2]` | `TourPhotoExporter.swift:27` |
| Classic JPEG quality | `0.5` (fixed, uncapped) | `WalkShareViewModel.swift:307` |
| Hi-res photo export cap | `20` (bare literal) | `…+ShareOrchestration.swift:412` |
| Pause cap | `200` (bare literal) | `WalkShareViewModel.swift:450` |
| Background-exhaustion threshold | `remaining < 10` s, gated on `isBackground` | `ShareService.swift:281` |
| PUT attempts | `2` (`0..<2`) | `ShareService.swift:384` |
| PUT retry backoff | `800_000_000` ns = 800 ms | `ShareService.swift:404` |
| Journal char cap | `140` | `WalkShareView.swift:267` |

**DAT-1 · `RouteDownsampler.downsample` defaults to a 200-point cap for shared route geometry.** `Pilgrim/Models/Share/RouteDownsampler.swift:5-9@3f9f9e8` — **[consensus: data + edge-cases (EDG-1, EDG-2)]**
```swift
static func downsample(
        _ points: [SharePayload.RoutePoint],
        maxPoints: Int = 200
    ) -> [SharePayload.RoutePoint] {
        guard points.count > maxPoints else { return points }
```
*Drift risk:* Android could hardcode a different default or forget the early-return short-circuit for already-short routes.

**DAT-2 · If Ramer-Douglas-Peucker still overshoots `maxPoints` after epsilon search, `downsample` falls back to uniform stride sampling to hard-guarantee the cap.** `Pilgrim/Models/Share/RouteDownsampler.swift:9-15@3f9f9e8` — **[consensus: data + edge-cases (EDG-3)]**
```swift
let result = ramerDouglasPeucker(points, epsilon: findEpsilon(points, target: maxPoints))
        guard result.count <= maxPoints else {
            return strideSample(result, target: maxPoints)
        }
        return result
```
*Drift risk:* A port that only implements RDP without the stride-sample backstop can silently exceed maxPoints on adversarial geometry, risking a worker-side payload rejection.

**DAT-3 · `findEpsilon` binary-searches simplification tolerance between 0.0 and 0.01 (degrees) over exactly 20 iterations.** `Pilgrim/Models/Share/RouteDownsampler.swift:90-103@3f9f9e8` — **[consensus: data + edge-cases (EDG-14, EDG-15, EDG-16)]**
```swift
var low = 0.0
        var high = 0.01

        for _ in 0..<20 {
            let mid = (low + high) / 2
            let result = ramerDouglasPeucker(points, epsilon: mid)
            if result.count > target {
                low = mid
            } else {
                high = mid
            }
        }

        return high
```
*Drift risk:* Different bounds or iteration count change the simplification aggressiveness and could produce a visibly different downsampled route shape from the same input.

**DAT-4 · `RouteTrimmer.trim` only trims when `meters > 0`, route has more than 3 points, and total walked distance is at least 4× the trim distance; otherwise it returns the route untrimmed.** `Pilgrim/Models/Share/RouteTrimmer.swift:8-15@3f9f9e8` — **[consensus: data + behavior (BEH-1) + edge-cases (EDG-17, EDG-18, EDG-20)]**
```swift
static func trim(_ route: [SharePayload.RoutePoint], meters: Double) -> [SharePayload.RoutePoint] {
        guard meters > 0, route.count > 3 else { return route }
        var cumulative: [Double] = [0]
        for i in 1..<route.count {
            cumulative.append(cumulative[i - 1] + haversineMeters(route[i - 1], route[i]))
        }
        let total = cumulative[route.count - 1]
        guard total >= meters * 4 else { return route }
```
*Drift risk:* Missing the 4x-total guard means short walks would get over-aggressively trimmed (or trimmed to near-nothing) instead of shared untrimmed, contradicting the doc comment's stated behavior.

**DAT-5 · `RouteTrimmer.canTrim` is defined purely in terms of `trim()`'s output length, guaranteeing it can never disagree with what `trim()` actually does.** `Pilgrim/Models/Share/RouteTrimmer.swift:27-29@3f9f9e8` — **[consensus: data + behavior (BEH-2) + edge-cases (EDG-24, EDG-25)]**
```swift
static func canTrim(_ route: [SharePayload.RoutePoint], meters: Double) -> Bool {
        trim(route, meters: meters).count < route.count
    }
```
*Drift risk:* A port implementing canTrim as an independent length/threshold check (rather than delegating to trim) can drift out of sync if trim's guard logic changes later.

**DAT-34 · Background-exhaustion threshold: the app is treated as "about to be suspended" once backgrounded with less than 10 seconds of OS background time remaining (of iOS's ~30 s grant).** `Pilgrim/Models/Share/ShareService.swift:265-283@3f9f9e8` — **[consensus: data + behavior (BEH-13, BEH-14) + edge-cases (EDG-47, EDG-48)]**
```swift
nonisolated(unsafe) static var backgroundStateProvider: @MainActor () -> (isBackground: Bool, remaining: TimeInterval) = {
        (UIApplication.shared.applicationState == .background, UIApplication.shared.backgroundTimeRemaining)
    }
...
    private static func backgroundTimeExhausted() async -> Bool {
        await MainActor.run {
            let state = backgroundStateProvider()
            return state.isBackground && state.remaining < 10
        }
    }
```
*Drift risk:* This is an iOS-specific background-task-time concept with no direct Android equivalent (Android's answer is a foreground service / WorkManager, per CLAUDE.md's own mapping) — the PORT decision here isn't 'copy the threshold', it's 'does this concept even apply', which needs explicit triage rather than literal translation.

**DAT-35 · Each media item gets exactly one retry (2 total attempts), with an 800 ms fixed backoff between them, and a mid-retry background-exhaustion re-check.** `Pilgrim/Models/Share/ShareService.swift:377-411@3f9f9e8` — **[consensus: data + behavior (BEH-18), identical citation; + edge-cases EDG-52, EDG-53, EDG-54]**
```swift
private static func putWithRetry(
        shareID: String,
        kind: MediaKind,
        n: Int,
        body: () throws -> Data
    ) async -> Bool {
        var lastError: Error?
        for attempt in 0..<2 {
            do {
                let data = try body()
                let request = mediaUploadRequest(shareID: shareID, kind: kind, n: n, contentLength: data.count)
                let (_, response) = try await URLSession.shared.upload(for: request, from: data)
                if let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) {
                    return true
                }
            } catch {
                lastError = error
            }
            if attempt == 0 {
                if await backgroundTimeExhausted() {
                    return false
                }
                try? await Task.sleep(nanoseconds: 800_000_000)
            }
        }
```
*Drift risk:* Success is judged purely by status code range, ignoring response body — an Android OkHttp Call that also inspects the body for errors on 2xx would diverge from this exact contract.

**DAT-43 · TourBuilder's four hard caps: max recordings per share, max single-file bytes, max total tour bytes, and max total tour seconds (108 minutes).** `Pilgrim/Models/Share/TourBuilder.swift:24-27@3f9f9e8` — **[consensus: data + behavior (BEH-20), identical citation; + edge-cases EDG-56..EDG-59]**
```swift
static let maxRecordings = 12
    static let maxFileBytes = 15 * 1024 * 1024
    static let maxTotalBytes = 60 * 1024 * 1024
    static let maxTotalSeconds: Double = 6480  // 108 minutes — the eternal cairn's number
```
*Drift risk:* maxFileBytes/maxTotalBytes use binary MiB (1024-based) multipliers, not decimal MB — an Android constant written as 15_000_000 would be a ~5% mismatch against the worker's actual enforced cap.

**DAT-46 · `classify()` treats any transcription with fewer than 8 whitespace-separated words as ambient (non-speech); nil transcription defaults to spoken.** `Pilgrim/Models/Share/TourBuilder.swift:34-41@3f9f9e8` — **[consensus: data + ui-visual (UI-75), identical citation; + edge-cases EDG-60..EDG-63]**
```swift
static func classify(transcription: String?) -> TourRecordingKind {
        guard let text = transcription?.trimmingCharacters(in: .whitespacesAndNewlines) else {
            return .spoken
        }
        let wordCount = text.split(whereSeparator: \.isWhitespace).count
        if wordCount < 8 { return .ambient }
        return .spoken
    }
```
*Drift risk:* Explicitly no words-per-minute gate by design (per the doc comment above) — an Android port that reintroduces a wpm-based heuristic would misclassify slow contemplative speech as ambient, contradicting the documented intent.

**DAT-48 · A recording is excluded from candidates entirely (not just flagged unavailable) when its truncated end timestamp doesn't exceed its truncated start timestamp — matching the worker's rejection of `end_ts <= start_ts`.** `Pilgrim/Models/Share/TourBuilder.swift:49-54@3f9f9e8` — **[consensus: data + behavior (BEH-99) + edge-cases (EDG-66)]**
```swift
let startTs = Int(rec.startDate.timeIntervalSince1970)
            let endTs = Int(rec.endDate.timeIntervalSince1970)
            // The worker validates truncated integers and rejects the WHOLE
            // POST on end_ts <= start_ts — a sub-second blip recording must
            // be excluded here, not shipped.
            guard endTs > startTs else { return nil }
```
*Drift risk:* This is a whole-POST-rejecting worker validation rule, not a per-item soft-fail — an Android port that instead marks such a recording 'unavailable' (still visible in the list) rather than dropping it from candidates entirely would change the UI list, even though both approaches prevent the bad POST.

**DAT-49 · Per-file availability: missing/zero-byte files are marked "audio removed"; files over `maxFileBytes` are marked "too large to carry"; otherwise available and included by default.** `Pilgrim/Models/Share/TourBuilder.swift:55-77@3f9f9e8` — **[consensus: data + behavior (BEH-19), identical citation; + ui-visual UI-74; + edge-cases EDG-67, EDG-68]**
```swift
let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? Int
            let unavailableReason: String?
            if size == nil || size == 0 {
                unavailableReason = "audio removed"
            } else if let size, size > maxFileBytes {
                unavailableReason = "too large to carry"
            } else {
                unavailableReason = nil
            }
```
*Drift risk:* These exact user-facing strings appear directly in the UI (unavailableReason) — an Android port must decide whether to literally reuse this English copy or route through its own string resources; either way the THRESHOLD (15 MiB) must match exactly.

**DAT-50 · `totals()` sums only currently-included, available candidates; `validationError()` enforces the three TourBuilder caps against those totals with distinct user-facing messages.** `Pilgrim/Models/Share/TourBuilder.swift:81-94@3f9f9e8` — **[consensus: data + behavior (BEH-21) + ui-visual (UI-73) + edge-cases (EDG-69, EDG-70)]**
```swift
static func totals(of candidates: [TourRecordingCandidate]) -> (count: Int, bytes: Int, seconds: Double) {
        let included = candidates.filter { $0.includeInShare && $0.unavailableReason == nil }
        return (included.count,
                included.reduce(0) { $0 + $1.sizeBytes },
                included.reduce(0) { $0 + $1.duration })
    }

    static func validationError(for candidates: [TourRecordingCandidate]) -> String? {
        let (count, bytes, seconds) = totals(of: candidates)
        if count > maxRecordings { return "A walk page carries at most \(maxRecordings) recordings — leave some out." }
        if bytes > maxTotalBytes { return "Recordings total \(bytes / 1_048_576) MB — the page carries at most 60 MB." }
        if seconds > maxTotalSeconds { return "Recordings total \(Int(seconds / 60)) minutes — the page carries at most \(Int(maxTotalSeconds / 60))." }
        return nil
    }
```
*Drift risk:* bytes / 1_048_576 in the message uses integer division on Int (truncating), and the '60 MB' in that string is a hardcoded literal separate from maxTotalBytes's actual byte value — if maxTotalBytes ever changes, this message string would need a matching manual edit; worth flagging as a magic-number-duplication trap for the Android port's own message strings.

**DAT-51 · `TourPhotoExporter`'s four constants: 2 MB per-photo byte cap, 1600 px target dimension, 20 s PhotoKit timeout, 2 s backstop grace beyond that timeout.** `Pilgrim/Models/Share/TourPhotoExporter.swift:18-21@3f9f9e8` — **[consensus: data + behavior (BEH-23), identical citation; + edge-cases EDG-74, EDG-75, EDG-80]**
```swift
static let maxBytes = 2 * 1024 * 1024
    static let targetPixels: CGFloat = 1600
    static let perPhotoTimeout: TimeInterval = 20
    static let backstopGrace: TimeInterval = 2
```
*Drift risk:* targetPixels 1600 is explicitly higher than the classic share page's inline thumbnail size — the doc comment contrasts it against a 600px value used elsewhere (WalkShareViewModel.loadSharePhoto's targetSize), so an Android port must not conflate the two photo pipelines' target resolutions.

**DAT-52 · `jpegDataUnder` walks a fixed quality ladder `[0.8, 0.65, 0.5, 0.35, 0.2]`, returning the first encoding that fits under the byte cap, or nil if none do.** `Pilgrim/Models/Share/TourPhotoExporter.swift:26-33@3f9f9e8` — **[consensus: data + edge-cases (EDG-76, EDG-82)]**
```swift
static func jpegDataUnder(cap: Int, image: UIImage) -> Data? {
        for quality in [0.8, 0.65, 0.5, 0.35, 0.2] {
            if let data = image.jpegData(compressionQuality: quality), data.count <= cap {
                return data
            }
        }
        return nil
    }
```
*Drift risk:* Android's Bitmap.compress(JPEG, quality, …) quality scale is an Int 0-100, not a Float 0.0-1.0 — the five ladder values need an exact ×100 translation (80, 65, 50, 35, 20), and JPEG encoders can produce different byte counts at nominally-equal quality settings across platforms, so the SAME image might exhaust the ladder differently.

**DAT-56 · `WalkShareViewModel`'s route-trim distance constant: 150 metres shaved off each end when interactive+trim are both enabled.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:36@3f9f9e8` — **[consensus: data + edge-cases (EDG-120)]**
```swift
static let trimMeters = 150
```
*Drift risk:* This literal feeds both RouteTrimmer.trim(meters:) and the wire trimM value (via computeInteractiveRoute) — a mismatched constant on Android would make the reported trim_m disagree with the actual geometry sent.

**DAT-60 · Interactive `talkDuration` is clamped to `walk.talkDuration` (not allowed to exceed it) because the worker 400s when meditate+talk exceeds active duration.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:363-365@3f9f9e8` — **[consensus: data + behavior (BEH-86) + edge-cases (EDG-142)]**
```swift
// Recordings outrun active time by design (a talk can run through a pause); NewWalk clamps talkDuration to activeDuration for the same reason, and the worker 400s on meditate+talk > active — clamp the included-candidate sum the same way.
            talkDuration: interactive ? min(includedTalkCandidates.reduce(0) { $0 + $1.duration }, walk.talkDuration) : walk.talkDuration,
```
*Drift risk:* This clamp only applies in interactive mode — classic mode sends walk.talkDuration unclamped (trusting it was already clamped upstream at recording time per the comment) — an Android port must replicate this asymmetry, not clamp both paths identically or neither.

**DAT-66 · `interactivePhotoExportList` caps the export list at 20 photos and filters `pinnedPhotos` to the same `keptWindow` the trimmed route computed, reused identically by both the live `share()` export and any later `retryFailedMedia()` re-export.** `.../WalkShareViewModel+ShareOrchestration.swift:406-414@3f9f9e8` — **[consensus: data + behavior (BEH-75) + edge-cases (EDG-119)]**
```swift
func interactivePhotoExportList() -> [PhotoCandidate] {
        guard hasPinnedPhotos else { return [] }
        let window = interactiveKeptWindow()
        return Array(
            pinnedPhotos
                .filter { window?.contains(Int($0.capturedAt.timeIntervalSince1970)) ?? true }
                .prefix(20)
        )
    }
```
*Drift risk:* 20 is a distinct cap from TourBuilder.maxRecordings (12) and is NOT centrally defined alongside the other TourBuilder/TourPhotoExporter constants — easy to miss when enumerating 'all the caps' for an Android port since it's a bare literal inline here rather than a named static constant.

### 4.7 Upload contract

**DAT-17 · Unit tests explicitly pin the omit-when-nil contract for `tour`, `pauses`, and `Photo.data` — confirming the `encodeIfPresent` behavior is tested, not incidental.** `UnitTests/SharePayloadTourTests.swift:59-71@3f9f9e8` — **[consensus: data + edge-cases (EDG-159, EDG-160)]**
```swift
func testAbsentTourAndPausesOmittedFromJSON() throws {
        let json = try encodeToJSON(minimalPayload(tour: nil))
        XCTAssertNil(json["tour"])
        XCTAssertNil(json["pauses"])
    }

    func testPhotoWithoutDataOmitsDataKey() throws {
        let photo = SharePayload.Photo(lat: 35.69, lon: -105.94, ts: 1200, data: nil)
        let json = try encodeToJSON(minimalPayload(tour: nil, photos: [photo]))
        let photos = try XCTUnwrap(json["photos"] as? [[String: Any]])
        XCTAssertNil(photos[0]["data"])
        XCTAssertEqual(photos[0]["ts"] as? Int, 1200)
    }
```
*Drift risk:* An Android parity test suite needs the equivalent assertion (key absence, not null-value presence) for every optional field, or a regression here would go undetected by naive 'field equals null' checks.

**DAT-18 · Test confirms Tour/Pause snake_case field names round-trip through real JSONEncoder/JSONSerialization, including a recording whose `wpm` is nil.** `UnitTests/SharePayloadTourTests.swift:31-57@3f9f9e8` — **[consensus: data + edge-cases (EDG-161)]**
```swift
XCTAssertEqual(tourJSON["trim_m"] as? Int, 150)
        let recs = try XCTUnwrap(tourJSON["recordings"] as? [[String: Any]])
        XCTAssertEqual(recs.count, 2)
        XCTAssertEqual(recs[0]["n"] as? Int, 1)
        XCTAssertEqual(recs[0]["start_ts"] as? Int, 1100)
        XCTAssertEqual(recs[0]["end_ts"] as? Int, 1400)
        XCTAssertEqual(recs[0]["kind"] as? String, "spoken")
        XCTAssertEqual(recs[0]["size_bytes"] as? Int, 2_400_000)
        XCTAssertEqual(recs[1]["wpm"] as? Double, nil)
```
*Drift risk:* This confirms wire field names precisely; a wpm-as-null-cast assertion doesn't by itself distinguish 'key absent' from 'key present with JSON null', so pair with the omission test above for full contract coverage on the Android side.

**DAT-30 · Doc-level contract for `uploadAllMedia`: sequential (not parallel) uploads, photos strictly before audio, 1-based per-kind index, one retry per item, wrapped in a background-task assertion.** `Pilgrim/Models/Share/ShareService.swift:160-167@3f9f9e8` — **[consensus: data + behavior (BEH-9) + edge-cases (EDG-43)]**
```swift
/// Sequential by contract: photos MUST land in index order (enrich
    /// HEADs only the last one), and one-at-a-time keeps memory flat for
    /// 15MB audio files. PHOTOS UPLOAD FIRST — they gate the keepsake
    /// render window; audio degrades gracefully to "voice unavailable".
    /// Each item gets one retry. Runs inside a background-task assertion
    /// so pocketing the phone doesn't kill the remaining PUTs. Returns
    /// the indices (1-based, per kind) that ultimately failed.
    static func uploadAllMedia(
```
*Drift risk:* Photos-before-audio ordering is a deliberate product decision (keepsake render gating), not an implementation detail — reversing it on Android would change which failures are user-visible first. **Note:** the function's PARAMETER order is `audioFiles:` then `photos:`, while the LOOP order is photos then audio — see §8.

**DAT-31 · The photos loop: 1-based index via `index + 1`, per-item background-exhaustion short-circuit that marks all remaining photo slots failed without attempting the PUT, `onItemSuccess` callback per landed item, and a completed-count accounting bounded to the loop's own remaining items.** `Pilgrim/Models/Share/ShareService.swift:184-203@3f9f9e8` — **[consensus: data + behavior (BEH-10, BEH-11) + edge-cases (EDG-44, EDG-45)]**
```swift
for (index, data) in photos.enumerated() {
                if await backgroundTimeExhausted() {
                    for remaining in index..<photos.count { failures.append((.photos, remaining + 1)) }
                    completed += photos.count - index
                    report()
                    break
                }
                let ok = await putWithRetry(shareID: shareID, kind: .photos, n: index + 1) { data }
                if ok {
                    onItemSuccess?(.photos, index + 1)
                } else {
                    failures.append((.photos, index + 1))
                }
                completed += 1
                report()
            }
```
*Drift risk:* The 'bounded to what THIS loop still owes' completed-count comment (line 190) flags a specific off-by-one class of bug a naive port could reintroduce by jumping straight to the grand total.

**DAT-32 · The audio loop mirrors the photos loop exactly, reading file bytes lazily via `try Data(contentsOf: fileURL)` inside `putWithRetry`'s body closure (not pre-loaded).** `Pilgrim/Models/Share/ShareService.swift:205-223@3f9f9e8` — **[consensus: data + behavior (BEH-12) + edge-cases (EDG-46)]**
```swift
for (index, fileURL) in audioFiles.enumerated() {
                if await backgroundTimeExhausted() {
                    for remaining in index..<audioFiles.count { failures.append((.audio, remaining + 1)) }
                    completed += audioFiles.count - index
                    report()
                    break
                }
                let ok = await putWithRetry(shareID: shareID, kind: .audio, n: index + 1) {
                    try Data(contentsOf: fileURL)
                }
```
*Drift risk:* Lazy per-attempt file reads mean a retry re-reads the file from disk rather than reusing bytes already loaded into memory — this is the stated 'keeps memory flat for 15MB audio files' design; buffering all audio upfront on Android would defeat that.

**DAT-33 · `uploadSpecific` is the targeted-retry variant used by "Carry the missing files" — same background-exhaustion short-circuit and per-item progress, driven by a caller-supplied `(kind, n, data-thunk)` list instead of full audioFiles/photos arrays.** `Pilgrim/Models/Share/ShareService.swift:228-258@3f9f9e8`
```swift
static func uploadSpecific(
        shareID: String,
        items: [(kind: MediaKind, n: Int, data: () throws -> Data)],
        onItemSuccess: ((MediaKind, Int) -> Void)? = nil,
        progress: @escaping (MediaProgress) -> Void
    ) async -> [(kind: MediaKind, n: Int)] {
```
*Drift risk:* n here is the CACHED original slot number (from FailedMediaItem), not a freshly computed index — conflating the two would upload retried bytes under the wrong slot.

**DAT-64 · `computeInteractiveRoute` reports trimM/keptWindow strictly by OUTCOME (whether `RouteTrimmer` actually shortened the array), never by intent.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:469-482@3f9f9e8` — **[consensus: data + behavior (BEH-87) + ui-visual (UI-68) + edge-cases (EDG-146, EDG-147) — four-lens]**
```swift
private func computeInteractiveRoute() -> (route: [SharePayload.RoutePoint], trimM: Int, keptWindow: ClosedRange<Int>?) {
        let downsampled = downsampledRoutePoints()
        guard interactiveEnabled && trimEnabled else { return (downsampled, 0, nil) }

        let trimmed = RouteTrimmer.trim(downsampled, meters: Double(Self.trimMeters))
        let didTrim = trimmed.count < downsampled.count
        let trimM = didTrim ? Self.trimMeters : 0
        let keptWindow: ClosedRange<Int>? = (didTrim && trimmed.count >= 2)
            ? trimmed.first!.ts...trimmed.last!.ts
            : nil
        return (trimmed, trimM, keptWindow)
    }
```
*Drift risk:* keptWindow (a timestamp range) is what gates BOTH waypoints and photo metadata inclusion in interactive mode (see waypointPayload/interactivePhotoExportList) — an Android port that derives keptWindow from a DIFFERENT trim outcome check than the one that produced the actual route array risks a doorstep photo surviving a trim that hid the doorstep route.

**DAT-65 · `applyInteractiveTourAndPauses` caps pauses at 200 entries and filters out any pause whose truncated end equals or precedes its truncated start, mirroring the same truncated-integer worker validation `TourBuilder.candidates()` applies to recordings.** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:442-452@3f9f9e8` — **[consensus: data + edge-cases (EDG-144)]**
```swift
private func applyInteractiveTourAndPauses(to payload: inout SharePayload, trimM: Int) {
        payload.tour = TourBuilder.tourItems(candidates: tourCandidates, trimM: trimM).tour
        // The worker validates TRUNCATED integers: filter after truncation or a
        // sub-second pause 400s the whole share.
        payload.pauses = Array(
            walk.pauses
                .map { (start: Int($0.startDate.timeIntervalSince1970), end: Int($0.endDate.timeIntervalSince1970)) }
                .filter { $0.end > $0.start }
                .prefix(200)
        ).map { SharePayload.Pause(startTs: $0.start, endTs: $0.end) }
    }
```
*Drift risk:* Order of operations matters: truncate-to-Int happens BEFORE the end>start filter — filtering on the original Double timestamps first (before truncation) could let a sub-second pause that truncates to end==start slip through on an Android port that reorders these steps.

**DAT-67 · `completeShare` pre-populates the failed-media cache with an "everything about to be uploaded" record BEFORE `uploadAllMedia` runs, then overwrites it with the actual failures after — a THREE-write lifecycle per share.** `.../WalkShareViewModel+ShareOrchestration.swift:125-155@3f9f9e8` — **[consensus: data + behavior (BEH-59) + edge-cases (EDG-113)]**
```swift
if let uuid = walk.uuid {
                    // Pre-populate so a kill mid-upload restores a repairable .partial instead of a lying .success; PUTs are idempotent, over-repair is harmless.
                    ShareService.cacheFailedMedia(Self.expectedFailureRecords(recordings: audioRecordings, photos: tourPhotos), walkID: uuid)
                }
...
                let failures = await ShareService.uploadAllMedia(
...
                if let uuid = walk.uuid {
                    let failureItems = failures.map {
                        Self.failedMediaItem(for: $0, audioRecordings: audioRecordings, tourPhotos: tourPhotos)
                    }
                    ShareService.cacheFailedMedia(failureItems, walkID: uuid)
                }
```
*Drift risk:* This write-before/write-after pattern plus the per-item pruneFailedMedia (removes one slot the instant its PUT lands) form a THREE-write lifecycle per share, not a single write at the end — an Android WorkManager-based port must replicate all three writes (pre-emptive full-failure, per-item prune, final overwrite) to get the same crash-survival guarantee, not just the final one.

**DAT-68 · In classic (non-interactive) mode, a successful share explicitly clears any PRE-EXISTING failed-media record for the walk.** `.../WalkShareViewModel+ShareOrchestration.swift:156-163@3f9f9e8` — **[consensus: data + behavior (BEH-62)]**
```swift
} else {
                if let uuid = walk.uuid {
                    // A fresh share must never inherit a previous share's failed-media
                    // record — this walk may have had a `.partial` share before.
                    ShareService.cacheFailedMedia([], walkID: uuid)
                }
                shareState = .success(url: result.url)
            }
```
*Drift risk:* Easy to omit on a port since classic mode itself never uploads media and might look like it has 'nothing to do' with the failed-media cache — but skipping this clear would leave a stale repair offer from an earlier interactive share attached to a walk that's now been re-shared classically.

**DAT-69 · `resolveRetryItems` matches each cached failure to CURRENT data by stable identity and uploads matched items under the ORIGINAL cached slot number `n`; unmatched items are returned untouched in `remaining` rather than uploaded to a guessed slot.** `.../WalkShareViewModel+ShareOrchestration.swift:318-360@3f9f9e8` — **[consensus: data + behavior (BEH-71), identical citation; + edge-cases EDG-117, EDG-118]**

| kind | identity key |
|---|---|
| `audio` | `recording.startTs == item.audioStartTs` (single-field) |
| `photos` | `sourceLocalIdentifier == item.photoLocalID` **AND** `meta.ts == item.photoTs` (compound) |

```swift
for item in cached {
            guard let kind = ShareService.MediaKind(rawValue: item.kind) else {
                remaining.append(item)
                continue
            }
            switch kind {
            case .audio:
                guard let index = currentRecordings.firstIndex(where: { $0.startTs == item.audioStartTs }),
                      currentAudioFiles.indices.contains(index) else {
                    remaining.append(item)
                    continue
                }
                let fileURL = currentAudioFiles[index]
                uploadable.append((kind: .audio, n: item.n, data: { try Data(contentsOf: fileURL) }))
            case .photos:
                guard let match = currentPhotos.first(where: {
                    $0.sourceLocalIdentifier == item.photoLocalID && $0.meta.ts == item.photoTs
                }) else {
                    remaining.append(item)
                    continue
                }
                let data = match.jpegData
                uploadable.append((kind: .photos, n: item.n, data: { data }))
            }
        }
```
*Drift risk:* This is a `nonisolated static` pure function specifically so it's directly unit-testable without MainActor/instance state — an Android equivalent should similarly be a pure, side-effect-free function taking plain data in and returning plain data out, to preserve the same direct-testability property called out in the doc comment.

**DAT-70 · `pruneFailedMedia` performs an immediate read-modify-write against the failed-media cache the instant a single item's PUT lands (via `onItemSuccess`), independent of the final end-of-run `cacheFailedMedia` write.** `.../WalkShareViewModel+ShareOrchestration.swift:371-376@3f9f9e8` — **[consensus: data + behavior (BEH-72)]**
```swift
private func pruneFailedMedia(kind: ShareService.MediaKind, n: Int) {
        guard let uuid = walk.uuid else { return }
        var remaining = ShareService.failedMedia(for: uuid)
        remaining.removeAll { $0.kind == kind.rawValue && $0.n == n }
        ShareService.cacheFailedMedia(remaining, walkID: uuid)
    }
```
*Drift risk:* This is a read-modify-write racing against itself if two onItemSuccess calls could ever fire concurrently — but the upload loops are documented sequential (one PUT at a time), so this is safe ONLY under that sequential contract; an Android port that parallelized uploads for speed would need to make this prune atomic/synchronized instead.

**DAT-72 · Test proves `cacheFailedMedia` round-trips full identity fields (not just kind/n), that pruning one completed item leaves exactly the other untouched, and that caching an empty array clears the record entirely.** `UnitTests/ShareMediaUploadTests.swift:22-42@3f9f9e8` — **[consensus: data + behavior (BEH-90), identical citation; + edge-cases EDG-156, EDG-157]**
```swift
let failures: [ShareService.FailedMediaItem] = [
            ShareService.FailedMediaItem(kind: "photos", n: 2, audioStartTs: nil, photoLocalID: "photo-abc", photoTs: 1_000),
            ShareService.FailedMediaItem(kind: "audio", n: 1, audioStartTs: 500, photoLocalID: nil, photoTs: nil)
        ]

        ShareService.cacheFailedMedia(failures, walkID: walkID)
        let reloaded = ShareService.failedMedia(for: walkID)
        XCTAssertEqual(reloaded, failures, "round-trip through JSON must preserve identity fields, not just kind/n")
...
        let afterOnePruned = ShareService.failedMedia(for: walkID).filter { !($0.kind == "audio" && $0.n == 1) }
        ShareService.cacheFailedMedia(afterOnePruned, walkID: walkID)
        XCTAssertEqual(ShareService.failedMedia(for: walkID), [failures[0]], "pruning the completed item must remove exactly it, leaving the other cached failure untouched")

        ShareService.cacheFailedMedia([], walkID: walkID)
        XCTAssertTrue(ShareService.failedMedia(for: walkID).isEmpty)
```
*Drift risk:* An Android DataStore Preferences implementation using kotlinx.serialization must confirm the same round-trip equality (including null fields) in an equivalent test, not just that SOME data persists.

**DAT-73 · Test proves that when the background-time budget is exhausted from the very first item of BOTH loops, every item fails without any network attempt (the nonexistent audio file URL is never even read), and the final progress tick still lands exactly on (total, total).** `UnitTests/ShareMediaUploadTests.swift:57-70@3f9f9e8` — **[consensus: data + behavior (BEH-89), identical citation; + edge-cases EDG-158]**
```swift
func testUploadAllMediaSkipsNetworkWhenBackgroundExhausted() async {
        ShareService.backgroundStateProvider = { (true, 2) } // background, well under the 10s threshold

        let audioFiles = [URL(fileURLWithPath: "/tmp/pilgrim-share-media-upload-tests-nonexistent.m4a")]
        let photos = [Data([0xAA]), Data([0xBB])]
        var lastProgress: ShareService.MediaProgress?

        let failures = await ShareService.uploadAllMedia(shareID: "test-share-id", audioFiles: audioFiles, photos: photos) { progress in
            lastProgress = progress
        }

        XCTAssertEqual(failures.count, audioFiles.count + photos.count, "background-exhausted from the very first item of each loop must fail everything without attempting a PUT — the nonexistent audio fileURL never gets read")
        XCTAssertEqual(lastProgress, ShareService.MediaProgress(completed: audioFiles.count + photos.count, total: audioFiles.count + photos.count), "the per-loop skip accounting must still land exactly on (total, total)")
```
*Drift risk:* This test's assertion that the nonexistent audio file URL 'never gets read' proves the background-exhaustion check happens BEFORE the lazy file-read closure runs, not after a failed read — an Android port checking exhaustion only around the network call (not before the file I/O) would throw a FileNotFoundException instead of cleanly skipping.

**DAT-74 · `tourItems()` builds the final wire `Tour` + parallel `files` array only from candidates that are included, available, AND have a resolved `fileURL`; recording index `n` is 1-based; transcription is deliberately never wired through even though the candidate has it.** `Pilgrim/Models/Share/TourBuilder.swift:96-115@3f9f9e8` — **[consensus: data + behavior (BEH-22) + edge-cases (EDG-71, EDG-72, EDG-73)]**
```swift
static func tourItems(candidates: [TourRecordingCandidate], trimM: Int) -> (tour: SharePayload.Tour, files: [URL]) {
        let included = candidates.filter { $0.includeInShare && $0.unavailableReason == nil && $0.fileURL != nil }
        let recordings = included.enumerated().map { index, c in
            SharePayload.TourRecording(
                n: index + 1,
                startTs: c.startTs,
                endTs: c.endTs,
                duration: c.duration,
                kind: c.effectiveKind.rawValue,
                // Transcripts never leave the device: the page renders none, and
                // a 108-minute walk's transcripts would blow the 2MB POST budget.
                // Deliberate — do not wire c.transcription through.
                transcription: nil,
                wpm: c.wpm,
                sizeBytes: c.sizeBytes
            )
        }
        let files = included.compactMap(\.fileURL)
        return (SharePayload.Tour(recordings: recordings, trimM: trimM), files)
    }
```
*Drift risk:* `recordings` and `files` are built from the SAME `included` array in the SAME order/filter, so recording n and files[n-1] always correspond — an Android port computing these two lists via separate filter passes risks index misalignment if the filter predicates ever diverge even slightly.

---

## 5. Edge cases & invariants

182 edge-cases-lens findings, sorted by category then file, cross-lens-consensus rows first within each file group. IDs run `EDG-1`…`EDG-182` matching their position in the lens output.

**File key** (every citation carries `@3f9f9e8`):

| short | path |
|---|---|
| `RD` | `Pilgrim/Models/Share/RouteDownsampler.swift` |
| `RT` | `Pilgrim/Models/Share/RouteTrimmer.swift` |
| `SP` | `Pilgrim/Models/Share/SharePayload.swift` |
| `SS` | `Pilgrim/Models/Share/ShareService.swift` |
| `TB` | `Pilgrim/Models/Share/TourBuilder.swift` |
| `TPE` | `Pilgrim/Models/Share/TourPhotoExporter.swift` |
| `ISS` | `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift` |
| `SSS` | `Pilgrim/Scenes/WalkShare/ShareStatusSection.swift` |
| `WSV` | `Pilgrim/Scenes/WalkShare/WalkShareView.swift` |
| `ORCH` | `Pilgrim/Scenes/WalkShare/WalkShareViewModel+ShareOrchestration.swift` |
| `VM` | `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift` |
| `RTT` | `UnitTests/RouteTrimmerTests.swift` |
| `SMUT` | `UnitTests/ShareMediaUploadTests.swift` |
| `SPTT` | `UnitTests/SharePayloadTourTests.swift` |
| `TBT` | `UnitTests/TourBuilderTests.swift` |
| `TPET` | `UnitTests/TourPhotoExporterTests.swift` |
| `WSIT` | `UnitTests/WalkShareInteractiveTests.swift` |
| `WDF` | `UnitTests/Helpers/WalkDataFactory.swift` |

### 5.1 async-delay (6) — all cross-lens consensus

| citation | quote | invariant | why-could-Android-miss |
|---|---|---|---|
| **EDG-42** ✅ `SS:153-156@3f9f9e8` | `// Idle timeout — resets on bytes moving, so slow uploads survive; ⏎ // stalls fail fast so the repair path picks them up instead of ⏎ // burning background time on a connection that's already dead. ⏎ request.timeoutInterval = 30` | Media PUT requests use a 30-second IDLE timeout (resets on byte movement), explicitly not a whole-upload timeout — pinned by an explicit non-obvious comment and a dedicated test. | URLRequest.timeoutInterval is inherently an idle timeout in iOS's networking stack; Android's OkHttp default readTimeout/writeTimeout must be configured the same way (not a total call timeout), or a legitimately slow-but-progressing 15MB upload would be killed early. |
| **EDG-48** ✅ `SS:269-281@3f9f9e8` | `/// iOS grants ~30s of background time total — a threshold at or above that ⏎ /// grant is always-true the instant the app backgrounds, abandoning the ⏎ /// usable ~25s before it. 10s lets small items still proceed and only ⏎ /// stops near true exhaustion; the real fix is a background URLSession ⏎ /// (scheduled fast-follow). ⏎ private static func backgroundTimeExhausted() async -> Bool { ⏎ await MainActor.run { ⏎ let state = backgroundStateProvider() ⏎ return state.isBackground && state.remaining < 10 ⏎ } ⏎ }` | iOS's background-time-exhaustion threshold is a hardcoded 10 seconds, deliberately conservative against a documented ~30s total OS grant, with the rationale spelled out in the doc comment. | This entire mechanism models iOS's ~30-second background-task budget cliff, which has no direct Android analog given the project's foreground-service architecture (per CLAUDE.md) — Android must NOT port this literally as 'stop uploads with 10s of background budget left' since a foreground service doesn't have this cliff; but the higher-level behaviors it protects (sequential-photos-first, 1-based n, one retry, idle timeout) absolutely must survive the port even though this specific timer construct may not need a literal equivalent. |
| **EDG-53** ✅ `SS:404@3f9f9e8` | `try? await Task.sleep(nanoseconds: 800_000_000)` | The inter-attempt backoff sleep is a hardcoded 800 milliseconds expressed in nanoseconds. | A different backoff duration changes total retry latency and how quickly background time budget (10s threshold) gets consumed across the retry cycle. |
| **EDG-80** ✅ `TPE:20-21,55-63@3f9f9e8` | `static let perPhotoTimeout: TimeInterval = 20 ⏎ static let backstopGrace: TimeInterval = 2` | Each photo load is bounded by a two-tier timeout: a 20-second primary cancel plus an independent 2-second backstop grace, guaranteeing the wall-clock bound is never truly unbounded even if PhotoKit's callback never fires at all. | A wedged/hung photo-load on Android (e.g. a stuck MediaStore/ContentResolver query) needs an equivalent independent backstop timer that doesn't rely on the primary cancellation mechanism actually working — a single-timeout implementation would hang indefinitely if the underlying platform API's cancel call is a no-op in some edge case, exactly the failure mode this two-tier design defends against. |
| **EDG-101** ✅ `WSV:130@3f9f9e8` | `try? await Task.sleep(for: .milliseconds(500))` | The podcast-card reveal is delayed by 500ms after the ritual modal dismisses. | Distinct from the 800ms ritual-reveal delay elsewhere in the same file — conflating the two delays would change the felt pacing of two different UI moments. |
| **EDG-102** ✅ `WSV:373-378@3f9f9e8` | `revealTask = Task { ⏎ try? await Task.sleep(for: .milliseconds(800)) ⏎ guard !Task.isCancelled else { return } ⏎ await MainActor.run { ⏎ UIImpactFeedbackGenerator(style: .soft).impactOccurred() ⏎ showPreview = true ⏎ } ⏎ }` | The ritual (share-success) preview reveal is delayed by 800ms, timed to a deliberate 'beat' before the modal appears with a haptic. | This is the specific delay the podcast-card bug-history comment refers to as colliding with a previous naive 800ms-after-success trigger — Android must NOT reuse this same 800ms value for anything other than the ritual reveal itself. |

### 5.2 boundary-arithmetic (22)

| citation | quote | invariant | why-could-Android-miss |
|---|---|---|---|
| **EDG-16** ✅ `RD:96-103@3f9f9e8` | `if result.count > target { ⏎ low = mid ⏎ } else { ⏎ high = mid ⏎ } ⏎ } ⏎ ⏎ return high` | findEpsilon returns the upper binary-search bound (high), which biases toward under-simplifying rather than exactly hitting the target count — the real guarantee comes from the caller's strideSample fallback, not from this function. | There is no dedicated RouteDownsampler test file in this slice — an Android port with a subtly different convergence/rounding behavior here would go undetected unless a route exceeding 200 points is exercised in a new test. |
| EDG-4 `RD:21@3f9f9e8` | `let step = Double(points.count - 1) / Double(target - 1)` | strideSample's step computation divides by target-1, which is a division-by-zero if target is ever 1. | If maxPoints were ever passed as 1, Swift produces infinity (no crash) but Kotlin/Java integer paths could crash differently; Android must guard the same edge the same way (or confirm it's unreachable). |
| EDG-5 `RD:23-27@3f9f9e8` | `for i in 0..<(target - 1) { ⏎ result.append(points[Int((Double(i) * step).rounded())]) ⏎ } ⏎ result.append(points[points.count - 1])` | strideSample builds target-1 interpolated points then appends the true last point separately. | Getting the loop bound wrong yields target-1 or target+1 points instead of exactly target, breaking the caller's size guarantee. |
| EDG-8 `RD:42@3f9f9e8` | `for i in 1..<(points.count - 1) {` | The max-deviation search deliberately excludes the first and last points (the anchor line endpoints) from candidacy. | Including endpoint 0 or count-1 in the deviation search would let RDP recurse on itself or double-count anchor points. |
| EDG-11 `RD:68-72@3f9f9e8` | `guard lengthSq > 0 else { ⏎ let px = point.lon - lineStart.lon ⏎ let py = point.lat - lineStart.lat ⏎ return sqrt(px * px + py * py) ⏎ }` | Degenerate (zero-length) line segments fall back to plain point-to-point distance instead of dividing by zero. | Missing this guard causes NaN/divide-by-zero when consecutive anchor points share identical coordinates (e.g., a paused walk with duplicate GPS fixes). |
| **EDG-19** ✅ `RT:10-13@3f9f9e8` | `var cumulative: [Double] = [0] ⏎ for i in 1..<route.count { ⏎ cumulative.append(cumulative[i - 1] + haversineMeters(route[i - 1], route[i])) ⏎ }` | Cumulative distance array is built starting at index 1, referencing i-1 for the running sum. | An indexing slip here silently corrupts every downstream distance comparison (start/end pointer walks, the 4x gate). |
| **EDG-21** ✅ `RT:18@3f9f9e8` | `while start < route.count - 1 && cumulative[start] < meters { start += 1 }` | The start pointer walk is bounded by route.count-1 to avoid overrunning the array. | Without the upper bound, a route entirely shorter than `meters` (but somehow past the 4x gate — not reachable today, but a future refactor could reorder guards) would walk off the end of the array. |
| EDG-22 `RT:19-20@3f9f9e8` | `var end = route.count - 1 ⏎ while end > 0 && total - cumulative[end] < meters { end -= 1 }` | The end pointer walk mirrors the start walk symmetrically from the tail. | Asymmetric bound logic between start/end walks would produce inconsistent trim behavior for routes trimmed near their geometric limits. |
| **EDG-37** ✅ `SS:73,389@3f9f9e8` | `guard (200...299).contains(httpResponse.statusCode) else {` | Success is defined as the inclusive HTTP range 200...299, checked twice in this file at two independent call sites. | Both the POST path and the media-PUT retry path duplicate this inclusive range independently; Android must replicate the identical inclusive boundary at both call sites, not just one. |
| **EDG-44** ✅ `SS:195@3f9f9e8` | `let ok = await putWithRetry(shareID: shareID, kind: .photos, n: index + 1) { data }` | Photo upload slots are 1-based (index+1), not 0-based. | A 0-based Android port would PUT to /photos/0 which the worker likely rejects or silently misfiles against a 1-based slot scheme. |
| **EDG-46** ✅ `SS:205-213@3f9f9e8` | `for (index, fileURL) in audioFiles.enumerated() { ⏎ if await backgroundTimeExhausted() { ⏎ for remaining in index..<audioFiles.count { failures.append((.audio, remaining + 1)) } ⏎ // Same reasoning as the photos loop above. ⏎ completed += audioFiles.count - index ⏎ report() ⏎ break ⏎ } ⏎ let ok = await putWithRetry(shareID: shareID, kind: .audio, n: index + 1) {` | Audio upload slots are also 1-based, mirroring the photo loop exactly, with an explicit cross-reference comment. | Same 1-based slot requirement as photos; audio and photos are independently 1-based, not offset from each other. |
| **EDG-65** ✅ `TB:45-46@3f9f9e8` | `let sorted = walk.voiceRecordings.sorted { $0.startDate < $1.startDate } ⏎ return sorted.enumerated().compactMap { index, rec in` | Candidate id values come from enumerate()-before-filter on the sorted recordings list, so surviving candidates can have non-contiguous ids if a sub-second-blip recording is filtered out mid-list. | An Android port that reassigns ids sequentially over the SURVIVING (post-filter) list instead of the pre-filter enumeration index would break stable candidate identity across recompositions/toggles — toggleInclude/flipKind both do id-based lookups (firstIndex(where:)), never positional array access, precisely because ids are not guaranteed dense. |
| **EDG-73** ✅ `TB:96-101@3f9f9e8` | `static func tourItems(candidates: [TourRecordingCandidate], trimM: Int) -> (tour: SharePayload.Tour, files: [URL]) { ⏎ let included = candidates.filter { $0.includeInShare && $0.unavailableReason == nil && $0.fileURL != nil } ⏎ let recordings = included.enumerated().map { index, c in ⏎ SharePayload.TourRecording( ⏎ n: index + 1,` | tourItems renumbers the final 'n' densely and 1-based over ONLY the included, available, file-backed candidates — decoupled entirely from candidate.id. | Test-pinned via testTourItems_renumbersAfterExclusion (ids 0,1,2 with id=1 excluded produces n=[1,2], NOT [1,3]) — this proves there are TWO independent numbering schemes in play (sparse 0-based candidate.id for UI toggle identity, dense 1-based recomputed n for the wire/upload-slot identity); conflating them in Android would misalign every upload PUT with its declared metadata. |
| **EDG-79** ✅ `TPE:50@3f9f9e8` | `progress(i + 1, candidates.count)` | Progress is reported as a 1-based 'done' count against a 0-based loop index. | Consistent with the 1-based convention used throughout this slice (upload slots, display numbers); an Android port using the raw 0-based index would show 'Preparing photos... 0/5' on the first photo instead of '1/5'. |
| **EDG-89** ✅ `ISS:143,182,200@3f9f9e8` | `Text("Recording \(candidate.id + 1) · \(durationLabel) · \(startLabel)")` | The recording number shown to the user is candidate.id+1 (fixed original sorted position), which can diverge from the server-side renumbered upload slot 'n' once any recording is excluded. | If recordings 1 and 3 (by display number) are the only two included, the server's actual TourRecording.n for the displayed 'Recording 3' is really 2 (dense renumbering in TourBuilder.tourItems) — the UI number and the wire slot number are NOT the same value whenever any recording upstream of it is excluded; this is a real, easy-to-miss product-visible numbering divergence, not a bug, but Android must replicate it exactly (display uses fixed id+1, never the recomputed n). |
| **EDG-96** ✅ `SSS:116@3f9f9e8` | `Text("\(dropped) of \(prepared + dropped) photo\(dropped == 1 ? "" : "s") couldn't be prepared — they may still be waiting in iCloud.")` | The dropped-photos prompt recomputes 'total requested' inline as prepared+dropped rather than being passed a separately-tracked total. | Android's equivalent UI state must recompute this sum the same way at render time rather than caching a separate 'requested count' field that could desync from the two counts it's derived from. |
| **EDG-111** ✅ `ORCH:61-62@3f9f9e8` | `let dropped = exportCount - tourPhotos.count ⏎ if dropped > 0 {` | The dropped-photo count is a simple arithmetic difference between requested export count and actually-exported count, gated by a strict >0 threshold. | If exportCount and tourPhotos.count could ever be computed from different underlying lists (a refactor risk), this difference could go negative or overcount silently. |
| EDG-115 `ORCH:182,185@3f9f9e8` | `let startTs = audioRecordings.indices.contains(failure.n - 1) ? audioRecordings[failure.n - 1].startTs : nil` | A failed item's 1-based n is converted back to a 0-based array index via n-1, always guarded by an explicit bounds check before subscripting. | Omitting the bounds check (a natural simplification since n 'should' always be in range) would crash on any inconsistency between the reported failure count and the actual array length; Android's equivalent must keep the same defensive bounds check even though it looks redundant. |
| **EDG-116** ✅ `ORCH:199-210@3f9f9e8` | `static func expectedFailureRecords( ⏎ recordings: [SharePayload.TourRecording], ⏎ photos: [TourPhoto] ⏎ ) -> [ShareService.FailedMediaItem] { ⏎ let audioItems = recordings.indices.map { index in ⏎ failedMediaItem(for: (kind: .audio, n: index + 1), audioRecordings: recordings, tourPhotos: photos) ⏎ } ⏎ let photoItems = photos.indices.map { index in ⏎ failedMediaItem(for: (kind: .photos, n: index + 1), audioRecordings: recordings, tourPhotos: photos) ⏎ } ⏎ return audioItems + photoItems ⏎ }` | expectedFailureRecords restarts the 1-based n counter independently for audio and photos — a walk with 2 recordings and 1 photo produces n=[1,2] for audio and n=[1] for photos, not a continuous n=[1,2,3]. | Test-pinned via testExpectedFailureRecordsMatchIdentityMapping (audio n=1,2; photos n=1, not n=3) — an Android port using a single monotonic counter across both kinds would silently corrupt the (kind,n) upload-slot identity for every photo whenever any audio recordings exist. |
| **EDG-119** ✅ `ORCH:406-413@3f9f9e8` | `func interactivePhotoExportList() -> [PhotoCandidate] { ⏎ guard hasPinnedPhotos else { return [] } ⏎ let window = interactiveKeptWindow() ⏎ return Array( ⏎ pinnedPhotos ⏎ .filter { window?.contains(Int($0.capturedAt.timeIntervalSince1970)) ?? true } ⏎ .prefix(20) ⏎ ) ⏎ }` | The hi-res photo export list is capped at 20 photos, applied AFTER filtering to the trimmed route's kept time window, not before. | If Android capped to 20 BEFORE applying the trim-window filter, a trimmed-out doorstep photo could occupy one of the 20 slots, silently squeezing out a legitimate in-window photo that should have made the cut. |
| **EDG-135** ✅ `VM:260@3f9f9e8` | `if s != nil && e != nil && s == e { return (s, nil) }` | When start and end place names resolve to the identical string (a loop walk), the end name is suppressed to nil rather than displaying a redundant 'X to X'. | An Android port that always sends both place names verbatim would let the shared page display something like 'Boulder to Boulder' for a common loop-walk pattern, instead of the deliberately deduplicated single place name. |
| EDG-148 `VM:490-497@3f9f9e8` | `switch marker { ⏎ case .winterSolstice: return "winter-solstice" ⏎ case .summerSolstice: return "summer-solstice" ⏎ case .springEquinox:  return "spring-equinox" ⏎ case .autumnEquinox:  return "autumn-equinox" ⏎ case .imbolc, .beltane, .lughnasadh, .samhain: return nil ⏎ }` | turningDayCode only forwards FOUR of eight possible turning-day marker cases to the wire (the four cardinal solstices/equinoxes); the four cross-quarter days (imbolc, beltane, lughnasadh, samhain) are explicitly mapped to nil and never sent. | An Android port that naively maps ALL eight TurningDayService marker cases to kebab-case strings (a very natural completionist instinct) would send turning_day values the shared-page frontend was never built to render, for the four cross-quarter days. |

### 5.3 encoding-subtlety (17)

| citation | quote | invariant | why-could-Android-miss |
|---|---|---|---|
| **EDG-29** ✅ `SP:3-131@3f9f9e8` | `struct SharePayload: Encodable {` | SharePayload relies on Swift Codable's auto-synthesized encodeIfPresent for every Optional property, which OMITS the JSON key entirely when nil rather than emitting null. | This is the single biggest cross-platform risk in this slice: most Kotlin JSON serializers (kotlinx.serialization, Gson, Moshi) include nulls by default. If Android doesn't explicitly configure encodeDefaults=false / @JsonInclude(NON_NULL) equivalents, every optional field (tour, pauses, journal, photos, steps, elevation, and critically `transcription`) will be sent as an explicit `null` key instead of being absent — breaking the test-pinned invariant that the literal string 'transcription' must never appear in the wire payload. |
| **EDG-30** ✅ `SP:120-130@3f9f9e8` | `enum CodingKeys: String, CodingKey { ⏎ case stats, route, journal, units, waypoints, mark, photos, tour, pauses ⏎ case activityIntervals = "activity_intervals" ⏎ case expiryDays = "expiry_days" ⏎ case startDate = "start_date" ⏎ case tzIdentifier = "tz_identifier" ⏎ case toggledStats = "toggled_stats" ⏎ case placeStart = "place_start" ⏎ case placeEnd = "place_end" ⏎ case turningDay = "turning_day" ⏎ }` | CodingKeys map most top-level fields to snake_case wire names, but stats/route/journal/units/waypoints/mark/photos/tour/pauses are sent verbatim (already single-word). | Any Android serializer field name that doesn't exactly match this mixed verbatim/snake_case scheme will 400 against the worker or silently drop data it doesn't recognize. |
| **EDG-31** ✅ `SP:32-42,57-61,93-96,109-114,83-86@3f9f9e8` | `enum CodingKeys: String, CodingKey { ⏎ case distance ⏎ case activeDuration = "active_duration" ⏎ case elevationAscent = "elevation_ascent" ⏎ case elevationDescent = "elevation_descent" ⏎ case steps ⏎ case meditateDuration = "meditate_duration" ⏎ case talkDuration = "talk_duration" ⏎ case weatherCondition = "weather_condition" ⏎ case weatherTemperature = "weather_temperature" ⏎ }` | Nested payload structs each carry their own CodingKeys with snake_case wire names distinct from the Swift property names. | Five separate nested CodingKeys enums (Stats, ActivityIntervalPayload, Tour, TourRecording, Pause) each need exact replication; RoutePoint/Waypoint/Photo deliberately have NO CodingKeys override (single-word fields sent as-is) — mixing these two patterns up in Android's serializer config is an easy transcription error. |
| EDG-32 `SP:49@3f9f9e8` | `let ts: Int` | All timestamp fields across the payload are Int seconds-since-epoch, truncated from Double, never milliseconds or Doubles. | If Android's Kotlin data classes use Long milliseconds (a common Android convention) without converting to truncated-integer seconds at serialization time, every timestamp sent to the worker would be 1000x too large. |
| **EDG-55** ✅ `SS:151@3f9f9e8` | `request.setValue("\(contentLength)", forHTTPHeaderField: "Content-Length")` | Content-Length is manually set as a stringified header rather than left to the HTTP client to compute from the body. | OkHttp on Android typically derives Content-Length automatically from the RequestBody; if Android's implementation ever streams a body whose declared length doesn't match actual bytes sent, the worker could reject the PUT — the value must be exact, not estimated. |
| **EDG-70** ✅ `TB:91@3f9f9e8` | `bytes / 1_048_576` | The byte-count shown in the cap-exceeded error message uses truncating integer division, while other MB displays elsewhere in the same feature use floating-point division with one decimal place. | This truncating form differs from InteractiveShareSection.swift's sizeLabel (Double(bytes)/1_048_576 with %.1f) and WalkShareViewModel's tourTotalsLabel (same float form) — three call sites, two different rounding behaviors for the same MB conversion; Android must replicate each site's specific behavior, not unify them. |
| **EDG-72** ✅ `TB:109@3f9f9e8` | `wpm: c.wpm,` | wpm (words-per-minute) DOES travel to the server even though transcription text never does — an asymmetry in what recording metadata ships. | An Android implementer pattern-matching 'don't send private recording data' might also strip wpm by mistake, or conversely might assume wpm's presence implies transcription is also safe to send — neither assumption is correct per the actual iOS behavior. |
| **EDG-83** ✅ `TPE:96-101@3f9f9e8` | `meta: SharePayload.Photo( ⏎ lat: candidate.capturedLat, ⏎ lon: candidate.capturedLng, ⏎ ts: Int(candidate.capturedAt.timeIntervalSince1970), ⏎ data: nil ⏎ ),` | Tour/interactive photo metadata always sets data to nil — the actual JPEG bytes travel out-of-band via a separate PUT, never embedded as base64 in the JSON. | Contrasts directly with the classic/non-interactive path (WalkShareViewModel.loadSharePhoto) which DOES embed base64 JPEG bytes in this same field — an Android port must route interactive photos through the PUT-upload path exclusively and never accidentally populate the data field for them. |
| **EDG-88** ✅ `ISS:132-134@3f9f9e8` | `private var kindLabel: String { ⏎ candidate.effectiveKind == .spoken ? "voice" : "ambience" ⏎ }` | The display label for ambient recordings is the word 'ambience', which differs from the wire/rawValue string 'ambient' used in the JSON payload and the enum case name. | An Android implementer reusing the enum's name/toString() for display text (a very natural shortcut) would show 'AMBIENT' or 'ambient' in the UI instead of the correct 'ambience' — display strings and wire strings are four DIFFERENT words across the two states, not a simple case-transform of each other. |
| EDG-107 `WSV:273@3f9f9e8` | `Text("\u{201C}A few words about this walk...")` | The journal placeholder text is prefixed with a literal Unicode left double quotation mark escape, not a plain ASCII quote character. | An Android string resource using a plain straight quote (") instead of the curly U+201C would be a subtle typographic mismatch from the iOS placeholder copy. |
| **EDG-121** ✅ `VM:52-53@3f9f9e8` | `let mb = Double(bytes) / 1_048_576 ⏎ parts.append("\(count) recording\(count == 1 ? "" : "s") · \(String(format: "%.1f", mb)) MB · \(Int(seconds / 60)) min")` | tourTotalsLabel's MB conversion uses floating point division with one decimal place, differing from TourBuilder's integer-division MB display for the cap-exceeded error string. | Three call sites across this slice convert bytes to MB with two different rounding behaviors (integer truncation in TourBuilder's error string vs float rounding here and in InteractiveShareSection's sizeLabel) — Android must replicate each site's specific behavior rather than assuming one shared helper. |
| **EDG-122** ✅ `VM:53,58@3f9f9e8` | `return parts.isEmpty ? "no recordings included" : parts.joined(separator: " · ")` | The tour totals label uses a literal middle-dot character as its part separator, exactly matching the separator used in the per-recording row text elsewhere. | Test-pinned exact string 'no recordings included' plus this specific Unicode separator (U+00B7); an Android string resource using a plain hyphen or pipe instead of the middle-dot would visibly differ from iOS. |
| **EDG-124** ✅ `VM:93-98@3f9f9e8` | `var kanji: String { ⏎ switch self { ⏎ case .moon: return "\u{6708}" ⏎ case .season: return "\u{5B63}" ⏎ case .cycle: return "\u{5DE1}" ⏎ } ⏎ }` | Expiry option kanji glyphs are literal Unicode escape sequences, one per case: U+6708 (月), U+5B63 (季), U+5DE1 (巡). | Each glyph must map to the EXACT same expiry option in Android; a transposition (e.g. swapping season/cycle glyphs) would be a silent, hard-to-notice localization bug since all three are single unfamiliar CJK characters to most reviewers. |
| EDG-128 `VM:182-187@3f9f9e8` | `var formattedActivityBreakdown: String? { ⏎ let parts = [ ⏎ walk.meditateDuration > 0 ? "\(Int(walk.meditateDuration / 60))m meditation" : nil, ⏎ walk.talkDuration > 0 ? "\(Int(walk.talkDuration / 60))m reflection" : nil ⏎ ].compactMap { $0 } ⏎ return parts.isEmpty ? nil : parts.joined(separator: ", ") ⏎ }` | The activity-breakdown string joins meditation/talk parts with a comma-space separator, DIFFERENT from the middle-dot separator used in tourTotalsLabel and the recording row text elsewhere in the same feature. | Two different join separators exist side-by-side in this one view model for superficially similar 'list of parts' strings; Android must not accidentally unify them to a single separator style. |
| EDG-129 `VM:190-193@3f9f9e8` | `var formattedSteps: String? { ⏎ guard let steps = walk.steps, steps > 0 else { return nil } ⏎ return "\(steps.formatted())" ⏎ }` | Step count formatting uses Swift's locale-aware Int.formatted(), which applies the device's CURRENT locale grouping/digits — a different convention than this project's own established Android practice of pinning Locale.US for numeric display. | iOS's steps display genuinely varies by locale (thousands separators, digit shapes); pinning Android's equivalent to Locale.US per the project's established convention is a deliberate cross-platform behavior difference to be aware of, not an oversight — worth an explicit product decision rather than a silent default. |
| **EDG-141** ✅ `VM:308@3f9f9e8` | `let base64 = jpegData.base64EncodedString()` | Classic photo bytes are base64-encoded and embedded directly in the JSON Photo.data field, incurring roughly 33% size overhead versus the interactive path's out-of-band PUT upload. | Only the classic path pays this encoding overhead; an Android port that accidentally routes classic photos through the interactive PUT-upload mechanism (or vice versa) changes both the payload's JSON shape (data: nil vs data: <base64>) and the network cost profile. |
| **EDG-143** ✅ `VM:370-377@3f9f9e8` | `let markValue: String? = { ⏎ guard let faviconStr = walk.favicon, let fav = WalkFavicon(rawValue: faviconStr) else { return nil } ⏎ switch fav { ⏎ case .flame: return "transformative" ⏎ case .leaf:  return "peaceful" ⏎ case .star:  return "extraordinary" ⏎ } ⏎ }()` | The favicon-to-mark mapping uses hand-picked wire strings ('transformative', 'peaceful', 'extraordinary') that don't match the underlying WalkFavicon enum's own case names or rawValues. | A port that derives this mapping from memory or from the enum's own naming instead of copying it verbatim risks sending the wrong mark string, which likely drives which icon/label the shared page displays. |

### 5.4 force-unwrap (1)

| citation | quote | invariant | why-could-Android-miss |
|---|---|---|---|
| **EDG-147** ✅ `VM:478-479@3f9f9e8` | `let keptWindow: ClosedRange<Int>? = (didTrim && trimmed.count >= 2) ⏎ ? trimmed.first!.ts...trimmed.last!.ts ⏎ : nil` | The kept-window's ClosedRange is built with force-unwrapped .first!/.last! on the trimmed array, safe only because the same ternary's left-hand condition already guarantees trimmed.count >= 2. | The force-unwraps are only safe because trimmed.count >= 2 is checked in the SAME ternary condition immediately before them; if a refactor separates the count check from the unwraps (e.g. extracts the unwrap into a helper called from multiple places), one of those call sites could crash on an empty or single-element array. Also: the resulting ClosedRange is INCLUSIVE on both ends, test-pinned by testInteractiveKeptWindowIncludesWaypointsAtExactBoundary — Android's equivalent IntRange (via `..`) must also be closed/inclusive, not exclusive. |

### 5.5 identity-verification (5)

| citation | quote | invariant | why-could-Android-miss |
|---|---|---|---|
| EDG-27 `RT:33-38@3f9f9e8` | `let dLat = (b.lat - a.lat) * .pi / 180 ⏎ let dLon = (b.lon - a.lon) * .pi / 180 ⏎ let la = a.lat * .pi / 180 ⏎ let lb = b.lat * .pi / 180 ⏎ let h = sin(dLat / 2) * sin(dLat / 2) + cos(la) * cos(lb) * sin(dLon / 2) * sin(dLon / 2) ⏎ return 2 * r * asin(sqrt(h))` | The exact haversine formula form (half-angle sin, cos(lat) product, asin of sqrt) must match bit-for-bit in behavior to keep trim boundaries identical across platforms. | A mathematically-equivalent but numerically different formula (e.g. atan2 form) could produce microscopically different distances that shift which point the start/end pointers land on near a trim boundary. |
| **EDG-49** ✅ `SS:285-293@3f9f9e8` | `/// A failed upload's slot (`kind`/`n`, the PUT index the worker is still ⏎ /// missing) plus the STABLE identity of the file it was meant to carry. ⏎ /// `n` alone isn't safe to retry against later: the local candidate list ⏎ /// an index was drawn from can shift (an export drop, an unpin) between ⏎ /// the original share and a retry, so the caller resolving this cache ⏎ /// must verify identity (recording `startTs`, or photo `localIdentifier` ⏎ /// + captured `ts`) before uploading anything under `n` again` | FailedMediaItem's doc comment explains exactly why n alone is unsafe for retries — identity must be verified by startTs (audio) or localIdentifier+ts (photos) before re-uploading under a cached slot number. | An Android port that trusts the cached n as a direct array index into the CURRENT candidate list (instead of re-resolving identity) risks uploading the wrong file's bytes under a stale slot number after a recording is deleted or a photo unpinned between the original share and a retry. |
| **EDG-113** ✅ `ORCH:130@3f9f9e8` | `// Pre-populate so a kill mid-upload restores a repairable .partial instead of a lying .success; PUTs are idempotent, over-repair is harmless.` | The pre-populate-before-upload strategy for the failed-media cache is explicitly justified by an assumption that PUTs are idempotent, making over-repair harmless. | This entire kill-safe repair design depends on the worker's PUT endpoints being safely re-invokable with the same bytes; if Android's equivalent talked to a DIFFERENT (non-idempotent) upload endpoint, the same pre-populate-then-prune strategy could cause duplicate-write side effects instead of being harmless. |
| **EDG-117** ✅ `ORCH:334-340@3f9f9e8` | `// Identity search, not an index-locked lookup: startTs ⏎ // collisions are structurally impossible (recordings can't ⏎ // overlap within one truncated second), so a match on ⏎ // startTs alone is safe wherever it now sits` | Audio-recording identity matching for retries relies on the structural assumption that two recordings can never share the same truncated-to-the-second start timestamp. | If Android's recording pipeline could ever produce two voice recordings starting within the same truncated second (e.g. an unusually fast stop/restart), this single-field identity match would silently resolve to the wrong recording — Android must confirm its own recording start-time granularity honors the same non-overlap guarantee before reusing this identity strategy. |
| **EDG-118** ✅ `ORCH:348-350@3f9f9e8` | `guard let match = currentPhotos.first(where: { ⏎ $0.sourceLocalIdentifier == item.photoLocalID && $0.meta.ts == item.photoTs ⏎ }) else {` | Photo retry-identity matching requires BOTH localIdentifier AND captured timestamp to agree, an asymmetric (compound-key) strategy compared to audio's single-field startTs match. | A port that 'simplifies' photo matching to a single-field lookup (mirroring the audio path for consistency) would be under-verifying identity — the compound key is deliberate, not an oversight, and dropping either half of it changes the false-positive-match rate for photo retries. |

### 5.6 idiomatic-trick (23)

| citation | quote | invariant | why-could-Android-miss |
|---|---|---|---|
| EDG-6 `RD:24@3f9f9e8` | `result.append(points[Int((Double(i) * step).rounded())])` | Index selection uses Swift's default rounding (round half away from zero), not truncation or banker's rounding. | Kotlin's Math.round or kotlin.math.roundToInt use different tie-breaking than Swift's .rounded(); at exact .5 boundaries the selected sample index could differ by one, changing which GPS point is kept. |
| EDG-10 `RD:51-53@3f9f9e8` | `let left = ramerDouglasPeucker(Array(points[...maxIndex]), epsilon: epsilon) ⏎ let right = ramerDouglasPeucker(Array(points[maxIndex...]), epsilon: epsilon) ⏎ return Array(left.dropLast()) + right` | The recursive split uses inclusive Swift ranges that both include maxIndex, then drops the duplicate at the seam when concatenating. | If Android's port forgets to drop the duplicate seam element, every RDP split doubles up one point, inflating the output count and shifting timestamps. |
| EDG-12 `RD:74-76@3f9f9e8` | `let t = max(0, min(1, ⏎ ((point.lon - lineStart.lon) * dx + (point.lat - lineStart.lat) * dy) / lengthSq ⏎ ))` | The projection parameter t is clamped into [0,1] via nested max/min, the classic point-to-segment projection clamp. | Without the clamp, projections outside the segment would extrapolate past the endpoints, producing wrong deviation distances. |
| EDG-13 `RD:64-66@3f9f9e8` | `let dx = lineEnd.lon - lineStart.lon ⏎ let dy = lineEnd.lat - lineStart.lat ⏎ let lengthSq = dx * dx + dy * dy` | perpendicularDistance operates in raw latitude/longitude degree space (dx=lon diff, dy=lat diff), NOT haversine meters — unlike RouteTrimmer's haversineMeters in the same feature area. | If Android 'improves' this to a proper meters-based haversine RDP, the epsilon threshold (tuned in degree-space) behaves completely differently and point selection will diverge from iOS at every latitude, especially since 1° longitude covers fewer real meters at high latitudes than at the equator. |
| **EDG-24** ✅ `RT:27-29@3f9f9e8` | `static func canTrim(_ route: [SharePayload.RoutePoint], meters: Double) -> Bool { ⏎ trim(route, meters: meters).count < route.count ⏎ }` | canTrim is defined by literally re-running trim() and comparing counts, not an independent heuristic. | If Android implements canTrim as a separate 'is the route long enough' estimate instead of delegating to trim itself, the two can disagree exactly on clustered-endpoint routes (see testCanTrimAlwaysAgreesWithTrim), showing 'trimmable' UI copy for a walk that actually ships untrimmed. |
| **EDG-28** ✅ `SP:19,117-118@3f9f9e8` | `var turningDay: String? = nil` | SharePayload's tour/pauses/turningDay fields are declared as mutable vars defaulting to nil, set post-construction by the view model rather than passed into the initializer. | An Android data class using immutable val fields would need these supplied at construction time or via a copy() call instead of the two-phase build-then-mutate pattern WalkShareViewModel relies on. |
| **EDG-38** ✅ `SS:121@3f9f9e8` | `let expiry = Calendar.current.date(byAdding: .day, value: expiryDays, to: now) ?? now` | Expiry-date computation falls back to 'now' if Calendar arithmetic somehow fails. | An Android port that lets a null/failed date computation propagate instead of defaulting could crash the caching path instead of degrading gracefully to an already-expired cache entry. |
| **EDG-40** ✅ `SS:147@3f9f9e8` | `let url = URL(string: "\(baseURL)/api/share/\(shareID)/\(kind.rawValue)/\(n)")!` | Media upload URLs are built via string interpolation combining shareID, kind rawValue, and a numeric slot. | This URL is force-unwrapped — if shareID or kind.rawValue ever contained URL-illegal characters, this would crash; Android's URL builder needs the same path shape with equivalent unwrap-or-fail semantics, and (kind, n) TOGETHER form the unique upload slot, not n alone. |
| **EDG-61** ✅ `TB:35@3f9f9e8` | `guard let text = transcription?.trimmingCharacters(in: .whitespacesAndNewlines) else { ⏎ return .spoken ⏎ }` | A nil transcription defaults to .spoken, not .ambient — the opposite of what an empty-string transcription produces. | Test-pinned via testClassify_noTranscriptionIsSpoken; a naive Android null-check that treats 'no transcription' the same as 'empty transcription' (both -> ambient) would misclassify recordings still awaiting transcription as ambience. |
| **EDG-62** ✅ `TB:38@3f9f9e8` | `let wordCount = text.split(whereSeparator: \.isWhitespace).count` | Word counting splits on any whitespace character (not just spaces) and Swift's split() drops empty subsequences by default, so an empty or whitespace-only string yields a word count of exactly 0. | A naive Kotlin port using text.trim().split(" ") on an empty string returns a list with one empty element (count=1, not 0), which would push wordCount above the ambient/spoken line incorrectly for empty transcripts — must use something like Regex("\\s+") with empty-filtering to match Swift's behavior, as pinned by testClassify_emptyTranscriptionIsAmbient and testClassify_whitespaceOnlyTranscriptionIsAmbient. |
| **EDG-64** ✅ `TB:44@3f9f9e8` | `let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]` | The documents directory URL is accessed via unchecked array subscript [0], relying on the platform contract that this array is always non-empty. | This is a force-unwrap-adjacent risk: on iOS this array is documented to always contain exactly one URL, but a literal Android port of 'grab element 0 of a directory list' without the same platform guarantee could crash if ever ported mechanically instead of using Android's own Context.filesDir equivalent directly. |
| **EDG-67** ✅ `TB:55@3f9f9e8` | `let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? Int` | File size lookup chains two optional-producing operations (try? then as?) so any file-read error OR wrong value type collapses silently to nil. | Android's equivalent File.length() doesn't throw the same way, so the exact 'nil = treat as missing/removed' semantics need explicit try/catch or exists() checks to replicate the same silent-degradation behavior. |
| **EDG-81** ✅ `TPE:83-90,116-123@3f9f9e8` | `let shouldResume = state.withLock { box -> Bool in ⏎ guard !box.resumed else { return false } ⏎ box.resumed = true ⏎ box.cancelItem?.cancel() ⏎ box.backstopItem?.cancel() ⏎ return true ⏎ } ⏎ guard shouldResume else { return }` | A lock-guarded one-shot 'resumed' flag ensures the checked continuation is resumed exactly once even though two independent async paths (the PhotoKit callback and the backstop timer) both race to resume it. | Swift's withCheckedContinuation crashes/traps on double-resume; Kotlin's suspendCancellableCoroutine throws IllegalStateException on the same misuse, so this exact invariant DOES carry over — Android's equivalent bridging code must use the same compare-and-set-then-cancel-the-other-path pattern, not two independent unguarded resume calls. |
| **EDG-82** ✅ `TPE:91-94@3f9f9e8` | `guard let image, let data = jpegDataUnder(cap: maxBytes, image: image) else { ⏎ continuation.resume(returning: nil) ⏎ return ⏎ }` | Both failure paths (nil image, or an image that doesn't compress under cap) collapse to the same silent-drop outcome with no distinguishing information kept. | Android's equivalent can't distinguish 'PhotoKit gave no image' from 'image was too complex to compress under 2MB' at the call site either — both must feed into the same photosDropped counting upstream, not a richer error type that changes the dropped-photo count semantics. |
| **EDG-87** ✅ `ISS:119-122@3f9f9e8` | `private var durationLabel: String { ⏎ let seconds = Int(candidate.duration) ⏎ return String(format: "%d:%02d", seconds / 60, seconds % 60) ⏎ }` | Duration is formatted as mm:ss with zero-padded seconds via a single format string. | Android must use Locale.US-pinned String.format (per the project's own established convention) with the same %02d zero-padding, or seconds under 10 would render as '3:5' instead of '3:05' — worse, default-locale formatting could emit non-ASCII digits. |
| **EDG-93** ✅ `SSS:41@3f9f9e8` | `Text("\(failedCount) file\(failedCount == 1 ? "" : "s") didn't make it — they'll show as unavailable on the page.")` | Failed-file and dropped-photo counts use a ternary to avoid an incorrect trailing 's' on singular counts. | Android string-plurals resources must reproduce this exact singular/plural boundary and the exact surrounding wording, not just the count substitution. |
| **EDG-97** ✅ `WSV:26-49@3f9f9e8` | `private var isShareInFlight: Bool { ⏎ switch viewModel.shareState { ⏎ case .preparingPhotos, .photosDropped, .uploading, .uploadingMedia: return true ⏎ default: return false ⏎ } ⏎ }` | isShareInFlight covers FOUR ShareState cases while the structurally similar isDismissLocked covers only TWO of those same four — a strict subset, not an equivalent gate, with both distinctions explicitly documented. | This is one of the easiest invariants to accidentally collapse into a single boolean during a port: form-editing is locked across all four in-flight-ish states, but toolbar Cancel / interactive dismiss is only locked for the two states where something already exists server-side (.uploading, .uploadingMedia) — merging these into one flag would either let users edit toggles during a live upload, or block Cancel during a harmless local photo-export phase. |
| **EDG-109** ✅ `ORCH:9-15,219-225@3f9f9e8` | `func beginShare() { ⏎ guard shareTask == nil else { return } ⏎ shareTask = Task { [weak self] in ⏎ await self?.share() ⏎ self?.shareTask = nil ⏎ } ⏎ }` | beginShare uses the nilness of a stored Task as a reentrancy mutex, guarding against double-taps. | The same guard-then-clear-on-completion pattern is used for both beginShare and beginRetry; an Android StateFlow/Job-based port must replicate the same 'guard non-null Job, clear to null on completion' idiom at both call sites, not just one. |
| **EDG-132** ✅ `VM:218@3f9f9e8` | `if tourCandidates.isEmpty { ⏎ tourCandidates = TourBuilder.candidates(for: walk) ⏎ }` | tourCandidates is populated lazily exactly once (guarded by isEmpty), meaning a recording deleted while the share sheet is open would not be reflected without re-opening the sheet. | An Android port that refreshes candidates on every prepareInteractive() call (a seemingly more 'correct' behavior) would change behavior from iOS's lazy-once snapshot, potentially causing candidate ids/positions to shift mid-session in ways the rest of the code doesn't expect. |
| **EDG-133** ✅ `VM:236-241@3f9f9e8` | `func flipKind(candidateID: Int) { ⏎ guard let i = tourCandidates.firstIndex(where: { $0.id == candidateID }) else { return } ⏎ let current = tourCandidates[i].effectiveKind ⏎ let flipped: TourRecordingKind = current == .spoken ? .ambient : .spoken ⏎ tourCandidates[i].kindOverride = flipped == tourCandidates[i].autoKind ? nil : flipped ⏎ }` | flipKind's second ternary resets kindOverride back to nil (not to an explicit redundant value) whenever the flipped kind happens to equal the original auto-detected kind — a self-canceling toggle. | Flipping a recording's kind twice must return it to EXACTLY the original nil-override state, not an explicitly-set-but-matching override — this matters if any future feature (e.g. an 'edited by you' indicator) keys off whether kindOverride is nil vs non-nil, which an Android port that always writes an explicit value would silently break. |
| **EDG-134** ✅ `VM:256-259@3f9f9e8` | `async let startName = geocodeSingle(geocoder: CLGeocoder(), location: startLoc) ⏎ async let endName = geocodeSingle(geocoder: CLGeocoder(), location: endLoc) ⏎ ⏎ let (s, e) = await (startName, endName)` | geocodeEndpoints runs the start and end reverse-geocode lookups CONCURRENTLY via Swift's async-let parallel binding, awaited together as a tuple. | A naive sequential Android port (await one geocode call, then the other) would roughly double the geocoding latency users experience before a share POST can start, since the two lookups have no dependency on each other. |
| **EDG-137** ✅ `VM:293@3f9f9e8` | `options.isNetworkAccessAllowed = false` | The classic photo-loading path explicitly disallows network access (isNetworkAccessAllowed = false), the OPPOSITE of TourPhotoExporter's interactive path which explicitly allows it — an asymmetric iCloud-fetch policy between the two photo pipelines. | If Android's classic-share photo path reused the interactive path's 'wait up to 22s for iCloud' logic, users sharing a classic (non-interactive) page could face unexpected multi-second stalls on a codepath that's supposed to be near-instant and local-only; conversely, using the classic path's no-network policy for interactive exports would silently drop iCloud-only photos that should have been fetched. |
| **EDG-138** ✅ `VM:294,299-316@3f9f9e8` | `options.isSynchronous = true ⏎ ⏎ let targetSize = CGSize(width: 600, height: 600) ⏎ ⏎ var result: SharePayload.Photo? ⏎ PHImageManager.default().requestImage( ⏎ for: asset, ⏎ targetSize: targetSize, ⏎ contentMode: .aspectFill, ⏎ options: options ⏎ ) { image, _ in ⏎ guard let image = image, ⏎ let jpegData = image.jpegData(compressionQuality: 0.5) else { return } ⏎ let base64 = jpegData.base64EncodedString() ⏎ result = SharePayload.Photo( ⏎ lat: lat, ⏎ lon: lon, ⏎ ts: Int(capturedAt.timeIntervalSince1970), ⏎ data: base64 ⏎ ) ⏎ } ⏎ return result` | The classic photo request is explicitly synchronous (isSynchronous = true), and the surrounding code relies on Apple's documented guarantee that the completion closure fires and mutates an outer var BEFORE requestImage returns control to the caller. | This pattern is only correct because PhotoKit guarantees synchronous callback completion before the call returns; Android's ContentResolver/BitmapFactory equivalent is inherently blocking too so the pattern maps cleanly, but a port using any async-by-default image-loading API (e.g. Coil, Glide) without an explicit synchronous/blocking call would return nil every time since the callback wouldn't have fired yet. |

### 5.7 magic-number (33)

| citation | quote | invariant | why-could-Android-miss |
|---|---|---|---|
| **EDG-1** ✅ `RD:7@3f9f9e8` | `maxPoints: Int = 200` | downsample() default cap of 200 route points is a bare literal default parameter, not a named constant. | Android could pick a different default cap, changing route fidelity/size for every share payload. |
| **EDG-14** ✅ `RD:90-91@3f9f9e8` | `var low = 0.0 ⏎ var high = 0.01` | The epsilon binary search bounds are hardcoded literals in degree-space, not meters. | 0.01 degrees is ~1.1km at the equator; picking a meters-based bound instead would change simplification aggressiveness entirely. |
| **EDG-15** ✅ `RD:93@3f9f9e8` | `for _ in 0..<20 {` | The epsilon binary search always runs exactly 20 iterations regardless of convergence. | Fewer iterations converge to a coarser epsilon, changing which points RDP keeps for large/complex routes; the fallback strideSample papers over gross failures but not subtle epsilon drift. |
| **EDG-20** ✅ `RT:15@3f9f9e8` | `guard total >= meters * 4 else { return route }` | The 4x floor is a literal multiplication against total route distance. | This is the enforced form of the doc comment's '4x' rule — dropping or changing the multiplier changes which walks get trimmed at all. |
| EDG-26 `RT:32@3f9f9e8` | `let r = 6_371_000.0` | Earth's radius for the haversine formula is a bare literal, not sourced from Constants. | A different radius constant (e.g. WGS-84 semi-major axis vs mean radius) shifts every distance calculation by a small but nonzero amount, moving trim boundaries. |
| **EDG-35** ✅ `SS:50@3f9f9e8` | `request.timeoutInterval = 30` | The share POST request uses a flat 30-second timeout. | A shorter Android OkHttp timeout could abort large-payload POSTs (108-minute walks with full route/tour data) that iOS tolerates. |
| **EDG-56** ✅ `TB:24@3f9f9e8` | `static let maxRecordings = 12` | maxRecordings caps the tour at exactly 12 recordings, verified against Constants.swift as not centrally defined. | Test-pinned inclusive boundary (12 passes, 13 fails) — any drift changes both validation and the exact wording of the 'leave some out' error string. |
| **EDG-57** ✅ `TB:25@3f9f9e8` | `static let maxFileBytes = 15 * 1024 * 1024` | maxFileBytes caps any single recording at 15MB (= 15728640 bytes). | A recording over this size is marked 'too large to carry' and silently excluded from the share by default; a different cap changes which real recordings get silently dropped. |
| **EDG-58** ✅ `TB:26@3f9f9e8` | `static let maxTotalBytes = 60 * 1024 * 1024` | maxTotalBytes caps the whole tour payload's carried audio at 60MB (= 62914560 bytes). | Test-pinned via a 70MB heavy case failing validation; a different cap changes when the Share button disables. |
| **EDG-59** ✅ `TB:27@3f9f9e8` | `static let maxTotalSeconds: Double = 6480  // 108 minutes — the eternal cairn's number` | maxTotalSeconds caps total included-recording duration at 6480 seconds (108 minutes), with a whimsical inline comment. | Test-pinned exactly at the boundary (6000s passes, 7000s fails); this is also the number referenced elsewhere in the codebase as the walk length driving the '2MB POST budget' rationale for never sending transcripts. |
| **EDG-69** ✅ `TB:91-92@3f9f9e8` | `if bytes > maxTotalBytes { return "Recordings total \(bytes / 1_048_576) MB — the page carries at most 60 MB." } ⏎ if seconds > maxTotalSeconds { return "Recordings total \(Int(seconds / 60)) minutes — the page carries at most \(Int(maxTotalSeconds / 60))." }` | The validationError byte-cap message hardcodes the literal text '60 MB' instead of deriving it from maxTotalBytes, while the adjacent seconds-cap message DOES derive its number from maxTotalSeconds — an inconsistency within the same function. | If maxTotalBytes is ever changed, the bytes-cap string silently goes stale on iOS while the seconds-cap string stays correct — Android must decide whether to replicate iOS's actual (inconsistent) current behavior verbatim or fix it, but should not assume both strings follow the same derivation pattern when copying the logic. |
| **EDG-74** ✅ `TPE:18@3f9f9e8` | `static let maxBytes = 2 * 1024 * 1024` | Interactive/tour photo export enforces a hard 2MB (2097152-byte) per-photo cap, distinct from (but numerically coincidental with) the '2MB POST budget' mentioned only in a comment in TourBuilder. | This is the ENFORCED constant (unlike TourBuilder's comment-only '2MB POST budget' reference to the whole payload) — Android must not conflate the two: this cap is per-photo file size for the compression ladder, a different budget than the overall POST size concern. |
| **EDG-75** ✅ `TPE:19,23-25@3f9f9e8` | `static let targetPixels: CGFloat = 1600` | Interactive photos target 1600px, explicitly documented as far larger than the classic page's 600px inline thumbnails. | Confusing these two photo pipelines (interactive full-bleed vs classic base64-embedded thumbnail) would either bloat the classic payload or under-serve the interactive page's full-bleed rendering. |
| **EDG-76** ✅ `TPE:26-33@3f9f9e8` | `for quality in [0.8, 0.65, 0.5, 0.35, 0.2] { ⏎ if let data = image.jpegData(compressionQuality: quality), data.count <= cap { ⏎ return data ⏎ } ⏎ } ⏎ return nil` | jpegDataUnder walks a fixed, ordered five-step JPEG quality ladder, taking the first quality that fits under the cap. | Android's Bitmap.compress-based equivalent must try these exact five quality values in this exact descending order, returning null if none fit, not just 'decreasing quality until it fits' with different step values — otherwise photo quality/size tradeoffs diverge from iOS for the same source image. |
| **EDG-84** ✅ `TPE:130-131@3f9f9e8` | `DispatchQueue.global().asyncAfter(deadline: .now() + perPhotoTimeout, execute: cancelItem) ⏎ DispatchQueue.global().asyncAfter(deadline: .now() + perPhotoTimeout + backstopGrace, execute: backstopItem)` | Both timeout deadlines are scheduled relative to 'now' using the sum of the two named constants. | The backstop must fire strictly after the primary cancel, never before or simultaneously; an Android timer implementation must preserve this ordering (22s backstop always later than 20s primary). |
| **EDG-85** ✅ `ISS:61-63@3f9f9e8` | `Text(viewModel.canTrimRoute ⏎ ? "Keeps the first and last 150 m off the shared map — including photos and waymarkers there." ⏎ : "This walk is too short to trim.")` | The 'trim start & end' toggle's help text hardcodes '150 m' as UI copy instead of interpolating WalkShareViewModel.trimMeters. | If trimMeters is ever changed, this copy silently goes stale on iOS; Android should decide deliberately whether to hardcode matching text or interpolate from the shared constant, not accidentally diverge from the CURRENT shipped copy. |
| **EDG-86** ✅ `ISS:72@3f9f9e8` | `.animation(.easeInOut(duration: 0.2), value: viewModel.interactiveEnabled)` | The interactive-section expand/collapse animation duration (0.2s) is a bare literal not found among Constants.UI.Motion's three named values (0.6, 1.2, 0.4). | A different animation duration on Android changes the felt responsiveness of the toggle disclosure, and confirms this feature area doesn't reuse the app's catalogued motion tokens. |
| **EDG-90** ✅ `ISS:178,196@3f9f9e8` | `.frame(minWidth: 44, minHeight: 44)` | Both the include-toggle and kind-chip buttons enforce a 44x44pt minimum tap target, Apple's HIG minimum. | Android's Material accessibility minimum is conventionally 48dp, not 44 — the literal number should NOT be copied verbatim, but the underlying invariant (an accessible minimum tap target exists on both interactive controls in this row) must be preserved with the platform-appropriate value. |
| **EDG-91** ✅ `ISS:171@3f9f9e8` | `.opacity(candidate.unavailableReason != nil ? 0.45 : (candidate.includeInShare ? 1 : 0.6))` | Row opacity uses a three-way nested ternary with three distinct magic values (0.45 unavailable, 1.0 included, 0.6 excluded-but-available), none of which match any Constants.UI.Opacity value. | Three distinct visual states must render at three distinct, exact opacity levels; collapsing any two of them (e.g. treating unavailable and excluded the same) loses a visual distinction users rely on to tell 'can't include' apart from 'chose not to include'. |
| **EDG-92** ✅ `ISS:203@3f9f9e8` | `.opacity(candidate.includeInShare ? 1 : 0.35)` | The kind chip applies its OWN separate opacity (0.35 when excluded) which compounds multiplicatively with the row's own 0.6, yielding an effective ~0.21, not 0.35, for an excluded-but-available recording's kind chip. | A Compose port that flattens this into a single computed alpha per element (a very natural refactor) could easily compute 0.35 instead of the true compounded ~0.21 for the kind chip specifically, making it visibly more opaque than iOS for that one sub-element in the excluded state. |
| **EDG-94** ✅ `SSS:58,132,185,200@3f9f9e8` | `.padding(.vertical, 12)` | Several button/prompt vertical paddings use 12pt, a value absent from Constants.UI.Padding's defined scale (4/8/16/24/64). | This off-catalog value recurs four times in this one file alone for text-button-style controls — Android should use a consistent equivalent spacing value across its ported button styles, not silently drift to the nearest catalogued 8 or 16dp value. |
| **EDG-95** ✅ `SSS:105@3f9f9e8` | `.padding(.vertical, 14)` | The progress-row background uses 14pt vertical padding, a third distinct off-catalog padding value. | Yet another bespoke spacing value (distinct from both 12 and the catalogued 16) specifically for the spinner-row chrome shared by uploading/preparingPhotos/uploadingMedia states. |
| EDG-105 `WSV:322@3f9f9e8` | `.foregroundColor(isSelected ? .parchment.opacity(0.12) : .fog.opacity(0.06))` | Expiry-button background kanji watermark opacities (0.12 selected, 0.06 unselected) are written as raw literals at the call site even though they numerically equal named Constants.UI.Opacity.light and .subtle respectively. | Even though matching named constants exist elsewhere in the codebase (Constants.UI.Opacity.light=0.12, .subtle=0.06), this call site doesn't reference them — an Android port that greps only for 'Constants' usage to find opacity values would miss this occurrence entirely since it's a bare literal, not a symbol reference. |
| **EDG-106** ✅ `WSV:266-269,284@3f9f9e8` | `.onChange(of: viewModel.journal) { _, newValue in ⏎ if newValue.count > 140 { ⏎ viewModel.journal = String(newValue.prefix(140)) ⏎ } ⏎ }` | The journal reflection field is capped at exactly 140 characters, both for input clamping and for the counter display. | Swift's String.count measures extended grapheme clusters (user-perceived characters), while Kotlin's String.length measures UTF-16 code units — for any journal entry containing emoji or combining characters, a naive Android port using .length would clamp at a DIFFERENT effective character count than iOS, and could even split a multi-codepoint grapheme cluster mid-character when truncating via prefix/substring. |
| **EDG-120** ✅ `VM:36@3f9f9e8` | `static let trimMeters = 150` | The route trim distance constant is 150 meters, referenced by RouteTrimmer calls and duplicated as hardcoded UI copy elsewhere. | This is the single source powering RouteTrimmer.trim/canTrim calls, but the UI copy in InteractiveShareSection hardcodes '150 m' as a separate string literal rather than interpolating this constant — both must be kept in sync manually. |
| **EDG-123** ✅ `VM:80-83@3f9f9e8` | `enum ExpiryOption: Int, CaseIterable { ⏎ case moon = 30 ⏎ case season = 90 ⏎ case cycle = 365` | Expiry options are encoded as raw day counts (30/90/365) via the enum's Int rawValue. | These values are sent directly as expiryDays in the payload and used for Calendar arithmetic — a mismatched day count silently changes when a shared walk actually expires server-side vs what the UI displays. |
| EDG-125 `VM:165-171@3f9f9e8` | `let h = Int(walk.activeDuration) / 3600 ⏎ let m = (Int(walk.activeDuration) % 3600) / 60 ⏎ if h > 0 { return "\(h)h \(m)m" } ⏎ return "\(m)m"` | formattedDuration hardcodes seconds-per-hour and seconds-per-minute as bare literals with a conditional format switch that omits the hour segment entirely when zero. | A walk under one hour must display as '23m', not '0h 23m' — Android's formatter needs the same conditional branch, not just the same divisor constants. |
| EDG-126 `VM:162@3f9f9e8` | `return String(format: "%.1f mi", walk.distance / 1609.344)` | The imperial-miles conversion uses the precise factor 1609.344, not a rounded approximation. | Using a less precise conversion factor (e.g. 1609.34 or 1609) would produce a different displayed mileage for long walks after rounding to one decimal. |
| EDG-127 `VM:179@3f9f9e8` | `return "\(Int(walk.ascend * 3.28084)) ft"` | The imperial-feet conversion uses the precise factor 3.28084. | Feet conversion is truncated to Int (not rounded) after multiplication — both the exact factor and the truncation-not-rounding must match to avoid off-by-one-foot display differences at conversion boundaries. |
| **EDG-139** ✅ `VM:297@3f9f9e8` | `let targetSize = CGSize(width: 600, height: 600)` | Classic (base64-embedded) photos target 600x600px, half the pixel dimension used by interactive photos (1600px). | Confirmed consistent with TourPhotoExporter's doc comment reference to '600px inline thumbnails' — the two photo pipelines must stay at their documented, deliberately different resolutions. |
| **EDG-140** ✅ `VM:307@3f9f9e8` | `let jpegData = image.jpegData(compressionQuality: 0.5) else { return }` | Classic photos use a single fixed JPEG quality of 0.5 with NO size-cap enforcement or compression ladder, unlike the interactive path's bounded 5-step ladder down to a hard 2MB cap. | Interactive/tour photos are guaranteed <= 2MB (or dropped entirely if impossible); classic/base64 photos have NO such guarantee and could theoretically exceed any size the interactive path would ever allow — an Android port must not assume both photo paths share the same size-safety properties. |
| EDG-181 `WDF:86@3f9f9e8` | `transcription: String? = "Test transcription"` | The shared test-fixture factory for voice recordings defaults transcription to a specific 2-word string, which classifies as .ambient by TourBuilder's 8-word threshold whenever a test doesn't override it. | An Android test-factory author choosing a different default transcription string (e.g. one that happens to cross the 8-word line) would silently change which branch of TourBuilder.classify any test exercises if it goes through this factory without explicitly overriding transcription — a latent trap for future test authors on either platform. |
| EDG-182 `WDF:21,24@3f9f9e8` | `activeDuration: Double = 1800, ⏎ pauseDuration: Double = 0, ⏎ dayIdentifier: String = "20240615", ⏎ talkDuration: Double = 0,` | The walk factory's default activeDuration (1800s) pairs with a default talkDuration of 0, meaning any test exercising the interactive talk-duration clamp must explicitly override talkDuration or the clamp collapses everything to zero, masking the actual behavior under test. | Confirmed by an explicit in-test comment elsewhere (WalkShareInteractiveTests.swift line 98-100) that a test must set talkDuration deliberately or the min(includedSum, walk.talkDuration) clamp masks the real target of the test — Android's own fixture defaults should be reviewed for the same trap before porting these tests mechanically. |

### 5.8 magic-string (4) — all cross-lens consensus

| citation | quote | invariant | why-could-Android-miss |
|---|---|---|---|
| **EDG-33** ✅ `SS:7@3f9f9e8` | `private static let baseURL = "https://walk.pilgrimapp.org"` | ShareService's base URL is a bare literal, not sourced from any shared constants file in this slice. | Already documented in pilgrim-android/CLAUDE.md as the shared backend, but any typo in the Android literal breaks every share silently at build time with no compiler error. |
| **EDG-34** ✅ `SS:8@3f9f9e8` | `private static let deviceTokenKey = "pilgrim.share.device-token"` | The UserDefaults key for the device token is a bare literal. | Not directly portable (Android uses its own DataStore key), but confirms the token is a per-install UUID persisted locally, not derived from any server-issued identity. |
| **EDG-39** ✅ `SS:99,131@3f9f9e8` | `UserDefaults.standard.set(dict, forKey: "share:\(walkID.uuidString)")` | Cached share records are keyed by a string-interpolated format combining a fixed prefix and the walk UUID (`share:<uuid>`). | Not cross-platform portable literally, but confirms the cache key scheme (prefix + uuid, no separator ambiguity) that Android's own DataStore/Room key scheme should mirror in spirit for per-walk share-state persistence. |
| **EDG-41** ✅ `SS:150@3f9f9e8` | `request.setValue(kind == .audio ? "audio/mp4" : "image/jpeg", forHTTPHeaderField: "Content-Type")` | Content-Type is a two-way ternary between exactly two MIME strings. | If Android's audio recordings are encoded differently (e.g. AAC in a different container), sending the wrong Content-Type could cause the worker to reject or mis-handle the file even though the bytes are valid. |

### 5.9 non-obvious-comment (34)

| citation | quote | invariant | why-could-Android-miss |
|---|---|---|---|
| **EDG-17** ✅ `RT:5-7@3f9f9e8` | `/// Shaves `meters` of walked distance off each end of the route so a ⏎ /// shared page never reveals a doorstep. Walks shorter than 4x the trim ⏎ /// distance share untrimmed — mid-walk geometry is all they have.` | RouteTrimmer's doc comment states the exact rule: routes shorter than 4x the trim distance ship untrimmed. | Missing the 4x floor would trim short walks down to nothing or a degenerate two-point route. |
| **EDG-25** ✅ `RT:25-26@3f9f9e8` | `/// Whether trim can actually apply to the route — the UI uses this to show ⏎ /// "too short to trim" instead of silently promising protection.` | canTrim's doc comment states its exact UI purpose: showing an honest 'too short to trim' instead of a false promise. | Divergence between canTrim and trim would let the UI promise doorstep protection it doesn't deliver. |
| **EDG-43** ✅ `SS:160-166@3f9f9e8` | `/// Sequential by contract: photos MUST land in index order (enrich ⏎ /// HEADs only the last one), and one-at-a-time keeps memory flat for ⏎ /// 15MB audio files. PHOTOS UPLOAD FIRST — they gate the keepsake ⏎ /// render window; audio degrades gracefully to "voice unavailable". ⏎ /// Each item gets one retry. Runs inside a background-task assertion ⏎ /// so pocketing the phone doesn't kill the remaining PUTs. Returns ⏎ /// the indices (1-based, per kind) that ultimately failed.` | uploadAllMedia's doc comment pins four load-bearing contracts: strictly sequential (not parallel) uploads, photos always before audio, exactly one retry per item, and 1-based indices scoped per media kind. | Parallelizing uploads (a natural Android/Kotlin coroutine temptation) would break the ordering contract the server-side 'enrich' step depends on for photos, and a global (not per-kind) index counter would corrupt the upload-slot mapping. |
| **EDG-45** ✅ `SS:190@3f9f9e8` | `// Bounded to what THIS loop still owes, not the grand total — jumping straight to `total` would let `completed` overshoot if the app foregrounds before the audio loop below and that one finishes normally.` | When background time runs out mid-photo-loop, the completed counter is bounded to what that loop still owes, explicitly not jumped to the grand total. | A naive 'just set completed = total' shortcut would report 100% progress while the audio loop hasn't even started, corrupting the progress UI if the app returns to foreground mid-upload. |
| **EDG-47** ✅ `SS:260-264@3f9f9e8` | `/// Injected rather than reading `UIApplication.shared` directly (mirrors ⏎ /// `WalkShareViewModel.isPhotosGranted`'s precedent): the OS background ⏎ /// state can't be driven from a unit test, so `backgroundTimeExhausted()` ⏎ /// needs a seam a test can force deterministically. Tests restore the ⏎ /// default in `tearDown`.` | The background-state provider is dependency-injected specifically because OS background state can't be driven deterministically from a unit test. | Android's equivalent must also expose an injectable seam (foreground-service/lifecycle state check) rather than hardcoding a direct system-state read, or Android's own tests can't deterministically simulate 'about to be killed.' |
| **EDG-50** ✅ `SS:302-305@3f9f9e8` | `/// Failed-media bookkeeping alongside the cached share, so a re-entry ⏎ /// can offer repair for the share's whole life (the worker accepts ⏎ /// PUTs until expiry). Stored as JSON — no shipped data exists in this ⏎ /// format yet, so it's free to change without a migration.` | The failed-media cache format is explicitly documented as free to change without a migration, since no shipped data existed in that format at time of writing. | Android's own persisted-format choice (Room row vs DataStore JSON blob) is unconstrained by any iOS-compat requirement here, but the underlying record's field set (kind, n, audioStartTs, photoLocalID, photoTs) must be preserved exactly for the retry-identity logic to work. |
| **EDG-51** ✅ `SS:322-332@3f9f9e8` | `/// `BackgroundAssertionState` is the one-shot ⏎ /// guard: the expiration handler (fired by the OS if we overstay our ⏎ /// background time) and the normal completion path both race to end ⏎ /// the same assertion, and the lock ensures only the first of them ⏎ /// actually calls endBackgroundTask — calling it twice is documented ⏎ /// Apple misuse.` | withBackgroundAssertion's one-shot state guard exists specifically because calling UIApplication.endBackgroundTask twice is documented Apple API misuse. | Not directly portable (no UIApplication background-task API on Android), but the ARCHITECTURAL invariant — a race between a normal-completion cleanup path and an OS-driven expiration/timeout cleanup path, guarded so exactly one wins — should be preserved for Android's own foreground-service stop/release logic to avoid a double-release crash. |
| **EDG-54** ✅ `SS:396-400@3f9f9e8` | `// A single item's own attempt-plus-retry cycle can burn up to ⏎ // ~60s of request timeouts on its own — re-check here, not ⏎ // just once per item before this call, so a slow first ⏎ // attempt can't blow through the remaining background grant ⏎ // before the retry even starts.` | The background-exhaustion check is deliberately re-evaluated a second time inside the retry loop (not just once per item before the whole call), because a single item's attempt+retry cycle can burn up to ~60s of timeouts on its own. | An Android port that checks background/lifecycle budget only once per item (not once per attempt) could start a retry PUT that has no realistic chance of completing before the process is killed. |
| **EDG-60** ✅ `TB:29-33@3f9f9e8` | `/// A deliberate recording is presumed to be a voice: only a transcription ⏎ /// that reads as non-speech (too few words) files the recording as ⏎ /// ambience. The walker can override either way. No words-per-minute ⏎ /// gate: contemplative talks — a thought, then a long silence — measure ⏎ /// ~25 wpm on real walks, and slow speech is still speech.` | classify()'s doc comment explains there is deliberately NO words-per-minute gate, because slow contemplative speech (~25 wpm) is still real speech. | An Android implementer who sees `wpm` being tracked on the model might reasonably add a WPM threshold gate 'for consistency' — this comment explicitly forbids that; only raw word count matters. |
| **EDG-66** ✅ `TB:51-54@3f9f9e8` | `// The worker validates truncated integers and rejects the WHOLE ⏎ // POST on end_ts <= start_ts — a sub-second blip recording must ⏎ // be excluded here, not shipped. ⏎ guard endTs > startTs else { return nil }` | A sub-second blip recording must be excluded at the candidate-building stage because the worker rejects the WHOLE POST if any recording has end_ts <= start_ts after truncation. | If Android ships a sub-second recording's truncated timestamps unfiltered, the ENTIRE share POST 400s, not just that one recording — this is a whole-request failure mode, not a partial one. |
| **EDG-71** ✅ `TB:105-108@3f9f9e8` | `// Transcripts never leave the device: the page renders none, and ⏎ // a 108-minute walk's transcripts would blow the 2MB POST budget. ⏎ // Deliberate — do not wire c.transcription through. ⏎ transcription: nil,` | Transcription is deliberately never wired into the tour payload — the comment explains both the privacy rationale and a size-budget rationale. | This is the single most safety-critical line in the whole slice from a privacy standpoint — an Android port that 'helpfully' passes the real transcription through (e.g. because the Kotlin data class field exists and looks unused) would leak private voice-transcript content to the server and, per the wire-format finding above, would also make the literal string 'transcription' appear in the JSON, breaking the test-pinned no-transcription-leaves-device invariant. |
| **EDG-77** ✅ `TPE:35-40@3f9f9e8` | `/// `progress` fires after every photo, off the main thread — `export` is a ⏎ /// nonisolated async function and reports from whatever executor happens to ⏎ /// be running when the current photo finishes, never the main actor. This ⏎ /// differs from the sibling `WalkPhotoMatcher.findCandidates`, whose ⏎ /// `completion` closure is always delivered on the main thread. Callers that ⏎ /// update UI from `progress` must hop to the MainActor themselves.` | export's progress callback fires off the main thread, on whatever executor happens to be running — an explicitly documented contrast with a sibling API that always delivers on the main thread. | Android's coroutine equivalent must document (and callers must respect) which dispatcher the progress callback fires on; assuming Main dispatcher when it's actually a background dispatcher would either crash on UI mutation or silently no-op depending on framework. |
| **EDG-78** ✅ `TPE:44-48@3f9f9e8` | `// A cancelled share() must stop within ~one photo, not run the ⏎ // whole remaining list — loadOne itself isn't cancellation-aware ⏎ // (its own timeout/backstop bound it independently), so this is ⏎ // the only place that can act on it. ⏎ if Task.isCancelled { break }` | Cancellation is checked once per photo in the loop, explicitly documented as the ONLY place that can act on cancellation since the per-photo load itself isn't cancellation-aware. | Android's coroutine cancellation is cooperative too, but if the equivalent per-photo suspend function doesn't itself check isActive/ensureActive(), a cancelled share() could still run one full 20+2 second photo load before the cancellation is observed — matching iOS's 'stop within ~one photo' bound requires the same single check-point design. |
| **EDG-98** ✅ `WSV:38-43@3f9f9e8` | `/// Only `.uploading` (the POST has landed a live page) and ⏎ /// `.uploadingMedia` (PUTs are streaming) have put anything server-side ⏎ /// that abandoning the sheet would leave stranded — `.preparingPhotos` ⏎ /// is a local, cancellable export and `.photosDropped` is a pre-POST ⏎ /// consent pause, so neither locks the toolbar Cancel or interactive ⏎ /// dismiss the way `isShareInFlight` locks the form.` | isDismissLocked's doc comment explains precisely why .preparingPhotos and .photosDropped do NOT lock dismissal: nothing exists server-side yet in either state. | Directly informs which Android nav-back/dismiss gesture states must be blockable vs freely cancellable. |
| **EDG-99** ✅ `WSV:110-115@3f9f9e8` | `// Reveal the podcast card after the ritual modal dismisses, not at ⏎ // the moment of share success. The previous 800ms-after-success ⏎ // trigger collided with the ritual's own reveal — the card animated ⏎ // invisibly behind the modal, and its haptic doubled up with the ⏎ // ritual's. Tying the reveal to `showPreview` going true → false ⏎ // gives the card a visible fade-in and separates the two haptics.` | A prior implementation triggered the podcast-card reveal 800ms after share success and this collided with the ritual modal's own reveal, causing an invisible animation and a doubled haptic — the fix ties the reveal to the ritual modal's dismiss transition instead. | An Android implementer who hasn't read this history could easily reintroduce the exact same bug by wiring the podcast-card reveal to shareState becoming .success directly instead of to the preview-modal's dismiss transition. |
| **EDG-100** ✅ `WSV:117-127@3f9f9e8` | `// Only reveal after a FRESH-share modal dismiss (ritualDidFire). ⏎ // Cache-hit re-entry via the walk summary's tappable URL also ⏎ // dismisses the modal via showPreview true → false, and without ⏎ // this gate the podcast card would spuriously appear on every ⏎ // re-view of a walk that was shared weeks ago. ⏎ guard wasShowing, !isShowing, ⏎ ritualDidFire,` | The podcast-card reveal is gated by a ritualDidFire latch specifically to prevent it from spuriously reappearing every time a user re-opens a walk that was shared weeks ago. | Without an equivalent one-shot latch, Android's podcast-card prompt would incorrectly resurface every time a user simply re-opens the preview of an old, already-shared walk. |
| **EDG-103** ✅ `WSV:345-349@3f9f9e8` | `// If the user taps to open during the 800ms ritual beat, cancel the ⏎ // pending reveal so its haptic + redundant showPreview assignment ⏎ // don't fire on an already-open modal. ⏎ revealTask?.cancel() ⏎ revealTask = nil` | Tapping to open the preview during the 800ms ritual beat must cancel the pending reveal task, or its haptic and redundant state assignment would fire on an already-open modal. | Without this cancellation, a fast user tap during the 800ms window on Android would trigger a second haptic pulse and a redundant state write against an already-visible modal. |
| EDG-104 `WSV:319-321@3f9f9e8` | `// CJK glyphs require system font — Cormorant Garamond has no kanji coverage ⏎ Text(option.kanji) ⏎ .font(.system(size: 40, weight: .ultraLight))` | CJK expiry-option glyphs deliberately use the system font because the app's custom typeface (Cormorant Garamond) has no kanji coverage; size 40, weight ultraLight. | If Android's ported custom font (Cormorant Garamond via Compose) also lacks CJK glyph coverage, the equivalent kanji text must fall back to a system/Noto font explicitly, or the glyphs will render as tofu boxes. |
| **EDG-108** ✅ `WSV:177-182@3f9f9e8` | `// Swipe-to-dismiss during .preparingPhotos never runs the ⏎ // toolbar Cancel button's action — without this, a sheet ⏎ // closed that way would keep exporting photos and POSTing in ⏎ // the background with no UI left to show it. ⏎ viewModel.cancelShare()` | onDisappear must call cancelShare() because swipe-to-dismiss during .preparingPhotos never runs the toolbar Cancel button's action. | Android's equivalent screen-dismiss/back-navigation lifecycle hook (onDispose, DisposableEffect, or Fragment onDestroyView) must also unconditionally trigger cancellation on every dismissal path, not just an explicit Cancel button tap, or a swipe-back/system-back gesture would leak a running export+POST. |
| **EDG-110** ✅ `ORCH:17-20@3f9f9e8` | `/// Safe to call any time: a no-op once nothing is running, and a no-op ⏎ /// in EFFECT once the POST has landed — `share()` only honors ⏎ /// cancellation at the one checkpoint before anything exists ⏎ /// server-side.` | cancelShare is documented as safe to call at any time — a true no-op once nothing runs, and a no-op IN EFFECT once the POST has landed. | An Android port that checks isActive/cancellation at MULTIPLE points after the POST (a natural-seeming defensive habit) would risk abandoning a live server-side share mid-media-upload, leaving a state the UI can't recover into (server has the share, but the client silently gave up). |
| **EDG-112** ✅ `ORCH:76-81@3f9f9e8` | `/// Claims `.uploading` ⏎ /// SYNCHRONOUSLY, before the `Task` is even spawned, so the prompt's ⏎ /// "Share without them" / "Don't share yet" buttons vanish immediately ⏎ /// instead of staying tappable through the geocode+POST that follows. ⏎ func continueShareWithoutDroppedPhotos() { ⏎ guard shareTask == nil else { return } ⏎ shareState = .uploading` | continueShareWithoutDroppedPhotos claims the .uploading state SYNCHRONOUSLY before the Task is even spawned, so the prompt's two buttons vanish within the same runloop turn. | An Android coroutine port that sets the equivalent StateFlow value INSIDE the launched coroutine (a common but subtly wrong pattern) leaves a frame where both prompt buttons are still tappable, opening a double-tap or conflicting-action window that iOS closes by ordering the state write first. |
| **EDG-114** ✅ `ORCH:133-136@3f9f9e8` | `// Prime the phase synchronously (no unstructured-Task hop to ⏎ // race) so the FIRST progress tick already finds shareState ⏎ // in .uploadingMedia — see applyMediaProgress. ⏎ shareState = .uploadingMedia(completed: 0, total: audioFiles.count + tourPhotos.count)` | shareState is primed synchronously to .uploadingMedia BEFORE the async upload call starts, specifically so the first progress tick isn't silently dropped by a state-guard elsewhere. | applyMediaProgress silently no-ops any progress tick that arrives while shareState isn't already .uploadingMedia (guard case .uploadingMedia = shareState else { return }); if Android primes the state AFTER launching the upload coroutine instead of before, a fast-completing first item's progress tick could be silently swallowed, leaving the progress UI stuck at 0/N until the next tick. |
| **EDG-130** ✅ `VM:203-212@3f9f9e8` | `// A share with un-landed media PUTs still has a live page — ⏎ // restore .partial so "Carry the missing files" survives ⏎ // leaving and returning here, not a quiet .success. ⏎ let failedCount = ShareService.failedMedia(for: uuid).count ⏎ shareState = failedCount > 0 ⏎ ? .partial(url: cached.url, failedCount: failedCount) ⏎ : .success(url: cached.url)` | Init synchronously restores .partial (not a quiet .success) when a previously-cached share still has un-landed media PUTs recorded in the failed-media cache. | This restoration must happen SYNCHRONOUSLY during construction (not deferred to a coroutine launch) so the very first render shows the correct state; an Android ViewModel that defers this read to a background coroutine could flash a wrong initial state (or .idle) before the real cached state loads. |
| **EDG-131** ✅ `VM:66,221-227@3f9f9e8` | `// Interactive means "carry the media": the first enable brings photos ⏎ // along automatically (the spec's auto-enable); the walker can still ⏎ // switch them off afterwards and we never re-flip. ⏎ if hasPinnedPhotos && !didAutoEnablePhotos { ⏎ didAutoEnablePhotos = true ⏎ includePhotos = true ⏎ }` | prepareInteractive's photo auto-enable happens exactly once per view-model lifetime via a private latch, and does not re-flip after the user manually disables photos. | Test-pinned via testInteractiveAutoEnablesPhotosOnce; without the one-shot latch, toggling Interactive off-then-on again would silently re-enable photos the user had deliberately turned off, undermining an explicit user choice. |
| **EDG-136** ✅ `VM:275-278@3f9f9e8` | `/// Loads a pinned photo as a low-res base64 JPEG for the share ⏎ /// payload. Synchronous (blocks main ~10-50ms per local photo). ⏎ /// Returns nil for deleted or iCloud-only photos, which are ⏎ /// silently dropped from the share.` | loadSharePhoto for the classic (non-interactive) share path is explicitly documented as synchronous, blocking the calling thread for roughly 10-50ms per local photo, and silently drops deleted or iCloud-only photos. | This is a deliberate architectural choice (keep the classic path fast-and-local) that Android must preserve deliberately rather than accidentally reusing the interactive path's async 20+2s-timeout machinery for classic photos, which would be needlessly slow for what's meant to be a quick local-only operation. |
| **EDG-142** ✅ `VM:364-365@3f9f9e8` | `// Recordings outrun active time by design (a talk can run through a pause); NewWalk clamps talkDuration to activeDuration for the same reason, and the worker 400s on meditate+talk > active — clamp the included-candidate sum the same way. ⏎ talkDuration: interactive ? min(includedTalkCandidates.reduce(0) { $0 + $1.duration }, walk.talkDuration) : walk.talkDuration,` | The included-candidates' summed talk duration is clamped to walk.talkDuration because the worker rejects the whole POST when meditate+talk exceeds active duration. | Test-pinned via testInteractiveTalkDurationClampedToWalkTalkDuration (120s summed clamped to 100s) — omitting this clamp means a walk with recordings spanning pauses could sum to MORE than walk.talkDuration, and the worker would 400 the entire share POST, not just drop the excess. |
| **EDG-144** ✅ `VM:444-450@3f9f9e8` | `// The worker validates TRUNCATED integers: filter after truncation or a ⏎ // sub-second pause 400s the whole share. ⏎ payload.pauses = Array( ⏎ walk.pauses ⏎ .map { (start: Int($0.startDate.timeIntervalSince1970), end: Int($0.endDate.timeIntervalSince1970)) } ⏎ .filter { $0.end > $0.start } ⏎ .prefix(200) ⏎ ).map { SharePayload.Pause(startTs: $0.start, endTs: $0.end) }` | Pauses are truncated to Int seconds and filtered AFTER truncation (not before), because the worker validates truncated integers and would 400 the whole share on a sub-second pause. | This introduces a THIRD distinct cap in the slice (200 pauses, vs 12 recordings, vs 20 photos) and a specific three-step pipeline order (truncate, then filter, then take first 200) — reordering to cap-before-filter could let a truncated-to-zero-length pause occupy one of the 200 slots and crowd out a legitimate 201st pause; test-pinned indirectly via testSubSecondPauseDroppedAfterTruncation. |
| **EDG-145** ✅ `VM:454-457@3f9f9e8` | `/// Shared by `canTrimRoute` and `computeInteractiveRoute()` so the route ⏎ /// judged for trimmability and the route actually trimmed can never be ⏎ /// two different arrays — the same divergence class Task 3 closed for ⏎ /// `RouteTrimmer.canTrim`/`.trim` themselves.` | downsampledRoutePoints() is deliberately shared by both canTrimRoute and computeInteractiveRoute so the route judged for trimmability and the route actually trimmed can never be two different arrays computed independently. | This is a THIRD layer of the same 'never compute the same derived value two different ways' pattern already applied inside RouteTrimmer itself (canTrim delegates to trim) — an Android port that recomputes downsampling separately in two places (even with identical-looking code) risks silent divergence if either call site's inputs drift, e.g. one reading walk.routeData directly and the other reading a cached copy. |
| **EDG-146** ✅ `VM:472-476@3f9f9e8` | `// Report the trim by OUTCOME, not intent: RouteTrimmer silently no-ops on a route too short to trim, so trimM/keptWindow must reflect what actually happened — never claim a 150m trim while shipping the full, untrimmed route. ⏎ let trimmed = RouteTrimmer.trim(downsampled, meters: Double(Self.trimMeters)) ⏎ let didTrim = trimmed.count < downsampled.count ⏎ let trimM = didTrim ? Self.trimMeters : 0` | Trim is reported by OUTCOME (actual point-count reduction), never by INTENT — trimM/keptWindow must reflect whether RouteTrimmer actually shortened the route. | Test-pinned via testShortRouteTrimIsHonestAndLeavesWaypointsUnfiltered — an Android port that reports trimM=150 whenever trimEnabled is true (intent-based, ignoring whether RouteTrimmer actually shortened anything) would falsely claim doorstep protection on short walks that shipped completely untrimmed. |
| EDG-149 `RTT:6@3f9f9e8` | `/// ~111m per 0.001 degrees latitude.` | The straight-route test fixture builder documents the exact geometry ratio (~111m per 0.001 degrees latitude) underlying every distance-boundary assertion in this file. | Any Android test-fixture author replicating these exact test cases must use the same latitude-only step scheme (not longitude, which varies by latitude) to get the same predictable meters-per-step ratio. |
| EDG-166 `TPET:22-25@3f9f9e8` | `// 1600px of fully uncorrelated 8px color blocks is a JPEG worst case (every ⏎ // block's DC coefficient defeats neighbor prediction): measured output ranges ⏎ // ~890KB (quality 0.2) to ~2.15MB (quality 0.8), so the cap must clear the ⏎ // ladder's lowest rung with margin for run-to-run hue randomness.` | The photo-export JPEG test uses a deterministic pseudo-random hue formula (not .random) specifically to defeat JPEG's neighbor-prediction compression while remaining reproducible across test runs, with documented empirical size bounds. | Confirms that even the ladder's MOST aggressive compression step (0.2) doesn't guarantee sub-2MB output for adversarial images — this is why jpegDataUnder can legitimately return nil, and Android's test suite needs an equally adversarial (or explicitly forced-impossible) test image to prove the same nil-when-impossible contract. |
| EDG-167 `WSIT:12@3f9f9e8` | `/// ~111m per 0.001 degree of latitude — the same geometry `RouteTrimmerTests` uses, spanning well past the 4x trim-distance threshold `RouteTrimmer` requires before it will shorten a route.` | The interactive-tests long-route fixture explicitly notes it deliberately spans well past the 4x trim-distance threshold, cross-referencing the identical geometry ratio used in RouteTrimmerTests. | Cross-file fixture consistency — Android's test suite should use the same predictable geometry scheme so fixture-derived expected values transfer cleanly. |
| EDG-177 `WSIT:507-509@3f9f9e8` | `// (testPhotosDroppedCountsAsInFlightForFormFreeze omitted: `isShareInFlight` is `private` on `WalkShareView`, not VM-exposed — nothing here to assert against.)` | A test that would assert isShareInFlight's behavior at the ViewModel level is deliberately omitted because that property is private to the View layer, not VM-exposed — an explicit meta-comment documenting a coverage gap by design, not oversight. | If Android's port hoists the equivalent form-freeze gate into the ViewModel/StateFlow layer (a very plausible architectural choice for a Compose port), that becomes a NEW testable surface iOS never had — worth deliberately adding coverage for on Android rather than treating the absence of an iOS test here as evidence none is needed. |
| EDG-178 `WSIT:539@3f9f9e8` | `XCTAssertEqual(vm.shareState, .photosDropped(prepared: 0, dropped: 1), "the fixture's localIdentifier never resolves in PHAsset.fetchAssets, so the export comes up short deterministically, without network")` | The photos-dropped-short test relies on a photo fixture whose localIdentifier is known to never resolve via PHAsset.fetchAssets, deterministically simulating an export shortfall without mocking the Photos framework or touching the network. | Android's equivalent MediaStore/ContentResolver-based export path needs an analogous deterministic-failure fixture technique (or a proper injectable seam) to test the dropped-photo consent pause without flaky reliance on real device photo state. |

### 5.10 test-pinned-invariant (27)

| citation | quote | invariant | why-could-Android-miss |
|---|---|---|---|
| EDG-150 `RTT:13-16@3f9f9e8` | `func testTrimZeroReturnsRouteUnchanged() { ⏎ let route = straightRoute(points: 10) ⏎ XCTAssertEqual(RouteTrimmer.trim(route, meters: 0).count, 10) ⏎ }` | A trim distance of exactly zero must return the route completely unchanged. | Pins the meters > 0 guard as a hard requirement, not just an optimization. |
| EDG-151 `RTT:27-30@3f9f9e8` | `func testShortWalkSharesUntrimmed() { ⏎ let route = straightRoute(points: 4)    // ~333m total < 4 * 150 ⏎ XCTAssertEqual(RouteTrimmer.trim(route, meters: 150).count, 4) ⏎ }` | A route whose total distance is just under 4x the trim distance must ship completely untrimmed, proving the exact 4x boundary numerically (4 points, ~333m total, vs 4*150=600m). | Pins the numeric value of the 4x floor, not just its existence. |
| EDG-152 `RTT:37-49@3f9f9e8` | `// Route with ~1000m + ~1000m + ~1m segments: total ~2km, but endpoints cluster ⏎ // together at the end, causing trim's start/end pointers to collide. ⏎ // canTrim must return false AND trim must return route unchanged.` | A route whose total distance clears the 4x floor can still fail to trim if the start/end pointers collide due to clustered endpoint geometry — canTrim must return false AND trim must return the route completely unchanged. | Proves the 4x-distance gate is necessary but NOT sufficient — the pointer-collision guard is an independent second safety net Android must also implement, not just the total-distance check. |
| EDG-153 `RTT:51-74@3f9f9e8` | `func testCanTrimAlwaysAgreesWithTrim() {` | canTrim and trim must agree across a battery of distinct geometries, including a uniform route, clustered endpoints, a short route, and a route with one huge end segment. | This test would catch an Android implementation where canTrim uses a different/simplified heuristic than trim itself — any independent canTrim implementation risks disagreeing with trim on exactly these adversarial geometries. |
| EDG-154 `RTT:76-103@3f9f9e8` | `func testDegenerateRouteCounts() {` | Degenerate routes of 0, 1, 2, and 3 points must all report canTrim=false and trim=unchanged, exercising the route.count > 3 guard boundary distinct from the 4x-distance guard. | Confirms the point-count guard (>3) is checked independently of and before the distance-based (4x) guard; an Android implementation that only checks distance could crash indexing into a route with fewer than 2 points. |
| **EDG-155** ✅ `SMUT:13@3f9f9e8` | `XCTAssertEqual(req.timeoutInterval, 30, "an idle timeout, not a whole-upload one — it resets on bytes moving")` | The media-upload PUT timeout must equal exactly 30 seconds and is explicitly asserted to be an idle timeout, not a whole-upload timeout. | Directly pins both the numeric value and its semantic meaning (idle vs total) for Android's HTTP client configuration. |
| **EDG-156** ✅ `SMUT:22-31@3f9f9e8` | `XCTAssertEqual(reloaded, failures, "round-trip through JSON must preserve identity fields, not just kind/n")` | FailedMediaItem must round-trip through JSON encoding preserving ALL identity fields (kind, n, audioStartTs, photoLocalID, photoTs), not just kind and n. | An Android serializer that drops or defaults any one of these five fields on deserialize would silently break retry-identity resolution for that record without an obvious symptom until a retry is attempted. |
| **EDG-157** ✅ `SMUT:33-38@3f9f9e8` | `XCTAssertEqual(ShareService.failedMedia(for: walkID), [failures[0]], "pruning the completed item must remove exactly it, leaving the other cached failure untouched")` | Pruning a completed upload item from the failed-media cache must remove exactly that (kind,n) pair, leaving any other cached failure untouched. | A prune implementation that matches on n alone (ignoring kind) could remove the wrong item if audio and photos ever share the same n value, which they routinely do since n restarts per kind. |
| **EDG-158** ✅ `SMUT:57-69@3f9f9e8` | `XCTAssertEqual(failures.count, audioFiles.count + photos.count, "background-exhausted from the very first item of each loop must fail everything without attempting a PUT — the nonexistent audio fileURL never gets read") ⏎ XCTAssertEqual(lastProgress, ShareService.MediaProgress(completed: audioFiles.count + photos.count, total: audioFiles.count + photos.count), "the per-loop skip accounting must still land exactly on (total, total)")` | When background time is exhausted from the very first item of a loop, ALL items in that loop must fail without any network attempt — proven by pointing at a genuinely nonexistent file path that would throw if actually read. | Confirms the exhaustion check happens BEFORE any file I/O is attempted for each item, and that the final progress report always lands exactly on (total,total) even when every item was skipped rather than actually uploaded. |
| **EDG-159** ✅ `SPTT:59-63@3f9f9e8` | `func testAbsentTourAndPausesOmittedFromJSON() throws { ⏎ let json = try encodeToJSON(minimalPayload(tour: nil)) ⏎ XCTAssertNil(json["tour"]) ⏎ XCTAssertNil(json["pauses"]) ⏎ }` | Tour and pauses fields must be entirely ABSENT from the encoded JSON (not present-as-null) when nil. | This is the test that would fail first if Android's serializer defaults to including nulls — directly validates the encodeIfPresent behavior flagged elsewhere in this report. |
| **EDG-160** ✅ `SPTT:65-71@3f9f9e8` | `func testPhotoWithoutDataOmitsDataKey() throws { ⏎ let photo = SharePayload.Photo(lat: 35.69, lon: -105.94, ts: 1200, data: nil) ⏎ let json = try encodeToJSON(minimalPayload(tour: nil, photos: [photo])) ⏎ let photos = try XCTUnwrap(json["photos"] as? [[String: Any]]) ⏎ XCTAssertNil(photos[0]["data"]) ⏎ XCTAssertEqual(photos[0]["ts"] as? Int, 1200) ⏎ }` | A photo without embedded data must omit the 'data' key entirely from its JSON object while still including its other fields like ts. | Directly validates the interactive-photo-path invariant that data is always nil/omitted for tour photos, distinguishing it from the classic path's populated base64 data field. |
| **EDG-161** ✅ `SPTT:31-57@3f9f9e8` | `XCTAssertEqual(tourJSON["trim_m"] as? Int, 150)` | Tour recordings and pauses encode with exact snake_case wire field names (trim_m, start_ts, end_ts, size_bytes) in array order matching input order. | Confirms the exact wire vocabulary Android's serializer must reproduce for the worker to accept the payload. |
| EDG-162 `TBT:14-19@3f9f9e8` | `func testClassify_slowContemplativeSpeechIsSpoken() { ⏎ // 25 wpm over a 5-minute talk is a real pattern on real walks — ⏎ // sparse words must not demote a deliberate talk to ambience. ⏎ let words = Array(repeating: "word", count: 120).joined(separator: " ") ⏎ XCTAssertEqual(TourBuilder.classify(transcription: words), .spoken) ⏎ }` | A transcription that reads as slow, contemplative speech (120 words over a notionally 5-minute talk, ~25wpm) must still classify as spoken, never demoted to ambient by word rate. | Confirms there is truly no WPM-based gate anywhere in the classification logic, only the raw word-count threshold — an Android 'improvement' adding a WPM check would fail this exact scenario. |
| EDG-163 `TBT:38-45@3f9f9e8` | `func testTourItems_renumbersAfterExclusion() { ⏎ let candidates = [candidate(id: 0), candidate(id: 1, included: false), candidate(id: 2)] ⏎ let (tour, files) = TourBuilder.tourItems(candidates: candidates, trimM: 150) ⏎ XCTAssertEqual(tour.recordings.map(\.n), [1, 2])` | tourItems must renumber the wire-facing 'n' densely starting at 1 over only the INCLUDED candidates, skipping the excluded one's original position entirely rather than leaving a gap. | Directly pins that n is a FRESH dense renumbering, structurally decoupled from candidate.id — the single most important identity-mapping invariant in this feature area to get right in an Android port. |
| **EDG-164** ✅ `TBT:121-141@3f9f9e8` | `XCTAssertEqual(candidates.count, 1, "a recording with no file on disk is still a candidate — just an unavailable one")` | A candidate with a missing audio file must still appear in the returned list marked unavailable, distinct from a sub-second blip which is excluded from the list entirely. | Two different 'bad recording' scenarios have two DIFFERENT handling strategies (visible-but-disabled vs invisible); an Android port that treats all bad-recording cases uniformly (e.g. 'just filter it out') would either hide a legitimately-informative unavailable row or show a phantom sub-second blip. |
| EDG-165 `TBT:143-160@3f9f9e8` | `XCTAssertEqual(candidates.map(\.startTs), candidates.map(\.startTs).sorted(), "candidates must come back sorted by start date regardless of storage order")` | Candidates must always come back sorted by chronological start date regardless of the underlying storage order in the walk model. | If Android's Room query doesn't guarantee ordering (or the repository forgets an explicit ORDER BY), candidate ids/display order could vary run-to-run. |
| EDG-168 `WSIT:95-110@3f9f9e8` | `XCTAssertEqual(talkIntervals.count, 1, "the excluded candidate's talk interval must not appear") ⏎ XCTAssertEqual(payload.stats.talkDuration, keptCandidate.duration, "excluded duration must not count toward the total")` | An excluded recording candidate must leave literally no trace in the payload: no talk activity interval, and its duration must not count toward the talk-duration total. | An Android port that filters activity intervals but forgets to also exclude the duration from the stats sum (or vice versa) would leave a subtle inconsistency between the map/timeline and the headline stat. |
| EDG-169 `WSIT:112-127@3f9f9e8` | `XCTAssertEqual(talkIntervals.count, 2, "classic path reads walk.voiceRecordings directly — candidate exclusions must have zero effect")` | The classic (non-interactive) share path must read walk.voiceRecordings directly and completely ignore any tourCandidates exclusion state. | An Android port that accidentally shares a single 'included recordings' computation between classic and interactive paths would let interactive-only exclusion state leak into classic shares, silently dropping talk intervals the user never asked to exclude. |
| EDG-170 `WSIT:206-223@3f9f9e8` | `XCTAssertTrue(labels.contains("AtLowerBound"), "a waypoint exactly at the kept window's lower bound must be included — ClosedRange.contains is inclusive") ⏎ XCTAssertTrue(labels.contains("AtUpperBound"), "a waypoint exactly at the kept window's upper bound must be included — ClosedRange.contains is inclusive")` | A waypoint sitting exactly at the trimmed route's lower or upper timestamp boundary must still be included, proving the kept-window range check is inclusive on both ends. | Kotlin's IntRange (`..`) is also closed/inclusive by default, which is convenient parity — but if Android instead models the kept window as a half-open range or two separate comparison operators, a waypoint exactly on the boundary could be dropped when iOS keeps it. |
| **EDG-171** ✅ `WSIT:225-247@3f9f9e8` | `XCTAssertEqual(payload.tour?.trimM, 0, "a route too short to actually trim must report trimM 0, not the requested 150 — RouteTrimmer silently no-ops on it")` | A route too short to actually trim must report trimM as 0 (not the requested 150) and must leave waypoints completely unfiltered, even a waypoint recorded before the route's first GPS fix. | Directly enforces the 'report by outcome, not intent' invariant — an Android port using trimEnabled as a proxy for 'was trimmed' (rather than checking RouteTrimmer's actual output) would falsely claim a trim that never happened. |
| EDG-172 `WSIT:249-264@3f9f9e8` | `XCTAssertNil(withoutExport.photos, "the interactive branch must never fall back to mapping pinnedPhotos")` | Interactive photo metadata in the final payload must come EXCLUSIVELY from the export result, never falling back to mapping raw pinnedPhotos even when the export result is empty. | A fallback-to-pinnedPhotos 'just in case' safety net in Android would orphan map markers whose corresponding photo file never actually got uploaded, since declared metadata would then reference photos that were never PUT. |
| **EDG-173** ✅ `WSIT:266-289@3f9f9e8` | `XCTAssertEqual(cappedVM.interactivePhotoExportList().count, 20, "the export list itself caps at 20") ⏎ XCTAssertTrue(cappedVM.tourTotalsLabel.contains("20 hi-res photos"), "the label's photo count must cap at the export list's count, not the full pinned count")` | The photo export list caps at exactly 20 even when 25 photos are pinned, and the totals label's photo count must reflect the capped 20, not the full 25. | An Android label implementation that reads pinnedPhotos.count directly (instead of interactivePhotoExportList().count) would display '25 hi-res photos' in the UI while only 20 actually export/upload — a visible over-promise. |
| EDG-174 `WSIT:398-412@3f9f9e8` | `XCTAssertEqual(resolved.n, 1, "must upload under the CACHED slot n, not photo-B's current array position") ⏎ XCTAssertEqual(try? resolved.data(), Data([0xBB]), "must upload photo-B's bytes (matched by identity), never photo-A's (which sat at the naive index)")` | A photo retry must resolve the correct file by stable identity (localIdentifier+ts) even when the failed item's original array position has shifted in the current export list, uploading under the ORIGINAL cached slot number, never the item's new position. | This is the exact scenario that breaks under a naive index-based retry (currentPhotos[n-1]) — a shifted export order would silently upload the WRONG photo's bytes under the failed slot's number, corrupting a live shared page. |
| **EDG-175** ✅ `WSIT:449-463@3f9f9e8` | `XCTAssertEqual(uploadable.first?.n, 3, "must upload under the CACHED slot n, not the recording's shifted array position")` | An audio recording that shifted to a different array index since the original share (because an earlier recording dropped out) must still be found by its startTs identity, and re-uploaded under its ORIGINAL cached slot number, not its new index. | Same class of bug as the photo case above but for audio — proves the identity-not-index rule applies symmetrically to both media kinds despite their different identity-key shapes (startTs alone vs localIdentifier+ts). |
| **EDG-176** ✅ `WSIT:479-505@3f9f9e8` | `XCTAssertEqual(records.count, 3, "one record per recording plus one per photo — the FULL upload, not just failures") ⏎ ⏎ XCTAssertEqual(records[2].kind, "photos") ⏎ XCTAssertEqual(records[2].n, 1)` | expectedFailureRecords must produce exactly one record per recording plus one per photo (the FULL upload manifest, not just currently-failed items), with n restarting at 1 for photos independent of how many audio recordings preceded them. | Directly proves the per-kind-independent n-restart rule with a concrete counter-example (if n were global, the photo record would need n=3, but the test asserts n=1) — the clearest single test to check an Android port against for this specific invariant. |
| **EDG-179** ✅ `WSIT:542-557@3f9f9e8` | `XCTAssertEqual(vm.shareState, .uploading, "the prompt's buttons must vanish within the same runloop turn, before any await") ⏎ ... ⏎ XCTAssertEqual(vm.shareState, .uploading, "a same-runloop double-tap must be a no-op — the shareTask guard covers it, not a second resume task")` | continueShareWithoutDroppedPhotos must claim the .uploading state within the same runloop turn (before any await), and a same-runloop double-tap must be a no-op that does not spawn a second resume task. | An Android coroutine implementation that sets the equivalent StateFlow value inside the launched coroutine block (rather than before launching it) would fail this exact test pattern, leaving a frame where a double-tap could launch two overlapping resume flows. |
| **EDG-180** ✅ `WSIT:559-568@3f9f9e8` | `XCTAssertEqual(vm.shareState, .idle, "declining while a resume is in flight must cancel it — completeShare's pre-POST checkpoint returns idle before geocoding or POSTing ever run")` | Declining ('Don't share yet') while a resume is already in flight must cancel that in-flight resume, landing on idle via the same pre-POST checkpoint rather than letting the resume complete and share anyway. | An Android implementation where 'decline' only clears local pending-photo state but doesn't cancel the already-launched resume coroutine would let a fast Share-then-Decline tap sequence share the walk anyway, against the user's explicit final choice. |

### 5.11 threshold-guard (10)

| citation | quote | invariant | why-could-Android-miss |
|---|---|---|---|
| **EDG-2** ✅ `RD:9@3f9f9e8` | `guard points.count > maxPoints else { return points }` | Downsampling is skipped entirely when point count is already at or under the cap. | An off-by-one (>= instead of >) would downsample a route exactly at 200 points when iOS would not. |
| **EDG-3** ✅ `RD:11@3f9f9e8` | `guard result.count <= maxPoints else { ⏎ return strideSample(result, target: maxPoints) ⏎ }` | A second fallback triggers only when RDP simplification still exceeds the cap. | Skipping the stride fallback would let over-cap outputs from RDP's epsilon binary search reach the server uncapped. |
| EDG-7 `RD:34@3f9f9e8` | `guard points.count > 2 else { return points }` | Ramer-Douglas-Peucker's recursive base case bails for 2 or fewer points. | An off-by-one here changes simplification behavior for very short route segments during recursion. |
| EDG-9 `RD:50@3f9f9e8` | `if maxDist > epsilon {` | RDP recurses only when the max perpendicular deviation strictly exceeds epsilon; ties keep just the two endpoints. | Using >= instead of > changes which routes recurse at exact-epsilon boundaries, producing a different point count for the same input. |
| **EDG-18** ✅ `RT:9@3f9f9e8` | `guard meters > 0, route.count > 3 else { return route }` | trim() only proceeds for positive meters and routes with more than 3 points. | A route of exactly 3 points must return unchanged; off-by-one here (>= 3) would attempt to trim a 3-point route and likely collide start/end pointers unpredictably. |
| EDG-23 `RT:21@3f9f9e8` | `guard end > start else { return route }` | If the inward-walking start/end pointers meet or cross, trim silently falls back to the untouched route instead of returning an invalid or empty slice. | This is the guard proven by testClusteredEndpointCollisionIsHonest — a route that clears the 4x total-distance gate can still have clustered endpoints that collide; without this second independent safety net, trim would return an empty or single-point route. |
| **EDG-36** ✅ `SS:69-71@3f9f9e8` | `if httpResponse.statusCode == 429 { ⏎ throw ShareError.rateLimited ⏎ }` | HTTP 429 is special-cased into a distinct rate-limited error before the general success-range check. | Without this branch, Android would surface 429 as a generic server error instead of the friendlier 'shared too many walks today' copy. |
| **EDG-52** ✅ `SS:384@3f9f9e8` | `for attempt in 0..<2 {` | Each media item gets exactly one retry (2 total attempts), enforced by a hardcoded loop range. | A different retry count changes both upload reliability and worst-case background-time consumption per item; the doc comment elsewhere assumes exactly this budget (~60s worst case for one item's attempt+retry cycle). |
| **EDG-63** ✅ `TB:39@3f9f9e8` | `if wordCount < 8 { return .ambient }` | The spoken/ambient classification boundary is a strict word count of 8. | Test-pinned via 3-word ('wind and birds') -> ambient and 20-word -> spoken; the exact boundary value must match or real recordings near 8 words get misclassified. |
| **EDG-68** ✅ `TB:56-63@3f9f9e8` | `if size == nil \|\| size == 0 { ⏎ unavailableReason = "audio removed" ⏎ } else if let size, size > maxFileBytes { ⏎ unavailableReason = "too large to carry" ⏎ } else { ⏎ unavailableReason = nil ⏎ }` | unavailableReason is computed by an if/else-if chain: nil-or-zero size means 'audio removed', over maxFileBytes means 'too large to carry', otherwise available. | These exact strings are shown directly in the UI (InteractiveShareSection); any wording drift is a visible UX mismatch, and the boundary is strictly greater-than maxFileBytes (exactly 15MB is fine, 15MB+1 byte is not). |

---

## 6. Android implementation notes

Existing Android surfaces the port should reuse rather than reinvent (from the controller's Android scan of `9bc3fa10`):

- `ui/theme/Tokens.kt` → `object PilgrimSpacing` (spacing tokens); `Color.kt`, `PilgrimFonts.kt`, `Type.kt`, `Theme.kt`, `seasonal/` for color/typography roles.
- `di/NetworkModule.kt:60-68` → `provideJson(): Json { explicitNulls = false; ... }` — already gives Swift `encodeIfPresent` omit-nil semantics for the share payload; the U1 production probe confirmed the worker 400s on literal nulls, so payload encoding MUST go through this `Json` (add a serialization test pinning key-absence).
- `data/share/DeviceTokenStore.kt` → `DeviceTokenSource` with DataStore-persisted UUID; reuse for `X-Device-Token` on media PUTs.
- `data/share/CachedShareStore.kt` → DataStore per-walk cache with encode/decode + `reconstructUuid` conventions; the repair record should be a sibling store (`ShareRepairStore`) following the same shape.
- `data/share/SharePayloadBuilder.kt` → `ShareInputs` + `WalkShareOptions` → `build(...)`; interactive/trim/exclusion options belong on `WalkShareOptions` so trim runs before payload build.
- `ui/walk/share/WalkShareViewModel.kt` → toggle-method convention (`toggleX(on: Boolean)`), `ExpiryOption` enum (default Season), `cachedShare` StateFlow short-circuit (first-share-only — must stay).
- `audio/OrphanRecordingSweeper.kt` → canonical-path + extension + regular-file guard pattern for the transcode-artifact orphan sweep.
- `audio/VoiceRecordingFileSystem.kt` → recording path convention `recordings/<walkUuid>/<recUuid>.wav`.
- `data/entity/VoiceRecording.kt` → fields `uuid, walkId, startTimestamp, endTimestamp, durationMillis, fileRelativePath, transcription?, wordsPerMinute?, isEnhanced` — everything `TourBuilder` consumes exists.
- Tests: JUnit4 + Turbine + Robolectric; NATIVE graphics mode precedent `GlyphAssetTest`; platform-object builder rule (real `.build()` on production classes).
- **Android-original context this spec must flag (no iOS reference):** Android records 16 kHz mono WAV (`AudioRecordCapture.SAMPLE_RATE_HZ = 16_000`), so a WAV→AAC-LC transcode-at-prep pipeline replaces iOS's already-AAC no-transcode path; iOS's `UIBackgroundTask` assertion lifecycle has no Android equivalent — repair-record semantics are the parity bar (per the approved plan's Decisions 1-4).

---

## 7. Lens disagreements

Findings that conflicted or framed the same code in incompatible ways. Each needs a decision before implementation.

- **`didAutoEnablePhotos` — "persisted" vs "per-VM-instance".** *behavior* (BEH-80, `VM:217-228@3f9f9e8`) states the latch "is plain instance state (not persisted), so it resets whenever a fresh VM is constructed," and warns that a port storing it *per-walk* would behave differently across re-opens. *ui-visual* (UI-64, same citation) says "An Android port using a derived/computed default instead of a **persisted** one-shot flag would re-trigger the auto-enable." Taken literally the two prescribe opposite storage. **Resolution needed:** iOS ground truth is per-VM-instance (`private var didAutoEnablePhotos` on the ViewModel, no `UserDefaults` write anywhere in this slice). "Persisted" in UI-64 should be read as "held in an explicit flag" not "written to DataStore." Matters because a Hilt `@HiltViewModel` scoped to a `NavBackStackEntry` survives back-navigation differently than an iOS sheet's VM.

- **`prepareInteractive()` re-entrancy — "call it every time" vs "don't refresh".** *behavior* (BEH-30, `ISS:25-27@3f9f9e8`) warns that "a port that only calls the equivalent setup once per VM lifetime instead of on every toggle would miss re-populating `tourCandidates` if it had been cleared." *edge-cases* (EDG-132, `VM:218@3f9f9e8`) warns the opposite direction: "An Android port that refreshes candidates on every `prepareInteractive()` call … would change behavior from iOS's lazy-once snapshot." **Resolution:** both are satisfied by iOS's actual shape — call the prepare function on every toggle-to-true, but guard the candidate population with `isEmpty`. A port must do *both* halves; picking one lens in isolation yields a bug either way.

- **`validationError` copy derivation — "derived" vs "hardcoded".** *ui-visual* (UI-73, `TB:24-27,90-92@3f9f9e8`) claims "Aggregate caps copy derives its minutes from `maxTotalSeconds` — the string has no hardcoded minute figure at the pin," implying the caps copy is uniformly derived. *edge-cases* (EDG-69, `TB:91-92@3f9f9e8`) shows the adjacent BYTES message *does* hardcode the literal text `60 MB` while only the seconds message derives. **Resolution:** both are correct about their own string; the function is internally inconsistent. Android must decide per-string whether to replicate iOS's inconsistency or normalize (and record the choice).

- **`isShareInFlight` architectural home — View-private vs VM-observable.** *behavior* categorises BEH-37 as a `state-machine` finding and *ui-visual* (UI-56) as a `conditional-render` finding, both treating it as observable feature state. *edge-cases* (EDG-177, `WSIT:507-509@3f9f9e8`) quotes the test-suite comment proving it is `private` on `WalkShareView` and therefore deliberately untested at the VM layer. **Resolution:** a Compose port will almost certainly hoist this into the ViewModel as a derived `StateFlow`, creating a testable surface iOS never had. That is an improvement, not drift — but it must be a *conscious* decision with new Android-side coverage, not an accident.

- **TourBuilderTests cap boundaries — self-referential version note.** *behavior* (BEH-100, `TBT:74-83@3f9f9e8`) quotes `let long = (0..<7).map { candidate(id: $0, seconds: 1000) }` and a 6-candidate under-cap assertion as present **at the pin**, but its own drift note says those same numbers "change to 7 × 1000s plus a new 6-candidate under-cap assertion in a **later** iOS commit past the parity pin." The quote and the note contradict each other about what is already at `3f9f9e8`. **Resolution:** re-verify `TBT:74-83` at `3f9f9e8` before encoding these boundary values in Android tests.

- **`StatToggleRow` accessibility — spec or gap?** *ui-visual* (UI-61, `WSV:406-428@3f9f9e8`) documents that `StatToggleRow` has no `.accessibilityElement(children: .combine)` unlike `TourRecordingRow` (UI-23), and explicitly hedges: "this may be an existing iOS accessibility gap rather than an intended design." No other lens covers it. **Resolution:** treat as an iOS gap; Android should merge semantics on the toggle row (better than parity) rather than reproduce the ungrouped structure.

- **Photos-first ordering vs the function's parameter order.** *behavior* (BEH-9), *data* (DAT-30) and *edge-cases* (EDG-43) all assert photos upload strictly before audio, quoting the doc comment. But the call site quoted in BEH-61 and the test quoted in BEH-89 both use the signature `uploadAllMedia(shareID:audioFiles:photos:)` — parameter order is audio-first while loop order is photos-first. No lens flags this. **Resolution:** the Kotlin signature should not mirror the Swift parameter order without a comment, or a future reader will "fix" the loop to match the params.

- **Cancellation granularity for photo export.** *behavior* (BEH-25) and *data* (DAT-54) both describe the one-check-per-photo bound as a *guarantee to preserve*; *edge-cases* (EDG-78) frames it as a constraint Android must consciously match rather than improve on. No factual conflict, but the framings pull in opposite directions on whether a mid-photo `ensureActive()` is acceptable. **Resolution:** iOS's bound is "stops within ~one photo," i.e. up to 22 s. If Android adds mid-photo cancellation, the dropped-photo accounting (`exportCount - tourPhotos.count`, BEH-54/EDG-111) must still produce a sane `.photosDropped` count rather than a spurious consent prompt on a user-initiated cancel.

---

## 8. Open questions

Ambiguities that need human review before this spec becomes a plan.

**Scope / pin**

1. **The pin itself — RESOLVED.** This spec is generated against iOS `3f9f9e8`, which IS the current frozen parity target in `pilgrim-android/CLAUDE.md` (re-pinned from `b4decad` via the approved 2026-08-14 retarget brainstorm, then from `38ef6b2` via the R2 fold-in of iOS PR #60 — 108-minute tours — on 2026-08-15). Triage is complete; this slice is Phase 19 of the approved plan (`docs/plans/2026-08-14-001-feat-walk-with-me-interactive-share-plan.md`).

**New / changed backend surface not in the CLAUDE.md ecosystem table**

2. **`/api/share` vs `/share/*`.** CLAUDE.md documents `Share worker: https://walk.pilgrimapp.org/share/*`. The actual iOS endpoints (DAT-21, DAT-25, DAT-26, EDG-40) are `POST /api/share` and `PUT /api/share/{shareID}/{audio|photos}/{n}` — the `/api/` prefix mirrors `/api/counter`. The CLAUDE.md table was corrected alongside this spec (same commit), and the Android client must target `/api/share/*` literally.
3. **Media PUT is a new endpoint family for Android.** Nothing in the current Android share stack (`ShareService.kt`, `SharePayload.kt`) does media uploads. `PUT /api/share/{id}/{kind}/{n}` with `X-Device-Token`, exact `Content-Length`, and per-kind MIME is entirely new surface.
4. **PUT idempotency is an unverified assumption.** EDG-113 quotes `PUTs are idempotent, over-repair is harmless` as the justification for pre-populating the whole failure record before upload. The entire kill-safe repair design rests on this. Needs confirmation the same worker serves Android and that re-PUTting identical bytes to an already-filled slot is a no-op.
5. **`Content-Type: audio/mp4` vs Android's WAV.** DAT-25/EDG-41 hardcode `audio/mp4` for audio PUTs. Android records **16 kHz mono WAV** (`AudioRecordCapture.SAMPLE_RATE_HZ = 16_000`). Either the worker accepts a different Content-Type, or Android must transcode WAV→AAC-LC at prep time (the plan's Decision 1-4 territory). This also changes `sizeBytes` (DAT-15) and the 15 MiB per-file / 60 MiB total caps' real-world headroom.
6. **`X-Device-Token` casing.** DAT-22: iOS emits `UUID().uuidString` (uppercase); Kotlin's `java.util.UUID.toString()` is lowercase. If the worker compares tokens case-sensitively, `DeviceTokenStore.kt`'s existing value may already differ from what an iOS install would send. Needs a worker-side confirmation.

**Platform concepts with no Android equivalent** *(Top-level either/or RESOLVED by plan Decision 1: AAC-LC mono 64 kbps M4A transcode at prep; the size_bytes/cap-headroom follow-on remains live for U4.)*

7. **`backgroundTimeExhausted()` (10 s / ~30 s grant).** BEH-14, DAT-34, EDG-48 — a hard iOS-only cliff. Android's foreground service has no equivalent. Decide: drop the concept entirely, or replace with a foreground-service teardown deadline plus an injectable seam (EDG-47 requires the seam either way for testability).
8. **`withBackgroundAssertion` / `UIBackgroundTask`.** BEH-17, DAT-36, EDG-51 — no Android analogue. The *architectural* invariant to preserve is the one-shot race guard between normal completion and OS-driven expiration (double-`stopSelf`/double-release protection).
9. **PhotoKit's 20 s cancel + 2 s backstop.** BEH-23/26/27/28, DAT-51/55, EDG-80/84 — the dual-deadline design exists because PhotoKit's cancellation is "best effort, may never call back." MediaStore/ContentResolver has different semantics; the backstop still needs an independent Android design, not a single `withTimeout`.
10. **`ritualDidFire` / podcast card / preview cover.** BEH-40/41/42/43/44/45/46/48, EDG-99..EDG-104 reference `PodcastSubmissionService.shared.isEligible(walk:)`, `WebViewLoader`, and a `fullScreenCover` ritual — **all outside this slice's file list and with no known Android equivalent**. Confirm whether the Android port includes the ritual/podcast reveal at all, or ships only the share mechanics.
11. **`hasExistingShare` consumer.** BEH-78 — this accessor re-reads the cache live rather than trusting `shareState`, and the lens speculates it's used by "a different screen (e.g. walk summary)." That consumer is outside the slice. Confirm whether Android needs a lightweight query path.
12. **Feature gates outside the slice.** UI-67 (`hasPinnedPhotos`) requires `UserPreferences.walkReliquaryEnabled.value` **and** `isPhotosGranted()`. Both need confirmed Android equivalents (a Reliquary preference + a runtime media-permission check) before the Photos toggle can be gated correctly.
13. **`WalkFavicon` → `mark` and `TurningDayService` markers.** DAT-61/EDG-143 (three-way mapping) and EDG-148 (only 4 of 8 turning-day markers forwarded) both depend on domain types outside this slice. Confirm the Android enums exist and carry the same cases.

**Magic numbers not traceable to any Constants symbol**

14. Bare literals with no `Constants` token, each needing an explicit Android home: **20** hi-res photo export cap (EDG-119 — inline `prefix(20)`, not a named constant); **200** pause cap (EDG-144); **200** route-point cap (EDG-1 — default parameter); **0.0 / 0.01 / 20** RDP epsilon bounds + iterations (EDG-14, EDG-15); **6_371_000.0** Earth radius (EDG-26); **44** pt tap target (EDG-90 — must become 48 dp, not 44); **1.5** letter-spacing tracking (UI-20); **200** pt thumbnail height (UI-54); **12** pt and **14** pt vertical paddings (EDG-94, EDG-95); **10** pt / **6** pt / **4** pt one-off spacings (UI-30, UI-49, UI-4); **0.45 / 0.6 / 0.35 / 0.4 / 0.12 / 0.06** opacities (EDG-91, EDG-92, EDG-105, UI-42, UI-47); **0.2 s** disclosure animation (EDG-86); **500 ms** and **800 ms** reveal delays (EDG-101, EDG-102); **800 ms** retry backoff (EDG-53); **cornerRadius 4** on the kind chip (UI-30).
15. **Three MB-conversion behaviours in one slice.** EDG-70 / EDG-121: `bytes / 1_048_576` (truncating Int) in `TourBuilder.validationError`, vs `Double(bytes) / 1_048_576` with `%.1f` in both `tourTotalsLabel` and `InteractiveShareSection.sizeLabel`. Android must replicate per-site, not unify.
16. **`"150 m"` hardcoded in copy** (UI-15, EDG-85) while `trimMeters = 150` lives in the VM (DAT-56, EDG-120). Decide whether Android interpolates (better) or hardcodes (exact parity).

**Behaviours with no Android-side reference implementation**

17. **Classic-path main-thread photo blocking.** BEH-84, DAT-62, EDG-136/EDG-138 — iOS deliberately blocks main for ~10-50 ms/photo with `isSynchronous = true`, `isNetworkAccessAllowed = false`. Android must decide explicitly (blocking MediaStore read on a background dispatcher, most likely) rather than either copying the block or silently going async.
18. **Journal 140-char clamp semantics.** EDG-106 — Swift `String.count` counts grapheme clusters; Kotlin `String.length` counts UTF-16 units. Emoji/combining-character entries clamp differently, and naive `substring` can split a cluster.
19. **`steps.formatted()` locale behaviour.** EDG-129 — iOS uses current-locale grouping/digits; project convention is `Locale.US` for numeric display. A deliberate, documented divergence either way.
20. **`RouteDownsampler` has no test file in this slice.** EDG-16 — the epsilon-convergence and stride-fallback behaviours are entirely untested on iOS. Android should add coverage rather than inherit the gap; note that Swift `.rounded()` (half-away-from-zero) vs Kotlin `roundToInt` tie-breaking (EDG-6) is exactly the kind of divergence no existing test would catch.
21. **Error copy is not portable.** UI-40 — `.error(message:)` renders `error.localizedDescription`, a system string. Android will produce different text for the same failure class; only the rust/centred/caption styling ports. `ShareError.rateLimited`'s copy (DAT-40) *is* app-authored and must match exactly.

> Spec written. Run `superpowers:writing-plans <path>` to break into tasks, or `jutsu swarm doc-review <path>` for a remote QA gate first.

## 9. Fold-in addendum @2ee1185 (2026-08-16 — iOS PRs #61, #62, #63)

Body sections 1-8 were lens-verified at `3f9f9e8` and their citations remain pinned there. This addendum covers the `3f9f9e8..2ee1185` delta (the only app-code changes: SharePayload.swift, TourBuilder.swift, InteractiveShareSection.swift, WalkShareViewModel.swift + their tests; later-line citations in those four files drift by +4/+15/+3/+7 respectively at the new pin). It SUPERSEDES: the §4 Tour entity field table, any §2/§5 row quoting the `tourItems(candidates:trimM:)` signature, the §3 trim-toggle binding row, and the §2 `applyInteractiveTourAndPauses` quote.

- **FOLD-1 (entity).** `Pilgrim/Models/Share/SharePayload.swift:89-100@2ee1185` — `Tour` gains `let soundscapeUrl: String?` with `case soundscapeUrl = "soundscape_url"`; doc comment: "The walker's own meditation soundscape (cdn URL). nil when the walker sits in silence — the page then stays silent too." Optional ⇒ encodeIfPresent ⇒ key ABSENT when nil (worker nulls rule).
- **FOLD-2 (resolver).** `Pilgrim/Models/Share/TourBuilder.swift:96-110@2ee1185` — `static func soundscapeUrl(selectedId: String?, manifest: AudioManifest?) -> String?` resolves `Config.Audio.r2BaseURL/{asset.type.rawValue}/{asset.id}.aac`. Comment pins the trap: "the same base/type/{id}.aac formula AudioDownloadManager fetches with — NOT r2Key, whose bucket-relative path already contains the audio/ prefix and would double it. nil in = silence chosen = nil out; a retired id also resolves to nil, never a dead link." (PR #62 fixed a live 404 from the r2Key form.)
- **FOLD-3 (signature).** `TourBuilder.swift:112@2ee1185` — `tourItems(candidates:trimM:soundscapeUrl: String? = nil)` threads the url into `SharePayload.Tour`.
- **FOLD-4 (VM seam).** `Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:442-450@2ee1185` — `applyInteractiveTourAndPauses` passes `TourBuilder.soundscapeUrl(selectedId: UserPreferences.selectedSoundscapeId.value, manifest: AudioManifestService.shared.manifest)`. Interactive-only by construction (rides inside `tour`).
- **FOLD-5 (UI).** `Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:56-59@2ee1185` — trim toggle binding becomes outcome-not-intent: `get: { viewModel.trimEnabled && viewModel.canTrimRoute }, set: { viewModel.trimEnabled = $0 }` — a too-short walk displays OFF while the stored intent survives.
- **FOLD-6 (test-pinned).** `UnitTests/TourBuilderTests.swift:62-86@2ee1185` — `testSoundscapeUrl_resolvesThroughManifest` (formula, nil-for-silence, nil-for-retired-id, nil-for-no-manifest) and `testTourItems_carriesSoundscapeUrl` (carried when given, nil default). `UnitTests/SharePayloadTourTests.swift:34-46@2ee1185` — wire assertion `tourJSON["soundscape_url"]`.

