# Stage 7-C Implementation Plan

**Spec:** `docs/superpowers/specs/2026-04-24-stage-7c-etegami-renderer-design.md`
**Branch:** `feat/stage-7c-etegami-renderer`
**Estimate:** ~13 new files + ~3 modified + ~9 tests. Graphics-heavy; extract geometry aggressively.

## Order of operations (strictly linear; each task green before the next)

### T1 — Palette lookup table + test
**New:** `ui/etegami/EtegamiPalette.kt`
```kotlin
data class EtegamiPalette(val paper: Color, val ink: Color)
object EtegamiPalettes {
    fun forHour(hour: Int): EtegamiPalette = when (hour) {
        in 5..7 -> EtegamiPalette(Color(0xFFF5E6C8), Color(0xFF2C241E))
        in 8..10 -> EtegamiPalette(Color(0xFFF5F0E8), Color(0xFF2C241E))
        in 11..13 -> EtegamiPalette(Color(0xFFFAF8F3), Color(0xFF2C241E))
        in 14..16 -> EtegamiPalette(Color(0xFFF0E4C8), Color(0xFF2C241E))
        in 17..19 -> EtegamiPalette(Color(0xFFE8D0C0), Color(0xFF2C241E))
        else -> EtegamiPalette(Color(0xFF1A1E2E), Color(0xFFD0C8B8))
    }
}
```
**Test:** `EtegamiPaletteTest.kt` — 24-hour cycle, boundaries (5=dawn, 4=night, 20=night), ink contrast present.

### T2 — Route geometry + test
**New:** `ui/etegami/EtegamiRouteGeometry.kt`:
```kotlin
internal object EtegamiRouteGeometry {
    data class SmoothedSegment(val x: Float, val y: Float, val taper: Float, val widthMultiplier: Float)
    fun smooth(
        points: List<LocationPoint>,
        canvasWidth: Int = 880,
        canvasHeight: Int = 900,
        offsetX: Int = 100,
        offsetTop: Int = 200,
        subdivisions: Int = 8,
    ): List<SmoothedSegment>
}
```
Implements:
- Short-circuit if `points.size < 2` → emptyList
- Equirectangular projection (lon→x, lat→y inverted), min/max normalization, aspect-preserving scale (fit + center)
- Catmull-Rom smoothing with `subdivisions` per original segment
- Vertical recenter to target midY=700 px between top=120 and bottom=1280
- Taper zone = `max(count*0.1, 1)` segments at each end, multiplier 0.15→1.0
- Width multiplier from altitude delta (for now: uniform 1.0 — altitude needs `AltitudeSample` list; defer altitude-aware width to a follow-up inside this stage if altitude samples accessible; else uniform width for MVP)

**Test:** `EtegamiRouteGeometryTest.kt` — empty points, 1 point, 2 points (expect `1 * 8 = 8` segments), 3 points, bounds within canvas, taper monotonic, determinism.

### T3 — Moon terminator geometry + test
**New:** `ui/etegami/EtegamiMoonGlyph.kt`:
```kotlin
internal object EtegamiMoonGlyph {
    fun terminatorPath(
        illumination: Double,
        isWaxing: Boolean,
        cx: Float,
        cy: Float,
        radius: Float,
    ): android.graphics.Path
}
```
Semicircle of 64 points + reverse arc with x scaled by `(2*illumination - 1).toFloat()`; if `!isWaxing`, mirror via `Matrix().setScale(-1f, 1f, cx, 0f)`.

**Test:** `EtegamiMoonGlyphTest.kt` — path bounds stay within `(cx±r, cy±r)` for 4 illuminations × 2 waxing states; illumination 0 → smallest bound; illumination 1 → full disk width.

### T4 — Grain + test
**New:** `ui/etegami/EtegamiGrain.kt`:
```kotlin
internal data class GrainDot(val x: Float, val y: Float, val radius: Float)
internal object EtegamiGrain {
    fun dots(seed: Long, count: Int = 3000, width: Int, height: Int): List<GrainDot>
}
```
Uses `java.util.Random(seed)` — deterministic. Each dot: uniform-random position + `radius = 0.5 + random.nextFloat() * 1.0` (→ [0.5, 1.5]).

