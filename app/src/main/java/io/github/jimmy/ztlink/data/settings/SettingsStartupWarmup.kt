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
    /** Planet 文件存储。 */
    private val planetFileStore: PlanetFileStore,
) {

    /** 预热互斥锁，避免重复并发预热导致磁盘 IO 重复执行。 */
    private val warmupMutex: Mutex = Mutex()

    /** 预热后的设置快照缓存。 */
    private val cachedStateRef: AtomicReference<SettingsUiState?> = AtomicReference(null)

    /**
     * 执行一次设置预热。
     *
     * @param forceRefresh 是否忽略内存缓存并强制读取最新持久化值。
     * @return 预热完成后的设置快照。
     */
    suspend fun warmup(forceRefresh: Boolean = false): SettingsUiState {
        if (!forceRefresh) {
            cachedStateRef.get()?.let { return it }
        }
        return warmupMutex.withLock {
            if (!forceRefresh) {
                cachedStateRef.get()?.let { return@withLock it }
            }
            val persisted = settingsStore.readState(forceDisableCustomPlanet = false)
            // 关键逻辑：
            // 与老项目保持一致：当“启用了自定义 planet”但文件已丢失时，必须自动回退到系统 planet，
            // 否则 runtime 会持续读取不存在文件，导致行为与用户预期不一致。
            val effective = if (persisted.planetUseCustom && !planetFileStore.hasCustomPlanetFile()) {
                settingsStore.disableCustomPlanet()
                persisted.copy(
                    planetUseCustom = false,
                    planetAutoRouteCheck = false,
                )
            } else {
                persisted
            }
            cachedStateRef.set(effective)
            effective
        }
    }

    /**
     * 更新预热缓存。
     *
     * 用途：
     * 1. 设置页写入后同步内存快照，避免后续新实例仍读取旧缓存；
     * 2. 保持“首帧快照”与最新用户设置一致。
     */
    fun updateCachedState(state: SettingsUiState) {
        cachedStateRef.set(state)
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
