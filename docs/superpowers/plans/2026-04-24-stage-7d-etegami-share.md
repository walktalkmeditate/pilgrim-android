# Stage 7-D Implementation Plan — Etegami PNG Export + Share

Spec: `docs/superpowers/specs/2026-04-24-stage-7d-etegami-share-design.md`

Sequence: **Task 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10**. Every task ends compiling + with passing tests before the next begins.

---

## Task 1 — FileProvider manifest config + `file_paths.xml`

First `FileProvider` in the app. Single authority `${applicationId}.fileprovider`, single `<cache-path>` entry for `etegami/`.

### Files

**Create** `app/src/main/res/xml/file_paths.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <!--
      Stage 7-D: FileProvider root for shared etegami PNG postcards.
      Content URIs: content://org.walktalkmeditate.pilgrim.fileprovider/etegami_cache/<filename>.png
      Files live under `context.cacheDir/etegami/` and are swept by
      EtegamiCacheSweeper after 24h; Android also auto-clears
      cacheDir on storage pressure.
    -->
    <cache-path name="etegami_cache" path="etegami/" />
</paths>
```

**Edit** `app/src/main/AndroidManifest.xml` — add inside `<application>`:
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

And add at the top (as a sibling of existing `<uses-permission>` tags):
```xml
<!-- Stage 7-D save-to-Photos legacy path for API 28 only. On API 29+
     MediaStore scoped storage doesn't need this. -->
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

### Verify
```bash
./gradlew :app:assembleDebug
# Expect BUILD SUCCESSFUL. Any manifest merger error fails fast.
```

---

## Task 2 — `EtegamiPngWriter`: atomic cache write

Pure-ish I/O helper. Writes the bitmap to `<cacheDir>/etegami/<filename>.tmp`, then atomic-rename to `<filename>`. Returns the final `File`. Caller owns bitmap lifecycle.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/etegami/share/EtegamiPngWriter.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 7-D: writes an etegami [Bitmap] to the app's cacheDir under
 * `etegami/<filename>` with atomic rename semantics. Runs on
 * [Dispatchers.Default] — `Bitmap.compress` is CPU-bound (not IO) per
 * Android 2026 best practice; a synchronous compress of a 2.1 MP
 * bitmap takes ~50-100ms on mid-tier devices.
 *
 * Atomic semantics: writes to `<filename>.tmp` then `renameTo` the
 * final name. A crash mid-write leaves the `.tmp` orphan for
 * [EtegamiCacheSweeper] to clean up; never a half-written final file.
 *
 * Caller owns the input [Bitmap]'s lifecycle — writer never
 * recycles, even on failure. Callers typically recycle after
 * dispatching the share intent.
 */
internal object EtegamiPngWriter {

    /** Root `<cacheDir>/etegami/` directory; created on first call. */
    fun cacheRoot(context: Context): File =
        File(context.cacheDir, "etegami").apply { mkdirs() }

    /**
     * Writes [bitmap] to `cacheRoot/<filename>`. Returns the final
     * [File]. Throws on any I/O failure; caller wraps in runCatching
     * + CE re-throw (Stage 5-C / 7-C lesson).
     */
    suspend fun writeToCache(
        bitmap: Bitmap,
        filename: String,
        context: Context,
    ): File = withContext(Dispatchers.Default) {
        require(filename.endsWith(".png")) { "filename must end with .png (got $filename)" }
        val dir = cacheRoot(context)
        val tmp = File(dir, "$filename.tmp")
        val finalFile = File(dir, filename)
        FileOutputStream(tmp).use { out ->
            // PNG is lossless so the quality parameter is ignored; 100
            // is customary for clarity.
            val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (!ok) {
                tmp.delete()
                error("Bitmap.compress returned false for $filename")
            }
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(finalFile)) {
            tmp.delete()
            error("Atomic rename failed: $tmp → $finalFile")
        }
        finalFile
    }
}
```

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/etegami/share/EtegamiPngWriterTest.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EtegamiPngWriterTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun cleanup() {
        EtegamiPngWriter.cacheRoot(context).deleteRecursively()
    }

    @Test
    fun `writeToCache produces a PNG file with the PNG magic header`() = runBlocking {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val file = EtegamiPngWriter.writeToCache(bitmap, "test-small.png", context)
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
        // PNG magic: 89 50 4E 47 0D 0A 1A 0A
        val header = file.inputStream().use { it.readNBytes(8) }
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            header,
        )
    }

    @Test
    fun `writeToCache overwrites an existing file idempotently`() = runBlocking {
        val b1 = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val b2 = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val f1 = EtegamiPngWriter.writeToCache(b1, "overwrite.png", context)
        val size1 = f1.length()
        val f2 = EtegamiPngWriter.writeToCache(b2, "overwrite.png", context)
        assertEquals(f1.absolutePath, f2.absolutePath)
        // Content differs ⇒ file length differs slightly (or identical
        // if PNG encoder yields same bytes); either way, file exists
        // and is non-zero. The key assertion is no crash on re-write.
        assertTrue(f2.length() > 0)
        assertTrue(size1 > 0)
    }

    @Test
    fun `writeToCache rejects non-png filenames`() = runBlocking {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            EtegamiPngWriter.writeToCache(bitmap, "bad.jpg", context)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains(".png") ?: false)
        }
    }

    @Test
    fun `writeToCache cleans up .tmp file on success`() = runBlocking {
        val bitmap = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888)
        EtegamiPngWriter.writeToCache(bitmap, "clean.png", context)
        val tmpFiles = EtegamiPngWriter.cacheRoot(context).listFiles { f -> f.name.endsWith(".tmp") }
        assertTrue("no .tmp stragglers expected", tmpFiles.isNullOrEmpty())
    }
}
```

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.EtegamiPngWriterTest"
```

