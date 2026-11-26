// 阅舟阅读器桥接脚本：连接 foliate-js 与 Android Kotlin 层。
// 通过 window.ReaderBridge（由 Android @JavascriptInterface 注入）通信。
// 采用 MIT 许可证的 foliate-js 作为阅读引擎（见 docs/01-工程与构建/third-party-notices.md）。

import './view.js'
import { Overlayer } from './overlayer.js'
import { SpeechHighlight } from './speech-highlight.js'
import * as XPointer from './xpointer.js'
import { pageSnapshotState } from './page-snapshot.js'

const view = document.getElementById('view')
const speechHighlight = new SpeechHighlight(view, Overlayer.highlight, () => { post('navigation', {}); return window.__readerTurnCommit })
function redrawSpeechHighlight() {
    requestAnimationFrame(() => {
        try { speechHighlight.render(false) } catch (_) { /* Range unavailable while chapter loads. */ }
    })
}

// 当前打开的书籍对象（实现 foliate-js 的 book 接口）
let currentBook = null

// 当前排版设置（字号/行距/边距/主题/翻页模式），供 applySettings 与 load 注入使用
let currentSettings = {
    fontId: "book",
    fontWeight: 0,
    fontSize: 18,        // px
    lineHeight: 1.6,     // 倍数
    pageMargin: 20,      // px（左右/上下边距）
    theme: 'light',      // light | sepia | dark
    vertical: false,     // false=左右翻页, true=上下滚动
    pageAnimation: 'slide', // none=无动画 | slide=平滑滑动
}

