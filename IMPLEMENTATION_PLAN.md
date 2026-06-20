# Seasonal + Turning-Day Parity Pass

Parity target: pilgrim-ios **v1.6.0** (`fcd2255`). Brings the Android
seasonal-color + solstice/equinox ("turning") surfaces up to iOS parity.

Root system: a walk's color is driven by (1) whether its start date is a
turning day (solstice/equinox, ±1.5° sun longitude) and (2) otherwise the
season (month) plus a HSB seasonal shift. Android shipped the engine and
detection but left several consumers stubbed.

## Stage 1: Journal dot + thread + expand-card seasonal color
**Goal**: Dots/threads vary by season (moss/rust/dawn/ink) + turning colors, matching iOS intensities.
**iOS refs**: `WalkDotView.swift:166-180`, `InkScrollView.swift:287-303,645-674` @fcd2255.
**Changes**:
- `WalkDotColor.kt`: replace `walkDotBaseColor` with `walkDotColor` (Full, season base, turning raw) + `walkThreadColor` (Moderate, season base, turning × 0.85 opacity). Turning colors are NOT seasonally shifted (iOS returns them raw).
- `HomeScreen.kt`: dot color → `walkDotColor` (Full); thread (`CalligraphyStrokeSpec.ink`) → `walkThreadColor` (Moderate); expand-card `seasonColor` → `walkDotColor` (Full).
**Tests**: `WalkDotColorTest` — month→base mapping, turning→accent, turning-not-shifted, full-vs-moderate divergence, thread 0.85 opacity on turning.
**Success**: summer dots read rust, autumn dawn, winter ink; turning days jade/gold/claret/indigo; thread dimmer than dot.
**Status**: Complete (11 tests pass)

## Stage 2: Walk Summary turning kanji
**Goal**: Append `· 春分/夏至/秋分/冬至` to the summary date title on turning days.
**iOS ref**: `WalkSummaryView.swift:160-170` @fcd2255.
**Changes**: compute walk turning in `WalkSummaryViewModel`; append kanji to the date-title string the screen renders.
**Tests**: VM emits kanji-suffixed title on a turning date, plain title otherwise.
**Status**: Complete (6 tests pass)

## Stage 3: Share payload turning_day
**Goal**: Send `turning_day` = `spring-equinox|summer-solstice|autumn-equinox|winter-solstice` instead of hardcoded null.
**iOS ref**: `WalkShareViewModel.swift:327-343` @fcd2255 (hemisphere-aware via first route coord).
**Changes**: `SharePayloadBuilder.kt` — compute turning from walk start + first route coord; map to iOS code strings; cross-quarter → null.
**Tests**: builder sets correct code on a turning walk, null otherwise.
**Status**: Not Started

## Stage 4: Celestial vignette turning halo
**Goal**: Soft turning-colored corona ring around the vignette pill on turning days.
**iOS ref**: `CelestialVignetteView.swift:36-51` @fcd2255 (`stroke(color.opacity(0.55), 1.5)` + shadow).
**Changes**: `WalkVignette.kt` — overlay a turning-colored capsule stroke on the celestial chip when today is a turning day.
**Tests**: halo present on turning day, absent otherwise (Robolectric or extracted color helper).
**Status**: Not Started

## Stage 5: Goshuin seal color parity
**Goal**: Replace `rust × seasonal-shift` seal ink with the iOS palette system.
**iOS ref**: `SealColorPalette.swift` (full) + `SealGenerator.swift:43` (raw, no shift) @fcd2255.
**Changes**:
- New `SealColorPalette.kt`: 16 base colors (warm/cool/accent/neutral, light+dark hex), 4 turning colors, `color(favicon, hashByte)` category map, `sealInk(spec, favicon, startMs, coord, isDark)` entry that turning-overrides then falls back to favicon+`hashByte[30]`. NO seasonal shift.
- Route all seal-ink call sites (`WalkSummaryScreen` reveal, `GoshuinScreen`, `GoshuinViewModel`, `HomeViewModel` FAB) through the palette. Use the Android FNV/SplitMix `sealHashBytes` byte[30] (geometry already diverges cross-platform by design — match the color *system*, not byte-exact color).
**Tests**: favicon→category, hashByte modulo selection, turning override precedence, dark/light variant, no-shift invariant.
**Status**: Not Started

## Notes / decisions
- Month→season base bucket is NOT hemisphere-adjusted on iOS (only the HSB shift is). Port faithfully.
- iOS turning seal returns `.light` even in dark mode — replicate with a comment (variants are near-identical).
- Per project rule: any new platform-builder path needs a Robolectric `.build()` test (none expected here — pure color logic).
