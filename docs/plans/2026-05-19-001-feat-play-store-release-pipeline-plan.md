---
title: "feat: Play Store 1.0.0 launch + GHA release pipeline"
type: feat
status: active
date: 2026-05-19
origin: docs/brainstorms/2026-05-19-play-store-release-pipeline-requirements.md
---

# feat: Play Store 1.0.0 Launch + GHA Release Pipeline

## Summary

Ship Pilgrim 1.0.0 to Google Play via an internal-first bootstrap: the current `release.yml` builds + signs the AAB, a human hand-uploads it **once** to the Internal Testing track (the Google Play Developer API cannot perform a new app's first upload), then two `workflow_dispatch` GitHub Actions workflows mirroring iOS — `internal.yml` (Play Internal Testing = TestFlight equivalent) + `production.yml` (Play Production with staged rollout) — take over via gradle-play-publisher (GPP). Internal validates on-device through the automated loop; `production.yml` then promotes to Production @ 20%. versionCode is computed per-commit via `git rev-list --count HEAD` (no per-workflow run_number scoping); production bumps commit back to `main` with `[skip ci]`. Plan front-loads all code work so it can land while D-U-N-S issuance (the long pole, ~30 days) processes in parallel.

---

## Problem Frame

Pilgrim Android has shipped phases 0–13 + iOS-v1.6.0 parity and is ready for a 1.0.0 launch on Google Play. Today the existing tag-triggered `release.yml` produces a signed AAB + APK and attaches them to a GitHub Release — but there is no Play Store upload path, no version-bump automation, and no track promotion. iOS already runs a two-workflow shape (`testflight.yml` + `release.yml`); Android should reach functional parity for release ceremony so both apps ship the same way. See origin for full pain narrative.

---

## Requirements

- R1. 1.0.0 reaches Play Production with all listing fields, content rating, data safety form, and Play App Signing enrollment complete (origin Goals).
- R2. 1.0.1 and beyond publish to Play Internal Testing entirely through `internal.yml` (zero manual file movement) (origin Goals, B.1, B.6).
- R3. The same 1.0.1 reaches Production via `production.yml` with no manual step beyond clicking "Run workflow" and confirming rollout % (origin Goals, B.1, B.6).
- R4. Native crashes from `whisper.cpp` symbolicate on Play Console (function names + line numbers, not raw addresses) (origin A.7).
- R5. versionCode is monotonically increasing across every Play upload, with one obvious generation rule (origin A.10).
- R6. Workflows commit the versionCode bump back to `main` with `[skip ci]` so the repo stays in sync with Play (origin Goals).
- R7. Store listing copy, release notes, and listing metadata live in-repo (declarative) so changes are reviewable in PRs (origin B.3, B.4).
- R8. Existing `release.yml` keeps working until 1.0.1 ships clean through new pipeline (origin Non-goals).

**Origin actors:** release operator (clicks "Run workflow" in GHA UI), Play Internal testers (~2 trusted), Play Production end users (staged rollout).

---

## Scope Boundaries

- Pre-launch report automation — Play runs it automatically on every internal upload (origin).
- Screenshot generation via `fastlane screengrab` / Compose screenshot tests — captured manually for 1.0.0 (origin).
- Closed and open testing tracks — Internal + Production only (origin).
- Dynamic features / App Bundle Explorer / on-demand modules (origin).
- Auto-tagging or auto-publishing on merge to `main` — releases stay human-initiated (origin).
- Tag-triggered workflows for the new pipeline — tags become a downstream artifact, not the trigger (origin).
- Localized listings + release notes — English-only at 1.0.0 (origin).
- Migration of iOS↔Android version-number parity — independent shipping cadences (origin).

### Deferred to Follow-Up Work

- Crashlytics / Sentry — Play vitals + native symbols cover the floor; revisit if production visibility gaps appear.
- Closed-track workflow — when tester pool grows past ~10.
- Automated rollout %-bump (20 → 50 → 100) — manual in Play Console at 1.0.0.
- In-tree screenshots / feature graphic / icon management via GPP — defer until churn justifies.
- Removing `release.yml` — runs as final cleanup unit AFTER 1.0.1 ships clean through new pipeline.

---

## Context & Research

### Relevant Code and Patterns

- `app/build.gradle.kts` — versionCode/versionName + `signingConfigs.release` already wired via `keystore.properties` lookup. Insertion point for GPP plugin block.
- `gradle/libs.versions.toml` — version catalog convention; new plugin alias added here (precedent: `kotlin.compose`, `hilt`, `ksp`).
- `.github/workflows/release.yml` — existing tag-triggered AAB+APK builder. Keystore decode + `local.properties` write pattern is reusable verbatim in new workflows.
- `.github/workflows/build.yml` — CI parallel-jobs pattern (`assemble`/`lint`/`unit-tests`). JDK 17 + `gradle/actions/setup-gradle@v6` + `actions/checkout@v6` + `cache-read-only` discipline carries over.
- `pilgrim-ios/.github/workflows/testflight.yml` + `release.yml` — iOS-parity precedent for `workflow_dispatch` shape, optional `version` input, bump-then-commit-back-to-main pattern (`scripts/release.sh bump` + commit with `[skip ci]`).
- `pilgrim-ios/docs/app-store/metadata.md` — source-of-truth for store listing copy (reuse with WhisperKit→whisper.cpp + WeatherKit→Open-Meteo substitutions per origin A.4).
- `.gitignore` — already excludes `*.keystore`, `keystore.properties`, `local.properties`. Need new line for `play-service-account.json`.

### Institutional Learnings

- Autopilot memory (`Stage 2-F device test + scheduler hotfix`): Fakes at the scheduler boundary hide `WorkRequest.build()` crashes that ship through 6 review cycles. Parallel principle here: faking AAB-build steps in CI hides plugin-config crashes that only surface against real Play API. **First-run validation (U9) MUST be a real internal-track upload with a real AAB, not a `--dry-run`** — Play API errors symmetric to AGP builder-validation traps.
- Autopilot memory (`Stage 5-D voice-guide picker`): `@Singleton` async init needs an `initialLoad: Deferred<Unit>` completion signal. Symmetric here: service account JSON decode in CI must complete + verify-readable BEFORE `bundleRelease` runs, otherwise a missing/malformed credential surfaces 20 min later as a publish-step failure (wasted CI time).
- No `docs/solutions/` directory exists in this repo; institutional learnings live in `.claude/projects/-Users-rubberduck-GitHub-momentmaker-pilgrim-android/memory/`.

### External References

- gradle-play-publisher (com.github.triplet.play): canonical Gradle plugin for Play API uploads. Current stable line is 3.x. **Verify latest tag at implementation time (`./gradlew dependencyUpdates` after wire-up, or check [github.com/Triple-T/gradle-play-publisher](https://github.com/Triple-T/gradle-play-publisher) releases).** Plugin docs: [github.com/Triple-T/gradle-play-publisher#quickstart](https://github.com/Triple-T/gradle-play-publisher#quickstart).
- Google Play Console — service account JSON, "Release manager" role, scope to Pilgrim app only (not org-wide).
- D-U-N-S registration: [dnb.com/duns-number](https://www.dnb.com/duns-number/get-a-duns-number.html) — standard free ~30 days, expedited paid ~5 business days.

---

## Key Technical Decisions

- **Hybrid 1.0.0 path** (origin "Resolved decisions"): existing `release.yml` produces the 1.0.0 AAB; human uploads to Play Console once Play account + listing are live. Rationale: 1.0.0 needs human attention in Play Console anyway (listing, screenshots, content rating, data safety form, Play App Signing enrollment) — automating around that adds risk without saving time.
- **gradle-play-publisher over fastlane / r0adkll action** (origin decision): GPP is Gradle-native, declarative metadata + release-notes management lives in-tree, no Ruby toolchain on CI. Single moving-parts surface for a Gradle project.
- **Two workflows, iOS-parallel shape** (`internal.yml` + `production.yml`) (origin decision): one workflow per ship-target so ceremony per track is independent. Mirrors `pilgrim-ios/.github/workflows/{testflight,release}.yml`.
- **workflow_dispatch only, no tag trigger** (origin decision): tags become a downstream artifact created BY the workflow after Play upload succeeds; matches iOS shape, decouples Play upload from git-tag race conditions.
- **versionCode = `git rev-list --count HEAD`** (origin A.10): 1.0.0 = manually-seeded `versionCode 100`; first automated build computes versionCode from the commit count on the checked-out main (currently 505+, so first automated build is at versionCode 506 or higher). Both `internal.yml` and `production.yml` apply the same rule against main, so production versionCode is always ≥ any internal versionCode built from the same commit (they're equal on the same commit). Same-commit duplicate uploads (e.g. two internal runs on the same main HEAD) intentionally produce duplicate versionCode → Play rejects → operator must land any commit to bump. This is the right operational discipline; releases should correspond to commits. The previous rule (`100 + github.run_number`) was wrong because `github.run_number` is scoped per-workflow-file (not per-repo) — internal.yml run #50 would produce versionCode 150, then the FIRST production.yml run would produce versionCode 101, and Play would reject production because every prior internal upload had a higher code. The `GHA_VERSION_CODE_OFFSET` env-var concept is dropped entirely.
- **production.yml: direct publish, not promote-from-internal** (resolution of origin B.2 "choose at implementation time" item): direct `publishReleaseBundle --track production --user-fraction 0.20 --release-status inProgress`. Rationale: (a) decouples production cadence from internal track state (you can ship a production hotfix without an internal build first); (b) avoids GPP's promote-artifact path which is more fragile around "no artifact on internal" errors; (c) re-builds the AAB so versionCode/versionName always match the production tag. Cost: ~3 min extra CI per production release. Worth it for the operational simplicity.
- **`releaseStatus = inProgress`** for production with 20% userFraction; **`COMPLETED`** is only used when promoting to 100% (separate workflow run OR a Play Console action). For internal: **`COMPLETED`** is safe (no userFraction concept on the internal track). Critical bug avoided: gradle-play-publisher 4.0.0's `TrackManager.kt:212-218` applies `userFraction.takeIf { isRollout() }`, and `isRollout()` is true only for `IN_PROGRESS`/`HALTED` — so `--release-status COMPLETED --user-fraction 0.20` silently nulls the fraction and ships 100% live (README confirms: "userFraction is only applicable where releaseStatus=[IN_PROGRESS/HALTED]"). Optional `DRAFT` for internal lets humans inspect in Play Console before going live; default `COMPLETED` on internal, revisit if internal builds need staging.
- **No commit-back-to-main on internal builds** (deviation from iOS): iOS commits the build-number bump unconditionally. For Android, internal builds rewrite `versionCode` at build time but **do NOT commit back to main** — internal cadence may be 10+ builds/day during active development, polluting commit log. Production workflow DOES commit back so `main` tracks each shipped versionName. Trade-off: `main`'s on-disk versionCode lags slightly during burst-internal periods; that's fine because both workflows compute versionCode fresh from `git rev-list --count HEAD` at run time, so the source-of-truth is the commit log itself, not the on-disk integer.
- **Native debug symbols via `ndk { debugSymbolLevel = "FULL" }`** (origin A.7): one-line `app/build.gradle.kts` change; AGP embeds `.so` symbols in AAB metadata; Play strips before serving to users. **Already in flight as PR #126.**

---

## High-Level Technical Design

End-to-end flow (post-1.0.0 release, e.g. 1.0.1):

```
Developer in GHA UI
  │
  ├── Click "Run workflow" on internal.yml, version="1.0.1"
  │     │
  │     ▼
  │   Checkout main (with push token, fetch-depth: 0)
  │   JDK 17 + Gradle setup
  │   Decode KEYSTORE_BASE64 → pilgrim-release.keystore
  │   Decode PLAY_SERVICE_ACCOUNT_JSON_BASE64 → play-service-account.json
  │   sed-replace versionName in app/build.gradle.kts (from input)
  │   sed-replace versionCode in app/build.gradle.kts (= $(git rev-list --count HEAD))
  │   ./gradlew bundleRelease  (produces signed AAB with NDK symbols)
  │   ./gradlew publishReleaseBundle --track internal
  │     │
  │     ▼
  │   Play Internal Testing receives AAB → testers install via opt-in URL
  │
  ├── (validate on-device)
  │
  ├── Click "Run workflow" on production.yml, version="1.0.1"
  │     │
  │     ▼
  │   [same prep as internal.yml — also checkout with fetch-depth: 0]
  │   ./gradlew bundleRelease  (rebuilds; versionCode = git rev-list count of current main HEAD)
  │   ./gradlew publishReleaseBundle --track production --user-fraction 0.20 --release-status inProgress
  │   git commit -am "release: bump to v1.0.1 (code N) [skip ci]" && git push
  │   git tag v1.0.1 && git push --tags
  │   Create GH Release with auto-generated notes
  │     │
  │     ▼
  │   Play Production receives AAB at 20% rollout
```

Directional, not implementation specification. The actual YAML may reorder steps for clarity or share setup via a composite action; the implementer chooses.

---

## Output Structure

New files this plan creates (existing files modified in-place):

```
.github/workflows/
├── internal.yml                       # NEW (U6)
└── production.yml                     # NEW (U7)

app/src/main/play/
├── default-language.txt               # NEW (U5) — "en-US"
├── listings/en-US/
│   ├── title.txt                      # NEW (U5)
│   ├── short-description.txt          # NEW (U5)
│   └── full-description.txt           # NEW (U5)
└── release-notes/en-US/
    └── default.txt                    # NEW (U5) — overwritten per release by workflow
```

Per-unit `**Files:**` lists remain authoritative.

---

## System-Wide Impact

- **CI environment**: new GHA secret `PLAY_SERVICE_ACCOUNT_JSON_BASE64`; new workflows compete for the same Gradle cache as `build.yml` (read-only on PRs already, so no eviction risk).
- **Repo working tree**: `play-service-account.json` MUST be gitignored (U3); leak risk would compromise the Play publishing identity.
- **App signing key**: existing `pilgrim-release.keystore` becomes the *upload* key once Play App Signing enrollment completes. **Recovery from key loss after enrollment is a Google support ticket** — back up to ≥2 secure locations BEFORE 1.0.0 upload (origin A.5).
- **`main` branch**: production.yml pushes commits as `github-actions[bot]`. CODEOWNERS / branch protection (if added later) must allow this identity to push directly.
- **versionCode invariant**: workflows compute versionCode via `git rev-list --count HEAD` against main; human bumps + workflow bumps must NOT collide. Plan locks human-bump to 1.0.0 only (seeded at 100, already below the current commit count of 505+); everything after is workflow-driven. Both `internal.yml` and `production.yml` use the same rule, so production versionCode is always ≥ any internal versionCode on the same commit.

---

## Implementation Units

> **Critical-path note.** D-U-N-S issuance (~30 days standard, ~5 business days expedited paid) is the longest pole. U1–U7 are all code work and can land in parallel while D-U-N-S processes. U8–U10 require the Play Console service account, which requires the Play developer account, which requires D-U-N-S. **Start D-U-N-S request today; U1–U7 in parallel.**

> **Operational note.** Phase A manual checklist items (Play account creation, app setup, declarations, listing, Play App Signing enrollment, Internal Testing email list, service account JSON creation, 1.0.0 AAB upload) live in the origin doc (`docs/brainstorms/2026-05-19-play-store-release-pipeline-requirements.md` sections A.1–A.9). They are operational prerequisites, not code units — referenced as dependencies of U8 and U9.

### U1. Add NDK debug symbols to release AAB

- **Goal:** Embed `whisper.cpp` JNI debug symbols in the release AAB so Play Console symbolicates native crash stack traces.
- **Requirements:** R4.
- **Dependencies:** none.
- **Status:** **In flight as PR #126** (`build/ndk-debug-symbols`). Build verified via `./gradlew :app:assembleDebug`. Awaiting user merge.
- **Files:** `app/build.gradle.kts`.
- **Approach:** Single block inside `android.defaultConfig`: `ndk { debugSymbolLevel = "FULL" }`. Sibling to existing `externalNativeBuild { cmake { ... } }`. AGP merges with build-type-level `ndk { abiFilters }` blocks in debug/release.
- **Patterns to follow:** existing `defaultConfig` block in `app/build.gradle.kts`.
- **Test scenarios:**
  - Covers R4. `./gradlew tasks --all | grep -i debugsymbols` shows `extractReleaseNativeDebugMetadata` and `mergeReleaseNativeDebugMetadata` registered.
  - After `bundleRelease`, AAB contains `BUNDLE-METADATA/com.android.tools.build.debugsymbols/` entries (unzip + ls).
- **Verification:** First Play Internal upload after this lands shows symbols available in Play Console → Android vitals → Deobfuscation files.

### U2. Add `play-service-account.json` to `.gitignore`

- **Goal:** Prevent the Play publishing credential from being committed.
- **Requirements:** R2, R3 (security precondition for both workflows).
- **Dependencies:** none.
- **Files:** `.gitignore`.
- **Approach:** Add `play-service-account.json` under the existing `# Secrets / signing` section. One-line change.
- **Patterns to follow:** existing entries `*.keystore`, `keystore.properties`, `local.properties`.
- **Test scenarios:**
  - `git check-ignore play-service-account.json` exits 0 after the change.
  - `git status` reports clean tree after a `touch play-service-account.json` in working dir.
- **Verification:** Manually create a dummy `play-service-account.json` and confirm `git status` does not list it.

### U3. Add gradle-play-publisher plugin + configuration

- **Goal:** Register GPP tasks (`publishReleaseBundle`, `promoteArtifact`, `bootstrap`) on the `:app` module, configured to read a service-account JSON at the repo root.
- **Requirements:** R2, R3, R7.
- **Dependencies:** U2 (gitignore must catch the credential file before any local-dev experimentation).
- **Files:** `app/build.gradle.kts`, `gradle/libs.versions.toml`.
- **Approach:**
  - Add `play-publisher` version to `[versions]` in `libs.versions.toml`. **Verify latest stable from [github.com/Triple-T/gradle-play-publisher/releases](https://github.com/Triple-T/gradle-play-publisher/releases) at impl time** — pin a known-good version, not `+`.
  - Add `play-publisher = { id = "com.github.triplet.play", version.ref = "play-publisher" }` to `[plugins]`.
  - Add `alias(libs.plugins.play.publisher)` to `app/build.gradle.kts` plugins block.
  - Add a top-level `play { ... }` block in `app/build.gradle.kts` with: `serviceAccountCredentials.set(rootProject.file("play-service-account.json"))`, `defaultToAppBundles.set(true)`, `track.set("internal")` (overridden per-task by workflows), `releaseStatus.set(ReleaseStatus.COMPLETED)`. The `COMPLETED` default is safe at the configuration layer because the default `track` is `internal` (no userFraction concept on internal). Production runs override via CLI flag (`--release-status inProgress`) — see U6. Skip listing/screenshot sync (`commit.set(false)` if needed to avoid GPP auto-uploading metadata before U5 lands).
- **Patterns to follow:** existing `plugins { ... }` block in `app/build.gradle.kts`; existing `[versions]` / `[plugins]` structure in `gradle/libs.versions.toml`.
- **Test scenarios:**
  - `./gradlew :app:tasks --all | grep -i play` lists `publishReleaseBundle`, `promoteArtifact`, `bootstrapListing`.
  - `./gradlew :app:assembleDebug` still passes (no regression).
  - Without `play-service-account.json` present locally, `./gradlew publishReleaseBundle` fails with a clear "credentials not found" error (NOT an NPE or generic Gradle stack trace).
- **Verification:** GPP tasks appear in task list; debug build green.

### U4. Create `app/src/main/play/` metadata layout (in-tree listing)

- **Goal:** Move store-listing copy from Play Console UI into a versioned, in-repo source-of-truth that GPP reads at publish time.
- **Requirements:** R7.
- **Dependencies:** U3 (GPP must be configured to know about this layout).
- **Files:**
  - `app/src/main/play/default-language.txt` (content: `en-US`).
  - `app/src/main/play/listings/en-US/title.txt` (content: `Pilgrim — Mindful Walking`).
  - `app/src/main/play/listings/en-US/short-description.txt` (content: `Walk with intention. Record. Reflect. Meditate. Everything on your device.`).
  - `app/src/main/play/listings/en-US/full-description.txt` (content: iOS App Store description from `pilgrim-ios/docs/app-store/metadata.md` lines 26–58, with WhisperKit→whisper.cpp + WeatherKit→Open-Meteo substitutions + Mapbox attribution line appended).
  - `app/src/main/play/release-notes/en-US/default.txt` (content: 1.0.0 launch note from origin / iOS metadata.md "What's New v1.0.0" — overwritten per release by workflows).
- **Approach:** Plain text files, one purpose each. **Do NOT add screenshots / icon / feature graphic to the play folder yet** — those stay manual in Play Console for 1.0.0 (deferred per scope). Verify the `.pilgrim packages or GPX files` line in full-description matches what Android actually ships by 1.0.0 — if either path isn't live, edit that sentence accordingly.
- **Patterns to follow:** GPP's expected directory structure ([github.com/Triple-T/gradle-play-publisher#managing-play-store-metadata](https://github.com/Triple-T/gradle-play-publisher#managing-play-store-metadata)).
- **Test scenarios:**
  - `./gradlew :app:bootstrap --dry-run` (after Play credentials exist) succeeds without overwriting these files.
  - Character counts: `wc -c < app/src/main/play/listings/en-US/short-description.txt` ≤ 80; `wc -c < app/src/main/play/listings/en-US/title.txt` ≤ 30; `wc -c < app/src/main/play/listings/en-US/full-description.txt` ≤ 4000.
- **Verification:** File tree matches Output Structure section; char limits respected.

### U5. Create `.github/workflows/internal.yml`

- **Goal:** Manual-trigger workflow that bumps versionCode, builds + signs AAB, uploads to Play Internal Testing.
- **Requirements:** R2, R5, R7.
- **Dependencies:** U1 (NDK symbols in build), U2 (gitignore), U3 (GPP plugin), U4 (metadata files).
- **Files:** `.github/workflows/internal.yml`.
- **Approach:**
  - Trigger: `workflow_dispatch` with optional `version` input (string, no default).
  - Permissions: `contents: write` (needed for git-tag push at end; even if internal doesn't commit-back, it may tag).
  - Concurrency: `group: internal-${{ github.ref }}, cancel-in-progress: false` (don't kill in-flight uploads).
  - Steps mirror existing `release.yml`:
    1. Checkout (full token, **fetch-depth: 0** so `git rev-list --count HEAD` returns the true commit count — default fetch-depth 1 would return 1).
    2. JDK 17 + Gradle setup (cache read-write).
    3. Make gradlew executable.
    4. Decode `KEYSTORE_BASE64` → `pilgrim-release.keystore`.
    5. Write `keystore.properties`.
    6. Write `local.properties` (Mapbox tokens).
    7. **NEW:** Decode `PLAY_SERVICE_ACCOUNT_JSON_BASE64` → `play-service-account.json`. Fail-fast if secret unset.
    8. **NEW:** If `inputs.version` non-empty, `sed`-replace `versionName = ".*"` in `app/build.gradle.kts`.
    9. **NEW:** Compute `VERSION_CODE=$(git rev-list --count HEAD)` and `sed`-replace `versionCode = .*` to `versionCode = ${VERSION_CODE}` in `app/build.gradle.kts`. No env-var offset; no dependency on `github.run_number`.
    10. `./gradlew bundleRelease`.
    11. `./gradlew publishReleaseBundle --track internal`. Release notes default to `app/src/main/play/release-notes/en-US/default.txt`.
  - **Internal does NOT commit back to main** (deviation from iOS, see Key Technical Decisions).
  - **Internal does NOT create git tag** (tags are production-only).
- **Patterns to follow:** existing `.github/workflows/release.yml` (keystore decode, local.properties write, secrets references).
- **Test scenarios:**
  - Workflow YAML passes `actionlint` (run locally before commit if available; otherwise GitHub validates on push).
  - Trigger the workflow with `version: 1.0.1` against a Play Console with the service account already registered — AAB lands on Internal Testing within ~5 min.
  - Trigger with empty `version` — current `versionName` from `app/build.gradle.kts` is used; versionCode still bumps via `git rev-list --count HEAD`.
  - Fail-fast: deliberately invalidate `PLAY_SERVICE_ACCOUNT_JSON_BASE64` secret value (e.g. truncate) — workflow fails at step 7 (decode), NOT at step 11 (20 min later).
- **Verification:** First successful run delivers an AAB to Play Internal Testing visible at `play.google.com/console/u/0/developers/.../app/.../tracks/internal`.
- **Execution note:** Workflow YAML changes are hard to test pre-merge — only GHA's runtime can fully validate. Use `actionlint` locally for static checks; rely on first-run validation (U8) for end-to-end proof. Do not try to fake the Play upload step in unit tests — autopilot memory `Stage 2-F` lesson applies: real-API validation only.

### U6. Create `.github/workflows/production.yml`

- **Goal:** Manual-trigger workflow that bumps versionCode, builds + signs AAB, uploads to Play Production at 20% staged rollout, commits the bump + tags release.
- **Requirements:** R3, R5, R6.
- **Dependencies:** U5 (shape pattern), U1, U2, U3, U4.
- **Files:** `.github/workflows/production.yml`.
- **Approach:**
  - Same trigger / permissions / concurrency / setup steps as internal.yml (U5 steps 1–10). Checkout already uses **fetch-depth: 0** for the tag-push step at the end; the same fetch is what makes `git rev-list --count HEAD` accurate in step 9.
  - **Differences from internal.yml:**
    - Step 11: `./gradlew publishReleaseBundle --track production --user-fraction 0.20 --release-status inProgress`. **Critical:** `--release-status inProgress` (not `COMPLETED`) is required for staged rollout — gradle-play-publisher 4.0.0 silently nulls `userFraction` when `releaseStatus` is `COMPLETED` (`TrackManager.kt:212-218`: `userFraction.takeIf { isRollout() }` where `isRollout()` is true only for `IN_PROGRESS`/`HALTED`). Using `COMPLETED` would ship 100% live instead of 20%. Promotion to 100% is a Play Console action (Halt → Resume at 100%) OR a separate workflow run with `--release-status completed`.
    - **NEW step 12:** `git commit -am "release: bump to v${VERSION} (code N) [skip ci]" && git push`. Identity: `github-actions[bot]`.
    - **NEW step 13:** `git tag v${VERSION} && git push --tags`.
    - **NEW step 14:** Create GH Release with auto-generated notes (`softprops/action-gh-release@b4309332981a82ec1c5618f44dd2e27cc8bfbfda` — pin SHA per existing `release.yml` precedent), attach the AAB + APK from `app/build/outputs/`.
  - The `[skip ci]` token in step 12's commit message prevents `build.yml` from rebuilding the bump commit.
  - No `GHA_VERSION_CODE_OFFSET` env-var; versionCode comes entirely from `git rev-list --count HEAD` per step 9 (inherited from U5).
- **Patterns to follow:** internal.yml from U5; existing `release.yml` for the GH Release step.
- **Test scenarios:**
  - YAML passes `actionlint`.
  - Trigger with `version: 1.0.1` after a successful internal-track build of the same version exists — AAB lands on Play Production at 20% rollout within ~5 min.
  - After successful run, `main` has a new commit `release: bump to v1.0.1 (code N) [skip ci]` (N = `git rev-list --count HEAD` of the commit production.yml checked out, currently 506+) AND a new tag `v1.0.1` AND a new GH Release with AAB attached.
  - Trigger with empty `version` — uses current versionName; same flow.
  - **Race scenario:** trigger production.yml while internal.yml is mid-run on the same main HEAD. Both compute the same versionCode (correct: same commit). Whichever uploads first wins; the second fails-loud at Play with "versionCode already used". This is the intended discipline — releases correspond to commits, so two same-commit uploads MUST collide. To ship two builds back-to-back, land any commit between them.
- **Verification:** Play Console → Production tab shows new release at 20% rollout; `git log -1` on main shows the bump commit; `git tag --list v*` shows the new tag.
- **Execution note:** Same as U5 — workflow YAML, real-API validation only.

### U7. Add GHA repo secret `PLAY_SERVICE_ACCOUNT_JSON_BASE64`

- **Goal:** Make the Play publishing credential available to internal.yml and production.yml.
- **Requirements:** R2, R3.
- **Dependencies:** Origin A.9 (service account created in Google Cloud + Play Console — operational prerequisite, NOT a code unit).
- **Files:** none (GHA secrets configured via web UI or `gh secret set`).
- **Approach:**
  - On the machine that holds `play-service-account.json` (downloaded from GCP per origin A.9): `gh secret set PLAY_SERVICE_ACCOUNT_JSON_BASE64 --body "$(base64 -i play-service-account.json)"`.
  - Verify visible in `gh secret list` for the repo.
  - **Do NOT commit the JSON file or its base64-encoded form to the repo.** U2 gitignore enforces this.
- **Patterns to follow:** existing secrets (`KEYSTORE_BASE64`, `MAPBOX_DOWNLOADS_TOKEN`) added via `gh secret set` or web UI.
- **Test scenarios:**
  - `gh secret list` shows `PLAY_SERVICE_ACCOUNT_JSON_BASE64` with non-empty updated timestamp.
  - U5 / U6 workflow decode step succeeds: `base64 -d` produces a JSON file whose `client_email` matches the GCP service account email.
- **Verification:** First internal.yml run authenticates against Play API without 401/403.

### U8. First-run validation: ship 1.0.1 through new pipeline

- **Goal:** Prove the end-to-end pipeline works against real Play before declaring the migration complete.
- **Requirements:** R2, R3 (acceptance for both).
- **Dependencies:** U1–U7 all merged to main; origin Phase A complete (1.0.0 live on Play Production); origin A.9 service account exists.
- **Files:** none (operational unit; trivial code change to trigger a release, e.g. typo fix in a string resource).
- **Approach:**
  - Land a trivial change on `main` (a no-op string resource edit is fine — needs SOMETHING to ship as 1.0.1).
  - Trigger `internal.yml` from GHA UI with `version: 1.0.1`.
  - Verify AAB appears on Play Internal Testing within 5 min; install on tester device via opt-in URL; launch + smoke-test.
  - Trigger `production.yml` from GHA UI with `version: 1.0.1`.
  - Verify Play Production shows 1.0.1 at 20% rollout; verify `main` has bump commit + `v1.0.1` tag; verify GH Release created.
- **Patterns to follow:** none — this is the validation gate.
- **Test scenarios:**
  - Covers R2. internal.yml run completes; tester device shows 1.0.1 in Play Store within 30 min of opt-in URL refresh.
  - Covers R3. production.yml run completes; Play Production tab shows 1.0.1 at 20%; `main` log + tag + GH Release all updated.
  - Covers R5. versionCode of the production 1.0.1 equals `git rev-list --count HEAD` against the main commit that production.yml checked out (≥ 506 at time of writing) and is strictly greater than every prior internal-track versionCode.
  - Covers R6. `main` commit log shows `release: bump to v1.0.1 (code N) [skip ci]` with `github-actions[bot]` as author.
- **Verification:** All four scenarios above pass. If ANY fails, halt — do not proceed to U9.
- **Execution note:** Real-API validation. No fakes, no `--dry-run`.

### U9. Delete legacy `.github/workflows/release.yml`

- **Goal:** Remove the tag-triggered AAB-only workflow now that the new pipeline owns release.
- **Requirements:** R8 (gate: only AFTER 1.0.1 ships clean).
- **Dependencies:** U8 (must succeed end-to-end).
- **Files:** `.github/workflows/release.yml` (delete).
- **Approach:**
  - `git rm .github/workflows/release.yml`.
  - Verify no other workflow / doc / README references it (`grep -r release.yml .github docs README.md` should return only matches inside `docs/brainstorms/` referencing it historically).
  - Land as standalone PR for reviewability.
- **Patterns to follow:** none — pure deletion.
- **Test scenarios:**
  - `find .github/workflows -name "release.yml"` returns empty after the PR merges.
  - Pushing a `v*` tag after this lands does NOT trigger any workflow (no double-publish risk).
- **Verification:** Tag-push test (e.g. `v1.0.1-test`) creates no GHA run.

---

## Risk Analysis & Mitigation

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **Upload key lost after Play App Signing enrollment** | Low | Severe (Google support ticket, days of downtime) | Back up `pilgrim-release.keystore` to ≥2 secure locations BEFORE 1.0.0 upload. Treat `KEYSTORE_BASE64` GHA secret as load-bearing — also export to a password manager. (Origin A.5.) |
| **Service account JSON leaks** | Low | High (attacker can ship malicious AAB under Pilgrim's name) | U2 gitignores the file. GCP IAM scopes the role to "Release manager" on Pilgrim only (not org-wide, not Admin). Rotate quarterly + on any contributor offboarding. |
| **versionCode collision** | Low (only on same-commit re-runs) | High (Play rejects upload mid-release) | Both workflows compute versionCode via `git rev-list --count HEAD` against main. Same-commit duplicates fail-loud at Play — correct: forces a commit between releases. No human bumps after 1.0.0 except in disaster recovery. Workflows MUST checkout with `fetch-depth: 0` or rev-list returns 1. |
| **D-U-N-S issuance blocks launch** | High (it's ~30 days) | High (blocks 1.0.0) | Front-load: start D-U-N-S request immediately; U1–U7 land in parallel. Expedited D-U-N-S available if launch date slips. |
| **Mapbox SDK license attribution missing from listing** | Medium | Medium (license violation; Play may flag) | U4 full-description.txt includes attribution line. Verify against Mapbox docs at U4 impl time. |
| **`whisper.cpp` JNI crash in production without symbols** | Medium | Low (symbolicated stack trace, not a user crash) | U1 ships NDK symbols. Already in PR #126. |
| **Auto-bump commit pushed by `github-actions[bot]` is rejected by branch protection** | Medium (depends on settings) | Medium (workflow fails late, release half-shipped) | Verify branch protection rules allow `github-actions[bot]` to push directly to main, OR use a PAT secret with the right permissions. Test in U8 first-run. |
| **`promoteArtifact` from non-existent internal release** | Medium | Low (workflow fails clearly) | Avoided by Key Technical Decision: production.yml direct-publishes, doesn't promote. |
| **Staged rollout at 20% surfaces a crash bug after partial rollout** | Medium | Medium (need to halt rollout) | Play Console has one-click "halt rollout" + rollback to prior versionCode. Document in operational runbook (see Operational Notes). |

---

## Operational Notes

- **Pre-launch (1.0.0):** Back up `pilgrim-release.keystore` to 1Password (secure file) + offline encrypted USB. Verify both can decrypt before uploading 1.0.0.
- **Per-release runbook (1.0.1+):**
  1. Land changes on `main`, wait for `build.yml` green.
  2. GHA UI → `internal.yml` → Run workflow → enter `version` (e.g. `1.0.1`) → Run.
  3. Wait ~5 min, install on tester device via Play opt-in URL, smoke-test.
  4. GHA UI → `production.yml` → Run workflow → enter same `version` → Run.
  5. Verify Play Production shows new release at 20%. Monitor Android Vitals for ~24h before manually bumping rollout to 50%, 100%.
- **Rollout halt procedure:** Play Console → Production → "Halt rollout". Then revert main if needed and ship a hotfix versionCode > halted versionCode through the same pipeline.
- **Service account rotation:** quarterly. `gh secret set PLAY_SERVICE_ACCOUNT_JSON_BASE64 --body "$(base64 -i new-json)"`; delete old key in GCP IAM.
- **versionCode generation:** workflows compute `versionCode = $(git rev-list --count HEAD)` from the checked-out main HEAD. Operators don't bump anything by hand after 1.0.0. To ship two releases back-to-back, land any commit between them — same-commit re-runs intentionally collide at Play upload.

---

## Phased Delivery

| Phase | Units | Gate to next phase |
|---|---|---|
| **0. Code prep (parallelizes with D-U-N-S)** | U1 (in flight as #126), U2, U3, U4, U5, U6 | All merged to main; debug build green |
| **1. Play account + app setup** | (Origin Phase A.1–A.5, A.7–A.9: D-U-N-S, Play account, create app, listing, declarations, signing enrollment, internal tester setup, service account JSON) | App created on Play; Internal tester list saved; service account linked + Release manager granted |
| **2. Bootstrap upload (Internal)** | Origin A.6: tag `v1.0.0` → `release.yml` AAB → **hand-upload to Internal track once** | First AAB live on Internal — clears the Play API first-upload requirement; testers validate on-device |
| **3. Automation proven** | U8: trigger `internal.yml` for build #2 → confirm fully-automated Internal upload (TestFlight-equivalent loop) | `internal.yml` lands a build on Internal with zero manual file movement |
| **4. Production launch** | `production.yml` promotes/publishes to Production @ 20% staged rollout | 1.0.0 live on Play Production |
| **5. Cleanup** | U9 (delete `release.yml`) | Tag-push test confirms no double-trigger |

**Bootstrap constraint:** the Google Play Developer API cannot perform the *first* upload of a new app — Phase 2's manual Internal upload is mandatory, not optional. Every build after it is automated. This replaces the original "hand-upload 1.0.0 straight to Production" approach: bootstrap to Internal, validate via the TestFlight-style loop, then promote to Production.

---

## Open Questions

_(none — all planning-time questions resolved; execution-time discoveries deferred per below)_

### Deferred to Implementation

- Exact pinned version of `gradle-play-publisher` (verify latest stable at U3 impl time).
- Mapbox attribution line exact wording (verify against installed Mapbox SDK 11.11.0 docs at U4 impl time).
- Whether `releaseStatus = DRAFT` on internal is useful (default `COMPLETED`; revisit if a use case appears).
- Whether `actionlint` should run as a pre-commit hook or just-in-CI (defer; not blocking).
- Branch protection rules + `github-actions[bot]` push permissions (verify at U8 first-run; only address if a real conflict appears).

---

## Next Step

Hand off to `/ce-work` to execute U2 → U7 (U1 already in flight). U8/U9 wait on Phase 1 operational work in Play Console.
