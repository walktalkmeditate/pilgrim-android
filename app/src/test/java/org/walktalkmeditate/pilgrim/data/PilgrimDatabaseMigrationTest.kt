// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.honor.HonorCardStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorFinishKind
import org.walktalkmeditate.pilgrim.data.honor.HonorMomentStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.HonorWalkMarkerEntity
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimWalk
import org.walktalkmeditate.pilgrim.data.pilgrim.builder.PilgrimPackageConverter
import org.walktalkmeditate.pilgrim.data.pilgrim.builder.WalkExportBundle
import org.walktalkmeditate.pilgrim.data.walk.WalkDistanceCalculator
import org.walktalkmeditate.pilgrim.data.walk.WalkMetricsCache
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase

/**
 * The 2→3 … 5→6 tests exercise each Migration's `migrate` directly
 * against a hand-built minimal SQLite DB. `MigrationTestHelper` is
 * avoided: under Robolectric the instrumentation context's asset loader
 * can't find the exported schema JSON (the helper is designed for
 * on-device `androidTest` runs).
 *
 * The 8→9 and 9→10 tests, and the chains from 6 and 7, build the starting version
 * from its exported schema
 * with [MigrationTestDatabases] and open it through `Room.databaseBuilder`
 * with the production [PilgrimDatabase.MIGRATIONS], so Room's schema
 * validation and identity check run exactly as on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimDatabaseMigrationTest {

    private val dbName = "pilgrim-migration-test.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        // Clean up on-disk DB between tests so test order is irrelevant.
        context.deleteDatabase(dbName)
    }

    private fun openV2Shape(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(V2_VERSION) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v2 shape: just the walks table MIGRATION_2_3's
                    // FK depends on. No need to replicate every v2 table —
                    // we only verify the ADDED table + FK here.
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `walks` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`uuid` TEXT NOT NULL, " +
                            "`start_timestamp` INTEGER NOT NULL, " +
                            "`end_timestamp` INTEGER, " +
                            "`intention` TEXT, " +
                            "`favicon` TEXT, " +
                            "`notes` TEXT)",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // Not used — we invoke the migration explicitly.
                }
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        return helper.writableDatabase
    }

    @Test
    fun `migration 2 to 3 adds walk_photos with the expected columns and indices`() {
        val db = openV2Shape()
        try {
            PilgrimDatabase.MIGRATION_2_3.migrate(db)

            db.query("PRAGMA table_info(`walk_photos`)").use { cursor ->
                val columns = mutableMapOf<String, Int>()
                while (cursor.moveToNext()) {
                    val nameIdx = cursor.getColumnIndexOrThrow("name")
                    val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
                    columns[cursor.getString(nameIdx)] = cursor.getInt(notNullIdx)
                }
                assertEquals(
                    setOf("id", "uuid", "walk_id", "photo_uri", "pinned_at", "taken_at"),
                    columns.keys,
                )
                // taken_at is the only nullable column.
                assertEquals(0, columns["taken_at"])
                assertEquals(1, columns["uuid"])
                assertEquals(1, columns["walk_id"])
                assertEquals(1, columns["photo_uri"])
                assertEquals(1, columns["pinned_at"])
            }

            db.query("PRAGMA index_list(`walk_photos`)").use { cursor ->
                val indices = mutableMapOf<String, Int>()
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                val uniqueIdx = cursor.getColumnIndexOrThrow("unique")
                while (cursor.moveToNext()) {
                    indices[cursor.getString(nameIdx)] = cursor.getInt(uniqueIdx)
                }
                assertEquals(0, indices["index_walk_photos_walk_id"])
                assertEquals(1, indices["index_walk_photos_uuid"])
            }

            db.query("PRAGMA foreign_key_list(`walk_photos`)").use { cursor ->
                assertTrue("expected a foreign key", cursor.moveToFirst())
                val table = cursor.getString(cursor.getColumnIndexOrThrow("table"))
                val from = cursor.getString(cursor.getColumnIndexOrThrow("from"))
                val to = cursor.getString(cursor.getColumnIndexOrThrow("to"))
                val onDelete = cursor.getString(cursor.getColumnIndexOrThrow("on_delete"))
                assertEquals("walks", table)
                assertEquals("walk_id", from)
                assertEquals("id", to)
                assertEquals("CASCADE", onDelete)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migrated walk_photos accepts an insert and respects the FK cascade`() {
        // After migration, exercise the table end-to-end via raw SQL so
        // we validate the FK cascade wiring without needing the full v2
        // schema (Room's open-time validator would otherwise reject our
        // minimal v2 shape). This catches SQL defects in MIGRATION_2_3
        // that `PRAGMA table_info` alone would miss — wrong FK action,
        // missing AUTOINCREMENT, UNIQUE constraint dropped, etc.
        val db = openV2Shape()
        try {
            PilgrimDatabase.MIGRATION_2_3.migrate(db)

            // Enable enforcement explicitly — SQLite is off by default
            // on new connections and a broken FK clause wouldn't fire
            // otherwise.
            db.execSQL("PRAGMA foreign_keys = ON")

            val walkId = db.insert(
                "walks",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                android.content.ContentValues().apply {
                    put("uuid", "w-uuid")
                    put("start_timestamp", 1_000L)
                },
            )
            assertTrue("expected walk row id > 0, got $walkId", walkId > 0)

            val photoId = db.insert(
                "walk_photos",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                android.content.ContentValues().apply {
                    put("uuid", "p-uuid")
                    put("walk_id", walkId)
                    put("photo_uri", "content://x/1")
                    put("pinned_at", 2_000L)
                },
            )
            assertNotNull(photoId)
            assertTrue(photoId > 0)

            // Cascade: deleting the walk should remove the photo.
            db.delete("walks", "id = ?", arrayOf<Any>(walkId))
            db.query("SELECT COUNT(*) FROM walk_photos WHERE walk_id = $walkId").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 3 to 4 adds analysis columns, all nullable`() {
        // Walk through 2→3 first so we have a walk_photos table, then
        // apply 3→4 and assert the three new columns landed nullable.
        val db = openV2Shape()
        try {
            PilgrimDatabase.MIGRATION_2_3.migrate(db)
            PilgrimDatabase.MIGRATION_3_4.migrate(db)

            db.query("PRAGMA table_info(`walk_photos`)").use { cursor ->
                val columns = mutableMapOf<String, Pair<String, Int>>()
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                val typeIdx = cursor.getColumnIndexOrThrow("type")
                val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
                while (cursor.moveToNext()) {
                    columns[cursor.getString(nameIdx)] =
                        cursor.getString(typeIdx) to cursor.getInt(notNullIdx)
                }
                assertEquals(
                    "expected v4 columns to be a superset of v3",
                    setOf(
                        "id", "uuid", "walk_id", "photo_uri", "pinned_at", "taken_at",
                        "top_label", "top_label_confidence", "analyzed_at",
                    ),
                    columns.keys,
                )
                assertEquals("TEXT" to 0, columns["top_label"])
                assertEquals("REAL" to 0, columns["top_label_confidence"])
                assertEquals("INTEGER" to 0, columns["analyzed_at"])
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 4 to 5 adds distance_meters + meditation_seconds, both nullable, and preserves existing rows`() {
        // Stage 11-A: walks table gains two nullable cache cols. The
        // migration is ALTER TABLE only (no row scan); pre-existing
        // walks must keep their data and report null for both new cols.
        // We build the v4 walks shape by hand (the helper-less pattern
        // used elsewhere in this file), insert id=42, run the migration,
        // and assert the new columns exist with correct affinity + null.
        val db = openV2Shape()
        try {
            db.execSQL("PRAGMA foreign_keys = ON")
            db.insert(
                "walks",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                android.content.ContentValues().apply {
                    put("id", 42L)
                    put("uuid", "abc-uuid")
                    put("start_timestamp", 1_000L)
                    put("end_timestamp", 5_000L)
                },
            )

            PilgrimDatabase.MIGRATION_4_5.migrate(db)

            db.query("PRAGMA table_info(`walks`)").use { cursor ->
                val columns = mutableMapOf<String, Pair<String, Int>>()
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                val typeIdx = cursor.getColumnIndexOrThrow("type")
                val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
                while (cursor.moveToNext()) {
                    columns[cursor.getString(nameIdx)] =
                        cursor.getString(typeIdx) to cursor.getInt(notNullIdx)
                }
                assertEquals(
                    "expected v5 walks columns to be a superset of v4",
                    setOf(
                        "id", "uuid", "start_timestamp", "end_timestamp",
                        "intention", "favicon", "notes",
                        "distance_meters", "meditation_seconds",
                    ),
                    columns.keys,
                )
                assertEquals("REAL" to 0, columns["distance_meters"])
                assertEquals("INTEGER" to 0, columns["meditation_seconds"])
            }

            db.query(
                "SELECT id, distance_meters, meditation_seconds FROM walks WHERE id = ?",
                arrayOf<Any>(42L),
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(42L, c.getLong(0))
                assertTrue("distance_meters should be null after migration", c.isNull(1))
                assertTrue("meditation_seconds should be null after migration", c.isNull(2))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migrated v5 walks accepts updates to the new cache cols`() {
        // Catches any SQL typo in MIGRATION_4_5 that PRAGMA alone would
        // miss (wrong type affinity, dropped NOT NULL, etc.) — same
        // pattern as the v3->v4 walk_photos write test above.
        val db = openV2Shape()
        try {
            db.execSQL("PRAGMA foreign_keys = ON")
            PilgrimDatabase.MIGRATION_4_5.migrate(db)

            val walkId = db.insert(
                "walks",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                android.content.ContentValues().apply {
                    put("uuid", "w-uuid")
                    put("start_timestamp", 1_000L)
                },
            )
            db.update(
                "walks",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                android.content.ContentValues().apply {
                    put("distance_meters", 1234.56)
                    put("meditation_seconds", 600L)
                },
                "id = ?",
                arrayOf<Any>(walkId),
            )
            db.query(
                "SELECT distance_meters, meditation_seconds FROM walks WHERE id = ?",
                arrayOf<Any>(walkId),
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1234.56, c.getDouble(0), 0.0001)
                assertEquals(600L, c.getLong(1))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 5 to 6 adds four nullable weather cols and preserves existing rows`() {
        // Stage 12-A: walks table gains four nullable weather cache cols.
        // Pure ALTER TABLE ADD COLUMN — no row scan, no defaults, all
        // nullable. Pre-existing walks must keep their data and report
        // null for every new col. Build the v5 walks shape (v2 + the
        // 4->5 ALTERs), insert id=99, run the migration, assert columns
        // exist with correct affinity + nullable, and the row survives.
        val db = openV2Shape()
        try {
            PilgrimDatabase.MIGRATION_4_5.migrate(db)
            db.execSQL("PRAGMA foreign_keys = ON")
            db.insert(
                "walks",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                android.content.ContentValues().apply {
                    put("id", 99L)
                    put("uuid", "weather-uuid")
                    put("start_timestamp", 1_000L)
                    put("end_timestamp", 5_000L)
                    put("distance_meters", 1500.0)
                    put("meditation_seconds", 300L)
                },
            )

            PilgrimDatabase.MIGRATION_5_6.migrate(db)

            db.query("PRAGMA table_info(`walks`)").use { cursor ->
                val columns = mutableMapOf<String, Pair<String, Int>>()
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                val typeIdx = cursor.getColumnIndexOrThrow("type")
                val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
                while (cursor.moveToNext()) {
                    columns[cursor.getString(nameIdx)] =
                        cursor.getString(typeIdx) to cursor.getInt(notNullIdx)
                }
                assertEquals(
                    "expected v6 walks columns to be a superset of v5",
                    setOf(
                        "id", "uuid", "start_timestamp", "end_timestamp",
                        "intention", "favicon", "notes",
                        "distance_meters", "meditation_seconds",
                        "weather_condition", "weather_temperature",
                        "weather_humidity", "weather_wind_speed",
                    ),
                    columns.keys,
                )
                assertEquals("TEXT" to 0, columns["weather_condition"])
                assertEquals("REAL" to 0, columns["weather_temperature"])
                assertEquals("REAL" to 0, columns["weather_humidity"])
                assertEquals("REAL" to 0, columns["weather_wind_speed"])
            }

            db.query(
                "SELECT id, distance_meters, meditation_seconds, " +
                    "weather_condition, weather_temperature, " +
                    "weather_humidity, weather_wind_speed " +
                    "FROM walks WHERE id = ?",
                arrayOf<Any>(99L),
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(99L, c.getLong(0))
                assertEquals(1500.0, c.getDouble(1), 0.0001)
                assertEquals(300L, c.getLong(2))
                assertTrue("weather_condition should be null after migration", c.isNull(3))
                assertTrue("weather_temperature should be null after migration", c.isNull(4))
                assertTrue("weather_humidity should be null after migration", c.isNull(5))
                assertTrue("weather_wind_speed should be null after migration", c.isNull(6))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migrated v6 walks accepts updates to the new weather cols`() {
        // Catches any SQL typo in MIGRATION_5_6 that PRAGMA alone would
        // miss (wrong type affinity, dropped NOT NULL, etc.) — same
        // pattern as the v4->v5 cache-cols write test above.
        val db = openV2Shape()
        try {
            db.execSQL("PRAGMA foreign_keys = ON")
            PilgrimDatabase.MIGRATION_4_5.migrate(db)
            PilgrimDatabase.MIGRATION_5_6.migrate(db)

            val walkId = db.insert(
                "walks",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                android.content.ContentValues().apply {
                    put("uuid", "w-uuid")
                    put("start_timestamp", 1_000L)
                },
            )
            db.update(
                "walks",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                android.content.ContentValues().apply {
                    put("weather_condition", "clear")
                    put("weather_temperature", 18.5)
                    put("weather_humidity", 0.62)
                    put("weather_wind_speed", 3.4)
                },
                "id = ?",
                arrayOf<Any>(walkId),
            )
            db.query(
                "SELECT weather_condition, weather_temperature, " +
                    "weather_humidity, weather_wind_speed " +
                    "FROM walks WHERE id = ?",
                arrayOf<Any>(walkId),
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("clear", c.getString(0))
                assertEquals(18.5, c.getDouble(1), 0.0001)
                assertEquals(0.62, c.getDouble(2), 0.0001)
                assertEquals(3.4, c.getDouble(3), 0.0001)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migrated v4 walk_photos accepts an update-analysis write`() {
        // Prove the new columns are writable with the expected types —
        // catches a typo in MIGRATION_3_4 SQL that would otherwise only
        // surface at app-start on a real device.
        val db = openV2Shape()
        try {
            PilgrimDatabase.MIGRATION_2_3.migrate(db)
            PilgrimDatabase.MIGRATION_3_4.migrate(db)
            db.execSQL("PRAGMA foreign_keys = ON")

            val walkId = db.insert(
                "walks",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                android.content.ContentValues().apply {
                    put("uuid", "w-uuid")
                    put("start_timestamp", 1_000L)
                },
            )
            val photoId = db.insert(
                "walk_photos",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                android.content.ContentValues().apply {
                    put("uuid", "p-uuid")
                    put("walk_id", walkId)
                    put("photo_uri", "content://x/1")
                    put("pinned_at", 2_000L)
                },
            )
            db.update(
                "walk_photos",
                android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
                android.content.ContentValues().apply {
                    put("top_label", "Plant")
                    put("top_label_confidence", 0.91)
                    put("analyzed_at", 3_000L)
                },
                "id = ?",
                arrayOf<Any>(photoId),
            )
            db.query(
                "SELECT top_label, top_label_confidence, analyzed_at " +
                    "FROM walk_photos WHERE id = ?",
                arrayOf<Any>(photoId),
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Plant", c.getString(0))
                assertEquals(0.91, c.getDouble(1), 0.0001)
                assertEquals(3_000L, c.getLong(2))
            }
        } finally {
            db.close()
        }
    }

    // ---- 8 → 9: the #223 sittings repair, through Room's own open path ----

    @Test
    fun `a v8 database migrates through 9 and Room's identity check and keeps its data`() {
        MigrationTestDatabases.createAtVersion(context, dbName, version = 8) { db -> seedV8World(db) }

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(LATEST_VERSION.toLong(), db.longQuery("PRAGMA user_version"))
            assertEquals(
                MigrationTestDatabases.identityHash(LATEST_VERSION),
                db.stringQuery("SELECT identity_hash FROM room_master_table WHERE id = 42"),
            )
            assertEquals(MigrationTestDatabases.identityHash(8), MigrationTestDatabases.identityHash(9))

            assertEquals(WORLD_WALK_COUNT, db.longQuery("SELECT COUNT(*) FROM walks"))
            assertEquals(WORLD_INTERVAL_COUNT, db.longQuery("SELECT COUNT(*) FROM activity_intervals"))
            assertEquals(
                "only the two intervals-only walks gain events: 2 sittings on walk 1, 1 on walk 5",
                WORLD_EVENT_COUNT + 6,
                db.longQuery("SELECT COUNT(*) FROM walk_events"),
            )
            runBlocking {
                val repairedWalk = room.walkDao().getById(IOS_MEDITATION_WALK)!!
                assertEquals("ios-meditation", repairedWalk.uuid)
                assertEquals("calm", repairedWalk.intention)
                assertEquals(iosRouteDistance(), repairedWalk.distanceMeters!!, 0.0)
                assertEquals(2, room.routeDataSampleDao().getForWalk(IOS_MEDITATION_WALK).size)
                val waypoint = room.waypointDao().getForWalk(IOS_MEDITATION_WALK).single()
                assertEquals("signpost.right.fill", waypoint.icon)
                assertEquals("Walked their way: Camino", waypoint.label)
                assertEquals("hello", room.voiceRecordingDao().getForWalk(IOS_MEDITATION_WALK).single().transcription)
                assertEquals("content://photo/1", room.walkPhotoDao().getForWalk(IOS_MEDITATION_WALK).single().photoUri)
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `an intervals-only meditation walk gains a START and an END per sitting`() {
        MigrationTestDatabases.createAtVersion(context, dbName, version = 8) { db -> seedV8World(db) }

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            val events = runBlocking { room.walkEventDao().getForWalk(IOS_MEDITATION_WALK) }
            assertEquals(
                "both MEDITATING rows become pairs; the zero-length row and the WALKING row add nothing",
                listOf(
                    WalkEventType.MEDITATION_START to 600_000L,
                    WalkEventType.MEDITATION_END to 1_200_000L,
                    WalkEventType.MEDITATION_START to 2_000_000L,
                    WalkEventType.MEDITATION_END to 2_300_000L,
                ),
                events.map { it.eventType to it.timestamp },
            )
            val uuids = runBlocking { room.walkEventDao().getForWalk(UNFINISHED_WALK) }.map { it.uuid } +
                events.map { it.uuid }
            assertEquals(uuids.size, uuids.toSet().size)
            assertTrue(uuids.toString(), uuids.all { CANONICAL_UUID.matches(it) })
        } finally {
            room.close()
        }
    }

    @Test
    fun `the backfill recomputes each repaired walk to its real sitting`() {
        MigrationTestDatabases.createAtVersion(context, dbName, version = 8) { db -> seedV8World(db) }

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            runBlocking {
                val dao = room.walkDao()
                assertNull(dao.getById(IOS_MEDITATION_WALK)!!.meditationSeconds)
                assertNull(dao.getById(NATIVE_MEDITATION_WALK)!!.meditationSeconds)
                assertNull(dao.getById(BOTH_SOURCES_WALK)!!.meditationSeconds)

                runMetricsBackfill(room)

                assertEquals(900L, dao.getById(IOS_MEDITATION_WALK)!!.meditationSeconds)
                assertEquals(
                    "the native walk's cached 0 becomes its real sitting",
                    600L,
                    dao.getById(NATIVE_MEDITATION_WALK)!!.meditationSeconds,
                )
                assertEquals("an interval plus its events count once", 300L, dao.getById(BOTH_SOURCES_WALK)!!.meditationSeconds)
                assertEquals(iosRouteDistance(), dao.getById(IOS_MEDITATION_WALK)!!.distanceMeters!!, 0.0)
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `walks without sittings keep their cached totals`() {
        MigrationTestDatabases.createAtVersion(context, dbName, version = 8) { db -> seedV8World(db) }

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            runBlocking {
                val dao = room.walkDao()
                assertEquals("iOS WALKING-only walk", 0L, dao.getById(IOS_WALKING_WALK)!!.meditationSeconds)
                assertEquals("archived stub", 420L, dao.getById(ARCHIVED_WALK)!!.meditationSeconds)
                assertEquals("archived stub", 1_234.0, dao.getById(ARCHIVED_WALK)!!.distanceMeters!!, 0.0)
                assertEquals("plain native walk", 0L, dao.getById(PLAIN_NATIVE_WALK)!!.meditationSeconds)
                assertTrue(room.walkEventDao().getForWalk(IOS_WALKING_WALK).isEmpty())
                assertTrue(room.walkEventDao().getForWalk(ARCHIVED_WALK).isEmpty())
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `an unfinished walk gains its events and stays uncached`() {
        MigrationTestDatabases.createAtVersion(context, dbName, version = 8) { db -> seedV8World(db) }

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            runBlocking {
                assertNull(room.walkDao().getById(UNFINISHED_WALK)!!.meditationSeconds)
                assertEquals(
                    listOf(WalkEventType.MEDITATION_START to 21_000_000L, WalkEventType.MEDITATION_END to 21_100_000L),
                    room.walkEventDao().getForWalk(UNFINISHED_WALK).map { it.eventType to it.timestamp },
                )
                runMetricsBackfill(room)
                assertNull("the backfill skips a walk in progress", room.walkDao().getById(UNFINISHED_WALK)!!.meditationSeconds)
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `a walk with both a MEDITATING row and its events gains nothing and exports one sitting`() {
        MigrationTestDatabases.createAtVersion(context, dbName, version = 8) { db -> seedV8World(db) }

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            runBlocking {
                assertEquals(2, room.walkEventDao().getForWalk(BOTH_SOURCES_WALK).size)
                runMetricsBackfill(room)
                val exported = export(room, BOTH_SOURCES_WALK)
                assertEquals(listOf("meditation"), exported.activities.map { it.type })
                assertEquals(300.0, exported.stats.meditateDuration, 0.0)
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `migrated iOS walks re-export their unknown activities unchanged`() {
        MigrationTestDatabases.createAtVersion(context, dbName, version = 8) { db -> seedV8World(db) }

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            runBlocking {
                runMetricsBackfill(room)
                val walkingOnly = export(room, IOS_WALKING_WALK)
                assertEquals(
                    listOf(
                        Triple("unknown", 30_000_000L, 30_600_000L),
                        Triple("unknown", 31_000_000L, 31_200_000L),
                    ),
                    walkingOnly.activities.map { Triple(it.type, it.startDate.toEpochMilli(), it.endDate.toEpochMilli()) },
                )
                assertEquals(0.0, walkingOnly.stats.meditateDuration, 0.0)

                val meditated = export(room, IOS_MEDITATION_WALK)
                assertEquals(
                    listOf(
                        Triple("unknown", 0L, 600_000L),
                        Triple("meditation", 600_000L, 1_200_000L),
                        Triple("meditation", 2_000_000L, 2_300_000L),
                    ),
                    meditated.activities.map { Triple(it.type, it.startDate.toEpochMilli(), it.endDate.toEpochMilli()) },
                )
                assertEquals(900.0, meditated.stats.meditateDuration, 0.0)
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `running migration 8 to 9 again adds nothing and recomputes the same totals`() {
        MigrationTestDatabases.createAtVersion(context, dbName, version = 8) { db -> seedV8World(db) }

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            val db = room.openHelper.writableDatabase
            runBlocking { runMetricsBackfill(room) }
            val eventsAfterFirstRun = db.longQuery("SELECT COUNT(*) FROM walk_events")
            val totalsAfterFirstRun = runBlocking { room.walkDao().getAll() }.associate { it.id to it.meditationSeconds }

            db.beginTransaction()
            try {
                PilgrimDatabase.MIGRATION_8_9.migrate(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            runBlocking { runMetricsBackfill(room) }

            assertEquals(eventsAfterFirstRun, db.longQuery("SELECT COUNT(*) FROM walk_events"))
            assertEquals(totalsAfterFirstRun, runBlocking { room.walkDao().getAll() }.associate { it.id to it.meditationSeconds })
        } finally {
            room.close()
        }
    }

    @Test
    fun `overlapping and duplicate MEDITATING rows backfill and export as one merged sitting`() {
        MigrationTestDatabases.createAtVersion(context, dbName, version = 8) { db ->
            db.insertWalk(SINGLE_WALK, "overlapping", start = 0L, end = 3_600_000L, distance = 0.0, meditationSeconds = 1_800L)
            db.insertInterval(SINGLE_WALK, 600_000L, 1_200_000L, "MEDITATING")
            db.insertInterval(SINGLE_WALK, 900_000L, 1_500_000L, "MEDITATING")
            db.insertInterval(SINGLE_WALK, 600_000L, 1_200_000L, "MEDITATING", uuid = "interval-duplicate")
        }

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            runBlocking {
                assertEquals(
                    "the repair writes a pair per row; merging happens when sittings are derived",
                    6,
                    room.walkEventDao().getForWalk(SINGLE_WALK).size,
                )
                runMetricsBackfill(room)
                assertEquals(900L, room.walkDao().getById(SINGLE_WALK)!!.meditationSeconds)
                val exported = export(room, SINGLE_WALK)
                assertEquals(
                    listOf(Triple("meditation", 600_000L, 1_500_000L)),
                    exported.activities.map { Triple(it.type, it.startDate.toEpochMilli(), it.endDate.toEpochMilli()) },
                )
                assertEquals(900.0, exported.stats.meditateDuration, 0.0)
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `MEDITATING rows beside a lone MEDITATION_START are left to the events`() {
        // The repair fills only walks with no meditation event at all: any
        // such event means walk_events already holds the walk's sittings.
        MigrationTestDatabases.createAtVersion(context, dbName, version = 8) { db ->
            db.insertWalk(SINGLE_WALK, "lone-start", start = 0L, end = 3_600_000L, distance = 0.0, meditationSeconds = 900L)
            db.insertInterval(SINGLE_WALK, 600_000L, 1_500_000L, "MEDITATING")
            db.insertEvent(SINGLE_WALK, 3_000_000L, "MEDITATION_START")
        }

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            runBlocking {
                assertEquals(
                    listOf(WalkEventType.MEDITATION_START to 3_000_000L),
                    room.walkEventDao().getForWalk(SINGLE_WALK).map { it.eventType to it.timestamp },
                )
                runMetricsBackfill(room)
                assertEquals(
                    "the open sitting closes at the walk's end; the row's 900 s is never read",
                    600L,
                    room.walkDao().getById(SINGLE_WALK)!!.meditationSeconds,
                )
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `a v7 database migrates to the latest version through the shared migrations array`() {
        assertMigratesToLatestFrom(version = 7)
    }

    @Test
    fun `a v6 database migrates to the latest version through the shared migrations array`() {
        assertMigratesToLatestFrom(version = 6)
    }

    /**
     * Replays [version]'s schema with one walk and one photo, then opens it
     * through [PilgrimDatabase.MIGRATIONS]: a migration missing from the
     * array, or one whose result differs from the current entities, fails
     * Room's open here.
     */
    private fun assertMigratesToLatestFrom(version: Int) {
        MigrationTestDatabases.createAtVersion(context, dbName, version = version) { db ->
            db.insertWalk(SINGLE_WALK, "chain", start = 0L, end = 3_600_000L, distance = 12.5, meditationSeconds = 0L)
            db.insertRow(
                "walk_photos",
                "uuid" to "ph-chain", "walk_id" to SINGLE_WALK, "photo_uri" to "content://photo/chain",
                "pinned_at" to 1_000L,
            )
        }

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(LATEST_VERSION.toLong(), db.longQuery("PRAGMA user_version"))
            assertEquals(
                MigrationTestDatabases.identityHash(LATEST_VERSION),
                db.stringQuery("SELECT identity_hash FROM room_master_table WHERE id = 42"),
            )
            runBlocking {
                val walk = room.walkDao().getById(SINGLE_WALK)!!
                assertEquals(12.5, walk.distanceMeters!!, 0.0)
                assertNull(walk.steps)
                val photo = room.walkPhotoDao().getForWalk(SINGLE_WALK).single()
                assertNull(photo.capturedLat)
                assertNull(photo.capturedLng)
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `a schema 10 replay opens through Room without any migration`() {
        // Proves the helper replays a schema exactly as Room would create
        // it: Room's validation would reject a table that differs.
        MigrationTestDatabases.createAtVersion(context, dbName, version = LATEST_VERSION)

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            assertEquals(LATEST_VERSION.toLong(), room.openHelper.writableDatabase.longQuery("PRAGMA user_version"))
        } finally {
            room.close()
        }
    }

    // ---- 9 → 10: the live Honor tables and the walk marker (U14) ----

    @Test
    fun `a v9 database migrates to 10 through Room's identity check and keeps its data`() {
        MigrationTestDatabases.createAtVersion(context, dbName, version = 9) { db -> seedV8World(db) }

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(10L, db.longQuery("PRAGMA user_version"))
            assertEquals(
                MigrationTestDatabases.identityHash(10),
                db.stringQuery("SELECT identity_hash FROM room_master_table WHERE id = 42"),
            )
            assertEquals(WORLD_WALK_COUNT, db.longQuery("SELECT COUNT(*) FROM walks"))
            assertEquals(WORLD_INTERVAL_COUNT, db.longQuery("SELECT COUNT(*) FROM activity_intervals"))
            assertEquals(
                "9 → 10 only adds tables: the #223 repair ran at 8 → 9, not again",
                WORLD_EVENT_COUNT,
                db.longQuery("SELECT COUNT(*) FROM walk_events"),
            )
            runBlocking {
                val walk = room.walkDao().getById(IOS_MEDITATION_WALK)!!
                assertEquals("ios-meditation", walk.uuid)
                assertEquals(900L, walk.meditationSeconds)
                assertEquals("signpost.right.fill", room.waypointDao().getForWalk(IOS_MEDITATION_WALK).single().icon)
                assertEquals("hello", room.voiceRecordingDao().getForWalk(IOS_MEDITATION_WALK).single().transcription)
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `the migrated Honor tables start empty and take their rows`() {
        MigrationTestDatabases.createAtVersion(context, dbName, version = 9) { db -> seedV8World(db) }

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            val db = room.openHelper.writableDatabase
            HONOR_TABLES.forEach { table ->
                assertEquals("$table starts empty", 0L, db.longQuery("SELECT COUNT(*) FROM $table"))
            }
            runBlocking {
                val dao = room.honorDao()
                dao.insertSession(
                    HonorSessionEntity(
                        walkId = UNFINISHED_WALK,
                        wayId = "walk:0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50",
                        sourceKind = HonorSourceKind.OWN_WALK,
                        voicesEnabled = true,
                        softTapEnabled = false,
                    ),
                )
                dao.upsertMomentState(HonorMomentStateEntity(UNFINISHED_WALK, "voice-1", reachedAt = 1L))
                dao.markCardTouched(UNFINISHED_WALK, "voice-1")
                dao.markCardDismissed(UNFINISHED_WALK, "voice-1", atMillis = 3L)
                dao.insertMarker(HonorWalkMarkerEntity("unfinished", finishedAt = 2L, finishKind = HonorFinishKind.CLEAN))

                assertEquals(HonorPhase.WALKING, dao.getSession(UNFINISHED_WALK)!!.phase)
                assertEquals(1, dao.getMomentStates(UNFINISHED_WALK).size)
                assertEquals(
                    "the card row keeps its dismissal's time",
                    HonorCardStateEntity(UNFINISHED_WALK, "voice-1", dismissedAtMillis = 3L, touched = true),
                    dao.getCardStates(UNFINISHED_WALK).single(),
                )

                room.walkDao().deleteById(UNFINISHED_WALK)

                assertNull("the live rows cascade from the walk", dao.getSession(UNFINISHED_WALK))
                assertTrue(dao.getMomentStates(UNFINISHED_WALK).isEmpty())
                assertTrue(dao.getCardStates(UNFINISHED_WALK).isEmpty())
                assertNotNull("the marker has no foreign key", dao.getMarker("unfinished"))
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `running migration 9 to 10 again changes nothing`() {
        MigrationTestDatabases.createAtVersion(context, dbName, version = 9)

        val room = MigrationTestDatabases.openWithProductionMigrations(context, dbName)
        try {
            val db = room.openHelper.writableDatabase
            val schemaBefore = db.stringQuery(HONOR_SCHEMA_SQL)
            db.beginTransaction()
            try {
                PilgrimDatabase.MIGRATION_9_10.migrate(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            assertEquals(schemaBefore, db.stringQuery(HONOR_SCHEMA_SQL))
        } finally {
            room.close()
        }
    }

    /**
     * One v8 database holding every shape the repair must tell apart.
     * Timestamps are epoch millis; each walk owns a separate stretch.
     */
    private fun seedV8World(db: SupportSQLiteDatabase) {
        // iOS import before v9: sittings only as MEDITATING rows, cached
        // 900 s by the old interval-based cache. Also an iOS "unknown"
        // (WALKING) row and a zero-length MEDITATING row.
        db.insertWalk(
            IOS_MEDITATION_WALK, "ios-meditation", start = 0L, end = 3_600_000L,
            distance = iosRouteDistance(), meditationSeconds = 900L, intention = "calm",
        )
        db.insertInterval(IOS_MEDITATION_WALK, 0L, 600_000L, "WALKING")
        db.insertInterval(IOS_MEDITATION_WALK, 600_000L, 1_200_000L, "MEDITATING")
        db.insertInterval(IOS_MEDITATION_WALK, 2_000_000L, 2_300_000L, "MEDITATING")
        db.insertInterval(IOS_MEDITATION_WALK, 3_000_000L, 3_000_000L, "MEDITATING")
        iosRouteSamples().forEach { db.insertRouteSample(it) }
        db.insertRow(
            "waypoints",
            "uuid" to "wp-1", "walk_id" to IOS_MEDITATION_WALK, "timestamp" to 3_500_000L,
            "latitude" to 42.88, "longitude" to -8.54,
            "label" to "Walked their way: Camino", "icon" to "signpost.right.fill",
        )
        db.insertRow(
            "voice_recordings",
            "uuid" to "vr-1", "walk_id" to IOS_MEDITATION_WALK, "start_timestamp" to 100_000L,
            "end_timestamp" to 110_000L, "duration_millis" to 10_000L, "file_relative_path" to "",
            "transcription" to "hello", "is_enhanced" to 0,
        )
        db.insertRow(
            "walk_photos",
            "uuid" to "ph-1", "walk_id" to IOS_MEDITATION_WALK, "photo_uri" to "content://photo/1",
            "pinned_at" to 3_000_000L,
        )

        // Native walk: the sitting lives only in events; the cache summed
        // the empty interval table and stored 0.
        db.insertWalk(NATIVE_MEDITATION_WALK, "native", start = 10_000_000L, end = 13_600_000L, distance = 0.0, meditationSeconds = 0L)
        db.insertEvent(NATIVE_MEDITATION_WALK, 10_600_000L, "MEDITATION_START")
        db.insertEvent(NATIVE_MEDITATION_WALK, 11_200_000L, "MEDITATION_END")

        // iOS import with only "unknown" activities (stored WALKING).
        db.insertWalk(IOS_WALKING_WALK, "ios-walking", start = 30_000_000L, end = 33_600_000L, distance = 0.0, meditationSeconds = 0L)
        db.insertInterval(IOS_WALKING_WALK, 30_000_000L, 30_600_000L, "WALKING")
        db.insertInterval(IOS_WALKING_WALK, 31_000_000L, 31_200_000L, "WALKING")

        // A sitting held twice: a MEDITATING row and its events.
        db.insertWalk(BOTH_SOURCES_WALK, "both", start = 40_000_000L, end = 43_600_000L, distance = 0.0, meditationSeconds = 300L)
        db.insertInterval(BOTH_SOURCES_WALK, 40_600_000L, 40_900_000L, "MEDITATING")
        db.insertEvent(BOTH_SOURCES_WALK, 40_600_000L, "MEDITATION_START")
        db.insertEvent(BOTH_SOURCES_WALK, 40_900_000L, "MEDITATION_END")

        // Still in progress (no end), intervals only. Uncached, as
        // production never caches an unfinished walk.
        db.insertWalk(UNFINISHED_WALK, "unfinished", start = 20_000_000L, end = null, distance = null, meditationSeconds = null)
        db.insertInterval(UNFINISHED_WALK, 21_000_000L, 21_100_000L, "MEDITATING")

        // Archive-stripped: surface stats survive, children are gone.
        db.insertWalk(ARCHIVED_WALK, "archived", start = 50_000_000L, end = 53_600_000L, distance = 1_234.0, meditationSeconds = 420L)

        // Native walk with a pause and no sitting.
        db.insertWalk(PLAIN_NATIVE_WALK, "plain", start = 60_000_000L, end = 63_600_000L, distance = 0.0, meditationSeconds = 0L)
        db.insertEvent(PLAIN_NATIVE_WALK, 61_000_000L, "PAUSED")
        db.insertEvent(PLAIN_NATIVE_WALK, 61_100_000L, "RESUMED")
    }

    private fun iosRouteSamples(): List<RouteDataSample> = listOf(
        RouteDataSample(uuid = "rs-1", walkId = IOS_MEDITATION_WALK, timestamp = 1_000L, latitude = 42.88, longitude = -8.54),
        RouteDataSample(uuid = "rs-2", walkId = IOS_MEDITATION_WALK, timestamp = 60_000L, latitude = 42.881, longitude = -8.54),
    )

    private fun iosRouteDistance(): Double = WalkDistanceCalculator.computeDistanceMeters(iosRouteSamples())

    /**
     * Drains stale walks exactly as [org.walktalkmeditate.pilgrim.data.walk.WalkMetricsBackfillCoordinator]
     * does — same predicate, same [WalkMetricsCache] — without its
     * Flow plumbing.
     */
    private suspend fun runMetricsBackfill(room: PilgrimDatabase) {
        val cache = WalkMetricsCache(repositoryFor(room), room.walkDao(), room.walkEventDao())
        repeat(room.walkDao().getAll().size) {
            val stale = room.walkDao().getAll().firstOrNull { walk ->
                walk.endTimestamp != null && (walk.distanceMeters == null || walk.meditationSeconds == null)
            } ?: return
            cache.computeAndPersist(stale.id)
        }
    }

    private suspend fun export(room: PilgrimDatabase, walkId: Long): PilgrimWalk {
        val repository = repositoryFor(room)
        val bundle = WalkExportBundle(
            walk = room.walkDao().getById(walkId)!!,
            routeSamples = repository.locationSamplesFor(walkId),
            altitudeSamples = repository.altitudeSamplesFor(walkId),
            walkEvents = repository.eventsFor(walkId),
            activityIntervals = repository.activityIntervalsFor(walkId),
            waypoints = repository.waypointsFor(walkId),
            voiceRecordings = repository.voiceRecordingsFor(walkId),
            walkPhotos = room.walkPhotoDao().getForWalk(walkId),
        )
        return PilgrimPackageConverter.convert(bundle, includePhotos = false).walk
    }

    private fun repositoryFor(room: PilgrimDatabase) = WalkRepository(
        database = room,
        walkDao = room.walkDao(),
        routeDao = room.routeDataSampleDao(),
        altitudeDao = room.altitudeSampleDao(),
        walkEventDao = room.walkEventDao(),
        activityIntervalDao = room.activityIntervalDao(),
        waypointDao = room.waypointDao(),
        voiceRecordingDao = room.voiceRecordingDao(),
        walkPhotoDao = room.walkPhotoDao(),
    )

    private fun SupportSQLiteDatabase.insertWalk(
        id: Long,
        uuid: String,
        start: Long,
        end: Long?,
        distance: Double?,
        meditationSeconds: Long?,
        intention: String? = null,
    ) = insertRow(
        "walks",
        "id" to id, "uuid" to uuid, "start_timestamp" to start, "end_timestamp" to end,
        "distance_meters" to distance, "meditation_seconds" to meditationSeconds, "intention" to intention,
    )

    private fun SupportSQLiteDatabase.insertEvent(walkId: Long, timestamp: Long, type: String) = insertRow(
        "walk_events",
        "uuid" to "event-$walkId-$timestamp-$type", "walk_id" to walkId, "timestamp" to timestamp, "event_type" to type,
    )

    private fun SupportSQLiteDatabase.insertInterval(
        walkId: Long,
        start: Long,
        end: Long,
        type: String,
        uuid: String = "interval-$walkId-$start-$end",
    ) = insertRow(
        "activity_intervals",
        "uuid" to uuid, "walk_id" to walkId,
        "start_timestamp" to start, "end_timestamp" to end, "activity_type" to type,
    )

    private fun SupportSQLiteDatabase.insertRouteSample(sample: RouteDataSample) = insertRow(
        "route_data_samples",
        "uuid" to sample.uuid, "walk_id" to sample.walkId, "timestamp" to sample.timestamp,
        "latitude" to sample.latitude, "longitude" to sample.longitude,
    )

    private fun SupportSQLiteDatabase.insertRow(table: String, vararg columns: Pair<String, Any?>) {
        val values = ContentValues()
        for ((column, value) in columns) {
            when (value) {
                null -> values.putNull(column)
                is Long -> values.put(column, value)
                is Int -> values.put(column, value)
                is Double -> values.put(column, value)
                is String -> values.put(column, value)
                else -> error("unsupported value for $table.$column: $value")
            }
        }
        insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
    }

    private fun SupportSQLiteDatabase.longQuery(sql: String): Long =
        query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.stringQuery(sql: String): String =
        query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private companion object {
        private const val V2_VERSION = 2

        const val IOS_MEDITATION_WALK = 1L
        const val NATIVE_MEDITATION_WALK = 2L
        const val IOS_WALKING_WALK = 3L
        const val BOTH_SOURCES_WALK = 4L
        const val UNFINISHED_WALK = 5L
        const val ARCHIVED_WALK = 6L
        const val PLAIN_NATIVE_WALK = 7L
        const val WORLD_WALK_COUNT = 7L
        const val WORLD_INTERVAL_COUNT = 8L
        const val WORLD_EVENT_COUNT = 6L

        /** The one walk of a test that seeds its own database, not [seedV8World]. */
        const val SINGLE_WALK = 1L

        val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        const val LATEST_VERSION = 10

        val HONOR_TABLES = listOf("honor_sessions", "honor_moment_states", "honor_card_states", "honor_walk_markers")

        val HONOR_SCHEMA_SQL =
            "SELECT group_concat(sql, ';') FROM (SELECT sql FROM sqlite_master " +
                "WHERE name LIKE 'honor_%' ORDER BY name)"
    }
}
