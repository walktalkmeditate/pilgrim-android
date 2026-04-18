// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import org.walktalkmeditate.pilgrim.R

/**
 * Bundled typeface families for Pilgrim's theme. All glyphs are loaded
 * from locally-shipped TTF assets in `res/font/` (no Downloadable Fonts
 * dependency — privacy-first, offline-first).
 *
 * [cormorantGaramond] uses a single variable-weight TTF with three
 * `Font` declarations at different weights. Android 11+ (API 30+)
 * correctly interpolates along the wght axis via
 * [FontVariation.Settings]. On API 28/29 the platform typeface loader's
 * variable-font support is partial: for fonts whose `fvar` table
 * declares a continuous axis WITHOUT named instances (which is how
 * Cormorant Garamond ships), the weight hint may be ignored and all
 * three entries render at the font's default instance (~300 for
 * Cormorant). That's a visual degradation, not a crash. Stage 3-G's
 * on-device QA checklist should verify headings vs body weight
 * contrast on a real API 28/29 device; if it's unusable, the fix is
 * to vendor upstream's static-weight TTFs from
 * github.com/CatharsisFonts/Cormorant instead of google/fonts'
 * variable-only build.
 *
 * [lato] uses static-weight TTFs. Google Fonts still ships static
 * Lato; static Cormorant is no longer distributed.
 *
 * **Vendoring.** All five TTFs vendored from
 * `github.com/google/fonts` at commit
 * `47831f08ec6d6d7ad6b465f23dc9f9a890a2a04b`. Paths within that tree:
 *  - `ofl/cormorantgaramond/CormorantGaramond[wght].ttf` →
 *    `res/font/cormorant_garamond_variable.ttf`
 *  - `ofl/cormorantgaramond/CormorantGaramond-Italic[wght].ttf` →
 *    `res/font/cormorant_garamond_italic_variable.ttf`
 *  - `ofl/lato/Lato-Regular.ttf` → `res/font/lato_regular.ttf`
 *  - `ofl/lato/Lato-Bold.ttf` → `res/font/lato_bold.ttf`
 *  - `ofl/lato/Lato-Italic.ttf` → `res/font/lato_italic.ttf`
 *
 * To re-vendor: bump the commit hash, curl the raw files, drop into
 * `res/font/`, bump this doc and `app/src/main/assets/open_source_licenses.md`.
 *
 * Source fonts are SIL OFL 1.1; attribution + full license text lives
 * in `app/src/main/assets/open_source_licenses.md`.
 */
@OptIn(ExperimentalTextApi::class)
object PilgrimFonts {
    val cormorantGaramond: FontFamily = FontFamily(
        // variationSettings is required for a variable-weight TTF —
        // without it, all three Font entries default to the font's
        // built-in default instance (~300 for Cormorant Garamond),
        // so FontWeight.Light and FontWeight.SemiBold callers would
        // render identically. The weight parameter tells Compose
        // "this entry serves weight X requests"; variationSettings
        // tells the typeface system to instantiate the variable font
        // at wght=X.
        Font(
            resId = R.font.cormorant_garamond_variable,
            weight = FontWeight.Light,
            variationSettings = FontVariation.Settings(FontVariation.weight(300)),
        ),
        Font(
            resId = R.font.cormorant_garamond_variable,
            weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
        Font(
            resId = R.font.cormorant_garamond_variable,
            weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600)),
        ),
        // Italic variant. Without this, callers using
        // `fontStyle = FontStyle.Italic` on a body TextStyle would
        // trigger Compose's synthesized italic (an algorithmic slant
        // of upright glyphs) — noticeably worse than a true italic
        // cut on a high-contrast oldstyle serif.
        Font(
            resId = R.font.cormorant_garamond_italic_variable,
            weight = FontWeight.Normal,
            style = FontStyle.Italic,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
    )

    val lato: FontFamily = FontFamily(
        Font(R.font.lato_regular, weight = FontWeight.Normal),
        Font(R.font.lato_bold, weight = FontWeight.Bold),
        // Italic variant for the Home row caption and any other Lato
        // italic usage; avoids the synthesized-slant fallback.
        Font(R.font.lato_italic, weight = FontWeight.Normal, style = FontStyle.Italic),
    )
}
