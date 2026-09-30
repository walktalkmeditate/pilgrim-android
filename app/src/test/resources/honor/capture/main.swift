// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

// Builds the U13 own-walk fixture with iOS's real OwnWalkWayBuilder and
// encodes it exactly as WayStore.swift:26-31@7c200bf does. Run with
// CFFIXED_USER_HOME pointing at a scratch home (the builder's file probe
// reads Documents) and TZ=Europe/Madrid (the builder stamps
// TimeZone.current.identifier).
//
//   usage: harness <output way.json>

let start = Date(timeIntervalSince1970: 1_777_622_400)  // 2026-05-01T08:00:00Z
func at(_ seconds: Double) -> Date { start.addingTimeInterval(seconds) }

let uuid = UUID(uuidString: "E621E1F8-C36C-495A-93FC-0C247A3E6E5F")!
let folder = "Recordings/\(uuid.uuidString)"

// Twenty fixes, one a minute from 5 s after the start, drifting north-east.
// Fixes 6 and 7 share a place: a minute standing still (a distance plateau).
let samples: [Sample] = (0..<20).map { i in
    let p = Double(i <= 6 ? i : i - 1)
    return Sample(timestamp: at(5 + 60 * Double(i)),
                  latitude: 42.88 + 0.0003 * p,
                  longitude: -8.54 + 0.00122 * p,
                  altitude: 300 + 1.5 * Double(i))
}.reversed()

let recordings = [
    Recording(startDate: at(130), endDate: at(190), duration: 60,
              fileRelativePath: "\(folder)/r1.m4a",
              transcription: "  I keep coming back to the river. It sounds different in the morning, slower somehow.\n"),
    Recording(startDate: at(300), endDate: at(320), duration: 20,
              fileRelativePath: "\(folder)/gone.m4a", transcription: "this file was deleted in the app"),
    Recording(startDate: at(310), endDate: at(315), duration: 5,
              fileRelativePath: "", transcription: nil),
    Recording(startDate: at(490), endDate: at(510), duration: 20,
              fileRelativePath: "\(folder)/r2.m4a", transcription: "birds, mostly"),
    Recording(startDate: at(1040), endDate: at(1100), duration: 60,
              fileRelativePath: "\(folder)/r3.m4a", transcription: nil),
]

let walk = FixtureWalk(
    uuid: uuid,
    startDate: start,
    comment: "  the long way round  ",
    activeDuration: 795,
    routeData: samples,
    pauses: [
        Pause(startDate: at(320), endDate: at(400)),
        Pause(startDate: at(600), endDate: at(870)),
    ],
    voiceRecordings: recordings,
    activityIntervals: [
        Sitting(activityType: .meditation, startDate: at(880), endDate: at(1030)),
    ],
    waypoints: [
        Mark(latitude: 42.8815, longitude: -8.5330, label: "Oak", icon: "leaf", timestamp: at(128)),
        Mark(latitude: 42.8830, longitude: -8.5290, label: "Found it", icon: "sun.haze", timestamp: at(700)),
        Mark(latitude: 42.8854, longitude: -8.5181, label: "Walked their way: x", icon: "signpost.right.fill", timestamp: at(1140)),
    ],
    walkPhotos: [
        Photo(localIdentifier: "9F1C2B7A-3D4E-4F50-8A6B-7C8D9E0F1A2B/L0/002", capturedAt: at(275),
              capturedLat: 42.8807, capturedLng: -8.5351),
        Photo(localIdentifier: "9F1C2B7A-3D4E-4F50-8A6B-7C8D9E0F1A2B/L0/001", capturedAt: at(274),
              capturedLat: 42.8806, capturedLng: -8.5352),
    ],
    weatherCondition: "clear",
    weatherTemperature: 14.5)

let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
try FileManager.default.createDirectory(at: docs.appendingPathComponent(folder), withIntermediateDirectories: true)
for name in ["r1.m4a", "r2.m4a", "r3.m4a"] {
    try Data(repeating: 1, count: 64).write(to: docs.appendingPathComponent("\(folder)/\(name)"))
}
try? FileManager.default.removeItem(at: docs.appendingPathComponent("\(folder)/gone.m4a"))

guard let way = OwnWalkWayBuilder.make(from: walk) else { fatalError("the builder returned nil") }

// WayStore.swift:26-31@7c200bf
let encoder: JSONEncoder = {
    let e = JSONEncoder()
    e.dateEncodingStrategy = .iso8601
    e.outputFormatting = [.sortedKeys]
    return e
}()
try encoder.encode(way).write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
