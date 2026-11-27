package com.yuezhou.reader.speech

import com.yuezhou.reader.core.speech.SourceAnchor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 朗读进度桥接：把 TTS 回调的「片段序号 + 朗读文本内字符偏移」映射回原文锚点，
 * 供阅读 UI 高亮当前朗读位置，并作为云端同步锚点的候选（仅「已实际确认」的位置）。
 *
 * 对齐技术方案 §7.4：
 *   - TTS 返回偏移属于发送给它的文本，必须经映射转成原文位置；
 *   - 云端同步锚点只采用已实际确认的当前朗读点，不把「已排队未播出」的下一句当进度。
 *
 * 本类为进程内单例，服务写入、Activity 收集（同进程）。
 */
object SpeechProgressTracker {

    /**
     * 已确认的朗读位置快照。
     *
     * @param bookId 当前朗读书籍。
     * @param segmentIndex 已确认片段序号。
     * @param charOffset 片段内已确认字符偏移。
     * @param sourceAnchor 偏移映射回原文得到的锚点（供 UI 高亮）。
     */
    data class Snapshot(
        val bookId: String,
        val chapterIndex: Int = 0,
        val segmentIndex: Int,
        val charOffset: Int,
        val sourceAnchor: SourceAnchor?,
        val endAnchor: SourceAnchor? = null,
    )

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    internal fun setPaused(paused: Boolean) {
        _paused.value = paused
    }

    private val _current = MutableStateFlow<Snapshot?>(null)

    /** 当前已确认朗读位置；null 表示尚无进度或已停止。 */
    val current: StateFlow<Snapshot?> = _current.asStateFlow()

    /** 服务在片段进度确认时调用。@param anchor 由片段 charToSource 映射回原文的锚点（段内当前点）。 */
    fun onProgress(
        bookId: String,
        chapterIndex: Int,
        segmentIndex: Int,
        charOffset: Int,
        anchor: SourceAnchor?,
        endAnchor: SourceAnchor? = null,
    ) {
        _current.value = Snapshot(bookId, chapterIndex, segmentIndex, charOffset, anchor, endAnchor)
    }

    /** 停止/切书/作废时清空进度。 */
    fun clear() {
        _paused.value = false
        _current.value = null
    }
}
