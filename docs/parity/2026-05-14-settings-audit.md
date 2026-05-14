# Parity Spec: Settings

| field | value |
|---|---|
| **iOS pin** | `v1.5.0` = `db4196e` |
| **Android HEAD** | `dc4cef8` |
| **Generated** | 2026-05-14 |
| **Type** | audit |
| **Generator** | ios-parity skill |

## Scope notes

- Two files in the requested `ios_files` list — `Pilgrim/Scenes/Settings/AppearanceView.swift` and `Pilgrim/Scenes/Settings/JourneyEditorView.swift` — DO NOT EXIST at the pinned SHA `db4196e` (added post-v1.5.0). **Marked OUT-OF-SCOPE** for this audit.
- The eleven legacy UIKit setting wrappers under `Pilgrim/Models/Settings/Setting Models/*.swift` plus `SettingSection.swift` and `SettingsModel.swift` are NOT used by the SwiftUI scene files in this slice. They predate the SwiftUI Settings rewrite. **Marked LEGACY / DEAD CODE in parity scope — recommend Android NOT port them.**
- This audit was generated with 5-of-8 lens outputs available on disk; the iOS `data` lens and Android `behavior` + `data` lens outputs were returned inline by their subagents and are not represented in the drift tables below. Treated as empty per synthesizer protocol. See Open Questions.

## iOS source map

| file | LOC | purpose |
|---|---|---|
| `Pilgrim/Scenes/Settings/AboutView.swift@db4196e` | 453 | About / app stats hero — stat-mode cycling, share, icon picker |
| `Pilgrim/Scenes/Settings/DataSettingsView.swift@db4196e` | 398 | Data settings root — export, recordings, journey link, delete-all |
| `Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift@db4196e` | 247 | Export confirmation modal — date range, scope, format |
| `Pilgrim/Scenes/Settings/ExportDateRangeFormatter.swift@db4196e` | 29 | Helper formatting export date ranges |
| `Pilgrim/Scenes/Settings/FeedbackView.swift@db4196e` | 239 | Feedback form — subject/body, send, sent confirmation |
| `Pilgrim/Scenes/Settings/JourneyViewerView.swift@db4196e` | 235 | Read-only walk-thread browser launched from DataSettingsView |
| `Pilgrim/Scenes/Settings/PermissionStatusViewModel.swift@db4196e` | 87 | VM tracking notifications + location permission states |
| `Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift@db4196e` | 161 | Streak flame + per-week dots summary header |
| `Pilgrim/Scenes/Settings/RecordingsListView.swift@db4196e` | 521 | Voice recordings list with playback, export, delete, transcript |
| `Pilgrim/Scenes/Settings/SettingsCards/AtmosphereCard.swift@db4196e` | 45 | Sounds toggle + Bells & Soundscapes nav link |
| `Pilgrim/Scenes/Settings/SettingsCards/ConnectCard.swift@db4196e` | 75 | Feedback nav row + share + GitHub + rate-app links |
| `Pilgrim/Scenes/Settings/SettingsCards/DataCard.swift@db4196e` | 17 | Wrapper around DataSettingsView nav row |
| `Pilgrim/Scenes/Settings/SettingsCards/PermissionsCard.swift@db4196e` | 96 | Permissions card — notifications + location toggles |
| `Pilgrim/Scenes/Settings/SettingsCards/PracticeCard.swift@db4196e` | 122 | Practice card — meditation toggle + duration + breathing |
| `Pilgrim/Scenes/Settings/SettingsCards/SettingsCardStyle.swift@db4196e` | 91 | Shared card / row / toggle style modifier set |
| `Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift@db4196e` | 114 | Voice recording toggle + recordings list nav row |
| `Pilgrim/Scenes/Settings/SettingsView.swift@db4196e` | 163 | Settings root — header + cards in scroll |
| `Pilgrim/Scenes/Settings/SoundSettingsView.swift@db4196e` | 502 | Bells & Soundscapes pick-list with previews + downloads |
| `Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift@db4196e` | 222 | Voice guide pack picker + manifest sync + downloads |
| `Pilgrim/Models/Settings/PolicyManager.swift@db4196e` | 91 | Privacy / terms / app-info URLs + policy version manager |
| `Pilgrim/Models/Settings/SettingSection.swift@db4196e` | 65 | Legacy UIKit SettingSection model (NOT used by SwiftUI scenes) *(legacy UIKit — out-of-scope)* |
| `Pilgrim/Models/Settings/SettingsModel.swift@db4196e` | 341 | Legacy UIKit SettingsModel (NOT used by SwiftUI scenes) *(legacy UIKit — out-of-scope)* |
| `Pilgrim/Models/Settings/Setting Models/ButtonSetting.swift@db4196e` | 68 | Legacy UIKit setting wrapper *(legacy UIKit — out-of-scope)* |
| `Pilgrim/Models/Settings/Setting Models/DatePickerSetting.swift@db4196e` | 75 | Legacy UIKit setting wrapper *(legacy UIKit — out-of-scope)* |
| `Pilgrim/Models/Settings/Setting Models/InputViewSetting.swift@db4196e` | 97 | Legacy UIKit setting wrapper *(legacy UIKit — out-of-scope)* |
| `Pilgrim/Models/Settings/Setting Models/PickerSetting.swift@db4196e` | 80 | Legacy UIKit setting wrapper *(legacy UIKit — out-of-scope)* |
| `Pilgrim/Models/Settings/Setting Models/SelectionSetting.swift@db4196e` | 62 | Legacy UIKit setting wrapper *(legacy UIKit — out-of-scope)* |
| `Pilgrim/Models/Settings/Setting Models/SwitchSetting.swift@db4196e` | 122 | Legacy UIKit setting wrapper *(legacy UIKit — out-of-scope)* |
| `Pilgrim/Models/Settings/Setting Models/TextInputSetting.swift@db4196e` | 162 | Legacy UIKit setting wrapper *(legacy UIKit — out-of-scope)* |
| `Pilgrim/Models/Settings/Setting Models/TextViewSetting.swift@db4196e` | 131 | Legacy UIKit setting wrapper *(legacy UIKit — out-of-scope)* |
| `Pilgrim/Models/Settings/Setting Models/TimeIntervalPickerSetting.swift@db4196e` | 129 | Legacy UIKit setting wrapper *(legacy UIKit — out-of-scope)* |
| `Pilgrim/Models/Settings/Setting Models/TitleSetting.swift@db4196e` | 96 | Legacy UIKit setting wrapper *(legacy UIKit — out-of-scope)* |
| `Pilgrim/Models/Settings/Setting Models/TitleSubTitleSetting.swift@db4196e` | 88 | Legacy UIKit setting wrapper *(legacy UIKit — out-of-scope)* |
| `Pilgrim/Scenes/Settings/AppearanceView.swift@db4196e` | — | *does not exist at pinned SHA — out-of-scope* |
| `Pilgrim/Scenes/Settings/JourneyEditorView.swift@db4196e` | — | *does not exist at pinned SHA — out-of-scope* |

## Android source map

