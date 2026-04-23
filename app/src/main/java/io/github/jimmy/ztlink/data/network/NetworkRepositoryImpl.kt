package io.github.jimmy.ztlink.data.network

import io.github.jimmy.ztlink.data.network.local.NetworkDao
import io.github.jimmy.ztlink.data.network.local.NetworkDbEntity
import io.github.jimmy.ztlink.data.network.local.NetworkConfigDao
import io.github.jimmy.ztlink.data.network.local.NetworkConfigDbEntity
import io.github.jimmy.ztlink.data.network.local.AssignedAddressDao
import io.github.jimmy.ztlink.data.network.local.AssignedAddressDbEntity
import io.github.jimmy.ztlink.data.network.local.DnsServerDao
import io.github.jimmy.ztlink.data.network.local.DnsServerDbEntity
import io.github.jimmy.ztlink.model.network.NetworkConfig
import io.github.jimmy.ztlink.util.enums.NetworkStatusEnum
import io.github.jimmy.ztlink.util.enums.NetworkDnsModeEnum
import io.github.jimmy.ztlink.model.network.NetworkEntity
import io.github.jimmy.ztlink.model.network.NetworkId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.map

/**
 * 网络仓库实现。
 *
 * 说明：
 * - 负责领域模型与 DAO 记录模型之间的双向映射。
 * - 负责统一线程调度，避免调用方误在主线程做磁盘 IO。
 */