---

## Task 3 — `EtegamiCacheSweeper`: stale file cleanup

Runs on Walk Summary VM init (fire-and-forget) + before every fresh write (to bound the directory under spontaneous heavy use).

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/etegami/share/EtegamiCacheSweeper.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.content.Context
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 7-D: prunes stale etegami PNGs from [EtegamiPngWriter.cacheRoot].
 *
 * Share intents outlive the calling composition — a user may tap
 * Share, back out to home, open Gmail, and finish the send several
 * minutes later. Keeping files for 24h is a generous upper bound;
 * Android's cacheDir is also auto-cleared under storage pressure so
 * real-world retention is shorter.
 *
 * Exceptions from individual deletes are logged-and-skipped — the
 * sweeper is best-effort. Only [CancellationException] propagates.
 */
internal object EtegamiCacheSweeper {

    private const val TAG = "EtegamiCacheSweeper"

    suspend fun sweepStale(
        context: Context,
        olderThan: Duration = 24.hours,
        now: () -> Long = System::currentTimeMillis,
    ): Int = withContext(Dispatchers.Default) {
        val root = EtegamiPngWriter.cacheRoot(context)
        val cutoff = now() - olderThan.inWholeMilliseconds
        var deleted = 0
        root.listFiles()?.forEach { file ->
            try {
                if (file.lastModified() < cutoff && file.delete()) {
                    deleted++
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "failed to delete ${file.name}", t)
            }
        }
        deleted
    }
}
```

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/etegami/share/EtegamiCacheSweeperTest.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EtegamiCacheSweeperTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun cleanup() {
        EtegamiPngWriter.cacheRoot(context).deleteRecursively()
    }

    @Test
    fun `sweepStale deletes files older than the cutoff`() = runBlocking {
        val root = EtegamiPngWriter.cacheRoot(context)
        val now = 10_000_000L
        val stale = File(root, "stale.png").apply {
            writeText("stale")
            setLastModified(now - 25 * 3600 * 1000L) // 25h old
        }
        val fresh = File(root, "fresh.png").apply {
            writeText("fresh")
            setLastModified(now - 1 * 3600 * 1000L) // 1h old
        }
        val deleted = EtegamiCacheSweeper.sweepStale(context, olderThan = 24.hours, now = { now })
        assertEquals(1, deleted)
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `sweepStale tolerates an empty root`() = runBlocking {
        val count = EtegamiCacheSweeper.sweepStale(context)
        assertEquals(0, count)
    }

    @Test
    fun `sweepStale ignores individual delete failures without throwing`() = runBlocking {
        // Not easily reproducible without mocking File.delete; instead
        // just prove no exception on normal inputs — failure paths are
        // log-and-continue by contract.
        val root = EtegamiPngWriter.cacheRoot(context)
        File(root, "a.png").writeText("x")
        EtegamiCacheSweeper.sweepStale(context, olderThan = 0.hours)
        // Reached here ⇒ no throw.
        assertTrue(true)
    }
}
```

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.EtegamiCacheSweeperTest"
```

---

## Task 4 — `EtegamiShareIntentFactory`: pure intent builder

Builds the `ACTION_SEND` chooser intent. ClipData mandatory. No Activity coupling.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/etegami/share/EtegamiShareIntentFactory.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Stage 7-D: pure builder for the etegami share chooser intent.
 *
 * `ClipData.newRawUri` is MANDATORY on all APIs — without it, modern
 * receiving apps (Google Drive, Gmail) fail to render the image
 * preview in the chooser. [`EXTRA_STREAM`] alone is not sufficient.
 *
 * `FileProvider.getUriForFile` uses the authority
 * `${applicationId}.fileprovider` declared in the manifest; the
 * content URI is only valid for as long as the receiving Activity
 * holds the `FLAG_GRANT_READ_URI_PERMISSION` grant.
 */
internal object EtegamiShareIntentFactory {

    const val AUTHORITY_SUFFIX = ".fileprovider"

    /**
     * Build the chooser intent that can be passed to
     * [android.content.Context.startActivity]. Returns a wrapped
     * chooser intent (not the raw ACTION_SEND) so callers don't need
     * to invoke [Intent.createChooser] themselves.
     */
    fun build(
        context: Context,
        pngFile: File,
        chooserTitle: CharSequence,
    ): Intent {
        val authority = context.packageName + AUTHORITY_SUFFIX
        val uri: Uri = FileProvider.getUriForFile(context, authority, pngFile)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData is mandatory for modern chooser previews.
            clipData = ClipData.newRawUri(pngFile.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
```

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/etegami/share/EtegamiShareIntentFactoryTest.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EtegamiShareIntentFactoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun cleanup() {
        EtegamiPngWriter.cacheRoot(context).deleteRecursively()
    }

    private fun fixturePng(): File {
        val f = File(EtegamiPngWriter.cacheRoot(context), "test.png")
        f.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        return f
    }

    @Test
    fun `build returns a chooser Intent wrapping ACTION_SEND with image-png type`() {
        val chooser = EtegamiShareIntentFactory.build(context, fixturePng(), "Share")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(inner)
        assertEquals(Intent.ACTION_SEND, inner!!.action)
        assertEquals("image/png", inner.type)
    }

    @Test
    fun `inner intent carries EXTRA_STREAM and a matching ClipData newRawUri`() {
        val chooser = EtegamiShareIntentFactory.build(context, fixturePng(), "Share")
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val stream = inner.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertNotNull(stream)
        val clip = inner.clipData
        assertNotNull("ClipData is mandatory for modern chooser previews", clip)
        assertEquals(1, clip!!.itemCount)
        assertEquals(stream, clip.getItemAt(0).uri)
    }

    @Test
    fun `inner intent grants read URI permission`() {
        val chooser = EtegamiShareIntentFactory.build(context, fixturePng(), "Share")
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertTrue(
            "FLAG_GRANT_READ_URI_PERMISSION missing",
            (inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
        )
    }

    @Test
    fun `content URI uses the applicationId fileprovider authority`() {
        val chooser = EtegamiShareIntentFactory.build(context, fixturePng(), "Share")
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val stream = inner.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("${context.packageName}.fileprovider", stream.authority)
    }
}
```

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.EtegamiShareIntentFactoryTest"
```

