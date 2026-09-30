// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

// The synthetic corpus: nine Ways and the GPS traces walked on them. No
// point comes from a real walk. Every place is a round-number origin and
// every line is drawn here, in local metres around that origin, so the
// corpus can be regenerated exactly (`harness corpus <dir>`).

/// One input to the engine, in the order the view model delivers it.
struct TraceInput: Codable, Equatable {
    /// "fix", "tick" (the engine clock), "gates" (the four gates at bind),
    /// "gate" (one gate changes), or "finish" (the voice player ended or was skipped).
    var kind: String
    /// Seconds since the walk's start; `now()` reads it on a fix.
    var t: Double
    var lat: Double?
    var lon: Double?
    /// Nil is a fix with no accuracy: Android's null, iOS's invalid -1.
    var accuracy: Double?
    /// Nil is an unknown speed: Android's null, iOS's -1.
    var speed: Double?
    var activeSeconds: Double?
    var gate: String?
    var value: Bool?
    var paused: Bool?
    var meditating: Bool?
    var recording: Bool?
    var externalAudio: Bool?
}

struct Trace: Codable {
    let name: String
    let about: String
    /// Epoch seconds of `t = 0`.
    let t0: Double
    let softTapEnabled: Bool
    let voicesEnabled: Bool
    /// Voices whose file is gone: the view model hands the turn straight back.
    let missingMedia: [String]
    let inputs: [TraceInput]
}

// MARK: - Deterministic noise

/// SplitMix64 with integer-only draws, so a regenerated corpus is identical.
struct Noise {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
    /// Uniform on [-1, 1] in steps of 0.001.
    mutating func unit() -> Double { Double(Int(next() % 2001) - 1000) / 1000 }
    mutating func pick(_ values: [Double]) -> Double { values[Int(next() % UInt64(values.count))] }
}

// MARK: - Local geometry

/// A point in metres east and north of a site's origin.
struct P { var e: Double; var n: Double }

func distance(_ a: P, _ b: P) -> Double { hypot(b.e - a.e, b.n - a.n) }
func lerp(_ a: P, _ b: P, _ u: Double) -> P { P(e: a.e + (b.e - a.e) * u, n: a.n + (b.n - a.n) * u) }
func offset(_ p: P, _ e: Double, _ n: Double) -> P { P(e: p.e + e, n: p.n + n) }

/// Seven decimals of a degree, about a centimetre.
func round7(_ x: Double) -> Double { (x * 10_000_000).rounded() / 10_000_000 }

struct Site {
    let lat0: Double
    let lon0: Double
    func coordinate(_ p: P) -> WayCoordinate {
        let lonScale = 111_320 * cos(lat0 * .pi / 180)
        return WayCoordinate(lat: round7(lat0 + p.n / 111_320), lon: round7(lon0 + p.e / lonScale))
    }
}

struct Polyline {
    let vertices: [P]
    let cumulative: [Double]
    init(_ vertices: [P]) {
        self.vertices = vertices
        var running = 0.0
        var cum = [0.0]
        for i in 1..<vertices.count {
            running += distance(vertices[i - 1], vertices[i])
            cum.append(running)
        }
        cumulative = cum
    }
    var length: Double { cumulative.last! }
    func point(at s: Double) -> P {
        let s = min(max(s, 0), length)
        for i in 1..<vertices.count where s <= cumulative[i] {
            let span = cumulative[i] - cumulative[i - 1]
            return lerp(vertices[i - 1], vertices[i], span > 0 ? (s - cumulative[i - 1]) / span : 0)
        }
        return vertices.last!
    }
}

// MARK: - Ways

struct Rest { let along: Double; let seconds: Double }

/// The Way's route: every vertex, and a sample every `spacing` metres
/// between them, timed at the original walker's `speed`; a rest repeats
/// the nearest sample `seconds` later, a plateau like the builder's.
struct RouteBuild {
    let route: [WayPoint]
    let along: [Double]
    let geometry: WayGeometry

