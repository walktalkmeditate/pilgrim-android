# Stage 7-C: Etegami Postcard Renderer — Design

**Date:** 2026-04-24
**Phase:** Port Phase 7 (Photo reliquary + etegami) — sub-stage C
**Ships:** pure-bitmap etegami renderer + on-screen preview card on Walk Summary. No PNG export, no share (7-D).

---

## Intent

Port iOS's etegami postcard renderer. iOS's etegami is a deliberately austere 1080×1920 portrait PNG composing **12 layers** from a finished walk: paper fill → radial gradient → seeded ink grain → inner border → moon terminator glyph → route glow + crisp strokes + activity markers → seal with glow+shadow → haiku/intention text → stats whisper → provenance. The iOS design is a keepsake — contemplative, not flashy.

Stage 7-C lays the foundation: the renderer itself (produces a `Bitmap`) plus an on-screen preview card on Walk Summary. Stage 7-D wires a Share button to the same renderer + `ACTION_SEND` via FileProvider.

## Why this shape (intuition first)

Render to `android.graphics.Canvas` + `Bitmap`, not Compose's `Canvas` composable. The iOS equivalent is `UIGraphicsImageRenderer`: a fixed-resolution offscreen rendering context independent of display density. On Android the equivalent is `Bitmap.createBitmap(1080, 1920, ARGB_8888)` + `android.graphics.Canvas` drawing to it. Reasons to take the bitmap route over Compose's `Canvas` + `captureToImage()`:
- **Deterministic resolution.** A 1080×1920 share artifact should not depend on the phone's display density. Compose's `Canvas` draws at display density; `captureToImage()` is documented as "primarily for debugging/testing."
- **Reusable for share (7-D).** Same function → same bitmap → one code path for preview and PNG export.
- **Testable without Robolectric's Canvas stubs.** `android.graphics.Canvas` backed by a real `Bitmap` gives us a PNG we can compare pixels on (future screenshot tests).
- **Matches iOS `UIGraphicsImageRenderer` 1:1.** The iOS code is a sequence of layer draws against a `CGContext` — the same shape on Android.

Text rendering uses `StaticLayout.Builder` (multi-line haiku/intention center-aligned with line spacing) plus `android.graphics.Paint` (single-line stats). Fonts load via `ResourcesCompat.getFont(ctx, R.font.cormorant_garamond_variable)` with an explicit `FontVariation.weight(300)` per Stage 3-B's variable-font trap.

Time-of-day palette (6 slots, hour-of-walk-start keyed) — NOT seasonal. iOS is explicit about this, and matching the aesthetic matters more than reusing `SeasonalColorEngine`. The palette captures the quality of light at the moment of the walk; seasonal tint would muddle that reference.

On-screen preview uses `Image(bitmap.asImageBitmap())` in a card between `WalkLightReadingCard` and `VoiceRecordingsSection` — the natural contemplative slot. Bitmap renders lazily on first composition via `produceState` + `Dispatchers.Default` (encoding 2.1M pixels is not a Main-thread operation).

## Considered and rejected

