package io.github.jimmy.ztlink.data.network.local

/**
 * 网络配置 DAO 接口。
 */
interface NetworkConfigDao {

    /**
     * 新增或更新网络配置。
     *
     * @param entity 配置实体。
     */
    fun upsert(entity: NetworkConfigDbEntity)

    /**
     * 按网络 ID 查询配置。
     *
     * @param networkId 网络 ID。
     * @return 配置实体，未命中返回 null。
     */
    fun findByNetworkId(networkId: String): NetworkConfigDbEntity?

    /**
     * 删除指定网络配置。
     *
     * @param networkId 网络 ID。
     */
    fun deleteByNetworkId(networkId: String)
}
