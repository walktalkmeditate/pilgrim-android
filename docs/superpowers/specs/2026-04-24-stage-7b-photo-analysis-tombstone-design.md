# Stage 7-B: Photo Analysis + Broken-Pin Tombstone — Design

**Date:** 2026-04-24
**Phase:** Port Phase 7 (Photo reliquary + etegami) — sub-stage B
**Ships:** (1) on-device ML Kit image labels persisted on `walk_photos` via Room v3→v4, (2) a broken-pin tombstone UX that upgrades the bare broken-image icon into a legible "Photo unavailable — long press to remove" tile.

---

## Intent

Stage 7-A shipped the picker + grid. Stage 7-B adds the analysis backbone (so 7-C's etegami — or a future AI-prompt surface — has labels to consume) plus a small UX polish that makes dead pins legible rather than just iconic.

**In scope:**
- Per-walk batch analysis via WorkManager. Matches Stage 2-D's `TranscriptionScheduler` pattern exactly — per-walk enqueue, `KEEP` policy, runner reads pending rows from Room and writes back top label + confidence + `analyzed_at`.
- Three new nullable columns on `walk_photos`: `top_label`, `top_label_confidence`, `analyzed_at`. Explicit v3→v4 migration.
- ML Kit bundled `image-labeling:17.0.9` (~5.7 MB APK bump) wrapped behind a thin `PhotoLabeler` interface so tests can fake it.
- Tombstone UI: Coil's error slot renders a full tile (muted background + broken-image icon + "Photo unavailable" caption) with a TalkBack-friendly contentDescription update.

**Out of scope (deferred):**
- Saliency, OCR, people/animal detection — iOS's `PhotoContextAnalyzer` produces 7 fields; Android 7-B stores only the top label. Etegami (7-C) is the earliest consumer; it doesn't need the full iOS PhotoContext yet.
- Multi-label UI (chips on tiles). No UI surface for labels in 7-B at all — strictly backend storage.
- Aesthetic scoring (no ML Kit equivalent).
- Walk-deletion grant cleanup, Snackbar for clipped batches, `rememberSaveable` for the unpin dialog — still deferred.

## Why this shape (intuition first)

The iOS review surfaced a load-bearing fact: **iOS's analysis output is NOT consumed by the etegami renderer.** `PhotoContext` feeds `PromptAssembler` for AI-prompt generation — a surface Android hasn't ported yet. Etegami will render a route + seal + intention text; it doesn't read photo labels. So the URGENT 7-B deliverable is the **analysis backbone**, not parity with all 7 iOS Vision requests. Labels are the minimum useful output — they're also the one ML Kit provides out of the box with zero accuracy caveats. Saliency, aesthetic, OCR, people/animal detection each require a separate ML Kit module (or MediaPipe, or nothing) and each one compounds failure modes without a downstream consumer to justify them. **Ship the pipeline with a minimal payload and grow the payload in 7-B.1 when 7-C tells us what it actually needs.**

Per-walk scheduler (not per-photo) matches the codebase's existing rhythm: Stage 2-D's transcription does per-walk batch from the runner, not per-recording enqueue. It makes WorkManager idempotent trivially (`KEEP` + unique name per walk), makes re-schedule on cold-start simple (one call, runner filters pending), and lets the `ImageLabeler` stay alive across all photos in a walk rather than spinning up 20 workers that each initialize JNI.

The tombstone is small on purpose. A bare broken-image icon leaves the user wondering whether it's a loading state or a failure; adding "Photo unavailable" and aligning the contentDescription with the real affordance (long-press to remove) resolves the ambiguity for both sighted and TalkBack users without inventing a whole preview/management sheet (which belongs to 7-C).

## Considered and rejected

- **Per-photo enqueue** (match VoiceGuide/Soundscape download patterns). Rejected: per-photo gives no batching benefit, costs one WorkManager row per pin, and makes `ImageLabeler` lifecycle annoying (construct per-worker = JNI re-init 20× per batch).
- **Ship saliency + OCR + people detection alongside labels.** Rejected: no downstream consumer in 7-B or 7-C; adds APK bloat (Subject Segmentation is Play Services ModuleInstallClient = install-on-demand flow, which is its own feature). Defer to 7-B.1 when 7-C specifies need.
- **Custom TFLite model.** Rejected: MVP is the bundled 447-class labeler. The accuracy caveats around "cathedral" vs "building" are fine when the only consumer is a future AI prompt.
- **Cache analysis in a side store (DataStore/UserDefaults-equivalent).** iOS caches in UserDefaults by `localIdentifier`. Rejected for Android: URI-grant lifecycle (Stage 7-A lesson) makes URI-keyed side caches fragile. Persist directly on `WalkPhoto` in Room; survives cap-clip, process death, cold starts.
- **Tap (single) on tombstone opens unpin dialog.** Rejected for 7-B: long-press for the whole grid is the consistent gesture. The tombstone's "Photo unavailable" text carries the affordance hint; same gesture as any other tile. Introducing tap-vs-long-press divergence between live and dead tiles would be surprising.
- **Dismiss-only tombstone** (keep the row, just show "unavailable" forever). Rejected: the user can't pin it again if they recover the photo; unpinning the row removes the grant and clears the cap slot. Long-press-to-remove is the right call.

---

## Architecture

### Data layer

**`WalkPhoto` entity** (additions):
```kotlin
@Immutable
data class WalkPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "walk_id") val walkId: Long,
    @ColumnInfo(name = "photo_uri") val photoUri: String,
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long,
    @ColumnInfo(name = "taken_at") val takenAt: Long? = null,
    // Stage 7-B additions
    @ColumnInfo(name = "top_label") val topLabel: String? = null,
    @ColumnInfo(name = "top_label_confidence") val topLabelConfidence: Double? = null,
    @ColumnInfo(name = "analyzed_at") val analyzedAt: Long? = null,
) { /* existing init invariants; new fields nullable */ }
```

Compose-stability: nullable primitive/`String?` fields keep `WalkPhoto` stable (already `@Immutable`).

**Room migration 3→4** — explicit `ALTER TABLE`:
```kotlin
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `walk_photos` ADD COLUMN `top_label` TEXT")
        db.execSQL("ALTER TABLE `walk_photos` ADD COLUMN `top_label_confidence` REAL")
        db.execSQL("ALTER TABLE `walk_photos` ADD COLUMN `analyzed_at` INTEGER")
    }
}
```

Registered via `.addMigrations(MIGRATION_2_3, MIGRATION_3_4)` in `DatabaseModule`. `exportSchema = true` regenerates `4.json`.

**`WalkPhotoDao` additions:**
```kotlin
@Query("""
    UPDATE walk_photos
    SET top_label = :label,
        top_label_confidence = :confidence,
        analyzed_at = :analyzedAt
    WHERE id = :id
""")
suspend fun updateAnalysis(id: Long, label: String?, confidence: Double?, analyzedAt: Long)

@Query("""
    SELECT * FROM walk_photos
    WHERE walk_id = :walkId AND analyzed_at IS NULL
    ORDER BY pinned_at ASC, id ASC
""")
suspend fun getPendingAnalysisForWalk(walkId: Long): List<WalkPhoto>
```

**`WalkRepository` additions:**
```kotlin
suspend fun updatePhotoAnalysis(
    photoId: Long,
    label: String?,
    confidence: Double?,
    analyzedAt: Long,
) = walkPhotoDao.updateAnalysis(photoId, label, confidence, analyzedAt)

suspend fun pendingAnalysisPhotosFor(walkId: Long): List<WalkPhoto> =
    walkPhotoDao.getPendingAnalysisForWalk(walkId)
```

Per-id `@Query` update (not `@Update` on the whole row) to avoid clobbering concurrent pin writes.

### Analysis pipeline

**`PhotoLabeler` interface** (`data/photo/PhotoLabeler.kt`):
```kotlin
data class LabeledResult(val text: String, val confidence: Double)

interface PhotoLabeler {
    suspend fun label(bitmap: Bitmap): List<LabeledResult>
}
```

**`MlKitPhotoLabeler`** (`@Singleton`, implements `PhotoLabeler`):
- Wraps `ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())`.
- Serializes calls via a `Mutex` so concurrent workers (unlikely but possible) don't interleave `process()` on the same JNI-backed TFLite interpreter.
- Bridges `Task` → `suspend` via `kotlinx-coroutines-play-services.await()`.
- Maps `ImageLabel` → `LabeledResult` with `text = it.text, confidence = it.confidence.toDouble()`.
- Results sorted by confidence desc; caller takes the top.

**`BitmapLoader`** (`data/photo/BitmapLoader.kt`, `@Singleton`):
```kotlin
class BitmapLoader @Inject constructor(@ApplicationContext private val context: Context) {
    // Returns null if the URI can't be opened (grant revoked, SD unmounted, photo deleted).
    // Downsamples to ≤1024 px long edge to avoid OOM on 12MP originals.
    suspend fun load(uri: Uri): Bitmap? = withContext(Dispatchers.IO) { /* decode-bounds + inSampleSize + decode */ }
}
```

Two-pass decode: first `BitmapFactory.Options(inJustDecodeBounds = true)` to get dimensions, compute `inSampleSize` from `max(outWidth, outHeight) / 1024`, then decode for real with `ARGB_8888`. Wraps both in `runCatching` so a deleted photo returns `null` not an exception.

**`PhotoAnalysisRunner`** (`@Singleton`, mirrors `TranscriptionRunner`):
```kotlin
@Singleton
class PhotoAnalysisRunner @Inject constructor(
    private val repository: WalkRepository,
    private val labeler: PhotoLabeler,
    private val bitmapLoader: BitmapLoader,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun analyzePending(walkId: Long): Result<Int> {
        val pending = try {
            repository.pendingAnalysisPhotosFor(walkId)
        } catch (t: Throwable) { return Result.failure(t) }

        var count = 0
        for (photo in pending) {
            val bitmap = bitmapLoader.load(Uri.parse(photo.photoUri))
            val now = clock()
            if (bitmap == null) {
                // Photo unreadable — mark analyzed so we don't retry forever;
                // label/confidence stay null and the UI tombstone takes over.
                try {
                    repository.updatePhotoAnalysis(photo.id, label = null, confidence = null, analyzedAt = now)
                    count++
                } catch (t: Throwable) { Log.w(TAG, "DB update failed (null-bitmap path) id=${photo.id}", t) }
                continue
            }
            val top = try {
                labeler.label(bitmap).firstOrNull()  // already sorted desc
            } catch (ce: CancellationException) { throw ce }
              catch (t: Throwable) {
                Log.w(TAG, "labeler failed id=${photo.id}; storing null label", t)
                null
            } finally {
                bitmap.recycle()
            }
            try {
                repository.updatePhotoAnalysis(
                    photoId = photo.id,
                    label = top?.text,
                    confidence = top?.confidence,
                    analyzedAt = now,
                )
                count++
            } catch (t: Throwable) { Log.w(TAG, "DB update failed id=${photo.id}", t) }
        }
        return Result.success(count)
    }
}
```

**`PhotoAnalysisScheduler`** (interface + WorkManager impl):
```kotlin
interface PhotoAnalysisScheduler {
    fun scheduleForWalk(walkId: Long)
}

@Singleton
class WorkManagerPhotoAnalysisScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhotoAnalysisScheduler {
    override fun scheduleForWalk(walkId: Long) {
        val request = OneTimeWorkRequestBuilder<PhotoAnalysisWorker>()
            .setInputData(workDataOf(PhotoAnalysisWorker.KEY_WALK_ID to walkId))
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            // NOT expedited — Stage 2-F lesson: Expedited + BatteryNotLow
            // crashes. Analysis is not urgent. Drop expedited entirely.
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "photo-analysis-walk-$walkId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
```

**`PhotoAnalysisWorker`** (`@HiltWorker CoroutineWorker`):
```kotlin
@HiltWorker
class PhotoAnalysisWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runner: PhotoAnalysisRunner,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val walkId = inputData.getLong(KEY_WALK_ID, -1L).takeIf { it > 0 } ?: return Result.failure()
        return runner.analyzePending(walkId).fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
    companion object { const val KEY_WALK_ID = "walk_id" }
}
```

### Hilt wiring

New `PhotoAnalysisModule`:
```kotlin
@Module @InstallIn(SingletonComponent::class)
abstract class PhotoAnalysisModule {
    @Binds @Singleton
    abstract fun bindPhotoAnalysisScheduler(impl: WorkManagerPhotoAnalysisScheduler): PhotoAnalysisScheduler
    @Binds @Singleton
    abstract fun bindPhotoLabeler(impl: MlKitPhotoLabeler): PhotoLabeler
}
```

`BitmapLoader` is `@Inject constructor` without a Hilt module (ctor-injection is enough).

### VM integration

`WalkSummaryViewModel`:
- Inject `PhotoAnalysisScheduler`.
- In `pinPhotos(...)`, after `repository.pinPhotos(...)` returns (post-orphan-release), call `scheduler.scheduleForWalk(walkId)` IF `result.insertedIds.isNotEmpty()`. Single call per batch; runner scans for pending.
- Also call `scheduler.scheduleForWalk(walkId)` from `runStartupSweep` so a process-death mid-analysis reconvergence on Walk Summary entry. KEEP policy makes this free if a worker is already running.

### UI — tombstone

`PhotoReliquarySection.PhotoTile` change:
```kotlin
var loadFailed by remember(photo.id) { mutableStateOf(false) }
val contentDesc = if (loadFailed) "Photo unavailable — long press to remove" else "Photo from this walk"

Box(
    modifier = modifier
        .aspectRatio(1f)
        .clip(RoundedCornerShape(PilgrimCornerRadius.small))
        .background(pilgrimColors.parchmentSecondary)
        .pointerInput(photo.id) { detectTapGestures(onLongPress = { onLongPress() }) }
        .semantics {
            contentDescription = contentDesc
            customActions = listOf(CustomAccessibilityAction("Remove from walk") { onLongPress(); true })
        },
) {
    SubcomposeAsyncImage(
        model = photo.photoUri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
        onState = { state -> loadFailed = state is AsyncImagePainter.State.Error },
        error = { ReliquaryTombstone() },
        loading = { /* parchment shows */ },
    )
}
```

New `ReliquaryTombstone`:
```kotlin
@Composable
private fun ReliquaryTombstone() {
    Column(
        modifier = Modifier.fillMaxSize().padding(4.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.BrokenImage, contentDescription = null, tint = pilgrimColors.fog)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Photo unavailable",
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
```

No change to dialog, no change to repo unpin path. Long-press still opens the confirmation dialog.

### Dependencies

Additions to `libs.versions.toml`:
```toml
mlkitImageLabeling = "17.0.9"
coroutinesPlayServices = "1.10.2"

[libraries]
mlkit-image-labeling = { group = "com.google.mlkit", name = "image-labeling", version.ref = "mlkitImageLabeling" }
kotlinx-coroutines-play-services = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-play-services", version.ref = "coroutinesPlayServices" }
```

Wired in `app/build.gradle.kts` `dependencies { implementation(libs.mlkit.image.labeling); implementation(libs.kotlinx.coroutines.play.services) }`.

### Error handling

| Failure | Handling |
|---|---|
| URI deleted / grant revoked | `BitmapLoader.load` returns null → runner stores `analyzedAt = now`, label null → UI tombstone activates via Coil's error slot |
| Bitmap OOM | Caught at `BitmapLoader.load` via `runCatching { }.getOrNull()` → null → same null-label path |
| `ImageLabeler` throws `MlKitException` | Runner logs + stores null label with `analyzedAt = now` (won't retry this photo; we've made a best-effort) |
| Worker-level `runner.analyzePending` fails | `Result.retry()` — WorkManager backs off + retries with default policy |
| Process death mid-batch | Partial rows have `analyzedAt IS NULL`; next `scheduleForWalk` re-enqueues runner which picks up where it left off (KEEP dedups if already running) |
| Concurrent `ImageLabeler.process()` | `Mutex` inside `MlKitPhotoLabeler` serializes |

### Testing

- **Entity**: existing `WalkPhoto` invariant tests still pass (new fields nullable). Add `walkPhoto with analysis fields populated round-trips` DAO test.
- **DAO**: `updateAnalysis writes label/confidence/analyzedAt`; `getPendingAnalysisForWalk returns only rows with analyzed_at IS NULL` ordered by pinned_at asc.
- **Migration**: 3→4 test via `MIGRATION_3_4.migrate(rawSqliteAtV3)` — verify three columns added with correct types + nullability.
- **Repository**: `updatePhotoAnalysis`, `pendingAnalysisPhotosFor` coverage.
- **`PhotoAnalysisRunner`**: in-memory Room + fake `PhotoLabeler` (returns configurable `LabeledResult` list) + fake `BitmapLoader` (returns configurable Bitmap?). Cases: happy path writes labels; null-bitmap path stores null label with analyzedAt; labeler throws → null label + analyzedAt; empty pending → Result.success(0).
- **`MlKitPhotoLabeler` smoke test**: Robolectric test creating a 100×100 ARGB_8888 bitmap, calling `label(bitmap)`. Real ML Kit call via JNI — returns empty list most likely; asserts no exception. Per CLAUDE.md platform-object rule.
- **`WorkManagerPhotoAnalysisSchedulerTest`**: real production class, `WorkManagerTestInitHelper`, asserts WorkInfo exists after `scheduleForWalk` — mirrors `WorkManagerTranscriptionSchedulerTest`.
- **VM**: after `pinPhotos` succeeds, assert `FakePhotoAnalysisScheduler.scheduleForWalkCalls == listOf(walkId)`. Also verify scheduler NOT called when `insertedIds` is empty.
- **UI**: new `PhotoReliquarySectionTest` case — simulate Coil error state by feeding an unreadable URI, assert "Photo unavailable" text appears + contentDescription is the tombstone form.

### Accessibility

- Tombstone tile `contentDescription`: `"Photo unavailable — long press to remove"` — tells TalkBack both what and how.
- `customActions` unchanged — "Remove from walk" still dispatches.
- BrokenImage icon has `contentDescription = null` (decorative; text carries meaning).

---

## Non-goals

- iOS-parity Vision pipeline (saliency, OCR, people, animals, horizon, dominant color). Top label only.
- Re-analysis on model version bump.
- Multi-label UI / label-based search.
- Localizing label strings (English-only from bundled model).
- rememberSaveable for dialog, walk-deletion grant cleanup, clipped-batch Snackbar, time-window badge.
- Etegami renderer, PNG share.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| ML Kit APK size (+5.7MB) | Bundled variant is required for offline-first; accept the cost. Unbundled would need a download-manager flow = more surface area. |
| ImageLabeler concurrent use | `Mutex` inside the `@Singleton` labeler. |
| 12MP OOM | `BitmapLoader` downsamples to ≤1024 px long edge. `runCatching` on the decode path catches residual OOM. |
| Process-death leaves partial analysis | `analyzed_at IS NULL` acts as pending marker; `scheduleForWalk` + runner scan picks up from where it left off. |
| Tombstone-vs-loading confusion | Coil's `onState` fires distinctly; `loading` shows parchment (empty), `error` shows tombstone (icon + text). |
| Future etegami wants more fields | Add in 7-C with a v4→v5 migration; pattern is now paved. |

## Success criteria

- [ ] `./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest` all pass.
- [ ] A finished walk with pinned photos shows labels in Room within seconds after pin (verified via logcat or a debug screen; no UI surface exposes them).
- [ ] Killing the process mid-analysis and reopening Walk Summary resumes analysis.
- [ ] An unreadable pin renders "Photo unavailable" + BrokenImage icon, long-press opens the unpin dialog, TalkBack announces the correct hint.
- [ ] Room v4 schema exported; migration test green.
- [ ] `WorkManagerPhotoAnalysisSchedulerTest` passes — real class builds a real `WorkRequest`.
- [ ] No regression in Stage 7-A data-layer or VM tests.

## References

- iOS: `/Users/rubberduck/GitHub/momentmaker/pilgrim-ios/Pilgrim/Models/Walk/PhotoContextAnalyzer.swift` (274 LOC)
- Android exemplars: `audio/TranscriptionScheduler.kt`, `audio/TranscriptionWorker.kt`, `audio/TranscriptionRunner.kt`, `data/WalkRepository.kt#updateVoiceRecording`
- ML Kit docs: https://developers.google.com/ml-kit/vision/image-labeling/android
- Coroutines play-services: https://github.com/Kotlin/kotlinx.coroutines/tree/master/integration/kotlinx-coroutines-play-services
