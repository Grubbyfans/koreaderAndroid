// 阅舟阅读器 XPointer 桥接模块：在 JS 侧完成 CFI ↔ XPointer 的精准转换。
//
// 为什么在 JS 侧做：真实 DOM 只存在于 foliate-js 渲染的 iframe 中（view.renderer 或
// book.sections[index].createDocument()），而 XPointer 的「元素路径 + 文本偏移」必须基于
// 真实 DOM 计算。Kotlin 侧的 PositionCodec 用轻量树模型实现同一算法（用于单元测试），
// 运行时则由本模块在真实 DOM 上执行等价逻辑。
//
// 依赖 foliate-js 提供的能力：
//   - view.resolveCFI(cfi) → { index, anchor }，anchor(doc) → DOM Range
//   - view.getCFI(index, range) → CFI 字符串
//   - book.sections[index].createDocument() → 该 spine 章节的独立 document
//   - CFI.fromRange(range) / CFI.toRange(doc, parts)（epubcfi.js）
//
// XPointer 格式（单 EPUB spine）：
//   /body/DocFragment[N]/body/div/p[3]/text().12
//   - DocFragment[N]：1-based spine 序号，单 spine item 时省略 [N]
//   - 元素步：tag 或 tag[1-based 序号]，同标签唯一时省略序号
//   - 文本定位：/text().N（唯一文本子节点）/text()[K].N（第 K 个）/tag[idx].N（累计偏移）

import * as CFI from './epubcfi.js'

// ---------- CREngine 空白折叠规则（对齐 core/CrengineWhitespace.kt） ----------

const WS = /[ \t\n\r\f]/

function isWhitespace(c) {
    return c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === '\f'
}

const PRE_TAGS = new Set(['pre', 'code', 'listing', 'plaintext', 'xmp', 'textarea'])

const BLOCK_TAGS = new Set([
    'body', 'div', 'p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'ul', 'ol', 'li', 'dl', 'dt', 'dd', 'blockquote', 'pre',
    'section', 'article', 'aside', 'header', 'footer', 'nav', 'main',
    'figure', 'figcaption', 'table', 'thead', 'tbody', 'tfoot',
    'tr', 'td', 'th', 'caption', 'address', 'details', 'summary',
    'form', 'fieldset', 'center',
])

// 原始 DOM 偏移 → CREngine 折叠后偏移（连续空白折叠为单空格）
function toCollapsedOffset(text, rawOffset) {
    let collapsed = 0
    const end = Math.min(rawOffset, text.length)
    for (let i = 0; i < end; i++) {
        if (!isRunContinuation(text, i)) collapsed++
    }
    return collapsed + Math.max(0, rawOffset - text.length)
}

// CREngine 折叠后偏移 → 原始 DOM 偏移
function toRawOffset(text, collapsedOffset) {
    let collapsed = 0
    for (let i = 0; i < text.length; i++) {
        if (isRunContinuation(text, i)) continue
        if (collapsed === collapsedOffset) return i
        collapsed++
    }
    return text.length
}

function isRunContinuation(text, i) {
    return i > 0 && isWhitespace(text[i]) && isWhitespace(text[i - 1])
}

function isBlankText(text) {
    for (const c of text) if (!isWhitespace(c)) return false
    return true
}

function isElement(node) {
    return node && node.nodeType === 1
}

function isTextNode(node) {
    return node && (node.nodeType === 3 || node.nodeType === 4)
}

function preservesWhitespace(node) {
    let cur = node.parentNode
    while (cur) {
        if (isElement(cur) && PRE_TAGS.has(cur.tagName.toLowerCase())) return true
        cur = cur.parentNode
    }
    return false
}

// CREngine 保留的直接文本子节点（块级元素开头且不在 pre 内的纯空白文本被丢弃）
function crengineTextChildren(element) {
    const dropsLeadingBlank =
        BLOCK_TAGS.has(element.tagName.toLowerCase()) && !preservesWhitespace(element)
    const result = []
    element.childNodes.forEach((child, i) => {
        if (!isTextNode(child)) return
        if (child.nodeValue.length === 0) return
        if (dropsLeadingBlank && i === 0 && isBlankText(child.nodeValue)) return
        result.push(child)
    })
    return result
}

// ---------- XPointer 路径解析/构建 ----------

