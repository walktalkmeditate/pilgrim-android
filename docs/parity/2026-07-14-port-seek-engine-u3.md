# Seek Engine / Stillness Detector (U3) — iOS → Android Port Spec

> **Plan:** `docs/plans/2026-07-14-001-feat-seek-mode-journal-scenery-plan.md` (U3) · **Requirement:** R4 (iOS Seek U2)
> **iOS pin:** `pilgrim-ios` @ `c1745e8` (HEAD of `main`, 2026-07-14). All quotes cite `file:line@c1745e8`.
> **Android files:** `app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/{SeekEngine,SeekStillnessDetector}.kt` (pure Kotlin — NO Android framework imports), `app/src/main/java/org/walktalkmeditate/pilgrim/power/SeekPowerTierSource.kt` (the only Android-touching piece), a nullable-bearing field on `domain/LocationPoint` populated by `location/FusedLocationSource`, + mirrored tests.
> **Builds on U2:** `docs/parity/2026-07-14-port-seek-chain-u2.md` — `SeekTuning.REROLL_MIN_BUDGET_METERS` already exists there (spec D5); the engine references it and MUST NOT redeclare it.

## B1. Events

**iOS** (`Pilgrim/Models/Walk/Seek/SeekEngine.swift:8-15@c1745e8`):

```swift
enum SeekEngineEvent: Equatable {
    case pulse(aligned: Bool, distanceMeters: Double)
    case arrived(clearingIndex: Int)
    case stillnessBegan(clearingIndex: Int)
    /// Fired for both stillness and grace reveals.
    case revealedNext(activeIndex: Int)
    case seekComplete
}
```

**Android:** `sealed class SeekEngineEvent` with `Pulse(aligned: Boolean, distanceMeters: Double)`, `Arrived(clearingIndex: Int)`, `StillnessBegan(clearingIndex: Int)`, `RevealedNext(activeIndex: Int)`, `SeekComplete` (object). Emitted on a hot `SharedFlow` (≙ iOS `PassthroughSubject`, `SeekEngine.swift:49,57`); house precedent `ProximityDetectionService._events` (`MutableSharedFlow(extraBufferCapacity = …)`).

## B2. Phases

**iOS** (`Pilgrim/Models/Walk/Seek/SeekGlance.swift:21-30@c1745e8`):

```swift
/// Declared here rather than SeekEngine.swift so the widget-shared file is
/// self-contained; the engine (app target only) references it freely.
enum SeekEnginePhase: Equatable {
    case guiding
    case arrived
    /// Reserved for the reveal ritual choreography (U7); the engine commits
    /// reveals atomically and never parks here itself.
    case revealing
    case complete
}
```

**Android:** `enum class SeekEnginePhase { GUIDING, ARRIVED, REVEALING, COMPLETE }`, declared in `SeekEngine.kt` (D8 — Android has no widget extension process split for seek; R6 maps the Live Activity to the foreground-service notification in-process). `REVEALING` is ported as the same reserved value: the engine never enters it itself.

Initial phase (`SeekEngine.swift:92`): `chain.clearings.isEmpty ? .complete : .guiding`.

## B3. Tuning

**iOS** (`SeekEngine.swift:19-36@c1745e8`):

```swift
enum SeekEngineTuning {
    static let farDistanceMeters = 2000.0
    static let nearDistanceMeters = 100.0
    static let farPulseInterval: TimeInterval = 60
    static let nearPulseInterval: TimeInterval = 10
    static let lowPowerPulseFloor: TimeInterval = 30
    static let alignmentConeDegrees = 60.0
    static let headingWindowSeconds: TimeInterval = 15
    static let arrivalFixCount = 3
    static let arrivalAccuracyMeters = 50.0
    static let graceSeconds: TimeInterval = 240
    static let stillnessWindowRange = 45.0...90.0
    static let stillnessCheckInterval: TimeInterval = 5
    static let rerollMinBudgetMeters = SeekTuning.minStartDistanceMeters * 2.5
}
```

