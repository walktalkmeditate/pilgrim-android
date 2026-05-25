# Play Store 1.0.0 Launch + Release Pipeline — Requirements

**Date:** 2026-05-19
**Status:** Ready for planning
**Owner:** @momentmaker

## Problem

Pilgrim Android has shipped phases 0–13 + iOS-v1.6.0 parity work and is ready for a 1.0.0 launch on Google Play. Current `release.yml` builds + signs an AAB on `v*` tag push and attaches it to a GitHub Release. There is no Play Store upload path, no version-bump automation, and no track promotion. iOS uses a two-workflow shape (`testflight.yml` + `release.yml`) — Android should reach functional parity for release ceremony so both apps ship the same way.

## Goals

- Ship 1.0.0 to Google Play Production by hand-uploading an AAB produced by the existing GHA pipeline. No new CI work required to reach 1.0.0.
- For 1.0.1 and beyond, replace manual Play Console uploads with two GHA workflows that mirror iOS: `internal.yml` (TestFlight-equivalent) and `production.yml` (App Store-equivalent), both `workflow_dispatch`-triggered.
- Use gradle-play-publisher (GPP) for the upload mechanism so release notes, track, and rollout % are declarative + in-tree.
- Symbolicate native crashes from `whisper.cpp` JNI on Play Console by uploading NDK debug symbols with every release.
- Enroll in Play App Signing at 1.0.0 so Google manages the app signing key and the existing `pilgrim-release.keystore` becomes the upload key only.

## Non-goals

- Pre-launch report automation — Play Console runs it automatically on every internal-track upload.
- Screenshot generation via `fastlane screengrab` / Compose screenshot tests — capture by hand for 1.0.0; revisit if churn becomes painful.
- Closed and open testing tracks — Internal + Production only.
- Dynamic features / App Bundle Explorer / on-demand modules.
- Auto-tagging or auto-publishing on merge to `main`. Releases stay human-initiated via GHA UI button click.
- Tag-triggered workflows. Tags become a downstream artifact created BY the workflow after a successful Play upload, not the trigger.
- Removing the existing `release.yml` until 1.0.1 has shipped successfully through the new pipeline.

## Users

- **You (release operator)** — clicks "Run workflow" in the GHA UI, optionally enters a versionName like `1.0.1`, watches it land on Play Internal, validates on-device, then re-runs the production workflow to ship.
- **Play Internal Testing testers** — small allowlist (you + 1–2 trusted users) who receive every internal-track build via the Play Store opt-in URL.
- **End users on Play Production** — receive staged rollouts (default starting %) for every production release.

## Success criteria

- 1.0.0 is live on Google Play Production with all store listing fields complete, content rating assigned, data safety form submitted, and Play App Signing enrolled.
- 1.0.1 (or whatever ships first after 1.0.0) is published to Internal Testing entirely through `internal.yml` — zero manual file movement.
- The same 1.0.1 reaches Production via a `production.yml` run that needs no manual intervention beyond clicking "Run workflow" and confirming the rollout %.
- Native crashes from `whisper.cpp` appear in Play Console's Android vitals → ANRs & crashes view with symbolicated stack frames (function names, not raw addresses).
- versionCode is monotonically increasing across every Play upload, and there is one obvious rule for how it gets generated.
- Both workflows commit the versionCode bump back to `main` with `[skip ci]` to keep the repo's `app/build.gradle.kts` in sync with Play.

## Scope — Phase A: 1.0.0 manual launch

### A.1 Google Play Developer account setup

If the developer account does not already exist:

