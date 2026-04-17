# Stage 2-A — VoiceRecording data layer

## Context

Phase 2 of the Android port adds voice recording + transcription. Stage
2-A is the data layer only: one Room entity, one DAO, repository
methods, one schema version bump. No UI, no audio capture, no whisper.

Subsequent stages (2-B through 2-F) will stack onto these stable types:
`AudioRecord` capture writes `file_relative_path`, whisper.cpp writes
`transcription`, the summary UI binds to `getForWalk(...)`.

## Source of truth (iOS)

iOS `PilgrimV7.VoiceRecording` (CoreData) has:

| iOS field | Type | Note |
|---|---|---|
| `_uuid` | UUID? | iOS allows null; we'll require it |
| `_startDate` | Date | required, default epoch |
| `_endDate` | Date | required, default epoch |
| `_duration` | Double (seconds) | stored, not computed |
| `_fileRelativePath` | String | required |
| `_transcription` | String? | nullable until transcribed |
| `_wordsPerMinute` | Double? | computed post-transcription |
| `_isEnhanced` | Bool | whether AudioEnhancer has run |
| `_workout` | → Walk | many-to-one |

**iOS critical observation:** voice recordings are NOT pinned to GPS
coordinates. They carry timestamps and are correlated to the walk's
route samples after the fact. We match this — the port plan's mention
of "pinned to current GPS point" is imprecise; the iOS model doesn't
store lat/lng on the recording itself.

## Android shape

### Entity

`VoiceRecording` → table `voice_recordings`.

| Kotlin field | Column | Type | Nullability | Default |
|---|---|---|---|---|
| `id` | `id` | Long, `@PrimaryKey(autoGenerate)` | non-null | 0 |
| `uuid` | `uuid` | String | non-null, unique | `UUID.randomUUID().toString()` |
| `walkId` | `walk_id` | Long, `@ForeignKey(Walk, CASCADE)` | non-null | — |
| `startTimestamp` | `start_timestamp` | Long (ms epoch) | non-null | — |
| `endTimestamp` | `end_timestamp` | Long (ms epoch) | non-null | — |
| `durationMillis` | `duration_millis` | Long | non-null | — |
| `fileRelativePath` | `file_relative_path` | String | non-null | — |
| `transcription` | `transcription` | String | nullable | null |
| `wordsPerMinute` | `words_per_minute` | Double | nullable | null |
| `isEnhanced` | `is_enhanced` | Boolean | non-null | false |

**Units decision:** everything is `Long` milliseconds since epoch. Walks
are already ms-based (`start_timestamp Long NOT NULL`). iOS uses
seconds-as-Double but Kotlin should match Android Room conventions and
our own existing entities.

**Duration: stored vs computed.** iOS stores it. We match for query
simplicity — "total voice-note time per walk" becomes a simple `SUM(
duration_millis)` instead of a computed subtract. The 8 bytes of
redundancy per recording is cheaper than the arithmetic at read time.

### Indices

- `Index("walk_id")` — matches every other child-of-Walk entity. Needed
  for fast `getForWalk` and the cascade-delete scan.
- `Index("uuid", unique = true)` — matches existing convention;
  protects against double-insert during retry flows and gives us a
  stable cross-process identifier for export/share later.

No `Index("start_timestamp")` — iOS sorts recordings by `startDate`
scoped to a walk, and walks typically have < 20 recordings, so the
`walk_id` index is enough. If a recordings-across-all-walks screen ever
needs DESC ordering we can add it then.

### DAO (`VoiceRecordingDao`)

```kotlin
@Dao
interface VoiceRecordingDao {
    @Insert suspend fun insert(recording: VoiceRecording): Long
    @Update suspend fun update(recording: VoiceRecording)
    @Delete suspend fun delete(recording: VoiceRecording)

    @Query("SELECT * FROM voice_recordings WHERE id = :id")
    suspend fun getById(id: Long): VoiceRecording?

    @Query("SELECT * FROM voice_recordings WHERE walk_id = :walkId ORDER BY start_timestamp ASC")
    suspend fun getForWalk(walkId: Long): List<VoiceRecording>

    @Query("SELECT * FROM voice_recordings WHERE walk_id = :walkId ORDER BY start_timestamp ASC")
    fun observeForWalk(walkId: Long): Flow<List<VoiceRecording>>

    @Query("SELECT * FROM voice_recordings ORDER BY start_timestamp DESC")
    fun observeAll(): Flow<List<VoiceRecording>>

    @Query("SELECT COUNT(*) FROM voice_recordings WHERE walk_id = :walkId")
    suspend fun countForWalk(walkId: Long): Int
}
```