| file | LOC | purpose |
|---|---|---|
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt@dc4cef8` | 535 | About / app stats hero composable |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutSeasonHelpers.kt@dc4cef8` | 30 | Seasonal tint helpers for About hero |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutViewModel.kt@dc4cef8` | 61 | About VM — aggregated walk stats Flow |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutWalkSource.kt@dc4cef8` | 24 | Walks-source seam for AboutViewModel tests |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/FootprintShape.kt@dc4cef8` | 38 | Tiny footprint vector path for About hero |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/PilgrimLogo.kt@dc4cef8` | 67 | Logo composable with breathing animation |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/SeasonalTree.kt@dc4cef8` | 66 | Seasonal-tree mini-illustration |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/AtmosphereCard.kt@dc4cef8` | 108 | Atmosphere card — sounds toggle + Bells & Soundscapes link |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/ConnectCard.kt@dc4cef8` | 61 | Connect card — feedback / share / GitHub / rate links |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/DeviceInfoProvider.kt@dc4cef8` | 26 | Device info seam for feedback metadata |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackScreen.kt@dc4cef8` | 316 | Feedback form composable |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackSubmitter.kt@dc4cef8` | 10 | Feedback submission seam |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackSubmitterImpl.kt@dc4cef8` | 15 | FeedbackSubmitter implementation |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackUiState.kt@dc4cef8` | 20 | Feedback UI state class |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackViewModel.kt@dc4cef8` | 65 | Feedback VM |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataCard.kt@dc4cef8` | 31 | Data card wrapper |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataExportEnv.kt@dc4cef8` | 14 | Export env data class |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsScreen.kt@dc4cef8` | 345 | Data Settings composable |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsViewModel.kt@dc4cef8` | 268 | Data Settings VM |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/ExportConfirmationSheet.kt@dc4cef8` | 257 | Export confirmation modal |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/ExportDateRangeFormatter.kt@dc4cef8` | 30 | Date range formatter |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/JourneyViewerScreen.kt@dc4cef8` | 198 | Journey viewer composable |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/JourneyViewerViewModel.kt@dc4cef8` | 133 | Journey viewer VM |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/PilgrimPackageGateway.kt@dc4cef8` | 34 | Export-package gateway seam |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/RecordingsCountSource.kt@dc4cef8` | 25 | Recordings count source |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/RecordingsExporterGateway.kt@dc4cef8` | 28 | Recordings exporter seam |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/RecordingsExportResult.kt@dc4cef8` | 11 | Export result class |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/WalksSource.kt@dc4cef8` | 27 | Walks source seam |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/AskedFlagSource.kt@dc4cef8` | 15 | Asked-flag store seam |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/LivePermissionChecks.kt@dc4cef8` | 25 | Live permission check helpers |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionAskedStoreAdapter.kt@dc4cef8` | 18 | AskedFlag DataStore adapter |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCard.kt@dc4cef8` | 185 | Permissions card composable |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCardViewModel.kt@dc4cef8` | 78 | Permissions card VM |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/practice/PracticeCard.kt@dc4cef8` | 192 | Practice card composable |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt@dc4cef8` | 322 | Practice summary header (streak + dots) |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsAction.kt@dc4cef8` | 54 | Settings action sealed class |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsCardStyle.kt@dc4cef8` | 312 | Shared SettingsCard / row / toggle styling |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt@dc4cef8` | 253 | Settings root composable |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt@dc4cef8` | 338 | Settings root VM |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/BellPickerSheet.kt@dc4cef8` | 155 | Bell picker bottom sheet |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/BreathRhythmPickerSheet.kt@dc4cef8` | 135 | Breath rhythm picker bottom sheet |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsScreen.kt@dc4cef8` | 545 | Bells & soundscapes screen |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsViewModel.kt@dc4cef8` | 218 | Sound settings VM |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/soundscape/SoundscapePickerScreen.kt@dc4cef8` | 244 | Soundscape picker screen |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/soundscape/SoundscapePickerViewModel.kt@dc4cef8` | 63 | Soundscape picker VM |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/StreakFlame.kt@dc4cef8` | 94 | Streak flame animation |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voice/VoiceCard.kt@dc4cef8` | 139 | Voice recording card |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePackDetailScreen.kt@dc4cef8` | 289 | Voice guide pack detail |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePackDetailViewModel.kt@dc4cef8` | 64 | Voice guide pack detail VM |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePickerScreen.kt@dc4cef8` | 209 | Voice guide picker screen |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePickerViewModel.kt@dc4cef8` | 50 | Voice guide picker VM |

## Drift — Lens: behavior

| iOS finding | Android finding | severity | claim |
|---|---|---|---|
| Pilgrim/Scenes/Settings/AboutView.swift:6-14@db4196e | — | missing | AboutView holds 9 @State vertices including statMode (3-way cycling enum), totalDistance, walkCount, firstWalkDate, hasWalks, safariURL (sheet item), appeared (animation gate), and showIconConfirmation (dialog gate) |
| Pilgrim/Scenes/Settings/AboutView.swift:378-398@db4196e | — | missing | loadWalkData runs on the .task structured concurrency scope and explicitly hops to MainActor.run to publish results |
| Pilgrim/Scenes/Settings/AboutView.swift:472-475@db4196e | — | missing | Section-appear staggered animation uses 0.1s per-index delay multiplier |
| Pilgrim/Scenes/Settings/AboutView.swift:179-181@db4196e | — | missing | Stat-mode cycle button uses 0.3s easeInOut animation duration |
| Pilgrim/Scenes/Settings/AboutView.swift:446-456@db4196e | — | missing | statMode cycles through 3 vertices distance → count → since → distance on tap, with .numericText contentTransition |
| Pilgrim/Scenes/Settings/AboutView.swift:411-417@db4196e | — | missing | App icon confirmation dialog triggered by tap on PilgrimLogoView and calls UIApplication.shared.setAlternateIconName |
| Pilgrim/Scenes/Settings/AboutView.swift:402-409@db4196e | — | missing | iconDialogTitle reads VoiceGuideManifestService.shared.pack(byId:) synchronously to display pack name + tagline in the dialog title |
| Pilgrim/Scenes/Settings/AppearanceView.swift:16-21@db4196e | — | missing | AppearanceView holds a String mode vertex with 4 valid values: 'system', 'light', 'dark', 'constellation' |
| Pilgrim/Scenes/Settings/AppearanceView.swift:23-32@db4196e | — | missing | AppearanceView's body reads appearanceManager.themeID to force re-evaluation when mode changes (in-place picker update) |
| Pilgrim/Scenes/Settings/AppearanceView.swift:68-70@db4196e | — | missing | AppearanceView onAppear re-reads UserPreferences to resynchronize local mode state |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:8-31@db4196e | — | missing | DataSettingsView has interlocked busy flags: isExporting, isImporting, isExportingRecordings; isBusy=disjunction governs all buttons |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:191-210@db4196e | — | missing | exportData has explicit re-entry guard against double-tap that catches BOTH the confirmation-sheet-up gap AND the share-sheet-visible gap |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:216-230@db4196e | — | missing | performExport sets lastSkippedPhotoCount AFTER successful build and BEFORE assigning exportURL, so the post-share alert can fire on cleanup |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:317-332@db4196e | — | missing | exportRecordings dispatches zip-build to .global(qos:.userInitiated) and hops back to .main.async for UI updates |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:357-374@db4196e | — | missing | cleanupExport surfaces 'some photos couldn't be included' alert AFTER share sheet dismisses, intentionally NOT during the share flow |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:274-291@db4196e | — | missing | ImportData calls startAccessingSecurityScopedResource/stopAccessingSecurityScopedResource around the import op |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:297-313@db4196e | — | missing | ImportSummary has three counters (added, replaced, archived) and a totalChanges fallback message |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:179-181@db4196e | — | missing | recordingCount read on onAppear from synchronous DataManager.recordingFileCount() |
| Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:25-35@db4196e | — | missing | ExportConfirmationSheet has includePhotos and hasCommitted vertices; hasCommitted is double-tap guard |
| Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:142-187@db4196e | — | missing | exportTapped applies effectiveIncludePhotos guard so pinnedPhotoCount==0 always passes false regardless of toggle state |
| Pilgrim/Scenes/Settings/ExportDateRangeFormatter.swift:12-28@db4196e | — | missing | ExportDateRangeFormatter collapses same-month ranges and uses 'MMMM yyyy' format with default locale .current |
| Pilgrim/Scenes/Settings/FeedbackView.swift:5-11@db4196e | — | missing | FeedbackView has 6 vertices: selectedCategory, message, includeDeviceInfo (default true), isSubmitting, showConfirmation, errorMessage |
| Pilgrim/Scenes/Settings/FeedbackView.swift:162-184@db4196e | — | missing | FeedbackView confirmation overlay displays for 2.5s before auto-dismissing (Task.sleep nanoseconds 2_500_000_000) |
| Pilgrim/Scenes/Settings/FeedbackView.swift:167-178@db4196e | — | missing | FeedbackService.submit invoked inside Task @MainActor — UI work stays on main thread but underlying network call must hop dispatchers internally |
| Pilgrim/Scenes/Settings/FeedbackView.swift:211-239@db4196e | — | missing | FeedbackCategory enum has 3 vertices (bug/feature/thought) each with title, icon, apiValue mappings |
| Pilgrim/Scenes/Settings/JourneyEditorView.swift:24-32@db4196e | — | missing | JourneyEditorView has 4 vertices: isLoading, pilgrimPayload, error, savedFile + pendingTempCleanup (mirrored for onDismiss-after-nil) |
| Pilgrim/Scenes/Settings/JourneyEditorView.swift:257-272@db4196e | — | missing | JourneyEditor waitForBridgeReady polls every 100ms up to 50 attempts (5s max) for window.pilgrimViewer.loadFile |
| Pilgrim/Scenes/Settings/JourneyEditorView.swift:134-153@db4196e | — | missing | JourneyEditor buildPayload dispatches base64 encode to Task.detached(priority:.userInitiated) and defers file removal there |
| Pilgrim/Scenes/Settings/JourneyEditorView.swift:163-201@db4196e | — | missing | JourneyEditor uses non-persistent WKWebsiteDataStore and reloadIgnoringLocalAndRemoteCacheData URLRequest to avoid stale JS bundle |
| Pilgrim/Scenes/Settings/JourneyEditorView.swift:221-255@db4196e | — | missing | Coordinator.injected latch fires loadFile injection exactly once on first didFinish navigation |
| Pilgrim/Scenes/Settings/JourneyEditorView.swift:290-319@db4196e | — | missing | savePilgrim WKScriptMessageHandler decodes base64, writes atomic to temp dir, then publishes savedFile + pendingTempCleanup via .main.async |
| Pilgrim/Scenes/Settings/JourneyViewerView.swift:196-215@db4196e | — | missing | JourneyViewer JS injection deferred 1.0 seconds after didFinish to allow page settling |
| Pilgrim/Scenes/Settings/JourneyViewerView.swift:133-160@db4196e | — | missing | JourneyViewer photo enrichment uses synchronous PHImageManager.requestImage with isSynchronous=true, isNetworkAccessAllowed=false |
| Pilgrim/Scenes/Settings/PermissionStatusViewModel.swift:6-14@db4196e | — | missing | PermissionStatusViewModel exposes 3 PermissionState fields (location/microphone/motion) with 4 vertices each: granted, notDetermined, denied, restricted |
| Pilgrim/Scenes/Settings/PermissionStatusViewModel.swift:18-23@db4196e | — | missing | PermissionStatusViewModel.needsAttention returns true when location OR microphone is denied or notDetermined (motion is NOT in attention set) |
| Pilgrim/Scenes/Settings/PermissionStatusViewModel.swift:29-51@db4196e | — | missing | PermissionStatusViewModel.refresh() is synchronous: reads CLLocationManager().authorizationStatus, AVAudioSession.recordPermission, CMMotionActivityManager.authorizationStatus |
| Pilgrim/Scenes/Settings/PermissionStatusViewModel.swift:53-57@db4196e | — | missing | openSettings() opens UIApplication.openSettingsURLString to deep-link to app's Settings page |
| Pilgrim/Scenes/Settings/SettingsCards/PermissionsCard.swift:33-36@db4196e | — | missing | PermissionsCard observes UIApplication.willEnterForegroundNotification to refresh permission state on every return from Settings |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:27-39@db4196e | — | missing | PracticeSummaryHeader cycles statPhase through 0/1/2 with %3 wraparound on tap, transitioning between stats/meditation/walkingSince lines |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:71-76@db4196e | — | missing | Milestone toast dismisses 8 seconds after onAppear via DispatchQueue.main.asyncAfter |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:81-82@db4196e | — | missing | PracticeSummaryHeader fires CollectiveCounterService.fetch via .task on view appearance |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:83-85@db4196e | — | missing | PracticeSummaryHeader observes UserDefaults.didChangeNotification to update isImperial when units change anywhere else in the app |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:88-93@db4196e | — | missing | playMilestoneBell gates on UserPreferences.soundsEnabled then resolves walkStartBellId via AudioManifestService and plays BellPlayer at volume 0.4 |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:7-22@db4196e | — | missing | RecordingsListView holds 13+ state vertices including walkSections, deletedPaths set, transcriptionOverrides map, waveforms map, fileSizes map, plus UI sheet/alert flags |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:42-43@db4196e | — | missing | RecordingsListView.onAppear loads walks synchronously; .onDisappear stops audio player |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:325-334@db4196e | — | missing | Tap-to-seek on inactive waveform toggles playback then delays seek 0.1s to allow player startup |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:394-420@db4196e | — | missing | loadWaveformAndSize uses Task.detached(priority:.utility) for the WaveformGenerator pass, then awaits back to MainActor for state assignment |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:424-431@db4196e | — | missing | retranscribe spawns Task to call TranscriptionService.transcribeSingle and writes result to transcriptionOverrides map only on success |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:78-93@db4196e | — | missing | Delete-all action stops audio, deletes all recording files, then formUnion-merges paths into deletedPaths set |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:96-108@db4196e | — | missing | Delete-single action stops audio only if currentPath matches the target path |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:259-288@db4196e | — | missing | Inline transcription edit commits via Done button: trimmed non-empty writes both overrides map AND DataManager.updateVoiceRecordingTranscription |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:379-390@db4196e | — | missing | filteredSections lowercases searchText and tests recording.transcription (or override) lowercased contains query — case-insensitive |
| Pilgrim/Scenes/Settings/SettingsCards/AtmosphereCard.swift:40-43@db4196e | — | missing | AtmosphereCard.onAppear re-syncs soundsEnabled + appearanceMode from UserPreferences |
| Pilgrim/Scenes/Settings/SettingsCards/AtmosphereCard.swift:30-37@db4196e | — | missing | AtmosphereCard sounds settings NavigationLink to SoundSettingsView is conditionally shown only when soundsEnabled is on |
| Pilgrim/Scenes/Settings/SettingsCards/AtmosphereCard.swift:39@db4196e | — | missing | AtmosphereCard uses 0.2s easeInOut animation duration on soundsEnabled value change |
| Pilgrim/Scenes/Settings/SettingsCards/ConnectCard.swift:5-10@db4196e | — | missing | ConnectCard holds showPodcast bool for SafariView sheet over fixed podcast URL |
| Pilgrim/Scenes/Settings/SettingsCards/ConnectCard.swift:61-74@db4196e | — | missing | Connect share action uses UIActivityViewController with fixed text + shareURL, walks scene tree to find foregroundActive UIWindowScene's topmost presenter |
| Pilgrim/Scenes/Settings/SettingsCards/PermissionsCard.swift:75-95@db4196e | — | missing | PermissionsCard renders 4 mutually-exclusive trailing actions based on PermissionState: checkmark (granted), Grant button (notDetermined), Settings button (denied), 'Restricted' label |
| Pilgrim/Scenes/Settings/SettingsCards/PracticeCard.swift:5-12@db4196e | — | missing | PracticeCard holds 7 toggle/picker @State fields including walkReliquary which is paired with showPhotosDeniedNote latch |
| Pilgrim/Scenes/Settings/SettingsCards/PracticeCard.swift:82-109@db4196e | — | missing | Reliquary toggle ON path: clear denied-note, request photos permission, then guard against stale callback (user toggled off mid-request) and revert if so |
| Pilgrim/Scenes/Settings/SettingsCards/PracticeCard.swift:119-120@db4196e | — | missing | PracticeCard uses 0.2s easeInOut animation on celestialAwareness AND on showPhotosDeniedNote (two separate value-keyed animations) |
| Pilgrim/Scenes/Settings/SettingsCards/PracticeCard.swift:46-52@db4196e | — | missing | applyUnitSystem(metric:) is a single-call distinct from individual UserPreferences toggles, propagating to all unit-pref properties at once |
| Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:5-10@db4196e | — | missing | VoiceCard holds 5 vertices: voiceGuideEnabled, dynamicVoiceEnabled, autoTranscribe, recordingCount, recordingSizeMB — with transcriptionService.state observed as ObservableObject |
| Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:84-104@db4196e | — | missing | Auto-transcribe ON branch: if model already downloaded, just write pref; else Task await ensureModelReady then write pref only if userIntent latched true |
| Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:50-61@db4196e | — | missing | VoiceCard observes transcriptionService.state .downloadingModel(progress) case to show inline ProgressView |
| Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:108-113@db4196e | — | missing | VoiceCard.refreshStats reads recording dir size synchronously off main using FileManager.sizeOfDirectory |
| Pilgrim/Scenes/Settings/SettingsView.swift:6-13@db4196e | — | missing | SettingsView root holds 7 @State + 1 @StateObject (PermissionStatusViewModel) for top-level orchestration |
| Pilgrim/Scenes/Settings/SettingsView.swift:55-73@db4196e | — | missing | SettingsView.onAppear refreshes perms, loads stats, then triggers hasAppeared one-shot entrance animation only if !hasAppeared |
| Pilgrim/Scenes/Settings/SettingsView.swift:26-40@db4196e | — | missing | SettingsView cardEntrance modifier uses per-card delay values 0.0, 0.1, 0.2, ..., 0.7 (8 cards × 0.1s) |
| Pilgrim/Scenes/Settings/SettingsView.swift:79-92@db4196e | — | missing | Pull-to-reveal tagline uses GeometryReader frame offset > 40 threshold and (offset-40)/60 opacity ramp |
| Pilgrim/Scenes/Settings/SettingsView.swift:123-138@db4196e | — | missing | SettingsView.loadStats fetches walks synchronously via DataManager.dataStack.fetchAll (CoreStore main-stack read) |
| Pilgrim/Scenes/Settings/SoundSettingsView.swift:5-24@db4196e | — | missing | SoundSettingsView holds 11+ @State + observes 3 ObservableObjects (manifestService, downloadManager, soundscapePlayer); activePicker drives a PickerType enum sheet |
| Pilgrim/Scenes/Settings/SoundSettingsView.swift:439-463@db4196e | — | missing | PickerType enum has 6 vertices (walkStartBell, walkEndBell, meditationStartBell, meditationEndBell, soundscape, breathRhythm) each with title and subtitle |
| Pilgrim/Scenes/Settings/SoundSettingsView.swift:54-57@db4196e | — | missing | SoundSettings.onDisappear stops BOTH bellPlayer and soundscapePlayer |
| Pilgrim/Scenes/Settings/SoundSettingsView.swift:337-372@db4196e | — | missing | Soundscape row tap: play if not active OR stop if currentAsset matches and isPlaying — handles same-asset toggle vs different-asset switch |
| Pilgrim/Scenes/Settings/SoundSettingsView.swift:198-204@db4196e | — | missing | Clear Downloaded Sounds: fileStore.clearAll() then AudioManifestService.shared.syncIfNeeded() to refresh manifest after deletion |
| Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift:14-27@db4196e | — | missing | VoiceGuideSettingsView state machine: enabled gate, then [syncing+empty packs] vs [empty packs] vs [packs] sub-states |
| Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift:39-44@db4196e | — | missing | VoiceGuideSettingsView.onAppear: re-read enabled+selectedPackId AND call manifestService.syncIfNeeded() |
| Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift:201-216@db4196e | — | missing | Pack download-complete watcher: after activeDownloads no longer contains pack.id AND pack is downloaded AND no pack is currently selected, auto-select this pack |
| Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift:174-186@db4196e | — | missing | Pack swipe-delete blocked when isSelected (only delete unselected, downloaded packs); also nils selectedPackId if currently selected pack got deleted |
| Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift:135-146@db4196e | — | missing | Pack tap selects ONLY if isDownloaded; not-downloaded taps are no-ops |
| Pilgrim/Models/Settings/PolicyManager.swift:28-66@db4196e | — | missing | PolicyManager.query uses URLSession.dataTask off-main and explicitly hops to DispatchQueue.main.async wrap before calling completion |
| Pilgrim/Models/Settings/PolicyManager.swift:26-89@db4196e | — | missing | PolicyManager.baseURL is fixed 'https://pilgrimapp.org/policies/' + 'terms-of-service.txt' or 'privacy-policy.txt' |
| Pilgrim/Models/Settings/SettingsModel.swift:81-92@db4196e | — | missing | Legacy SettingsModel/SettingSection/Setting hierarchy is the OLD pre-SwiftUI settings — current SettingsView and *Card files are the active path, SettingsModel.main is dead |
| Pilgrim/Models/Settings/SettingsModel.swift:206-218@db4196e | — | missing | Legacy SettingsModel.dataPreferences has empty selectAction for 'DeleteAllData' — feature unwired |
| Pilgrim/Models/Settings/SettingsModel.swift:225-245@db4196e | — | missing | Legacy SettingsModel.support has TermsOfService and PrivacyPolicy with commented-out controller wiring |
| Pilgrim/Scenes/Settings/AboutView.swift:52-59@db4196e | — | missing | AboutView .task fires loadWalkData on every appearance (no first-appearance guard) — re-runs on navigation back |
| Pilgrim/Scenes/Settings/SettingsView.swift:11@db4196e | — | missing | SettingsView.hasAppeared one-shot latch is @State (NOT @SceneStorage) — survives view re-creation but NOT process death |
| Pilgrim/Scenes/Settings/PermissionStatusViewModel.swift:35-51@db4196e | — | missing | Permission Request callbacks use [weak self] capture pattern to prevent retain cycles |
| Pilgrim/Scenes/Settings/JourneyEditorView.swift:235-238@db4196e | — | missing | JourneyEditor coordinator's `injected` bool is the first-emission guard — first didFinish triggers injection, subsequent didFinish (sub-frames, redirects) ignored |
| Pilgrim/Scenes/Settings/JourneyViewerView.swift:189-198@db4196e | — | missing | JourneyViewer coordinator's `injected` bool is the first-emission guard — identical pattern to JourneyEditor |
| Pilgrim/Scenes/Settings/AppearanceView.swift:4-8@db4196e | — | missing | AppearanceView mode field initialized at @State from UserPreferences.appearanceMode.value — captures only at view first-creation, then re-synced in onAppear |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:93-94@db4196e | — | missing | RecordingsListView searchable view modifier provides SwiftUI-managed searchText binding and search bar; no explicit search subscription lifetime |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:188-196@db4196e | — | missing | RecordingsListView play-toggle: clicks the play/pause button atomically toggles via audioPlayer.toggle(relativePath:) — pause from elsewhere requires audioPlayer.stop() |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:220-230@db4196e | — | missing | Speed button cycles speeds via audioPlayer.cycleSpeed(); speeds > 1.0 use inverted color scheme (parchment fg, stone bg) vs default |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:511-518@db4196e | — | missing | WaveformBarView uses DragGesture(minimumDistance:0) + onChanged for continuous scrubbing — every drag event seeks immediately, no debounce |
| Pilgrim/Scenes/Settings/AboutView.swift:60-62@db4196e | — | missing | AboutView linkRow safariURL=IdentifiableURL → .sheet(item:) opens SafariView with URL; in-app browser pattern |
| Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:76-79@db4196e | — | missing | VoiceCard.onAppear re-reads voiceGuideEnabled BUT NOT the other 5 toggle states — only one toggle is re-synced on view appearance |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:36-46@db4196e | — | missing | DataSettingsView ExportConfirmData snapshot is frozen at button-tap time and persists via @State exportConfirmData; sheet displays the frozen snapshot regardless of subsequent DB changes |
| Pilgrim/Scenes/Settings/SoundSettingsView.swift:175-184@db4196e | — | missing | SoundSettingsView storageSection downloads spinner driven by downloadManager.isDownloading flag |

## Drift — Lens: ui-visual

| iOS finding | Android finding | severity | claim |
|---|---|---|---|
| Pilgrim/Scenes/Settings/AppearanceView.swift@db4196e | — | missing | AboutView appearance modes ('Auto'/'Light'/'Dark') and Edit My Journey link do NOT exist at pinned SHA db4196e — the file list's `AppearanceView.swift` and `JourneyEditorView.swift` were added in post-v1.5.0 commit 20142de (1.6.0). |
| Pilgrim/Scenes/Settings/SettingsView.swift:16-44@db4196e | — | missing | Settings root is NavigationStack { ScrollView { VStack(spacing: big) } } with 8 cards stacked vertically: PracticeSummaryHeader, PracticeCard, AtmosphereCard, VoiceCard, PermissionsCard, DataCard, ConnectCard, aboutLink. |
| Pilgrim/Scenes/Settings/SettingsView.swift:18,42-43@db4196e | — | missing | Cards stack with 24pt vertical spacing; 16pt horizontal page inset; 64pt bottom breathing-room. |
| Pilgrim/Scenes/Settings/SettingsView.swift:79-92@db4196e | — | missing | Pull-to-reveal tagline appears only when GeometryReader-tracked scroll minY exceeds 40pt, with opacity ramp from 0→1 over the next 60pt (i.e., fully visible at minY=100). |
| Pilgrim/Scenes/Settings/SettingsView.swift:142-157@db4196e | — | missing | Cards perform staggered entrance: each card fades opacity 0→1 and translates y from 20→0; delay is index * 0.1s (0.0, 0.1, ... 0.7 across 8 cards); curve is easeOut(duration: 0.4); reduceMotion bypasses entirely. |
| Pilgrim/Scenes/Settings/SettingsView.swift:67-73,101@db4196e | — | missing | About-link logo breathes when not reduceMotion; animation toggled via @State aboutBreathing on appear/disappear. |
| Pilgrim/Scenes/Settings/SettingsView.swift:49-53@db4196e | — | missing | Toolbar 'Settings' title uses Constants.Typography.heading (CormorantGaramond-SemiBold, 17pt), color = .ink. |
| Pilgrim/Scenes/Settings/SettingsCards/SettingsCardStyle.swift:3-10@db4196e | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsCardStyle.kt:62-67@dc4cef8 | drift-cosmetic | iOS: Card chrome: settingsCard() applies normal padding (16pt) + parchmentSecondary background + cornerRadius normal (12pt).<br>Android: settingsCard() modifier bakes 16dp horizontal indent + RoundedCornerShape(16dp) + parchmentSecondary background + 16dp internal padding. |
| Pilgrim/Scenes/Settings/SettingsCards/SettingsCardStyle.swift:20-30@db4196e | — | missing | cardHeader is a VStack(alignment: .leading, spacing: 2) of heading-font title (ink) over caption-font subtitle (fog), with 8pt bottom padding. |
| Pilgrim/Scenes/Settings/SettingsCards/SettingsCardStyle.swift:32-50@db4196e | — | missing | settingToggle = SwiftUI Toggle with 2-line label (body/ink title over caption/fog description, spacing 2), tinted .stone. |
| Pilgrim/Scenes/Settings/SettingsCards/SettingsCardStyle.swift:52-72@db4196e | — | missing | settingPicker is segmented control with fixed 180pt frame, body-font label on the left. |
| Pilgrim/Scenes/Settings/SettingsCards/SettingsCardStyle.swift:74-91@db4196e | — | missing | settingNavRow is body-font label on left, optional caption-font detail in middle (minScaleFactor 0.7, single-line), and SF Symbol 'chevron.right' on right. |
| Pilgrim/Scenes/Settings/SettingsCards/AtmosphereCard.swift:38-39@db4196e | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/AtmosphereCard.kt:90-95@dc4cef8 | drift-cosmetic | iOS: AtmosphereCard expand/collapse of Bells & Soundscapes nav row uses .animation(.easeInOut(duration: 0.2), value: soundsEnabled).<br>Android: AtmosphereCard bells-soundscapes nav row enter/exit animation: 200ms fadeIn+expandVertically / fadeOut+shrinkVertically. |
| Pilgrim/Scenes/Settings/SettingsCards/AtmosphereCard.swift:30-36@db4196e | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/AtmosphereCard.kt:90-106@dc4cef8 | drift-cosmetic | iOS: Sounds nav row appears only when soundsEnabled toggle is on.<br>Android: AtmosphereCard conditionally renders Bells & Soundscapes row only when soundsEnabled is true. |
| Pilgrim/Scenes/Settings/SettingsCards/PracticeCard.swift:34-42,111-116,119-120@db4196e | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/practice/PracticeCard.kt:176-191@dc4cef8 | drift-cosmetic | iOS: PracticeCard reveals zodiac picker only when celestialAwareness is on, and reveals photo-denied note only when showPhotosDeniedNote.<br>Android: PracticeCard photos-denied note conditionally renders only when showPhotosDeniedNote (separate from walkReliquary toggle state). |
| Pilgrim/Scenes/Settings/SettingsCards/PracticeCard.swift:54-58@db4196e | — | missing | Units-system summary uses U+00B7 (·) middle-dot separator with single-line scaling at 0.7 min factor; entire line is caption/fog. |
| Pilgrim/Scenes/Settings/SettingsCards/PermissionsCard.swift:46-73@db4196e | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCard.kt:180-185@dc4cef8 | drift-cosmetic | iOS: PermissionsCard dot color encodes permission state: granted=.moss, notDetermined=.dawn, denied=.rust, restricted=.fog. Dot is a 10×10pt Circle.<br>Android: PermissionsCard dotColor map: Granted=moss, NotDetermined=dawn, Denied=rust, Restricted=fog. |
| Pilgrim/Scenes/Settings/SettingsCards/PermissionsCard.swift:75-95@db4196e | — | missing | Permission action button uses Constants.Typography.button (Lato-Bold 17) when actionable (Grant/Settings); restricted text uses Constants.Typography.caption. |
| Pilgrim/Scenes/Settings/SettingsCards/ConnectCard.swift:45-59@db4196e | — | missing | ConnectCard rows: 14pt SF Symbol icon in a 24pt-wide alignment-center frame on left, body label, then external-link 'arrow.up.right' or 'chevron.right' caption-size icon on right. |
| Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:24-30,50-61,65-72@db4196e | — | missing | VoiceCard reveals Guide Packs nav row when voiceGuideEnabled; shows linear ProgressView + percent label when transcriptionService.state == .downloadingModel(progress). Recordings nav-row detail concatenates count + size with • separator (U+2022). |
| Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:75@db4196e | — | missing | Sounds toggle expand animation uses .easeInOut(duration: 0.2) on voiceGuideEnabled. |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:14-86@db4196e | — | missing | PracticeSummaryHeader contains 4 stacked elements: season label (caption/fog) + symbol with .opacity(0.3); cycling stat line; collective stats block with streak flame; milestone toast. |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:27-39,106-115@db4196e | — | missing | Cycling stat line tappable: tap rotates statPhase ∈ {0,1,2} → distance / meditation-time / walking-since lines; phase change animated with easeInOut(duration: 0.3). |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:51-54@db4196e | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt:135-138@dc4cef8 | drift-cosmetic | iOS: Streak flame appears only when streakDays > 1 (Archetype B threshold).<br>Android: PracticeSummaryHeader streak only shows if streakDays > 1 (Archetype B threshold — 1-day streaks suppressed). |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:60-82@db4196e | — | missing | Milestone banner: appears with .transition(.opacity), plays bell on appear, auto-dismisses after 8 seconds via DispatchQueue.asyncAfter + withAnimation. Outer animation key: milestone.number with easeInOut(0.5). |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:64-69@db4196e | — | missing | Milestone banner: 24pt horizontal × 8pt vertical inner padding, 8pt-radius corner over Color.moss.opacity(0.08) background, 8pt top spacing from preceding content. |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:43,62; Pilgrim/Scenes/Settings/AboutView.swift:303@db4196e | — | missing | Body.italic() typography variant used in 3 places (milestone banner, collective progress message, motto) — italic is a derived font style applied via .body.italic() / .caption.italic(). |
| Pilgrim/Scenes/Settings/AboutView.swift:18-38@db4196e | — | missing | AboutView is ScrollView { VStack(alignment: .leading, spacing: 0) } with 7 sections divided by linear-gradient horizontal dividers; whole page padded horizontal big(24). |
| Pilgrim/Scenes/Settings/AboutView.swift:335-343@db4196e | — | missing | AboutView divider is a LinearGradient (clear → stone.opacity(0.2) → clear) at 1pt height; this is NOT a plain divider line. |
| Pilgrim/Scenes/Settings/AboutView.swift:89-108@db4196e | — | missing | AboutView hero PilgrimLogoView is 80pt size; top padding = big + normal (40pt = 24+16); 'Every walk is a small pilgrimage.' text is displayMedium-italic (CormorantGaramond-Light 28 italic), multiline centered. |
| Pilgrim/Scenes/Settings/AboutView.swift:147-167@db4196e | — | missing | Pillar row: 36×36 tinted-fill Circle with 16pt SF Symbol overlay, then heading (CormorantGaramond-SemiBold 17) over caption (Lato 12); circle fill uses tint.opacity(Constants.UI.Opacity.light=0.12). |
| Pilgrim/Scenes/Settings/AboutView.swift:115-120@db4196e | — | missing | Pillars subhead 'walk · talk · meditate' uses caption font with tracking 3 (letter-spacing) over .stone color. |
| Pilgrim/Scenes/Settings/AboutView.swift:233-237@db4196e | — | missing | 'OPEN SOURCE' caption (Lato 12) uses .tracking(2) and .foregroundColor(.stone.opacity(0.6)) — 60% stone tint vs full stone in other captions. |
| Pilgrim/Scenes/Settings/AboutView.swift:215-227@db4196e | — | missing | AboutView footprint trail: 4 mirrored, rotated FootprintShapes with varying opacity ramp (0.08 + idx*0.04 = 0.08, 0.12, 0.16, 0.20). Each is 12×18pt; alternating scaleEffect(x: -1) for mirror and rotationEffect ±10°. |
| Pilgrim/Scenes/Settings/AboutView.swift:432-452@db4196e | — | missing | Each AboutView section uses sectionAppear modifier: opacity 0→1 + offset y 8→0; index 0..4; per-index delay = idx * 0.1; curve = easeInOut(duration: Constants.UI.Motion.appear=0.4); reduceMotion bypasses. |
| Pilgrim/Scenes/Settings/AboutView.swift:171-211@db4196e | — | missing | statsWhisper: stat value uses Typography.statValue (Lato-Regular 20) with .stone color; numericText contentTransition for value AND label morphing. |
| Pilgrim/Scenes/Settings/AboutView.swift:61-84@db4196e | — | missing | AboutView icon-change dialog: Buttons appear conditionally based on guideId + voiceGuideEnabled + current alternateIconName state — three nested guards, all using UIApplication.shared.alternateIconName comparison. |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:52-128@db4196e | — | missing | DataSettingsView is List-based (not ScrollView), with grouped Section headers/footers, .scrollContentBackground(.hidden) + .background(Color.parchment). |
| Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:37-53@db4196e; DataSettingsView.swift:158-159@db4196e | — | missing | ExportConfirmationSheet uses .presentationDetents([.medium]) (~50% screen height fixed), .presentationDragIndicator(.visible); body is VStack { header / ScrollView{content} / buttonBar } — header & button bar pinned outside scroll. |
| Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:128-136@db4196e | — | missing | Export button is capsule-shaped: stone-fill background, parchment text, Padding.big horizontal + 12pt vertical (12 is magic, not a Padding token). |
| Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:112-124@db4196e | — | missing | Cancel button has 44pt minimum hit area enforced via 16pt horizontal + 12pt vertical padding + contentShape(Rectangle()). |
| Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:168-175@db4196e | — | missing | Photo toggle subtitle composes 'N photos · ≈X.X MB' via ByteCountFormatter (.file, KB/MB) + ASCII bullet (·). |
| Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:35,64-74,182-187@db4196e | — | missing | Photo toggle section visible only when pinnedPhotoCount > 0 (Archetype B threshold). When hidden, effectiveIncludePhotos always returns false regardless of @State. |
| Pilgrim/Scenes/Settings/FeedbackView.swift:35-50@db4196e | — | missing | FeedbackView form is ScrollView containing categoryCards / textEditor / deviceInfoToggle / errorText? / sendButton in VStack(spacing: big=24), all inside .padding(Constants.UI.Padding.big). |
| Pilgrim/Scenes/Settings/FeedbackView.swift:52-90@db4196e | — | missing | Category cards: Title3 (~20pt) SF icon in 28pt-wide frame, body label, optional checkmark.moss when selected. Background = .stone.opacity(0.08) if selected else .parchmentSecondary; 1pt stone stroke when selected else .clear; cornerRadius.normal (12). |
| Pilgrim/Scenes/Settings/FeedbackView.swift:92-112@db4196e | — | missing | TextEditor minHeight 120pt, padded small(8) inside parchmentSecondary background with cornerRadius.normal(12); placeholder overlay aligned topLeading with top padding 8pt + leading 4pt. |
| Pilgrim/Scenes/Settings/FeedbackView.swift:134-154@db4196e | — | missing | Send button uses canSubmit-gated background: Color.stone when canSubmit else Color.fog.opacity(0.2); text is .parchment, cornerRadius.normal(12), 12pt vertical padding. |
| Pilgrim/Scenes/Settings/FeedbackView.swift:162-184@db4196e | — | missing | Submission success → confirmation overlay transitions with .opacity, easeInOut(duration: 0.5), then auto-dismisses after 2.5 s (sleep 2_500_000_000 ns). |
| Pilgrim/Scenes/Settings/FeedbackView.swift:188-206@db4196e | — | missing | Confirmation overlay is a 3-element VStack between two Spacers: checkmark.largeTitle/moss → 2-line 'Your note has been\nleft on the path.' body/ink → 'Thank you.' body.italic/fog; whole overlay parchment-backgrounded. |
| Pilgrim/Scenes/Settings/JourneyViewerView.swift:13-42@db4196e | — | missing | JourneyViewerView is ZStack { JourneyWebView + loading overlay + error overlay }, with explicit ignoresSafeArea(edges:.bottom) on the webview only. |
| Pilgrim/Scenes/Settings/JourneyViewerView.swift:196-215@db4196e | — | missing | JourneyWebView injects walks JSON via webView.callAsyncJavaScript after a hard-coded 1.0s asyncAfter delay (waits for the page's pilgrimViewer to mount). |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:51-93@db4196e | — | missing | RecordingsListView is a List of grouped sections per Walk, with section headers, swipe actions on rows (Delete trailing, Retranscribe leading), and a final destructive 'Delete All Recording Files' button. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:188-231@db4196e | — | missing | Player header: 'pause/play.circle.fill' .title2 button (auto-sized ~22pt), label VStack with body 'Recording N' over caption duration+size+enhanced trio, then speed-cycle pill (caption text, 6pt horizontal/3pt vertical padding, cornerRadius 4, stone-tinted when >1.0x else stone.opacity(0.12)). |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:481-521@db4196e | — | missing | Waveform: GeometryReader-sized 32pt-tall bar row with 0.5pt inter-bar spacing; per-bar width = (totalWidth / sampleCount) - 0.5; mask-clipped 'stone' overlay over 'fog.opacity(0.4)' base; drag-to-seek gesture across width. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:260-288@db4196e | — | missing | Transcription block in edit mode: TextEditor minHeight 60 / maxHeight 200, parchmentTertiary background, cornerRadius.small=8, 4pt internal padding; Done button is caption-text 12×4 padding, stone-translucent (0.12) background, cornerRadius 4. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:157-178@db4196e | — | missing | Recordings rows expose swipeActions (Delete, Retranscribe) but have no explicit accessibilityLabel / accessibilityValue / accessibilityHint. Symbol-effect replacement transition + waveform-drag gesture have no a11y override. |
| Pilgrim/Scenes/Settings/SoundSettingsView.swift:25-58,133-172@db4196e | — | missing | SoundSettingsView body is a List of conditional sections: mainToggle (always), then walk/meditation/volume/storage only when soundsEnabled. Sections have caption-font headers; volume rows use VStack(spacing: 6) of label/percent row + Slider. |
| Pilgrim/Scenes/Settings/SoundSettingsView.swift:251-285@db4196e | — | missing | Bell picker / soundscape picker / breath-rhythm picker presented via .sheet(item: $activePicker) with .presentationDetents([.medium, .large]) (two-step). Each picker has a custom toolbar showing centered title+subtitle VStack. |
| Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift:135-221@db4196e | — | missing | VoiceGuideSettingsView pack row: 28pt-wide title2 SF icon, body name over caption tagline (single-line), trailing slot is one of {linear ProgressView (40pt frame), 'arrow.down.circle' button, 'checkmark'}. |
| Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift:14-27@db4196e | — | missing | VoiceGuideSettingsView main-content branches by predicates: 'syncing + empty' → loading, 'empty' → empty, else → packs + volume. Wrapped under `if enabled { ... }`. |
| Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift:83,149-160; Pilgrim/Scenes/Settings/SoundSettingsView.swift:291,339@db4196e | — | missing | ForEach(manifestService.packs) and ForEach(manifestService.soundscapes) feed Compose-cascadable models. VoiceGuidePack / AudioAsset hold non-primitive types (Date capturedAt, custom enums) — must be @Immutable in Android port. |
| Pilgrim/Scenes/Settings/SettingsCards/SettingsCardStyle.swift:7-8,23-27; Pilgrim/Scenes/Settings/SettingsCards/PermissionsCard.swift:66-72; Pilgrim/Scenes/Settings/RecordingsListView.swift:269@db4196e | — | missing | Color palette referenced across Settings slice: .parchment (background), .parchmentSecondary (card bg), .parchmentTertiary (deeper inset bg), .ink (primary text), .fog (secondary text + chevrons), .stone (accent/tint), .moss (green/granted), .dawn (yellow/notDetermined), .rust (red/denied). |
| Pilgrim/Scenes/Settings/*.swift@db4196e | — | missing | No explicit accessibilityLabel / accessibilityHint / contentDescription / customActions found in any Settings file — all rows rely on SwiftUI auto-derived a11y from Text contents and SF Symbol roles. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:449,456,461; Pilgrim/Scenes/Settings/SoundSettingsView.swift:193; Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:70; Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:101,122@db4196e | — | missing | Numeric/format strings throughout use String(format: "%.1f MB", ...) / "%d:%02d" / "%.0f mi" patterns WITHOUT explicit Locale — relies on default locale's DecimalStyle. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:116-122@dc4cef8 | extra | AboutScreen root content uses a vertically-scrolling Column with 24dp horizontal padding wrapping all sections. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:152-158@dc4cef8 | extra | AboutScreen HeroSection uses 48dp top + 32dp bottom padding plus 16dp vertical inter-element spacing. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:162-167,469-475@dc4cef8 | extra | HeroSection tree decoration is 36dp; SeasonalVignetteSection tree decoration is 32dp. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:170-175@dc4cef8 | extra | About hero title uses italic displayMedium typography (custom font style override). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:194-202@dc4cef8 | extra | About pillars caption uses caption type with 3.sp letter-spacing override. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:231-264@dc4cef8 | extra | PillarRow uses 36dp icon background circle, 16dp inner icon, 12dp icon-to-text spacing, 2dp vertical text spacing. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:269-293@dc4cef8 | extra | StatsWhisperSection cycles 3 phases via AnimatedContent fadeIn-togetherWith-fadeOut on tap. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:300-308@dc4cef8 | extra | Singular vs plural walks label keyed on count == 1 (Archetype B threshold). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:326-330@dc4cef8 | extra | FootprintTrailSection alpha ramps 0.08 → 0.20 across 4 indices via 0.08f + index * 0.04f. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:319-336@dc4cef8 | extra | Footprint canvas is 12dp wide × 18dp tall per print, 16dp horizontal spacing. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:334-335@dc4cef8 | extra | FootprintTrailSection uses Modifier.scale(scaleX, scaleY) value-form on static (non-animated) floats — safe. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/PilgrimLogo.kt:40-50@dc4cef8 | extra | PilgrimLogo breathing animation: 1.0f → 1.02f scale over 4000ms tween, RepeatMode.Reverse, infinite. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/PilgrimLogo.kt:58-64@dc4cef8 | extra | PilgrimLogo correctly uses graphicsLayer { scaleX/Y } lambda form to keep animated scale in layout phase. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/PilgrimLogo.kt:60@dc4cef8 | extra | PilgrimLogo clip uses RoundedCornerShape(percent = 18) — magic percent. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/SeasonalTree.kt:40-65@dc4cef8 | extra | SeasonalTree canopy uses three alpha tiers 0.12, 0.30, 0.50 + trunk 0.40. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/SeasonalTree.kt:46-65@dc4cef8 | extra | SeasonalTree canopy geometry uses fractional offsets/sizes (e.g. w*-0.04 topLeft, w*1.08 size) — magic fractions. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/FootprintShape.kt:17-34@dc4cef8 | extra | FootprintShape composes 8 oval primitives (heel + outer + ball + 5 toes) at fractional rects. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/ConnectCard.kt:27-30@dc4cef8 | extra | ConnectCard 4 nav rows with 4dp vertical spacing — tighter than other cards (which use 8dp). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackScreen.kt:124-130@dc4cef8 | extra | FeedbackScreen FormContent uses 24dp screen padding + 24dp inter-section + 8dp category-card spacing. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackScreen.kt:244-252@dc4cef8 | extra | FeedbackScreen CategoryCard selected vs unselected: 0.08f stone tint vs parchmentSecondary, 1dp stone border when selected. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackScreen.kt:261-298@dc4cef8 | extra | FeedbackScreen CategoryCard icon is 28dp, Check icon is 20dp, ConfirmationOverlay check is 56dp. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackScreen.kt:67-72,316@dc4cef8 | extra | FeedbackScreen confirmation dismisses after exactly 2500ms. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackScreen.kt:205-214@dc4cef8 | extra | FeedbackScreen submit button background gates on canSubmit AND on form-validity (decoupled flags). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsScreen.kt:223-226@dc4cef8 | extra | DataSettingsScreen LazyColumn uses 16dp top/bottom contentPadding + 24dp item spacing. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsScreen.kt:333-344@dc4cef8 | extra | DataSettingsScreen SectionHeader and SectionFooter use 32dp horizontal padding — exceeds settingsCard's 16dp. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsScreen.kt:273-299@dc4cef8 | extra | DataSettingsScreen audio section only renders when recordingCount > 0 (Archetype B threshold). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/JourneyViewerScreen.kt:145-167,198@dc4cef8 | extra | JourneyViewer injects JS exactly 1000ms after onPageFinished — hardcoded delay. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/ExportConfirmationSheet.kt:74-90@dc4cef8 | extra | ExportConfirmationSheet uses 24dp horizontal + 24dp vertical content padding, 24dp section spacing, 8dp toggle row spacing. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/ExportConfirmationSheet.kt:63,82-89@dc4cef8 | extra | ExportConfirmationSheet showsPhotoToggle gates on pinnedPhotoCount > 0. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/ExportConfirmationSheet.kt:242-247@dc4cef8 | extra | ExportConfirmationSheet ButtonBar Export uses RoundedCornerShape(percent = 50) — full pill shape. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCard.kt:135-145@dc4cef8 | extra | PermissionsCard PermissionRow status dot is 10dp circle with 12dp text gap. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCard.kt:113-122@dc4cef8 | extra | PermissionsCard motion permission row only requests on SDK_INT >= Q (29); no-op on older. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/practice/PracticeCard.kt:117-123@dc4cef8 | extra | PracticeCard zodiac picker reveal: 200ms easeInOut fadeIn+expandVertically — comment cites iOS easeInOut(0.2). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/practice/PracticeCard.kt:143-158@dc4cef8 | extra | PracticeCard distance-units caption switches resource by UnitSystem.Metric vs Imperial, allows up to 2 lines. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt:86-92@dc4cef8 | extra | PracticeSummaryHeader Column uses 24dp vertical padding + 8dp inter-element spacing, center-aligned. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt:161-175@dc4cef8 | extra | PracticeSummaryHeader milestone banner animates fadeIn/fadeOut 500ms with FastOutSlowInEasing, dismisses after 8000ms. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt:181-188@dc4cef8 | extra | PracticeSummaryHeader milestone banner uses moss tint at 0.08f alpha with 8dp rounded corners. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt:95-115@dc4cef8 | extra | PracticeSummaryHeader stat phase cycle suppressed entirely when walkCount == 0. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/StreakFlame.kt:38-55@dc4cef8 | extra | StreakFlame outer flicker: 0.9→1.15 scale + 0.3→0.6 alpha over 800ms; inner: 0.95→1.05 scale + 0.7→1.0 alpha over 1100ms. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/StreakFlame.kt:67-86@dc4cef8 | extra | StreakFlame correctly uses graphicsLayer { scaleX/Y } lambda form for animated scale (Stage 5-A pattern verified). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/StreakFlame.kt:66-78@dc4cef8 | extra | StreakFlame outer/inner icons both use rust tint with dynamic alpha (0.3+flicker1*0.3 / 0.7+flicker2*0.3). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsCardStyle.kt:183-186@dc4cef8 | extra | SettingPicker caps segmented row width at 220dp and height at 32dp. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsCardStyle.kt:197-207@dc4cef8 | extra | SettingPicker SegmentedButton inactive uses ink at 0.6f alpha (not fog) for WCAG AA contrast in dark mode. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsCardStyle.kt:248-293@dc4cef8 | extra | SettingNavRow heightIn(min = 48dp) for accessible touch target; trailing chevron is 16dp. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsCardStyle.kt:249-251@dc4cef8 | extra | SettingNavRow correctly threads onClickLabel into clickable for TalkBack announcement. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsCardStyle.kt:305-312@dc4cef8 | extra | SettingsDivider uses fog at 0.2f alpha — subtler than default M3 outline. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt:108-238@dc4cef8 | extra | SettingsScreen reserves 48dp title height + 12dp blur overlay overhang via haze gradient on top zIndex. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt:239-249@dc4cef8 | extra | SettingsScreen Text title applied padding(top=16) AND padding(top=8, bottom=16) — double-padding pattern. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt:117-124@dc4cef8 | extra | SettingsScreen LazyColumn contentPadding top=titleHeightDp(48) bottom=120dp, item spacing 16dp. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt:196-216@dc4cef8 | extra | SettingsScreen About row uses PilgrimLogo(size=24, breathing=true) — same logo as About hero but tiny. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsScreen.kt:132-208@dc4cef8 | extra | SoundSettingsScreen replicates AnimatedVisibility 200ms expand/shrink for each of 4 conditional sections (Walk/Meditation/Volume/Storage). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsScreen.kt:112-120@dc4cef8 | extra | SoundSettingsScreen title uses top=8, bottom=16 padding (NOT the double-padding seen in SettingsScreen). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsScreen.kt:443-455@dc4cef8 | extra | SoundSettingsScreen VolumeRow uses 19 steps on 0-1 range (20 discrete levels) for slider. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsScreen.kt:319-330@dc4cef8 | extra | SoundSettingsScreen MeditationSection takes List<AudioAsset> (potentially Unstable) into LazyColumn item. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/BellPickerSheet.kt:64-119@dc4cef8 | extra | BellPickerSheet uses 16dp horizontal + 8dp vertical container padding, 4dp item spacing, BellRow heightIn min 48dp. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/BellPickerSheet.kt:122-136@dc4cef8 | extra | BellPickerSheet BellRow conditionally renders 40dp Spacer when onPreview is null (slot reservation). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/BreathRhythmPickerSheet.kt:96-99@dc4cef8 | extra | BreathRhythmPickerSheet RhythmRow heightIn min 56dp (vs BellPickerSheet's 48dp). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/soundscape/SoundscapePickerScreen.kt:169-193@dc4cef8 | extra | SoundscapePickerScreen ListItem uses Color.Transparent container (overrides M3 default). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/soundscape/SoundscapePickerScreen.kt:239-244@dc4cef8 | extra | SoundscapePickerScreen status colors: Failed=rust, Downloaded=moss, others=fog. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/soundscape/SoundscapePickerScreen.kt:188-192@dc4cef8 | extra | SoundscapePickerScreen uses combinedClickable with onLongClick for delete confirmation — TalkBack-invisible without semantic action. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/soundscape/SoundscapePickerScreen.kt:125@dc4cef8 | extra | SoundscapePickerScreen HorizontalDivider uses fog at 0.25f alpha (vs SettingsDivider at 0.2f). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voice/VoiceCard.kt:78-83@dc4cef8 | extra | VoiceCard Guide Packs reveal explicitly sets EaseInOut easing (not relying on tween default) — only screen to do so. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voice/VoiceCard.kt:114-122@dc4cef8 | extra | VoiceCardState is @Stable annotated — VoiceCard should skip recompose on identical state. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePickerScreen.kt:109-117@dc4cef8 | extra | VoiceGuidePickerScreen LazyColumn renders List<VoiceGuidePackState> sealed class — stability of each variant must be audited. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePickerScreen.kt:164-174@dc4cef8 | extra | VoiceGuidePickerScreen CircularProgressIndicator wraps state.fraction directly — fine-grained progress updates. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePackDetailScreen.kt:154@dc4cef8 | extra | VoiceGuidePackDetailScreen header uses pilgrimType.displayMedium directly (no italic) vs About hero which adds Italic. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePackDetailScreen.kt:188-273@dc4cef8 | extra | VoiceGuidePackDetailScreen uses FontWeight.Medium override on labels via direct property (not via pilgrimType). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePackDetailScreen.kt:108-164@dc4cef8 | extra | VoiceGuidePackDetailScreen LinearProgressIndicator uses moss tint; CircularProgressIndicator on Loading state uses stone tint. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/soundscape/SoundscapePickerScreen.kt:67,app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePickerScreen.kt:66@dc4cef8 | extra | SoundscapePickerScreen and VoiceGuidePickerScreen TopAppBar back button uses Icons.Default.ArrowBack (not AutoMirrored). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackScreen.kt:99-113@dc4cef8 | extra | ConfirmationOverlay dismissal coupling: showConfirmation drives both UI swap AND 2500ms LaunchedEffect timer. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/BellPickerSheet.kt:114-149@dc4cef8 | extra | BellPickerSheet BellRow uses onClickLabel = label for TalkBack; Check icon has null contentDescription. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsScreen.kt:312-318@dc4cef8 | extra | DataSettingsScreen ExportingRow uses ink at 0.6f alpha — dimmed text during in-flight operations. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePackDetailScreen.kt:148-152@dc4cef8 | extra | VoiceGuidePackDetailScreen uses verticalScroll Column with 24dp horizontal + 16dp vertical padding (inconsistent with SettingsScreen's settingsCard 16/16). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsScreen.kt:262-278@dc4cef8 | extra | MainToggleSection's bell-haptic reveal animates inside an already-animated parent — nested 200ms animations possible. |

## Drift — Lens: data

*No findings available on disk for this lens — see Open Questions.*

## Drift — Lens: edge-cases

| iOS finding | Android finding | severity | claim |
|---|---|---|---|
| Pilgrim/Scenes/Settings/AboutView.swift:91@db4196e | — | missing | Hero logo size is hard-coded 80pt (not from Constants). |
| Pilgrim/Scenes/Settings/AboutView.swift:149-156@db4196e | — | missing | Circle avatar size hard-coded 36x36 inside pillar row. |
| Pilgrim/Scenes/Settings/AboutView.swift:157@db4196e | — | missing | Inner VStack spacing literally 2pt (sub-Constants). |
| Pilgrim/Scenes/Settings/AboutView.swift:117@db4196e | — | missing | Pillars caption letter-spacing tracking(3). |
| Pilgrim/Scenes/Settings/AboutView.swift:173@db4196e | — | missing | Stat-mode toggle animation duration 0.3s. |
| Pilgrim/Scenes/Settings/AboutView.swift:217@db4196e | — | missing | Footprint trail uses ForEach 0..<4 (exactly 4 footprints). |
| Pilgrim/Scenes/Settings/AboutView.swift:219@db4196e | — | missing | Footprint opacity formula 0.08 + index * 0.04 (per-index stepped). |
| Pilgrim/Scenes/Settings/AboutView.swift:220-222@db4196e | — | missing | Footprint shape size 12x18 + rotation ±10°. |
| Pilgrim/Scenes/Settings/AboutView.swift:235@db4196e | — | missing | Open-source caption tracking(2) (distinct from pillars tracking(3)). |
| Pilgrim/Scenes/Settings/AboutView.swift:236@db4196e | — | missing | Stone-opacity-0.6 on OPEN SOURCE label is bespoke (not Constants.UI.Opacity). |
| Pilgrim/Scenes/Settings/AboutView.swift:256@db4196e | — | missing | `UIApplication.shared.open(URL(string: ...)!)` force-unwraps a literal review URL. |
| Pilgrim/Scenes/Settings/AboutView.swift:246,252,256@db4196e | — | missing | linkRow URLs constructed with `URL(string:)!` — three literal URLs. |
| Pilgrim/Scenes/Settings/AboutView.swift:306@db4196e | — | missing | Motto block uses lineSpacing(8). |
| Pilgrim/Scenes/Settings/AboutView.swift:316-318@db4196e | — | missing | Seasonal vignette tree icon size 40, opacity Opacity.medium. |
| Pilgrim/Scenes/Settings/AboutView.swift:328@db4196e | — | missing | Version label opacity 0.3 on .fog. |
| Pilgrim/Scenes/Settings/AboutView.swift:337-342@db4196e | — | missing | Divider gradient stone-opacity-0.2 and height 1. |
| Pilgrim/Scenes/Settings/AboutView.swift:393,397@db4196e | — | missing | Miles conversion uses 1609.344 and 3.28084 literals. |
| Pilgrim/Scenes/Settings/AboutView.swift:394@db4196e | — | missing | `if miles >= 1` switches between mi and ft. |
| Pilgrim/Scenes/Settings/AboutView.swift:399@db4196e | — | missing | `if meters >= 1000` km vs m switch. |
| Pilgrim/Scenes/Settings/AboutView.swift:407-409@db4196e | — | missing | DateFormatter `"MMM yyyy"` without explicit locale (uses default Locale). |
| Pilgrim/Scenes/Settings/AboutView.swift:442@db4196e | — | missing | SectionAppearModifier per-index stagger delay 0.1s. |
| Pilgrim/Scenes/Settings/AboutView.swift:440@db4196e | — | missing | SectionAppearModifier offset 8pt before reveal. |
| Pilgrim/Scenes/Settings/AppearanceView.swift:24-27@db4196e | — | missing | Touching themeID inside body to force re-evaluation on theme change. |
| Pilgrim/Scenes/Settings/AppearanceView.swift:37@db4196e | — | missing | Glyph frame width hard-coded 28pt in mode rows. |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:50@db4196e | — | missing | estimatedBytesPerPhoto = 80,000 — pivotal constant for export size label. |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:184-193@db4196e | — | missing | Re-entry guard for tap during sheet-visible window (state machine documented). |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:332-343@db4196e | — | missing | Post-share alert must fire AFTER share sheet dismisses, not before. |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:268-270@db4196e | — | missing | `URL.startAccessingSecurityScopedResource()` paired with `stopAccessingSecurityScopedResource()`. |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:275,341@db4196e | — | missing | Singular/plural via inline ternary `count == 1 ? "" : "s"`. |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:306@db4196e | — | missing | FileManager documentDirectory first force-unwrap. |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:289@db4196e | — | missing | Recording-export ProgressView dispatch QOS userInitiated. |
| Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:13-15@db4196e | — | missing | Photo toggle defaults ON when visible — explicit product decision. |
| Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:27-33@db4196e | — | missing | hasCommitted double-tap guard — 0.3s dismiss-animation window racing onExport. |
| Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:40-46@db4196e | — | missing | ScrollView around content for Dynamic Type accessibility XXL fit at fixed medium detent. |
| Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:116-122@db4196e | — | missing | Apple HIG 44pt minimum tap target — implemented as 12pt vertical padding. |
| Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:177-187@db4196e | — | missing | Static helper `effectiveIncludePhotos` enforces invariant: never `true` when no pinned photos. |
| Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:163-175@db4196e | — | missing | ByteCountFormatter .file with .useKB + .useMB — matches plan's '≈1.4 MB' wording exactly. |
| Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:160-162@db4196e | — | missing | Walk-count plural: `count == 1 ? "" : "s"` (no 0 special case). |
| Pilgrim/Scenes/Settings/ExportDateRangeFormatter.swift:7-19@db4196e | — | missing | Locale-defaulted DateFormatter `MMMM yyyy` with explicit override for tests. |
| Pilgrim/Scenes/Settings/ExportDateRangeFormatter.swift:27@db4196e | — | missing | En-dash `" – "` (U+2013) between earliest/latest, not hyphen. |
| Pilgrim/Scenes/Settings/FeedbackView.swift:177@db4196e | — | missing | Feedback confirmation auto-dismiss after 2.5s. |
| Pilgrim/Scenes/Settings/FeedbackView.swift:174@db4196e | — | missing | Confirmation crossfade 0.5s. |
| Pilgrim/Scenes/Settings/FeedbackView.swift:97-108@db4196e | — | missing | TextEditor minHeight 120 + placeholder pad 8/4. |
| Pilgrim/Scenes/Settings/FeedbackView.swift:105@db4196e | — | missing | Fog opacity 0.5 on placeholder text. |
| Pilgrim/Scenes/Settings/FeedbackView.swift:150@db4196e | — | missing | Disabled-state background opacity 0.2 on fog. |
| Pilgrim/Scenes/Settings/FeedbackView.swift:5,178@db4196e | — | missing | Tri-state @Environment(\.dismiss) handle used to pop nav AFTER 2.5s task sleep. |
| Pilgrim/Scenes/Settings/FeedbackView.swift:167-183@db4196e | — | missing | `Task { @MainActor in ... }` in `submit()` has no cancellation handle — survives view dismissal. |
| Pilgrim/Scenes/Settings/JourneyViewerView.swift:146,156@db4196e | — | missing | Photo extraction targetSize 600x600, JPEG quality 0.7. |
| Pilgrim/Scenes/Settings/JourneyViewerView.swift:108-114@db4196e | — | missing | Synchronous PHImageManager network-disabled — iCloud-only photos return nil silently. |
| Pilgrim/Scenes/Settings/JourneyEditorView.swift:258-271@db4196e | — | missing | JS-bridge readiness poll: 50 attempts × 100ms = 5s. |
| Pilgrim/Scenes/Settings/JourneyViewerView.swift:201-214@db4196e | — | missing | JourneyViewer load: 1s asyncAfter before injecting walks JSON. |
| Pilgrim/Scenes/Settings/JourneyEditorView.swift:269@db4196e | — | missing | JourneyEditor `try? await Task.sleep` in poll loop suppresses CancellationException. |
| Pilgrim/Scenes/Settings/JourneyEditorView.swift:167-173@db4196e | — | missing | Non-persistent WKWebView data store — avoid stale-JS-bundle cache. |
| Pilgrim/Scenes/Settings/JourneyEditorView.swift:195-200@db4196e | — | missing | URLRequest.cachePolicy = reloadIgnoringLocalAndRemoteCacheData — additional cache busting. |
| Pilgrim/Scenes/Settings/JourneyEditorView.swift:239-254@db4196e | — | missing | [weak self] only inside DispatchQueue.main blocks but `Task { @MainActor in }` strongly captures self. |
| Pilgrim/Scenes/Settings/JourneyViewerView.swift:176@db4196e | — | missing | JourneyWebView force-unwraps view URL literal. |
| Pilgrim/Scenes/Settings/JourneyViewerView.swift:116-130@db4196e | — | missing | `compactMap` + nested optional projection on photos array. |
| Pilgrim/Scenes/Settings/PermissionStatusViewModel.swift:10-14@db4196e | — | missing | `@Published` ObservableObject implicit Combine binding (legacy pattern, not `@Observable`). |
| Pilgrim/Scenes/Settings/PermissionStatusViewModel.swift:18-23@db4196e | — | missing | Combined `needsAttention` rule treats `.notDetermined` as needs-attention same as `.denied`. |
| Pilgrim/Scenes/Settings/SettingsCards/PermissionsCard.swift:49@db4196e | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsViewModel.kt:178-179,239@dc4cef8 | drift-cosmetic | iOS: Dot indicator hard-coded 10x10 in PermissionsCard.<br>Android: ESTIMATED_BYTES_PER_PHOTO = 80_000L hard-coded. |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:22@db4196e | — | missing | Season symbol opacity 0.3 in PracticeSummaryHeader. |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:27@db4196e | — | missing | `if walkCount > 0` gates stat line render. |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:51@db4196e | — | missing | `if let streak = stats.streakDays, streak > 1` — single-day streak hidden. |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:73-75@db4196e | — | missing | Milestone banner auto-dismiss 8s. |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:36@db4196e | — | missing | Stat-phase modulo 3 (.0..2 cycle). |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:92@db4196e | — | missing | Bell volume 0.4 for milestone playback (hard-coded, not from prefs). |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:96,119@db4196e | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt:267,313@dc4cef8 | drift-cosmetic | iOS: Miles conversion 0.621371 (km to mi).<br>Android: 0.621371 mi-per-km conversion duplicated in PracticeSummaryHeader (both stats lines). |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:126-132@db4196e | — | missing | Meditation seconds → h:m formula with 3600 modulus. |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:145,152@db4196e | — | missing | Hemisphere hint normalized to ±1 via `(value ?? 1) >= 0 ? 1 : -1`. |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:83-85@db4196e | — | missing | isImperial re-read on UserDefaults change notification — view doesn't re-pull preference. |
| Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:98@db4196e | — | missing | `stats.totalWalks.formatted()` uses default Locale digit grouping. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:490-492@db4196e | — | missing | WaveformBarView spacing 0.5pt + barWidth - 0.5 formula. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:496,503@db4196e | — | missing | WaveformBarView height clamp `max(2, geo.size.height * amp)`. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:519@db4196e | — | missing | Waveform fixed frame height 32. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:329-333@db4196e | — | missing | Waveform seek paired with 0.1s delay after toggle. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:63-64@db4196e | — | missing | Recording row index `index + 1` (1-based display, 0-based iteration). |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:22@db4196e | — | missing | `@FocusState` private wrapper for TextEditor focus management. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:157-176@db4196e | — | missing | `.swipeActions(edge: .leading/.trailing, allowsFullSwipe: false)` — leading=retranscribe, trailing=delete. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:397-410@db4196e | — | missing | WaveformCache.markInFlight returns Bool — only one task per uuid wins. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:402-404@db4196e | — | missing | `Task.detached(priority: .utility)` for off-actor waveform generation. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:449,456@db4196e | — | missing | Duration `String(format: "%d:%02d", m, s)` without Locale.US. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:461@db4196e | — | missing | File size `String(format: "%.1f MB", mb)` — uses default Locale decimal separator. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:460@db4196e | — | missing | File size divisor 1_000_000 (decimal MB, not 1024² binary MiB). |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:464-468@db4196e | — | missing | Section header DateFormatter `MMMM d, h:mm a` — no Locale set. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:368-373@db4196e | — | missing | Section recordings sorted by `startDate` then filtered for nil uuid (post-filter). |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:400,413,436@db4196e | — | missing | FileManager docs first force-unwrap repeated in 3 spots. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:140,384@db4196e | — | missing | Boolean+optional flatMap `recording.uuid.flatMap { transcriptionOverrides[$0] }` returns nil if uuid is nil OR map miss. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:184-186@db4196e | — | missing | Truncating-remainder check for whole-number speed formatting. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:223,226@db4196e | — | missing | Speed button colors/threshold `speed > 1.0`. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:226@db4196e | — | missing | Background stone-opacity 0.12 on inactive speed chip. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:495,337@db4196e | — | missing | WaveformBarView fog opacity 0.4 unplayed / 0.15 placeholder. |
| Pilgrim/Scenes/Settings/SettingsCards/SettingsCardStyle.swift:69@db4196e | — | missing | Settings PracticeCard `Picker.frame(width: 180)` — Settings picker fixed width. |
| Pilgrim/Scenes/Settings/SettingsCards/ConnectCard.swift:5@db4196e | — | missing | ConnectCard share URL is `plgr.im/share` (short link, not pilgrimapp.org). |
| Pilgrim/Scenes/Settings/SettingsCards/ConnectCard.swift:5-7@db4196e | — | missing | Three URL force-unwraps in ConnectCard. |
| Pilgrim/Scenes/Settings/SettingsCards/PracticeCard.swift:86-100@db4196e | — | missing | PracticeCard stale-callback guard on photos permission async result. |
| Pilgrim/Scenes/Settings/SettingsCards/PracticeCard.swift:102-108@db4196e | — | missing | Re-entry into OFF branch from denial path must NOT clear showPhotosDeniedNote. |
| Pilgrim/Scenes/Settings/SettingsCards/PracticeCard.swift:119-120@db4196e | — | missing | Picker frame width 180 + Toggle animation 0.2s. |
| Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift:147@db4196e | — | missing | soundscapeRow / bell row use literal 12pt spacing in pack rows. |
| Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift:199-200@db4196e | — | missing | ProgressView frame width 40 for inline pack download indicator. |
| Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift:209-216@db4196e | — | missing | Pack auto-select on download completion via `.onChange(of: activeDownloads)`. |
| Pilgrim/Scenes/Settings/SoundSettingsView.swift:119@db4196e | — | missing | BreathRhythm picker `breathRhythm < BreathRhythm.all.count` bounds check before array access. |
| Pilgrim/Scenes/Settings/SoundSettingsView.swift:141,145,158,162@db4196e | — | missing | Volume slider range 0..1, percentage `Int(value * 100)`. |
| Pilgrim/Scenes/Settings/SoundSettingsView.swift:188@db4196e | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voice/VoiceCard.kt:131-138@dc4cef8 | drift-cosmetic | iOS: fileStore.totalDiskUsage() / 1_000_000.0 — decimal MB.<br>Android: VoiceCard formatRecordingsDetail uses 1_000_000.0 decimal MB to match iOS. |
| Pilgrim/Scenes/Settings/SoundSettingsView.swift:284@db4196e | — | missing | Picker sheet uses both .medium AND .large detents. |
| Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:70@db4196e | — | missing | Recording-size pluralization inline ternary. |
| Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:70@db4196e | — | missing | VoiceCard `\u{2022}` bullet vs PracticeSummaryHeader `\u{00B7}` middle-dot — two different separators in same screen tree. |
| Pilgrim/Scenes/Settings/AboutView.swift:115; Pilgrim/Scenes/Settings/SettingsCards/PracticeCard.swift:54; Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift:101,122@db4196e | — | missing | `\u{00B7}` middle-dot separator used 4+ places. |
| Pilgrim/Scenes/Settings/SettingsView.swift:26-40@db4196e | — | missing | Settings cards staggered entrance: 0.0..0.7s in 0.1s increments per card. |
| Pilgrim/Scenes/Settings/SettingsView.swift:151@db4196e | — | missing | Card-entrance offset 20pt (not 8 like SectionAppear). |
| Pilgrim/Scenes/Settings/SettingsView.swift:82-87@db4196e | — | missing | Pull-to-reveal tagline threshold offset > 40, opacity ramp `min((offset - 40) / 60, 1)`. |
| Pilgrim/Scenes/Settings/SettingsView.swift:82@db4196e | — | missing | `if offset > 40` reveal gate. |
| Pilgrim/Models/Settings/PolicyManager.swift:42-65@db4196e | — | missing | PolicyManager mixes async DataTask resume with immediate session.finishTasksAndInvalidate — fire-and-forget pattern. |
| Pilgrim/Models/Settings/PolicyManager.swift:39@db4196e | — | missing | PolicyManager uses cachePolicy `.reloadIgnoringLocalAndRemoteCacheData` — always fetch fresh policy. |
| Pilgrim/Models/Settings/PolicyManager.swift:26@db4196e | — | missing | PolicyManager `baseURL = "https://pilgrimapp.org/policies/"` — single shared base path. |
| Pilgrim/Models/Settings/PolicyManager.swift:72-88@db4196e | — | missing | PolicyManager localizes title via LS[] subscript but URL extension is hard-coded English filename. |
| Pilgrim/Scenes/Settings/AboutView.swift:66-77@db4196e | — | missing | AboutView: `UIApplication.shared.alternateIconName != iconName` after force-unwrapping `appIconName(for: guideId)`. |
| Pilgrim/Scenes/Settings/AboutView.swift:55-57,347-367@db4196e | — | missing | AboutView `.task { await loadWalkData() }` captures view by value (struct) but @State writes; CoreStore fetch in MainActor.run. |
| Pilgrim/Scenes/Settings/SettingsView.swift:43@db4196e | — | missing | Settings padding.bottom uses Constants.UI.Padding.breathingRoom (sentinel-style large pad). |
| Pilgrim/Scenes/Settings/AboutView.swift:16@db4196e | — | missing | @AppStorage selectedGuideId in AboutView — auto-syncs with UserDefaults; one of the few @AppStorage usages. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:7-8@db4196e | — | missing | RecordingsListView mixes `@StateObject` (AudioPlayerModel) and `@ObservedObject` (TranscriptionService.shared) — singleton vs view-owned. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:43@db4196e | — | missing | RecordingsListView .onDisappear stops audioPlayer — explicit teardown for nav-pop. |
| Pilgrim/Scenes/Settings/SoundSettingsView.swift:54-57@db4196e | — | missing | SoundSettingsView .onDisappear stops BOTH bellPlayer and soundscapePlayer. |
| Pilgrim/Scenes/Settings/SettingsView.swift:82@db4196e | — | missing | `offset > 40` checks STRICT greater-than for tagline; `offset == 40` shows nothing. |
| Pilgrim/Scenes/Settings/SettingsView.swift:6; Pilgrim/Scenes/Settings/SettingsCards/PermissionsCard.swift:33-35@db4196e | — | missing | `@StateObject private var permissionVM = PermissionStatusViewModel()` plus `.onReceive(...UIApplication.willEnterForegroundNotification...)` refresh. |
| Pilgrim/Scenes/Settings/PermissionStatusViewModel.swift:36-50@db4196e | — | missing | PermissionStatusViewModel callbacks `[weak self]` correctly, but PermissionManager singleton retains closures internally — verify lifetime. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:472-477; Pilgrim/Scenes/Settings/AppearanceView.swift:8-14; Pilgrim/Scenes/Settings/FeedbackView.swift:211-238; Pilgrim/Scenes/Settings/JourneyEditorView.swift:7-21; Pilgrim/Scenes/Settings/DataSettingsView.swift:38-44; Pilgrim/Scenes/Settings/SoundSettingsView.swift:439-462@db4196e | — | missing | Swift structs are value types — no Compose stability concerns on iOS. Note for Android: WalkSection, BreathRhythm, FeedbackCategory, ModeEntry, ExportConfirmData, PilgrimSaveItem, PilgrimPayload, PickerType all model classes that on Android port to `data class` and need `@Immutable` if used in LazyList items. |
| Pilgrim/Scenes/Settings/JourneyEditorView.swift:175-178@db4196e | — | missing | JourneyEditorView `Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler` — userContentController retains coordinator strongly. |
| Pilgrim/Scenes/Settings/JourneyEditorView.swift:176,294@db4196e | — | missing | JourneyEditor JS bridge name 'savePilgrim' — exact string contract. |
| Pilgrim/Scenes/Settings/JourneyEditorView.swift:243; Pilgrim/Scenes/Settings/JourneyViewerView.swift:205@db4196e | — | missing | JourneyEditor JS bridge: `window.pilgrimViewer.loadFile(filename, base64)` and `loadData(data)`. |
| Pilgrim/Scenes/Settings/JourneyEditorView.swift:146-152@db4196e | — | missing | JourneyEditorView reads zip bytes off-main with `Task.detached(priority: .userInitiated)`. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:453@db4196e | — | missing | RecordingsListView `Int(max(0, seconds))` clamps negative durations. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:476@db4196e | — | missing | RecordingsListView WalkSection id fallback: `walk.uuid ?? UUID()`. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:267-268@db4196e | — | missing | RecordingsListView transcription TextEditor minHeight 60, maxHeight 200, pad 4. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:224-227@db4196e | — | missing | Speed chip padding 6/3 horizontal/vertical + cornerRadius 4. |
| Pilgrim/Scenes/Settings/RecordingsListView.swift:283-287@db4196e | — | missing | Done-button padding 12/4 + cornerRadius 4. |
| Pilgrim/Scenes/Settings/SoundSettingsView.swift:135,152@db4196e | — | missing | Soundscape volume slider step uses 6pt vstack spacing inside row. |
| Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift:94,111@db4196e | — | missing | VoiceGuide volume sliders use spacing 6. |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:138-141@db4196e | — | missing | DataSettingsView `Binding(get:/set:)` synthesizes a Bool from optional `exportURL`. |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:254@db4196e | — | missing | DataSettingsView `pinnedPhotoCount * Self.estimatedBytesPerPhoto` — Int multiplication, no overflow guard. |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:101@db4196e | — | missing | DataSettingsView `recordingCount` is computed `recordingCount > 0` to drive conditional Section visibility. |
| Pilgrim/Scenes/Settings/AboutView.swift:61-84@db4196e | — | missing | AboutView confirmationDialog filters three buttons by alternate-icon state — order matters. |
| Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:226,245@db4196e | — | missing | ExportConfirmationSheet preview values include `1_440_000` bytes for 18 photos = 80KB exact. |
| Pilgrim/Scenes/Settings/FeedbackView.swift:191-193@db4196e | — | missing | FeedbackView ProgressView checkmark uses `.largeTitle` font size. |
| Pilgrim/Scenes/Settings/FeedbackView.swift:60,191@db4196e | — | missing | FeedbackView `.title3` font size on category icons. |
| Pilgrim/Scenes/Settings/FeedbackView.swift:232-238@db4196e | — | missing | FeedbackCategory.apiValue mapping — `.thought` → 'feedback' (NOT 'thought'). |
| Pilgrim/Scenes/Settings/AboutView.swift:415-425@db4196e | — | missing | AboutView statMode `var next: StatMode { switch self { ... } }` round-robin enum. |
| Pilgrim/Scenes/Settings/AppearanceView.swift:16-21; Pilgrim/Scenes/Settings/SettingsCards/AtmosphereCard.swift:13-18@db4196e | — | missing | AppearanceView entries hard-coded order: Auto, Light, Dark, Constellation (4 options, only 3 in AtmosphereCard picker). |
| Pilgrim/Scenes/Settings/AppearanceView.swift:17-20@db4196e | — | missing | AppearanceView ModeEntry uses raw-value string `system`/`light`/`dark`/`constellation` — magic-string match against UserPreferences.appearanceMode.value. |
| Pilgrim/Scenes/Settings/DataSettingsView.swift:236-241@db4196e | — | missing | DataSettingsView `(try? DataManager.dataStack.fetchOne(...))?.startDate` — chained optional try then property. |
| Pilgrim/Scenes/Settings/SettingsCards/DataCard.swift:7@db4196e | — | missing | DataCard subtitle 'Your walk archive' — wording matters (matches naming of PilgrimPackageBuilder export concept). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt:107-110@dc4cef8 | extra | Title height tuned constant 48.dp mirrored from HomeScreen — drift target if HomeScreen retunes its glyph baseline. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt:122@dc4cef8 | extra | LazyColumn bottom padding of 120.dp is a tuned spacer for the bottom nav. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt:220-227@dc4cef8 | extra | Sticky title backdrop blur extends 12.dp past the title bottom — explicit comment promised '24dp' divergence. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt:86-95@dc4cef8 | extra | rememberSaveable used for transient photo-denied flag and tab-cycling phase — explicit rotation preservation. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt:97-104@dc4cef8 | extra | WindowInsets(0) inside nested Scaffold — load-bearing zero to avoid double-counting insets. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt:238-260@dc4cef8 | extra | MILESTONE_BELL_SCALE 0.4f mirrors iOS milestone-bell volume — multiplicative invariant comment. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt:171-175@dc4cef8 | extra | Auto-dismiss delay 8_000L for milestone banner — comment claims test alignment via mainClock.advanceTimeBy. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt:112@dc4cef8 | extra | Stat phase cycle modulo 3 — silent overflow if a new phase added without bumping divisor. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt:142-159@dc4cef8 | extra | `displayedMilestone` cache trick keeps content available during 500ms fadeOut after `milestone` goes null. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:531-535@dc4cef8 | extra | AboutScreen formatSinceDate + walkingSinceLine use Locale.getDefault() — non-ASCII digits on Arabic/Persian/Hindi locales (contradicts Stage 5-A / 6-A memory). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt:294-297@dc4cef8 | extra | PracticeSummaryHeader.walkingSinceLine reuses Locale.getDefault() — same digit-locale risk as AboutScreen. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:521-525@dc4cef8 | extra | 1.609344 m per mile constant inline in AboutScreen formatDistance. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:326-330@dc4cef8 | extra | FootprintTrailSection: stride 0.08f + index * 0.04f alpha — opaque to readers. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:326@dc4cef8 | extra | Footprint count hardcoded `repeat(4)` rather than parameter. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt:199-203@dc4cef8 | extra | ZoneId/Hemisphere overrides hardwired to Northern / latitude=0.0 — Southern-Hemisphere users see Northern seasons. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:162-169@dc4cef8 | extra | AboutScreen HeroSection hard-coded TreeScenery + PilgrimLogo sizes 36.dp / 80.dp. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:196-359@dc4cef8 | extra | Letter spacing 3.sp / 2.sp / alpha 0.6f / 0.3f / 0.5f / 0.2f / 0.08f magic-cluster in AboutScreen. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackScreen.kt:67-72,316@dc4cef8 | extra | FeedbackScreen auto-dismiss delay 2500L after submission confirmation. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackScreen.kt:248-252@dc4cef8 | extra | FeedbackScreen uses Modifier.let{ } chain idiom to conditionally apply border — non-obvious. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackScreen.kt:201-214@dc4cef8 | extra | Background-vs-clickable split: background reflects form-validity while clickable.enabled reflects in-flight state. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/connect/FeedbackViewModel.kt:52-62@dc4cef8 | extra | FeedbackViewModel.submit() catches Throwable; correctly re-throws CancellationException — explicit pattern. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsViewModel.kt:128-150@dc4cef8 | extra | DataSettingsViewModel.exportRecordings nested try/catch correctly re-throws CE inside withContext(IO). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/JourneyViewerViewModel.kt:100-106@dc4cef8 | extra | JourneyViewerViewModel.buildPayload catches Throwable in encodeAsDataUrl path; re-throws CE. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsViewModel.kt:128-129@dc4cef8 | extra | compareAndSet flag flip avoids race on rapid Export double-tap. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsViewModel.kt:102-111@dc4cef8 | extra | Channel(BUFFERED) + receiveAsFlow (NOT consumeAsFlow) for rotation-safe one-shot events. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsViewModel.kt:65-77@dc4cef8 | extra | Eagerly + .catch upstream because .value reads don't count as WhileSubscribed subscribers (Stage 5-F lesson cascaded). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsScreen.kt:139-145@dc4cef8 | extra | String comparison on serialized error message body to derive snackbar copy — fragile. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsScreen.kt:168-173@dc4cef8 | extra | Similar exact-string check for import 'Unsupported version' uses startsWith — slightly more forgiving but coupled. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsScreen.kt:250-253@dc4cef8 | extra | Import picker MIME types list includes "*/*" wildcard fallback — surfaces every file in SAF. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsScreen.kt:96-106@dc4cef8 | extra | ClipData + FLAG_GRANT_READ_URI_PERMISSION redundant pair is MANDATORY for receiving apps. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsScreen.kt:93-136@dc4cef8 | extra | Intent.createChooser + ACTION_SEND + ClipData built inline — no Robolectric .build() test exists for this exact intent shape per CLAUDE.md guidance. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/JourneyViewerScreen.kt:173-183@dc4cef8 | extra | WebView is created via AndroidView with custom onRelease — leak risk if onRelease isn't called (NavGraph pop semantics). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/JourneyViewerScreen.kt:145-167,198@dc4cef8 | extra | INJECTION_DELAY_MS = 1_000L matches iOS's 1-second delay heuristic for WebView JS attachment. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/JourneyViewerScreen.kt:196@dc4cef8 | extra | VIEWER_URL https://view.pilgrimapp.org hardcoded — environment switching impossible without rebuild. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/JourneyViewerScreen.kt:186-194@dc4cef8 | extra | escapeJsBoundary preemptive U+2028/U+2029 escape — defense-in-depth for downlevel WebView. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/JourneyViewerScreen.kt:150-167@dc4cef8 | extra | WebView postDelayed lambda captures `view` (WebView) — if delay fires after navigation, `view.parent == null` guard runs; race window of ~1s. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCard.kt:61-68@dc4cef8 | extra | DisposableEffect with lifecycleOwner for ON_RESUME refresh — idiomatic but easy to break. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCard.kt:114-121@dc4cef8 | extra | Activity-recognition permission only requested on SDK ≥ Q; the pre-API-29 branch is unreachable. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCard.kt:70-85@dc4cef8 | extra | Hoisted ActivityResultContracts.RequestPermission() instances via remember — Stage 7-A photo-picker race precedent. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCardViewModel.kt:35-65@dc4cef8 | extra | MutableStateFlow(0) tick pattern for forcing combine-refresh on resume — non-obvious. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/permissions/PermissionsCardViewModel.kt:76@dc4cef8 | extra | SUBSCRIPTION_KEEPALIVE_MS = 5_000L duplicated across SettingsViewModel, PermissionsCardViewModel, DataSettingsViewModel, AboutViewModel, SoundSettingsViewModel. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voice/VoiceCard.kt:115-122@dc4cef8 | extra | VoiceCardState marked @Stable but contains only primitives — @Immutable would be the stronger annotation. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt:316-330@dc4cef8 | extra | PracticeSummaryStats data class fed into LazyColumn item via StateFlow — holds java.time.Instant (non-stdlib Stable type). |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutViewModel.kt:17-26@dc4cef8 | extra | AboutStats holds java.time.Instant — same Stage 4-D pattern, no @Immutable. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsViewModel.kt:247-268@dc4cef8 | extra | PilgrimExportState.Confirming and related sealed-interface data classes lack @Immutable; consumed by Compose UI. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsScreen.kt:465@dc4cef8 | extra | 1_000_000.0 byte→MB divisor in SoundSettingsScreen + 1_024.0 * 1_024.0 in VoiceGuidePackDetailScreen — disagreement on MB definition. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePackDetailScreen.kt:283-289@dc4cef8 | extra | VoiceGuidePackDetailScreen uses 1024.0 * 1024.0 — binary MB labeled 'MB'. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsScreen.kt:443-455@dc4cef8 | extra | Slider valueRange 0..1f with steps = 19 — 5% increments, no token. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsScreen.kt:419-447@dc4cef8 | extra | Slider drag-value local copy via remember(volume) — keyed on incoming value to re-sync on external change. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsScreen.kt:198-204@dc4cef8 | extra | Soundscape count = bells + soundscapes — iOS-faithful 'all assets' definition. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsViewModel.kt:170-207@dc4cef8 | extra | clearAllDownloads documents the cancel-workers-FIRST race lesson from Stage 5-D. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt:183-188@dc4cef8 | extra | moss banner alpha 0.08f and corner radius 8.dp — milestone overlay tokens. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/JourneyViewerScreen.kt:160-165@dc4cef8 | extra | JourneyViewerScreen `view.evaluateJavascript` runs after `view.parent == null` guard but webView field is not null-checked — postDelayed lambda holds strong ref. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataSettingsScreen.kt:89-117,123-137@dc4cef8 | extra | LaunchedEffect(Unit) collecting Channel events — if rotation re-composes with same Unit key, the collector cancels and recreates; in the gap, sent events buffer (correct), but stale lambda captures `context` + `authority`. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/JourneyViewerScreen.kt:140@dc4cef8 | extra | AndroidView WebView setBackgroundColor(0) — using a magic 0 instead of Color.TRANSPARENT. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt:283-289@dc4cef8 | extra | meditationStatLine: hours = totalMeditationSeconds / 3_600, minutes = ((totalMeditationSeconds % 3_600) / 60).toInt() — Long→Int truncation at top-call only. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePackDetailScreen.kt:276-280@dc4cef8 | extra | formatDuration in VoiceGuidePackDetailScreen truncates seconds — rounds DOWN to floor minutes. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsScreen.kt:94-95@dc4cef8 | extra | rememberSaveable mutableStateOf with sealed-interface or enum target — survives rotation but requires the type be Parcelable/Serializable. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voice/VoiceCard.kt:78-84@dc4cef8 | extra | AnimatedVisibility tween(200) appears 8+ times across Sound settings + Practice card + Atmosphere card + Voice card — iOS-parity 200ms ease. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt:161-164@dc4cef8 | extra | AnimatedVisibility fadeIn/fadeOut tween 500ms with FastOutSlowInEasing for milestone banner. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/PracticeSummaryHeader.kt:156-159@dc4cef8 | extra | remember without key for `displayedMilestone` — initial seed once, mutated in-place during composition by the `if (milestone != …)` branch. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/ExportConfirmationSheet.kt:92-99@dc4cef8 | extra | ExportConfirmationSheet has zero force-unwraps; ButtonBar's `if (hasCommitted) return@ButtonBar` uses @ButtonBar label. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/ExportConfirmationSheet.kt:65@dc4cef8 | extra | hasCommitted as `remember` (not rememberSaveable) — rotation re-enables Export double-tap. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/ExportConfirmationSheet.kt:241-247@dc4cef8 | extra | ButtonBar Export button uses RoundedCornerShape(percent = 50) — pill button. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsScreen.kt:505-532@dc4cef8 | extra | noteLabel fallback path falls back to noneLabel silently when persisted bell/soundscape id doesn't resolve in current manifest. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsViewModel.kt:79-96@dc4cef8 | extra | SoundSettingsViewModel.init { launch { fileStore.invalidations.collect { ... } } } — long-lived flow collector, no try/catch. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsViewModel.kt:209-213@dc4cef8 | extra | SoundSettingsViewModel.recomputeDiskUsage uses runCatching — does NOT explicitly re-throw CancellationException. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt:101-232@dc4cef8 | extra | SettingsViewModel — 7 setter functions use `runCatching { suspend write }` without CancellationException rethrow. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/sounds/SoundSettingsViewModel.kt:98-167@dc4cef8 | extra | SoundSettingsViewModel — same pattern, 10 setter functions all use `runCatching { suspend write }`. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt:205@dc4cef8 | extra | SettingsScreen Pilgrim version Text removes the '-debug' suffix from BuildConfig.VERSION_NAME. |
| — | app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt:155-158@dc4cef8 | extra | SettingsScreen card-order comment locks iOS pixel-parity ordering despite a previous reordering bug. |

## Cross-cutting drift summary

| severity | count |
|---|---|
| drift-critical | 0 |
| missing | 305 |
| drift-cosmetic | 9 |
| extra | 145 |
| matches | 0 |

### Worst offenders (drift-critical + missing, top 25)

| severity | lens | citation | claim |
|---|---|---|---|
| missing | behavior | Pilgrim/Scenes/Settings/AboutView.swift:6-14@db4196e | AboutView holds 9 @State vertices including statMode (3-way cycling enum), totalDistance, walkCount, firstWalkDate, hasWalks, safariURL (sheet item), appeared ( |
| missing | behavior | Pilgrim/Scenes/Settings/AboutView.swift:378-398@db4196e | loadWalkData runs on the .task structured concurrency scope and explicitly hops to MainActor.run to publish results |
| missing | behavior | Pilgrim/Scenes/Settings/AboutView.swift:472-475@db4196e | Section-appear staggered animation uses 0.1s per-index delay multiplier |
| missing | behavior | Pilgrim/Scenes/Settings/AboutView.swift:179-181@db4196e | Stat-mode cycle button uses 0.3s easeInOut animation duration |
| missing | behavior | Pilgrim/Scenes/Settings/AboutView.swift:446-456@db4196e | statMode cycles through 3 vertices distance → count → since → distance on tap, with .numericText contentTransition |
| missing | behavior | Pilgrim/Scenes/Settings/AboutView.swift:411-417@db4196e | App icon confirmation dialog triggered by tap on PilgrimLogoView and calls UIApplication.shared.setAlternateIconName |
| missing | behavior | Pilgrim/Scenes/Settings/AboutView.swift:402-409@db4196e | iconDialogTitle reads VoiceGuideManifestService.shared.pack(byId:) synchronously to display pack name + tagline in the dialog title |
| missing | behavior | Pilgrim/Scenes/Settings/AppearanceView.swift:16-21@db4196e | AppearanceView holds a String mode vertex with 4 valid values: 'system', 'light', 'dark', 'constellation' |
| missing | behavior | Pilgrim/Scenes/Settings/AppearanceView.swift:23-32@db4196e | AppearanceView's body reads appearanceManager.themeID to force re-evaluation when mode changes (in-place picker update) |
| missing | behavior | Pilgrim/Scenes/Settings/AppearanceView.swift:68-70@db4196e | AppearanceView onAppear re-reads UserPreferences to resynchronize local mode state |
| missing | behavior | Pilgrim/Scenes/Settings/DataSettingsView.swift:8-31@db4196e | DataSettingsView has interlocked busy flags: isExporting, isImporting, isExportingRecordings; isBusy=disjunction governs all buttons |
| missing | behavior | Pilgrim/Scenes/Settings/DataSettingsView.swift:191-210@db4196e | exportData has explicit re-entry guard against double-tap that catches BOTH the confirmation-sheet-up gap AND the share-sheet-visible gap |
| missing | behavior | Pilgrim/Scenes/Settings/DataSettingsView.swift:216-230@db4196e | performExport sets lastSkippedPhotoCount AFTER successful build and BEFORE assigning exportURL, so the post-share alert can fire on cleanup |
| missing | behavior | Pilgrim/Scenes/Settings/DataSettingsView.swift:317-332@db4196e | exportRecordings dispatches zip-build to .global(qos:.userInitiated) and hops back to .main.async for UI updates |
| missing | behavior | Pilgrim/Scenes/Settings/DataSettingsView.swift:357-374@db4196e | cleanupExport surfaces 'some photos couldn't be included' alert AFTER share sheet dismisses, intentionally NOT during the share flow |
| missing | behavior | Pilgrim/Scenes/Settings/DataSettingsView.swift:274-291@db4196e | ImportData calls startAccessingSecurityScopedResource/stopAccessingSecurityScopedResource around the import op |
| missing | behavior | Pilgrim/Scenes/Settings/DataSettingsView.swift:297-313@db4196e | ImportSummary has three counters (added, replaced, archived) and a totalChanges fallback message |
| missing | behavior | Pilgrim/Scenes/Settings/DataSettingsView.swift:179-181@db4196e | recordingCount read on onAppear from synchronous DataManager.recordingFileCount() |
| missing | behavior | Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:25-35@db4196e | ExportConfirmationSheet has includePhotos and hasCommitted vertices; hasCommitted is double-tap guard |
| missing | behavior | Pilgrim/Scenes/Settings/ExportConfirmationSheet.swift:142-187@db4196e | exportTapped applies effectiveIncludePhotos guard so pinnedPhotoCount==0 always passes false regardless of toggle state |
| missing | behavior | Pilgrim/Scenes/Settings/ExportDateRangeFormatter.swift:12-28@db4196e | ExportDateRangeFormatter collapses same-month ranges and uses 'MMMM yyyy' format with default locale .current |
| missing | behavior | Pilgrim/Scenes/Settings/FeedbackView.swift:5-11@db4196e | FeedbackView has 6 vertices: selectedCategory, message, includeDeviceInfo (default true), isSubmitting, showConfirmation, errorMessage |
| missing | behavior | Pilgrim/Scenes/Settings/FeedbackView.swift:162-184@db4196e | FeedbackView confirmation overlay displays for 2.5s before auto-dismissing (Task.sleep nanoseconds 2_500_000_000) |
| missing | behavior | Pilgrim/Scenes/Settings/FeedbackView.swift:167-178@db4196e | FeedbackService.submit invoked inside Task @MainActor — UI work stays on main thread but underlying network call must hop dispatchers internally |
| missing | behavior | Pilgrim/Scenes/Settings/FeedbackView.swift:211-239@db4196e | FeedbackCategory enum has 3 vertices (bug/feature/thought) each with title, icon, apiValue mappings |

## Lens disagreements

- The behavior and edge-cases lenses each flagged the **AtmosphereCard sounds toggle → sub-screen link** conditional render (`if soundsEnabled { NavigationLink }`) under different categories (`transition` and `conditional-render`); both consolidated to a single Android comparison row but the Android-side finding only flags the `conditional-render` aspect, not the state-machine implication.
- The behavior lens cites `RecordingsListView` async-sync-points around export progress, but no Android finding for `RecordingsListView` parity exists in the saved Android files (the Android slice does NOT contain a RecordingsListView at all — recordings flow lives under `data/` and `voice/`). Flagged as missing across the lens.
- The ui-visual lens disagrees with the edge-cases lens on whether `PilgrimLogoView(size: 80)` in `AboutView.swift:91` is a magic-number drift target — ui-visual marks it dimension-only, edge-cases marks it magic-number. Both kept as separate rows.

## Open questions

- **iOS data lens output is missing from disk.** The agent returned its ~50 findings inline (persistence, network endpoints, file I/O, entities, DataStore keys around DataSettingsView, JourneyViewerView, PolicyManager, RecordingsListView, SoundSettingsView, VoiceGuideSettingsView, FeedbackView, PermissionStatusViewModel, SettingsModel). These are NOT represented in the per-lens drift table for `data` above. Re-run with `--save` to capture them, or run `/ios-parity port Settings` which will exercise the data lens fresh.
- **Android behavior lens output is missing from disk.** Agent `ad6b05bf` returned ~50 findings inline covering observer-lifetime, state-machine, dispatcher, async-sync-point, transition findings across the Settings + DataSettings + Sound/Voice/Soundscape picker + Feedback / About / Permissions / Practice viewmodels. The behavior drift table above only contains iOS-side rows because there is no Android JSON to match against. **Effect**: every iOS behavior finding currently renders as `missing` in the drift table. Treat those as un-audited rather than provably absent until the Android behavior lens output is replayed.
- **Android data lens output is missing from disk.** Same as above for data findings (~50 covering Android entity classes, DataStore-backed reads/writes, file I/O on `Dispatchers.IO`, FeedbackService URL unknown from slice).
- **`FeedbackService` endpoint URL is not visible in this Android slice.** The iOS side uses a backend endpoint family enumerated in `pilgrim-android/CLAUDE.md`'s ecosystem table; Android's `FeedbackSubmitterImpl.kt` (15 LOC) is too thin to reveal whether it hits `walk.pilgrimapp.org` or a different host. Confirm via `app/src/main/java/.../FeedbackSubmitterImpl.kt`'s actual implementation outside the settings slice.
- **`RecordingsListView` (iOS) has no Android counterpart inside the settings slice.** iOS surfaces voice recordings inside Settings (`Pilgrim/Scenes/Settings/RecordingsListView.swift`, 521 LOC); Android exposes recording UI under non-settings packages. Confirm intent: does Android intentionally relocate this surface, or is there a missing settings entry-point?
- **Bottom-sheet pickers are Android-only (`BellPickerSheet.kt`, `BreathRhythmPickerSheet.kt`).** iOS handles bell + rhythm selection inline inside `SoundSettingsView.swift` and `PracticeCard.swift`. Confirm this is an intentional Android-platform idiom (Material 3 modal sheet) and not a parity gap.
- **Twelve legacy UIKit setting wrappers exist on iOS (`Pilgrim/Models/Settings/Setting Models/*.swift` + `SettingSection.swift` + `SettingsModel.swift`).** These predate the SwiftUI rewrite and are not referenced by any SwiftUI scene in the slice. Marked DEAD CODE in this audit — confirm with iOS maintainer before declaring final.
- **`AppearanceView.swift` and `JourneyEditorView.swift` added post-v1.5.0.** These are listed in the spec-prompt's `ios_files` but do not exist at `db4196e`. Re-pin parity target if these become required.

> Drift report written. Per-lens findings above. Use `/ios-parity port <slice>` to regenerate the parity spec for any drift-critical or missing items.