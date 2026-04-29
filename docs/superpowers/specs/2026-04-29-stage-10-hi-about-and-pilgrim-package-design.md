# Stage 10-H + 10-I: About link / AboutView + full `.pilgrim` package — design spec

> **Bundle:** ships AboutView (the small last surface in Settings) together with the full cross-platform `.pilgrim` walks export/import + JourneyViewer. Same PR. Stage 10-G already shipped UI scaffolding for the Walks/Journey buttons as Snackbar stubs — those become real this stage.

## Goals

1. **AboutView (10-H)** — iOS-faithful port of `pilgrim-ios/Pilgrim/Scenes/Settings/AboutView.swift`. Hero (logo + tagline + body), Pillars (walk · talk · meditate), Stats Whisper (cycling totalDistance / walkCount / firstWalkDate), Footprint Trail (4 stamps), Open Source links (3 rows: walktalkmeditate.org, GitHub, Rate Pilgrim), Motto, Seasonal Vignette (simplified Compose tree silhouette), Version. Plus the "About Pilgrim" nav row at the bottom of SettingsScreen.

2. **`.pilgrim` walks export/import (10-I)** — full cross-platform binary-compat port of iOS's `PilgrimPackageBuilder` / `PilgrimPackageImporter` / `PilgrimPackageConverter` / `PilgrimPhotoEmbedder` / `PilgrimPackagePhotoConverter`. Schema version `"1.0"` (matches iOS). A `.pilgrim` produced on Android opens cleanly on iOS and vice versa.

3. **JourneyViewer (10-I)** — WebView surface loading `view.pilgrimapp.org` with walks-JSON + manifest-JSON injected via `evaluateJavascript("window.pilgrimViewer.loadData(...)")`, mirroring `JourneyViewerView.swift` exactly. Reuses the same converter pipeline as the export.

## Non-goals (deferred)

- App-icon picker (long-press logo on iOS). Android requires adaptive-icon manifest aliases + activity-alias rewiring per icon — significant infrastructure for a contemplative wabi-sabi flourish. Defer to "Phase N — Future."
- Animated SceneryItemView tree. The iOS version is ~150 LOC of TimelineView + sway/gust/leaf math. Port a simplified static tree silhouette via Compose Canvas with seasonal tint (~30 LOC) — same visual silhouette, no animation. Acceptable wabi-sabi degradation.
- Heart-rate samples in `.pilgrim` exports. iOS Vision dropped these; Android never started tracking. Emit `heartRates: []` on export, drop on import.
- `events[]` calendar markers in manifest. Android has no Event entity (iOS feature was deferred from Stage 1). Emit `events: []` on export, drop on import. Future stage can add the Event surface separately.
- `customPromptStyles[]` in manifest. Android has no custom-prompt-style storage. Emit `[]` on export, drop on import.
- `intentions[]` in manifest. Android has no IntentionHistoryStore (iOS UserDefaults-backed last-5-intentions list). Emit `[]` on export, drop on import. Future stage can add this if the Intention picker grows a "recent" section.

## Source files

### iOS reference

| iOS file | Purpose |
|---|---|
| `Pilgrim/Scenes/Settings/AboutView.swift` | The view itself (411 LOC) |
| `Pilgrim/Views/PilgrimLogoView.swift` | Hero logo with breathing animation |
| `Pilgrim/Views/FootprintShape.swift` | 4-stamp trail (10 ellipses each) |
| `Pilgrim/Views/Scenery/SceneryItemView.swift` | Animated seasonal tree (we port a simplified static version) |
| `Pilgrim/Views/SafariView.swift` | In-app browser (Custom Tabs equivalent) |
| `Pilgrim/Models/Seal/SealGenerator.swift` (`enum SealTimeHelpers`) | `season(for:latitude:)` returns "Spring"/"Summer"/"Autumn"/"Winter" |
| `Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageModels.swift` | Codable data structs (PilgrimWalk, PilgrimManifest, GeoJSON, etc.) |
| `Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageBuilder.swift` | Build .pilgrim ZIP |
| `Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageImporter.swift` | Read .pilgrim ZIP |
| `Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageConverter.swift` | Walk ↔ PilgrimWalk + GeoJSON build/parse |
| `Pilgrim/Models/Data/PilgrimPackage/PilgrimPhotoEmbedder.swift` | PHAsset → JPEG bytes for ZIP photos/ |
| `Pilgrim/Models/Data/PilgrimPackage/PilgrimPackagePhotoConverter.swift` | WalkPhoto ↔ PilgrimPhoto metadata |
| `Pilgrim/Scenes/Settings/JourneyViewerView.swift` | WKWebView + JSON injection |
| `Pilgrim/Scenes/Settings/DataSettingsView.swift` | Already partially ported in Stage 10-G; this stage replaces stub buttons |
| `Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift` | Already designed in Stage 10-EFG spec; ported this stage |
| `Pilgrim/Scenes/Settings/ExportDateRangeFormatter.swift` | "March 2024 – April 2026" formatter |

### Android target paths

