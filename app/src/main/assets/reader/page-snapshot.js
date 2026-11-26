// Snapshot inspection for the isolated preview reader; never navigates the primary reader.
export async function pageSnapshotState(view) {
    for (const { doc } of view.renderer?.getContents?.() ?? []) {
        await doc.fonts.ready
        await Promise.all([...doc.images].map(image => {
            if (image.complete) return Promise.resolve()
            return new Promise((resolve, reject) => {
                const finish = () => { clearTimeout(timer); image.removeEventListener('load', finish); image.removeEventListener('error', finish); resolve() }
                const timer = setTimeout(() => {
                    image.removeEventListener('load', finish); image.removeEventListener('error', finish)
                    reject(new Error('Page image readiness timed out'))
                }, 3000)
                image.addEventListener('load', finish, { once: true })
                image.addEventListener('error', finish, { once: true })
            })
        }))
    }
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
    const location = view.lastLocation
    if (!location?.range) throw new Error('Page location unavailable')
    const start = location.range.cloneRange()
    start.collapse(true)
    return {
        cfi: view.getCFI(location.section.current, start),
        index: location.section.current,
        text: location.range.toString(),
        atStart: view.renderer.atStart,
        atEnd: view.renderer.atEnd,
    }
}