    init(site: Site, path: Polyline, spacing: Double, speed: Double, rests: [Rest] = []) {
        var stations: [Double] = []
        for i in 1..<path.vertices.count {
            let a = path.cumulative[i - 1], b = path.cumulative[i]
            let steps = max(1, Int((b - a) / spacing))
            for k in 0..<steps { stations.append(a + (b - a) * Double(k) / Double(steps)) }
        }
        stations.append(path.length)
        var points: [WayPoint] = []
        var alongs: [Double] = []
        var restSeconds = 0.0
        var pending = rests.sorted { $0.along < $1.along }
        for s in stations {
            let c = site.coordinate(path.point(at: s))
            let t = (s / speed + restSeconds).rounded()
            points.append(WayPoint(lat: c.lat, lon: c.lon, alt: nil, t: t))
            alongs.append(s)
            if let rest = pending.first, abs(rest.along - s) < spacing / 2 {
                pending.removeFirst()
                restSeconds += rest.seconds
                points.append(WayPoint(lat: c.lat, lon: c.lon, alt: nil, t: t + rest.seconds))
                alongs.append(s)
            }
        }
        precondition(pending.isEmpty, "a rest fell between samples")
        route = points
        along = alongs
        geometry = WayGeometry(route: points)
    }

    /// The first route index nearest `s` along the drawing.
    func index(near s: Double) -> Int {
        var best = 0
        for i in along.indices where abs(along[i] - s) < abs(along[best] - s) { best = i }
        return best
    }

    func frac(near s: Double) -> Double { geometry.cumulative[index(near: s)] / geometry.totalMeters }
}

struct MomentSpec {
    let id: String
    let along: Double
    /// Metres east and north of the route sample; nil places the moment by its frac alone.
    let at: (e: Double, n: Double)?
    let kind: (RouteBuild) -> WayMomentKind
}

func voice(_ n: Int, along: Double, at: (e: Double, n: Double)? = (0, 0), seconds: Double, ambient: Bool = false) -> MomentSpec {
    MomentSpec(id: "voice-\(n)", along: along, at: at) { build in
        .voice(endFrac: build.frac(near: along + seconds * 1.2), duration: seconds,
               kind: ambient ? .ambient : .spoken,
               media: .recording(relativePath: "Recordings/golden/voice-\(n).m4a"))
    }
}

func photo(_ n: Int, along: Double, at: (e: Double, n: Double) = (0, 0)) -> MomentSpec {
    MomentSpec(id: "photo-\(n)", along: along, at: at) { _ in
        .photo(media: .photoAsset(localIdentifier: "golden-photo-\(n)"))
    }
}

func waypoint(_ n: Int, along: Double, at: (e: Double, n: Double) = (0, 0), label: String) -> MomentSpec {
    MomentSpec(id: "waypoint-\(n)", along: along, at: at) { _ in .waypoint(label: label, icon: "leaf") }
}

func rest(_ n: Int, along: Double, minutes: Int) -> MomentSpec {
    MomentSpec(id: "rest-\(n)", along: along, at: (0, 0)) { _ in .rest(minutes: minutes) }
}

func sit(_ n: Int, along: Double, minutes: Int) -> MomentSpec {
    MomentSpec(id: "sit-\(n)", along: along, at: (0, 0)) { _ in .meditation(minutes: minutes, isEstimate: false) }
}

func makeWay(name: String, uuid: String, site: Site, build: RouteBuild, path: Polyline, moments: [MomentSpec]) -> Way {
    let made: [WayMoment] = moments.map { spec in
        let at: WayCoordinate? = spec.at.map { off in
            let base = path.point(at: build.along[build.index(near: spec.along)])
            return site.coordinate(offset(base, off.e, off.n))
        }
        return WayMoment(id: spec.id, frac: build.frac(near: spec.along), at: at, kind: spec.kind(build))
    }
    .sorted { $0.frac == $1.frac ? $0.id < $1.id : $0.frac < $1.frac }
    return Way(id: "walk:\(uuid)", source: .ownWalk(UUID(uuidString: uuid)!), title: name,
               departedAt: Date(timeIntervalSince1970: 1_775_030_400), tzIdentifier: "UTC", expires: nil,
               route: build.route, totalDistanceMeters: build.geometry.totalMeters.rounded(),
               theirActiveSeconds: build.geometry.totalSeconds, moments: made, weather: nil)
}

