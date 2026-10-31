---
title: "feat: Honor — a temple ahead stamps until five, iOS PR #91 port, Phase 22 (Android 2.1.0)"
type: feat
status: completed
date: 2026-10-29
origin: docs/parity/2026-10-28-stamp-closing-port.md
---

# A Temple Ahead Stamps Until Five Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. House pattern: one task at a time on one branch, an implementation agent, then a correctness review and a parity review, then fixes. Merge only on the owner's word.
>
> **Authority order:** (1) the parity spec `docs/parity/2026-10-28-stamp-closing-port.md`, pinned at iOS `e551b11`, with its "Owner decisions (2026-10-29)" section; (2) the pre-merge annex in `docs/parity/2026-10-02-honor-pilgrimage-stages-port.md`, lines 8100–9316, only where the spec's table says it still holds (A.7's strings in A.4.3's `%1$s` form; A.8's tests as the spec corrects them); (3) iOS at `e551b11`, read-only: `git -C ../pilgrim-ios show e551b11:<path>`. Each outranks this plan where they disagree; this plan outranks memory.
>
> **Unit numbering:** U1–U12 belong to Phase 22's stage-links plan (`docs/plans/2026-10-29-001-feat-stage-links-port-plan.md`, on its own branch `feat/stage-links`); this plan is U20–U29, so commits and reviews that cite "Phase 22 U23" stay unambiguous across both.
>
> **Path shorthand:** `P/` is `app/src/main/java/org/walktalkmeditate/pilgrim/`, `T/` is `app/src/test/java/org/walktalkmeditate/pilgrim/`, `R/` is `app/src/main/res/`. Android line numbers are at `0ee2872`; iOS paths are at `e551b11` in `../pilgrim-ios`.

**Goal:** On a Shikoku stage, in the last two hours before the stamp office shuts, the walk screen's caption line names the nearest numbered temple at least a kilometre ahead, once, with water's soft tap: "temple 10 stamps until 5 · 3.2 km".

**Architecture:** `route.json`'s `stampHours` is read leniently into `WayStampHours` and copied onto every stage Way when it is staged, and, for routes Android 2.0.0 installed, by a launch pass on the package manager's actor. In `:tracker` the moment tracker gains a temple watcher that runs before water, with three engine-time clocks (water's hour, the temples' ten minutes, a shared minute) persisted in Room schema 13 beside `STAMP` notice rows; `planEvents` writes the row and plans water's tap only when the walk's Way can caption it. The UI resolves the latest `STAMP` row against the loaded Way into `HonorCaption.Stamp` and draws it in the walker's own 12- or 24-hour clock.

**Tech Stack:** Kotlin, Jetpack Compose, Room 2 with KSP schema export, kotlinx.serialization JSON, coroutines with a `Mutex` actor, `android.icu` and `android.text.format.DateFormat`; tests on JUnit 4, Robolectric 4.16.1 (`ShadowSettings`), MockWebServer, kotlinx-coroutines-test.

