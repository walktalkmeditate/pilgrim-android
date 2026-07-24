# Collective Contribution Ledger (U4) — iOS → Android Port Spec

> **Plan:** `docs/plans/2026-07-23-001-feat-ios-v190-parity-port-plan.md` (U4) · **Requirements:** R3
> **iOS pin:** `pilgrim-ios` @ `9a418e4` (HEAD of `main`, 2026-07-21). All Swift quotes cite `file@9a418e4`. Feature anchors: `ce7a750 feat(collective): a contributed walk ends among real pilgrims`, `b6ec2f0 test(collective): … cover the contribution ledger`; the class was later reshaped by `8c83c36`/`8b33711` refactors — the pin state below is the end state and is what Android ports.
> **Authority:** SHIPPED Swift over plan docs. **One material plan-vs-Swift correction (D1):** the plan says the ledger "entry carries the walk-start UTC date". The shipped ledger stores **only walk UUID strings** — no date, no per-entry payload of any kind. The walk-start date anchor the plan describes is real, but it lives on the walk row (`walk.startDate`) and is applied at **read time** by the summary, never persisted in the ledger. Android matches shipped Swift: UUIDs only.
> **Android files:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/routes/ContributionLedger.kt` (new), `data/collective/CollectiveRepository.kt` + `CollectiveCounterDelta.kt` (snapshot gains `walkUuid`) + `CollectiveQualifiers.kt`, `walk/WalkFinalizationObserver.kt` (call site passes the uuid — Android analogue of `ce7a750`'s `MainCoordinatorView` change), provider in `di/CollectiveModule.kt`; tests `data/collective/routes/ContributionLedgerTest.kt` + extensions to `CollectiveRepositoryTest.kt` / `WalkFinalizationObserverTest.kt`.
> **Android templates:** `data/collective/CollectiveCacheStore.kt` (qualified DataStore + JSON-string values + `ReplaceFileCorruptionHandler`), `data/seek/DataStoreSeekPreferencesRepository.kt` (iOS-verbatim storage keys).

## L1. Storage contract — one key, an ordered array of UUID strings

**iOS** (`Pilgrim/Models/Collective/CollectiveContributionLog.swift@9a418e4`, whole class):

```swift
/// Remembers which walks actually moved the collective counter. The summary's line
/// is a claim about one walk's past, so it cannot read the live contribution
/// preference: toggling off would erase a true line, on would fabricate one.
final class CollectiveContributionLog {

    /// Roughly three years of daily walking. Past it the oldest identifiers fall off
    /// and those summaries lose their line — newest wins, as the journal reads recent-first.
    static let capacity = 1_000

    private let key = "collectiveContributedWalkUUIDs"
    private let defaults: UserDefaults

    func wasContributed(walkUUID: String) -> Bool {
        contributedUUIDs.contains(walkUUID)
    }

    /// Idempotent: a walk re-recorded after a retry keeps its original position rather than evicting an unrelated walk.
    func record(walkUUID: String) {
        var uuids = contributedUUIDs
        guard !uuids.contains(walkUUID) else { return }

        uuids.append(walkUUID)
        if uuids.count > Self.capacity {
            uuids.removeFirst(uuids.count - Self.capacity)
        }
        defaults.set(uuids, forKey: key)
    }