// MARK: - Walkers

/// A walker's true path over time, piecewise linear, with a nominal speed.
struct Motion {
    struct Leg { let t0: Double; let t1: Double; let from: P; let to: P; let speed: Double? }
    private(set) var legs: [Leg] = []
    private(set) var here: P
    private(set) var now = 0.0

    init(start: P) { here = start }

    mutating func walk(_ route: [P], speed: Double) {
        for target in route {
            let seconds = distance(here, target) / speed
            legs.append(Leg(t0: now, t1: now + seconds, from: here, to: target, speed: speed))
            now += seconds
            here = target
        }
    }

    mutating func walk(_ line: Polyline, from s0: Double, to s1: Double, speed: Double) {
        let stations = stride(from: s0, to: s1, by: 5).map { line.point(at: $0) } + [line.point(at: s1)]
        walk(stations, speed: speed)
    }

    mutating func stand(_ seconds: Double) {
        drift(to: here, seconds: seconds)
    }

    /// Standing still while the fixes wander: a standing walker's speeds.
    mutating func drift(to target: P, seconds: Double) {
        legs.append(Leg(t0: now, t1: now + seconds, from: here, to: target, speed: nil))
        now += seconds
        here = target
    }

    func sample(_ t: Double) -> (P, Double?) {
        for leg in legs where t <= leg.t1 {
            let span = leg.t1 - leg.t0
            return (lerp(leg.from, leg.to, span > 0 ? (t - leg.t0) / span : 0), leg.speed)
        }
        return (here, nil)
    }
}

struct FixOverride {
    var accuracy: Double??
    var speed: Double??
    var at: P?
}

/// Fixes every `interval` seconds on whole seconds, with `sigma` metres of
/// jitter, a typical phone accuracy, and the nominal speed (or a standing
/// walker's near-zero reading).
func fixes(site: Site, motion: Motion, seed: UInt64, interval: Double = 2, sigma: Double = 1.5,
           overrides: [Double: FixOverride] = [:]) -> [TraceInput] {
    var noise = Noise(seed: seed)
    var result: [TraceInput] = []
    var t = 0.0
    while t <= motion.now {
        let (truth, nominal) = motion.sample(t)
        var p = offset(truth, sigma * noise.unit(), sigma * noise.unit())
        var accuracy: Double? = noise.pick([4, 5, 5, 6, 8, 10, 12.5])
        var speed: Double? = nominal.map { $0 + noise.pick([-0.125, 0, 0, 0.125]) } ?? noise.pick([0, 0.125, 0.25, 0.375])
        if let o = overrides[t] {
            if let a = o.accuracy { accuracy = a }
            if let s = o.speed { speed = s }
            if let at = o.at { p = at }
        }
        let c = site.coordinate(p)
        result.append(TraceInput(kind: "fix", t: t, lat: c.lat, lon: c.lon, accuracy: accuracy, speed: speed))
        t += interval
    }
    return result
}

/// The first fix at or after `t`: fixes land on even seconds.
func fixTime(atOrAfter t: Double) -> Double { 2 * (t / 2).rounded(.up) }

func gate(_ name: String, _ value: Bool, at t: Double) -> TraceInput {
    TraceInput(kind: "gate", t: t, gate: name, value: value)
}

func finish(at t: Double) -> TraceInput { TraceInput(kind: "finish", t: t) }

func tick(at t: Double, _ seconds: Double) -> TraceInput { TraceInput(kind: "tick", t: t, activeSeconds: seconds) }