**Android:** `object SeekEngineTuning` in `SeekEngine.kt`, same values, SCREAMING_SNAKE, time values in **millis Long** (house `Clock.now(): Long` convention): `FAR_DISTANCE_METERS=2000.0`, `NEAR_DISTANCE_METERS=100.0`, `FAR_PULSE_INTERVAL_MILLIS=60_000L`, `NEAR_PULSE_INTERVAL_MILLIS=10_000L`, `LOW_POWER_PULSE_FLOOR_MILLIS=30_000L`, `ALIGNMENT_CONE_DEGREES=60.0`, `HEADING_WINDOW_MILLIS=15_000L`, `ARRIVAL_FIX_COUNT=3`, `ARRIVAL_ACCURACY_METERS=50.0`, `GRACE_MILLIS=240_000L`, `STILLNESS_WINDOW_RANGE_SECONDS=45.0..90.0`, `STILLNESS_CHECK_INTERVAL_MILLIS=5_000L`. `rerollMinBudgetMeters` is **NOT** ported here — U2 already hosts it as `SeekTuning.REROLL_MIN_BUDGET_METERS` (U2 spec D5, honoring iOS's own "single source" comment at `SeekEngine.swift:32-35`); the engine references that constant.

## B4. Closeness curve

**iOS** (`SeekEngine.swift:183-190@c1745e8`):

```swift
/// 0 far → 1 near, on the same clamp the cadence uses — ping volume and
/// haptic intensity share this curve so ear and skin agree.
static func closeness(forDistanceMeters meters: Double) -> Double {
    let near = SeekEngineTuning.nearDistanceMeters
    let far = SeekEngineTuning.farDistanceMeters
    let clamped = min(max(meters, near), far)
    return 1 - (clamped - near) / (far - near)
}
```

**Android:** companion `fun closeness(distanceMeters: Double): Double` — identical clamp-and-invert. Pinned: 2000→0, 5000→0, 100→1, 40→1, 1050→0.5 (iOS `testCloseness_sharesTheCadenceCurve`, `UnitTests/Seek/SeekEngineTests.swift:469-475`).

## B5. Pulse interval + power tier semantics

**iOS** (`SeekEngine.swift:192-208@c1745e8`):

```swift
static func pulseInterval(
    forDistance meters: Double,
    tier: WalkSessionGuard.PowerTier
) -> TimeInterval {
    let near = SeekEngineTuning.nearDistanceMeters
    let far = SeekEngineTuning.farDistanceMeters
    let clamped = min(max(meters, near), far)
    let fraction = (clamped - near) / (far - near)
    let interval = SeekEngineTuning.nearPulseInterval
        + fraction * (SeekEngineTuning.farPulseInterval - SeekEngineTuning.nearPulseInterval)
    switch tier {
    case .low, .critical:
        return max(interval, SeekEngineTuning.lowPowerPulseFloor)
    case .normal, .meditation:
        return interval
    }
}
```

**Android:** companion `fun pulseIntervalMillis(distanceMeters: Double, tier: SeekPowerTier): Long` — linear 10 s @ ≤100 m → 60 s @ ≥2000 m; `LOW` applies the 30 s floor; `NORMAL` does not. Pinned points (iOS tests `SeekEngineTests.swift:129-148`): 3000→60 s, 2000→60 s, 100→10 s, 40→10 s, monotone non-increasing as distance falls; low tier @100 m→30 s, low @2000 m→60 s, meditation @100 m→10 s (on Android: `NORMAL` @100 m→10 s — see D1).

**iOS tier source** (`Pilgrim/Models/Walk/WalkSessionGuard.swift:42-46,217-244@c1745e8`):

```swift
enum PowerTier: Equatable, CustomStringConvertible {
    case normal
    case meditation
    case low
    case critical
...
let newTier: PowerTier
if (batteryLevel >= 0 && batteryLevel <= 0.05) || thermalState == .serious || thermalState == .critical {
    newTier = .critical
} else if batteryLevel >= 0 && batteryLevel <= 0.20 {
    newTier = .low
} else if isMeditating {
    newTier = .meditation
} else {
    newTier = .normal
}
```

The guard exposes it as an observation-only publisher (`WalkSessionGuard.swift:20-23`): "Announces tier changes to consumers that adapt their own cadence (seek pulse clock). Observation only — GPS power stays routed through this guard exclusively (AF14)." The engine mirrors it as an input: `private(set) var currentTier … = .normal` (`SeekEngine.swift:51`), `handleTier` stores it and reschedules only when a timer is live (`SeekEngine.swift:410-415`):

```swift
private func handleTier(_ tier: WalkSessionGuard.PowerTier) {
    currentTier = tier
    if pulseTimer?.isValid == true {
        schedulePulse()
    }
}
```

**Android:** `enum class SeekPowerTier { NORMAL, LOW }` in `SeekEngine.kt` (pure Kotlin, engine-consumable). Producer `power/SeekPowerTierSource.kt` — `@Singleton`, `callbackFlow` from `PowerManager.isPowerSaveMode` seeded at collection + `ACTION_POWER_SAVE_MODE_CHANGED` broadcast receiver, `distinctUntilChanged()`, receiver unregistered on flow cancellation. See D1 for the tier-collapse divergence. Engine's `handleTier` port: store tier; reschedule only if `pulseJob?.isActive == true` (canonical `Job` guard per house memory).

## B6. Pulse clock — one-shot rescheduling with generation invalidation

**iOS** (`SeekEngine.swift:52,212-253@c1745e8`):

```swift
private(set) var pulseGeneration = 0
...
func pulseTimerFired(generation: Int) {
    guard generation == pulseGeneration else { return }
    emitPulse()
    schedulePulse()
}

func emitPulse() {
    guard phase == .guiding, !isSuspended,
          let distance = distanceToActiveMeters ?? rerollPulseDistance else { return }
    eventsSubject.send(.pulse(aligned: isAligned, distanceMeters: distance))
}

private func ensurePulseScheduled() {
    guard pulseTimer?.isValid != true else { return }
    schedulePulse()
}

private func schedulePulse() {
    pulseGeneration += 1
    let generation = pulseGeneration
    pulseTimer?.invalidate()
    pulseTimer = nil
    guard phase == .guiding, !isSuspended,
          let distance = distanceToActiveMeters ?? rerollPulseDistance else { return }
    var interval = Self.pulseInterval(forDistance: distance, tier: currentTier)
    ...
    let timer = Timer(timeInterval: interval, repeats: false) { [weak self] _ in
        self?.pulseTimerFired(generation: generation)
    }
    RunLoop.main.add(timer, forMode: .common)
    pulseTimer = timer
}

private func invalidatePulseTimer() {
    pulseGeneration += 1
    pulseTimer?.invalidate()
    pulseTimer = nil
}
```

**Android claims, one-for-one:** the runloop `Timer` becomes a coroutine `Job` (`pulseJob = scope.launch { delay(intervalMillis); pulseTimerFired(generation) }`). `pulseGeneration` increments on EVERY `schedulePulse` AND on `invalidatePulseTimer`; a fired callback whose generation is stale no-ops (pinned by iOS `testSeekAnew_…stalePulsesNoOp`, `SeekEngineTests.swift:308-329`). `emitPulse` guards: phase GUIDING, not suspended, distance available from `distanceToActiveMeters ?: rerollPulseDistance`. `ensurePulseScheduled` only schedules when no live job (`pulseJob?.isActive == true` short-circuit). The first fix arms the heartbeat via `updateArrivalDebounce → ensurePulseScheduled` — iOS field regression `testFirstFix_armsThePulseHeartbeat` (`SeekEngineTests.swift:422-438`: "device walk was silent — no production path ever scheduled the first pulse timer").

## B7. Location intake

**iOS** (`SeekEngine.swift:257-276@c1745e8`):

```swift
func processLocation(_ location: CLLocation) {
    guard !isSuspended, phase == .guiding || phase == .arrived else { return }
    lastCoordinate = location.coordinate
    recordCourse(of: location)
    guard let active = activeClearing else { return }
    let center = CLLocation(latitude: active.center.latitude, longitude: active.center.longitude)
    let distance = location.distance(from: center)
    distanceToActiveMeters = distance
    rerollPulseDistance = nil

    switch phase {
    case .guiding:
        updateArrivalDebounce(location: location, distance: distance, radius: active.radiusMeters)
    case .arrived:
        stillnessDetector?.recordLocation(location)
        evaluateStillness(at: now())
    case .revealing, .complete:
        break
    }
}
```

**Android:** `internal fun processLocation(point: LocationPoint)` (internal ≙ iOS test seam), identical guard/order: record coordinate + course, compute distance to active center (via `SeekChainGenerator.distance` on `SeekPoint`s — see D2), publish it, clear `rerollPulseDistance`, then branch. A fresh fix always supersedes the stale reroll distance.

## B8. Arrival debounce

**iOS** (`SeekEngine.swift:284-313@c1745e8`):

```swift
/// Fixes worse than the accuracy gate neither advance nor reset the
/// consecutive count — a momentary multipath fix must not erase honest
/// progress toward arrival, and must never fake it either.
private func updateArrivalDebounce(location: CLLocation, distance: Double, radius: Double) {
    let accuracy = location.horizontalAccuracy
    guard accuracy >= 0, accuracy <= SeekEngineTuning.arrivalAccuracyMeters else {
        ensurePulseScheduled()
        return
    }
    consecutiveInsideCount = distance <= radius ? consecutiveInsideCount + 1 : 0
    if consecutiveInsideCount >= SeekEngineTuning.arrivalFixCount {
        transitionToArrived()
    } else {
        ensurePulseScheduled()
    }
}

private func transitionToArrived() {
    phase = .arrived
    consecutiveInsideCount = 0
    invalidatePulseTimer()
    let baseWindow = stillnessWindowOverride
        ?? Double.random(in: SeekEngineTuning.stillnessWindowRange)
    let detector = SeekStillnessDetector(motion: motionProvider, windowDuration: baseWindow)
    detector.start()
    stillnessDetector = detector
    graceDeadline = now().addingTimeInterval(SeekEngineTuning.graceSeconds)
    startStillnessCheckTimer()
    eventsSubject.send(.arrived(clearingIndex: activeIndex))
}
```

**Android claims, one-for-one:** 3 consecutive good in-region fixes → arrived. Accuracy gate `0 ≤ accuracy ≤ 50` — iOS's negative-accuracy sentinel maps to Android's `horizontalAccuracyMeters == null` (absent), which likewise neither advances nor resets AND still keeps the pulse armed. A good fix outside the region resets to 0. Arrival is one-way per clearing (nothing ever transitions ARRIVED→GUIDING except reveal or reroll). `transitionToArrived` order preserved: phase flip, count reset, pulse invalidated, detector created (window = override ?: uniform 45–90 s) and started, grace deadline `now + 240 s`, 5 s check timer started, `Arrived(activeIndex)` emitted LAST.

## B9. Alignment

**iOS** (`SeekEngine.swift:424-463@c1745e8`):

```swift
private var isAligned: Bool {
    guard let coordinate = lastCoordinate,
          let active = activeClearing,
          let heading = Self.smoothedHeading(of: courseSamples.map { $0.course }) else {
        return false
    }
    let bearing = SeekChainGenerator.bearingDegrees(
        from: SeekPoint(latitude: coordinate.latitude, longitude: coordinate.longitude),
        to: active.center
    )
    return abs(Self.angleDelta(heading, bearing)) <= SeekEngineTuning.alignmentConeDegrees
}

private func recordCourse(of location: CLLocation) {
    if location.course >= 0 {
        courseSamples.append((timestamp: location.timestamp, course: location.course))
    }
    guard let newest = courseSamples.last?.timestamp else { return }
    let cutoff = newest.addingTimeInterval(-SeekEngineTuning.headingWindowSeconds)
    courseSamples.removeAll { $0.timestamp < cutoff }
}

/// Circular mean over the smoothing window — a single corner flap
/// cannot flip alignment the way per-fix comparison would.
static func smoothedHeading(of courses: [Double]) -> Double? {
    guard !courses.isEmpty else { return nil }
    var x = 0.0
    var y = 0.0
    for course in courses {
        let radians = course * .pi / 180
        x += cos(radians)
        y += sin(radians)
    }
    guard x != 0 || y != 0 else { return nil }
    return atan2(y, x) * 180 / .pi
}

static func angleDelta(_ a: Double, _ b: Double) -> Double {
    ((a - b + 540).truncatingRemainder(dividingBy: 360)) - 180
}
```

**Android claims, one-for-one:** iOS's negative-course sentinel (`course >= 0` filter) maps to `LocationPoint.bearingDegrees == null` — bearing-less fixes are never recorded. The prune window is measured from the NEWEST recorded sample's timestamp (not wall clock), so course-less fixes don't age the window. Alignment = |circular-mean heading − great-circle bearing to active center| ≤ 60°, `false` whenever no coordinate, no active clearing, or no course samples (misalignment is positive-only — iOS AE2 test `testWalkingDirectlyAway_pulsesUnalignedAndNothingElse`, `SeekEngineTests.swift:111-125`). `angleDelta` ports verbatim (`%` on Double ≙ Swift `truncatingRemainder`; `a−b+540 > 0` for all heading/bearing pairs from `atan2` degrees). `smoothedHeading` returns null on empty input or a zero resultant vector. `bearingDegrees` reuses U2's `SeekChainGenerator.bearingDegrees`.

## B10. Stillness detector — displacement path

**iOS** (`Pilgrim/Models/Walk/Seek/SeekStillnessDetector.swift:35-190@c1745e8`) — two-of-three voting (step delta, motion activity, displacement) with displacement double-duty as vote AND veto, and a documented degraded mode:

```swift
/// … With Motion & Fitness denied, the detector runs on displacement
/// alone over a lengthened window so the reveal ritual still fires.
...
static let displacementThresholdMeters = 15.0
static let accuracyGateMeters = 50.0
static let deniedWindowMultiplier = 1.5
...
self.windowDuration = displacementOnly
    ? windowDuration * Self.deniedWindowMultiplier
    : windowDuration
...
func recordLocation(_ location: CLLocation) {
    guard location.horizontalAccuracy >= 0,
          location.horizontalAccuracy <= Self.accuracyGateMeters else { return }
    lastGoodFix = location
    if let anchorFix {
        maxDisplacementMeters = max(maxDisplacementMeters, location.distance(from: anchorFix))
    } else {
        anchorFix = location
    }
    goodFixCount += 1
}

func evaluate(at date: Date) -> Update {
    guard isMonitoring, !isSuspended, !hasCompleted else { return .none }
    guard assessStillness() else {
        stillSince = nil
        return .none
    }
    guard let since = stillSince else {
        stillSince = date
        return .began
    }
    guard date.timeIntervalSince(since) >= windowDuration else { return .none }
    hasCompleted = true
    return .completed
}

private func assessStillness() -> Bool {
    let stepsStill = consumeStepDelta()
    let displacement = consumeDisplacement()
    if displacement.veto { return false }
    if isDisplacementOnly { return displacement.still }
    let votes = [stepsStill, motionSaysStill, displacement.still].filter { $0 }.count
    return votes >= 2
}

/// Net displacement is measured from an anchor fix; a veto re-anchors at
/// the newest fix so a walker who stops after moving can still settle
/// into a fresh window.
private func consumeDisplacement() -> (still: Bool, veto: Bool) {
    guard maxDisplacementMeters < Self.displacementThresholdMeters else {
        anchorFix = lastGoodFix
        goodFixCount = lastGoodFix == nil ? 0 : 1
        maxDisplacementMeters = 0
        return (still: false, veto: true)
    }
    return (still: goodFixCount >= 2, veto: false)
}
```

**Android:** iOS's displacement-only degraded path is the Android detector's ONLY path (plan committed decision; D3). `class SeekStillnessDetector(baseWindowMillis: Long)` — effective `windowDurationMillis = baseWindowMillis × 1.5` always (the `deniedWindowMultiplier`, kept as `DISPLACEMENT_WINDOW_MULTIPLIER = 1.5`). Constants `DISPLACEMENT_THRESHOLD_METERS = 15.0`, `ACCURACY_GATE_METERS = 50.0`. Claims, one-for-one:

- `recordLocation(point)`: accuracy gate — `null` accuracy excluded (≙ iOS negative sentinel), `> 50 m` excluded; a good fix updates `lastGoodFix`, anchors if unanchored else maxes displacement, increments `goodFixCount`. Displacement math on `SeekPoint` haversine (D2).
- `evaluate(atMillis)`: monitoring/suspension/one-shot-completion guards → not-still resets `stillSince` → first still evaluation stamps `stillSince` and returns `BEGAN` → still-before-window returns `NONE` → window elapsed (`>=`) returns `COMPLETED` exactly once (`hasCompleted` latch).
- Still = `goodFixCount >= 2 && maxDisplacement < 15 m`; `≥ 15 m` = veto that re-anchors at the newest good fix (`goodFixCount` = 1 if one exists else 0, displacement zeroed) and reads not-still.
- `suspend()`: freeze + `stillSince = nil` (no partial credit, `SeekStillnessDetector.swift:95-102`: "the window restarts from zero on resume — a paused walk banks no partial stillness credit"); `resume()`: reset displacement signals (fresh anchor).
- `start()`/`stop()`: monitoring latch; stopped detector evaluates to `NONE`.
- No motion provider, no step intake, no `isDisplacementOnly` flag (it is constantly true) — D3.

## B11. Stillness evaluation loop + grace

**iOS** (`SeekEngine.swift:317-331,359-370@c1745e8`):

```swift
func evaluateStillness(at date: Date) {
    guard phase == .arrived, !isSuspended, let detector = stillnessDetector else { return }
    switch detector.evaluate(at: date) {
    case .began:
        eventsSubject.send(.stillnessBegan(clearingIndex: activeIndex))
    case .completed:
        reveal()
        return
    case .none:
        break
    }
    if let graceDeadline, date >= graceDeadline {
        reveal()
    }
}
...
private func startStillnessCheckTimer() {
    stillnessCheckTimer?.invalidate()
    let timer = Timer(
        timeInterval: SeekEngineTuning.stillnessCheckInterval,
        repeats: true
    ) { ... self.evaluateStillness(at: self.now()) }
    ...
}
```

**Android:** `internal fun evaluateStillness(atMillis: Long)` — same dispatch; note `BEGAN` falls THROUGH to the grace check (only `COMPLETED` early-returns). The 5 s repeating timer becomes `scope.launch { while (isActive) { delay(5_000); evaluateStillness(clock.now()) } }` — a perpetual tick loop (test with `runCurrent()`, never `advanceUntilIdle()`). Grace reveals at `now ≥ deadline` regardless of voting (iOS AE4 `testGrace_revealsQuietlyWithoutStillness`, `SeekEngineTests.swift:243-252` — quiet: no `stillnessBegan` ever fired). Per-sample evaluation also runs on every ARRIVED-phase fix (B7).

## B12. Reveal + completion

**iOS** (`SeekEngine.swift:333-357@c1745e8`):

```swift
private func reveal() {
    stopStillnessMachinery()
    let nextIndex = activeIndex + 1
    guard nextIndex < chain.clearings.count else {
        phase = .complete
        stop()
        eventsSubject.send(.seekComplete)
        return
    }
    activeIndex = nextIndex
    phase = .guiding
    distanceToActiveMeters = nil
    rerollPulseDistance = nil
    consecutiveInsideCount = 0
    eventsSubject.send(.revealedNext(activeIndex: nextIndex))
}

private func stopStillnessMachinery() {
    stillnessDetector?.stop()
    stillnessDetector = nil
    stillnessCheckTimer?.invalidate()
    stillnessCheckTimer = nil
    graceDeadline = nil
    suspendedGraceRemaining = nil
}
```

**Android claims, one-for-one:** reveal past the last clearing → phase COMPLETE, full `stop()`, `SeekComplete` emitted exactly once; the completed engine is inert (phase guards silence every entry point — iOS `testFinalReveal_emitsSeekCompleteOnce_thenEngineGoesQuiet`, `SeekEngineTests.swift:290-304`). Non-final reveal: advance index, back to GUIDING, distance/reroll-distance/debounce cleared, `RevealedNext(nextIndex)` — no pulse until the next fix re-arms (distance is null).

## B13. Suspension (pause banks grace; meditation does NOT suspend)

**iOS** (`SeekEngine.swift:374-408@c1745e8`):

```swift
private func handleStatus(_ status: WalkBuilder.Status) {
    if status.isPausedStatus {
        suspend()
    } else if status == .recording {
        resumeFromSuspension()
    }
}

private func suspend() {
    guard !isSuspended else { return }
    isSuspended = true
    invalidatePulseTimer()
    stillnessCheckTimer?.invalidate()
    stillnessCheckTimer = nil
    if let deadline = graceDeadline {
        suspendedGraceRemaining = max(0, deadline.timeIntervalSince(now()))
        graceDeadline = nil
    }
    stillnessDetector?.suspend()
}

private func resumeFromSuspension() {
    guard isSuspended else { return }
    isSuspended = false
    if let remaining = suspendedGraceRemaining {
        graceDeadline = now().addingTimeInterval(remaining)
        suspendedGraceRemaining = nil
    }
    stillnessDetector?.resume()
    if phase == .arrived {
        startStillnessCheckTimer()
    } else if phase == .guiding {
        ensurePulseScheduled()
    }
}
```

`isPausedStatus` = `[.paused, .autoPaused].contains(self)` (`Pilgrim/Models/Walk/WalkBuilder/WalkBuilder+Status.swift:75-77@c1745e8`). Meditation is NOT a builder status on iOS — a meditating walk stays `.recording`, so the engine keeps pulsing at normal cadence (only the TIER changes, and `.meditation` applies no floor, B5).

**Android:** the status input is `Flow<WalkState>` (the domain analogue of `WalkBuilder.Status`): `WalkState.Paused → suspendEngine()`, `WalkState.Active → resumeFromSuspension()`, everything else (`Meditating`, `Idle`, `Finished`) is a no-op — `Meditating` deliberately falls in the no-op bucket so meditation neither suspends nor resumes, matching iOS where meditation never leaves `.recording` (D4). Android has no distinct auto-pause state — auto-pause dispatches through the same `WalkState.Paused`. Suspension banks the grace REMAINDER (`max(0, deadline − now)`) and re-arms it from resume time; the stillness detector's window restarts from zero (B10). Resume restarts the 5 s check timer when ARRIVED, or re-arms the pulse when GUIDING.

## B14. Reroll (seekAnew)

**iOS** (`SeekEngine.swift:127-173@c1745e8`):

```swift
func seekAnew(currentLocation: SeekPoint, seed: UInt64? = nil) {
    guard phase == .guiding || phase == .arrived else { return }
    if phase == .arrived {
        stopStillnessMachinery()
        phase = .guiding
    }
    let fractionAhead = 1 - Double(activeIndex) / Double(max(chain.clearings.count, 1))
    let remainingBudget = max(
        chain.budgetMeters * fractionAhead,
        SeekEngineTuning.rerollMinBudgetMeters
    )
    if var seeded = seed.map(SeekSeededGenerator.init(seed:)) {
        regenerateRemainder(current: currentLocation, budgetMeters: remainingBudget, using: &seeded)
    } else {
        var rng = SystemRandomNumberGenerator()
        regenerateRemainder(current: currentLocation, budgetMeters: remainingBudget, using: &rng)
    }
    consecutiveInsideCount = 0
    rerollPulseDistance = distanceToActiveMeters
    distanceToActiveMeters = nil
    invalidatePulseTimer()
    if rerollPulseDistance != nil {
        schedulePulse()
        // The immediate pulse IS the reroll's feedback: one ping, one
        // haptic, one ring the moment the new clearing exists.
        emitPulse()
    }
}
```

**Android claims, one-for-one:** allowed only in GUIDING/ARRIVED; ARRIVED first stops stillness machinery and returns to GUIDING. Remaining budget = `budget × (1 − activeIndex/max(count,1))` clamped to `SeekTuning.REROLL_MIN_BUDGET_METERS` (referenced from U2 — never redeclared). Seeded rng via `SeekSeededGenerator(seed)` when a seed is passed, `Random.Default` otherwise (≙ `SystemRandomNumberGenerator`). Chain swapped via U2's `regeneratingRemainder`. `rerollPulseDistance` deliberately carries the STALE pre-reroll distance (`SeekEngine.swift:69-72`: "carries the pre-reroll distance so the sonar heartbeat keeps pulsing across `seekAnew` until the next fix supplies the true distance"); the published distance resets to null; the pulse clock is invalidated then — only when a stale distance exists — rescheduled AND an immediate pulse fires (commit ece26a7's tangible confirmation; iOS `testSeekAnew_emitsAnImmediateFeedbackPulse`, `SeekEngineTests.swift:440-450`). A subsequent fresh fix clears `rerollPulseDistance` (B7).

