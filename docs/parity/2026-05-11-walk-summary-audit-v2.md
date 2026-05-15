> **SUPERSEDED** by `docs/parity/2026-05-15-parity-ledger.md` — findings predate the v1.6.0 port + bug fixes and are stale. Format precedent only.

# Parity Audit: WalkSummary (post-PR-#92)

| field | value |
|---|---|
| iOS pin | `v1.5.0` = `db4196e` |
| Android HEAD | `ff15ac8` |
| Generated | 2026-05-11 |
| Type | audit |
| Generator | ios-parity skill (8-lens fan-out + synth) |

---

## iOS source map

- `Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift` — primary view + reveal cinematic + light reading + intentions + onAppear sequencing
- `Pilgrim/Scenes/WalkSummary/WalkSummaryView+Map.swift` — RadialGradient mask + photo PointAnnotation overlay on Mapbox
- `Pilgrim/Scenes/WalkSummary/ActivityTimelineBar.swift` — colored walk/talk/meditate bar + PaceSparkline embedded
- `Pilgrim/Scenes/WalkSummary/ActivityInsightsView.swift` — talk-% + meditation-streak insights
- `Pilgrim/Scenes/WalkSummary/ActivityListView.swift` — meditation + voice rows
- `Pilgrim/Scenes/WalkSummary/AudioPlayerModel.swift` — local AVAudioPlayer per playback context
- `Pilgrim/Scenes/WalkSummary/ElevationProfileView.swift` — altitude sparkline + terrain icons
- `Pilgrim/Scenes/WalkSummary/FaviconSelectorView.swift` — 3-tile selector
- `Pilgrim/Scenes/WalkSummary/PaceSparklineView.swift` — pace bucket curve
- `Pilgrim/Scenes/WalkSummary/Reliquary/PhotoCarouselView.swift` — long-press activate + tap-preview
- `Pilgrim/Scenes/WalkSummary/Reliquary/PhotoPreviewSheet.swift` — full-screen drag-dismiss preview
- `Pilgrim/Scenes/WalkSummary/Reliquary/PhotoReliquarySection.swift` — 4-state dispatcher
- `Pilgrim/Scenes/WalkSummary/VoiceRecordingRow.swift` — waveform + transcription + speed badge
- `Pilgrim/Views/WalkSharingButtons.swift` — Goshuin + Etegami + Walk Journey 3-state share card

## Android source map

- `ui/walk/WalkSummaryScreen.kt` — primary screen scaffold + reveal sequencing + sharing-button wiring
- `ui/walk/WalkSummaryViewModel.kt` — `buildState()` aggregate + hot Flows for celestial/light/sharing/pinned-photos/reliquary
- `ui/walk/PilgrimMap.kt` — Mapbox view (now `textureBackend = true` for Walk Summary)
- `ui/walk/WalkLightReadingCard.kt` — gated card with koan
- `ui/walk/summary/WalkSummaryTopBar.kt` — date + pill Done button
- `ui/walk/summary/CelestialLineRow.kt` — centered moon/hour/element line
- `ui/walk/summary/WalkStatsRow.kt` — Distance + Elevation (no Steps)
- `ui/walk/summary/WalkSharingButtons.kt` — 3-action parchmentSecondary card
- `ui/walk/summary/ElevationProfile.kt`, `PaceSparkline.kt`, `WalkActivityTimelineCard.kt`, `WalkActivityInsightsCard.kt`, `WalkActivityListCard.kt`, `WalkDurationHero.kt`, `WalkIntentionCard.kt`, `WalkJourneyQuote.kt`, `MilestoneCalloutRow.kt`, `WalkTimeBreakdownGrid.kt`, `FaviconSelectorCard.kt`, `WalkSummaryDetailsCard.kt`, `AIPromptsRow.kt`, `WalkSummaryRevealAnimations.kt`, `WalkSummaryCalloutProse.kt`, `TimelineSegments.kt`, `RevealAnimation.kt`, `SealShareBitmapWriter.kt`, `MapCameraBounds.kt`, `JourneyQuoteCase.kt`
- `ui/walk/reliquary/PhotoCarousel.kt`, `PhotoThumbnail.kt`, `PhotoPreviewSheet.kt`, `PhotoReliquarySection.kt`, `ReliquaryState.kt`, `ReliquaryConstants.kt`
- `data/sharing/WalkSharingTracker.kt`, `data/seal/SealRevealStore.kt`
- `data/entity/Walk.kt` — Room entity (Steps column MISSING)
- `data/entity/RouteDataSample.kt` — has `speedMetersPerSecond` for pace sparkline ✓
- `data/entity/ActivityInterval.kt`, `WalkPhoto.kt`, `AltitudeSample.kt`, `WalkEvent.kt`, `Waypoint.kt`, `VoiceRecording.kt`