**Test:** `EtegamiGrainTest.kt` — count, bounds, determinism (same seed → same List), different seeds differ, radius range.

### T5 — EtegamiSpec + composeEtegamiSpec + test
**New:** `ui/etegami/EtegamiSpec.kt`:
```kotlin
@Immutable
data class EtegamiSpec(...)  // per spec

@Immutable
data class ActivityMarker(val kind: Kind, val timestampMs: Long) {
    enum class Kind { Meditation, Voice }
}

internal fun composeEtegamiSpec(
    walk: Walk,
    routePoints: List<LocationPoint>,
    sealSpec: SealSpec,
    lightReading: LightReading?,
    distanceMeters: Double,
    durationMillis: Long,
    altitudeSamples: List<AltitudeSample>,
    activityIntervals: List<ActivityInterval>,
    voiceRecordings: List<VoiceRecording>,
): EtegamiSpec
```
- `topText`: `walk.intention?.takeIf { it.isNotBlank() } ?: walk.notes?.takeIf { it.isNotBlank() }`
- `elevationGainMeters`: sum of positive `altitudeMeters` deltas between consecutive samples
- `activityMarkers`: meditation starts (from intervals where kind == Meditation) + voice starts (from recordings)
- `zoneId`: `ZoneId.systemDefault()` (consistent with Stage 6-B LightReading)
- `moonPhase`: `lightReading?.moon`

**Test:** `EtegamiSpecTest.kt` — intention-over-notes fallback, notes-when-no-intention, null-when-both-absent; elevation math; marker assembly; empty inputs all valid.

### T6 — EtegamiSealBitmapRenderer + smoke test
**New:** `ui/etegami/EtegamiSealBitmapRenderer.kt`:
```kotlin
internal object EtegamiSealBitmapRenderer {
    fun renderToBitmap(spec: SealSpec, sizePx: Int, context: Context): Bitmap
}
```
- Reuses `sealGeometry(spec)` from Stage 4-A (public/internal — check; may need visibility bump).
- Draws to `android.graphics.Canvas(bitmap)` with the same draw primitives the Compose `SealRenderer` uses: background circle, rings, radials, arcs, center text.
- Font: `ResourcesCompat.getFont(ctx, R.font.cormorant_garamond_variable)` + `FontVariation.weight(600)` (seal uses SemiBold per Stage 4-A).

**Test:** `EtegamiSealBitmapRendererTest.kt` (Robolectric) — returns non-null bitmap at expected size; no exception with diverse SealSpecs.

### T7 — EtegamiBitmapRenderer (main) + smoke test
**New:** `ui/etegami/EtegamiBitmapRenderer.kt`:
```kotlin
object EtegamiBitmapRenderer {
    const val WIDTH_PX = 1080
    const val HEIGHT_PX = 1920
    private const val GRAIN_SEED = 12345L
    suspend fun render(spec: EtegamiSpec, context: Context): Bitmap
}
```
Implements the 12-layer pipeline. Each layer is a private `internal fun` for test-accessibility (e.g. `drawPaper`, `drawRadialGradient`, `drawGrain`, `drawInnerBorder`, `drawMoonGlyph`, `drawRouteGlow`, `drawRouteCrisp`, `drawActivityMarkers`, `drawSealWithGlow`, `drawTopText`, `drawStatsWhisper`, `drawProvenance`).

Text specs:
- Haiku/intention: `StaticLayout.Builder.obtain(text, 0, text.length, paint, width=920).setAlignment(ALIGN_CENTER).setLineSpacing(8f, 1f).build()`; paint: Cormorant Garamond Light 46sp×3 (convert sp→px via `context.resources.displayMetrics.scaledDensity`, but in a fixed-pixel renderer we set 46 * (1080/targetDp) ≈ fixed px... actually since canvas is fixed at 1080×1920 we use fixed pixel sizes: 46px for text, same for all devices).
- Stats whisper: single-line Lato Regular 16px, centered at `x=540, y=1700`.
- Provenance: Lato 14px right-aligned at `(w-60, h-60)`.

Stats format: `"${distanceLabel} · ${durationLabel}"` + ` · ${elevationLabel}` only if `elevationGainMeters > 1`.
- `distanceLabel`: `WalkFormat.distance(distanceMeters)` — reuse existing formatter.
- `durationLabel`: `WalkFormat.duration(durationMillis)` — reuse.
- `elevationLabel`: `"${elevationGainMeters.roundToInt()}m ↑"`.

