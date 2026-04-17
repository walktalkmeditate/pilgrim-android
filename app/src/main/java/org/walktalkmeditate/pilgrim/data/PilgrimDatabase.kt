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
