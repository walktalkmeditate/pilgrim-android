# Stage 10-EFG: Permissions + Connect + Data — design spec

> **Bundle:** ships three Settings cards in one PR. They're independent surfaces with overlapping infra (Settings card chrome, `SettingsAction` routing, DeviceTokenStore, Custom Tabs / Intent helpers) so it's cheaper to land them as one stage than three.

## Goals

Bring the Android Settings page to iOS pixel parity for the bottom-half of the card stack:

1. **PermissionsCard (10-E)** — three permission rows (Location, Microphone, Motion) with per-row state + Grant / Settings actions, refreshing on resume.
2. **ConnectCard + FeedbackScreen (10-F)** — four nav rows (Podcast, Trail Note, Rate, Share) and a working "Trail Note" feedback form that posts to the existing iOS endpoint.
3. **DataCard + DataSettingsScreen (10-G)** — single "Export & Import" nav row leading to a sectioned screen with Walks / Journey / Audio. **Scoped:** Audio recordings ZIP export ships fully; Walks export/import + Journey viewer ship as scaffolded UI with the heavy `.pilgrim` format port tracked as a follow-up stage (10-I).

## iOS reference

| iOS file | Android target |
|---|---|
| `Pilgrim/Scenes/Settings/SettingsCards/PermissionsCard.swift` | `ui/settings/permissions/PermissionsCard.kt` |
| `Pilgrim/Scenes/Settings/PermissionStatusViewModel.swift` | `permissions/PermissionStatus.kt` + `ui/settings/permissions/PermissionsCardViewModel.kt` |
| `Pilgrim/Scenes/Settings/SettingsCards/ConnectCard.swift` | `ui/settings/connect/ConnectCard.kt` |
| `Pilgrim/Scenes/Settings/FeedbackView.swift` | `ui/settings/connect/FeedbackScreen.kt` |
| `Pilgrim/Models/Feedback/FeedbackService.swift` | `data/feedback/FeedbackService.kt` |
| `Pilgrim/Scenes/Settings/SettingsCards/DataCard.swift` | `ui/settings/data/DataCard.kt` |
| `Pilgrim/Scenes/Settings/DataSettingsView.swift` | `ui/settings/data/DataSettingsScreen.kt` |
| `Pilgrim/Scenes/Settings/JourneyViewerView.swift` | `ui/settings/data/JourneyViewerScreen.kt` |

iOS reference screenshot: `pilgrim-ios/docs/screenshots/09_settings.png` confirms card visual order: Practice → Atmosphere → Voice → **Permissions → Data → Connect → About** (this stage lands the bold three; About is deferred to 10-H).

---

## E. PermissionsCard

### Visuals

- Card chrome via existing `Modifier.settingsCard()` + `CardHeader("Permissions", "What Pilgrim can access")`.
- Three rows in a `Column(spacing = 8.dp)`. Each row:
  - Leading 10dp circle dot, color encodes state.
  - Two-line text column (label + caption, 2dp gap, body / caption styles).
  - Trailing action (checkmark icon, "Grant" TextButton, "Settings" TextButton, or "Restricted" caption).

| Row | iOS title | iOS caption | Android permission |
|---|---|---|---|
| Location | "Location" | "Track your route" | `ACCESS_FINE_LOCATION` |
| Microphone | "Microphone" | "Record reflections" | `RECORD_AUDIO` |
| Motion | "Motion" | "Count your steps" | `ACTIVITY_RECOGNITION` (API 29+; auto-granted otherwise) |

### State machine

```kotlin
enum class PermissionStatus { Granted, NotDetermined, Denied, Restricted }
```

iOS-faithful color mapping:

| Status | Dot | Trailing |
|---|---|---|
| Granted | `pilgrimColors.moss` | Checkmark icon (moss) |
| NotDetermined | `pilgrimColors.dawn` | "Grant" TextButton (stone) |
| Denied | `pilgrimColors.rust` | "Settings" TextButton (stone) |
| Restricted | `pilgrimColors.fog` | "Restricted" caption (fog) |

### Android-specific decisions

