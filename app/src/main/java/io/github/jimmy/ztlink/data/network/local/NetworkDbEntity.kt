package io.github.jimmy.ztlink.data.network.local

import android.content.ContentValues
import android.database.Cursor

/**
 * 网络主表实体（仅保存网络基础信息）。
 *
 * 设计说明：
 * - 按老项目关系拆分：网络主表只保存基础信息。
 * - 运行配置、分配地址、DNS 服务器均拆到独立子表。
 */
data class NetworkDbEntity(
    /** 网络 ID（16 位小写十六进制，主键）。 */
    val networkId: String,
    /** 网络显示名称。 */
    val networkName: String,
    /** 是否启用该网络。 */
    val isEnabled: Boolean,
    /** 是否为最近激活网络。 */
    val lastActivated: Boolean,
    /** 关联配置表主键（默认与 networkId 一致）。 */
    val networkConfigId: String,
    /** 创建时间戳（毫秒）。 */
    val createdAt: Long,
    /** 更新时间戳（毫秒）。 */
    val updatedAt: Long,
) {

    companion object : DbTableContract {
        /** 表名。 */
        const val TABLE_NAME: String = "networks"
        override val tableName: String
            get() = TABLE_NAME

        /** 网络 ID 列名。 */
        const val COL_NETWORK_ID: String = "network_id"
        /** 网络名称列名。 */
        const val COL_NETWORK_NAME: String = "network_name"
        /** 启用标记列名。 */
        const val COL_IS_ENABLED: String = "is_enabled"
        /** 最近激活列名。 */
        const val COL_LAST_ACTIVATED: String = "last_activated"
        /** 配置主键列名。 */
        const val COL_NETWORK_CONFIG_ID: String = "network_config_id"
        /** 创建时间列名。 */
        const val COL_CREATED_AT: String = "created_at"
        /** 更新时间列名。 */
        const val COL_UPDATED_AT: String = "updated_at"

        /** 建表 SQL。 */
        override val createTableSql: String = """
            CREATE TABLE IF NOT EXISTS $tableName (
                $COL_NETWORK_ID TEXT PRIMARY KEY NOT NULL,
                $COL_NETWORK_NAME TEXT NOT NULL DEFAULT '',
                $COL_IS_ENABLED INTEGER NOT NULL DEFAULT 1,
                $COL_LAST_ACTIVATED INTEGER NOT NULL DEFAULT 0,
                $COL_NETWORK_CONFIG_ID TEXT NOT NULL DEFAULT '',
                $COL_CREATED_AT INTEGER NOT NULL DEFAULT 0,
                $COL_UPDATED_AT INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        /**
         * Cursor 当前行转换为网络实体。
         *
         * @param cursor 数据游标。
         * @return 网络实体。
         */
        fun fromCursor(cursor: Cursor): NetworkDbEntity {
            return NetworkDbEntity(
                networkId = cursor.getString(cursor.getColumnIndexOrThrow(COL_NETWORK_ID)),
                networkName = cursor.getString(cursor.getColumnIndexOrThrow(COL_NETWORK_NAME)),
                isEnabled = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_ENABLED)).toDbBoolean(),
                lastActivated = cursor.getInt(cursor.getColumnIndexOrThrow(COL_LAST_ACTIVATED)).toDbBoolean(),
                networkConfigId = cursor.getString(cursor.getColumnIndexOrThrow(COL_NETWORK_CONFIG_ID)),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT)),
            )
        }

        /**
         * 网络实体转换为 ContentValues。
         *
         * @param entity 网络实体。
         * @return ContentValues。
         */
        fun toContentValues(entity: NetworkDbEntity): ContentValues {
            return ContentValues().apply {
                put(COL_NETWORK_ID, entity.networkId)
                put(COL_NETWORK_NAME, entity.networkName)
                put(COL_IS_ENABLED, entity.isEnabled.toDbBoolean())
                put(COL_LAST_ACTIVATED, entity.lastActivated.toDbBoolean())
                put(COL_NETWORK_CONFIG_ID, entity.networkConfigId)
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
