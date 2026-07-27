// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.cairn.CairnTier

/**
 * iOS parity `CairnTier.glyphAssetName@9a418e4` — each tier's vector
 * master from the U13 import (port spec
 * `docs/parity/2026-07-27-port-vector-masters-u13.md`). The art is
 * baked-color: never tint it; ghost states use view alpha.
 */
@get:DrawableRes
val CairnTier.glyphRes: Int
    get() = when (this) {
        CairnTier.Faint -> R.drawable.glyph_cairn_faint
        CairnTier.Small -> R.drawable.glyph_cairn_small
        CairnTier.Medium -> R.drawable.glyph_cairn_medium
        CairnTier.Large -> R.drawable.glyph_cairn_large
        CairnTier.Great -> R.drawable.glyph_cairn_great
        CairnTier.Sacred -> R.drawable.glyph_cairn_sacred
        CairnTier.Eternal -> R.drawable.glyph_cairn_eternal
    }

/**
 * iOS parity `CairnTier.displayNameWithArticle@9a418e4` — "a faint" …
 * "an eternal". Accessibility labels splice this after a verb, and the
 * milestone tier must not read as "a eternal cairn". Articles live in
 * resources (translator-owned) instead of runtime a/an logic.
 */
@get:StringRes
val CairnTier.displayNameWithArticleRes: Int
    get() = when (this) {
        CairnTier.Faint -> R.string.cairn_tier_article_faint
        CairnTier.Small -> R.string.cairn_tier_article_small
        CairnTier.Medium -> R.string.cairn_tier_article_medium
        CairnTier.Large -> R.string.cairn_tier_article_large
        CairnTier.Great -> R.string.cairn_tier_article_great
        CairnTier.Sacred -> R.string.cairn_tier_article_sacred
        CairnTier.Eternal -> R.string.cairn_tier_article_eternal
    }
