package com.yuezhou.reader.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 横向滑动闸门。
 *
 * 页面内存在与会横滑冲突的交互态（详情、筛选弹层、输入框聚焦、管理态、下载/全屏子页）时，
 * 由持有方以 token 形式临时关闭 Pager 手势；页面销毁时由 DisposableEffect 自动释放。
 * 只做布尔闸门，不参与导航状态，避免与 committedKey 混淆。
 */
class SwipeBlockRegistry {
    private var tokens by mutableStateOf<Set<String>>(emptySet())

    /** 是否存在任一阻塞项。 */
    val blocked: Boolean
        get() = tokens.isNotEmpty()

    fun hold(token: String) {
        tokens = tokens + token
    }

    fun release(token: String) {
        tokens = tokens - token
    }

    fun isHeld(token: String): Boolean = token in tokens
}
