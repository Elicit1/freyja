<template>
  <div v-if="videos.length" class="control-editor">
    <div class="flex items-center justify-between gap-3"><div><strong>ControlNet 动作控制</strong><p>可选；只使用你指定的本段视频。</p></div><el-switch :model-value="modelValue.enabled" @update:model-value="set('enabled', Boolean($event))" /></div>
    <div v-if="modelValue.enabled" class="grid grid-cols-3 gap-3 mt-3"><label>控制视频<el-select :model-value="modelValue.referenceId" placeholder="请选择控制源" @update:model-value="set('referenceId', $event)"><el-option v-for="(v, i) in videos" :key="v.id" :value="v.id" :label="v.name || `Video ${i + 1}`" /></el-select></label><label>控制类型<el-select :model-value="modelValue.controlType" @update:model-value="set('controlType', $event)"><el-option v-for="t in H3_CONTROL_TYPES" :key="t.value" :value="t.value" :label="t.label" /></el-select></label><label>强度<el-input-number :model-value="modelValue.strength" :min="0" :max="2" :step="0.1" @update:model-value="set('strength', $event ?? 1)" /></label></div>
  </div>
</template>
<script setup lang="ts">
import { watch } from 'vue'
import { H3_CONTROL_TYPES } from '@/constants/h3ControlTypes'
import type { ShotRefVideo } from '@/types/drama'
export interface ControlNetConfiguration { enabled: boolean; referenceId?: string; controlType: string; strength: number }
const props = defineProps<{ modelValue: ControlNetConfiguration; videos: ShotRefVideo[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: ControlNetConfiguration] }>()
function set(key: keyof ControlNetConfiguration, value: unknown) { emit('update:modelValue', { ...props.modelValue, [key]: value }) }
watch(() => props.videos.map(v => v.id), ids => { if ((!ids.length && props.modelValue.enabled) || (props.modelValue.referenceId && !ids.includes(props.modelValue.referenceId))) emit('update:modelValue', { ...props.modelValue, enabled: ids.length ? props.modelValue.enabled : false, referenceId: undefined }) }, { immediate: true })
</script>
<style scoped>.control-editor{padding:16px;border:1px solid var(--border-default,#e5e7eb);border-radius:10px;background:var(--surface-muted,#f9fafb)}strong{font-size:13px}p,label{font-size:12px;color:var(--text-secondary)}p{margin:4px 0 0}label{display:grid;gap:6px}.el-select,.el-input-number{width:100%}@media(max-width:700px){.grid{grid-template-columns:1fr}}</style>
