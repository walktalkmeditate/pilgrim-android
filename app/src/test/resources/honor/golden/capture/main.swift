// SPDX-License-Identifier: GPL-3.0-or-later
import Combine
import CoreLocation
import Foundation
import ObjectiveC

// Drives iOS's real HonorEngine (HonorEngine.swift, HonorMomentTracker.swift,
// HonorTuning.swift, WayGeometry.swift, Way.swift, ArrivalDebounce.swift,
// fetched verbatim at 7c200bf) over the corpus, the way
// ActiveWalkViewModel+Honor.swift@7c200bf does, and records what it does:
// own walks' voices and moments, and pilgrimage stages' water.
//
//   harness corpus <corpus dir>                  writes the Ways and traces
//   harness capture <corpus dir> <expected dir>  writes the event streams
//   harness pairs <file>                         writes GeoDistanceTest's pairs
//   harness cache                                checks the README's model of CLLocation.distance

// MARK: - CLLocation.distance, observed

/// Apple's `-[CLLocation distanceFromLocation:]`, swizzled to log the
/// engine's calls. The value always comes from Apple's own implementation.
/// It keeps the radii of curvature of the last pair whose receiver was 0.005°
/// of latitude or more from the stored latitude, and reuses them otherwise
/// (see README), so `forceMiss` first measures a pair far away, which makes
/// the next pair recompute its own radii: the value with no history.
final class DistanceTap {
    static let shared = DistanceTap()
    struct Call { let from: CLLocationCoordinate2D; let to: CLLocationCoordinate2D; let meters: Double }
    var logging = false
    var forceMiss = false
    var bypass = false
    var calls: [Call] = []

