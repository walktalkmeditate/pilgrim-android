# iOS PR #45 (v1.7.0) → Android Parity Matrix

> **Plan:** `docs/plans/2026-06-22-001-feat-ios-pr45-parity-sweep-plan.md` · **Origin:** `docs/brainstorms/2026-06-22-ios-pr45-v170-parity-sweep-requirements.md`
> **iOS pin:** `pilgrim-ios` @ `a4f81c3` (HEAD of `pr45-parity-readonly`, PR #45, OPEN). Valid only against this sha — re-diff if #45 advances.
> **Spine:** `docs/brainstorms/2026-06-11-audit-findings.md` @ `ecc7fa0` (frozen at the PR's first commit — records audit *intent*, reconciled here against the commit log for *shipped* disposition).
> **Generated:** 2026-06-22 (U1 inventory). Per-area verdicts: U2–U7. Rollup: U8.

## Verdict legend

| verdict | meaning |
|---|---|
| `match` | Android verified equivalent; the iOS-fix mechanism + the Android mechanism are both named (`kotlin:line`). |
| `match-by-reading` | race/ordering/interruption/teardown: code reads correct but the invariant is **not** test-pinned → routes to `needs-device`/F2. Plain `match` forbidden on these. |
| `close-the-gap` | a real Android gap to fix. |
| `re-justify` | intentional Android difference (native substitution: Open-Meteo / Mapbox / whisper.cpp / SpeechRecognizer) — dated reason. |
| `n-a` | iOS-only, no Android relevance — requires *subsystem inspected + bug-class checked*. |
| `stale` | iOS reverted/removed this; no Android action. |
| `needs-device` | desk-unverifiable; flagged for an on-device / test-pinning pass. |
| _(blank)_ | not yet verified (awaiting its area unit). |

## iOS disposition legend (reconciled vs commit log)

`fixed` = implemented in PR #45 · `reverted` = implemented then backed out (no behavior to mirror) · `accepted` = dispositioned with reason, not fully fixed · `refuted` = false positive (X-series).

---

## Master inventory (U1)

> 77 confirmed (AF1–AF77) + 3 uncertain (CU1–CU3) + 14 polish (P1–P14) + 5 refuted (X1–X5). Triage: **CP** = cross-platform behavior/UX · **AN** = iOS-analogue (different mechanism) · **IO** = iOS-only internal (gets the R6 Android-equivalent health check) · **n/a** = no Android surface.

| ID | Sev | Unit | iOS disp | Triage | Title |
|----|-----|------|----------|--------|-------|
| AF1 | crit | U2 | fixed | CP | Checkpoint deleted before save confirmed → walk loss on failed save |
| AF2 | maj | U2 | fixed | CP | Launch orphan-sweep races recovery → deletes crashed walk's audio |
| AF3 | maj | U2 | fixed | CP | "Clear Downloaded Sounds" wipes voice-guide packs/manifest/history |
| AF4 | maj | U3 | fixed | AN | Audio session never downgrades mode while a consumer remains |
| AF5 | maj | U3 | fixed | AN | Soundscape never resumes after an audio interruption |
| AF6 | maj | U3 | fixed | CP | VoiceGuidePlayer.stop drops onFinished → guide permanently silent |
| AF7 | maj | U2 | fixed | AN | `.nullify` delete rule orphans child rows on tended-import overwrite |
| AF8 | maj | U4 | fixed | AN | WalkBuilder retain cycle via preSnapshotFlush closures |
| AF9 | maj | U4 | fixed | CP | Per-GPS-sample O(n) route pipeline (copy/remap/rebuild/re-upload) |
| AF10 | maj | U4 | fixed | AN | 20 Hz metering re-renders the entire ActiveWalkView |
| AF11 | maj | U3 | fixed | AN | Talk recording silently stops after a non-call interruption |
| AF12 | maj | U2 | fixed | AN | Battery/thermal sinks off-main → checkpoint Timer stops firing |
| AF13 | maj | U4 | fixed | CP | Whole-walk JSON checkpoint encoded on main thread every 30 s |
| AF14 | maj | U4 | fixed | CP | Low-battery GPS power never re-applies after meditation |
| AF15 | maj | U4 | fixed | AN | Summary dismiss mid-playback leaks audio consumer + 10 Hz timer |
| AF16 | maj | U4 | fixed | AN | AudioPlayerModel no deinit → leaked repeating timer + stuck consumer |
| AF17 | maj | U4 | fixed | AN | Summary traverses full route relationship per body eval (10 Hz) |
| AF18 | maj | U7 | **reverted** | IO | WeatherKit ES256 key ships in IPA — **reverted (key kept); Android n/a (Open-Meteo)** |
| AF19 | maj | U7 | fixed | CP | Photo-usage string falsely promises photos never uploaded |
| AF20 | maj | U4 | fixed | AN | Map annotations rebuilt + re-rasterized every update, no change detection |
| AF21 | min | U3 | fixed | AN | Soundscape crossfade never sets isPlaying=true |
| AF22 | min | U3 | fixed | AN | Crossfade cleanup asyncAfter has no generation guard |
| AF23 | min | U5 | fixed | CP | Pack download reports 100% even when every file failed |
| AF24 | min | U3 | fixed | CP | Ending meditation force-resumes a manually-paused voice guide |
| AF25 | min | U7 | fixed | CP | Eternal-cairn '108' badge uses system serif, not brand fonts |
| AF26 | min | U2 | fixed | CP | Transcription/WPM persistence fire-and-forget → silent data loss |
| AF27 | min | U2 | fixed | CP | Export maps any fetch failure to `.noWalksFound` |
| AF28 | min | U2 | fixed | CP | Import silently skips undecodable walks, reports success |
| AF29 | min | U6 | fixed | CP | Podcast consent checkbox state not conveyed to VoiceOver |
| AF30 | min | U4 | fixed | AN | GeoCache fetch bookkeeping mutated cross-thread without sync |
| AF31 | min | U4 | fixed | AN | ensureModelReady reachable concurrently → races whisper var |
| AF32 | min | U5 | fixed | CP | Auto-transcription reports `.completed` even when all failed |
| AF33 | min | U4 | fixed | AN | unloadModel has no callers — whisper model stays resident |
| AF34 | min | U4 | fixed | n/a | averageHeartRate Int 0/0 trap — **Android has no HR samples** |
| AF35 | min | U4 | fixed | AN | Altitude reset/status binders lack main-thread dispatch |
| AF36 | min | U4 | fixed | AN | StepCounter mutable state shared main/CMPedometer w/o sync |
| AF37 | min | U3 | fixed | AN | startRecording leaks consumer when recordings dir can't be created |
| AF38 | min | U2 | fixed | CP | isEnhanced=true set before enhancement runs; failure ignored |
| AF39 | min | U7 | fixed | CP | Adaptive `.ink` shadow on weather vignette → dark-mode halo |
| AF40 | min | U3 | fixed | AN | WhisperPlayer preview cancellation leaks consumer |
| AF41 | min | U7 | fixed | CP | Privacy manifest under-declares transmitted data |
| AF42 | min | U4 | fixed | AN | Options-button phaseAnimator pulses for the entire walk |
| AF43 | min | U4 | fixed | AN | proximityAnnotations recomputed every body eval (≥1/s) |
| AF44 | min | U7 | fixed | CP | Adaptive `.ink` shadow on active-walk audio buttons |
| AF45 | min | U5 | fixed | CP | insufficientPermission signal has no subscriber (no feedback) |
| AF46 | min | U4 | fixed | CP | Full route re-published/re-mapped every GPS sample (O(n²) cumulative) |
| AF47 | min | U6 | fixed | CP | Meditation options only via undiscoverable long-press; no VoiceOver |
| AF48 | min | U6 | fixed | CP | MeditationView never reads Reduce Motion |
| AF49 | min | U6 | fixed | CP | Breath count is a bare unlabeled number for VoiceOver |
| AF50 | min | U6 | fixed | CP | GoshuinFAB has no accessibilityLabel |
| AF51 | min | U4 | fixed | AN | InkScrollView recomputes scans/colors every body eval |
| AF52 | min | U4 | fixed | AN | Home ink scroll is non-lazy (whole history instantiated) |
| AF53 | min | U6 | fixed | CP | Journey header stat cycling is a bare, trait-less tap |
| AF54 | min | U6 | fixed | CP | Fixed-size fonts don't scale with Dynamic Type |
| AF55 | min | U7 | fixed | CP | Adaptive `.ink` shadow on archived-walks card → dark glow |
| AF56 | min | U7 | fixed | CP | Adaptive `.ink` shadow on journal walk dots → dark halo *(Android: fixed in #176)* |
| AF57 | min | U6 | fixed | CP | Walk dots: no button trait, missing 44pt tap frame |
| AF58 | min | U6 | fixed | CP | Walk mode selector conveys selection only by color |
| AF59 | min | U6 | fixed | CP | WalkStartView Reduce Motion branch still breathes |
| AF60 | min | U5 | fixed | CP | Seal share sets two sheet items in one transaction → one dropped |
| AF61 | min | U4 | fixed | AN | RootCoordinatorViewModel retain cycle via assign(to:on:) |
| AF62 | min | U6 | fixed | CP | Seal reveal: unlabeled image, gesture-only share/dismiss |
| AF63 | min | U6 | fixed | CP | Audio waveform scrubber has no VoiceOver/adjustable action |
| AF64 | min | U6 | fixed | CP | Permission grant buttons ~32pt; granted state unlabeled |
| AF65 | min | U6 | fixed | CP | Activity timeline bar invisible to VoiceOver |
| AF66 | min | U6 | fixed | CP | Transcription icon buttons 32pt + misleading fallback names |
| AF67 | min | U7 | fixed | CP | Voice-recordings count badge has no font modifier |
| AF68 | min | U7 | fixed | CP | `fog` token fails WCAG contrast for secondary text |
| AF69 | min | U7 | fixed | CP | Adaptive `.ink` shadow on celestial vignette capsule |
| AF70 | min | U4 | fixed | AN | Map onStyleLoaded one-shot observer strongly captures coordinator |
| AF71 | min | U7 | fixed | CP | Adaptive `.ink` shadow on proximity notification banner |
| AF72 | min | U6 | fixed | CP | Scenery animations have no Reduce Motion gate |
| AF73 | min | U7 | fixed | CP | Widget colors don't match the app asset catalog |
| AF74 | min | U7 | fixed | CP/IO | Live Activity (IO) + home widget (CP) use system fonts |
| AF75 | triv | U7 | fixed | n/a | Changelog.strings empty → "NIL" — iOS localization only |
| AF76 | triv | U2 | fixed | CP | Event save results discarded during import |
| AF77 | triv | U2 | fixed | CP | IntentionVoiceRecorder.transcribe races recordingURL (deletes file) |
| CU1 | maj? | U2 | fixed | CP | (uncertain) Tended import deletes-then-reinserts in separate txns |
| CU2 | min? | U4 | fixed | AN | (uncertain) asBackgroundPublisher de-serializes the builder pipeline |
| CU3 | maj? | U4 | fixed | AN | (uncertain) ConstellationOverlay redraws full-screen Canvas @60fps |
| P1–P14 | polish | U4/U7/U2 | mostly fixed (P8/P11 accepted) | CP/AN | Leak cleanup, off-token spacing/radii/fonts, dead code, timer modes |
| X1–X5 | refuted | — | refuted | n/a | False positives (iOS disposition log) — no Android action |

**Reconciliation notes (U1):**
- **AF18 is `reverted`** (`28808d3 revert(weather): restore WeatherKit REST fallback`). Do NOT treat "remove WeatherKit key" as iOS behavior. Android uses Open-Meteo (no key) → `n-a` for the key itself; the live Android question is the Mapbox/secret-in-AAB check (U7).
- **AF19 + AF41 (privacy) are `fixed`** — only the key-removal half of `38d13c1` was reverted; the manifest truth-telling stuck.
- A launch `versionLock` un-gate was reverted (`6e8a9ed`) — sub-item of #42, no Android surface.
- Two parity data points already closed on Android: **AF56** (walk-dot dark halo) = our #176 dot-shadow fix; iOS's `73046ba` journal pluralization = our #175; iOS's `a4f81c3` CI-flake timeout-widen = our #171 (Android went further w/ canonical helpers).
- Issues #41/#42/#43: #43 = onboarding delight (U5); #42 = CoreStore launch perf (U4, IO→Room); #41 = (resolve during U-area verification).

---

## Per-area verdicts

### Resilience & Data (U2)

| ID | verdict | Android evidence | action |
|----|---------|------------------|--------|
| AF1 | re-justify | No JSON checkpoint; Room writes incrementally; recovery sets `endTimestamp` via `finishWalkAtomic` (`WalkRepository.kt:109`), recovery emits `Finished` not `Idle` (`WalkControllerImpl.kt:283`) | none — tear-down-before-commit failure mode absent |
| AF2 | match | Sweeper is a daily `KEEP` WorkManager job (`OrphanSweeperScheduler.kt:23`), not launch-coupled; skips active walks; tested (`OrphanRecordingSweeperTest.kt:208/252/309`) | none |
| AF3 | match | `clearAll()` roots are disjoint: soundscape `filesDir/audio/` vs voice-guide `filesDir/voice_guide_prompts/` (`SoundscapeFileStore.kt:31`, `VoiceGuideFileStore.kt:41`) | none |
| AF7 | match | All 7 child entities `onDelete = ForeignKey.CASCADE`; tested (`WalkDataLayerTest.kt:146`, `PilgrimDatabaseMigrationTest.kt:114`) | none |
| AF12 | re-justify | No thermal/battery power-tier or main-thread checkpoint timer; durability = Room incremental + AlarmManager watchdog | none |
| AF26 | re-justify | Write failure logged (`TranscriptionRunner.kt:56`) AND re-enqueued by sweeper case-(d) + WM retry — retry path exists | none (optional per-row signal) |
| AF27 | match | `NoWalksFound` thrown only on empty; real failure → `FileSystemError(e)` (`PilgrimPackageBuilder.kt:69,119`) | none |
| **AF28** | **close-the-gap** | `readWalks()` log-and-skips undecodable files (`PilgrimPackageImporter.kt:184`); `ImportSummary` has no `skipped` field → partial import invisible | add `skipped` count + surface in import result |
| AF38 | n-a | No audio-enhancement pipeline; `isEnhanced` is import-roundtrip-only flag | none |
| AF76 | n-a | No event-collection import path (manifest events dropped by design); per-walk `WalkEvent` rows import atomically | none |
| AF77 | match-by-reading | `SpeechRecognizer` (not record-to-file) — no `recordingURL` race possible; but `IntentionVoiceController` lifecycle has no concurrency test | add lifecycle test |
| CU1 | match-by-reading | Tended import is delete-then-reinsert in ONE `withTransaction` (`PilgrimPackageImporter.kt:213`) — atomic; but no real-Room-DB test (VM test fakes importer) | add integration test |

### Audio & interruptions (U3)

| ID | verdict | Android evidence | action |
|----|---------|------------------|--------|
| AF4 | re-justify | No `AVAudioSession.mode`; each consumer owns an `AudioFocusRequest`; `VoiceRecorder.stop()` abandons immediately (`VoiceRecorder.kt:124`) | none |
| AF5 | needs-device | Resume logic present + state honest (`ExoPlayerSoundscapePlayer.kt:318/384`); no focus-change test | add `ShadowAudioManager` focus test → then device |
| AF6 | match | `stop()` fires `onFinished` once (`ExoPlayerVoiceGuidePlayer.kt:164`); tested | none |
| **AF11** | **close-the-gap** | `VoiceRecorder.start()` uses no-listener `requestTransient()` (`:89`); no telephony/interruption handler → recording not finalized/surfaced on Siri/alarm/declined call | wire a focus-loss listener that finalizes the recording (needs-device to confirm) |
| AF21 | n-a | No crossfade path; `play()` always sets `Playing` (`ExoPlayerSoundscapePlayer.kt:227`) | none |
| AF22 | n-a | No crossfade asyncAfter; swap is synchronous stop-then-play | none |
| **AF24** | **close-the-gap** | Ending meditation spawns a fresh walk scheduler that unconditionally clears `_isPaused` (`VoiceGuideOrchestrator.kt:201`) — a user-paused guide resumes | add `wasPausedByMeditation` distinction |
| AF37 | match | Focus requested last, after the dir-create guard (`VoiceRecorder.kt:70→89`) | none |
| AF40 | needs-device | Focus acquired after cancellable download; refcounted abandon; no `WhisperPlayer` test | add focus-refcount test |
| interrupt-a (re-entrancy) | re-justify / needs-device | Lock-free `getAndSet` abandon + `mainHandler.post` (non-re-entrant); no deadlock surface | device-confirm listener paths |
| interrupt-b (resume race) | needs-device | No `didBecomeActive`; resume driven solely by `AUDIOFOCUS_GAIN` + one-shot latch | device-confirm single resume |

### Performance & memory/leaks (U4)

| ID | verdict | Android evidence | action |
|----|---------|------------------|--------|
| AF8 | n-a | No `WalkBuilder` closure subsystem; `@Singleton WalkControllerImpl` + `WalkAccumulator` value objects | none |
| **AF9 / AF46** | **close-the-gap** | `WalkViewModel.routePoints` re-maps full list per sample (`:484`); live polyline re-uploads all points (`PilgrimMap.kt:560`). Milder than iOS (off-main, no segment rebuild) | incremental append for live route/polyline |
| **AF10** | **close-the-gap** | `audioLevel` collected at screen scope (`ActiveWalkScreen.kt:241`) → metering recomposes the map composable; partly blunted by annotation gates | hoist `audioLevel` read into the waveform leaf |
| AF13 | n-a | No JSON checkpoint; incremental Room writes | none |
| AF14 | n-a | No battery/thermal GPS power tier (fixed `PRIORITY_HIGH_ACCURACY`) | none |
| AF15/AF16 | match | Progress tick + focus released on every teardown path; `onCleared()` stops (`ExoPlayerVoicePlaybackController.kt:161`, `WalkSummaryViewModel.kt:1447`) | none |
| AF17 | match | Route/segments cached once in `@Immutable WalkSummary` via `stateIn(Eagerly)` | none |
| AF20 | match | Every annotation manager has a snapshot-rebuild gate (`PilgrimMap.kt:534/659/690/779`) | none |
| AF30 | match-by-reading | Fetch bookkeeping mutex-guarded (`GeoCacheService.kt:177`); minor `invalidateLastFetch` outside mutex (atomic ref writes) | optional: move invalidate under mutex |
| AF31 | match-by-reading | `synchronized(nativeLock)` over load+call; `@Volatile` handle (`WhisperCppEngine.kt:35`); tested | none |
| **AF33** | **close-the-gap** | Whisper model never unloaded + `TranscriptionWorker` runs in main process → ~75MB resident (`WhisperCppEngine.kt:14`) | unload-after-batch or dedicated transcription process |
| AF34 | n-a | No heart-rate subsystem | none |
| AF35 | n-a | No barometer; altitude is GPS-derived, persisted via the same mutex | none |
| AF36 | match-by-reading | `@Volatile` on all cross-thread fields; tested (`StepCounter.kt:65`); minor `+=` compound-op (safe: writer runs post-unregister) | optional harden |
| AF42 | n-a | No infinite button pulse (static `OverlayCircleButton`) | none |
| AF43 | match | Proximity pins via `combine(...).distinctUntilChanged().stateIn` (`WalkViewModel.kt:230`) | none |
| **AF51 / AF52** | **close-the-gap** | Home is `Column(verticalScroll)` + `forEachIndexed` — eager whole-history + per-item animations (`HomeScreen.kt:140`); virtualization deferred ("bucket 14-D") | port to `LazyColumn` + cull off-screen scenery |
| AF61 | n-a | All VMs via `hiltViewModel()`; no inline-constructed root coordinator | none |
| AF70 | match | `onRelease` → `onDestroy()` + nulls refs (`PilgrimMap.kt:817`) | none |
| CU2 | n-a | Pipeline serialized via `dispatchMutex` (`WalkControllerImpl.kt:401`) | none |
| CU3 | re-justify | `ConstellationOverlay` 60fps gated on reduce-motion + constellation-appearance + non-walk routes (stronger than iOS) | optional 30fps throttle |
| #42 | n-a | Room `version=8`, lightweight migrations, fresh installs open at v8 — no version-chain probe | none |

### Onboarding delight & honest feedback (U5)

| ID | verdict | Android evidence | action |
|----|---------|------------------|--------|
| **#43 Wander Zoom** | **close-the-gap** | `WelcomeScreen.kt` has the exit fade but NO logo zoom-on-Begin stage | implement zoom (1.0→1.4 easeOut 0.4s), `!reduceMotion` bypass |
| **#43 Permission ritual** | **close-the-gap** | No per-permission bell-played persistence, no checkmark pulse, no bell-on-grant in `PermissionsScreen`/`PermissionsViewModel` | add ritual (DataStore flag + bell@0.5 + spring pulse gated on reduce-motion) |
| AF23 | match | `VoiceGuideDownloadWorker` returns `Result.retry()` on all-fail (`:82`) → `Failed` state (`VoiceGuideCatalogRepository.kt:100`) | verify picker surfaces retry (needs-device) |
| **AF32** | **close-the-gap** | `TranscriptionRunner` returns `success(0)` when all fail (`:77`); worker maps to `success()` → no "all failed" signal | return failure / surface retry when `count==0 && pending.isNotEmpty()` |
| **AF45** | **close-the-gap** | Location permission failure has no surface: `@SuppressLint("MissingPermission")` in `FusedLocationSource`; only mic has `emitPermissionDenied` (`WalkViewModel.kt:540`) | catch `SecurityException` → surface alert + Settings deep-link |
| AF60 | n-a | Goshuin share = direct `startActivity` (`WalkSummaryScreen.kt:730`); no two-sheet race | none |

### Accessibility (U6)

| ID | verdict | Android evidence | action |
|----|---------|------------------|--------|
| AF29 | n-a | Podcast is a `SettingNavRow` link, not a consent checkbox (`ConnectCard.kt:35`) | none |
| **AF47** | **close-the-gap** | Meditation-options long-press is gesture-only (`MeditationScreen.kt:494`), invisible to TalkBack | add `CustomAccessibilityAction` on the breathing circle |
| **AF48** | **close-the-gap** | `MeditationScreen` never reads `LocalReduceMotion`; ceremony tweens + `BreathingCircle` always animate | gate animations on reduce-motion |
| **AF49** | **close-the-gap** | Breath count `Text("$breathCount")` unlabeled (`:696`) | add `contentDescription = "$n breaths"` |
| AF50 | match | FAB has `contentDescription` (`HomeScreen.kt:727`) | needs-device confirm |
| **AF53** | **close-the-gap** | Stat cycling `.clickable` has no Role/description/state (`JourneySummaryHeader.kt:87`) | add `Role.Button` + state description |
| AF54 | match | Type scale all `.sp` (`Type.kt:32`) | needs-device (Canvas `drawText` in SealRenderer not sp — spot-check) |
| AF57 | close-the-gap (small) | Dots have `contentDescription` + ≥48dp halo but no `Role.Button` (`WalkDot.kt:97/122`) | add `role = Role.Button` |
| **AF58** | **close-the-gap** | Mode selector conveys selection by color only (`WalkStartScreen.kt:416`) | add `Role.Tab`/`selected` semantics |
| AF59 | match | Reduce-motion gated (`WelcomeScreen.kt:103/108/117`) | needs-device confirm |
| **AF62** | **close-the-gap** | Seal-reveal overlay unlabeled + gesture-only (`SealRevealOverlay.kt:162`) | add description + `CustomAccessibilityAction("Dismiss")` |
| **AF63** | **close-the-gap** | Waveform scrubber Canvas gesture-only, no slider semantics (`WaveformBar.kt:34`) | add `Role.Slider` + seek custom actions |
| AF64 | close-the-gap (small) | Granted state labeled; but granted `Icon` 32dp tap target (`PermissionsCard.kt:175`) | enforce 48dp interactive target |
| AF65 | match | Timeline bar has per-segment `CustomAccessibilityAction` (`WalkActivityTimelineCard.kt:195`) | minor: add container description |
| **AF66** | **close-the-gap** | Transcription/play icon buttons 32dp (`VoiceRecordingsSection.kt:177`, `:414/428/438`) | `minimumInteractiveComponentSize()` (keep 32dp visual) |
| AF72 | match | Scenery + ripple + logo gated on reduce-motion (`sceneryTimeSeconds()`, `WalkDot.kt:313`) | needs-device confirm |

### Design-system & Security/Privacy (U7)

| ID | verdict | Android evidence | action |
|----|---------|------------------|--------|
| AF25 | match | Cairn digits use `pilgrimType.displayLarge` (Cormorant); only the CJK kanji uses system font (justified) (`CairnDetailSheet.kt:97`) | none |
| AF39/AF44/AF55/AF69/AF71 | match | Compose `Modifier.shadow` defaults to black, not `.ink`; audited surfaces have no shadow or an intentional turning-accent corona (`WalkVignette.kt:202`) | none — the adaptive-ink-halo class can't occur |
| AF56 | re-justify | Dot shadow uses `ink@0.15` intentionally (iOS parity, PR #176) (`WalkDot.kt:170`) | none unless iOS changes the dot token |
| AF67 | match | Count badge uses `pilgrimType.caption` (`VoiceRecordingsSection.kt:113`) | none |
| **AF68** | **close-the-gap** | `fog` `#B8AFA2`/`#6B6359` (iOS-verbatim) computes 1.58–2.97 contrast across surfaces, all < WCAG 4.5 (`Color.kt:29/45`); 167 secondary-text sites | darken `fog` (coordinate w/ iOS so palettes stay in sync) |
| AF73 | match | Widget hex exactly matches `Color.kt` tokens (`PilgrimWidget.kt:327`) | none (optional drift test) |
| AF74 | close-the-gap (low) | Glance widget `TextStyle` sets no `fontFamily` → system font (`PilgrimWidget.kt:165`); Live Activity = n-a (iOS-only) | low-priority: Glance custom-font (limited support) |
| AF18 | n-a | Open-Meteo (`OpenMeteoClient.kt`); no WeatherKit key | none |
| **AF19** | **close-the-gap** | Reliquary string promises photos "never copied or uploaded" but Walk Share base64-uploads them (`strings.xml:248` vs `SharePayload.kt:74`/`ShareService.kt:48`) | reword to scope the promise to explicit share/export |
| **sec-secrets-in-aab** | **match (clean)** ✅ | Only `pk.*` (referrer-restricted, ships by design) in `buildConfigField` (`build.gradle.kts:40`); `sk.*` is Gradle-only (`settings.gradle.kts:32`); no secret in tree/history | none (optional CI artifact scan) |
| **AF41 / sec-data-safety** | **close-the-gap (deferred)** | Off-device: GPS routes + journal text + photos + feedback + collective deltas, all w/ persistent UUID `X-Device-Token`; likely under-declared in Play data-safety | reconcile Play data-safety form — **action deferred behind the gate** |
| **sec-device-token** | **close-the-gap** | Token survives device-to-device transfer (`data_extraction_rules.xml:10` includes `domain=file`) → defeats rate-limit fairness; cloud-backup correctly excluded | exclude the token DataStore file from device-transfer |
| **sec-log-leak** | **close-the-gap (minor)** | `MapboxRefererInterceptor.Log.d` ungated in release (`:60`) — no token leak (path excludes query) but `Log.*` not stripped (`proguard-rules.pro`) | gate behind `BuildConfig.DEBUG` or ProGuard-strip `Log.d/v` |

---

## Rollup (U8)

**Headline:** Android is in strong shape against iOS PR #45. The data/resilience/leak/concurrency findings — the scariest on iOS — are mostly **already handled or structurally absent** on Android (Room incremental writes + `CASCADE` + atomic `withTransaction`; daily WorkManager sweep; Hilt/Compose scoping instead of retain cycles; `dispatchMutex`-serialized pipeline; `synchronized` JNI lock). **No P0/critical Android gap.** Security secrets are **clean** (no key/secret ships). Three iOS items are already closed on Android (#176 dot shadow = AF56, #175 plural, #171 flake).

**Verdict counts (≈99 items):** `match` / `re-justify` / `n-a` (no action) ≈ 55 · `match-by-reading` (needs a test) ≈ 6 · `needs-device` (on-device/TalkBack pass) ≈ 8 · **`close-the-gap` (real Android work) ≈ 27**.

### Confirmed gaps — grouped by area & effort

**Quick / mechanical (high value, low risk)**
- **AF19** — reword the reliquary "never uploaded" string (it's false; Share uploads photos). *(legal-honesty; do first)*
- **AF28** — add a `skipped` count to `ImportSummary` so partial imports aren't silently "success".
- **AF57 / AF64 / AF66 / AF49 / AF53** — small a11y adds: `Role.Button` on dots, 48dp tap targets on permission/transcription icons, label the breath count, role+state on stat cycling.
- **sec-log-leak** — gate `Log.d` behind `BuildConfig.DEBUG` (or ProGuard-strip).
- **sec-device-token** — exclude the device-token DataStore file from device-transfer.

**Behavioral fixes (medium)**
- **AF11** — `VoiceRecorder` registers no audio-focus-loss listener → a mid-recording interruption isn't finalized/surfaced. *(real, audible)*
- **AF24** — ending meditation force-resumes a manually-paused voice guide (add `wasPausedByMeditation`).
- **AF32** — auto-transcription reports success even when every recording failed → surface failure/retry.
- **AF45** — location-permission failure during walk setup has no user feedback → surface an alert.
- **AF47 / AF48 / AF58 / AF62 / AF63** — a11y: TalkBack action for meditation options; reduce-motion gate on MeditationScreen; mode-selector selection semantics; seal-reveal label + dismiss action; waveform slider semantics.
- **AF68** — `fog` token fails WCAG contrast (coordinate the darken with iOS).

**Onboarding delight (#43) — net-new UX (medium)**
- **#43 Wander Zoom** on Begin + **permission-grant ritual** (bell + checkmark pulse, reduce-motion-aware, bell-once-per-permission persisted). Both absent on Android.

**Perf (medium; some already TODO)**
- **AF9 / AF46** — incremental live-route append (stop full re-map/upload per GPS sample).
- **AF33** — unload the whisper model after a transcription batch (~75 MB resident).
- **AF51 / AF52** — virtualize the home journal (`LazyColumn` + cull off-screen scenery) — already the acknowledged "bucket 14-D".
- **AF10** — hoist the 20 Hz metering read into the waveform leaf.

### Deferred behind the gate (not code / not desk)
- **AF41 / sec-data-safety** — reconcile the Play Console data-safety form (declare Location, Photos, free-text content, persistent device-UUID). **Play Console action — do at remediation, not in the sweep.**
- **`needs-device` / `match-by-reading`** — on-device + test-pinning pass: audio interruption family (AF5, AF11, AF40, interrupt-a/b), the a11y TalkBack confirmations (AF50/54/59/72 + the SealRenderer Canvas-text scaling), and add deterministic tests for AF77/CU1/AF30/AF36.

### Open decisions (per the plan's Outstanding Questions — decide now at the gate)
1. **Wait for iOS PR #45 to merge** before F2 remediation, or proceed against the `a4f81c3` snapshot? (Most gaps are Android-native and stable regardless; the photo-string/security items are independent of #45's final state.)
2. **Fold in a systematic Android-native audit pass?** This sweep already surfaced Android-only items the iOS lens wouldn't (the `Log.d` leak, the device-token transfer, the home non-laziness) — a dedicated pass would find more.
3. **Remediation batching** — suggested batches below.
