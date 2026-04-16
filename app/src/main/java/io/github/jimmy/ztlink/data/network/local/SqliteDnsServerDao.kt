package io.github.jimmy.ztlink.data.network.local

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 基于 SQLiteOpenHelper 的网络 DNS DAO 实现。
 */
class SqliteDnsServerDao(
    /** 数据库助手。 */
    private val dbHelper: ZtAppDbHelper,
) : DnsServerDao {

    /** DAO 级读写锁。 */
    private val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()

    override fun replaceByNetworkId(networkId: String, entities: List<DnsServerDbEntity>) {
        lock.write {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                db.delete(
                    DnsServerDbEntity.tableName,
                    "${DnsServerDbEntity.COL_NETWORK_ID} = ?",
                    arrayOf(networkId),
                )
                entities.forEach { entity ->
                    val normalizedEntity = if (entity.networkId == networkId) {
                        entity
                    } else {
                        entity.copy(networkId = networkId)
                    }
                    db.insert(
                        DnsServerDbEntity.tableName,
                        null,
                        DnsServerDbEntity.toContentValues(normalizedEntity),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override fun listByNetworkId(networkId: String): List<DnsServerDbEntity> {
        lock.read {
            val db = dbHelper.readableDatabase
            db.query(
                DnsServerDbEntity.tableName,
                null,
                "${DnsServerDbEntity.COL_NETWORK_ID} = ?",
                arrayOf(networkId),
                null,
                null,
                "${DnsServerDbEntity.COL_ID} ASC",
            ).use { cursor ->
                val result = ArrayList<DnsServerDbEntity>(cursor.count.coerceAtLeast(0))
                while (cursor.moveToNext()) {
                    result.add(DnsServerDbEntity.fromCursor(cursor))
                }
                return result
            }
        }
    }

    override fun deleteByNetworkId(networkId: String) {
        lock.write {
            val db = dbHelper.writableDatabase
            db.delete(
                DnsServerDbEntity.tableName,
                "${DnsServerDbEntity.COL_NETWORK_ID} = ?",
                arrayOf(networkId),
            )
        }
    }
}