```
ui/settings/about/
  AboutScreen.kt
  AboutViewModel.kt
  PilgrimLogo.kt
  FootprintShape.kt
  SeasonalTree.kt          ← simplified static port of SceneryItemView tree
  AboutSeasonHelpers.kt    ← season(for date, latitude) port of SealTimeHelpers

data/pilgrim/
  PilgrimSchema.kt          ← schemaVersion + the embedded JSON Schema doc
  PilgrimWalk.kt            ← @Serializable data class hierarchy
  PilgrimManifest.kt
  PilgrimPause.kt
  PilgrimActivity.kt
  PilgrimVoiceRecording.kt
  PilgrimHeartRate.kt
  PilgrimWorkoutEvent.kt
  PilgrimPhoto.kt
  PilgrimWeather.kt
  PilgrimReflection.kt      ← celestial context (lunar phase, planetary positions, etc.)
  PilgrimEvent.kt
  GeoJsonModels.kt          ← FeatureCollection / Feature / Geometry / Properties + sealed Coordinates
  PilgrimDateCoding.kt      ← seconds-since-epoch instant serializer

data/pilgrim/builder/
  PilgrimPackageBuilder.kt
  PilgrimPackageImporter.kt
  PilgrimPackageConverter.kt
  PilgrimPhotoEmbedder.kt
  PilgrimPackagePhotoConverter.kt
  PilgrimPackageError.kt
  BackupTimeCode.kt          ← already exists (Stage 10-G)
  RecordingsExporter.kt      ← already exists (Stage 10-G)

ui/settings/data/
  DataSettingsScreen.kt      ← modify: replace Walks/Journey stubs with real flows
  DataSettingsViewModel.kt   ← modify: add export/import/journey state
  ExportConfirmationSheet.kt
  JourneyViewerScreen.kt
  PilgrimDocumentPicker.kt   ← SAF picker for .pilgrim files
```

---

## 10-H AboutView design

### Visual breakdown (top-to-bottom)

1. **Hero** (centered, ~280dp tall)
   - PilgrimLogo (80dp, breathing animation)
   - Title: `"Every walk is a\nsmall pilgrimage."` (display-medium italic, ink, multiline center)
   - Body: `"Walking is how we think, process, and return to ourselves. Pilgrim is a quiet companion for the path — no leaderboards, no metrics, just you and the walk."` (body, fog, multiline center, horizontal padding 16dp)

2. **Divider** (LinearGradient stone-alpha-0.2, full width, 1dp)

3. **Pillars** (3 rows, vertical spacing 16dp)
   - Caption header: `"walk · talk · meditate"` (caption, stone, tracking 3sp, centered)
   - Row pattern: leading 36dp circle (tint at 0.08 alpha) with 16sp icon, title (heading, ink), description (caption, fog)
   - Walk → `Icons.AutoMirrored.Filled.DirectionsWalk` (verified: existing usage in `WalkStatsSheet.kt:463` and `PilgrimBottomBar.kt:47`), tint moss; "Walking as practice, not transit. Side by side, step by step — strengthening the physical body."
   - Talk → `Icons.Outlined.ChatBubbleOutline` (Material Icons Extended; replace with `Icons.Outlined.QuestionAnswer` if not available — confirm at plan time by importing in a scratch file), tint dawn; "Deep reflection and connection, not small talk. Ask and share your unique perspective of reality."
   - Meditate → `Icons.Filled.NightsStay` (closest M3 to iOS `moon.stars.fill`; fallback `Icons.Filled.NightlightRound` — confirm at plan time), tint stone; "Seek the peace and calmness within. Harmonize your being with the group and the environment."

4. **Divider**

5. **Stats Whisper** (when `hasWalks == true`, otherwise skipped)
   - Tap-to-cycle button (clip + ripple, plain button style)
   - Phase 0: distance — `"5.2 km"` or `"3.2 mi"` (depending on units pref) + caption `"walked with Pilgrim"`
   - Phase 1: count — `"23"` + caption `"walks taken"` / `"walk taken"`
   - Phase 2: since — `"Mar 2024"` + caption `"walking since"`
   - Stat value uses `pilgrimType.statValue` (already exists in `Type.kt:21` — 20sp, FontWeight.Normal, Text family)
   - `AnimatedContent` for Compose-side fade between phases (acceptable degradation from iOS `.contentTransition(.numericText())`)

6. **Footprint Trail** (4 footprint shapes, horizontal spacing 16dp)
   - Each 12dp wide × 18dp tall
   - Alpha increases: 0.08, 0.12, 0.16, 0.20 (stone)
   - Even-indexed footprints rotate -10°, odd rotate +10° (alternating left/right pattern)
   - Even-indexed scaleX = 1, odd scaleX = -1 (mirror)

7. **Open Source** (vertical stack)
   - Header caption `"OPEN SOURCE"` (caption, stone-0.6 alpha, tracking 2sp)
   - Body: `"Pilgrim is free and open source. No accounts, no tracking, no data leaves your device. Built as part of the walk · talk · meditate project."` (body, ink)
   - Link row 1: globe icon + `"walktalkmeditate.org"` → Custom Tabs to `https://walktalkmeditate.org`
   - Link row 2: code icon (`Icons.Filled.Code` is in Material Icons Extended; fallback `Icons.AutoMirrored.Filled.Article` if missing — confirm at plan time) + `"Source code on GitHub"` → Custom Tabs to `https://github.com/walktalkmeditate/pilgrim-android` (note: Android repo, not iOS)
   - Link row 3: heart icon + `"Rate Pilgrim"` → `PlayStore.openListing(context)` (existing helper from Stage 10-F)
   - Each row ~44dp tall, stone tint, vertical padding 12dp

