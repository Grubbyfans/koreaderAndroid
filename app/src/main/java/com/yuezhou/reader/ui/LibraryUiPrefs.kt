package com.yuezhou.reader.ui

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.edit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** 网格滚动位置：索引 + 像素偏移，二者共同决定视觉位置。 */
data class GridScrollPos(val index: Int, val offset: Int)

/**
 * 书库相关页面的轻量展示状态持久化。
 *
 * 只保存纯 UI 偏好：各列表滚动位置、Z-Library 展示模式、上次选中的云端书源。
 * 不涉及书籍数据、凭证或云端索引，因此使用普通 SharedPreferences。
 */
class LibraryUiPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("library_ui", Context.MODE_PRIVATE)

    fun scroll(key: String): GridScrollPos = GridScrollPos(
        index = prefs.getInt("scroll.$key.index", 0),
        offset = prefs.getInt("scroll.$key.offset", 0),
    )

    fun saveScroll(key: String, index: Int, offset: Int) {
        prefs.edit {
            putInt("scroll.$key.index", index)
            putInt("scroll.$key.offset", offset)
        }
    }

    var lastCloudProvider: String?
        get() = prefs.getString("last_cloud_provider", null)
        set(value) {
            prefs.edit { putString("last_cloud_provider", value) }
        }

    var catalogGridMode: Boolean
        get() = prefs.getBoolean("catalog_grid_mode", true)
        set(value) {
            prefs.edit { putBoolean("catalog_grid_mode", value) }
        }

    var localGridMode: Boolean
        get() = prefs.getBoolean("local_grid_mode", true)
        set(value) {
            prefs.edit { putBoolean("local_grid_mode", value) }
        }
}

/**
 * 恢复并持续保存网格滚动位置。
 *
 * 切换底部页签会销毁列表组合，靠索引 + 偏移量重建视觉位置；
 * 滚动过程中延迟落盘，避免每帧写 SharedPreferences。
 */
@Composable
fun rememberRestoredGridState(key: String, prefs: LibraryUiPrefs): LazyGridState {
    val saved = remember(key) { prefs.scroll(key) }
    val state = rememberLazyGridState(
        initialFirstVisibleItemIndex = saved.index,
        initialFirstVisibleItemScrollOffset = saved.offset,
    )
    LaunchedEffect(key, state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collectLatest { (index, offset) ->
                delay(150)
                prefs.saveScroll(key, index, offset)
            }
    }
    return state
}

/** 与 [rememberRestoredGridState] 相同，用于单列列表模式。 */
@Composable
fun rememberRestoredListState(key: String, prefs: LibraryUiPrefs): LazyListState {
    val saved = remember(key) { prefs.scroll(key) }
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = saved.index,
        initialFirstVisibleItemScrollOffset = saved.offset,
    )
    LaunchedEffect(key, state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collectLatest { (index, offset) ->
                delay(150)
                prefs.saveScroll(key, index, offset)
            }
    }
    return state
}
