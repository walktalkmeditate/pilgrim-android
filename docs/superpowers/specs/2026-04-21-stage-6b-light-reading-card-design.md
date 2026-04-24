# Stage 6-B — Light Reading card UI + Walk Summary integration

Status: design
Author: autopilot run 2026-04-21
Scope: Phase 6 of the Pilgrim Android port, sub-stage B. Ships the UI consumer for Stage 6-A's `core.celestial` primitives.

## Intent

Render a Light Reading card on the Walk Summary that surfaces the four primitives `LightReading.from(...)` returns: moon phase, sun times, planetary hour, koan. One card per walk. Always rendered (no reveal gate like iOS). Long-press copies the koan text.

## Goals

- One Compose `WalkLightReadingCard(reading: LightReading)` composable, pure and testable under Robolectric.
- A `LightReadingPresenter` (or similar) of pure functions that map primitives → display strings. Testable without Compose.
- `WalkSummaryViewModel` computes `LightReading` once per walk during `buildState()`; exposes via the existing `WalkSummary` aggregate.
- `WalkSummaryScreen` renders the card between `SummaryStats` and the voice-recordings list (or the Done button if no recordings).
- Graceful degradation when `sun == null` (walk had no GPS fix) — the sun row is omitted; the rest of the card renders.
- Typography: Cormorant Garamond for the koan + attribution (serif, already wired in Stage 3-B); Lato for the celestial-context labels + footer caption.
- Colors: `pilgrimColors.{parchment, ink, stone, fog, moss}` on a `Card` with standard `PilgrimCornerRadius.normal`.

## Design philosophy

The koan is the **emotional payload** — one true sentence for the walk. The celestial primitives are **context** — what the sky was doing. Both matter; the card keeps them compositionally distinct:

- **Top**: moon-phase emoji (visual anchor) + koan (serif body) + attribution (italic caption).
- **Bottom**: celestial context lines (sans-serif, `stone` color) — compact, scannable.
- **Footer**: italic "— a light reading" (matches iOS brand language).

Primitives are rendered as labels rather than composed into English sentences. This is iOS-divergent but intentional: Pilgrim Android has 40 koans (not 63 templates across 12 tiers), so composing template sentences from primitives would duplicate what the koan already does emotionally. The 12-tier priority ladder is explicitly deferred (Stage 6-C or later).

## Non-goals

