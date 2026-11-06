<template>
  <div class="director-local">
    <div class="flex items-center justify-between"><strong>本段导演规划</strong><el-button v-if="!modelValue" size="small" @click="create">创建手工计划</el-button><el-button v-else size="small" text type="danger" @click="emit('update:modelValue', undefined)">移除手工计划</el-button></div>
    <p v-if="!modelValue">可手工编辑本段运镜节拍，或通过 AI 自动规划生成。没有计划时不替你猜测导演决策。</p>
    <template v-else>
      <div class="grid grid-cols-2 gap-3 mt-3"><label>景别<el-select v-model="modelValue.shotSize"><el-option v-for="v in directorPlanOptions.shotSize" :key="v.value" :value="v.value" :label="v.label" /></el-select></label><label>机位角度<el-select v-model="modelValue.cameraAngle"><el-option v-for="v in directorPlanOptions.cameraAngle" :key="v.value" :value="v.value" :label="v.label" /></el-select></label></div>
      <label>叙事意图<el-input v-model="modelValue.narrativeIntent" type="textarea" :rows="2" /></label><label>主体动作<el-input v-model="modelValue.subjectAction" type="textarea" :rows="2" /></label><label>视线与焦点<el-input v-model="modelValue.gaze" /></label>
      <div class="flex items-center justify-between text-xs"><span>节拍须连续覆盖 0～{{ duration }} 秒</span><el-button size="small" @click="resetBeats">按本段时长重置节拍</el-button></div>
      <div v-for="(beat, i) in modelValue.cameraBeats" :key="i" class="beat"><div class="flex justify-between"><strong>Camera Beat {{ i + 1 }}</strong><el-button size="small" text type="danger" @click="modelValue.cameraBeats?.splice(i, 1)">移除</el-button></div><div class="grid grid-cols-2 gap-3"><label>起始秒<el-input-number v-model="beat.startSec" :min="0" :precision="3" /></label><label>结束秒<el-input-number v-model="beat.endSec" :min="0" :precision="3" /></label><label>运镜<el-select v-model="beat.movement" @change="() => { beat.speed = beat.movement === 'STATIC' ? 'NONE' : 'NORMAL'; if (beat.movement === 'STATIC') beat.direction = undefined }"><el-option v-for="v in directorPlanOptions.movement" :key="v.value" :value="v.value" :label="v.label" /></el-select></label><label>速度<el-select v-model="beat.speed"><el-option v-for="v in directorPlanOptions.speed" :key="v.value" :value="v.value" :label="v.label" /></el-select></label></div><label>方向<el-input v-model="beat.direction" :disabled="beat.movement === 'STATIC'" /></label><div class="grid grid-cols-2 gap-3"><label>开始节点<el-input v-model="beat.startCue" /></label><label>结束节点<el-input v-model="beat.stopCue" /></label></div><label>运镜目的<el-input v-model="beat.narrativePurpose" /></label></div>
      <el-button size="small" @click="modelValue.cameraBeats?.push({ startSec: modelValue.cameraBeats.at(-1)?.endSec || 0, endSec: duration, movement: 'STATIC', speed: 'NONE' })">添加节拍</el-button>
    </template>
  </div>
</template>
<script setup lang="ts">
import type { DirectorPlan } from '@/types/drama'
import { directorPlanOptions } from '@/utils/director-plan-labels'
const props = defineProps<{ modelValue?: DirectorPlan; duration: number }>()
const emit = defineEmits<{ 'update:modelValue': [value: DirectorPlan | undefined] }>()
function create() { emit('update:modelValue', { schemaVersion: '1.0', duration: props.duration, shotSize: 'MEDIUM_SHOT', cameraAngle: 'EYE_LEVEL', cameraBeats: [{ startSec: 0, endSec: props.duration, movement: 'STATIC', speed: 'NONE' }], lockedFields: ['duration'], skills: [] }) }
function resetBeats() { if (props.modelValue) { props.modelValue.duration = props.duration; props.modelValue.cameraBeats = [{ startSec: 0, endSec: props.duration, movement: 'STATIC', speed: 'NONE' }] } }
</script>
<style scoped>.director-local{border:1px solid var(--border-default,#e5e7eb);border-radius:10px;padding:16px}label{display:grid;gap:6px;font-size:12px;margin:12px 0;color:var(--text-secondary)}p{font-size:12px;color:var(--text-muted);margin:12px 0 0}.beat{margin:12px 0;padding:12px;background:var(--surface-muted,#fafafa);border-radius:8px}.el-select,.el-input-number{width:100%}</style>
