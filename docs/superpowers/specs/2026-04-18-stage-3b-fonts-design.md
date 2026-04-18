# Stage 3-B Design — Cormorant Garamond + Lato fonts

**Date:** 2026-04-18
**Stage:** 3-B (Phase 3, font polish)

## Phase 1 UNDERSTAND finding

`ui/theme/Type.kt` already has the ideal structure: `PilgrimTypography` is a custom data class (not M3's `Typography`), and it funnels through exactly TWO `FontFamily` constants at the top:

```kotlin
private val Display = FontFamily.Serif
private val Text = FontFamily.SansSerif
```

A comment on line 13-14 literally says: *"Font families are placeholders for now. Phase 1/3 swaps these for bundled Cormorant Garamond and Lato loaded via res/font/."*

**3-B is therefore two `FontFamily` swaps.** No typography role redefinition needed.

## Actual font weights used by `pilgrimTypography()`

Scanning the 12 TextStyle entries:

| Role | Family | Weight | Needs bundled? |
|---|---|---|---|
| `displayLarge` | Display | Light (300) | Cormorant Light |
| `displayMedium` | Display | Light (300) | Cormorant Light |
| `heading` | Display | SemiBold (600) | Cormorant SemiBold |
| `timer` | Text | Normal (400) | Lato Regular |
| `statValue` | Text | Normal (400) | Lato Regular |
| `statLabel` | Text | Normal (400) | Lato Regular |
| `body` | Display | Normal (400) | Cormorant Regular |
| `button` | Text | Bold (700) | Lato Bold |
| `caption` | Text | Normal (400) | Lato Regular |
| `annotation` | Display | Normal (400) | Cormorant Regular |
| `micro` | Text | Normal (400) | Lato Regular |
| `microBold` | Text | Bold (700) | Lato Bold |

**Required static-weight TTFs:** 5 total.
- `cormorant_garamond_light.ttf` (300)
- `cormorant_garamond_regular.ttf` (400)
- `cormorant_garamond_semibold.ttf` (600)
- `lato_regular.ttf` (400)
- `lato_bold.ttf` (700)

Revision from original intent: added Light + SemiBold for Cormorant (original intent only listed Regular + Medium, which would have forced `FontWeight.Light` to synthesize from Regular — Android would up-weight a Regular glyph to approximate Light, producing a smeary blur on the beautiful Cormorant forms). Skipped Lato Medium/Light; the typography doesn't use them.

## Deliverables

1. **Five TTF files in `app/src/main/res/font/`** (new directory). Source:
   - **Cormorant Garamond**: upstream repo `github.com/CatharsisFonts/Cormorant` (SIL OFL 1.1). Static TTFs are available in the repo's `4. Web Font Kits/` subdirectory AND via Google Fonts' `github.com/google/fonts/tree/main/ofl/cormorantgaramond`. Use google/fonts mirror — more stable, known-good at a specific commit, and already packaged as `CormorantGaramond-{Weight}.ttf`.
   - **Lato**: upstream repo `github.com/latofonts/lato-source` (SIL OFL 1.1). Static TTFs at `github.com/google/fonts/tree/main/ofl/lato`. Files: `Lato-Regular.ttf`, `Lato-Bold.ttf`.
   - Download via `curl` from a pinned commit on google/fonts. Document the exact commit in a `VENDORING.md` inside `res/font/` so future re-vendoring is reproducible.
   - Rename to lowercase-underscore per Android resource naming.

2. **`PilgrimFonts.kt`** in `ui/theme/` — single source of truth:
   ```kotlin
   object PilgrimFonts {
       val cormorantGaramond: FontFamily = FontFamily(
           Font(R.font.cormorant_garamond_light, weight = FontWeight.Light),
           Font(R.font.cormorant_garamond_regular, weight = FontWeight.Normal),
           Font(R.font.cormorant_garamond_semibold, weight = FontWeight.SemiBold),
       )
       val lato: FontFamily = FontFamily(
           Font(R.font.lato_regular, weight = FontWeight.Normal),
           Font(R.font.lato_bold, weight = FontWeight.Bold),
       )
   }
   ```

3. **`Type.kt` modification** — two-line swap:
   ```kotlin
   private val Display = PilgrimFonts.cormorantGaramond
   private val Text = PilgrimFonts.lato
   ```
   Remove the placeholder comment. No other changes.

4. **License attribution** — `app/src/main/assets/open_source_licenses.md`. Contains:
   - Header noting this is the attribution file for bundled third-party assets.
   - SIL OFL 1.1 full license text.
   - Attribution for Cormorant Garamond (by Catharsis Fonts, via google/fonts).
   - Attribution for Lato (by Łukasz Dziedzic, via google/fonts).
   - Attribution for the vendored whisper.cpp + ggml-tiny.en (Stage 2-D) while we're at it — not previously captured and would be good hygiene.
   - Attribution for Mapbox Maps SDK terms reference (link only, not full text — they're proprietary, not OSS).

   Placing in `assets/` rather than repo-root so a future Settings > About > Open Source Licenses surface can load it via `context.assets.open(...)`.

5. **Tests** — one Robolectric test `PilgrimFontsTest` that constructs both `FontFamily` references via `Font(R.font.*, weight)` and asserts the resources resolve (no `Resources.NotFoundException`). Does NOT load glyphs — that's the Compose renderer's job and requires a real device.

## Not in scope (per original intent)

- Italic variants (would need `cormorant_garamond_regular_italic.ttf` etc.)
- Variable-font editions (bundling static weights keeps APK minimal)
- Light/Bold Lato (typography doesn't use them)
- Extended language subsetting (keep the full Latin + Latin Extended set — adds a few KB but covers future localization)

## APK impact

Five static TTF files, typically 80-150 KB each. Estimated APK growth: ~500-700 KB. Debug APK goes from ~172 MB → ~173 MB. Negligible.

## Risks

1. **Font resource names must be lowercase.** Android's resource ID system rejects mixed-case filenames. `CormorantGaramond-Light.ttf` renamed to `cormorant_garamond_light.ttf`.

2. **Weight mismatch breaks at runtime.** If I bundle `cormorant_garamond_regular.ttf` and declare it as `FontWeight.Bold` in the `Font(...)` constructor, the compose text renderer will use the regular glyph for bold callers — breaking visual weight hierarchy. Double-check each `Font(R.font.X, weight = Y)` maps the TTF's actual weight to the declared weight.

3. **Missing weight falls back to synthesized.** If the typography asks for `FontWeight.W200` (Extra Light) and I only bundle W300 (Light), Android picks the closest and renders W300. That's acceptable — not buggy. The original intent to bundle only Regular + Medium would have missed Light AND SemiBold, forcing synthesized strokes on all headers and the `heading` role — the single worst-case visual bug.

4. **OFL compliance.** OFL 1.1 requires: (a) the bundled font cannot be sold on its own, (b) modifications to the font must rename the font, (c) the full license text must be bundled with the derivative (our APK). Requirement (c) is the one we satisfy by including `open_source_licenses.md`. (a) and (b) don't apply — we're bundling unmodified fonts inside an app.

## Testing

- `PilgrimFontsTest` — Robolectric, 2 assertions: each `FontFamily` constructible + each `Font(R.font.*)` reference resolves.
- Manual device verification deferred to Stage 3-G's end-to-end test (same pattern as Stage 3-A).

## Quality gates

- Assemble + lint + test CI gate green
- All 5 TTF files present in `res/font/`
- `open_source_licenses.md` contains OFL text + both font attributions
- Font resource names lowercase + underscore
- No `FontFamily.Serif` / `FontFamily.SansSerif` references in `Type.kt` after the swap
