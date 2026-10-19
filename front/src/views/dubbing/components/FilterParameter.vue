<template>
  <div class="parameter" :data-parameter="definition.key">
    <div class="parameter-label"><span>{{ definition.label }}</span><small v-if="definition.unit">{{ definition.unit }}</small></div>
    <div class="parameter-control">
      <div v-if="definition.kind === 'number'" class="numeric-control">
        <el-slider v-if="(definition.step || 1) >= 0.001" :model-value="numericValue" :min="definition.min" :max="definition.max" :step="definition.step" @update:model-value="setNumber" />
        <el-input-number :model-value="numericValue" :min="definition.min" :max="definition.max" :step="definition.step" controls-position="right" @update:model-value="setNumber" />
      </div>
      <el-select v-else-if="definition.kind === 'select'" :model-value="String(value)" @update:model-value="emit('update:modelValue', $event)"><el-option v-for="option in definition.options" :key="option.value" :label="option.label" :value="option.value" /></el-select>
      <el-switch v-else-if="definition.kind === 'boolean'" :model-value="value === true" @update:model-value="emit('update:modelValue', !!$event)" />
      <div v-else-if="definition.kind === 'numberList'" class="list-control">
        <div v-for="(item, index) in listValue" :key="index" class="list-item"><small>{{ index + 1 }}</small><el-input-number :model-value="item" :min="definition.min" :max="definition.max" :step="definition.step" controls-position="right" @update:model-value="setListItem(index, $event)" /><el-button text type="danger" :disabled="listValue.length <= 1" @click="updateList(listValue.filter((_, i) => i !== index))">移除</el-button></div>
        <el-button size="small" :disabled="listValue.length >= (definition.maxItems || 4)" @click="updateList([...listValue, listValue.at(-1) ?? 1])">添加一项</el-button>
      </div>
      <p v-if="definition.description" class="parameter-help">{{ definition.description }}</p>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { FilterParameter, ParameterValue } from '@/api/dubbing'
const props = defineProps<{ definition: FilterParameter; modelValue?: ParameterValue }>()
const emit = defineEmits<{ 'update:modelValue': [value: ParameterValue] }>()
const value = computed(() => props.modelValue ?? props.definition.initial)
const numericValue = computed(() => typeof value.value === 'number' ? value.value : 0)
const listValue = computed(() => Array.isArray(value.value) ? value.value : [])
function setNumber(value: number | number[] | undefined) { if (typeof value === 'number' && Number.isFinite(value)) emit('update:modelValue', value) }
function updateList(value: number[]) { emit('update:modelValue', value) }
function setListItem(index: number, value?: number) { if (typeof value !== 'number' || !Number.isFinite(value)) return; const next = [...listValue.value]; next[index] = value; updateList(next) }
</script>
<style scoped>
.parameter{display:grid;grid-template-columns:140px minmax(0,1fr);gap:20px;padding:12px 0;border-bottom:1px solid var(--el-border-color-extra-light)}.parameter-label{font-size:13px;display:flex;flex-direction:column;gap:4px;padding-top:7px}.parameter-label small,.parameter-help,.list-item small{font-size:11px;color:var(--el-text-color-secondary)}.parameter-help{margin-top:7px;line-height:1.6}.numeric-control{display:flex;gap:22px;align-items:center}.numeric-control .el-slider{flex:1;min-width:60px}.numeric-control .el-input-number{width:155px;flex-shrink:0}.parameter-control>.el-select{width:100%}.list-control{display:flex;flex-wrap:wrap;gap:8px;align-items:center}.list-item{display:flex;gap:6px;align-items:center}.list-item .el-input-number{width:125px}@media(max-width:650px){.parameter{grid-template-columns:1fr;gap:6px}.parameter-label{flex-direction:row;align-items:center}.numeric-control{gap:12px}.numeric-control .el-input-number{width:125px}}
</style>
