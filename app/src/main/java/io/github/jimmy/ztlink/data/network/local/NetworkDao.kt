package io.github.jimmy.ztlink.data.network.local

/**
 * 网络 DAO 接口。
 *
 * 说明：
 * - 聚焦“网络记录”的落库与查询。
 * - 该层不承担业务规则，仅做数据持久化操作。
 */
interface NetworkDao {

    /**
     * 新增或更新网络记录。
     *
     * @param entity 待写入的网络记录实体。
     */
    fun upsert(entity: NetworkDbEntity)

    /**
     * 按网络 ID 查询单条记录。
     *
     * @param networkId 网络 ID。
     * @return 查询到的网络记录，未命中返回 null。
     */
    fun findById(networkId: String): NetworkDbEntity?

    /**
     * 查询全部网络记录。
     *
     * @return 网络记录列表。
     */
    fun listAll(): List<NetworkDbEntity>

    /**
     * 删除指定网络记录。
     *
     * @param networkId 网络 ID。
     */
    fun deleteById(networkId: String)

    /**
     * 将所有记录的 lastActivated 清零。
     */
    fun clearLastActivated()

    /**
     * 设置指定网络的 lastActivated 标记。
     *
     * @param networkId 网络 ID。
     * @param lastActivated 目标标记值。
     */
    fun setLastActivated(networkId: String, lastActivated: Boolean)
}
