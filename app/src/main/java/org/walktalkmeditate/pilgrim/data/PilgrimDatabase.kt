// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import org.walktalkmeditate.pilgrim.data.dao.ActivityIntervalDao
import org.walktalkmeditate.pilgrim.data.dao.AltitudeSampleDao
import org.walktalkmeditate.pilgrim.data.dao.RouteDataSampleDao
import org.walktalkmeditate.pilgrim.data.dao.VoiceRecordingDao
import org.walktalkmeditate.pilgrim.data.dao.WalkDao
import org.walktalkmeditate.pilgrim.data.dao.WalkEventDao
import org.walktalkmeditate.pilgrim.data.dao.WalkPhotoDao
import org.walktalkmeditate.pilgrim.data.dao.WaypointDao
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.data.honor.HonorCardStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorDao
import org.walktalkmeditate.pilgrim.data.honor.HonorMomentStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorWalkMarkerEntity
import org.walktalkmeditate.pilgrim.data.seek.SeekDao
import org.walktalkmeditate.pilgrim.data.seek.SeekSessionEntity

@Database(
    entities = [
        Walk::class,
        RouteDataSample::class,
        AltitudeSample::class,
        WalkEvent::class,
        ActivityInterval::class,
        Waypoint::class,
        VoiceRecording::class,
        WalkPhoto::class,
        HonorSessionEntity::class,
        HonorMomentStateEntity::class,
        HonorCardStateEntity::class,
        HonorWalkMarkerEntity::class,
        SeekSessionEntity::class,
    ],
    version = 11,
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
    abstract fun walkPhotoDao(): WalkPhotoDao
    abstract fun honorDao(): HonorDao
    abstract fun seekDao(): SeekDao

    companion object {
        const val DATABASE_NAME = "pilgrim.db"

        /**
         * Stage 7-A: adds `walk_photos` for the photo reliquary. Written
         * explicitly (rather than AutoMigration) so the SQL is visible
         * and the migration test harness can exercise the exact
         * production script. Column types + defaults + FK cascade must
         * match what Room generates for v3 — verified against
         * `app/schemas/.../3.json`.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `walk_photos` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`uuid` TEXT NOT NULL, " +
                        "`walk_id` INTEGER NOT NULL, " +
                        "`photo_uri` TEXT NOT NULL, " +
                        "`pinned_at` INTEGER NOT NULL, " +
                        "`taken_at` INTEGER, " +
                        "FOREIGN KEY(`walk_id`) REFERENCES `walks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_walk_photos_walk_id` " +
                        "ON `walk_photos` (`walk_id`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_walk_photos_uuid` " +
                        "ON `walk_photos` (`uuid`)",
                )
            }
        }

        /**
         * Stage 7-B: adds three nullable columns for on-device ML Kit
         * image analysis. All `ALTER TABLE ADD COLUMN` — safe on an
         * existing `walk_photos` table of any size because SQLite
         * appends nullable columns without a table rewrite. Null for
         * every pre-existing row; the analysis worker fills them in
         * on next schedule.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `walk_photos` ADD COLUMN `top_label` TEXT")
                db.execSQL("ALTER TABLE `walk_photos` ADD COLUMN `top_label_confidence` REAL")
                db.execSQL("ALTER TABLE `walk_photos` ADD COLUMN `analyzed_at` INTEGER")
            }
        }

        /**
         * Stage 11-A: adds two nullable cache columns to the walks table
         * for finalize-time precomputed metrics — `distance_meters` (REAL)
         * and `meditation_seconds` (INTEGER). Both are pure `ALTER TABLE
         * ADD COLUMN`: SQLite appends nullable columns without a row scan,
         * so this is O(1) on existing DBs of any size. Pre-existing walks
         * read null for both fields; a lazy backfill coordinator
         * (Stage 11-B) computes values on first read and writes them back.
         *
         * Column order does not affect Room's schema validator (it
         * compares names/types/nullability/defaults, not physical order),
         * but we still emit the ALTERs in declaration order
         * (`distanceMeters` then `meditationSeconds`) so the table layout
         * matches the entity declaration for readability + debugging
         * tools that snapshot the schema visually.
         *
         * No manual transaction wrapper here — Room's RoomOpenHelper
         * already wraps `migrate()` in a transaction; nesting deadlocks.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `walks` ADD COLUMN `distance_meters` REAL")
                db.execSQL("ALTER TABLE `walks` ADD COLUMN `meditation_seconds` INTEGER")
            }
        }

        /**
         * Stage 12-A: adds four nullable weather columns to the walks
         * table — `weather_condition` (TEXT), `weather_temperature`,
         * `weather_humidity`, `weather_wind_speed` (all REAL). Like
         * MIGRATION_4_5, this is pure `ALTER TABLE ADD COLUMN`: SQLite
         * appends nullable columns without a row scan, so it's O(1) on
         * existing DBs of any size. Pre-existing walks read null for
         * every new field; the weather snapshot service (Task 2) writes
         * values during walk finalize for new walks only — there is no
         * historical backfill.
         *
         * ALTER order matches the entity field declaration order
         * (condition, temperature, humidity, wind speed) for parity with
         * `app/schemas/.../6.json` and so debugging tools that snapshot
         * the table layout visually see the same column sequence as the
         * Kotlin source.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `walks` ADD COLUMN `weather_condition` TEXT")
                db.execSQL("ALTER TABLE `walks` ADD COLUMN `weather_temperature` REAL")
                db.execSQL("ALTER TABLE `walks` ADD COLUMN `weather_humidity` REAL")
                db.execSQL("ALTER TABLE `walks` ADD COLUMN `weather_wind_speed` REAL")
            }
        }

        /**
         * Adds iOS-parity `steps` column to the walks table. Stored as
         * INTEGER (nullable). Populated at finishWalk by [StepCounter]
         * from the diff of `Sensor.TYPE_STEP_COUNTER` cumulative readings
         * between start + finish. Null when the sensor is unavailable,
         * the ACTIVITY_RECOGNITION permission is denied, or the device
         * rebooted mid-walk. Pure `ALTER TABLE ADD COLUMN` — O(1) on
         * existing DBs of any size.
         *
         * iOS parity: `Walk.steps: Int?` (`Walk.swift:122@db4196e`).
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `walks` ADD COLUMN `steps` INTEGER")
            }
        }

        /**
         * Adds iOS-parity captured_lat/captured_lng REAL columns to
         * walk_photos. Populated at pin time from EXIF GPS metadata via
         * ExifInterface. Null when photo has no GPS EXIF (indoor shot,
         * screenshot, stripped metadata). Pure `ALTER TABLE ADD COLUMN`
         * — O(1) on existing rows; pre-pinned photos read null and
         * render in the carousel only (no map pin). iOS parity:
         * `WalkSummaryView+Map.swift:34-46@db4196e`.
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `walk_photos` ADD COLUMN `captured_lat` REAL")
                db.execSQL("ALTER TABLE `walk_photos` ADD COLUMN `captured_lng` REAL")
            }
        }

        /**
         * #223 data-only repair: `walk_events` becomes the one source of
         * sittings. The schema is unchanged (9's identity hash equals 8's).
         *
         * 1. Walks imported from iOS before v9 hold their sittings only as
         *    `'MEDITATING'` rows in `activity_intervals`. Each such walk
         *    with no meditation event gains a `'MEDITATION_START'` at the
         *    row's start and a `'MEDITATION_END'` at its end. A row whose
         *    end isn't after its start adds nothing, and `'WALKING'` /
         *    `'TALKING'` rows are never read, so no sitting is invented.
         *    Every target row is read before the first insert: a check run
         *    per insert would see its own new START and skip the END.
         * 2. Every finished walk with meditation events or MEDITATING rows
         *    gets its cached `meditation_seconds` nulled. Native walks
         *    cached 0 (the cache summed the empty interval table), and
         *    [org.walktalkmeditate.pilgrim.data.walk.WalkMetricsBackfillCoordinator]
         *    only fills NULLs, so this is what makes it recompute them.
         *
         * Idempotent: a second run finds no target walk (they now have
         * events) and only re-nulls values the backfill recomputes to the
         * same number. Enum names are literals on purpose: they are what
         * v9 stores, whatever the Kotlin enums later become.
         *
         * No manual transaction wrapper — Room wraps `migrate()` in one.
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val sittings = mutableListOf<Triple<Long, Long, Long>>()
                db.query(SELECT_SITTINGS_WITHOUT_EVENTS_SQL).use { cursor ->
                    while (cursor.moveToNext()) {
                        sittings += Triple(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
                    }
                }
                for ((walkId, start, end) in sittings) {
                    db.execSQL(
                        INSERT_EVENT_SQL,
                        arrayOf<Any>(UUID.randomUUID().toString(), walkId, start, "MEDITATION_START"),
                    )
                    db.execSQL(
                        INSERT_EVENT_SQL,
                        arrayOf<Any>(UUID.randomUUID().toString(), walkId, end, "MEDITATION_END"),
                    )
                }
                db.execSQL(NULL_MEDITATION_CACHE_SQL)
            }
        }

        private const val SELECT_SITTINGS_WITHOUT_EVENTS_SQL =
            "SELECT `ai`.`walk_id`, `ai`.`start_timestamp`, `ai`.`end_timestamp` " +
                "FROM `activity_intervals` AS `ai` " +
                "WHERE `ai`.`activity_type` = 'MEDITATING' " +
                "AND `ai`.`end_timestamp` > `ai`.`start_timestamp` " +
                "AND NOT EXISTS (SELECT 1 FROM `walk_events` AS `e` " +
                "WHERE `e`.`walk_id` = `ai`.`walk_id` " +
                "AND `e`.`event_type` IN ('MEDITATION_START', 'MEDITATION_END')) " +
                "ORDER BY `ai`.`walk_id`, `ai`.`start_timestamp`"

        private const val INSERT_EVENT_SQL =
            "INSERT INTO `walk_events` (`uuid`, `walk_id`, `timestamp`, `event_type`) " +
                "VALUES (?, ?, ?, ?)"

        private const val NULL_MEDITATION_CACHE_SQL =
            "UPDATE `walks` SET `meditation_seconds` = NULL " +
                "WHERE `end_timestamp` IS NOT NULL " +
                "AND (EXISTS (SELECT 1 FROM `walk_events` AS `e` " +
                "WHERE `e`.`walk_id` = `walks`.`id` " +
                "AND `e`.`event_type` IN ('MEDITATION_START', 'MEDITATION_END')) " +
                "OR EXISTS (SELECT 1 FROM `activity_intervals` AS `ai` " +
                "WHERE `ai`.`walk_id` = `walks`.`id` " +
                "AND `ai`.`activity_type` = 'MEDITATING'))"

        /**
         * Phase 21 (U14): the live Honor tables and the walk marker. Purely
         * additive: four new tables, nothing existing is touched, and every
         * one starts empty. The DDL is Room's own for v10, copied from
         * `app/schemas/.../10.json`, with `IF NOT EXISTS` so a re-run is
         * harmless.
         *
         * - `honor_sessions`: one row per walk with Honor, keyed by walk id.
         * - `honor_moment_states`: one row per (walk, moment).
         * - `honor_card_states`: the UI's card rows, per (walk, moment).
         * - `honor_walk_markers`: keyed by walk uuid with no foreign key, so
         *   it outlives the walk row and a web-editor re-import.
         *
         * The first three cascade from `walks`, as every walk child does.
         */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `honor_sessions` (" +
                        "`walk_id` INTEGER NOT NULL, `way_id` TEXT NOT NULL, " +
                        "`source_kind` TEXT NOT NULL, `voices_enabled` INTEGER NOT NULL, " +
                        "`soft_tap_enabled` INTEGER NOT NULL, `phase` TEXT NOT NULL, " +
                        "`start_frac` REAL, `anchored_by_fallback` INTEGER NOT NULL, " +
                        "`anchor_active_seconds` REAL NOT NULL, `companion_t0_seconds` REAL NOT NULL, " +
                        "`progress_frac` REAL NOT NULL, `progress_high_water` REAL NOT NULL, " +
                        "`walked_frac` REAL NOT NULL, `off_way_since` INTEGER, " +
                        "`off_way_active_seconds` REAL NOT NULL, `last_reacquire_attempt` INTEGER, " +
                        "`soft_tap_since` INTEGER, `soft_tap_armed` INTEGER NOT NULL, " +
                        "`arrival_inside_fixes` INTEGER NOT NULL, `playing_moment_id` TEXT, " +
                        "`voice_paused` INTEGER NOT NULL, `voice_started_at` INTEGER, " +
                        "`voice_start_offset_millis` INTEGER, `voice_pause_offset_millis` INTEGER, " +
                        "`voice_rate` REAL NOT NULL, `arrival_their_seconds` REAL, " +
                        "`arrival_your_seconds` REAL, `last_command_seq` INTEGER NOT NULL, " +
                        "`gate_generation` INTEGER NOT NULL, `finish_kind` TEXT, " +
                        "PRIMARY KEY(`walk_id`), " +
                        "FOREIGN KEY(`walk_id`) REFERENCES `walks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `honor_moment_states` (" +
                        "`walk_id` INTEGER NOT NULL, `moment_id` TEXT NOT NULL, " +
                        "`reached_at` INTEGER, `queue_position` INTEGER, " +
                        "`voice_started_at` INTEGER, `voice_ended_at` INTEGER, " +
                        "`voice_end` TEXT, `heard` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`walk_id`, `moment_id`), " +
                        "FOREIGN KEY(`walk_id`) REFERENCES `walks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `honor_card_states` (" +
                        "`walk_id` INTEGER NOT NULL, `moment_id` TEXT NOT NULL, " +
                        "`dismissed` INTEGER NOT NULL, `touched` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`walk_id`, `moment_id`), " +
                        "FOREIGN KEY(`walk_id`) REFERENCES `walks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `honor_walk_markers` (" +
                        "`walk_uuid` TEXT NOT NULL, `finished_at` INTEGER NOT NULL, " +
                        "`finish_kind` TEXT NOT NULL, PRIMARY KEY(`walk_uuid`))",
                )
            }
        }

        /**
         * Phase 21 (U25): the seek session table, for Seek in `:tracker` with
         * the release flag on. Purely additive: one new table, nothing
         * existing is touched, and it starts empty. The DDL is Room's own for
         * v11, copied from `app/schemas/.../11.json`, with `IF NOT EXISTS` so
         * a re-run is harmless.
         *
         * - `seek_sessions`: one row per seek walk, keyed by walk id, written
         *   only by `:tracker`, cascading from `walks` as every walk child does.
         */
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `seek_sessions` (" +
                        "`walk_id` INTEGER NOT NULL, `chain` TEXT NOT NULL, " +
                        "`duration_minutes` INTEGER NOT NULL, `tint_hex` TEXT, " +
                        "`seed` INTEGER NOT NULL, `seeded_at` INTEGER NOT NULL, `intention` TEXT, " +
                        "`active_index` INTEGER NOT NULL, `phase` TEXT NOT NULL, `arrived_at` INTEGER, " +
                        "`distance_to_active_meters` REAL, `fog_bucket` INTEGER, " +
                        "`walker_latitude` REAL, `walker_longitude` REAL, " +
                        "`pulse_token` INTEGER NOT NULL, `pulse_aligned` INTEGER NOT NULL, " +
                        "`pulse_closeness` REAL NOT NULL, `next_pulse_due_at` INTEGER, " +
                        "`sonar_enabled` INTEGER NOT NULL, `sonar_volume` REAL NOT NULL, " +
                        "`sounds_enabled` INTEGER NOT NULL, `last_command_seq` INTEGER NOT NULL, " +
                        "`last_preference_seq` INTEGER NOT NULL, `gate_generation` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`walk_id`), " +
                        "FOREIGN KEY(`walk_id`) REFERENCES `walks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }

        /**
         * Every manual migration, in order — the one list the production
         * builder ([org.walktalkmeditate.pilgrim.di.DatabaseModule]) and the
         * migration tests register. 1→2 is the AutoMigration declared on
         * [Database]. A getter, so a migration declared below this line
         * can never be captured before it is initialized.
         */
        val MIGRATIONS: Array<Migration>
            get() = arrayOf(
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
            )
    }
}