// 解析单 spine EPUB 的 XPointer，返回 { spineIndex, steps:[{tag,index}], text } 或 null
function parseXPointer(xpointer) {
    if (!xpointer || !xpointer.trim()) return null
    const s = xpointer.trim()
    const m = /^\/body\/DocFragment(?:\[(\d+)])?\/body(\/.*)?$/.exec(s)
    if (!m) return null
    const spine = m[1] ? parseInt(m[1], 10) : 1
    const rest = m[2] || ''

    // 分离文本定位步（最后一段）
    const split = splitTextStep(rest)
    const pathPart = split.path
    const textStep = split.text

    const steps = []
    if (pathPart) {
        for (const seg of pathPart.replace(/^\//, '').split('/')) {
            if (!seg) continue
            const mm = /^([\w-]+)(?:\[(\d+)])?$/.exec(seg)
            if (!mm) return null
            steps.push({ tag: mm[1], index: mm[2] ? parseInt(mm[2], 10) : 0 })
        }
    }

    let text = null
    if (textStep.type === 'single') text = { textNodeIndex: 0, offset: textStep.offset }
    else if (textStep.type === 'indexed') text = { textNodeIndex: textStep.index, offset: textStep.offset }
    else if (textStep.type === 'element') text = { textNodeIndex: 0, offset: textStep.offset, cumulative: true }

    return { spineIndex: spine - 1, steps, text }
}

function splitTextStep(rest) {
    if (!rest) return { path: '', text: { type: 'none' } }
    let m
    if ((m = /^(.+)\/text\(\)\[(\d+)]\.(\d+)$/.exec(rest))) {
        return { path: m[1], text: { type: 'indexed', index: parseInt(m[2], 10), offset: parseInt(m[3], 10) } }
    }
    if ((m = /^(.+)\/text\(\)\.(\d+)$/.exec(rest))) {
        return { path: m[1], text: { type: 'single', offset: parseInt(m[2], 10) } }
    }
    if ((m = /^(.*\/[\w-]+(?:\[\d+])?)\.(\d+)$/.exec(rest))) {
        return { path: m[1], text: { type: 'element', offset: parseInt(m[2], 10) } }
    }
    return { path: rest, text: { type: 'none' } }
}

// 构建 XPointer 字符串
function buildXPointer(spineIndex, steps, text) {
    let s = '/body/DocFragment'
    if (spineIndex !== 0) s += `[${spineIndex + 1}]`
    s += '/body'
    for (const step of steps) {
        s += '/' + step.tag
        if (step.index > 1) s += `[${step.index}]`
    }
    if (text) {
        if (text.cumulative) {
            s += `.${text.offset}`
        } else if (text.textNodeIndex === 0) {
            s += `/text().${text.offset}`
        } else {
            s += `/text()[${text.textNodeIndex}].${text.offset}`
        }
    }
    return s
}

// 从 body 根沿元素步解析目标元素
function resolveElement(body, steps) {
    let cur = body
    for (const step of steps) {
        const tag = step.tag.toLowerCase()
        const matches = []
        for (const child of cur.children) {
            if (child.tagName.toLowerCase() === tag) matches.push(child)
        }
        const idx = step.index > 0 ? step.index - 1 : 0
        if (idx >= matches.length) return null
        cur = matches[idx]
    }
    return cur
}

// 构建某元素相对 body 根的元素步列表（1-based 序号，唯一时 0）
function buildSteps(body, element) {
    const steps = []
    let cur = element
    while (cur && cur !== body) {
        const parent = cur.parentNode
        if (!parent) break
        const tag = cur.tagName.toLowerCase()
        // 统计父级下同名元素的数量与当前位置
        const siblings = Array.from(parent.children).filter(c => c.tagName.toLowerCase() === tag)
        const index = siblings.length > 1 ? siblings.indexOf(cur) + 1 : 0
        steps.unshift({ tag, index })
        cur = parent
    }
    return steps
}

// ---------- 核心转换：在真实 DOM 上执行 ----------

/**
 * CFI → XPointer。
 * 依赖 view.resolveCFI 反解 CFI 得到 { index, anchor }，anchor(doc) 得到 Range，
 * 再基于真实 DOM 计算 XPointer 元素路径 + 折叠后偏移。
 */
export function cfiToXPointer(view, cfi) {
    if (!cfi) return null
    try {
        const resolved = view.resolveCFI(cfi)
        if (!resolved) return null
        const { index, anchor } = resolved
        // 拿到该章节的 document（优先用已渲染内容，否则独立加载）
        const doc = getChapterDoc(view, index)
        if (!doc || typeof anchor !== 'function') return null
        const range = anchor(doc)
        if (!range) return null
        return rangeToXPointer(index, doc, range)
    } catch (e) {
        return null
    }
}

/**
 * XPointer → CFI。
 * 解析 XPointer 得到 spine 序号 + 元素路径 + 文本偏移，在对应章节 document 上
 * 定位 DOM 节点与偏移，再用 CFI.fromRange 生成 CFI。
 */
export function xPointerToCfi(view, xpointer) {
    if (!xpointer) return null
    try {
        const path = parseXPointer(xpointer)
        if (!path) return null
        const { spineIndex, steps, text } = path

        // 定位章节根：book.sections[spineIndex] 的 body
        const section = view.book?.sections?.[spineIndex]
        if (!section) return null
        const doc = section.createDocument()
        if (!doc || !doc.body) return null

        const element = resolveElement(doc.body, steps)
        if (!element) return null

        let node, offset
        if (!text) {
            // 仅指向元素起点
            node = element
            offset = 0
        } else if (text.cumulative) {
            // /tag[idx].N：元素累计偏移
            const found = findTextNodeAtCumulativeOffset(element, text.offset)
            if (!found) return null
            node = found.node
            offset = found.offset
        } else {
            const directTexts = crengineTextChildren(element)
            if (text.textNodeIndex === 0) {
                const n = directTexts.find(t => !isBlankText(t.nodeValue)) || directTexts[0]
                if (!n) return null
                node = n
                offset = toRawOffset(n.nodeValue, text.offset)
            } else {
                const n = directTexts[text.textNodeIndex - 1]
                if (!n) return null
                node = n
                offset = toRawOffset(n.nodeValue, text.offset)
            }
        }

        const range = doc.createRange()
        if (isTextNode(node)) {
            range.setStart(node, Math.min(offset, node.nodeValue.length))
        } else {
            // 元素节点：定位到元素内部起点
            range.setStart(node, 0)
        }
        range.collapse(true)
        return view.getCFI(spineIndex, range)
    } catch (e) {
        return null
    }
}

/** 获取某章节的 document：优先用已渲染内容，否则独立 createDocument。 */
function getChapterDoc(view, index) {
    try {
        const contents = view.renderer?.getContents?.()
        if (Array.isArray(contents)) {
            const hit = contents.find(c => c.index === index)
            if (hit && hit.doc) return hit.doc
        }
        const section = view.book?.sections?.[index]
        if (section && typeof section.createDocument === 'function') {
            return section.createDocument()
        }
        return null
    } catch (e) {
        return null
    }
}

/** 从 DOM Range 计算 XPointer（元素路径 + 折叠偏移）。 */
function rangeToXPointer(index, doc, range) {
    const startContainer = range.startContainer
    const startOffset = range.startOffset || 0

    if (isTextNode(startContainer)) {
        const parent = startContainer.parentNode
        if (!parent) return null
        const steps = buildSteps(doc.body, parent)
        const siblings = crengineTextChildren(parent)
        const pos = siblings.indexOf(startContainer)
        if (pos < 0) {
            // 被丢弃的块首空白 → 父元素路径
            return buildXPointer(index, steps, null)
        }
        const collapsed = toCollapsedOffset(startContainer.nodeValue, startOffset)
        const text = siblings.length <= 1
            ? { textNodeIndex: 0, offset: collapsed }
            : { textNodeIndex: pos + 1, offset: collapsed }
        return buildXPointer(index, steps, text)
    } else if (isElement(startContainer)) {
        const steps = buildSteps(doc.body, startContainer)
        return buildXPointer(index, steps, null)
    }
    return null
}

/** 元素内累计文本偏移 → 文本节点锚点。 */
function findTextNodeAtCumulativeOffset(element, offset) {
    const textNodes = []
    collectTextNodes(element, textNodes)
    let current = 0
    for (const t of textNodes) {
        const len = t.nodeValue.length
        if (current + len >= offset) {
            return { node: t, offset: offset - current }
        }
        current += len
    }
    if (textNodes.length > 0) {
        const last = textNodes[textNodes.length - 1]
        return { node: last, offset: last.nodeValue.length }
    }
    return null
}

function collectTextNodes(element, out) {
    for (const child of element.childNodes) {
        if (isTextNode(child)) {
            if (child.nodeValue.length > 0) out.push(child)
        } else if (isElement(child)) {
            collectTextNodes(child, out)
        }
    }
}
