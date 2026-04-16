package io.github.jimmy.ztlink.data.network.local

/**
 * 本机节点身份 DAO 接口。
 */
interface AppNodeDao {

    /**
     * 新增或更新节点身份。
     *
     * @param entity 节点实体。
     */
    fun upsert(entity: AppNodeDbEntity)

    /**
     * 按节点 ID 查询节点身份。
     *
     * @param nodeId 节点 ID。
     * @return 节点实体，未命中返回 null。
     */
    fun findByNodeId(nodeId: Long): AppNodeDbEntity?

    /**
     * 查询最新一条节点身份。
     *
     * @return 节点实体，未命中返回 null。
     */
    fun findLatest(): AppNodeDbEntity?

    /**
     * 删除节点身份。
     *
     * @param nodeId 节点 ID。
     */
    fun deleteByNodeId(nodeId: Long)
}