class NetworkRepositoryImpl(
    /** 网络 DAO。 */
    private val networkDao: NetworkDao,
    /** 网络配置 DAO。 */
    private val networkConfigDao: NetworkConfigDao,
    /** 分配地址 DAO。 */
    private val assignedAddressDao: AssignedAddressDao,
    /** DNS 服务器 DAO。 */
    private val dnsServerDao: DnsServerDao,
    /** IO 调度器。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NetworkRepository {
    /** 数据版本号流，用于触发响应式重读。 */
    private val dataVersion: MutableStateFlow<Long> = MutableStateFlow(0L)

    /** 自增版本号生成器。 */
    private val versionGenerator: AtomicLong = AtomicLong(0L)

    override fun observeAll(): Flow<List<NetworkEntity>> {
        return dataVersion.map {
            withContext(ioDispatcher) {
                listAllInternal()
            }
        }
    }

    override suspend fun upsert(entity: NetworkEntity) = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        val networkId = entity.networkId.value
        networkDao.upsert(entity.toRecordEntity(now = now))
        networkConfigDao.upsert(entity.toConfigRecordEntity(now = now))
        assignedAddressDao.replaceByNetworkId(
            networkId = networkId,
            entities = entity.toAssignedAddressRecordEntities(now = now),
        )
        dnsServerDao.replaceByNetworkId(
            networkId = networkId,
            entities = entity.toDnsServerRecordEntities(now = now),
        )
        notifyDataChanged()
    }

    override suspend fun findById(networkId: NetworkId): NetworkEntity? = withContext(ioDispatcher) {
        networkDao.findById(networkId.value)?.toDomainEntity(
            config = networkConfigDao.findByNetworkId(networkId.value),
            addresses = assignedAddressDao.listByNetworkId(networkId.value),
            dnsServers = dnsServerDao.listByNetworkId(networkId.value),
        )
    }

    override suspend fun listAll(): List<NetworkEntity> = withContext(ioDispatcher) {
        listAllInternal()
    }

    override suspend fun deleteById(networkId: NetworkId) = withContext(ioDispatcher) {
        networkDao.deleteById(networkId.value)
        networkConfigDao.deleteByNetworkId(networkId.value)
        assignedAddressDao.deleteByNetworkId(networkId.value)
        dnsServerDao.deleteByNetworkId(networkId.value)
        notifyDataChanged()
    }

    override suspend fun setLastActivated(networkId: NetworkId) = withContext(ioDispatcher) {
        // 关键逻辑：先清空，再置位，保证只有一个最近激活网络。
        networkDao.clearLastActivated()
        networkDao.setLastActivated(networkId.value, true)
        notifyDataChanged()
    }

    override suspend fun findLastActivated(): NetworkEntity? = withContext(ioDispatcher) {
        val lastActivated = networkDao.listAll().firstOrNull { it.lastActivated } ?: return@withContext null
        lastActivated.toDomainEntity(
            config = networkConfigDao.findByNetworkId(lastActivated.networkId),
            addresses = assignedAddressDao.listByNetworkId(lastActivated.networkId),
            dnsServers = dnsServerDao.listByNetworkId(lastActivated.networkId),
        )
    }

    /**
     * 读取全部网络实体内部实现。
     */
    private fun listAllInternal(): List<NetworkEntity> {
        return networkDao.listAll().map { network ->
            network.toDomainEntity(
                config = networkConfigDao.findByNetworkId(network.networkId),
                addresses = assignedAddressDao.listByNetworkId(network.networkId),
                dnsServers = dnsServerDao.listByNetworkId(network.networkId),
            )
        }
    }

    /**
     * 通知外层数据已变更，触发观察流重读。
     */
    private fun notifyDataChanged() {
        dataVersion.value = versionGenerator.incrementAndGet()
    }

    /**
     * 领域实体转 DAO 记录实体。
     *
     * @return DAO 记录实体。
     */
    private fun NetworkEntity.toRecordEntity(now: Long): NetworkDbEntity {
        return NetworkDbEntity(
            networkId = networkId.value,
            networkName = displayName,
            isEnabled = isEnabled,
            lastActivated = lastActivated,
            networkConfigId = networkId.value,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * 领域实体转配置 DAO 记录实体。
     *
     * @param now 当前时间戳（毫秒）。
     * @return 配置记录实体。
     */
    private fun NetworkEntity.toConfigRecordEntity(now: Long): NetworkConfigDbEntity {
        return NetworkConfigDbEntity(
            networkId = networkId.value,
            routeViaZeroTier = config.routeViaZeroTier,
            dnsModeCode = config.dnsMode.code,
            statusName = status.name,
            mac = mac,
            mtu = mtu,
            broadcastEnabled = broadcastEnabled,
            bridgingEnabled = bridgingEnabled,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * 领域实体转分配地址 DAO 记录列表。
     *
     * @param now 当前时间戳（毫秒）。
     * @return 分配地址记录列表。
     */
    private fun NetworkEntity.toAssignedAddressRecordEntities(now: Long): List<AssignedAddressDbEntity> {
        return assignedIps.map { address ->
            val (normalizedAddress, prefix) = normalizeAddressAndPrefix(address)
            AssignedAddressDbEntity(
                id = null,
                networkId = networkId.value,
                typeCode = detectIpType(normalizedAddress),
                addressBytes = null,
                addressString = normalizedAddress,
                prefix = prefix,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    /**
     * 领域实体转 DNS DAO 记录列表。
     *
     * @param now 当前时间戳（毫秒）。
     * @return DNS 记录列表。
     */
    private fun NetworkEntity.toDnsServerRecordEntities(now: Long): List<DnsServerDbEntity> {
        val sourceList = if (dnsServers.isNotEmpty()) {
            dnsServers
        } else {
            config.customDns.deserializeStringList()
        }
        return sourceList.map { dns ->
            DnsServerDbEntity(
                id = null,
                networkId = networkId.value,
                nameServer = dns.trim(),
                createdAt = now,
                updatedAt = now,
            )
        }.filter { it.nameServer.isNotEmpty() }
    }

    /**
     * DAO 记录转领域实体。
     *
     * @param config 配置记录。
     * @param addresses 地址记录列表。
     * @param dnsServers DNS 记录列表。
     * @return 领域实体。
     */
    private fun NetworkDbEntity.toDomainEntity(
        config: NetworkConfigDbEntity?,
        addresses: List<AssignedAddressDbEntity>,
        dnsServers: List<DnsServerDbEntity>,
    ): NetworkEntity {
        val parsedNetworkId = NetworkId.parse(networkId)
            ?: error("数据库存在非法 networkId: $networkId")
        val configRecord = config ?: NetworkConfigDbEntity(
            networkId = networkId,
            routeViaZeroTier = false,
            dnsModeCode = NetworkDnsModeEnum.NONE.code,
            statusName = NetworkStatusEnum.DISCONNECTED.name,
            mac = "",
            mtu = null,
            broadcastEnabled = false,
            bridgingEnabled = false,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
        val dnsList = dnsServers.map { it.nameServer.trim() }.filter { it.isNotEmpty() }
        return NetworkEntity(
            networkId = parsedNetworkId,
            displayName = networkName,
            isEnabled = isEnabled,
            lastActivated = lastActivated,
            config = NetworkConfig(
                routeViaZeroTier = configRecord.routeViaZeroTier,
                dnsMode = NetworkDnsModeEnum.fromCode(configRecord.dnsModeCode),
                customDns = dnsList.serializeStringList(),
            ),
            status = runCatching { NetworkStatusEnum.valueOf(configRecord.statusName) }
                .getOrDefault(NetworkStatusEnum.UNKNOWN),
            assignedIps = addresses.map { it.toCidrAddressString() },
            dnsServers = dnsList,
            mac = configRecord.mac,
            mtu = configRecord.mtu,
            broadcastEnabled = configRecord.broadcastEnabled,
            bridgingEnabled = configRecord.bridgingEnabled,
        )
    }

    /**
     * 地址记录转 CIDR 字符串。
     *
     * @return CIDR 文本。
     */
    private fun AssignedAddressDbEntity.toCidrAddressString(): String {
        return if (prefix > 0) {
            "$addressString/$prefix"
        } else {
            addressString
        }
    }

    /**
     * 解析地址文本与前缀。
     *
     * @param rawAddress 原始地址文本。
     * @return 地址与前缀。
     */
    private fun normalizeAddressAndPrefix(rawAddress: String): Pair<String, Int> {
        val parts = rawAddress.trim().split("/", limit = 2)
        val address = parts.firstOrNull().orEmpty().trim()
        val prefix = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        return address to prefix
    }

    /**
     * 根据地址文本推断地址类型编码。
     *
     * @param address 地址文本。
     * @return 类型编码。
     */
    private fun detectIpType(address: String): Int {
        return when {
            ':' in address -> 2
            '.' in address -> 1
            else -> 0
        }
    }

    /**
     * 序列化字符串列表。
     *
     * @return 序列化文本。
     */
    private fun List<String>.serializeStringList(): String {
        // 关键逻辑：使用换行符序列化，IP/DNS 文本中通常不含换行，简单且可读。
        return this.joinToString(separator = "\n")
    }

    /**
     * 反序列化字符串列表。
     *
     * @return 字符串列表。
     */
    private fun String.deserializeStringList(): List<String> {
        if (isBlank()) {
            return emptyList()
        }
        return lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }
}
