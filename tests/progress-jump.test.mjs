import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { runInNewContext } from 'node:vm'

const source = await readFile(new URL('../app/src/main/assets/reader/bridge.js', import.meta.url), 'utf8')

function makeReader(fraction, pointVisible = true) {
    const messages = []
    const visited = []
    const window = { ReaderBridge: { onReaderEvent: (type, data) => messages.push([type, JSON.parse(data)]) } }
    const view = {
        addEventListener() {},
        renderer: { getContents: () => [{ index: 0, doc: {} }] },
        async goTo(cfi) {
            visited.push(cfi)
            this.lastLocation = { fraction, range: { isPointInRange: () => pointVisible } }
            return { index: 0, anchor: () => ({ startContainer: {}, startOffset: 0 }) }
        },
    }
    runInNewContext(source.replace(/^import .*$/gm, ''), {
        window, document: { getElementById: () => view }, Overlayer: {}, SpeechHighlight: class {},
        Range: { START_TO_START: 0 },
    })
    return { reader: window.__reader, messages, visited, view, window }
}

test('verified jump reports the fraction at the actual landing position', async () => {
    const { reader, messages } = makeReader(0.315)
    await reader.verifyJump('request', 'target', 'previous')
    assert.deepEqual(messages, [['jumpVerified', { requestId: 'request', verified: true, fraction: 0.315 }]])
})

test('unknown jump fraction remains absent for the caller to fall back', async () => {
    const { reader, messages } = makeReader(NaN)
    await reader.verifyJump('request', 'target', 'previous')
    assert.deepEqual(messages, [['jumpVerified', { requestId: 'request', verified: true, fraction: null }]])
})

test('unverified jump restores the previous page and does not report its fraction', async () => {
    const { reader, messages, visited } = makeReader(0.315, false)
    await reader.verifyJump('request', 'target', 'previous')
    assert.deepEqual(visited, ['target', 'previous'])
    assert.deepEqual(messages, [['jumpVerified', { requestId: 'request', verified: false, fraction: null }]])
})

test('automatic jump leaves the current page alone after a late relocation', async () => {
    const { reader, messages, visited, view } = makeReader(0.315)
    view.lastLocation = { cfi: 'new-local-page' }
    await reader.verifyJump('request', 'target', 'previous', 'previous')
    assert.deepEqual(visited, [])
    assert.deepEqual(messages, [['jumpVerified', {
        requestId: 'request', verified: false, fraction: null, stale: true,
    }]])
})

test('compares spine order and DOM positions without reading displayed percentages', async () => {
    const { reader, messages, view } = makeReader(0.315)
    view.book = { sections: [{}, {}] }
    view.resolveCFI = cfi => {
        const [index, offset] = cfi.split(':').map(Number)
        return {
            index,
            anchor: () => ({ offset, compareBoundaryPoints: (_, local) => offset - local.offset }),
        }
    }
    await reader.comparePositions('next-chapter', '1:0', '0:100')
    await reader.comparePositions('earlier-in-chapter', '0:19', '0:20')
    await reader.comparePositions('later-in-chapter', '0:21', '0:20')
    await reader.comparePositions('same', '0:20', '0:20')
    await reader.comparePositions('invalid-anchor', '4:0', '0:20')
    assert.deepEqual(messages.map(([type, message]) => [type, message.requestId, message.value]), [
        ['positionConverted', 'next-chapter', '1'],
        ['positionConverted', 'earlier-in-chapter', '-1'],
        ['positionConverted', 'later-in-chapter', '1'],
        ['positionConverted', 'same', '0'],
        ['positionConverted', 'invalid-anchor', null],
    ])
})

test('changing only page animation does not touch layout or navigate', async () => {
    const { reader, view, visited } = makeReader(0.315)
    const mutations = []
    view.renderer.setAttribute = (name, value) => mutations.push([name, value])
    view.renderer.removeAttribute = name => mutations.push([name, null])
    assert.equal(await reader.applySettings({ pageAnimation: 'fade' }), true)
    assert.deepEqual(mutations, [['animated', 'slide']])
    assert.deepEqual(visited, [])
})

test('external navigation waits for an already committed page turn', async () => {
    const { reader, visited, window } = makeReader(0.315)
    let complete
    window.__readerTurnCommit = new Promise(resolve => { complete = resolve })
    const navigation = reader.goToCfi('target-after-turn')
    await Promise.resolve()
    assert.deepEqual(visited, [])
    complete()
    assert.equal(await navigation, true)
    assert.deepEqual(visited, ['target-after-turn'])
})
