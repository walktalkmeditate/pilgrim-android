// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    ],
    version = 5,
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
         * Column order matters — must match the order of `distanceMeters`
         * then `meditationSeconds` in the [Walk] entity. Room hashes the
         * column ordering at startup, so swapping these two ALTERs would
         * fail schema validation.
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
    }
}
