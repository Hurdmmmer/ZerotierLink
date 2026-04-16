package io.github.jimmy.ztlink.app.ui.components.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow

/**
 * 所有 UI 事件的基础接口
 */
interface CommonUiEvent {
    /** Toast 事件接口 */
    interface ShowToast : CommonUiEvent {
        val messageRes: Int
    }

    /** 剪贴板事件接口 */
    interface CopyToClipboard : CommonUiEvent {
        val text: String
        val successMsgRes: Int
        val label: String get() = "text"
    }
}

/**
 * 通用事件观察器
 */
@Composable
fun <T> ObserveUiEvents(
    flow: Flow<T>,
    onEvent: (T) -> Unit = {}
) {
    val context = LocalContext.current
    LaunchedEffect(flow) {
        flow.collect { event ->
            // 1. 处理通用逻辑
            when (event) {
                is CommonUiEvent.ShowToast -> {
                    Toast.makeText(context, context.getString(event.messageRes), Toast.LENGTH_SHORT).show()
                }
                is CommonUiEvent.CopyToClipboard -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(event.label, event.text))
                    Toast.makeText(context, context.getString(event.successMsgRes), Toast.LENGTH_SHORT).show()
                }
            }
            // 2. 回调给页面处理特殊逻辑
            onEvent(event)
        }
    }
}