## B15. Lifecycle: construction, binding, stop

**iOS** (`SeekEngine.swift:78-125,175-179@c1745e8`): init takes `chain`, `now`, `motionProvider`, `stillnessWindowOverride`; `bind(locations:stepCounts:builderStatus:powerTier:)` subscribes all four streams on the main queue; `stop()` invalidates the pulse timer, stops stillness machinery, and drops all subscriptions.

**Android:** constructor `SeekEngine(chain, scope: CoroutineScope, clock: Clock, locations: Flow<LocationPoint>, walkStates: Flow<WalkState>, powerTier: Flow<SeekPowerTier>, stillnessWindowOverrideMillis: Long? = null, windowRng: Random = Random.Default)` per the plan's injected-flows requirement; an explicit `start()` launches the three collectors (no init-block launching — house rule). No step flow (D3). Published state as `StateFlow`s (≙ `@Published`): `chain`, `activeIndex`, `phase`, `distanceToActiveMeters`. `stop()` cancels the pulse job, stillness machinery, and collector jobs; idempotent; called internally by the final reveal. Engine state is confined to `scope`'s dispatcher (≙ iOS main-queue delivery) — U9 must construct the scope on a single-threaded dispatcher and call `seekAnew`/`stop` from it.

## Divergences (conscious) and resolved ambiguities

