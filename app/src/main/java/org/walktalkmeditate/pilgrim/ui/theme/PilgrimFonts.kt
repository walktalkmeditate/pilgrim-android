// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
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
 * interpolates weights from the wght axis; API 28/29 fall back to the
 * nearest available instance — visually similar enough for our
 * header-only usage.
 *
 * [lato] uses static-weight TTFs. Google Fonts still ships static
 * Lato; static Cormorant is no longer distributed.
 *
 * **Vendoring.** All three TTFs vendored from
 * `github.com/google/fonts` at commit
 * `47831f08ec6d6d7ad6b465f23dc9f9a890a2a04b`. Paths within that tree:
 *  - `ofl/cormorantgaramond/CormorantGaramond[wght].ttf` →
 *    `res/font/cormorant_garamond_variable.ttf`
 *  - `ofl/lato/Lato-Regular.ttf` → `res/font/lato_regular.ttf`
 *  - `ofl/lato/Lato-Bold.ttf` → `res/font/lato_bold.ttf`
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
        // built-in default instance (~400), so FontWeight.Light and
        // FontWeight.SemiBold callers would render identically. The
        // weight parameter tells Compose "this entry serves weight X
        // requests"; variationSettings tells the typeface system to
        // instantiate the variable font at wght=X.
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
    )

    val lato: FontFamily = FontFamily(
        Font(R.font.lato_regular, weight = FontWeight.Normal),
        Font(R.font.lato_bold, weight = FontWeight.Bold),
    )
}
