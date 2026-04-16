# Pilgrim for Android

A contemplative walking-meditation app. Native Kotlin + Jetpack Compose.

**Status:** In development. This is the Android port of [Pilgrim for iOS](../pilgrim-ios). MVP scope — walk tracking, voice recording, journal. More surfaces land phase by phase.

## What Pilgrim is

A walk is a creative practice, not a fitness metric. Pilgrim captures GPS routes, voice reflections transcribed on-device, meditation moments, and weaves them with celestial context (moon phase, planetary hour), AI-assisted writing prompts, and generative art (goshuin seals, etegami postcards). Everything stays on the device. No accounts. No analytics. No cloud sync.

> The road is made by walking.

## Build

Requires JDK 17 and the Android SDK (platform 35 or newer).

```bash
./gradlew assembleDebug
```

For Mapbox maps to render, copy `local.properties.example` to `local.properties` and set your public access token:

```
MAPBOX_ACCESS_TOKEN=pk.xxx
```

(`MAPBOX_DOWNLOADS_TOKEN` is only required once the Mapbox Maven repo is added in Phase 1 — see `local.properties.example`.)

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

Copyright © 2026 Walk Talk Meditate contributors.