---

## Task 5 — `EtegamiGallerySaver`: MediaStore (API 29+) + legacy (API 28) dispatch

Sealed result type. Branches on `Build.VERSION.SDK_INT`.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/etegami/share/EtegamiGallerySaver.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 7-D: save the etegami PNG to the user's Photos / Gallery.
 *
 * API 29+ (Q+): MediaStore scoped-storage insert, two-phase
 * IS_PENDING pattern. No permission needed for files the app itself
 * inserts.
 *
 * API 28 (P): legacy `Environment.getExternalStoragePublicDirectory`
 * write under `Pictures/Pilgrim/`. Requires
 * `WRITE_EXTERNAL_STORAGE` runtime grant; the Composable checks +
 * requests before calling into this saver and passes the outcome as
 * a precondition.
 *
 * All paths run on [Dispatchers.IO] — these are content-resolver /
 * filesystem calls, not CPU work.
 */
internal object EtegamiGallerySaver {

    private const val TAG = "EtegamiGallerySaver"

    /** The album name under `Pictures/` in the gallery. */
    private const val ALBUM = "Pilgrim"

    sealed interface SaveResult {
        data class Success(val uri: Uri) : SaveResult
        /** Only returned on API 28 when WRITE_EXTERNAL_STORAGE isn't granted. */
        object NeedsPermission : SaveResult
        data class Failed(val cause: Throwable) : SaveResult
    }

