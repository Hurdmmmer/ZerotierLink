package io.github.jimmy.ztlink.data.settings

import android.net.Uri
import io.github.jimmy.ztlink.app.ui.components.settings.PlanetSourceType
import io.github.jimmy.ztlink.app.ui.components.settings.SettingsUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设置状态单例持有器（应用级唯一状态源）。
 *
 * 设计目标：
 * 1. 统一设置状态读写入口，避免多个 ViewModel 各自持有快照导致相互覆盖。
 * 2. 保留 warmup 首帧优化，同时支持后续强制刷新最新持久化值。
 * 3. 对外暴露 StateFlow，让任意页面/主题层都能订阅同一份设置状态。
 *
 * @property settingsStore 设置持久化存储。
 * @property settingsStartupWarmup 启动预热缓存。
 * @property planetFileStore planet 文件导入与落盘管理器。
 */
@Singleton
class SettingsStateHolder @Inject constructor(
    private val settingsStore: SettingsStore,
    private val settingsStartupWarmup: SettingsStartupWarmup,
    private val planetFileStore: PlanetFileStore,
) {
    /**
     * 设置读写互斥锁。
     *
     * 关键逻辑：
     * - 串行化 refresh/update，避免多 ViewModel 并发时发生“旧快照覆盖新值”。
     */
    private val stateMutex: Mutex = Mutex()

    /**
     * 当前设置状态流。
     *
     * 关键逻辑：
     * - 优先使用 warmup 缓存作为初始值，避免首帧闪烁；
     * - 若缓存不存在，使用默认值兜底，后续再强制刷新。
     */
    private val _state: MutableStateFlow<SettingsUiState> = MutableStateFlow(
        normalizeIntranetProbePolicyState(
            settingsStartupWarmup.cachedStateOrNull() ?: SettingsUiState(),
        ),
    )

    /**
     * 只读设置状态流。
     */
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /**
     * 读取当前内存状态快照。
     *
     * @return 当前已发布的设置状态。
     */
    fun currentState(): SettingsUiState = _state.value

    /**
     * 从持久化层刷新状态。
     *
     * @param forceRefresh 是否强制忽略 warmup 缓存读取最新持久化值。
     * @return 刷新后的设置状态。
     */
    suspend fun refreshFromStore(forceRefresh: Boolean): SettingsUiState {
        return stateMutex.withLock {
            val rawState = settingsStartupWarmup.warmup(forceRefresh = forceRefresh)
            val latestState = normalizeIntranetProbePolicyState(rawState)
            if (latestState != rawState) {
                // 关键逻辑：
                // 当历史持久化数据不满足当前策略不变式时，刷新阶段立即回写，
                // 避免后续仍被旧数据污染（例如旧版脏值带空白符）。
                settingsStartupWarmup.updateCachedState(latestState)
                settingsStore.writeState(latestState)
            }
            _state.value = latestState
            latestState
        }
    }

    /**
     * 原子更新状态并持久化。
     *
     * @param reducer 基于旧状态生成新状态的转换函数。
     * @return 更新后的新状态。
     */
    suspend fun updateState(
        reducer: (SettingsUiState) -> SettingsUiState,
    ): SettingsUiState {
        return stateMutex.withLock {
            val newState = reducer(_state.value)
            applyStateLocked(newState)
        }
    }

    /**
     * 导入本地 planet 文件并更新设置状态。
     *
     * @param displayName 用于 UI 展示的文件名。
     * @param rawUri 文件 Uri 字符串。
     * @return 导入结果；失败时可由上层映射为提示文案。
     */
    suspend fun importPlanetFromFile(
        displayName: String,
        rawUri: String,
    ): PlanetFileStore.ImportResult {
        val sourceUri = runCatching { Uri.parse(rawUri) }.getOrNull()
            ?: return PlanetFileStore.ImportResult.Failure(
                PlanetFileStore.FailureReason.CANNOT_READ_SOURCE
            )

        val importResult = planetFileStore.importFromUri(sourceUri)
        if (importResult !is PlanetFileStore.ImportResult.Success) {
            return importResult
        }

        stateMutex.withLock {
            // 关键逻辑：
            // 导入成功后立即写回来源类型与来源值，保证下次进入页面仍可恢复来源信息。
            val newState = _state.value.copy(
                planetUseCustom = true,
                planetSourceType = PlanetSourceType.FILE,
                planetSourceDisplay = displayName,
                planetSourceRawValue = rawUri,
            )
            applyStateLocked(newState)
        }
        return importResult
    }

    /**
     * 从 URL 下载 planet 文件并更新设置状态。
     *
     * @param sourceUrl 输入 URL。
     * @return 导入结果；失败时可由上层映射为提示文案。
     */
    suspend fun importPlanetFromUrl(
        sourceUrl: String,
    ): PlanetFileStore.ImportResult {
        val importResult = planetFileStore.importFromUrl(sourceUrl)
        if (importResult !is PlanetFileStore.ImportResult.Success) {
            return importResult
        }

        stateMutex.withLock {
            // 关键逻辑：
            // URL 导入成功后将来源类型改为 URL，避免显示与真实来源不一致。
            val newState = _state.value.copy(
                planetUseCustom = true,
                planetSourceType = PlanetSourceType.URL,
                planetSourceDisplay = sourceUrl,
                planetSourceRawValue = sourceUrl,
            )
            applyStateLocked(newState)
        }
        return importResult
    }

    /**
     * 在持锁上下文中应用新状态并持久化。
     *
     * @param newState 需要生效的新设置快照。
     */
    private suspend fun applyStateLocked(newState: SettingsUiState): SettingsUiState {
        val normalizedState = normalizeIntranetProbePolicyState(newState)
        // 先更新内存状态，确保 UI 订阅方立即看到最新值。
        _state.value = normalizedState
        // 同步更新 warmup 缓存，避免后续新实例回退到旧快照。
        settingsStartupWarmup.updateCachedState(normalizedState)
        // 最后写入持久化层，保证重启后仍能恢复。
        settingsStore.writeState(normalizedState)
        return normalizedState
    }

    /**
     * 归一化“内网 IP 探测”相关状态，保证策略语义一致。
     *
     * 关键逻辑：
     * 1. 自动探测关闭时，不清空用户已配置的内网 IP（仅策略失效）；
     * 2. 自定义 Planet 关闭时，策略层会整体禁用探测功能，但不改写 auto 开关值；
     * 3. 仅做首尾空白归一化。
     */
    private fun normalizeIntranetProbePolicyState(state: SettingsUiState): SettingsUiState {
        return state.copy(
            planetIntranetProbeIp = state.planetIntranetProbeIp.trim(),
        )
    }
}
