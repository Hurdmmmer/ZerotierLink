package io.github.jimmy.ztlink.data.network

import io.github.jimmy.ztlink.model.network.NetworkEntity
import io.github.jimmy.ztlink.model.network.NetworkId
import kotlinx.coroutines.flow.Flow

/**
 * 网络仓库接口。
 *
 * 说明：
 * - 向上层提供“领域模型级”读写能力。
 * - 屏蔽底层 DAO 与表结构细节。
 */
interface NetworkRepository {

    /**
     * 观察全部网络实体（响应式）。
     *
     * 说明：
     * - 任何入库变更（upsert/delete/setLastActivated）都会触发新快照。
     * - UI 层应优先订阅该流，而不是手动轮询 listAll。
     *
     * @return 网络实体列表流。
     */
    fun observeAll(): Flow<List<NetworkEntity>>

    /**
     * 新增或更新网络实体。
     *
     * @param entity 网络领域实体。
     */
    suspend fun upsert(entity: NetworkEntity)

    /**
     * 按网络 ID 查询网络实体。
     *
     * @param networkId 网络 ID。
     * @return 查询到的实体，未命中返回 null。
     */
    suspend fun findById(networkId: NetworkId): NetworkEntity?

    /**
     * 查询全部网络实体。
     *
     * @return 网络实体列表。
     */
    suspend fun listAll(): List<NetworkEntity>

    /**
     * 删除指定网络实体。
     *
     * @param networkId 网络 ID。
     */
    suspend fun deleteById(networkId: NetworkId)

    /**
     * 将指定网络设置为最近激活。
     *
     * @param networkId 网络 ID。
     */
    suspend fun setLastActivated(networkId: NetworkId)

    /**
     * 查询最近一次激活的网络。
     *
     * @return 最近激活网络，未命中返回 null。
     */
    suspend fun findLastActivated(): NetworkEntity?
}
