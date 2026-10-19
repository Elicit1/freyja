<template>
  <div>
    <el-select :model-value="modelValue.map(item => item.effectId)" multiple clearable filterable placeholder="无特效，直接使用克隆原声" class="!w-full" :multiple-limit="6" @update:model-value="select">
      <el-option v-for="effect in options" :key="String(effect.id)" :value="String(effect.id)" :label="effect.name" :disabled="effect.enabled !== 1 && !modelValue.some(s => s.effectId === String(effect.id))" />
    </el-select>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { Effect, Selection, Step } from '@/api/dubbing'
const props = defineProps<{ modelValue: Selection[]; effects: Effect[]; names?: Record<string, string> }>()
const emit = defineEmits<{ 'update:modelValue': [value: Selection[]] }>()
const options = computed(() => {
  const known = new Set(props.effects.map(e => String(e.id)))
  return [...props.effects, ...props.modelValue.filter(s => !known.has(s.effectId)).map(s => ({ id: s.effectId, name: props.names?.[s.effectId] || '历史特效', enabled: 0, stepsJson: JSON.stringify(s.steps || []), version: 0 }))]
})
function steps(id: string): Step[] { return JSON.parse(props.effects.find(e => String(e.id) === id)?.stepsJson || '[]') }
function select(ids: string[]) { emit('update:modelValue', ids.map(id => props.modelValue.find(s => s.effectId === id) || { effectId: id, steps: steps(id) })) }
</script>
