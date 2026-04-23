package io.github.jimmy.ztlink.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jimmy.ztlink.service.AppWhitelistApplier
import io.github.jimmy.ztlink.service.DefaultServiceStateStore
import io.github.jimmy.ztlink.service.ServiceStateStore
import javax.inject.Singleton

/**
 * Runtime 相关依赖注入模块。
 */
@Module
@InstallIn(SingletonComponent::class)
object RuntimeDiManage {

    /**
     * 提供服务状态仓库。
     *
     * @param impl 默认实现。
     * @return 状态仓库。
     */
    @Provides
    @Singleton
    fun provideServiceStateStore(
        impl: DefaultServiceStateStore,
    ): ServiceStateStore {
        return impl
    }

    /**
     * 提供应用白名单应用器。
     */
    @Provides
    @Singleton
    fun provideAppWhitelistApplier(): AppWhitelistApplier {
        return AppWhitelistApplier()
    }
}

