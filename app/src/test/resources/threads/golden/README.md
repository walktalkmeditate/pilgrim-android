# Thought Threads golden fixture (Task U11)

Cross-platform golden dossier fixture: a synthetic corpus, rendered once
through the real iOS `ThreadsDossierBuilder` at the parity pin, pinned
against the same corpus rendered through the real Android pipeline in
`ThreadsGoldenDossierTest.kt`.

## Contents

- `corpus/fixture.json` — the full structured fixture: six walks (five
  historical + the current walk under test), recording metadata (UUIDs,
  timestamps, words-per-minute, route fixes), altitude samples, one photo,
  the moon-line state, and the `sensesDesignNotes` block explaining which
  of the eight senses this corpus does and does not exercise, and why.
- `corpus/transcripts/*.txt` — the eight recording transcripts, referenced
  by `fixture.json`'s `transcriptFile` fields. **Every word here was
  invented for this fixture.** None of it was ever spoken into the app,
  recorded on a device, or derived from any real walk — see "Synthetic
  corpus constraint" below.
- `expected/current-walk-dossier.txt` — the dossier text `ThreadsDossierBuilder.build`
  produced for the **current** walk (`walk-current`) at the iOS pin,
  captured verbatim via the procedure below. This is the golden text
  `ThreadsGoldenDossierTest` asserts Android's own real pipeline reproduces
  (modulo the three documented, principled allowances — see that test).
- `capture-harness.patch` — a `git diff` of the standalone XCTest harness
  (`UnitTests/ThreadsGoldenCaptureHarness.swift`) and its four-entry
  `project.pbxproj` registration, taken inside a throwaway worktree. Never
  committed to `pilgrim-ios`; kept here so a future re-pin can replay the
  exact capture without re-deriving it from scratch.

## Synthetic corpus constraint (review-mandated)

