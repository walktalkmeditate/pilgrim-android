# Parity audit — journal walk dot vs iOS `WalkDotView` @ c1745e8

**Date**: 2026-07-16
**Trigger**: device QA — user reports Android walk dots read near-black/flat
side-by-side against iOS, spring green called out specifically.
**iOS spec**: `Pilgrim/Scenes/Home/WalkDotView.swift`,
`Pilgrim/Scenes/Home/InkScrollView.swift`, `Pilgrim/Models/SeasonalColorEngine.swift`,
`Pilgrim/Models/Constants.swift`, asset colorsets — all read at `c1745e8`.
**Android under audit**: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/`
(`WalkDot.kt`, `WalkDotColor.kt`, `WalkDotMath.kt`) + composition site in
`ui/home/HomeScreen.kt` + `ui/theme/Color.kt`, `ui/theme/Tokens.kt`,
`ui/theme/seasonal/SeasonalColorEngine.kt`.

## Verdict summary

The **color pipeline is verbatim** — every palette hex, every seasonal-engine
constant, and the month-bucket/turning rules match iOS exactly. The
"near-black/flat" reading traces to **five structural drifts** in the layer
stack, headlined by the drop-shadow color: Android ported the **pre-3c8c443**
iOS shadow (`.ink.opacity(0.15)`), which iOS itself fixed because adaptive ink
"inverts to near-white in dark mode and renders as a light halo around every
dot" — flattening the dot silhouette and making it read darker. All five
fixed.

## Element table

| # | Element | iOS @ c1745e8 | Android (before) | Verdict |
|---|---------|----------------|------------------|---------|
| 1 | Size math | `minSize 8 / maxSize 22`; duration clamped 300–7200 s, linear (`dotSize` in WalkDotView.swift:169-175) — note: **duration**, not distance | `WalkDotMath.dotSize` identical | **match** |
| 2 | Age fade | `dotOpacity = 1.0 − (index/(total−1)) × 0.5`, newest 1.0 → oldest 0.5 (InkScrollView.swift:572-576) | `WalkDotMath.dotOpacity` identical | **match** |
| 3 | Dot color rule | Turning day → raw cardinal accent; else month bucket 3-5 moss / 6-8 rust / 9-11 dawn / else ink, then `SeasonalColorEngine.seasonalColor(named:, intensity: .full, on: snapshot.startDate)` (WalkDotView.swift:177-193) | `walkDotColor` identical (raw palette in, Full shift at walk date) | **match** |
| 4 | Palette tokens | moss L `#7A8B6F` / D `#95A888`, rust `#A0634B`/`#C47E63`, dawn `#C4956A`/`#D4A87A`, ink `#2C2416`/`#F0EBE1`, fog `#8A8175`/`#948E88` (post-AF68), stone `#8B7355`/`#B8976E`, turning jade/gold/claret/indigo — from `Assets.xcassets/*.colorset` | `PilgrimPaletteLight/Dark` — every channel re-derived from the colorset floats, all 26 values match | **match** |
| 5 | Seasonal engine | peaks 105/196/288/15, spread 91, hue +0.02/+0.01/+0.03/−0.02, sat +0.10/+0.15/+0.05/−0.15, bright +0.05/+0.03/−0.03/−0.05; cos² weights; Full=1.0 scale (Constants.swift Seasonal, SeasonalColorEngine.swift) | `PilgrimSeasonal` + `SeasonalColorEngine` identical | **match** |
| 6 | Ripple (newest only) | 2 rings: phase `(t×0.4 + i×0.5) % 1`, radius `0.5s + phase×1.2s`, α `(1−phase)×0.2`, lw `1.5×(1−phase×0.5)`; breath glow `1.5s` α `0.04 + breath×0.04`; Reduce-Motion → static ring `s+16` α 0.15 lw 1.5 (WalkDotView.swift:231-283). **No age fade** | Formulas identical; sat under a root `graphicsLayer{alpha=opacity}` (no visible effect — newest is always opacity 1.0) | **match** (root alpha removed anyway in fix #3) |
| 7 | Outer halo / aura | `RadialGradient([dotColor.opacity(0.15), .clear], startRadius: s×0.5, endRadius: s×1.8)` on a `s×3.5` frame — **no `.opacity(opacity)`, aura never age-fades** (WalkDotView.swift:59-68) | Age-faded via root `graphicsLayer { alpha = opacity }` → oldest dots' auras at half strength; fade also ended at 1.75s instead of 1.8s | **drift → fixed** (per-layer opacity; brush radius now `s×1.8` with plateau stop `0.5/1.8` — exact) |
| 8 | Core fill | `RadialGradient([dotColor, dotColor.opacity(0.7)], center: UnitPoint(0.4, 0.35), endRadius: s×0.6)` × `.opacity(opacity)` (WalkDotView.swift:70-80) | Identical (gradient radius `coreR×1.2` ≡ `s×0.6`; opacity now multiplied into the gradient colors) | **match** |
| 9 | Core drop shadow (PR #176 layer) | `.shadow(color: .black.opacity(0.15), radius: 2, x: 1, y: 2)` — **fixed black** since iOS `3c8c443`: *"Fixed .black, not adaptive .ink: .ink inverts to near-white in dark mode and renders as a light halo around every dot."* Applied after `.opacity(opacity)`, so it fades with age | `pilgrimColors.ink.copy(alpha = 0.15f)` — the **pre-fix** iOS value. In dark mode that is `#F0EBE1` @ 15%, blurred: a light halo behind every dot → the dot reads darker/flatter. **This is the headline cause of the QA report** | **drift → fixed** (`Color.Black.copy(alpha = 0.15f)` × opacity, pinned by test) |
| 10 | Favicon glyph | SF Symbol at `s×0.4`, bold, `.parchment`, `.shadow(color: .black.opacity(0.4), radius: 0.5, x: 0, y: 0.5)`, × opacity (WalkDotView.swift:122-136) | Icon at 0.4×, parchment — **no shadow at all** | **drift → fixed** (black-40% icon copy nudged 0.5 dp down; the 0.5 pt blur is sub-pixel at glyph scale and omitted — documented) |
| 11 | Activity arcs — **the "warm arc" identified** | Talk arc `Color.rust.opacity(0.7)` trim `[0, talkFrac]` + meditate arc `Color.dawn.opacity(0.7)` trim `[talkFrac, talkFrac+meditateFrac]`, lw 2, ring `s+5`, `rotationEffect(-90°)` (starts 12 o'clock), only when frac > 0.01, × opacity (WalkDotView.swift:140-167) | Identical geometry, colors from `pilgrimColors.rust/.dawn` (≡ iOS `Color.rust/.dawn`, Full/Full shift at today) | **match** — the small warm arc on the top edge is the **talk-duration arc in rust**; iOS draws the same element with the same geometry |
| 12 | Specular highlight | `RadialGradient([white.opacity(0.3), .clear], center (0.3, 0.3), endRadius s×0.4)`, frame `s×0.7`, offset `(−0.08s, −0.08s)`, × `opacity×0.5` (WalkDotView.swift:89-100) | Identical | **match** |
| 13 | Shared-walk ring | `stroke(Color.stone.opacity(0.5), lineWidth: 1)`, frame `s+12`, × opacity (WalkDotView.swift:102-107) | Identical | **match** |
| 14 | Archived treatment | Hollow ring `stroke(Color.fog.opacity(0.5), lineWidth: 1)` at **`s×0.6`** inside a **fixed 44×44 frame**; the archived branch **ignores `opacity`** — no age fade (WalkDotView.swift:37-47) | Ring at full `s`, touch target only `s` (8-22 dp), age-faded via `graphicsLayer` | **drift ×3 → fixed** (0.6× ring, 44 dp frame, constant alpha) |
| 15 | Tap target | `.frame(width: max(44, size × 3.5))` + `contentShape(Circle())` (WalkDotView.swift:110-111, from the cbd24fc 44 pt a11y sweep) | Box = `3.5×s` only → 28 dp for the smallest dots | **drift → fixed** (`WalkDotMath.dotBoxDp = max(44, 3.5×s)`; HomeScreen offset re-centered on the actual box). Rect hit shape vs iOS circle: **accepted** (superset, no visual) |
| 16 | Long-press | `simultaneousGesture(LongPressGesture(0.4s))` → transient `previewSnapshot` card (InkScrollView.swift:284-290) | Routed to the same expand-card `onTap` — documented prior product decision | **drift-accepted** (same content surfaces; pre-existing documented deviation) |
| 17 | Distance label (dot-adjacent) | `.fog.opacity(0.5)`, micro font, x ±32, y +14, × `opacity×0.7` (InkScrollView.swift:614-625) | `fogColor.copy(alpha = 0.5×labelAlpha)`, offsets 32/14 dp, `labelOpacity = dotOpacity×0.7` | **match** |

## Accepted approximations (documented, not drift)

- **Shadow blur mapping**: SwiftUI `.shadow(radius: 2)` ↔ `BlurMaskFilter(2dp, NORMAL)` — different Gaussian parameterizations, visually equivalent at dot scale.
- **HSV quantization**: Android decomposes via 8-bit `RGBToHSV`; iOS via continuous `UIColor.getHue`. ≤1/255 per channel.
- **Ripple clock**: iOS `TimelineView(minimumInterval: 1/10)` (10 fps floor); Android `sceneryTimeSeconds()` (frame-driven, 300 s loop). Smoother, formulas identical; the breath-glow phase has a sub-0.04-alpha discontinuity at the 5-min wrap.
- **Favicon shadow blur**: iOS 0.5 pt Gaussian omitted (sub-pixel); offset + color exact.

## Spring-green ARGB comparison (the specific QA question)

Both platforms compute the identical value — **no color drift**:

| Appearance | Base moss | Shifted @ spring peak (day 105, Full) |
|---|---|---|
| Light | `#FF7A8B6F` (both) | ≈ `#FF7A9272` (both — hue +0.02, sat ×1.09996, bright ×1.04999) |
| Dark | `#FF95A888` (both) | ≈ `#FF96B08B` (both) |

The near-black perception is explained by element #9: in dark mode Android
drew a near-white (`#F0EBE1` @ 15%, blurred) halo behind every dot — the
sage-green dot silhouetted against a light glow reads dark and flat. In light
mode, element #7 dimmed older dots' auras to half strength, compounding the
"flat" reading. Both fixed.

## Fixes shipped in this commit

1. `WalkDot.kt` — core shadow pinned to `Color.Black.copy(alpha = 0.15f)` (iOS 3c8c443 quote above).
2. `WalkDot.kt` — favicon glyph black-40% shadow added.
3. `WalkDot.kt` — root `graphicsLayer { alpha = opacity }` replaced with per-layer age fade (ripple/halo constant; shadow, core, favicon, arcs ×opacity; specular ×opacity×0.5; shared ring ×opacity), and the halo gradient now uses iOS's exact `startRadius 0.5s / endRadius 1.8s` bounds.
4. `WalkDot.kt` — archived ring at `s×0.6` in a fixed 44 dp frame with no age fade.
5. `WalkDotMath.dotBoxDp` — live dots `max(44, 3.5×s)`, archived 44; `HomeScreen.kt` centers on the actual box (replaces `HALO_HALF`).

Tests: `WalkDotMathTest` (+4: box floor/scale/archived + constant pins),
`WalkDotColorTest` (+2: shadow-color pins), `WalkDotComposableTest`
(+3: 44 dp minimum for small live dot and archived ring, 77 dp for largest).

## Device QA checklist

- Dark mode: no light halo behind dots; shadow reads as a soft dark drop below-right.
- Spring (moss) dots read sage green in both modes, matching iOS side-by-side.
- Oldest dots: core fades to 50% but the soft colored aura stays full strength (scroll to the bottom of a long journal).
- Archived walks: tiny hollow fog ring (much smaller than before — 0.6× the dot size), does not fade with age, still comfortably tappable.
- Smallest dots (short walks) are tappable without hunting (44 dp target).
- Favicon glyphs show a hair of dark shadow under the parchment glyph.
- Dot centers still sit exactly on the calligraphy thread (offset math changed).
