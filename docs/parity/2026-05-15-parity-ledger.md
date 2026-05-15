# iOS↔Android Parity Ledger

> **Sole source of current parity truth.** All `docs/parity/*-audit.md` dated before 2026-05-15 are SUPERSEDED — pre-v1.6.0, format-precedent only.

- **iOS parity target:** `pilgrim-ios` @ `fcd2255` (v1.6.0, per `CLAUDE.md`)
- **Android target:** `feat/v1.6.0-parity`
- **Generated:** 2026-05-15 (U1) — regenerate via `docs/parity/README-parity-ledger.md`
- **Plan:** `docs/plans/2026-05-15-001-feat-ios-android-parity-verification-plan.md`

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
| path.wander.idle | Scenes/Home/WalkStartView.swift | Wander mode, idle, moon glyph | ui/path/WalkStartScreen.kt | L/D/C | anim | none | unverified |
| path.together.comingsoon | Scenes/Home/WalkStartView.swift | Together mode "coming soon" | WalkStartScreen Together | L/D/C | — | none | unverified |
| path.seek.comingsoon | Scenes/Home/WalkStartView.swift | Seek mode "coming soon" | WalkStartScreen Seek | L/D/C | — | none | unverified |
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
| walk.options.idle | Scenes/ActiveWalk/WalkOptionsSheet.swift | Options sheet, pre-walk (intention only, leaf icon) | ui/walk/WalkOptionsSheet.kt | L/D/C | — | none | unverified |
| walk.options.inwalk | Scenes/ActiveWalk/WalkOptionsSheet.swift | Options in-walk (waypoint/whisper/stone) | WalkOptionsSheet in-walk | L/D/C | — | live walk | unverified |
| walk.intention.sheet | Scenes/ActiveWalk/IntentionSettingView.swift | Intention setting sheet | ui/walk/IntentionSettingDialog.kt | L/D/C | — | none | unverified |
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
| settings.root | Scenes/Settings/SettingsView.swift | Settings card stack | ui/settings/SettingsScreen.kt | L/D/C | — | none | unverified |
| settings.practice | Scenes/Settings/SettingsCards/PracticeCard.swift | Practice card (intention/celestial/zodiac/units/hemisphere/collective/reliquary) | ui/settings/practice/PracticeCard.kt | L/D/C | — | none | unverified |
| settings.atmosphere | Scenes/Settings/SettingsCards/AtmosphereCard.swift | Atmosphere card (appearance nav row + sounds) | ui/settings/AtmosphereCard.kt | L/D/C | — | none | unverified |
| settings.appearance | Scenes/Settings/AppearanceView.swift | Appearance detail (4 rows) | ui/settings/AppearanceScreen.kt | L/D/C | — | none | unverified |
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
| settings.about | Scenes/Settings/AboutView.swift | About (hero/pillars/data-sources/open-source/motto) | ui/settings/about/AboutScreen.kt | L/D/C | anim | none | unverified |
| settings.about.iconswitch | Scenes/Settings/AboutView.swift | Tap-logo icon switcher dialog (constellation icon) | ui/settings/about icon dialog | L/D/C | — | none | unverified |
| settings.feedback | Scenes/Settings/FeedbackView.swift | Feedback form | ui/settings feedback | L/D/C | — | none | unverified |
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

## Gate summary (computed at U7)

- Total rows: 86 · `unverified`: 86 · `match`: 0 · `close-the-gap`: 0 · `re-justify`: 0 · `stale`: 0
- **Ship gate: NOT PASSABLE** — 86 unverified rows (R8). Gate recomputed in `docs/parity/gate.md` after U5/U6/U7.

## Notes

- `(NO Android equivalent — gap)` / `(verify Android equivalent)` rows are parity gaps by *absence* — they capture iOS only and enter triage as `close-the-gap`.
- `appearance` `C(no nebulae)` = constellation overlay applies but nebulae suppressed on that route (ActiveWalk/Summary/Meditation) per PilgrimNavHost route gating.
- Rows are screen×state, NOT screen×state×appearance — the `appearance` column tells the capturer which modes to shoot for that row (a row is not `verified` until every listed mode is captured + reviewed).
