# Stage 8-A Design — Share Journey (ephemeral HTML share URL)

## Context

Phase 8 of the port plan is "Sharing + Collective Counter." 8-A covers only the Share Worker piece — POST walk data to `https://walk.pilgrimapp.org/api/share`, get an ephemeral `https://walk.pilgrimapp.org/<id>` URL that renders a styled HTML page of the walk. The Collective Counter (8-B) is a separate stage.

**iOS canonical reference:**
- `../pilgrim-ios/Pilgrim/Models/Share/ShareService.swift` — HTTP client + Keychain device token + UserDefaults-backed per-walk cache.
- `../pilgrim-ios/Pilgrim/Models/Share/SharePayload.swift` — full payload schema.
- `../pilgrim-ios/Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift` — modal VM, expiry options, payload building.
- `../pilgrim-ios/Pilgrim/Scenes/WalkShare/WalkShareView.swift` — modal UI.
- `../pilgrim-ios/Pilgrim/Views/WalkSharingButtons.swift` — the journey-share section on Walk Summary (the one 7-D left out).

**Backend contract** (read from `pilgrim-worker/src/handlers/share.ts` + `types.ts`):
- `POST /api/share` with header `X-Device-Token: <uuid>` + JSON body
- Rate limit: 10 shares/day per device
- Max payload: 2 MB
- 201 response: `{ url: "<SHARE_DOMAIN>/<id>", id: "<nanoid-10>" }`
- 4xx/5xx: `{ error: "<message>" }`
- Validation rules enforced server-side (route ≥ 2 points, journal ≤ 140, expiry_days ∈ {30, 90, 365}, etc.)

**Android existing infra (Stage 5-C foundation):** `NetworkModule.kt` already provides singleton `OkHttpClient` + `Json` **explicitly annotated** as reusable for Phase 8 Share Worker work. Stage 5-C comment: "future Phase 8 work (Collective Counter, Share Worker) can reuse them without re-establishing the stack." Reuse wholesale.

## Recommended approach

Port iOS's `WalkSharingButtons` journey-share section + `WalkShareViewModel` + `WalkShareView` modal + `ShareService` HTTP client wholesale to Android, **minus the iOS-only-today features** that Android hasn't ported yet (reverse geocoding for `place_start`/`place_end`, photo inclusion, weather stats, `turning_day` seasonal markers, `mark` favicon). All of those are additive — the wire format accepts nullable fields for them, so a future stage can opt-in without breaking the 8-A contract.

The shape:

1. **Walk Summary adds a journey-share section** below the 7-D image-share row. Three visual states mirror iOS exactly:
   - **Fresh** (no cached share): "Create a web page" label + "Share Journey" button → opens the modal.
   - **Active** (cached + not expired): expiry kanji watermark (月/季/巡) behind the content. Opacity fades linearly from 7% at `shareDate` to 2.5% at `expiry` — port iOS's `watermarkOpacity(cached)` (`WalkSharingButtons.swift:281-288`) as a pure helper. URL row (truncated, tappable) — **tapping the URL opens the modal in its success state**, NOT the browser directly (iOS parity — `Copy` handles clipboard, `Share` button invokes the system chooser with the URL). "Returns to the trail on `<date>`" italic + Copy / Share button pair.
   - **Expired**: "This walk has returned to the trail" + optional "Shared for 1 moon" mini-caption + "Share again" link.
   - **Gating** (iOS parity — `WalkSharingButtons.swift:22-24, 37`): the ENTIRE `hasRoute` guard — `walk.routePoints.size >= 2` — hides BOTH the 7-D etegami-share row AND the 8-A journey-share section. Walks without a real GPS trail don't surface any share affordance. Stage 8-A must wrap both rows in a single `if (routePoints.size >= 2)` block in `WalkSummaryScreen` (updating 7-D's unconditional slot).
2. **`WalkShareScreen` modal** opens on "Share Journey" tap via a `walkShare/<walkId>` nav route. Sections:
   - Route preview (reuse `PilgrimMap` composable from 7-A / summary).
   - Stat toggles (distance / duration / elevation / activity breakdown / steps). **Share button is disabled (with helper text "Toggle at least one stat to share") when zero stats are on** — client-side gate matching backend validation (`share.ts:147`).
   - Journal input — `OutlinedTextField` with `minLines = 3` to match iOS `TextEditor(minHeight: 80)` (NOT a single-line field). 140-char cap enforced via silent `onValueChange` truncation (iOS `WalkShareView.swift:225-228` parity — drop overflow chars rather than rejecting input). Character counter shown beneath the field.
   - Expiry picker — segmented 3-option control (1 moon / 1 season / 1 cycle → 30/90/365 days).
   - Waypoint opt-in — conditional on `walk.waypointCount > 0`.
   - Share button — primary CTA. Disabled while uploading, or when zero stats toggled; shows spinner during upload.
   - Cancel in top-left, Done in top-right (Done only after success).
   - **VM hand-off:** `WalkShareViewModel` takes `walkId: Long` via `SavedStateHandle` (Navigation Compose nav arg) and re-fetches walk + route + altitude + intervals + recordings + waypoints from the repo independently. `WalkSummaryViewModel` is NOT shared across the nav boundary — the modal is self-contained. Duplicate DAO calls across screens are cheap (Room query ~1ms) and keep the modal independently testable.