| # | Divergence | Reason |
|---|---|---|
| D1 | `SeekPowerTier { NORMAL, LOW }` collapses iOS's four tiers; source is `PowerManager.isPowerSaveMode` (+ `ACTION_POWER_SAVE_MODE_CHANGED`), not battery %/thermal polling. | The engine only ever distinguishes floor vs no-floor (`SeekEngine.swift:202-207`: `.low/.critical → floor`, `.normal/.meditation → interval`). Android's user-facing battery-saver switch (OEM-default auto-on ≈15–20%) is the platform-idiomatic signal for iOS's ≤20% threshold; thermal escalation and checkpoint/GPS tiering are WalkSessionGuard responsibilities Android does not have in scope. Meditation is not a power tier on Android at all — it changes neither cadence (matches iOS: no floor) nor suspension (B13/D4). Plan U3 explicitly sanctions this minimal producer. |
| D2 | Distance/displacement math uses U2's `SeekChainGenerator.distance` haversine on `SeekPoint` (6 371 000 m sphere), not `android.location.Location.distanceBetween`. | `domain/seek/` is framework-free by contract (U2 spec header; plan: engine testable without Robolectric). iOS uses `CLLocation.distance` (Vincenty-ish) — divergence vs haversine is <0.6% and irrelevant at 15/50 m thresholds; U2 already committed the same haversine for chain geometry, so engine and generator agree with each other, which is the binding property (arrival radius and placement use the same metric). |
| D3 | Detector is displacement-only: no step-delta vote, no motion-activity vote, no `isDisplacementOnly` flag; the ×1.5 denied-window multiplier applies ALWAYS. Engine takes no `stepCounts` flow and has no `processSteps`. | Plan committed decision ("stillness voting ships displacement-primary — iOS's own Motion-&-Fitness-denied fallback; no activity-recognition prompt tied to seek") + Deferred-to-Follow-Up ("second stillness-vote signal (step-delta) if device QA shows displacement-only voting too eager/slow"). NOTE for that follow-up: a usable step signal DOES already exist on Android — `sensor/StepCounter.liveSteps: StateFlow<Int?>` with `ACTIVITY_RECOGNITION` already in the manifest — so the deferral is a scope choice, not a platform gap. iOS's displacement-only path is quoted in B10 and ported exactly, including the lengthened window. |
| D4 | Pause-status input is `Flow<WalkState>` (domain sealed class), not a builder-status enum. `Paused → suspend`, `Active → resume`, `Meditating/Idle/Finished → no-op`. | Android's reducer models meditation as a STATE (`WalkState.Meditating`) where iOS models it as a flag over `.recording`. Mapping `Meditating` to no-op reproduces iOS observable behavior exactly: entering/leaving meditation neither suspends nor resumes (an engine suspended by a pause stays suspended; a running engine keeps pulsing and voting). `Idle`/`Finished` are no-ops like iOS `.waiting`/`.ready` — the orchestrator (U9) stops the engine on walk end. Android has no distinct auto-pause state; auto-pause reaches the engine as the same `WalkState.Paused`. |
| D5 | Time is `Long` millis end-to-end (`Clock` seam, tuning constants, detector window, course timestamps) — no `Date`/`TimeInterval`. | House convention (`domain/Clock.kt`, `LocationPoint.timestamp: Long`); U2 spec D3 set the precedent. `pulseIntervalMillis` returns `Long` for `delay()`; second-scale iOS values convert losslessly. |
| D6 | Pulse/stillness timers are coroutine `Job`s in the injected scope (one-shot `delay` + generation check; 5 s `while(isActive)` loop), not runloop `Timer`s. No `_test_pulseIntervalOverride` debug hook and no deinit-releases-timer test. | Plan mandates the coroutine port. Virtual-time tests (`advanceTimeBy`/`runCurrent`) make the real intervals directly testable — the iOS override existed only because wall-clock runloop timers can't be compressed. Structured concurrency ties job lifetime to the scope, so iOS's `deinit`/retain-cycle test has no analogue; the `stop()`-silence test ports instead. |
| D7 | `LocationPoint` gains `bearingDegrees: Float? = null` (from `Location.hasBearing()/getBearing()`); iOS's negative-course sentinel maps to null. No speed change — `speedMetersPerSecond` already exists (U10's glance need is already carried). | The Android pipeline carried no course data; alignment (B9) needs it. Nullable-with-default keeps every existing constructor/fake source-compatible; `Float` matches the platform getter and the neighboring `speedMetersPerSecond: Float?` field style. Both `FusedLocationSource` mapping sites (stream + last-known) populate it. |
| D8 | `SeekEnginePhase` lives in `SeekEngine.kt`, not a widget-shared file; no `Codable`. | iOS parked it in `SeekGlance.swift` purely for the widget-extension compile boundary (`SeekGlance.swift:21-22`); Android's notification glance (U10) renders in-process. |
| D9 | Events ride a `MutableSharedFlow(extraBufferCapacity = 64)` with `tryEmit` from non-suspending engine methods. | ≙ `PassthroughSubject.send` (fire-and-forget, no backpressure); house precedent `ProximityDetectionService`. Buffer sized far above any burst the engine can produce in one dispatch (worst case ~3 events); slow collectors drop oldest-buffered rather than deadlocking the engine. |
| D10 | Engine RNG injection: `windowRng: Random` constructor param (stillness window draw) and `seekAnew(seed: ULong?)` falling back to `Random.Default`. | iOS uses global `Double.random`/`SystemRandomNumberGenerator` for these; Kotlin's injectable `Random` keeps the same defaults while letting tests pin the window without the override param when desired. `stillnessWindowOverrideMillis` is still ported (iOS test seam parity). |