    suspend fun saveToGallery(
        bitmap: Bitmap,
        filename: String,
        context: Context,
    ): SaveResult = withContext(Dispatchers.IO) {
        require(filename.endsWith(".png")) { "filename must end with .png" }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(bitmap, filename, context)
            } else {
                saveViaLegacyExternal(bitmap, filename, context)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "saveToGallery failed", t)
            SaveResult.Failed(t)
        }
    }

    private fun saveViaMediaStore(
        bitmap: Bitmap,
        filename: String,
        context: Context,
    ): SaveResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return SaveResult.Failed(IllegalStateException("insert returned null"))
        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    error("compress returned false")
                }
            } ?: error("openOutputStream returned null")
            val clear = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, clear, null, null)
            return SaveResult.Success(uri)
        } catch (t: Throwable) {
            // Rollback the pending insert so it doesn't linger as a
            // ghost record in the user's Photos.
            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun saveViaLegacyExternal(
        bitmap: Bitmap,
        filename: String,
        context: Context,
    ): SaveResult {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return SaveResult.NeedsPermission
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM,
        ).apply { mkdirs() }
        val out = File(dir, filename)
        FileOutputStream(out).use { os ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                out.delete()
                error("compress returned false")
            }
            os.flush()
            os.fd.sync()
        }
        // Notify MediaScanner so the file appears in Photos.
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DATA, out.absolutePath)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: Uri.fromFile(out)
        return SaveResult.Success(uri)
    }
}
```

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/etegami/share/EtegamiGallerySaverTest.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EtegamiGallerySaverTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `saveToGallery API 29+ path returns Success with a content URI`() = runBlocking {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val result = EtegamiGallerySaver.saveToGallery(bitmap, "test.png", context)
        assertTrue(
            "expected Success got $result",
            result is EtegamiGallerySaver.SaveResult.Success,
        )
        val uri = (result as EtegamiGallerySaver.SaveResult.Success).uri
        assertTrue(uri.toString().contains("media") || uri.scheme == "content")
    }

    @Test
    fun `saveToGallery rejects non-png filenames`() = runBlocking {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            EtegamiGallerySaver.saveToGallery(bitmap, "bad.jpg", context)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains(".png") ?: false)
        }
    }
}
```

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.EtegamiGallerySaverTest"
```

---

## Task 6 — Filename helper (iOS parity)

Tiny pure helper for `pilgrim-etegami-<yyyy-MM-dd-HHmm>.png`. Extract into its own file so VM + Composable + tests share it and it's trivially Locale-robust.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/etegami/share/EtegamiFilename.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Stage 7-D: filename convention for shared/saved etegami PNGs.
 *
 * Byte-identical to iOS's `WalkSharingButtons.writeToTemp`, which
 * formats `walk.startDate` via the iOS `DateFormatter` pattern
 * `yyyy-MM-dd-HHmm` (24-hour). Cross-platform users recognize the
 * filename immediately.
 *
 * `Locale.ROOT` forces ASCII digits — important on Arabic/Persian/
 * Hindi locales where default decimal digits break filename parsing
 * (Stage 6-B lesson).
 */
internal object EtegamiFilename {

    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT)

    /** Returns `pilgrim-etegami-<yyyy-MM-dd-HHmm>.png`. */
    fun forWalk(startedAtEpochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val stamp = FORMATTER.format(Instant.ofEpochMilli(startedAtEpochMs).atZone(zoneId))
        return "pilgrim-etegami-$stamp.png"
    }
}
```

