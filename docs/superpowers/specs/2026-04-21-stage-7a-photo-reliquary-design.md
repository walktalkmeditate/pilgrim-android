# Stage 7-A: Photo Reliquary (picker + pin/unpin + grid) — Design

**Date:** 2026-04-21
**Phase:** Port Phase 7 (Photo reliquary + etegami) — sub-stage A
**Ships:** the minimum usable photo feature — user can pin photos from their gallery to a finished walk and view them on Walk Summary.

---

## Intent (one paragraph)

Give Pilgrim Android users the ability to associate photos with a finished walk. They tap **"Add photos"** on Walk Summary, the Android Photo Picker opens, they pick up to the remaining slot count (cap: 20 per walk), and the picked photos appear as a 3-column square grid in a new Reliquary section. Long-press a thumbnail to unpin. The app stores only `content://` URIs — never photo bytes — and holds persistable read grants so the URIs survive process death. ML Kit analysis, the etegami postcard renderer, and PNG share land in 7-B, 7-C, 7-D.

## Why this shape (intuition first)

The iOS original auto-scans the user's photo library within the walk's time window, presenting candidates without asking. That works on iOS because PHAsset's `localIdentifier` + `PHFetchOptions` gives you free, permissionless, fast library access once the user grants "Photos (full access)". **Android is different**: auto-scan requires `READ_MEDIA_IMAGES` (API 33+) or `READ_EXTERNAL_STORAGE` (pre-33), which is exactly the kind of broad-permission ask Android's modern privacy UX is designed to avoid. The idiomatic replacement is **explicit picker** via `PickMultipleVisualMedia`, which requires no runtime permission at all — the system picker acts as the consent moment. This also happens to be more contemplative: the user chooses what belongs to the walk rather than the algorithm guessing.

Grid (3×N) beats carousel for Android because the Reliquary in this port is conceptually a **collection** — a thing you return to look at, not a transient ribbon. A grid reads as "stored" rather than "passing by." Horizontal scroll carousel stays on the iOS side where it's already contextual.

Long-press to unpin (with confirm dialog) keeps 7-A from needing a full-screen preview sheet. That screen lives in 7-B/7-C where we'll want it anyway for etegami framing. Long-press with confirmation is safer than tap-to-unpin (fewer fat-finger disasters) and cheaper than a bottom sheet.

## Considered and rejected

- **Auto-scan of MediaStore by walk time window** (iOS parity). Rejected: requires `READ_MEDIA_IMAGES`, ugly permission dialog, against Android privacy ethos. Also brittle — many users' photos lack `DATE_TAKEN` or have clock skew. Time-window labeling comes back in 7-B as a passive "taken during this walk" badge read from picker-URI metadata, no auto-scan needed.
- **Copying photo bytes into app-private storage**. Rejected: bloats backups, duplicates the user's library, conflicts with "reference not copy" local-first ethos, matches iOS PHAsset model.
- **Bottom-sheet preview with pin/unpin + open-in-photos buttons**. Rejected for 7-A scope. Will revisit in 7-C when we need a preview for etegami framing anyway.
- **Horizontal LazyRow carousel matching iOS**. Rejected — grid reads as "kept" rather than "scrolled-past." Also Walk Summary already scrolls vertically; horizontal nested scroll would need an `overscroll` story.
- **Coil vs Glide**. Chose Coil 3.x — purpose-built for Compose, modern, smaller, built-in `content://` URI support. Glide is overkill for this use case.
- **Custom builder for picker fallback on pre-API-30 without Play Services**. `ActivityResultContracts.PickMultipleVisualMedia` already falls back to SAF automatically on such devices. Accept the fallback — it's a long-tail audience.

---

## Architecture

### Data layer

**New Room entity** `WalkPhoto` (`data/entity/WalkPhoto.kt`):