---

## Matching parity (no work — verified iOS↔Android)

- **Body scroll order** — 20 sections in exact same order both platforms (Map → PhotoReliquary → Intention → Elevation → JourneyQuote → DurationHero → MilestoneCallout → StatsRow → WeatherLine → CelestialLine → TimeBreakdown → FaviconSelector → ActivityTimeline → ActivityInsights → ActivityList → VoiceRecordings → AIPrompts → Details → LightReading → WalkSharingButtons)
- **Reveal cinematic phases** — Hidden → Zoomed (100ms camera + 800ms hold) → Revealed (2500ms camera + 600ms opacity). Distance count-up 31 steps × 67ms with smoothstep easing. Match exactly.
- **Per-section opacity stagger** — durations + delays (JourneyQuote 800ms/0, DurationHero 600ms/0 fires-on-Zoomed, MilestoneCallout 800ms/300, StatsRow 600ms/200, WeatherLine 600ms/200, CelestialLine 600ms/300, TimeBreakdown 600ms/400). All match.
- **Light Reading reveal** — 1200ms fadeIn after share, instant under reduce-motion. Match.
- **Threshold guards** — `ascend > 1`, `routePoints.size >= 2`, `altitudes.count > 5 && range > 1`, `paceSparkline routeData >= 3`, `speed > 0.3` filter, `widthFraction > 0`, etc. All match.
- **Stats row Distance + Elevation** — both gates match.
- **Celestial line centering** — Android Arrangement.spacedBy(small, CenterHorizontally) matches iOS HStack default centering.
- **WalkSharingTracker** — DataStore key `"sharedWalkUUIDs"` matches iOS UserDefaults key. API symmetric.
- **`markCurrentWalkShared()` on `persistenceScope`** — Android improvement over iOS @MainActor; survives Dialog/sheet dismiss mid-write.
- **Sheet presentation** — Android ModalBottomSheet ≡ iOS .sheet semantically. Rounded top corners, scrim, drag-to-dismiss, top inset.
- **Done button pill** — Android Button(CircleShape, parchmentTertiary, stone) matches iOS Button("Done").foregroundColor(.stone) + system pill styling.
- **CelestialSnapshot** — both computed at appear; Android improves via live combine with celestialAwarenessEnabled pref.
- **ReliquaryState** — 4-state machine matches iOS 3-state + Loading variant; Android resolver matches precedence.
- **Photo carousel** — long-press 400ms activates, tap commits, drag clears. Match.
- **PhotoPreviewSheet drag-dismiss threshold** — 120dp/120pt matches.
- **Reliquary skeleton 300ms defer** — matches iOS DispatchQueue.main.asyncAfter(0.3).
- **WalkLightReadingCard gated AnimatedVisibility(fadeIn(tween(1200ms)))** — matches iOS withAnimation(.easeInOut(duration: 1.2)).

---

## Deltas — P0 (block design + functional parity)

### Delta 1: Walk.steps column + step capture (NEW SCOPE)

**iOS:** `walk.steps: Int?` stored on Walk entity. Gate: `if let steps = walk.steps, steps > 0 { miniStat(label: "Steps", value: "\(steps)") }` (`WalkSummaryView.swift:466@db4196e`).

**Android:** No `steps` column on `Walk.kt:10-41@ff15ac8`. No step counter integration anywhere. `WalkStatsRow.kt:34-55@ff15ac8` only renders Distance + Elevation.