**Test:** `EtegamiBitmapRendererTest.kt` (Robolectric) — returns non-null bitmap at 1080×1920; no exception with:
  - full-featured spec (intention + route + moon + markers)
  - no moon (lightReading null)
  - empty route
  - no topText
  - night hour (inverted palette)

### T8 — WalkEtegamiCard composable + UI test
**New:** `ui/walk/WalkEtegamiCard.kt` — composable per spec.
**Test:** `WalkEtegamiCardTest.kt` (Robolectric + ComposeRule) — loading state exists, bitmap renders after a short wait (runTest + advanceUntilIdle), contentDescription correct.

### T9 — WalkSummary field + VM compose
**Modify:** `ui/walk/WalkSummaryViewModel.kt`:
- Add `etegamiSpec: EtegamiSpec?` to `WalkSummary`.
- In `buildState()`, alongside `lightReading`:
  ```kotlin
  val etegamiSpec = runCatching {
      composeEtegamiSpec(walk, points, sealSpec, lightReading, distance, totalElapsed,
          altitudeSamples, activityIntervals, voiceRecordings)
  }.onFailure { Log.w(TAG, "etegami compose failed for walk $walkId", it) }.getOrNull()
  ```
- Populate `WalkSummary(..., etegamiSpec = etegamiSpec)`.
- Pull `altitudeSamples`, `activityIntervals`, `voiceRecordings` — these need repository reads. Expose via `WalkRepository.altitudeSamplesFor(walkId)` (exists), `activityIntervalsFor(walkId)` (exists), `voiceRecordingsFor(walkId)` (exists).

**Test:** `WalkSummaryViewModelTest.kt` additions — a loaded summary for a walk with intention emits a non-null etegamiSpec with topText populated.

### T10 — Walk Summary integration
**Modify:** `ui/walk/WalkSummaryScreen.kt`:
- Slot `WalkEtegamiCard(spec = s.summary.etegamiSpec)` between `WalkLightReadingCard` and `VoiceRecordingsSection`, wrapped in a `null?.let { }` guard so absence is invisible.

No new test — existing `WalkSummaryViewModelTest` loaded-summary cases now implicitly verify the card path via composition. Device QA confirms visual rendering.

### T11 — Build gate
- `./gradlew :app:compileDebugKotlin`
- `./gradlew :app:assembleDebug`
- `./gradlew :app:lintDebug`
- `./gradlew :app:testDebugUnitTest`
All pass.

## Spec coverage

| Spec § | Task |
|---|---|
| Palette lookup | T1 |
| Route smoothing/projection | T2 |
| Moon terminator | T3 |
| Grain | T4 |
| EtegamiSpec + composer | T5 |
| Seal bitmap renderer | T6 |
| Main bitmap renderer | T7 |
| UI card | T8 |
| VM integration | T9 |
| WalkSummary slot | T10 |
| Build gate | T11 |

## Polish + review cycles

After all tasks green:
1. `/polish` — up to 4 review-fix passes until clean
2. Initial review → fix → re-polish
3. Final review × up to 3 cycles until Diamond

## Type consistency

- Coordinates: `Float` throughout renderer (matches `android.graphics.Canvas` API).
- Colors: Compose `androidx.compose.ui.graphics.Color` in palette tokens, `android.graphics.Color` (via `.toArgb()`) at draw time.
- Pixel dimensions: `Int` (1080, 1920, 880, 900, etc.).
- Time: `Long` epoch ms at data layer; `ZoneId`/`Instant` for hour-of-day conversion.

## Open questions → resolutions during implementation

1. **Altitude-aware route width:** if elevation data is sparse or missing, fall back to uniform width (simpler). Revisit in a follow-up stage.
2. **TextMeasurer vs StaticLayout for topText:** `StaticLayout.Builder` is the stable API for multi-line center alignment with line spacing; use it.
3. **Font fallback if variable font unavailable:** `Paint.typeface = ResourcesCompat.getFont(ctx, R.font.xxx) ?: Typeface.DEFAULT` — chain fallback, don't throw.
