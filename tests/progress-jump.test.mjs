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
    })
    return { reader: window.__reader, messages, visited }
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