/// The inputs in delivery order. Begin replays the three `@Published`
/// values `bind` subscribes to, in `bind`'s order: the last pre-Begin fix,
/// the engine clock's 0, and the gates with the status still `.ready`
/// (paused). Then the status hop opens the pause gate, the start date's
/// stamp ticks the clock once, and the 1 Hz timer ticks at a fixed phase
/// (0.4 s past each second) with walk time minus `pauses`. Fixes land on
/// whole seconds and scripted inputs at their own times, so no two inputs
/// share an instant.
func assemble(fixes: [TraceInput], scripted: [TraceInput], pauses: [(Double, Double)] = []) -> [TraceInput] {
    func active(_ t: Double) -> Double {
        var paused = 0.0
        for (a, b) in pauses where t > a { paused += min(t, b) - a }
        return ((t - paused) * 1000).rounded() / 1000
    }
    let first = fixes[0]
    precondition(first.t == 0)
    var begin = [
        first,
        tick(at: 0, 0),
        TraceInput(kind: "gates", t: 0, paused: true, meditating: false, recording: false, externalAudio: false),
        gate("paused", false, at: 0),
        tick(at: 0.02, 0.02),
    ]
    let end = fixes.last!.t
    var rest = Array(fixes.dropFirst()) + scripted
    var k = 0.0
    while k + 0.4 <= end {
        rest.append(tick(at: k + 0.4, active(k + 0.4)))
        k += 1
    }
    rest.sort { $0.t < $1.t }
    for i in 1..<rest.count { precondition(rest[i].t != rest[i - 1].t, "two inputs at t = \(rest[i].t)") }
    begin += rest
    return begin
}

// MARK: - The corpus

let corpusT0 = 1_777_622_400.0  // 2026-05-01T08:00:00Z

func corpus() -> [(Way, Trace)] {
    [straight(), loopLong(), loopShort(), outAndBack(), detour(), blackout(), stationary(), pauseMidVoice(), sitMidVoice()]
}

private func trace(_ name: String, _ about: String, softTap: Bool = true, voices: Bool = true,
                   missing: [String] = [], _ inputs: [TraceInput]) -> Trace {
    Trace(name: name, about: about, t0: corpusT0, softTapEnabled: softTap, voicesEnabled: voices,
          missingMedia: missing, inputs: inputs)
}

/// A straight-ish kilometre at 42.88°N with every moment kind, a voice at
/// the trailhead, a rest plateau, and an arrival.
func straight() -> (Way, Trace) {
    let site = Site(lat0: 42.88, lon0: -8.54)
    let line = Polyline([P(e: 0, n: 0), P(e: 300, n: 40), P(e: 560, n: 20), P(e: 800, n: 120)])
    let build = RouteBuild(site: site, path: line, spacing: 15, speed: 1.2, rests: [Rest(along: 500, seconds: 240)])
    let way = makeWay(name: "straight", uuid: "0D6E3A52-7C1B-4E0A-9F21-5B8C4D2E1A01", site: site, build: build, path: line,
                      moments: [
                          voice(1, along: 10, seconds: 25),
                          waypoint(1, along: 150, at: (0, 20), label: "Stone wall"),
                          photo(1, along: 290, at: (0, 35)),
                          voice(2, along: 400, at: nil, seconds: 30, ambient: true),
                          rest(1, along: 500, minutes: 4),
                          sit(1, along: 620, minutes: 3),
                          voice(3, along: 720, at: (5, -5), seconds: 20),
                      ])
    var motion = Motion(start: P(e: -4, n: 3))
    motion.stand(6)
    motion.walk(line, from: 0, to: line.length, speed: 1.3)
    motion.stand(14)
    let inputs = assemble(fixes: fixes(site: site, motion: motion, seed: 1),
                          scripted: [finish(at: 25.7), finish(at: 318.7), finish(at: 560.7)])
    return (way, trace("straight-42n", "A straight Way walked end to end: every moment kind, a voice at the trailhead started by the replayed pre-Begin fix, a rest plateau, arrival.", inputs))
}

