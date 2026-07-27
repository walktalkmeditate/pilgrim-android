# Glyph Sheets, Cairn Detail, and Mood Rows (U16) — iOS → Android Port Spec

> **Plan:** `docs/plans/2026-07-23-001-feat-ios-v190-parity-port-plan.md` (U16) · **Requirements:** R11
> **iOS pin:** `pilgrim-ios` @ `9a418e4` (tag v1.9.0, 2026-07-21). All Swift quotes cite `file@9a418e4` unless a history commit is named.
> **Authority:** SHIPPED Swift over intermediate history. The #57 size doubling landed in three moves — `525bf5b` doubled the stone sheet (48/56 → 96/112) and the map pins, `32e1dfa` moved the doubling to the detail view (32–68 → 64–136, reverting the sheet to 48/56), `a227348` re-doubled the sheet ("double the add-a-stone glyphs too — 96/112pt"). What ships at the pin: **stone sheet 96/112pt, detail view 64–136pt** — both doublings stand.
> **Android files:** `ui/walk/StonePlacementSheet.kt`, `ui/walk/CairnDetailSheet.kt`, `ui/walk/WhisperPlacementSheet.kt`, `ui/walk/CairnTierResources.kt` (new), `data/cairn/CachedCairn.kt` (becomingTier); tests `data/cairn/CairnTierTest.kt`, `ui/walk/StonePlacementSheetGlyphTest.kt`, `ui/walk/CairnDetailSheetGlyphTest.kt`, `ui/walk/map/GlyphAssetTest.kt` (production-mapping pin).
> **Upstream unit:** U13 landed the eight drawables (`glyph_cairn_<tier>`, `glyph_whisper_wisp`) — see `docs/parity/2026-07-27-port-vector-masters-u13.md` for tint mechanics (wisp = white fill + root tint template; cairn art baked, never tinted).

## L1. Becoming-tier computation — the sheet previews what your stone makes

**iOS** (`Pilgrim/Models/Cairn/CachedCairn.swift@9a418e4`):

```swift
/// The tier this cairn becomes when a walker adds one stone — the
/// add-a-stone sheet previews this, not the current tier (AE1-AE3).
var becomingTier: CairnTier {
    CairnTier.from(stoneCount: stoneCount + 1)
}
```

`StonePlacementSheet.swift@9a418e4` consumes it as `cairn.becomingTier`; the new-cairn branch hardcodes `CairnTier.faint` ("A first stone always begins a faint cairn" — source comment), which equals `from(stoneCount: 0 + 1)`, so a nil cairn yields the first tier without special-casing.

**Android claims:**
- `CachedCairn.becomingTier` computed property: `CairnTier.forStoneCount(stoneCount + 1)` — on the model like iOS (not sheet-local), so the threshold semantics are testable beside `CairnTierTest` exactly as iOS pins them in `CairnTierTests`.
- Thresholds already match (`CairnTier.kt` minStones 0/3/7/12/42/77/108 ↔ iOS `from(stoneCount:)` cases `108... / 77... / 42... / 12... / 7... / 3... / default`).

## L2. Stone placement sheet — both sections carry becoming art

**iOS** (`Pilgrim/Scenes/ActiveWalk/StonePlacementSheet.swift@9a418e4`), sizes as shipped after `a227348`:

```swift
/// Glyph frames scale with the walker's text size — the surrounding
/// copy grows under accessibility sizes and the art must keep pace.
@ScaledMetric(relativeTo: .title) private var existingGlyphSize: CGFloat = 96
@ScaledMetric(relativeTo: .title) private var newGlyphSize: CGFloat = 112
```

Existing-cairn section (art → count → copy):

```swift
Image(cairn.becomingTier.glyphAssetName)
    .resizable()
    .scaledToFit()
    .frame(width: existingGlyphSize, height: existingGlyphSize)
    .accessibilityLabel("Becomes \(cairn.becomingTier.displayNameWithArticle) cairn")
```

New-cairn section (ghost treatment):

```swift
// View-level opacity, not a tint: the baked-color art ignores
// foregroundColor, and "not yet placed" must still read as ghost.
// A first stone always begins a faint cairn.
Image(CairnTier.faint.glyphAssetName)
    .resizable()
    .scaledToFit()
    .frame(width: newGlyphSize, height: newGlyphSize)
    .opacity(0.4)
    .accessibilityLabel("Begins a faint cairn")
```