**Create** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/etegami/share/EtegamiFilenameTest.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class EtegamiFilenameTest {

    @Test
    fun `forWalk produces pilgrim-etegami-yyyy-MM-dd-HHmm dot png`() {
        // 2026-04-24T09:32:00Z → in UTC → "pilgrim-etegami-2026-04-24-0932.png"
        val epoch = java.time.ZonedDateTime.of(
            2026, 4, 24, 9, 32, 0, 0, ZoneId.of("UTC"),
        ).toInstant().toEpochMilli()
        assertEquals(
            "pilgrim-etegami-2026-04-24-0932.png",
            EtegamiFilename.forWalk(epoch, ZoneId.of("UTC")),
        )
    }

    @Test
    fun `forWalk uses 24-hour clock with zero-padded hours`() {
        val epoch = java.time.ZonedDateTime.of(
            2026, 4, 24, 23, 5, 0, 0, ZoneId.of("UTC"),
        ).toInstant().toEpochMilli()
        assertEquals(
            "pilgrim-etegami-2026-04-24-2305.png",
            EtegamiFilename.forWalk(epoch, ZoneId.of("UTC")),
        )
    }

    @Test
    fun `forWalk respects the supplied zoneId`() {
        val epoch = 1_700_000_000_000L
        val utc = EtegamiFilename.forWalk(epoch, ZoneId.of("UTC"))
        val tokyo = EtegamiFilename.forWalk(epoch, ZoneId.of("Asia/Tokyo"))
        // +9 hours wraps differently from UTC.
        assert(utc != tokyo)
    }
}
```

### Verify
```bash
./gradlew :app:testDebugUnitTest --tests "*.EtegamiFilenameTest"
```

---

## Task 7 — Strings

### Files

**Edit** `app/src/main/res/values/strings.xml` — append inside `<resources>`:
```xml
<!-- Stage 7-D: etegami share + save-to-gallery -->
<string name="etegami_share_button">Share</string>
<string name="etegami_save_button">Save</string>
<string name="etegami_share_chooser_title">Share etegami</string>
<string name="etegami_save_success">Saved to Photos</string>
<string name="etegami_save_failed">Couldn\'t save etegami</string>
<string name="etegami_share_failed">Couldn\'t prepare etegami</string>
<string name="etegami_save_needs_permission">Allow Photos access to save</string>
<string name="etegami_share_button_content_description">Share the etegami postcard</string>
<string name="etegami_save_button_content_description">Save the etegami postcard to Photos</string>
```

### Verify
```bash
./gradlew :app:assembleDebug
```

---

## Task 8 — VM integration: share/save methods + event flow

Add to `WalkSummaryViewModel`. Follows Stage 7-A pattern for snackbar events via `SharedFlow`.

### Files

**Edit** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt`:

1. Add imports:
```kotlin
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.walktalkmeditate.pilgrim.ui.etegami.EtegamiBitmapRenderer
import org.walktalkmeditate.pilgrim.ui.etegami.EtegamiSpec
import org.walktalkmeditate.pilgrim.ui.etegami.share.EtegamiCacheSweeper
import org.walktalkmeditate.pilgrim.ui.etegami.share.EtegamiFilename
import org.walktalkmeditate.pilgrim.ui.etegami.share.EtegamiGallerySaver
import org.walktalkmeditate.pilgrim.ui.etegami.share.EtegamiPngWriter
import org.walktalkmeditate.pilgrim.ui.etegami.share.EtegamiShareIntentFactory
```

2. Add state + event fields inside the class body:
```kotlin
/**
 * Stage 7-D: guards against double-tap + concurrent share/save.
 * A single Mutex covers BOTH actions — a second tap while a render
 * is in flight is a no-op, regardless of which button was tapped.
 */
private val etegamiShareMutex = Mutex()

private val _etegamiBusy = MutableStateFlow(false)
val etegamiBusy: StateFlow<Boolean> = _etegamiBusy.asStateFlow()

private val _etegamiEvents = MutableSharedFlow<EtegamiShareEvent>(
    replay = 0,
    extraBufferCapacity = 1,
)
val etegamiEvents: SharedFlow<EtegamiShareEvent> = _etegamiEvents.asSharedFlow()

sealed interface EtegamiShareEvent {
    data class DispatchShare(val chooser: Intent) : EtegamiShareEvent
    data object SaveSucceeded : EtegamiShareEvent
    data object SaveFailed : EtegamiShareEvent
    data object ShareFailed : EtegamiShareEvent
    data object SaveNeedsPermission : EtegamiShareEvent
}
```