- **iOS 12-tier priority ladder** (eclipse, supermoon, meteor shower, seasonal marker, etc.) — deferred to 6-C+.
- **Pre-composed English sentences** from primitives (iOS's 63 template system) — deferred. The card renders primitives as labels instead.
- **Procedural moon-phase canvas** (à la goshuin seal) — emoji is sufficient for 6-B. Canvas is a nice future enhancement.
- **Planetary color tinting** of the card border. Considered; judged too decorative for the wabi-sabi aesthetic. A single small dot next to the planetary-hour line uses a muted classical color — ambient, not loud.
- **Threshold-moment flourish** ("at the edge of light" for walks near sunrise/sunset) — Agent 3's nice-to-have. Deferred; the existing celestial rows already make sunrise/sunset proximity legible.
- **Long-press copy payload with full context block** (Agent 3's multi-line share text) — 6-B copies the koan text alone. Richer share payload can come with Phase 8's sharing feature.
- **Reveal-after-first-share gating** (iOS's `hasRevealedLightReading` flag). Android doesn't have sharing yet; always render.
- **Strings.xml localization** — English literals for 6-B, matching 6-A's English-only koan corpus. Phase 10.
- **Fade-in animation** on card reveal.
- **Error UI** if `LightReading.from` throws — the VM catches and leaves `lightReading = null`; the card simply doesn't render. Logged.

## Architecture

### Files to create

```
app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/
├── WalkLightReadingCard.kt           # @Composable — stateless, takes LightReading
└── LightReadingPresenter.kt          # pure fns — primitives → display strings
```

### Files to modify

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt` — extend `WalkSummary` with `lightReading: LightReading?`; compute in `buildState()`.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt` — render the card on the `Loaded` branch between stats and recordings.

### Files to test

- `app/src/test/java/.../ui/walk/WalkLightReadingCardTest.kt` — Robolectric + Compose test rule. Asserts visible strings for 3 variants (with-sun, without-sun, long-koan).
- `app/src/test/java/.../ui/walk/LightReadingPresenterTest.kt` — pure unit tests for each mapping function.

### Public API

`LightReadingPresenter` is an `internal object` (only the card needs it):

```kotlin
internal object LightReadingPresenter {
    /** Moon phase emoji: 🌑 🌒 🌓 🌔 🌕 🌖 🌗 🌘 — matches the 8 canonical phase names. */
    fun phaseEmoji(phaseName: String): String

    /** "Waxing Gibbous · 78% lit" */
    fun moonLine(moon: MoonPhase): String

    /** "Hour of Venus · Friday" */
    fun planetaryHourLine(hour: PlanetaryHour): String

    /** "Sunrise 03:47 · Sunset 19:58" — formatted in the supplied ZoneId.
     *  Returns null when SunTimes is null or both rise/set are null (polar). */
    fun sunLine(sun: SunTimes?, zoneId: ZoneId): String?

    /** Muted Chaldean color for the hour's ruling planet. Used as a small dot. */
    fun planetDotColor(planet: Planet): Color
}
```

`WalkLightReadingCard` is public within the package (not an exported API; only the Walk Summary calls it).

### VM integration

Extend `WalkSummary` (in `WalkSummaryViewModel.kt`):

```kotlin
@Immutable
data class WalkSummary(
    val walk: Walk,
    // ...existing fields...
    val sealSpec: SealSpec,
    val milestone: GoshuinMilestone? = null,
    // NEW: null iff LightReading.from failed (e.g., walkId <= 0 — unreachable
    // in production but guarded). Card simply doesn't render when null.
    val lightReading: LightReading? = null,
)
```

Inside `buildState()`:

```kotlin
val firstLocation = samples.firstOrNull()?.let {
    LocationPoint(timestamp = it.timestamp, latitude = it.latitude, longitude = it.longitude)
}
val lightReading = runCatching {
    LightReading.from(
        walkId = walkId,
        startedAtEpochMs = walk.startTimestamp,
        location = firstLocation,
        zoneId = ZoneId.systemDefault(),
    )
}.onFailure { Log.w(TAG, "LightReading.from failed for walk $walkId", it) }
 .getOrNull()
```

`ZoneId.systemDefault()` at render time is a documented simplification (same as iOS). 6-A's LEARN entry notes this; 6-B accepts it. Storing the walk's original zone in Room would require a migration — out of scope for 6-B.

### Compose stability

`LightReading` contains `Instant` fields (via SunTimes). Kotlin data classes are Stable in Compose, but `Instant` is not in the allow-list — Compose treats any `java.time` type as Unstable by default.

Mitigation: add `@Immutable` to `LightReading`, `MoonPhase`, `SunTimes`, `PlanetaryHour`, `Koan` in `core.celestial`. These types are genuinely immutable; the annotation is an honest contract. (Small edit to 6-A's files — acceptable scope crossover.)

### Layout

```
┌──────────────────────────────────────┐
│              🌔                       │  48sp emoji, centered
│                                      │
│  "The moon reflects in ten thousand  │  pilgrimType.body (Cormorant
│   pools, yet it is still one moon."  │  Garamond, 17sp, ink,
│                                      │  centered, minLines=2 maxLines=5)
│                                      │
│              — Zen                   │  pilgrimType.caption italic,
│                                      │  fog, centered (only if attribution)
│                                      │
│                                      │  (spacer — no divider)
│                                      │
│  Waxing Gibbous · 78% lit            │  pilgrimType.caption (Lato 12sp),
│  ● Hour of Venus · Friday            │  stone color, centered
│  Sunrise 03:47 · Sunset 19:58        │  (omitted if sun null or polar)
│                                      │
│          — a light reading           │  pilgrimType.caption italic,
│                                      │  fog/0.6, centered
└──────────────────────────────────────┘
```

- Card background: `pilgrimColors.parchmentSecondary` (slightly warmer than the screen bg so the card stands out).
- Corner radius: 12.dp (matches existing `SummaryMap` card).
- Padding: `PilgrimSpacing.big` vertical, `PilgrimSpacing.normal` horizontal.
- Moon emoji size: 48sp via `TextStyle(fontSize = 48.sp)`.
- Planet dot: 8.dp circle filled with `planetDotColor(hour.planet)`.
- Long-press handler: `Modifier.pointerInput` + `detectTapGestures(onLongPress = ...)` → `ClipboardManager.setText` + `HapticFeedback.LongPress`. No toast / snackbar; the haptic is the confirmation.

### Presenter details

**Moon emoji** (exact mapping):
```
"New Moon"         → 🌑
"Waxing Crescent"  → 🌒
"First Quarter"    → 🌓
"Waxing Gibbous"   → 🌔
"Full Moon"        → 🌕
"Waning Gibbous"   → 🌖
"Last Quarter"     → 🌗
"Waning Crescent"  → 🌘
(anything else)    → 🌕 (safe default)
```

**Moon line**: `"${phase.name} · ${(illumination * 100).roundToInt()}% lit"`. `Locale.US` isn't needed — integer-to-string defaults to ASCII digits.

**Planetary hour line**: `"Hour of ${planet} · ${dayOfWeekOf(dayRuler)}"`. Day-of-week is derived from planet (Sun → Sunday, Moon → Monday, …, Saturn → Saturday).

**Sun line**: `"Sunrise ${fmt(sunrise)} · Sunset ${fmt(sunset)}"` using `DateTimeFormatter.ofPattern("HH:mm").withZone(zoneId)`. Returns null if SunTimes is null OR if both sunrise and sunset are null (polar). When ONE is non-null but the other null: shouldn't happen from SunCalc but be defensive — render only the non-null side (e.g., "Sunrise 03:47").

**Planet dot color** (muted Chaldean):
```
Saturn  → #6B6359  (fog-ish, desaturated gray-brown)
Jupiter → #D4A87A  (dawn, muted gold)
Mars    → #A0634B  (rust, already in palette)
Sun     → #C4956A  (dawn, yellow-gold)
Venus   → #C9A8B0  (muted rose)
Mercury → #7A9CB8  (muted blue)
Moon    → #B8AFA2  (fog)
```

These are intentionally desaturated to sit on the parchment bg without shouting.

### Long-press behavior

```kotlin
Modifier.pointerInput(reading) {
    detectTapGestures(
        onLongPress = {
            clipboardManager.setText(AnnotatedString(reading.koan.text))
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        },
    )
}
```

Copies the koan text alone. No attribution. No celestial context. Users who want to share the whole moment can paste into their own message. Phase 8 will build a proper share sheet with full context.

No visible "Copied" confirmation — the haptic is the signal. Adding a Snackbar would add UI complexity for a low-frequency action.

### Preview provider

A `@Preview` with three variants: with-sun + attribution, without-sun, polar (null sunrise/sunset). Uses synthetic `LightReading` literals (doesn't call `LightReading.from`).

## Test plan

### `LightReadingPresenterTest`

Pure unit tests. No Compose, no Robolectric needed.

- `phaseEmoji` for all 8 canonical phase names → correct emoji. Unknown name → 🌕 default.
- `moonLine("Full Moon", 1.0)` → `"Full Moon · 100% lit"`.
- `moonLine("Waxing Gibbous", 0.78)` → `"Waxing Gibbous · 78% lit"`.
- `moonLine` with `illumination = 0.0` → `"New Moon · 0% lit"` (or however rounding resolves).
- `planetaryHourLine(PlanetaryHour(Venus, Venus))` → `"Hour of Venus · Friday"`.
- `planetaryHourLine` for all 7 day-rulers → correct English day name.
- `sunLine(sunTimes, zoneId)` for Paris 2024-06-21 @ CEST → `"Sunrise 05:47 · Sunset 21:58"`.
- `sunLine(null, zoneId)` → null.
- `sunLine(SunTimes(null, null, noon), zoneId)` (polar) → null.
- `sunLine` where only sunrise is non-null → `"Sunrise ..."` with no sunset half.
- `planetDotColor` for all 7 planets → each returns a distinct Color.

### `WalkLightReadingCardTest`

Robolectric + `createComposeRule()`. Asserts visible strings.

- `renders koan + attribution + moon line + planetary hour line + sun line + footer` — a fully-populated LightReading renders all rows.
- `renders without attribution when koan.attribution is null` — no "— Rumi"-style line.
- `renders without sun line when reading.sun is null` — moon + planetary hour rows still present.
- `renders with polar sun (null rise/set)` — sun line omitted, not crashed.
- `long koan text still renders without crash` — 120-char koan.

### VM integration test

Extend existing `WalkSummaryViewModelTest` (or add a new file) with:
- `buildState populates lightReading when walk has samples`.
- `buildState lightReading has sun when first sample has lat/lon`.
- `buildState lightReading has null sun when no samples`.
- (If feasible under the existing test harness.) Error path can be deferred to manual verification if mock setup is heavy.

## Risks + open items

- **Emoji rendering on older Android devices.** Moon phase emojis (🌑-🌘) have existed since Android 6+ in the system fonts. Our min SDK is 28 (Android 9); all devices support these.
- **Hour-of-X wording.** "Hour of Venus" is astrologically correct but might read oddly to casual users. Acceptable for a contemplative app; matches the iOS tone.
- **ZoneId render-time drift.** Already flagged in 6-A's LEARN. Accepted trade-off.
- **`LightReading.from` throwing.** Guarded with `runCatching`. In production this can only happen if walkId <= 0, which Room autoGenerate prevents. Logged + card omitted.
- **Future localization of English celestial labels.** "Waxing Gibbous", "Hour of Venus", "Sunrise", "Friday" are all English-literal in the presenter. Extracting to strings.xml is straightforward when Phase 10 arrives.

## Success criteria

- `./gradlew :app:testDebugUnitTest` passes.
- `./gradlew :app:assembleDebug` passes.
- Presenter tests all pass.
- Card renders cleanly in a manual APK install (device QA is optional for 6-B; the card is pure layout + immutable data).
- Long-press-to-copy works on-device (easy device QA).
- No Android framework imports beyond what's needed for Compose + ClipboardManager + HapticFeedback.

## Out of scope — explicit deferrals

Recorded here so a future reviewer doesn't re-raise them:

- 12-tier priority ladder, placeholder templates, astronomical events (eclipse, supermoon, meteor shower).
- Pre-composed English sentences from primitives.
- Procedural moon-phase canvas.
- Planetary-color border tinting.
- Threshold-moment flourish.
- Full-context share payload on copy.
- Localization.
- Reveal-after-share gating.
- Fade-in animation.
- Error UI for failed LightReading computation.
- Storing walk's original ZoneId in Room.

## References

- Stage 6-A spec: `docs/superpowers/specs/2026-04-21-stage-6a-celestial-koan-design.md`
- Stage 6-A LEARN: `~/.claude/projects/.../memory/autopilot-run-2026-04-21-stage-6a-celestial-koan.md`
- iOS card: `../pilgrim-ios/Pilgrim/Views/WalkLightReadingCard.swift`
- Android patterns: `WalkSummaryScreen.kt` (Stage 1-E initial + Stage 4-B SealRevealOverlay + Stage 2-E VoiceRecordingsSection).
