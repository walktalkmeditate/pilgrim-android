# Phase 20 — Thought Threads device QA (v1.5.0 release gate)

Device: OnePlus 13. Build: `feat/phase20-thought-threads` debug variant (`org.walktalkmeditate.pilgrim.debug`) for the field-report items; release-candidate build for the store-behavior items. Owner: user. This pass is R17's release gate — the release dispatch waits on it.

## A. Core pipeline on real speech

- [ ] Record 2+ real walks with voice recordings on overlapping topics; transcribe (auto or manual). Open the AI prompts screen, copy a prompt, and READ the dossier: `**Thought threads**` marker lines (density vs small-sample form as appropriate), `**Threads across recent walks:**` lines, `**Quiet this walk:**` when earned, `**Noticed:**` senses block ≤ 3 lines. Judge theme quality: do the surfaced themes read as recognizable and meaningful (the iOS field-gate bar: ≥3 of 4)?
- [ ] **Homograph watchlist** (the substrate field read — the release's accept-or-tune decision): watch for junk themes from the deliberately-unsuppressed ambiguous class — `thinking, feeling, being, looking, seeing, talking, asking, open`. Any of these threading as a theme on real speech → add to `androidHomographNounSuppression`/`androidGerundExtension` per the U4/U11 precedent before release. `living`/`working` are deliberately admitted — junk firings there are also signal.
- [ ] **Fold-in stoplists** (iOS PR #74, ported pre-release): confirm the newly-suppressed words no longer thread — conversational filler (`yeah`, `okay`, `hmm`, `gonna` …) and `time`/`times`, `person`/`people`, `app`/`apps`. iOS field-confirmed all three light nouns as live junk themes; on our substrate `okay`, `time`/`times`, `person`/`people` were the live ones. Note that unlike iOS, BOTH singular and plural forms are load-bearing here (Morphy never folds `people`→`person`), so a plural slipping through is a real finding.
- [ ] Chips: after a theme recurs across 2 real walks within 30 days, open the intention sheet — "Recurring" shelf renders FIRST, chips read `walk with '<term>'`, tap REPLACES the text, chips vanish while typing and return when cleared.
- [ ] **Prompt-side fold-in** (iOS PR #72, ported pre-release): copy prompts from several walks and read the tail of each.
  - The old unconditional "Compare the first recording with the last…" line must be GONE. Its replacement fires only on a measured shift — a ≥15% speaking-rate change (both recordings ≥25 words) or a low-vocabulary-overlap subject change — so most two-recording walks should say nothing here. When it does fire, sanity-check it against what you actually said: did the pace or the subject really move?
  - `**How to respond:**` carries the interpretive key only for what the dossier actually printed: the absolutist/self-focus reading when density lines appear, the bare-tally variant when a recording printed "small sample, raw counts only", and the modal-lean gloss only when a modal-lean clause is present. A key naming signals the dossier never printed is a bug.
  - Reflective-voice copy is the record-bound rewrite ("where it does not, say less rather than reaching"). Judge the readback in section E against it.

## B. Field report + flag rates (debug build)

- [ ] Trigger: `adb shell am broadcast -n org.walktalkmeditate.pilgrim.debug/org.walktalkmeditate.pilgrim.core.threads.ThreadsFieldReportReceiver -a org.walktalkmeditate.pilgrim.debug.RUN_SENSES_FIELD_REPORT`, then `adb logcat` for the `===== DOSSIER SENSES FIELD REPORT =====` banner. CAVEAT (by design): rates are inflated vs production — uncapped, no dedup, moon line fires every eligible walk.
- [ ] **Flagged-segment rates** (accept-or-tune, release-blocking, product-owner decision): inspect per-walk flagged-fragment behavior on real recordings. Remember whisper.cpp's native pre-filter already suppresses the worst windows (`no_speech_prob > 0.6 AND avg_logprob < -1.0`) before the Kotlin `2.4/0.6` filter sees segments — the Kotlin filter's job is the survivors. If real speech is being flagged away (themes missing words you clearly said), tune the 0.6 threshold before release; record the decision here.
- [ ] Sense firing sanity on real ground: any sense firing near-every-walk (degeneration) or provably-wrongly (e.g. place resonance on a daily loop) → re-threshold/cut decision per the iOS ship-gate precedent.

## C. Backfill + battery

- [ ] First-activation backfill: install the build over existing history (or toggle off→on) → backfill sweeps history battery-gated; origin-claiming labels ("first appearance in the record", "(first spoken …)", Quiet lines) stay ABSENT until it completes, then appear.
- [ ] **Version-bump re-sweep** (fold-in `ANALYSIS_VERSION` 1→2): installing this build over an EARLIER Phase 20 debug build (one that already wrote v1 contexts) must re-arm the backfill and re-analyze everything — stale-version contexts are pruned, not trusted. Watch the new sweep outcome line in `adb logcat` (tag `ThreadsBackfill`) for a genuine full pass, and confirm themes that previously named filler/`time`/`person`/`app` are gone afterward. A device with no prior Phase 20 build has nothing to re-sweep — note which case you tested.
- [ ] Battery gate: with battery < 20% and not charging, finish a walk with recordings + auto-transcribe ON → summary shows "Auto-transcription skipped — battery below 20%", the recording row does NOT read "Queued", TalkBack announces the banner; tap Transcribe (all) on charge → banner clears only when something actually transcribed.
- [ ] Long-walk regression (JNI touched the critical path): one 45+ min walk, screen off, recordings at intervals → transcriptions land, stats intact.
- [ ] Release-candidate (minified) build: one recording transcribes end-to-end (guards the JNI keep-rule bug class).
- [ ] Non-ASCII speech on the release candidate: one recording whose transcript includes non-ASCII output (accented name/loanword; the Japanese probe in section D doubles here) transcribes without a crash or garbled characters — guards the JNI Modified-UTF-8 sanitizer, which JVM tests cannot exercise.

## D. Sovereignty + hygiene

- [ ] Toggle OFF ("Thought Threads" in Settings → Voice; verify label + description incl. the English-only clause): prompts carry zero threads content; no new context files (`adb shell run-as org.walktalkmeditate.pilgrim.debug ls files/transcript_contexts` count frozen); chips gone. Toggle back ON → backfill re-arms and sweeps the gap.
- [ ] Deletion: delete a single recording FILE → transcription + analysis retained; delete a walk → its contexts removed; (internal wipe covered by unit tests — spot-check via the debug seam only if convenient).
- [ ] Non-English probe (observed-behavior expectation): record a short walk speaking Japanese. The decoder forces English, so record WHAT ACTUALLY HAPPENS: garbled-English analysis, or ML-Kit-detected non-English → silent skip. Either way the toggle's English-only description makes the behavior legible. Log the observation here for the acoustic-lang-id fast-follow decision.
- [ ] Clipboard: copy a dossier-bearing prompt on Android 13+ → the clipboard preview shows the sensitive-content treatment (no content preview).

## E. Release-side checks

- [ ] LLM-readback spot check: paste one real dossier (elevated markers if available) into a consumer LLM (record which vendor + date); verify no clinical/diagnostic language comes back. The handling note ported verbatim from iOS's passed gate — this is inherited-pass verification. With the PR #72 fold-in this check gains a second question: does the reply stay inside what the walk's record supports, or does it manufacture connections and tensions? That over-reach is what the record-bound Reflective copy and the interpretive key exist to curb — if the reply still reaches, say so here; iOS tuned this copy against the same failure.
- [ ] Play Data Safety: confirm no declaration change needed (nothing collected/transmitted; derived data stays on-device; the only egress is the user-carried clipboard).
- [ ] What's-new copy (release dispatch): discloses on-device speech analysis (default ON, toggleable) + first-launch backfill; store copy uses the precise "never transmits transcripts or derived data" form, never an absolute claim.

## Sign-off

- [ ] All sections above pass or carry recorded accept decisions → release dispatch (`production.yml`) with version bump per pipeline convention (v1.5.0; code per convention — prior: 650).
