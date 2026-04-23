package io.github.jimmy.ztlink.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务动作派发器。
 *
 * 职责：
 * 1. 将业务动作转换为 Service Intent；
 * 2. 统一处理 Android 前台服务启动兼容逻辑；
 * 3. 为 ViewModel/广播/通知提供同一派发入口。
 */
@Singleton
class ServiceActionDispatcher @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) {

    /**
     * 使用应用上下文派发动作。
     */
    fun dispatch(action: ServiceAction) {
        val intent = action.toIntent(appContext)
        val intentAction = intent.action.orEmpty()
        logChain("派发开始 动作=${action.javaClass.simpleName} Intent=$intentAction 原因=${action.reason}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
        logChain("派发结束 动作=${action.javaClass.simpleName} Intent=$intentAction")
    }

    /**
     * 动作转换为 Service Intent。
     */
    private fun ServiceAction.toIntent(context: Context): Intent {
        return when (this) {
            is ServiceAction.StartOrResume -> {
                Intent(context, ZeroTierVpnService::class.java).apply {
                    action = ZeroTierVpnService.ACTION_START_OR_RESUME
                    putExtra(
                        ZeroTierVpnService.EXTRA_HAS_EXPLICIT_NETWORK_ID,
                        hasExplicitNetworkId,
                    )
                    if (hasExplicitNetworkId && targetNetworkId != null) {
                        putExtra(ZeroTierVpnService.EXTRA_NETWORK_ID, targetNetworkId.value)
                    }
                    putExtra(ZeroTierVpnService.EXTRA_REASON, reason)
                }
            }

            is ServiceAction.Join -> {
                Intent(context, ZeroTierVpnService::class.java).apply {
                    action = ZeroTierVpnService.ACTION_JOIN
                    putExtra(ZeroTierVpnService.EXTRA_NETWORK_ID, params.networkId.value)
                    putExtra(ZeroTierVpnService.EXTRA_ROUTE_VIA_ZERO_TIER, params.routeViaZeroTier)
                    putExtra(ZeroTierVpnService.EXTRA_DNS_MODE_CODE, params.dnsMode.code)
                    putExtra(ZeroTierVpnService.EXTRA_CUSTOM_DNS, params.customDns)
                    putExtra(ZeroTierVpnService.EXTRA_REASON, reason)
                }
            }

            is ServiceAction.Leave -> {
                Intent(context, ZeroTierVpnService::class.java).apply {
                    action = ZeroTierVpnService.ACTION_LEAVE
                    putExtra(ZeroTierVpnService.EXTRA_NETWORK_ID, networkId.value)
                    putExtra(ZeroTierVpnService.EXTRA_REASON, reason)
                }
            }

            is ServiceAction.Stop -> {
                Intent(context, ZeroTierVpnService::class.java).apply {
                    action = ZeroTierVpnService.ACTION_STOP
                    putExtra(ZeroTierVpnService.EXTRA_KEEP_SERVICE_ALIVE, keepServiceAlive)
                    putExtra(ZeroTierVpnService.EXTRA_REASON, reason)
                }
            }

            is ServiceAction.EnterMonitorOnly -> {
                Intent(context, ZeroTierVpnService::class.java).apply {
                    action = ZeroTierVpnService.ACTION_ENTER_MONITOR_ONLY
                    if (networkId != null) {
                        putExtra(ZeroTierVpnService.EXTRA_NETWORK_ID, networkId.value)
                    }
                    putExtra(ZeroTierVpnService.EXTRA_DETAIL, detail)
                    putExtra(ZeroTierVpnService.EXTRA_REASON, reason)
                }
            }

            is ServiceAction.ResumeRelay -> {
                Intent(context, ZeroTierVpnService::class.java).apply {
                    action = ZeroTierVpnService.ACTION_RESUME_RELAY
                    putExtra(ZeroTierVpnService.EXTRA_REASON, reason)
                }
            }

            is ServiceAction.SyncNetworkConfig -> {
                Intent(context, ZeroTierVpnService::class.java).apply {
                    action = ZeroTierVpnService.ACTION_SYNC_NETWORK_CONFIG
                    putExtra(ZeroTierVpnService.EXTRA_NETWORK_ID, networkId.value)
                    putExtra(ZeroTierVpnService.EXTRA_REASON, reason)
                }
            }

            is ServiceAction.ReconfigureTunnel -> {
                Intent(context, ZeroTierVpnService::class.java).apply {
                    action = ZeroTierVpnService.ACTION_RECONFIGURE_TUNNEL
                    putExtra(ZeroTierVpnService.EXTRA_NETWORK_ID, networkId.value)
                    putExtra(ZeroTierVpnService.EXTRA_ROUTE_VIA_ZERO_TIER, routeViaZeroTier)
                    putExtra(ZeroTierVpnService.EXTRA_DNS_MODE_CODE, dnsMode.code)
                    putStringArrayListExtra(
                        ZeroTierVpnService.EXTRA_CUSTOM_DNS_SERVERS,
                        ArrayList(customDnsServers),
                    )
                    putStringArrayListExtra(
                        ZeroTierVpnService.EXTRA_WHITELIST_PACKAGES,
                        ArrayList(whitelistPackages),
                    )
                    putExtra(
                        ZeroTierVpnService.EXTRA_INCLUDE_BUILT_IN_WHITELIST,
                        includeBuiltInWhitelistPackages,
                    )
                    putExtra(ZeroTierVpnService.EXTRA_REASON, reason)
                }
            }

            is ServiceAction.OrbitMoons -> {
                Intent(context, ZeroTierVpnService::class.java).apply {
                    action = ZeroTierVpnService.ACTION_ORBIT_MOONS
                    putExtra(
                        ZeroTierVpnService.EXTRA_MOON_WORLD_IDS,
                        moons.map { it.moonWorldId }.toLongArray(),
                    )
                    putExtra(
                        ZeroTierVpnService.EXTRA_MOON_SEEDS,
                        moons.map { it.moonSeed }.toLongArray(),
                    )
                    putExtra(ZeroTierVpnService.EXTRA_REASON, reason)
                }
            }

            is ServiceAction.DeorbitMoons -> {
                Intent(context, ZeroTierVpnService::class.java).apply {
                    action = ZeroTierVpnService.ACTION_DEORBIT_MOONS
                    putExtra(
                        ZeroTierVpnService.EXTRA_MOON_WORLD_IDS,
                        moonWorldIds.toLongArray(),
                    )
                    putExtra(ZeroTierVpnService.EXTRA_REASON, reason)
                }
            }

            is ServiceAction.QueryPeers -> {
                Intent(context, ZeroTierVpnService::class.java).apply {
                    action = ZeroTierVpnService.ACTION_QUERY_PEERS
                    putExtra(ZeroTierVpnService.EXTRA_REASON, reason)
                }
            }

            is ServiceAction.QueryNode -> {
                Intent(context, ZeroTierVpnService::class.java).apply {
                    action = ZeroTierVpnService.ACTION_QUERY_NODE
                    putExtra(ZeroTierVpnService.EXTRA_REASON, reason)
                }
            }

            is ServiceAction.QueryNetworkConfig -> {
                Intent(context, ZeroTierVpnService::class.java).apply {
                    action = ZeroTierVpnService.ACTION_QUERY_NETWORK_CONFIG
                    putExtra(ZeroTierVpnService.EXTRA_NETWORK_ID, networkId.value)
                    putExtra(ZeroTierVpnService.EXTRA_REASON, reason)
                }
            }
        }
    }

    private fun logChain(message: String) {
        Log.i(TAG, "[$LOG_KEY] $message")
    }

    private companion object {
        private const val TAG = "ServiceActionDispatcher"
        private const val LOG_KEY = "ZTL_CHAIN"
    }
}

