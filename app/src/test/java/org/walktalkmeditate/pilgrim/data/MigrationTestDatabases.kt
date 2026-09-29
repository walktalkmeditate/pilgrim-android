// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds a Pilgrim database file at an exported schema version, for
 * migration tests that must run Room's own open path.
 *
 * `MigrationTestHelper` can't find `app/schemas` under Robolectric (the
 * build adds them only to the androidTest assets), so this replays
 * `app/schemas/org.walktalkmeditate.pilgrim.data.PilgrimDatabase/N.json`
 * itself, read from the module directory: every entity's `createSql`
 * (with `${TABLE_NAME}` substituted), its `indices[].createSql`, and the
 * `setupQueries` that stamp `room_master_table` with version N's
 * identity hash, at `user_version = N`.
 *
 * A test seeds rows through raw SQL in [createAtVersion]'s `seed`, then
 * opens the file with [openWithProductionMigrations]: Room runs the
 * production migrations array, validates every table against the
 * current entities, and checks the identity hash, exactly as on a device.
 */
internal object MigrationTestDatabases {

    fun createAtVersion(
        context: Context,
        name: String,
        version: Int,
        seed: (SupportSQLiteDatabase) -> Unit = {},
    ) {
        val statements = schemaStatements(version)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    statements.forEach(db::execSQL)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    error("$name already exists at version $oldVersion; delete it before creating version $newVersion")
                }
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            seed(helper.writableDatabase)
        }
    }

    fun openWithProductionMigrations(context: Context, name: String): PilgrimDatabase =
        Room.databaseBuilder(context, PilgrimDatabase::class.java, name)
            .addMigrations(*PilgrimDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    fun identityHash(version: Int): String =
        schema(version)["identityHash"]!!.jsonPrimitive.content

    private fun schemaStatements(version: Int): List<String> {
        val database = schema(version)
        val tables = database["entities"]!!.jsonArray.flatMap { element ->
            val entity = element.jsonObject
            val tableName = entity["tableName"]!!.jsonPrimitive.content
            val indices = entity["indices"]?.jsonArray.orEmpty()
                .map { it.jsonObject["createSql"]!!.jsonPrimitive.content }
            (listOf(entity["createSql"]!!.jsonPrimitive.content) + indices)
                .map { it.replace("\${TABLE_NAME}", tableName) }
        }
        val setup = database["setupQueries"]!!.jsonArray.map { it.jsonPrimitive.content }
        return tables + setup
    }

    private fun schema(version: Int): JsonObject {
        val relative = "schemas/org.walktalkmeditate.pilgrim.data.PilgrimDatabase/$version.json"
        val file = listOf(File(relative), File("app", relative)).firstOrNull { it.isFile }
            ?: error("exported schema $version.json not found from ${File("").absolutePath}")
        return Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }
}