    static func install() {
        let original = class_getInstanceMethod(CLLocation.self, #selector(CLLocation.distance(from:)))!
        let tapped = class_getInstanceMethod(CLLocation.self, #selector(CLLocation.goldenDistance(from:)))!
        method_exchangeImplementations(original, tapped)
    }

    /// Makes the next measurement recompute its radii.
    static func farPair(from latitude: Double) -> (CLLocation, CLLocation) {
        let far = abs(latitude) < 40 ? 80.0 : 0.0
        return (CLLocation(latitude: far, longitude: 0), CLLocation(latitude: far, longitude: 0.001))
    }

    static func fresh(_ a: CLLocation, _ b: CLLocation) -> Double {
        let (x, y) = farPair(from: a.coordinate.latitude)
        _ = x.distance(from: y)
        return a.distance(from: b)
    }
}

extension CLLocation {
    /// After the exchange, this selector names Apple's implementation.
    @objc func goldenDistance(from location: CLLocation) -> CLLocationDistance {
        let tap = DistanceTap.shared
        if tap.bypass { return goldenDistance(from: location) }
        tap.bypass = true
        defer { tap.bypass = false }
        if tap.forceMiss {
            let (x, y) = DistanceTap.farPair(from: coordinate.latitude)
            _ = x.goldenDistance(from: y)
        }
        let meters = goldenDistance(from: location)
        if tap.logging { tap.calls.append(.init(from: coordinate, to: location.coordinate, meters: meters)) }
        return meters
    }
}

// MARK: - Records

struct EventRecord: Codable, Equatable {
    let type: String
    var id: String?
    var offWayMeters: Double?
    var theirSeconds: Double?
    var yourSeconds: Double?
    /// How far ahead a water mark is, unrounded, as `.markAhead` carries it.
    var meters: Double?
}

struct Coordinate: Codable, Equatable { let lat: Double; let lon: Double }

struct CallRecord: Codable, Equatable {
    /// "end", or the id of the moment whose place was measured.
    let target: String
    let to: Coordinate
    /// The value the engine got, with CoreLocation's cache as this run left it.
    var cl: Double?
    /// The same pair measured with no history.
    var fresh: Double?
}

struct StateRecord: Codable, Equatable {
    let progressFrac: Double
    let distanceRemainingMeters: Double
    let offWayMeters: Double
    let isOnWay: Bool
    let companionFrac: Double
    let phase: String
    let startFrac: Double?
    let companionT0: Double
    let distanceWalkedMeters: Double
    let isAnchoredOnWay: Bool
    // Private in the engine, read with Mirror: the soft tap's state, the
    // arrival count, the re-acquire clocks (seconds since t0), the voices.
    let softTap: String
    let arrivalCount: Int
    let offWaySince: Double?
    let lastReacquireAttempt: Double?
    let playing: String?
    let voicePaused: Bool
    let queue: [String]
    // A stage's water watcher, also private, recorded only for a Way with
    // marks so the own-walk traces keep their bytes: the marks spoken
    // (sorted), and the engine clock at the last notice. iOS names that
    // clock `lastMarkSeconds` at 7c200bf; the key is the name iOS PR #91
    // gives it, `lastNoticeSeconds`, so the fold-in changes one label here.
    let firedMarks: [String]?
    let lastNoticeSeconds: Double?
}

struct Record: Codable, Equatable {
    let i: Int
    let kind: String
    var fix: Int?
    var events: [EventRecord]?
    var calls: [CallRecord]?
    var state: StateRecord?
    var companionFrac: Double?
}

func field<T>(_ subject: Any, _ name: String, as: T.Type = T.self) -> T {
    guard let child = Mirror(reflecting: subject).children.first(where: { $0.label == name }) else {
        fatalError("\(type(of: subject)) has no stored property \(name)")
    }
    guard let value = child.value as? T else { fatalError("\(name) is \(type(of: child.value)), not \(T.self)") }
    return value
}

func encode<T: Encodable>(_ value: T) -> String {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys]
    return String(decoding: try! encoder.encode(value), as: UTF8.self)
}

// MARK: - The drive

final class ManualClock { var now = Date(timeIntervalSince1970: 0) }

/// Runs the main queue until everything enqueued so far has run: the
/// engine's `receive(on: DispatchQueue.main)` hops deliver in order.
func drain() {
    var done = false
    DispatchQueue.main.async { done = true }
    while !done { _ = RunLoop.main.run(mode: .default, before: .distantFuture) }
}

func place(of moment: WayMoment, in geometry: WayGeometry) -> CLLocationCoordinate2D {
    if let at = moment.at { return CLLocationCoordinate2D(latitude: at.lat, longitude: at.lon) }
    return geometry.coordinate(atFrac: moment.frac)
}

func run(way: Way, trace: Trace, forceMiss: Bool) -> [Record] {
    let tap = DistanceTap.shared
    tap.forceMiss = forceMiss
    tap.calls = []
    // A clean CoreLocation cache for every run.
    let (x, y) = DistanceTap.farPair(from: way.route[0].lat)
    tap.bypass = true
    _ = x.distance(from: y)
    tap.bypass = false

    let clock = ManualClock()
    let engine = HonorEngine(way: way, softTapEnabled: trace.softTapEnabled, voicesEnabled: trace.voicesEnabled,
                             now: { clock.now })
    let locations = PassthroughSubject<CLLocation, Never>()
    let durations = PassthroughSubject<TimeInterval, Never>()
    let gates: [String: PassthroughSubject<Bool, Never>] = [
        "paused": .init(), "meditating": .init(), "recording": .init(), "externalAudio": .init(),
    ]
    engine.bind(locations: locations.eraseToAnyPublisher(),
                activeDuration: durations.eraseToAnyPublisher(),
                isPaused: gates["paused"]!.eraseToAnyPublisher(),
                isMeditating: gates["meditating"]!.eraseToAnyPublisher(),
                isRecordingVoice: gates["recording"]!.eraseToAnyPublisher(),
                externalAudio: gates["externalAudio"]!.eraseToAnyPublisher())

    var events: [EventRecord] = []
    let missing = Set(trace.missingMedia)
    let sink = engine.events.sink { event in
        switch event {
        case .momentReached(let m): events.append(EventRecord(type: "momentReached", id: m.id))
        case .voiceStart(let m):
            events.append(EventRecord(type: "voiceStart", id: m.id))
            // ActiveWalkViewModel+Honor.swift:214-218@7c200bf: no file, so the turn goes straight back.
            if missing.contains(m.id) { engine.voiceDidFinish() }
        case .voicePause: events.append(EventRecord(type: "voicePause"))
        case .voiceResume: events.append(EventRecord(type: "voiceResume"))
        case .voiceDropped(let m): events.append(EventRecord(type: "voiceDropped", id: m.id))
        case .softTap(let meters): events.append(EventRecord(type: "softTap", offWayMeters: meters))
        case .markAhead(let mark, let meters): events.append(EventRecord(type: "markAhead", id: mark.id, meters: meters))
        case .arrived(let theirs, let yours):
            events.append(EventRecord(type: "arrived", theirSeconds: theirs, yourSeconds: yours))
        }
    }
    defer { sink.cancel(); engine.stop() }

    let end = way.route.last.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
    let places = way.moments.map { ($0.id, place(of: $0, in: engine.geometry)) }
    func target(of to: CLLocationCoordinate2D) -> String {
        if let end, to.latitude == end.latitude, to.longitude == end.longitude { return "end" }
        let ids = places.filter { $0.1.latitude == to.latitude && $0.1.longitude == to.longitude }.map(\.0)
        guard ids.count == 1 else { fatalError("a distance to (\(to.latitude), \(to.longitude)) matching \(ids)") }
        return ids[0]
    }
    func sinceT0(_ date: Date?) -> Double? { date.map { $0.timeIntervalSince1970 - trace.t0 } }

    func snapshot() -> StateRecord {
        let tracker = field(engine, "moments", as: HonorMomentTracker.self)
        let armed = field(engine, "softTapArmed", as: Bool.self)
        let since = field(engine, "softTapSince", as: Date?.self)
        let watchesWater = way.marks != nil
        return StateRecord(
            progressFrac: engine.progressFrac,
            distanceRemainingMeters: engine.distanceRemainingMeters,
            offWayMeters: engine.offWayMeters,
            isOnWay: engine.isOnWay,
            companionFrac: engine.companionFrac,
            phase: engine.phase == .walking ? "walking" : "arrived",
            startFrac: engine.startFrac,
            companionT0: engine.companionT0,
            distanceWalkedMeters: engine.distanceWalkedMeters,
            isAnchoredOnWay: engine.isAnchoredOnWay,
            softTap: !armed ? "disarmed" : (since == nil ? "armed" : "timing"),
            arrivalCount: field(engine, "arrival", as: ArrivalDebounce.self).consecutiveInside,
            offWaySince: sinceT0(field(engine, "offWaySince", as: Date?.self)),
            lastReacquireAttempt: sinceT0(field(engine, "lastReacquireAttempt", as: Date?.self)),
            playing: tracker.playing?.id,
            voicePaused: tracker.isVoicePaused,
            queue: field(tracker, "queue", as: [WayMoment].self).map(\.id),
            firedMarks: watchesWater ? field(tracker, "firedMarks", as: Set<String>.self).sorted() : nil,
            lastNoticeSeconds: watchesWater ? field(tracker, "lastMarkSeconds", as: TimeInterval?.self) : nil)
    }

    var records: [Record] = []
    var fixCount = 0
    var previous: CLLocation?
    for (i, input) in trace.inputs.enumerated() {
        clock.now = Date(timeIntervalSince1970: trace.t0 + input.t)
        events = []
        tap.calls = []
        var record = Record(i: i, kind: input.kind)
        switch input.kind {
        case "fix":
            let location = CLLocation(
                coordinate: CLLocationCoordinate2D(latitude: input.lat!, longitude: input.lon!),
                altitude: 0, horizontalAccuracy: input.accuracy ?? -1, verticalAccuracy: 5,
                course: -1, speed: input.speed ?? -1, timestamp: clock.now)
            // LocationManagement's route distance (LocationManagement.swift:291@7c200bf)
            // runs before the fix reaches the engine; it moves CoreLocation's cache as in the app.
            if let previous { _ = location.distance(from: previous) }
            previous = location
            tap.logging = true
            locations.send(location)
            drain()
            tap.logging = false
            record.fix = fixCount
            fixCount += 1
            record.calls = tap.calls.map { call in
                guard call.from.latitude == input.lat!, call.from.longitude == input.lon! else {
                    fatalError("input \(i): a distance from somewhere other than the fix")
                }
                let meters = call.meters
                return CallRecord(target: target(of: call.to), to: Coordinate(lat: call.to.latitude, lon: call.to.longitude),
                                  cl: forceMiss ? nil : meters, fresh: forceMiss ? meters : nil)
            }
        case "tick":
            durations.send(input.activeSeconds!)
            drain()
            record.companionFrac = engine.companionFrac
            records.append(record)
            continue
        case "gates":
            for name in ["paused", "meditating", "recording", "externalAudio"] {
                let value: Bool? = [
                    "paused": input.paused, "meditating": input.meditating,
                    "recording": input.recording, "externalAudio": input.externalAudio,
                ][name]!
                gates[name]!.send(value!)
            }
            drain()
        case "gate":
            gates[input.gate!]!.send(input.value!)
            drain()
        case "finish":
            guard field(engine, "moments", as: HonorMomentTracker.self).playing != nil else {
                fatalError("\(trace.name) input \(i): the player finished with no voice playing")
            }
            engine.voiceDidFinish()
            drain()
        default:
            fatalError("unknown input kind \(input.kind)")
        }
        record.events = events
        record.state = snapshot()
        records.append(record)
    }
    return records
}

/// Both runs must agree on everything but the measured values; otherwise
/// CoreLocation's cache decided something, and the corpus must change.
func merge(_ natural: [Record], _ fresh: [Record], trace: String) -> [Record] {
    precondition(natural.count == fresh.count)
    return zip(natural, fresh).map { a, b in
        var stripped = b
        stripped.calls = b.calls.map { $0.map { var c = $0; c.fresh = nil; return c } }
        var bare = a
        bare.calls = a.calls.map { $0.map { var c = $0; c.cl = nil; return c } }
        guard bare == stripped else {
            fatalError("\(trace) input \(a.i): the engine behaved differently with and without CoreLocation's cache")
        }
        var merged = a
        merged.calls = a.calls.map { calls in zip(calls, b.calls!).map { var c = $0; c.fresh = $1.fresh; return c } }
        return merged
    }
}

// MARK: - Files

func writeCorpus(to dir: URL) throws {
    let encoder = JSONEncoder()
    encoder.dateEncodingStrategy = .iso8601  // WayStore.swift:26-31@7c200bf
    encoder.outputFormatting = [.sortedKeys]
    for (way, trace) in corpus() {
        let folder = dir.appendingPathComponent(trace.name)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var wayJSON = try encoder.encode(way)
        wayJSON.append(0x0A)
        try wayJSON.write(to: folder.appendingPathComponent("way.json"))
        // One input per line, so a diff shows which input moved.
        var text = "{\"about\":\(encode(trace.about)),\"inputs\":[\n"
        text += trace.inputs.map(encode).joined(separator: ",\n")
        text += "\n],\"missingMedia\":\(encode(trace.missingMedia)),\"name\":\(encode(trace.name))"
        text += ",\"softTapEnabled\":\(trace.softTapEnabled),\"t0\":\(encode(trace.t0)),\"voicesEnabled\":\(trace.voicesEnabled)}\n"
        try text.write(to: folder.appendingPathComponent("trace.json"), atomically: true, encoding: .utf8)
    }
}

func capture(corpus dir: URL, expected out: URL) throws {
    let decoder = JSONDecoder()
    decoder.dateDecodingStrategy = .iso8601
    try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
    let names = try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { !$0.hasPrefix(".") }.sorted()
    for name in names {
        let folder = dir.appendingPathComponent(name)
        let way = try decoder.decode(Way.self, from: Data(contentsOf: folder.appendingPathComponent("way.json")))
        let trace = try decoder.decode(Trace.self, from: Data(contentsOf: folder.appendingPathComponent("trace.json")))
        let records = merge(run(way: way, trace: trace, forceMiss: false), run(way: way, trace: trace, forceMiss: true),
                            trace: name)
        let text = records.map(encode).joined(separator: "\n") + "\n"
        try text.write(to: out.appendingPathComponent("\(name).jsonl"), atomically: true, encoding: .utf8)
        summarize(name, way: way, records: records)
    }
}

/// The run's outline on stdout, for the README and for a reviewer.
func summarize(_ name: String, way: Way, records: [Record]) {
    print("== \(name)")
    for r in records {
        for e in r.events ?? [] {
            let at = r.fix.map { "fix \($0)" } ?? "\(r.kind)"
            var line = "  input \(r.i) (\(at)): \(e.type)"
            if let id = e.id { line += " \(id)" }
            if let m = e.offWayMeters { line += String(format: " %.1f m", m) }
            if let m = e.meters { line += String(format: " %.3f m ahead", m) }
            if e.type == "markAhead", let clock = r.state?.lastNoticeSeconds { line += String(format: ", clock %.1f s", clock) }
            if let a = e.theirSeconds, let b = e.yourSeconds { line += String(format: " their %.1f s, your %.1f s", a, b) }
            print(line)
        }
    }
    let calls = records.flatMap { $0.calls ?? [] }
    let worst = calls.map { abs($0.cl! - $0.fresh!) }.max() ?? 0
    print(String(format: "  %d distance calls; cache effect up to %.3e m", calls.count, worst))
}

/// GeoDistanceTest's literals: CLLocation.distance with no history.
func writePairs(to file: URL) throws {
    let pairs: [(Double, Double, Double, Double)] = [
        (42.88, -8.54, 42.88, -8.5396333),
        (42.88, -8.54, 42.8803774, -8.54),
        (42.88, -8.54, 42.8801905, -8.5397398),
        (0.0, 32.61, 0.0, 32.6126951),
        (0.0, 32.61, 0.0026951, 32.61),
        (60.17, 24.94, 60.1702694, 24.9405406),
        (-33.87, 151.21, -33.8716169, 151.2129031),
        (64.14, -21.94, 64.1400898, -21.9364962),
        (47.6, -122.33, 47.6000377, -122.3299443),
        (51.5, -0.12, 51.5, -0.1199872),
        (51.4779, -0.0002, 51.4781, 0.0003),
        (-16.5, 179.9998, -16.5003, -179.9997),
        (35.0, 135.0, 35.9, 135.8),
    ]
    var text = "# lat1 lon1 lat2 lon2 CLLocation(lat1, lon1).distance(from: CLLocation(lat2, lon2)), no history\n"
    for (a, b, c, d) in pairs {
        let meters = DistanceTap.fresh(CLLocation(latitude: a, longitude: b), CLLocation(latitude: c, longitude: d))
        text += "\(a) \(b) \(c) \(d) \(meters)\n"
    }
    try text.write(to: file, atomically: true, encoding: .utf8)
}

/// The README's model of `CLLocation.distance`: WGS84 radii of curvature
/// at the pair's mean latitude, kept and reused while the receiver's
/// latitude is within 0.005° of the latitude they were computed at, and
/// longitudes taken into [0°, 360°) before subtracting. Checked against
/// CoreLocation over a seeded random walk of calls.
func checkCacheModel() {
    let a = 6_378_137.0, f = 1 / 298.257223563, e2 = f * (2 - f)
    var key: Double?
    var m = 0.0, nc = 0.0
    func model(_ p: CLLocationCoordinate2D, _ q: CLLocationCoordinate2D) -> Double {
        if key == nil || !(abs(p.latitude - key!) < 0.005) {
            let mean = (p.latitude + q.latitude) / 2, phi = mean * (.pi / 180), w = 1 - e2 * sin(phi) * sin(phi)
            key = mean
            m = a * (1 - e2) / (w * sqrt(w))
            nc = a / sqrt(w) * cos(phi)
        }
        func positive(_ lon: Double) -> Double { lon < 0 ? lon + 360 : lon }
        var dLon = positive(q.longitude) - positive(p.longitude)
        if dLon > 180 { dLon -= 360 } else if dLon < -180 { dLon += 360 }
        return hypot(m * ((q.latitude - p.latitude) * (.pi / 180)), nc * (dLon * (.pi / 180)))
    }
    var noise = Noise(seed: 2026)
    var lat = 0.0, lon = 0.0
    var worstRelative = 0.0, worstStale = 0.0, calls = 0
    for i in 0..<200_000 {
        if i % 4000 == 0 { lat = 70 * noise.unit(); lon = 170 * noise.unit() }
        lat += 0.0005 * noise.unit()
        lon += 0.0005 * noise.unit()
        let meters = (i % 7 == 0 ? 3000 : 400) * (noise.unit() + 1) / 2 + 1
        let bearing = Double.pi * noise.unit()
        let other = CLLocationCoordinate2D(latitude: lat + meters * cos(bearing) / 111_132,
                                           longitude: lon + meters * sin(bearing) / (111_320 * cos(lat * .pi / 180)))
        let here = CLLocationCoordinate2D(latitude: lat, longitude: lon)
        let (p, q) = i % 3 == 0 ? (other, here) : (here, other)
        let apple = CLLocation(latitude: p.latitude, longitude: p.longitude)
            .distance(from: CLLocation(latitude: q.latitude, longitude: q.longitude))
        let predicted = model(p, q)
        worstRelative = max(worstRelative, abs(apple - predicted) / apple)
        let fresh = DistanceTap.fresh(CLLocation(latitude: p.latitude, longitude: p.longitude),
                                      CLLocation(latitude: q.latitude, longitude: q.longitude))
        worstStale = max(worstStale, abs(apple - fresh) / apple)
        // Measuring `fresh` moved the cache; put the model's latitude back.
        let (x, y) = DistanceTap.farPair(from: key!)
        _ = x.distance(from: y)
        _ = CLLocation(latitude: key!, longitude: 0).distance(from: CLLocation(latitude: key!, longitude: 0.001))
        calls += 1
    }
    print(String(format: "cache model: %d calls, 1-3,000 m, |lat| <= 70; model vs CLLocation max relative %.1e; "
        + "cached vs no-history value max relative %.1e", calls, worstRelative, worstStale))
}

DistanceTap.install()
let args = CommandLine.arguments
switch args.count > 1 ? args[1] : "" {
case "corpus": try writeCorpus(to: URL(fileURLWithPath: args[2]))
case "capture": try capture(corpus: URL(fileURLWithPath: args[2]), expected: URL(fileURLWithPath: args[3]))
case "pairs": try writePairs(to: URL(fileURLWithPath: args[2]))
case "cache": checkCacheModel()
default:
    FileHandle.standardError.write("usage: harness corpus <dir> | capture <corpus> <expected> | pairs <file> | cache\n".data(using: .utf8)!)
    exit(2)
}
