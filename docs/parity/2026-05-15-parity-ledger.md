# iOS↔Android Parity Ledger

> **Sole source of current parity truth.** All `docs/parity/*-audit.md` dated before 2026-05-15 are SUPERSEDED — pre-v1.6.0, format-precedent only.

- **iOS parity target:** `pilgrim-ios` @ `fcd2255` (v1.6.0, per `CLAUDE.md`)
- **Android target:** `feat/v1.6.0-parity`
- **Generated:** 2026-05-15 (U1) — regenerate via `docs/parity/README-parity-ledger.md`
- **Plan:** `docs/plans/2026-05-15-001-feat-ios-android-parity-verification-plan.md`
- **Seed:** `docs/parity/fixtures/parity-seed.pilgrim` — real iOS-exported tended file: 16 full walks + 53 archived + 1 modification (covers archived/tended/journal-populated/goshuin; 108-walk milestone NOT covered → those rows await a richer seed).
- **2026-05-15 capture-session note:** iOS env unblocked + proven (sim builds/installs/seeds/screenshots). Android seed import VERIFIED on real data — importing `parity-seed.pilgrim` took the Journal from 23→39 walks / 44.1 km, exercising the v1.6.0 tended-mode + archived-strip path end-to-end without error (evidence: `evidence/journal.inkscroll.populated__L__android.png`, `evidence/settings.data-detail__L__android.png`). This is the **Android half + behavioral confirmation** of the archived/tended ledger rows; those rows stay `unverified` pending the **iOS-side paired capture** (iOS-sim must import the SAME file — UI-automation/hand-import is the remaining friction; `--demo-mode` ScreenshotDataSeeder is NOT data-identical so cannot be used for seed-gated rows).

## Verdict legend

- `unverified` — no paired evidence captured yet (default; blocks ship gate per R8)
- `match` — visually + behaviorally indistinguishable from iOS within the state
- `close-the-gap` — a difference that should be made to match iOS
- `re-justify` — a difference that may ship only with a dated explicit current reason (U7)
- `stale` — iOS removed this surface; row kept for audit trail

Stubbed/replaced native substitutions (Open-Meteo, Mapbox, whisper/cairn server pins) **cannot receive `match`** by virtue of being documented (R6) — they enter U7 triage.

## Appearance-mode cross-cut

Every screen renders under **light / dark / constellation**. Constellation additionally overlays stars+nebulae+cosmic-gradient and swaps parchment→indigo / content→lavender. Rows below carry a base state; the `appearance` column lists which modes materially differ and must each be captured. `reduce-motion` is a required extra capture for every animated row (marked `anim`).

## Ledger

Row id = `<area>.<screen>.<state>`. `iOS ref` is repo-relative under `../pilgrim-ios/`.