```kotlin
@Entity(
    tableName = "walk_photos",
    foreignKeys = [
        ForeignKey(entity = Walk::class, parentColumns = ["id"],
                   childColumns = ["walk_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("walk_id"), Index("uuid", unique = true)],
)
@Immutable
data class WalkPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "walk_id") val walkId: Long,
    @ColumnInfo(name = "photo_uri") val photoUri: String,
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long,  // epoch ms
    @ColumnInfo(name = "taken_at") val takenAt: Long? = null,  // epoch ms, nullable
)
```

- `photoUri` is the `content://` URI string from the Photo Picker.
- `pinnedAt` is wall-clock at pin time.
- `takenAt` is `DATE_TAKEN` if queryable; null for SAF-fallback URIs or photos with stripped EXIF.
- `@Immutable` since it holds only primitives + `String?` — Compose stability for `@Composable` consumers.

**New DAO** `WalkPhotoDao`:

- `@Insert` (suspend) — single insert + multi-insert (`insertAll` for batch pick commits).
- `@Delete` (suspend) by entity.
- `@Query("DELETE FROM walk_photos WHERE id = :id")` — delete by id (matches UI unpin pattern).
- `@Query("SELECT * FROM walk_photos WHERE walk_id = :walkId ORDER BY pinned_at ASC")` — `getForWalk` (suspend) + `observeForWalk` (Flow).
- `@Query("SELECT COUNT(*) FROM walk_photos WHERE walk_id = :walkId")` — `countForWalk` (suspend) for slot-availability math.

**Database migration**: version bump 2 → 3, **explicit** (not AutoMigration — new table with FK, we want the exact SQL visible). Add `MIGRATION_2_3` to `PilgrimDatabase` companion wiring, register via `.addMigrations(...)`:

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `walk_photos` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `uuid` TEXT NOT NULL,
                `walk_id` INTEGER NOT NULL,
                `photo_uri` TEXT NOT NULL,
                `pinned_at` INTEGER NOT NULL,
                `taken_at` INTEGER,
                FOREIGN KEY(`walk_id`) REFERENCES `walks`(`id`) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_walk_photos_walk_id` ON `walk_photos` (`walk_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_walk_photos_uuid` ON `walk_photos` (`uuid`)")
    }
}
```

Schema JSON is regenerated under `app/schemas/` via Room's `exportSchema = true`.

**WalkRepository additions** (`data/WalkRepository.kt`):

```kotlin
suspend fun pinPhoto(walkId: Long, photoUri: String, takenAt: Long?): Long
suspend fun pinPhotos(walkId: Long, refs: List<PhotoPinRef>): List<Long>  // batch
suspend fun unpinPhoto(photoId: Long)
suspend fun countPhotosFor(walkId: Long): Int
fun observePhotosFor(walkId: Long): Flow<List<WalkPhoto>>
```

`PhotoPinRef(uri: String, takenAt: Long?)` is a small `data class` in `data/` so the UI layer can hand the repo a pre-read batch.

### Permissions & persistability

- **No manifest permission additions.** The Photo Picker's returned URIs come with ephemeral read grants.
- Per URI, call `contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)` on `Dispatchers.IO` **before** inserting the Room row. If `takePersistable...` throws `SecurityException` (rare, happens when the source grant wasn't persistable — e.g. SAF URIs don't need it but some OEM pickers reject the call), log and swallow: the ephemeral grant is enough for the current process lifetime and the row still inserts. Document this as a known limitation (pin might become unreadable after process restart on those devices).
- Add the Play Services Photo Picker backport metadata to `AndroidManifest.xml` so API 28–29 devices with Play Services get the modern picker:

```xml
<service android:name="com.google.android.gms.metadata.ModuleDependencies"
         android:enabled="false" android:exported="false"
         tools:ignore="MissingClass">
    <intent-filter>
        <action android:name="com.google.android.gms.metadata.MODULE_DEPENDENCIES" />
    </intent-filter>
    <meta-data android:name="photopicker_activity:0:required" android:value="" />
</service>
```

### UI layer

**New composable** `ui/walk/reliquary/PhotoReliquarySection.kt`:

```
┌─ Reliquary ──────────────────── [+ Add] ──┐
│ ┌───┐ ┌───┐ ┌───┐                        │
│ │ 📷│ │ 📷│ │ 📷│                        │
│ └───┘ └───┘ └───┘                        │
│ ┌───┐                                    │
│ │ 📷│                                    │
│ └───┘                                    │
└───────────────────────────────────────────┘
```

- Section header row: "Reliquary" (stringResource) on left, `OutlinedButton` with `Icons.Default.AddAPhoto` + label on right. Button disabled when `currentCount >= MAX_PINS_PER_WALK` with supporting text "Full".
- Grid: `LazyVerticalGrid` with `GridCells.Fixed(3)`, 4dp spacing, each cell `aspectRatio(1f)`, `Modifier.heightIn(max = ...)`. **BUT**: `LazyVerticalGrid` inside a parent `verticalScroll(Column)` crashes (double-scroll). Solve by building a **non-lazy grid** — manual `Row` / `Column` chunked — since pin count is capped at 20 (7 rows max). Trivial perf; no lazy loading needed.
- Each cell: `AsyncImage(model = uri, contentDescription = ..., modifier = clip + long-press gesture)`. Coil handles loading / error. On error show a neutral placeholder (fog-colored box with broken-image icon).
- Empty state (0 photos): don't render the grid; just the header row. Feels intentional — add button is the affordance.
- Long-press → `AlertDialog`:
  - Title: "Remove from walk?"
  - Body: "This photo will be unpinned. Your photo library isn't changed."
  - Buttons: "Remove" / "Keep"
  - `HapticFeedbackType.LongPress` fires on gesture detection (pre-dialog).

**Picker launcher** (inside `PhotoReliquarySection`):

```kotlin
val context = LocalContext.current
val scope = rememberCoroutineScope()
val currentCount = photos.size
val slots = (MAX_PINS_PER_WALK - currentCount).coerceAtLeast(0)

val multiLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.PickMultipleVisualMedia(
        maxItems = slots.coerceAtLeast(2)  // contract requires > 1
    )
) { uris -> onUrisPicked(uris) }

val singleLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.PickVisualMedia()
) { uri -> onUrisPicked(listOfNotNull(uri)) }
```

- If `slots == 0`: button disabled (no launch).
- If `slots == 1`: launch `singleLauncher`.
- If `slots >= 2`: launch `multiLauncher`.
- `onUrisPicked` hops to the VM: `viewModel.pinPhotos(uris)`.

**VM extension** (`WalkSummaryViewModel`):

Add to `WalkSummary` data class:
```kotlin
val pinnedPhotos: List<WalkPhoto> = emptyList(),
```

Add observed flow (same pattern as `recordings`):
```kotlin
val pinnedPhotos: StateFlow<List<WalkPhoto>> =
    repository.observePhotosFor(walkId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
        initialValue = emptyList(),
    )
```

**Decision**: expose `pinnedPhotos` as a **separate StateFlow** (like `recordings`), not as a field on the `Loaded` state payload. Keeps the Loaded state read-once; photo additions don't force a summary rebuild.

Add VM methods:
```kotlin
fun pinPhotos(uris: List<Uri>) {
    if (uris.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        val refs = uris.mapNotNull { uri ->
            val persistOk = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.isSuccess
            val takenAt = readDateTaken(uri)
            PhotoPinRef(uri.toString(), takenAt)
        }
        repository.pinPhotos(walkId, refs)
    }
}

fun unpinPhoto(photo: WalkPhoto) {
    viewModelScope.launch(Dispatchers.IO) {
        repository.unpinPhoto(photo.id)
    }
}
```

`context` is provided via `@ApplicationContext Context` Hilt injection into the VM. `readDateTaken(uri)` queries `ContentResolver` for `MediaStore.MediaColumns.DATE_TAKEN` — isolated helper with try/catch (returns null on any failure).

### WalkSummaryScreen integration

Slot the reliquary **between stats and light reading card**:

```
Title
 ↓
Map
 ↓
Stats
 ↓
★ PhotoReliquarySection  ← NEW
 ↓
LightReadingCard (if present)
 ↓
VoiceRecordings (if present)
 ↓
Done button
```

Rationale: photos are the most concrete "artifacts" of the walk. They sit just below the objective stats and above the interpretive (Light Reading) / reflective (recordings) content. Matches reading order — tangible → felt.

### Dependencies

- `io.coil-kt.coil3:coil-compose:3.4.0` — AsyncImage, built-in `content://` URI support. Add to `libs.versions.toml` + `app/build.gradle.kts`.
- No network module; no GIF/SVG/video modules.
- Activity Compose and Material3 already on classpath.

### Error handling

- Picker returns empty list (user cancelled): no-op, no toast.
- `takePersistableUriPermission` throws: log at `Log.w`, insert row anyway.
- `readDateTaken` fails / returns null: `takenAt = null`, row inserts.
- Coil fails to load URI (photo deleted from library, device storage unmounted): show error placeholder tile, user can unpin.
- `ContentResolver` query returns null Cursor: return null `takenAt`.
- Batch insert partially fails: transaction wraps via `database.withTransaction { ... }` — all or nothing per pick event.
- Unpin on already-deleted row: idempotent no-op.

### Testing

