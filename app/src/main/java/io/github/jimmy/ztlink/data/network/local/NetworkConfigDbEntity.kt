package io.github.jimmy.ztlink.data.network.local

import android.content.ContentValues
import android.database.Cursor

/**
 * 网络配置实体（DAO 层）。
 *
 * 说明：
 * - 该表承载 Join Network 的配置与运行态字段。
 * - 主键直接使用 networkId，保证一网一配置。
 */
data class NetworkConfigDbEntity(
    /** 关联网络 ID（主键）。 */
    val networkId: String,
    /** 是否通过 ZeroTier 下发默认路由。 */
    val routeViaZeroTier: Boolean,
    /** DNS 模式编码（0=NONE, 1=NETWORK, 2=CUSTOM）。 */
    val dnsModeCode: Int,
    /** 连接状态名称（枚举名）。 */
    val statusName: String,
    /** 虚拟网卡 MAC。 */
    val mac: String,
    /** MTU 值。 */
    val mtu: Int?,
    /** 广播开关。 */
    val broadcastEnabled: Boolean,
    /** 桥接开关。 */
    val bridgingEnabled: Boolean,
    /** 创建时间戳（毫秒）。 */
    val createdAt: Long,
    /** 更新时间戳（毫秒）。 */
    val updatedAt: Long,
) {

    companion object : DbTableContract {
        /** 表名。 */
        const val TABLE_NAME: String = "network_configs"
        override val tableName: String
            get() = TABLE_NAME

        /** 网络 ID 列名。 */
        const val COL_NETWORK_ID: String = "network_id"
        /** 默认路由列名。 */
        const val COL_ROUTE_VIA_ZERO_TIER: String = "route_via_zero_tier"
        /** DNS 模式列名。 */
        const val COL_DNS_MODE_CODE: String = "dns_mode_code"
        /** 状态列名。 */
        const val COL_STATUS_NAME: String = "status_name"
        /** MAC 列名。 */
        const val COL_MAC: String = "mac"
        /** MTU 列名。 */
        const val COL_MTU: String = "mtu"
        /** 广播列名。 */
        const val COL_BROADCAST_ENABLED: String = "broadcast_enabled"
        /** 桥接列名。 */
        const val COL_BRIDGING_ENABLED: String = "bridging_enabled"
        /** 创建时间列名。 */
        const val COL_CREATED_AT: String = "created_at"
        /** 更新时间列名。 */
        const val COL_UPDATED_AT: String = "updated_at"

        /** 建表 SQL。 */
        override val createTableSql: String = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_NETWORK_ID TEXT PRIMARY KEY NOT NULL,
                $COL_ROUTE_VIA_ZERO_TIER INTEGER NOT NULL DEFAULT 0,
                $COL_DNS_MODE_CODE INTEGER NOT NULL DEFAULT 0,
                $COL_STATUS_NAME TEXT NOT NULL DEFAULT 'DISCONNECTED',
                $COL_MAC TEXT NOT NULL DEFAULT '',
                $COL_MTU INTEGER NULL,
                $COL_BROADCAST_ENABLED INTEGER NOT NULL DEFAULT 0,
                $COL_BRIDGING_ENABLED INTEGER NOT NULL DEFAULT 0,
                $COL_CREATED_AT INTEGER NOT NULL DEFAULT 0,
                $COL_UPDATED_AT INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        /**
         * Cursor 当前行转实体。
         *
         * @param cursor 数据游标。
         * @return 配置实体。
         */
        fun fromCursor(cursor: Cursor): NetworkConfigDbEntity {
            return NetworkConfigDbEntity(
                networkId = cursor.getString(cursor.getColumnIndexOrThrow(COL_NETWORK_ID)),
                routeViaZeroTier = cursor.getInt(cursor.getColumnIndexOrThrow(COL_ROUTE_VIA_ZERO_TIER)).toDbBoolean(),
                dnsModeCode = cursor.getInt(cursor.getColumnIndexOrThrow(COL_DNS_MODE_CODE)),
                statusName = cursor.getString(cursor.getColumnIndexOrThrow(COL_STATUS_NAME)),
                mac = cursor.getString(cursor.getColumnIndexOrThrow(COL_MAC)),
                mtu = if (cursor.isNull(cursor.getColumnIndexOrThrow(COL_MTU))) {
                    null
                } else {
                    cursor.getInt(cursor.getColumnIndexOrThrow(COL_MTU))
                },
                broadcastEnabled = cursor.getInt(cursor.getColumnIndexOrThrow(COL_BROADCAST_ENABLED)).toDbBoolean(),
                bridgingEnabled = cursor.getInt(cursor.getColumnIndexOrThrow(COL_BRIDGING_ENABLED)).toDbBoolean(),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT)),
            )
        }

        /**
         * 实体转 ContentValues。
         *
         * @param entity 配置实体。
         * @return ContentValues。
         */
        fun toContentValues(entity: NetworkConfigDbEntity): ContentValues {
            return ContentValues().apply {
                put(COL_NETWORK_ID, entity.networkId)
                put(COL_ROUTE_VIA_ZERO_TIER, entity.routeViaZeroTier.toDbBoolean())
                put(COL_DNS_MODE_CODE, entity.dnsModeCode)
                put(COL_STATUS_NAME, entity.statusName)
                put(COL_MAC, entity.mac)
                if (entity.mtu == null) {
                    putNull(COL_MTU)
                } else {
                    put(COL_MTU, entity.mtu)
                }
                put(COL_BROADCAST_ENABLED, entity.broadcastEnabled.toDbBoolean())
                put(COL_BRIDGING_ENABLED, entity.bridgingEnabled.toDbBoolean())
                put(COL_CREATED_AT, entity.createdAt)
                put(COL_UPDATED_AT, entity.updatedAt)
            }
        }

        /**
         * 布尔值转数据库整型（0/1）。
         *
         * @return 整型值。
         */
        private fun Boolean.toDbBoolean(): Int = if (this) 1 else 0

        /**
         * 数据库整型转布尔值。
         *
         * @return 布尔值。
         */
        private fun Int.toDbBoolean(): Boolean = this != 0
    }
}