/// AE2 on a loop longer than the 300 m window, straddling the equator,
/// with voices off.
func loopLong() -> (Way, Trace) {
    let site = Site(lat0: -0.0006, lon0: 32.61)
    let line = Polyline([P(e: 0, n: 0), P(e: 280, n: 0), P(e: 330, n: 120), P(e: 280, n: 240),
                         P(e: 0, n: 240), P(e: -40, n: 120), P(e: 0, n: 0)])
    let build = RouteBuild(site: site, path: line, spacing: 15, speed: 1.2)
    let way = makeWay(name: "loop long", uuid: "0D6E3A52-7C1B-4E0A-9F21-5B8C4D2E1A02", site: site, build: build, path: line,
                      moments: [
                          photo(1, along: 200, at: (0, -10)),
                          voice(1, along: 430, seconds: 30),
                          waypoint(1, along: 700, label: "Fig tree"),
                          voice(2, along: 900, seconds: 20),
                      ])
    var motion = Motion(start: P(e: 2, n: -3))
    motion.stand(16)
    motion.walk(line, from: 0, to: line.length, speed: 1.35)
    motion.stand(20)
    let inputs = assemble(fixes: fixes(site: site, motion: motion, seed: 2), scripted: [])
    return (way, trace("loop-long-0n", "AE2: a 1 km loop whose ends coincide. Standing at the trailhead never arrives; walking the loop arrives exactly once. Voices off.", voices: false, inputs))
}

/// pilgrim-ios #100 as shipped: a 280 m square loop at 60°N arrives at
/// Begin, then tracking, moments, and a backward re-acquire go on.
func loopShort() -> (Way, Trace) {
    let site = Site(lat0: 60.17, lon0: 24.94)
    let line = Polyline([P(e: 0, n: 0), P(e: 70, n: 0), P(e: 70, n: 70), P(e: 0, n: 70), P(e: 0, n: 0)])
    let build = RouteBuild(site: site, path: line, spacing: 10, speed: 1.2)
    let way = makeWay(name: "loop short", uuid: "0D6E3A52-7C1B-4E0A-9F21-5B8C4D2E1A03", site: site, build: build, path: line,
                      moments: [
                          waypoint(1, along: 140, label: "Corner"),
                          photo(1, along: 210, at: (-8, 0)),
                      ])
    var motion = Motion(start: P(e: 0, n: 3))
    motion.stand(10)
    motion.walk([P(e: 0, n: 0)], speed: 1.3)
    motion.walk(line, from: 0, to: line.length, speed: 1.3)
    motion.stand(10)
    let inputs = assemble(fixes: fixes(site: site, motion: motion, seed: 3, sigma: 0.8), scripted: [])
    return (way, trace("loop-short-60n", "pilgrim-ios #100 as shipped: on a 280 m loop the Begin fix, 3 m north of the start on the closing side, projects onto the closing leg, and three fixes arrive at Begin.", inputs))
}

