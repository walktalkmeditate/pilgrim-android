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

// Fixtures, verbatim from the tests.
func stagePoints(lonOffset: Double) -> [CLLocationCoordinate2D] {
    (0...30).map { i in CLLocationCoordinate2D(latitude: 42, longitude: lonOffset + Double(i) * 0.001209) }
}
func stagesPoints(_ count: Int) -> [[CLLocationCoordinate2D]] {
    (0..<count).map { stagePoints(lonOffset: Double($0) * 0.04) }
}
func straight(km: Double, lat: Double = 42) -> [CLLocationCoordinate2D] {
    let metersPerDegreeLon = 111_320 * cos(lat * .pi / 180)
    let steps = Int(km * 10)
    return (0...steps).map { i in CLLocationCoordinate2D(latitude: lat, longitude: Double(i) * 100 / metersPerDegreeLon) }
}

print("MemoryLayout<Int>.size =", MemoryLayout<Int>.size)
print("version 2 bytes:", withUnsafeBytes(of: 2) { Array($0) })
print("1.0e0 rounded raw bytes of 42e6:", withUnsafeBytes(of: (42.0 * 1_000_000).rounded()) { Array($0).map { String(format: "%02x", $0) }.joined() })

// 1. Hand-built ring hash (no trig).
let square: [CLLocationCoordinate2D] = [
    CLLocationCoordinate2D(latitude: 0, longitude: 0),
    CLLocationCoordinate2D(latitude: 1, longitude: 0),
    CLLocationCoordinate2D(latitude: 1, longitude: 1),
    CLLocationCoordinate2D(latitude: 0, longitude: 1),
    CLLocationCoordinate2D(latitude: 0, longitude: 0)
]
print("hash(square, v2) =", corridorHash([square]))
print("hash(square, v1) =", corridorHash([square], version: 1))
print("hash([], v2) =", corridorHash([]))
print("input(square, v2) =", hashInputHex([square]))
// Rounding probes
let halfUp: [CLLocationCoordinate2D] = [CLLocationCoordinate2D(latitude: 0.0000005, longitude: -0.0000005)]
print("half probe input:", hashInputHex([halfUp]))
print("(0.0000005*1e6) =", 0.0000005 * 1_000_000, "rounded", (0.0000005 * 1_000_000).rounded())
print("(-0.0000005*1e6) rounded", (-0.0000005 * 1_000_000).rounded())
print("(2.5).rounded", (2.5).rounded(), "(-2.5).rounded", (-2.5).rounded())
print("-0.4 rounded =", (-0.4).rounded(), "sign", (-0.4).rounded().sign == .minus)
print("(-0.4).rounded bytes", withUnsafeBytes(of: (-0.4).rounded()) { Array($0).map { String(format: "%02x", $0) }.joined() })

// 2. The manager test fixture.
let s0 = stagePoints(lonOffset: 0)
let simp = G.simplified(s0, toleranceMeters: 25)
print("stage(0) simplified count:", simp.count, simp.map { "\($0.latitude),\($0.longitude)" })
let rings0 = G.corridor(around: s0, halfWidthMeters: 500)
print("stage(0) parts:", rings0.count)
for (i, r) in rings0.enumerated() {
    print(" part \(i):", r.map { String(format: "(%.17g, %.17g)", $0.latitude, $0.longitude) }.joined(separator: " "))
}
print("hash(stage0 corridor, v2) =", corridorHash(rings0))
print("hash(stage0 corridor, v1) =", corridorHash(rings0, version: 1))
let two = stagesPoints(2)
let ringsEach = two.map { G.corridor(around: $0, halfWidthMeters: 500) }
print("packCount each z11:", ringsEach.map { D.tileCount(rings: $0, zooms: 11...11) })
print("packCount two together:", D.tileCount(rings: ringsEach.flatMap { $0 }, zooms: 11...11))
print("cells stage0:", D.cells(rings: ringsEach[0], z: 11), "stage1:", D.cells(rings: ringsEach[1], z: 11))
let three = stagesPoints(3).map { G.corridor(around: $0, halfWidthMeters: 500) }
print("packCount three each:", three.map { D.tileCount(rings: $0, zooms: 11...11) }, "together:", D.tileCount(rings: three.flatMap { $0 }, zooms: 11...11))
print("cells three:", D.cells(rings: three.flatMap { $0 }, z: 11))
let four = stagesPoints(4).map { G.corridor(around: $0, halfWidthMeters: 500) }
print("packCount four together:", D.tileCount(rings: four.flatMap { $0 }, zooms: 11...11))
let seven = stagesPoints(7).map { G.corridor(around: $0, halfWidthMeters: 500) }
print("packCount seven together:", D.tileCount(rings: seven.flatMap { $0 }, zooms: 11...11))
print("calibrate two at 400_000 each:", 800_000 / D.tileCount(rings: ringsEach.flatMap { $0 }, zooms: 11...11))
// Redrawn stage 1 fixture
let redrawn = G.corridor(around: stagePoints(lonOffset: 0.04 + 0.01), halfWidthMeters: 500)
print("redrawn stage1 hash differs:", corridorHash(redrawn) != corridorHash(ringsEach[1]))

