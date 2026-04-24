# Stage 7-D Design — Etegami PNG Export + Share

## Context

Stage 7-C shipped a pure-bitmap `EtegamiBitmapRenderer.render(spec, context): Bitmap` that produces a 1080×1920 ARGB postcard, displayed on Walk Summary via `WalkEtegamiCard` (`produceState` → `asImageBitmap()`). The renderer was deliberately built PNG-exportable — fixed resolution, deterministic output, pure function. 7-D adds the share surface.

iOS reference: `Pilgrim/Views/WalkSharingButtons.swift` — a small two-icon row slotted into Walk Summary. Tapping "Etegami" renders on a background task, writes to `temporaryDirectory`, and presents `UIActivityViewController`. The iOS share sheet includes "Save to Photos" automatically via the system. **iOS has no fullscreen preview, no custom action sheet — the Walk Summary already shows the etegami.**

The user's original 7-D intent suggested a "fullscreen preview + bottom action row (Share PNG, Save to Photos, Cancel)". That over-specifies vs. iOS; the card on Walk Summary already functions as the preview. Design below matches iOS's simpler flow and accepts one asymmetry: Android's share chooser does NOT auto-include gallery save, so we add an explicit "Save to Photos" action that iOS doesn't need.

## Recommended approach

A small two-button action row slotted directly **below** `WalkEtegamiCard` on Walk Summary. Buttons: **Share** (primary) and **Save** (secondary, icon-first). Tap → background render via the Stage 7-C renderer → write PNG to `cacheDir/etegami/pilgrim-etegami-<yyyy-MM-dd-HHmm>.png` → dispatch the appropriate system intent. No fullscreen preview, no Cancel button (system chooser has its own dismiss).

### Layer breakdown

1. **`EtegamiShareIntentFactory`** (object) — builds `ACTION_SEND` + `image/png` + `EXTRA_STREAM` + **mandatory `ClipData.newRawUri(...)`** + `FLAG_GRANT_READ_URI_PERMISSION`. Wraps in `Intent.createChooser`.
2. **`EtegamiPngWriter`** (object) — `suspend fun writeToCache(bitmap: Bitmap, filename: String, context: Context): File` on `Dispatchers.Default` (CPU-bound compress per Android 2026 best practice). Atomic: writes to `<filename>.tmp` then renames on success.
3. **`EtegamiGallerySaver`** (object) — `suspend fun saveToGallery(bitmap: Bitmap, filename: String, context: Context): SaveResult` dispatching API 29+ path (MediaStore.Images insert with `IS_PENDING=1` → OutputStream → `IS_PENDING=0`) or API 28 path (legacy `WRITE_EXTERNAL_STORAGE` → `DIRECTORY_PICTURES/Pilgrim/`). Returns `SaveResult.Success(Uri)` | `SaveResult.NeedsPermission` | `SaveResult.Failed(Throwable)`.
4. **`EtegamiCacheSweeper`** (object) — `suspend fun sweepStale(context: Context, olderThan: Duration = 24.hours)`. Runs on Walk Summary VM init (fire-and-forget) and on tap (before writing a new file). Uses `lastModified()` + simple `deleteRecursively`.
5. **FileProvider + `file_paths.xml`** — first FileProvider in the app. Authority: `${applicationId}.fileprovider`. Path: `cache-path name="etegami_cache" path="etegami/"`.
6. **`WalkEtegamiShareRow`** (Composable) — the two-button row. Holds ephemeral `isSharing` / `isSaving` StateFlow flags (from VM) for spinner + disable-during-work. Snackbar/Toast on save success/failure.
7. **VM additions** — `shareEtegami(activity: ComponentActivity)` + `saveEtegamiToGallery(...)` methods on `WalkSummaryViewModel`. Both: render Bitmap via 7-C renderer → persist → dispatch intent or emit snackbar event.

### Filename convention (iOS parity)

