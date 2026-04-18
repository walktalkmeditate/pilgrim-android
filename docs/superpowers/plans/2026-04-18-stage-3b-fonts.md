# Stage 3-B Implementation Plan — Cormorant Garamond + Lato fonts

**Spec:** [2026-04-18-stage-3b-fonts-design.md](../specs/2026-04-18-stage-3b-fonts-design.md)

**Test command:**
```bash
JAVA_HOME=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8 ANDROID_HOME=/Users/rubberduck/Library/Android/sdk PATH=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8/bin:$PATH ./gradlew assembleDebug lintDebug testDebugUnitTest
```

Tasks 1 → 7.

---

## Task 1 — Fetch TTFs from google/fonts mirror, rename, commit

Pin to a specific commit in `github.com/google/fonts` so re-vendoring is reproducible. Use `main` branch as of 2026-04-18 (capture the exact commit hash in `res/font/VENDORING.md`).

```bash
mkdir -p /tmp/font-vendor && cd /tmp/font-vendor
COMMIT=main   # pinned at fetch time; replace with sha once fetched

# Cormorant Garamond: Light (300), Regular (400), SemiBold (600)
curl -L -o cormorant_garamond_light.ttf \
  "https://raw.githubusercontent.com/google/fonts/${COMMIT}/ofl/cormorantgaramond/CormorantGaramond-Light.ttf"
curl -L -o cormorant_garamond_regular.ttf \
  "https://raw.githubusercontent.com/google/fonts/${COMMIT}/ofl/cormorantgaramond/CormorantGaramond-Regular.ttf"
curl -L -o cormorant_garamond_semibold.ttf \
  "https://raw.githubusercontent.com/google/fonts/${COMMIT}/ofl/cormorantgaramond/CormorantGaramond-SemiBold.ttf"

# Lato: Regular (400), Bold (700)
curl -L -o lato_regular.ttf \
  "https://raw.githubusercontent.com/google/fonts/${COMMIT}/ofl/lato/Lato-Regular.ttf"
curl -L -o lato_bold.ttf \
  "https://raw.githubusercontent.com/google/fonts/${COMMIT}/ofl/lato/Lato-Bold.ttf"

# Move into the project
mkdir -p /Users/rubberduck/GitHub/momentmaker/pilgrim-android/app/src/main/res/font/
mv /tmp/font-vendor/*.ttf /Users/rubberduck/GitHub/momentmaker/pilgrim-android/app/src/main/res/font/
```

Then write `app/src/main/res/font/VENDORING.md` capturing the pinned commit hash.

---

## Task 2 — `PilgrimFonts.kt`

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/PilgrimFonts.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.walktalkmeditate.pilgrim.R

/**
 * Bundled typeface families for Pilgrim's theme. All glyphs are loaded
 * from locally-shipped TTF assets in `res/font/` (no Downloadable Fonts
 * dependency — privacy-first, offline-first).
 *
 * Source fonts are SIL OFL 1.1; attribution lives in
 * `app/src/main/assets/open_source_licenses.md`.
 */
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

---

## Task 3 — Swap `Type.kt` families

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Type.kt` (modify)

Replace:
```kotlin
// Font families are placeholders for now. Phase 1/3 swaps these for bundled
// Cormorant Garamond and Lato loaded via res/font/.
private val Display = FontFamily.Serif
private val Text = FontFamily.SansSerif
```
With:
```kotlin
private val Display = PilgrimFonts.cormorantGaramond
private val Text = PilgrimFonts.lato
```

Remove the `import androidx.compose.ui.text.font.FontFamily` if it becomes unused (it's still used by `PilgrimFonts`; the import here was only needed for `FontFamily.Serif` / `FontFamily.SansSerif`).

---

## Task 4 — License attribution file

**File:** `app/src/main/assets/open_source_licenses.md` (new)

Contents structure:

```markdown
# Open source licenses

Third-party assets and code bundled with Pilgrim for Android.

## Fonts

### Cormorant Garamond
- **Copyright:** © 2015 Catharsis Fonts (Christian Thalmann)
- **License:** SIL Open Font License 1.1
- **Source:** https://github.com/google/fonts/tree/main/ofl/cormorantgaramond
- **Usage:** Bundled unmodified in `res/font/` as cormorant_garamond_{light,regular,semibold}.ttf

