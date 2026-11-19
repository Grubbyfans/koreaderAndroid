// 阅舟阅读器桥接脚本：连接 foliate-js 与 Android Kotlin 层。
// 通过 window.ReaderBridge（由 Android @JavascriptInterface 注入）通信。
// 采用 MIT 许可证的 foliate-js 作为阅读引擎（见 docs/third-party-notices.md）。

import './view.js'

const view = document.getElementById('view')

// 当前打开的书籍对象（实现 foliate-js 的 book 接口）
let currentBook = null

// 当前排版设置（字号/行距/边距/主题/翻页模式），供 applySettings 与 load 注入使用
let currentSettings = {
    fontSize: 18,        // px
    lineHeight: 1.6,     // 倍数
    pageMargin: 20,      // px（左右/上下边距）
    theme: 'light',      // light | sepia | dark
    vertical: false,     // false=左右翻页, true=上下滚动
}

// 供 Kotlin 调用的全局接口
window.__reader = {
    // 打开 EPUB：epubUrl 为 Android 侧提供的可访问 URL；restoreCfi 为可选的恢复位置
    async openBook(epubUrl, restoreCfi) {
        try {
            await view.open(epubUrl)
            // view.open() 无返回值，book 挂在 view.book 上
            currentBook = view.book
            // 关键：open() 只装载书，不渲染第一页；必须显式 init 才会翻页显示内容，
            // 否则 WebView 白屏。lastLocation 为空时显示正文起始。
            await view.init({ showTextStart: true })
            // 有恢复位置则跳转过去
            if (restoreCfi) {
                await view.goTo(restoreCfi)
            }
            post('bookOpened', {
                sections: currentBook?.sections?.length ?? 0,
                title: currentBook?.metadata?.title ?? '',
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

    // 获取目录树：返回 { label, href, subitems } 嵌套结构
    getTableOfContents() {
        try {
            const book = currentBook || view.book
            const toc = (book && book.toc) ? book.toc : []
            post('toc', { items: toc.map(toTocItem) })
            return true
        } catch (e) {
            post('error', { message: String(e?.message ?? e) })
            return false
        }
    },

    // 跳转到目录项（href 指向章节）
    async goToToc(href) {
        try {
            await view.goTo(href)
            return true
        } catch (e) {
            post('error', { message: String(e?.message ?? e) })
            return false
        }
    },

    // 跳到全书百分比进度（0..1）
    async goToFraction(frac) {
        try {
            await view.goToFraction(frac)
            return true
        } catch (e) {
            post('error', { message: String(e?.message ?? e) })
            return false
        }
    },

    // 关闭当前书
    closeBook() {
        currentBook = null
    },

    // 应用排版设置：{ fontSize, lineHeight, pageMargin, theme, vertical }
    async applySettings(settings) {
        try {
            const prevVertical = currentSettings.vertical
            Object.assign(currentSettings, settings || {})
            applyTheme()
            applyTextStyle()
            // 翻页模式切换：设置 foliate-paginator 的 flow 属性并重载当前章节
            if (settings && typeof settings.vertical === 'boolean'
                && settings.vertical !== prevVertical && currentBook) {
                await applyFlow(settings.vertical)
            }
            return true
        } catch (e) {
            post('error', { message: String(e?.message ?? e) })
            return false
        }
    },
}

// 切换翻页模式：vertical=true 上下滚动(scrolled)，false 左右翻页(paginated)
async function applyFlow(vertical) {
    const renderer = view.renderer
    if (!renderer) return
    if (vertical) {
        renderer.setAttribute('flow', 'scrolled')
    } else {
        renderer.removeAttribute('flow')
    }
    // 重载当前位置，让 paginator 按新 flow 重新布局
    const cfi = view.lastLocation?.cfi
    if (cfi) {
        await view.goTo(cfi)
    }
}

// 把 foliate-js 的 toc 节点转成纯数据（label/href/subitems），避免序列化循环引用
function toTocItem(node) {
    return {
        label: node.label ?? node.title ?? '',
        href: node.href ?? '',
        subitems: Array.isArray(node.subitems) ? node.subitems.map(toTocItem) : [],
    }
}

// 主题：设置 foliate-view 根元素与 iframe 的背景/文字色
function applyTheme() {
    const themes = {
        light: { bg: '#faf9f7', fg: '#1a1a1a' },
        sepia: { bg: '#f5ecd7', fg: '#3a3226' },
        dark: { bg: '#1a1a1a', fg: '#c8c8c8' },
    }
    const t = themes[currentSettings.theme] || themes.light
    const root = document.getElementById('reader')
    if (root) {
        root.style.background = t.bg
        root.style.color = t.fg
    }
    // foliate-view 及内部 iframe 透明，露出根背景
    view.style.background = t.bg
}

// 向每个已加载章节的 iframe document 注入字号/行距/主题样式
function applyTextStyle() {
    const s = currentSettings
    const themes = {
        light: { bg: '#faf9f7', fg: '#1a1a1a' },
        sepia: { bg: '#f5ecd7', fg: '#3a3226' },
        dark: { bg: '#1a1a1a', fg: '#c8c8c8' },
    }
    const t = themes[s.theme] || themes.light
    // 注意：不再注入 body 的 padding，边距由 foliate 的 gap/margin 属性控制，
    // 避免与 foliate 内置的 grid 边距（--_gap）叠加导致左右留白过大。
    const css = `
        html, body {
            background: ${t.bg} !important;
            color: ${t.fg} !important;
        }
        body {
            font-size: ${s.fontSize}px !important;
            line-height: ${s.lineHeight} !important;
        }
        p, div, li {
            line-height: inherit;
        }
    `
    try {
        const renderer = view.renderer
        if (!renderer) return
        // 边距交给 foliate 属性控制：margin=上下留白，gap=左右留白。
        // pageMargin 作为统一边距：左右用其一半（避免过宽），上下用其值。
        if (renderer.setAttribute) {
            renderer.setAttribute('margin', `${s.pageMargin}px`)
            renderer.setAttribute('gap', `${Math.round(s.pageMargin / 2)}px`)
        }
        // foliate-paginator 的 document 通过 getContents() 拿不到，这里遍历 shadow 内的 iframe
        const contents = renderer.getContents?.()
        if (Array.isArray(contents)) {
            for (const { doc } of contents) {
                injectStyle(doc, css)
            }
        }
    } catch (e) { /* ignore */ }
}

// 向指定 document 注入（或更新）用户样式
function injectStyle(doc, css) {
    if (!doc) return
    let styleEl = doc.getElementById('__yuezhou_reader_style__')
    if (!styleEl) {
        styleEl = doc.createElement('style')
        styleEl.id = '__yuezhou_reader_style__'
        doc.head.appendChild(styleEl)
    }
    styleEl.textContent = css
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

// 每章加载后注入用户排版样式（字号/行距/边距/主题）
view.addEventListener('load', (e) => {
    const doc = e.detail && e.detail.doc
    if (doc) {
        const s = currentSettings
        const themes = {
            light: { bg: '#faf9f7', fg: '#1a1a1a' },
            sepia: { bg: '#f5ecd7', fg: '#3a3226' },
            dark: { bg: '#1a1a1a', fg: '#c8c8c8' },
        }
        const t = themes[s.theme] || themes.light
        const css = `
            html, body { background: ${t.bg} !important; color: ${t.fg} !important; }
            body { font-size: ${s.fontSize}px !important; line-height: ${s.lineHeight} !important; }
        `
        injectStyle(doc, css)
    }
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