| id | iOS ref | screen / state | android route/surface | appearance | anim | seed req | verdict |
|---|---|---|---|---|---|---|---|
| **Setup** ||||||||
| setup.launch.loading | Scenes/Root/LaunchLoadingView.swift | Launch loading (breathing mark + cue) | MainActivity splash path | L/D/C | anim | none | unverified |
| setup.welcome.entrance | Scenes/Setup/Welcome/WelcomeView.swift | Welcome ritual full sequence | ui/onboarding/WelcomeScreen.kt | L/D/C | anim | none | unverified |
| setup.welcome.reduce-motion | Scenes/Setup/Welcome/WelcomeAnimationState.swift | Welcome, reduce-motion terminal frame | WelcomeScreen reduce-motion path | L/D/C | — | none | unverified |
| setup.breath.transition | Scenes/Setup/BreathTransitionView.swift | Welcome→Permissions breath transition | (verify Android equivalent) | L/D/C | anim | none | unverified |
| setup.permissions.initial | Scenes/Setup/Permissions/PermissionsView.swift | Permissions, nothing granted | ui/onboarding/PermissionsScreen.kt | L/D/C | — | none | unverified |
| setup.permissions.partial | Scenes/Setup/Permissions/PermissionsView.swift | Location coarse-only / needs-settings degraded | PermissionsScreen degraded states | L/D/C | — | none | unverified |
| setup.permissions.granted | Scenes/Setup/Permissions/PermissionsView.swift | All granted (auto-advance) | PermissionsScreen complete | L/D/C | — | none | unverified |
| **Path tab** ||||||||
| path.wander.idle | Scenes/Home/WalkStartView.swift | Wander mode, idle, moon glyph | ui/path/WalkStartScreen.kt | L/D/C | anim | none | L:close-the-gap; D/C/rm:unverified (see detail) |
| path.together.comingsoon | Scenes/Home/WalkStartView.swift | Together mode "coming soon" | WalkStartScreen Together | L/D/C | — | none | L:match; D/C:unverified |
| path.seek.comingsoon | Scenes/Home/WalkStartView.swift | Seek mode "coming soon" | WalkStartScreen Seek | L/D/C | — | none | L:match; D/C:unverified |
| path.wander.recovery-banner | Scenes/Home/WalkStartView.swift | Recovery banner (stale-walk swipe) | WalkStartScreen RecoveryBanner | L/D/C | anim | recovered walk | unverified |
| path.wander.vignette | Views/CelestialVignetteView.swift | Pre-walk celestial vignette (no weather) | ui/walk/WalkVignette.kt | L/D/C | — | none | unverified |
| **Journal tab** ||||||||
| journal.home.empty | Scenes/Home/HomeView.swift | No finished walks | ui/home/HomeScreen.kt empty | L/D/C | — | none | unverified |
| journal.home.loading | Scenes/Home/HomeView.swift | Loading | HomeScreen loading | L/D/C | — | none | unverified |
| journal.inkscroll.populated | Scenes/Home/InkScrollView.swift | Calligraphy path + dots + scenery | ui/home/scroll + HomeScreen | L/D/C | anim | ≥20 walks | unverified |
| journal.inkscroll.lunar | Scenes/Home/InkScrollView+LunarMarkers.swift | Lunar markers on path | ui/home lunar markers | L/D/C | — | ≥20 walks | unverified |
| journal.inkscroll.milestone | Scenes/Home/MilestoneMarkerView.swift | Milestone marker (dawn halo) | ui/home/markers milestone | L/D/C | — | milestone walk | unverified |
| journal.dot.standard | Scenes/Home/WalkDotView.swift | Standard walk dot (favicon/arcs/halo) | ui/home/dot/WalkDot.kt | L/D/C | anim | ≥1 walk | unverified |
| journal.dot.newest | Scenes/Home/WalkDotView.swift | Newest dot ripple/breath | WalkDot isNewest | L/D/C | anim | ≥1 walk | unverified |
| journal.dot.shared | Scenes/Home/WalkDotView.swift | Shared-walk stone ring | WalkDot isShared | L/D/C | — | shared walk | unverified |
| journal.dot.archived | Scenes/Home/WalkDotView.swift | Archived hollow fog ring | WalkDot isArchived | L/D/C | — | archived walk | unverified |
| journal.expandcard.open | Scenes/Home/HomeView.swift | Expand card overlay (long-press/tap) | ui/home/expand/ExpandCardSheet.kt | L/D/C | anim | ≥1 walk | unverified |
| journal.expandcard.archived | Scenes/Home/HomeView.swift | Degraded "Released" expand card | ExpandCardSheet archived variant | L/D/C | — | archived walk | unverified |
| journal.fab.seal | Scenes/Goshuin/GoshuinFAB.swift | Goshuin FAB w/ latest seal thumb | HomeScreen GoshuinFAB | L/D/C | — | ≥1 walk | unverified |
| journal.fab.hidden | Scenes/Goshuin/GoshuinFAB.swift | FAB hidden while expand card open | HomeScreen AnimatedVisibility | L/D/C | anim | ≥1 walk | unverified |
| journal.turningbanner | Scenes/Home/HomeView.swift | Turning-day banner (solstice/equinox) | ui/home/banner TurningDayBanner | L/D/C | — | turning date | unverified |
| **Active Walk** ||||||||
| walk.active.tracking | Scenes/ActiveWalk/ActiveWalkView.swift | Active, map + minimized stats sheet | ui/walk/ActiveWalkScreen.kt | L/D/C(no nebulae) | anim | live walk | unverified |
| walk.active.stats-expanded | Scenes/ActiveWalk/WalkStatsSheet.swift | Stats sheet expanded | ui/walk/WalkStatsSheet.kt | L/D/C | anim | live walk | unverified |
| walk.active.peek-hint | Scenes/ActiveWalk/WalkStatsSheet.swift | One-time swipe-hint wink | WalkStatsSheet peekHintTrigger | L/D/C | anim | live walk | unverified |
| walk.active.paused | Scenes/ActiveWalk/ActiveWalkView.swift | Paused state | ActiveWalkScreen paused | L/D/C | — | live walk | unverified |
| walk.active.vignette | Views/CelestialVignetteView.swift, Models/Weather/WeatherVignetteView.swift | Weather+celestial vignette bottom-end | ui/walk/WalkVignette.kt | L/D/C | — | live walk | unverified |
| walk.active.greeting | Scenes/ActiveWalk/ActiveWalkView.swift | Weather/celestial greeting overlay | ui/walk/GreetingOverlay.kt | L/D/C | anim | live walk | unverified |
| walk.active.sparkline | Scenes/WalkSummary/PaceSparklineView.swift | Live pace sparkline | (verify Android equivalent) | L/D/C | anim | live walk >10 pace pts | unverified |
| walk.options.idle | Scenes/ActiveWalk/WalkOptionsSheet.swift | Options sheet, pre-walk (intention only, leaf icon) | ui/walk/WalkOptionsSheet.kt | L/D/C | — | none | L:close-the-gap→resolved (header-align residual); D/C:unverified |
| walk.options.inwalk | Scenes/ActiveWalk/WalkOptionsSheet.swift | Options in-walk (waypoint/whisper/stone) | WalkOptionsSheet in-walk | L/D/C | — | live walk | unverified |
| walk.intention.sheet | Scenes/ActiveWalk/IntentionSettingView.swift | Intention setting sheet | ui/walk/IntentionSettingDialog.kt | L/D/C | — | none | L:close-the-gap (copy fixed; structural/feature gaps need disposition); D/C:unverified |
| walk.waypoint.sheet | Scenes/ActiveWalk/WaypointMarkingSheet.swift | Waypoint marking | ui/walk/WaypointMarkingSheet.kt | L/D/C | — | live walk | unverified |
| walk.whisper.sheet | Scenes/ActiveWalk/WhisperPlacementSheet.swift | Whisper placement (category picker) | ui/walk/WhisperPlacementSheet.kt | L/D/C | — | live walk, unlocked | unverified |
| walk.stone.sheet | Scenes/ActiveWalk/StonePlacementSheet.swift | Stone placement | ui/walk/StonePlacementSheet.kt | L/D/C | — | live walk, unlocked | unverified |
| walk.turning.card | Scenes/ActiveWalk/TurningRitualCard.swift | Turning ritual card | ui/walk/TurningRitualCard.kt | L/D/C | anim | turning date + live walk | unverified |
| walk.meditation.timer | Scenes/ActiveWalk/MeditationView.swift | Meditation timer (idle→meditating→done) | ui/meditation MeditationScreen | L/D/C(no nebulae) | anim | live walk | unverified |
| **Seal Reveal** ||||||||
| sealreveal.phases | Scenes/SealReveal/SealRevealView.swift | Hidden→pressing→revealed + haptics | ui/walk seal reveal | L/D/C | anim | finished walk | unverified |
| sealreveal.milestone | Scenes/SealReveal/SealRevealView.swift | Milestone celebration variant | seal reveal milestone | L/D/C | anim | milestone walk | unverified |
| **Walk Summary** ||||||||
| summary.reveal | Scenes/WalkSummary/WalkSummaryView.swift | Cinematic reveal sequence | ui/walk/summary WalkSummaryScreen | L/D/C(no nebulae) | anim | finished walk | unverified |
| summary.loaded | Scenes/WalkSummary/WalkSummaryView.swift | Loaded summary, all stats | WalkSummaryScreen loaded | L/D/C | — | finished walk | unverified |
| summary.map | Scenes/WalkSummary/WalkSummaryView+Map.swift | Map + annotations (start/end/med/voice/photo/waypoint/whisper/cairn) | ui/walk/PilgrimMap.kt | L/D/C | — | walk w/ route+pins | unverified |
| summary.map.no-token | Scenes/WalkSummary/WalkSummaryView+Map.swift | Map w/ no Mapbox token (Android fallback) | PilgrimMap "Map unavailable" | L/D/C | — | finished walk | unverified |
| summary.lightreading | Views/WalkLightReadingCard.swift | Light Reading card (koan/moon/celestial) | ui/walk/WalkLightReadingCard.kt | L/D/C | — | finished walk | unverified |
| summary.timeline | Scenes/WalkSummary/ActivityTimelineBar.swift | Activity timeline bar (tap-to-zoom) | ui/walk/summary WalkActivityTimelineCard | L/D/C | — | walk w/ activities | unverified |
| summary.activitylist | Scenes/WalkSummary/ActivityListView.swift | Activity list | ui/walk/summary activity list | L/D/C | — | walk w/ activities | unverified |
| summary.insights | Scenes/WalkSummary/ActivityInsightsView.swift | Activity insights | ui/walk/summary insights | L/D/C | — | walk w/ activities | unverified |
| summary.elevation | Scenes/WalkSummary/ElevationProfileView.swift | Elevation profile | ui/walk/summary elevation | L/D/C | — | walk w/ altitude | unverified |
| summary.voicerow.read | Scenes/WalkSummary/VoiceRecordingRow.swift | Transcription read mode (7-line clamp + show-more + pencil) | ui/walk/VoiceRecordingsSection.kt | L/D/C | — | walk w/ long transcript | unverified |
| summary.voicerow.edit | Scenes/WalkSummary/VoiceRecordingRow.swift | Transcription inline edit | VoiceRecordingsSection edit | L/D/C | — | walk w/ recording | unverified |
| summary.voicerow.pending | Scenes/WalkSummary/VoiceRecordingRow.swift | Pending / no-speech placeholder | TranscriptionPlaceholder | L/D/C | — | walk w/ recording | unverified |
| summary.reliquary.grid | Scenes/WalkSummary/Reliquary/PhotoReliquarySection.swift | Photo reliquary section | ui/walk/reliquary PhotoReliquarySection | L/D/C | — | walk w/ photos | unverified |
| summary.reliquary.carousel | Scenes/WalkSummary/Reliquary/PhotoCarouselView.swift | Photo carousel (activate+commit pin) | ui/walk/reliquary/PhotoCarousel.kt | L/D/C | anim | walk w/ photos | unverified |
| summary.reliquary.preview | Scenes/WalkSummary/Reliquary/PhotoPreviewSheet.swift | Photo preview sheet | ui/walk/reliquary/PhotoPreviewSheet.kt | L/D/C | — | walk w/ photos | unverified |
| summary.favicon.selector | Scenes/WalkSummary/FaviconSelectorView.swift | Favicon selector | ui/walk favicon selector | L/D/C | — | finished walk | unverified |
| summary.sharingbuttons | Views/WalkSharingButtons.swift | Share buttons row | ui/walk/share | L/D/C | — | finished walk | unverified |
| **Walk Share** ||||||||
| share.preview | Scenes/WalkShare/WalkSharePreviewView.swift | Share preview (route shape) | ui/walk/share WalkShareScreen | L/D/C | — | finished walk | unverified |
| share.webview | Scenes/WalkShare/WalkShareView.swift | Share webview (worker) | ui/walk/share webview | L/D/C | — | finished walk | unverified |
| **Goshuin** ||||||||
| goshuin.empty | Scenes/Goshuin/GoshuinView.swift | No seals | ui/goshuin/GoshuinScreen.kt empty | L/D/C | — | none | unverified |
| goshuin.populated | Scenes/Goshuin/GoshuinView.swift | Seal grid + stats header | GoshuinScreen loaded | L/D/C | — | ≥1 walk | unverified |
| goshuin.milestone | Scenes/Goshuin/GoshuinMilestones.swift | Milestone seal (halo + label) | GoshuinScreen milestone cell | L/D/C | — | milestone walk | unverified |
| goshuin.archived-ghost | Scenes/Goshuin/GoshuinPageView.swift | Archived seal ghost (excluded from grid) | GoshuinViewModel archived filter | L/D/C | — | archived walk | unverified |
| goshuin.statsheader | Scenes/Settings/PracticeSummaryHeader.swift (pattern) | Stats header (walks·dist·med, incl archived) | GoshuinScreen GoshuinStatsHeader | L/D/C | — | ≥1 walk + archived | unverified |
| goshuin.share-render | Scenes/Goshuin/GoshuinShareRenderer.swift | Full-collection share image 1080×1920 | (NO Android equivalent — gap) | n/a | — | ≥1 walk | unverified |
| goshuin.page-indicators | Scenes/Goshuin/GoshuinPageView.swift | TabView page dots (6/page) | (Android uses LazyVerticalGrid — divergence) | L/D/C | — | ≥7 walks | unverified |
| **Settings** ||||||||
| settings.root | Scenes/Settings/SettingsView.swift | Settings card stack | ui/settings/SettingsScreen.kt | L/D/C | — | none | L:close-the-gap; D/C:unverified (see detail) |
| settings.practice | Scenes/Settings/SettingsCards/PracticeCard.swift | Practice card (intention/celestial/zodiac/units/hemisphere/collective/reliquary) | ui/settings/practice/PracticeCard.kt | L/D/C | — | none | unverified |
| settings.atmosphere | Scenes/Settings/SettingsCards/AtmosphereCard.swift | Atmosphere card (appearance nav row + sounds) | ui/settings/AtmosphereCard.kt | L/D/C | — | none | unverified |
| settings.appearance | Scenes/Settings/AppearanceView.swift | Appearance detail (4 rows) | ui/settings/AppearanceScreen.kt | L/D/C | — | none | L:close-the-gap; D/C:unverified (see detail) |
| settings.voice | Scenes/Settings/SettingsCards/VoiceCard.swift | Voice card | ui/settings/voice | L/D/C | — | none | unverified |
| settings.permissions | Scenes/Settings/SettingsCards/PermissionsCard.swift | Permissions card | ui/settings/permissions | L/D/C | — | none | unverified |
| settings.data | Scenes/Settings/SettingsCards/DataCard.swift | Data card | ui/settings/data DataCard | L/D/C | — | none | unverified |
| settings.connect | Scenes/Settings/SettingsCards/ConnectCard.swift | Connect card | ui/settings ConnectCard | L/D/C | — | none | unverified |
| settings.sound | Scenes/Settings/SoundSettingsView.swift | Sound settings (bells/soundscapes/breath) | ui/settings/sounds | L/D/C | — | none | unverified |
| settings.bellpicker | Scenes/Settings/SoundSettingsView.swift | Bell picker sheet (per-id preview) | ui/settings/sounds/BellPickerSheet.kt | L/D/C | — | bells downloaded | unverified |
| settings.voiceguide | Scenes/Settings/VoiceGuideSettingsView.swift | Voice guide settings (download/delete) | ui/settings/voiceguide | L/D/C | — | none | unverified |
| settings.voiceguide.picker | Scenes/Settings/VoiceGuideSettingsView.swift | Voice guide pack picker + progress | ui/settings/voiceguide picker | L/D/C | — | none | unverified |
| settings.recordings | Scenes/Settings/RecordingsListView.swift | Recordings list (swipe actions) | ui/recordings/RecordingsListScreen.kt | L/D/C | — | walks w/ recordings | unverified |
| settings.data-detail | Scenes/Settings/DataSettingsView.swift | Data settings (export/import/journey rows) | ui/settings/data DataSettingsScreen | L/D/C | — | none | unverified |
| settings.export-confirm | Scenes/Settings/ExportConfirmationSheet.swift | Export confirmation sheet | ui/settings/data ExportConfirmationSheet | L/D/C | — | ≥1 walk | unverified |
| settings.journey-viewer | Scenes/Settings/JourneyViewerView.swift | Journey viewer webview | ui/settings/data JourneyViewerScreen | L/D/C | — | ≥1 walk | unverified |
| settings.journey-editor | Scenes/Settings/JourneyEditorView.swift | Edit My Journey webview | ui/settings/data JourneyEditorScreen | L/D/C | — | ≥1 walk | unverified |
| settings.about | Scenes/Settings/AboutView.swift | About (hero/pillars/data-sources/open-source/motto) | ui/settings/about/AboutScreen.kt | L/D/C | anim | none | L:close-the-gap (motion-pending); D/C:unverified (see detail) |
| settings.about.iconswitch | Scenes/Settings/AboutView.swift | Tap-logo icon switcher dialog (constellation icon) | ui/settings/about icon dialog | L/D/C | — | none | unverified |
| settings.feedback | Scenes/Settings/FeedbackView.swift | Feedback form | ui/settings feedback | L/D/C | — | none | L:close-the-gap→resolved (icon-drift residual); D/C:unverified |
| settings.practiceheader | Scenes/Settings/PracticeSummaryHeader.swift | Practice summary header (cycling stats + milestone) | ui/settings/PracticeSummaryHeader.kt | L/D/C | anim | ≥1 walk | unverified |
| **Prompts** ||||||||
| prompts.list | Scenes/Prompts/PromptListView.swift | Prompt list | (verify Android equivalent) | L/D/C | — | none | unverified |
| prompts.detail | Scenes/Prompts/PromptDetailView.swift | Prompt detail | (verify Android equivalent) | L/D/C | — | none | unverified |
| prompts.editor | Scenes/Prompts/CustomPromptEditorView.swift | Custom prompt editor | (verify Android equivalent) | L/D/C | — | none | unverified |
| **Cross-cut overlays** ||||||||
| overlay.constellation | Views/ConstellationOverlay.swift | Constellation stars/nebulae/cosmic/shooting | ui/design/ConstellationOverlay.kt | C only | anim | constellation mode | unverified |
| overlay.constellation.reduce | Views/ConstellationOverlay.swift | Constellation reduce-motion static frame | ConstellationOverlay reduce-motion | C only | — | constellation + reduce-motion | unverified |
| overlay.proximity | Views/ProximityNotificationView.swift | Proximity whisper notification | ui/walk/ProximityNotificationBanner.kt | L/D/C | anim | nearby whisper | unverified |
| overlay.pilgrimlogo | Views/PilgrimLogoView.swift | Logo (default/per-guide/constellation) | ui/design/BreathingLogo.kt, ui/settings/about/PilgrimLogo.kt | L/D/C | anim | none | unverified |
| overlay.streakflame | Views/StreakFlameView.swift | Streak flame (dual flicker) | ui/settings/StreakFlame.kt | L/D/C | anim | streak ≥1 | unverified |

