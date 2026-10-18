<template>
  <div>
    <el-select :model-value="modelValue.map(item => item.effectId)" multiple clearable filterable placeholder="无特效，直接使用克隆原声" class="!w-full" :multiple-limit="6" @update:model-value="select">
      <el-option v-for="effect in options" :key="String(effect.id)" :value="String(effect.id)" :label="effect.name" :disabled="effect.enabled !== 1 && !modelValue.some(s => s.effectId === String(effect.id))" />
    </el-select>
    <div v-for="(selection, index) in modelValue" :key="selection.effectId" class="effect-config">
      <div class="effect-title">
        <strong>{{ index + 1 }}. {{ label(selection.effectId) }}</strong>
        <el-button size="small" :disabled="index === 0" @click="move(index, -1)">上移</el-button>
        <el-button size="small" :disabled="index === modelValue.length - 1" @click="move(index, 1)">下移</el-button>
        <el-button size="small" text @click="reset(index)">使用模板最新参数</el-button>
      </div>
      <FilterSteps :model-value="selection.steps || []" :filters="filters" @update:model-value="update(index, $event)" />
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { Effect, Selection, Filter, Step } from '@/api/dubbing'
import FilterSteps from './FilterSteps.vue'
const props = defineProps<{ modelValue: Selection[]; effects: Effect[]; filters: Filter[]; names?: Record<string, string> }>()
const emit = defineEmits<{ 'update:modelValue': [value: Selection[]] }>()
const options = computed(() => {
  const known = new Set(props.effects.map(e => String(e.id)))
  return [...props.effects, ...props.modelValue.filter(s => !known.has(s.effectId)).map(s => ({ id: s.effectId, name: props.names?.[s.effectId] || '历史特效', enabled: 0, stepsJson: JSON.stringify(s.steps || []), version: 0 }))]
})
function label(id: string) { return props.names?.[id] || props.effects.find(e => String(e.id) === id)?.name || '历史特效' }
function steps(id: string): Step[] { return JSON.parse(props.effects.find(e => String(e.id) === id)?.stepsJson || '[]') }
function select(ids: string[]) { emit('update:modelValue', ids.map(id => props.modelValue.find(s => s.effectId === id) || { effectId: id, steps: steps(id) })) }
function update(index: number, value: Step[]) { emit('update:modelValue', props.modelValue.map((s, i) => i === index ? { ...s, steps: value } : s)) }
function reset(index: number) { const item = props.modelValue[index]; if (item) update(index, steps(item.effectId)) }
function move(index: number, offset: number) { const items = [...props.modelValue]; const [item] = items.splice(index, 1); if (item) items.splice(index + offset, 0, item); emit('update:modelValue', items) }
</script>
<style scoped>
.effect-config{margin-top:16px;padding:14px;border-radius:12px;background:var(--el-fill-color-light)}.effect-title{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:12px}.effect-title strong{margin-right:auto}
</style>
