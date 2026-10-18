<template>
  <div class="filter-editor">
    <div v-for="(step, index) in modelValue" :key="index" class="filter-step">
      <div class="step-heading">
        <strong>{{ index + 1 }}. {{ definition(step.type)?.label || step.type }}</strong>
        <el-tag v-if="definition(step.type) && !definition(step.type)?.available" type="warning" size="small">服务器暂不支持</el-tag>
        <div class="step-actions">
          <el-button size="small" :disabled="index === 0" @click="move(index, -1)">上移</el-button>
          <el-button size="small" :disabled="index === modelValue.length - 1" @click="move(index, 1)">下移</el-button>
          <el-button size="small" type="danger" text @click="remove(index)">删除</el-button>
        </div>
      </div>
      <div v-for="parameter in definition(step.type)?.parameters || []" :key="parameter.key" class="parameter">
        <span>{{ parameter.label }}</span>
        <el-slider :model-value="step.params[parameter.key] ?? parameter.initial" :min="parameter.min" :max="parameter.max" :step="parameter.step" show-input @update:model-value="setParameter(index, parameter.key, $event)" />
      </div>
    </div>
    <el-select v-model="addType" placeholder="添加处理步骤" @change="add" class="!w-full">
      <el-option v-for="filter in filters" :key="filter.type" :label="filter.label + (filter.available ? '' : '（不可用）')" :value="filter.type" :disabled="!filter.available || modelValue.length >= 12" />
    </el-select>
    <p class="hint">步骤按顺序执行。男女双声是同一声音的变调叠加。</p>
  </div>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import type { Step, Filter } from '@/api/dubbing'
const props = defineProps<{ modelValue: Step[]; filters: Filter[] }>()
const emit = defineEmits<{ 'update:modelValue': [steps: Step[]] }>()
const addType = ref('')
const definition = (type: string) => props.filters.find(f => f.type === type)
function copy() { return structuredClone(props.modelValue.map(s => ({ type: s.type, params: { ...s.params } }))) }
function add(type: string) {
  const filter = definition(type)
  if (filter) emit('update:modelValue', [...copy(), { type, params: Object.fromEntries(filter.parameters.map(p => [p.key, p.initial])) }])
  addType.value = ''
}
function move(index: number, offset: number) { const steps = copy(); const [item] = steps.splice(index, 1); if (item) steps.splice(index + offset, 0, item); emit('update:modelValue', steps) }
function remove(index: number) { emit('update:modelValue', copy().filter((_, i) => i !== index)) }
function setParameter(index: number, key: string, value: number | number[]) { const steps = copy(); if (steps[index] && typeof value === 'number') steps[index].params[key] = value; emit('update:modelValue', steps) }
</script>
<style scoped>
.filter-step { border: 1px solid var(--el-border-color-light); border-radius: 12px; padding: 16px; margin-bottom: 12px; }
.step-heading,.step-actions { display:flex; align-items:center; gap:8px; }.step-actions{margin-left:auto}.step-heading{flex-wrap:wrap;margin-bottom:10px}
.parameter{display:grid;grid-template-columns:110px 1fr;gap:18px;align-items:center;padding:6px 0}.parameter span{font-size:12px;color:var(--el-text-color-secondary)}.hint{font-size:12px;color:var(--el-text-color-secondary);margin-top:10px}
@media(max-width:650px){.parameter{grid-template-columns:1fr}.step-actions{margin-left:0}}
</style>
