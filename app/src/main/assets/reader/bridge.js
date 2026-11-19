// 阅舟阅读器桥接脚本：连接 foliate-js 与 Android Kotlin 层。
// 通过 window.ReaderBridge（由 Android @JavascriptInterface 注入）通信。
// 采用 MIT 许可证的 foliate-js 作为阅读引擎（见 docs/third-party-notices.md）。

import './view.js'

const view = document.getElementById('view')

// 当前打开的书籍对象（实现 foliate-js 的 book 接口）
let currentBook = null

// 供 Kotlin 调用的全局接口
window.__reader = {
    // 打开 EPUB：epubUrl 为 Android 侧提供的可访问 URL；restoreCfi 为可选的恢复位置
    async openBook(epubUrl, restoreCfi) {
        try {
            currentBook = await view.open(epubUrl)
            // 关键：open() 只装载书，不渲染第一页；必须显式 init 才会翻页显示内容，
            // 否则 WebView 白屏。lastLocation 为空时显示正文起始。
            await view.init({ showTextStart: true })
            // 有恢复位置则跳转过去
            if (restoreCfi) {
                await view.goTo(restoreCfi)
            }
            post('bookOpened', {
                sections: currentBook.sections?.length ?? 0,
                title: currentBook.metadata?.title ?? '',
            })
            return true
        } catch (e) {
            post('error', { message: String(e?.message ?? e) })
            return false
        }
    },

    // 跳转到指定 CFI（foliate-js 的 view 已实现 resolveCFI + goTo）
    async goToCfi(cfi) {
        try {
            await view.goTo(cfi)
            return true
        } catch (e) {
            post('error', { message: String(e?.message ?? e) })
            return false
        }
    },

    // 下一页 / 上一页
    async next() { await view.next(); return true },
    async prev() { await view.prev(); return true },

    // 关闭当前书
    closeBook() {
        currentBook = null
    },
}

// 监听位置变化：view 的 relocate 事件 detail 已含 CFI（内部 joinIndir 生成）
view.addEventListener('relocate', (e) => {
    const { cfi, index, fraction, range } = e.detail
    post('relocate', {
        cfi: cfi ?? null,
        index: index ?? null,
        fraction: fraction ?? null,
        hasRange: !!range,
    })
})

// 回传消息给 Android 层
function post(type, data) {
    const bridge = window.ReaderBridge
    if (bridge && typeof bridge.onReaderEvent === 'function') {
        bridge.onReaderEvent(type, JSON.stringify(data))
    }
}

// 暴露就绪标志，方便 Kotlin 侧确认脚本已加载
window.__readerReady = true
