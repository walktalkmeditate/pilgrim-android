# Phase 19 — Walk with Me interactive share: device QA checklist (v1.4.0 gate)

Device: OnePlus 13 · production worker (`walk.pilgrimapp.org`) · every line gets a written result before release. Parity pin `2ee1185`.

## Functional

1. **Contract test (AE6):** real interactive share — ≥2 voices (≥1 spanning a pause) + ≥2 photos. Story page renders voice chapters audible + photos full-bleed in a desktop browser AND Android Chrome. Record the share URL.
2. **Transcode envelope (AE4):** include a 20+ min recording — artifact lands under 15 MB; note wall-clock encode time; audio plays on the page (also closes U1's deferred literal-playback check).
3. **Kill mid-upload** after voice 2 → relaunch → repair resumes from the record, no re-encode, no mis-slot (page audio matches disclosure rows).
4. **Background without kill** mid-upload, wait past the background-execution window → return → upload resumed or honestly reported (Android cancels PUTs on VM clear — the repair offer must appear; R10 honesty check).
5. **Airplane mode** at POST → honest error card; mid-PUT → partial + repair; network restored → repair completes.
6. **Interactive OFF regression:** classic share unchanged; zero prep/upload traffic.
7. **Already-shared walk** re-entry → lands on Shared, no form, no second POST (first-share-only accepted).
8. **Toggle race (fix APPLIED, verify on hardware):** the serialization fix landed in the final review — Interactive toggle transitions now chain through one field-held Job that cancels and joins the previous transition, so cleanup and prep can no longer overlap (regression-tested in `WalkShareInteractiveTest`). Hardware check confirms it holds against the real MediaCodec transcoder: rapid Interactive off→on double-taps ×10 → no row stuck "audio removed", Share gate reopens, and a subsequent share's page voice count equals the included count (worst endpoint: silent drop). This line is now a verification, not a gate on reproduction.
9. **photosDropped:** make a photo unresolvable (cloud-only item or revoke media permission mid-flow) → pre-POST consent pause ("Share without them" / "Don't share yet"); decline sends nothing.
10. **Excluded recording:** exclude 1 of 3 → page has 2 voices; talk stats follow consent (AE3 on hardware).
11. **Partial stays put:** force a failed audio PUT → card shows "Carry the missing files", browser does NOT auto-open.
12. **Repair after cache eviction:** clear app cache (keep data) while a partial share exists → repair re-encodes via ensureArtifact and completes.

14. **Soundscape on the page (fold-in #61/#62):** share with a selected soundscape → story page plays it; share with silence chosen → page has no soundscape. Verify the URL in tour.json is base/type/{id}.aac (not a doubled audio/ path).

## Accessibility

13. TalkBack pass over the Interactive section + status card. Known gap: Interactive/Trim toggle rows read as 3 stops (no mergeDescendants) — decide ship-or-fix.

## Release steps (after QA green)

- R2 re-diff `2ee1185..ios-main` once more; triage any delta.
- One `production.yml` dispatch (version name/code per release infra — no manual gradle bump).
- Staged rollout per house practice; then CLAUDE.md phasing note, plan status → completed, memory updates.

## Results — session 2026-08-17 (OnePlus 13 / CPH2655, build 338d579a)

- **Line 6 (Interactive-off regression): PASS** — July 29 walk shared classic; logcat shows zero SharePrepStore/transcoder/uploadMedia activity; page `Tt5fwSWaYO` served with 0 audio elements, no tour script, no tour.json (story-chrome tokens are worker-side page furniture, present for all shares). Test page deleted from R2 post-verification (404 confirmed).
- **Line 7 (already-shared short-circuit): PASS** — re-entering the shared walk lands directly on the Shared card ("View scroll"); no form, Interactive unreachable, no network activity.
- Env note: initial "photos missing from pin screen" report was READ_MEDIA_IMAGES not granted on the debug install (user-granted; photos then appeared). Investigation still open on whether the not-granted state should surface an in-app grant affordance.
- **Line 1 (production contract test): PASS** — real interactive share `tLWbySVLYk` (2.5 km walk, 2 recordings, 2 photos, 1 waypoint, meditation interval). Worker compiled encounters: departure/voice(span)/photo×2/ambience/waypoint/arrival; audio 1+2 serve HTTP 206 with Range; photos exactly 1600px long edge; zero transcription keys or transcript text on the wire; user confirms page parity with iOS. Local prep artifacts + repair record correctly cleared on success.
- **U4 device gate:** real-hardware MediaCodec transcode produced both artifacts (43 s + 120 s WAVs → AAC), uploaded and playable via the page — closes the Robolectric-unverifiable encode path.
- **Line 14 (soundscape): PASS** — "Gentle stream" selected at share time; story page embeds `cdn.pilgrimapp.org/audio/soundscape/gentle-stream.aac` (correct base/type/{id}.aac formula, no doubled prefix). Null path (silence → silent page) covered by unit/wire tests.
- **Classifier field note:** a deliberately voice-free 2-min ambient capture transcribed as sparse Whisper "[Coughing]" tags and auto-classified AMBIENCE via the word-count floor — no manual kind-flip needed; honest-sound design validated on real-world hallucinated non-speech.
- **Lines 2, 3, 4, 5, 8, 9, 10, 11, 12, 13: DESCOPED by user (2026-08-17)** — matches the iOS release bar (equivalent device tests were not run there either). Compensating coverage: every mechanism has dedicated unit/wire tests (kill-window record accuracy, resume-without-re-encode, ensureArtifact eviction re-encode, prep/cleanup serialization RED-proven, partial-no-auto-open, exclusion leave-no-trace on raw wire, per-candidate photo deadline); TalkBack toggle-row merge gap remains tracked from U7 review.
- **U9 verdict amendment (2026-08-17, post-close):** the Line 1 share exposed a walk-old data bug — `activity_intervals` was a never-written table, so every Android share (incl. `tLWbySVLYk`) sent zero meditation intervals; Line 1's own encounter list (no meditation entry) recorded the symptom unnoticed. Fixed on-branch (`ec5ed918` + regression tests incl. the also-nulled `meditate_duration` stat). **Gate re-closes after one re-share verifies a meditation encounter + thread tint + meditate stat compile on the production worker.**
