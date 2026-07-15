# Port spec — U10: Notification seek glance

> **Plan:** `docs/plans/2026-07-14-001-feat-seek-mode-journal-scenery-plan.md` (U10) · **Requirements:** R4 (iOS Seek U8), R6 (Live Activity → foreground-service notification content), R11 (Robolectric builder rule)
> **iOS pin:** `pilgrim-ios` @ `c1745e8` (HEAD of `main`, verified 2026-07-14). All quotes cite `file:line@c1745e8`.
> **iOS files:** `Pilgrim/Models/Walk/Seek/SeekGlance.swift` (shared glance model), `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Seek.swift:219-236` (`currentSeekGlance`), `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:440-487` (1 Hz Live Activity loop), `Pilgrim/Models/Walk/WalkActivityManager.swift` (`shouldPush` cadence + staleDate), `PilgrimWidget/PilgrimWidgetLiveActivity.swift:196-242` (`seekGlanceBar`, `seekRingSymbol`, `seekDistanceText`), `UnitTests/Seek/SeekLiveActivityTests.swift`.
> **Android files:** `domain/seek/SeekGlanceModel.kt` (new, pure), `walk/seek/SeekOrchestrator.kt` (glance derivation + UI-side pre-throttle), `walk/WalkActionPublisher.kt` (UI→tracker transport), `di/SeekModule.kt` (publisher binding), `service/WalkTrackingService.kt` (glance intake + notify throttle), `service/WalkNotificationFactory.kt` (seek line rendering), `res/values/strings.xml`.
> **Builds on:** U3 engine spec (`…port-seek-engine-u3.md` D7 — `LocationPoint.speedMetersPerSecond`/`bearingDegrees` already carry the glance inputs; D8 — `SeekEnginePhase` lives in `SeekEngine.kt`), U9 orchestrator spec (`…port-seek-orchestrator-u9.md` "U10 seam": `enginePhase`, the engine's `distanceToActiveMeters`/`chain`/`activeIndex` StateFlows, and `latestFix` are the UI-process glance inputs; `WalkActionPublisher` is the transport precedent).

---

## B1. Shared glance model — `SeekGlance.swift` → `SeekGlanceModel.kt`

**iOS** (`Pilgrim/Models/Walk/Seek/SeekGlance.swift:8-19,35-86@c1745e8`) — compiled into both the app and the widget extension; pure Foundation:

```swift
enum SeekDirectionHint: String, Codable, CaseIterable {
    case ahead
    case left
    case right
    case behind
}

struct SeekGlanceState: Codable, Hashable {
    let distanceBucketMeters: Int
    let directionHint: SeekDirectionHint?
    let isComplete: Bool
}
...
/// Coarse lock-screen glance derived in the app process (the widget has no
/// sensors and only renders the state it is handed). Direction is relative
/// to course over ground — direction of travel, never compass (AE7).
enum SeekGlanceModel {

    static let bucketWidthMeters = 100.0
    static let maxBucketMeters = 2000
    /// Below this speed the course is stale noise, not a direction of
    /// travel — the hint hides rather than mislead (AE7).
    static let stationarySpeedFloor = 0.4
    static let aheadConeHalfAngle = 45.0
    static let behindConeHalfAngle = 135.0

    static func glance(
        distanceToActiveMeters: Double?,
        courseDegrees: Double?,
        speedMetersPerSecond: Double?,
        bearingToClearingDegrees: Double?,
        phase: SeekEnginePhase
    ) -> SeekGlanceState? {
        if phase == .complete {
            return SeekGlanceState(distanceBucketMeters: 0, directionHint: nil, isComplete: true)
        }
        guard let distance = distanceToActiveMeters else { return nil }

        var hint: SeekDirectionHint?
        if let course = courseDegrees, course >= 0,
           let speed = speedMetersPerSecond, speed >= stationarySpeedFloor,
           let bearing = bearingToClearingDegrees {
            hint = directionHint(courseDegrees: course, bearingDegrees: bearing)
        }
        return SeekGlanceState(
            distanceBucketMeters: distanceBucket(forMeters: distance),
            directionHint: hint,
            isComplete: false
        )
    }

    static func distanceBucket(forMeters meters: Double) -> Int {
        let clamped = max(0, meters)
        let bucket = Int((clamped / bucketWidthMeters).rounded(.down)) * Int(bucketWidthMeters)
        return min(bucket, maxBucketMeters)
    }

    static func directionHint(courseDegrees: Double, bearingDegrees: Double) -> SeekDirectionHint {
        let delta = normalizedDelta(from: courseDegrees, to: bearingDegrees)
        if abs(delta) <= aheadConeHalfAngle { return .ahead }
        if abs(delta) >= behindConeHalfAngle { return .behind }
        return delta > 0 ? .right : .left
    }

    private static func normalizedDelta(from course: Double, to bearing: Double) -> Double {
        ((bearing - course + 540).truncatingRemainder(dividingBy: 360)) - 180
    }
}
```

**Android claims, one-for-one** (`domain/seek/SeekGlanceModel.kt`, pure Kotlin — no framework imports, JVM-tested):

- `enum class SeekDirectionHint { AHEAD, LEFT, RIGHT, BEHIND }` — no `Codable`/raw-value (the wire form is the intent extra's enum name, B3; the display words are string resources, B5).
- `data class SeekGlanceState(distanceBucketMeters: Int, directionHint: SeekDirectionHint?, isComplete: Boolean)` — structural equality is load-bearing: it is BOTH throttle keys (UI-side publish-on-change, tracker-side notify-on-change), ≙ iOS `Hashable` equality driving `seek != lastSeekGlance`.
- `object SeekGlanceModel` with the same constants (`BUCKET_WIDTH_METERS = 100.0`, `MAX_BUCKET_METERS = 2000`, `STATIONARY_SPEED_FLOOR_METERS_PER_SECOND = 0.4`, `AHEAD_CONE_HALF_ANGLE_DEGREES = 45.0`, `BEHIND_CONE_HALF_ANGLE_DEGREES = 135.0`) and the same three functions:
  - `glance(...)`: COMPLETE phase short-circuits to `SeekGlanceState(0, null, isComplete = true)` ignoring every other input; no distance → null; the hint requires `course >= 0` (kept for parity even though the Android pipeline maps invalid courses to null — a defensive double-gate, U3 spec D7) AND `speed >= 0.4` (floor value **inclusive**, iOS `testGlanceMovingAtSpeedFloorShowsHint`) AND a bearing.
  - `distanceBucket(meters)`: clamp negatives to 0, floor to 100 m steps, cap at 2000.
  - `directionHint(course, bearing)`: |delta| ≤ 45 → AHEAD, |delta| ≥ 135 → BEHIND, else sign picks RIGHT (+) / LEFT (−). `normalizedDelta` = `((bearing − course + 540) % 360) − 180` — Kotlin `%` on Double ≙ Swift `truncatingRemainder` (the same equivalence the engine's `angleDelta` port used, U3 spec B9); `+540` keeps the dividend positive for all inputs from `atan2` degrees × [0, 360) courses.
- `REVEALING` phase (never produced, U9 spec D9) falls through the non-complete path like GUIDING/ARRIVED — same as iOS's `phase == .complete` single check.

## B2. Glance derivation — `currentSeekGlance()` → orchestrator fog-combine hook

**iOS** (`ActiveWalkViewModel+Seek.swift:216-236@c1745e8`):

```swift
/// Computed here — never in the widget, which has no sensors. The
/// direction hint is relative to course over ground; the glance model
/// hides it while stationary or when the course is invalid.
func currentSeekGlance() -> SeekGlanceState? {
    guard let engine = seekEngine else { return nil }
    var bearing: Double?
    if let sample = currentLocation,
       engine.chain.clearings.indices.contains(engine.activeIndex) {
        bearing = SeekChainGenerator.bearingDegrees(
            from: SeekPoint(latitude: sample.latitude, longitude: sample.longitude),
            to: engine.chain.clearings[engine.activeIndex].center
        )
    }
    return SeekGlanceModel.glance(
        distanceToActiveMeters: engine.distanceToActiveMeters,
        courseDegrees: currentLocation?.direction,
        speedMetersPerSecond: currentLocation?.speed,
        bearingToClearingDegrees: bearing,
        phase: engine.phase
    )
}
```

Called from the VM's 1 Hz Live Activity timer loop (`ActiveWalkViewModel.swift:440-487@c1745e8`, `Timer.TimerPublisher(interval: 1 …)` → `WalkActivityManager.shared.update(…, seek: self.currentSeekGlance())`).

**Android:** the orchestrator (UI process, U9 seam) derives the glance inside the existing fog-combine collector (`chain × activeIndex × phase × distanceToActiveMeters`) — the same emission that already recomputes fog. The combine re-fires on every processed fix (each fix publishes a distinct `distanceToActiveMeters`), so the effective cadence matches the fix rate ≈ iOS's 1 Hz loop without a second clock (D2). One nuance of keying on the distance StateFlow: a fix whose haversine distance is EXACTLY the previous value would defer a pure course/speed hint change to the next fix — unobservable in practice (jittering doubles never repeat) and pinned by the orchestrator test's stride-with-course-swing shape:

- Bearing: `chain.clearings.getOrNull(activeIndex)` + `latestFix` → `SeekChainGenerator.bearingDegrees(fix, clearing.center)` (≙ iOS `indices.contains` guard + `currentLocation`).
- Course/speed: `latestFix.bearingDegrees`/`speedMetersPerSecond` (`Float?→Double?`; U3 spec D7 mapped iOS's negative-course sentinel to null at the source — the model's `>= 0` gate stays as a defensive double-check).
- No engine → no derivation at all (wander walks and torn-down sessions publish nothing; the tracker's default is no glance — ≙ iOS `guard let engine … return nil`).

## B3. Transport — ActivityKit push → `WalkActionPublisher` intent channel (D1)

**iOS** hands the glance to `WalkActivityManager.update(…, seek:)` in-process; ActivityKit carries the `Codable` ContentState to the widget process.

**Android:** the notification renders in the `:tracker` process (`WalkNotificationFactory`), which cannot read the UI-process orchestrator (U9 spec "U10 seam"). The established UI→tracker channel is the `WalkActionPublisher` service-intent bridge (U8 added `EXTRA_WALK_MODE` the same way):

- `WalkActionPublisher.publishSeekGlance(glance: SeekGlanceState?)` fires `ACTION_UPDATE_SEEK_GLANCE` with extras:

| Extra | Type | Meaning |
|---|---|---|
| `EXTRA_SEEK_GLANCE_PRESENT` (`extra.seek_glance_present`) | Boolean | false ≙ iOS `seek: nil` (clears the tracker's stored glance — happens mid-walk right after a reveal, when `distanceToActiveMeters` is null until the next fix) |
| `EXTRA_SEEK_GLANCE_BUCKET` (`extra.seek_glance_bucket`) | Int | `distanceBucketMeters` |
| `EXTRA_SEEK_GLANCE_DIRECTION` (`extra.seek_glance_direction`) | String? | `SeekDirectionHint.name`; absent = no hint |
| `EXTRA_SEEK_GLANCE_COMPLETE` (`extra.seek_glance_complete`) | Boolean | `isComplete` |

- Delivery uses the publisher's existing `safeStartService` (background-start `IllegalStateException` logged and swallowed — a dropped glance self-heals on the next change).
- **UI-side pre-throttle:** the orchestrator publishes ONLY when the derived glance differs from the last published value (per-session `publishedGlance` + `hasPublishedGlance` latch; a session that has published nothing suppresses leading nulls). This is the cheap half of iOS's `seek != lastSeekGlance`; the tracker re-checks via its fingerprint (B4) so a duplicated intent is still a no-op.
- The orchestrator hangs onto the transport through a `fun interface SeekGlancePublisher` bound in `SeekModule` to `walkActionPublisher::publishSeekGlance` — the same test-seam pattern as `SeekSenses` (orchestrator tests record publishes without a Context).
- Tracker intake (`WalkTrackingService.handleSeekGlanceAction`): mirrors the soundscape-action guard — no live pipeline → log + `stopSelf()` (a stray intent must not leave a started-but-unpromoted service lingering); otherwise parse extras (unknown direction names collapse to null — the same forgiving-wire convention as `WalkMode.fromWire`), store `latestSeekGlance`, and re-run `updateNotification(controller.state.value)` so a changed glance surfaces immediately instead of waiting for the next state emission.
- **No teardown clear intent (D4):** the orchestrator does NOT publish on session teardown. Walk end tears the whole service down (notification removed with it), and a post-stop intent would pointlessly resurrect the service just to `stopSelf()`. The tracker's stored glance dies with the service instance (one service instance per walk — terminal states self-stop).

## B4. Push cadence — `shouldPush` → tracker notify throttle

**iOS** (`WalkActivityManager.swift:13-19,26-36,122-123@c1745e8`):

```swift
private var lastSeekGlance: SeekGlanceState?
static let distanceThreshold: Double = 15
static let timeThreshold: TimeInterval = 15
/// Seek updates arrive on ~100 m bucket changes, so the dead-process
/// net must outlive the longest plausible gap between buckets (~3 min
/// at a slow walk) — the wander 45 s net would mark live seeks stale.
static let seekStaleInterval: TimeInterval = 180
...
/// Pure gating decision: push on meaningful movement, a flag flip, a
/// changed seek glance (bucket/hint/completion — naturally coarse), or
/// the periodic floor as fallback.
static func shouldPush(
    movedMeters: Double,
    flagsChanged: Bool,
    seekGlanceChanged: Bool,
    secondsSinceLastPush: TimeInterval
) -> Bool {
    movedMeters >= distanceThreshold
        || flagsChanged
        || seekGlanceChanged
        || secondsSinceLastPush >= timeThreshold
}
...
let staleInterval = seek != nil ? Self.seekStaleInterval : Self.timeThreshold * 3
let staleDate = Date().addingTimeInterval(staleInterval)
```

**Android** (plan approach: "rebuild only when the glance state changes or ≥15 s elapsed — the bucket is the display rounding — Samsung suppression rule; wander walks unaffected"):

- The existing fingerprint throttle stays the skeleton. For **Active seek** states the fingerprint's distance component is REPLACED by the glance (packed: presence bit, bucket÷100, hint ordinal, complete flag) — the 100 m bucket IS the display rounding, exactly as the wander path's 5 m component matches its `%.2f km` rounding. State-class and units ordinal stay in the fingerprint, so pause/resume flips and mid-walk unit toggles still re-render instantly (≙ iOS `flagsChanged`).
- `SEEK_NOTIFY_FLOOR_MILLIS = 15_000L` (≙ iOS `timeThreshold = 15`): an unchanged fingerprint still notifies when ≥ 15 s have passed since the last `notify()` — this is what keeps the line's walked-distance prefix fresh (the seek fingerprint deliberately drops the 5 m distance component). Floor applies ONLY to Active seek states; wander and non-Active seek states keep today's exact behavior (regression-guarded byte-identical text).
- Pure seams (`WalkTrackingServiceDecisionTest` precedent): `notificationFingerprint(state, seekGlance, unitsOrdinal)` and `shouldNotify(fingerprint, lastFingerprint, isActiveSeek, millisSinceLastNotify)` live in the service companion, JVM-tested; the collector applies them with `SystemClock.elapsedRealtime()`.
- iOS's `movedMeters >= 15` trigger has no seek analogue on Android (D3): the walked-distance prefix refreshes on the 15 s floor instead — the seek line's own datum (the bucket) drives immediacy.
- **staleDate has no Android analogue (D5):** notifications cannot self-expire content. iOS's 180 s net exists for a dead app process; on Android a dead UI process freezes the last glance on the notification until walk end (the walk itself keeps recording in `:tracker`, U9 spec D1). Accepted: plan scope says no live-seek resume after process death; flagged for Phase 14 QA (see below). The alternative — a UI-side keep-alive republish — is explicitly out: the U10 contract is publish-on-change only.

## B5. Rendering — `seekGlanceBar` → notification content line

**iOS** (`PilgrimWidget/PilgrimWidgetLiveActivity.swift:196-242@c1745e8`):

```swift
// Static per-state rendering only: the ring glyph steps by distance
// bucket, no timers or animation in the widget process.
@ViewBuilder
private func seekGlanceBar(_ seek: SeekGlanceState, imperial: Bool) -> some View {
    HStack(spacing: 6) {
        Image(systemName: seekRingSymbol(seek))
        ...
        if seek.isComplete {
            Text("seeking complete")
        } else {
            Text(seekDistanceText(bucket: seek.distanceBucketMeters, imperial: imperial))
            if let hint = seek.directionHint {
                Text(hint.rawValue)
            }
        }
        Spacer()
    }
}

private func seekRingSymbol(_ seek: SeekGlanceState) -> String {
    if seek.isComplete { return "circle.fill" }
    switch seek.distanceBucketMeters {
    case 1000...: return "circle.dashed"
    case 300..<1000: return "smallcircle.circle"
    case 100..<300: return "target"
    default: return "smallcircle.filled.circle"
    }
}

private func seekDistanceText(bucket: Int, imperial: Bool) -> String {
    if bucket >= 2000 { return imperial ? "1.2 mi +" : "2 km +" }
    if bucket < 100 { return "close" }
    if imperial {
        return String(format: "~%.1f mi", Double(bucket) / 1609.344)
    }
    if bucket >= 1000 {
        return String(format: "~%.1f km", Double(bucket) / 1000)
    }
    return "~\(bucket) m"
}
```

**Android** (`WalkNotificationFactory`, internal helpers — the factory's existing test seam):

- `walkNotificationText` gains a defaulted `seekGlance: SeekGlanceState? = null` parameter. Only `WalkState.Active` with `walk.mode == WalkMode.Seek` AND a non-null glance renders the seek variant; every other input produces today's exact strings (wander regression-guarded by golden-string tests). A seek walk whose glance hasn't arrived (or was cleared) renders the plain Active line — ≙ iOS `if let seek = context.state.seek`.
- Line shape: `"Walking — {distance} · {glance line}"` via `walk_notification_seek_active` = `"Walking — %1$s · %2$s"` — the walked distance keeps delegating to `WalkFormat.distance` (unit fallbacks intact); `·` is the house sign-line separator (U11).
- `seekGlanceLine(context, glance, units)`: complete → `walk_notification_seek_complete` = `"seeking complete"`; else distance text + optional direction word joined with a space (≙ the HStack).
- `seekGlanceDistanceText(context, bucket, units)`, verbatim ladder: `>= 2000` → `"1.2 mi +"` / `"2 km +"`; `< 100` → `"close"` (`walk_notification_seek_close`); imperial → `String.format(Locale.US, "~%.1f mi", bucket / 1609.344)`; metric ≥ 1000 → `"~%.1f km"` (÷1000); else `"~%d m"`. Numeric-with-unit formatting stays in code with `Locale.US` (the `WalkFormat` convention); the words are resources.
- Direction words: `walk_notification_seek_direction_{ahead,left,right,behind}` = `"ahead"`, `"left"`, `"right"`, `"behind"` (≙ `hint.rawValue`).
- The **ring glyph is not ported (D6)**: the notification's small icon is the app glyph and a single content-text line has no leading-image slot; the bucket is already legible in the text. The lock-screen `publicVersion` stays title-only (house privacy shape) — iOS's lock screen IS the Live Activity, Android's public version deliberately hides walk data.
- iOS's Dynamic Island compact trailing seek distance (`PilgrimWidgetLiveActivity.swift:103-105`) has no Android surface (D6).

## B6. Strings

| Resource | Value | iOS source |
|---|---|---|
| `walk_notification_seek_active` | `Walking — %1$s · %2$s` | composition of lock-screen distance + `seekGlanceBar` line |
| `walk_notification_seek_complete` | `seeking complete` | `PilgrimWidgetLiveActivity.swift:205` |
| `walk_notification_seek_close` | `close` | `:234` |
| `walk_notification_seek_direction_ahead` | `ahead` | `SeekDirectionHint.ahead.rawValue` |
| `walk_notification_seek_direction_left` | `left` | `.left.rawValue` |
| `walk_notification_seek_direction_right` | `right` | `.right.rawValue` |
| `walk_notification_seek_direction_behind` | `behind` | `.behind.rawValue` |

Distance numerics (`~%d m`, `~%.1f km`, `~%.1f mi`, `2 km +`, `1.2 mi +`) are code-side `Locale.US` formats, matching `WalkFormat`'s hardcoded-unit precedent.

## Divergence table

| # | iOS @c1745e8 | Android U10 | Why |
|---|---|---|---|
| D1 | Glance handed in-process to `WalkActivityManager`; ActivityKit ships Codable state to the widget process | `WalkActionPublisher.publishSeekGlance` service intent (4 extras) from the UI-process orchestrator to `:tracker` | The notification renders in `:tracker`; the intent channel is the established UI→tracker bridge (U8 `EXTRA_WALK_MODE` precedent) |
| D2 | 1 Hz VM timer loop recomputes the glance every second | Derived per fog-combine emission (≈ per fix) in the orchestrator | The combine already re-fires on every distance publish; a second 1 Hz clock would add nothing but wakeups |
| D3 | `shouldPush` also fires on `movedMeters ≥ 15` | Tracker fingerprint = state class + glance + units; walked-distance prefix refreshes on the 15 s floor only | The seek line's own datum is the 100 m bucket; a 15 m distance trigger would defeat the Samsung notify-suppression budget the 5 m→glance swap buys |
| D4 | `update(seek: nil)` every second keeps pushing after teardown scenarios; `end()` freezes state | No teardown clear intent; the tracker's stored glance dies with the per-walk service instance | Walk end destroys the service + notification; a post-stop intent would resurrect the service to do nothing |
| D5 | `staleDate = now + 180 s` marks a dead app process's Live Activity stale | No analogue — a dead UI process freezes the last glance line until walk end | Notifications can't self-expire; plan scope accepts no live-seek resume after UI-process death; keep-alive republish rejected (publish-on-change contract). Phase 14 QA item |
| D6 | Ring glyph steps by bucket (`circle.dashed`/`smallcircle.circle`/`target`/`smallcircle.filled.circle`/`circle.fill`); Dynamic Island compact trailing shows seek distance | Text-only seek line; no island analogue | Single content-text line has no image slot; the bucket is legible as text |
| D7 | `SeekGlanceState`/`SeekDirectionHint` are `Codable` (ActivityKit wire) with backward-compat decode tests | Plain data class + enum; wire form is intent extras versioned with the APK | No cross-binary payload exists; `ContentState` decode tests have no analogue |
| D8 | Widget hardcodes `"seeking complete"`, `"close"`, `hint.rawValue` | String resources + code-side `Locale.US` numeric formats | House convention (notification copy in `strings.xml`, numeric+unit formatting in code per `WalkFormat`) |

## Test parity map (`UnitTests/Seek/SeekLiveActivityTests.swift@c1745e8` → Android)

| iOS test | Android home |
|---|---|
| `testDistanceBucketFloorsToHundredMeterSteps` (`:8-15`) | `SeekGlanceModelTest."distance bucket floors to hundred meter steps"` |
| `testDistanceBucketCapsAtMax` (`:17-21`) | `"distance bucket caps at max"` |
| `testDistanceBucketClampsNegativeToZero` (`:23-25`) | `"distance bucket clamps negative to zero"` |
| `testDistanceBucketIsMonotonic` (`:27-31`) | `"distance bucket is monotonic"` |
| `testDirectionHintQuadrants` (`:35-40`) | `"direction hint quadrants"` |
| `testDirectionHintConeBoundaries` (`:42-49`) | `"direction hint cone boundaries"` (45/46, 315/314, 135/134 exact) |
| `testDirectionHintIsCourseRelativeAcrossNorthWrap` (`:51-56`) | `"direction hint is course relative across north wrap"` |
| `testGlanceInvalidCourseHidesHintButKeepsDistance` (`:60-71`) | `"invalid course hides hint but keeps distance"` |
| `testGlanceStationarySpeedHidesHintButKeepsDistance` (`:73-83`) | `"stationary speed hides hint but keeps distance"` (+ Android-only 0.39/0.41 bracketing) |
| `testGlanceMovingAtSpeedFloorShowsHint` (`:85-94`) | `"moving at the exact speed floor shows hint"` |
| `testGlanceCompletePhaseIgnoresDistanceInputs` (`:96-106`) | `"complete phase ignores distance inputs"` |
| `testGlanceWithoutDistanceReturnsNil` (`:108-117`) | `"without distance returns null"` |
| `testGlanceWithAllNilInputsReturnsNil` (`:119-128`) | `"all null inputs return null"` |
| `testShouldPushOnSeekGlanceChangeAlone` (`:132-139`) | `WalkTrackingServiceSeekGlanceTest."glance change alone forces a notify"` |
| `testShouldNotPushWhenNothingChangedInsideFloor` (`:141-148`) | `"identical glance inside the floor does not notify"` |
| `testShouldPushOnExistingTriggers` (`:150-169`) | `"state class and units changes notify"` + `"the floor forces a notify"` |
| `testGlanceEqualityMatchesGatingExpectations` (`:171-183`) | `"fingerprint distinguishes bucket hint and completion"` (equality exercised through the packed fingerprint) |
| `testContentStateDecodesPreSeekFixtureWithoutSeekKey` / `…EncodesWithoutSeekKey` / `…RoundTripsSeekGlance` (`:187-243`) | intent round-trip: `"publishSeekGlance round-trips through the service intent"` (Robolectric captured intent → `seekGlanceFromExtras`); Codable back-compat has no analogue (D7) |
| — (Android-only) | `WalkNotificationFactoryTest` seek-line goldens (metric + imperial ladder, wander byte-identical, mode gate, `.build()` with actions per R11); `SeekOrchestratorTest` publish-on-change pre-throttle + terminal completion glance + wander-publishes-nothing |

## Device QA additions (Phase 14 consolidated pass)

1. Pocket/screen-off seek walk ≥ 10 min: notification's seek line keeps stepping buckets (glance intents land in `:tracker` with the UI backgrounded; UID-level FGS entitlement, U9 D1).
2. Samsung/OnePlus notify-suppression: confirm bucket steps render within a beat on One UI/OxygenOS (the glance-change + 15 s floor cadence stays under vendor update budgets — this is the rule the throttle exists for).
3. Force-stop the UI process mid-seek: walk keeps recording; the notification keeps its LAST glance line frozen (D5 — verify this reads acceptably; if not, follow-up = 60 s keep-alive republish + 180 s tracker-side stale drop).
4. Unit toggle mid-seek (Settings → Imperial): seek line re-renders immediately in miles.
5. Completion: "seeking complete" appears on the notification with the completion bowl and persists until walk end; wander walk after a seek walk shows no seek residue.
