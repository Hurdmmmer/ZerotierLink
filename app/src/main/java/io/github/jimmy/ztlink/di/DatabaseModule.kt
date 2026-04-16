package io.github.jimmy.ztlink.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jimmy.ztlink.data.network.local.AppNodeDao
import io.github.jimmy.ztlink.data.network.local.AssignedAddressDao
import io.github.jimmy.ztlink.data.network.local.DnsServerDao
import io.github.jimmy.ztlink.data.network.local.NetworkConfigDao
import io.github.jimmy.ztlink.data.network.local.NetworkDao
import io.github.jimmy.ztlink.data.network.local.SqliteAppNodeDao
import io.github.jimmy.ztlink.data.network.local.SqliteAssignedAddressDao
import io.github.jimmy.ztlink.data.network.local.SqliteDnsServerDao
import io.github.jimmy.ztlink.data.network.local.SqliteNetworkConfigDao
import io.github.jimmy.ztlink.data.network.local.SqliteNetworkDao
import io.github.jimmy.ztlink.data.network.local.ZtAppDbHelper
import javax.inject.Singleton

/**
 * 本地数据库依赖注入模块。
 *
 * 说明：
 * - 统一创建一个数据库助手实例。
 * - 各 DAO 共享同一个数据库助手，避免重复初始化。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * 提供数据库助手单例。
     *
     * @param appContext 应用级上下文。
     * @return 数据库助手实例。
     */
    @Provides
    @Singleton
    fun provideAppDbHelper(
        @ApplicationContext appContext: Context,
    ): ZtAppDbHelper {
        return ZtAppDbHelper(appContext)
    }

    /**
     * 提供网络主表 DAO。
     *
     * @param dbHelper 数据库助手。
     * @return 网络主表 DAO。
     */
    @Provides
    @Singleton
    fun provideNetworkDao(
        dbHelper: ZtAppDbHelper,
    ): NetworkDao {
        return SqliteNetworkDao(dbHelper)
    }

    /**
     * 提供网络配置 DAO。
     *
     * @param dbHelper 数据库助手。
     * @return 网络配置 DAO。
     */
    @Provides
    @Singleton
    fun provideNetworkConfigDao(
        dbHelper: ZtAppDbHelper,
    ): NetworkConfigDao {
        return SqliteNetworkConfigDao(dbHelper)
    }

    /**
     * 提供分配地址 DAO。
     *
     * @param dbHelper 数据库助手。
     * @return 分配地址 DAO。
     */
    @Provides
    @Singleton
    fun provideAssignedAddressDao(
        dbHelper: ZtAppDbHelper,
    ): AssignedAddressDao {
        return SqliteAssignedAddressDao(dbHelper)
    }

    /**
     * 提供 DNS 服务器 DAO。
     *
     * @param dbHelper 数据库助手。
     * @return DNS 服务器 DAO。
     */
    @Provides
    @Singleton
    fun provideDnsServerDao(
        dbHelper: ZtAppDbHelper,
    ): DnsServerDao {
        return SqliteDnsServerDao(dbHelper)
    }

    /**
     * 提供节点身份 DAO。
     *
     * @param dbHelper 数据库助手。
     * @return 节点身份 DAO。
     */
    @Provides
    @Singleton
    fun provideAppNodeDao(
        dbHelper: ZtAppDbHelper,
    ): AppNodeDao {
        return SqliteAppNodeDao(dbHelper)
    }
}
