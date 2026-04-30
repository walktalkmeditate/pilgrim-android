# Cross-Platform `.pilgrim` Round-Trip QA Checklist

**Stage:** 11 — Item #1 (the round-trip QA component bundled into the cache + milestone PR).
**Validates:** Stage 10-I claim that `.pilgrim` exports/imports cross-platform iPhone↔Android.
**Format:** manual paired-device test. No code; checklist-driven.

## Setup

- iOS device with Pilgrim 1.3+ (latest TestFlight or App Store).
- Android device with the Stage 11 build installed.
- At least 3 finished walks on each side, ideally with a mix of:
  - Pinned photos (some)
  - Voice recordings (some)
  - Waypoints (some)
  - Intention text on at least one walk

## Procedure A — iOS → Android

1. iOS: Settings → Data → Export `.pilgrim` (with photos).
2. AirDrop / Drive / email the file to the Android device.
3. Android: Settings → Data → Import → pick the `.pilgrim`.
4. Verify on Android:
   - [ ] Walk count matches iOS (post-import).
   - [ ] Each walk's distance: within **5%** of iOS values. Drift is expected — Android's `Location.distanceTo` (Vincenty WGS84) and iOS's `CLLocation.distance(from:)` (haversine) are different geodesy implementations; over a 5km walk the two can differ by 5–20m. Additionally, Android currently sums all stored RouteDataSamples while iOS gates on `checkForAppropriateAccuracy` — bad-fix samples produce extra divergence on poor-GPS walks.
   - [ ] Each walk's duration: within **1 second** (wall-clock arithmetic; should match exactly).
   - [ ] Each walk's meditation: within **1 second** (both platforms now apply the same iOS-style clamp `min(rawMeditation, activeDuration)`).
   - [ ] Pinned photos appear in reliquary (or skip-count surfaced if iOS sourced from Photos that don't resolve on Android — expected).
   - [ ] Walk intention text round-trips.
   - [ ] Goshuin seal regenerates with the same hash → same visual.

## Procedure B — Android → iOS

1. Android: Settings → Data → Export Pilgrim Package.
2. Move file to iOS device (AirDrop / Drive / email / Files share-sheet).
3. iOS: import via Files share-sheet → Pilgrim.
4. Verify on iOS:
   - [ ] Walk count, distances (within 5%), durations, meditation match.
   - [ ] Photos come through (if iOS resolves the URI — likely fails since Android `MediaStore` URIs are app-private — expected; flag in QA notes).
   - [ ] JourneyViewer renders the imported walk with the route stroke + photo thumbnails.

## Acceptance

- Walk metadata (count, distance, durations, intentions, dayIdentifier) round-trips losslessly within stated tolerances.
- Photo bytes embed → render on receiving platform when MediaStore/PhotoKit resolves.
- No silent drops: if photos can't resolve, the post-import alert surfaces a count.

## Out-of-scope failures (noted, not blockers)

- **Cross-platform photo URI resolution.** Android `content://` URIs are not iOS-resolvable; iOS PHAsset ids are not Android-resolvable. Embedded photo bytes mitigate but in-app reliquary view of those photos is best-effort on the receiving side.
- **Voice recording GPS.** Android stores per-recording lat/lng; iOS does not. iOS export drops the field on read; Android import accepts the missing field.
- **Distance drift up to ~5%** due to geodesy-implementation difference + Android lacking accuracy gating. A future stage MAY port iOS's `checkForAppropriateAccuracy` filter to `WalkDistanceCalculator` (existing TODO in `domain/WalkDistance.kt:13`). Out of scope for Stage 11.

## Reporting

Capture failures as GitHub issues tagged `qa-pilgrim-roundtrip`. Include:
- Source platform + version (iOS X.Y / Android Stage 11 commit).
- Receiving platform + version.
- Walk count + which walks failed.
- Specific divergence (e.g. "walk #3 distance: iOS 4523m, Android 4187m, 7.4% drift").
- Whether photos resolved.
- Console / logcat snippet around the import.
