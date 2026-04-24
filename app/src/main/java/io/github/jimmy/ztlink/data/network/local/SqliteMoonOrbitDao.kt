package io.github.jimmy.ztlink.data.network.local

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class SqliteMoonOrbitDao(
    private val dbHelper: ZtAppDbHelper,
) : MoonOrbitDao {

    private val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()

    override fun upsert(entity: MoonOrbitDbEntity) {
        lock.write {
            val db = dbHelper.writableDatabase
            db.replace(
                MoonOrbitDbEntity.tableName,
                null,
                MoonOrbitDbEntity.toContentValues(entity),
            )
        }
    }

    override fun findByMoonWorldId(moonWorldId: Long): MoonOrbitDbEntity? {
        lock.read {
            val db = dbHelper.readableDatabase
            db.query(
                MoonOrbitDbEntity.tableName,
                null,
                "${MoonOrbitDbEntity.COL_MOON_WORLD_ID} = ?",
                arrayOf(moonWorldId.toString()),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                return cursor.takeIf { it.moveToFirst() }?.let { MoonOrbitDbEntity.fromCursor(it) }
            }
        }
    }

    override fun listAll(): List<MoonOrbitDbEntity> {
        lock.read {
            val db = dbHelper.readableDatabase
            db.query(
                MoonOrbitDbEntity.tableName,
                null,
                null,
                null,
                null,
                null,
                "${MoonOrbitDbEntity.COL_MOON_WORLD_ID} ASC",
            ).use { cursor ->
                val result = ArrayList<MoonOrbitDbEntity>(cursor.count.coerceAtLeast(0))
                while (cursor.moveToNext()) {
                    result.add(MoonOrbitDbEntity.fromCursor(cursor))
                }
                return result
            }
        }
    }

    override fun deleteByMoonWorldId(moonWorldId: Long) {
        lock.write {
            val db = dbHelper.writableDatabase
            db.delete(
                MoonOrbitDbEntity.tableName,
                "${MoonOrbitDbEntity.COL_MOON_WORLD_ID} = ?",
                arrayOf(moonWorldId.toString()),
            )
        }
    }
}

