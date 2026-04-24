# Stage 6-B implementation plan — Light Reading card UI

Branch: `stage-6b/light-reading-card`
Worktree: `/Users/rubberduck/GitHub/momentmaker/.worktrees/stage-6b-light-reading-card`
Spec: `docs/superpowers/specs/2026-04-21-stage-6b-light-reading-card-design.md`

Order: 1 → 2 → 3 → 4 → 5. Each task produces one commit; tests pass at every step.

---

## Task 1 — `@Immutable` annotations on Stage 6-A celestial types

**Modified files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/LightReading.kt`
- `.../MoonPhase.kt`
- `.../SunTimes.kt`
- `.../PlanetaryHour.kt`
- `.../Koan.kt`

Add `@Immutable` (from `androidx.compose.runtime.Immutable`) to each data class. These types are genuinely immutable; the annotation honestly reports that to Compose's stability inference. Without this, `Instant` fields inside `SunTimes` mark the entire `LightReading` Unstable, causing `WalkSummaryScreen` to skip-check-fail on every recomposition (same lesson as Stage 4-C `GoshuinSeal`).

Test: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "*.core.celestial.*"` passes.

Commit: `feat(celestial): Stage 6-B add @Immutable to LightReading aggregate`

---

## Task 2 — `LightReadingPresenter` + tests

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/LightReadingPresenter.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/LightReadingPresenterTest.kt`

`LightReadingPresenter` is an `internal object` with pure functions:

```kotlin
internal object LightReadingPresenter {
    /** Moon phase emoji — matches the 8 canonical phase names.
     *  Unknown names fall back to 🌕 (full-moon default). */
    fun phaseEmoji(phaseName: String): String

    /** "Waxing Gibbous · 78% lit" */
    fun moonLine(moon: MoonPhase): String

    /** "Hour of Venus · Friday" */
    fun planetaryHourLine(hour: PlanetaryHour): String

    /** "Sunrise 03:47 · Sunset 19:58" in the supplied zone. Null when
     *  sun is null or when both rise/set are null (polar). Renders only
     *  the non-null side if just one is null. */
    fun sunLine(sun: SunTimes?, zoneId: ZoneId): String?

    /** Muted classical color per Chaldean planet (see spec). */
    fun planetDotColor(planet: Planet): Color
}
```

Internals:
- Phase emoji via `when` on the 8 canonical names.
- Illumination: `(moon.illumination * 100).roundToInt()`.
- Day-of-week from day-ruler: `Sun→Sunday`, `Moon→Monday`, `Mars→Tuesday`, `Mercury→Wednesday`, `Jupiter→Thursday`, `Venus→Friday`, `Saturn→Saturday`.
- Sun formatter: `DateTimeFormatter.ofPattern("HH:mm").withZone(zoneId)`.
- Dot colors: hardcoded `Color(0xFF…)` literals per the spec.

Tests (pure JUnit, no Robolectric):
- All 8 phase names → correct emoji.
- Unknown phase name → 🌕.
- `moonLine("Full Moon", 1.0)` → `"Full Moon · 100% lit"`.
- `moonLine("Waxing Gibbous", 0.78)` → `"Waxing Gibbous · 78% lit"`.
- `moonLine("New Moon", 0.001)` → `"New Moon · 0% lit"`.
- All 7 `PlanetaryHour(hour, dayRuler)` combinations → correct day name.
- `sunLine(SunTimes(sunrise, sunset, noon), Paris)` with 2024-06-21 UTC Instants → `"Sunrise 05:47 · Sunset 21:58"` (CEST).
- `sunLine(null, …)` → null.
- `sunLine(SunTimes(null, null, noon), …)` → null.
- `sunLine(SunTimes(sunrise, null, noon), …)` → `"Sunrise 05:47"` (no sunset).
- `sunLine(SunTimes(null, sunset, noon), …)` → `"Sunset 19:58"` (no sunrise).
- `planetDotColor` returns 7 distinct, non-black colors.

Test: `./gradlew :app:testDebugUnitTest --tests "*LightReadingPresenterTest"` passes.

Commit: `feat(walk): Stage 6-B LightReadingPresenter + tests`

---

## Task 3 — `WalkLightReadingCard` composable + tests

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkLightReadingCard.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkLightReadingCardTest.kt`

Composable signature:

```kotlin
@Composable
fun WalkLightReadingCard(
    reading: LightReading,
    zoneId: ZoneId = ZoneId.systemDefault(),
    modifier: Modifier = Modifier,
)
```

Layout (per spec):

```kotlin
Card(
    modifier = modifier
        .fillMaxWidth()
        .pointerInput(reading) {
            detectTapGestures(onLongPress = {
                clipboard.setText(AnnotatedString(reading.koan.text))
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            })
        },
    colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
    shape = RoundedCornerShape(12.dp),
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            vertical = PilgrimSpacing.big,
            horizontal = PilgrimSpacing.normal,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        // Moon emoji (48sp)
        Text(
            text = LightReadingPresenter.phaseEmoji(reading.moon.name),
            fontSize = 48.sp,
        )

        // Koan
        Text(
            text = reading.koan.text,
            style = pilgrimType.body,
            color = pilgrimColors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        // Attribution (conditional)
        val attribution = reading.koan.attribution
        if (attribution != null) {
            Text(
                text = "— $attribution",
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(PilgrimSpacing.small))

        // Moon phase + illumination line
        Text(
            text = LightReadingPresenter.moonLine(reading.moon),
            style = pilgrimType.caption,
            color = pilgrimColors.stone,
            textAlign = TextAlign.Center,
        )

        // Planetary hour line with small dot
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = LightReadingPresenter.planetDotColor(
                            reading.planetaryHour.planet,
                        ),
                        shape = CircleShape,
                    ),
            )
            Text(
                text = LightReadingPresenter.planetaryHourLine(reading.planetaryHour),
                style = pilgrimType.caption,
                color = pilgrimColors.stone,
            )
        }

        // Sun line (conditional)
        val sunLine = LightReadingPresenter.sunLine(reading.sun, zoneId)
        if (sunLine != null) {
            Text(
                text = sunLine,
                style = pilgrimType.caption,
                color = pilgrimColors.stone,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(PilgrimSpacing.small))

        // Footer
        Text(
            text = "— a light reading",
            style = pilgrimType.caption,
            color = pilgrimColors.fog.copy(alpha = 0.6f),
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
        )
    }
}
```

