package io.github.jimmy.ztlink.data.settings

import io.github.jimmy.ztlink.app.ui.components.settings.SettingsUiState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设置启动预热器。
 *
 * 目标：
 * 1. 在 Application 启动阶段提前读取设置快照。
 * 2. 为首帧提供“已就绪状态”，避免页面先用默认值再跳到持久化值导致闪烁。
 * 3. 统一处理 custom planet 文件缺失时的状态回退规则。
 */
@Singleton
class SettingsStartupWarmup @Inject constructor(
    /** 设置持久化存储。 */
    private val settingsStore: SettingsStore,
    /** planet 文件落盘管理器。 */
    private val planetFileStore: PlanetFileStore,
) {

    /** 预热互斥锁，避免重复并发预热导致磁盘 IO 重复执行。 */
    private val warmupMutex: Mutex = Mutex()

    /** 预热后的设置快照缓存。 */
    private val cachedStateRef: AtomicReference<SettingsUiState?> = AtomicReference(null)

    /**
     * 执行一次设置预热。
     *
     * @return 预热完成后的设置快照。
     */
    suspend fun warmup(): SettingsUiState {
        cachedStateRef.get()?.let { return it }
        return warmupMutex.withLock {
            cachedStateRef.get()?.let { return@withLock it }
            val hasPlanetFile = planetFileStore.hasCustomPlanetFile()
            val shouldForceDisableCustomPlanet = !hasPlanetFile
            val state = settingsStore.readState(
                forceDisableCustomPlanet = shouldForceDisableCustomPlanet
            )
            if (shouldForceDisableCustomPlanet && state.planetUseCustom) {
                // 保险兜底：若历史状态异常，强制回写关闭开关，避免后续状态不一致。
                settingsStore.disableCustomPlanet()
            }
            cachedStateRef.set(state)
            state
        }
    }

    /**
     * 获取当前缓存的设置快照。
     *
     * @return 若预热已完成返回快照；否则返回 null。
     */
    fun cachedStateOrNull(): SettingsUiState? {
        return cachedStateRef.get()
    }
}
