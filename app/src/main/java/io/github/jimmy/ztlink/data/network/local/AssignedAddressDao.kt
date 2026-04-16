package io.github.jimmy.ztlink.data.network.local

/**
 * 网络分配地址 DAO 接口。
 */
interface AssignedAddressDao {

    /**
     * 用新的地址列表替换网络的旧地址列表。
     *
     * @param networkId 网络 ID。
     * @param entities 地址实体列表。
     */
    fun replaceByNetworkId(networkId: String, entities: List<AssignedAddressDbEntity>)

    /**
     * 查询指定网络的地址列表。
     *
     * @param networkId 网络 ID。
     * @return 地址实体列表。
     */
    fun listByNetworkId(networkId: String): List<AssignedAddressDbEntity>

    /**
     * 删除指定网络的全部地址。
     *
     * @param networkId 网络 ID。
     */
    fun deleteByNetworkId(networkId: String)
}