1. **Get D-U-N-S number FIRST** — Play requires it for organization verification and D-U-N-S issuance takes longer than Play's own review. Request at [dnb.com/duns-number](https://www.dnb.com/duns-number/get-a-duns-number.html) for `ZREIG, LLC`. Standard processing ~30 days free; expedited ~5 business days (paid, ~$200). Have ready: legal entity name, registered business address, phone, year of incorporation, number of employees, primary contact.
2. Once D-U-N-S in hand, go to [play.google.com/console/signup](https://play.google.com/console/signup) and sign in with the Google account that will own the publishing identity. **Pick this Google account carefully** — transferring ownership later is painful. Use a dedicated `publishing@` or owner-level account, not a personal one.
3. Choose **Organization** account type.
4. Pay the **one-time $25 USD** registration fee.
5. Complete identity verification:
   - D-U-N-S number for `ZREIG, LLC`.
   - Official organization email (must match the domain associated with the org if possible).
   - Authorized representative's government-issued ID.
   - Address proof.
   - This step typically takes 2–3 business days; can take up to 2 weeks once D-U-N-S is provided. **Block on D-U-N-S before scheduling a launch date.**
6. Complete the **Developer profile**:
   - **Developer name** (publicly shown on every listing): `walk, talk, meditate` (all lowercase).
   - **Legal/billing name** (private): `ZREIG, LLC`.
   - Support email, website (`pilgrimapp.org`), physical address.
7. Accept the Developer Distribution Agreement.

### A.2 Create the app in Play Console

1. Console → **All apps** → **Create app**.
2. App name: `Pilgrim` (≤ 30 chars).
3. Default language: English (United States).
4. App or game: **App**.
5. Free or paid: **Free**.
6. Declarations: tick both (Play policies, US export laws).

### A.3 Mandatory pre-launch declarations

Each of these is a separate task in Play Console's "Set up your app" dashboard. All must show ✅ before Production rollout is unlocked.

1. **Privacy policy URL** — `https://pilgrimapp.org/privacy`. Verify publicly reachable + HTTPS before pasting. (iOS lists `pilgrimapp.org/privacy.html`; either canonicalize to one URL or ensure both resolve to the same content.)
2. **App access** — declare whether parts of the app are gated by login. Pilgrim is fully accessible without auth → tick "All functionality is available without restrictions".
3. **Ads** — declare whether the app contains ads. Pilgrim has none → tick "No, my app does not contain ads".
4. **Content rating** — fill out the IARC questionnaire (~10 min). Honest answers about content (no violence, no user-generated content displayed publicly, location use). Expect a PEGI 3 / ESRB Everyone result.
5. **Target audience and content** — pick age groups (likely 18+). Confirm no children-targeted content.
6. **News app** — No.
7. **COVID-19 contact tracing and status apps** — No.
8. **Data safety** — fill out the data collection form. For Pilgrim:
   - Location data collected (precise + approximate), used for app functionality (walk tracking), not shared with third parties, processed on-device + sent to Mapbox for map tiles.
   - Audio recordings collected, used for app functionality (voice journaling), stored on-device only, not shared, optional.
   - Photos collected, used for app functionality (reliquary), stored on-device only, not shared, optional.
   - Crash logs + diagnostics collected by Google Play (required to tick for any app that uses Play crash reporting).
   - **Cite the exact data types collected by Mapbox SDK in the disclosure** — Mapbox's docs list what their SDK sends; copy those rows into the form.
9. **Government apps** — No.
10. **Financial features** — No.
11. **Health features** — Pilgrim ships Health Connect integration → declare in "Health Connect" section once form is enabled (currently a separate Play Console review; allow ~1 week if Health Connect is in 1.0.0).
12. **Advertising ID** — declare whether the app uses the ad ID. Pilgrim does not → tick "No".

### A.4 Store listing

Console → **Main store listing**. All fields required. Source-of-truth for copy is iOS at `pilgrim-ios/docs/app-store/metadata.md` — most fields port verbatim with the substitutions called out below.

- **App name** (≤ 30 chars): `Pilgrim — Mindful Walking` (24 chars, reuse iOS).
- **Short description** (≤ 80 chars): `Walk with intention. Record. Reflect. Meditate. Everything on your device.` (74 chars)
- **Full description** (≤ 4000 chars): reuse iOS Description block (lines 26–58 of `pilgrim-ios/docs/app-store/metadata.md`) verbatim with these substitutions:
  - `WhisperKit` → `whisper.cpp`
  - `WeatherKit` → `Open-Meteo`
  - Drop or rework the `.pilgrim packages or GPX files` line if those export paths haven't landed on Android by 1.0.0 — verify against current parity ledger before submission.
  - Append Mapbox SDK attribution line (Mapbox's BSD-3-style license requires it; copy the exact wording from Mapbox's docs for the SDK version Pilgrim ships).
- **App icon**: 512 × 512 PNG, 32-bit with alpha. Generate from existing Android adaptive icon.
- **Feature graphic**: 1024 × 500 PNG/JPG, no transparency. Hero image shown at top of listing.
- **Phone screenshots**: minimum 2, recommended 8, max 8. Required dimensions: 16:9 to 9:16 aspect ratio, 320–3840 px. **Capture from OnePlus 13 manually for 1.0.0** (use `adb shell screencap -p > screen.png` from the active walk, summary, goshuin grid, journal thread, meditation, voice-guide picker, soundscape, photo reliquary). Stitched screenshots with marketing copy are allowed if at least one screenshot is also a raw capture.
- **Tablet screenshots** (7-inch + 10-inch): only required if "Designed for tablet" is claimed. Skip for 1.0.0.
- **Promo video** (YouTube URL): optional. Skip.
- **App category**: Health & Fitness or Lifestyle (pick Health & Fitness — closer to active-tracking apps users will compare to).
- **Tags**: pick up to 5 from Play's preset list.
- **Contact details**: support email, website, phone (optional).

### A.5 Play App Signing enrollment (one-time, critical)

Modern Play accounts have Play App Signing enrolled by default for new apps, but verify:

1. Console → **Setup** → **App integrity** → **App signing**.
2. The existing `pilgrim-release.keystore` becomes the **upload key**; Google generates and holds the actual **app signing key**.
3. Two upload paths:
   - **Option 1 (recommended)**: upload the AAB built and signed with the existing keystore. Play extracts the upload certificate from the first AAB and pins it. The existing `release.yml` already produces this — use that AAB for 1.0.0.
   - **Option 2**: export the upload certificate from the keystore and register it manually via Play's UI. Useful if you want to validate before uploading the AAB.
4. Once enrolled, **the upload key cannot be casually rotated**. Treat `pilgrim-release.keystore` and the `KEYSTORE_BASE64` GHA secret as load-bearing — if either is lost, recovery is a Google support ticket and takes days.
5. Back up the keystore: store the original `.keystore` file in a password manager (1Password / Bitwarden secure file) AND a second offline location. The base64-in-GHA-secret form is not a backup.

### A.6 Build + bootstrap-upload the first AAB (Internal track)

**Why Internal first, not Production:** the Google Play Developer API cannot perform the *first* upload of a brand-new app — Google requires at least one manual AAB upload through the Console to establish the package before the API (gradle-play-publisher → `internal.yml`) can publish. We satisfy that bootstrap by hand-uploading the first AAB to the **Internal Testing** track. This (a) clears the API first-upload requirement, (b) gives us TestFlight-style internal testing immediately, and (c) lets us validate on-device before any production exposure. After this one manual upload, every subsequent build is fully automated via `internal.yml`.

1. Bump `app/build.gradle.kts` locally: `versionName = "1.0.0"`. versionCode is computed by CI as `git rev-list --count HEAD` (see A.10) — no manual versionCode edit needed.
2. Commit + push to `main`. Wait for `build.yml` CI to pass.
3. Tag: `git tag v1.0.0 && git push --tags`. The existing `release.yml` triggers, builds + signs the AAB, attaches it to a GitHub Release.
4. Download `app-release.aab` from the GitHub Release page.
5. Console → **Testing → Internal testing** → **Create new release** → upload the AAB.
6. Add release notes (≤ 500 chars). Draft a 1.0.0 launch note.
7. Review and roll out to Internal. Play runs the automated pre-launch report (~30 min).
8. Testers install via the Internal opt-in URL (A.8); validate on-device.

**After bootstrap — automation takes over:**
- New builds → `internal.yml` (workflow_dispatch). No more manual uploads. This is the TestFlight-equivalent loop.
- When ready for the public: `production.yml` promotes/publishes to **Production** at 20 % staged rollout. The original "1.0.0 straight to Production" plan is replaced by "bootstrap to Internal, validate, then promote to Production."

### A.7 NDK debug symbols (do once, before A.6)

Crashes from `whisper.cpp` (Stage 2-D JNI) won't symbolicate on Play without this.

Add to `app/build.gradle.kts` inside `android { defaultConfig { ... } }`:

```kotlin
ndk {
    debugSymbolLevel = "FULL"
}
```

Verify: after `./gradlew bundleRelease`, the produced AAB should contain `BUNDLE-METADATA/com.android.tools.build.debugsymbols/` entries. Play Console → **Android vitals** → **Deobfuscation files** confirms the upload succeeded after the AAB lands.

### A.8 Internal Testing track setup

The Internal track is the bootstrap target (A.6) AND the ongoing TestFlight-equivalent loop, so set it up before the first upload.

1. Console → **Testing → Internal testing** → **Testers** → **Create email list**.
2. Add your own email + 1–2 trusted testers. **At least one tester is required** or the track upload is rejected.
3. Copy the **opt-in URL** Play generates — testers must open it on their device and tap "Become a tester" before they can install internal builds.
4. Save the email list as default for Internal testing.

### A.9 Service account for future CI

Required for Phase B (GHA → Play upload). Set up now while you're already in Play Console.

1. Console → **Setup** → **API access** → **Create new service account** → opens Google Cloud Console in a new tab.
2. In Google Cloud: create a new project (e.g. `pilgrim-android-publishing`) or use an existing one.
3. **IAM & Admin** → **Service Accounts** → **Create service account**.
   - Name: `play-publisher-ci`.
   - Role: leave blank at the GCP project level (permissions are granted in Play Console, not GCP).
4. After creation, click the service account → **Keys** → **Add key** → **Create new key** → **JSON**. Download the `.json` file. **Treat as a secret.**
5. Back in Play Console → **API access** → the new service account appears in the list → click **Grant access**.
6. Permissions:
   - **App permissions**: select Pilgrim only (do not grant org-wide).
   - **Account permissions**: tick **Release manager** (covers create/edit/promote releases on Internal + Production tracks). Do NOT grant Admin.
7. Save.
8. Base64-encode the JSON for GHA: `base64 -i play-publisher-ci.json | pbcopy`. Add as repo secret `PLAY_SERVICE_ACCOUNT_JSON_BASE64`.

### A.10 versionCode seeding rule (decision lock-in)

- 1.0.0 = `versionCode 100` (manual seed, chosen now).
- 1.0.1+ workflows compute `versionCode = $(git rev-list --count HEAD)` against the checked-out main. Currently 505+ → first automated build will be at versionCode 506 or higher. Already above the manually-seeded 100 from 1.0.0. Per-commit monotonic.
- Both `internal.yml` and `production.yml` use the same rule against main. Production versionCode is always ≥ any internal versionCode for the same commit (they're equal on the same commit).
- Same-commit duplicate uploads (e.g. two internal runs on the same main HEAD) intentionally produce duplicate versionCode → Play rejects → operator must land any commit to bump. This is the right operational discipline; releases should correspond to commits.
- Drop the `GHA_VERSION_CODE_OFFSET` env-var concept entirely. The previous rule (`100 + github.run_number`) is wrong because `github.run_number` is scoped per-workflow-file (not per-repo): internal.yml run #50 would produce versionCode 150, then the FIRST production.yml run would produce versionCode 101, and Play would reject production because every prior internal upload had a higher code.
- Workflows MUST `actions/checkout@v6` with `fetch-depth: 0` so `git rev-list --count HEAD` returns the true commit count (default fetch-depth 1 returns 1).

## Scope — Phase B: GHA pipeline for 1.0.1 and beyond

### B.1 Two workflows, iOS-parallel shape

| iOS workflow | Android workflow | Trigger | Target |
|---|---|---|---|
| `testflight.yml` | `internal.yml` (new) | `workflow_dispatch` | Play Internal Testing |
| `release.yml` (iOS) | `production.yml` (new) | `workflow_dispatch` | Play Production (staged) |

Both Android workflows take an optional `version` input (e.g. `1.0.1`). Empty input → keep current `versionName` from `build.gradle.kts`.

### B.2 What each workflow does

Shared steps (both workflows):

1. Checkout with `token: ${{ secrets.GITHUB_TOKEN }}` (need push permission for the version-bump commit) AND `fetch-depth: 0` (needed for `git rev-list --count HEAD` in step 7; default fetch-depth 1 returns 1).
2. JDK 17 + Gradle setup (mirror existing `build.yml`).
3. Decode `KEYSTORE_BASE64`, write `keystore.properties` (mirror existing `release.yml`).
4. Write `local.properties` with Mapbox tokens (mirror existing `release.yml`).
5. Decode `PLAY_SERVICE_ACCOUNT_JSON_BASE64` → `play-service-account.json`. Path is referenced from `app/build.gradle.kts` via GPP config.
6. Set marketing version (if `version` input provided): `sed`-replace `versionName = "X.Y.Z"` in `app/build.gradle.kts`.
7. Compute `VERSION_CODE=$(git rev-list --count HEAD)` and `sed`-replace `versionCode = .*` to `versionCode = ${VERSION_CODE}` in `app/build.gradle.kts`. No env-var offset; no dependency on `github.run_number` (which is scoped per-workflow-file and would collide across the internal/production tracks).
8. Commit + push: `git commit -am "release: bump to vX.Y.Z (code N) [skip ci]" && git push`. Use `github-actions[bot]` identity. **This commit step is the iOS-parity move.** Production-only — internal does NOT commit back (see B.2 deviation note).
9. `./gradlew bundleRelease` — produces signed AAB with NDK symbols.

Workflow-specific final step:

- `internal.yml`: `./gradlew publishReleaseBundle --track internal`. `releaseStatus = COMPLETED` is safe here — the internal track has no userFraction concept. Release notes come from `app/src/main/play/release-notes/en-US/internal.txt` (auto-generated from git log of commits since last tag if file is empty/absent).
- `production.yml`: `./gradlew publishReleaseBundle --track production --user-fraction 0.20 --release-status inProgress` for direct publish at 20% staged rollout. **Critical:** `--release-status inProgress` (not `COMPLETED`) is required. gradle-play-publisher 4.0.0's `TrackManager.kt:212-218` applies `userFraction.takeIf { isRollout() }`, and `isRollout()` is true only for `IN_PROGRESS`/`HALTED` — so `--release-status COMPLETED --user-fraction 0.20` silently nulls the fraction and ships 100% live. README confirms: "userFraction is only applicable where releaseStatus=[IN_PROGRESS/HALTED]". Promotion to 100% is a Play Console action (Halt → Resume at 100%) OR a separate workflow run with `--release-status completed`. Direct publish (not `promoteArtifact`) chosen so production runs decouple from internal-track state and always rebuild the AAB with matching versionCode/versionName.

After Play upload succeeds:

10. Create git tag: `git tag vX.Y.Z && git push --tags`. Triggers the existing `release.yml` (or, in the unified future, just attaches AAB to a fresh GH Release without re-uploading to Play).
11. Create GH Release with auto-generated notes (`softprops/action-gh-release`, mirror existing `release.yml`).

### B.3 gradle-play-publisher config

Add to `app/build.gradle.kts`:

```kotlin
plugins {
    // ... existing plugins
    alias(libs.plugins.play.publisher) // com.github.triplet.play
}

play {
    serviceAccountCredentials.set(rootProject.file("play-service-account.json"))
    track.set("internal")  // overridden per-task in workflow
    defaultToAppBundles.set(true)
    releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.COMPLETED)
    // COMPLETED default is safe at the config layer because the default track is
    // internal (no userFraction concept). Production runs override via CLI flag:
    // --release-status inProgress --user-fraction 0.20 (see B.2). Using COMPLETED
    // + userFraction would silently null the fraction (GPP TrackManager:212-218).
    // Release notes default to file-based: app/src/main/play/release-notes/<lang>/default.txt
    // Listing/screenshots: app/src/main/play/listings/<lang>/{full-description.txt,short-description.txt,...}
}
```

Add to `gradle/libs.versions.toml`:

```toml
[versions]
play-publisher = "3.10.1"

[plugins]
play-publisher = { id = "com.github.triplet.play", version.ref = "play-publisher" }
```

### B.4 Repo metadata layout (in-tree, declarative)

```
app/src/main/play/
├── listings/
│   └── en-US/
│       ├── short-description.txt   (≤ 80 chars)
│       ├── full-description.txt    (≤ 4000 chars)
│       └── title.txt               (≤ 30 chars)
├── release-notes/
│   └── en-US/
│       └── default.txt             (≤ 500 chars — overwritten per release by workflow)
└── default-language.txt            ("en-US")
```

Screenshots + feature graphic + icon: keep manual via Play Console for now (re-deciding later if churn justifies in-tree management).

### B.5 GHA secrets needed (one-time setup)

| Secret | Already exists | Source |
|---|---|---|
| `KEYSTORE_BASE64` | ✅ | Existing |
| `KEYSTORE_PASSWORD` | ✅ | Existing |
| `KEY_ALIAS` | ✅ | Existing |
| `KEY_PASSWORD` | ✅ | Existing |
| `MAPBOX_ACCESS_TOKEN` | ✅ | Existing |
| `MAPBOX_DOWNLOADS_TOKEN` | ✅ | Existing |
| `PLAY_SERVICE_ACCOUNT_JSON_BASE64` | ❌ | Phase A.9 |

### B.6 First-run validation (1.0.1)

1. After 1.0.0 is live, make a trivial change (e.g. typo fix in a string resource).
2. Land on `main`.
3. Trigger `internal.yml` from GHA UI with `version: 1.0.1`.
4. Workflow bumps + commits, builds AAB, publishes to Internal.
5. Tester device installs from Play opt-in URL within ~5 min. Verify install + launch.
6. Trigger `production.yml` from GHA UI with `version: 1.0.1`.
7. Promotes Internal → Production at 20 %. Confirm in Play Console.
8. After 1.0.1 ships through this pipeline successfully, delete `release.yml`.

## Out of scope but worth noting for future plans

- **Crashlytics / Sentry**: not in scope. Play Console's built-in vitals + native symbols cover the floor. Add only if visibility gaps appear in production.
- **iOS↔Android version-number parity**: not enforced. iOS may be on 1.2.x while Android is still on 1.0.x. They are independent shipping cadences.
- **Beta / closed testing track**: Internal Testing + Production is sufficient for current tester pool. Add closed-track workflow only when tester pool grows past ~10.
- **Automated rollout %-bump**: workflow ships at 20 %; bumping to 50/100 % is manual in Play Console for now.
- **Localized listings + release notes**: English-only. Add languages when the product has users in those locales.

## Dependencies / assumptions

- Google Play Developer account is either already registered or will be registered as part of Phase A. **Identity verification can take up to 2 weeks** — start this immediately if not done.
- A privacy policy is drafted + hosted at a stable URL before Phase A.4 can complete.
- Marketing assets (icon 512², feature graphic 1024×500, ≥2 phone screenshots) are produced before Phase A.4 can complete.
- The existing `pilgrim-release.keystore` is backed up to ≥2 secure locations before Phase A.5. **If lost after Play App Signing enrollment, recovery is a Google support ticket.**
- Mapbox SDK 12's data collection disclosure (for the data safety form A.3.8) is current with the SDK version Pilgrim ships — verify the Mapbox docs match the installed version at form-submission time.
- Health Connect data type disclosures (A.3.11) are accurate to the actual Health Connect permissions Pilgrim requests in 1.0.0.

## Open questions

_(none — all opening questions resolved)_

## Resolved decisions (2026-05-19)

- **Privacy policy URL**: `https://pilgrimapp.org/privacy` (host + serve before Phase A.3 can complete).
- **Publisher identity**: Organization account. Legal entity **ZREIG, LLC**, doing business as **Walk, Talk, Meditate**. Play Console "Developer name" (publicly shown) = `walk, talk, meditate` (all lowercase). Legal/billing name = `ZREIG, LLC`. **D-U-N-S number required** for organization verification — request free from [dnb.com/duns-number](https://www.dnb.com/duns-number/get-a-duns-number.html); takes ~30 days standard, ~5 business days expedited (paid). **Start D-U-N-S request today** — this is now the longest pole, not Play identity verification.
- **Store listing copy**: reuse iOS App Store metadata from `pilgrim-ios/docs/app-store/metadata.md` with the platform-specific substitutions in A.4.
- **Short description**: `Walk with intention. Record. Reflect. Meditate. Everything on your device.` (74 chars).
- **versionCode rule**: `versionCode = $(git rev-list --count HEAD)` against checked-out main. 1.0.0 manually seeded at 100; first automated build computes from commit count (currently 505+ → first automated build at 506+). Both `internal.yml` and `production.yml` use the same rule, so production versionCode is always ≥ any internal versionCode on the same commit. Same-commit duplicate uploads fail-loud at Play (operator must land any commit to bump). `GHA_VERSION_CODE_OFFSET` env-var concept dropped. Previous rule (`100 + github.run_number`) was wrong because `github.run_number` is scoped per-workflow-file, not per-repo — internal+production tracks would collide.
- **Production rollout flag**: `--release-status inProgress --user-fraction 0.20` (NOT `COMPLETED`). gradle-play-publisher 4.0.0 silently nulls `userFraction` when `releaseStatus = COMPLETED`, shipping 100% live. `COMPLETED` is only used when promoting from staged to 100% (separate workflow run OR Play Console action). Internal track uses default `COMPLETED` (no userFraction concept).

## Next step

Hand off to `/ce-plan` to produce a sequenced task breakdown across Phase A (manual launch) and Phase B (GHA pipeline), with explicit acceptance criteria per task and a critical-path call-out (Play account verification on the long pole).