## Per-row verdict detail

Per-mode blinded-review results for rows not yet fully verified (a row's table-cell stays partial until every `appearance` mode + motion is reviewed; row effective verdict = worst mode).

### path.wander.idle

- **L (Light) — `close-the-gap`** (fresh blinded reviewer, 2026-05-15)
  - evidence: `evidence/path.wander.idle__L__ios.png`, `evidence/path.wander.idle__L__android.png`; motion: `evidence/motion/path.wander.idle__L__{ios,android}.frames/` (iOS also `.mov`; Android frame-sampled — on-device `screenrecord` is OEM-blocked on the OnePlus, U6 frame-sample method used)
  - observed-diff: Android renders an EXTRA bottom-end celestial moon pill (`WalkVignette`, BottomEnd) on the pre-walk screen that iOS `WalkStartView` does not have — iOS `CelestialVignetteView` is used only in `ActiveWalkView.swift:450` (active walk), never pre-walk (capturer verified iOS host `MainTabView.swift` does not wrap it either; behavior slice was complete).
  - **U7 disposition (2026-05-15, A5/user):** **close-the-gap → match iOS.** Pre-walk `WalkVignette` removed from `ui/path/WalkStartScreen.kt`; dead `WalkViewModel.preWalkCelestialSnapshot()` deleted. Celestial pill remains on active-walk only (iOS `ActiveWalkView.swift:450` parity). Compiles clean. **Evidence re-capture pending** — Android device still runs the pre-fix build; the Light verdict stays `close-the-gap (resolved-in-code, re-capture+re-review pending)` until a post-fix Android shot is paired and re-reviewed. Per protocol a code fix never auto-promotes to `match` without fresh evidence.
  - **L re-review #2 (post-pill-fix, fresh blinded reviewer):** `close-the-gap` — Android lacked iOS `runEntrance` staggered fade (logo→quote→moon, 0.5s decelerate, +0.4s/+0.6s delays; logo also scales 0.95→1.0). **Resolved in code:** added staggered entrance + reduceMotion-immediate path to `WalkStartScreen.kt` (mirrors `WelcomeScreen.kt` convention: `animateFloatAsState` + `graphicsLayer{alpha}`). Compiles + `WalkStartScreenTest` green.
  - **L net state:** `close-the-gap (resolved-in-code ×2: pre-walk pill removed + entrance stagger added). Final recapture+re-review deferred to a consolidation pass — entrance is a one-shot on-appear; capturing it needs burst timed to navigation.`
  - D (Dark), C (Constellation), reduce-motion: NOT yet captured → row stays partial.

### path.together.comingsoon
- **L — `match`** (fresh blinded reviewer, 2026-05-15). evidence: `evidence/path.together.comingsoon__L__{ios,android}.png`. observed-diff: none. Selector (TOGETHER selected, stone label + underline, dimmed siblings), "coming soon" caption, disabled fog "Walk Together" button, together footprint cluster all correspond; quote text differs (random pool, not a divergence). D/C pending.

### path.seek.comingsoon
- **L — `match`** (fresh blinded reviewer, 2026-05-15). evidence: `evidence/path.seek.comingsoon__L__{ios,android}.png`. observed-diff: none. SEEK selected + underline, "coming soon" caption, disabled button, seek footprint (lead print + dissolving-dot stack) correspond; quote text differs (random pool). D/C pending.

### settings.root
- **L — `close-the-gap`** (fresh blinded reviewer, 2026-05-15). evidence: `evidence/settings.root__L__{ios,android}.png`. Card-stack structure (PracticeSummaryHeader + Practice/Atmosphere/Voice/Permissions/Data/Connect, same order) corresponds.
  - **Real divergence (capturer-confirmed in code):** Android `PracticeCard` renders an always-visible **Hemisphere (North/South)** segmented row. iOS has **no hemisphere UI anywhere** — `UserPreferences.hemisphereOverride` is auto-set from location once in `HomeViewModel.swift:108` (`guard … == nil`), never user-editable. observed-diff: *Android exposes a manual Hemisphere picker row in Practice settings that iOS does not have.*
  - **Reviewer flags that were STATE ARTIFACTS, not bugs (capturer-verified):** (a) "Zodiac shown unconditionally" — false: Android gates Zodiac behind `AnimatedVisibility(visible=celestialAwareness)`, logic identical to iOS `if celestialAwareness`; difference was only because Android device had Celestial-awareness ON and iOS sim had it OFF. (b) Toggle defaults (intention/celestial ON vs OFF) and (c) header season numbers differ — both because the two devices imported the seed onto **non-equal baselines** (see Capture-methodology note below), not code parity bugs.
  - **U7 disposition (2026-05-15, A5/user): close-the-gap → match iOS. RESOLVED in code.** Investigation confirmed Android already has the iOS-equivalent set-once-from-location auto-detect (`HemisphereRepository.refreshFromLocationIfNeeded`, wired via `WalkFinalizationObserver`); removing the manual UI breaks nothing in the seasonal engine. Manual Hemisphere row + `SettingsViewModel` wiring + unused strings removed; 5 settings test classes updated, green. On-device verified: Practice card now flows Units → Walk-with-collective with no Hemisphere row (iOS-parallel). Final paired re-capture+re-review folds into the clean-baseline pass.
  - D/C pending.

## Capture-methodology finding (cross-cutting — affects every seed-gated + stateful row)

**Seed baseline was NOT equalized before import.** iOS sim (`74 walks·116mi` season / `149·226mi` all-time) and Android device (`39 walks·44km` season / `149·363km` all-time) imported the SAME `parity-seed.pilgrim` but onto **different pre-existing data** (iOS sim carried `--demo-mode` walks; Android device had 23 real user walks + the user's real settings). All-time distance actually corresponds (226mi ≈ 363km — unit display only); season counts/toggle-state diverge purely from baseline residue. **Consequence:** seed-gated and settings-state rows cannot be fairly pixel/state-compared until BOTH platforms import the seed onto a CLEAN wiped baseline (the `README-seed.md` round-trip-equivalence gate). No-seed structural rows (path modes, settings card structure, appearance/about/feedback chrome) remain valid now. Seeded rows (journal populated, goshuin, archived, summary, practice header stats) must be (re)captured post-clean-baseline or their verdicts carry a `state-artifact-risk` caveat.

### settings.appearance
- **L — `close-the-gap`** (fresh blinded reviewer, 2026-05-15). evidence: `evidence/settings.appearance__L__{ios,android}.png`. Four distinct sub-divergences (itemized for U7 batch / focused follow-up):
  1. **Container grouping:** iOS = one grouped card with internal hairline dividers; Android = 4 separate elevated cards with gaps. (structural Compose change)
  2. **Description copy (3 of 4 reworded):** "Match the system setting"→"Match your system theme"; "Parchment background, ink text"→"Parchment, ink, and warm sand"; "Easy on the eyes for evening walks"→"Restful low-light reading". (trivial string fix — change Android `settings_appearance_*_description` to iOS wording)
  3. **Icons:** iOS SF Symbols (circle.righthalf.filled / sun.max / moon / sparkles) vs Android Material (Brightness6 / LightMode / Brightness4 / AutoAwesome). SF Symbols are iOS-native — needs a design call: approximate with custom vectors vs accept Material substitution (latter blocks `match` by R6).
  4. **Title/back chrome:** iOS centered "Appearance" + circular back button; Android left-aligned title + plain ArrowBack TopAppBar.
  - **U7 disposition needed (A5/user):** items 1,2,4 are unambiguous close-the-gap (fixable); item 3 (icons) needs the design call. Not fixed inline — batched as a focused follow-up (mixed trivial + structural + design).
  - D/C pending.

### settings.about
- **L — `close-the-gap` (motion-pending)** (fresh blinded reviewer, 2026-05-15). evidence: `evidence/settings.about__L__{ios,android}.png`. Still divergences (dispositive — motion can only add, not remove; full clear needs U6 motion later):
  1. **Tree/scenery glyph above the logo** — Android renders `TreeScenery` above the hero logo; iOS `AboutView` has no tree/scenery at all. (Android-only decoration → close-the-gap)
  2. **Logo tile color:** Android purple/mauve guide-themed tile vs iOS neutral parchment "p". (partly state — active voice-guide; confirm under clean baseline whether default also diverges)
  3. **Nav chrome:** left-aligned title + back arrow (Android `TopAppBar`) vs iOS centered nav title. — see Systemic finding below.
  - Copy + pillar structure correspond. D/C + motion pending.

## Systemic finding — Settings detail-screen nav chrome

Both `settings.appearance` and `settings.about` flagged the same chrome divergence: iOS detail screens use a centered nav title; Android used a Material `TopAppBar` with a left-aligned title.

**RESOLVED (2026-05-15, A5/user chose "systemic fix now").** Created shared `ui/design/PilgrimDetailScaffold.kt` (`CenterAlignedTopAppBar` = iOS principal-centered title, `pilgrimType.heading`/ink, parchment, leading back; `contentWindowInsets = WindowInsets(0)` to defer to the nav-host's outer Scaffold — also fixes a latent double-top-inset; optional `snackbarHost` slot). Converted all 10 pushed Settings/Recordings detail screens (appearance, about, feedback, data-detail, journey-editor, journey-viewer, soundscape-picker, voiceguide pack-detail, voiceguide-picker, recordings) to it. Compiles; all converted-screen test classes green; on-device verified (Appearance title now centered, no inset gap). Per-row chrome sub-diffs (settings.appearance item 4, settings.about item 3) are now CLOSED; the remaining settings.appearance items (1 grouping, 2 copy, 3 icons) and settings.about items (1 tree, 2 logo tile) still stand for their own dispositions.

### walk.options.idle
- **L — `close-the-gap` → RESOLVED in code** (fresh blinded reviewer, 2026-05-15). evidence: `evidence/walk.options.idle__L__{ios,android}.png`. observed-diff: Android rendered a placeholder subtitle "A line for this walk" under "Set Intention"; iOS passes `subtitle: currentIntention` (nil pre-walk → subtitle line omitted). **Fixed:** `OptionRow.subtitle` made nullable + conditionally rendered; intention call site drops the `?: stringResource(...)` placeholder; dead `walk_options_intention_pre_walk_unset` string removed; `WalkOptionsSheetTest` updated (now asserts subtitle absent pre-walk) + green; on-device verified single-line row. **Residual:** sheet header "Options" is left-aligned on Android vs centered on iOS (ModalBottomSheet header — NOT covered by PilgrimDetailScaffold; batched as a minor close-the-gap). D/C pending.

### settings.feedback
- **L — `close-the-gap` → RESOLVED in code** (fresh blinded reviewer, 2026-05-15). evidence: `evidence/settings.feedback__L__{ios,android}.png`. observed-diff: Android omitted the device-info preview line that iOS renders under "Include device info" when ON (Android computed the string for the request but never displayed it). **Fixed:** `FeedbackViewModel.deviceInfo` exposed (`DeviceInfoProvider.deviceInfo()`, already iOS-faithful "Android <rel> · <model> · v<ver>"); `FormContent` renders it (caption/fog) under the toggle when `includeDeviceInfo`, mirroring iOS `if includeDeviceInfo { Text(deviceInfoPreview) }`. Compiles; feedback tests green; on-device verified ("Android 16 · CPH2655 · v0.1.0-debug" — value platform-appropriate per the 0.1.0 no-mirror rule, format matches). Title centered via `PilgrimDetailScaffold` (chrome already cleared). **Residual:** category icons drift (iOS SF `leaf`/`sparkles`/bug vs Android Material) — same SF-vs-Material design-call class as `settings.appearance` item 3; batched. D/C pending.

### walk.intention.sheet
- **L — `close-the-gap`** (fresh blinded reviewer, 2026-05-15). evidence: `evidence/walk.intention.sheet__L__{ios,android}.png`. 6 sub-divergences:
  1. **Presentation:** Android centered `AlertDialog` vs iOS bottom **sheet** (rounded-top, full-width, grabber). (structural)
  2. **Voice/mic input row** — iOS has an `IntentionVoiceRecorder` mic-dictation row + countdown; Android has none. (iOS-native speech feature — likely deferred-this-milestone like whispers; R6 bars `match` regardless)
  3. **"Suggested" chips section** — iOS only; Android absent.
  4. **"Recent" intentions list** — iOS only; Android absent.
  5. ~~Header copy "Set Intention" vs "Set Your Intention"~~ — **FIXED** (string → "Set Your Intention"; test updated; on-device verified).
  6. ~~Placeholder "A line for this walk…" vs "What purpose guides this walk?"~~ — **FIXED** (string → iOS wording; `IntentionSettingDialogTest` locator updated; green; on-device verified).
  - **U7 disposition needed (A5/user):** items 1 (sheet-vs-dialog) + 3 + 4 = close-the-gap (buildable); item 2 (voice dictation) likely `re-justify` deferred-feature (parallels the deferred whispers/auto-play pattern). Not fixed inline (presentation paradigm + voice subsystem + Suggested/Recent data+UX too large for a sweep-inline fix). D/C pending.

## Gate summary (computed at U7)

- Total rows: 86 · `unverified`: 86 · `match`: 0 · `close-the-gap`: 0 · `re-justify`: 0 · `stale`: 0
- **Ship gate: NOT PASSABLE** — 86 unverified rows (R8). Gate recomputed in `docs/parity/gate.md` after U5/U6/U7.

## Notes

- `(NO Android equivalent — gap)` / `(verify Android equivalent)` rows are parity gaps by *absence* — they capture iOS only and enter triage as `close-the-gap`.
- `appearance` `C(no nebulae)` = constellation overlay applies but nebulae suppressed on that route (ActiveWalk/Summary/Meditation) per PilgrimNavHost route gating.
- Rows are screen×state, NOT screen×state×appearance — the `appearance` column tells the capturer which modes to shoot for that row (a row is not `verified` until every listed mode is captured + reviewed).
