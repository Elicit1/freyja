import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import ts from 'typescript'

const source = await readFile(new URL('../src/utils/voice-request-editor.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
const { parseVoiceRequest, readVoiceForm, writeVoiceForm, emptyVoiceRequest, formatVoiceRequest } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

test('editing the form preserves advanced options, message metadata and exact tags without mutating the original', () => {
  const original = { temperature: 0.7, audio: { optimize_text_preview: false }, messages: [
    { role: 'user', content: '原指导', name: 'director' },
    { role: 'assistant', content: '(悲伤)原台词', name: 'actor' },
  ] }
  const saved = structuredClone(original)
  const fields = { instruction: '最后一句轻声说出。', text: '(悲伤 克制)你回来了。\n[哽咽]我一直在等你。' }
  const changed = writeVoiceForm(original, fields)
  assert.deepEqual(original, saved)
  assert.deepEqual(readVoiceForm(parseVoiceRequest(JSON.stringify(changed))), fields)
  assert.deepEqual(changed.audio, original.audio)
  assert.equal(changed.temperature, 0.7)
  assert.equal(changed.messages[0].name, 'director')
  assert.equal(changed.messages[1].name, 'actor')
})

test('a request with only assistant content gains a user message only when explicitly filled', () => {
  const request = { messages: [{ role: 'assistant', content: '你好' }] }
  assert.equal(writeVoiceForm(request, { instruction: '', text: '新台词' }).messages.length, 1)
  assert.deepEqual(writeVoiceForm(request, { instruction: '放慢语速', text: '新台词' }).messages,
    [{ role: 'user', content: '放慢语速' }, { role: 'assistant', content: '新台词' }])
  assert.deepEqual(readVoiceForm(emptyVoiceRequest()), { instruction: '', text: '' })
})

test('complex or generated-text requests stay in JSON mode instead of losing messages', () => {
  for (const messages of [
    [{ role: 'system', content: '保留系统消息' }, { role: 'assistant', content: '你好' }],
    [{ role: 'user', content: '自动试音' }],
    [{ role: 'assistant', content: '第一句' }, { role: 'assistant', content: '第二句' }],
    [{ role: 'assistant', content: [{ type: 'text', text: '你好' }] }],
  ]) assert.throws(() => readVoiceForm({ messages }), /继续使用 JSON/)
})

test('formatting preserves message strings and rejects invalid or non-object JSON', () => {
  const compact = JSON.stringify({ messages: [{ role: 'assistant', content: '（愤怒）马上\n[提高音量]离开！' }], audio: { optimize_text_preview: false } })
  assert.deepEqual(JSON.parse(formatVoiceRequest(compact)), JSON.parse(compact))
  assert.ok(formatVoiceRequest(compact).includes('\n  "messages"'))
  for (const invalid of ['{', '[]', 'null', '"text"']) assert.throws(() => formatVoiceRequest(invalid))
})
