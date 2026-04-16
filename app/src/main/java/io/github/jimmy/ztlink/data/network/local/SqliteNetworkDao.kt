package io.github.jimmy.ztlink.data.network.local

import android.content.ContentValues
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 基于 SQLiteOpenHelper 的网络 DAO 实现。
 *
 * 说明：
 * - 采用读写锁保证并发读写安全。
 * - 采用 replace 实现 upsert，减少“先查后改”的竞态窗口。
 */
class SqliteNetworkDao(
    /** 数据库助手。 */
    private val dbHelper: ZtAppDbHelper,
) : NetworkDao {

    /** DAO 级读写锁。 */
    private val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()

    override fun upsert(entity: NetworkDbEntity) {
        lock.write {
            val db = dbHelper.writableDatabase
            // 关键逻辑：使用 replace 保证“存在则更新，不存在则插入”。
            db.replace(
                NetworkDbEntity.tableName,
                null,
                NetworkDbEntity.toContentValues(entity),
            )
        }
    }

    override fun findById(networkId: String): NetworkDbEntity? {
        lock.read {
            val db = dbHelper.readableDatabase
            db.query(
                NetworkDbEntity.tableName,
                null,
                "${NetworkDbEntity.COL_NETWORK_ID} = ?",
                arrayOf(networkId),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                return cursor.takeIf { it.moveToFirst() }?.let { NetworkDbEntity.fromCursor(it) }
            }
        }
    }

    override fun listAll(): List<NetworkDbEntity> {
        lock.read {
            val db = dbHelper.readableDatabase
            db.query(
                NetworkDbEntity.tableName,
                null,
                null,
                null,
                null,
                null,
                // 关键逻辑：最近激活网络优先展示，其次按 networkId 排序。
                "${NetworkDbEntity.COL_LAST_ACTIVATED} DESC, ${NetworkDbEntity.COL_NETWORK_ID} ASC",
            ).use { cursor ->
                val result = ArrayList<NetworkDbEntity>(cursor.count.coerceAtLeast(0))
                while (cursor.moveToNext()) {
                    result.add(NetworkDbEntity.fromCursor(cursor))
                }
                return result
            }
        }
    }

    override fun deleteById(networkId: String) {
        lock.write {
            val db = dbHelper.writableDatabase
            db.delete(
                NetworkDbEntity.tableName,
                "${NetworkDbEntity.COL_NETWORK_ID} = ?",
                arrayOf(networkId),
            )
        }
    }

    override fun clearLastActivated() {
        lock.write {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put(NetworkDbEntity.COL_LAST_ACTIVATED, 0)
            }
            db.update(
                NetworkDbEntity.tableName,
                values,
                null,
                null,
            )
        }
    }

    override fun setLastActivated(networkId: String, lastActivated: Boolean) {
        lock.write {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put(NetworkDbEntity.COL_LAST_ACTIVATED, lastActivated.toDbBoolean())
            }
            db.update(
                NetworkDbEntity.tableName,
                values,
                "${NetworkDbEntity.COL_NETWORK_ID} = ?",
                arrayOf(networkId),
            )
        }
    }

    /**
     * 布尔值转数据库整型（0/1）。
     *
     * @return 0 或 1。
     */
    private fun Boolean.toDbBoolean(): Int = if (this) 1 else 0

}
