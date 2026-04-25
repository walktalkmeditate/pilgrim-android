# Stage 7-B Implementation Plan

**Spec:** `docs/superpowers/specs/2026-04-24-stage-7b-photo-analysis-tombstone-design.md`
**Branch:** `feat/stage-7b-photo-analysis-tombstone`
**Estimate:** ~13 files + migration + ~9 tests. Mid-sized.

## Tasks (bite-sized, strictly ordered)

### Task 1 — Dependencies

- `gradle/libs.versions.toml`: add `mlkitImageLabeling = "17.0.9"`, `coroutinesPlayServices = "1.10.2"`; libraries `mlkit-image-labeling`, `kotlinx-coroutines-play-services`.
- `app/build.gradle.kts`: `implementation(libs.mlkit.image.labeling)` + `implementation(libs.kotlinx.coroutines.play.services)`.
- Verify: `./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -E 'mlkit|play-services'`.
- Commit: `chore(deps): add ML Kit Image Labeling + coroutines-play-services for Stage 7-B`.

### Task 2 — `WalkPhoto` entity additions

- `data/entity/WalkPhoto.kt`: add `topLabel: String? = null`, `topLabelConfidence: Double? = null`, `analyzedAt: Long? = null` with matching `@ColumnInfo` names.
- Existing init invariants unchanged (new fields are nullable).
- Compile-only check; no behavior change.
- Commit: `feat(data): WalkPhoto analysis fields (Stage 7-B)`.

### Task 3 — DB v3→v4 migration + DAO

- `data/PilgrimDatabase.kt`: bump `version = 3` → `4`; add `MIGRATION_3_4` next to `MIGRATION_2_3`:
  ```kotlin
  val MIGRATION_3_4: Migration = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE `walk_photos` ADD COLUMN `top_label` TEXT")
          db.execSQL("ALTER TABLE `walk_photos` ADD COLUMN `top_label_confidence` REAL")
          db.execSQL("ALTER TABLE `walk_photos` ADD COLUMN `analyzed_at` INTEGER")
      }
  }
  ```
- `di/DatabaseModule.kt`: `.addMigrations(PilgrimDatabase.MIGRATION_2_3, PilgrimDatabase.MIGRATION_3_4)`.
- `data/dao/WalkPhotoDao.kt`: add `updateAnalysis(id, label, confidence, analyzedAt)` `@Query` and `getPendingAnalysisForWalk(walkId)` `@Query`.
- Regenerate `app/schemas/.../4.json` via build; commit.
- Commit: `feat(data): DB v4 with analysis columns + WalkPhotoDao update/pending queries`.

### Task 4 — DAO + migration tests

- `app/src/test/java/.../data/dao/WalkPhotoDaoTest.kt`: add cases for `updateAnalysis` round-trip + `getPendingAnalysisForWalk` filtering (only `analyzed_at IS NULL`, order by `pinned_at ASC, id ASC`).
- `app/src/test/java/.../data/PilgrimDatabaseMigrationTest.kt`: add `migration 3 to 4 adds analysis columns` via `PRAGMA table_info` (3 new columns, all nullable, correct types).
- Run: `./gradlew :app:testDebugUnitTest --tests "*WalkPhotoDaoTest" --tests "*PilgrimDatabaseMigrationTest"`.
- Commit: `test(data): 7-B migration + DAO analysis coverage`.

### Task 5 — `WalkRepository` additions

- `data/WalkRepository.kt`:
  ```kotlin
  suspend fun updatePhotoAnalysis(photoId: Long, label: String?, confidence: Double?, analyzedAt: Long) =
      walkPhotoDao.updateAnalysis(photoId, label, confidence, analyzedAt)

  suspend fun pendingAnalysisPhotosFor(walkId: Long): List<WalkPhoto> =
      walkPhotoDao.getPendingAnalysisForWalk(walkId)
  ```
- `app/src/test/java/.../data/WalkPhotoDataLayerTest.kt`: add repository coverage (update round-trip, pending-filter).
- Commit: `feat(data): WalkRepository analysis update + pending query`.

### Task 6 — `PhotoLabeler` interface + `MlKitPhotoLabeler`

- `data/photo/PhotoLabeler.kt`:
  ```kotlin
  data class LabeledResult(val text: String, val confidence: Double)
  interface PhotoLabeler { suspend fun label(bitmap: Bitmap): List<LabeledResult> }
  ```