    /// Insertion-ordered, which is what makes the eviction above drop the oldest walk rather than an arbitrary one.
    private var contributedUUIDs: [String] {
        defaults.array(forKey: key) as? [String] ?? []
    }
}
```

The iOS tests pin the key literal independently of the type (`UnitTests/CollectiveTrailSectionTests.swift@9a418e4`): *"Spelled out rather than read off the type. If the storage contract moves, this should fail rather than follow it — a renamed key orphans every already-recorded walk on every installed device."*

**Android claims:**
- Key **verbatim**: `stringPreferencesKey("collectiveContributedWalkUUIDs")` (house rule, same as seek prefs).
- Value: JSON-encoded `List<String>` under that single key. DataStore Preferences has no ordered-array primitive (`stringSetPreferencesKey` is unordered, which would break oldest-first eviction), so the house JSON-string-value pattern from `CollectiveCacheStore` carries the order.
- Own DataStore file `collective_contribution_log`, **not** the `collective_counter` file: the counter cache's corruption-reset comment declares its contents "forward-recoverable on next fetch" — ledger entries are historical facts that cannot be reconstructed, so they get their own corruption blast radius. (Adaptation; iOS keeps both in `UserDefaults.standard`, which has no per-domain corruption failure mode. See D3.)
- Wrong-shape/garbage value decodes to the empty list (iOS `as? [String] ?? []`), CancellationException re-thrown.
- API mirrors iOS: `suspend fun wasContributed(walkUuid: String): Boolean`, `suspend fun record(walkUuid: String)`, `CAPACITY = 1_000`.

## L2. Capacity and eviction — oldest falls off, retries evict nothing

From L1's quote: capacity `1_000`; append at the tail; overflow removes from the **front** (insertion order = age order); the `contains` guard runs **before** the append, so re-recording a known walk both keeps its original position and — at capacity — evicts nothing (pinned by `testRecord_atCapacity_repeatingAKnownWalkEvictsNothing@9a418e4`).

**Android claims:** identical algorithm inside a single `dataStore.edit { }` block (read-decide-write atomic, Stage 3-D TOCTOU rule): `if (walkUuid in uuids) return@edit`, append, `takeLast(CAPACITY)`.

## L3. The single decision point — ledger truth is "opted-in and queued", never "POST succeeded"

**iOS** (`CollectiveCounterService.swift@9a418e4`, `recordWalk`):

```swift
func recordWalk(walkUUID: UUID?, distanceKm: Double, meditationMin: Int, talkMin: Int) {
    guard UserPreferences.contributeToCollective.value else { return }

    // Past the preference gate this walk's distance belongs to the
    // collective's ledger: the pending delta is written below and survives
    // a failed POST to ride along with the next one. Recording the fact
    // here rather than on POST success is what lets a walk that ended with
    // no signal still say something true about the collective.
    if let walkUUID {
        contributionLog.record(walkUUID: walkUUID.uuidString)
    }

    DispatchQueue.main.async {
        var pending = self.loadPending()
        pending.walks += 1
        ...
```

One preference read gates **both** the ledger write and the pending-delta merge — that is what makes the ledger and the counter unable to disagree under toggle races. The test-suite doc comment states the toggle semantics (`CollectiveCounterServiceRecordWalkTests@9a418e4`): *"A pilgrim who contributes a walk and later turns the toggle off must keep the line on that walk; one who turns it on afterwards must not gain a line on walks that were never sent."* And: *"The toggle is read once, when the walk ends."*

**Android claims:**
- The ledger write goes in `CollectiveRepository.recordWalk` immediately after the **existing** `if (!cacheStore.optInFlow.first()) return@launch` gate and before `mutatePending` — same one read, no second opt-in read added.
- Truth condition: opted-in at finalize + delta queued. POST outcome (`Success`/`RateLimited`/`Failed`) is never consulted for the ledger.
- A ledger write failure (DataStore `IOException` — a failure mode `UserDefaults.set` doesn't have) is logged and swallowed so the pending merge + POST still run; CancellationException re-thrown. Losing the summary line must not lose the contribution (D4).

## L4. Nil identifier — queue the distance, claim nothing

**iOS** (`CollectiveCounterServiceTests.swift@9a418e4`):

```swift
/// The nil case is reachable: `MainCoordinatorView` passes `snapshot.uuid`,
/// which is whatever `DataManager.saveWalk` handed back, so a save that
/// reported success without a usable object arrives here as nil.
///
/// The split is deliberate in the safe direction rather than accidental. The
/// distance is real and belongs to the collective's ledger, so it is queued;
/// but with no identifier there is nothing a summary could match against, so
/// no claim is recorded. The walk counts and stays silent — the app
/// under-claims rather than showing a line it cannot substantiate.
func testRecordWalk_withoutAWalkIdentifier_queuesTheDistanceButClaimsNothing() async {
```

**Android claims:** `CollectiveWalkSnapshot` gains `walkUuid: String?` (first parameter, mirroring the iOS signature `recordWalk(walkUUID:distanceKm:meditationMin:talkMin:)`; no default, so every call site decides). `null` → no ledger write, delta still queued. The nil case is reachable on Android too: `WalkFinalizationObserver` resolves the uuid via `repository.getWalk(walkId)?.uuid`, and a lookup failure degrades to `null` (under-claim) rather than dropping the POST.

## L5. Ordering — the claim is readable before the caller moves on

**iOS** (`CollectiveCounterServiceTests.swift@9a418e4`): the ledger write is synchronous, **before** the `DispatchQueue.main.async` that merges the pending delta — *"it calls `recordWalk` and then walks the pilgrim toward a summary that reads this log. Nothing awaits anything in between. If the write moved into the `DispatchQueue.main.async` below it … the summary could open first and read a walk that just contributed as one that did not."*

**Android adaptation (D5):** `recordWalk` is fire-and-forget on `@CollectiveRepoScope`, and the summary reads the ledger through a **suspending** DataStore read — the read naturally queues behind the in-flight write on the store's single-writer actor, so the iOS same-turn guarantee translates to "ledger write precedes the pending merge inside the one recordWalk coroutine, and readers suspend rather than snapshot". The write stays *first* in the coroutine body to preserve the iOS ordering intent under any future refactor of the POST path.

## L6. Read surface — by walk UUID; the date anchor is the walk row's start date, read at render time

**iOS** (`Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift@9a418e4`):

```swift
walkWasContributed = walk.uuid.map { contributionLog.wasContributed(walkUUID: $0.uuidString) } ?? false
```

```swift
// The walk's own start date, never `Date()`. This screen opens for any
// walk in the journal, so anchoring to today would silently re-route a
// walk from last month every time it is reopened.
collectiveContributionLine = catalog?.contributionLine(
    for: walk.startDate,
    walkKm: walk.distance / 1000
)
```

And the render gate (`CollectiveTrailSection.swift@9a418e4`): `static func renderedLine(wasContributed: Bool, contributionLine: String?) -> String? { wasContributed ? contributionLine : nil }`.

The midnight/UTC-day behavior is pinned on the **catalog resolution**, not the ledger (`CollectiveTrailSectionDateAnchorTests@9a418e4`, `testLine_holdsAcrossTheWholeUtcDayOfTheWalk`): the walk's own UTC day decides the entry, wherever the pilgrim is and whenever the walk is reopened. A walk that starts before UTC midnight and finalizes after still anchors to its **start** date because `walk.startDate` is what the summary passes — finalize time and ledger contents play no role.

**U6 consumption contract (Android):**
- Query: `contributionLedger.wasContributed(walk.uuid)` — walk UUID string from the `walks.uuid` column, loaded with the walk the summary already has.
- Date anchor: `Instant.ofEpochMilli(walk.startTimestamp)` → UTC day → `catalog.contributionLine(...)` (U2 API). Nothing about the anchor is read from the ledger.
- Gate: pure function of `(wasContributed, resolvedLine)`; contributed-but-catalog-not-loaded renders nothing; the line never needs the collective total.

## L7. Toggle-timing matrix

Derived from L3's single-read semantics + the iOS tests quoted there. "Queued" = the pending delta got the walk (POST outcome irrelevant).

| opt-in at finalize | opt-in after | ledger entry | queued | iOS pin |
|---|---|---|---|---|
| ON | stays ON | yes | yes | `testRecordWalk_whileContributing_recordsTheWalkAgainstTheLog` |
| ON | flipped OFF | yes (persists) | yes | `testRecordWalk_contributionSurvivesTheToggleBeingTurnedOffLater` |
| OFF | stays OFF | no | no | `testRecordWalk_whileNotContributing_recordsNothingAndQueuesNothing` ("no trace to be sent later … the privacy claim the toggle makes") |
| OFF | flipped ON | no (no back-fill) | no | first-time opt-in is forward-looking only (existing Android `CollectiveRepository` doc + iOS single-read design) |

In every cell the ledger agrees with what was actually queued — the invariant U4's repository tests assert.

## Audit — walk-entity / share-payload surface (plan escalation check)

`git log c1745e8..9a418e4 -- Pilgrim/Models/Walk.swift` → **empty**. `git log c1745e8..9a418e4 -- Pilgrim/Models/Data/PilgrimPackage Pilgrim/Models/Share` → **empty**. The ledger commits (`ce7a750`, `b6ec2f0`, refactors `8c83c36`/`8b33711`) touch only the collective models, the summary scene, the coordinator call site, and tests. The ledger reads `walk.uuid` and `walk.startDate`, both pre-existing. **No walk-entity fields, no share-payload fields — device-local store only.** No `PilgrimPackageConverter` work joins U4; no escalation.

## Divergences (conscious) and resolved ambiguities

- **D1 (plan corrected by shipped Swift):** the ledger stores walk **UUIDs only** — no walk-start UTC date is persisted anywhere in the ledger. The plan's "entry carries the walk-start UTC date" is realized at read time from `walk.startDate` (Android: `walks.start_timestamp`), which the summary already loads. Storing a date would be drift, and would create a second source of truth that could disagree with the walk row.
- **D2 (value encoding):** iOS stores a native `[String]` plist array; Android stores the same list JSON-encoded in one string preference, because DataStore Preferences has no ordered-list type and `Set<String>` would break the oldest-first eviction. Shape pinned by test.
- **D3 (storage file):** own DataStore file (`collective_contribution_log`) with `ReplaceFileCorruptionHandler { emptyPreferences() }`, rather than the `collective_counter` file — corruption of the forward-recoverable counter cache must not erase unreconstructable history. iOS has no analogous split because `UserDefaults.standard` has no per-file corruption handler.
- **D4 (write-failure isolation):** DataStore writes can throw where `UserDefaults.set` cannot; the ledger write is individually try/caught (CE re-thrown) so a failed claim never drops the queued contribution.
- **D5 (ordering):** iOS guarantees the log is readable on the same main-queue turn; Android's suspending reads against the DataStore single-writer actor give the equivalent no-stale-read property (L5).
- **Capacity trim:** `takeLast(CAPACITY)` ≡ iOS `removeFirst(count - capacity)` — both keep the newest `capacity` entries in order.

## Test parity map

| iOS test @9a418e4 | Android test |
|---|---|
| `testWasContributed_unrecordedWalk_isFalse` | `ContributionLedgerTest.wasContributed is false for an unrecorded walk` |
| `testRecord_survivesARoundTripThroughANewInstance` | `record survives a round trip through a new instance` |
| `testRecord_doesNotVouchForWalksItNeverSaw` | `record does not vouch for walks it never saw` |
| `testRecord_isIdempotent` (+ key literal pin) | `record is idempotent and stores a JSON string-array under the verbatim iOS key` |
| `testRecord_usesASingleKeyRatherThanOnePerWalk` | same test (single key asserted via stored-shape decode) |
| `testRecord_atCapacity_dropsTheOldestIdentifier` (seeded, one write) | `record at capacity drops the oldest identifier` (seeded) |
| `testRecord_atCapacity_repeatingAKnownWalkEvictsNothing` | `record at capacity repeating a known walk evicts nothing` |
| — (UserDefaults can't corrupt) | `corrupted datastore file is replaced with empty preferences without crashing`, `garbage value under the key reads as empty and is recoverable` |
| `testRecordWalk_whileContributing_recordsTheWalkAgainstTheLog` | `CollectiveRepositoryTest.recordWalk opted-in records the walk against the ledger (matrix ON→ON)` |
| `testRecordWalk_whileNotContributing_recordsNothingAndQueuesNothing` | `recordWalk opt-in OFF …` extended with ledger assertion (matrix OFF→OFF) |
| `testRecordWalk_contributionSurvivesTheToggleBeingTurnedOffLater` | `ledger entry survives the toggle being turned off later (matrix ON→OFF)` |
| — (single-read design) | `opting in after finalize does not back-fill the ledger (matrix OFF→ON)` |
| stubbed-to-fail `postDelta` in every recordWalk test | `ledger records even when the POST fails` |
| `testRecordWalk_withoutAWalkIdentifier_queuesTheDistanceButClaimsNothing` | `recordWalk with null walkUuid queues the delta but claims nothing` |
| (date anchor is U6's: `CollectiveTrailSectionDateAnchorTests`) | `WalkFinalizationObserverTest.midnight-spanning walk is claimed by uuid` — ledger stores no date, so the start-date anchor survives a post-midnight finalize by construction; resolution pinned in U6 |