Matches the `RouteDataSampleDao` / `WalkEventDao` style: single-row
mutators return `Long`, `getForWalk` returns `List` (terminal), the
live `observeForWalk` returns `Flow` (for UI), and `observeAll` is a
preview for the future Recordings tab. `countForWalk` mirrors the
existing count pattern used in `RouteDataSampleDao`.

### Repository surface

Add to `WalkRepository`:

```kotlin
suspend fun recordVoice(recording: VoiceRecording): Long = voiceRecordingDao.insert(recording)
suspend fun updateVoiceRecording(recording: VoiceRecording) = voiceRecordingDao.update(recording)
suspend fun deleteVoiceRecording(recording: VoiceRecording) = voiceRecordingDao.delete(recording)
suspend fun getVoiceRecording(id: Long): VoiceRecording? = voiceRecordingDao.getById(id)
suspend fun voiceRecordingsFor(walkId: Long): List<VoiceRecording> = voiceRecordingDao.getForWalk(walkId)
fun observeVoiceRecordings(walkId: Long): Flow<List<VoiceRecording>> = voiceRecordingDao.observeForWalk(walkId)
fun observeAllVoiceRecordings(): Flow<List<VoiceRecording>> = voiceRecordingDao.observeAll()
suspend fun countVoiceRecordingsFor(walkId: Long): Int = voiceRecordingDao.countForWalk(walkId)
```

Naming mirrors `recordLocation` / `locationSamplesFor` / etc. No new
transactional flows at this stage — Stage 2-B will add the
"capture + persist" flow as a single service-layer method.

### Database version bump

`PilgrimDatabase` → version 2 with declared auto-migration:

```kotlin
@Database(
    entities = [..., VoiceRecording::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
```

- Add `voiceRecordingDao()` abstract accessor.
- `app/schemas/.../2.json` is generated by Room on build.
- No `Converters` changes — all fields are primitive.

**Why auto-migration, not `fallbackToDestructiveMigration`?**

pilgrim-android has zero shipped users today, but Stage 1-G has
demonstrated the app works end-to-end on real hardware. We're one
Google Play release away from real users. Establishing proper
migration discipline from v1 → v2 prevents a "we'll add migrations
later" habit that becomes a data-loss incident. The auto-migration
Room generates for "add a new table" is trivial — cost is near-zero.

### DI wiring

Add one provider to `DatabaseModule`:

```kotlin
@Provides
fun provideVoiceRecordingDao(db: PilgrimDatabase): VoiceRecordingDao = db.voiceRecordingDao()
```

### Tests (`VoiceRecordingDataLayerTest`, Robolectric + in-memory Room)

Following the same pattern as `WalkDataLayerTest`:

1. `insert + read back` — insert one, verify all fields round-trip
2. `getForWalk orders by start_timestamp ascending` — insert three
   with shuffled start times, verify order
3. `walk deletion cascades to recordings` — insert walk + 2
   recordings, delete walk, verify `getForWalk` returns empty
4. `recordings for different walks are isolated` — insert two walks +
   two recordings each, verify `getForWalk` filters correctly
5. `unique uuid constraint is enforced` — insert one, attempt to
   insert duplicate uuid, expect `SQLiteConstraintException`
6. `observeForWalk emits on insert` — Turbine-style flow assertion
7. `countForWalk returns accurate total`

Existing `WalkDataLayerTest` covers cascade-delete semantics for other
child entities — we mirror that shape.

## What this stage does NOT do

- No UI.
- No audio capture (`AudioRecord`, `MediaRecorder`).
- No whisper.cpp integration.
- No file-path creation logic — `fileRelativePath` is a plain string
  field that the Stage 2-B capture layer will populate.
- No deletion cascade for the audio file on disk — file lifecycle is
  a Stage 2-B concern. If a row is deleted via Stage 2-A's `delete()`
  before 2-B lands, the orphaned file stays on disk (acceptable
  transient state; covered by 2-B).

## Success criteria

- `./gradlew assembleDebug lintDebug testDebugUnitTest` green
- `app/schemas/.../2.json` generated and committed
- Seven new unit tests all passing
- Zero existing tests broken (91 → 98)
- Auto-migration from v1 → v2 verified via Room's generated schema
  (Room itself refuses to build if the migration is invalid)
