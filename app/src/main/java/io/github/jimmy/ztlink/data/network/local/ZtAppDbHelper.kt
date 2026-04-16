package io.github.jimmy.ztlink.data.network.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 应用级统一数据库助手。
 *
 * 说明：
 * - 统一维护 Join Network 所需全部网络相关表。
 * - 各表结构由实体 companion object 提供，DbHelper 只做调度。
 */
class ZtAppDbHelper(
    /** 应用上下文。 */
    context: Context,
) : SQLiteOpenHelper(
    context,
    DB_NAME,
    null,
    DB_VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        TABLE_CONTRACTS.forEach { contract ->
            db.execSQL(contract.createTableSql)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 当前是首版落地，迁移策略先保留占位，后续按版本追加。
        if (oldVersion < 1) {
            TABLE_CONTRACTS.forEach { contract ->
                db.execSQL(contract.createTableSql)
            }
        }
    }

    companion object {
        /** 数据库文件名。 */
        const val DB_NAME: String = "ztlink_app.db"
        /** 数据库版本。 */
        const val DB_VERSION: Int = 1

        /**
         * 统一注册的表契约列表。
         *
         * 关键逻辑：
         * - 通过实体 companion object 汇总建表语句。
         * - 后续新增表时，只需在此追加契约即可纳入统一管理。
         */
        val TABLE_CONTRACTS: List<DbTableContract> = listOf(
            NetworkDbEntity.Companion,
            NetworkConfigDbEntity.Companion,
            AssignedAddressDbEntity.Companion,
            DnsServerDbEntity.Companion,
            AppNodeDbEntity.Companion,
        )
    }
}
