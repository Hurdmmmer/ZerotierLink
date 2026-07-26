package io.github.jimmy.ztlink.service.runtime

import io.github.jimmy.ztlink.model.network.NetworkId

/**
 * 已存在 runtime 的复用决策。
 *
 * runtime 内部的 TUN 桥接器在创建时固定绑定 networkId，因此只有启动模式一致、
 * 目标网络没有变化且所有核心 worker 真实存活时才能复用。任何一项不满足都必须先
 * 完整停止旧 runtime，再执行冷启动，不能携带旧内核快照继续启动。
 */
internal enum class RuntimeReuseDecision {
    REUSE,
    RESTART_PLANET_MODE_CHANGED,
    RESTART_NETWORK_CHANGED,
    RESTART_UNHEALTHY,
}

/**
 * 判断已存在的 runtime 是否可以安全复用。
 *
 * @param currentNetworkId 旧 runtime 实际绑定的网络。
 * @param targetNetworkId 本次要求连接的网络；为空表示沿用旧网络。
 * @param planetModeMatches 旧 runtime 的 Planet 模式是否明确且与当前设置一致。
 * @param kernelAlive Node、UDP Socket 与全部核心 worker 是否仍存活。
 */
internal fun decideRuntimeReuse(
    currentNetworkId: NetworkId?,
    targetNetworkId: NetworkId?,
    planetModeMatches: Boolean,
    kernelAlive: Boolean,
): RuntimeReuseDecision {
    if (!planetModeMatches) {
        return RuntimeReuseDecision.RESTART_PLANET_MODE_CHANGED
    }
    if (targetNetworkId != null && targetNetworkId != currentNetworkId) {
        return RuntimeReuseDecision.RESTART_NETWORK_CHANGED
    }
    if (!kernelAlive) {
        return RuntimeReuseDecision.RESTART_UNHEALTHY
    }
    return RuntimeReuseDecision.REUSE
}