/// An out-and-back on shared pavement at 33.87°S, begun from a car park
/// 220 m away: the fallback anchor, the failed re-acquires, the join.
func outAndBack() -> (Way, Trace) {
    let site = Site(lat0: -33.87, lon0: 151.21)
    let line = Polyline([P(e: 0, n: 0), P(e: 180, n: 40), P(e: 340, n: 60), P(e: 340, n: 58), P(e: 180, n: 38), P(e: 0, n: -2)])
    let build = RouteBuild(site: site, path: line, spacing: 15, speed: 1.2, rests: [Rest(along: 345.2, seconds: 120)])
    let way = makeWay(name: "out and back", uuid: "0D6E3A52-7C1B-4E0A-9F21-5B8C4D2E1A04", site: site, build: build, path: line,
                      moments: [
                          waypoint(1, along: 90, at: (0, 15), label: "Bench"),
                          voice(1, along: 184.4, seconds: 30),
                          photo(1, along: 345.2, at: (10, 10)),
                          voice(2, along: 506.4, at: (0, 1), seconds: 25),
                      ])
    var motion = Motion(start: P(e: -160, n: -150))
    motion.stand(130)
    motion.walk([P(e: 0, n: 0)], speed: 1.5)
    motion.walk(line, from: 0, to: 345.2, speed: 1.4)
    motion.stand(20)
    motion.walk(line, from: 345.2, to: line.length, speed: 1.4)
    motion.stand(12)
    let inputs = assemble(fixes: fixes(site: site, motion: motion, seed: 4),
                          scripted: [finish(at: 410.7), finish(at: 660.7)])
    return (way, trace("out-and-back-33s", "An out-and-back on shared pavement begun from a car park 220 m away: fallback anchor, silent soft tap, failed re-acquires every 10 s, the join re-anchors, the return leg's voice waits for the return, arrival at the start.", inputs))
}

/// A detour at 47.6°N: the whisper gate, a dropped voice, the soft tap's
/// timer cleared and restarted, re-acquire at 120 s with 10 s retries, a
/// bus ride whose jump is credited only at the Way's pace, arrival.
func detour() -> (Way, Trace) {
    let site = Site(lat0: 47.6, lon0: -122.33)
    let line = Polyline([P(e: 0, n: 0), P(e: 500, n: 80), P(e: 1000, n: 120)])
    let build = RouteBuild(site: site, path: line, spacing: 15, speed: 0.8)
    let way = makeWay(name: "detour", uuid: "0D6E3A52-7C1B-4E0A-9F21-5B8C4D2E1A05", site: site, build: build, path: line,
                      moments: [
                          voice(1, along: 200, seconds: 40),
                          photo(1, along: 760, at: (0, 12)),
                          waypoint(1, along: 900, label: "Gate"),
                      ])
    var motion = Motion(start: P(e: 1, n: 2))
    motion.walk(line, from: 0, to: 250, speed: 1.4)
    motion.walk([P(e: 260, n: 280), P(e: 340, n: 210), P(e: 430, n: 320), P(e: 560, n: 400), P(e: 700, n: 420)], speed: 1.5)
    motion.walk([line.point(at: 722)], speed: 6)
    motion.walk(line, from: 722, to: line.length, speed: 1.4)
    motion.stand(12)
    let inputs = assemble(fixes: fixes(site: site, motion: motion, seed: 5),
                          scripted: [gate("externalAudio", true, at: 100.7), gate("externalAudio", false, at: 520.7)])
    return (way, trace("detour-reacquire-47n", "A detour: a voice queued behind a whisper and dropped 300 m away, the soft tap's timer cleared by a dip under 200 m and restarted, re-acquire tried at exactly 120 s and retried every 10 s, a bus ride back to the Way credited at the Way's pace, arrival.", inputs))
}