**iOS data flow:** Stage 2-D `StepCounter.swift` polls `CMPedometer.queryPedometerData(from:to:)` at walk end and writes `steps` onto the Walk row.

**Fix scope:**
- Add `steps: Int?` to `data/entity/Walk.kt` + Room migration (single ALTER TABLE)
- Wire Android step capture during active walk via `Sensor.TYPE_STEP_COUNTER` (cumulative since boot — diff at finish for walk-relative count)
- OR Health Connect `StepsRecord` (richer, asks user permission). iOS uses CMPedometer (free, no permission). For Android, sensor approach matches iOS UX (no permission prompt).
- Add `steps` field to `WalkSummary` data class; project into `WalkStatsRow` 3-column layout when `steps > 0`.

---

### Delta 2: Photos on map (NEW SCOPE)

**iOS:** Pinned photos render as map annotations at GPS EXIF coordinates. Tapping a map photo-pin scrolls the carousel to that photo (does NOT open PhotoPreviewSheet).

```swift
let photoPins = photoCandidates.filter { $0.isPinned }.map { candidate in
    PilgrimAnnotation(
        coordinate: CLLocationCoordinate2D(
            latitude: candidate.capturedLat,
            longitude: candidate.capturedLng
        ),
        kind: .photo(localIdentifier: candidate.localIdentifier)
    )
}
return cachedAnnotations + photoPins
```
`Pilgrim/Scenes/WalkSummary/WalkSummaryView+Map.swift:34-46@db4196e`

```swift
// "map pin taps focus the carousel rather than preview"
withAnimation(.easeInOut(0.2)) {
    activePhotoID = localIdentifier
}
```
`Pilgrim/Scenes/WalkSummary/WalkSummaryView+Map.swift:12-21@db4196e`

**Android:** No map photo overlay. `WalkSummaryScreen.kt:898-948@ff15ac8` SummaryMap composable doesn't pass photos to PilgrimMap. PilgrimMap.kt has `walkAnnotations: List<WalkMapAnnotation>` slot but `WalkMapAnnotation` doesn't have a photo kind.

**Fix scope:**
- Extend `WalkMapAnnotation` sealed class with `Photo(walkPhotoId: Long, lat: Double, lng: Double)` variant
- Render via Mapbox `ViewAnnotation` (preferred — supports composable bitmap) OR PointAnnotation with a custom bitmap
- `WalkPhoto` entity already has `photoUri` + `pinnedAt`; need EXIF/MediaStore lookup for capture lat/lng (add `capturedLat: Double?`, `capturedLng: Double?` columns to WalkPhoto OR query MediaStore.Images.Media.LATITUDE/LONGITUDE at pin time)
- Wire from `WalkSummaryViewModel.pinnedPhotos` → `WalkSummary.photoMapAnnotations` → `SummaryMap.PilgrimMap(walkAnnotations = ... + photoAnnotations)`
- On map photo-pin tap: scroll carousel to matching photo. Need `activePhotoId: StateFlow<Long?>` on VM + LazyListState scroll-to-item in PhotoCarousel.
- Filter only pinned photos (skip unpinned candidates — Android only stores pinned, so all-photos = pinned ✓)

---

## Deltas — P1 (polish — visible but minor)

### Delta 3: Map mask — true alpha vs opaque overlay

**iOS:** `.mask(RadialGradient(...))` clips map to alpha. Corners reveal whatever is BEHIND the map (screen .parchment).

**Android:** `Canvas` overlay paints opaque `parchmentSecondary` over corners. Corners cover map with secondary color.

After PR #92's TextureView fix + 0.30 gradient stop, visual is "close enough" per device QA. But the alpha-mask approach would be cleaner: use `graphicsLayer { compositingStrategy = Offscreen }` + `drawWithCache { onDrawWithContent { drawContent(); drawRect(brush, blendMode = DstIn) } }` — now feasible because TextureView keeps map pixels in the parent canvas.

