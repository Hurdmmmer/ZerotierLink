package io.github.jimmy.ztlink.data.network.local

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 基于 SQLiteOpenHelper 的网络配置 DAO 实现。
 */
class SqliteNetworkConfigDao(
    /** 数据库助手。 */
    private val dbHelper: ZtAppDbHelper,
) : NetworkConfigDao {

    /** DAO 级读写锁。 */
    private val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()

    override fun upsert(entity: NetworkConfigDbEntity) {
        lock.write {
            val db = dbHelper.writableDatabase
            db.replace(
                NetworkConfigDbEntity.tableName,
                null,
                NetworkConfigDbEntity.toContentValues(entity),
            )
        }
    }

    override fun findByNetworkId(networkId: String): NetworkConfigDbEntity? {
        lock.read {
            val db = dbHelper.readableDatabase
            db.query(
                NetworkConfigDbEntity.tableName,
                null,
                "${NetworkConfigDbEntity.COL_NETWORK_ID} = ?",
                arrayOf(networkId),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                return cursor.takeIf { it.moveToFirst() }?.let { NetworkConfigDbEntity.fromCursor(it) }
            }
        }
    }

    override fun deleteByNetworkId(networkId: String) {
        lock.write {
            val db = dbHelper.writableDatabase
            db.delete(
                NetworkConfigDbEntity.tableName,
                "${NetworkConfigDbEntity.COL_NETWORK_ID} = ?",
                arrayOf(networkId),
            )
        }
    }
}