Every transcript under `corpus/transcripts/` is **hand-authored**, never
sourced from any real recorded walk — including the implementer's own.
Real reflective speech, once it lands in this repository's git history, is
a permanent privacy exposure outside every control the app itself offers
(the on-device Threads feature this fixture tests is explicitly designed
so a walker's own words never leave their device). This constraint applies
to every future refresh of this fixture, not just its first authoring:
**never replace, extend, or "improve" these transcripts with real
recorded/transcribed speech, even anonymized or paraphrased.**

## Capture procedure

**Pin:** `pilgrim-ios` @ `0172e2b` (the `v1.11.0` tag), per this repo's
`.superpowers/sdd/phase20/u11-brief.md` Global Constraints.

**Environment used for this capture (2026-08-26):**
- macOS 26.6.1 (build 25G76)
- Xcode 26.6 (build 17F113)
- iOS Simulator runtime 26.5 (23F77), device "iPhone 17"

Apple's on-device NL models (`NLTagger`, `NLEmbedding`, `NLLanguageRecognizer`)
are deterministic **per OS release** — a re-pin on a different macOS/Xcode/
simulator-runtime combination should re-run this capture rather than assume
the existing `expected/` text still matches, even though nothing in this
fixture actually depends on the embedding-based `related()` path (see
"Capture method" below for why).

**Steps to reproduce or refresh:**

1. Create a detached worktree at the pin (or the new pin being refreshed to)
   — **never work directly in the `pilgrim-ios` checkout**:
   ```
   git -C <path-to-pilgrim-ios> worktree add /tmp/pilgrim-ios-golden <PIN> --detach
   ```
2. Apply `capture-harness.patch` inside the worktree (`git apply` from the
   worktree root), or hand-copy `UnitTests/ThreadsGoldenCaptureHarness.swift`
   from this patch and re-do the four-entry `project.pbxproj` registration
   (`PBXBuildFile`, `PBXFileReference`, group-children, Sources-build-phase
   — see the patch for exact insertion points; `plutil -lint` the pbxproj
   after editing).
3. If any transcript, walk timestamp, or coordinate in `corpus/fixture.json`
   changed, update the harness's literal copies to match (the harness
   deliberately does NOT read `fixture.json` at runtime — it is a
   standalone file with no dependency on this repo's build system — so the
   two must be kept in sync by hand; a checklist diff between the two is
   cheap given the fixture's small size).
4. Run just the harness test:
   ```
   xcodebuild -workspace Pilgrim.xcworkspace -scheme Pilgrim -sdk iphonesimulator \
     -destination 'platform=iOS Simulator,name=iPhone 17' \
     -only-testing:UnitTests/ThreadsGoldenCaptureHarness test
   ```
   (`-only-testing:UnitTests/<HarnessClass>` per the task brief; a
   `-destination` was added here to pin the simulator explicitly rather than
   relying on whichever device xcodebuild picks by default.)
5. Copy the text between the `===GOLDEN-DOSSIER-BEGIN===` /
   `===GOLDEN-DOSSIER-END===` markers in xcodebuild's own console output
   into `expected/current-walk-dossier.txt` (a `sed -n '<begin+1>,<end-1>p'`
   over the captured log is exact and avoids terminal-copy corruption of
   the em dashes / curly quotes / multiplication sign the templates use).
   The harness also best-effort writes the same text to
   `/tmp/pilgrim-ios-golden-capture-output.txt` as a convenience fallback —
   stdout via xcodebuild's log is the authoritative capture path.
6. Remove the worktree:
   ```
   git -C <path-to-pilgrim-ios> worktree remove /tmp/pilgrim-ios-golden --force
   ```
7. Re-run `ThreadsGoldenDossierTest` on the Android side. Any diff the test
   surfaces against the refreshed `expected/` text is either a genuine
   Android regression (fix Android) or a real iOS behavior change (update
   the corpus design notes / allowances and document why in that PR — never
   silently loosen an assertion to make a real divergence disappear).

## Capture method: full pipeline, not the narrowed fallback

The brief's own contingency allows a narrower fallback — capturing via
`ThreadsDossierFormatter`'s pure functions fed hand-constructed
`TranscriptContext` values, if driving the real analyzer proved too
entangled with CoreData. That fallback was **not needed**. Reading
`Pilgrim/Models/Threads/ThreadsDossierBuilder.swift` at the pin showed its
`build(walkUUID:recordings:walkIndex:store:senses:resolveRouteFix:defaults:)`
overload is already a CoreData-free, fully-injectable static function (this
is the exact overload `UnitTests/ThreadsDossierTests.swift` already
exercises in the shipped iOS test suite) — `recordings` is a plain
`[RecordingContext]` value-type array, `walkIndex`/`store`/`senses`/
`resolveRouteFix`/`defaults` are all constructor-injected value types or
closures, and the only two remaining global reads (`UserPreferences
.threadsAfterWalks` and `ThreadsBackfill.isComplete`, itself a bare
`UserDefaults.standard.bool(forKey:)` read) are forced via the same
save-mutate-restore pattern that file's own tests already use.

The harness therefore drives the **real, unmodified**:
- `TranscriptContextAnalyzer.analyzeAndStore` (real `NLTagger`/`NLEmbedding`/
  `NLLanguageRecognizer` — the actual theme extraction, marker computation,
  and sentiment scoring), seeding the five historical recordings' contexts
  into a real `TranscriptContextStore` over a throwaway temp directory;
- `ThreadStore.build` (real, pure aggregation);
- `ThreadsDossierFormatter.dossier` (real, pure rendering); and
- `DossierSenses.lines` (real, pure — evaluated via the SAME
  `ThreadsDossierBuilder.build` call, not invoked separately), fed a
  hand-built `DossierSensesFetchBundle` (walk snapshots, elevation samples,
  photo, recording timestamps, the closed lunation from a REAL
  `LunationCalendar.mostRecentClosed` call) and a hand-built
  `resolveRouteFix` closure backed by an exact-timestamp lookup table.

The **only** thing NOT exercised is the CoreData/`DataManager`
orchestration layer that, in production, gathers those same inputs from a
live `Walk`/`VoiceRecording` object graph — `gatherSensesBundle`,
`DataManager.walkSensesSnapshot`, `DataManager.voiceRecordingTimestampIndex`,
`DataManager.routeFixNear`. That layer has no Android counterpart to pin
against in the first place (Android's own DAOs are a completely different
implementation over Room, already covered by Android's own
`ThreadsDossierBuilderTest`/`ThreadsDeletionHygieneTest` suites) — pinning
it would test CoreData query correctness, not iOS/Android engine parity.

**Net claim:** this fixture pins **analyzer + aggregation + formatter +
senses** equivalence — the entire algorithmic surface the parity specs
(`docs/parity/2026-08-25-threads-engine-port.md`,
`docs/parity/2026-08-26-threads-senses-port.md`) describe — not merely
formatter-and-store equivalence. `NLEmbedding`-based `related()` is not
exercised by this particular corpus (no sense in this fixture's design
depends on embedding-based relatedness — `intentionLineage` is
deliberately silent because every walk's intention is `null`), which is
why the OS-version determinism caveat above is a "when in doubt, re-run"
note rather than a "this WILL drift" warning.

## Corpus design rationale

Six walks: five historical (`H0`..`H4`, `-46` to `-8` days before the
current walk) plus the current walk itself (three recordings). Every
checklist item from `.superpowers/sdd/phase20/u11-brief.md` is exercised
by ONE combined fixture rather than several small ones — deliberately: a
single fixture that must get section ORDER, caps, and cross-signal
interaction (e.g. the dispatcher-level lemma dedup between `placeResonance`
and `moonLine` below) right in combination is a stronger test than several
fixtures that each isolate one concern, and it keeps the capture/maintenance
burden bounded to one xcodebuild run rather than N.

- **Multiple themes, recurring + first-time:** `river` (recurring — H0, H1,
  H2, plus the current walk; also carries a `fading` salience direction,
  computed, not designed), `bridge` (recurring — H4 plus the current walk),
  `garden` (first-time — current walk only), `mountain` (absent from the
  current walk entirely — the quiet-line candidate, present on H1 and H3).
- **Marker densities above AND below the 100-word floor, in the SAME
  dossier:** the current walk's three recordings are 110, 144, and 62
  words — the first two clear `DENSITY_FLOOR_WORDS` (100) and render the
  percentage form; the third renders the small-sample raw-count form.
- **Personal baseline (≥5 qualifying recordings):** six recordings clear
  the 100-word floor across the whole corpus (`H1`-`H4` plus the current
  walk's two long recordings) — one over the `BASELINE_FLOOR_RECORDINGS`
  (5) floor with margin.
- **Modal-lean firing:** the current walk's `garden` recording repeats
  "could" eleven times (family `possibility`) against a baseline of six
  "could"s spread across the four historical ≥100-word walks — comfortably
  past both the `MODAL_REMARKABLE_MIN_COUNT` (10) and
  `MODAL_REMARKABLE_RATE_MULTIPLE` (2.0×) gates.
- **Pace correlation, both directions:** the current walk's three
  recordings carry hand-set `wordsPerMinute` values (river 180, garden 90,
  bridge 130 — independent of the transcript's own word count, since this
  field is stored, not derived) chosen so `river` reads faster than its
  rest-of-walk mean and `garden` reads slower, in the same dossier.
- **Quiet-this-walk line:** `mountain` appears on two historical walks
  (H1, H3) inside the 30-day window, never on the current walk.
- **At least two senses' inputs — three actually fire:** `placeResonance`
  (river's two H1/H2 route fixes cluster ~27m apart against a ~13.6km
  baseline set by H4's far-away bridge fix), `moonLine` (H0 sits inside the
  lunation that closed most recently before the build's `now`, computed via
  `LunationCalendar`'s own pinned formula rather than a hand-picked date —
  see `sensesDesignNotes` for the verification script's reasoning), and
  `speechShape` (all three current-walk recordings end inside the first
  third of the walk span, then 65 wordless minutes follow). The other five
  senses are deliberately silent, each for a structural reason recorded in
  `fixture.json`'s `sensesDesignNotes.deliberatelyNotFired` — not because
  they were untestable, but to keep this fixture's firing set small enough
  to reason about and predict by hand before the capture ever ran.
- **A real cross-sense interaction, not designed but confirmed by the
  capture:** `moonLine`'s theme-naming clause is silent (the fallback
  ".", not "; 'river' walked in N of them.") because `placeResonance` fires
  first in priority order and adds `river` to the dispatcher's shared
  `used` set — `moonLine`'s own topTheme search then finds no un-suppressed
  candidate with an in-lunation appearance and falls back to the
  theme-less form. This is exactly the belt-and-suspenders dedup the
  parity spec documents, caught by running the REAL engine rather than
  predicting its output by hand.
- **Senses inputs not exercised:** non-English handling (R5) is out of
  scope for this fixture (see `fixture.json`'s `notExercisedAtAll`) —
  already covered by `TranscriptContextAnalyzerTest`/`MarkerAnalyzerTest`,
  and mixing an unpredictable cross-platform language-id disagreement into
  a golden fixture would add risk without adding coverage of the engine/
  formatter/senses surface this fixture targets.

## Corpus-authoring hazard found during the first capture (fixed, not normalized)

The first capture round used slightly different wording in `h1-river-mountain.txt`,
`h2-river.txt`, and `current-a-river.txt`, and the Android golden test (written
against that first capture) failed with three EXTRA active/recurring threads
Android produced that iOS's captured text did not: `'felt'`, `'open'`, and
`'whole'`. All three were genuine corpus-authoring bugs, not an engine
divergence to allow for:

- `felt` appeared twice each in `h1-river-mountain.txt`, `h2-river.txt`, and
  the original `current-a-river.txt` — ordinary past-tense "felt" (from
  "feel"), but WordNet also lists **felt the fabric** as a noun, and
  Android's dictionary-based tagger (no contextual disambiguation — see the
  parity specs' own repeated warnings about this substrate gap) tagged every
  occurrence as a noun candidate regardless of context. Because it repeated
  in two historical walks AND the current walk, it became a fully-formed,
  visible "recurring thread" line in Android's output that iOS's contextual
  `NLTagger` never produced at all.
- `open` and `whole` each repeated twice in the original `current-a-river.txt`
  alone (adjectival uses — "the open field" / "chest felt open", "the whole
  valley" / "the whole way") — WordNet separately lists **the open**
  (outdoors) and **a whole** (the entirety) as legitimate but rare noun
  senses, so both became spurious first-time themes.

**Fix:** reworded the second occurrence of each word in those three files
(`felt`→`seemed`/`grew`, the second `whole`→ rephrased, the second `open`→
rephrased — see git history on this directory for the exact diffs) so no
non-target word repeats within a single recording's own text, re-ran the
capture, and confirmed the three spurious lines disappeared with no other
change to the output. This was the right fix, not a new allowance: the
corpus's own design goal (stated above) was to avoid exercising the
theme-set-drift allowance at all, and the failure was corpus noise, not a
finding about the engines. **Lesson for future corpus refreshes:** WordNet's
dictionary POS lookup can tag an ordinary verb or adjective as a noun purely
because SOME sense of that surface form is nominal somewhere in the
dictionary — auditing for "does any word repeat 2+ times outside the
stoplists" is necessary but not sufficient; the practical rule that held up
under a real run is "does any word repeat 2+ times AT ALL within one
recording's text, stoplisted or not" (function words — determiners,
pronouns, prepositions — are the only safe exception, since they carry no
noun sense in any dictionary). A repeated content word, even a common verb
or adjective, is a latent risk until it has actually been run through both
engines.

## Timezone / locale determinism

The harness sets `NSTimeZone.default = TimeZone(identifier: "UTC")!` before
capture, since `ThreadsDossierFormatter`'s `shortDateFormatter` and
`LunationCalendar.moonName`'s calendar both read the ambient time zone when
none is passed explicitly. `ThreadsGoldenDossierTest` on the Android side
must force the JVM default time zone to UTC the same way (`TimeZone
.setDefault`, restored in `@After`) for the "first spoken <Mon Day>" clauses
and the moon-name-by-calendar-month lookup to agree with this captured text.
