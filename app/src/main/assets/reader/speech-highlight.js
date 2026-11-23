// Retain the confirmed playback range across pagination and document reloads.
export class SpeechHighlight {
    constructor(view, draw) { this.view = view; this.draw = draw; this.request = null; this.version = 0 }
    clear() {
        this.request = null
        ++this.version
        for (const item of this.view.renderer?.getContents?.() ?? []) item.overlayer?.remove('__tts_highlight__')
    }
    async set(startCfi, startOffset, endCfi, endOffset) {
        this.request = { startCfi, startOffset, endCfi, endOffset }
        const version = ++this.version
        const resolved = resolveSpeechAnchor(this.view, startCfi, startOffset)
        if (!(this.view.renderer?.getContents?.() ?? []).some(x => x.index === resolved.index)) {
            await this.view.goTo(resolved.index)
            if (version !== this.version) return false
        }
        return this.render(true)
    }
    render(follow = false) {
        if (!this.request) return false
        const { startCfi, startOffset, endCfi, endOffset } = this.request
        const resolved = resolveSpeechAnchor(this.view, startCfi, startOffset)
        const item = this.view.renderer?.getContents?.().find(x => x.index === resolved.index)
        if (!item?.doc) return false
        const range = resolved.anchor(item.doc)
        if (endCfi) {
            const end = resolveSpeechAnchor(this.view, endCfi, endOffset)
            if (end.index === resolved.index) {
                const endRange = end.anchor(item.doc)
                range.setEnd(endRange.startContainer, endRange.startOffset)
            }
        }
        // A new playback event may turn the page. Repainting after a manual turn must not navigate.
        if (follow && !startIsVisible(range, this.view.lastLocation?.range)) {
            const point = range.cloneRange()
            point.collapse(true)
            Promise.resolve(this.view.renderer?.scrollToAnchor?.(point, true)).catch(() => {})
        }
        for (const other of this.view.renderer?.getContents?.() ?? [])
            if (other !== item) other.overlayer?.remove('__tts_highlight__')
        item.overlayer?.add('__tts_highlight__', range, this.draw, { color: 'rgba(7, 193, 96, 0.28)' })
        return true
    }
}

export function startIsVisible(range, visible) {
    if (!visible || visible.startContainer.ownerDocument !== range.startContainer.ownerDocument) return false
    const point = range.cloneRange(); point.collapse(true)
    const pageStart = visible.cloneRange(); pageStart.collapse(true)
    const pageEnd = visible.cloneRange(); pageEnd.collapse(false)
    return point.compareBoundaryPoints(0, pageStart) >= 0 && point.compareBoundaryPoints(0, pageEnd) < 0
}

// Mirrors HtmlTextExtractor's native chapter/paragraph anchors, preserving DOM positions.
const nativeParagraphs = new WeakMap()
const skipped = new Set(['SCRIPT', 'STYLE', 'HEAD', 'TITLE', 'NOSCRIPT', 'TEMPLATE'])
const blocks = new Set(['P', 'DIV', 'SECTION', 'ARTICLE', 'ASIDE', 'HEADER', 'FOOTER', 'NAV',
    'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'LI', 'UL', 'OL', 'BLOCKQUOTE', 'PRE', 'TABLE', 'TR',
    'FIGCAPTION', 'FIGURE', 'BR', 'HR'])
export function chapterParagraphs(doc) {
    if (nativeParagraphs.has(doc)) return nativeParagraphs.get(doc)
    const chars = []
    function visit(node) {
        if (node.nodeType === 3) {
            const text = node.nodeValue
            let whitespace = false
            for (let i = 0; i < text.length; i++) {
                const ch = text[i]
                if (ch === '\u200b' || ch === '\u00ad') continue
                if (/[ \t\n\f\r\u00a0]/.test(ch)) {
                    if (!whitespace) chars.push({ ch: ' ', node, offset: i })
                    whitespace = true
                } else { chars.push({ ch, node, offset: i }); whitespace = false }
            }
        } else {
            const tag = node.tagName?.toUpperCase()
            if (skipped.has(tag)) return
            for (const child of node.childNodes ?? []) visit(child)
            if (blocks.has(tag)) chars.push({ ch: '\n' })
        }
    }
    visit(doc.body)
    const paragraphs = []
    let line = []
    const flush = () => {
        while (line.length && /\s/.test(line[0].ch)) line.shift()
        while (line.length && /\s/.test(line.at(-1).ch)) line.pop()
        if (line.length) paragraphs.push(line)
        line = []
    }
    for (const char of chars) {
        if (char.ch === '\n') flush()
        else line.push(char)
    }
    flush()
    nativeParagraphs.set(doc, paragraphs)
    return paragraphs
}

export function resolveSpeechAnchor(view, value, offset = 0) {
    const native = /^(\d+):(\d+)$/.exec(value)
    if (!native) {
        const resolved = view.resolveCFI(value)
        return { index: resolved.index, anchor: doc => {
            const range = resolved.anchor(doc)
            range.setStart(range.startContainer, Math.min(range.startOffset + Math.max(0, offset), range.startContainer.textContent.length))
            range.collapse(true)
            return range
        } }
    }
    return { index: Number(native[1]), anchor: doc => {
        const paragraph = chapterParagraphs(doc)[Number(native[2])]
        if (!paragraph?.length) throw new Error('Speech paragraph unavailable')
        const index = Math.max(0, Math.min(offset, paragraph.length))
        const char = paragraph[Math.min(index, paragraph.length - 1)]
        const range = doc.createRange()
        range.setStart(char.node, char.offset + (index === paragraph.length ? 1 : 0))
        range.collapse(true)
        return range
    } }
}
