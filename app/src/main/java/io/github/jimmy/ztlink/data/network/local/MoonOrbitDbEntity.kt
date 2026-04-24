package io.github.jimmy.ztlink.data.network.local

import android.content.ContentValues
import android.database.Cursor

data class MoonOrbitDbEntity(
    val moonWorldId: Long,
    val moonSeed: Long,
    val fromFile: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {

    companion object : DbTableContract {
        const val TABLE_NAME: String = "moon_orbits"
        override val tableName: String
            get() = TABLE_NAME

        const val COL_MOON_WORLD_ID: String = "moon_world_id"
        const val COL_MOON_SEED: String = "moon_seed"
        const val COL_FROM_FILE: String = "from_file"
        const val COL_CREATED_AT: String = "created_at"
        const val COL_UPDATED_AT: String = "updated_at"

        const val MOON_FILE_PATH_FORMAT: String = "moons.d/%016x.moon"

        override val createTableSql: String = """
            CREATE TABLE IF NOT EXISTS $tableName (
                $COL_MOON_WORLD_ID INTEGER PRIMARY KEY NOT NULL,
                $COL_MOON_SEED INTEGER NOT NULL DEFAULT 0,
                $COL_FROM_FILE INTEGER NOT NULL DEFAULT 0,
                $COL_CREATED_AT INTEGER NOT NULL DEFAULT 0,
                $COL_UPDATED_AT INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        fun fromCursor(cursor: Cursor): MoonOrbitDbEntity {
            return MoonOrbitDbEntity(
                moonWorldId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_MOON_WORLD_ID)),
                moonSeed = cursor.getLong(cursor.getColumnIndexOrThrow(COL_MOON_SEED)),
                fromFile = cursor.getInt(cursor.getColumnIndexOrThrow(COL_FROM_FILE)).toDbBoolean(),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT)),
            )
        }

        fun toContentValues(entity: MoonOrbitDbEntity): ContentValues {
            return ContentValues().apply {
                put(COL_MOON_WORLD_ID, entity.moonWorldId)
                put(COL_MOON_SEED, entity.moonSeed)
                put(COL_FROM_FILE, entity.fromFile.toDbBoolean())
                put(COL_CREATED_AT, entity.createdAt)
                put(COL_UPDATED_AT, entity.updatedAt)
            }
        }

        private fun Boolean.toDbBoolean(): Int = if (this) 1 else 0

        private fun Int.toDbBoolean(): Boolean = this != 0
    }
}