- `WalkPhotoDaoTest` (Robolectric + in-memory Room): insert / delete / observe / count — parallel to `VoiceRecordingDaoTest`.
- `PilgrimDatabaseMigrationTest`: 2→3 via `MigrationTestHelper`, verify table + indices created, verify FK cascades on walk deletion.
- `WalkRepositoryTest` additions: `pinPhoto`, `pinPhotos`, `unpinPhoto`, `countPhotosFor`, `observePhotosFor` (mirror existing `VoiceRecording` tests).
- `WalkSummaryViewModelTest`: stub the repository, call `pinPhotos(listOf(uri1, uri2))`, assert `pinnedPhotos.value` contains both within a Turbine window. Also: verify empty URI list is a no-op (doesn't launch a coroutine that touches repository).
- **Builder test** (CLAUDE.md platform-object rule): `rememberLauncherForActivityResult(PickMultipleVisualMedia(maxItems = ...))` is not a "platform object with runtime-validated builder" in the same sense as `WorkRequest`. Contract construction is pure Kotlin. No builder test needed for the picker itself. But we DO build an `Intent.FLAG_GRANT_READ_URI_PERMISSION` — that's a constant, no test. Mark this box unchecked-but-intentional.
- **Time-window snapshot** (stretch): `readDateTaken(fakeUri) == expected` via Robolectric `ShadowContentResolver.registerInputStream(uri, ...)`. Likely defer to 7-B.

### Accessibility

- Grid cell `contentDescription`: `"Photo from this walk"` (localized) + `takenAt` formatted if present.
- "Add photos" button has text label, not just icon.
- Long-press is invisible to TalkBack; add `Modifier.semantics { customActions = listOf(CustomAccessibilityAction("Remove from walk", onRemove) ) }` per cell, reusing the same onRemove callback as the gesture. **This is critical — lesson from Stage 6-B.**
- AlertDialog auto-announces through TalkBack (Material3 handles it).

---

## Non-goals for 7-A

- ML Kit image labeling / saliency / subject segmentation — 7-B.
- Etegami postcard renderer (Compose Canvas) — 7-C.
- Share PNG via `ACTION_SEND` — 7-D.
- Time-window filtering of picker candidates — post-filter label in 7-B.
- "Taken during this walk" badge on grid cells — 7-B.
- Full-screen photo preview sheet — 7-B or 7-C.
- MediaStore `DATE_TAKEN` auto-scan (permissionful) — never (not idiomatic Android).
- Reordering photos in the grid — never (YAGNI).
- Photo deletion from MediaStore — never (pilgrim is local-first; we don't touch the user's library).
- Batch operations across walks — never (YAGNI).
- Tombstone for broken pins beyond Coil's default error state — 7-B (if we find it meaningfully useful).
- Localization of new strings — Phase 10 (English-only for now).

---

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| `takePersistableUriPermission` crashes on SAF-fallback URIs from API 28-29 without Play Services | Wrap in `runCatching`, insert row regardless, log silently |
| `PickMultipleVisualMedia(maxItems=1)` throws IllegalArgumentException | Branch to `PickVisualMedia()` when slots == 1 |
| User picks same photo twice (across two batches) | Unique index on `uuid` is per-row uuid not per-URI — duplicates allowed intentionally, matches iOS behavior where you can pin the same PHAsset twice. A pre-insert `SELECT EXISTS WHERE walk_id = ? AND photo_uri = ?` check would be nicer but adds scope — defer. |
| Grid inside `verticalScroll(Column)` causes double-scroll crash | Use non-lazy grid (manual Row chunking) — count is bounded at 20 |
| Process death during a picker transaction | URIs arrive in ActivityResult before VM sees them; lifecycle-safe launcher handles recreation; if pin-save coroutine is killed mid-batch, individual transactional inserts are still committed or rolled back cleanly |
| Coil loads photo into a cell oriented wrong | Coil honors EXIF rotation by default on API 29+; on API 28 we accept occasional sideways rendering as known limitation |
| `walk_photos` table grows unbounded over years | 20-per-walk cap + FK cascade on walk deletion — acceptable for 1.0. Pruning policy is Phase 10. |

---

## Data-model parity with iOS

| Field | iOS `WalkPhoto` | Android `WalkPhoto` | Notes |
|---|---|---|---|
| Identifier | `_localIdentifier` (PHAsset.localIdentifier) | `photoUri` (content:// URI string) | Platform-native reference type |
| Uuid | `_uuid` | `uuid` | UUID4 string |
| Walk FK | `_workout` relationship | `walkId: Long` | Standard Room FK |
| Captured time | `_capturedAt` | `takenAt` | Nullable on Android (not all photos have it) |
| Pinned time | `_keptAt` | `pinnedAt` | |
| Lat/Lng | `_capturedLat`, `_capturedLng` | deferred to 7-B | Not needed until ML Kit or etegami — map pin |

---

## Open questions (to resolve during implementation)

1. ~~Should `@Entity` use `prePackagedDatabaseCallback` or plain migration?~~ Plain migration — matches existing style.
2. ~~Do we add a soft uniqueness constraint on `(walk_id, photo_uri)`?~~ No — iOS allows duplicate pins of same asset, preserving parity.
3. Should "Add photos" button fire `HapticFeedbackType.LongPress` on tap for tactile feedback? **Yes, lightweight confirmation** — consistent with other Walk Summary interactions.
4. What's the minimum text for the "Reliquary" section header? Just **"Reliquary"** — no count, match iOS.

---

## Success criteria

- [ ] A finished walk shows a Reliquary section on Walk Summary.
- [ ] Tapping "Add photos" opens the Android Photo Picker without a permission dialog.
- [ ] Picked photos persist through app restart (URI still resolvable, thumbnails still render).
- [ ] Long-press + confirm removes a photo; grid updates live.
- [ ] The 20-pin cap is enforced: button disables at 20, attempts to pick past the cap trim to remaining slots.
- [ ] Deleting the walk cascades to its photo rows.
- [ ] TalkBack users can add photos (button has label) and unpin photos (custom action per cell).
- [ ] No `READ_MEDIA_IMAGES` in manifest; picker URIs survive process death via `takePersistableUriPermission`.
- [ ] All new unit + Room tests green; project builds clean on `./gradlew assembleDebug`.

---

## References

- Android Photo Picker: https://developer.android.com/training/data-storage/shared/photo-picker
- Persist media file access: https://developer.android.com/training/data-storage/shared/photo-picker#persist-media-file-access
- Coil 3.x: https://coil-kt.github.io/coil/
- iOS reference: `../pilgrim-ios/Pilgrim/Scenes/WalkSummary/Reliquary/` and `../pilgrim-ios/Pilgrim/Models/Data/DataModels/WalkPhoto.swift`
