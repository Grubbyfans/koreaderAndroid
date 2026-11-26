import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
const src = await readFile(new URL('../app/src/main/assets/reader/speech-highlight.js', import.meta.url), 'utf8')
const { SpeechHighlight, startIsVisible, chapterParagraphs, resolveSpeechAnchor } = await import(`data:text/javascript;base64,${Buffer.from(src).toString('base64')}`)
const text = value => ({ nodeType: 3, nodeValue: value, textContent: value })
const element = (tagName, ...childNodes) => ({ nodeType: 1, tagName, childNodes })
class FakeRange {
    constructor(doc) { this.doc = doc }
    setStart(node, offset) { this.startContainer = node; this.startOffset = offset; this.endContainer ??= node; this.endOffset ??= offset }
    setEnd(node, offset) { this.endContainer = node; this.endOffset = offset }
    cloneRange() { return Object.assign(new FakeRange(this.doc), this) }
    collapse(start) {
        if (start) { this.endContainer = this.startContainer; this.endOffset = this.startOffset }
        else { this.startContainer = this.endContainer; this.startOffset = this.endOffset }
    }
    compareBoundaryPoints(_, other) { return this.startContainer.order * 1000 + this.startOffset - (other.startContainer.order * 1000 + other.startOffset) }
}
const a = text('  第一段'), b = text('39.8元。  '), c = text('下一段\n继续。')
const doc = { body: element('BODY', element('P', a, element('EM', b)), element('SCRIPT', text('跳过')), element('P', c)), createRange() { return new FakeRange(this) } }
for (const [order, node] of [a,b,c].entries()) { node.ownerDocument = doc; node.order = order }
assert.deepEqual(chapterParagraphs(doc).map(x => x.map(c => c.ch).join('')), ['第一段39.8元。', '下一段 继续。'])
let scrolls = 0, draws = 0, removed = 0, resolveNavigation
const item = { doc, index: 0, overlayer: { add() { draws++ }, remove() { removed++ } } }
const view = { renderer: { getContents: () => [item], scrollToAnchor: point => { scrolls++; assert.equal(point.startOffset, point.endOffset) } },
    resolveCFI: () => ({ index: 0, anchor: () => { const r = doc.createRange(); r.setStart(a, 0); r.collapse(true); return r } }),
    goTo: () => new Promise(resolve => { resolveNavigation = resolve }), lastLocation: {} }
const start = resolveSpeechAnchor(view, '0:0', 3).anchor(doc)
assert.equal(start.startContainer, b); assert.equal(start.startOffset, 0)
const visible = doc.createRange(); visible.setStart(a, 0); visible.setEnd(a, a.textContent.length)
view.lastLocation.range = visible
assert.equal(startIsVisible(start, visible), false)
const boundary = doc.createRange(); boundary.setStart(a, a.textContent.length); boundary.collapse(true)
assert.equal(startIsVisible(boundary, visible), false) // exclusive page end
const highlight = new SpeechHighlight(view, () => {})
await highlight.set('0:0', 3, '0:0', 8)
assert.equal(scrolls, 1); assert.equal(draws, 1)
highlight.render(false) // manual page turn redraws but cannot turn the page back
assert.equal(scrolls, 1); assert.equal(draws, 2)
await highlight.set('epubcfi(test)', 2, 'epubcfi(test)', 4)
assert.equal(scrolls, 1) // current visible start remains on same page
const pending = highlight.set('1:0', 0, '1:0', 2)
highlight.clear(); resolveNavigation(); assert.equal(await pending, false)
assert.equal(highlight.render(), false); assert.ok(removed > 0)
console.log('PASS: native anchor mapping, CFI, page bounds, following, redraw, clear and stale navigation')

let releaseTurn
const committedTurn = new Promise(resolve => { releaseTurn = resolve })
const deferredHighlight = new SpeechHighlight(view, () => {}, () => committedTurn)
const beforeDeferred = scrolls
await deferredHighlight.set('0:0', 3, '0:0', 8)
assert.equal(scrolls, beforeDeferred)
deferredHighlight.clear()
releaseTurn()
await Promise.resolve()
await Promise.resolve()
assert.equal(scrolls, beforeDeferred, 'cleared TTS range cannot navigate after an earlier turn completes')