**Android claims:**
- The shared `Icon(Icons.Outlined.Terrain)` placeholder is deleted; each branch owns its art per the iOS section structure (existing: 96dp art → stone count → "Add your stone to this cairn"; new: 112dp art at `Modifier.alpha(0.4f)` → "Start a new cairn here" → caption).
- `painterResource(becomingTier.glyphRes)` in the existing branch; `painterResource(CairnTier.Faint.glyphRes)` + `Modifier.alpha(0.4f)` in the new branch — view alpha, never a tint (U13 spec L3: tints no-op semantically on baked art; the wisp is the only template).
- `@ScaledMetric(relativeTo: .title)` has no direct Compose analogue; fixed 96.dp/112.dp (dp already tracks display scale; font-scale-linked icon sizing is not a repo convention — divergence D1).
- Accessibility: `contentDescription = "Becomes {a medium} cairn"` via format string + per-tier article strings; new branch `"Begins a faint cairn"` as a plain string. Copy sourced from `strings.xml` like every existing label in these sheets.

## L3. Article grammar — "a faint" through "an eternal"

**iOS** (`Pilgrim/Models/Walk/MapManagement/CairnTier.swift@9a418e4`):

```swift
/// "a faint" … "an eternal" — VoiceOver labels splice this after a verb,
/// and the milestone tier must not read as "a eternal cairn".
var displayNameWithArticle: String {
    self == .eternal ? "an \(displayName)" : "a \(displayName)"
}
```

**Android claims:**
- Seven `cairn_tier_article_<tier>` strings ("a faint" … "an eternal") + `CairnTier.displayNameWithArticleRes` extension in `CairnTierResources.kt`. Resource-per-tier rather than runtime "a"/"an" logic — articles are translator-owned on Android.
- iOS `testDisplayNameWithArticle_handlesEternal` maps to the Compose sheet test asserting the full spliced label `"Becomes an eternal cairn"` (107-stone cairn) and `"Becomes a small cairn"` (2-stone) — the article is pinned where it is user-visible.

## L4. Cairn detail hero — tier art replaces the symbol

**iOS** (`Pilgrim/Models/Cairn/CairnDetailView.swift@9a418e4`). The glyph commit `5341a82` deleted the SF-symbol path (`iconName` = `mountain.2`/`mountain.2.fill`) **and** the per-tier `iconGradient` (LinearGradient fog→stone→dawn) — baked art needs no foreground styling. Shipped hero:

```swift
Image(tier.glyphAssetName)
    .resizable()
    .scaledToFit()
    .frame(width: iconSize, height: iconSize)
    .accessibilityLabel("\(tier.displayNameWithArticle) cairn")
    .scaleEffect(appeared ? 1.0 : entryScale)
    .opacity(appeared ? 1.0 : 0)
    .scaleEffect(breathing ? 1.03 : 1.0)
```

Per-tier sizes as shipped after `32e1dfa` ("the doubling belongs to the detail view"):

```swift
private var iconSize: CGFloat {
    switch tier {
    case .faint: return 64
    case .small: return 76
    case .medium: return 88
    case .large: return 100
    case .great: return 112
    case .sacred: return 124
    case .eternal: return 136
    }
}
```

(= 64 + rawValue × 12; pre-doubling was 32–68.) The same commit grew the hero frame 140 → 220 and the great+ glow ring 180 → 260 (breathing endRadius 80/90 → 116/130). Kept at the pin: kanji watermark (120pt ultralight, opacity 0.04–0.08, `accessibilityHidden(true)`), glow ring for great+, breathing scale, entry spring.

**Android claims:**
- Hero `Icon(Icons.Outlined.Terrain, size = 32 + ordinal*6)` → `Image(painterResource(tier.glyphRes), size = (64 + ordinal * 12).dp)` — the doubled table verbatim (64/76/88/100/112/124/136).
- No tint parameter, no gradient analogue — art as authored (iOS dropped `iconGradient` for the same reason).
- Kanji watermark kept, and now explicitly `clearAndSetSemantics {}` (iOS `accessibilityHidden(true)`) so TalkBack reads the hero label, not the ideograph.
- Hero `contentDescription = "{an eternal} cairn"` via the L3 article strings.
- Glow ring + breathing + entry spring remain **deferred** on Android (pre-existing documented deferral, decorative-only; unchanged by this unit — divergence D2).

## L5. Whisper mood rows — the tinted wisp

**iOS** (`Pilgrim/Scenes/ActiveWalk/WhisperPlacementSheet.swift@9a418e4`, `categoryRow` between preview button and label):

```swift
Image(MapGlyphImageBuilder.whisperAssetName)
    .resizable()
    .renderingMode(.template)
    .scaledToFit()
    .frame(width: 20, height: 20)
    .foregroundColor(Color(category.borderColor))
    .accessibilityHidden(true)
```

