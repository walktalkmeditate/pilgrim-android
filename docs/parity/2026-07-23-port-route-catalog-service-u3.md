# Collective Route Catalog Service (U3) — iOS → Android Port Spec

> **Plan:** `docs/plans/2026-07-23-001-feat-ios-v190-parity-port-plan.md` (U3) · **Requirements:** R3, R4, R5 (AE4)
> **iOS pin:** `pilgrim-ios` @ `9a418e4` (HEAD of `main`, 2026-07-21). All Swift quotes cite `file@9a418e4`.
> **Authority:** SHIPPED Swift over plan docs. One plan-vs-Swift correction (R5): the plan says an "empty response falls back to the bundled catalog" — the shipped guard keeps the **current** catalog, whichever tier it came from (C4). On a fresh install the current catalog *is* the bootstrap, so the plan's acceptance example (AE4) still holds observably; with a cache present, shipped Swift keeps the cache, not the bootstrap.
> **U2 companion:** `docs/parity/2026-07-23-port-route-catalog-u2.md` — the model/decode this service publishes. Per U2's D6, this service calls `CollectiveRouteCatalog.decode(text)` and never rolls its own decoder.
> **Android files:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/routes/{CollectiveRouteCatalogService,CollectiveRoutesConfig,CollectiveRouteCatalogScope}.kt`, `app/src/main/assets/collective/collective-routes-bootstrap.json`, providers in `di/NetworkModule.kt`, launch hook in `PilgrimApp.kt`, tests `CollectiveRouteCatalogServiceTest.kt` + `CollectiveRouteBundledArtifactTest.kt`.
> **Android template:** `data/voiceguide/VoiceGuideManifestService.kt` — filesDir cache with atomic tmp+rename, `initialLoad: Deferred<Unit>`, `compareAndSet` sync dedup, CE-rethrow catch family. The bundled-asset bootstrap tier below the cache is new to U3.

## C1. Service shape — published catalog, three-tier precedence

**iOS** (`Pilgrim/Models/Collective/CollectiveRouteCatalogService.swift@9a418e4`):

```swift
/// Owns the collective-route artifact: loads a catalog from disk at launch,
/// refreshes it from the CDN, publishes whichever is current. Shaped after
/// `WhisperManifestService` — the cheap init, detached load, service-as-parameter
/// and await-before-compare each close a stall this app has already shipped once.
final class CollectiveRouteCatalogService: ObservableObject {
    static let shared = CollectiveRouteCatalogService()
    @Published private(set) var catalog: CollectiveRouteCatalog?
    @Published private(set) var isSyncing = false
    ...
    private static let cacheFileName = "routes.json"
```

Precedence is fetched > cached > bundled bootstrap: the initial load prefers the cache and falls back to the bootstrap (C2); a successful sync with a different content-version replaces whatever is published (C4).

**Android:** `@Singleton class CollectiveRouteCatalogService @Inject constructor(context, httpClient, scope, catalogUrl, bootstrapAssetPath)` exposing `catalog: StateFlow<CollectiveRouteCatalog>` (initial `CollectiveRouteCatalog.EMPTY` — the non-null stand-in for iOS's `nil`; `EMPTY.entry(...)` already returns null so pre-load lookups render nothing) and `isSyncing: StateFlow<Boolean>`. Hilt `@Singleton` replaces `.shared`; the injected `@CollectiveRouteCatalogUrl` / `@CollectiveRouteBootstrapAsset` strings replace iOS's injectable `fetchRemoteData` / `bootstrapCatalogURL` closures as the test seams. Cache file: `filesDir/collective_routes.json` (house flat-filesDir convention, vs iOS's `Application Support/CollectiveRoutes/routes.json` — D2). No injected `Json`: decode is `CollectiveRouteCatalog.decode` per U2 D6.

## C2. Initial load — cheap init, detached three-tier read, publish-if-still-unset

**iOS:**

```swift
/// Init must stay cheap: the first `.shared` touch happens on the main
/// thread during the welcome entrance (issue #42), so the cache / bootstrap
/// disk reads and JSON decodes run in a detached task and only the publish
/// hops back to main.
init(catalogDirectory: URL, bootstrapCatalogURL: @escaping () -> URL?, fetchRemoteData: ...) {
    ...
    initialLoad = Self.makeInitialLoad(service: self, localURL: localCatalogURL, bootstrapCatalogURL: bootstrapCatalogURL)
}

return Task.detached(priority: .utility) { [weak service] in
    let loaded = loadInitialCatalog(localURL: localURL, bootstrapURL: bootstrapCatalogURL())
    await MainActor.run { [service] in
        guard let service, service.catalog == nil else { return }
        service.catalog = loaded
    }
}
```

```swift
/// Cache, then bundled bootstrap. An entry-less cached file is passed over rather
/// than adopted — a build shipped before the sync guard could have written one,
/// and serving it would shadow a working bootstrap for every offline launch.
private static func loadInitialCatalog(localURL: URL, bootstrapURL: URL?) -> CollectiveRouteCatalog {
    if FileManager.default.fileExists(atPath: localURL.path),
       let data = try? Data(contentsOf: localURL),
       let saved = try? decoder.decode(CollectiveRouteCatalog.self, from: data),
       !saved.entries.isEmpty {
        return saved
    }
    guard let bootstrapURL, let data = try? Data(contentsOf: bootstrapURL),
          let bootstrap = try? decoder.decode(CollectiveRouteCatalog.self, from: data) else {
        assertionFailure("Missing collective-routes-bootstrap.json — ...")
        return .empty
    }
    return bootstrap
}
```

**Android claims:**

- `init { scope.launch { try { val loaded = withContext(Dispatchers.IO) { loadInitialCatalog() } ; publish-if-still-EMPTY } finally { _initialLoad.complete(Unit) } } }` — constructor does no I/O (Hilt-injection thread may be Main); the IO hop is the detached-task analogue. `initialLoad: Deferred<Unit>` is the template's completion signal, completed in `finally` like the template.
- Publish guard `if (_catalog.value == CollectiveRouteCatalog.EMPTY)` ports `guard service.catalog == nil` — a fetched catalog that has already been published (impossible in practice because sync awaits `initialLoad`, C4, but the guard is the same belt-and-braces iOS ships).
- Cache tier: file exists → read → `decode` succeeds → **`entries.isNotEmpty()`** — the entry-less-cache pass-over is quoted above and ported verbatim. Corrupt (undecodable) cache → same fallthrough to bootstrap; the file is left in place for the next sync's atomic rewrite (template convention — nothing eagerly deletes user data).
- Bootstrap tier: `context.assets.open(bootstrapAssetPath)` → `decode`. **No emptiness guard on the bootstrap** — iOS adopts whatever the bundle decodes to (a bad bake is caught by the bundled-artifact tests at CI time, C6), matched exactly.
- Missing/undecodable bootstrap: iOS `assertionFailure` (debug-only trap, compiled out in release) then `.empty`. Android: `Log.wtf(TAG, ...)` + `EMPTY` — `wtf` is Android's assertion-failure analogue ("should never happen"; may terminate under strict system settings, logs an assertion otherwise) and keeps the release behavior (serve nothing, don't crash a launch) identical on both build types (D3).

## C3. Lookups — main-thread semantics vs StateFlow, pre-load nulls

**iOS:**

```swift
// Reads must happen on main, because @Published state is only mutated there.
// A caller that trips the assert needs to dispatch to main first. Until the
// initial load lands these return nil, and no surface may assume otherwise.

func dailyLine(for date: Date, collectiveKm: Double?) -> String? {
    assert(Thread.isMainThread)
    return catalog?.dailyLine(for: date, collectiveKm: collectiveKm)
}

func contributionLine(for date: Date, walkKm: Double) -> String? {
    assert(Thread.isMainThread)
    return catalog?.contributionLine(for: date, walkKm: walkKm)
}
```

**Android:** `fun dailyLine(epochMillis: Long, collectiveKm: Double?, units: UnitSystem): String?` and `fun contributionLine(epochMillis: Long, walkKm: Double, units: UnitSystem): String?` delegate to `catalog.value.…`. No thread assert: iOS's main-thread rule exists because `@Published` is main-mutated; `StateFlow.value` is thread-safe by construction, so the constraint has no Android counterpart (D4). Until `initialLoad` completes both return null without blocking — `EMPTY`'s zero total weight makes `entry(...)` return null, so "no surface may assume otherwise" holds by the same mechanism as iOS's `catalog?`. Signatures carry `epochMillis`/`units` per U2 D1/D2.

## C4. syncIfNeeded — dedup, await-before-compare, the empty-catalog guard, `!=` versions

**iOS:**

```swift
func syncIfNeeded() {
    // Before the task is built, not inside it: a re-entrant call would otherwise
    // swap `syncTask` for a handle that returns at once, reading as a finished sync.
    guard !isSyncing else { return }

    syncTask = Task { @MainActor in
        isSyncing = true

        // Before the comparison, never after: a fast network response would
        // otherwise be overwritten by the bootstrap decode still in flight.
        await initialLoad?.value

        guard let data = await fetchRemoteData() else {
            isSyncing = false
            return
        }

        // Off main: this closure is @MainActor, so an inline decode would put
        // a JSON parse and the canonical sort on the main thread at launch ...
        let decode = Task.detached(priority: .utility) {
            try? Self.decoder.decode(CollectiveRouteCatalog.self, from: data)
        }
        // An empty catalog is rejected like an undecodable one: arrays are optional
        // and elements decode lossily, so a bake dropping a field only Swift needs
        // — `companyLine` — parses cleanly into nothing and would cache dark.
        guard let remote = await decode.value, !remote.entries.isEmpty else {
            isSyncing = false
            return
        }

        // Inequality rather than `>`: the version carries no ordering, so a
        // curator reverting to a prior artifact has to reach devices too.
        if catalog?.version != remote.version {
            catalog = remote
            await saveLocalCatalog(data)
        }

        isSyncing = false
    }
}
```

**This is the plan's R5 answer.** A decoded-but-empty catalog does **not** replace the current one and is never cached: `guard let remote = await decode.value, !remote.entries.isEmpty else { isSyncing = false; return }` bails before the version compare. The current catalog — cache or bootstrap, whichever the load tier produced — stays published. There is no "fall back to the bundled catalog" step in the sync path.

**Android claims:**

- Dedup: `if (!_isSyncing.compareAndSet(expect = false, update = true)) return` before `scope.launch` — the template's atomic CAS, which is the Android-correct port of iOS's before-the-task guard (iOS gets atomicity from the main actor; a plain read-then-launch leaks on Android, Stage 5-C lesson).
- Body order inside the launched coroutine, all under `try { ... } finally { _isSyncing.value = false }` (the `finally` covers every one of iOS's four explicit `isSyncing = false` exits with one construct):
  1. `initialLoad.await()` — before the fetch, never after, same stomp-prevention rationale.
  2. `fetchRemoteCatalog() ?: return@launch` — raw body `String` or null (C5).
  3. Decode via `CollectiveRouteCatalog.decode(body)` wrapped in catch-with-CE-rethrow → null; then `if (remote == null || remote.entries.isEmpty()) return@launch` — the quoted guard, verbatim semantics. Decode runs on the scope's `Dispatchers.Default`, off main by construction (iOS's `Task.detached` exists only because its closure is `@MainActor`).
  4. `if (_catalog.value.version != remote.version) { _catalog.value = remote; withContext(Dispatchers.IO) { saveLocalCatalog(body) } }` — `!=` never `>`; rollback to an older artifact applies; equal version publishes nothing and spends no disk write.
- No `syncTask` handle: iOS exposes it because "nothing in the app awaits a sync, so without a handle they could only poll for its effects" — Android tests join the injected scope's children (template pattern), so the extra surface is dropped (D5).

## C5. Fetch — URL, strict 200, cache bypass, exact bytes

**iOS:**

```swift
private static func fetchPublishedCatalog() async -> Data? {
    // Bypass URLCache: the CDN serves this with an ETag but no Cache-Control,
    // so URLSession falls back to heuristic freshness and may replay a body the
    // curator has already rolled back — on the launch where the rollback has to land.
    var request = URLRequest(url: Config.Collective.routeCatalogURL)
    request.cachePolicy = .reloadIgnoringLocalCacheData
    do {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            return nil
        }
        return data
    } catch { ... return nil }
}
```

**iOS** (`Pilgrim/Models/Config.swift@9a418e4`):

```swift
enum Collective {
    /// The route artifact `pilgrimapp.org` and both apps read, so a curator
    /// edit reaches every surface from one publish.
    static let routeCatalogURL = URL(string: "https://cdn.pilgrimapp.org/collective/routes.json")!
}
```

**iOS** (`saveLocalCatalog`):

```swift
/// Caches the exact bytes the CDN served rather than re-encoding the decoded
/// catalog: a round-trip would strip every field this app ignores today,
/// handing the next launch a thinner artifact than the one fetched.
```

**Android claims:**

- URL: `CollectiveRoutesConfig.CATALOG_URL = "https://cdn.pilgrimapp.org/collective/routes.json"` — one full literal in one place (the voice-guide double-path 404 lesson), package-local config object like `VoiceGuideConfig`, injected via `@CollectiveRouteCatalogUrl` so tests substitute MockWebServer.
- Strict `response.code == 200` — iOS is deliberately `== 200`, not "any 2xx"; ported as written (the template's `isSuccessful` is the one place U3 diverges from the template toward iOS).
- Cache bypass: `CacheControl.FORCE_NETWORK` on the request. The shared OkHttpClient has no `.cache()` installed today, so this is currently a no-op — it exists so iOS's rollback-must-land intent survives any future client gaining a cache (D6).
- The fetch returns the raw body text and `saveLocalCatalog(body)` writes those exact bytes (atomic tmp+rename per the template) — never a re-encode. This is why the service holds no `Json` and the cache write takes the string, not the decoded catalog.
- Failure family: catch `CancellationException` → rethrow; catch `Throwable` → log + null (template's mandatory CE-rethrow pattern; iOS's plain `catch` has no cancellation to leak).

## C6. Bundled bootstrap + the fixture↔bundle agreement guard

**iOS** (`UnitTests/Helpers/CollectiveArtifactFixtures.swift@9a418e4`):

```swift
/// Decoded exactly as `CollectiveRouteCatalogService` decodes it: a stock
/// `JSONDecoder` with no configuration, which is what the service holds.
func decodeCatalog(_ data: Data) throws -> CollectiveRouteCatalog { ... }