### Lato
- **Copyright:** © 2010-2014 Łukasz Dziedzic
- **License:** SIL Open Font License 1.1
- **Source:** https://github.com/google/fonts/tree/main/ofl/lato
- **Usage:** Bundled unmodified in `res/font/` as lato_{regular,bold}.ttf

## Native libraries

### whisper.cpp
- **Copyright:** © 2023 Georgi Gerganov + contributors
- **License:** MIT
- **Source:** https://github.com/ggml-org/whisper.cpp (tag v1.7.5)
- **Usage:** Vendored under `app/src/main/cpp/whisper/` for on-device transcription

### ggml
- **Copyright:** © 2023 Georgi Gerganov + contributors
- **License:** MIT
- **Source:** github.com/ggml-org/whisper.cpp → ggml/ subtree (same tag)

### ggml-tiny.en Whisper model
- **Copyright:** © 2022 OpenAI; converted to ggml format by Georgi Gerganov
- **License:** MIT
- **Source:** https://huggingface.co/ggerganov/whisper.cpp
- **Usage:** Bundled at `app/src/main/assets/models/ggml-tiny.en.bin` for transcription

## Runtime dependencies

Third-party libraries pulled via Gradle (Mapbox SDK, AndroidX, Hilt, media3, etc.) carry their own licenses — see `gradle/libs.versions.toml` and the dependency authors' pages.

---

## SIL Open Font License, Version 1.1

<full OFL 1.1 text here>
```

Include the full OFL 1.1 text verbatim from `https://scripts.sil.org/OFL` (static — the license text hasn't changed since 2007). Paste into the markdown file under the `## SIL Open Font License, Version 1.1` header.

---

## Task 5 — `PilgrimFontsTest`

**File:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/theme/PilgrimFontsTest.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimFontsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `cormorant garamond family constructs without throwing`() {
        // FontFamily instantiation succeeds; resolving a glyph would
        // require a Compose TextMeasurer which is instrumented-test
        // territory (Stage 3-G).
        assertNotNull(PilgrimFonts.cormorantGaramond)
    }

    @Test
    fun `lato family constructs without throwing`() {
        assertNotNull(PilgrimFonts.lato)
    }

    @Test
    fun `cormorant light TTF resource exists`() {
        assertNotNull(context.resources.getResourceName(R.font.cormorant_garamond_light))
    }

    @Test
    fun `cormorant regular TTF resource exists`() {
        assertNotNull(context.resources.getResourceName(R.font.cormorant_garamond_regular))
    }

    @Test
    fun `cormorant semibold TTF resource exists`() {
        assertNotNull(context.resources.getResourceName(R.font.cormorant_garamond_semibold))
    }

    @Test
    fun `lato regular TTF resource exists`() {
        assertNotNull(context.resources.getResourceName(R.font.lato_regular))
    }

    @Test
    fun `lato bold TTF resource exists`() {
        assertNotNull(context.resources.getResourceName(R.font.lato_bold))
    }
}
```

---

## Task 6 — Full CI gate

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest
```

Expected: 183 → 190 tests, build green, lint green, debug APK ~172 → ~173 MB.

---

## Task 7 — Commit + push

```
feat(theme): Stage 3-B — bundle Cormorant Garamond + Lato fonts

Stage 1-E's Type.kt had two placeholder FontFamily constants
(FontFamily.Serif / FontFamily.SansSerif) routed through the
entire PilgrimTypography surface, with a comment anticipating
this stage. Swap them for a new PilgrimFonts object backed by
five TTFs in res/font/.

Bundled weights: Cormorant Light (300), Regular (400), SemiBold
(600); Lato Regular (400), Bold (700) — matching every
FontWeight the typography actually requests, so Android doesn't
synthesize Light or SemiBold from Regular (which would smear
Cormorant's delicate strokes).

Fonts fetched from github.com/google/fonts main @ <commit>,
unmodified, SIL OFL 1.1. Attribution + full OFL text bundled at
app/src/main/assets/open_source_licenses.md (also catches up
the Stage 2-D whisper.cpp + ggml + ggml-tiny.en attributions
that were never captured).

APK delta: +~600 KB (five TTFs). Debug APK ~172 MB → ~173 MB.

Tests: 183 → 190 (+7 PilgrimFontsTest covering FontFamily
construction + each R.font.* resource resolution).
```
