// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
// Same shape as MapboxMaps.TileRegionError (OfflineErrors.swift:9-61 @ 11.20.0)
enum TileRegionError: LocalizedError, Equatable {
    case canceled(String), doesNotExist(String), tilesetDescriptor(String), diskFull(String), other(String), tileCountExceeded(String)
    var errorDescription: String? {
        switch self {
        case let .canceled(m), let .doesNotExist(m), let .tilesetDescriptor(m), let .diskFull(m), let .other(m), let .tileCountExceeded(m): return m
        }
    }
}
// Verbatim copy of WayMediaDownloader.isDiskFull @ 7c200bf:242-248
func isDiskFull(_ error: Error) -> Bool {
    if (error as? URLError)?.code == .cannotWriteToFile { return true }
    let nsError = error as NSError
    if nsError.domain == NSCocoaErrorDomain && nsError.code == NSFileWriteOutOfSpaceError { return true }
    if let underlying = nsError.userInfo[NSUnderlyingErrorKey] as? NSError { return isDiskFull(underlying) }
    return false
}
for e in [TileRegionError.other("No space left on device (ENOSPC)"), .diskFull("x"), .other("database or disk is full")] {
    let ns = e as NSError
    print(e, "domain=\(ns.domain) code=\(ns.code) userInfoKeys=\(ns.userInfo.keys.sorted()) isDiskFull=\(isDiskFull(e))")
}