// 供 Kotlin 调用的全局接口
window.__reader = {
    async commitPageTurn(direction) {
        const renderer = view.renderer
        const animated = renderer.getAttribute('animated')
        renderer.removeAttribute('animated')
        try { if (direction > 0) await view.next(); else await view.prev() }
        finally { if (animated !== null) renderer.setAttribute('animated', animated) }
    },
    // Read-only inspection shared by primary and isolated preview instances.
    snapshotState() { return pageSnapshotState(view) },
    // 打开 EPUB：epubUrl 为 Android 侧提供的可访问 URL；restoreCfi 为可选的恢复位置
    async openBook(epubUrl, restoreCfi, settings) {
        try {
            speechHighlight.clear()
            await view.open(epubUrl)
            currentBook = view.book
            Object.assign(currentSettings, settings)
            applyTheme()
            applyTextStyle()
            setFlow(currentSettings.vertical)
            applyPageAnimation()
            await view.init({ lastLocation: restoreCfi || null, showTextStart: true })
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
        await window.__readerTurnCommit
        try {
            await view.goTo(cfi)
            return true
        } catch (e) {
            post('error', { message: String(e?.message ?? e) })
            return false
        }
    },

    async verifyJump(requestId, cfi, restoreCfi, expectedCfi) {
        await window.__readerTurnCommit
        if (expectedCfi && view.lastLocation?.cfi !== expectedCfi) {
            post('jumpVerified', { requestId, verified: false, fraction: null, stale: true })
            return
        }
        let verified = false
        let fraction = null
        try {
            const target = await view.goTo(cfi)
            const location = view.lastLocation
            const visible = location?.range
            const contents = view.renderer?.getContents?.().find(item => item.index === target?.index)
            if (contents && visible && typeof target.anchor === 'function') {
                const range = target.anchor(contents.doc)
                verified = !!range && visible.isPointInRange(range.startContainer, range.startOffset)
                if (verified && Number.isFinite(location.fraction))
                    fraction = Math.max(0, Math.min(1, location.fraction))
            }
        } catch {
            // Navigation and DOM-range failures are reported as an unverified jump.
        }
        if (!verified && restoreCfi) await view.goTo(restoreCfi).catch(() => {})
        post('jumpVerified', { requestId, verified, fraction })
    },

    // 下一页 / 上一页
    async next() { await window.__readerTurnCommit; await view.next(); return true },
    async prev() { await window.__readerTurnCommit; await view.prev(); return true },

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
        await window.__readerTurnCommit
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
        await window.__readerTurnCommit
        try {
            await view.goToFraction(frac)
            return true
        } catch (e) {
            post('error', { message: String(e?.message ?? e) })
            return false
        }
    },

    // 朗读高亮：把「起止文本节点 CFI + 节点内字符偏移」定位成 range，用 overlayer 高亮整句。
    // startCfi/endCfi 为文本节点级 CFI，startOffset/endOffset 为各自节点内的字符偏移（0-based）。
    // 高亮块用固定 key（__tts_highlight__），下次调用自动覆盖旧块，实现「跟随朗读进度移动」。
    async highlightRange(startCfi, startOffset, endCfi, endOffset) {
        try { return await speechHighlight.set(startCfi, startOffset, endCfi, endOffset) }
        catch (e) { post('error', { message: String(e?.message ?? e) }); return false }
    },
    clearSpeechHighlight() { speechHighlight.clear() },

    async cfiToXPointer(requestId, cfi) {
        const value = await XPointer.cfiToXPointer(view, cfi)
        post('positionConverted', { requestId, value })
    },

    async comparePositions(requestId, remoteCfi, localCfi) {
        let value = null
        try {
            const remote = view.resolveCFI(remoteCfi)
            const local = view.resolveCFI(localCfi)
            const sections = view.book.sections
            if (sections[remote.index] && sections[local.index]) {
                if (remote.index !== local.index) {
                    value = String(Math.sign(remote.index - local.index))
                } else {
                    const content = view.renderer?.getContents?.().find(item => item.index === local.index)
                    const doc = content?.doc ?? await sections[local.index].createDocument()
                    const remoteRange = remote.anchor(doc)
                    const localRange = local.anchor(doc)
                    if (remoteRange && localRange)
                        value = String(Math.sign(remoteRange.compareBoundaryPoints(Range.START_TO_START, localRange)))
                }
            }
        } catch {
            // Invalid or unresolvable anchors need explicit confirmation.
        }
        post('positionConverted', { requestId, value })
    },

    async xPointerToCfi(requestId, xpointer) {
        const value = await XPointer.xPointerToCfi(view, xpointer)
        post('positionConverted', { requestId, value })
    },

    // 关闭当前书
    closeBook() {
        speechHighlight.clear()
        currentBook = null
    },

    // 提取当前章节正文文本：返回整章 [{ cfi, text }] 列表 + startPosition，
    // 供朗读（TTS）分句使用。整章文本保证片段索引跨次稳定；startPosition
    // 记录当前阅读位置（起点 CFI、节点序号、节点内字符偏移），由 Kotlin 侧
    // 经 charToSource 映射到「包含该字符的片段」，不从节点开头重读。
    getCurrentChapterText(startCfi) {
        try {
            const contents = view.renderer?.getContents?.()
            if (!Array.isArray(contents) || contents.length === 0) {
                post('chapterText', { items: [], startPosition: null })
                return false
            }
            const visibleIndex = view.lastLocation?.section?.current
            const { doc, index } = contents.find(item => item.index === visibleIndex) ?? contents[0]

            // 反解起点：当前阅读位置的文本节点 + 节点内偏移
            let startNode = null
            let startOffset = 0
            if (startCfi) {
                try {
                    const resolved = view.resolveCFI?.(startCfi)
                    if (resolved && typeof resolved.anchor === 'function') {
                        const range = resolved.anchor(doc)
                        if (range && range.startContainer) {
                            startNode = range.startContainer
                            startOffset = range.startOffset || 0
                        }
                    }
                } catch (e) { /* 起点反解失败则回退到章首 */ }
            }

            const items = []
            const paragraphIds = new Map()
            let startNodeIndex = -1
            let startCharOffsetInNode = 0
            const walker = doc.createTreeWalker(doc.body || doc.documentElement, NodeFilter.SHOW_TEXT)
            let node
            while ((node = walker.nextNode())) {
                const text = node.nodeValue
                if (!text || !text.trim()) continue
                const parent = node.parentElement
                if (!parent || isNonReadable(parent)) continue

                const cfi = getTextNodeCfi(index, node, doc)
                if (!cfi) continue

                // 定位起点所在的整章文本节点：优先同一文本节点，否则取其后首个可读节点。
                if (startNode && startNodeIndex < 0) {
                    if (node === startNode) {
                        startNodeIndex = items.length
                        startCharOffsetInNode = Math.min(startOffset, text.length)
                    } else if (!isBeforeStart(node, startNode)) {
                        startNodeIndex = items.length
                        startCharOffsetInNode = 0
                    }
                }

                // 同一块级段落中的 span/em/strong 文本属于同一句，不能逐节点断开。
                let paragraph = parent
                while (paragraph.parentElement && paragraph !== doc.body) {
                    const display = doc.defaultView.getComputedStyle(paragraph).display
                    if (!['inline', 'contents', 'inline-block'].includes(display)) break
                    paragraph = paragraph.parentElement
                }
                if (!paragraphIds.has(paragraph)) paragraphIds.set(paragraph, paragraphIds.size)
                items.push({ cfi, text: text.replace(/\u00a0/g, ' '), paragraphId: paragraphIds.get(paragraph) })
            }

            let startPosition = null
            if (items.length > 0) {
                if (!startCfi) {
                    startPosition = { startCfi: null, nodeIndex: 0, charOffsetInNode: 0 }
                } else if (startNodeIndex >= 0) {
                    startPosition = {
                        startCfi,
                        nodeIndex: startNodeIndex,
                        charOffsetInNode: startCharOffsetInNode,
                    }
                }
            }
            post('chapterText', { items, index, startPosition })
            return true
        } catch (e) {
            post('error', { message: String(e?.message ?? e) })
            return false
        }
    },

    // 应用排版设置：{ fontSize, lineHeight, pageMargin, theme, vertical }
    async applySettings(settings) {
        try {
            const prevVertical = currentSettings.vertical
            const supplied = settings || {}
            const themeChanged = Object.hasOwn(supplied, 'theme') && supplied.theme !== currentSettings.theme
            const layoutChanged = ['fontId', 'fontWeight', 'fontSize', 'lineHeight', 'pageMargin', 'theme']
                .some(key => Object.hasOwn(supplied, key) && supplied[key] !== currentSettings[key])
            Object.assign(currentSettings, supplied)
            if (themeChanged) applyTheme()
            if (layoutChanged) applyTextStyle()
            applyPageAnimation()
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
function setFlow(vertical) {
    const renderer = view.renderer
    if (!renderer) return
    if (vertical) {
        renderer.setAttribute('flow', 'scrolled')
    } else {
        renderer.removeAttribute('flow')
    }
}

// 根据翻页动画设置开启/关闭动画：给 foliate-paginator 加 animated 属性。
// 只有 slide 使用底层滑动；fade/ios_curl 的触摸与动画由 Android 接管。
function applyPageAnimation() {
    const renderer = view.renderer
    if (!renderer) return
    const mode = currentSettings.pageAnimation || 'slide'
    if (mode === 'slide') renderer.setAttribute('animated', 'slide')
    else renderer.removeAttribute('animated')
    if (mode === 'fade' || mode === 'ios_curl') renderer.setAttribute('native-page-turn', '')
    else renderer.removeAttribute('native-page-turn')
}

async function applyFlow(vertical) {
    setFlow(vertical)
    // 重载当前位置，让 paginator 按新 flow 重新布局
    const cfi = view.lastLocation?.cfi
    if (cfi) {
        await view.goTo(cfi)
    }
}

// 把 foliate-js 的 toc 节点转成纯数据（label/href/subitems），避免序列化循环引用
function toTocItem(node) {
    return {
        id: node.id ?? null,
        label: node.label ?? node.title ?? '',
        href: node.href ?? '',
        subitems: Array.isArray(node.subitems) ? node.subitems.map(toTocItem) : [],
    }
}

// 统一主题色板：阅读背景 + 文字色。theme 取值：light/sepia/dark/green/night-blue
function themePalette(theme) {
    const themes = {
        light: { bg: '#faf9f7', fg: '#1a1a1a' },
        sepia: { bg: '#f5ecd7', fg: '#3a3226' },
        dark: { bg: '#1a1a1a', fg: '#c8c8c8' },
        green: { bg: '#cce8cf', fg: '#1f3a24' },
        'night-blue': { bg: '#0d1b2a', fg: '#a8c0d8' },
    }
    return themes[theme] || themes.light
}

// 主题：设置 foliate-view 根元素与 iframe 的背景/文字色
function applyTheme() {
    const t = themePalette(currentSettings.theme)
    const root = document.getElementById('reader')
    if (root) {
        root.style.background = t.bg
        root.style.color = t.fg
    }
    // foliate-view 及内部 iframe 透明，露出根背景
    view.style.background = t.bg
    // 刷新 foliate-paginator 的 #background 边缘填充（否则切主题后屏幕边缘残留旧色）
    try { view.renderer?.refreshBackground?.() } catch (e) { /* ignore */ }
}

const systemFontFamilies = new Map([
    ['sans', 'sans-serif'],
    ['serif', 'serif'],
    ['mono', 'monospace'],
])

const bundledFonts = new Map([
    ['noto-serif-sc', { file: 'noto-serif-sc.otf', fallback: 'serif' }],
    ['noto-sans-sc', { file: 'noto-sans-sc.otf', fallback: 'sans-serif' }],
    ['wenkai-lite', { file: 'wenkai-lite.ttf', fallback: 'serif' }],
])

function fontStyle(id) {
    let family = systemFontFamilies.get(id)
    let face = ''
    const bundled = bundledFonts.get(id)
    const isImported = typeof id === 'string' && /^[0-9a-f-]{36}\.(ttf|otf)$/.test(id)
    if (bundled || isImported) {
        const path = bundled ? `/assets/reader/fonts/${bundled.file}` : `/fonts/${id}`
        const url = new URL(path, location.origin).href
        face = `@font-face { font-family: YuezhouCustom; src: url("${url}"); font-display: swap; }`
        family = `YuezhouCustom, ${bundled?.fallback ?? 'sans-serif'}`
    }
    if (!family) return ''
    return `${face}
        html body,
        html body :is(p, div, li, span, em, strong, blockquote, h1, h2, h3, h4, h5, h6, td, th):not(:where(pre *, code *, svg *, math *)) {
            font-family: ${family} !important;
        }`
}

// 向每个已加载章节的 iframe document 注入字号/行距/主题样式
function textStyle() {
    const s = currentSettings
    const t = themePalette(s.theme)
    // 注意：不再注入 body 的 padding，边距由 foliate 的 gap/margin 属性控制，
    // 避免与 foliate 内置的 grid 边距（--_gap）叠加导致左右留白过大。
    return `
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
        ${fontStyle(s.fontId)}
        ${weightStyle(s.fontWeight)}
    `
}

// 字重注入：支持任意 100~900 值。当字体本身缺对应字重（如内置字体仅有 Regular/Bold）
// 时，font-weight 回退到最近字重导致「加粗无感」，此时用 -webkit-text-stroke 描边模拟加粗
// （KOReader 的做法），描边宽度随 weight 增大而增大。
function weightStyle(weight) {
    const w = Number(weight)
    if (!(w >= 100 && w <= 900)) return ''
    const stroke = w > 400 ? Math.max(0, (w - 400) / 1000).toFixed(3) : 0
    const strokeCss = stroke > 0
        ? `-webkit-text-stroke: ${stroke}px currentColor;`
        : ''
    return `
        html body, html body :is(p, div, li, blockquote, td, th):not(:where(pre *, code *, svg *, math *)) {
            font-weight: ${w} !important;
            ${strokeCss}
        }
    `
}

function applyTextStyle() {
    const s = currentSettings
    const css = textStyle()
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

// Save the start of the visible page, independent of its trailing boundary.
view.addEventListener('relocate', ({ detail }) => {
    redrawSpeechHighlight()
    const { cfi, section, fraction, range, tocItem } = detail
    const index = section?.current ?? null
    let pageStart = cfi
    if (range && index !== null) {
        try {
            const start = range.cloneRange()
            start.collapse(true)
            pageStart = view.getCFI(index, start) ?? cfi
        } catch { /* Keep the renderer CFI on unsupported layouts. */ }
    }
    post('relocate', {
        cfi: pageStart ?? null,
        index,
        tocId: tocItem?.id ?? null,
        tocHref: tocItem?.href ?? null,
        fraction: fraction ?? null,
    })
})

// Apply the same preferences before the first pagination of each chapter.
view.addEventListener('load', ({ detail }) => {
    if (detail?.doc) injectStyle(detail.doc, textStyle())
    redrawSpeechHighlight()
})

view.addEventListener('create-overlay', redrawSpeechHighlight)

// 判断元素是否为不可朗读内容：脚本/样式/隐藏装饰/导航
function isNonReadable(el) {
    if (!el) return true
    const tag = el.tagName
    if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') return true
    // 隐藏元素（display:none / visibility:hidden / 折叠）不朗读
    const style = el.style
    if (style && (style.display === 'none' || style.visibility === 'hidden')) return true
    // 脚注链接的返回标记等 foliate 注入的装饰元素（如 foliate-footnote 序号）可跳过
    return false
}

// 为文本节点生成其指定偏移位置的 CFI；失败返回 null。
// 复用 view.getCFI(index, range)：foliate 内部用 CFI.fromRange 计算到文本节点级偏移。
// offset 为节点内字符偏移（0-based）；默认 0 即节点起始。
function getTextNodeCfi(index, textNode, doc, offset = 0) {
    try {
        const range = doc.createRange()
        range.setStart(textNode, Math.min(offset, textNode.nodeValue?.length ?? 0))
        range.collapse(true)
        return view.getCFI(index, range) ?? null
    } catch (e) {
        return null
    }
}

// 判断文本节点 node 是否位于 startNode 之前（文档序）。
// 用于起点反解后跳过起点之前的文本节点（当起点节点不在 TreeWalker 结果中时兜底）。
function isBeforeStart(node, startNode) {
    try {
        // compareBoundaryPoints：node 的末尾 与 startNode 的开头比较；<0 表示 node 在前。
        const range = document.createRange()
        range.selectNodeContents(node)
        const startRange = document.createRange()
        startRange.selectNodeContents(startNode)
        return range.compareBoundaryPoints(Range.END_TO_START, startRange) < 0
    } catch (e) {
        return false
    }
}

// 回传消息给 Android 层
function post(type, data) {
    const bridge = window.ReaderBridge
    if (bridge && typeof bridge.onReaderEvent === 'function') {
        bridge.onReaderEvent(type, JSON.stringify(data))
    }
}

// 暴露就绪标志，方便 Kotlin 侧确认脚本已加载
window.__readerReady = true
