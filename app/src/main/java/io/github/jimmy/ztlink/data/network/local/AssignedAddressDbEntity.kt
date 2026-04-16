package io.github.jimmy.ztlink.data.network.local

import android.content.ContentValues
import android.database.Cursor

/**
 * 网络分配地址实体（DAO 层）。
 *
 * 说明：
 * - 一条记录对应一个分配地址（IPv4/IPv6）。
 * - 通过 networkId 关联到 network_configs。
 */
data class AssignedAddressDbEntity(
    /** 自增主键。 */
    val id: Long?,
    /** 关联网络 ID。 */
    val networkId: String,
    /** 地址类型编码（0=UNKNOWN, 1=IPV4, 2=IPV6）。 */
    val typeCode: Int,
    /** 地址原始字节（可空，用于兼容仅文本场景）。 */
    val addressBytes: ByteArray?,
    /** 地址文本（如 10.147.20.2）。 */
    val addressString: String,
    /** 地址前缀长度。 */
    val prefix: Int,
    /** 创建时间戳（毫秒）。 */
    val createdAt: Long,
    /** 更新时间戳（毫秒）。 */
    val updatedAt: Long,
) {

    companion object : DbTableContract {
        /** 表名。 */
        const val TABLE_NAME: String = "assigned_addresses"
        override val tableName: String
            get() = TABLE_NAME

        /** 主键列名。 */
        const val COL_ID: String = "id"
        /** 网络 ID 列名。 */
        const val COL_NETWORK_ID: String = "network_id"
        /** 地址类型列名。 */
        const val COL_TYPE_CODE: String = "type_code"
        /** 地址字节列名。 */
        const val COL_ADDRESS_BYTES: String = "address_bytes"
        /** 地址文本列名。 */
        const val COL_ADDRESS_STRING: String = "address_string"
        /** 前缀长度列名。 */
        const val COL_PREFIX: String = "prefix"
        /** 创建时间列名。 */
        const val COL_CREATED_AT: String = "created_at"
        /** 更新时间列名。 */
        const val COL_UPDATED_AT: String = "updated_at"

        /** 建表 SQL。 */
        override val createTableSql: String = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NETWORK_ID TEXT NOT NULL,
                $COL_TYPE_CODE INTEGER NOT NULL DEFAULT 0,
                $COL_ADDRESS_BYTES BLOB NULL,
                $COL_ADDRESS_STRING TEXT NOT NULL DEFAULT '',
                $COL_PREFIX INTEGER NOT NULL DEFAULT 0,
                $COL_CREATED_AT INTEGER NOT NULL DEFAULT 0,
                $COL_UPDATED_AT INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        /**
         * Cursor 当前行转实体。
         *
         * @param cursor 数据游标。
         * @return 地址实体。
         */
        fun fromCursor(cursor: Cursor): AssignedAddressDbEntity {
            return AssignedAddressDbEntity(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                networkId = cursor.getString(cursor.getColumnIndexOrThrow(COL_NETWORK_ID)),
                typeCode = cursor.getInt(cursor.getColumnIndexOrThrow(COL_TYPE_CODE)),
                addressBytes = if (cursor.isNull(cursor.getColumnIndexOrThrow(COL_ADDRESS_BYTES))) {
                    null
                } else {
                    cursor.getBlob(cursor.getColumnIndexOrThrow(COL_ADDRESS_BYTES))
                },
                addressString = cursor.getString(cursor.getColumnIndexOrThrow(COL_ADDRESS_STRING)),
                prefix = cursor.getInt(cursor.getColumnIndexOrThrow(COL_PREFIX)),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT)),
            )
        }

        /**
         * 实体转 ContentValues。
         *
         * @param entity 地址实体。
         * @return ContentValues。
         */
        fun toContentValues(entity: AssignedAddressDbEntity): ContentValues {
            return ContentValues().apply {
                if (entity.id != null) {
                    put(COL_ID, entity.id)
                }
                put(COL_NETWORK_ID, entity.networkId)
                put(COL_TYPE_CODE, entity.typeCode)
                if (entity.addressBytes == null) {
                    putNull(COL_ADDRESS_BYTES)
                } else {
                    put(COL_ADDRESS_BYTES, entity.addressBytes)
                }
                put(COL_ADDRESS_STRING, entity.addressString)
                put(COL_PREFIX, entity.prefix)
                put(COL_CREATED_AT, entity.createdAt)
                put(COL_UPDATED_AT, entity.updatedAt)
            }
        }
    }
}
