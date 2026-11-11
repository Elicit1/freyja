import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import vm from 'node:vm'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

const source = await readFile(new URL('../src/components/workflow/ContinuationSegmentUpscaleDialog.vue', import.meta.url), 'utf8')
const script = compileScript(parse(source).descriptor, { id: 'segment-upscale-test' }).content
const compiled = ts.transpileModule(script, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const ids = ['2032095628944588801', '2032095628944588802', '2032095628944588803']
const originalOptions = { upscale: true, upscaleEngine: 'FREYJA', upscaleDenoise: .25, upscaleMemory: 'TEMPORAL' }
const summary = () => ({ supported: true, providerId: '2032095628944588888', modelCode: 'h3', options: { ...originalOptions },
  segments: ids.map((id, index) => ({ id, index: index + 1, name: `片段${index + 1}`, startFrame: index * 24, endFrame: (index + 1) * 24, videoUrl: `clip-${index}.mp4`, allowed: index !== 1 })) })
function setup(overrides = {}) {
  const exports = {}, scope = vue.effectScope(), calls = [], messages = []
  const api = { segments: async () => summary(), capabilities: async () => ({ models: [{ modelCode: 'h3', supported: true, freyjaUpscale: { supported: true, nativeLatentInput: true } }] }),
    replaceSegments: async (...args) => { calls.push(args); return { taskId: 'new-task' } }, ...overrides }
  let serial = 0
  vm.runInNewContext(compiled, { exports, crypto: { randomUUID: () => `request-${++serial}` }, require: name => name === 'vue' ? vue : name === 'element-plus' ? { ElMessage: { success: text => messages.push(text) } } : name.endsWith('.vue') ? {} : {
    videoEnhancementApi: api, defaultWorkflowOptions: () => ({ ...originalOptions }), normalizeWorkflowOptions: value => ({ ...originalOptions, ...value })
  } })
  const state = scope.run(() => exports.default.setup({}, { expose() {} }))
  return { state, api, calls, messages, stop: () => scope.stop() }
}
test('select all excludes unavailable clips; the ordered selection, count and preview stay in sync', async () => {
  const view = setup(), s = view.state
  try {
    await s.open('source')
    await vue.nextTick()
    s.options.value.upscaleDenoise = .1
    s.toggleAll(true)
    await vue.nextTick()
    assert.deepEqual(Array.from(s.selected.value, clip => clip.id), [ids[0], ids[2]])
    assert.equal(s.allSelected.value, true)
    assert.equal(s.selectedSeconds.value, '2.0')
    assert.equal(s.options.value.upscaleDenoise, .1)
    s.previewId.value = ids[2]
    s.removeSegment(ids[2])
    await vue.nextTick()
    assert.equal(s.previewSegment.value.id, ids[0])
    assert.equal(s.partlySelected.value, true)
    s.toggleAll(false)
    await vue.nextTick()
    assert.equal(s.previewSegment.value, undefined)
    assert.equal(s.canSubmit.value, false)
    await s.submit()
    assert.equal(view.calls.length, 0)
  } finally { view.stop() }
})
test('one multi-selection request preserves IDs and keeps the submitted clips visible', async () => {
  const view = setup(), s = view.state
  try {
    await s.open('source')
    s.selectedIds.value = [ids[2], ids[0]]
    await s.submit()
    assert.equal(view.calls.length, 1)
    assert.deepEqual(Array.from(view.calls[0][1]), [ids[0], ids[2]])
    assert.equal(s.submittedTask.value, 'new-task')
    assert.equal(s.visible.value, true)
    s.toggleAll(false)
    s.removeSegment(ids[0])
    await s.submit()
    assert.equal(s.selected.value.length, 2)
    assert.equal(view.calls.length, 1)
  } finally { view.stop() }
})
test('network retries keep the request ID; changing the selection creates a new request', async () => {
  const requests = []
  const view = setup({ replaceSegments: async (...args) => { requests.push(args); throw new Error('connection lost') } }), s = view.state
  try {
    await s.open('source')
    await s.submit(); await s.submit()
    assert.equal(requests[0][3], requests[1][3])
    s.toggleAll(true)
    await s.submit()
    assert.notEqual(requests[1][3], requests[2][3])
    assert.equal(s.error.value, 'connection lost')
  } finally { view.stop() }
})
test('late opens cannot overwrite a different source and unsupported services cannot submit', async () => {
  let finish
  const view = setup({ segments: task => task === 'old' ? new Promise(resolve => { finish = resolve }) : Promise.resolve(summary()), capabilities: async () => ({ models: [] }) }), s = view.state
  try {
    const old = s.open('old')
    await s.open('new')
    finish({ ...summary(), supported: false })
    await old
    assert.equal(s.taskId.value, 'new')
    assert.equal(s.summary.value.supported, true)
    await s.submit()
    assert.equal(view.calls.length, 0)
  } finally { view.stop() }
})

test('rerun scope allows fresh base without native latent and is included in idempotency', async () => {
  const view = setup(), s = view.state
  try {
    await s.open('source')
    s.rerunMode.value = 'BASE_ONLY'
    await vue.nextTick()
    s.toggleAll(true)
    assert.deepEqual(Array.from(s.selected.value, clip => clip.id), ids)
    await s.submit()
    assert.equal(view.calls[0][4], 'BASE_ONLY')
    assert.equal(s.selected.value.length, 3)
  } finally { view.stop() }
})
test('result steps name the original segment and preserve whether its output was reused', async () => {
  const source = await readFile(new URL('../src/components/workflow/DynamicWorkflowSteps.vue', import.meta.url), 'utf8')
  const compiled = ts.transpileModule(compileScript(parse(source).descriptor, { id: 'steps-test' }).content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const exports = {}, scope = vue.effectScope()
  const steps = [{ step_key: 'segment-002:01:UPSCALE', stage: 'UPSCALE', status: 'SUCCESS' }, { step_key: 'segment-001:02:FACE', stage: 'FACE', status: 'SUCCESS', source_task_id: 'original' }]
  vm.runInNewContext(compiled, { exports, require: name => name === 'vue' ? vue : name.endsWith('.vue') ? {} : { videoEnhancementApi: { steps: async () => steps } } })
  const s = scope.run(() => exports.default.setup({ taskId: 'new', status: 'SUCCESS' }, { expose() {} }))
  try {
    await s.load()
    assert.equal(s.stepLabel(steps[0]), '片段 3 · 二采放大')
    assert.equal(s.stepLabel(steps[1]), '片段 2 · 人脸修复')
    assert.equal(s.steps.value[1].source_task_id, 'original')
    assert.equal(s.stepLabel({ step_key: 'single:01:UPSCALE', stage: 'UPSCALE' }), '二采放大')
  } finally { scope.stop() }
})
