package com.yuezhou.reader.ui.reader

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuezhou.reader.ReaderAutomaticSync
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.reader.LibraryStore
import com.yuezhou.reader.reader.SyncLogStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** 同步操作的结果，用于在进度对话框中就地展示。 */
internal data class SyncActionResult(val message: String, val isError: Boolean)

/**
 * 阅读进度与云端同步状态。
 *
 * 位置跟踪、同步面板与自动同步的运行状态集中在这里，配置变更（如旋转）后不丢失；
 * WebView 跳转等交互编排仍留在界面层，通过 suspend 参数传入，保证 ViewModel 不依赖视图。
 */
internal class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    val libraryStore = LibraryStore(application)
    val cloudSync = CloudSync(application)
    val syncLogStore = SyncLogStore(application)
    val automaticSync = ReaderAutomaticSync(cloudSync::fetchRemoteProgress)

    /** 最近一次位置（CFI, 百分比）；null 表示尚未定位。 */
    var lastPosition by mutableStateOf<Pair<String, Double?>?>(null)

    /** 位置版本号：CFI 变化时递增，用于判定异步跳转是否已过期。 */
    var positionVersion by mutableLongStateOf(0L)

    /** 当前位置的 XPointer 转换任务。 */
    var positionConversion by mutableStateOf<Deferred<String?>?>(null)

    // —— 进度同步面板 ——
    var syncDialogOpen by mutableStateOf(false)
    var syncInfo by mutableStateOf<CloudSync.ProgressInfo?>(null)
    var syncLoading by mutableStateOf(false)
    var syncBusy by mutableStateOf(false)
    var undoPosition by mutableStateOf<Pair<String, Double?>?>(null)

    /** 上次同步操作结果；非空时对话框保留并展示结果，直到用户关闭。 */
    var syncResult by mutableStateOf<SyncActionResult?>(null)

    // —— 自动同步 ——
    var autoSyncBusy by mutableStateOf(false)
    /** 本次打开是否已执行过自动拉取。 */
    var autoPulled by mutableStateOf(false)
    /** 需要用户确认的云端进度。 */
    var remoteOffer by mutableStateOf<RemoteProgressOffer?>(null)
    /** 手动跳转到云端位置的结果；非空时弹框就地展示。 */
    var offerResult by mutableStateOf<SyncActionResult?>(null)

    /** 切换书籍时清空上一次阅读残留的进度状态。 */
    fun resetForBook() {
        positionConversion?.cancel()
        positionConversion = null
        lastPosition = null
        positionVersion = 0L
        syncDialogOpen = false
        syncInfo = null
        syncLoading = false
        syncBusy = false
        undoPosition = null
        syncResult = null
        autoSyncBusy = false
        autoPulled = false
        remoteOffer = null
        offerResult = null
    }

    /**
     * 更新当前位置。
     *
     * [xpointer] 已知时直接使用；否则通过 [convert] 异步转换，结果挂在 [positionConversion] 上，
     * 供退出时的自动上传等待。
     */
    fun updatePosition(
        position: Pair<String, Double?>,
        xpointer: String? = null,
        convert: (suspend (cfi: String) -> String?)? = null,
    ) {
        positionConversion?.cancel()
        if (lastPosition?.first != position.first) positionVersion++
        lastPosition = position
        positionConversion = when {
            xpointer != null -> CompletableDeferred(xpointer)
            convert != null -> viewModelScope.async { convert(position.first) }
            else -> null
        }
    }

    suspend fun saveProgress(bookId: String, position: Pair<String, Double?>): Boolean =
        libraryStore.saveProgress(bookId, position.first, position.second)

    suspend fun loadProgressInfo(bookId: String): CloudSync.ProgressInfo = cloudSync.progressInfo(bookId)

    /**
     * 上传本机进度。返回 null 表示成功，否则为展示给用户的失败说明。
     */
    suspend fun uploadProgress(
        bookId: String,
        cfi: String,
        percent: Double?,
        convert: suspend (cfi: String) -> String?,
    ): String? {
        val xpointer = convert(cfi) ?: return "当前阅读位置无法转换，已取消上传"
        return cloudSync.uploadLocalProgress(bookId, xpointer, percent)
    }
}