**Android claims:**
- `Image(painterResource(R.drawable.glyph_whisper_wisp), colorFilter = ColorFilter.tint(category.borderColor), Modifier.size(20.dp))` inserted in `CategoryRow` between the preview IconButton and the label — same slot, same 20dp, same category color source (`WhisperCategory.borderColor`, already the row's border/play tint).
- The wisp drawable is the U13 template (white fill + root tint); a draw-time `ColorFilter.tint` (SRC_IN default) overrides the root-tint default exactly like iOS `renderingMode(.template) + foregroundColor` — the Compose analogue of `Drawable.setTint`.
- `contentDescription = null` (decorative; the row itself carries the mood name) — iOS `accessibilityHidden(true)`.

## L6. Tier → drawable mapping goes production

U13 shipped the mapping only as a test pin (`GlyphAssetTest.tierDrawables`). U16 is the first production consumer, so `CairnTierResources.kt` adds `CairnTier.glyphRes` (the Android analogue of iOS `CairnTier.glyphAssetName`, quoted in the U13 spec L1), and `GlyphAssetTest` gains an assertion that the production mapping matches its independently pinned table — double-entry, same spirit as iOS `testTierAssetNameMapping`. U14's rasterizer can consume the same extension.

## L7. Test parity map

| iOS (`UnitTests/CairnTierTests.swift@9a418e4`, pinned by `db06ceb`) | Android |
|---|---|
| `testTierThresholdTable` — 0,2→faint; 3,6→small; 7,11→medium; 12,41→large; 42,76→great; 77,107→sacred; 108→eternal | `CairnTierTest.forStoneCount maps thresholds` extended with the upper-bound counts 6/11/41/76/107 (already had 0/2/3/7/12/42/77/108/500) |
| `testBecoming_sixStones_crossesIntoMedium` (AE1) | `becoming six stones crosses into medium` |
| `testBecoming_eightStones_staysMedium` (AE2) | `becoming eight stones stays medium` |
| `testBecoming_newCairn_isFaint` (AE3, `from(stoneCount: 1) == .faint`) | `becoming for a new cairn is faint` |
| `testBecoming_thresholdCrossings` — 2→small, 11→large, 41→great, 76→sacred, 107→eternal | `becoming crosses every remaining threshold` |
| `testSoundTier_boundaryCounts` — 2→1, 3→2, 6→2, 7→3, 41→4, 42→5, 77→6, 108→7 | `soundTier boundary counts` (`forStoneCount(n).soundTier` — Android has no separate static, same one-derivation guarantee) |
| `testDisplayNameWithArticle_handlesEternal` — "an eternal", "a faint" | `StonePlacementSheetGlyphTest` asserts the spliced labels "Becomes an eternal cairn" / "Becomes a small cairn" (articles live in resources; pinned at the user-visible splice) |
| — (sheet rendering verified on device) | `StonePlacementSheetGlyphTest` — becoming art node exists for existing cairn (6 stones → "Becomes a medium cairn") and new cairn ("Begins a faint cairn"); `CairnDetailSheetGlyphTest` — hero names its tier for faint and eternal |
| `GlyphAssetTests.testTierAssetNameMapping` (U13) | `GlyphAssetTest.production glyph mapping matches the pinned table` (new: `tier.glyphRes` vs the test's own map) |

## Divergences (conscious) and resolved ambiguities

- **D1 — `@ScaledMetric` sizing**: iOS scales the 96/112pt frames with Dynamic Type (`relativeTo: .title`). Android ships fixed dp; no repo convention exists for font-scale-linked icon frames and Compose has no one-line analogue. The doubled base values are what parity pins; font-scale tracking is recorded as a known simplification.
- **D2 — detail-sheet motion**: glow ring, breathing scale, and entry spring were already documented as deferred decorative items in `CairnDetailSheet.kt`'s header before this unit; U16 keeps that deferral (kanji watermark and tier scaling are kept, per plan). The doubled glow-ring numbers (260 frame / 116–130 radius) are quoted in L4 so the deferral, if ever picked up, lands at shipped size.
- **D3 — becoming property placement**: plan text says "becoming tier as a private computed property" on the sheet; shipped iOS puts it on `CachedCairn` where `CairnTierTests` exercises it. Android follows shipped iOS (model property) so the AE1–AE3 tests are model tests, not UI tests.
- **D4 — article strings**: runtime `a`/`an` ternary becomes seven translator-owned resources (L3). English output is byte-identical.
- **Resolved**: the new-cairn ghost needs no tintable art — view alpha over the baked faint master reads as ghost in both themes (iOS source comment, L2 quote).
- **Out of scope**: map-pin sizing and rasterization (U14/U15 — `MapGlyphImageBuilder`, 28pt whisper / `24 + rawValue × 2` cairn from `525bf5b` belong to those specs); Seek/proximity banners; any surface not named by plan U16.
