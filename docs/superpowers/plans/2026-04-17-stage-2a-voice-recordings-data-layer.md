# Stage 2-A Implementation Plan — VoiceRecording data layer

**Spec:** [2026-04-17-stage-2a-voice-recordings-data-layer-design.md](../specs/2026-04-17-stage-2a-voice-recordings-data-layer-design.md)

**Test command (run after every task):**
```bash
JAVA_HOME=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8 ANDROID_HOME=/Users/rubberduck/Library/Android/sdk PATH=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8/bin:$PATH ./gradlew assembleDebug lintDebug testDebugUnitTest
```

Task order matters: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8. Each task ends in a
green build/test cycle before moving on.

---

## Task 1 — Create `VoiceRecording` entity

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/VoiceRecording.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "voice_recordings",
    foreignKeys = [
        ForeignKey(
            entity = Walk::class,
            parentColumns = ["id"],
            childColumns = ["walk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("walk_id"),
        Index("uuid", unique = true),
    ],
)
data class VoiceRecording(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "walk_id")
    val walkId: Long,
    @ColumnInfo(name = "start_timestamp")
    val startTimestamp: Long,
    @ColumnInfo(name = "end_timestamp")
    val endTimestamp: Long,
    @ColumnInfo(name = "duration_millis")
    val durationMillis: Long,
    @ColumnInfo(name = "file_relative_path")
    val fileRelativePath: String,
    val transcription: String? = null,
    @ColumnInfo(name = "words_per_minute")
    val wordsPerMinute: Double? = null,
    @ColumnInfo(name = "is_enhanced")
    val isEnhanced: Boolean = false,
)
```

**Verify:** file compiles in isolation (just having it here does
nothing yet — it must be registered with `@Database` next).

---

## Task 2 — Create `VoiceRecordingDao`

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/VoiceRecordingDao.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

@Dao
interface VoiceRecordingDao {
    @Insert
    suspend fun insert(recording: VoiceRecording): Long

    @Update
    suspend fun update(recording: VoiceRecording)

    @Delete
    suspend fun delete(recording: VoiceRecording)

    @Query("SELECT * FROM voice_recordings WHERE id = :id")
    suspend fun getById(id: Long): VoiceRecording?

    @Query("SELECT * FROM voice_recordings WHERE walk_id = :walkId ORDER BY start_timestamp ASC")
    suspend fun getForWalk(walkId: Long): List<VoiceRecording>

    /**
     * Live-updating flow for the Walk Summary recordings list. Emits
     * a new List on every insert/update/delete that touches the given
     * walk's recordings.
     */
    @Query("SELECT * FROM voice_recordings WHERE walk_id = :walkId ORDER BY start_timestamp ASC")
    fun observeForWalk(walkId: Long): Flow<List<VoiceRecording>>

    /**
     * All recordings across all walks, newest first. For a future
     * Recordings tab; not read by Stage 2-A.
     */
    @Query("SELECT * FROM voice_recordings ORDER BY start_timestamp DESC")
    fun observeAll(): Flow<List<VoiceRecording>>

    @Query("SELECT COUNT(*) FROM voice_recordings WHERE walk_id = :walkId")
    suspend fun countForWalk(walkId: Long): Int
}
```

---

## Task 3 — Bump `PilgrimDatabase` to v2 with auto-migration

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/PilgrimDatabase.kt` (modify)

Replace the entire file with:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import org.walktalkmeditate.pilgrim.data.dao.ActivityIntervalDao
import org.walktalkmeditate.pilgrim.data.dao.AltitudeSampleDao
import org.walktalkmeditate.pilgrim.data.dao.RouteDataSampleDao
import org.walktalkmeditate.pilgrim.data.dao.VoiceRecordingDao
import org.walktalkmeditate.pilgrim.data.dao.WalkDao
import org.walktalkmeditate.pilgrim.data.dao.WalkEventDao
import org.walktalkmeditate.pilgrim.data.dao.WaypointDao
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.Waypoint

@Database(
    entities = [
        Walk::class,
        RouteDataSample::class,
        AltitudeSample::class,
        WalkEvent::class,
        ActivityInterval::class,
        Waypoint::class,
        VoiceRecording::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
    ],
)
@TypeConverters(Converters::class)
abstract class PilgrimDatabase : RoomDatabase() {
    abstract fun walkDao(): WalkDao
    abstract fun routeDataSampleDao(): RouteDataSampleDao
    abstract fun altitudeSampleDao(): AltitudeSampleDao
    abstract fun walkEventDao(): WalkEventDao
    abstract fun activityIntervalDao(): ActivityIntervalDao
    abstract fun waypointDao(): WaypointDao
    abstract fun voiceRecordingDao(): VoiceRecordingDao

    companion object {
        const val DATABASE_NAME = "pilgrim.db"
    }
}
```