- **Compose `Canvas` + `rememberGraphicsLayer()` + `captureToImage()`** — display-density-bound, officially for test/debug only, diverges from iOS's offscreen-rendering model.
- **Save bitmap to disk and display via Coil** — unnecessary indirection; in-memory `ImageBitmap` is fast.
- **Eager generation inside `WalkSummaryViewModel.buildState`** — couples VM to Android Canvas APIs; VM should emit a pure `EtegamiSpec`, not a Bitmap. The renderer lives in UI.
- **Seasonal tint via `SeasonalColorEngine`** — iOS doesn't do this. Time-of-day is the intended signal.
- **Haiku generation (port iOS's `EtegamiTextGenerator`)** — substantial corpus + selection logic; scope-expand. For 7-C MVP, use `Walk.intention ?: Walk.notes`; if neither is present, skip the text layer entirely. Haiku corpus can be a future stage.
- **Nav destination / fullscreen modal** — the preview is a card like LightReading. Fullscreen viewing is a 7-D concern (tap-to-expand from share sheet).
- **Shared `SealRenderer` rewrite to `android.graphics.Canvas`** — the existing Compose `SealRenderer` stays. We extract a new `EtegamiSealBitmapRenderer.renderToBitmap(spec, sizePx, context)` helper that re-uses `sealGeometry(spec)` (already pure) but draws via `android.graphics.Canvas`. The Compose renderer and bitmap renderer share the geometry layer, diverge only at the draw call.

---

## Architecture

### Data layer

**`EtegamiSpec`** (`ui/etegami/EtegamiSpec.kt`):
```kotlin
@Immutable
data class EtegamiSpec(
    val walkUuid: String,
    val startedAtEpochMs: Long,
    val zoneId: ZoneId,
    val routePoints: List<LocationPoint>,
    val sealSpec: SealSpec,
    val moonPhase: MoonPhase?,     // from LightReading; null if no GPS
    val distanceMeters: Double,
    val durationMillis: Long,
    val elevationGainMeters: Double,
    val topText: String?,          // intention ?: notes; null → skip layer
    val activityMarkers: List<ActivityMarker>,
)

@Immutable
data class ActivityMarker(
    val kind: Kind,
    val timestampMs: Long,
) { enum class Kind { Meditation, Voice } }
```

All fields are Compose-stable (primitives + `String` + `List<ImmutableFoo>` + nullable primitives). `ZoneId` and `LocationPoint` are already established stable types in the codebase.

**`WalkSummary.etegamiSpec: EtegamiSpec?`** — new field, mirrors `lightReading` pattern. VM's `buildState()` composes it inside a `runCatching`; null on failure, card simply doesn't render.

**Time-of-day palette** (`ui/etegami/EtegamiPalette.kt`):
```kotlin
data class EtegamiPalette(val paper: Color, val ink: Color)

object EtegamiPalettes {
    fun forHour(hour: Int): EtegamiPalette = when (hour) {
        in 5..7 -> EtegamiPalette(paper = Color(0xFFF5E6C8), ink = Color(0xFF2C241E))
        in 8..10 -> EtegamiPalette(paper = Color(0xFFF5F0E8), ink = Color(0xFF2C241E))
        in 11..13 -> EtegamiPalette(paper = Color(0xFFFAF8F3), ink = Color(0xFF2C241E))
        in 14..16 -> EtegamiPalette(paper = Color(0xFFF0E4C8), ink = Color(0xFF2C241E))
        in 17..19 -> EtegamiPalette(paper = Color(0xFFE8D0C0), ink = Color(0xFF2C241E))
        else -> EtegamiPalette(paper = Color(0xFF1A1E2E), ink = Color(0xFFD0C8B8)) // night
    }
}
```
Pure table. Tests assert: all 24 hours map to a palette, boundaries correct (hour=5 is dawn, hour=20 is night, hour=4 is night).

### Renderer

**`EtegamiBitmapRenderer`** (`ui/etegami/EtegamiBitmapRenderer.kt`) — pure Kotlin object; `context` for typeface loading only:

```kotlin
object EtegamiBitmapRenderer {
    const val WIDTH_PX = 1080
    const val HEIGHT_PX = 1920
    private const val GRAIN_SEED = 12345L

    suspend fun render(spec: EtegamiSpec, context: Context): Bitmap =
        withContext(Dispatchers.Default) {
            val bitmap = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(bitmap)
            val hour = hourOfDay(spec.startedAtEpochMs, spec.zoneId)
            val palette = EtegamiPalettes.forHour(hour)
            val smoothed = EtegamiRouteGeometry.smooth(spec.routePoints)
            drawPaper(canvas, palette)
            drawRadialGradient(canvas, palette, smoothed)
            drawGrain(canvas, palette, GRAIN_SEED)
            drawInnerBorder(canvas, palette)
            if (spec.moonPhase != null) drawMoonGlyph(canvas, palette, spec.moonPhase)
            if (smoothed.isNotEmpty()) {
                drawRouteGlow(canvas, palette, smoothed)
                drawRouteCrisp(canvas, palette, smoothed, spec.activityMarkers)
            }
            drawSealWithGlow(canvas, palette, spec.sealSpec, context)
            spec.topText?.takeIf { it.isNotBlank() }?.let { drawTopText(canvas, palette, it, context) }
            drawStatsWhisper(canvas, palette, spec, context)
            drawProvenance(canvas, palette, context)
            bitmap
        }
}
```

All private `draw*` functions are `internal fun` for testability (geometry math can be asserted without invoking the full pipeline).

**Sub-helpers** (grouped by concern):
- `ui/etegami/EtegamiRouteGeometry.kt` — Catmull-Rom smoothing (8 subdivs/segment), equirectangular projection, vertical recenter, per-segment width (altitude-delta-based), taper zone, clamp. Pure; unit-tested.
- `ui/etegami/EtegamiMoonGlyph.kt` — `fun moonTerminatorPath(illumination: Double, isWaxing: Boolean, center: PointF, radius: Float): Path`. Pure; unit-tested for `illumination == 0`, `0.5` waxing (right-half-lit), `0.5` waning (left-half-lit), `1.0` (full moon).
- `ui/etegami/EtegamiGrain.kt` — `fun grainDots(seed: Long, count: Int = 3000, w: Int, h: Int): List<GrainDot>`. Deterministic. Unit-tested for count, bounds, determinism.
- `ui/etegami/EtegamiSealBitmapRenderer.kt` — `fun renderSealToBitmap(spec: SealSpec, sizePx: Int, context: Context): Bitmap`. Reuses `sealGeometry(spec)` from Stage 4-A; draws via `android.graphics.Canvas`.

### VM integration

In `WalkSummaryViewModel.buildState()`, alongside the `runCatching { LightReading.from(...) }` pattern:

```kotlin
val etegamiSpec = runCatching {
    composeEtegamiSpec(
        walk = walk,
        routePoints = points,
        sealSpec = sealSpec,
        lightReading = lightReading,
        distanceMeters = distance,
        durationMillis = totalElapsed,
        altitudeSamples = altitudeSamples,
        activityIntervals = activityIntervals,
        voiceRecordings = voiceRecordings,
    )
}.onFailure {
    android.util.Log.w(TAG, "EtegamiSpec compose failed for walk $walkId", it)
}.getOrNull()
```

`composeEtegamiSpec` is a pure helper in `ui/etegami/EtegamiSpec.kt`. It:
- Computes `elevationGainMeters` from altitude samples (sum of positive deltas).
- Builds `activityMarkers` from `ActivityInterval` (Meditation start times) and `VoiceRecording` (startTimestamp).
- Fills `topText` via `walk.intention?.takeIf { it.isNotBlank() } ?: walk.notes?.takeIf { it.isNotBlank() }`.

### UI card

**`WalkEtegamiCard`** (`ui/walk/WalkEtegamiCard.kt`):

```kotlin
@Composable
fun WalkEtegamiCard(spec: EtegamiSpec, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, spec) {
        value = runCatching {
            EtegamiBitmapRenderer.render(spec, context).asImageBitmap()
        }.getOrNull()
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(
                    EtegamiBitmapRenderer.WIDTH_PX.toFloat() / EtegamiBitmapRenderer.HEIGHT_PX,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val b = bitmap
            if (b != null) {
                Image(
                    bitmap = b,
                    contentDescription = "Etegami postcard for this walk",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = pilgrimColors.stone,
                )
            }
        }
    }
}
```

Slotted on `WalkSummaryScreen` between `WalkLightReadingCard` and `VoiceRecordingsSection`.

### Error handling

| Failure | Handling |
|---|---|
| `composeEtegamiSpec` throws | VM's `runCatching` → `etegamiSpec = null` → card doesn't render |
| `render()` throws (OOM, typeface load fail) | `produceState` `runCatching` → `bitmap = null` → loading spinner stays |
| Route empty / single point | Route + glow layers skipped; paper/seal/moon/text/stats still render |
| `moonPhase == null` (no GPS) | Moon glyph layer skipped |
| `topText` empty/blank | Text layer skipped (absence of text is cleaner than a placeholder) |

### Testing

- `EtegamiRouteGeometryTest` — Catmull-Rom subdivision count, equirectangular projection edge cases (single-point route, two-point route, zero-area bounding box), taper clamp, width clamp, vertical recenter.
- `EtegamiMoonGlyphTest` — terminator path `Path.getBounds()` for 4 canonical illuminations × 2 waxing states.
- `EtegamiGrainTest` — determinism (same seed → same dots), count correctness, all dots within canvas bounds.
- `EtegamiPaletteTest` — 24-hour lookup table, boundary cases (5 → dawn, 4 → night, 20 → night).
- `EtegamiSpecTest` — `composeEtegamiSpec` assembly from synthetic Walk + intervals + recordings, fallback order (intention → notes → null), elevation-gain math.
- `EtegamiBitmapRendererTest` (Robolectric) — call `render(spec, context)`, assert returns non-null Bitmap at 1080×1920, no exception. Does NOT assert pixels.
- `WalkEtegamiCardTest` (Robolectric + ComposeRule) — card renders (assertExists), loading state visible, post-render state visible.

### Accessibility

- `Image.contentDescription = "Etegami postcard for this walk"`.
- No interactive elements in 7-C (share button is 7-D).
- Tile is decorative but represents the walk — TalkBack announcement is sufficient.

---

## Non-goals

- PNG export / save-to-gallery / `ACTION_SEND` share (7-D).
- Haiku corpus / generation — use intention/notes/nothing for topText.
- Pinned-photo rendering inside the etegami.
- Animations (reveal, crossfade, ink-settle) — static render only.
- User customization (font, palette override).
- Nav destination / fullscreen modal — card preview only.
- Seasonal tint (iOS uses time-of-day; don't diverge).
- Localization of "pilgrimapp.org" or "Etegami postcard for this walk".

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| 2.1M-pixel Bitmap OOM on low-end devices | Render on `Dispatchers.Default`; bitmap wrapped in `ImageBitmap` whose lifecycle Compose manages. Monitor real-device memory in QA. |
| Long render time (>500ms) blocks preview forever | Wrap render in a bounded coroutine; spinner stays until completion. For 7-D share path, surface a timeout + retry. |
| Typeface load race | `ResourcesCompat.getFont` blocks until loaded, on `Dispatchers.Default` we can wait; Paint constructed with null typeface falls back to DEFAULT. |
| Seal bitmap rendering diverges from Compose SealRenderer | Both share `sealGeometry(spec)` — only the draw code differs. Cross-checked tests if divergence becomes a risk. |
| Route with < 2 points produces degenerate smoothed path | Geometry helper short-circuits when `points.size < 2` → empty list → route layers no-op. |
| Antimeridian crossing (long walks across ±180°) | Equirectangular projection wraps naturally from min/max — rare Pilgrim case; accept. |
| Bitmap leaked on card disposal | `ImageBitmap` wraps the Bitmap; Compose handles recycle via the composition lifecycle. Verify with device QA. |

## Success criteria

- [ ] `./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest` all pass.
- [ ] A finished walk with a route shows an etegami preview card on Walk Summary.
- [ ] The card renders visually recognizable: paper background (hour-appropriate color), brushed route line, vermilion seal lower-left, moon glyph upper-right, intention/notes text centered (if any), stats whisper at bottom.
- [ ] An empty-route walk renders the card without crashing (skips route layer).
- [ ] A walk without intention AND without notes renders without the top-text layer.
- [ ] TalkBack announces the postcard when focus lands on it.
- [ ] No regression on existing Walk Summary sections.

## References

- iOS: `/Users/rubberduck/GitHub/momentmaker/pilgrim-ios/Pilgrim/Models/Etegami/EtegamiRenderer.swift` (+ siblings `EtegamiGenerator`, `EtegamiRouteStroke`, `EtegamiMoonPhase`)
- Android exemplars: `ui/design/seals/SealRenderer.kt` + `SealGeometry.kt`, `ui/design/calligraphy/CalligraphyPath.kt`
- Compose bitmap pattern: `ImageBitmap.asImageBitmap()`, `produceState` for async load
- Stage 3-B lesson: `FontVariation.weight(N)` mandatory for variable-font weight selection
