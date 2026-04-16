package io.github.jimmy.ztlink.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jimmy.ztlink.data.network.NetworkRepository
import io.github.jimmy.ztlink.data.network.NetworkRepositoryImpl
import io.github.jimmy.ztlink.data.network.local.AssignedAddressDao
import io.github.jimmy.ztlink.data.network.local.DnsServerDao
import io.github.jimmy.ztlink.data.network.local.NetworkConfigDao
import io.github.jimmy.ztlink.data.network.local.NetworkDao
import javax.inject.Singleton

/**
 * 网络仓库依赖注入模块。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * 提供网络仓库。
     *
     * @param networkDao 网络主表 DAO。
     * @param networkConfigDao 网络配置 DAO。
     * @param assignedAddressDao 分配地址 DAO。
     * @param dnsServerDao DNS 服务器 DAO。
     * @return 网络仓库。
     */
    @Provides
    @Singleton
    fun provideNetworkRepository(
        networkDao: NetworkDao,
        networkConfigDao: NetworkConfigDao,
        assignedAddressDao: AssignedAddressDao,
        dnsServerDao: DnsServerDao,
    ): NetworkRepository {
        return NetworkRepositoryImpl(
            networkDao = networkDao,
            networkConfigDao = networkConfigDao,
            assignedAddressDao = assignedAddressDao,
            dnsServerDao = dnsServerDao,
        )
    }
}
