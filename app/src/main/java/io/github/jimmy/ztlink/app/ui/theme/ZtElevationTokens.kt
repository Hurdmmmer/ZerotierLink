package io.github.jimmy.ztlink.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 层级令牌（Elevation Tokens）。
 *
 * 对应 MD3 Elevation 规范的五个层级（Level 0–4）：
 *   Level 0  0dp  → 背景层（background、surface）
 *   Level 1  1dp  → 轻微浮起（filled card、navigation bar）
 *   Level 2  3dp  → 中等浮起（elevated card）
 *   Level 3  6dp  → 明显浮起（dialog、modal）
 *   Level 4  8dp  → 强浮起（snackbar、FAB pressed）
 *
 * 实际项目中只暴露"有命名语义"的那几个，避免直接使用原始数字。
 *
 * @param card      普通卡片，Level 1（1dp）；若需要 ElevatedCard 风格可改用 Level 2（3dp）
 * @param dialog    对话框 / BottomSheet，Level 3（6dp）
 * @param topBar    TopAppBar 静止状态，Level 0（0dp）；滚动后由 TopAppBarScrollBehavior 自动抬升
 * @param fab       FAB 默认状态，Level 3（6dp）
 * @param snackbar  Snackbar，Level 4（8dp）
 */
@Immutable
data class ZtElevationTokens(
    val card: Dp      = 1.dp,
    val dialog: Dp    = 6.dp,
    val topBar: Dp    = 0.dp,
    val fab: Dp       = 6.dp,
    val snackbar: Dp  = 8.dp,
)

val LocalZtElevationTokens = staticCompositionLocalOf { ZtElevationTokens() }