package com.yuezhou.reader.reader.online.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 本地书库变更信号。
 *
 * 书架列表是一次性快照（MainActivity 用 refreshKey 重载），过去只在 ON_RESUME 或本地导入/
 * 删除时刷新，在线下载导入成功这条链路没有通知 UI，导致「显示已导入，切到书架却看不到，
 * 切桌面回来才出现」。每有一本书成功登记进书库就自增版本号，UI 观察该信号即可即时刷新。
 *
 * 用 StateFlow（replay=1）而非一次性事件流：下载可能在后台完成，回到前台后仍能拿到最新
 * 版本，不会丢事件。版本号只表示「发生过变更」，不携带书籍数据，也不影响下载状态机。
 */
class LibraryRefreshSignal {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    /** 一本书成功登记进本地书库（含同名覆盖）后调用。 */
    fun notifyImportSucceeded() {
        _version.update { it + 1 }
    }
}
