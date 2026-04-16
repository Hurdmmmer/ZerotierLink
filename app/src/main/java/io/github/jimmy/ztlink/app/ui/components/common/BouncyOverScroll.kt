package io.github.jimmy.ztlink.app.ui.components.common

import android.annotation.SuppressLint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * BouncyOverScroll -
 * App 内容页面中上下滑动的动画控制器，提供类似 iOS 弹性回弹的视觉反馈。
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun BouncyOverScroll(
    modifier: Modifier = Modifier,
    pullEnabled: Boolean = true,
    pushEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    // 调试开关：需要临时关闭日志时改为 false。
    val logEnabled = true
    val scope = rememberCoroutineScope()
    // 逻辑位移：用于同步驱动 UI
    val syncOffset = remember { mutableFloatStateOf(0f) }
    // 动画控制：用于平滑回弹
    val animatable = remember { Animatable(0f) }
    // 动画 Job：用于在新触摸时即刻中断回弹
    val recoveryJob = remember { mutableStateOf<Job?>(null) }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val maxBounceLimit = screenHeightPx * 0.20f
    // 至少达到这个位移才触发“弹性回弹”动画；过小位移直接归零，避免“无中生有”的弹跳。
    val minRecoverOffset = with(density) { 6.dp.toPx() }

    val nestedScrollConnection = remember(
        pullEnabled,
        pushEnabled,
        maxBounceLimit,
        minRecoverOffset,
    ) {
//        fun log(message: String) {
//            if (logEnabled) Log.d("BouncyOverScroll", message)
//        }

        object : NestedScrollConnection {
            // 记录本轮手势/惯性周期中是否发生过真实过冲位移。
            // 用于阻断“没有过冲却触发回弹”的不自然效果。
            private var hadOverscroll = false
            // 记录连续“小惯性帧”数量，避免单帧抖动误触发提前回弹。
            private var consecutiveSmallSideEffect = 0
            // 回弹接管后，屏蔽同一轮残余惯性 SideEffect 写入。
            // 目的：避免“回弹结束后又被同轮惯性再次拉偏”的二段弹。
            private var blockSideEffectUntilUserInput = false
            // 记录一轮 fling 的速度，用于在 SideEffect 首次形成过冲时做“动量接管”。
            private var pendingFlingVelocityY = 0f
            private var hasPendingFling = false
            // pending fling 接管最小门槛：
            // - 位移太小或残余增量太小都不接管，避免“轻微触边也重弹”。
            private val pendingTakeoverMinOffset = minRecoverOffset
            private val pendingTakeoverMinDelta = 8f

            private fun stopRecovery() {
                recoveryJob.value?.cancel()
                recoveryJob.value = null
//                log("stopRecovery()")
            }

            private fun isRecovering(): Boolean = recoveryJob.value?.isActive == true

            // 根据当前过冲位移动态限速，保证“小过冲轻弹、大过冲强弹”。
            private fun computeHandoffVelocity(rawVelocityY: Float, currentOffset: Float): Float {
                val sameDirectionVelocity = if (rawVelocityY * currentOffset > 0f) rawVelocityY else 0f
                if (sameDirectionVelocity == 0f) return 0f
                // 当位移达到约 35% 上限时放开到最大上限；更小位移按比例收敛。
                val ratio = (abs(currentOffset) / (maxBounceLimit * 0.35f)).coerceIn(0.2f, 1f)
                val dynamicCap = 3500f * ratio
                return sameDirectionVelocity.coerceIn(-dynamicCap, dynamicCap)
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 只要用户开始新的触摸，立即停止回弹
                if (source == NestedScrollSource.UserInput) {
                    stopRecovery()
                    consecutiveSmallSideEffect = 0
                    blockSideEffectUntilUserInput = false
                    hasPendingFling = false
                    pendingFlingVelocityY = 0f
//                    log("onPreScroll(UserInput): reset recovery flag")
                }

                val current = syncOffset.floatValue
                if (abs(current) < 0.1f) return Offset.Zero

                // 在拉伸状态下，反向滑动优先回收位移
                if ((current > 0 && available.y < 0) || (current < 0 && available.y > 0)) {
                    val consumedY = if (current > 0) {
                        available.y.coerceAtLeast(-current)
                    } else {
                        available.y.coerceAtMost(-current)
                    }
                    syncOffset.floatValue = current + consumedY
//                    log(
//                        "onPreScroll recycle: source=$source, availableY=${available.y}, " +
//                            "current=$current -> ${syncOffset.floatValue}, consumedY=$consumedY"
//                    )
                    if (abs(syncOffset.floatValue) < 0.1f) {
                        hadOverscroll = false
//                        log("onPreScroll recycle: offset~0, hadOverscroll=false")
                    }
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val delta = available.y
                if (delta == 0f) return Offset.Zero

                val current = syncOffset.floatValue
                if (delta > 0 && !pullEnabled) return Offset.Zero
                if (delta < 0 && !pushEnabled) return Offset.Zero

                // 关键：回弹动画接管期间，忽略后续 SideEffect 写入，
                // 避免“回弹中又被惯性推偏”造成二段跳和拖泥带水。
                if (source == NestedScrollSource.SideEffect && isRecovering()) {
                    return Offset.Zero
                }
                // 关键：即使 recovery 已结束，只要还在同一轮惯性链路，也忽略残余 SideEffect。
                if (source == NestedScrollSource.SideEffect && blockSideEffectUntilUserInput) {
                    return Offset.Zero
                }

                // 双阻尼分流：
                // 1) UserInput：保留大幅弹性（主手感）。
                // 2) SideEffect：仅做小幅微弹，避免“硬停”同时抑制过冲。
                val baseResistance = if (source == NestedScrollSource.UserInput) {
                    0.55f
                } else {
                    0.12f
                }
                // 关键：limit 始终使用统一上限，避免 UserInput -> SideEffect 时出现突变 clamp。
                val resistance = baseResistance * (1f - abs(current) / maxBounceLimit).coerceAtLeast(0f)

                val newOffset = (current + delta * resistance).coerceIn(-maxBounceLimit, maxBounceLimit)
                val applied = newOffset - current
//                log(
//                    "onPostScroll: source=$source, delta=$delta, current=$current, " +
//                        "resistance=$resistance, newOffset=$newOffset, applied=$applied, " +
//                        "hadOverscroll=$hadOverscroll, recovering=${isRecovering()}"
//                )

                if (abs(applied) > 0.01f) {
                    syncOffset.floatValue = newOffset
                    hadOverscroll = true
                    // 统一接管策略（关键）：
                    // 如果 PreFling 时还没法接管（比如 current 接近 0），
                    // 则在 SideEffect 刚形成有效过冲时立刻接管回弹，并继承 fling 动量。
                    if (
                        source == NestedScrollSource.SideEffect &&
                        hasPendingFling &&
                        !isRecovering() &&
                        abs(newOffset) >= pendingTakeoverMinOffset &&
                        abs(delta) >= pendingTakeoverMinDelta
                    ) {
                        val handoffVelocity = computeHandoffVelocity(
                            rawVelocityY = pendingFlingVelocityY,
                            currentOffset = newOffset
                        )
                        hasPendingFling = false
                        pendingFlingVelocityY = 0f
                        consecutiveSmallSideEffect = 0
                        blockSideEffectUntilUserInput = true
//                        log(
//                            "onPostScroll: takeover by pending fling, " +
//                                "delta=$delta, newOffset=$newOffset, handoffVelocity=$handoffVelocity"
//                        )
                        startRecovery(newOffset, handoffVelocity)
                        return Offset(0f, delta)
                    }
                    // 解决“高速时停顿”：
                    // 当惯性进入减速末段且已有真实过冲时，提前接管回弹，
                    // 避免等待 onPostFling 才开始而产生空档。
                    if (
                        source == NestedScrollSource.SideEffect &&
                        abs(delta) < 14f &&
                        hadOverscroll &&
                        abs(newOffset) >= minRecoverOffset &&
                        !isRecovering()
                    ) {
                        consecutiveSmallSideEffect += 1
                    } else if (source == NestedScrollSource.SideEffect) {
                        consecutiveSmallSideEffect = 0
                    }
                    // 兜底策略：低速场景没有 pending fling 时，尾段小帧再接管。
                    val tailReady = consecutiveSmallSideEffect >= 3
                    if (
                        source == NestedScrollSource.SideEffect &&
                        hadOverscroll &&
                        abs(newOffset) >= minRecoverOffset &&
                        !isRecovering() &&
                        tailReady
                    ) {
                        consecutiveSmallSideEffect = 0
                        blockSideEffectUntilUserInput = true
//                        log(
//                            "onPostScroll: trigger early recovery, " +
//                                "delta=$delta, newOffset=$newOffset, minRecoverOffset=$minRecoverOffset, " +
//                                "tailReady=$tailReady"
//                        )
                        startRecovery(newOffset)
                    }
                    // 仅消费当前链路位移，避免过度吞噬可用滚动量。
                    return Offset(0f, delta)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // 在已有过冲位移时，fling 开始就直接接管回弹，
                // 并把一部分惯性速度作为初速度交给回弹动画，
                // 这样不会出现“先继续漂一段，过会儿才回弹”的延迟感。
                val current = syncOffset.floatValue
                consecutiveSmallSideEffect = 0
                hasPendingFling = abs(available.y) >= 800f
                pendingFlingVelocityY = if (hasPendingFling) available.y else 0f
                if (hadOverscroll && abs(current) >= minRecoverOffset && !isRecovering()) {
                    blockSideEffectUntilUserInput = true
                    val handoffVelocity = computeHandoffVelocity(
                        rawVelocityY = available.y,
                        currentOffset = current
                    )
                    hasPendingFling = false
                    pendingFlingVelocityY = 0f
//                    log(
//                        "onPreFling: takeover recovery, available=$available, current=$current, " +
//                            "handoffVelocity=$handoffVelocity"
//                    )
                    startRecovery(current, handoffVelocity)
                    return available
                }
//                log(
//                    "onPreFling: available=$available, reset consecutiveSmallSideEffect=0, " +
//                        "hasPendingFling=$hasPendingFling"
//                )
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val current = syncOffset.floatValue
                // 如果 Fling 结束后仍处于拉伸状态（或因 Fling 刚产生的拉伸），启动回弹
                if (hadOverscroll && abs(current) >= minRecoverOffset && !isRecovering()) {
//                    log(
//                        "onPostFling: trigger recovery, current=$current, " +
//                            "minRecoverOffset=$minRecoverOffset"
//                    )
                    blockSideEffectUntilUserInput = true
                    startRecovery(current)
                } else if (!isRecovering() && abs(current) < minRecoverOffset && abs(current) > 0.01f) {
                    // 位移很小则直接归零，避免出现“本不该弹却弹一下”。
                    stopRecovery()
                    syncOffset.floatValue = 0f
                    hadOverscroll = false
//                    log(
//                        "onPostFling: snap to 0 (small offset), current=$current, " +
//                            "minRecoverOffset=$minRecoverOffset"
//                    )
                }
                consecutiveSmallSideEffect = 0
                hasPendingFling = false
                pendingFlingVelocityY = 0f
//                log(
//                    "onPostFling end: consumed=$consumed, available=$available, " +
//                        "current=${syncOffset.floatValue}, hadOverscroll=$hadOverscroll"
//                )
                return Velocity.Zero
            }

            private fun startRecovery(from: Float, initialVelocity: Float = 0f) {
                stopRecovery()
//                log("startRecovery(from=$from, initialVelocity=$initialVelocity)")
                recoveryJob.value = scope.launch {
                    animatable.snapTo(from)
                    animatable.animateTo(
                        targetValue = 0f,
                        initialVelocity = initialVelocity,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) {
                        syncOffset.floatValue = value
                    }
                    hadOverscroll = false
//                    log("startRecovery complete: offset=0, hadOverscroll=false")
                }
            }
        }
    }

    Box(
        modifier = modifier
            .nestedScroll(nestedScrollConnection)
            .clipToBounds()
    ) {
        // 关闭子树（LazyColumn 等）的系统 Overscroll 效果，避免与自定义回弹叠加。
        // 否则会出现“系统先弹一下，我们再弹一下”的双阶段体感。
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            Box(
                modifier = Modifier.graphicsLayer {
                    translationY = syncOffset.floatValue
                }
            ) {
                content()
            }
        }
    }
}