**Verify:** `./gradlew kspDebugKotlin` should generate
`app/schemas/org.walktalkmeditate.pilgrim.data.PilgrimDatabase/2.json`.
Commit that file alongside the code.

---

## Task 4 — Add DAO provider to `DatabaseModule`

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/di/DatabaseModule.kt` (modify)

Add import:

```kotlin
import org.walktalkmeditate.pilgrim.data.dao.VoiceRecordingDao
```

Add provider at the bottom of the module object (after
`provideWaypointDao`):

```kotlin
@Provides
fun provideVoiceRecordingDao(db: PilgrimDatabase): VoiceRecordingDao = db.voiceRecordingDao()
```

---

## Task 5 — Extend `WalkRepository` with voice-recording APIs

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt` (modify)

Add imports:

```kotlin
import org.walktalkmeditate.pilgrim.data.dao.VoiceRecordingDao
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
```

Extend the constructor parameter list (add after `waypointDao`):

```kotlin
private val voiceRecordingDao: VoiceRecordingDao,
```

Add the repository methods at the end of the class (after
`waypointsFor`):

```kotlin
suspend fun recordVoice(recording: VoiceRecording): Long =
    voiceRecordingDao.insert(recording)

suspend fun updateVoiceRecording(recording: VoiceRecording) =
    voiceRecordingDao.update(recording)

suspend fun deleteVoiceRecording(recording: VoiceRecording) =
    voiceRecordingDao.delete(recording)

suspend fun getVoiceRecording(id: Long): VoiceRecording? =
    voiceRecordingDao.getById(id)

suspend fun voiceRecordingsFor(walkId: Long): List<VoiceRecording> =
    voiceRecordingDao.getForWalk(walkId)

fun observeVoiceRecordings(walkId: Long): Flow<List<VoiceRecording>> =
    voiceRecordingDao.observeForWalk(walkId)

fun observeAllVoiceRecordings(): Flow<List<VoiceRecording>> =
    voiceRecordingDao.observeAll()

suspend fun countVoiceRecordingsFor(walkId: Long): Int =
    voiceRecordingDao.countForWalk(walkId)
```

---

## Task 6 — Update manual WalkRepository construction in tests

Four test files construct `WalkRepository` directly. Each needs the
new `voiceRecordingDao = db.voiceRecordingDao()` parameter.

**Files:**
- `app/src/test/java/org/walktalkmeditate/pilgrim/data/WalkDataLayerTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkControllerTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModelTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModelTest.kt`

In each, find the existing `WalkRepository(...)` construction and add:

```kotlin
voiceRecordingDao = db.voiceRecordingDao(),
```

**Placement:** after `waypointDao = db.waypointDao(),`. All four files
use named arguments so the addition is trivial.

---

## Task 7 — New unit tests for the voice-recording data layer

