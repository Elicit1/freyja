import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

const source = await readFile(new URL('../src/utils/control-net.ts', import.meta.url), 'utf8')
const exports = {}
vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, { exports })
const id = '2032095628944588801'

test('active control rows share their source exclusion while each occupies its own slot', () => {
  const config = { enabled: true, controls: [{ enabled: true, referenceId: id }, { enabled: true, referenceId: id }, { enabled: false, referenceId: 'other' }] }
  assert.equal(exports.controlSlotCount(config), 2)
  assert.deepEqual(Array.from(exports.controlSourceIds(config)), [id])
  assert.deepEqual(JSON.parse(JSON.stringify(exports.videoReferenceBindings([{id}],config))), [{sourceId:id,controlIndex:0},{sourceId:id,controlIndex:1}])
  config.enabled = false
  assert.equal(exports.controlSlotCount(config), 0)
  assert.equal(exports.controlSourceIds(config).size, 0)
  assert.deepEqual(JSON.parse(JSON.stringify(exports.videoReferenceBindings([{id}],config))), [{sourceId:id}])
})

test('legacy single control preserves the Snowflake source ID', () => {
  assert.deepEqual(Array.from(exports.controlSourceIds({ enabled: true, referenceId: id })), [id])
})
