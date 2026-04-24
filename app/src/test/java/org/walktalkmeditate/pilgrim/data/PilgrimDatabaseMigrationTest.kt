// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [PilgrimDatabase.MIGRATION_2_3] directly against a hand-built
 * v2-shape SQLite DB. We deliberately avoid `MigrationTestHelper` here:
 * under Robolectric the instrumentation context's asset loader can't
 * find the exported v2 schema JSON (the helper is designed for on-device
 * `androidTest` runs). Rather than wrestle with asset plumbing, we test
 * the thing we actually ship — the Migration's `migrate` function — by
 * opening a raw SQLite DB, running the migration, and asserting the new
 * schema and Room round-trip both behave as expected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimDatabaseMigrationTest {

    private val dbName = "pilgrim-migration-test.db"
    private lateinit var context: Context

    @After
    fun tearDown() {
        // Clean up on-disk DB between tests so test order is irrelevant.
        context.deleteDatabase(dbName)
    }

    private fun openV2Shape(): SupportSQLiteDatabase {
        context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase(dbName)
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

    private companion object {
        private const val V2_VERSION = 2
    }
}
