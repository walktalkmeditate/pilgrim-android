# own-walk-way.json capture

`../own-walk-way.json` is written by iOS's own code, not by hand. The harness runs `OwnWalkWayBuilder` at the parity pin (`7c200bf`) over a fixed walk, and encodes the result with `WayStore`'s encoder (`.iso8601` dates, `[.sortedKeys]`). `WayCodecTest` then checks Android's model and builder against that output.

- `capture.sh` fetches `Way.swift`, `WayGeometry.swift`, and `OwnWalkWayBuilder.swift` from pilgrim-ios at the pin, compiles them with the two files here, and rewrites the fixture. It needs macOS with `swiftc` and a pilgrim-ios checkout (by default the repo's sibling; set `IOS_REPO` otherwise).
- `Stubs.swift` stands in for the app's data interfaces, covering only the members the builder reads, with the pin's exact types.
- `main.swift` is the walk. It has a voice with a transcript, a recording whose file is gone, one with no path, two photos, a waypoint plus the two reserved arrival icons, two pauses (one long enough to be a rest), a sitting, and a one-minute plateau.

Rerun it when the parity pin moves. A changed fixture means iOS changed its builder or its wire format.
