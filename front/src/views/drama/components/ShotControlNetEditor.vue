<template>
  <div class="control-editor">
    <div class="control-heading">
      <div><strong>ControlNet 动作控制</strong><p>每条启用的控制输入各占一个 Video 参考槽位，可在提示词中引用对应编号；原视频不再额外挂载。同源的多条控制分别编号。当前占用 {{ controlSlotCount(modelValue) }} 个槽位。</p></div>
      <el-switch :model-value="modelValue.enabled" :disabled="!videos.length" aria-label="启用 ControlNet" @update:model-value="toggle(Boolean($event))" />
    </div>
    <p v-if="!videos.length">添加参考视频后可配置控制源。</p>
    <template v-if="modelValue.enabled">
      <div v-for="(input, index) in inputs" :key="index" class="control-row">
        <div class="control-heading"><strong>控制源 {{ index + 1 }} <span v-if="slotLabel(index)">· {{ slotLabel(index) }}</span></strong><div class="row-actions"><el-switch :model-value="input.enabled" :aria-label="`启用控制源 ${index + 1}`" @update:model-value="patch(index, 'enabled', Boolean($event))" /><el-button text type="danger" :aria-label="`移除控制源 ${index + 1}`" @click="remove(index)">移除</el-button></div></div>
        <div class="control-fields">
          <label>参考视频<el-select :model-value="input.referenceId" :disabled="!input.enabled" placeholder="请选择控制源" clearable @update:model-value="patch(index, 'referenceId', $event || undefined)"><el-option v-for="(video, i) in videos" :key="String(video.id)" :value="String(video.id)" :label="video.name || `Video ${i + 1}`" /></el-select></label>
          <label>控制类型<el-select :model-value="input.controlType" :disabled="!input.enabled" @update:model-value="patch(index, 'controlType', $event)"><el-option v-for="type in H3_CONTROL_TYPES" :key="type.value" :value="type.value" :label="type.label" /></el-select></label>
          <label>输入方式<el-select :model-value="input.inputMode" :disabled="!input.enabled || ['PREPARED', 'LAYOUT'].includes(input.controlType)" @update:model-value="patch(index, 'inputMode', $event)"><el-option value="RAW" label="原始视频，自动提取" /><el-option value="PREPROCESSED" label="已处理视频，直接使用" /></el-select></label>
          <label>强度<el-input-number :model-value="input.strength" :disabled="!input.enabled" :min="0" :max="2" :step="0.1" @update:model-value="patch(index, 'strength', $event ?? 1)" /></label>
        </div>
        <p v-if="input.inputMode === 'PREPROCESSED'">直接使用此视频，不再提取骨架、深度或边缘。</p>
      </div>
      <el-button :disabled="inputs.length >= MAX_CONTROL_INPUTS" @click="add">添加控制源（{{ inputs.length }} / {{ MAX_CONTROL_INPUTS }}）</el-button>
    </template>
  </div>
</template>
<script setup lang="ts">
import { computed, watch } from 'vue'
import { H3_CONTROL_TYPES } from '@/constants/h3ControlTypes'
import type { ShotRefVideo } from '@/types/drama'
import { controlInputs, controlSlotCount, videoReferenceBindings, MAX_CONTROL_INPUTS, type ControlNetConfiguration, type ControlNetInput } from '@/utils/control-net'
export type { ControlNetConfiguration } from '@/utils/control-net'
const props = defineProps<{ modelValue: ControlNetConfiguration; videos: ShotRefVideo[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: ControlNetConfiguration] }>()
const inputs = computed(() => controlInputs(props.modelValue))
function slotLabel(index: number) {
  if (!props.modelValue.enabled || !inputs.value[index]?.enabled) return ''
  const activeIndex = inputs.value.slice(0,index).filter(input => input.enabled).length
  const slot = videoReferenceBindings(props.videos,props.modelValue).findIndex(binding => binding.controlIndex === activeIndex)
  return slot < 0 ? '' : `Video ${slot + 1}`
}
function update(controls: ControlNetInput[], enabled = props.modelValue.enabled) { emit('update:modelValue', { enabled, controls }) }
function newInput(): ControlNetInput { return { enabled: true, referenceId: props.videos.length === 1 ? String(props.videos[0]!.id) : undefined, controlType: 'POSE', inputMode: 'RAW', strength: 1 } }
function toggle(enabled: boolean) {
  const rows = inputs.value.length ? inputs.value : [newInput()]
  update(rows.map(input => !input.referenceId && props.videos.length === 1 ? { ...input, referenceId: String(props.videos[0]!.id) } : input), enabled)
}
function add() { if (inputs.value.length < MAX_CONTROL_INPUTS) update([...inputs.value, newInput()]) }
function remove(index: number) { const rows = inputs.value.filter((_, i) => i !== index); update(rows, rows.length > 0 && props.modelValue.enabled) }
function patch<K extends keyof ControlNetInput>(index: number, key: K, value: ControlNetInput[K]) {
  const rows = inputs.value.map((input, i) => i === index ? { ...input, [key]: value } : input)
  if (key === 'controlType' && (value === 'PREPARED' || value === 'LAYOUT')) rows[index] = { ...rows[index]!, inputMode: 'PREPROCESSED' }
  update(rows)
}
watch(() => props.videos.map(video => String(video.id)), ids => {
  let changed = !ids.length && props.modelValue.enabled
  const rows = inputs.value.map(input => {
    if (input.referenceId && !ids.includes(input.referenceId)) { changed = true; return { ...input, referenceId: undefined, enabled: false } }
    return input
  })
  if (changed) update(rows, ids.length > 0 && props.modelValue.enabled)
}, { immediate: true })
</script>
<style scoped>
.control-editor { display: grid; gap: 12px; padding: 16px; border: 1px solid var(--border-default, #e5e7eb); border-radius: 10px; background: var(--surface-muted, #f9fafb); }
.control-heading, .row-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.control-row { display: grid; gap: 12px; padding: 14px; border: 1px solid var(--border-default, #e5e7eb); border-radius: 8px; background: var(--surface-raised, #fff); }
.control-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
strong { font-size: 13px; } p, label { font-size: 12px; color: var(--text-secondary); } p { margin: 4px 0 0; line-height: 1.6; } label { display: grid; gap: 6px; min-width: 0; } .el-select, .el-input-number { width: 100%; }
@media (max-width: 700px) { .control-fields { grid-template-columns: 1fr; } }
</style>
