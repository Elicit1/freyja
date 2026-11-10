import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import vm from 'node:vm'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

const source = await readFile(new URL('../src/components/workflow/FreyjaUpscaleOptions.vue', import.meta.url), 'utf8')
const script = compileScript(parse(source).descriptor, { id: 'upscale-options-test' }).content
const compiled = ts.transpileModule(script, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const flush = () => new Promise(resolve => setImmediate(resolve))

function setup(fetch, value = {}, extra = {}) {
  const props = vue.reactive({ providerId: '2032095628944588801', modelCode: 'h3', nodeId: 'node-a', modelValue: {
    upscaleEngine: 'FREYJA', upscaleMethod: 'H3_LEARNED', upscaleMemory: 'TEMPORAL', ...value
  }, ...extra })
  const exports = {}, scope = vue.effectScope(), updates = []
  vm.runInNewContext(compiled, { exports, require: name => name === 'vue' ? vue : {
    videoEnhancementApi: { upscaleModels: fetch }, defaultUpscaleTiling: () => ({ tileWidth: 768, tileHeight: 512, overlap: 192, windowFrames: 56, overlapFrames: 22 })
  } })
  const state = scope.run(() => exports.default.setup(props, { expose() {}, emit: (_, value) => { updates.push(value); props.modelValue = value } }))
  return { props, state, updates, stop: () => scope.stop() }
}

test('built-in upscale needs no inventory request and explains all three memory modes', async () => {
  const view = setup(() => assert.fail('Built-in upscale must not probe installed weights'), { upscaleMethod: 'LATENT_BICUBIC' })
  try {
    await flush()
    assert.equal(view.state.memoryModes.length, 3)
    assert.ok(view.state.memoryModes.every(mode => mode.description.length > 10))
    assert.equal(view.props.modelValue.upscaleMemory, 'TEMPORAL')
  } finally { view.stop() }
})

test('a single installed model is selected without changing the Snowflake provider ID', async () => {
  const calls = []
  const view = setup(async (...args) => { calls.push(args); return { models: ['installed.safetensors'] } })
  try {
    await flush()
    assert.deepEqual(calls, [['2032095628944588801', 'h3', 'node-a']])
    assert.equal(view.props.modelValue.upscaleLatentModel, 'installed.safetensors')
    view.state.patch('upscaleMethod', 'LATENT_BICUBIC')
    assert.equal(view.props.modelValue.upscaleLatentModel, undefined)
  } finally { view.stop() }
})

test('changing the compute node discards a late inventory response', async () => {
  let finishOld
  const view = setup((_, __, node) => node === 'node-a' ? new Promise(resolve => { finishOld = resolve }) : Promise.resolve({ models: ['new.safetensors'] }))
  try {
    view.props.nodeId = 'node-b'
    await flush()
    finishOld({ models: ['old.safetensors'] })
    await flush()
    assert.equal(view.props.modelValue.upscaleLatentModel, 'new.safetensors')
    assert.equal(view.state.installedModels.value[0], 'new.safetensors')
  } finally { view.stop() }
})

test('unknown or unavailable inventory never invents or silently replaces a saved model', async () => {
  for (const fetch of [async () => ({ models: [] }), async () => { throw new Error('node offline') }]) {
    const view = setup(fetch, { upscaleLatentModel: 'previous.safetensors' })
    try {
      await flush()
      assert.equal(view.props.modelValue.upscaleLatentModel, 'previous.safetensors')
      assert.equal(view.updates.length, 0)
      assert.equal(view.state.installedModels.value.length, 0)
    } finally { view.stop() }
  }
})