// 3. Descriptors test fixture.
let longLine = (0...300).map { i in CLLocationCoordinate2D(latitude: 33.8, longitude: 135.5 + Double(i) * 0.001) }
let longRings = G.corridor(around: longLine, halfWidthMeters: 500)
print("long line simplified:", G.simplified(longLine, toleranceMeters: 25).count, "parts:", longRings.count)
for z in 11...15 { print(" z\(z):", D.tileCount(rings: longRings, zooms: z...z)) }
print(" z11...14 summed:", D.tileCount(rings: longRings, zooms: 11...14))

// 4. Corridor tests fixtures.
let line3 = straight(km: 3)
print("straight(3) count", line3.count, "parts", G.corridor(around: line3, halfWidthMeters: 500).count)
print("one point parts", G.corridor(around: [CLLocationCoordinate2D(latitude: 42, longitude: 0)], halfWidthMeters: 500).count)
let p = CLLocationCoordinate2D(latitude: 42, longitude: 0)
print("two identical parts", G.corridor(around: [p, p], halfWidthMeters: 500).count)
print("empty parts", G.corridor(around: [], halfWidthMeters: 500).count)
print("three identical simplified", G.simplified([p, p, p], toleranceMeters: 25).count, "parts", G.corridor(around: [p, p, p], halfWidthMeters: 500).count)
// Exactly-at-tolerance probe: a midpoint exactly 25 m off a 2-point line keeps or drops?
let lonScale = 111_320.0 * cos(0.0)
let a0 = CLLocationCoordinate2D(latitude: 0, longitude: 0)
let b0 = CLLocationCoordinate2D(latitude: 0, longitude: 200 / lonScale)
let m25 = CLLocationCoordinate2D(latitude: 25 / 111_320.0, longitude: 100 / lonScale)
let m26 = CLLocationCoordinate2D(latitude: 26 / 111_320.0, longitude: 100 / lonScale)
print("25 m midpoint kept?", G.simplified([a0, m25, b0], toleranceMeters: 25).count, " 26 m:", G.simplified([a0, m26, b0], toleranceMeters: 25).count)
// Identity: does simplified return the original values?
let odd = CLLocationCoordinate2D(latitude: 0.123456789123, longitude: 0.987654321987)
print("identity:", G.simplified([a0, odd, b0], toleranceMeters: 25).map { "\($0.latitude),\($0.longitude)" })
// Closed loop: first == last
let loop = [a0, CLLocationCoordinate2D(latitude: 0.01, longitude: 0), CLLocationCoordinate2D(latitude: 0.01, longitude: 0.01), a0]
print("closed loop simplified:", G.simplified(loop, toleranceMeters: 25).count)

// 5. megabytes
for b in [0, 1, 400_000, 499_999, 500_000, 1_499_999, 1_500_000, 1_900_000, 2_500_000, 26_100_000, 26_400_000, 999_999_999, -3_000_000] {
    print("megabytes(\(b)) =", megabytes(b))
}

// 6. tile maths edges
print("tile(85.1, 180) at z11:", D.tile(lat: 85.1, lon: 180, n: 2048), "tile(-90,-180):", D.tile(lat: -89.9, lon: -180, n: 2048))
print("tile(90, 0):", D.tile(lat: 90, lon: 0, n: 2048))

// 7. Extra hash vectors for the JVM comparison.
print("half probe hash =", corridorHash([[CLLocationCoordinate2D(latitude: 0.0000005, longitude: -0.0000005)]]))
print("neg-zero probe hash =", corridorHash([[CLLocationCoordinate2D(latitude: -0.0000004, longitude: 0.0000004)]]))
