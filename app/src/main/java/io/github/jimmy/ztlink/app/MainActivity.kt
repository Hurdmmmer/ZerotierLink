package io.github.jimmy.ztlink.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.github.jimmy.ztlink.app.navigation.ZerotierNavHost
import io.github.jimmy.ztlink.app.navigation.ZerotierTab
import io.github.jimmy.ztlink.app.ui.theme.AppThemeProvider
import io.github.jimmy.ztlink.app.ui.theme.ZtTheme

/**
 * Compose 单 Activity 入口。
 *
 * 设计原则：
 * 1. Activity 仅负责生命周期和根级装配。
 * 2. 导航与页面结构放在 Composable 层维护。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 内容绘制到系统栏区域；系统栏明暗由主题层统一控制。
        enableEdgeToEdge()

        setContent {
            ZerotierAppRoot()
        }
    }
}

/**
 * 应用根节点。
 *
 * 说明：
 * 1. 使用 ThemeProvider 统一承载主题状态与更新能力。
 * 2. Main 层不直接感知 SettingsViewModel，避免 Activity 与业务状态耦合。
 */
@Composable
private fun ZerotierAppRoot() {
    AppThemeProvider {
        ZerotierApp()
    }
}


/**
 * 应用主壳组件，统一承载全局背景、底部导航和路由容器。
 *
 * 动画职责划分：
 * 1. NavHost 负责“页面内容 + AppTopBar”的水平转场；
 * 2. 底栏由本壳层统一管理，Tab <-> 子页面时直接显隐，不参与页面动效；
 * 3. Tab <-> Tab 切换时，底栏保持静止，避免“底栏也跟着切换动画”。
 *
 */
@Composable
fun ZerotierApp() {
    // 全局唯一 NavController：保证底部 Tab 的回退栈行为一致可控。
    val navController = rememberNavController()
    val motion = ZtTheme.motion
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val isTabRoute = currentRoute != null && ZerotierTab.entries.any { it.route == currentRoute }
    var selectedTabRoute by rememberSaveable { mutableStateOf(ZerotierTab.NETWORKS.route) }
    LaunchedEffect(currentRoute) {
        if (currentRoute != null && isTabRoute) {
            selectedTabRoute = currentRoute
        }
    }

    // 只有 Tab 路由显示底栏；所有子页面隐藏底栏。
    // 额外处理：首帧 route 可能为 null，这里按“应显示底栏”处理，避免启动时先隐藏再滑入。
    val showBottomBar = currentRoute == null || isTabRoute
    val bottomBarEnterSpec = tween<IntOffset>(
        durationMillis = 200,
        easing = motion.emphasizedDecelerateEasing,
    )
    val bottomBarExitSpec = tween<IntOffset>(
        durationMillis = 200,
        easing = motion.emphasizedAccelerateEasing,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 全局兜底背景层：Scaffold/页面透明时由此层负责显示背景。
            .background(ZtTheme.background.baseColor)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            // 保持透明，让外层全局背景可见。
            containerColor = Color.Transparent,
            bottomBar = {
                // 关键点：
                // - Tab <-> Tab 时 showBottomBar 一直为 true，底栏不会触发显隐动画；
                // - Tab -> 子页面时，底栏直接隐藏；
                // - 子页面 -> 返回 Tab 时，底栏直接显示。
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = slideInVertically(
                        animationSpec = bottomBarEnterSpec,
                        initialOffsetY = { it },
                    ),
                    exit = slideOutVertically(
                        animationSpec = bottomBarExitSpec,
                        targetOffsetY = { it },
                    ),
                ) {
                    ZerotierBottomBar(
                        currentDestination = currentBackStackEntry?.destination,
                        selectedTabRoute = selectedTabRoute,
                        onTabClick = { tab ->
                            // 底栏导航标准策略：
                            // 1) popUpTo 起点，避免 Tab 反复点击导致深层堆栈；
                            // 2) launchSingleTop，避免同一路由重复实例；
                            // 3) restoreState，恢复已访问 Tab 的滚动和 UI 状态。
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            },
        ) { innerPadding ->
            // 将 Scaffold 内边距传给 NavHost：
            // 1) Tab 页时，内容可避让底栏；
            // 2) 子页面时，底栏隐藏后内边距会收敛到 0。
            ZerotierNavHost(
                navController = navController,
                paddingValues = innerPadding,
            )
        }
    }
}

/**
 * 底部导航栏纯展示组件。
 *
 * 设计约束：
 * 1. 只负责 UI，不持有 NavController。
 * 2. 选中逻辑由传入的 currentDestination 驱动。
 *
 * @param currentDestination 当前可见目的地，用于判断标签是否选中。
 * @param onTabClick 标签点击回调，由外层壳组件处理导航。
 */
@Composable
fun ZerotierBottomBar(
    currentDestination: NavDestination?,
    selectedTabRoute: String?,
    onTabClick: (ZerotierTab) -> Unit
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer,
        windowInsets = NavigationBarDefaults.windowInsets.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top
        )
    ) {
        ZerotierTab.entries.forEach { tab ->
            // 子页面显示时保留上一次 Tab 选中态，避免 icon 进入子页后“失焦”。
            val selected = selectedTabRoute == tab.route || currentDestination
                ?.hierarchy
                ?.any { it.route == tab.route } == true

            NavigationBarItem(
                selected = selected,
                onClick = { onTabClick(tab) },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.iconFilled else tab.iconOutlined,
                        contentDescription = stringResource(tab.titleRes)
                    )
                },
                label = { Text(text = stringResource(tab.titleRes)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}