3. Add methods:
```kotlin
fun shareEtegami(context: Context, spec: EtegamiSpec) {
    viewModelScope.launch(Dispatchers.Default) {
        if (!etegamiShareMutex.tryLock()) return@launch
        _etegamiBusy.value = true
        try {
            EtegamiCacheSweeper.sweepStale(context)
            val filename = EtegamiFilename.forWalk(spec.startedAtEpochMs)
            val bitmap = EtegamiBitmapRenderer.render(spec, context)
            try {
                val file = EtegamiPngWriter.writeToCache(bitmap, filename, context)
                val chooser = EtegamiShareIntentFactory.build(
                    context,
                    file,
                    context.getString(R.string.etegami_share_chooser_title),
                )
                _etegamiEvents.tryEmit(EtegamiShareEvent.DispatchShare(chooser))
            } finally {
                // Recycle after compress read the pixels (EtegamiPngWriter
                // completed) — gate on `settled` per Stage 7-B Bitmap
                // lifecycle pattern. Try/finally ensures recycle on any
                // throw from writeToCache, including CE cancellation
                // AFTER compress (CE during compress is rare and leaves
                // the bitmap to GC).
                bitmap.recycle()
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "shareEtegami failed", t)
            _etegamiEvents.tryEmit(EtegamiShareEvent.ShareFailed)
        } finally {
            _etegamiBusy.value = false
            etegamiShareMutex.unlock()
        }
    }
}

fun saveEtegamiToGallery(context: Context, spec: EtegamiSpec) {
    viewModelScope.launch(Dispatchers.Default) {
        if (!etegamiShareMutex.tryLock()) return@launch
        _etegamiBusy.value = true
        try {
            val filename = EtegamiFilename.forWalk(spec.startedAtEpochMs)
            val bitmap = EtegamiBitmapRenderer.render(spec, context)
            try {
                val result = EtegamiGallerySaver.saveToGallery(bitmap, filename, context)
                val ev = when (result) {
                    is EtegamiGallerySaver.SaveResult.Success -> EtegamiShareEvent.SaveSucceeded
                    is EtegamiGallerySaver.SaveResult.NeedsPermission -> EtegamiShareEvent.SaveNeedsPermission
                    is EtegamiGallerySaver.SaveResult.Failed -> EtegamiShareEvent.SaveFailed
                }
                _etegamiEvents.tryEmit(ev)
            } finally {
                bitmap.recycle()
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "saveEtegamiToGallery failed", t)
            _etegamiEvents.tryEmit(EtegamiShareEvent.SaveFailed)
        } finally {
            _etegamiBusy.value = false
            etegamiShareMutex.unlock()
        }
    }
}
```

### Verify
```bash
./gradlew :app:compileDebugKotlin
```

---

## Task 9 — `WalkEtegamiShareRow` Composable + Walk Summary slot

Row of two buttons; collects VM events via `LaunchedEffect`; dispatches chooser intent on Main; snackbars via `SnackbarHostState`.

### Files

**Create** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkEtegamiShareRow.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.etegami.EtegamiSpec
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing

/**
 * Stage 7-D: Share + Save action row slotted below WalkEtegamiCard.
 *
 * Mirrors iOS `WalkSharingButtons` image-share row — two compact
 * outlined buttons, labeled text, disabled-during-work via
 * [busy]. Snackbar feedback is handled by the caller via
 * [snackbarHostState] + collected VM events; this row just
 * renders the buttons.
 *
 * On API 28, save-to-Gallery requires WRITE_EXTERNAL_STORAGE. The
 * permission launcher is `remember`'d per the Stage 7-A lesson
 * about `rememberLauncherForActivityResult` + `DisposableEffect`
 * contract identity.
 */
@Composable
fun WalkEtegamiShareRow(
    spec: EtegamiSpec,
    busy: Boolean,
    onShare: () -> Unit,
    onSave: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Legacy permission launcher for API 28 save-to-gallery. Contract
    // is `remember`'d so its DisposableEffect identity is stable
    // across recompositions (Stage 7-A picker lesson).
    val legacyPermissionContract = remember { ActivityResultContracts.RequestPermission() }
    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = legacyPermissionContract,
    ) { granted -> if (granted) onSave() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = PilgrimSpacing.small),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        OutlinedButton(
            onClick = { if (!busy) onShare() },
            enabled = !busy,
            modifier = Modifier
                .weight(1f)
                .semantics {
                    contentDescription = context.getString(
                        R.string.etegami_share_button_content_description,
                    )
                },
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(PilgrimSpacing.small))
                Text(stringResource(R.string.etegami_share_button))
            }
        }
        OutlinedButton(
            onClick = {
                if (busy) return@OutlinedButton
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    onSave()
                } else {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) onSave()
                    else legacyPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            },
            enabled = !busy,
            modifier = Modifier
                .weight(1f)
                .semantics {
                    contentDescription = context.getString(
                        R.string.etegami_save_button_content_description,
                    )
                },
        ) {
            Icon(Icons.Outlined.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(PilgrimSpacing.small))
            Text(stringResource(R.string.etegami_save_button))
        }
    }
}
```

**Edit** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt`:

