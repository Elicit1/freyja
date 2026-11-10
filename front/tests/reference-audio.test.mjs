import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import ts from 'typescript'

// Exercise the real TypeScript helper with Node's built-in runner; no new dependencies.
const source = await readFile(new URL('../src/utils/reference-audio.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText
const { probeReferenceAudioDuration } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
test('metadata failure stays unknown instead of fabricating a duration', async () => {
  globalThis.Audio = class {
    set src(value) { queueMicrotask(() => this.onerror?.()) }
    removeAttribute() {}
    load() {}
  }
  assert.equal(await probeReferenceAudioDuration('unreadable.wav'), undefined)
})