## Test parity map

| iOS test (`UnitTests/Seek/…@c1745e8`) | Android test |
|---|---|
| `SeekEngineTests.testWalkingDirectlyAway_pulsesUnalignedAndNothingElse` (AE2) | `SeekEngineTest."walking directly away pulses unaligned and nothing else"` |
| `…testPulseInterval_mapsDistanceLinearlyAndClampsAtEnds` | `"pulse interval maps distance linearly and clamps at ends"` |
| `…testPulseInterval_lowAndCriticalTiersRaiseFloor` | `"pulse interval low tier raises floor"` (LOW covers iOS low+critical; NORMAL covers normal+meditation, D1) |
| `…testSingleStrayFixInside_doesNotArrive` | `"single stray fix inside does not arrive"` |
| `…testThreeConsecutiveGatedFixes_arriveAndPausePulseClock` | `"three consecutive gated fixes arrive and pause pulse clock"` |
| `…testLowAccuracyFixes_neitherAdvanceNorResetDebounce` | `"low accuracy fixes neither advance nor reset debounce"` (accuracy 51 m + null-accuracy variants) |
| `…testCourseFlapping_withinSmoothingWindow_doesNotFlipAlignment` | `"course flapping within smoothing window does not flip alignment"` |
| `…testStaleCourseSamples_agePastSmoothingWindow` | `"stale course samples age past smoothing window"` |
| `…testStillness_beginsThenRevealsNextClearing` | `"stillness begins then reveals next clearing"` (displacement-only voting, D3) |
| `…testGrace_revealsQuietlyWithoutStillness` (AE4) | `"grace reveals quietly without stillness"` |
| `…testPauseDuringStillness_freezes_resumeKeepsActiveClearing` | `"pause during stillness freezes and resume keeps active clearing"` |
| `…testFinalReveal_emitsSeekCompleteOnce_thenEngineGoesQuiet` | `"final reveal emits seek complete once then engine goes quiet"` |
| `…testSeekAnew_swapsRemainder_keepsPrefixAndActiveIndex_stalePulsesNoOp` | `"seek anew swaps remainder keeps prefix and active index and stale pulses no op"` |
| `…testSeekAnew_withPriorDistance_pulsesBeforeNextFix` | `"seek anew with prior distance pulses before next fix"` |
| `…testStop_silencesPulseTimer` | `"stop silences pulse timer"` (virtual-time: zero post-stop emissions) |
| `…testDeinit_engineReleasesDespiteScheduledTimer` | no analogue (D6 — structured concurrency) |
| `…testMotionDenied_displacementOnlyMode_revealsAfterLengthenedWindow` | `"displacement only window is lengthened"` (the Android baseline IS this mode, D3) |
| `…testTierPublisher_reachesEngineAndWidensFloor` | `"tier flow reaches engine and widens floor"` |
| `…testWalkSessionGuard_exposesCurrentTierPublisher` | `SeekPowerTierSourceTest` (Robolectric: initial NORMAL, broadcast → LOW, receiver unregistered on cancel) |
| `…testFirstFix_armsThePulseHeartbeat` | `"first fix arms the pulse heartbeat"` |
| `…testSeekAnew_emitsAnImmediateFeedbackPulse` | `"seek anew emits an immediate feedback pulse"` (stale distance value asserted) |
| `…testSeekAnew_withSeed_regeneratesDeterministically` | `"seek anew with seed regenerates deterministically"` |
| `…testCloseness_sharesTheCadenceCurve` | `"closeness shares the cadence curve"` |
| — (Android-only, plan scenarios) | `"pulse fires at the exact scheduled interval"` (advanceTimeBy(t−1)/+1), `"meditation does not suspend pulse clock or stillness voting"` (D4), `"pause banks grace remainder and re-arms on resume"`, `"two clearing walk emits the full event contract"` (integration), `"empty chain starts complete"` |
| `SeekStillnessDetectorTests.testAllThreeSignalsStill_beginsThenCompletesAfterWindow` | `SeekStillnessDetectorTest."still fixes begin then complete after window"` (displacement votes; window ×1.5 baked in, D3) |
| `…testTwoOfThreeStill_begins` / `…testOnlyOneSignalStill_neverBegins` / `…testDenied_stepsAndMotionAloneCannotBegin` | `"fewer than two good fixes never begins"` (the surviving signal's vote floor) |
| `…testDisplacementVeto_overridesTwoStillVotes` | `"displacement veto reads as not still"` + `"veto re-anchors at newest fix so a settling walker can begin fresh"` |
| `…testLowAccuracyFixes_doNotFeedDisplacement` | `"low accuracy fixes do not feed displacement"` (+ null accuracy) |
| `…testStepDelta_breaksRunningWindow_thenRestartsAfterQuiet` | no analogue (step vote deferred, D3); window-restart behavior covered by veto re-anchor test |
| `…testDenied_lengthensWindowAndRunsOnDisplacementAlone` | `"window is lengthened by the displacement multiplier"` |
| `…testSuspend_freezesEvaluation_resumeRestartsWindow` | `"suspend freezes evaluation and resume restarts window"` |
| `…testStop_stopsMotionUpdatesAndSilencesEvaluation` | `"stop silences evaluation"` |
