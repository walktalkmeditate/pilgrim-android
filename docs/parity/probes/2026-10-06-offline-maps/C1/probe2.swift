// SPDX-License-Identifier: GPL-3.0-or-later
import CoreLocation
import CryptoKit
import Foundation

// Verbatim copies of the iOS functions at 7c200bf (WayGeometry corridor block,
// PilgrimageTilesDescriptors.tileCount and helpers, corridorHash, megabytes).

enum G {
    static func corridor(around points: [CLLocationCoordinate2D], halfWidthMeters h: Double) -> [[CLLocationCoordinate2D]] {
        let line = simplified(points, toleranceMeters: 25)
        guard let first = line.first else { return [] }
        let latScale = 111_320.0
        let lonScale = 111_320.0 * cos(first.latitude * .pi / 180)
        let local = line.map { (x: ($0.longitude - first.longitude) * lonScale, y: ($0.latitude - first.latitude) * latScale) }
        func geo(_ p: (x: Double, y: Double)) -> CLLocationCoordinate2D {
            CLLocationCoordinate2D(latitude: first.latitude + p.y / latScale, longitude: first.longitude + p.x / lonScale)
        }
        func square(_ v: (x: Double, y: Double)) -> [CLLocationCoordinate2D] {
            [geo((v.x - h, v.y - h)), geo((v.x + h, v.y - h)), geo((v.x + h, v.y + h)), geo((v.x - h, v.y + h)), geo((v.x - h, v.y - h))]
        }
        var parts: [[CLLocationCoordinate2D]] = []
        for i in 0..<local.count {
            if i + 1 < local.count {
                let a = local[i], b = local[i + 1]
                var dx = b.x - a.x, dy = b.y - a.y
                let len = (dx * dx + dy * dy).squareRoot()
                if len > 0 {
                    dx /= len; dy /= len
                    let nx = -dy * h, ny = dx * h
                    parts.append([geo((a.x - nx, a.y - ny)), geo((b.x - nx, b.y - ny)),
                                  geo((b.x + nx, b.y + ny)), geo((a.x + nx, a.y + ny)), geo((a.x - nx, a.y - ny))])
                }
            }
            parts.append(square(local[i]))
        }
        return parts
    }

    static func corridorContains(_ rings: [[CLLocationCoordinate2D]], _ point: CLLocationCoordinate2D) -> Bool {
        rings.contains { ringContains($0, point) }
    }

    static func simplified(_ points: [CLLocationCoordinate2D], toleranceMeters: Double) -> [CLLocationCoordinate2D] {
        guard points.count > 2, let first = points.first else { return points }
        let latScale = 111_320.0
        let lonScale = 111_320.0 * cos(first.latitude * .pi / 180)
        let local = points.map { (x: ($0.longitude - first.longitude) * lonScale, y: ($0.latitude - first.latitude) * latScale) }
        var keep = [Bool](repeating: false, count: points.count)
        keep[0] = true
        keep[points.count - 1] = true
        var stack: [(Int, Int)] = [(0, points.count - 1)]
        while let (a, b) = stack.popLast() {
            guard b - a > 1 else { continue }
            let ax = local[a].x, ay = local[a].y, bx = local[b].x, by = local[b].y
            let dx = bx - ax, dy = by - ay
            let lenSq = dx * dx + dy * dy
            var farthest = -1.0, index = a
            for i in (a + 1)..<b {
                let px = local[i].x - ax, py = local[i].y - ay
                let distance: Double
                if lenSq > 0 {
                    let u = max(0, min(1, (px * dx + py * dy) / lenSq))
                    let cx = px - u * dx, cy = py - u * dy
                    distance = (cx * cx + cy * cy).squareRoot()
                } else {
                    distance = (px * px + py * py).squareRoot()
                }
                if distance > farthest { farthest = distance; index = i }
            }
            if farthest > toleranceMeters {
                keep[index] = true
                stack.append((a, index))
                stack.append((index, b))
            }
        }
        return zip(points, keep).compactMap { $1 ? $0 : nil }
    }

