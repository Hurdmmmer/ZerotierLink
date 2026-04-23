package io.github.jimmy.ztlink.service

import io.github.jimmy.ztlink.model.service.ServiceEffect
import io.github.jimmy.ztlink.model.service.ServiceState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务状态与事件存储契约。
 */
interface ServiceStateStore {

    /**
     * 持续状态流。
     */
    val state: StateFlow<ServiceState>

    /**
     * 一次性事件流。
     */
    val effects: SharedFlow<ServiceEffect>

    /**
     * 读取当前状态快照。
     *
     * @return 当前服务状态。
     */
    fun currentState(): ServiceState

    /**
     * 发布新状态。
     *
     * @param nextState 目标状态。
     */
    suspend fun setState(
        nextState: ServiceState,
    )

    /**
     * 发布一次性事件。
     *
     * @param effect 待发布事件。
     */
    suspend fun emitEffect(
        effect: ServiceEffect,
    )
}

/**
 * 服务状态仓库默认实现。
 *
 * 设计说明：
 * 1. 状态流只保留当前值，作为 UI 和 Service 的单一状态源；
 * 2. 事件流用于一次性动作，不回放历史，避免 UI 重订阅重复消费。
 */
@Singleton
class DefaultServiceStateStore @Inject constructor() : ServiceStateStore {

    override val state: MutableStateFlow<ServiceState> = MutableStateFlow(ServiceState.stopped())

    override val effects: MutableSharedFlow<ServiceEffect> =
        MutableSharedFlow(extraBufferCapacity = EFFECT_BUFFER_SIZE)

    override fun currentState(): ServiceState = state.value

    override suspend fun setState(nextState: ServiceState) {
        state.emit(nextState)
    }

    override suspend fun emitEffect(effect: ServiceEffect) {
        effects.emit(effect)
    }

    private companion object {
        /** Effect 缓冲大小。 */
        private const val EFFECT_BUFFER_SIZE = 64
    }
}