- `data/photo/MlKitPhotoLabeler.kt`: `@Singleton` wrapping `ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())`; `Mutex.withLock { labeler.process(image).await() }`; maps to `LabeledResult` sorted desc by confidence.
- Imports `kotlinx.coroutines.tasks.await`.
- No test here — covered by `MlKitPhotoLabelerSmokeTest` in Task 10.
- Commit: `feat(photo): PhotoLabeler + MlKitPhotoLabeler wrapper`.

### Task 7 — `BitmapLoader`

- `data/photo/BitmapLoader.kt`: `@Singleton class BitmapLoader @Inject constructor(@ApplicationContext context: Context)`.
  - `suspend fun load(uri: Uri): Bitmap?` on `Dispatchers.IO`.
  - Two-pass: `BitmapFactory.Options(inJustDecodeBounds = true)` to get `outWidth`/`outHeight`; compute `inSampleSize = max(outWidth, outHeight) / MAX_EDGE_PX` rounded up to next power of 2, clamped to >= 1; decode for real with `inSampleSize` + `ARGB_8888`.
  - Both passes use `context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }`.
  - Wrap in `runCatching { }.getOrNull()` so FileNotFoundException / SecurityException / OOM → null.
  - `MAX_EDGE_PX = 1024`.
- `app/src/test/java/.../data/photo/BitmapLoaderTest.kt`: Robolectric test — use `ShadowContentResolver.registerInputStream(uri, bytes)` with a real PNG byte stream, assert bitmap width/height ≤ 1024; also null-path (unregistered URI).
- Commit: `feat(photo): BitmapLoader with two-pass decode + downsample`.

### Task 8 — `PhotoAnalysisRunner`

- `data/photo/PhotoAnalysisRunner.kt`: `@Singleton class` injecting `WalkRepository`, `PhotoLabeler`, `BitmapLoader`, `clock: () -> Long`.
- `suspend fun analyzePending(walkId: Long): Result<Int>`:
  - Read `repository.pendingAnalysisPhotosFor(walkId)` via try/catch → Result.failure on read errors.
  - Per photo: load bitmap via `bitmapLoader.load(Uri.parse(photo.photoUri))`.
  - If bitmap == null: `updatePhotoAnalysis(id, null, null, clock())`, continue.
  - Else: `runCatching { labeler.label(bitmap).firstOrNull() }`, handling CE re-throw. Finally `bitmap.recycle()`.
  - Write `updatePhotoAnalysis(id, top?.text, top?.confidence, clock())`.
  - Per-photo DB write errors logged and counted toward failure; batch continues.
  - Return `Result.success(count)`.
- `app/src/test/java/.../data/photo/PhotoAnalysisRunnerTest.kt`: In-memory Room + `FakePhotoLabeler` + `FakeBitmapLoader`.
  - Happy path: 2 pending photos both get labels written.
  - Null-bitmap path: `analyzedAt` set, `label` null.
  - Labeler throws: `analyzedAt` set, `label` null.
  - Empty pending: `Result.success(0)`.
  - CancellationException re-thrown.
- Commit: `feat(photo): PhotoAnalysisRunner batch orchestrator + tests`.

### Task 9 — `PhotoAnalysisScheduler` + `PhotoAnalysisWorker`

- `data/photo/PhotoAnalysisScheduler.kt`: interface `PhotoAnalysisScheduler { fun scheduleForWalk(walkId: Long) }`.
- `data/photo/WorkManagerPhotoAnalysisScheduler.kt`: `@Singleton` implementation using `OneTimeWorkRequestBuilder<PhotoAnalysisWorker>()` with `setRequiresStorageNotLow(true)`, NO `setExpedited`, `ExistingWorkPolicy.KEEP`, unique name `"photo-analysis-walk-$walkId"`.
- `data/photo/PhotoAnalysisWorker.kt`: `@HiltWorker CoroutineWorker` delegating to `PhotoAnalysisRunner.analyzePending(walkId)`; `Result.success`/`Result.retry` fold.
- `di/PhotoAnalysisModule.kt`: `@Binds @Singleton` for scheduler + labeler.
- Commit: `feat(photo): PhotoAnalysisScheduler + Worker + Hilt module`.

### Task 10 — Scheduler + labeler builder tests (MANDATORY platform-object path)