/// An accuracy blackout at 42.87°N: fixes over 50 m, at 50.5 m, invalid,
/// and missing never reach the engine; arrival's debounce skips them.
func blackout() -> (Way, Trace) {
    let site = Site(lat0: 42.87, lon0: -8.50)
    let line = Polyline([P(e: 0, n: 0), P(e: 560, n: -60)])
    let build = RouteBuild(site: site, path: line, spacing: 15, speed: 1.2)
    let way = makeWay(name: "blackout", uuid: "0D6E3A52-7C1B-4E0A-9F21-5B8C4D2E1A06", site: site, build: build, path: line,
                      moments: [
                          voice(1, along: 100, seconds: 20),
                          photo(1, along: 260),
                          waypoint(1, along: 480, label: "Spring"),
                      ])
    var motion = Motion(start: P(e: 0, n: 1))
    motion.walk(line, from: 0, to: 520, speed: 1.3)
    motion.stand(30)
    let approach = motion.now
    motion.walk(line, from: 520, to: 548, speed: 1.3)
    motion.stand(40)
    var overrides: [Double: FixOverride] = [:]
    let bad: [Double?] = [65, 120, 50.5, nil, -1, 80, 55, 51]
    var t = 152.0
    var k = 0
    while t < 250 { overrides[t] = FixOverride(accuracy: .some(bad[k % bad.count])); t += 2; k += 1 }
    overrides[150] = FixOverride(accuracy: .some(50))
    overrides[250] = FixOverride(accuracy: .some(50))
    // The last 30 m, placed exactly so no fix sits near the arrival radius:
    // in, in, a 36 m multipath jump (resets), in, two bad fixes (skipped), in, in.
    let end: [(Double, P, Double??)] = [
        (8, line.point(at: 530), nil), (10, line.point(at: 536), nil), (12, line.point(at: 538), nil),
        (14, offset(line.point(at: 540), 0, 36), nil), (16, line.point(at: 541), nil),
        (18, line.point(at: 542), .some(70)), (20, line.point(at: 543), .some(nil)),
        (22, line.point(at: 546), nil), (24, line.point(at: 548), nil),
    ]
    for (dt, p, accuracy) in end {
        overrides[fixTime(atOrAfter: approach) + dt] = FixOverride(accuracy: accuracy, at: p)
    }
    let inputs = assemble(fixes: fixes(site: site, motion: motion, seed: 6, overrides: overrides),
                          scripted: [finish(at: 110.7)])
    return (way, trace("blackout-42n", "An accuracy blackout: a photo passed while every fix is over 50 m, at 50.5 m, invalid, or missing is never reached; at the end a jump resets the arrival count and bad fixes neither advance nor reset it.", inputs))
}

/// A walker who stops at a voice at 35°N while recording: queued voices
/// survive a stationary walker over 300 m away, an unknown speed drops
/// one, a missing file hands the turn straight on.
func stationary() -> (Way, Trace) {
    let site = Site(lat0: 35.0, lon0: 135.77)
    let line = Polyline([P(e: 0, n: 0), P(e: 400, n: 300), P(e: 700, n: 300)])
    let build = RouteBuild(site: site, path: line, spacing: 15, speed: 1.2)
    let way = makeWay(name: "stationary", uuid: "0D6E3A52-7C1B-4E0A-9F21-5B8C4D2E1A07", site: site, build: build, path: line,
                      moments: [
                          voice(1, along: 120, seconds: 30),
                          voice(2, along: 416, seconds: 40),
                          voice(3, along: 416, at: (1, 0), seconds: 15),
                          waypoint(1, along: 700, label: "Shrine"),
                      ])
    var motion = Motion(start: P(e: 0, n: 1))
    motion.walk(line, from: 0, to: 417, speed: 1.3)
    let stop = motion.now
    // Standing at the second voice while the fixes drift past 300 m from the first.
    motion.drift(to: line.point(at: 422), seconds: 150)
    motion.walk(line, from: 422, to: 720, speed: 1.3)
    var overrides: [Double: FixOverride] = [:]
    overrides[fixTime(atOrAfter: stop + 136)] = FixOverride(speed: .some(nil), at: line.point(at: 423))
    let inputs = assemble(fixes: fixes(site: site, motion: motion, seed: 7, sigma: 1.5, overrides: overrides),
                          scripted: [gate("recording", true, at: 40.7), gate("recording", false, at: 470.7),
                                     tick(at: 470.8, 470.8), finish(at: 510.7)])
    return (way, trace("stationary-voice-35n", "A walker records a reply while two voices queue, stops at the second, and stands while the fixes drift: a stationary walker over 300 m from the first keeps it; one fix with unknown speed drops it; the reply ends and the second plays; the third's file is missing.", missing: ["voice-3"], inputs))
}

