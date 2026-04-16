package io.github.jimmy.ztlink.data.network.local

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 基于 SQLiteOpenHelper 的网络分配地址 DAO 实现。
 */
class SqliteAssignedAddressDao(
    /** 数据库助手。 */
    private val dbHelper: ZtAppDbHelper,
) : AssignedAddressDao {

    /** DAO 级读写锁。 */
    private val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()

    override fun replaceByNetworkId(networkId: String, entities: List<AssignedAddressDbEntity>) {
        lock.write {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                db.delete(
                    AssignedAddressDbEntity.tableName,
                    "${AssignedAddressDbEntity.COL_NETWORK_ID} = ?",
                    arrayOf(networkId),
                )
                entities.forEach { entity ->
                    val normalizedEntity = if (entity.networkId == networkId) {
                        entity
                    } else {
                        entity.copy(networkId = networkId)
                    }
                    db.insert(
                        AssignedAddressDbEntity.tableName,
                        null,
                        AssignedAddressDbEntity.toContentValues(normalizedEntity),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override fun listByNetworkId(networkId: String): List<AssignedAddressDbEntity> {
        lock.read {
            val db = dbHelper.readableDatabase
            db.query(
                AssignedAddressDbEntity.tableName,
                null,
                "${AssignedAddressDbEntity.COL_NETWORK_ID} = ?",
                arrayOf(networkId),
                null,
                null,
                "${AssignedAddressDbEntity.COL_ID} ASC",
            ).use { cursor ->
                val result = ArrayList<AssignedAddressDbEntity>(cursor.count.coerceAtLeast(0))
                while (cursor.moveToNext()) {
                    result.add(AssignedAddressDbEntity.fromCursor(cursor))
                }
                return result
            }
        }
    }

    override fun deleteByNetworkId(networkId: String) {
        lock.write {
            val db = dbHelper.writableDatabase
            db.delete(
                AssignedAddressDbEntity.tableName,
                "${AssignedAddressDbEntity.COL_NETWORK_ID} = ?",
                arrayOf(networkId),
            )
        }
    }
}