**File:** `app/src/test/java/org/walktalkmeditate/pilgrim/data/VoiceRecordingDataLayerTest.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceRecordingDataLayerTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WalkRepository(
            database = db,
            walkDao = db.walkDao(),
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = db.walkEventDao(),
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun makeWalk(start: Long = 1_000L) =
        repository.startWalk(startTimestamp = start)

    private fun makeRecording(
        walkId: Long,
        start: Long,
        duration: Long = 5_000L,
        transcription: String? = null,
    ) = VoiceRecording(
        walkId = walkId,
        startTimestamp = start,
        endTimestamp = start + duration,
        durationMillis = duration,
        fileRelativePath = "recordings/walk-$walkId/$start.wav",
        transcription = transcription,
    )

    @Test
    fun `insert and read back preserves all fields`() = runTest {
        val walk = makeWalk()
        val input = makeRecording(walk.id, start = 2_000L, transcription = "hello world").copy(
            wordsPerMinute = 120.0,
            isEnhanced = true,
        )

        val id = repository.recordVoice(input)
        val read = repository.getVoiceRecording(id)

        assertNotNull(read)
        assertEquals(walk.id, read?.walkId)
        assertEquals(2_000L, read?.startTimestamp)
        assertEquals(7_000L, read?.endTimestamp)
        assertEquals(5_000L, read?.durationMillis)
        assertEquals("recordings/walk-${walk.id}/2000.wav", read?.fileRelativePath)
        assertEquals("hello world", read?.transcription)
        assertEquals(120.0, read?.wordsPerMinute ?: 0.0, 0.001)
        assertEquals(true, read?.isEnhanced)
    }

    @Test
    fun `getForWalk orders by start_timestamp ascending`() = runTest {
        val walk = makeWalk()
        repository.recordVoice(makeRecording(walk.id, start = 3_000L))
        repository.recordVoice(makeRecording(walk.id, start = 1_000L))
        repository.recordVoice(makeRecording(walk.id, start = 2_000L))

        val list = repository.voiceRecordingsFor(walk.id)

        assertEquals(listOf(1_000L, 2_000L, 3_000L), list.map { it.startTimestamp })
    }

    @Test
    fun `walk deletion cascades to its recordings`() = runTest {
        val walk = makeWalk()
        repository.recordVoice(makeRecording(walk.id, start = 1_000L))
        repository.recordVoice(makeRecording(walk.id, start = 2_000L))

        repository.deleteWalk(walk)

        assertTrue(repository.voiceRecordingsFor(walk.id).isEmpty())
    }

    @Test
    fun `recordings from different walks are isolated by getForWalk`() = runTest {
        val walkA = makeWalk(start = 1_000L)
        val walkB = makeWalk(start = 10_000L)
        repository.recordVoice(makeRecording(walkA.id, start = 2_000L))
        repository.recordVoice(makeRecording(walkA.id, start = 3_000L))
        repository.recordVoice(makeRecording(walkB.id, start = 11_000L))

        assertEquals(2, repository.countVoiceRecordingsFor(walkA.id))
        assertEquals(1, repository.countVoiceRecordingsFor(walkB.id))
        assertEquals(
            setOf(2_000L, 3_000L),
            repository.voiceRecordingsFor(walkA.id).map { it.startTimestamp }.toSet(),
        )
    }

    @Test
    fun `duplicate uuid insert is rejected by the unique constraint`() = runTest {
        val walk = makeWalk()
        val first = makeRecording(walk.id, start = 1_000L)
        repository.recordVoice(first)
        val duplicate = first.copy(id = 0, startTimestamp = 2_000L)

        try {
            repository.recordVoice(duplicate)
            fail("expected SQLiteConstraintException for duplicate uuid")
        } catch (_: SQLiteConstraintException) {
            // expected
        }
    }

    @Test
    fun `observeForWalk emits updates on insert`() = runTest {
        val walk = makeWalk()

        repository.observeVoiceRecordings(walk.id).test {
            assertEquals(emptyList<VoiceRecording>(), awaitItem())

            repository.recordVoice(makeRecording(walk.id, start = 1_000L))
            assertEquals(1, awaitItem().size)

            repository.recordVoice(makeRecording(walk.id, start = 2_000L))
            assertEquals(2, awaitItem().size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `update writes transcription back onto the row`() = runTest {
        val walk = makeWalk()
        val id = repository.recordVoice(makeRecording(walk.id, start = 1_000L))
        val original = repository.getVoiceRecording(id) ?: fail("recording should exist")
        (original as VoiceRecording) // smart cast for the null path

        repository.updateVoiceRecording(
            original.copy(transcription = "late bloom", wordsPerMinute = 90.0),
        )
        val updated = repository.getVoiceRecording(id)

        assertEquals("late bloom", updated?.transcription)
        assertEquals(90.0, updated?.wordsPerMinute ?: 0.0, 0.001)
    }

    @Test
    fun `delete removes a recording from subsequent queries`() = runTest {
        val walk = makeWalk()
        val id = repository.recordVoice(makeRecording(walk.id, start = 1_000L))
        val recording = repository.getVoiceRecording(id)!!

        repository.deleteVoiceRecording(recording)

        assertNull(repository.getVoiceRecording(id))
        assertEquals(0, repository.countVoiceRecordingsFor(walk.id))
    }
}
```

Count: 8 test cases (I added "update writes transcription" and
"delete removes from queries" on top of the 7 listed in the spec —
they're cheap and cover the update/delete DAO methods that are
otherwise unexercised).

---

## Task 8 — Full CI gate

Run:

```bash
JAVA_HOME=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8 ANDROID_HOME=/Users/rubberduck/Library/Android/sdk PATH=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8/bin:$PATH ./gradlew assembleDebug lintDebug testDebugUnitTest
```

**Expected:**
- `BUILD SUCCESSFUL`
- Tests: 99 passing (91 existing + 8 new), 0 failing
- `app/schemas/org.walktalkmeditate.pilgrim.data.PilgrimDatabase/2.json` exists and contains the `voice_recordings` table

Verify the schema JSON was generated:

```bash
ls app/schemas/org.walktalkmeditate.pilgrim.data.PilgrimDatabase/
# Expect: 1.json 2.json
```

Commit everything in one atomic commit once green:

```
feat(data): Stage 2-A — VoiceRecording entity + DAO + repository

Room v2 with auto-migration adds the voice_recordings table and the
associated DAO + repository methods. Mirrors iOS PilgrimV7.VoiceRecording
shape: uuid, start/end/duration ms, file path, optional transcription +
wpm, is_enhanced flag, CASCADE delete from Walk.

No UI, no audio capture, no whisper — this stage ships stable types
that Stage 2-B (AudioRecord capture) and Stage 2-D (whisper.cpp
transcription) will bind to.
```
