import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import ts from 'typescript'

// Exercise the real TypeScript helper with Node's built-in runner; no new dependencies.
const source = await readFile(new URL('../src/utils/reference-audio.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText
const { initialVoiceReferences, probeReferenceAudioDuration } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
const id = '2032095628944588801'
const character = { id, name: '林远', voiceSampleUrl: 'https://example.invalid/voice.wav' }

test('saved empty selection remains empty on reopening', () => {
  assert.deepEqual(initialVoiceReferences(true, [], [id], [character], 3), { audios: [], requiresSelection: false })
})
test('defaults preserve Snowflake IDs and unknown metadata', () => {
  const { audios } = initialVoiceReferences(false, [], [id], [character], 3)
  assert.equal(audios[0].characterId, id)
  assert.equal(audios[0].sourceType, 'CHARACTER')
  assert.equal(audios[0].usageMode, 'VOICE_TIMBRE')
  assert.equal(audios[0].duration, undefined)
})
test('over-capacity voices require a creator choice, without truncation', () => {
  const options = Array.from({ length: 4 }, (_, i) => ({ ...character, id: `${id}${i}`, voiceSampleUrl: `voice-${i}.wav` }))
  assert.deepEqual(initialVoiceReferences(false, [], options.map(c => c.id), options, 3), { audios: [], requiresSelection: true })
})
test('a selected current recording preserves its usage and snapshot URL', () => {
  const recording = { sourceType: 'UPLOAD', audioUrl: 'current.wav', usageMode: 'DIALOGUE_REUSE' }
  assert.deepEqual(initialVoiceReferences(false, [recording], [id], [character], 3).audios, [recording])
})
test('metadata failure stays unknown instead of fabricating a duration', async () => {
  globalThis.Audio = class {
    set src(value) { queueMicrotask(() => this.onerror?.()) }
    removeAttribute() {}
    load() {}
  }
  assert.equal(await probeReferenceAudioDuration('unreadable.wav'), undefined)
})