`pilgrim-etegami-<yyyy-MM-dd-HHmm>.png` using `walk.startTimestamp` in the walker's `ZoneId.systemDefault()`. Matches iOS byte-for-byte so cross-platform users recognize shared files.

### API-level branching (min SDK 28)

- **API 29+** (~99% of users in 2026): MediaStore.Images insert, no storage permission required.
- **API 28**: declare `<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />` in the manifest, request at runtime on first save tap, write to `Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES)/Pilgrim/`. Share button always works on all APIs (FileProvider doesn't need storage permission).

### Error paths

- Render failure (OOM, typeface null): swallow + snackbar "Couldn't create postcard — try again."
- Share intent unresolvable (unlikely; every device has a share chooser): snackbar "No app available to share to."
- MediaStore insert fails (quota, IS_PENDING-flip race): rollback via `contentResolver.delete(pendingUri, null, null)` then snackbar.
- Permission denied on API 28: snackbar "Save requires permission to your Photos."
- Disk full writing to cache: snackbar "Storage full — free space and try again."

### Concurrency guards

- Share button is disabled (greyed) while `isSharing = true` or `isSaving = true` (prevent double-tap races spawning two compresses).
- VM methods are no-ops if a render is already in flight (`Mutex.tryLock`).

### Thread policy

Per Stage 7-C's pattern:
- `EtegamiBitmapRenderer.render` → `Dispatchers.Default` (already wired).
- `Bitmap.compress(PNG, 100, OutputStream)` → `Dispatchers.Default` (CPU-bound, not IO).
- `MediaStore.insert` + OutputStream writes → `Dispatchers.IO` (content-resolver IO path can block).
- Intent dispatch → Main (requires Activity).

### Testability

- `EtegamiShareIntentFactory.build(...)`: pure — assert intent action, MIME type, EXTRA_STREAM, ClipData, flags.
- `EtegamiPngWriter.writeToCache`: Robolectric — writes a small Bitmap, asserts file exists + has PNG magic bytes.
- `EtegamiGallerySaver`: Robolectric on API 29+ path (ShadowContentResolver); API 28 path skipped locally (device-dependent, deferred to 7-E device QA).
- `EtegamiCacheSweeper`: pure file-system, uses `tempFolder` + timestamp manipulation.
- VM: unit test the `shareEtegami` / `saveEtegamiToGallery` state transitions + error snackbar events via Turbine.
- Per CLAUDE.md policy for runtime-validated builders: Robolectric test that calls the *production* `EtegamiShareIntentFactory.build(...)` → `.resolveActivity(ctx.packageManager)` check (though a Sandbox with no chooser installed may return null — assert the intent itself is well-formed via `action`, `type`, `extras` shape).

### Out of scope (documented; deferred)

- Fullscreen preview with crop/zoom (not in iOS; not in 7-D).
- Customization (palette override, font size, intention edit) — future stage.
- Upload to Cloudflare Worker for ephemeral HTML links — Stage 8-A (journey share).
- Bulk-share multiple walks — not an iOS feature.
- Video export / animated postcard — not an iOS feature.
- Watermark / attribution overlay — iOS postcard already ends with "pilgrimapp.org" provenance text (Stage 7-C layer 12).

## What was considered and rejected

**Fullscreen preview with explicit Cancel.** iOS doesn't have one; the existing `WalkEtegamiCard` IS the preview. Adding a preview step adds a tap for no new information.

**Single "Share" button that relies on system chooser's gallery save.** Android's share chooser doesn't natively offer "Save to Photos" the way iOS does. Users who want gallery save would have to pick "Files" and navigate manually, or install a gallery app that accepts `image/*`. That's a parity regression vs iOS.

**In-memory Bitmap share via `ClipData.newRawUri` without a file write.** Android's FileProvider must back content URIs with an actual file for cross-process read. There's no reliable in-memory share target on API 28-36 for 8 MB PNGs.

**Embedding Share/Save affordances INSIDE `WalkEtegamiCard`.** Keeps the card display-only (Stage 7-C contract), makes the action row reusable if we ever surface etegami outside Walk Summary.

**Using `viewModelScope.launch { withContext(Main) { ... } }` for intent dispatch.** The Composable already runs on Main and receives the `Activity` directly via `LocalContext.current as ComponentActivity`. VM emits an `Intent` via a `SharedFlow<ShareEvent>`; Composable collects and calls `startActivity`. Keeps VM Android-framework-free.

## Quality considerations

### Stage 7-C/5-C lessons that apply here

- **CE re-throw** in every `runCatching` / `catch (Throwable)` block wrapping suspend work.
- **Bitmap recycle timing**: the 7-C `render` returns a new Bitmap; we own it in 7-D. After `compress` writes bytes out, recycle eagerly — `compress` is synchronous when the OutputStream flushes. But gate recycle on a `settled` flag (Stage 7-B pattern): recycle only after compress returns non-exceptionally AND is not cancelled.
- **`viewModelScope.launch` defaults to Main** (Stage 2-E ANR trap) — every suspend worker must `withContext(Dispatchers.Default)` or `.IO` explicitly.
- **Robolectric + platform builders** (CLAUDE.md policy) — `EtegamiShareIntentFactory.build()` MUST be exercised in a Robolectric test. Intent builders aren't "validated" per se (no `.build()` throw) but framework resolver validation happens at `startActivity`.

### Memory

Peak live bitmap: two at most during save+share overlap (unlikely with mutex guard). Worst case: `render` returns an 8.3 MB bitmap, `compress` reads it, write completes, recycle. Single bitmap held for ~50-100ms on mid-tier device.

### Security / privacy

- FileProvider URIs are `FLAG_GRANT_READ_URI_PERMISSION`-gated — receiving app can read for the lifetime of the intent.
- Cache files auto-clear under OS storage pressure + our 24h sweeper. No sensitive content in etegami (it's a postcard users explicitly share).
- Save-to-Photos writes to user's gallery explicitly initiated by user tap. No background/silent writes.
- No network: 7-D is 100% local. Cloudflare share lives in Stage 8-A.

## Success criteria

1. Tap "Share" on Walk Summary → Android chooser appears with image preview thumbnail, filename `pilgrim-etegami-2026-04-24-0932.png`, and the etegami renders correctly in receiving apps (Messages, Gmail, Photos).
2. Tap "Save" on API 29+ → snackbar "Saved to Photos", file visible in Google Photos under Pictures/Pilgrim.
3. Tap "Save" on API 28 → permission prompt first time, then snackbar "Saved to Photos".
4. Double-tap protection: two rapid taps spawn exactly one render + one chooser.
5. Cache sweeper keeps `cacheDir/etegami/` under 20 MB over time.
6. Walk Summary re-entry: subsequent taps re-render (idempotent) and overwrite the cached PNG by filename (no stale content).
7. Build + tests clean on min SDK 28 through target 36.

## Files to create

- `app/src/main/AndroidManifest.xml` — add `<provider>` for FileProvider, add `<uses-permission maxSdkVersion=28>` for legacy save.
- `app/src/main/res/xml/file_paths.xml` — FileProvider paths config.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/etegami/share/EtegamiShareIntentFactory.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/etegami/share/EtegamiPngWriter.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/etegami/share/EtegamiGallerySaver.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/etegami/share/EtegamiCacheSweeper.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkEtegamiShareRow.kt` — Composable action row.
- Modifications: `WalkSummaryViewModel.kt` (shareEtegami + saveEtegamiToGallery + share event SharedFlow), `WalkSummaryScreen.kt` (slot the row below WalkEtegamiCard), `strings.xml` (button labels, snackbar messages).
- Tests: one file per new `.kt` above + VM test additions.

Estimated: ~10 new files including tests. Modest-sized stage, dominated by Android-intent plumbing, no new algorithms.