/// A pause mid-voice at 51.5°N: the gate pauses and resumes the voice,
/// the engine clock holds, cards still appear, and a second pause walked
/// 300 m away drops the paused voice.
func pauseMidVoice() -> (Way, Trace) {
    let site = Site(lat0: 51.5, lon0: -0.12)
    let line = Polyline([P(e: 0, n: 0), P(e: 300, n: 200), P(e: 650, n: 250)])
    let build = RouteBuild(site: site, path: line, spacing: 15, speed: 1.2)
    let way = makeWay(name: "pause", uuid: "0D6E3A52-7C1B-4E0A-9F21-5B8C4D2E1A08", site: site, build: build, path: line,
                      moments: [
                          voice(1, along: 100, seconds: 90),
                          waypoint(1, along: 150, at: (0, 12), label: "Lamp post"),
                          voice(2, along: 170, seconds: 60),
                          photo(1, along: 600),
                      ])
    var motion = Motion(start: P(e: 1, n: 1))
    motion.walk(line, from: 0, to: 70, speed: 1.3)
    motion.stand(40)
    motion.walk(line, from: 70, to: 150, speed: 1.0)
    motion.stand(110)
    motion.walk(line, from: 150, to: line.length, speed: 1.4)
    motion.stand(12)
    let pauses = [(65.7, 185.7), (290.7, 540.7)]
    let inputs = assemble(fixes: fixes(site: site, motion: motion, seed: 8), scripted: [
        gate("paused", true, at: 65.7), gate("paused", false, at: 185.7), gate("paused", false, at: 200.7),
        finish(at: 255.7), gate("paused", true, at: 290.7), gate("paused", false, at: 540.7),
    ], pauses: pauses)
    return (way, trace("pause-mid-voice-51n", "Paused mid-voice with the engine clock held: the voice pauses and resumes, a waypoint still appears and a second voice queues during the pause, a repeated gate is inert, and a second pause walked 300 m away drops the paused voice.", inputs))
}

/// A sitting mid-voice at 64.14°N: the voice pauses, the clock and the
/// companion keep running, a card appears during the sitting, the voice
/// resumes when it ends, and the sitting counts in `yourSeconds`.
func sitMidVoice() -> (Way, Trace) {
    let site = Site(lat0: 64.14, lon0: -21.94)
    let line = Polyline([P(e: 0, n: 0), P(e: 250, n: 150), P(e: 500, n: 180)])
    let build = RouteBuild(site: site, path: line, spacing: 15, speed: 1.2, rests: [Rest(along: 200, seconds: 300)])
    let way = makeWay(name: "sit", uuid: "0D6E3A52-7C1B-4E0A-9F21-5B8C4D2E1A09", site: site, build: build, path: line,
                      moments: [
                          voice(1, along: 170, seconds: 120),
                          sit(1, along: 200, minutes: 5),
                          voice(2, along: 205, at: (6, 0), seconds: 30),
                          photo(1, along: 240),
                          waypoint(1, along: 450, label: "Cairn"),
                      ])
    var motion = Motion(start: P(e: 0, n: 1))
    motion.walk(line, from: 0, to: 200, speed: 1.3)
    let sat = motion.now
    motion.stand(316)
    motion.walk(line, from: 200, to: line.length, speed: 1.3)
    motion.stand(12)
    var overrides: [Double: FixOverride] = [:]
    overrides[fixTime(atOrAfter: sat + 150)] = FixOverride(at: line.point(at: 222))
    let inputs = assemble(fixes: fixes(site: site, motion: motion, seed: 9, sigma: 1.2, overrides: overrides), scripted: [
        gate("meditating", true, at: 160.7), gate("meditating", false, at: 460.7),
        finish(at: 525.7), finish(at: 570.7),
    ])
    return (way, trace("sit-mid-voice-64n", "A sitting mid-voice: the voice pauses and a second queues; the engine clock and the companion run on; one fix leaning 22 m ahead reaches a photo during the sitting; the voice resumes when it ends; arrival counts the sitting.", inputs))
}