3. **`ShareService`** — `suspend fun share(payload: SharePayload): ShareResult` using OkHttp. Builds a POST with `X-Device-Token` header + `application/json` body. Parses 201 into `ShareResult(url, id)`. Maps 429 to a dedicated `ShareError.RateLimited`, other 4xx/5xx to `ShareError.ServerError(code, message)`, IO to `ShareError.NetworkError(cause)`. Uses a dedicated `OkHttpClient` (not the default 45s-call-timeout one) with `callTimeout = 90s` — a share POST (~150 KB body) on a slow connection plus server-side Mapbox image generation + R2 writes can legitimately take 30-60s. Provide via `@ShareHttpClient` qualifier in `ShareModule` that calls `defaultOkHttpClient.newBuilder().callTimeout(90, SECONDS).build()`. Rate-limit UX: button re-enables after the 429 snackbar so the user CAN retry tomorrow (iOS parity — no client-side lockout).
4. **Device token** — `DeviceTokenStore` backed by DataStore. Generates a UUID v4 on first call, persists, returns. No encryption — it's not a secret, just a per-device handle the server hashes for rate-limiting.
5. **`CachedShareStore`** — DataStore-backed, keyed per walk. DataStore Preferences don't tolerate colons or dashes in raw key names, so the key format is `"share_cache_" + walkUuid.replace("-", "")` — e.g., `share_cache_abcd1234ef...`. Stores `url / id / expiry (ISO) / shareDate (ISO) / expiryOption ("moon"|"season"|"cycle")` as a JSON blob under that key (not separate keys per field — simpler atomic update). Shape is iOS-compatible (same 5 fields) but serialization differs (iOS UserDefaults dict vs Android JSON); the `.pilgrim` Phase-10 export/import will have to do an explicit format bridge either direction — it's already doing that for other entities, so share state is just one more aggregate. `Flow<CachedShare?>` observer per walk so the Walk Summary UI updates reactively. **Expiry is a wall-clock point-in-time check at read time** (iOS `CachedShare.isExpired` parity, `ShareService.swift:40`): every flow emission / composition computes `expiry <= Clock.System.now()`. No live ticker — the active → expired transition visible to the user occurs on next screen re-entry or navigation, not mid-screen. Acceptable at iOS parity.
6. **`SharePayloadBuilder`** — pure mapper `(ShareInputs, WalkShareOptions) → SharePayload`. `ShareInputs` is an aggregate that `WalkShareViewModel` assembles by pulling from the repo directly (not from `WalkSummary`, which doesn't carry altitude or voice-recording data):
   - `walk: Walk` + `routePoints: List<LocationPoint>` + `altitudeSamples: List<AltitudeSample>` — zipped by timestamp to produce `RoutePoint(lat, lon, alt, ts)`. Missing altitude → `alt = 0.0`. Same pattern as Stage 7-C `composeEtegamiSpec` which already pulls altitude samples via `repository.altitudeSamplesFor(walkId)`.
   - `activityIntervals: List<ActivityInterval>` — filtered to `MEDITATING` → `ActivityIntervalPayload(type = "meditation", ...)`.
   - `voiceRecordings: List<VoiceRecording>` — each recording's `startTimestamp / endTimestamp` → `ActivityIntervalPayload(type = "talk", ...)`.
   - `waypoints: List<Waypoint>` — included only when the modal's waypoint toggle is on.
   - Handles route downsampling, ISO date formatting with `Locale.ROOT` (Stage 6-B lesson), `tz_identifier = ZoneId.systemDefault().id`.
7. **`RouteDownsampler`** — port of iOS's Ramer-Douglas-Peucker (RDP) with fallback stride-sample. Target 200 points max. Pure function, unit-testable.

### Active-state Copy / Share buttons

- **Copy** → `ClipboardManager.setPrimaryClip(ClipData.newPlainText("Pilgrim walk", url))` + transient "Copied" confirmation on the button for ~2s (iOS `WalkSharingButtons.swift:229-255` parity — `copiedToastGeneration` debounce so rapid double-taps don't flicker).
- **Share** → `Intent.ACTION_SEND` with `type = "text/plain"` + `EXTRA_TEXT = url`, wrapped in `Intent.createChooser`. Reuse Stage 7-D's `ActivityNotFoundException` catch pattern — edge-case devices without a share chooser surface a "Couldn't open share sheet" snackbar instead of crashing the collector.

### Expiry option parity

Port iOS `ExpiryOption` enum verbatim:
```kotlin
enum class ExpiryOption(val days: Int, val label: String, val kanji: String, val cacheKey: String) {
    Moon(30, "1 moon", "月", "moon"),
    Season(90, "1 season", "季", "season"),
    Cycle(365, "1 cycle", "巡", "cycle"),
}
```

### Device token persistence

DataStore Preferences (not EncryptedSharedPreferences — not a secret, and plain DataStore is a less-heavy dependency). Key: `share_device_token` (no dots — DataStore-safe). If absent on first call, generate `UUID.randomUUID().toString()` and write atomically via `dataStore.edit { prefs[key] = prefs[key] ?: UUID.randomUUID().toString() }`. `DeviceTokenStore` exposes `suspend fun getToken(): String` (generate-on-read semantics) so the same accessor serves Stage 8-A's share flow AND any future feedback flow that needs to include the token for support tracing (iOS exposes an equivalent `deviceTokenForFeedback()` at `ShareService.swift:82`).

### Unit system for `units` payload field

Android doesn't have a distance-unit preference today (iOS reads `UserPreferences.distanceMeasurementType`). Stage 8-A **hardcodes `units = "metric"`** on the wire. The modal's displayed `formattedDistance` also uses km (matches the rest of the app — `WalkFormat.distance` is km-only). A future settings stage can add a DataStore `distance_unit` preference and wire both the modal formatter and the payload `units` field to it; 8-A leaves a `TODO(phase-10)` comment at the hardcode site and the settings field is out of scope here.

### Non-goals (documented; deferred to follow-up stages)

- **Reverse geocoding for `place_start` / `place_end`**: iOS uses CLGeocoder. Android has `Geocoder` but results are best-effort and often poor quality on non-Google devices; skipping for 8-A. Both fields send as null. Page renders without place names.
- **Photo inclusion in share payload**: iOS loads pinned photos via `PHImageManager`, resizes to 600×600, base64-encodes at JPEG 50%. Android equivalent would need `ImageDecoder` + `Bitmap.compress` + base64. Substantial work for a secondary feature; defer. Toggle will be visually absent in the modal on Android, as if `hasPinnedPhotos = false`.
- **Weather fields**: Phase 10 (Open-Meteo integration) hasn't happened. Stats payload sends `weather_condition = null` and `weather_temperature = null`.
- **`turning_day` seasonal markers**: iOS's `TurningDayService` returns solstice/equinox codes. Stage 8-A skips — sends null.
- **`mark` favicon**: iOS walks can have a post-hoc favicon (transformative/peaceful/extraordinary). Android doesn't have the favicon concept yet. Send null.
- **Podcast submission flow**: iOS offers podcast generation after a successful share (`PodcastSubmissionService`). Out of scope for 8-A.
- **Editing / revoking a cached share**: iOS doesn't support this either — wait for expiry.
- **Offline queuing**: if share fails, snackbar the error; user re-taps when back online.
- **Cached-share URL preview via WebView**: iOS can preview the shared page in-app. Android can link out via `ACTION_VIEW`; no in-app WebView.

### Scope observation

~15 new production files (10 under `data/share/`, 4 under `ui/walk/share/`, 1 DI) + ~7 test files = **~22 files total**. Medium-large stage. Bulk is mechanical mapping (payload shape, UI states, DataStore wiring) — only new algorithm is the 40-line RDP port. Autopilot's 15-task threshold is exceeded if each file becomes its own task, but several cluster (data-layer tests stack, UI wiring clusters) so the actual plan task count should land closer to 12-14. Decision: **keep as one stage** because splitting would require a "dead" intermediate merge (8-A.1 with no UI means users see no change). Ship the end-to-end flow atomic.

**Service-scope note:** `ShareService` needs no long-lived `CoroutineScope` (no background polling, no sync worker). All calls are user-triggered and owned by `WalkShareViewModel.viewModelScope`. No `@ShareServiceScope` qualifier — just provide `ShareService` as a `@Singleton` and let callers invoke its `suspend fun` on whatever scope they want.

## Why this approach

iOS's design is already the right one: short, quiet, restrained. The journey-share section is a single card with three visual states; the modal is a single scrollable form with no wizard steps; the service is ~150 LOC of HTTP + Keychain. Porting it directly avoids inventing Android-native patterns that might feel subtly different from the iOS counterpart, and preserves cross-platform visual parity for users who switch devices. The deferrals (geocoding, photos, weather, turning day, mark) are all additive and don't touch the 8-A wire contract — they become stage 8-A-follow-up or Phase 10 items without backwards-compat concerns.

## What was considered and rejected

- **Splitting 8-A into 8-A.1 (service + payload) + 8-A.2 (UI)**: cleaner per-PR size but the first merge ships zero user-visible value. Users wouldn't be able to exercise 8-A.1 until 8-A.2 lands, and an empty intermediate merge is wasted motion.
- **Encrypted device token storage (EncryptedSharedPreferences)**: the token is not a secret — the server hashes it with a salt before storing. Plain DataStore keeps the dep surface smaller and avoids AndroidX Security dependency.
- **Retrofit for the share call**: one endpoint, one payload, no code-gen value. Raw OkHttp + kotlinx-serialization matches the existing Stage 5-C pattern.
- **Ktor for the share call**: would diverge from Stage 5-C's OkHttp choice for no benefit. Stick with OkHttp.
- **In-app WebView preview of the shared page**: adds a new AndroidX WebView dependency + JavaScript console + unpredictable page-JS interactions. Out-link via `ACTION_VIEW` is simpler and matches user-expectations of "open in browser."
- **Attempt to implement photo inclusion + geocoding now**: would double the stage size with secondary features that can be opted-in later without breaking the wire contract.

## Quality considerations

### Stage lessons that apply

- **Stage 5-C CE re-throw**: every `runCatching`/`catch (Throwable)` around suspend network work MUST re-throw `CancellationException` explicitly.
- **Stage 5-C `withContext(Dispatchers.IO)`** for HTTP calls (not `Default`). OkHttp's blocking API is disk/network-bound.
- **Stage 5-C dedup pattern**: `MutableStateFlow.compareAndSet(false, true)` for the "is-sharing" flag, BEFORE `scope.launch`, to prevent the three-rapid-taps race.
- **Stage 7-D SharedFlow event pattern**: VM emits `ShareEvent.Success(url) | Failed(msg) | RateLimited` → UI collects + snackbars / updates card.
- **Stage 7-D Mutex alternative**: could also use a Mutex to guard double-taps. `compareAndSet` is lighter and sufficient here because the guard only protects against concurrent upload calls.
- **Stage 7-A DataStore observer pattern**: `Flow<CachedShare?>` via `dataStore.data.map { ... }` — UI observes and shows active/expired/fresh state reactively.
- **Stage 6-B Locale.ROOT for ISO date strings**: `DateTimeFormatter.ISO_INSTANT` with `Locale.ROOT` so Arabic/Persian/Hindi locales don't break the server's JSON parse.
- **Stage 7-D filename parity lesson**: match iOS's ISO formatting exactly. iOS uses `ISO8601DateFormatter()` default → `yyyy-MM-ddTHH:mm:ssZ`. Use Java `DateTimeFormatter.ISO_INSTANT`.
- **Stage 7-A launcher / permission pattern NOT needed**: 8-A doesn't request any new permissions.
- **Stage 5-C HTTP scope pattern**: `@VoiceGuideManifestScope` — add an analogous `@ShareServiceScope` if the service needs a long-lived scope; currently it doesn't, all calls are user-triggered.

### Thread policy
- OkHttp calls → `Dispatchers.IO`
- DataStore reads/writes → `Dispatchers.IO` (DataStore dispatches internally)
- Payload building → `Dispatchers.Default` (CPU: list mapping + RDP + JSON encode)
- UI state updates → Main (via StateFlow emission from VM)

### Security / privacy

- Device token is a random UUID, not linked to account or PII. Server hashes it for rate-limiting.
- Payload sent to the backend: route + stats + activity intervals + optional journal. No device/user identifiers. User explicitly opts into waypoints via the modal toggle.
- Cached share URL is public (anyone with the URL can view), but the URL is a 10-char nanoid — un-guessable.
- No account, no sign-in, no push tokens. Matches Pilgrim's local-first ethos.

### Memory / performance

- Payload building is a one-shot operation on user tap. Route with 10K+ GPS samples → RDP downsamples to 200 before JSON encoding; peak memory stays bounded at ~10 KB of route JSON.
- OkHttp request body: typically 50-150 KB for a 30-min walk with 200 route points + activity intervals. Well under the 2 MB server cap.
- DataStore cache of CachedShare: ~200 bytes per walk; users accumulate ~1 walk/day → 70 KB/year. Negligible.

### Error paths

All show snackbars from the Walk Summary screen (consistent with 7-D's event pattern):
- **Network failure** (no connectivity, DNS, timeout): "Couldn't reach the trail keeper."
- **Rate-limited (429)**: "You've shared 10 walks today. Try again tomorrow."
- **Server error (4xx/5xx)**: "The trail keeper had trouble. <server message>"
- **Encoding / validation client-side** (unreachable — payload is type-safe): fall back to generic "Couldn't prepare this walk."

## Success criteria

1. Tap "Share Journey" → modal opens with walk preview + toggles + expiry picker.
2. Tap "Share" → success within 10s on typical connection; row transitions to active state with URL + expiry copy + Copy/Share buttons.
3. URL opens in the system browser and renders the walk page correctly.
4. Subsequent Walk Summary re-entries show the cached active state without a new network call.
5. After expiry date passes, row transitions to "returned to the trail" with a Share-again affordance.
6. Rate limit (11th share in 24h) surfaces the user-facing "try again tomorrow" snackbar without crashing.
7. Airplane-mode share → user sees the "couldn't reach trail keeper" snackbar + can retry.
8. Journal 140 char cap is enforced client-side (matches server validation; saves a round-trip).
9. Build + tests + lint clean on min SDK 28 / target 36.

## Files to create

All under `app/src/main/java/org/walktalkmeditate/pilgrim/`:

**Data + service:**
- `data/share/ShareConfig.kt` — base URL constant
- `data/share/SharePayload.kt` — serializable data class + CodingKeys
- `data/share/RouteDownsampler.kt` — pure RDP port
- `data/share/SharePayloadBuilder.kt` — `(WalkSummary, WalkShareOptions) → SharePayload`
- `data/share/ShareService.kt` — HTTP client
- `data/share/ShareError.kt` — sealed hierarchy
- `data/share/DeviceTokenStore.kt` — DataStore-backed UUID
- `data/share/CachedShare.kt` — in-memory value type
- `data/share/CachedShareStore.kt` — DataStore-backed per-walk cache
- `data/share/ExpiryOption.kt` — enum (Moon/Season/Cycle)

**UI:**
- `ui/walk/share/WalkShareViewModel.kt` — modal VM
- `ui/walk/share/WalkShareScreen.kt` — modal Composable
- `ui/walk/share/WalkShareJourneyRow.kt` — the Walk Summary card section
- `ui/walk/share/WalkShareEvent.kt` — sealed events

**DI:**
- `di/ShareModule.kt` — Hilt `@Provides` for `ShareService`, `CachedShareStore`, `DeviceTokenStore` (extends existing `NetworkModule` indirectly by depending on the shared `OkHttpClient`).

**Modified:**
- `ui/walk/WalkSummaryScreen.kt` — slot `WalkShareJourneyRow` below the 7-D `WalkEtegamiShareRow`.
- `ui/walk/WalkSummaryViewModel.kt` — expose cached-share Flow for the walk.
- `app/src/main/res/values/strings.xml` — ~10 new strings (button labels, snackbar messages, modal headings).
- Navigation graph — add a `walkShare/<walkId>` route for the modal.

**Tests (~7 new files):**
- `RouteDownsamplerTest` — RDP correctness (straight line → 2 points, noisy line → 200, edge cases).
- `SharePayloadBuilderTest` — toggled-stats assembly, expiry mapping, ISO date formatting under `Locale.ROOT`.
- `ShareServiceTest` — OkHttp MockWebServer for 201 / 429 / 500 / IOException paths.
- `CachedShareStoreTest` — DataStore round-trip, expiry comparison.
- `DeviceTokenStoreTest` — first-call generation + persistence + atomic write.
- `WalkShareViewModelTest` — share flow, state transitions, cache read on init.
- Optional: `WalkShareJourneyRowTest` (Compose UI test for fresh/active/expired states).

**Dependency additions:** none. Production deps (OkHttp, kotlinx-serialization, DataStore Preferences) all via Stage 5-C + Stage 3-D. Test dep `com.squareup.okhttp3:mockwebserver` already present in `libs.versions.toml:90` (`okhttp-mockwebserver`), pulled in by Stage 5-C's `VoiceGuideManifestServiceTest`.

**Estimate:** ~15 new files + 3 modified. Mid-large stage. Dominated by iOS-port mechanical mapping.
