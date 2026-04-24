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
    version = 3,
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
    }
}