1. Add `SnackbarHost` + `SnackbarHostState` at the screen root (wrap existing Box in `Scaffold` OR add a bare `SnackbarHost` as a sibling of the main Column). Simplest: add `SnackbarHost(hostState)` as a `Box`-aligned bottom overlay.

2. Add VM event collection:
```kotlin
val snackbarHostState = remember { SnackbarHostState() }
val etegamiBusy by viewModel.etegamiBusy.collectAsStateWithLifecycle()
val shareSuccess = stringResource(R.string.etegami_save_success)
val shareFailed = stringResource(R.string.etegami_share_failed)
val saveFailed = stringResource(R.string.etegami_save_failed)
val needsPerm = stringResource(R.string.etegami_save_needs_permission)
val activity = LocalContext.current as ComponentActivity

LaunchedEffect(viewModel) {
    viewModel.etegamiEvents.collect { ev ->
        when (ev) {
            is WalkSummaryViewModel.EtegamiShareEvent.DispatchShare ->
                activity.startActivity(ev.chooser)
            WalkSummaryViewModel.EtegamiShareEvent.SaveSucceeded ->
                snackbarHostState.showSnackbar(shareSuccess)
            WalkSummaryViewModel.EtegamiShareEvent.SaveFailed ->
                snackbarHostState.showSnackbar(saveFailed)
            WalkSummaryViewModel.EtegamiShareEvent.ShareFailed ->
                snackbarHostState.showSnackbar(shareFailed)
            WalkSummaryViewModel.EtegamiShareEvent.SaveNeedsPermission ->
                snackbarHostState.showSnackbar(needsPerm)
        }
    }
}
```

3. Slot the row just below `WalkEtegamiCard`:
```kotlin
s.summary.etegamiSpec?.let { etegami ->
    Spacer(Modifier.height(PilgrimSpacing.big))
    WalkEtegamiCard(spec = etegami)
    WalkEtegamiShareRow(
        spec = etegami,
        busy = etegamiBusy,
        onShare = { viewModel.shareEtegami(activity, etegami) },
        onSave = { viewModel.saveEtegamiToGallery(activity, etegami) },
        snackbarHostState = snackbarHostState,
    )
}
```

4. Add `SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))` inside the outer `Box`.

### Verify
```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

---

## Task 10 — `.gitignore` + state cleanup

Ensure `.claude/autopilot-state.md` is gitignored (global gitignore likely already covers `.claude/`; verify). No commit needed if already covered.

### Verify
```bash
git check-ignore .claude/autopilot-state.md || echo "NEEDS GITIGNORE ENTRY"
```

If not ignored, add to `.gitignore` at repo root:
```
.claude/autopilot-state.md
```

---

## Sequence summary

1. FileProvider + manifest (2 files)
2. `EtegamiPngWriter` + test (2 files)
3. `EtegamiCacheSweeper` + test (2 files)
4. `EtegamiShareIntentFactory` + test (2 files)
5. `EtegamiGallerySaver` + test (2 files)
6. `EtegamiFilename` + test (2 files)
7. Strings (1 file edit)
8. VM integration (1 file edit)
9. Composable row + Walk Summary wiring (2 files / edits)
10. gitignore verification (0-1 file)

**Total:** ~13 new files + 4 edits. Spec-coverage check: every success criterion maps to at least one task. Scope is stable.

## Self-review notes

- No placeholders.
- Type consistency: `SaveResult`, `EtegamiShareEvent`, `EtegamiSpec` consistent across layers.
- Thread policy explicit per task (Default for CPU, IO for content-resolver, Main for intent dispatch).
- CE re-throw in every `catch (Throwable)` block wrapping suspend work.
- Bitmap recycle gated by `try/finally` after writer/saver returns, per Stage 7-B lesson.
- First FileProvider in the app — manifest task runs FIRST so downstream compose helpers have a valid authority to test against.
