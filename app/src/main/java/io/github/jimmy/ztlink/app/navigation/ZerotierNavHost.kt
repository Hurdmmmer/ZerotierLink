package io.github.jimmy.ztlink.app.navigation

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.moons.MoonsScreen
import io.github.jimmy.ztlink.app.ui.components.network.NetworkDetailScreen
import io.github.jimmy.ztlink.app.ui.components.network.NetworksScreen
import io.github.jimmy.ztlink.app.ui.components.network.NetworksViewModel
import io.github.jimmy.ztlink.app.ui.components.network.join.JoinNetworkScreen
import io.github.jimmy.ztlink.app.ui.components.peers.PeersScreen
import io.github.jimmy.ztlink.app.ui.components.settings.SettingScreen
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme

/**
 * Tab 路由与顺序映射。
 *
 * 作用：
 * 1. 统一判断目标路由是否属于 Tab 主页面；
 * 2. 用 ordinal 表示 Tab 在导航条中的相对顺序，方便决定左右滑动方向；
 * 3. 避免在动画回调里反复线性查找 `ZerotierTab.entries.find { ... }`。
 */
private val TAB_ROUTE_INDEX: Map<String, Int> =
    ZerotierTab.entries.associate { it.route to it.ordinal }

/**
 * 读取路由在 Tab 中的顺序；非 Tab 路由返回 null。
 *
 * 约定：
 * - 返回 null 代表“子页面或未知路由”，统一走跨层页面转场；
 * - 返回非 null 代表“Tab 路由”，可以按顺序决定左右滑动方向。
 */
private fun tabOrdinalOf(route: String?): Int? = route?.let { TAB_ROUTE_INDEX[it] }

/**
 * 网络模块内部路由定义。
 *
 * 设计意图：
 * 1. 只在本文件内部使用，避免为极小路由集合额外拆文件。
 * 2. route 构造函数集中管理，减少字符串散落。
 */
private object NetworkRoutes {
    const val JOIN_NETWORK = "join_network"
    const val NETWORK_DETAIL = "network_detail/{networkId}"
    private const val NETWORK_DETAIL_PREFIX = "network_detail"

    fun networkDetail(networkId: String): String = "$NETWORK_DETAIL_PREFIX/$networkId"
}

/**
 * 应用主路由容器。
 *
 * 动画策略：
 * 1. Tab <-> Tab：仅内容区水平切换，底栏保持静止。
 * 2. Tab <-> 子页面：覆盖式 Push/Pop，避免双层同屏位移。
 * 3. 子页面不使用透明与缩放，保证文本与细线稳定。
 */
