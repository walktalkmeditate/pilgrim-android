// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

// Minimal stand-ins for the app types OwnWalkWayBuilder.swift@7c200bf reads.
// Only the members the builder touches, with the pin's exact types
// (Pilgrim/Protocols/DataInterfaces/*.swift@7c200bf). Way.swift,
// WayGeometry.swift, and OwnWalkWayBuilder.swift are copied verbatim.

protocol RouteDataSampleInterface {
    var timestamp: Date { get }
    var latitude: Double { get }
    var longitude: Double { get }
    var altitude: Double { get }
}

protocol VoiceRecordingInterface {
    var startDate: Date { get }
    var endDate: Date { get }
    var duration: Double { get }
    var fileRelativePath: String { get }
    var transcription: String? { get }
}

protocol WalkPhotoInterface {
    var localIdentifier: String { get }
    var capturedAt: Date { get }
    var capturedLat: Double { get }
    var capturedLng: Double { get }
}

protocol WaypointInterface {
    var latitude: Double { get }
    var longitude: Double { get }
    var label: String { get }
    var icon: String { get }
    var timestamp: Date { get }
}

protocol WalkPauseInterface {
    var startDate: Date { get }
    var endDate: Date { get }
}

enum ActivityInterval {
    enum ActivityType { case unknown, meditation }
}

protocol ActivityIntervalInterface {
    var activityType: ActivityInterval.ActivityType { get }
    var startDate: Date { get }
    var endDate: Date { get }
}

protocol WalkInterface {
    var uuid: UUID? { get }
    var startDate: Date { get }
    var comment: String? { get }
    var activeDuration: Double { get }
    var routeData: [RouteDataSampleInterface] { get }
    var pauses: [WalkPauseInterface] { get }
    var voiceRecordings: [VoiceRecordingInterface] { get }
    var activityIntervals: [ActivityIntervalInterface] { get }
    var waypoints: [WaypointInterface] { get }
    var walkPhotos: [WalkPhotoInterface] { get }
    var weatherCondition: String? { get }
    var weatherTemperature: Double? { get }
}

// Verbatim: Pilgrim/Models/Share/TourBuilder.swift:3,38-45@7c200bf
enum TourRecordingKind: String { case spoken, ambient }

enum TourBuilder {
    static func classify(transcription: String?) -> TourRecordingKind {
        guard let text = transcription?.trimmingCharacters(in: .whitespacesAndNewlines) else {
            return .spoken
        }
        let wordCount = text.split(whereSeparator: \.isWhitespace).count
        if wordCount < 8 { return .ambient }
        return .spoken
    }
}

// Verbatim: Pilgrim/Models/Walk/Seek/SeekPersistence.swift:14-18@7c200bf
enum SeekPersistence {
    static let arrivalWaypointIcon = "sun.haze"

    static func isArrivalWaypoint(_ waypoint: WaypointInterface) -> Bool {
        waypoint.icon == arrivalWaypointIcon
    }
}

// Verbatim: Pilgrim/Models/Honor/HonorPersistence.swift:10,26-28@7c200bf
enum HonorPersistence {
    static let arrivalWaypointIcon = "signpost.right.fill"

    static func isArrivalWaypoint(_ waypoint: WaypointInterface) -> Bool {
        waypoint.icon == arrivalWaypointIcon
    }
}

// Plain value rows for the fixture walk.
struct Sample: RouteDataSampleInterface {
    let timestamp: Date, latitude: Double, longitude: Double, altitude: Double
}
struct Recording: VoiceRecordingInterface {
    let startDate: Date, endDate: Date, duration: Double, fileRelativePath: String, transcription: String?
}
struct Photo: WalkPhotoInterface {
    let localIdentifier: String, capturedAt: Date, capturedLat: Double, capturedLng: Double
}
struct Mark: WaypointInterface {
    let latitude: Double, longitude: Double, label: String, icon: String, timestamp: Date
}
struct Pause: WalkPauseInterface {
    let startDate: Date, endDate: Date
}
struct Sitting: ActivityIntervalInterface {
    let activityType: ActivityInterval.ActivityType, startDate: Date, endDate: Date
}
struct FixtureWalk: WalkInterface {
    let uuid: UUID?
    let startDate: Date
    let comment: String?
    let activeDuration: Double
    let routeData: [RouteDataSampleInterface]
    let pauses: [WalkPauseInterface]
    let voiceRecordings: [VoiceRecordingInterface]
    let activityIntervals: [ActivityIntervalInterface]
    let waypoints: [WaypointInterface]
    let walkPhotos: [WalkPhotoInterface]
    let weatherCondition: String?
    let weatherTemperature: Double?
}
