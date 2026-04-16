package io.github.jimmy.ztlink.data.network.local

/**
 * 数据表契约接口。
 *
 * 说明：
 * - 各实体通过 companion object 实现该接口。
 * - DbHelper 统一收集并执行建表语句。
 */
interface DbTableContract {
    /** 表名。 */
    val tableName: String

    /** 建表 SQL。 */
    val createTableSql: String
}