**Recommendation:** Convert back to mask approach (revertless to PR #92 logic but with TextureView backend). Lower priority — current visual is acceptable.

### Delta 4: Accessibility — timeline + photo tap gestures invisible to TalkBack

**Android gaps:**
- `WalkActivityTimelineCard.kt:158-179@ff15ac8` — `detectTapGestures` segment-zoom action not surfaced. Add `Modifier.semantics { customActions = [...] }` per segment with "Zoom to Walking 0:00 to 12:34" action.
- `PhotoThumbnail.kt:65-76@ff15ac8` — long-press activate + tap commit invisible. Add Custom Accessibility Actions per iOS Stage 7-A pattern.
- `WalkSharingButtons.kt:72,82@ff15ac8` — Bookmark + Brush icons have `contentDescription = null`, relying on M3 merge. Set explicit description for screen-reader robustness.

**iOS reference:** uses `.accessibilityAction(named:)`, `.accessibilityHint`, `.accessibilityElement(children: .ignore)` patterns.

### Delta 5: Hardcoded strings in PhotoReliquarySection header

**Android:** `PhotoReliquarySection.kt:300@ff15ac8` — `"Add"` / `"Full"` literal strings inside `Text(...)`.

**iOS reference:** iOS likely uses LocalizedStringKey. Confirm + replace with `stringResource(R.string.reliquary_header_add)` etc.

### Delta 6: Timeline compact-duration not localized

**Android:** `WalkActivityTimelineCard.kt:354-355@ff15ac8` — `"${total}s"` / `"${total / 60}m"` raw interpolation.

**Recommendation:** Route through stringResource with placeholders like `R.string.summary_compact_seconds` to match `WalkActivityInsightsCard.kt:103-108@ff15ac8` pattern.

---

## Deltas — P2 (Android improvements over iOS — intentional divergences, DOCUMENT)

- `SealRevealStore` per-walk-once vs iOS replay-on-every-visit (user-requested divergence)
- `Mutex.tryLock()` for pin concurrency (Android improvement)
- `markCurrentWalkShared` on `persistenceScope` (Android improvement, survives sheet dismiss)
- Prompts cache invalidator with `.drop(1)` (Android improvement)
- ModalBottomSheet vs Dialog wrap (semantic match — Android picked more idiomatic widget)

---

## Implementation prioritization (next PR series)

**PR-A (P0 — Steps stat):**
1. Add `steps: Int?` column to Walk Room entity + migration
2. Wire `Sensor.TYPE_STEP_COUNTER` capture during active walk (delta from session start)
3. Add `steps` field to `WalkSummary` data class
4. Project into `WalkStatsRow` 3-column layout (Distance | Steps | Elevation) when present

**PR-B (P0 — Photos on map):**
1. Extend `WalkMapAnnotation` with `Photo` variant
2. Add `capturedLat: Double?`, `capturedLng: Double?` columns to `WalkPhoto` (Room migration); query EXIF/MediaStore at pin time to populate
3. Render via Mapbox `ViewAnnotation` with circular Composable bitmap
4. Wire `activePhotoId: StateFlow<Long?>` from VM; tap-to-scroll carousel; LazyListState.animateScrollToItem
5. Filter pinned-only (already-implicit on Android since WalkPhoto = pinned)

**PR-C (P1 polish bundle — accessibility + map mask + strings):**
1. CustomAccessibilityAction on timeline segments + photo thumbnails
2. Explicit contentDescription on share-button icons
3. Move hardcoded "Add"/"Full" to string resources
4. Move timeline compact-duration to stringResource
5. (optional) revert map mask to true-alpha approach with TextureView

---

## Notes for downstream `writing-plans`

- PR-A and PR-B are independent (different files, different domains). Can ship in parallel.
- PR-A requires runtime sensor permission discussion — `Sensor.TYPE_STEP_COUNTER` is NOT a dangerous permission below Android 10; above Android 10 needs `ACTIVITY_RECOGNITION`. Decide whether to ask at first walk or onboarding.
- PR-B requires deciding ViewAnnotation vs PointAnnotation-with-Bitmap. Recommendation: ViewAnnotation (Compose-native, simpler).
- PR-C is small (~5 files), can squash with PR-A or PR-B if convenient.

## Handoff

Spec ready for `superpowers:writing-plans` to break into tasks per PR. Recommend running `jutsu swarm doc-review` on this audit first for completeness check before plan generation.
