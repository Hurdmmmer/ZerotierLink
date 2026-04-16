package io.github.jimmy.ztlink.data.network.local

import android.content.ContentValues
import android.database.Cursor

/**
 * 网络 DNS 服务器实体（DAO 层）。
 *
 * 说明：
 * - 按老项目结构独立存储 DNS 列表。
 * - 通过 networkId 关联到 network_configs。
 */
data class DnsServerDbEntity(
    /** 自增主键。 */
    val id: Long?,
    /** 关联网络 ID。 */
    val networkId: String,
    /** DNS 服务器地址。 */
    val nameServer: String,
    /** 创建时间戳（毫秒）。 */
    val createdAt: Long,
    /** 更新时间戳（毫秒）。 */
    val updatedAt: Long,
) {

    companion object : DbTableContract {
        /** 表名。 */
        const val TABLE_NAME: String = "dns_servers"
        override val tableName: String
            get() = TABLE_NAME

        /** 主键列名。 */
        const val COL_ID: String = "id"
        /** 网络 ID 列名。 */
        const val COL_NETWORK_ID: String = "network_id"
        /** DNS 地址列名。 */
        const val COL_NAME_SERVER: String = "name_server"
        /** 创建时间列名。 */
        const val COL_CREATED_AT: String = "created_at"
        /** 更新时间列名。 */
        const val COL_UPDATED_AT: String = "updated_at"

        /** 建表 SQL。 */
        override val createTableSql: String = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NETWORK_ID TEXT NOT NULL,
                $COL_NAME_SERVER TEXT NOT NULL DEFAULT '',
                $COL_CREATED_AT INTEGER NOT NULL DEFAULT 0,
                $COL_UPDATED_AT INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        /**
         * Cursor 当前行转实体。
         *
         * @param cursor 数据游标。
         * @return DNS 实体。
         */
        fun fromCursor(cursor: Cursor): DnsServerDbEntity {
            return DnsServerDbEntity(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                networkId = cursor.getString(cursor.getColumnIndexOrThrow(COL_NETWORK_ID)),
                nameServer = cursor.getString(cursor.getColumnIndexOrThrow(COL_NAME_SERVER)),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT)),
            )
        }

        /**
         * 实体转 ContentValues。
         *
         * @param entity DNS 实体。
         * @return ContentValues。
         */
        fun toContentValues(entity: DnsServerDbEntity): ContentValues {
            return ContentValues().apply {
                if (entity.id != null) {
                    put(COL_ID, entity.id)
                }
                put(COL_NETWORK_ID, entity.networkId)
                put(COL_NAME_SERVER, entity.nameServer)
                put(COL_CREATED_AT, entity.createdAt)
                put(COL_UPDATED_AT, entity.updatedAt)
            }
        }
    }
}