enum BundledCollectiveArtifact {
    static let resourceName = "collective-routes-bootstrap"
    ...
}
```

**iOS** (`UnitTests/CollectiveRouteBundledArtifactTests.swift@9a418e4`):

```swift
/// Every vector in `CollectiveRouteCatalogTests` is pinned against
/// `collectiveParityFixtureJSON`, an inline transcription. The file the app
/// actually reads is `Pilgrim/Support Files/collective-routes-bootstrap.json` ...
/// Nothing forces the two to agree — so a re-bake that adds an eighth entry changes the
/// shipped pool, every date it resolves, and every line a pilgrim reads, while
/// all 62 vectors keep passing against a copy that no longer describes anything.
///
/// These compare only what selection consumes. Company sentences are
/// curator-editable by design and must be free to change without failing here;
/// ids, distances, seasons and provenance are not.
```

Five iOS assertions: decodes-through-production-path into non-empty entries + non-empty version; same ids in the same order; same `km`; same `bestMonths`/`peakMonths`; same `isCosmic` filing. (iOS's sixth concern, `companyLine` render budget, lives in a different test and is out of U3 scope.)

**Android claims:**

- `app/src/main/assets/collective/collective-routes-bootstrap.json` is a **verbatim byte copy** of `Pilgrim/Support Files/collective-routes-bootstrap.json@9a418e4` (R4 — reused, not re-generated; sha1 `036bf49ada6b01ffd7bdcaaf9b1a102fc6f787aa` on both sides at port time). Version `0faeb638520c`, 7 pilgrimages + 3 horizons, `reflections`/`annual` intact — the lenient `decode` ignores them (U2 D6).
- `CollectiveRouteBundledArtifactTest` (Robolectric, `context.assets`) ports all five assertions one-for-one, comparing the asset against the U2 parity fixture (`app/src/test/resources/collective/collective-routes-parity-fixture.json`) through `CollectiveRouteCatalog.decode` — the production path, exactly as iOS's `decodeCatalog` helper pins it.

## C7. Launch registration

**iOS** (`Pilgrim/AppDelegate.swift@9a418e4`):

```swift
// Flip `.done` IMMEDIATELY so WelcomeView can render. The
// post-setup work below (recording recovery, manifest
// syncs, collective counter) is all fire-and-forget and
// does not need to gate the UI.
...
AudioManifestService.shared.syncIfNeeded()
VoiceGuideManifestService.shared.syncIfNeeded()
WhisperManifestService.shared.syncIfNeeded()
CollectiveRouteCatalogService.shared.syncIfNeeded()
Task { await CollectiveCounterService.shared.fetch() }
```

**Android:** `PilgrimApp.onCreate` (main process only — the `:tracker` process early-returns before any UI-side init) gains a `Provider<CollectiveRouteCatalogService>` and calls `.get().syncIfNeeded()` beside the Stage 8-B collective-counter warm fetch — the same launch phase iOS uses (post-setup fire-and-forget, off the UI-gating path). `Application.onCreate` fires once per process, matching the once-per-launch semantics; the CAS dedup makes any future second call a no-op. Construction is cheap (no constructor I/O, C2) so the eager `.get()` costs one object + one launched coroutine.

## Divergences (conscious) and resolved ambiguities

| # | Divergence | Reason |
|---|---|---|
| D1 | `catalog: StateFlow<CollectiveRouteCatalog>` starting `EMPTY`, vs iOS `@Published var catalog: CollectiveRouteCatalog?` starting nil. | House non-null-with-empty-sentinel convention (template's `packs` starts `emptyList()`). `EMPTY` produces the identical observable behavior (null lookups) through `entry`'s zero-weight guard. Micro-edge: a remote artifact with a literal `""` version arriving before any tier loaded would no-op on Android (`"" != ""`) where iOS's `nil != ""` applies it — unreachable (versions are content hashes, and the bootstrap tier always publishes first because sync awaits `initialLoad`). |
| D2 | Cache at `filesDir/collective_routes.json`, no subdirectory; iOS uses `Application Support/CollectiveRoutes/routes.json`. | Template convention (`voice_guide_manifest.json` is flat in filesDir). Flat file needs no `createDirectory` step; the atomic tmp+rename is the same. |
| D3 | Missing/undecodable bootstrap: `Log.wtf` + publish `EMPTY`, vs iOS `assertionFailure` + `.empty`. | `assertionFailure` traps only in debug and is compiled out in release; `Log.wtf` is the Android analogue of "assert this never happens" without making debug unit tests crash a supervisor scope. Release behavior identical: serve nothing, never crash a launch. |
| D4 | No main-thread assert on lookups. | iOS's assert guards `@Published`'s main-only mutation; `StateFlow.value` is thread-safe, so the constraint doesn't exist. U5/U6 may read from any dispatcher. |
| D5 | No `syncTask` handle exposed. | iOS exposes it purely for tests ("nothing in the app awaits a sync"). Android tests join the injected scope's children — the template's established pattern. |
| D6 | `CacheControl.FORCE_NETWORK` on the fetch request, vs iOS `.reloadIgnoringLocalCacheData`. | Same intent (a curator rollback must land, never replay a stale cached body). OkHttp has no cache installed today so it is defensive; documented so a future `.cache()` addition can't silently break rollback delivery. |
| D7 | Injectable seams are `@CollectiveRouteCatalogUrl` + `@CollectiveRouteBootstrapAsset` strings, vs iOS's `fetchRemoteData` / `bootstrapCatalogURL` closures. | Template pattern (`@VoiceGuideManifestUrl` + MockWebServer). The bootstrap seam is an asset *path* rather than content because Robolectric serves the real merged assets — tier tests assert against the shipped artifact (pinned by C6 anyway), and the missing-bootstrap case injects a nonexistent path. |
| D8 | Single `finally { _isSyncing.value = false }` replaces iOS's four explicit `isSyncing = false` exits. | Identical observable behavior on every path (failed fetch, undecodable, empty, success) plus crash/cancellation safety iOS's linear code doesn't need. |

## Test parity map

| iOS test (`UnitTests/CollectiveRouteCatalogServiceTests.swift@9a418e4`) | Android test (`CollectiveRouteCatalogServiceTest.kt`) |
|---|---|
| `testInitialLoad_withNoCachedFile_servesTheBundledBootstrap` | `init with no cache serves the bundled bootstrap` |
| `testInitialLoad_cachedFileWinsOverTheBootstrap` | `cached file wins over the bootstrap` |
| `testInitialLoad_corruptCachedFile_fallsBackToTheBootstrap` | `corrupt cached file falls back to the bootstrap` |
| — (iOS covers via the quoted `!saved.entries.isEmpty` load guard, untested) | `entry-less cached file is passed over for the bootstrap` |
| — (iOS: `assertionFailure` path, untestable under XCTest) | `missing bootstrap publishes the empty catalog without crashing` |
| `testLookupsBeforeInitialLoadCompletes_returnNothingWithoutBlocking` | `lookups before initial load return nothing without blocking` (deterministic via an unstarted `StandardTestDispatcher` standing in for iOS holding the main actor) |
| `testSync_remoteWithADifferentVersion_replacesTheCacheAndPublishes` | `sync remote with a different version replaces the cache and publishes` (asserts the cache holds the exact served bytes) |
| — (Android-only, three-tier completeness) | `sync fetched catalog wins over the bootstrap when no cache exists` |
| `testSync_remoteWithAnEqualVersion_leavesThePublishedCatalogUntouched` | `sync remote with an equal version leaves the published catalog untouched` (also: no disk write) |
| `testSync_remoteWithAnOlderVersion_stillApplies` | `sync remote with an older version still applies` |
| `testSync_failedNetworkResponse_leavesTheExistingCatalogInPlace` | `sync failed network response leaves the existing catalog in place` |
| `testSync_undecodableRemotePayload_leavesTheExistingCatalogInPlace` | `sync undecodable remote payload leaves the existing catalog in place` |
| — (iOS folds into the sync guard, untested directly; plan R5 scenario) | `sync remote decoding to zero entries keeps the current catalog` |
| `testSync_clearsTheSyncingFlagWhenItFinishes` | `sync clears the syncing flag when it finishes` |
| — (template parity) | `concurrent syncIfNeeded calls dedupe to a single request` |
| — (template parity) | `isSyncing emits true during fetch then false` |
| — (Android-only, lookup passthrough) | `lookups delegate to the published catalog` |

| iOS test (`UnitTests/CollectiveRouteBundledArtifactTests.swift@9a418e4`) | Android test (`CollectiveRouteBundledArtifactTest.kt`) |
|---|---|
| `testBundledArtifact_decodesThroughTheProductionPathIntoEntries` | `bundled artifact decodes through the production path into entries` |
| `testBundledArtifact_selectsTheSameEntriesInTheSameOrderAsTheParityFixture` | `bundled artifact selects the same entries in the same order as the parity fixture` |
| `testBundledArtifact_carriesTheSameDistancesAsTheParityFixture` | `bundled artifact carries the same distances as the parity fixture` |
| `testBundledArtifact_carriesTheSameSeasonsAsTheParityFixture` | `bundled artifact carries the same seasons as the parity fixture` |
| `testBundledArtifact_filesEachEntryUnderTheSameKindAsTheParityFixture` | `bundled artifact files each entry under the same kind as the parity fixture` |

## U5/U6 consumption notes

- Read `CollectiveRouteCatalogService.catalog: StateFlow<CollectiveRouteCatalog>` (hot, always current tier) or call the service's `dailyLine`/`contributionLine` passthroughs. Both are safe from any thread and return null until content exists — no surface may assume a catalog before `initialLoad` lands (C3), exactly iOS's contract.
- The collective total (`collectiveKm`) comes from `CollectiveRepository`'s counter stats, converted meters→km at the call site; `dailyLine(collectiveKm = null)` is the "counter never fetched" state and renders nothing (U2 B3 branch 1).
- Unit preference is the caller's job (U2 D2): pass `UnitSystem` explicitly and re-render on preference change.
- U2 D8 still stands: `CollectiveRoute`/`CollectiveRouteCatalog` carry no Compose stability annotations — add stability handling at the UI seam if they cross into a LazyList.
