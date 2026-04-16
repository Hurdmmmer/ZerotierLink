package io.github.jimmy.ztlink.data.network.local

import android.content.ContentValues
import android.database.Cursor

/**
 * 本机节点身份实体（DAO 层）。
 *
 * 说明：
 * - 用于持久化当前设备的 ZeroTier 节点身份。
 * - 主键使用节点 ID，避免重复记录。
 */
data class AppNodeDbEntity(
    /** 节点 ID（数值）。 */
    val nodeId: Long,
    /** 节点 ID（十六进制字符串）。 */
    val nodeIdHex: String,
    /** 创建时间戳（毫秒）。 */
    val createdAt: Long,
    /** 更新时间戳（毫秒）。 */
    val updatedAt: Long,
) {

    companion object : DbTableContract {
        /** 表名。 */
        const val TABLE_NAME: String = "app_nodes"
        override val tableName: String
            get() = TABLE_NAME

        /** 节点 ID 列名。 */
        const val COL_NODE_ID: String = "node_id"
        /** 节点十六进制列名。 */
        const val COL_NODE_ID_HEX: String = "node_id_hex"
        /** 创建时间列名。 */
        const val COL_CREATED_AT: String = "created_at"
        /** 更新时间列名。 */
        const val COL_UPDATED_AT: String = "updated_at"

        /** 建表 SQL。 */
        override val createTableSql: String = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_NODE_ID INTEGER PRIMARY KEY NOT NULL,
                $COL_NODE_ID_HEX TEXT NOT NULL DEFAULT '',
                $COL_CREATED_AT INTEGER NOT NULL DEFAULT 0,
                $COL_UPDATED_AT INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        /**
         * Cursor 当前行转实体。
         *
         * @param cursor 数据游标。
         * @return 节点实体。
         */
        fun fromCursor(cursor: Cursor): AppNodeDbEntity {
            return AppNodeDbEntity(
                nodeId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_NODE_ID)),
                nodeIdHex = cursor.getString(cursor.getColumnIndexOrThrow(COL_NODE_ID_HEX)),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT)),
            )
        }

        /**
         * 实体转 ContentValues。
         *
         * @param entity 节点实体。
         * @return ContentValues。
         */
        fun toContentValues(entity: AppNodeDbEntity): ContentValues {
            return ContentValues().apply {
                put(COL_NODE_ID, entity.nodeId)
                put(COL_NODE_ID_HEX, entity.nodeIdHex)
                put(COL_CREATED_AT, entity.createdAt)
                put(COL_UPDATED_AT, entity.updatedAt)
            }
        }
    }
}
