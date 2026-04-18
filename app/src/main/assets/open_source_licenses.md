# Open source licenses

Third-party assets and code bundled with Pilgrim for Android.

## Fonts

### Cormorant Garamond

- **Copyright:** © 2015 Catharsis Fonts (Christian Thalmann)
- **License:** SIL Open Font License, Version 1.1
- **Source:** https://github.com/google/fonts/tree/main/ofl/cormorantgaramond
- **Usage:** Bundled unmodified in `res/font/` as
  `cormorant_garamond_variable.ttf` (upright, variable wght axis)
  and `cormorant_garamond_italic_variable.ttf` (italic, variable
  wght axis).

### Lato

- **Copyright:** © 2010-2014 Łukasz Dziedzic (tyPoland)
- **License:** SIL Open Font License, Version 1.1
- **Source:** https://github.com/google/fonts/tree/main/ofl/lato
- **Usage:** Bundled unmodified in `res/font/` as `lato_regular.ttf`
  (400), `lato_bold.ttf` (700), and `lato_italic.ttf` (400 italic).

Both font families vendored at google/fonts commit
`47831f08ec6d6d7ad6b465f23dc9f9a890a2a04b`. See the KDoc block in
`ui/theme/PilgrimFonts.kt` for the pinned commit hash and re-vendor
procedure (Android's `res/font/` directory rejects non-TTF files, so
the doc lives next to the usage site instead of inside the resource
folder).

## Native libraries (on-device transcription)

### whisper.cpp

- **Copyright:** © 2023 Georgi Gerganov and contributors
- **License:** MIT
- **Source:** https://github.com/ggml-org/whisper.cpp (tag `v1.7.5`)
- **Usage:** Vendored subset under `app/src/main/cpp/whisper/` and built
  via the project's NDK + CMake config. Compiled into
  `libpilgrim-whisper.so` for on-device voice-note transcription.

### ggml

- **Copyright:** © 2023 Georgi Gerganov and contributors
- **License:** MIT
- **Source:** Same as whisper.cpp (ggml subtree at the same tag)
- **Usage:** Tensor library used by whisper.cpp.

### ggml-tiny.en Whisper model

- **Copyright:** © 2022 OpenAI; converted to ggml format by Georgi
  Gerganov and contributors.
- **License:** MIT
- **Source:** https://huggingface.co/ggerganov/whisper.cpp (file
  `ggml-tiny.en.bin`)
- **Usage:** Bundled at `app/src/main/assets/models/ggml-tiny.en.bin`
  (~75 MB). Loaded by the JNI wrapper on first transcription and
  copied into the app's filesDir at runtime.

## Proprietary / SDK dependencies

Runtime dependencies pulled via Gradle — see `gradle/libs.versions.toml`
for the full list. Notable:

- **Mapbox Maps SDK** — Mapbox Terms of Service (mapbox.com/legal/tos).
  Not open-source; used under their standard Android SDK terms.
- **AndroidX libraries** — Apache License 2.0.
- **Hilt / Dagger** — Apache License 2.0.
- **Kotlin / kotlinx-coroutines** — Apache License 2.0.
- **media3 (ExoPlayer)** — Apache License 2.0.

---

## SIL Open Font License, Version 1.1

Copyright (c) <dates above, as attributed to each font family>.

This Font Software is licensed under the SIL Open Font License,
Version 1.1. This license is copied below, and is also available with a
FAQ at: https://openfontlicense.org

-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded,
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