@Composable
fun ZerotierNavHost(
    navController: NavHostController,
    paddingValues: PaddingValues,
) {
    val motion = ZtTheme.motion
    val tabBottomPadding = paddingValues.calculateBottomPadding()

    // ── 页面转场动画配置 ─────────────────────────────────────────────
    // 同级 Tab：节奏干脆但不突兀，避免过快导致“闪切”。
    val tabSlideSpec = tween<IntOffset>(
        durationMillis = ZtTheme.motion.normalMillis,
        easing = ZtTheme.motion.standardEasing
    )

    // Tab 切换使用纯水平位移，A/B 同步滑动形成“推着走”。
    val tabEnter = { targetIndex: Int, initialIndex: Int ->
        if (targetIndex > initialIndex) {
            slideInHorizontally(tabSlideSpec) { it }
        } else {
            slideInHorizontally(tabSlideSpec) { -it }
        }
    }

    val tabExit = { targetIndex: Int, initialIndex: Int ->
        if (targetIndex > initialIndex) {
            slideOutHorizontally(tabSlideSpec) { -it }
        } else {
            slideOutHorizontally(tabSlideSpec) { it }
        }
    }

    // ── 页面转场动画配置 ─────────────────────────────────────────────

    // 子页面采用覆盖式导航动画：
    // - Push：新页从右侧滑入，旧页静止（被覆盖）
    // - Pop：当前页向右滑出，旧页静止（被揭开）
    val childOffsetSpec = tween<IntOffset>(
        durationMillis = ZtTheme.motion.normalMillis,
        easing = motion.standardEasing
    )
    val childEnter = { forward: Boolean ->
        if (forward) {
            slideInHorizontally(childOffsetSpec) { fullWidth -> fullWidth }
        } else {
            EnterTransition.None
        }
    }

    val childExit = { forward: Boolean ->
        if (forward) {
            ExitTransition.None
        } else {
            slideOutHorizontally(childOffsetSpec) { fullWidth -> fullWidth }
        }
    }

    NavHost(
        navController = navController,
        startDestination = "network_flow", // 将入口改为网络流图
        modifier = Modifier,
        // ... 保持原有动画配置 ...
        enterTransition = {
            val from = tabOrdinalOf(initialState.destination.route)
            val to = tabOrdinalOf(targetState.destination.route)
            if (from != null && to != null) tabEnter(to, from)
            else childEnter(true)
        },
        exitTransition = {
            val from = tabOrdinalOf(initialState.destination.route)
            val to = tabOrdinalOf(targetState.destination.route)
            if (from != null && to != null) tabExit(to, from)
            else childExit(true)
        },
        popEnterTransition = {
            val from = tabOrdinalOf(initialState.destination.route)
            val to = tabOrdinalOf(targetState.destination.route)
            if (from != null && to != null) tabEnter(to, from)
            else childEnter(false)
        },
        popExitTransition = {
            val from = tabOrdinalOf(initialState.destination.route)
            val to = tabOrdinalOf(targetState.destination.route)
            if (from != null && to != null) tabExit(to, from)
            else childExit(false)
        },
    ) {
        // ── 网络业务流（共享 ViewModel 作用域） ───────────────────────
        // 使用 navigation 嵌套图将“列表、加入、详情”三个页面聚合成一个整体。
        // 这样它们可以共享同一个 NetworksViewModel 实例，保证数据在不同页面间实时同步。
        navigation(
            route = "network_flow",
            startDestination = ZerotierTab.NETWORKS.route
        ) {
            // 网络列表主页
            composable(route = ZerotierTab.NETWORKS.route) { backStackEntry ->
                val parentEntry = remember(backStackEntry) { navController.getBackStackEntry("network_flow") }
                // Keep list/join/detail on the same graph-scoped ViewModel instance.
                val viewModel: NetworksViewModel = hiltViewModel(parentEntry)
                NetworksScreen(
                    viewModel = viewModel,
                    onNetworkClick = { networkId ->
                        navController.navigate(NetworkRoutes.networkDetail(networkId))
                    },
                    onJoinNetwork = {
                        navController.navigate(NetworkRoutes.JOIN_NETWORK)
                    },
                )
            }

            // 加入网络页面
            composable(route = NetworkRoutes.JOIN_NETWORK) { backStackEntry ->
                val parentEntry = remember(backStackEntry) { navController.getBackStackEntry("network_flow") }
                // 复用与列表页相同的 ViewModel 实例
                val viewModel: NetworksViewModel = hiltViewModel(parentEntry)
                
                JoinNetworkScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            // 网络详情页面
            composable(
                route = NetworkRoutes.NETWORK_DETAIL,
                arguments = listOf(navArgument("networkId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val parentEntry = remember(backStackEntry) { navController.getBackStackEntry("network_flow") }
                // 复用与列表页相同的 ViewModel 实例
                val viewModel: NetworksViewModel = hiltViewModel(parentEntry)
                // 从路由参数中提取 networkId
                val networkId = backStackEntry.arguments?.getString("networkId").orEmpty()
                
                NetworkDetailScreen(
                    networkId = networkId,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        // ── 其他 Tab 页面 ─────────────────────────────────────────────
        composable(route = ZerotierTab.PEERS.route) {
            PeersScreen(modifier = Modifier.fillMaxSize())
        }

        composable(route = ZerotierTab.MOONS.route) {
            MoonsScreen(modifier = Modifier.fillMaxSize())
        }

        composable(route = ZerotierTab.SETTINGS.route) {
            SettingScreen(
                title = stringResource(R.string.nav_settings),
                externalBottomPadding = tabBottomPadding,
            )
        }
    }
}
