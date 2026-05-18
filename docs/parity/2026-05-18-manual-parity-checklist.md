# Manual Parity Checklist — iOS v1.6.0 ↔ Android

**Purpose.** Human side-by-side device pass to corroborate the 77 deep-state rows the U7 ship gate accepted as *code-verified + owner-waived* (frozen `pilgrim-ios` is uncapturable for automated deep-state diff — see ledger § "Gate result (R8)"). This upgrades those rows from *code-verified* to *human-corroborated* **without** building/instrumenting the frozen iOS reference (the waiver exists precisely to avoid that).

**Why manual, not automated.** Cross-stack by construction (Mapbox≠MapKit, Open-Meteo≠WeatherKit, Skia≠CoreGraphics) → automated pixel-diff is high-noise and resolves to "a human reviewed it" anyway. The product bar is design/**vibe** (motion landing, rhythm, feel) — a human side-by-side is the correct instrument; pixel-diff is the wrong one. Automation worth doing later is *Android-only* Roborazzi regression tests on static screens (in-repo, no iOS, no freeze conflict) — a self-regression guard, not a parity tool. Not in scope here.

**Scope.** iOS parity target **v1.6.0** (`fcd22553`). Rows already `RESOLVED`/`match` (splash, goshuin.populated, goshuin.share-render) and `stale` (path.wander.vignette) are excluded. Rows iOS ships **after** v1.6.0 are out of scope.

---

## Setup

**Devices.** Two phones (or iOS Simulator + Android emulator), same screen size class if possible. Pre-install: iOS `pilgrim-ios` @ `v1.6.0` (`fcd22553`); Android `pilgrim-android` `main` debug build.

**Identical data — the `.pilgrim` seed (mandatory for seed-gated rows).** Both apps must import the SAME fixture: `docs/parity/fixtures/parity-seed.pilgrim` (16 full walks + 53 archived + 1 modification = "69 walks imported").

Android recipe (verified clean — no pre-existing contamination):
1. `adb shell pm clear org.walktalkmeditate.pilgrim.debug`
2. `adb push docs/parity/fixtures/parity-seed.pilgrim /sdcard/Download/`
3. Launch → complete onboarding (reliable emulator taps) → grant permissions
4. Settings → Data → **Import Data** → SAF roots drawer → Downloads → `parity-seed.pilgrim` → expect **"69 walks imported"**

iOS recipe: import the same `parity-seed.pilgrim` via the iOS Settings → Data → Import flow. (`--demo-mode` ScreenshotDataSeeder is NOT data-identical — do not use for seed-gated rows.)

**Appearance.** Every row check **light + dark + constellation** unless the row's `modes` says otherwise. Toggle dark: `adb shell cmd uimode night yes|no`. Constellation: Settings → Appearance.
**Reduce-motion.** Rows marked `anim` get an extra reduce-motion check (OS reduce-motion ON).

**How to record.** Open both apps to the same state, compare. Tick `M` (match) or write the divergence inline. **Any real divergence found = a new parity finding** → add it to `docs/parity/2026-05-15-parity-ledger.md` as `close-the-gap` (don't fix inline during the pass; log then triage).

Legend: `M?` = match verdict — `✓` match · `~` minor/acceptable (note why) · `✗` real gap (log to ledger). `iOS ref` is under `../pilgrim-ios/`.

---

## A. Setup / Onboarding

Reach iOS: fresh install (wiped) → Welcome → Permissions. Android: `pm clear` then launch (pre-import).

| id | reach (precondition) | iOS ref | compare | modes | M? |
|---|---|---|---|---|---|
| setup.welcome.entrance | wiped, first launch | Scenes/Setup/Welcome/WelcomeView.swift | full entrance ritual sequence — stagger, timing, copy | L/D/C +rm | ☐ |
| setup.welcome.reduce-motion | wiped + OS reduce-motion ON | WelcomeAnimationState.swift | terminal frame (no anim) | L/D/C | ☐ |
| setup.breath.transition | after Permissions complete (now shipped #112) | Scenes/Setup/BreathTransitionView.swift | parchment + warmth + logo inhale/exhale, soft haptic; reduce-motion = 0.5s hold | L/D/C +rm | ☐ |
| setup.permissions.initial | wiped, nothing granted | Scenes/Setup/Permissions/PermissionsView.swift | headline/subtitle, 3 cards + Android-only notification card, optional badges, button labels | L/D/C | ☐ |
| setup.permissions.partial | location coarse-only / blocked | PermissionsView.swift | degraded copy + "Open settings" affordance | L/D/C | ☐ |
| setup.permissions.granted | all granted | PermissionsView.swift | granted ticks → auto-advance | L/D/C | ☐ |

## B. Path tab

iOS: launch → Path tab. Android: Path screen.

| id | reach | iOS ref | compare | modes | M? |
|---|---|---|---|---|---|
| path.wander.idle | Path → Wander | Scenes/Home/WalkStartView.swift | idle Wander, moon glyph, logo (D-mode logo treatment is a known residual) | L/D/C +rm | ☐ |
| path.wander.recovery-banner | stale in-progress walk exists, return to Path | WalkStartView.swift | recovery banner + stale-walk swipe | L/D/C +rm | ☐ |

## C. Journal tab (seed required: ≥20 walks → use the `.pilgrim` seed)

iOS/Android: Journal/Home tab after seed import.

| id | reach | iOS ref | compare | modes | M? |
|---|---|---|---|---|---|
| journal.home.loading | cold open Journal | Scenes/Home/HomeView.swift | loading state | L/D/C | ☐ |
| journal.inkscroll.populated | seed imported | Scenes/Home/InkScrollView.swift | calligraphy path + dots + scenery + journey header — **motion** is the residual; check the path-draw feel | L/D/C +rm | ☐ |
| journal.inkscroll.lunar | seed imported | InkScrollView+LunarMarkers.swift | lunar markers placement on path | L/D/C | ☐ |
| journal.inkscroll.milestone | seed has a milestone walk | MilestoneMarkerView.swift | milestone marker dawn-halo | L/D/C | ☐ |
| journal.dot.standard | ≥1 walk | Scenes/Home/WalkDotView.swift | favicon / activity arcs / halo | L/D/C +rm | ☐ |
| journal.dot.newest | ≥1 walk | WalkDotView.swift | newest dot ripple/breath anim | L/D/C +rm | ☐ |
| journal.dot.shared | a shared walk | WalkDotView.swift | shared stone ring | L/D/C | ☐ |
| journal.dot.archived | an archived walk | WalkDotView.swift | hollow fog ring | L/D/C | ☐ |
| journal.expandcard.open | tap a walk dot | Scenes/Home/HomeView.swift | expand-card overlay content + open anim | L/D/C +rm | ☐ |
| journal.expandcard.archived | tap an archived walk | HomeView.swift | "Released" degraded card | L/D/C | ☐ |
| journal.fab.seal | ≥1 walk | Scenes/Goshuin/GoshuinFAB.swift | FAB shows latest seal thumb | L/D/C | ☐ |
| journal.fab.hidden | open expand card | GoshuinFAB.swift | FAB hides while card open (anim) | L/D/C +rm | ☐ |
| journal.turningbanner | device clock = solstice/equinox date | HomeView.swift | turning-day banner | L/D/C | ☐ |

## D. Active Walk (precondition: start a live walk — walk a route or mock-location)

Both: Path → start a Wander walk; let GPS accumulate.

| id | reach | iOS ref | compare | modes | M? |
|---|---|---|---|---|---|
| walk.active.tracking | live walk | Scenes/ActiveWalk/ActiveWalkView.swift | map + minimized stats sheet (C: no nebulae) | L/D/C +rm | ☐ |
| walk.active.stats-expanded | live walk, expand sheet | WalkStatsSheet.swift | expanded stats sheet | L/D/C +rm | ☐ |
| walk.active.peek-hint | first walk, sheet minimized | WalkStatsSheet.swift | one-time swipe-hint wink | L/D/C +rm | ☐ |
| walk.active.paused | live walk → pause | ActiveWalkView.swift | paused state | L/D/C | ☐ |
| walk.active.vignette | live walk, weather present | CelestialVignetteView.swift / WeatherVignetteView.swift | weather+celestial bottom vignette | L/D/C | ☐ |
| walk.active.greeting | walk start | ActiveWalkView.swift | weather/celestial greeting overlay | L/D/C +rm | ☐ |
| walk.active.sparkline | live walk, >10 positive pace samples (shipped #112) | Scenes/WalkSummary/PaceSparklineView.swift + ActiveWalkView LivePaceSparklineView | ambient pace line above minimized sheet — appears only after enough movement | L/D/C +rm | ☐ |
| walk.options.idle | pre-walk, open options | Scenes/ActiveWalk/WalkOptionsSheet.swift | intention-only, leaf icon (header-align is a known residual) | L/D/C | ☐ |
| walk.options.inwalk | live walk, open options | WalkOptionsSheet.swift | waypoint/whisper/stone options | L/D/C | ☐ |
| walk.intention.sheet | open intention sheet | Scenes/ActiveWalk/IntentionSettingView.swift | field + counter + Suggested/Recent chips + **Voice** mic row (shipped #112; iOS uses on-device whisper, Android SpeechRecognizer — see privacy note) | L/D/C | ☐ |
| walk.waypoint.sheet | live walk → waypoint | WaypointMarkingSheet.swift | waypoint marking | L/D/C | ☐ |
| walk.whisper.sheet | live walk, whisper unlocked | WhisperPlacementSheet.swift | whisper category picker | L/D/C | ☐ |
| walk.stone.sheet | live walk, stone unlocked | StonePlacementSheet.swift | stone placement | L/D/C | ☐ |
| walk.turning.card | turning date + live walk | TurningRitualCard.swift | turning ritual card | L/D/C +rm | ☐ |
| walk.meditation.timer | live walk → begin meditation | Scenes/ActiveWalk/MeditationView.swift | idle→meditating→done timer (C: no nebulae) | L/D/C +rm | ☐ |

## E. Seal Reveal & Walk Summary (precondition: finish a walk)

Both: finish a walk → reveal → summary. For map rows use a walk with a route + pins.

| id | reach | iOS ref | compare | modes | M? |
|---|---|---|---|---|---|
| sealreveal.phases | finish a walk | Scenes/SealReveal/SealRevealView.swift | hidden→pressing→revealed + haptics — **feel/timing** is the point | L/D/C +rm | ☐ |
| sealreveal.milestone | finish a milestone walk | SealRevealView.swift | milestone celebration variant | L/D/C +rm | ☐ |
| summary.reveal | finish a walk | Scenes/WalkSummary/WalkSummaryView.swift | cinematic reveal sequence (C: no nebulae) | L/D/C +rm | ☐ |
| summary.loaded | open a finished walk | WalkSummaryView.swift | all stats laid out | L/D/C | ☐ |
| summary.map | walk w/ route + pins | WalkSummaryView+Map.swift | annotations: start/end/med/voice/photo/waypoint/whisper/cairn (Mapbox≠MapKit — judge *structure*, not pixels) | L/D/C | ☐ |
| summary.map.no-token | (Android no-token build) | WalkSummaryView+Map.swift | "Map unavailable" fallback — intentional stub | L/D/C | ☐ |
| summary.lightreading | finished walk | Views/WalkLightReadingCard.swift | koan/moon/celestial card | L/D/C | ☐ |
| summary.timeline | walk w/ activities | ActivityTimelineBar.swift | timeline bar + tap-to-zoom | L/D/C | ☐ |
| summary.activitylist | walk w/ activities | ActivityListView.swift | activity list | L/D/C | ☐ |
| summary.insights | walk w/ activities | ActivityInsightsView.swift | insights | L/D/C | ☐ |
| summary.elevation | walk w/ altitude | ElevationProfileView.swift | elevation profile | L/D/C | ☐ |
| summary.voicerow.read | walk w/ long transcript | VoiceRecordingRow.swift | 7-line clamp + show-more + pencil | L/D/C | ☐ |
| summary.voicerow.edit | walk w/ recording | VoiceRecordingRow.swift | inline edit mode | L/D/C | ☐ |
| summary.voicerow.pending | walk w/ recording (no speech) | VoiceRecordingRow.swift | pending / no-speech placeholder | L/D/C | ☐ |
| summary.reliquary.grid | walk w/ photos | Reliquary/PhotoReliquarySection.swift | photo reliquary section | L/D/C | ☐ |
| summary.reliquary.carousel | walk w/ photos | Reliquary/PhotoCarouselView.swift | carousel + activate/commit pin (anim) | L/D/C +rm | ☐ |
| summary.reliquary.preview | walk w/ photos | Reliquary/PhotoPreviewSheet.swift | preview sheet | L/D/C | ☐ |
| summary.favicon.selector | finished walk | FaviconSelectorView.swift | favicon selector | L/D/C | ☐ |
| summary.sharingbuttons | finished walk | Views/WalkSharingButtons.swift | share buttons row | L/D/C | ☐ |
| share.preview | finished walk → share | Scenes/WalkShare/WalkSharePreviewView.swift | route-shape share preview | L/D/C | ☐ |
| share.webview | finished walk → share | WalkShareView.swift | share webview (worker) | L/D/C | ☐ |

## F. Goshuin (seed required)

| id | reach | iOS ref | compare | modes | M? |
|---|---|---|---|---|---|
| goshuin.empty | wiped (no seals) | Scenes/Goshuin/GoshuinView.swift | empty state | L/D/C | ☐ |
| goshuin.milestone | seed milestone walk | GoshuinMilestones.swift | milestone seal halo + label | L/D/C | ☐ |
| goshuin.archived-ghost | archived walk | GoshuinPageView.swift | archived seal excluded/ghosted | L/D/C | ☐ |
| goshuin.statsheader | ≥1 + archived | PracticeSummaryHeader.swift | walks·dist·med incl. archived (presentation-model = known residual) | L/D/C | ☐ |
| goshuin.page-indicators | ≥7 walks | GoshuinPageView.swift | iOS TabView dots vs Android LazyVerticalGrid — **known divergence**, judge acceptability | L/D/C | ☐ |

## G. Settings (mostly no precondition; some seeded/state-gated)

| id | reach | iOS ref | compare | modes | M? |
|---|---|---|---|---|---|
| settings.practice | Settings | SettingsCards/PracticeCard.swift | intention/celestial/zodiac/units/hemisphere/collective/reliquary | L/D/C | ☐ |
| settings.atmosphere | Settings | SettingsCards/AtmosphereCard.swift | appearance nav row + sounds | L/D/C | ☐ |
| settings.voice | Settings | SettingsCards/VoiceCard.swift | voice card | L/D/C | ☐ |
| settings.permissions | Settings | SettingsCards/PermissionsCard.swift | permissions card | L/D/C | ☐ |
| settings.data | Settings | SettingsCards/DataCard.swift | data card | L/D/C | ☐ |
| settings.connect | Settings | SettingsCards/ConnectCard.swift | connect card | L/D/C | ☐ |
| settings.bellpicker | Settings → Sound → bell picker (bells downloaded) | SoundSettingsView.swift | per-id preview rows | L/D/C | ☐ |
| settings.voiceguide | Settings, Voice Guide ON | VoiceGuideSettingsView.swift | download/delete | L/D/C | ☐ |
| settings.voiceguide.picker | Voice Guide ON | VoiceGuideSettingsView.swift | pack picker + progress | L/D/C | ☐ |
| settings.recordings | walks w/ recordings | RecordingsListView.swift | list + swipe actions | L/D/C | ☐ |
| settings.export-confirm | ≥1 walk → Export | ExportConfirmationSheet.swift | export confirmation sheet | L/D/C | ☐ |
| settings.journey-viewer | ≥1 walk | JourneyViewerView.swift | journey viewer webview | L/D/C | ☐ |
| settings.journey-editor | ≥1 walk | JourneyEditorView.swift | edit-journey webview | L/D/C | ☐ |
| settings.about | Settings → About | Scenes/Settings/AboutView.swift | hero/pillars/data-sources/open-source/motto — **motion** residual; logo breath | L/D/C +rm | ☐ |
| settings.about.iconswitch | tap logo | AboutView.swift | icon-switcher dialog (constellation icon) | L/D/C | ☐ |
| settings.practiceheader | ≥1 walk | PracticeSummaryHeader.swift | cycling stats + milestone (anim) | L/D/C +rm | ☐ |

## H. Prompts — ⚠ confirm Android surface exists first

`prompts.*` ledger target = "(verify Android equivalent)". Before comparing, confirm Android has a Prompts surface at all (`grep -ri prompt app/src/main/.../ui`). If absent → this is a **real missing-surface gap** (parity-by-absence), log to ledger as `close-the-gap`, not a visual diff.

| id | reach | iOS ref | compare | M? |
|---|---|---|---|---|
| prompts.list | iOS Prompts | Scenes/Prompts/PromptListView.swift | confirm Android equivalent / list | ☐ |
| prompts.detail | iOS Prompt detail | PromptDetailView.swift | detail | ☐ |
| prompts.editor | iOS custom prompt editor | CustomPromptEditorView.swift | editor | ☐ |

## I. Cross-cut overlays

| id | reach | iOS ref | compare | modes | M? |
|---|---|---|---|---|---|
| overlay.constellation | constellation mode on any screen | Views/ConstellationOverlay.swift | stars/nebulae/cosmic/shooting | C +rm | ☐ |
| overlay.constellation.reduce | constellation + reduce-motion | ConstellationOverlay.swift | static frame | C | ☐ |
| overlay.proximity | live walk, near a placed whisper | Views/ProximityNotificationView.swift | proximity whisper notification | L/D/C +rm | ☐ |
| overlay.pilgrimlogo | any logo surface (default/per-guide/constellation) | Views/PilgrimLogoView.swift | logo treatment all variants | L/D/C +rm | ☐ |
| overlay.streakflame | streak ≥1 | Views/StreakFlameView.swift | dual-flicker streak flame | L/D/C +rm | ☐ |

---

## J. Device-QA items (from the 2026-05-17 code review — not iOS comparisons)

| item | check | M? |
|---|---|---|
| sparkline a11y (API 28) | TalkBack on API-28 + API-34: during the pace-sparkline `AnimatedVisibility` fade, TalkBack must NOT traverse into the Canvas | ☐ |
| SpeechRecognizer OEM | On a non-Google recognizer device (Samsung/Bixby): voice dictation start/Done/cancel — recognizer tears down, mic releases, no stuck Listening, no crash | ☐ |
| voice/breath smoke | One real-device pass of voice dictation + the post-permissions breath transition end-to-end | ☐ |

## K. Open product decision (not a test — needs a call)

Android voice dictation uses platform `SpeechRecognizer` (FREE_FORM) → audio routed to the device default RecognitionService (often **Google cloud**). iOS WhisperKit is **on-device**. Privacy-posture divergence. Decide: accept (and reflect in privacy copy), or switch to `createOnDeviceSpeechRecognizer` / `EXTRA_PREFER_OFFLINE` where available. → record decision in the ledger.

---

## Outcome

When the pass is complete: update each `M?` cell. Every `✗` (real gap) → add a `close-the-gap` row to `docs/parity/2026-05-15-parity-ledger.md` with the observed diff + iOS ref, then triage/fix via the normal flow. `~` (acceptable divergence) → record as a dated `re-justify` in the ledger. A fully `✓`/`~` pass converts the 77 waived rows from *code-verified* to *human-corroborated* and the motion-pending rows (welcome.entrance, inkscroll, about, practiceheader) to *verified*.