8. **Divider**

9. **Motto** (centered)
   ```
   Slow and chill is the motto.
   Relax and release is the practice.
   Peace and harmony is the way.
   ```
   (body italic, stone, multiline center, lineHeight 8sp extra)

10. **Seasonal vignette** (40dp tree silhouette)
    - Static Compose Canvas tree (3 ellipses for canopy, 1 stem)
    - Tinted with seasonal color via existing `SeasonalColorEngine.seasonalColor("moss" or "stone", intensity = .full, on = today)`
    - `opacity = 0.5` (Constants.UI.Opacity.medium iOS equiv)

11. **Version** (centered, fog-0.3 alpha caption)
    - Read from `BuildConfig.VERSION_NAME`. Strip `.debug` suffix on debug builds for parity with release display.

### AboutViewModel

`@HiltViewModel` exposing:

```kotlin
data class AboutStats(
    val walkCount: Int,
    val totalDistanceMeters: Double,
    val firstWalkDate: Instant?,
    val hasWalks: Boolean,
)

val stats: StateFlow<AboutStats>
val distanceUnits: StateFlow<UnitSystem>   // passthrough from UnitsPreferencesRepository
```

`stats` derived from `walkRepository.observeAllWalks()` → reduce to total distance via existing helper (`computeWalkDistance(routeSamples)` from `WalkSummaryViewModel`). `WhileSubscribed(5_000)` + `initialValue = AboutStats(0, 0.0, null, false)`. Acceptable one-frame "no walks" flash on first composition (same precedent as Stage 10-G's audio section flash).

### Navigation

- New `Routes.ABOUT = "about"` constant.
- `composable(Routes.ABOUT) { AboutScreen(onBack = { navController.popBackStack() }) }`.
- `SettingsAction.OpenAbout` was reserved in Stage 10-A's sealed interface — wire its handler in `PilgrimNavHost.handleSettingsAction` to navigate to ABOUT.
- Add the "About Pilgrim" nav row to `SettingsScreen.kt` as the LAST item (after ConnectCard). Layout matches iOS:
  ```
  Row {
      PilgrimLogo(size = 24, animated = true)
      Spacer(8dp)
      Text("About Pilgrim", body, ink)
      Spacer(weight = 1f)
      Text(versionName, caption, fog)
      Icon(KeyboardArrowRight, caption, fog)
  }
  ```
  Wrapped in `Modifier.fillMaxWidth().settingsCard()` + `clickable { onAction(SettingsAction.OpenAbout) }`.

### Android divergences from iOS (acknowledged)

| iOS | Android | Reason |
|---|---|---|
| Long-press logo → app icon dialog | None | Android adaptive-icon picker requires manifest aliases; deferred. |
| Animated SceneryItemView tree | Static Compose Canvas tree | 150 LOC of animation port vs 30 LOC of static silhouette; wabi-sabi degradation acceptable. |
| `.contentTransition(.numericText())` morph | `AnimatedContent` fade | Compose has no numeric-morph primitive. |
| `SafariView` (SFSafariViewController) | Custom Tabs (existing helper) | Same in-app browser feel. |
| iOS GitHub URL `pilgrim-ios` | Android URL `pilgrim-android` | Each platform points to its own repo. |
| `UserPreferences.distanceMeasurementType.safeValue == .miles` | `UnitsPreferencesRepository.distanceUnits` | Existing Android infra (Stage 10-C). |
| `Bundle.main.infoDictionary["CFBundleShortVersionString"]` | `BuildConfig.VERSION_NAME.removeSuffix("-debug")` | Build system difference. |

---

## 10-I `.pilgrim` package design

### JSON schema parity

The `.pilgrim` archive is a ZIP containing:

```
manifest.json
schema.json     ← embedded copy of the JSON Schema doc
walks/
  <uuid1>.json
  <uuid2>.json
  ...
photos/         ← present only when user opts in at export time AND has pinned photos
  <sanitized-localid>.jpg
  ...
```

`schemaVersion = "1.0"` — matches iOS verbatim. No version bump.

#### Walk JSON shape (verbatim from iOS)

```kotlin
@Serializable
data class PilgrimWalk(
    val schemaVersion: String,
    val id: String,                              // UUID string
    val type: String,                            // "walking" | "unknown"
    val startDate: Double,                       // seconds since epoch
    val endDate: Double,
    val stats: PilgrimStats,
    val weather: PilgrimWeather? = null,
    val route: GeoJsonFeatureCollection,
    val pauses: List<PilgrimPause>,
    val activities: List<PilgrimActivity>,
    val voiceRecordings: List<PilgrimVoiceRecording>,
    val intention: String? = null,
    val reflection: PilgrimReflection? = null,
    val heartRates: List<PilgrimHeartRate>,
    val workoutEvents: List<PilgrimWorkoutEvent>,
    val favicon: String? = null,
    val isRace: Boolean,
    val isUserModified: Boolean,
    val finishedRecording: Boolean,
    val photos: List<PilgrimPhoto>? = null,      // null = key omitted (pre-reliquary parity)
)

@Serializable
data class PilgrimStats(
    val distance: Double,           // meters
    val steps: Int? = null,
    val activeDuration: Double,     // seconds
    val pauseDuration: Double,      // seconds
    val ascent: Double,             // meters
    val descent: Double,
    val burnedEnergy: Double? = null,
    val talkDuration: Double,
    val meditateDuration: Double,
)
```

(... full hierarchy mirrors `PilgrimPackageModels.swift` field-for-field — see plan for exhaustive listing.)

#### Date encoding

iOS uses `JSONEncoder.dateEncodingStrategy = .secondsSince1970`. We'll mirror with a kotlinx-serialization `Instant` serializer that emits `Double` seconds:

```kotlin
object EpochSecondsInstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.DOUBLE)
    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeDouble(value.epochSecond + value.nano / 1_000_000_000.0)
    override fun deserialize(decoder: Decoder): Instant {
        val seconds = decoder.decodeDouble()
        val whole = seconds.toLong()
        val nanos = ((seconds - whole) * 1_000_000_000).toLong()
        return Instant.ofEpochSecond(whole, nanos)
    }
}
```

Annotated via `@Serializable(with = EpochSecondsInstantSerializer::class)` on every `Instant` field.

#### GeoJSON `coordinates` polymorphic union

iOS uses Swift's `enum AnyCodableCoordinates { case point([Double]); case lineString([[Double]]) }` with custom Codable. Android equivalent:

```kotlin
@Serializable(with = GeoJsonCoordinatesSerializer::class)
sealed class GeoJsonCoordinates {
    data class Point(val coords: List<Double>) : GeoJsonCoordinates()
    data class LineString(val coords: List<List<Double>>) : GeoJsonCoordinates()
}

object GeoJsonCoordinatesSerializer : KSerializer<GeoJsonCoordinates> {
    // Try-decode as List<List<Double>> first (LineString); fall back to List<Double> (Point).
    // Matches iOS's "if let lineString = try? container.decode([[Double]].self) ..." order.
}
```

#### Encoder/decoder config (parity with iOS)

```kotlin
val pilgrimJson = Json {
    prettyPrint = true
    encodeDefaults = false           // omit null `photos` to keep pre-reliquary byte-parity
    explicitNulls = false
    ignoreUnknownKeys = true         // forward-compat with iOS adding new fields
    // sortedKeys equivalent: prettyPrintIndent = "  " — kotlinx.serialization sorts by
    // declaration order, not alphabetically. iOS uses .sortedKeys so a manual
    // alphabetic-by-property ordering in our data classes IS load-bearing for byte
    // parity. We won't claim byte parity — only semantic JSON parity.
}
```

**Byte parity is NOT a goal.** iOS uses `.sortedKeys` which alphabetizes; kotlinx-serialization doesn't have an equivalent. Cross-platform import works on JSON parsing, not byte equality.

### Walk → PilgrimWalk conversion (Android-specific compute)

Android's Room Walk entity is a thin metadata stub. Most iOS schema fields are computed from the related entities at export time. Mapping table:

| PilgrimWalk field | Android source |
|---|---|
| `id` | `walk.uuid` |
| `type` | `"walking"` (Android has no other workout types) |
| `startDate` | `Instant.ofEpochMilli(walk.startTimestamp)` |
| `endDate` | `Instant.ofEpochMilli(walk.endTimestamp ?: walk.startTimestamp)` |
| `stats.distance` | haversine sum over consecutive `routeSamples`. **Note:** the math currently lives inline in `WalkSummaryViewModel.buildState()` — this stage extracts it to a new public utility `data/walk/WalkDistanceCalculator.kt: fun computeDistanceMeters(samples: List<RouteDataSample>): Double`. The Stage 7-C etegami converter and the existing summary VM both refactor to call the extracted helper (one inline-math site collapsed to one call site). |
| `stats.steps` | `null` (Android doesn't track) |
| `stats.activeDuration` | `(endTimestamp - startTimestamp) / 1000 - pauseDuration` |
| `stats.pauseDuration` | sum of paired `WalkEvent.PAUSED` → `WalkEvent.RESUMED` intervals (see "Pause pairing" below) |
| `stats.ascent` | sum of positive altitude deltas. **Note:** no helper exists today. Add `data/walk/AltitudeCalculator.kt: fun computeAscentDescent(samples: List<AltitudeSample>): Pair<Double, Double>` returning `(ascent, descent)`. |
| `stats.descent` | second component of the same helper. |
| `stats.burnedEnergy` | `null` (Android doesn't compute) |
| `stats.talkDuration` | `sum(activityIntervals where type == TALKING) of (end - start) / 1000` |
| `stats.meditateDuration` | `sum(activityIntervals where type == MEDITATING) of (end - start) / 1000` |
| `weather` | `null` (Android doesn't track yet) |
| `route` | `buildRouteGeoJSON(routeSamples, waypoints)` |
| `pauses` | derived from `WalkEvent.PAUSED` → `WalkEvent.RESUMED` pair walk; `type = "manual"` |
| `activities` | `activityIntervals` mapped 1:1, `type = lowercased ActivityType` |
| `voiceRecordings` | `voiceRecordings` mapped 1:1. **Unit conversion:** Android's `VoiceRecording.durationMillis: Long` → `PilgrimVoiceRecording.duration: Double` (seconds) via `durationMillis / 1000.0`. Inverse on import. |
| `intention` | `walk.intention` |
| `reflection` | `null` for now (porting CelestialCalculator → PilgrimReflection is Stage 10-J; not blocking) |
| `heartRates` | `[]` (Android doesn't track) |
| `workoutEvents` | `[]` (Android's WalkEvent enum doesn't map to iOS lap/marker/segment) |
| `favicon` | `walk.favicon` |
| `isRace` | `false` |
| `isUserModified` | `false` (Android has no edit-walk feature yet) |
| `finishedRecording` | `walk.endTimestamp != null` |
| `photos` | `null` if user opted out OR no pinned photos; else `walkPhotos.map { ... }` |

#### Pause pairing

WalkEvent rows for a finished walk are sorted ascending by timestamp. Walk forward, pairing each `PAUSED` with the next `RESUMED` to form a `PilgrimPause`. Edge cases:

- **Dangling pause** (`PAUSED` with no matching `RESUMED` — walk ended while paused) → emit a final `PilgrimPause(startDate = pausedAt, endDate = walk.endDate, type = "manual")`. iOS treats a paused-on-finish walk identically.
- **Orphan resume** (`RESUMED` with no preceding `PAUSED` — shouldn't happen but defensive) → ignore silently with `Log.w`.
- **Auto-pause** — Android doesn't currently auto-pause. All pauses emit `type = "manual"`. If auto-pause ever ships, add a discriminator to `WalkEventType` first.

> **Reflection scope decision.** iOS `buildReflection` invokes `CelestialCalculator.snapshot` which Android already ported in Stage 6-A. We could include `reflection` on Android exports too — but the port is tedious (zodiac sign names, planetary position lowercasing, element balance enum mapping). Spec defers `reflection` emission to Stage 10-J as a follow-up; emit `null` for now. Imports tolerate `reflection: null` already (iOS field is optional).

### PilgrimManifest

```kotlin
@Serializable
data class PilgrimManifest(
    val schemaVersion: String,
    val exportDate: Instant,
    val appVersion: String,                                    // BuildConfig.VERSION_NAME
    val walkCount: Int,
    val preferences: PilgrimPreferences,
    val customPromptStyles: List<PilgrimCustomPromptStyle>,    // []
    val intentions: List<String>,                              // []
    val events: List<PilgrimEvent>,                            // []
)

@Serializable
data class PilgrimPreferences(
    val distanceUnit: String,                  // "km" | "mi"
    val altitudeUnit: String,                  // "m" | "ft"
    val speedUnit: String,                     // "km/h" | "mph"
    val energyUnit: String,                    // "kJ" | "kcal"
    val celestialAwareness: Boolean,
    val zodiacSystem: String,                  // "tropical" | "sidereal"
    val beginWithIntention: Boolean,
)
```

Preferences sourced from existing `UnitsPreferencesRepository` + `PracticePreferencesRepository`. `altitudeUnit`/`speedUnit`/`energyUnit` derive from the same metric-vs-imperial setting (Android Stage 10-C wired only `distanceUnits`; the iOS preference structure has 4 separate units). For now we'll mirror: `distance/altitude/speed` toggle in lockstep with the imperial flag, `energyUnit = "kcal"` always (matches both iOS metric and imperial — Apple defaults).

### Photo embedder (Android)

```kotlin
class AndroidPilgrimPhotoEmbedder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class EmbedResult(val filenameMap: Map<String, String>, val skippedCount: Int)

    fun embedPhotos(walks: List<PilgrimWalk>, tempDir: File): EmbedResult { ... }
}
```

Per-photo pipeline (mirrors iOS):
1. Read `photo.localIdentifier` (Android-side this is the `content://media/...` URI string).
2. `contentResolver.openInputStream(uri)` → `BitmapFactory.decodeStream` with `inSampleSize` chosen so the long edge ≤ 1200px (2× headroom for resize).
3. Resize to ≤600×600 aspect-fit via `Bitmap.createScaledBitmap`.
4. `ByteArrayOutputStream` + `bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)`.
5. Reject if encoded bytes > 150KB hard ceiling (matches iOS `maxEncodedBytes`).
6. Write to `tempDir/photos/<sanitized-localid>.jpg`.

Sanitization: `localIdentifier.replace("/", "_") + ".jpg"`. Same as iOS. (Android `content://` URIs contain `/` separators; replacing flattens.)

**Cross-platform note.** Android exports use `localIdentifier = the content:// URI string`. iOS exports use `localIdentifier = PHAsset id`. Neither resolves on the other platform's MediaStore/PhotoKit. The embedded JPEG bytes in `photos/` are the cross-platform photo carrier — desktop viewer renders them; in-app reliquary on the receiving platform shows metadata-only (no thumbnail). Same pragmatic limitation iOS already accepts.

**Schema gap: capturedLat / capturedLng / capturedAt.** iOS `PilgrimPhoto` requires `capturedLat: Double`, `capturedLng: Double`, `capturedAt: Date` (decoded as required). Android's `WalkPhoto` schema today stores only `photoUri`, `pinnedAt`, `takenAt: Long?` — no GPS columns. To produce a valid cross-platform export, the converter must derive these:

- `capturedAt` ← `walkPhoto.takenAt ?: walkPhoto.pinnedAt`. Falling back to `pinnedAt` when EXIF date is unavailable is acceptable; both fields are epoch-ms on Android.
- `capturedLat` / `capturedLng` ← look up the nearest `RouteDataSample` to `capturedAt`, return its `(latitude, longitude)`. Yields a "where was the user walking when the photo was pinned" coordinate — slightly different semantics from iOS PHAsset GPS (where the photo was *taken*) but visually indistinguishable on a route map.
- If the walk has no `RouteDataSample` (recent fresh walk pre-GPS-fix) → drop the photo from the export with a `Log.w` and increment `skippedPhotoCount`. iOS's photo embedder skips on PHAsset-not-found via the same path.
- On import: if Android receives a `PilgrimPhoto`, store `capturedAt → takenAt`, drop `capturedLat/capturedLng` (Android `WalkPhoto` has no columns to receive them — same drop-silently pattern as `weather` and `reflection`).

**Future Android schema migration option** (out of scope, future stage): add `captured_lat`, `captured_lng`, `captured_at` to `WalkPhoto` with a Room migration so imports preserve the iOS-supplied GPS metadata. Not blocking 10-I — the converter compromise above produces valid `.pilgrim` files for cross-platform consumption today.

### PilgrimPackageBuilder (Android)

```kotlin
sealed class PilgrimPackageError : Exception() {
    object NoWalksFound : PilgrimPackageError()
    data class EncodingFailed(override val cause: Throwable?) : PilgrimPackageError()
    data class ZipFailed(override val cause: Throwable) : PilgrimPackageError()
    data class FileSystemError(override val cause: Throwable) : PilgrimPackageError()
    object InvalidPackage : PilgrimPackageError()
    data class DecodingFailed(override val cause: Throwable) : PilgrimPackageError()
    data class UnsupportedSchemaVersion(val version: String) : PilgrimPackageError()
}

data class PilgrimPackageBuildResult(
    val file: File,
    val skippedPhotoCount: Int,
)

class PilgrimPackageBuilder @Inject constructor(
    private val walkRepository: WalkRepository,
    private val practicePrefs: PracticePreferencesRepository,
    private val unitsPrefs: UnitsPreferencesRepository,
    private val photoEmbedder: AndroidPilgrimPhotoEmbedder,
    @ApplicationContext private val context: Context,
) {
    suspend fun build(includePhotos: Boolean): PilgrimPackageBuildResult { ... }
}
```

Pipeline (matches iOS):
1. `withContext(Dispatchers.IO)` for the whole flow.
2. `walks = walkRepository.allWalks().filter { it.endTimestamp != null }` (Android repo has no `allFinishedWalks()` accessor today; this stage either inlines the filter or adds a `fun allFinishedWalks(): List<Walk>` accessor — decide at plan-write time. Inlining is simpler; adding the accessor is only worth it if a second consumer arrives.) If empty → throw `NoWalksFound`.
3. For each walk: load related entities, call `PilgrimPackageConverter.convert(walk, related, includePhotos)`.
4. Build `manifest = buildManifest(walks.size)`.
5. Create temp dir under `cacheDir/pilgrim-export-<uuid>/`.
6. If `includePhotos`, run `photoEmbedder.embedPhotos(pilgrimWalks, tempDir)` → returns `EmbedResult`. Apply `embeddedPhotoFilename` to each walk's photos.
7. Write `walks/<uuid>.json` per walk, `manifest.json`, `schema.json` (verbatim copy of the embedded schema doc) into temp dir.
8. ZIP the temp dir to `cacheDir/pilgrim_export/pilgrim-<backupTimeCode>.pilgrim` using existing `ZipOutputStream` pattern (Stage 10-G `RecordingsExporter` is the precedent).
9. Delete temp dir in `finally`.
10. Return `PilgrimPackageBuildResult(file, skippedPhotoCount = embedResult.skippedCount)`.

**FileProvider config update:** add `<cache-path name="pilgrim_export" path="pilgrim_export/" />` to `res/xml/file_paths.xml` (existing entries: `etegami/`, `recordings_export/` from Stages 7-D / 10-G). Without this the share intent's `FileProvider.getUriForFile` throws `IllegalArgumentException`.

### PilgrimPackageImporter (Android)

```kotlin
class PilgrimPackageImporter @Inject constructor(
    private val walkRepository: WalkRepository,
    @ApplicationContext private val context: Context,
) {
    /** Returns the count of walks successfully imported. */
    suspend fun import(uri: Uri): Int { ... }
}
```

Pipeline:
1. `withContext(Dispatchers.IO)`.
2. Open `Uri` via `ContentResolver.openInputStream`. Copy to a per-import temp `cacheDir/pilgrim-import-<uuid>/`.
3. Unzip via `ZipInputStream`.
4. Read `manifest.json` → parse `PilgrimManifest`. If missing → throw `InvalidPackage`. If `schemaVersion != "1.0"` → throw `UnsupportedSchemaVersion(version)`.
5. List `walks/*.json`, read each, `pilgrimJson.decodeFromString<PilgrimWalk>` (skip on per-file decode failure with log).
6. For each `PilgrimWalk`, run `PilgrimPackageConverter.convertToImport` → produce a `PendingImport(walk, route, waypoints, ...)` bundle.
7. Inside a single `database.withTransaction { ... }`:
   - Insert each Walk (skipping duplicates by uuid via `INSERT OR IGNORE`)
   - Insert RouteDataSamples, Waypoints, ActivityIntervals, VoiceRecordings, WalkPhotos, WalkEvents
   - **Photo bytes are NEVER copied into app storage.** Imported photos carry only `localIdentifier + lat/lng + timestamps`. The desktop viewer will render the embedded JPEGs in the ZIP; the in-app reliquary will resolve PHAssets/MediaStore URIs that exist on the receiving device, or tombstone-fail otherwise.
8. Delete temp dir in `finally`.
9. Return imported walk count.

Drops on import (silently logged):
- `events[]` from manifest (no Event entity)
- `intentions[]` from manifest (no IntentionHistoryStore)
- `customPromptStyles[]` from manifest (no custom prompt store)
- `heartRates[]` per walk (no HeartRate entity)
- `workoutEvents[]` per walk (no lap/marker concept on Android — could map to WAYPOINT_MARKED but iOS shape doesn't carry the location data)
- `weather` per walk (no weather columns on Walk)
- `reflection` per walk (no reflection storage)
- `isRace`, `isUserModified` (no schema columns)

### DataSettingsScreen wiring

Replace the Stage 10-G stub buttons:

| Button | Stage 10-G | Stage 10-I |
|---|---|---|
| Export My Data | Snackbar "Coming soon" | Compute counts → ExportConfirmationSheet → builder → share |
| Import Data | Snackbar "Coming soon" | SAF picker → importer → result alert. **MIME filter:** use `arrayOf("application/zip", "application/octet-stream", "*/*")`. `.pilgrim` is structurally a ZIP but Android system pickers won't recognize the extension as `application/zip` automatically; the broad filter ensures the file is selectable across pickers (Files, Drive, Dropbox, etc.). Validation happens on parse — non-ZIP input throws `InvalidPackage`. |
| View My Journey | Snackbar "Coming soon" | Navigate to `Routes.JOURNEY_VIEWER` |

`DataSettingsViewModel` gains:
- `walkCount: StateFlow<Int>` (passthrough)
- `dateRange: StateFlow<Pair<Instant, Instant>?>` (earliest/latest walk start)
- `pinnedPhotoCount: StateFlow<Int>` (no `observeCount()` exists today — add a `@Query("SELECT COUNT(*) FROM walk_photos") fun observeAllCount(): Flow<Int>` to `WalkPhotoDao`. Existing dao has only `countForWalk(walkId)` and `countByPhotoUri(uri)` as suspend ints.)
- `exportState: StateFlow<ExportState>` (Idle / Confirming(summary) / Building / SharedSuccess(skippedCount) / Error(message))
- `importState: StateFlow<ImportState>` (Idle / Importing / Imported(count) / Error(message))
- `exportRequest()`, `confirmExport(includePhotos: Boolean)`, `cancelExport()`, `import(uri: Uri)`

Reuse existing `Channel<ExportEvent>` pattern (Stage 10-G fix) for one-shot share-sheet/alert events that survive rotation.

### ExportConfirmationSheet (Android)

ModalBottomSheet (M3) at `medium` detent, mirroring `ExportConfirmationSheet.swift`:

- Header: "Export Walks" (heading, ink, top-padded)
- Summary: "5 walks" (body, ink) + "March 2024 – April 2026" (caption, fog)
- Photo toggle (only when pinned photo count > 0):
  - Switch + label "Include pinned photos" + caption "18 photos · ≈1.4 MB"
  - Description: "Photos travel with the file. Anyone you share it with will see them as map markers."
- Button bar:
  - "Cancel" (TextButton, fog) ← LEFT
  - "Export" (Filled, stone bg, parchment text, capsule shape) ← RIGHT
- `hasCommitted` double-tap guard mirroring iOS

`ExportDateRangeFormatter` ports verbatim — already designed in the Stage 10-G spec; implement now.

### JourneyViewerScreen

`AndroidView(WebView)` loading `https://view.pilgrimapp.org`:

```kotlin
@Composable
fun JourneyViewerScreen(
    onBack: () -> Unit,
    viewModel: JourneyViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text("My Journey") }, ... ) },
    ) { padding ->
        when (state) {
            JourneyState.Loading -> ProgressIndicator()
            JourneyState.NoWalks -> Text("No walks yet. Take a walk first.")
            is JourneyState.Error -> ErrorView(state.message)
            is JourneyState.Ready -> AndroidView(
                factory = { context -> WebView(context).apply { ... } },
                update = { webView -> webView.injectIfReady(state.walksJson, state.manifestJson) },
            )
        }
    }
}
```

WebView config:
- `javaScriptEnabled = true`
- `domStorageEnabled = true`
- `setBackgroundColor(0)` (transparent so parchment shows)
- `loadUrl("https://view.pilgrimapp.org")`
- After `WebViewClient.onPageFinished`, inject the data:

```kotlin
val walksJson = pilgrimJson.encodeToString(walks)         // already valid JSON literal
val manifestJson = pilgrimJson.encodeToString(manifest)   // already valid JSON literal
val payload = """{"walks":$walksJson,"manifest":$manifestJson}"""
webView.evaluateJavascript("window.pilgrimViewer.loadData($payload);", null)
```

Both `walksJson` and `manifestJson` are already-valid JSON literals from kotlinx-serialization, so embedding them directly into the JS function call produces a valid JS expression. No HTML escaping concern (we're not writing into HTML, just calling a JS function via the WebView's JS bridge).

JSON shape matches iOS:
```json
{
  "walks": [...],
  "manifest": {...}
}
```

Built via the same converter pipeline as the export. **Photo handling decision:** iOS optionally enriches each `PilgrimPhoto` with a base64 `inlineUrl` (data URL of a 600×600 JPEG) when reliquary is enabled, so the in-app journey viewer renders thumbnails. Without enrichment the WebView shows photo MARKERS (lat/lng pins) but no thumbnails. Stage 10-I ships **without enrichment** to keep the JS payload small and the Android port focused; in-app photo rendering is a follow-up. The data round-trips correctly — the JS bridge just renders no thumbnail when `inlineUrl` is absent (verified in iOS view.pilgrimapp.org code path).

### Navigation routes

```kotlin
object Routes {
    const val JOURNEY_VIEWER = "journey_viewer"
    const val ABOUT = "about"
    // ABOUT also gets a composable destination wired in PilgrimNavHost
}
```

---

## Stage size estimate

| Cluster | New files | Modified files | LOC (incl. tests) |
|---|---|---|---|
| 10-H AboutView | ~6 | 2 (SettingsScreen, PilgrimNavHost) | ~600 |
| 10-I JSON models + converter | ~14 | 0 | ~1200 |
| 10-I Builder + Importer + PhotoEmbedder | ~6 | 0 | ~700 |
| 10-I Wiring (DataSettings, Journey, Confirmation sheet, picker) | ~5 | 3 (DataSettingsScreen/VM, PilgrimNavHost) | ~700 |
| **Total** | **~31** | **5** | **~3200** |

This is a substantially larger stage than 10-EFG. The .pilgrim format alone is the bulk. Honest framing: this would be 2-3x the implementation time of 10-EFG, with at least one round of cross-platform compat testing (export from Android, open on iOS — and vice versa).

## Open questions

1. **Reflection emission** — should `PilgrimReflection` be emitted on Android exports (port `CelestialCalculator → PilgrimReflection` mapping), or deferred? Recommendation: defer to Stage 10-J. iOS imports tolerate `reflection: null`. Android exports without celestial context are still valid `.pilgrim` files.

2. **`reflection` on Android imports** — should we DROP iOS-supplied reflection silently, or store it in a new column for future surfacing? Recommendation: drop silently with a `Log.d("PilgrimImport", "Dropped reflection for walk $uuid — Android storage not yet implemented")`. Adding a column requires a Room migration just to receive data we never display.

3. **Cross-platform photo testing** — exporting an Android `.pilgrim` and importing on iOS would require a paired iPhone for dev test. Recommendation: ship the conversion code with unit tests against fixture archives, run a one-time manual round-trip test on an iPhone before declaring "iOS parity". Stage 10-I's "Diamond" criterion includes this manual round-trip.

4. **JourneyViewer JSON payload size** — for users with 1000+ walks, the injected JSON could balloon to 50+ MB. iOS doesn't gate this. Recommendation: ship un-gated for now, add a "Loading large journey..." spinner during the JS evaluation, escalate to streaming in a future stage if it becomes a problem.

5. **Staged rollout** — ship 10-H by itself first (small, low-risk, immediate iOS parity payoff), then 10-I as a separate PR? Recommendation: combine in one PR per the user's framing, but split commits cleanly so 10-H's value lands even if 10-I needs revisions.

6. **Schema doc embedding** — port the iOS-embedded JSON Schema (`PilgrimPackageSchema.json`) verbatim as a Kotlin string constant in `PilgrimSchema.kt`. Same content, same semantics. Confirms cross-platform schema compat without inventing a parallel doc.

7. **JourneyViewer photo thumbnails** — iOS enriches photos with base64 `inlineUrl` data URLs so thumbnails render in the in-app WebView. Stage 10-I ships without enrichment (markers only, no thumbnails), defers thumbnail support to a follow-up. OK?

8. **Helper extractions** — Stage 10-I needs `WalkDistanceCalculator.computeDistanceMeters()` (extract from `WalkSummaryViewModel.buildState()` haversine sum) and `AltitudeCalculator.computeAscentDescent()` (new, no existing helper). These are standalone JVM utilities in `data/walk/`, easily unit-testable. Plan should include the extraction commits + a single follow-up commit refactoring `WalkSummaryViewModel` to call the extracted distance helper (one inline-math site collapses to one call site). Worth doing as part of this stage rather than later.
