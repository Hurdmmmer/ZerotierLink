package io.github.jimmy.ztlink.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 业务语义色扩展。
 *
 * 为什么不用 MD3 colorScheme？
 * colorScheme 的角色位（primary / secondary / tertiary）是通用 UI 角色，
 * 无法承载"直连节点 / 中继节点 / root 节点"这类 ZeroTier 业务语义。
 * 运行时值由 Theme.kt 的 buildSemanticColors() 从当前色盘派生，
 * 确保语义色与 MD3 colorScheme 的色相关系始终协调。
 *
 * 默认值仅供 @Preview 使用，不用于生产运行时。
 *
 * @param connected  直连 Peer 状态指示色（绿色系，表示健康直连）
 * @param relay      中继路由状态指示色（紫色系，表示经由 Moon/Planet 中转）
 * @param root       Root / Moon 节点标识色（蓝色系，表示基础设施节点）
 * @param warning    警告状态色（琥珀色系）
 * @param errorStrong 强错误状态色，对应 MD3 error 角色
 * @param inactive   离线 / 未激活状态色，对应 MD3 outline 角色
 */
@Immutable
data class ZtSemanticColors(
    val connected: Color,
    val relay: Color,
    val root: Color,
    val warning: Color,
    val errorStrong: Color,
    val inactive: Color,
)

/**
 * CompositionLocal 占位默认值（仅供 Preview）。
 * 生产环境中由 ZerotierLinkTheme 的 buildSemanticColors() 覆盖注入。
 */
val LocalZtSemanticColors = staticCompositionLocalOf {
    ZtSemanticColors(
        connected   = Color(0xFF1A9B68),  // 翠绿：直连健康
        relay       = Color(0xFF7A6DD4),  // 柔紫：中继路由
        root        = Color(0xFF3D74D4),  // 品蓝：基础设施
        warning     = Color(0xFFD4921A),  // 琥珀：警告
        errorStrong = Color(0xFFD03848),  // 玫红：错误
        inactive    = Color(0xFF8A96AA),  // 中性灰：离线
    )
}