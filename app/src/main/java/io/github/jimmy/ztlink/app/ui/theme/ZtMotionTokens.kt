package io.github.jimmy.ztlink.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 动效令牌。
 *
 * 对应 MD3 Motion 规范的三条曲线：
 *   Standard          → 通用进出，页面内元素的默认过渡
 *   EmphasizedDecel   → 强调减速（进入），元素从屏幕外飞入，结尾平滑停止
 *   EmphasizedAccel   → 强调加速（退出），元素飞出屏幕，开头平滑启动
 *
 * 时长分级：
 *   fast   160ms → 小组件状态切换（图标、badge）
 *   normal 300ms → 页面内元素进出（卡片、列表项）
 *   slow   500ms → 整页转场（导航切换、全屏弹窗）
 *
 * 参考：https://m3.material.io/styles/motion/easing-and-duration/tokens-specs
 */
@Immutable
data class ZtMotionTokens(
    val fastMillis: Int,
    val normalMillis: Int,
    val slowMillis: Int,
    /** 通用曲线：适合大多数 UI 状态变化 */
    val standardEasing: Easing,
    /** 强调减速曲线：进入动画，结尾丝滑 */
    val emphasizedDecelerateEasing: Easing,
    /** 强调加速曲线：退出动画，开头平滑启动后快速离场 */
    val emphasizedAccelerateEasing: Easing,
)

val LocalZtMotionTokens = staticCompositionLocalOf {
    ZtMotionTokens(
        fastMillis   = 160,
        normalMillis = 300,
        slowMillis   = 500,
        // MD3 Standard：https://m3.material.io/styles/motion/easing-and-duration/tokens-specs
        standardEasing             = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f),
        // MD3 Emphasized Decelerate（进入）
        emphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f),
        // MD3 Emphasized Accelerate（退出）
        emphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f),
    )
}

// ── 快捷扩展 ──────────────────────────────────────────────────────────────

/** 小组件状态切换，使用标准曲线 */
fun <T> ZtMotionTokens.fastSpec()   = tween<T>(fastMillis,   easing = standardEasing)

/** 页面内元素进入，使用强调减速曲线 */
fun <T> ZtMotionTokens.enterSpec()  = tween<T>(normalMillis, easing = emphasizedDecelerateEasing)

/** 页面内元素退出，使用强调加速曲线 */
fun <T> ZtMotionTokens.exitSpec()   = tween<T>(normalMillis, easing = emphasizedAccelerateEasing)

/** 整页转场，慢速 + 强调减速 */
fun <T> ZtMotionTokens.pageSpec()   = tween<T>(slowMillis,   easing = emphasizedDecelerateEasing)