**Spec:** `docs/parity/2026-10-28-stamp-closing-port.md` (iOS `e551b11`, PR #91's merge; it reads the same at the stage-links pin `3b78728`). Ships in Android 2.1.0 with stage links, at parity with iOS `main` at `3b78728` (owner decision 3).

## Global Constraints

- JDK 17 (asdf `temurin-17.0.18+8`). If `./gradlew` fails on the Java version, run `export PATH="$HOME/.asdf/shims:$PATH"` first.
- Work on branch `feat/temple-stamp-hours`, cut from `main` at `0ee2872e`, checked out in the worktree `/Users/rubberduck/GitHub/momentmaker/pilgrim-android-temple-stamps`; every command runs there, never in the main checkout. Never push, open a PR, run a workflow, or release without the owner's word.
- Every commit ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never `--no-verify`, never a bare `git stash`. Commit style `feat(honor):`, `test(honor):`, `docs(…):`, as the repo's history.
- One test class: `./gradlew :app:testDebugUnitTest --tests '<fully.qualified.Class>'`. The whole suite: `./gradlew testDebugUnitTest`. Lint: `./gradlew lintDebug`. Build: `./gradlew assembleDebug`. Release-variant flag checks: `./gradlew compileReleaseUnitTestKotlin testReleaseUnitTest --tests 'org.walktalkmeditate.pilgrim.core.flags.*'`. There is no ktlint or detekt task; `.editorconfig` is the format (120 columns, trailing commas, no wildcard imports, 4-space indents).
- SPDX header only on a new file: `// SPDX-License-Identifier: GPL-3.0-or-later`. No OutRun mention anywhere.
- The four tunings, verbatim: `NOTICE_GAP_SECONDS = 60.0`, `STAMP_NOTICE_MINUTES = 120`, `STAMP_MIN_AHEAD_METERS = 1000.0`, `STAMP_QUIET_SECONDS = 600.0`. `MARK_QUIET_SECONDS = 3600.0` and `MARK_AHEAD_METERS = 300.0` are unchanged. All seconds are engine seconds; only the office window reads the wall clock.
- The words, verbatim: `temple %1$s stamps until %2$s · %3$s`, all lowercase, U+00B7 with a space either side, its three arguments formatted before they arrive (the number by `Int.toString()`, the hour with `Locale.US`, the distance by `WayRelation.distance`). English only: `R/values/strings.xml`.
- Schema 13 (owner decision 1): `ALTER TABLE honor_sessions ADD COLUMN last_water_seconds REAL`, `ADD COLUMN last_stamp_seconds REAL`, then `UPDATE honor_sessions SET last_water_seconds = last_notice_seconds`; `last_notice_seconds` becomes iOS's shared gap clock. Schema 12's export shipped in 2.0.0 and is never edited. Schema 13 is frozen the moment any phone runs it.
- Golden traces (owner decision 2): none recorded. `app/src/test/resources/honor/golden/corpus/`, `expected/` and `capture/` stay byte for byte; `T/domain/honor/HonorGoldenTraceTest.kt` gains only the one `when` arm the new event needs to compile.
- Nothing under `P/data/honor/pilgrimage/` logs (`PilgrimageWayImporterTest`'s "nothing under data honor pilgrimage logs" enforces it).
- Disk work the launch does runs on IO under the package manager's actor (`onActor`), never on the main thread, where iOS does it.
- Do not edit `CLAUDE.md` (the stage-links plan's U1 does), do not touch `versionName` or `versionCode` (the release workflow sets both), and do not edit `CHANGELOG.md` (not asked).
- No platform-object builder is constructed here, so CLAUDE.md's Robolectric `.build()` rule isn't triggered.
- The stage-links branch shares no file with this one but `R/values/strings.xml` (its lines go after line 1287, this plan's after 1542). `PilgrimageRoute.stampHours` defaults to null so that branch's new `PilgrimageRoute(...)` in `PilgrimageRouteScrollTest` (its U10) compiles whichever branch merges first. Rebase onto `main` before review if it lands first.

## Review Focus

1. **A dataset release that reshapes `stampHours` (say, an array of windows) on a phone with saved maps:** the route must still read as installed, so the launch's tile sweep keeps its maps, and its stages simply stay silent. Pinned in U22 by `a launch whose installed route json reshapes its stamp hours keeps the route's saved maps`.
2. **A stage `way.json` that no longer decodes, or a disk that refuses a write, during the launch hand-down:** that stage is left exactly as it was, every other stage gets its hours, the launch goes on, and the next launch tries again. Pinned in U22 by iOS's refused-stage port and `a stage whose way json no longer loads is skipped, its bytes untouched, and the rest are stamped`.
3. **The UI process dying inside a temple caption's 20 s, or `:tracker` dying right after the `STAMP` row commits:** the rest of the 20 s shows and then nothing, never a replay; the temple never speaks or taps twice. Pinned in U26 by `after a UI restart a temple caption shows for what is left of its 20 s`, and in U24 by `a revival keeps the temple spoken and its clocks, so temple 10 never speaks twice`.
4. **A walker on the 24-hour clock, or one who flips the switch mid-walk:** "until 17" on a 24-hour phone, "until 5" on a 12-hour one, never "5 PM", read when the line is drawn. Pinned in U25 by `the walker's own 24-hour switch decides the hour` and in U26 by the two sheet semantics tests under `ShadowSettings.set24HourTimeFormat`.
5. **A phone whose zone isn't the temple's (travel, a manual zone):** the window follows the Way's zone when it names one Java reads, else the zone the engine was built with (Begin, or a `:tracker` revival). Pinned in U24 by the three engine office tests.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `P/domain/honor/Way.kt` | `WayStampHours`; `Way.stampHours`; `WayMoment.templeNumber` | U20 |
| `P/domain/honor/SwiftText.kt` | `leadingAsciiDigits()`, Swift's character-wise digit run | U20 |
| `P/data/honor/pilgrimage/PilgrimageModels.kt` | `RouteFile.stampHours` as raw JSON; `PilgrimageRoute.stampHours` | U21 |
| `P/data/honor/pilgrimage/PilgrimageWayImporter.kt` | lenient hours read, "HH:mm" parse, `way(…, stampHours)` | U21 |
| `P/data/honor/pilgrimage/PilgrimagePackageManager.kt` | `StagePlan.stampHours` (U21); `restampStageHours` (U22) | U21, U22 |
| `P/walk/honor/HonorFinalizer.kt` | the launch hand-down after the tiles reconcile | U22 |
| `P/domain/honor/HonorTuning.kt` | the four constants | U23, U24 |
| `P/domain/honor/HonorMomentTracker.kt` | three clocks (U23); `StampOffice`, `fudasho`, `stampAhead` (U24) | U23, U24 |
| `P/data/honor/HonorSessionEntity.kt`, `HonorDao.kt`, `P/data/PilgrimDatabase.kt`, `app/schemas/…/13.json` | schema 13 | U23 |
| `P/walk/honor/HonorSessionState.kt` | clocks to and from Room (U23); `STAMP` rows and `stampNotice` (U24) | U23, U24 |
| `P/domain/honor/HonorEngine.kt` | `StampAhead` event, the office from the Way, `fallbackZone` | U24 |
| `P/data/honor/HonorNoticeEntity.kt` | `HonorNoticeKind.STAMP` | U24 |
| `P/walk/honor/HonorSession.kt` | `planEvents`' `StampAhead` arm | U24 |
| `app/src/debug/kotlin/…/debug/honor/HonorDebugReceiver.kt` | HONOR_DUMP shows clocks, notices, hours | U24 |
| `P/ui/honor/WayMomentHeader.kt`, `R/values/strings.xml` | `WayStampNotice`; `honor_stamp_caption` | U25 |
| `P/ui/walk/HonorWalkViewModel.kt`, `P/ui/walk/WalkStatsSheet.kt` | `HonorCaption.Stamp`, the notice flow, drawing it | U26 |
| `app/src/test/resources/honor/golden/README.md` | one row: not recaptured for 2.1.0 | U27 |
| `app/src/main/play/release-notes/en-US/whatsnew.txt`, `default.txt` | 2.1.0 Play notes | U28 |
| `docs/qa/2026-10-29-temple-stamp-hours-qa.md` | the owner's device pass | U29 |

## Where the Spec and the Code Disagree, Ruled

- **iOS's `"stageCount": "2"` refusal can't be ported literally.** kotlinx reads a quoted number (A9, pinned by `PilgrimageWayImporterTest`'s "A9 - kotlinx reads a quoted number, where iOS refuses one"). Ruled by the spec's rule, not its example: U21 proves a required member of the wrong type is still refused with `"name": 5`, which both decoders refuse, and asserts the A9 reading of iOS's own case beside it.
- **The Play notes file.** The owner asked for `default.txt`. `production.yml` runs `scripts/release-notes.sh`, which regenerates `default.txt` from commit messages unless a curated `whatsnew.txt` exists, then copies `whatsnew.txt` over `default.txt` and deletes it (2.0.0 did exactly this, `30989b04` then `f59d98b4`). U28 writes the owner's text to both, so `default.txt` reads right now and survives the release.
- **The restamp's per-stage failure.** iOS prints it. Nothing in `P/data/honor/pilgrimage/` may log, so the catch carries a comment and no log, as the package's other `try?` ports do.
- **`templeNumber`'s digits.** The spec says a `'0'..'9'` range. Swift's `prefix` walks characters, so a digit carrying a combining mark ends the run; U20 reads the run by grapheme cluster (the file's existing `BreakIterator` pattern) and pins it.
- **The 12-hour test pattern.** iOS checks `"j".contains("a")`; annex A.4.3 asks for `a` outside quoted literals. The production path reads `DateFormat.is24HourFormat`, so the ICU form is test-only; U25 uses the annex's form.
- **Tests that treat `"STAMP"` as an unknown kind:** `T/data/ConvertersTest.kt:62-65` and `T/data/honor/HonorDaoTest.kt:185-193`. U24 moves both to `"SHRINE"`.
- **Migration tests that hardcode 12:** `T/data/PilgrimDatabaseMigrationTest.kt:1078-1081` and `:1159-1162`. U23 moves them to `LATEST_VERSION`.
- **The spec's golden checklist (item 6)** is void by owner decision 2; the capture harness stays at `7c200bf`, and its README says why (U27).
- **"The restamp runs after the reconcile."** The reconcile only posts to the tiles manager's thread, so the order is in the code (iOS's order) and U22's test pins the hand-down inside the package step, before the expiry sweep.

---

## Task U20: A Way carries its stamp hours and knows its temples

**Files:**
- Modify: `P/domain/honor/Way.kt:112-152` (WayMoment), `:187-191` (after `WayStageHours`), `:267-284` (Way)
- Modify: `P/domain/honor/SwiftText.kt:65` (after `prefixCharacters`, which runs 54-65)
- Test: `T/domain/honor/WayMomentTest.kt`, `T/domain/honor/WayCodecTest.kt:158-169`
- Commit with this task: `docs/parity/2026-10-28-stamp-closing-port.md` and this plan

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `@Serializable data class WayStampHours(val opensMinutes: Int, val closesMinutes: Int)` in `org.walktalkmeditate.pilgrim.domain.honor`.
  - `Way(..., val stampHours: WayStampHours? = null)`, the last constructor parameter.
  - `val WayMoment.templeNumber: Int?` (computed, never serialized).
  - `internal fun String.leadingAsciiDigits(): String` in `SwiftText.kt`.

- [ ] **Step 1: Check the worktree**

```bash
cd /Users/rubberduck/GitHub/momentmaker/pilgrim-android-temple-stamps
git branch --show-current   # feat/temple-stamp-hours (the worktree already has it, off main at 0ee2872e)
```

- [ ] **Step 2: Write the failing tests**

Append to `T/domain/honor/WayMomentTest.kt`, before `private fun moment(`:

```kotlin
    // iOS PR #91: which moments are stamp temples (`Way.swift:108-115@e551b11`, annex A.3.3)

    private fun place(text: String?) = WayMoment(
        id = "wp",
        frac = 0.5,
        at = null,
        kind = WayMomentKind.Waypoint(label = "Ryozen-ji", icon = "seal"),
        text = text,
    )

    /** iOS `testTempleZeroIsNotANumberedTemple` (`HonorWayRenderingTests.swift:175-187@e551b11`). */
    @Test
    fun testTempleZeroIsNotANumberedTemple() {
        assertNull(place("Temple 0 · Koyasan Shingon · stamp available (¥500)").templeNumber)
        assertNull("zero however it is padded", place("Temple 0000 · Koyasan Shingon").templeNumber)
        assertEquals(1, place("Temple 1 · Koyasan Shingon · stamp available (¥500)").templeNumber)
        assertEquals(88, place("Temple 88 · Shingon").templeNumber)
    }

    /** Annex A.3.3's probed outcomes, which hold at `e551b11` but for Temple 0. */
    @Test
    fun `a temple number is one to four ASCII digits after the exact prefix, and only the digits are read`() {
        assertEquals(1, place("Temple 01 · x").templeNumber)
        assertEquals(10, place("Temple 10· x").templeNumber)
        assertEquals(1234, place("Temple 1234 · x").templeNumber)
        assertNull(place("Temple 12345 · x").templeNumber)
        assertNull("the prefix is case-sensitive", place("temple 10 · x").templeNumber)
        assertNull("Arabic-Indic digits are not ASCII", place("Temple ١٠ · x").templeNumber)
        assertNull("a seal that is not a numbered temple", place("Pilgrim office · 172 m · stamp available").templeNumber)
        assertNull(place(null).templeNumber)
    }

    /** Swift reads characters: a digit carrying a combining mark is one character and not ASCII, so the run ends before it. */
    @Test
    fun `a digit with a combining mark ends the number, as Swift's characters do`() {
        assertNull(place("Temple 1\u0301 · x").templeNumber)
        assertEquals(1, place("Temple 10\u0301 · x").templeNumber)
    }
```

In `T/domain/honor/WayCodecTest.kt`, change line 162's list to include the new key:

```kotlin
        val omitted = listOf("spans", "marks", "stage", "stampHours", "tzIdentifier", "expires", "weather")
```

and append, before `private fun way(source: WaySource`:

```kotlin
    /** iOS `Way.stampHours` (`Way.swift:249@e551b11`): last, optional, sorted on the wire, and absent from older files. */
    @Test
    fun `a stage's stamp hours round-trip in sorted keys, and a way json written before them decodes with none`() {
        val way = way(source = WaySource.Pilgrimage(routeId = "shikoku-88-awa", stageIndex = 0))
            .copy(stampHours = WayStampHours(opensMinutes = 480, closesMinutes = 1020))

        val json = WayJson.encode(way)

        val written = ",\"stampHours\":{\"closesMinutes\":1020,\"opensMinutes\":480}"
        assertTrue(json, json.contains(written))
        assertEquals(way, WayJson.decode(json))
        assertNull(WayJson.decode(json.replace(written, "")).stampHours)
    }
```

- [ ] **Step 3: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.domain.honor.WayMomentTest' --tests 'org.walktalkmeditate.pilgrim.domain.honor.WayCodecTest'`
Expected: FAIL to compile, "Unresolved reference 'templeNumber'" and "Unresolved reference 'WayStampHours'".

- [ ] **Step 4: Implement**

In `P/domain/honor/SwiftText.kt`, after `prefixCharacters` (line 62):

```kotlin
/**
 * Swift's `prefix { $0.isASCII && $0.isNumber }`: the leading characters,
 * whole grapheme clusters, that are each one ASCII digit. A digit carrying
 * a combining mark is one Swift character and not ASCII, so the run stops
 * before it, where a `'0'..'9'` test on UTF-16 units would read on.
 */
internal fun String.leadingAsciiDigits(): String {
    val characters = BreakIterator.getCharacterInstance(Locale.ROOT)
    characters.setText(this)
    var start = 0
    var end = characters.next()
    while (end != BreakIterator.DONE && end - start == 1 && this[start] in '0'..'9') {
        start = end
        end = characters.next()
    }
    return substring(0, start)
}
```

In `P/domain/honor/Way.kt`, after `WayMoment.media` (line 123), inside `WayMoment`:

```kotlin
    /**
     * The fudasho's number when this place is one of Shikoku's numbered
     * stamp temples, null otherwise (iOS `templeNumber`,
     * `Way.swift:95-113@e551b11`): the test for "is this a temple" and the
     * word the caption uses, so the notice never names a temple the rule
     * didn't recognise. Read from [text], which the dataset writes as
     * "Temple 10 · …", never from the `seal` icon, which 48 places that
     * aren't temples also carry. One to four ASCII digits after the exact
     * prefix, read as Swift reads characters, and above zero: the temples
     * are numbered from one, so a zero is a typo and names nothing.
     */
    val templeNumber: Int?
        get() {
            val text = text ?: return null
            if (!text.startsWith(TEMPLE_PREFIX)) return null
            val digits = text.substring(TEMPLE_PREFIX.length).leadingAsciiDigits()
            if (digits.length !in 1..4) return null
            return digits.toInt().takeIf { it > 0 }
        }
```

and in its `companion object`, after `SENTENCE_ENDS` (line 128):

```kotlin
        private const val TEMPLE_PREFIX = "Temple "
```

After `WayStageHours` (line 191):

```kotlin
/**
 * iOS `WayStampHours` (`Way.swift:151-163@e551b11`): when a route's stamp
 * office opens and shuts, in minutes since midnight, parsed once at the
 * import so the rule read on every fix is arithmetic. Only
 * [closesMinutes] is read; [opensMinutes] is kept as iOS keeps it.
 */
@Serializable
data class WayStampHours(
    val opensMinutes: Int,
    val closesMinutes: Int,
)
```

In `Way`, after `val stage: WayStage? = null,` (line 283):

```kotlin
    /**
     * The route's stamp hours, copied onto each of its stages so the walk
     * reads one Way (`Way.swift:245-249@e551b11`). Optional and last: a
     * `way.json` written before them, as Android 2.0.0 staged every stage,
     * decodes with none.
     */
    val stampHours: WayStampHours? = null,
```

- [ ] **Step 5: Run them to see them pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.domain.honor.WayMomentTest' --tests 'org.walktalkmeditate.pilgrim.domain.honor.WayCodecTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/Way.kt \
  app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/SwiftText.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/domain/honor/WayMomentTest.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/domain/honor/WayCodecTest.kt \
  docs/parity/2026-10-28-stamp-closing-port.md \
  docs/plans/2026-10-29-002-feat-temple-stamp-hours-port-plan.md
git commit -m "feat(honor): a Way carries its route's stamp hours and knows its temples

Phase 22 U20 (iOS #91, e551b11): WayStampHours, Way.stampHours last and
optional, and WayMoment.templeNumber read from text in Swift's characters,
with Temple 0 naming nothing. Commits the parity spec and this plan.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task U21: Stamp hours from `route.json` onto every staged stage

**Files:**
- Modify: `P/data/honor/pilgrimage/PilgrimageModels.kt:49-65` (`PilgrimageRoute`), `:127-138` (wire comment), `:223-235` (`RouteFile`)
- Modify: `P/data/honor/pilgrimage/PilgrimageWayImporter.kt:72-111` (`way`), `:113-154` (`route`), after `:227` (new private functions)
- Modify: `P/data/honor/pilgrimage/PilgrimagePackageManager.kt:293`, `:338-341`, `:545-546`
- Test: `T/data/honor/pilgrimage/PilgrimageWayImporterTest.kt`, `T/data/honor/pilgrimage/PilgrimagePackageManagerTest.kt`

**Interfaces:**
- Consumes: U20's `WayStampHours`, `Way.stampHours`.
- Produces:
  - `PilgrimageRoute(..., val summary: String?, val stampHours: WayStampHours? = null, val stages: List<PilgrimageRouteStage>)`: the importer always passes it; the default keeps every test construction, the stage-links branch's included, compiling.
  - `PilgrimageWayImporter.way(from: ByteArray, routeId: String, stageIndex: Int, stampHours: WayStampHours? = null): Way`.
  - `internal data class RouteFile(..., val stampHours: JsonElement? = null)`.

- [ ] **Step 1: Write the failing tests**

In `T/data/honor/pilgrimage/PilgrimageWayImporterTest.kt`, add `import org.junit.Assert.assertNotNull` and `import org.walktalkmeditate.pilgrim.domain.honor.WayStampHours`.

At the end of `decodes the route file` (after line 284):

```kotlin
        assertNull("the Camino declares no stamp office hours", route.stampHours)
```

Replace the test at lines 865-880 with:

```kotlin
    @Test
    fun `a stage file's stampHours and schemaVersion are ignored, and a route file's stampHours is read`() {
        val stampHours = buildJsonObject {
            put("opens", "08:00")
            put("closes", "17:00")
        }
        val stageFile = stageTree()
            .edited(listOf("schemaVersion"), JsonPrimitive("1.0.0"))
            .edited(listOf("stampHours"), stampHours)
        val routeFile = routeTree()
            .edited(listOf("schemaVersion"), JsonPrimitive("1.0.0"))
            .edited(listOf("stampHours"), stampHours)

        assertEquals(stage00(), stage(stageFile.toString()))
        assertEquals(
            route(fixtureText("route.json")).copy(stampHours = WayStampHours(opensMinutes = 480, closesMinutes = 1020)),
            route(routeFile.toString()),
        )
    }
```

Add a section before `// ---- Helpers` (line 1068):

```kotlin
    // ---- iOS PR #91: stamp hours (`PilgrimageWayImporterTests.swift:294-410@e551b11`), names kept ----

    /** Shikoku's `route.json` writes `"08:00"`; the phone keeps minutes. */
    @Test
    fun testARoutesStampHoursAreReadAsMinutesSinceMidnight() {
        val route = route(routeFileWith(""""stampHours": { "opens": "08:00", "closes": "17:00" },"""))

        assertEquals(WayStampHours(opensMinutes = 480, closesMinutes = 1020), route.stampHours)
    }

    /**
     * A walker told the wrong closing time is worse off than one told
     * nothing, so an hour this build cannot read is dropped whole, and the
     * route stays walkable regardless.
     */
    @Test
    fun testAnUnreadableStampHourLeavesTheRouteWalkableAndSilent() {
        val unreadable = listOf(
            """"stampHours": { "opens": "08:00", "closes": "5pm" },""",
            """"stampHours": { "opens": "8:00", "closes": "17:00" },""",
            """"stampHours": { "opens": "08:00", "closes": "24:00" },""",
            """"stampHours": { "opens": "08:00", "closes": "17:60" },""",
            """"stampHours": { "opens": "08:00", "closes": "" },""",
        )
        for (block in unreadable) {
            val route = route(routeFileWith(block))
            assertNull(block, route.stampHours)
            assertEquals("the route is still walkable: $block", 2, route.stageCount)
        }
    }

    /**
     * The same promise for a value that isn't even the right shape, a later
     * dataset writing a season's two windows as an array, say: it costs the
     * notice and never the route, nor `installed()`, whose null would let the
     * launch's tile sweep take every saved map.
     */
    @Test
    fun testAMisShapedStampHoursLeavesTheRouteWalkableAndSilent() {
        val misShaped = listOf(
            """"stampHours": { "opens": "08:00" },""",
            """"stampHours": { "opens": 8, "closes": 17 },""",
            """"stampHours": "08:00-17:00",""",
            """"stampHours": { "opens": null, "closes": "17:00" },""",
            """"stampHours": null,""",
            """"stampHours": [ { "opens": "08:00", "closes": "12:00" }, { "opens": "13:00", "closes": "17:00" } ],""",
        )
        for (block in misShaped) {
            val route = route(routeFileWith(block))
            assertNull(block, route.stampHours)
            assertEquals("the route is still walkable: $block", 2, route.stageCount)
            assertEquals(block, 2, route.stages.size)
        }
    }

    /**
     * Leniency is for `stampHours` alone: a route file whose required
     * members are mis-shaped is still refused whole. iOS's own case,
     * `"stageCount": "2"`, is one kotlinx reads as 2 (A9, pinned above for a
     * stage's count), so the refusal is shown with a mis-shape both decoders
     * refuse, and iOS's case beside it as the divergence it is.
     */
    @Test
    fun testARequiredMemberMisShapedIsStillNotWalkable() {
        val base = fixtureText("route.json")
        val numberForName = base.replace("\"name\": \"Camino de Santiago (Francés)\"", "\"name\": 5")
        assertNotEquals(base, numberForName)
        assertNotWalkable("a number for the route's name") { route(numberForName) }

        val quotedCount = base.replace("\"stageCount\": 2", "\"stageCount\": \"2\"")
        assertNotEquals(base, quotedCount)
        assertEquals("A9: kotlinx reads a quoted number, where iOS refuses one", 2, route(quotedCount).stageCount)
    }

    /** The two files come down separately and only the package manager holds both, so the hours ride each stage Way. */
    @Test
    fun testAStageCarriesItsRoutesStampHoursAndSurvivesTheStoresEncoding() {
        val hours = WayStampHours(opensMinutes = 480, closesMinutes = 1020)
        val way = PilgrimageWayImporter.way(
            from = fixture("stage-00.json"),
            routeId = "camino-frances",
            stageIndex = 0,
            stampHours = hours,
        )

        assertEquals(hours, way.stampHours)
        assertEquals(way, WayJson.decode(WayJson.encode(way)))
    }

    /** Every route but Shikoku's, and every `way.json` Android 2.0.0 staged. */
    @Test
    fun testAStageWithoutStampHoursCarriesNoneAndAnOlderWayJsonStillDecodes() {
        assertNull("a route that declares no hours hands its stages none", stage00().stampHours)

        val stamped = PilgrimageWayImporter.way(
            from = fixture("stage-00.json"),
            routeId = "camino-frances",
            stageIndex = 0,
            stampHours = WayStampHours(opensMinutes = 480, closesMinutes = 1020),
        )
        val tree = Json.parseToJsonElement(WayJson.encode(stamped)).jsonObject
        assertNotNull("written when the route has them", tree["stampHours"])

        val older = WayJson.decode(JsonObject(tree - "stampHours").toString())
        assertNull("a way.json written before the field existed still decodes", older.stampHours)
    }

    /** Swift's `Int(_:)` reads a sign, and only ASCII digits (annex A.1.2, probed); Kotlin's own parse would take both further. */
    @Test
    fun `an hour reads a sign as Swift does, and refuses digits that aren't ASCII`() {
        val plus = route(routeFileWith(""""stampHours": { "opens": "+8:00", "closes": "17:00" },"""))
        val minusZero = route(routeFileWith(""""stampHours": { "opens": "-0:00", "closes": "17:00" },"""))
        val arabicIndic = route(routeFileWith(""""stampHours": { "opens": "٠٨:٠٠", "closes": "17:00" },"""))

        assertEquals(480, plus.stampHours?.opensMinutes)
        assertEquals(0, minusZero.stampHours?.opensMinutes)
        assertNull(arabicIndic.stampHours)
    }
```

In the helpers (after `routeTree()`, line 1085):

```kotlin
    /** iOS `routeFile(stampHours:)`: the fixture route with [block] spliced in where the dataset writes it, before `"cover"`. */
    private fun routeFileWith(block: String): String {
        val base = fixtureText("route.json")
        val json = base.replace("\"cover\": \"cover.jpg\",", "$block\n  \"cover\": \"cover.jpg\",")
        assertNotEquals(base, json)
        return json
    }
```

In `T/data/honor/pilgrimage/PilgrimagePackageManagerTest.kt`, add imports `kotlinx.serialization.json.buildJsonObject`, `kotlinx.serialization.json.put` and `org.walktalkmeditate.pilgrim.domain.honor.WayStampHours`. At the end of `download installs every stage and records the release` (after line 119):

```kotlin
        assertNull("a route that states no hours stages none", h.wayStore.load("pilgrimage:camino-frances:0")?.stampHours)
```

and after that test:

```kotlin
    /** The hours are `route.json`'s, handed to every stage as it is staged (annex A.2, A.11 item 6); iOS has no such test. */
    @Test
    fun `a route with stamp hours hands them to every stage it stages`() {
        val hours = buildJsonObject {
            put("opens", "08:00")
            put("closes", "17:00")
        }
        h.stub("route.json", fixtureJson("route.json").with("stampHours", hours).bytes())

        h.makeManager().download(h.entry, RELEASE).awaitBlocking()

        for (index in 0 until h.entry.stageCount) {
            assertEquals(
                "stage $index",
                WayStampHours(opensMinutes = 480, closesMinutes = 1020),
                h.wayStore.load(WayStore.stageWayId(ROUTE_ID, index))?.stampHours,
            )
        }
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageWayImporterTest' --tests 'org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManagerTest'`
Expected: FAIL to compile, "No parameter with name 'stampHours' found" and "Unresolved reference 'stampHours'".

- [ ] **Step 3: Implement the wire and the route**

In `P/data/honor/pilgrimage/PilgrimageModels.kt`, add `import kotlinx.serialization.json.JsonElement` and `import org.walktalkmeditate.pilgrim.domain.honor.WayStampHours`. In `PilgrimageRoute`, between `summary` (line 63) and `stages`:

```kotlin
    /**
     * Only a route whose dataset declares them, Shikoku's four; null
     * everywhere else, and for a shape this build can't read
     * (`PilgrimageWayImporter.swift:47-49@e551b11`). The importer always
     * sets it; the default is for the tests that build a route by hand.
     */
    val stampHours: WayStampHours? = null,
```

Replace the comment's lines 132-135 so they read:

```kotlin
 * `null` fails the whole decode, as Swift's synthesized `Decodable` does;
 * unknown keys (`schemaVersion`, `cover`, and a stage file's `stampHours`)
 * are ignored. A route file's `stampHours` is the one lenient member (iOS
 * `RouteFile.init(from:)`, `PilgrimageWayImporter.swift:178-197@e551b11`).
 * Swift's `Int` is 64-bit, so every
 * integer is a [Long], narrowed only once its bound has passed (P1 §4.3, A7).
```

In `RouteFile`, after `val stages: List<Stage>,` (line 234):

```kotlin
    /**
     * Any JSON at all: iOS reads it with `try?`, so a value of a shape this
     * build can't read costs the hours, never the route, and never
     * `installed()`. [PilgrimageWayImporter.route] makes the hours of it.
     */
    val stampHours: JsonElement? = null,
```

- [ ] **Step 4: Implement the importer**

In `P/data/honor/pilgrimage/PilgrimageWayImporter.kt`, add imports `kotlinx.serialization.json.JsonElement`, `kotlinx.serialization.json.JsonObject`, `kotlinx.serialization.json.JsonPrimitive` and `org.walktalkmeditate.pilgrim.domain.honor.WayStampHours`.

Change `way`'s doc and signature (lines 72-80) to:

```kotlin
    /**
     * iOS `way(from:routeId:stageIndex:stampHours:)` (`PilgrimageWayImporter.swift:202-241@e551b11`),
     * its checks in iOS's order (P1 §5.1): the request before the bytes,
     * the length cap before the decode, the file's identity against the
     * request, every bound, the departure date, then the line's length.
     * [stampHours] is the route's, which the package manager hands down as
     * it stages each stage; a stage read on its own carries none.
     *
     * @throws PilgrimageException [PilgrimageError.NOT_WALKABLE], for every refusal.
     */
    fun way(from: ByteArray, routeId: String, stageIndex: Int, stampHours: WayStampHours? = null): Way {
```

and its `Way(...)` ends:

```kotlin
            marks = marks(file.marks),
            stage = stage(file.stage),
            stampHours = stampHours,
        )
```

In `route(from:)`, between `summary = …` (line 142) and `stages = …`:

```kotlin
            stampHours = stampHours(file.stampHours),
```

After `isSaneHours` (line 227):

```kotlin
    /**
     * iOS's `try?` read of `RouteFile.StampHours` and `stampHours(_:)`
     * (`PilgrimageWayImporter.swift:158-161,196,388-392@e551b11`): an object
     * whose `opens` and `closes` are both JSON strings, each "HH:mm", or no
     * hours at all. Any other shape (a missing or null member, numbers, a
     * bare string, `null`, an array of windows) is no hours, and the route
     * still decodes; a pair either half of which won't read is dropped whole.
     */
    private fun stampHours(raw: JsonElement?): WayStampHours? {
        val members = raw as? JsonObject ?: return null
        val opens = members["opens"]?.jsonString()?.let(::minutesSinceMidnight) ?: return null
        val closes = members["closes"]?.jsonString()?.let(::minutesSinceMidnight) ?: return null
        return WayStampHours(opensMinutes = opens, closesMinutes = closes)
    }

    /** A JSON string's text; null for a number, a boolean, `null`, an object or an array. */
    private fun JsonElement.jsonString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * iOS `minutesSinceMidnight` (`PilgrimageWayImporter.swift:394-400@e551b11`,
     * annex A.1.2): two parts on `:`, empty ones kept, each exactly two
     * characters that Swift's `Int(_:)` reads, so a sign is allowed ("+8" is
     * 8) and only ASCII digits are, which a bare `toIntOrNull` wouldn't hold
     * to; then 0–23 and 0–59.
     */
    private fun minutesSinceMidnight(clock: String): Int? {
        val parts = clock.split(':')
        if (parts.size != 2 || parts.any { it.length != 2 || !CLOCK_PART.matches(it) }) return null
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }
```

and beside `LANGUAGE_CODE` (line 65):

```kotlin
    private val CLOCK_PART = Regex("[+-]?[0-9]{1,2}")
```

- [ ] **Step 5: Thread the hours through the download**

In `P/data/honor/pilgrimage/PilgrimagePackageManager.kt`, add `import org.walktalkmeditate.pilgrim.domain.honor.WayStampHours`. Replace `StagePlan` (lines 545-546):

```kotlin
    /**
     * [expected] is `route.json`'s row at this index, safe by position: the rows are exactly `0 until count`.
     * [stampHours] is `route.json`'s, copied onto the stage Way here, the one place that holds both files
     * (`PilgrimagePackageManager.swift:363-366@e551b11`).
     */
    private class StagePlan(
        val routeId: String,
        val url: HttpUrl,
        val stageCount: Int,
        val expected: PilgrimageRouteStage,
        val stampHours: WayStampHours?,
    )
```

Line 293 becomes:

```kotlin
                val plan = StagePlan(entry.id, stageUrl, fetched.route.stageCount, fetched.route.stages[index], fetched.route.stampHours)
```

and line 341 becomes:

```kotlin
        val way = PilgrimageWayImporter.way(from = data, routeId = plan.routeId, stageIndex = index, stampHours = plan.stampHours)
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.data.honor.pilgrimage.*' --tests 'org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.*' --tests 'org.walktalkmeditate.pilgrim.ui.settings.data.*'`
Expected: PASS, "nothing under data honor pilgrimage logs" included.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/pilgrimage/ app/src/test/java/org/walktalkmeditate/pilgrim/data/honor/pilgrimage/
git commit -m "feat(honor): a route's stamp hours reach every stage it stages

Phase 22 U21 (iOS #91, e551b11): route.json's stampHours read leniently, so
a shape this build can't read costs the hours and never the route; strict
HH:mm minutes; StagePlan hands them to each stage. iOS's quoted-stageCount
refusal is A9 on Android and is pinned as such.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task U22: The launch hands the hours down to routes 2.0.0 installed

**Files:**
- Modify: `P/data/honor/pilgrimage/PilgrimagePackageManager.kt:220-235` (doc of `runAtLaunch`, new `restampStageHours` after it)
- Modify: `P/walk/honor/HonorFinalizer.kt:263-278`
- Test: `T/data/honor/pilgrimage/PilgrimagePackageManagerLifecycleTest.kt` (new section before `private companion object`, line 381), `T/walk/honor/HonorFinalizerTest.kt` (new tests before `launchFinalizer`, line 930)

**Interfaces:**
- Consumes: U21's `PilgrimageRoute.stampHours`; U20's `Way.stampHours`; the existing `saveStage: (id: String, wayJson: String) -> Unit` seam, `onActor`, `isDownloading`.
- Produces: `suspend fun PilgrimagePackageManager.restampStageHours(installed: PilgrimagePackageManager.Installed)`, called by `HonorFinalizer.launchPilgrimage`.

- [ ] **Step 1: Write the failing lifecycle tests**

In `T/data/honor/pilgrimage/PilgrimagePackageManagerLifecycleTest.kt` add imports `java.io.IOException`, `java.util.concurrent.atomic.AtomicInteger`, `kotlinx.serialization.json.Json`, `kotlinx.serialization.json.JsonArray`, `kotlinx.serialization.json.JsonElement`, `kotlinx.serialization.json.JsonObject`, `kotlinx.serialization.json.buildJsonObject`, `kotlinx.serialization.json.jsonObject`, `kotlinx.serialization.json.put` and `org.walktalkmeditate.pilgrim.domain.honor.WayStampHours`. Before `private companion object` (line 381):

```kotlin
    // ---- iOS PR #91: a route file this build only partly reads, and the launch hand-down ----
    // (`PilgrimagePackageManagerTests+Lifecycle.swift:158-316@e551b11`, names kept)

    private val stageZeroId = WayStore.stageWayId(ROUTE_ID, 0)
    private val stageOneId = WayStore.stageWayId(ROUTE_ID, 1)
    private val shikokuMinutes = WayStampHours(opensMinutes = 480, closesMinutes = 1020)

    /** What open-pilgrimages v1.12.0 writes on every Shikoku `route.json`. */
    private val shikokuHours = buildJsonObject {
        put("opens", "08:00")
        put("closes", "17:00")
    }

    /** iOS `rewriteInstalledRouteFile(stampHours:)`: the installed `route.json` with its `stampHours` replaced, as a dataset release would put it there. */
    private fun rewriteInstalledRouteFile(stampHours: JsonElement) {
        val file = requireNotNull(h.wayStore.routeFile(ROUTE_ID))
        val route = Json.parseToJsonElement(file.readText()).jsonObject
        file.writeText(JsonObject(route + ("stampHours" to stampHours)).toString())
    }

    /** iOS `countingSaves(on:)`: every write the manager makes to a stage Way, counted on its way to this test's store. */
    private fun countingSaves(manager: PilgrimagePackageManager): AtomicInteger {
        val saves = AtomicInteger()
        manager.saveStage = { id, wayJson ->
            saves.incrementAndGet()
            h.wayStore.saveEncoded(id, wayJson)
        }
        return saves
    }

    private fun PilgrimagePackageManager.restamp(installed: PilgrimagePackageManager.Installed) =
        runBlocking { restampStageHours(installed) }

    /** `installed()` reads `route.json` through the download's decode; a shape it can't read must not make the route vanish. */
    @Test
    fun testAnInstalledRouteWhoseStampHoursAreMisShapedStillReadsAsInstalled() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        val windows = listOf("08:00" to "12:00", "13:00" to "17:00").map { (opens, closes) ->
            buildJsonObject {
                put("opens", opens)
                put("closes", closes)
            }
        }
        rewriteInstalledRouteFile(JsonArray(windows))

        val installed = requireNotNull(manager.installedBlocking()) { "still on the phone" }

        assertEquals(ROUTE_ID, installed.routeId)
        assertEquals(2, installed.route.stageCount)
        assertNull("a shape this build cannot read states no hours", installed.route.stampHours)
    }

    /**
     * A Shikoku leg downloaded under 2.0.0: `route.json` holds the hours,
     * its stages don't, and an Update compares releases only. The pass
     * hands them down and touches nothing else a walker has made of the stage.
     */
    @Test
    fun testAStageInstalledWithoutItsRoutesStampHoursLearnsThemAtLaunch() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        rewriteInstalledRouteFile(shikokuHours)
        val walk = UUID.randomUUID().toString()
        h.wayStore.link(walk, stageZeroId, arrival = null)
        h.wayStore.setReply(stageZeroId, originN = HonorPersistence.STAGE_REFLECTION_ORIGIN, relativePath = "Recordings/reply.m4a")
        val acceptedAt = h.wayStore.acceptedAt(stageZeroId)
        val before = requireNotNull(h.wayStore.load(stageZeroId))
        assertNull("the state a 2.0.0 install leaves", before.stampHours)

        manager.restamp(requireNotNull(manager.installedBlocking()))

        val stamped = requireNotNull(h.wayStore.load(stageZeroId))
        assertEquals(shikokuMinutes, stamped.stampHours)
        assertEquals(shikokuMinutes, h.wayStore.load(stageOneId)?.stampHours)
        assertEquals("nothing but the hours is rewritten", before, stamped.copy(stampHours = null))
        assertEquals(
            "a saved map region still matches its stage",
            PilgrimageTilesCorridor.stage(before).corridorHash,
            PilgrimageTilesCorridor.stage(stamped).corridorHash,
        )
        assertEquals(acceptedAt, h.wayStore.acceptedAt(stageZeroId))
        assertEquals("Recordings/reply.m4a", h.wayStore.replies(stageZeroId)[HonorPersistence.STAGE_REFLECTION_ORIGIN])
        assertEquals(stageZeroId, h.wayStore.wayLink(walk)?.wayId)
        assertEquals("the package itself is left as it was", RELEASE, manager.installedBlocking()?.release)
    }

    /** Compare, then save: every launch runs the pass, and only the first after the update has anything to write. */
    @Test
    fun testASecondLaunchPassWritesNothing() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        rewriteInstalledRouteFile(shikokuHours)
        val saves = countingSaves(manager)

        manager.restamp(requireNotNull(manager.installedBlocking()))
        assertEquals("both stages lacked them", 2, saves.get())

        manager.restamp(requireNotNull(manager.installedBlocking()))
        assertEquals("the second pass found nothing to change", 2, saves.get())
    }

    @Test
    fun testARouteThatStatesNoStampHoursIsLeftAlone() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        val saves = countingSaves(manager)

        manager.restamp(requireNotNull(manager.installedBlocking()))

        assertEquals(0, saves.get())
        assertNull(h.wayStore.load(stageZeroId)?.stampHours)
    }

    /** A launch pass never takes the launch down: a stage the disk refuses stays silent until the next launch, and the rest learn their hours. */
    @Test
    fun testAStageThatCannotBeWrittenLeavesTheRestStamped() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        rewriteInstalledRouteFile(shikokuHours)
        manager.saveStage = { id, wayJson ->
            if (id == stageZeroId) throw IOException("No space left on device")
            h.wayStore.saveEncoded(id, wayJson)
        }

        manager.restamp(requireNotNull(manager.installedBlocking()))

        assertNull("left as it was", h.wayStore.load(stageZeroId)?.stampHours)
        assertNotNull(h.wayStore.load(stageOneId)?.stampHours)
    }

    /**
     * An Update's commit writes the same stage files from off the actor; a
     * pass that loaded a stage before the commit and saved it after would put
     * the old stage back. The Update is held open on `route.json`, so the
     * precondition is proved, not timed (spec, Edge cases, last row).
     */
    @Test
    fun testThePassStandsAsideWhileADownloadIsInFlight() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        rewriteInstalledRouteFile(shikokuHours)
        val installed = requireNotNull(manager.installedBlocking())
        h.stubWholePackage("v1.8.0")
        val routeFile = h.hold("route.json", "v1.8.0")
        val saves = countingSaves(manager)
        val update = manager.update(h.entry, "v1.8.0")
        routeFile.awaitArrival()
        assertTrue("the Update is in flight, or this proves nothing", manager.phase.value is Phase.Downloading)

        manager.restamp(installed)

        assertEquals("nothing written while the download holds the stages", 0, saves.get())
        routeFile.release()
        update.awaitBlocking()
        assertEquals("the commit's own two writes", 2, saves.get())
    }

    /** Review Focus 2: a stage `way.json` that no longer decodes is stepped over, its bytes as they were. */
    @Test
    fun `a stage whose way json no longer loads is skipped, its bytes untouched, and the rest are stamped`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        rewriteInstalledRouteFile(shikokuHours)
        val broken = File(h.waysDir, "$stageZeroId/way.json").apply { writeText("{\"id\":") }

        manager.restamp(requireNotNull(manager.installedBlocking()))

        assertEquals("{\"id\":", broken.readText())
        assertEquals(shikokuMinutes, h.wayStore.load(stageOneId)?.stampHours)
    }
```

- [ ] **Step 2: Write the failing launch tests**

In `T/walk/honor/HonorFinalizerTest.kt` add imports `java.util.concurrent.CopyOnWriteArrayList`, `kotlinx.serialization.json.Json`, `kotlinx.serialization.json.JsonArray`, `kotlinx.serialization.json.JsonElement`, `kotlinx.serialization.json.JsonObject`, `kotlinx.serialization.json.buildJsonObject`, `kotlinx.serialization.json.jsonObject`, `kotlinx.serialization.json.put` and `org.walktalkmeditate.pilgrim.domain.honor.WayStampHours`. Before `/** Through the injected constructor` (line 930):

```kotlin
    // The stamp hours at launch (iOS PR #91, `AppDelegate.swift:188-199@e551b11`): fed by the package read, after the reconcile

    private fun hours(opens: String, closes: String) = buildJsonObject {
        put("opens", opens)
        put("closes", closes)
    }

    private fun PilgrimagePackageHarness.rewriteRouteFile(stampHours: JsonElement) {
        val file = requireNotNull(wayStore.routeFile("camino-frances"))
        val route = Json.parseToJsonElement(file.readText()).jsonObject
        file.writeText(JsonObject(route + ("stampHours" to stampHours)).toString())
    }

    @Test
    fun `the launch hands the installed route's stamp hours down to its stages, before the expiry sweep`() = runBlocking {
        val packages = PilgrimagePackageHarness(File(folder.root, "packages"))
        val tiles = PilgrimageTilesHarness()
        val steps = CopyOnWriteArrayList<String>()
        try {
            packages.makeManager().download(packages.entry, PilgrimagePackageHarness.RELEASE).awaitBlocking()
            packages.rewriteRouteFile(hours("08:00", "17:00"))
            val manager = packages.makeManager()
            manager.saveStage = { id, wayJson ->
                steps += "restamp $id"
                packages.wayStore.saveEncoded(id, wayJson)
            }
            val finalizer = HonorFinalizer(
                h.db,
                h.store,
                h.clock,
                ledgers = h.ledgers,
                waySweeper = Provider {
                    steps += "expiry sweep"
                    noSweep()
                },
                releaseFlags = FixedReleaseFlags(honor = true),
                packageManager = Provider { manager },
                tilesManager = Provider { tiles.manager },
            )

            finalizer.runAtLaunch()

            assertEquals(
                listOf("restamp pilgrimage:camino-frances:0", "restamp pilgrimage:camino-frances:1", "expiry sweep"),
                steps.toList(),
            )
            (0..1).forEach { index ->
                assertEquals(
                    WayStampHours(opensMinutes = 480, closesMinutes = 1020),
                    packages.wayStore.load(WayStore.stageWayId("camino-frances", index))?.stampHours,
                )
            }
        } finally {
            tiles.close()
            packages.close()
        }
    }

    /** Review Focus 1: a later dataset's shape costs the notice, never the route, so the sweep leaves its maps. */
    @Test
    fun `a launch whose installed route json reshapes its stamp hours keeps the route's saved maps`() = runBlocking {
        val packages = PilgrimagePackageHarness(File(folder.root, "packages"))
        val tiles = PilgrimageTilesHarness()
        try {
            packages.makeManager().download(packages.entry, PilgrimagePackageHarness.RELEASE).awaitBlocking()
            packages.rewriteRouteFile(JsonArray(listOf(hours("08:00", "12:00"), hours("13:00", "17:00"))))
            listOf("pilgrimage:camino-frances:0", "pilgrimage:camino-frances:1", "pilgrimage:camino-norte:0")
                .forEach { tiles.loader.seed(it, corridorHash = "h") }

            launchFinalizer(tiles, packages.makeManager()).runAtLaunch()
            tiles.drain()
            tiles.loader.releaseRegions()

            assertEquals("only the map no installed route owns goes", setOf("pilgrimage:camino-norte:0"), tiles.loader.removedIds.toSet())
            assertNull("and its stages stay silent", packages.wayStore.load("pilgrimage:camino-frances:0")?.stampHours)
        } finally {
            tiles.close()
            packages.close()
        }
    }

    @Test
    fun `a launch with nothing installed hands nothing down`() = runBlocking {
        val tiles = PilgrimageTilesHarness()
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = packageManager(File(folder.root, "pilgrimage-tmp"), managerScope)
            val saves = AtomicInteger()
            manager.saveStage = { _, _ -> saves.incrementAndGet() }

            launchFinalizer(tiles, manager).runAtLaunch()

            assertEquals(0, saves.get())
        } finally {
            tiles.close()
            managerScope.cancel()
        }
    }

    /** The XCTest guard's counterpart (`AppDelegate.swift:189@e551b11`): with the flag off, no launch work runs. */
    @Test
    fun `with the release flag off the launch hands nothing down`() = runBlocking {
        val packages = PilgrimagePackageHarness(File(folder.root, "packages"))
        val tiles = PilgrimageTilesHarness()
        try {
            packages.makeManager().download(packages.entry, PilgrimagePackageHarness.RELEASE).awaitBlocking()
            packages.rewriteRouteFile(hours("08:00", "17:00"))
            val finalizer = HonorFinalizer(
                h.db,
                h.store,
                h.clock,
                ledgers = h.ledgers,
                waySweeper = Provider { noSweep() },
                releaseFlags = FixedReleaseFlags(honor = false),
                packageManager = Provider { packages.makeManager() },
                tilesManager = Provider { tiles.manager },
            )

            finalizer.runAtLaunch()

            assertNull(packages.wayStore.load("pilgrimage:camino-frances:0")?.stampHours)
        } finally {
            tiles.close()
            packages.close()
        }
    }
```

- [ ] **Step 3: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManagerLifecycleTest' --tests 'org.walktalkmeditate.pilgrim.walk.honor.HonorFinalizerTest'`
Expected: FAIL to compile, "Unresolved reference 'restampStageHours'".

- [ ] **Step 4: Implement the pass**

In `P/data/honor/pilgrimage/PilgrimagePackageManager.kt`, change `runAtLaunch`'s `@return` (lines 227-229) to:

```kotlin
     * @return what that read found, for the tiles reconcile and
     *   [restampStageHours], which iOS feeds from its same launch read
     *   (spec D §C2.10; `AppDelegate.swift:188-199@e551b11`). A throw means
     *   no read: the caller sweeps and restamps nothing.
```

After `runAtLaunch` (line 235):

```kotlin
    /**
     * iOS `restampStageHours(of:)` (`PilgrimagePackageManager.swift:143-170@e551b11`):
     * a route installed by a build whose stages carry no stamp hours
     * (Android 2.0.0) has them in its `route.json`, committed byte for byte,
     * and an Update compares releases only, so nothing else would ever hand
     * them down. Each stage Way whose hours differ from the route's is saved
     * with the route's through [saveStage], and nothing else about it
     * changes; the store keeps its `accepted.json`. Compare, then save, so
     * every launch after the first writes nothing. It never clears hours.
     *
     * On the actor and IO, never the main thread, where iOS runs it. It
     * stands aside while a download is in flight: that download's commit
     * writes these same files from [offActor], and [isDownloading] covers
     * that window. A route that states no hours is never touched.
     */
    suspend fun restampStageHours(installed: Installed) = onActor {
        val hours = installed.route.stampHours
        if (isDownloading || hours == null) return@onActor
        for (index in 0 until installed.route.stageCount) {
            val id = WayStore.stageWayId(installed.routeId, index)
            val way = store.load(id)
            if (way == null || way.stampHours == hours) continue
            try {
                saveStage(id, WayJson.encode(way.copy(stampHours = hours)))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // That stage stays silent and the next launch tries again: a
                // notice is never a reason to fail a launch. iOS prints the
                // failure; nothing in this package logs (P1 §3.1).
            }
        }
    }
```

- [ ] **Step 5: Call it at launch**

In `P/walk/honor/HonorFinalizer.kt`, replace lines 263-278 with:

```kotlin
        /**
         * iOS `reconcilePilgrimageAtLaunch` (`AppDelegate.swift:188-199@e551b11`,
         * spec D §C2.10), in its order: the tiles first, whose store starts its
         * first read here so it is warm even if the package read below throws;
         * then the launch's package read, which finishes a Replace a kill
         * interrupted; then the sweep of every saved map that read doesn't
         * account for, which posts and returns, so nothing after it waits on
         * the tile store; then, from that same read, the route's stamp hours
         * handed down to any stage staged without them (pilgrim-ios #91), on
         * the package manager's actor and IO. A thrown read skips both.
         */
        private suspend fun launchPilgrimage(tiles: PilgrimageTilesManager, packages: Provider<PilgrimagePackageManager>) {
            tiles.warm()
            val manager = packages.get()
            val installed = manager.runAtLaunch()
            tiles.reconcile(installed?.let { PilgrimageTilesManager.InstalledRoute(it.routeId, it.route.stageCount) })
            if (installed != null) manager.restampStageHours(installed)
        }
```

- [ ] **Step 6: Run them to see them pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.data.honor.pilgrimage.*' --tests 'org.walktalkmeditate.pilgrim.walk.honor.HonorFinalizerTest'`
Expected: PASS, the existing launch-order tests included.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/pilgrimage/PilgrimagePackageManager.kt \
  app/src/main/java/org/walktalkmeditate/pilgrim/walk/honor/HonorFinalizer.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/data/honor/pilgrimage/PilgrimagePackageManagerLifecycleTest.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/walk/honor/HonorFinalizerTest.kt
git commit -m "feat(honor): the launch hands a route's stamp hours down to its stages

Phase 22 U22 (iOS #91's launch pass, 9b25093): restampStageHours on the
package actor, fed by the launch's one installed read after the tiles
reconcile; stands aside mid-download, compares before it saves, and skips a
stage it can't write.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task U23: Three notice clocks, in memory and in Room schema 13

Water keeps its own hour, every notice keeps a minute from the last, and the temples' ten minutes get a home. Schema 13 lands here, with the split, so no commit forgets water's hour across a `:tracker` revival. `last_stamp_seconds` is stored and restored from here on; U24's watcher is its writer.

**Files:**
- Modify: `P/domain/honor/HonorTuning.kt:37-38`
- Modify: `P/domain/honor/HonorMomentTracker.kt:11-16` (doc), `:46-56` (Snapshot), `:88-93` (clocks), `:170-194` (snapshot, restore), `:203-223` (waterAhead)
- Modify: `P/data/honor/HonorSessionEntity.kt:26-28`, `:122-128`, after `:150`
- Modify: `P/data/honor/HonorDao.kt:31`
- Modify: `P/data/PilgrimDatabase.kt:53`, `:383-384`, after `:411`, `:431`
- Create (generated): `app/schemas/org.walktalkmeditate.pilgrim.data.PilgrimDatabase/13.json`
- Modify: `P/walk/honor/HonorSessionState.kt:188-194`, `:215`
- Test: `T/domain/honor/HonorMomentTrackerTest.kt:439-467` and new tests; `T/walk/honor/HonorSessionStateTest.kt`; `T/data/PilgrimDatabaseMigrationTest.kt`; `T/data/honor/HonorDaoTest.kt:58-74`; `T/ui/walk/HonorWalkViewModelTest.kt:1876-1885`

**Interfaces:**
- Consumes: nothing from U20–U22.
- Produces:
  - `HonorTuning.NOTICE_GAP_SECONDS: Double = 60.0`.
  - `HonorMomentTracker.Snapshot(reached: Set<String>, queue: List<String>, firedMarks: Set<String>, lastNoticeSeconds: Double?, lastWaterSeconds: Double?, lastStampSeconds: Double?)`.
  - Private tracker fields `lastNoticeSeconds` (the shared minute), `lastWaterSeconds`, `lastStampSeconds`, and `private fun isLineFree(activeSeconds: Double): Boolean`.
  - `HonorSessionEntity.lastWaterSeconds: Double? = null`, `.lastStampSeconds: Double? = null` (columns `last_water_seconds`, `last_stamp_seconds`, last in the entity).
  - `HonorEngineState.lastWaterSeconds: Double?`, `.lastStampSeconds: Double?` (last, no default).
  - `PilgrimDatabase.MIGRATION_12_13`, `version = 13`.

- [ ] **Step 1: Write the failing tracker tests**

In `T/domain/honor/HonorMomentTrackerTest.kt`, in `a restore brings back the water spoken and the quiet clock, keeping only marks it watches` (line 444) add after the first `assertEquals`:

```kotlin
        assertEquals("water's own hour", 42.0, snapshot.lastWaterSeconds!!, 0.0)
```

Replace line 461 with:

```kotlin
        t.restore(
            HonorMomentTracker.Snapshot(
                reached = emptySet(),
                queue = emptyList(),
                firedMarks = emptySet(),
                lastNoticeSeconds = null,
                lastWaterSeconds = null,
                lastStampSeconds = null,
            ),
        )
```

Before `the injected distance decides reach` (line 469):

```kotlin
    // iOS PR #91's clocks (`HonorMomentTracker.swift:62-81,179-189,226-229@e551b11`)

    private fun clocks(notice: Double?, water: Double?, stamp: Double?) = HonorMomentTracker.Snapshot(
        reached = emptySet(),
        queue = emptyList(),
        firedMarks = emptySet(),
        lastNoticeSeconds = notice,
        lastWaterSeconds = water,
        lastStampSeconds = stamp,
    )

    @Test
    fun `water writes its own hour and the shared minute, and no temple clock`() {
        val t = markTracker(water("a", frac = 0.3))

        t.waterAt(100.0, progress = 0.1, clock = 42.0)

        val snapshot = t.snapshot()
        assertEquals(listOf<Double?>(42.0, 42.0, null), listOf(snapshot.lastNoticeSeconds, snapshot.lastWaterSeconds, snapshot.lastStampSeconds))
    }

    /** The shared minute binds water too, though in a water-only walk its own hour always covers it. */
    @Test
    fun `a notice under a minute ago holds water, even with water's own hour free`() {
        val t = markTracker(water("a", frac = 0.5))
        t.restore(clocks(notice = 100.0, water = null, stamp = 100.0))

        assertEquals("30 s after another notice", emptyList<String>(), markAhead(t.waterAt(250.0, progress = 0.25, clock = 130.0)))
        assertEquals("exactly a minute after it the line is free", listOf("a"), markAhead(t.waterAt(260.0, progress = 0.26, clock = 160.0)))
    }

    @Test
    fun `a restore keeps each clock its own, and a null stays null`() {
        val t = markTracker(water("a", frac = 0.5))
        val restored = clocks(notice = 500.0, water = null, stamp = 450.0)

        t.restore(restored)

        assertEquals(restored, t.snapshot())
    }
```

- [ ] **Step 2: Write the failing Room and session-state tests**

In `T/walk/honor/HonorSessionStateTest.kt`, in `a session row and its moment rows restore the engine where it stood`, after `lastNoticeSeconds = state.lastNoticeSeconds,` (line 111):

```kotlin
            lastWaterSeconds = state.lastWaterSeconds,
            lastStampSeconds = state.lastStampSeconds,
```

and add a test after it:

```kotlin
    @Test
    fun `the row's three clocks go to the tracker and come back, each its own`() {
        val session = HonorSessionEntity(
            walkId = 1L,
            wayId = HonorHarness.STAGE_ID,
            sourceKind = HonorSourceKind.PILGRIMAGE,
            voicesEnabled = false,
            softTapEnabled = false,
            lastNoticeSeconds = 2_400.0,
            lastWaterSeconds = 1_820.4,
            lastStampSeconds = 2_400.0,
        )

        val snapshot = session.engineSnapshot(rows = emptyList(), notices = emptyList())
        val state = snapshot.toEngineState(walkId = 1L)

        assertEquals(
            listOf<Double?>(2_400.0, 1_820.4, 2_400.0),
            listOf(snapshot.tracker.lastNoticeSeconds, snapshot.tracker.lastWaterSeconds, snapshot.tracker.lastStampSeconds),
        )
        assertEquals(listOf<Double?>(2_400.0, 1_820.4, 2_400.0), listOf(state.lastNoticeSeconds, state.lastWaterSeconds, state.lastStampSeconds))
    }
```

In `T/data/honor/HonorDaoTest.kt`, the `engine` helper (line 73) gains:

```kotlin
        lastNoticeSeconds = null,
        lastWaterSeconds = null,
        lastStampSeconds = null,
    )
```

and after `an engine update writes the quiet clock and leaves the stage identity alone` (line 159):

```kotlin
    @Test
    fun `an engine update writes all three notice clocks, each to its own column`() = runTest {
        dao.insertSession(session())

        dao.updateEngineState(engine(progress = 0.4).copy(lastNoticeSeconds = 2_400.0, lastWaterSeconds = 1_820.4, lastStampSeconds = 2_400.0))

        val row = dao.getSession(walkId)!!
        assertEquals(listOf<Double?>(2_400.0, 1_820.4, 2_400.0), listOf(row.lastNoticeSeconds, row.lastWaterSeconds, row.lastStampSeconds))
    }
```

In `T/ui/walk/HonorWalkViewModelTest.kt`'s `disarmSoftTap` (line 1884), after `lastNoticeSeconds = session.lastNoticeSeconds,`:

```kotlin
                lastWaterSeconds = session.lastWaterSeconds, lastStampSeconds = session.lastStampSeconds,
```

In `T/data/PilgrimDatabaseMigrationTest.kt`:
- Change line 51 to `The 8→9, 9→10, 10→11, 11→12, and 12→13 tests, and the chains from 6, 7, and 8, build the starting version`.
- Rename `a v11 database migrates to 12 through Room's identity check and keeps its data` to `a v11 database migrates through 12 and Room's identity check and keeps its data`, and change lines 1078 and 1080 to `LATEST_VERSION.toLong()` and `MigrationTestDatabases.identityHash(LATEST_VERSION)`.
- Change line 1150's comment to `// ---- 8 → 13: the path every 1.5.0 install takes to 2.1.0 ----`; rename the test below it to `a v8 database with walks, sittings, and cached stats reaches the latest schema through Room's identity check intact`, and change lines 1159 and 1161 the same way.
- `LATEST_VERSION` (line 1397) becomes `13`, and beside `SCHEMA_11_IDENTITY_HASH` (line 1404):

```kotlin
        const val SCHEMA_12_PATH = "schemas/org.walktalkmeditate.pilgrim.data.PilgrimDatabase/12.json"
        const val SCHEMA_12_SHA256 = "59bf5c4ce0f9eb35981a1baf10abf699ca3bfa778a6d647743e78fc59c9a5aeb"
        const val SCHEMA_12_IDENTITY_HASH = "6b5cb42e6e0b92d704bdd83b718cf6fd"
```

Add after `schema 11's export is byte for byte the one that ran on a device` (line 1148):

```kotlin
    /** Schema 12 shipped in Android 2.0.0 (code 912), so its export is frozen as schema 11's is. */
    @Test
    fun `schema 12's export is byte for byte the one 2_0_0 shipped`() {
        val export = listOf(File(SCHEMA_12_PATH), File("app", SCHEMA_12_PATH)).first { it.isFile }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(export.readBytes())
            .joinToString("") { "%02x".format(it) }

        assertEquals(SCHEMA_12_SHA256, sha256)
        assertEquals(SCHEMA_12_IDENTITY_HASH, MigrationTestDatabases.identityHash(12))
    }

    // ---- 12 → 13: the three notice clocks (iOS PR #91; stamp-closing spec, owner decision 1) ----

    /** A 2.0.0 stage row, mid-walk, with water spoken at 1,820.4 s of engine time. */
    private fun SupportSQLiteDatabase.insertV12StageSession(lastNoticeSeconds: Double?) = insertRow(
        "honor_sessions",
        "walk_id" to UNFINISHED_WALK, "way_id" to "pilgrimage:camino-frances:4",
        "source_kind" to "PILGRIMAGE", "voices_enabled" to 0, "soft_tap_enabled" to 0, "phase" to "WALKING",
        "start_frac" to 0.1, "anchored_by_fallback" to 0, "anchor_active_seconds" to 12.0,
        "companion_t0_seconds" to 30.0, "progress_frac" to 0.4, "progress_high_water" to 0.4,
        "walked_frac" to 0.3, "off_way_active_seconds" to 0.0, "soft_tap_armed" to 1,
        "arrival_inside_fixes" to 0, "voice_paused" to 0, "voice_rate" to 1.0,
        "last_command_seq" to 2L, "gate_generation" to 1L, "last_notice_seconds" to lastNoticeSeconds,
        "stage_route_id" to "camino-frances", "stage_index" to 4, "stage_name" to "Larrasoaña to Pamplona",
        "stage_distance_km" to 15.6,
    )

    @Test
    fun `a v12 database migrates through 13, and water's hour is the one clock schema 12 kept`() {
        MigrationTestDatabases.createAtVersion(context, dbName, version = 12) { db ->
            seedV8World(db)
            db.insertV12StageSession(lastNoticeSeconds = 1_820.4)
            db.insertRow(
                "honor_notices",
                "walk_id" to UNFINISHED_WALK, "kind" to "WATER", "ref_id" to "wp-osm-water-node1",
                "meters" to 280.0, "fired_at" to 21_500_000L,
            )
        }

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(LATEST_VERSION.toLong(), db.longQuery("PRAGMA user_version"))
            assertEquals(
                MigrationTestDatabases.identityHash(LATEST_VERSION),
                db.stringQuery("SELECT identity_hash FROM room_master_table WHERE id = 42"),
            )
            runBlocking {
                val session = room.honorDao().getSession(UNFINISHED_WALK)!!
                assertEquals("only water wrote it through schema 12", 1_820.4, session.lastWaterSeconds!!, 0.0)
                assertEquals("and the same value now starts the shared minute", 1_820.4, session.lastNoticeSeconds!!, 0.0)
                assertNull("no temple has spoken", session.lastStampSeconds)
                assertEquals(listOf<Any?>("camino-frances", 4, 0.4), listOf(session.stageRouteId, session.stageIndex, session.progressFrac))
                assertEquals(listOf("wp-osm-water-node1"), room.honorDao().getNotices(UNFINISHED_WALK).map { it.refId })
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `a v12 row whose first notice is still free migrates with all three clocks free`() {
        MigrationTestDatabases.createAtVersion(context, dbName, version = 12) { db ->
            seedV8World(db)
            db.insertV12StageSession(lastNoticeSeconds = null)
        }

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            runBlocking {
                val session = room.honorDao().getSession(UNFINISHED_WALK)!!
                assertEquals(listOf<Double?>(null, null, null), listOf(session.lastNoticeSeconds, session.lastWaterSeconds, session.lastStampSeconds))
            }
        } finally {
            room.close()
        }
    }
```

- [ ] **Step 3: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.domain.honor.HonorMomentTrackerTest' --tests 'org.walktalkmeditate.pilgrim.walk.honor.HonorSessionStateTest' --tests 'org.walktalkmeditate.pilgrim.data.honor.HonorDaoTest' --tests 'org.walktalkmeditate.pilgrim.data.PilgrimDatabaseMigrationTest'`
Expected: FAIL to compile, "No parameter with name 'lastWaterSeconds' found".

- [ ] **Step 4: Implement the tracker's clocks**

In `P/domain/honor/HonorTuning.kt`, after `MARK_QUIET_SECONDS` (line 38):

```kotlin

    /**
     * The least walking between any two notices on the caption line, water
     * or temple (`HonorTuning.swift:33-36@e551b11`): the line holds a notice
     * for 20 s, and a minute keeps one from replacing another still being
     * read, or two taps landing together.
     */
    const val NOTICE_GAP_SECONDS = 60.0
```

In `P/domain/honor/HonorMomentTracker.kt`, replace the class doc's water paragraph (lines 11-16) with:

```kotlin
 * It also watches a pilgrimage stage's water (pilgrimage-stage spec P3
 * §2): only on-way water marks, each spoken once, within
 * [HonorTuning.MARK_AHEAD_METERS] ahead along the line while the walker is
 * on the Way. Clocks of engine seconds keep the caption line quiet (iOS PR
 * #91, `HonorMomentTracker.swift:62-81@e551b11`): water's own hour
 * ([HonorTuning.MARK_QUIET_SECONDS]) and a minute between any two notices
 * ([HonorTuning.NOTICE_GAP_SECONDS]), each first notice free. It reads no
 * gate, and runs only on a fix. Own and shared Ways carry no marks, so for
 * them it never speaks.
```

Replace `Snapshot` (lines 46-56):

```kotlin
    /**
     * What a revival needs back: the moments reached, the voices waiting in
     * queue order, the water marks spoken, and the notice clocks, each null
     * while its first notice is still free (a null restored stays null, never 0).
     */
    data class Snapshot(
        val reached: Set<String>,
        val queue: List<String>,
        val firedMarks: Set<String>,
        val lastNoticeSeconds: Double?,
        val lastWaterSeconds: Double?,
        val lastStampSeconds: Double?,
    )
```

Replace lines 88-93:

```kotlin
    /**
     * The engine clock at the last notice of either kind, read only for the
     * minute that keeps two notices off the caption line at once; null means
     * the line is free (iOS `lastNoticeSeconds`, `HonorMomentTracker.swift:68-73@e551b11`).
     * That minute can cost one of them, as iOS documents and intends: a
     * fountain under about 80 m ahead when another notice speaks is passed
     * before the minute is up, and a temple 1.00–1.08 km ahead is inside the
     * kilometre by then.
     */
    private var lastNoticeSeconds: Double? = null

    /** Water's own hour: the engine clock at the last water notice; null means the first is free (`:64-67`). */
    private var lastWaterSeconds: Double? = null

    /** The temples' ten minutes: the engine clock at the last temple notice; null means the first is free (`:80-81`). */
    private var lastStampSeconds: Double? = null
```

`snapshot()` (lines 170-175) becomes:

```kotlin
    fun snapshot(): Snapshot = Snapshot(
        reached = reached.toSet(),
        queue = queue.map { it.id },
        firedMarks = firedMarks.toSet(),
        lastNoticeSeconds = lastNoticeSeconds,
        lastWaterSeconds = lastWaterSeconds,
        lastStampSeconds = lastStampSeconds,
    )
```

and in `restore`, after `lastNoticeSeconds = snapshot.lastNoticeSeconds` (line 191):

```kotlin
        lastWaterSeconds = snapshot.lastWaterSeconds
        lastStampSeconds = snapshot.lastStampSeconds
```

Replace `waterAhead` (lines 203-223):

```kotlin
    /**
     * iOS `waterAhead` (`HonorMomentTracker.swift:175-191@e551b11`): the
     * nearest unfired water the walker is about to reach. Never one already
     * behind them, never off the Way, never inside the minute after another
     * notice, and at most one an hour of walking; the marks skipped inside
     * the quiet hour stay silent pins. A water notice writes its own hour
     * and the shared minute.
     */
    private fun waterAhead(progressFrac: Double, activeSeconds: Double, isOnWay: Boolean): List<Action> {
        if (!isOnWay || marks.isEmpty() || !(geometry.totalMeters > 0) || !isLineFree(activeSeconds)) return emptyList()
        val last = lastWaterSeconds
        if (last != null && activeSeconds - last < HonorTuning.MARK_QUIET_SECONDS) return emptyList()
        for (mark in marks) {
            if (mark.id in firedMarks) continue
            val ahead = (mark.frac - progressFrac) * geometry.totalMeters
            if (!(ahead >= 0)) continue
            if (!(ahead <= HonorTuning.MARK_AHEAD_METERS)) break
            firedMarks += mark.id
            lastWaterSeconds = activeSeconds
            lastNoticeSeconds = activeSeconds
            return listOf(Action.MarkAhead(mark, meters = ahead))
        }
        return emptyList()
    }

    /** One notice on the caption line at a time, whatever its kind (`:225-229`): exactly a minute is free. */
    private fun isLineFree(activeSeconds: Double): Boolean {
        val last = lastNoticeSeconds ?: return true
        return activeSeconds - last >= HonorTuning.NOTICE_GAP_SECONDS
    }
```

- [ ] **Step 5: Implement schema 13**

In `P/data/honor/HonorSessionEntity.kt`, replace lines 26-28 of the class doc with:

```kotlin
 * `companionFrac`, which the next fix or tick recomputes. A stage's
 * notices spoken are rows of `honor_notices`; the clocks between them are
 * [lastNoticeSeconds], [lastWaterSeconds] and [lastStampSeconds] (schema
 * 13, stamp-closing spec owner decision 1).
```

Replace the `lastNoticeSeconds` doc (lines 122-126):

```kotlin
    /**
     * The engine clock at the last notice of either kind, in seconds, read
     * only for the minute between notices (iOS `lastNoticeSeconds`,
     * `HonorMomentTracker.swift:68-73@e551b11`); null while none has spoken.
     * Through schema 12 only water wrote it, as water's hour, which schema
     * 13 copied into [lastWaterSeconds].
     */
```

After `stageDistanceKm` (line 150):

```kotlin
    /** Water's own hour: the engine clock at the last water notice, in seconds; null while the first is free. */
    @ColumnInfo(name = "last_water_seconds")
    val lastWaterSeconds: Double? = null,
    /** The temples' ten minutes: the engine clock at the last temple notice, in seconds; null while the first is free. */
    @ColumnInfo(name = "last_stamp_seconds")
    val lastStampSeconds: Double? = null,
```

In `P/data/honor/HonorDao.kt`, after `lastNoticeSeconds` (line 31):

```kotlin
    @ColumnInfo(name = "last_water_seconds") val lastWaterSeconds: Double?,
    @ColumnInfo(name = "last_stamp_seconds") val lastStampSeconds: Double?,
```

In `P/data/PilgrimDatabase.kt`, line 53 becomes `version = 13,`, and lines 383-384 become:

```kotlin
         * - `last_notice_seconds`: the engine clock at the last notice;
         *   NULL means the first is free. Water's hour through schema 12;
         *   schema 13 makes it the shared minute.
```

After `MIGRATION_11_12` (line 411):

```kotlin

        /**
         * Phase 22 (iOS PR #91): a stage's three notice clocks (stamp-closing
         * spec, owner decision 1). Two nullable columns on `honor_sessions`.
         * `last_notice_seconds` was water's hour, the only notice schema 12
         * wrote, so it is copied into `last_water_seconds` exactly, and from
         * here on it is the minute shared by every notice. A row with no
         * notice yet keeps every clock NULL, the first notice free.
         *
         * Schema 12 shipped in 2.0.0, so it is never edited: this is the
         * only way its tables change.
         */
        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `honor_sessions` ADD COLUMN `last_water_seconds` REAL")
                db.execSQL("ALTER TABLE `honor_sessions` ADD COLUMN `last_stamp_seconds` REAL")
                db.execSQL("UPDATE `honor_sessions` SET `last_water_seconds` = `last_notice_seconds`")
            }
        }
```

and `MIGRATIONS` ends:

```kotlin
                MIGRATION_11_12,
                MIGRATION_12_13,
            )
```

In `P/walk/honor/HonorSessionState.kt`, `engineSnapshot`'s tracker (lines 188-193) ends:

```kotlin
        lastNoticeSeconds = lastNoticeSeconds,
        lastWaterSeconds = lastWaterSeconds,
        lastStampSeconds = lastStampSeconds,
    ),
```

and `toEngineState` (line 215) ends:

```kotlin
    lastNoticeSeconds = tracker.lastNoticeSeconds,
    lastWaterSeconds = tracker.lastWaterSeconds,
    lastStampSeconds = tracker.lastStampSeconds,
)
```

- [ ] **Step 6: Export schema 13 and check it**

Run: `./gradlew :app:kspDebugKotlin` (Room's KSP processor writes the export; `./gradlew :app:compileDebugKotlin` runs it too)

Then:

```bash
shasum -a 256 app/schemas/org.walktalkmeditate.pilgrim.data.PilgrimDatabase/12.json
python3 -c "
import json
d = json.load(open('app/schemas/org.walktalkmeditate.pilgrim.data.PilgrimDatabase/13.json'))['database']
print(d['version'])
print([e['createSql'] for e in d['entities'] if e['tableName'] == 'honor_sessions'][0][-170:])
"
```

Expected: `59bf5c4ce0f9eb35981a1baf10abf699ca3bfa778a6d647743e78fc59c9a5aeb` (schema 12 untouched), then `13`, then a `honor_sessions` DDL ending `` `stage_distance_km` REAL, `last_water_seconds` REAL, `last_stamp_seconds` REAL, PRIMARY KEY(`walk_id`), FOREIGN KEY(`walk_id`) REFERENCES `walks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE ) ``.

- [ ] **Step 7: Run the tests, the water revivals and the golden traces**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.domain.honor.*' --tests 'org.walktalkmeditate.pilgrim.walk.honor.*' --tests 'org.walktalkmeditate.pilgrim.data.*' --tests 'org.walktalkmeditate.pilgrim.ui.walk.HonorWalkViewModelTest'`
Expected: PASS, with `HonorGoldenTraceTest` (W1–W10) and `HonorSessionWaterGoldenTest` unchanged, and `HonorSessionTest`'s `a revival keeps the quiet hour, so node 2 in reach waits for it` green: water's hour survives the revival through `last_water_seconds`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/ app/src/main/java/org/walktalkmeditate/pilgrim/data/ \
  app/src/main/java/org/walktalkmeditate/pilgrim/walk/honor/HonorSessionState.kt \
  app/schemas/org.walktalkmeditate.pilgrim.data.PilgrimDatabase/13.json \
  app/src/test/java/org/walktalkmeditate/pilgrim/domain/honor/HonorMomentTrackerTest.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/walk/honor/HonorSessionStateTest.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/data/ app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/HonorWalkViewModelTest.kt
git commit -m "feat(honor): water keeps its own hour, every notice a shared minute (schema 13)

Phase 22 U23 (iOS #91's c8e2d35): three engine-time clocks in the tracker
and in Room schema 13; MIGRATION_12_13 copies last_notice_seconds, which
only water wrote, into last_water_seconds. Schema 12's export is frozen.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task U24: A temple ahead, in `:tracker`

**Files:**
- Modify: `P/domain/honor/HonorTuning.kt` (after U23's `NOTICE_GAP_SECONDS`)
- Modify: `P/domain/honor/HonorMomentTracker.kt` (imports; doc; constructor `:18-24`; `Action` `:26-35`; new `StampOffice`; `Snapshot`; after `marks` `:83`; `update` `:144`; `snapshot`, `restore`; new `stampAhead`)
- Modify: `P/domain/honor/HonorEngine.kt:23-34`, `:47-60`, `:96-102`, `:376-383`, `:385-394`
- Modify: `P/data/honor/HonorNoticeEntity.kt:9-14`, `:41-43`
- Modify: `P/walk/honor/HonorSessionState.kt` (`engineSnapshot`; new `stampNotice`)
- Modify: `P/walk/honor/HonorSession.kt:79-88`, `:523-554`
- Modify: `app/src/debug/kotlin/org/walktalkmeditate/pilgrim/debug/honor/HonorDebugReceiver.kt:350-372`, `:403`
- Test: `T/domain/honor/HonorMomentTrackerTest.kt`, `T/domain/honor/HonorEngineTest.kt`, `T/domain/honor/HonorGoldenTraceTest.kt:611-621`, `T/walk/honor/HonorHarness.kt` (companion, after `stageWay`, `:247`), `T/walk/honor/HonorSessionTest.kt`, `T/walk/honor/HonorSessionStateTest.kt`, `T/data/ConvertersTest.kt:62-65`, `T/data/honor/HonorDaoTest.kt:185-193`

**Interfaces:**
- Consumes: U20's `WayMoment.templeNumber`, `Way.stampHours`, `WayStampHours`; U23's three clocks, `isLineFree`, `Snapshot` (six fields), schema 13 columns.
- Produces:
  - `HonorTuning.STAMP_NOTICE_MINUTES: Int = 120`, `STAMP_MIN_AHEAD_METERS = 1000.0`, `STAMP_QUIET_SECONDS = 600.0`.
  - `class HonorMomentTracker.StampOffice(val closesMinutes: Int, val zone: ZoneId, clock: Clock) { val isClosingSoon: Boolean }`.
  - `HonorMomentTracker(moments, marks = emptyList(), geometry, voicesEnabled, distance = WGS84_HONOR_DISTANCE, stamp: StampOffice? = null)`.
  - `HonorMomentTracker.Action.StampAhead(val temple: WayMoment, val meters: Double)`.
  - `Snapshot(..., lastStampSeconds, val firedStamps: Set<String>)` (appended last).
  - `HonorEngineEvent.StampAhead(val temple: WayMoment, val meters: Double)`.
  - `HonorEngine(way, softTapEnabled, voicesEnabled, clock, distance = …, fallbackZone: ZoneId = ZoneId.systemDefault())`.
  - `enum class HonorNoticeKind { WATER, STAMP, UNKNOWN }`.
  - `internal fun stampNotice(walkId: Long, way: Way, event: HonorEngineEvent.StampAhead, firedAt: Long): HonorNoticeEntity?`.
  - Test harness: `HonorHarness.SHIKOKU_HOURS`, `HonorHarness.temple(number: Int, lon: Double)`, `HonorHarness.templeStage(stampHours: WayStampHours? = SHIKOKU_HOURS)`, `HonorHarness.tokyoMillis(hour: Int, minute: Int): Long`.

- [ ] **Step 1: Write the failing tracker tests**

In `T/domain/honor/HonorMomentTrackerTest.kt` add imports `java.time.LocalDateTime`, `java.time.ZoneId` and `org.walktalkmeditate.pilgrim.domain.Clock`. In the `a restored null clock leaves the first notice free` test's `Snapshot(...)` (U23's form) add `firedStamps = emptySet(),` after `lastStampSeconds = null,`, and in U23's `clocks(...)` helper add `firedStamps = emptySet(),` the same way. Then, before `the injected distance decides reach`:

```kotlin
    // iOS PR #91's stamp tests (`HonorMomentTrackerTests.swift:215-490@e551b11`), names kept

    /** 10 km straight east, so a frac is a kilometre: the stamp rule works in kilometres where the water rule works in metres. */
    private val longGeometry = WayGeometry((0..10).map { i -> WayPoint(lat = 0.0, lon = i * 0.00898, alt = null, t = i * 600.0) })

    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")

    /** A fixed wall clock in the temple's own zone: [hour]:[minute] on April [day], 2026, in Tokyo. */
    private fun tokyoMillis(hour: Int, minute: Int, day: Int = 12): Long =
        LocalDateTime.of(2026, 4, day, hour, minute).atZone(tokyo).toInstant().toEpochMilli()

    private fun office(hour: Int, minute: Int) =
        HonorMomentTracker.StampOffice(closesMinutes = 17 * 60, zone = tokyo, clock = Clock { tokyoMillis(hour, minute) })

    /** A numbered fudasho as the dataset writes one: the number lives in the composed `text`, the romanized name in `label`. */
    private fun temple(number: Int, frac: Double) = WayMoment(
        id = "temple-$number",
        frac = frac,
        at = WayCoordinate(lat = 0.0, lon = frac * 10_000 / 111_320),
        kind = WayMomentKind.Waypoint(label = "Kirihata-ji", icon = "seal"),
        text = "Temple $number · Koyasan Shingon · stamp available (¥500)",
    )

    /** iOS `stampTracker(_:marks:at:)`: [at] null is a route with no hours. */
    private fun stampTracker(moments: List<WayMoment>, marks: List<WayMark> = emptyList(), at: Pair<Int, Int>? = 15 to 30) =
        HonorMomentTracker(
            moments = moments,
            marks = marks,
            geometry = longGeometry,
            voicesEnabled = false,
            stamp = at?.let { (hour, minute) -> office(hour, minute) },
        )

    /** iOS `stampTracker(_:marks:clock:)`: read against a clock the test moves on between fixes. */
    private fun stampTrackerOn(clock: Clock, moments: List<WayMoment>, marks: List<WayMark> = emptyList()) =
        HonorMomentTracker(
            moments = moments,
            marks = marks,
            geometry = longGeometry,
            voicesEnabled = false,
            stamp = HonorMomentTracker.StampOffice(closesMinutes = 17 * 60, zone = tokyo, clock = clock),
        )

    /** An on-way fountain on the 10 km line, [atKm] from its start. */
    private fun fountain(atKm: Double) = WayMark(
        id = "fuente-$atKm",
        kind = WayMarkKind.WATER,
        name = "Fuente",
        at = WayCoordinate(lat = 0.0, lon = atKm * 1000 / 111_320),
        frac = atKm / 10,
        offLineMeters = 10.0,
    )

    private fun stampAhead(actions: List<Action>): List<String> = actions.filterIsInstance<Action.StampAhead>().map { it.temple.id }

    /** The iOS tests' `update(location: coord(meters), progressFrac:, gates: .init(), isStationary: false, activeSeconds:, isOnWay:)`. */
    private fun HonorMomentTracker.noticeAt(meters: Double, progress: Double, seconds: Double, onWay: Boolean = true): List<Action> =
        update(coord(meters), progressFrac = progress, gates = open, isStationary = false, activeSeconds = seconds, isOnWay = onWay)

    /** Every route but Shikoku's. Nothing, ever, whatever the hour. */
    @Test
    fun testARouteThatStatesNoStampHoursNeverSpeaksOfTemples() {
        val t = stampTracker(listOf(temple(10, frac = 0.5)), at = null)
        assertEquals(emptyList<String>(), stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))
    }

    @Test
    fun testTheNoticeOpensTwoHoursBeforeClosingAndShutsWithTheOffice() {
        val early = stampTracker(listOf(temple(10, frac = 0.5)), at = 14 to 59)
        assertEquals("14:59: the afternoon is still young", emptyList<String>(), stampAhead(early.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))
        val onTheHour = stampTracker(listOf(temple(10, frac = 0.5)), at = 15 to 0)
        assertEquals("15:00 exactly: two hours to closing", listOf("temple-10"), stampAhead(onTheHour.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))
        val closed = stampTracker(listOf(temple(10, frac = 0.5)), at = 17 to 0)
        assertEquals("17:00: no stamp left to be had", emptyList<String>(), stampAhead(closed.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))
    }

    @Test
    fun testATempleInsideAKilometreHasStoppedBeingADecision() {
        val total = longGeometry.totalMeters
        val near = stampTracker(listOf(temple(10, frac = 0.5)))
        assertEquals(
            "999 m short: the walker can see it and is already arriving",
            emptyList<String>(),
            stampAhead(near.noticeAt(4000.0, progress = 0.5 - 999 / total, seconds = 0.0)),
        )

        val far = stampTracker(listOf(temple(10, frac = 0.5)))
        val hit = far.noticeAt(4000.0, progress = 0.5 - 1001 / total, seconds = 0.0)

        assertEquals(listOf("temple-10"), stampAhead(hit))
        assertEquals(1001.0, (hit.first() as Action.StampAhead).meters, 1.0)
    }

    @Test
    fun testEachTempleSpeaksOnce() {
        val t = stampTracker(listOf(temple(10, frac = 0.5), temple(11, frac = 0.9)))
        assertEquals(listOf("temple-10"), stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))
        assertEquals(
            "still 3 km ahead ten minutes later: spoken for, and still the nearest choice, so temple 11 waits",
            emptyList<String>(),
            stampAhead(t.noticeAt(2000.0, progress = 0.2, seconds = 600.0)),
        )
        assertEquals(
            "an hour and a half on, temple 11, 4 km ahead, speaks",
            listOf("temple-11"),
            stampAhead(t.noticeAt(5000.0, progress = 0.5, seconds = 5400.0)),
        )
    }

    @Test
    fun testATempleAlreadyBehindIsNeverAnnounced() {
        val t = stampTracker(listOf(temple(10, frac = 0.3)))
        assertEquals(
            "a temple you have walked past cannot still be stamped today",
            emptyList<String>(),
            stampAhead(t.noticeAt(6000.0, progress = 0.6, seconds = 0.0)),
        )
    }

    /** A seal means a stamp is available, as at Camino pilgrim offices and Kumano shrines; only "Temple N" keeps an office that shuts. */
    @Test
    fun testASealThatIsNotANumberedTempleStaysSilent() {
        val pilgrimOffice = WayMoment(
            id = "wp-sjpp-pilgrim-office",
            frac = 0.5,
            at = WayCoordinate(lat = 0.0, lon = 5000.0 / 111_320),
            kind = WayMomentKind.Waypoint(label = "Pilgrim Welcome Office", icon = "seal"),
            text = "Pilgrim office · 172 m · stamp available",
        )
        val t = stampTracker(listOf(pilgrimOffice))
        assertEquals(emptyList<String>(), stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))
    }

    @Test
    fun testAWalkerOffTheWayIsToldNothing() {
        val t = stampTracker(listOf(temple(10, frac = 0.5)))
        assertEquals(
            "off the way the distance ahead would be a guess",
            emptyList<String>(),
            stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0, onWay = false)),
        )
    }

    /** One caption line, one notice at a time: the temple takes it, and the fountain speaks a breath later. */
    @Test
    fun testTheTempleTakesTheLineAheadOfTheFountain() {
        val t = stampTracker(listOf(temple(10, frac = 0.5)), marks = listOf(fountain(atKm = 1.25)))
        val actions = t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)
        assertEquals(listOf("temple-10"), stampAhead(actions))
        assertEquals("never two notices on one fix", emptyList<String>(), markAhead(actions))

        val tooSoon = HonorTuning.NOTICE_GAP_SECONDS / 2
        assertEquals("nor one replacing another still on the line", emptyList<String>(), markAhead(t.noticeAt(1020.0, progress = 0.102, seconds = tooSoon)))
        assertEquals(
            "the fountain keeps its own hour, not the temple's",
            listOf("fuente-1.25"),
            markAhead(t.noticeAt(1080.0, progress = 0.108, seconds = HonorTuning.NOTICE_GAP_SECONDS)),
        )
    }

    /** Water at 14:59 used to hold every temple for an hour; only the shared minute stands between them now. */
    @Test
    fun testAFountainDoesNotSilenceATemple() {
        var clock = 14 to 59
        val t = stampTrackerOn(Clock { tokyoMillis(clock.first, clock.second) }, listOf(temple(10, frac = 0.5)), marks = listOf(fountain(atKm = 1.2)))
        assertEquals(listOf("fuente-1.2"), markAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))

        clock = 15 to 0
        assertEquals(
            "a minute after the water, the temple speaks",
            listOf("temple-10"),
            stampAhead(t.noticeAt(1060.0, progress = 0.106, seconds = HonorTuning.NOTICE_GAP_SECONDS)),
        )
    }

    @Test
    fun testATempleDoesNotSilenceAFountain() {
        val t = stampTracker(listOf(temple(10, frac = 0.5)), marks = listOf(fountain(atKm = 3.2)))
        assertEquals(listOf("temple-10"), stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))
        assertEquals(
            "twenty minutes after the temple, the fountain still speaks",
            listOf("fuente-3.2"),
            markAhead(t.noticeAt(3000.0, progress = 0.3, seconds = 1200.0)),
        )
    }

    /** A first temple at 16:10 used to hold the next past 17:00; now the next may speak from 16:20. */
    @Test
    fun testTwoTemplesInTheLastHoursBothSpeakOnceEach() {
        var clock = 16 to 10
        val t = stampTrackerOn(Clock { tokyoMillis(clock.first, clock.second) }, listOf(temple(10, frac = 0.22), temple(11, frac = 0.9)))
        assertEquals(listOf("temple-10"), stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))

        clock = 16 to 20
        assertEquals(
            "ten minutes on, 600 m short of temple 10, temple 11 is the choice",
            listOf("temple-11"),
            stampAhead(t.noticeAt(1600.0, progress = 0.16, seconds = HonorTuning.STAMP_QUIET_SECONDS)),
        )

        clock = 16 to 50
        assertEquals("each has had its say", emptyList<String>(), stampAhead(t.noticeAt(5000.0, progress = 0.5, seconds = 2400.0)))
    }

    /** Only the nearest temple still a choice may speak, so the one after it waits until the walker is nearly at it. */
    @Test
    fun testATempleAlreadyNamedHoldsTheNextUntilTheWalkerIsNearlyThere() {
        val t = stampTracker(listOf(temple(10, frac = 0.5), temple(11, frac = 0.6), temple(12, frac = 0.9)))
        assertEquals(listOf("temple-10"), stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))
        assertEquals(
            "temple 10 is 3 km on and still the walker's choice; 11 and 12 are not news yet",
            emptyList<String>(),
            stampAhead(t.noticeAt(2000.0, progress = 0.2, seconds = 600.0)),
        )
        assertEquals(
            "500 m short of temple 10, the next one is the choice",
            listOf("temple-11"),
            stampAhead(t.noticeAt(4500.0, progress = 0.45, seconds = 2400.0)),
        )
    }

    /** A temple named a step outside the kilometre is inside it a minute later; the next waits ten minutes after the last. */
    @Test
    fun testTheNextTempleWaitsTenMinutesAfterTheLast() {
        val total = longGeometry.totalMeters
        val t = stampTracker(listOf(temple(10, frac = 0.5), temple(11, frac = 0.9)))
        assertEquals(listOf("temple-10"), stampAhead(t.noticeAt(3999.0, progress = 0.5 - 1001 / total, seconds = 0.0)))
        assertEquals(
            "inside the caption's minute",
            emptyList<String>(),
            stampAhead(t.noticeAt(4032.0, progress = 0.5 - 968 / total, seconds = HonorTuning.NOTICE_GAP_SECONDS / 2)),
        )
        assertEquals(
            "the line is free, but the last temple spoke a minute ago",
            emptyList<String>(),
            stampAhead(t.noticeAt(4066.0, progress = 0.5 - 934 / total, seconds = HonorTuning.NOTICE_GAP_SECONDS)),
        )
        assertEquals(
            emptyList<String>(),
            stampAhead(t.noticeAt(4665.0, progress = 0.5 - 335 / total, seconds = HonorTuning.STAMP_QUIET_SECONDS - 1)),
        )
        assertEquals(
            listOf("temple-11"),
            stampAhead(t.noticeAt(4666.0, progress = 0.5 - 334 / total, seconds = HonorTuning.STAMP_QUIET_SECONDS)),
        )
    }

    /** Awa stage-00 from 15.0 km at 4 km/h: temple 6 spoke at 1.1 km and temple 7 two minutes later; now ten. */
    @Test
    fun testTwoTemplesAKilometreApartDoNotSpeakMinutesApart() {
        val total = longGeometry.totalMeters
        val t = stampTracker(listOf(temple(6, frac = 0.5), temple(7, frac = 0.5 + 1130 / total)))
        assertEquals(listOf("temple-6"), stampAhead(t.noticeAt(3900.0, progress = 0.5 - 1100 / total, seconds = 0.0)))
        assertEquals(
            "temple 6 is inside the kilometre and temple 7 is the choice, but not two minutes on",
            emptyList<String>(),
            stampAhead(t.noticeAt(4033.0, progress = 0.5 - 967 / total, seconds = 120.0)),
        )

        val hit = t.noticeAt(4567.0, progress = 0.5 - 433 / total, seconds = HonorTuning.STAMP_QUIET_SECONDS)

        assertEquals(listOf("temple-7"), stampAhead(hit))
        assertEquals(1563.0, (hit.first() as Action.StampAhead).meters, 1.0)
    }

    // The spec's Android additions (Behavior rows 11, 12, 14, 15; annex A.3.2, A.3.4 probes)

    /** Row 11, annex probe 1: the nearest temple inside a kilometre is passed over, and a temple skipped never speaks later. */
    @Test
    fun `the nearest temple inside a kilometre is passed over for the next, and never speaks later`() {
        val t = stampTracker(listOf(temple(10, frac = 0.15), temple(11, frac = 0.9)), at = 15 to 0)
        val hit = t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)
        assertEquals(listOf("temple-11"), stampAhead(hit))
        assertEquals(0.8 * longGeometry.totalMeters, (hit.single() as Action.StampAhead).meters, 1.0)

        assertEquals(emptyList<String>(), stampAhead(t.noticeAt(1200.0, progress = 0.12, seconds = 600.0)))
        assertEquals(emptyList<String>(), stampAhead(t.noticeAt(2000.0, progress = 0.2, seconds = 1200.0)))
    }

    /** Row 12, annex probe 7: off the way nothing fires and no clock moves; the first fix back on the way speaks. */
    @Test
    fun `off the way no clock moves, and the first fix back on the way speaks`() {
        val t = stampTracker(listOf(temple(10, frac = 0.5)))

        t.noticeAt(1000.0, progress = 0.1, seconds = 0.0, onWay = false)

        val off = t.snapshot()
        assertEquals(listOf<Any?>(null, null, null, emptySet<String>()), listOf(off.lastNoticeSeconds, off.lastWaterSeconds, off.lastStampSeconds, off.firedStamps))
        assertEquals(listOf("temple-10"), stampAhead(t.noticeAt(1010.0, progress = 0.101, seconds = 30.0)))
    }

    /** Row 14, annex probe 4: after the close nothing is spoken or marked, so an unfired temple speaks in the next day's window. */
    @Test
    fun `after the office shuts nothing is spoken or marked, and an unfired temple speaks in the next day's window`() {
        var day = 12 to (17 to 0)
        val t = stampTrackerOn(Clock { tokyoMillis(day.second.first, day.second.second, day = day.first) }, listOf(temple(10, frac = 0.5)))

        assertEquals(emptyList<String>(), stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))
        assertEquals(emptySet<String>(), t.snapshot().firedStamps)
        assertNull(t.snapshot().lastStampSeconds)

        day = 13 to (15 to 0)
        assertEquals(listOf("temple-10"), stampAhead(t.noticeAt(1010.0, progress = 0.101, seconds = 79_200.0)))
    }

    /** Annex probe 3, kept upstream as deliberate (`HonorMomentTracker.swift:33-36@e551b11`): the window is the close's own day. */
    @Test
    fun `the window reads the closing time alone and never wraps past midnight`() {
        var clock = 23 to 30
        val office = HonorMomentTracker.StampOffice(closesMinutes = 30, zone = tokyo, clock = Clock { tokyoMillis(clock.first, clock.second) })
        val t = HonorMomentTracker(moments = listOf(temple(10, frac = 0.5)), geometry = longGeometry, voicesEnabled = false, stamp = office)

        assertEquals("23:30 is before a 00:30 close, but on the day before", emptyList<String>(), stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))
        clock = 0 to 10
        assertEquals(listOf("temple-10"), stampAhead(t.noticeAt(1010.0, progress = 0.101, seconds = 60.0)))
    }

    /** Row 15, the shared minute's documented cost (`HonorMomentTracker.swift:68-72@e551b11`), intended upstream. */
    @Test
    fun `a fountain 50 m ahead when a temple speaks is passed before the line frees, and never speaks`() {
        val t = stampTracker(listOf(temple(10, frac = 0.5)), marks = listOf(fountain(atKm = 1.05)))
        val heard = listOf(
            t.noticeAt(1000.0, progress = 0.1, seconds = 0.0),
            t.noticeAt(1040.0, progress = 0.104, seconds = 30.0),
            t.noticeAt(1080.0, progress = 0.108, seconds = 60.0),
        ).flatMap(::markAhead)
        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun `a restore brings back the temples spoken and every clock, keeping only temples it scans`() {
        val live = stampTracker(listOf(temple(10, frac = 0.5), temple(11, frac = 0.9)))
        live.noticeAt(1000.0, progress = 0.1, seconds = 42.0)
        val snapshot = live.snapshot()
        assertEquals(setOf("temple-10"), snapshot.firedStamps)
        assertEquals(listOf<Double?>(42.0, null, 42.0), listOf(snapshot.lastNoticeSeconds, snapshot.lastWaterSeconds, snapshot.lastStampSeconds))

        val revived = stampTracker(listOf(temple(10, frac = 0.5), temple(11, frac = 0.9)))
        revived.restore(snapshot.copy(firedStamps = snapshot.firedStamps + "wp-sjpp-pilgrim-office"))

        assertEquals(snapshot, revived.snapshot())
        assertEquals("temple 10 stays named", emptyList<String>(), stampAhead(revived.noticeAt(2000.0, progress = 0.2, seconds = 642.0)))
    }

    @Test
    fun `a tracker with no office restores no temples spoken`() {
        val live = stampTracker(listOf(temple(10, frac = 0.5)))
        live.noticeAt(1000.0, progress = 0.1, seconds = 0.0)

        val silent = stampTracker(listOf(temple(10, frac = 0.5)), at = null)
        silent.restore(live.snapshot())

        assertEquals(emptySet<String>(), silent.snapshot().firedStamps)
    }
```

- [ ] **Step 2: Write the failing engine tests**

In `T/domain/honor/HonorEngineTest.kt` add imports `java.time.ZoneId` and `org.walktalkmeditate.pilgrim.domain.honor.HonorEngineEvent.StampAhead`. Before `an empty Way is inert` (line 893):

```kotlin
    // iOS PR #91: a stage's stamp office (`HonorEngine.swift:90-105,335@e551b11`)

    private val shikokuHours = WayStampHours(opensMinutes = 480, closesMinutes = 1020)

    /** 10 km east along the equator, temple 10 at its middle, and [tz] the Way's zone. */
    private fun templeWay(tz: String?, hours: WayStampHours? = shikokuHours, moments: List<WayMoment> = emptyList()) = way(
        "pilgrimage:shikoku-88-awa:0",
        (0..10).map { i -> WayPoint(lat = 0.0, lon = i * 0.00898, alt = null, t = i * 600.0) },
        moments + WayMoment(
            id = "temple-10",
            frac = 0.5,
            at = WayCoordinate(lat = 0.0, lon = 5000.0 / 111_320),
            kind = WayMomentKind.Waypoint(label = "Kirihata-ji", icon = "seal"),
            text = "Temple 10 · Koyasan Shingon · stamp available (¥500)",
        ),
    ).copy(
        source = WaySource.Pilgrimage(routeId = "shikoku-88-awa", stageIndex = 0),
        tzIdentifier = tz,
        stampHours = hours,
    )

    /** 06:30 UTC on 2026-04-12: 15:30 in Tokyo, the office's window there, and nobody's in UTC. */
    private fun atHalfPastSixUtc() {
        nowMillis = Instant.parse("2026-04-12T06:30:00Z").toEpochMilli()
    }

    private fun templeEngine(way: Way, fallbackZone: ZoneId) =
        HonorEngine(way = way, softTapEnabled = false, voicesEnabled = false, clock = clock, fallbackZone = fallbackZone)

    private fun temples(): List<String> = events.filterIsInstance<StampAhead>().map { it.temple.id }

    /** Review Focus 5: the Way's own zone wins over the engine's. */
    @Test
    fun `a stage's stamp hours open the office in the Way's own zone, over the engine's`() {
        atHalfPastSixUtc()
        templeEngine(templeWay(tz = "Asia/Tokyo"), fallbackZone = ZoneId.of("UTC")).process(fix(lon = 0.0, at = 0.0))
        assertEquals(listOf("temple-10"), temples())

        events.clear()
        templeEngine(templeWay(tz = null), fallbackZone = ZoneId.of("UTC")).process(fix(lon = 0.0, at = 0.0))
        assertEquals("06:30 in the engine's UTC is nowhere near five", emptyList<String>(), temples())
    }

    /** iOS `way.tzIdentifier.flatMap(TimeZone.init(identifier:)) ?? .current`: a missing or unreadable zone is the phone's. */
    @Test
    fun `a Way with no zone, or one Java can't read, keeps the engine's fallback zone`() {
        atHalfPastSixUtc()
        for (tz in listOf(null, "Mars/Olympus_Mons")) {
            events.clear()
            templeEngine(templeWay(tz = tz), fallbackZone = ZoneId.of("Asia/Tokyo")).process(fix(lon = 0.0, at = 0.0))
            assertEquals("$tz", listOf("temple-10"), temples())
        }
    }

    /** No hours, no office: every Way but a Shikoku stage's, whatever its temples say. */
    @Test
    fun `a Way without stamp hours never builds an office`() {
        atHalfPastSixUtc()
        templeEngine(templeWay(tz = "Asia/Tokyo", hours = null), fallbackZone = ZoneId.of("Asia/Tokyo")).process(fix(lon = 0.0, at = 0.0))
        assertEquals(emptyList<String>(), temples())
    }

    @Test
    fun `the temple's event comes after the places a fix reaches, carrying its metres`() {
        atHalfPastSixUtc()
        val trailhead = WayMoment(
            id = "wp-trailhead",
            frac = 0.0,
            at = WayCoordinate(lat = 0.0, lon = 0.0),
            kind = WayMomentKind.Waypoint(label = "Ryozen-ji gate", icon = "mappin"),
        )
        val engine = templeEngine(templeWay(tz = "Asia/Tokyo", moments = listOf(trailhead)), fallbackZone = ZoneId.of("UTC"))

        engine.process(fix(lon = 0.0, at = 0.0))

        assertEquals(listOf(MomentReached::class, StampAhead::class), events.map { it::class })
        assertEquals(0.5 * engine.geometry.totalMeters, (events.last() as StampAhead).meters, 1.0)
    }
```

In `T/domain/honor/HonorGoldenTraceTest.kt`'s `golden()` (after line 618), the one arm the new event needs to compile, with no other change to the file:

```kotlin
            is HonorEngineEvent.StampAhead -> GoldenEvent("stampAhead", id = temple.id, meters = meters)
```

- [ ] **Step 3: Write the failing session and storage tests**

In `T/walk/honor/HonorHarness.kt`, add imports `java.time.LocalDateTime`, `java.time.ZoneId` and `org.walktalkmeditate.pilgrim.domain.honor.WayStampHours`, and in the companion after `stageWay` (line 247):

```kotlin
        /** What the importer reads from Shikoku's `{"opens":"08:00","closes":"17:00"}`. */
        val SHIKOKU_HOURS = WayStampHours(opensMinutes = 480, closesMinutes = 1020)

        /** A numbered fudasho at [lon], its number in `text` as the dataset writes it. */
        fun temple(number: Int, lon: Double) = WayMoment(
            id = "temple-$number",
            frac = lon / END_LON,
            at = WayCoordinate(lat = 0.0, lon = lon),
            kind = WayMomentKind.Waypoint(label = "Kirihata-ji", icon = "seal"),
            text = "Temple $number · Koyasan Shingon · stamp available (¥500)",
        )

        /** [stage] on Shikoku's clock: temple 10 at 0.95 of the line, 1,057 m from the trailhead, no water, Tokyo's zone. */
        fun templeStage(stampHours: WayStampHours? = SHIKOKU_HOURS) = stage(marks = emptyList()).copy(
            tzIdentifier = "Asia/Tokyo",
            moments = listOf(temple(10, 0.0095)),
            stampHours = stampHours,
        )

        /** [hour]:[minute] on 2026-04-12 in Tokyo, the temple's own clock. */
        fun tokyoMillis(hour: Int, minute: Int): Long =
            LocalDateTime.of(2026, 4, 12, hour, minute).atZone(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()
```

In `T/walk/honor/HonorSessionTest.kt`, add `import org.walktalkmeditate.pilgrim.data.honor.HonorNoticeKind`, and after `a cached tracker's second walk on the same stage starts with no water spoken` (line 694):

```kotlin
    // iOS PR #91: a temple ahead on a Shikoku stage (stamp-closing spec, Behavior, "Android split")

    private val templeStage = HonorHarness.templeStage()

    /** 15:30 in Tokyo, inside the office's last two hours; the stage names Tokyo, so nothing leans on the JVM's zone. */
    private fun atHalfPastThreeInTokyo() {
        h.clock.millis = HonorHarness.tokyoMillis(15, 30)
    }

    /** iOS `testTheStampNoticeBorrowsTheCaptionLineAndNothingElse` (`PilgrimageStageWalkTests.swift:375-394@e551b11`), its tap. */
    @Test
    fun `a temple ahead commits its STAMP row, then taps once with the water's tap, and writes nothing else`() = runBlocking {
        atHalfPastThreeInTokyo()
        val walk = h.startHonorWalk(templeStage)
        val ports = FakePorts()
        var noticesAtHaptic: List<HonorNoticeEntity>? = null
        ports.onWaterAhead = { runBlocking { noticesAtHaptic = notices(walk) } }
        val session = h.newSession(ports)

        session.begin(walk, trailhead())

        val notice = noticesAtHaptic!!.single()
        assertEquals(listOf<Any>(HonorNoticeKind.STAMP, "temple-10", h.clock.millis), listOf(notice.kind, notice.refId, notice.firedAt))
        assertEquals(0.95 * WayGeometry(templeStage.route).totalMeters, notice.meters, 1e-6)
        assertEquals(listOf("haptic water"), ports.calls.filter { it.startsWith("haptic") })
        val row = dao.getSession(walk.id)!!
        assertEquals(
            "the shared minute and the temples' ten minutes start at Begin; water's hour is still free",
            listOf<Double?>(0.0, null, 0.0),
            listOf(row.lastNoticeSeconds, row.lastWaterSeconds, row.lastStampSeconds),
        )
        assertTrue("a notice is never a card", dao.getCardStates(walk.id).isEmpty())
    }

    /** iOS `testAWayWithoutStampHoursPutsNothingOnTheLineAndTapsNothing` (`:396-412@e551b11`): no caption, no tap. */
    @Test
    fun `a temple on a Way without stamp hours writes no row and taps nothing`() = runBlocking {
        atHalfPastThreeInTokyo()
        val walk = h.startHonorWalk(HonorHarness.templeStage(stampHours = null))
        val ports = FakePorts()

        h.newSession(ports).begin(walk, trailhead())

        assertTrue(notices(walk).isEmpty())
        assertTrue(ports.calls.none { it.startsWith("haptic") })
    }

    /** Review Focus 3: a `:tracker` death after the row commits never speaks or taps the temple again. */
    @Test
    fun `a revival keeps the temple spoken and its clocks, so temple 10 never speaks twice`() = runBlocking {
        atHalfPastThreeInTokyo()
        val walk = h.startHonorWalk(templeStage)
        val firstProcess = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        h.newSession(FakePorts()).begin(walk, trailhead(), scope = firstProcess)
        val spoken = notices(walk).single()
        firstProcess.coroutineContext[Job]!!.cancelAndJoin()
        val revivedController = h.newController().also { it.restoreActiveWalk() }
        val ports = FakePorts()
        val revived = h.newSession(ports, arrivalRecorder = revivedController)
        assertEquals(HonorSessionStart.Started(revived = true), revived.begin(walk, trailhead(), revivedController))

        h.clock.millis += 700_000
        revived.tickNow()
        revived.walkTo(0.0)

        assertEquals("still 1,057 m ahead and past its ten minutes, but already named", listOf(spoken), notices(walk))
        assertTrue(ports.calls.none { it.startsWith("haptic") })
        val row = dao.getSession(walk.id)!!
        assertEquals(listOf<Double?>(0.0, null, 0.0), listOf(row.lastNoticeSeconds, row.lastWaterSeconds, row.lastStampSeconds))
    }

    @Test
    fun `a cached tracker's second walk on the same stage starts with no temple spoken`() = runBlocking {
        atHalfPastThreeInTokyo()
        val ports = FakePorts()
        val session = h.newSession(ports)
        val first = h.startHonorWalk(templeStage)
        session.begin(first, trailhead())
        h.controller.finishWalk()
        session.awaitIdle()
        assertTrue("the finalize took the notices with the live rows", notices(first).isEmpty())

        val second = h.startHonorWalk(templeStage)
        session.begin(second, trailhead())

        assertEquals(listOf("temple-10"), notices(second).map { it.refId })
        assertEquals(2, ports.calls.count { it == "haptic water" })
    }
```

In `T/walk/honor/HonorSessionStateTest.kt`, add imports `org.walktalkmeditate.pilgrim.domain.honor.HonorEngineEvent`, and two tests:

```kotlin
    @Test
    fun `the temples spoken are the walk's STAMP rows, and an unknown kind's row is ignored`() {
        val session = HonorSessionEntity(
            walkId = 1L,
            wayId = HonorHarness.STAGE_ID,
            sourceKind = HonorSourceKind.PILGRIMAGE,
            voicesEnabled = false,
            softTapEnabled = false,
        )
        val notices = listOf(
            HonorNoticeEntity(1L, HonorNoticeKind.WATER, "wp-osm-water-node1", meters = 280.0, firedAt = 5_000L),
            HonorNoticeEntity(1L, HonorNoticeKind.STAMP, "temple-10", meters = 1_057.5, firedAt = 9_000L),
            HonorNoticeEntity(1L, HonorNoticeKind.UNKNOWN, "wp-shrine-3", meters = 900.0, firedAt = 9_500L),
        )

        val tracker = session.engineSnapshot(rows = emptyList(), notices = notices).tracker

        assertEquals(setOf("wp-osm-water-node1") to setOf("temple-10"), tracker.firedMarks to tracker.firedStamps)
    }

    /** iOS's caption guard, moved to where Android plans the tap (`ActiveWalkViewModel+MarkPins.swift:69-73@e551b11`). */
    @Test
    fun `a temple ahead is a STAMP row only on a Way with hours, and only for a numbered temple`() {
        val way = HonorHarness.templeStage()
        val temple = way.moments.single()
        val event = HonorEngineEvent.StampAhead(temple, meters = 1_057.5)

        assertEquals(
            HonorNoticeEntity(1L, HonorNoticeKind.STAMP, "temple-10", meters = 1_057.5, firedAt = 9_000L),
            stampNotice(walkId = 1L, way = way, event = event, firedAt = 9_000L),
        )
        assertNull("no hours, no line, so no tap", stampNotice(1L, way.copy(stampHours = null), event, 9_000L))
        val zero = HonorEngineEvent.StampAhead(temple.copy(text = "Temple 0 · Koyasan Shingon"), meters = 1_057.5)
        assertNull("Temple 0 names no temple", stampNotice(1L, way, zero, 9_000L))
    }
```

In `T/data/ConvertersTest.kt`, replace lines 62-65 with:

```kotlin
    @Test
    fun `a temple notice's kind round trips through storage`() {
        assertEquals(
            HonorNoticeKind.STAMP,
            converters.stringToHonorNoticeKind(converters.honorNoticeKindToString(HonorNoticeKind.STAMP)),
        )
    }

    @Test
    fun `a notice kind a later build wrote reads as unknown instead of throwing`() {
        assertEquals(HonorNoticeKind.UNKNOWN, converters.stringToHonorNoticeKind("SHRINE"))
    }
```

In `T/data/honor/HonorDaoTest.kt`, change line 189 to `"VALUES ($walkId, 'SHRINE', 'wp-shrine-3', 1.0, 2)",`, and after that test add:

```kotlin
    @Test
    fun `a temple notice is stored by its name and read back as a temple`() = runTest {
        dao.insertNotice(HonorNoticeEntity(walkId, HonorNoticeKind.STAMP, "temple-10", meters = 1_057.5, firedAt = 9_000L))

        assertEquals(HonorNoticeKind.STAMP, dao.getNotices(walkId).single().kind)
        assertEquals(
            "STAMP",
            db.openHelper.writableDatabase.query("SELECT kind FROM honor_notices").use { it.moveToFirst(); it.getString(0) },
        )
    }
```

- [ ] **Step 4: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.domain.honor.HonorMomentTrackerTest' --tests 'org.walktalkmeditate.pilgrim.domain.honor.HonorEngineTest'`
Expected: FAIL to compile, "Unresolved reference 'StampOffice'" and "Unresolved reference 'StampAhead'".

- [ ] **Step 5: Implement the tunings and the tracker's watcher**

In `P/domain/honor/HonorTuning.kt`, after `NOTICE_GAP_SECONDS`:

```kotlin

    /**
     * How long before the stamp office shuts the notice is worth saying, from
     * 15:00 for a 17:00 close (`HonorTuning.swift:37-40@e551b11`).
     */
    const val STAMP_NOTICE_MINUTES = 120

    /** Nearer than this and the temple has stopped being a decision: the walker is already arriving (`:41-43`). */
    const val STAMP_MIN_AHEAD_METERS = 1000.0

    /**
     * The least walking between two temple notices (`:44-50`): Shikoku's
     * temples can stand barely a kilometre apart, and water's hour would
     * hold a 16:10 notice's successor past 17:00.
     */
    const val STAMP_QUIET_SECONDS = 600.0
```

In `P/domain/honor/HonorMomentTracker.kt`, add imports `java.time.Instant`, `java.time.ZoneId` and `org.walktalkmeditate.pilgrim.domain.Clock`. Append to the class doc (U23's text), before its closing ` */`:

```kotlin
 *
 * A Shikoku stage also has a [StampOffice] (iOS PR #91, `:193-223@e551b11`):
 * in the office's last [HonorTuning.STAMP_NOTICE_MINUTES], the nearest
 * numbered temple at least [HonorTuning.STAMP_MIN_AHEAD_METERS] ahead is
 * named, once, with [HonorTuning.STAMP_QUIET_SECONDS] of walking between
 * temples. A temple already named holds every later one until it is
 * inside the kilometre. The temple goes before the fountain on a fix, and
 * the shared minute then lets only the first speak.
```

Constructor (lines 18-24):

```kotlin
class HonorMomentTracker(
    moments: List<WayMoment>,
    marks: List<WayMark> = emptyList(),
    private val geometry: WayGeometry,
    private val voicesEnabled: Boolean,
    private val distance: HonorDistance = WGS84_HONOR_DISTANCE,
    /** The route's stamp office; null on every Way but a Shikoku stage's, and then no temple is ever named. */
    private val stamp: StampOffice? = null,
) {
```

In `Action`, after `MarkAhead` (line 34):

```kotlin

        /** A stamp temple [meters] ahead on the line, unrounded, with its office soon to shut. */
        data class StampAhead(val temple: WayMoment, val meters: Double) : Action()
```

After `Action` (line 35):

```kotlin

    /**
     * iOS `StampOffice` (`HonorMomentTracker.swift:21-45@e551b11`): when the
     * route's stamp office shuts, in minutes since midnight, and the wall
     * clock and zone to read that against, both fixed when the engine is
     * built.
     */
    class StampOffice(val closesMinutes: Int, val zone: ZoneId, private val clock: Clock) {

        /**
         * True from [HonorTuning.STAMP_NOTICE_MINUTES] before the office
         * shuts until it does, on the local minute of the day. Deliberately
         * reads the closing time alone, on the same day: every value the
         * dataset carries is 08:00–17:00, so `opens` never falls inside the
         * window and no close wraps past midnight.
         */
        val isClosingSoon: Boolean
            get() {
                val local = Instant.ofEpochMilli(clock.now()).atZone(zone)
                val minutesNow = local.hour * 60 + local.minute
                return minutesNow >= closesMinutes - HonorTuning.STAMP_NOTICE_MINUTES && minutesNow < closesMinutes
            }
    }
```

`Snapshot` gains its last field, after `lastStampSeconds`:

```kotlin
        /** The temples named, by moment id. */
        val firedStamps: Set<String>,
```

and its doc's first sentence becomes "What a revival needs back: the moments reached, the voices waiting in queue order, the water marks and temples spoken, and the notice clocks". After `marks` (line 83):

```kotlin

    /**
     * The stamp temples among the moments, in the order they are passed (the
     * frac-then-id sort above), empty whenever the route states no hours, so
     * the scan has nothing to scan on every Way but Shikoku's (`:97@e551b11`).
     */
    private val fudasho: List<WayMoment> = if (stamp == null) emptyList() else this.moments.filter { it.templeNumber != null }
    private val firedStamps = mutableSetOf<String>()
```

In `update`, replace `actions += waterAhead(progressFrac, activeSeconds, isOnWay)` (line 144) with:

```kotlin
        // The temple before the fountain: when both are due on one fix the
        // minute lets only the first speak, and of the two an office about to
        // shut is the one still worth a walker's afternoon (`:138-143@e551b11`).
        actions += stampAhead(progressFrac, activeSeconds, isOnWay)
        actions += waterAhead(progressFrac, activeSeconds, isOnWay)
```

`snapshot()` ends `lastStampSeconds = lastStampSeconds, firedStamps = firedStamps.toSet(),`. In `restore`, after `snapshot.firedMarks.filterTo(firedMarks) { it in watched }`:

```kotlin
        val temples = fudasho.mapTo(HashSet()) { it.id }
        firedStamps.clear()
        snapshot.firedStamps.filterTo(firedStamps) { it in temples }
```

and its doc's last sentence becomes "Moment ids this Way does not carry, and mark and temple ids it does not watch, are dropped." After `waterAhead`:

```kotlin
    /**
     * iOS `stampAhead` (`HonorMomentTracker.swift:193-223@e551b11`): the
     * nearest stamp temple still far enough ahead to be a choice, in the last
     * two hours its office is open. Once per temple, never one behind, never
     * off the way, never inside the minute after another notice, and ten
     * minutes of walking after the last temple. A temple behind or inside
     * the kilometre is passed over; the first one past it either speaks or,
     * if it was named already, ends the scan. A fact and never a forecast:
     * what the office does and how far it is.
     */
    private fun stampAhead(progressFrac: Double, activeSeconds: Double, isOnWay: Boolean): List<Action> {
        val office = stamp
        if (!isOnWay || fudasho.isEmpty() || !(geometry.totalMeters > 0) || office == null ||
            !isLineFree(activeSeconds) || !office.isClosingSoon
        ) {
            return emptyList()
        }
        val last = lastStampSeconds
        if (last != null && activeSeconds - last < HonorTuning.STAMP_QUIET_SECONDS) return emptyList()
        for (temple in fudasho) {
            val ahead = (temple.frac - progressFrac) * geometry.totalMeters
            // Behind them, or close enough that the walk has already decided.
            if (!(ahead >= HonorTuning.STAMP_MIN_AHEAD_METERS)) continue
            // The nearest choice, already named: the next is not news yet.
            if (temple.id in firedStamps) return emptyList()
            firedStamps += temple.id
            lastStampSeconds = activeSeconds
            lastNoticeSeconds = activeSeconds
            return listOf(Action.StampAhead(temple, meters = ahead))
        }
        return emptyList()
    }
```

- [ ] **Step 6: Implement the engine**

In `P/domain/honor/HonorEngine.kt`, add imports `java.time.DateTimeException` and `java.time.ZoneId`. After `MarkAhead` (line 32):

```kotlin

    /** A Shikoku temple [meters] ahead on the line, unrounded, its stamp office shutting within two hours (iOS PR #91). */
    data class StampAhead(val temple: WayMoment, val meters: Double) : HonorEngineEvent()
```

In the class doc (lines 47-49), the sentence beginning "[clock] is the wall clock" becomes "[clock] is the wall clock behind the 120 s re-acquire, its 10 s retry, the 120 s soft tap, and a Shikoku stage's stamp office, read only when a fix arrives." The constructor (lines 54-60):

```kotlin
class HonorEngine(
    val way: Way,
    val softTapEnabled: Boolean,
    voicesEnabled: Boolean,
    private val clock: Clock,
    private val distance: HonorDistance = WGS84_HONOR_DISTANCE,
    /**
     * The zone a stamp office reads when its Way names none Java can read:
     * iOS's `TimeZone.current` (`HonorEngine.swift:100-103@e551b11`),
     * captured once, here. A parameter so a test pins it without a
     * process-wide default (annex A.11 item 5).
     */
    fallbackZone: ZoneId = ZoneId.systemDefault(),
) {
```

The tracker (lines 96-102):

```kotlin
    private val moments = HonorMomentTracker(
        moments = way.moments,
        marks = way.marks ?: emptyList(),
        geometry = geometry,
        voicesEnabled = voicesEnabled,
        distance = distance,
        stamp = stampOffice(way, clock, fallbackZone),
    )
```

In `toEvent()` after the `MarkAhead` arm (line 382):

```kotlin
        is HonorMomentTracker.Action.StampAhead -> HonorEngineEvent.StampAhead(temple, meters)
```

In the private companion (after line 393):

```kotlin

        /**
         * iOS `stampOffice(for:now:)` (`HonorEngine.swift:95-105@e551b11`):
         * no hours, no office. The office keeps the Way's own zone where it
         * names one Java reads, and [fallbackZone] otherwise, which on
         * Shikoku is the same clock: no stage file names a zone.
         */
        fun stampOffice(way: Way, clock: Clock, fallbackZone: ZoneId): HonorMomentTracker.StampOffice? {
            val hours = way.stampHours ?: return null
            return HonorMomentTracker.StampOffice(
                closesMinutes = hours.closesMinutes,
                zone = zoneOf(way.tzIdentifier) ?: fallbackZone,
                clock = clock,
            )
        }

        /** `ZoneId.of` throws where iOS's `TimeZone(identifier:)` answers nil (`WayMomentCopy.wayZone`'s rule). */
        fun zoneOf(identifier: String?): ZoneId? = identifier?.let {
            try {
                ZoneId.of(it)
            } catch (_: DateTimeException) {
                null
            }
        }
```

- [ ] **Step 7: Implement the notice kind and the session's arm**

In `P/data/honor/HonorNoticeEntity.kt`, lines 9-14 become:

```kotlin
/**
 * What a notice spoke of. [WATER] names a `WayMark`; [STAMP] names a
 * numbered temple's `WayMoment` (iOS PR #91, `e551b11`). [UNKNOWN] is what a
 * later build's kind reads as here, and every reader ignores it. Stored by
 * name (`Converters`), so a new kind needs no migration.
 */
enum class HonorNoticeKind { WATER, STAMP, UNKNOWN }
```

and `refId`'s doc (line 41) becomes `/** The `WayMark.id` of a water notice; the temple's `WayMoment.id` of a stamp notice. */`. The header's "The water marks spoken are this walk's [HonorNoticeKind.WATER] rows" (line 19) becomes "The water marks and temples spoken are this walk's [HonorNoticeKind.WATER] and [HonorNoticeKind.STAMP] rows".

In `P/walk/honor/HonorSessionState.kt`, add imports `org.walktalkmeditate.pilgrim.domain.honor.HonorEngineEvent` and `org.walktalkmeditate.pilgrim.domain.honor.Way`. `engineSnapshot`'s doc becomes "The engine where this row, its moment rows, and its [notices] left it: the water and temples spoken are the walk's water and stamp notices, and an unknown kind's row is ignored", and its tracker ends:

```kotlin
        lastStampSeconds = lastStampSeconds,
        firedStamps = notices.filter { it.kind == HonorNoticeKind.STAMP }.mapTo(mutableSetOf()) { it.refId },
    ),
```

After `toEngineState`:

```kotlin
/**
 * The row a temple ahead writes, or null for none (iOS `showStampCaption`'s
 * guard, `ActiveWalkViewModel+MarkPins.swift:69-73@e551b11`): the walk's
 * Way must number the temple and state its hours, since without both there
 * is no caption, and iOS gives no tap without one
 * (`ActiveWalkViewModel+Honor.swift:208-215@e551b11`). `:tracker` plans the
 * tap, so the check is made here, on the staged Way the UI also reads
 * (stamp-closing spec, "Android split").
 */
internal fun stampNotice(walkId: Long, way: Way, event: HonorEngineEvent.StampAhead, firedAt: Long): HonorNoticeEntity? {
    if (event.temple.templeNumber == null || way.stampHours == null) return null
    return HonorNoticeEntity(
        walkId = walkId,
        kind = HonorNoticeKind.STAMP,
        refId = event.temple.id,
        meters = event.meters,
        firedAt = firedAt,
    )
}
```

In `P/walk/honor/HonorSession.kt`, in the class doc (lines 82-84) the clause becomes "a revival restores the anchor, the queue, and a stage's notices spoken and their clocks, never replays the voice the dead process was playing or a notice it spoke". `planEvents`' doc (line 523) becomes `/** iOS `handleHonorEvent` (`ActiveWalkViewModel+Honor.swift:175-212@7c200bf`; its `.stampAhead` arm, `:208-215@e551b11`), split into its writes and its effects. */`, and after the `MarkAhead` arm (line 554):

```kotlin
                    // The water's tap, deliberately, and only with a line to read (`ActiveWalkViewModel+Honor.swift:208-215@e551b11`).
                    is HonorEngineEvent.StampAhead -> stampNotice(walkId, way, event, firedAt = plan.now)?.let { notice ->
                        plan.notices += notice
                        plan.rituals += Ritual.WaterHaptic
                    }
```

- [ ] **Step 8: Show the clocks and notices in HONOR_DUMP**

In `app/src/debug/kotlin/org/walktalkmeditate/pilgrim/debug/honor/HonorDebugReceiver.kt`, in `sessionLines` after the `gates:` line (line 371):

```kotlin
            "  clocks: gap=${seconds(session.lastNoticeSeconds)} water=${seconds(session.lastWaterSeconds)} " +
                "temples=${seconds(session.lastStampSeconds)}",
```

in `dump`, after `lines += momentLines(...)` (line 350):

```kotlin
        lines += "  stamp hours: " + (way?.stampHours?.let { "opens ${it.opensMinutes} min, closes ${it.closesMinutes} min" } ?: "none")
        honorDao.getNotices(walk.id).forEach { lines += "  notice ${it.kind} ${it.refId} ${String.format(Locale.US, "%.0f", it.meters)} m" }
```

and after `frac` (line 403):

```kotlin
    private fun seconds(value: Double?): String = value?.let { String.format(Locale.US, "%.1f s", it) } ?: "none"
```

This is debug-only logging with no test of its own, as the dump's other lines; U29 reads it on the device.

- [ ] **Step 9: Run the tests, the golden traces included**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.domain.honor.*' --tests 'org.walktalkmeditate.pilgrim.walk.honor.*' --tests 'org.walktalkmeditate.pilgrim.data.ConvertersTest' --tests 'org.walktalkmeditate.pilgrim.data.honor.*'`
Expected: PASS: the 14 iOS stamp tests and the Android additions; `HonorGoldenTraceTest` and `HonorSessionWaterGoldenTest` with no expected file changed.

Then: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (the debug receiver compiles).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/ app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/HonorNoticeEntity.kt \
  app/src/main/java/org/walktalkmeditate/pilgrim/walk/honor/ app/src/debug/kotlin/org/walktalkmeditate/pilgrim/debug/honor/HonorDebugReceiver.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/domain/honor/ app/src/test/java/org/walktalkmeditate/pilgrim/walk/honor/ \
  app/src/test/java/org/walktalkmeditate/pilgrim/data/ConvertersTest.kt app/src/test/java/org/walktalkmeditate/pilgrim/data/honor/HonorDaoTest.kt
git commit -m "feat(honor): a temple ahead is named in its office's last two hours

Phase 22 U24 (iOS #91, e551b11): StampOffice and the temple watcher before
water, ten minutes between temples and a named temple holding the next;
StampAhead through the engine, which takes its fallback zone; a STAMP row
and water's tap only when the Way can caption it; revivals rebuild the
temples spoken.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task U25: The temple notice's words

**Files:**
- Modify: `P/ui/honor/WayMomentHeader.kt:1-40` (imports), after `:188` (new object)
- Modify: `R/values/strings.xml:1542`
- Create: `T/ui/honor/WayStampNoticeTest.kt`

**Interfaces:**
- Consumes: `WayRelation.distance(meters: Double, units: UnitSystem): String` (`P/ui/honor/WayPlaceCardState.kt:299-313`).
- Produces, in `org.walktalkmeditate.pilgrim.ui.honor`:
  - `object WayStampNotice`
  - `fun caption(resources: Resources, templeNumber: Int, closesMinutes: Int, meters: Double, units: UnitSystem, twelveHour: Boolean): String`
  - `fun hour(minutesSinceMidnight: Int, twelveHour: Boolean): String`
  - `fun isTwelveHourClock(context: Context): Boolean` (production)
  - `fun isTwelveHourClock(locale: Locale): Boolean` (tests, ICU `"j"`)
  - `R.string.honor_stamp_caption`.

- [ ] **Step 1: Write the failing tests**

Create `T/ui/honor/WayStampNoticeTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.walktalkmeditate.pilgrim.data.units.UnitSystem

/**
 * iOS `WayStampNotice` (`WayMomentHeader.swift:120-146@e551b11`): the
 * ports of `HonorWayRenderingTests.swift:143-173@e551b11`, which pass a
 * `Locale` where Android passes the 12-hour reading and the unit, then the
 * production reading of the walker's own 24-hour switch (annex A.11 item 2)
 * and the ASCII digits annex A.4.3 asks for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WayStampNoticeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resources = context.resources

    private fun caption(templeNumber: Int, meters: Double, units: UnitSystem, locale: Locale) = WayStampNotice.caption(
        resources,
        templeNumber = templeNumber,
        closesMinutes = 17 * 60,
        meters = meters,
        units = units,
        twelveHour = WayStampNotice.isTwelveHourClock(locale),
    )

    /** The line a walker on the henro reads, in their own clock and their own unit. */
    @Test
    fun testTheStampNoticeSpeaksTheWalkersClockAndUnit() {
        assertEquals("temple 10 stamps until 5 · 3.2 km", caption(10, 3200.0, UnitSystem.Metric, Locale.US))
        assertEquals("temple 10 stamps until 17 · 3.2 km", caption(10, 3200.0, UnitSystem.Metric, Locale.JAPAN))
        assertEquals("temple 88 stamps until 5 · 11.3 mi", caption(88, 18_244.0, UnitSystem.Imperial, Locale.US))
    }

    /** The hour comes from the data: 17:00 is "5" on a 12-hour clock and "17" on a 24-hour one, and a half hour says so. */
    @Test
    fun testTheClosingHourFollowsTheLocalesOwnClock() {
        for (twentyFour in listOf(Locale.JAPAN, Locale.UK)) {
            val twelveHour = WayStampNotice.isTwelveHourClock(twentyFour)
            assertEquals("$twentyFour", "17", WayStampNotice.hour(17 * 60, twelveHour))
            assertEquals("$twentyFour", "8", WayStampNotice.hour(8 * 60, twelveHour))
        }
        val twelve = WayStampNotice.isTwelveHourClock(Locale.US)
        assertEquals("5", WayStampNotice.hour(17 * 60, twelve))
        assertEquals("8", WayStampNotice.hour(8 * 60, twelve))
        assertEquals("noon is not zero", "12", WayStampNotice.hour(12 * 60, twelve))
        assertEquals("5:30", WayStampNotice.hour(17 * 60 + 30, twelve))
    }

    /** Review Focus 4: iOS's `Locale.current` carries the 24-Hour Time switch; Android's walker sets "Use 24-hour format". */
    @Test
    fun `the walker's own 24-hour switch decides the hour`() {
        ShadowSettings.set24HourTimeFormat(true)
        assertFalse(WayStampNotice.isTwelveHourClock(context))
        assertEquals("17", WayStampNotice.hour(17 * 60, WayStampNotice.isTwelveHourClock(context)))

        ShadowSettings.set24HourTimeFormat(false)
        assertTrue(WayStampNotice.isTwelveHourClock(context))
        assertEquals("5", WayStampNotice.hour(17 * 60, WayStampNotice.isTwelveHourClock(context)))
    }

    @Test
    fun `minutes show only when they aren't zero, two digits wide, and midnight reads 12 or 0`() {
        assertEquals("5:05", WayStampNotice.hour(17 * 60 + 5, twelveHour = true))
        assertEquals("8:30", WayStampNotice.hour(8 * 60 + 30, twelveHour = false))
        assertEquals("12", WayStampNotice.hour(0, twelveHour = true))
        assertEquals("0", WayStampNotice.hour(0, twelveHour = false))
    }

    /** Annex A.4.3: every argument is formatted before the resource, so a configuration's digits can't reach it. */
    @Test
    fun `the number, the hour and the distance stay ASCII under an Arabic configuration`() {
        val arabic = context.createConfigurationContext(
            Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag("ar")) },
        ).resources

        assertEquals(
            "temple 10 stamps until 5 · 3.2 km",
            WayStampNotice.caption(arabic, templeNumber = 10, closesMinutes = 17 * 60, meters = 3200.0, units = UnitSystem.Metric, twelveHour = true),
        )
    }

    /** iOS `max(0, meters.isFinite ? meters : 0)`. */
    @Test
    fun `a distance that isn't a number reads 0 m`() {
        assertEquals(
            "temple 10 stamps until 5 · 0 m",
            WayStampNotice.caption(resources, templeNumber = 10, closesMinutes = 17 * 60, meters = Double.NaN, units = UnitSystem.Metric, twelveHour = true),
        )
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.honor.WayStampNoticeTest'`
Expected: FAIL to compile, "Unresolved reference 'WayStampNotice'".

- [ ] **Step 3: Implement**

In `R/values/strings.xml`, after `honor_water_caption` (line 1542):

```xml
    <!-- iOS PR #91 (stamp-closing spec, UI, Strings): a Shikoku stage's temple notice, lowercase, with
         a U+00B7 middle dot. All three arguments arrive formatted (the number, the hour in Locale.US,
         the distance as WayDistance writes it), so a configuration can't change their digits. -->
    <string name="honor_stamp_caption">temple %1$s stamps until %2$s · %3$s</string>
```

In `P/ui/honor/WayMomentHeader.kt`, add imports `android.content.Context`, `android.icu.text.DateTimePatternGenerator` and `android.text.format.DateFormat`. After `WayStageLine` (line 188):

```kotlin

/**
 * iOS `WayStampNotice` (`WayMomentHeader.swift:120-146@e551b11`): "temple
 * 10 stamps until 5 · 3.2 km", the one line a stamp office may say, in the
 * walk screen's register, the distance in the walker's unit and the hour
 * in their own clock, with no meridiem marker. A fact, not a forecast.
 */
object WayStampNotice {

    fun caption(
        resources: Resources,
        templeNumber: Int,
        closesMinutes: Int,
        meters: Double,
        units: UnitSystem,
        twelveHour: Boolean,
    ): String = resources.getString(
        R.string.honor_stamp_caption,
        templeNumber.toString(),
        hour(closesMinutes, twelveHour),
        WayRelation.distance(if (meters.isFinite()) meters else 0.0, units),
    )

    /** 17:00 as "5" on a 12-hour clock and "17" on a 24-hour one; noon is 12; minutes only when they aren't zero. */
    fun hour(minutesSinceMidnight: Int, twelveHour: Boolean): String {
        val hour = minutesSinceMidnight / 60
        val minute = minutesSinceMidnight % 60
        val shown = if (twelveHour) (if (hour % 12 == 0) 12 else hour % 12) else hour
        return if (minute == 0) shown.toString() else String.format(Locale.US, "%d:%02d", shown, minute)
    }

    /** The walk screen's reading: the walker's "Use 24-hour format", as iOS's `Locale.current` carries its own switch. */
    fun isTwelveHourClock(context: Context): Boolean = !DateFormat.is24HourFormat(context)

    /** iOS's own test (`:143-145`), for tests: the locale's `"j"` skeleton marks a 12-hour clock with `a`, outside quoted text. */
    fun isTwelveHourClock(locale: Locale): Boolean =
        DateTimePatternGenerator.getInstance(locale).getBestPattern("j").replace(QUOTED_TEXT, "").contains('a')

    private val QUOTED_TEXT = Regex("'[^']*'")
}
```

- [ ] **Step 4: Run them to see them pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.honor.WayStampNoticeTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/WayMomentHeader.kt app/src/main/res/values/strings.xml \
  app/src/test/java/org/walktalkmeditate/pilgrim/ui/honor/WayStampNoticeTest.kt
git commit -m "feat(honor): the temple notice's words, in the walker's own clock

Phase 22 U25 (iOS #91, e551b11): WayStampNotice and honor_stamp_caption,
the hour from Use 24-hour format, every argument pre-formatted in ASCII.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task U26: The temple notice on the walk screen

**Files:**
- Modify: `P/ui/walk/HonorWalkViewModel.kt:204-228` (`HonorCaption`, `HonorSheetStats`), `:312-314`, `:411-415`, `:975-1011`
- Modify: `P/ui/walk/WalkStatsSheet.kt:421-436`, `:458-463`
- Create: `T/ui/walk/HonorCaptionTest.kt`
- Modify: `T/ui/walk/HonorWalkViewModelTest.kt` (call sites `:1308`, `:1491`, `:1566-1569`, `:1574`; new tests after `:1575`; helpers after `:1836`)
- Modify: `T/ui/honor/HonorOnWalkSemanticsTest.kt` (after `:664`)

**Interfaces:**
- Consumes: U24's `HonorNoticeKind.STAMP` rows; U20's `templeNumber`, `Way.stampHours`; U25's `WayStampNotice`.
- Produces:
  - `HonorCaption.Stamp(val templeNumber: Int, val closesMinutes: Int, val meters: Double)`.
  - `HonorCaption.of(notice: HonorNoticeEntity?, way: Way?): HonorCaption?` (companion).
  - `HonorCaptionCopy.text(resources: Resources, caption: HonorCaption, units: UnitSystem, twelveHour: Boolean): String`.

- [ ] **Step 1: Write the failing tests**

Create `T/ui/walk/HonorCaptionTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.honor.HonorNoticeEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorNoticeKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WayStampHours

/** What a notice row says on the walk's Way (iOS `showMarkCaption`, `showStampCaption`, `ActiveWalkViewModel+MarkPins.swift:62-73@e551b11`). */
class HonorCaptionTest {

    private val temple = WayMoment(
        id = "temple-10",
        frac = 0.9,
        at = null,
        kind = WayMomentKind.Waypoint(label = "Kirihata-ji", icon = "seal"),
        text = "Temple 10 · Koyasan Shingon · stamp available (¥500)",
    )

    private val way = Way(
        id = "pilgrimage:shikoku-88-awa:0",
        source = WaySource.Pilgrimage(routeId = "shikoku-88-awa", stageIndex = 0),
        title = "Ryozen-ji to Jizo-ji",
        departedAt = Instant.ofEpochSecond(1_000_000),
        tzIdentifier = null,
        expires = null,
        route = emptyList(),
        totalDistanceMeters = 0.0,
        theirActiveSeconds = 0.0,
        moments = listOf(temple),
        weather = null,
        stampHours = WayStampHours(opensMinutes = 480, closesMinutes = 1020),
    )

    private fun notice(kind: HonorNoticeKind, refId: String) =
        HonorNoticeEntity(walkId = 1L, kind = kind, refId = refId, meters = 3200.0, firedAt = 0L)

    @Test
    fun `a temple row reads its number and closing hour from the walk's Way, its metres from the row`() {
        assertEquals(HonorCaption.Stamp(templeNumber = 10, closesMinutes = 1020, meters = 3200.0), HonorCaption.of(notice(HonorNoticeKind.STAMP, "temple-10"), way))
    }

    /** The UI's repeat of `:tracker`'s guard: either value missing, and the line says nothing. */
    @Test
    fun `a temple row says nothing on a Way with no hours, for a moment the Way doesn't carry, or for Temple 0`() {
        val stamp = notice(HonorNoticeKind.STAMP, "temple-10")
        assertNull(HonorCaption.of(stamp, way.copy(stampHours = null)))
        assertNull(HonorCaption.of(notice(HonorNoticeKind.STAMP, "temple-11"), way))
        assertNull(HonorCaption.of(stamp, way.copy(moments = listOf(temple.copy(text = "Temple 0 · Koyasan Shingon")))))
        assertNull("no Way loaded yet", HonorCaption.of(stamp, null))
    }

    @Test
    fun `a water row is water, and an unknown kind or no row says nothing`() {
        assertEquals(HonorCaption.Water(3200.0), HonorCaption.of(notice(HonorNoticeKind.WATER, "wp-osm-water-node1"), way))
        assertNull(HonorCaption.of(notice(HonorNoticeKind.UNKNOWN, "wp-shrine-3"), way))
        assertNull(HonorCaption.of(null, way))
    }
}
```

In `T/ui/walk/HonorWalkViewModelTest.kt`, add `twelveHour = false` as the last argument of the seven existing `HonorCaptionCopy.text(...)` calls (lines 1308, 1491, 1566, 1567, 1568, 1569, 1574), add `import org.walktalkmeditate.pilgrim.domain.honor.WayStampHours`, and after `a water distance that isn't a number reads 0 m` (line 1575):

```kotlin
    // The temple caption (iOS PR #91; stamp-closing spec, UI)

    /** iOS `testTheStampNoticeBorrowsTheCaptionLineAndNothingElse` (`PilgrimageStageWalkTests.swift:375-394@e551b11`), the caption's half. */
    @Test
    fun testTheStampNoticeBorrowsTheCaptionLineAndNothingElse() = runTest(dispatcher) {
        startStageWalk(templeStageWay(WayStampHours(opensMinutes = 8 * 60, closesMinutes = 17 * 60)))
        val vm = viewModel()
        collectCards(vm)
        vm.state.awaitValue { it?.session != null }

        db.honorDao().insertNotice(stampNotice(meters = 3200.0, firedAt = clock.now()))

        val caption = vm.sheet.awaitValue { it?.caption != null }!!.caption!!
        assertEquals(HonorCaption.Stamp(templeNumber = 10, closesMinutes = 17 * 60, meters = 3200.0), caption)
        assertEquals(
            "temple 10 stamps until 5 · 3.2 km",
            HonorCaptionCopy.text(context.resources, caption, UnitSystem.Metric, twelveHour = true),
        )
        assertNull("a notice is never a card", vm.cards.value?.place)
    }

    /** iOS `testAWayWithoutStampHoursPutsNothingOnTheLineAndTapsNothing` (`:396-412@e551b11`), the caption's half. */
    @Test
    fun testAWayWithoutStampHoursPutsNothingOnTheLine() = runTest(dispatcher) {
        startStageWalk(templeStageWay(stampHours = null))
        val seen = MutableStateFlow<HonorNoticeEntity?>(null)
        val watched = object : HonorDao by db.honorDao() {
            override fun observeLatestNotice(walkId: Long): Flow<HonorNoticeEntity?> =
                db.honorDao().observeLatestNotice(walkId).onEach { seen.value = it }
        }
        val vm = viewModel(honorDao = watched)
        collectCards(vm)
        vm.state.awaitValue { it?.session != null }

        db.honorDao().insertNotice(stampNotice(meters = 3200.0, firedAt = clock.now()))
        withTimeout(5_000L) { seen.first { it?.kind == HonorNoticeKind.STAMP } }
        runCurrent()

        assertNull(vm.sheet.value?.caption)
    }

    /** Review Focus 3: a UI restart inside the 20 s shows what is left of them, then nothing. */
    @Test
    fun `after a UI restart a temple caption shows for what is left of its 20 s`() = runTest(dispatcher) {
        startStageWalk(templeStageWay(WayStampHours(opensMinutes = 480, closesMinutes = 1020)))
        db.honorDao().insertNotice(stampNotice(meters = 3200.0, firedAt = clock.now()))
        advanceTimeBy(15_000L)
        val vm = viewModel()
        collectCards(vm)

        val shown = vm.sheet.awaitValue { it?.caption != null }!!.caption
        advanceTimeBy(4_999L)
        runCurrent()
        val stillShown = vm.sheet.value!!.caption
        advanceTimeBy(1L)
        runCurrent()

        val stamp = HonorCaption.Stamp(templeNumber = 10, closesMinutes = 1020, meters = 3200.0)
        assertEquals(stamp, shown)
        assertEquals(stamp to null, stillShown to vm.sheet.value!!.caption)
    }

    @Test
    fun `a temple reads in the walker's clock and unit, a distance that isn't a number reading 0`() {
        val resources = context.resources
        val stamp = HonorCaption.Stamp(templeNumber = 88, closesMinutes = 17 * 60, meters = 18_244.0)

        assertEquals("temple 88 stamps until 5 · 11.3 mi", HonorCaptionCopy.text(resources, stamp, UnitSystem.Imperial, twelveHour = true))
        assertEquals("temple 88 stamps until 17 · 18.2 km", HonorCaptionCopy.text(resources, stamp, UnitSystem.Metric, twelveHour = false))
        assertEquals(
            "temple 88 stamps until 5 · 0 m",
            HonorCaptionCopy.text(resources, stamp.copy(meters = Double.NaN), UnitSystem.Metric, twelveHour = true),
        )
    }
```

and after `waterNotice` (line 1836):

```kotlin
    /** iOS's temple, added to [stageWay]'s own moments: Android reads it from the Way by the row's id (annex A.8.3). */
    private val temple10 = WayMoment(
        id = "temple-10",
        frac = 0.9,
        at = WayCoordinate(lat = 0.0, lon = 900.0 / 111_320),
        kind = WayMomentKind.Waypoint(label = "Kirihata-ji", icon = "seal"),
        text = "Temple 10 · Koyasan Shingon · stamp available (¥500)",
    )

    private fun templeStageWay(stampHours: WayStampHours?) =
        stageWay().let { it.copy(moments = it.moments + temple10, stampHours = stampHours) }

    /** `:tracker`'s temple notice, as it writes it before the tap. */
    private fun stampNotice(meters: Double, firedAt: Long) = HonorNoticeEntity(
        walkId = liveWalkId, kind = HonorNoticeKind.STAMP, refId = "temple-10", meters = meters, firedAt = firedAt,
    )
```

(`withTimeout` and `onEach` need `import kotlinx.coroutines.withTimeout` and `import kotlinx.coroutines.flow.onEach` if the file lacks them.)

In `T/ui/honor/HonorOnWalkSemanticsTest.kt`, add `import org.robolectric.shadows.ShadowSettings`, and after `a miles walker reads water in tenths of a mile` (line 664):

```kotlin
    /** Review Focus 4, at the draw site: the sheet reads the walker's switch when it draws the line. */
    @Test
    fun `a temple notice takes the third stat's place in the walker's 12-hour clock, and the Walk stats value reads it`() {
        ShadowSettings.set24HourTimeFormat(false)
        showSheet(HonorSheetStats(remainingMeters = 650.0, caption = HonorCaption.Stamp(10, 17 * 60, 3200.0), listening = null))

        composeRule.onNodeWithText("temple 10 stamps until 5 · 3.2 km", useUnmergedTree = true).assertExists()
        composeRule.onAllNodesWithText("Remaining", useUnmergedTree = true).assertCountEquals(0)
        val value = "${WalkFormat.duration(90_000L)}, ${WalkFormat.distance(250.0)}, temple 10 stamps until 5 · 3.2 km"
        composeRule.onNodeWithContentDescription("Walk stats")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
    }

    @Test
    fun `a walker on the 24-hour clock reads the office's close as 17`() {
        ShadowSettings.set24HourTimeFormat(true)
        showSheet(HonorSheetStats(remainingMeters = 650.0, caption = HonorCaption.Stamp(10, 17 * 60, 3200.0), listening = null))

        composeRule.onNodeWithText("temple 10 stamps until 17 · 3.2 km", useUnmergedTree = true).assertExists()
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.walk.HonorCaptionTest' --tests 'org.walktalkmeditate.pilgrim.ui.walk.HonorWalkViewModelTest' --tests 'org.walktalkmeditate.pilgrim.ui.honor.HonorOnWalkSemanticsTest'`
Expected: FAIL to compile, "Unresolved reference 'Stamp'" and "No parameter with name 'twelveHour' found".

- [ ] **Step 3: Implement the caption model**

In `P/ui/walk/HonorWalkViewModel.kt`, replace lines 204-218:

```kotlin
/**
 * The walk screen's one caption line, in the minimized bar's third stat for
 * 20 s (iOS `softTapCaption`; pilgrimage-stage spec P3 §7, P5 §6). A model
 * rather than a string; the sheet writes the words when it draws them.
 */
sealed interface HonorCaption {
    /** The soft tap's "off the way · N m", always metres (pilgrim-ios #109, matched); dark while nothing sets the preference. */
    @Immutable
    data class OffWay(val meters: Long) : HonorCaption

    /** A stage's "water in <distance>", in the walker's unit, as the engine measured it along the line. */
    @Immutable
    data class Water(val meters: Double) : HonorCaption

    /** A Shikoku stage's "temple 10 stamps until 5 · 3.2 km" (iOS PR #91): the number and close from the Way, the metres from the row. */
    @Immutable
    data class Stamp(val templeNumber: Int, val closesMinutes: Int, val meters: Double) : HonorCaption

    companion object {

        /**
         * What [notice] says on [way] (iOS `showMarkCaption` and
         * `showStampCaption`, `ActiveWalkViewModel+MarkPins.swift:62-73@e551b11`).
         * A temple's number and closing time are read from the walk's Way,
         * never the row, and with either missing the line says nothing: the
         * guard `:tracker` applied before it wrote the row, made again here.
         */
        fun of(notice: HonorNoticeEntity?, way: Way?): HonorCaption? {
            if (notice == null) return null
            return when (notice.kind) {
                HonorNoticeKind.WATER -> Water(notice.meters)
                HonorNoticeKind.STAMP -> {
                    val number = way?.moments?.firstOrNull { it.id == notice.refId }?.templeNumber
                    val closes = way?.stampHours?.closesMinutes
                    if (number != null && closes != null) Stamp(number, closes, notice.meters) else null
                }
                HonorNoticeKind.UNKNOWN -> null
            }
        }
    }
}
```

`HonorSheetStats.caption`'s doc (line 225) becomes `/** The latest notice's while it shows, else the soft tap's; a stage has no soft tap, so they never meet. */`, and `lastNotice`'s (line 312) becomes `/** The walk's latest notice as the caption last read it. */`.

The `sheet` combine (lines 411-415) becomes:

```kotlin
        combine(state, live, voiceNow, softTapMeters(), shownNotice()) { state, live, voice, softTap, notice ->
            if (state == null) return@combine null
            HonorSheetStats(
                remainingMeters = live?.let { (1 - it.session.progressFrac) * it.loaded.geometry.totalMeters },
                caption = HonorCaption.of(notice, live?.loaded?.way) ?: softTap?.let(HonorCaption::OffWay),
```

Replace lines 975-1011:

```kotlin
    /**
     * The walk's latest notice while its caption shows (iOS `showMarkCaption`
     * and `showStampCaption`; pilgrimage-stage spec P3 §7.3, P5 §6): the row
     * `:tracker` writes in the step that fires it, before its haptic, shown
     * while `0 ≤ now − firedAt < 20 s`, so a restarted UI shows what is left
     * of it, and a wall clock set back can't stretch it. A revived `:tracker`
     * never writes it again, so it never replays (P5 A2). A kind this build
     * doesn't know shows nothing; [HonorCaption.of] reads the rest against
     * the walk's Way.
     *
     * A screen back from the background starts from the last notice read,
     * worked out against the clock at once, so the sheet never waits on
     * Room with an expired caption still drawn.
     */
    private fun shownNotice(): Flow<HonorNoticeEntity?> =
        live.map { it?.walkId }.distinctUntilChanged()
            .flatMapLatest { walkId -> if (walkId == null) flowOf(null) else honorDao.observeLatestNotice(walkId) }
            .distinctUntilChanged()
            .onEach { lastNotice = it }
            .flatMapLatest(::whileShown)
            .onStart { emit(shownAt(lastNotice, clock.now())) }
            .distinctUntilChanged()

    private fun whileShown(notice: HonorNoticeEntity?): Flow<HonorNoticeEntity?> = flow {
        val now = clock.now()
        val shown = shownAt(notice, now)
        emit(shown)
        if (shown == null) return@flow
        delay(SOFT_TAP_CAPTION_MILLIS - (now - shown.firedAt))
        emit(null)
    }

    /** [notice] at [now]: a kind this build knows, fired less than 20 s before it and not after it. */
    private fun shownAt(notice: HonorNoticeEntity?, now: Long): HonorNoticeEntity? {
        if (notice == null || notice.kind == HonorNoticeKind.UNKNOWN) return null
        val age = now - notice.firedAt
        return notice.takeIf { age in 0 until SOFT_TAP_CAPTION_MILLIS }
    }
```

- [ ] **Step 4: Draw it**

In `P/ui/walk/WalkStatsSheet.kt`, add `import org.walktalkmeditate.pilgrim.ui.honor.WayStampNotice`, and replace lines 421-436:

```kotlin
/** The caption line's words, written when drawn, in the walker's unit and clock at that moment (Annex A.11 item 3). */
internal object HonorCaptionCopy {

    /**
     * "off the way · N m", always metres (pilgrim-ios #109, matched); "water
     * in 280 m" / "water in 0.2 mi" as iOS's `WayDistance` writes it; "temple
     * 10 stamps until 5 · 3.2 km" with the hour on the walker's [twelveHour]
     * clock. A non-finite or negative distance reads 0.
     */
    fun text(resources: Resources, caption: HonorCaption, units: UnitSystem, twelveHour: Boolean): String = when (caption) {
        is HonorCaption.OffWay -> resources.getString(R.string.honor_soft_tap_caption, caption.meters.toString())
        is HonorCaption.Water -> resources.getString(
            R.string.honor_water_caption,
            WayRelation.distance(if (caption.meters.isFinite()) caption.meters else 0.0, units),
        )
        is HonorCaption.Stamp -> WayStampNotice.caption(
            resources,
            templeNumber = caption.templeNumber,
            closesMinutes = caption.closesMinutes,
            meters = caption.meters,
            units = units,
            twelveHour = twelveHour,
        )
    }
}
```

In `HonorMinimizedContent`, replace line 462:

```kotlin
    val resources = LocalResources.current
    val context = LocalContext.current
    val caption = honor.caption?.let { HonorCaptionCopy.text(resources, it, units, WayStampNotice.isTwelveHourClock(context)) }
```

- [ ] **Step 5: Run them to see them pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.walk.*' --tests 'org.walktalkmeditate.pilgrim.ui.honor.*'`
Expected: PASS, the existing water caption tests included.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/HonorWalkViewModel.kt app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/ app/src/test/java/org/walktalkmeditate/pilgrim/ui/honor/HonorOnWalkSemanticsTest.kt
git commit -m "feat(honor): a temple ahead takes the walk screen's caption line

Phase 22 U26 (iOS #91, e551b11): HonorCaption.Stamp, resolved from the latest
STAMP row against the walk's Way, shown for what is left of its 20 s and
drawn in the walker's 12- or 24-hour clock.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task U27: The whole branch, the golden traces run on their own

**Files:**
- Modify: `app/src/test/resources/honor/golden/README.md:20-26` (one row in "The capture" table)

**Interfaces:**
- Consumes: U20–U26.
- Produces: nothing new; a gate.

- [ ] **Step 1: Run the golden traces explicitly**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.domain.honor.HonorGoldenTraceTest' --tests 'org.walktalkmeditate.pilgrim.walk.honor.HonorSessionWaterGoldenTest'`
Expected: PASS, every trace and W10's revival.

- [ ] **Step 2: Prove the corpus and the golden test are as they were**

```bash
git diff --stat main -- app/src/test/resources/honor/golden/corpus app/src/test/resources/honor/golden/expected app/src/test/resources/honor/golden/capture
git diff main -- app/src/test/java/org/walktalkmeditate/pilgrim/domain/honor/HonorGoldenTraceTest.kt
```

Expected: the first prints nothing; the second shows exactly one added line, the `is HonorEngineEvent.StampAhead -> GoldenEvent("stampAhead", id = temple.id, meters = meters)` arm.

- [ ] **Step 3: Record in the harness's README why it wasn't recaptured**

In `app/src/test/resources/honor/golden/README.md`, add a row after `| Captured | … |` (line 23):

```markdown
| Not recaptured | Android 2.1.0 moved the parity pin to `e551b11` (iOS PR #91) and `3b78728` without a recapture, by the owner's decision of 2026-10-29: these traces still pin water and own walks as they were, and the temple notice is pinned by unit tests (`docs/plans/2026-10-29-002-feat-temple-stamp-hours-port-plan.md`) |
```

- [ ] **Step 4: Run everything**

Run, in turn:
- `./gradlew testDebugUnitTest`
- `./gradlew compileReleaseUnitTestKotlin testReleaseUnitTest --tests 'org.walktalkmeditate.pilgrim.core.flags.*'`
- `./gradlew lintDebug`
- `./gradlew assembleDebug`

Expected: each BUILD SUCCESSFUL, no new lint error.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/resources/honor/golden/README.md
git commit -m "docs(honor): the golden traces stay at 7c200bf for 2.1.0, by decision

Phase 22 U27: the temple notice is pinned by unit tests; every existing
trace passes unchanged.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task U28: The 2.1.0 Play notes

**Files:**
- Create: `app/src/main/play/release-notes/en-US/whatsnew.txt`
- Modify: `app/src/main/play/release-notes/en-US/default.txt` (all of it)

**Interfaces:**
- Consumes: nothing.
- Produces: the curated Play "What's new" the release workflow publishes.

How the versions move, so nobody edits them by hand: `production.yml`, dispatched with input `version` = `2.1.0`, sets `versionName` with `sed`, sets `versionCode` to `git rev-list --count HEAD`, runs `scripts/release-notes.sh` (which copies a present `whatsnew.txt` over `default.txt` and deletes it; with no `whatsnew.txt` it regenerates `default.txt` from commit subjects since `v2.0.0`), publishes to Production at 20 %, then commits `chore(release): bump to v2.1.0 (code N) [skip ci]` with `app/build.gradle.kts` and the notes folder, and tags `v2.1.0`. That is what `f59d98b4` did for 2.0.0. `internal.yml` bumps the same two values for its build and commits nothing. So this task writes both files, with the same text.

- [ ] **Step 1: Write the notes**

Write exactly this, ending in one newline, to both `app/src/main/play/release-notes/en-US/whatsnew.txt` and `app/src/main/play/release-notes/en-US/default.txt` (replacing the 2.0.0 text):

```text
• A link can open one stage of a pilgrimage: tap it and Pilgrim lands on that day's walk, ready to begin
• A new pilgrimage to walk in Honor: the Gudbrandsdalsleden, the St. Olav way from Oslo to Nidaros, in 24 stages
• On Shikoku, a temple ahead tells you when its stamp office closes at five, so the day's last seal isn't missed
• Walk well.
```

- [ ] **Step 2: Check the length and that the two match**

```bash
cmp app/src/main/play/release-notes/en-US/whatsnew.txt app/src/main/play/release-notes/en-US/default.txt
python3 -c "print(len(open('app/src/main/play/release-notes/en-US/default.txt', encoding='utf-8').read()))"
wc -c < app/src/main/play/release-notes/en-US/default.txt
```

Expected: `cmp` prints nothing; `344` characters (Play's limit is 500); `352` bytes, under the 500 that `scripts/release-notes.sh` measures with `wc -c`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/play/release-notes/en-US/whatsnew.txt app/src/main/play/release-notes/en-US/default.txt
git commit -m "docs(release): Android 2.1.0 Play notes

Phase 22 U28: stage links, the St. Olav way, and Shikoku's stamp offices.
whatsnew.txt is the curated copy release-notes.sh consumes at the release;
default.txt carries the same text until then. Versions are the release
workflow's to set.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task U29: The owner's device pass

**Files:**
- Create: `docs/qa/2026-10-29-temple-stamp-hours-qa.md`

**Interfaces:**
- Consumes: U20–U26 on a debug build, HONOR_DUMP's U24 lines, the Stage 21-2 replay harness.
- Produces: the checklist the owner runs before the 2.1.0 release.

The repo has a mock-location replay (`HonorDebugReceiver`'s HONOR_REPLAY_START) and no debug clock, so the window rows move the phone's time zone.

- [ ] **Step 1: Write the checklist**

Create `docs/qa/2026-10-29-temple-stamp-hours-qa.md` with exactly:

````markdown
# A temple ahead stamps until five: the device pass (U29)

Device: the owner's phone. Owner: user. Build: the top of `feat/temple-stamp-hours`, debug variant (`org.walktalkmeditate.pilgrim.debug`), Honor flag on.

This is plan unit U29 (`docs/plans/2026-10-29-002-feat-temple-stamp-hours-port-plan.md`). The spec is `docs/parity/2026-10-28-stamp-closing-port.md`; each row names the unit it checks.

**Schema 13.** This build's first launch migrates Room 12 → 13 on the phone. From then on schema 13 is frozen: any later change is schema 14 with a migration.

**Run § D first.** It starts from a clean install of the 2.0.0 debug build, and `adb uninstall` wipes the debug app's walks.

## Setup

- `P=org.walktalkmeditate.pilgrim.debug` and `A=org.walktalkmeditate.pilgrim.debug.HONOR_`.
- Logs: `adb logcat -s HonorDebug HonorSession HonorFinalizer WayReplayer WalkTrackingService`.
- Mock location and kills: Phase 21 U20's setup (`docs/qa/2026-09-30-honor-architecture-proof.md` § Setup). `adb shell ps -A -o PID,NAME | grep pilgrim` gives both process ids; kill `:tracker` with `adb shell run-as $P kill -9 <pid>`, the UI with `adb shell 'run-as org.walktalkmeditate.pilgrim.debug kill -9 $(pidof org.walktalkmeditate.pilgrim.debug)'`.
- **The route:** download `shikoku-88-awa` from the catalog ("A pilgrimage"). Its `route.json` states `{"opens":"08:00","closes":"17:00"}`.
- **A stage's temples and their fracs**, and whether its Way carries the hours:
  `adb exec-out run-as $P cat 'no_backup/Ways/pilgrimage:shikoku-88-awa:0/way.json' | python3 -c 'import json,sys; w=json.load(sys.stdin); print("stampHours", w.get("stampHours")); print("km", round(w["totalDistanceMeters"]/1000, 2)); [print(m["id"], round(m["frac"], 4), m.get("text", "")[:12]) for m in w["moments"] if m.get("text", "").startswith("Temple ")]'`
- **A starting frac:** `from = (temple frac) − 3 ÷ (the stage's km)` puts that temple 3 km ahead at the first fix.
- **The walk:** Begin from the stage's page (the morning card's "walk", then Start), then `adb shell am broadcast -p $P -a ${A}REPLAY_START --es way pilgrimage:shikoku-88-awa:0 --ef pace 1.4 --ef from <frac>`. Stop with `${A}REPLAY_STOP` after Finish. A replay dies with a killed `:tracker`; start it again from the progress frac HONOR_DUMP reports.
- **The office's clock is the phone's time zone** (no stage file names one). There is no debug clock, so move the zone and keep the real time, which the downloads need: Settings → System → Date & time → "Set time zone automatically" off, then pick a zone whose local time is the one the row needs (`adb shell date` shows it). Set the zone **before Begin**: the engine reads it when it is built, at Begin and at a `:tracker` revival. Put it back to automatic at the end.
- **State:** `adb shell am broadcast -p $P -a ${A}DUMP` logs `clocks: gap=… water=… temples=…`, `stamp hours: …`, and one `notice STAMP temple-N … m` line per temple spoken.
- **The 24-hour switch:** `adb shell settings put system time_12_24 24` (24-hour) or `12` (12-hour).

## A. Before 15:00 (U24: the window opens at 15:00)

| # | Check | Result |
|---|---|---|
| A1 | Zone set so local time is about 14:50. Begin with a temple 3 km ahead. Until local 15:00: no caption, no tap, and HONOR_DUMP shows no `notice` line and every clock `none`. | — |
| A2 | On the first fix at or after 15:00: "temple N stamps until 5 · x.x km" in the bar's third stat, one soft tap (the water's), gone after 20 s. HONOR_DUMP: one `notice STAMP`, `gap` and `temples` equal, `water=none`. | — |

## B. Between 15:00 and 17:00 (U24, U26)

| # | Check | Result |
|---|---|---|
| B1 | Zone set to about 15:30 local. Each temple speaks once; walking on past it never repeats it. | — |
| B2 | A temple less than 1 km ahead at the moment another would speak is passed over, and the next one is named. | — |
| B3 | Two temples about a kilometre apart (Awa stage 0 from about 15 km: temples 6 and 7): the second speaks no sooner than ten minutes after the first, and before the walker reaches it. Record the two times. | — |
| B4 | Settings → units to miles: the next temple caption reads "· x.x mi". | — |
| B5 | A Camino stage (`camino-frances`) walked at 15:30: no temple caption, no extra tap; its water notices still speak. | — |
| B6 | TalkBack: the caption is read as the "Walk stats" value's third part; no separate announcement. | — |

## C. After 17:00 (U24: the window shuts with the office)

| # | Check | Result |
|---|---|---|
| C1 | Zone set to about 17:05 local. Ten minutes of walking with temples ahead: no caption, no tap; HONOR_DUMP shows no `notice STAMP` and `temples=none`. | — |

## D. A route 2.0.0 installed (U22: the launch hand-down; U23: schema 13)

| # | Check | Result |
|---|---|---|
| D1 | `adb uninstall $P`. Build 2.0.0's debug app: `git worktree add ../pilgrim-android-v200 v2.0.0`, copy `local.properties` into it, `./gradlew :app:assembleDebug` there, `adb install` its APK. Download `shikoku-88-awa`, and save its maps. The stage `way.json` (Setup's one-liner) prints `stampHours None`. | — |
| D2 | Note the mtimes: `adb shell run-as $P stat -c '%Y %n' 'no_backup/Ways/pilgrimage:shikoku-88-awa:0/way.json' 'no_backup/Ways/pilgrimage:shikoku-88-awa:0/accepted.json'`. Install this branch's debug build over it with `adb install -r`, and launch. Every stage's `way.json` now carries `"stampHours":{"closesMinutes":1020,"opensMinutes":480}`; `accepted.json`'s mtime is unchanged. | — |
| D3 | After that launch: the route page shows no "update ready", its maps still read "maps saved", and the journal's walks are all there (schema 13 migrated). | — |
| D4 | A second cold launch writes nothing: the `way.json` mtimes stay as D2 left them. | — |
| D5 | Walk a temple at 15:30 on the upgraded route: it speaks, so the hand-down reaches the walk. Then `git worktree remove ../pilgrim-android-v200`. | — |

## E. Kills (U24, U26)

| # | Check | Result |
|---|---|---|
| E1 | Kill `:tracker` right after a temple speaks. After the revival (logcat `revived`), restart the replay from HONOR_DUMP's frac: that temple never speaks or taps again; HONOR_DUMP's three clocks are what they were before the kill. | — |
| E2 | Kill `:tracker` two minutes after a temple notice, with the next temple more than 1 km ahead: after the revival the next still waits out the ten minutes (B3's spacing holds across the kill). | — |
| E3 | Kill the UI process inside a temple caption's 20 s: on return the rest of the 20 s shows, then nothing. | — |

## F. 12-hour and 24-hour (U25, U26)

| # | Check | Result |
|---|---|---|
| F1 | `time_12_24 12`: "until 5". | — |
| F2 | `time_12_24 24`: "until 17". | — |
| F3 | Flip the switch while a temple caption shows: record whether the hour follows within a second (annex A.11 item 3: drawn when drawn). | — |
````

- [ ] **Step 2: Commit**

```bash
git add docs/qa/2026-10-29-temple-stamp-hours-qa.md
git commit -m "docs(qa): the temple stamp hours device pass

Phase 22 U29: the window's three times by the phone's zone, a route 2.0.0
installed getting its hours at launch, the kills, and both clocks.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## For the Parity Gate

Android additions to record, each with its reason (spec, "Android additions to record at the gate", extending annex A.11):
1. The temple state survives a `:tracker` death: `STAMP` rows and three clock columns. iOS keeps them in memory and never resumes.
2. The 24-hour reading is `DateFormat.is24HourFormat`, iOS's `Locale.current` switch's platform equivalent; the tests' form is ICU `"j"`.
3. The caption is composed when drawn, so a units or clock change inside its 20 s shows at once.
4. The office's zone is re-read on a `:tracker` revival; iOS reads it once per walk.
5. The engine takes its fallback zone as a parameter: a test seam with no behaviour change.
6. The download test asserts the hours land on every staged stage; iOS has no such test.
7. Notice kinds are read leniently (`UNKNOWN` ignored).
8. The launch hand-down runs on IO under the package actor, not the main thread.
9. A live walk is unaffected by a restamp: it walks the copy staged at Begin.
10. The tap can lead the caption on screen by a beat, as water's already does.
11. The restamp's per-stage failure is swallowed without a log (the package logs nothing); iOS prints it.
12. iOS's `"stageCount": "2"` refusal is A9 on Android, already recorded.
13. `templeNumber` reads Swift's characters, so a digit carrying a combining mark ends the number, as on iOS.