    static func ringContains(_ ring: [CLLocationCoordinate2D], _ point: CLLocationCoordinate2D) -> Bool {
        guard ring.count > 3 else { return false }
        var inside = false
        var j = ring.count - 1
        for i in 0..<ring.count {
            let yi = ring[i].latitude, xi = ring[i].longitude
            let yj = ring[j].latitude, xj = ring[j].longitude
            if (yi > point.latitude) != (yj > point.latitude) {
                let x = (xj - xi) * (point.latitude - yi) / (yj - yi) + xi
                if point.longitude < x { inside.toggle() }
            }
            j = i
        }
        return inside
    }
}

enum D {
    static func tileCount(rings: [[CLLocationCoordinate2D]], zooms: ClosedRange<Int>) -> Int {
        let parts = rings.filter { $0.count > 3 }
        guard !parts.isEmpty else { return 0 }
        let boxes = parts.map { part -> (minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) in
            let lats = part.map(\.latitude), lons = part.map(\.longitude)
            return (lats.min()!, lats.max()!, lons.min()!, lons.max()!)
        }
        var total = 0
        for z in zooms {
            let n = Double(1 << z)
            let (xMin, yMax) = tile(lat: boxes.map(\.minLat).min()!, lon: boxes.map(\.minLon).min()!, n: n)
            let (xMax, yMin) = tile(lat: boxes.map(\.maxLat).max()!, lon: boxes.map(\.maxLon).max()!, n: n)
            for x in xMin...xMax {
                for y in yMin...yMax {
                    let southWest = coordinate(x: Double(x), y: Double(y + 1), n: n)
                    let northEast = coordinate(x: Double(x + 1), y: Double(y), n: n)
                    let touched = parts.indices.contains { index in
                        let box = boxes[index]
                        return box.maxLat >= southWest.latitude && box.minLat <= northEast.latitude
                            && box.maxLon >= southWest.longitude && box.minLon <= northEast.longitude
                            && tileTouches(parts[index], x: x, y: y, n: n)
                    }
                    if touched { total += 1 }
                }
            }
        }
        return total
    }

    static func cells(rings: [[CLLocationCoordinate2D]], z: Int) -> [(Int, Int)] {
        let parts = rings.filter { $0.count > 3 }
        guard !parts.isEmpty else { return [] }
        let boxes = parts.map { part -> (minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) in
            let lats = part.map(\.latitude), lons = part.map(\.longitude)
            return (lats.min()!, lats.max()!, lons.min()!, lons.max()!)
        }
        var out: [(Int, Int)] = []
        let n = Double(1 << z)
        let (xMin, yMax) = tile(lat: boxes.map(\.minLat).min()!, lon: boxes.map(\.minLon).min()!, n: n)
        let (xMax, yMin) = tile(lat: boxes.map(\.maxLat).max()!, lon: boxes.map(\.maxLon).max()!, n: n)
        for x in xMin...xMax {
            for y in yMin...yMax {
                let southWest = coordinate(x: Double(x), y: Double(y + 1), n: n)
                let northEast = coordinate(x: Double(x + 1), y: Double(y), n: n)
                let touched = parts.indices.contains { index in
                    let box = boxes[index]
                    return box.maxLat >= southWest.latitude && box.minLat <= northEast.latitude
                        && box.maxLon >= southWest.longitude && box.minLon <= northEast.longitude
                        && tileTouches(parts[index], x: x, y: y, n: n)
                }
                if touched { out.append((x, y)) }
            }
        }
        return out
    }

    static func tile(lat: Double, lon: Double, n: Double) -> (x: Int, y: Int) {
        let x = Int(floor((lon + 180) / 360 * n))
        let latRad = lat * .pi / 180
        let y = Int(floor((1 - log(tan(latRad) + 1 / cos(latRad)) / .pi) / 2 * n))
        return (min(max(x, 0), Int(n) - 1), min(max(y, 0), Int(n) - 1))
    }