- `app/src/test/java/.../data/photo/WorkManagerPhotoAnalysisSchedulerTest.kt`: Robolectric `WorkManagerTestInitHelper`, calls `WorkManagerPhotoAnalysisScheduler(context).scheduleForWalk(42L)`, asserts one WorkInfo for `"photo-analysis-walk-42"`. Mirrors `WorkManagerTranscriptionSchedulerTest`.
- `app/src/test/java/.../data/photo/MlKitPhotoLabelerSmokeTest.kt`: Robolectric, create a 100×100 ARGB_8888 `Bitmap`, construct `MlKitPhotoLabeler`, call `label(bitmap)`, assert no exception and return type is `List<LabeledResult>`. ML Kit likely returns empty list on synthetic bitmap; OK.
- `FakePhotoAnalysisScheduler` test fake under `app/src/test/java/.../data/photo/`: records `scheduleForWalkCalls: MutableList<Long>`.
- Commit: `test(photo): Scheduler build-path + labeler smoke + fake scheduler`.

### Task 11 — VM integration

- `ui/walk/WalkSummaryViewModel.kt`:
  - Inject `PhotoAnalysisScheduler`.
  - In `pinPhotos(...)`, after `result.droppedOrphanUris.forEach { release }`, add: `if (result.insertedIds.isNotEmpty()) scheduler.scheduleForWalk(walkId)`.
  - In `runStartupSweep()`, after the sweeper call: `viewModelScope.launch(Dispatchers.IO) { scheduler.scheduleForWalk(walkId) }` — unconditional (KEEP dedups; runner filters pending).
- `app/src/test/java/.../ui/walk/WalkSummaryViewModelTest.kt`: update `newViewModel` factory to pass `FakePhotoAnalysisScheduler`; add tests:
  - `pinPhotos succeeds and scheduler.scheduleForWalk is called with walkId`.
  - `pinPhotos with empty pick does NOT call scheduler`.
  - `runStartupSweep calls scheduler.scheduleForWalk`.
- Commit: `feat(walk): schedule photo analysis after pin and on startup sweep`.

### Task 12 — Tombstone UI

- `ui/walk/reliquary/PhotoReliquarySection.kt`:
  - `PhotoTile` adds `var loadFailed by remember(photo.id) { mutableStateOf(false) }`.
  - `SubcomposeAsyncImage(..., onState = { state -> loadFailed = state is AsyncImagePainter.State.Error })`.
  - `contentDescription` now dynamic: tombstone variant when `loadFailed`.
  - Replace `ReliquaryErrorTile` with `ReliquaryTombstone` (Column of icon + "Photo unavailable" text, centered, `pilgrimColors.fog`, `pilgrimType.caption`).
  - Import: `coil3.compose.AsyncImagePainter`.
- `app/src/test/java/.../ui/walk/reliquary/PhotoReliquarySectionTest.kt`: new case `tombstone tile renders Photo unavailable on Coil error` — feed an unreadable URI (e.g. `"content://fake/none"`), assert `onNodeWithText("Photo unavailable").assertIsDisplayed()` + content description swap.
- Commit: `feat(reliquary): broken-pin tombstone with label + a11y hint`.

### Task 13 — Build gate

- `./gradlew :app:compileDebugKotlin`
- `./gradlew :app:assembleDebug`
- `./gradlew :app:lintDebug`
- `./gradlew :app:testDebugUnitTest`
All pass. No commit — gate.

## Post-plan: polish + review cycles

1. `/polish` — up to 4 passes until clean.
2. Initial review → fix → re-polish.
3. Final review × up to 3 cycles, until "Diamond".

## Spec coverage check

| Spec § | Task |
|---|---|
| Data layer entity | T2 |
| Migration 3→4 | T3 |
| DAO queries | T3 |
| Repository methods | T5 |
| `PhotoLabeler` + `MlKitPhotoLabeler` | T6 |
| `BitmapLoader` | T7 |
| `PhotoAnalysisRunner` | T8 |
| Scheduler + Worker + Hilt module | T9 |
| VM integration (pin + startup) | T11 |
| Tombstone UI | T12 |
| Tests — DAO + migration | T4 |
| Tests — runner | T8 |
| Tests — scheduler (build path) | T10 |
| Tests — labeler smoke | T10 |
| Tests — VM scheduler wiring | T11 |
| Tests — UI tombstone | T12 |
| Build gate | T13 |

## Type consistency

- `Double` everywhere for confidence (Room REAL). ML Kit returns `Float` from `ImageLabel.getConfidence()` — cast to Double at the `MlKitPhotoLabeler` boundary.
- `Long` for `analyzed_at` (epoch ms).
- `String?` for `top_label` — null when labeler returns empty or errors.
- `Uri` at UI layer, `String` at persistence layer — same convention as Stage 7-A.