Tests (Robolectric + `createComposeRule`):

```kotlin
private fun synthReading(
    moonName: String = "Waxing Gibbous",
    illumination: Double = 0.78,
    sun: SunTimes? = SunTimes(
        sunrise = Instant.parse("2024-06-21T03:47:00Z"),
        sunset = Instant.parse("2024-06-21T19:58:00Z"),
        solarNoon = Instant.parse("2024-06-21T11:52:00Z"),
    ),
    attribution: String? = "Zen",
): LightReading = LightReading(
    moon = MoonPhase(name = moonName, illumination = illumination, ageInDays = 10.0),
    sun = sun,
    planetaryHour = PlanetaryHour(planet = Planet.Venus, dayRuler = Planet.Venus),
    koan = Koan(text = "The moon reflects in ten thousand pools.", attribution = attribution),
)
```

Test cases:
- Full reading (with sun + attribution) renders koan text, moon line, planetary-hour line, sun line, attribution, and footer.
- `attribution = null` → attribution line absent.
- `sun = null` → sun line absent; other rows present.
- Polar (SunTimes with null sunrise + sunset) → sun line absent.
- Long koan (120 chars) renders without crash.

Test: `./gradlew :app:testDebugUnitTest --tests "*WalkLightReadingCardTest"` passes.

Commit: `feat(walk): Stage 6-B WalkLightReadingCard composable + tests`

---

## Task 4 — `WalkSummaryViewModel` extension

**Modified file:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt`

Changes:
1. Add `lightReading: LightReading? = null` field to `WalkSummary` data class with KDoc noting the null contract.
2. Import `org.walktalkmeditate.pilgrim.core.celestial.LightReading`.
3. In `buildState()`, after the existing summary computation:

```kotlin
val firstSample = samples.firstOrNull()
val firstLocation = firstSample?.let {
    LocationPoint(timestamp = it.timestamp, latitude = it.latitude, longitude = it.longitude)
}
val lightReading = runCatching {
    LightReading.from(
        walkId = walkId,
        startedAtEpochMs = walk.startTimestamp,
        location = firstLocation,
        zoneId = ZoneId.systemDefault(),
    )
}.onFailure {
    Log.w(TAG, "LightReading.from failed for walk $walkId", it)
}.getOrNull()
```

4. Add `lightReading = lightReading` to the `WalkSummary(...)` constructor call.

No new tests required — existing `WalkSummaryViewModelTest` (if any) should keep passing. If there's a test that constructs `WalkSummary` directly, it'll need the optional new param (default null).

Test: `./gradlew :app:testDebugUnitTest --tests "*WalkSummaryViewModelTest" :app:compileDebugKotlin` passes.

Commit: `feat(walk): Stage 6-B compute LightReading in WalkSummaryViewModel`

---

## Task 5 — `WalkSummaryScreen` integration

**Modified file:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt`

On the `WalkSummaryUiState.Loaded` branch, after `SummaryStats(summary = s.summary)`, render the card if available:

```kotlin
is WalkSummaryUiState.Loaded -> {
    SummaryMap(points = s.summary.routePoints)
    Spacer(Modifier.height(PilgrimSpacing.big))
    SummaryStats(summary = s.summary)

    // Stage 6-B: Light Reading card, rendered if the VM successfully
    // computed it. `runCatching` in the VM means a failure yields
    // null here and the card simply doesn't render.
    s.summary.lightReading?.let { reading ->
        Spacer(Modifier.height(PilgrimSpacing.big))
        WalkLightReadingCard(reading = reading)
    }

    if (recordings.isNotEmpty()) {
        Spacer(Modifier.height(PilgrimSpacing.big))
        VoiceRecordingsSection(
            // ...existing params...
        )
    }
}
```

The card renders between the stats + recordings — positions it as the contemplative payoff right after the quantitative stats.

Full verification:
```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Commit: `feat(walk): Stage 6-B render LightReading card on WalkSummary`

---

## Implementation notes

- Use `LocalClipboardManager.current` + `LocalHapticFeedback.current` for the long-press handlers.
- Moon emoji sizes must use `fontSize = 48.sp` via `TextStyle` (not via Modifier) — emojis scale with text size.
- `CircleShape` is in `androidx.compose.foundation.shape.CircleShape`.
- Do NOT wrap the entire card content in a vertical scroll — the parent screen already scrolls.
- No new Gradle dependencies.

## Post-implementation

- `/polish` loop until clean.
- Initial + final code-reviewer agents.
- CHECKPOINT 2 with PR.
- Device QA is optional for 6-B (the card is pure layout) but straightforward: install, open a finished walk's summary, verify the card renders, long-press the koan to verify clipboard + haptic.
