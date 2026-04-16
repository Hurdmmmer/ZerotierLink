package io.github.jimmy.ztlink.app.navigation

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
 * 1. Tab <-> Tab：只动页面内容层（含 AppTopBar），底栏由外层保持静止；
 * 2. Tab <-> 子页面：轻微横移 + 淡入淡出，避免突兀；
 * 3. 前进/返回对称：enter 用 emphasizedDecelerate，exit 用 emphasizedAccelerate。
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
    val tabDurationMillis = 220
    val tabEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val tabSlideSpec = tween<IntOffset>(
        durationMillis = tabDurationMillis,
        easing = tabEasing
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

    // 子页面转场：统一使用 standardEasing 减少“加速离场”带来的跳跃感。
    // 时长设为 300ms，配合微缩放，能产生一种类似“揭开”或“覆盖”的层级流动感。
    val childDurationMillis = 300
    val childSpec = tween<Float>(durationMillis = childDurationMillis, easing = motion.standardEasing)
    val childOffsetSpec = tween<IntOffset>(durationMillis = childDurationMillis, easing = motion.standardEasing)

    // 进入子页面：淡入 + 缩放 + 水平微移
    val childEnter = { forward: Boolean ->
        fadeIn(animationSpec = childSpec) +
                scaleIn(
                    animationSpec = childSpec,
                    initialScale = if (forward) 0.96f else 1.04f // 前进时从小变大，返回时从大变小
                ) +
                slideInHorizontally(childOffsetSpec) { (it * if (forward) 0.1f else -0.1f).toInt() }
    }

    // 离开子页面：淡出 + 缩放 + 水平微移
    val childExit = { forward: Boolean ->
        fadeOut(animationSpec = childSpec) +
                scaleOut(
                    animationSpec = childSpec,
                    targetScale = if (forward) 1.04f else 0.96f // 前进时被推远，返回时向后缩进
                ) +
                slideOutHorizontally(childOffsetSpec) { (it * if (forward) -0.1f else 0.1f).toInt() }
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
            composable(route = ZerotierTab.NETWORKS.route) {
                // NetworksScreen 内部会通过 hiltViewModel 获取 ViewModel。
                // 如果需要共享 ViewModel，可以在 NetworksScreen 内部使用特定的 key。
                // 但目前建议保持简单，如果需要共享作用域，后续再优化。
                NetworksScreen(
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