    static func coordinate(x: Double, y: Double, n: Double) -> CLLocationCoordinate2D {
        let lon = x / n * 360 - 180
        let lat = atan(sinh(.pi * (1 - 2 * y / n))) * 180 / .pi
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    static func tileTouches(_ ring: [CLLocationCoordinate2D], x: Int, y: Int, n: Double) -> Bool {
        let probes = [
            coordinate(x: Double(x), y: Double(y), n: n),
            coordinate(x: Double(x + 1), y: Double(y), n: n),
            coordinate(x: Double(x), y: Double(y + 1), n: n),
            coordinate(x: Double(x + 1), y: Double(y + 1), n: n),
            coordinate(x: Double(x) + 0.5, y: Double(y) + 0.5, n: n)
        ]
        if probes.contains(where: { G.ringContains(ring, $0) }) { return true }
        let west = coordinate(x: Double(x), y: Double(y + 1), n: n), east = coordinate(x: Double(x + 1), y: Double(y), n: n)
        return ring.contains { $0.longitude >= west.longitude && $0.longitude <= east.longitude
            && $0.latitude >= west.latitude && $0.latitude <= east.latitude }
    }
}

func corridorHash(_ rings: [[CLLocationCoordinate2D]], version: Int = 2) -> String {
    var data = Data()
    data.append(contentsOf: withUnsafeBytes(of: version) { Array($0) })
    for ring in rings {
        for point in ring {
            data.append(contentsOf: withUnsafeBytes(of: (point.latitude * 1_000_000).rounded()) { Array($0) })
            data.append(contentsOf: withUnsafeBytes(of: (point.longitude * 1_000_000).rounded()) { Array($0) })
        }
    }
    return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
}

func hashInputHex(_ rings: [[CLLocationCoordinate2D]], version: Int = 2) -> String {
    var data = Data()
    data.append(contentsOf: withUnsafeBytes(of: version) { Array($0) })
    for ring in rings {
        for point in ring {
            data.append(contentsOf: withUnsafeBytes(of: (point.latitude * 1_000_000).rounded()) { Array($0) })
            data.append(contentsOf: withUnsafeBytes(of: (point.longitude * 1_000_000).rounded()) { Array($0) })
        }
    }
    return data.map { String(format: "%02x", $0) }.joined()
}

func megabytes(_ bytes: Int) -> String {
    "\(max(1, Int((Double(bytes) / 1_000_000).rounded()))) MB"
}

// tileTouches vs a dense-sampling truth on long diagonal quads.
func truthCells(_ rings: [[CLLocationCoordinate2D]], z: Int) -> Set<String> {
    let n = Double(1 << z)
    var out = Set<String>()
    for ring in rings {
        // Bilinear sampling across the convex quad/square (ring[0..3]).
        let a = ring[0], b = ring[1], c = ring[2], d = ring[3]
        let steps = 400
        for i in 0...steps {
            for j in 0...20 {
                let u = Double(i) / Double(steps), v = Double(j) / 20
                let lat = (1 - v) * ((1 - u) * a.latitude + u * b.latitude) + v * ((1 - u) * d.latitude + u * c.latitude)
                let lon = (1 - v) * ((1 - u) * a.longitude + u * b.longitude) + v * ((1 - u) * d.longitude + u * c.longitude)
                let t = D.tile(lat: lat, lon: lon, n: n)
                out.insert("\(t.x),\(t.y)")
            }
        }
    }
    return out
}
var misses = 0, cases = 0
for k in 0..<40 {
    let startLat = 42.0 + Double(k) * 0.013, startLon = -1.0 + Double(k) * 0.017
    let line = [CLLocationCoordinate2D(latitude: startLat, longitude: startLon),
                CLLocationCoordinate2D(latitude: startLat + 0.55, longitude: startLon + 0.9)]
    let rings = G.corridor(around: line, halfWidthMeters: 500)
    let counted = Set(D.cells(rings: rings, z: 11).map { "\($0.0),\($0.1)" })
    let truth = truthCells(rings, z: 11)
    cases += 1
    let missed = truth.subtracting(counted), extra = counted.subtracting(truth)
    if !missed.isEmpty { misses += 1 }
    if k < 6 || !missed.isEmpty { print("case \(k): truth \(truth.count) counted \(counted.count) missed \(missed.sorted()) extra \(extra.sorted())") }
}
print("cases with a missed cell:", misses, "of", cases)
