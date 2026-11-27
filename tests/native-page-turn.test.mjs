import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import { runInNewContext } from 'node:vm'

const source = await readFile(new URL('../app/src/main/assets/reader/paginator.js', import.meta.url), 'utf8')
const turnPage = source.slice(source.indexOf('    async #turnPage('), source.indexOf('    prev(distance)', source.indexOf('    async #turnPage(')))

function makePaginator(instant, crossChapter, scrollNext = async () => crossChapter) {
    const waits = []
    const Paginator = runInNewContext(`(class {
        #locked = false
        #index = 0
        #scrollNext = scrollNext
        #scrollPrev = scrollNext
        #adjacentIndex = direction => direction
        #goTo = async () => {}
        hasAttribute(name) { return name === 'instant-page-turn' && instant }
        ${turnPage}
        next() { return this.#turnPage(1) }
    })`, { scrollNext, instant, wait: async duration => waits.push(duration) })
    return { paginator: new Paginator(), waits }
}

for (const crossChapter of [false, true]) {
    test(`native ${crossChapter ? 'chapter' : 'page'} turn skips the legacy 100ms delay`, async () => {
        const { paginator, waits } = makePaginator(true, crossChapter)
        await paginator.next()
        assert.deepEqual(waits, [])
    })
}

test('ordinary navigation keeps its existing settling delay', async () => {
    const { paginator, waits } = makePaginator(false, false)
    await paginator.next()
    assert.deepEqual(waits, [100])
})

test('native navigation keeps the lock through chapter work and releases it afterwards', async () => {
    let complete
    let calls = 0
    const { paginator } = makePaginator(true, true, () => {
        calls++
        return new Promise(resolve => { complete = resolve })
    })
    const first = paginator.next()
    await paginator.next()
    assert.equal(calls, 1)
    complete(true)
    await first
    const next = paginator.next()
    assert.equal(calls, 2)
    complete(false)
    await next
})

const bridgeSource = await readFile(new URL('../app/src/main/assets/reader/bridge.js', import.meta.url), 'utf8')
const applyAnimationSource = bridgeSource.slice(
    bridgeSource.indexOf('function applyPageAnimation()'),
    bridgeSource.indexOf('async function applyFlow'),
)

function applyAnimation(mode, existing = {}) {
    const attributes = new Map(Object.entries(existing))
    const renderer = {
        setAttribute: (name, value) => attributes.set(name, value),
        removeAttribute: name => attributes.delete(name),
    }
    runInNewContext(`${applyAnimationSource}\napplyPageAnimation()`, {
        view: { renderer },
        currentSettings: { pageAnimation: mode },
    })
    return attributes
}

for (const mode of ['fade', 'ios_curl', 'ripple']) {
    test(`bridge hands ${mode} gestures to the native turn overlay`, () => {
        const attributes = applyAnimation(mode)
        assert.equal(attributes.get('native-page-turn'), '')
        assert.equal(attributes.has('animated'), false)
    })
}

test('bridge clears the native attribute when switching back to slide or none', () => {
    for (const mode of ['slide', 'none']) {
        const attributes = applyAnimation(mode, { 'native-page-turn': '' })
        assert.equal(attributes.has('native-page-turn'), false)
    }
    assert.equal(applyAnimation('slide').get('animated'), 'slide')
})
