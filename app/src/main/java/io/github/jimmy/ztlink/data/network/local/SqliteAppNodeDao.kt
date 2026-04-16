package io.github.jimmy.ztlink.data.network.local

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 基于 SQLiteOpenHelper 的本机节点身份 DAO 实现。
 */
class SqliteAppNodeDao(
    /** 数据库助手。 */
    private val dbHelper: ZtAppDbHelper,
) : AppNodeDao {

    /** DAO 级读写锁。 */
    private val lock: ReentrantReadWriteLock = ReentrantReadWriteLock()

    override fun upsert(entity: AppNodeDbEntity) {
        lock.write {
            val db = dbHelper.writableDatabase
            db.replace(
                AppNodeDbEntity.tableName,
                null,
                AppNodeDbEntity.toContentValues(entity),
            )
        }
    }

    override fun findByNodeId(nodeId: Long): AppNodeDbEntity? {
        lock.read {
            val db = dbHelper.readableDatabase
            db.query(
                AppNodeDbEntity.tableName,
                null,
                "${AppNodeDbEntity.COL_NODE_ID} = ?",
                arrayOf(nodeId.toString()),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                return cursor.takeIf { it.moveToFirst() }?.let { AppNodeDbEntity.fromCursor(it) }
            }
        }
    }

    override fun findLatest(): AppNodeDbEntity? {
        lock.read {
            val db = dbHelper.readableDatabase
            db.query(
                AppNodeDbEntity.tableName,
                null,
                null,
                null,
                null,
                null,
                "${AppNodeDbEntity.COL_UPDATED_AT} DESC",
                "1",
            ).use { cursor ->
                return cursor.takeIf { it.moveToFirst() }?.let { AppNodeDbEntity.fromCursor(it) }
            }
        }
    }

    override fun deleteByNodeId(nodeId: Long) {
        lock.write {
            val db = dbHelper.writableDatabase
            db.delete(
                AppNodeDbEntity.tableName,
                "${AppNodeDbEntity.COL_NODE_ID} = ?",
                arrayOf(nodeId.toString()),
            )
        }
    }
}
