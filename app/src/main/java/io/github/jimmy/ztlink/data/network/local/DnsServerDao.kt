package io.github.jimmy.ztlink.data.network.local

/**
 * 网络 DNS DAO 接口。
 */
interface DnsServerDao {

    /**
     * 用新的 DNS 列表替换网络的旧 DNS 列表。
     *
     * @param networkId 网络 ID。
     * @param entities DNS 实体列表。
     */
    fun replaceByNetworkId(networkId: String, entities: List<DnsServerDbEntity>)

    /**
     * 查询指定网络的 DNS 列表。
     *
     * @param networkId 网络 ID。
     * @return DNS 实体列表。
     */
    fun listByNetworkId(networkId: String): List<DnsServerDbEntity>

    /**
     * 删除指定网络的全部 DNS。
     *
     * @param networkId 网络 ID。
     */
    fun deleteByNetworkId(networkId: String)
}