- **NotDetermined detection.** Android has no first-class "never asked" state. We approximate via `shouldShowRequestPermissionRationale`: if the permission isn't granted AND we've never requested it (a `permissions_asked_<name>` flag in DataStore), treat as `NotDetermined`. After the first request, the system either grants or denies; subsequent denials resolve to `Denied` regardless of the rationale flag. This matches iOS's "not determined → grant button → user taps → system dialog → denied or granted" flow.
- **Restricted.** Android has no MDM-style restricted state for these permissions. Documented as unreachable; the UI branch is included for symmetry but expected to never render in production.
- **Refresh-on-resume.** Use `LifecycleEventObserver` on `LocalLifecycleOwner` listening for `ON_RESUME` (mirrors iOS's `willEnterForegroundNotification.onReceive`). The user can revoke from system Settings; we always re-check live via `PermissionChecks` rather than caching.
- **Grant request.** `rememberLauncherForActivityResult(RequestPermission())`. After the result lands, immediately re-read live state (the launcher's callback fires before the lifecycle resumes).
- **Settings deep-link.** Reuse existing `AppSettings.openDetailsIntent(context)`.

### ViewModel

`PermissionsCardViewModel` exposes a `StateFlow<PermissionsCardState>` with three nullable-or-status fields. The composable owns the request launchers; the VM only persists the "have we asked" flag and recomputes status from `PermissionChecks` + the flag store.

**Persisted flags** (DataStore, key set `permissions_asked_*`): one boolean per permission, set to `true` after a successful `RequestPermission` callback regardless of outcome.

### Files

| Action | Path |
|---|---|
| New | `permissions/PermissionStatus.kt` |
| New | `permissions/PermissionAskedStore.kt` (DataStore-backed flags) |
| New | `ui/settings/permissions/PermissionsCard.kt` |
| New | `ui/settings/permissions/PermissionsCardViewModel.kt` |
| Modify | `ui/settings/SettingsScreen.kt` (add card between Voice and Data) |
| New tests | `permissions/PermissionAskedStoreTest.kt`, `ui/settings/permissions/PermissionsCardViewModelTest.kt` |

---

## F. ConnectCard + FeedbackScreen

### ConnectCard visuals

Card chrome + `CardHeader("Connect", "Share the path")` + four `SettingNavRow`-style rows. Each row:
- 24dp leading icon (stone-tinted) + body label + trailing chevron OR `OpenInNew` icon.

| Row | Leading icon | iOS label | Android target | Trailing |
|---|---|---|---|---|
| Podcast | `Icons.Filled.GraphicEq` | "Pilgrim on the Path" | Custom Tabs → `https://podcast.pilgrimapp.org` | chevron |
| Feedback | `Icons.Filled.Edit` | "Leave a Trail Note" | Navigate to `Routes.FEEDBACK` | chevron |
| Rate | `Icons.Filled.FavoriteBorder` | "Rate Pilgrim" | Play Store deep link | `OpenInNew` |
| Share | `Icons.Filled.Share` | "Share Pilgrim" | `ACTION_SEND` chooser | chevron |

**Android intent specifics:**
- **Custom Tabs**: `androidx.browser:browser:1.8.0`. Reuse pattern across podcast + later 10-H GitHub link.
- **Play Store**: `Intent(ACTION_VIEW, Uri.parse("market://details?id=$packageName"))` with browser fallback to `https://play.google.com/store/apps/details?id=$packageName`. (Android-equivalent of iOS's `apps.apple.com/?action=write-review`.) **Caveat:** Pilgrim Android has no Play Store listing yet; until it does, this row will deep-link to "Item not found". See open question #4 — we may want to gate visibility on a `BuildConfig` flag or a future "listing live" feature flag.
- **Application ID for Play Store URL:** must be the *release* `applicationId` (`org.walktalkmeditate.pilgrim`), not `BuildConfig.APPLICATION_ID` directly — debug builds carry `applicationIdSuffix = ".debug"` which would deep-link to a non-existent debug listing. Hard-code the release id in a string resource or read it from `BuildConfig.APPLICATION_ID.removeSuffix(".debug")`.
- **Share**: `Intent(ACTION_SEND).type("text/plain").putExtra(EXTRA_TEXT, body + " https://plgr.im/share")`. Body verbatim from iOS: "I've been walking with Pilgrim — it tracks your walks, records voice notes, and even has a meditation mode. No accounts, no tracking, everything stays on your phone. Free and open source."

### FeedbackScreen

Top bar with back arrow + centered "Leave a Trail Note" title (iOS `navigationTitle("Trail Note")` with `principal` toolbar showing the longer label). Two states driven by a single VM `StateFlow`:

#### Form state

`Column(verticalScroll, spacing = 24.dp, padding = 24.dp)`:

1. **Category cards** — one column with three selectable `OutlinedCard`s. Selected state: `pilgrimColors.stone.copy(alpha = 0.08f)` background + 1dp `pilgrimColors.stone` border + trailing checkmark (moss). Unselected: `parchmentSecondary` background, no border.

| Category | Icon (Material) | iOS title | API value |
|---|---|---|---|
| `Bug` | `Icons.Outlined.BugReport` | "Something's broken" | "bug" |
| `Feature` | `Icons.Outlined.AutoAwesome` | "I wish it could..." | "feature" |
| `Thought` | `Icons.Outlined.EmojiNature` | "A thought" | "feedback" |

2. **Text editor** — `OutlinedTextField` with min 120dp height, parchmentSecondary container, "What's on your mind?" placeholder.

3. **Device info toggle** — Switch + label "Include device info"; when on, caption below shows `"Android $sdkVersion · ${Build.MODEL} · v$versionName"`.

4. **Inline error** (rust caption) when present.

5. **Send button** — full-width, stone background, parchment foreground, capsule shape. Disabled until a category is selected AND the trimmed message is non-empty. Shows `CircularProgressIndicator` while submitting.

#### Confirmation state

Full-screen overlay (parchment background) with vertical center stack:
- Large checkmark (moss).
- "Your note has been\nleft on the path." (body, ink, multiline center).
- "Thank you." (body italic, fog).

Auto-dismiss back to Settings after 2500ms (matches iOS).

### FeedbackService

```kotlin
class FeedbackService @Inject constructor(
    @FeedbackHttpClient private val httpClient: OkHttpClient,
    private val deviceTokenStore: DeviceTokenStore,
)
```

Reuses the project-wide `OkHttpClient` already provided by `di/NetworkModule.kt` (Stage 5-C). Wraps it via a `@FeedbackHttpClient` qualifier the same way `di/CollectiveModule.kt` and `di/ShareModule.kt` do — composing `default.newBuilder().callTimeout(15.seconds).build()` rather than constructing a fresh client. Keeps the connection pool shared across all Pilgrim HTTP traffic.

- `POST https://walk.pilgrimapp.org/api/feedback`
- Headers: `Content-Type: application/json`, `X-Device-Token: <DeviceTokenStore.getToken()>`
- Body: `{"category": "<bug|feature|feedback>", "message": "<trimmed>", "deviceInfo": "Android 14 · Pixel 8 · v0.1.0"}` (deviceInfo omitted when toggle off).
- Timeout: 15s.
- Error mapping (matches iOS):
  - 429 → `RateLimited` ("Too many submissions today.")
  - non-2xx → `ServerError(code)` ("Server error (\(code))")
  - network/IO → `NetworkError(msg)` ("Couldn't send — please try again" surfaced in UI)

### Files

| Action | Path |
|---|---|
| New | `data/feedback/FeedbackCategory.kt` |
| New | `data/feedback/FeedbackService.kt` |
| New | `data/feedback/FeedbackError.kt` |
| New | `di/FeedbackModule.kt` (Hilt: `@FeedbackHttpClient` qualifier + provider that decorates the shared `default: OkHttpClient` from NetworkModule) |
| New | `ui/settings/connect/ConnectCard.kt` |
| New | `ui/settings/connect/FeedbackScreen.kt` |
| New | `ui/settings/connect/FeedbackViewModel.kt` |
| New | `ui/util/CustomTabs.kt` (helper if no existing one) |
| Modify | `ui/settings/SettingsScreen.kt` (add ConnectCard) |
| Modify | `ui/navigation/PilgrimNavHost.kt` (handle 4 reserved actions; add Routes.FEEDBACK) |
| Modify | `app/build.gradle.kts` (add `androidx.browser:browser`) |
| Modify | `gradle/libs.versions.toml` (add catalog entry) |
| Modify | `app/src/main/res/values/strings.xml` (15+ new strings) |
| New tests | `data/feedback/FeedbackServiceTest.kt` (MockWebServer), `ui/settings/connect/FeedbackViewModelTest.kt` |

---

## G. DataCard + DataSettingsScreen

### Scope honesty

iOS `DataSettingsView` orchestrates three flows:

1. **Walks Export/Import** — full `.pilgrim` ZIP build/parse roundtrip (`PilgrimPackageBuilder` + `PilgrimPackageImporter`). Schema-versioned JSON, GeoJSON route encoding, optional photo embedding pipeline, manifest assembly, ZIP packaging. iOS implementation: ~1500 LOC of converter + builder + importer logic, ~1200 LOC of tests. Cross-platform binary compatibility is a design property the format guarantees.
2. **Journey viewer** — `WKWebView` loading `view.pilgrimapp.org` with all walks JSON-injected via JS bridge. Similar JSON encoding pipeline as #1, plus base64 photo enrichment.
3. **Audio export** — ZIP all files in `Recordings/` to a temp file, share. Trivial.

The `.pilgrim` builder + importer + JSON converter is the single largest piece of iOS-parity work remaining and **stretches the "low complexity" framing beyond reasonable**. We propose **scoping 10-G to the UI scaffolding + the trivially-portable subset**, and tracking the format port as **Stage 10-I**:

| Surface | 10-G state | Notes |
|---|---|---|
| DataCard (single nav row) | **Ships fully** | Identical to iOS DataCard.swift. |
| DataSettingsScreen (Lazy list with 3 sections) | **Ships fully (UI only)** | Section headers, footers, buttons all in iOS-parity copy. |
| Audio recordings export | **Ships fully** | ZIP via `ZipOutputStream`, share via `ACTION_SEND` + `FileProvider`. |
| Walks Export | **Stub** | Button visible; tap shows Snackbar "Walk export coming soon — track Stage 10-I". |
| Walks Import | **Stub** | Same Snackbar. |
| View My Journey | **Stub** | Same Snackbar (the WebView only works with our JSON injected; without injection it shows "no walks"). |

This keeps Stage 10-G genuinely low complexity (~400 LOC + tests) and unblocks the Settings page from being incomplete. Stage 10-I picks up the cross-platform format work as a focused undertaking.

### DataCard visuals

Card chrome + `CardHeader("Data", "Your walk archive")` + a single `SettingNavRow("Export & Import")` → `Routes.DATA_SETTINGS`.

### DataSettingsScreen

Top bar with back arrow + centered "Data" title (iOS principal toolbar pattern). `LazyColumn` with three section blocks. Each section: small caption header (uppercase), one-or-two row content card, footer caption.

Sections (iOS-faithful copy):

| Section | Header | Rows | Footer |
|---|---|---|---|
| Walks | "Walks" | "Export My Data" (with optional `CircularProgressIndicator`); "Import Data" (same) | "Export creates a .pilgrim archive with all your walks, transcriptions, and settings. Import restores walks from a .pilgrim file." |
| Journey | (no header on iOS, use blank) | "View My Journey" | "Opens view.pilgrimapp.org and renders all your walks in the browser. Your data stays on your device — nothing is uploaded." |
| Audio (when count > 0) | "Audio" | "Export Recordings" with trailing "<count>" caption + optional spinner | "Exports all voice recording audio files as a zip archive. These are not included in the data export." |

The recording count comes from the existing `SettingsViewModel.recordingsAggregate` (Stage 10-D added it for VoiceCard).

### Audio recordings export

- Single click handler:
  1. Disable button + show spinner.
  2. On `Dispatchers.IO`: enumerate all files under the canonical recordings dir (resolved via existing `VoiceRecordingFileSystem`).
  3. Build `pilgrim-recordings-<yyyyMMdd-HHmmss>.zip` in `cacheDir/recordings_export/`.
  4. Use `FileProvider.getUriForFile` to share via `ACTION_SEND`. Authority is `${applicationId}.fileprovider` (declared in `AndroidManifest.xml` Stage 7-D).
  5. Surface failure via Snackbar; success dismisses spinner.

**FileProvider config update.** `res/xml/file_paths.xml` currently maps only `<cache-path name="etegami_cache" path="etegami/" />` (Stage 7-D). This stage adds `<cache-path name="recordings_export" path="recordings_export/" />`. Files are written to `context.cacheDir/recordings_export/`, share intent grants temporary read URI permission, Android auto-clears `cacheDir` on storage pressure. No background sweeper required.

**Count-source divergence (acceptable).** iOS reads the recording count from disk via `DataManager.recordingFileCount()`; Android reads Room rows via `walkRepository.observeAllVoiceRecordings()` (Stage 10-D's `recordingsAggregate`). Stage 2-E's `RecordingsSweeper` reconciles disk ↔ Room; brief skew during a sweep window is harmless for this UI.

### Files

| Action | Path |
|---|---|
| New | `ui/settings/data/DataCard.kt` |
| New | `ui/settings/data/DataSettingsScreen.kt` |
| New | `ui/settings/data/DataSettingsViewModel.kt` |
| New | `data/export/RecordingsExporter.kt` (ZIP builder; pure JVM) |
| New | `data/export/BackupTimeCode.kt` (matches iOS `CustomDateFormatting.backupTimeCode`) |
| Modify | `ui/settings/SettingsScreen.kt` (add DataCard) |
| Modify | `ui/navigation/PilgrimNavHost.kt` (handle `OpenExportImport` + `OpenJourneyViewer`; add `Routes.DATA_SETTINGS`) |
| Modify | `app/src/main/res/xml/file_paths.xml` (add `recordings_export/` cache-path) |
| Modify | `app/src/main/res/values/strings.xml` (12+ new strings) |
| New tests | `data/export/RecordingsExporterTest.kt`, `data/export/BackupTimeCodeTest.kt`, `ui/settings/data/DataSettingsViewModelTest.kt` |

---

## Cross-cutting integration points

### SettingsScreen card order (final, this stage)

```
1. CollectiveStatsCard      (Stage 8-B, existing)
2. PracticeCard             (Stage 10-C, existing)
3. VoiceCard                (Stage 10-D, existing)
4. AtmosphereCard           (Stage 10-A/B, existing)
5. (transitional Soundscapes nav row → absorbed into Atmosphere in 10-B; existing)
6. PermissionsCard          ⬅ this stage
7. DataCard                 ⬅ this stage
8. ConnectCard              ⬅ this stage
9. (About link — Stage 10-H)
```

iOS order is Practice → Atmosphere → Voice → Permissions → Data → Connect → About. Android has Voice before Atmosphere (Stage 10-D landed it that way intentionally — Voice is the most-frequently-tapped card after Practice, while Atmosphere is set-once). **Acknowledged divergence**, kept this stage. Re-aligning to iOS order is a one-line `SettingsScreen.kt` reshuffle and can land any time without spec churn.

### SettingsAction wiring

The existing `SettingsAction` sealed interface already has all 10-EFG variants reserved. `PilgrimNavHost.handleSettingsAction` becomes:

```kotlin
SettingsAction.OpenAppPermissionSettings -> context.startActivity(AppSettings.openDetailsIntent(context))
SettingsAction.OpenExportImport -> navController.navigate(Routes.DATA_SETTINGS) { launchSingleTop = true }
SettingsAction.OpenJourneyViewer -> // 10-G stub: snackbar in DataSettingsScreen
SettingsAction.OpenFeedback -> navController.navigate(Routes.FEEDBACK) { launchSingleTop = true }
SettingsAction.OpenAbout -> // 10-H stub
SettingsAction.OpenPodcast -> CustomTabs.launch(context, "https://podcast.pilgrimapp.org".toUri())
SettingsAction.OpenPlayStoreReview -> launchPlayStoreReview(context)
SettingsAction.SharePilgrim -> launchShareSheet(context)
```

### Routes added

```kotlin
object Routes {
    // existing ...
    const val FEEDBACK = "feedback"
    const val DATA_SETTINGS = "data_settings"
}
```

### Strings (excerpt — see plan for full list)

All copy is iOS-verbatim where iOS already shipped final copy; we don't paraphrase. ~30 new string resources spread across the three cards.

---

## Non-goals

- **Stage 10-H AboutView**. Out of scope; deferred. The `OpenAbout` action stays a Log.w stub.
- **Full `.pilgrim` walks export/import**. Tracked as Stage 10-I.
- **Journey viewer JSON injection.** Tracked as Stage 10-I (depends on the same converter pipeline).
- **iOS PracticeSummaryHeader port** (the "Spring 2026 · 5 walks · 27 mi" header above the cards). Out of scope; Android currently uses CollectiveStatsCard for community totals and has no per-user header.
- **Health Connect export.** Phase N — explicitly punted in the master plan.
- **In-app permission rationale dialogs.** Per the iOS pattern, we trust the system permission dialog. The "Settings" button handles the post-denial path.

---

## Verification

### Unit / instrumented tests

- `PermissionAskedStore` round-trips boolean flags per key.
- `PermissionsCardViewModel` translates `(checkSelfPermission, askedFlag, sdkVersion)` → `PermissionStatus`. Covers ACTIVITY_RECOGNITION pre-API-29 auto-grant.
- `FeedbackService` (MockWebServer): success path, 429 → `RateLimited`, 5xx → `ServerError`, IOException → `NetworkError`. Asserts header + JSON body shape.
- `FeedbackViewModel`: category selection, send disabled until category+message present, success → confirmation state, error → form retained.
- `RecordingsExporter`: empty dir produces an empty ZIP; one-file dir produces a one-entry ZIP with correct name; large file (≥10 MB synthetic) doesn't OOM.
- `BackupTimeCode`: deterministic format `yyyyMMdd-HHmmss`, locale-stable (`Locale.ROOT`).
- `DataSettingsViewModel`: aggregates count from existing `recordingsAggregate`; Audio section visibility = (count > 0).

### Device QA (manual)

- Permissions card on a fresh install: all three rows show "NotDetermined" (dawn dot, "Grant" buttons). Tap Grant → system dialog → granted state visible after dismiss.
- Permissions card after revocation in system Settings → app foreground → state refreshes to "Denied" (rust dot, "Settings" button) without restart.
- Feedback form happy path: sends successfully, confirmation appears for 2.5s, returns to Settings.
- Feedback form rate limit: hammer 30+ submits in 5 min on a dev backend; "Too many submissions today." surfaces.
- Feedback form offline: submit fails with "Couldn't send — please try again"; form text preserved.
- Connect card: each row reaches its destination (Podcast in Custom Tab, Play Store opens, Share sheet, Trail Note nav).
- DataCard nav row → DataSettingsScreen → Audio export of 3 voice recordings → share sheet shows ZIP file → opens cleanly in any unzip app.
- DataSettingsScreen on a device with zero recordings: Audio section not rendered.

### Build gates

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` clean.
- ktlint clean.
- No new ProGuard rules required (all touched APIs already covered).

---

## Open questions for the user

1. **Scope of 10-G.** Confirm Option B (UI scaffold + Audio export only; Walks/Journey deferred to Stage 10-I) is acceptable. Alternative: spend 4-5× the effort to land the full `.pilgrim` builder/importer in this PR.
2. **PermissionsCard "Restricted" state.** Android has no equivalent. Drop the branch entirely (always render Granted/NotDetermined/Denied), or keep as defensive code? Recommendation: keep, but mark with `@Suppress("UNUSED")` and a comment.
3. **Custom Tabs vs system browser.** Custom Tabs preserves Pilgrim's in-app feel for the podcast link. Adds one ~200KB dependency (`androidx.browser`). OK?
4. **Rate Pilgrim row before Play Store listing exists.** Three options: (a) ship it anyway and accept "Item not found" deep-link until listing publishes; (b) hide the row until a `BuildConfig` flag flips; (c) defer the entire ConnectCard's Rate row to a follow-up stage. Recommendation: (a) — the iOS card shows it from day one, Play Store handles the not-found case gracefully, and gating ConnectCard layout on listing state adds branching for transient state.
5. **Card ordering reshuffle.** Should this stage also re-align Android's Settings card order (Voice ↔ Atmosphere swap) to match iOS? Recommendation: no — keep the divergence so this PR stays focused on landing three new cards. Reshuffle is a separate, trivial PR.
