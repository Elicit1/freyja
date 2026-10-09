<template>
  <div class="studio-card p-4 space-y-3 border-indigo-200">
    <button
      type="button"
      class="flex w-full items-center justify-between gap-2 text-left"
      :aria-expanded="expanded"
      :aria-controls="contentId"
      @click="expanded = !expanded"
    >
      <strong>🎬 结构化导演决策计划</strong>
      <span class="flex items-center gap-2">
        <el-tag v-if="dirty" size="small" type="warning">有未保存修改</el-tag>
        <el-tag v-if="inputsChanged" size="small" type="warning">需要重新规划</el-tag>
        <el-tag>{{ mode === 'MANUAL' ? '手工计划' : 'AI 自动规划' }} · v{{ revision }}</el-tag>
        <span class="text-xs text-slate-500">{{ expanded ? '收起' : '展开' }}</span>
        <el-icon class="transition-transform" :class="{ 'rotate-180': expanded }"><ArrowDown /></el-icon>
      </span>
    </button>
    <div :id="contentId" v-show="expanded" class="space-y-3">
      <p class="text-xs text-slate-500">未编辑时按原流程自动规划；保存后，提示词 AI 将使用你的完整计划。生成时自动保存当前修改。</p>
      <div class="flex gap-2">
        <el-button v-if="!editing" size="small" @click="begin">{{ plan ? '编辑计划' : '创建手工计划' }}</el-button>
        <template v-else>
          <el-button size="small" type="primary" :loading="saving" @click="saveClick">保存计划</el-button>
          <el-button size="small" :disabled="saving" @click="cancel">取消</el-button>
        </template>
        <el-button v-if="mode === 'MANUAL'" size="small" :disabled="saving" @click="reset">恢复 AI 自动规划</el-button>
      </div>
      <template v-if="editing && draft">
        <div class="grid grid-cols-2 gap-3">
          <label>总时长（分镜时长）<el-input :model-value="duration" disabled /></label>
          <label>协议版本<el-input :model-value="draft.schemaVersion" disabled /></label>
          <label>景别<el-select v-model="draft.shotSize"><el-option v-for="v in sizes" :key="v" :value="v" :label="v" /></el-select></label>
          <label>机位角度<el-select v-model="draft.cameraAngle"><el-option v-for="v in angles" :key="v" :value="v" :label="v" /></el-select></label>
        </div>
        <label class="block">叙事意图<el-input v-model="draft.narrativeIntent" type="textarea" /></label>
        <label class="block">主体动作<el-input v-model="draft.subjectAction" type="textarea" /></label>
        <label class="block">视线与焦点<el-input v-model="draft.gaze" type="textarea" /></label>
        <div v-for="(beat, i) in draft.cameraBeats" :key="i" class="border rounded p-3 space-y-2">
          <div class="flex justify-between"><strong>节拍 {{ i + 1 }}</strong><el-button size="small" type="danger" plain @click="draft.cameraBeats?.splice(i, 1)">删除</el-button></div>
          <div class="grid grid-cols-2 gap-2">
            <label>起始秒<el-input-number v-model="beat.startSec" :min="0" :precision="3" /></label>
            <label>结束秒<el-input-number v-model="beat.endSec" :min="0" :precision="3" /></label>
            <label>运镜<el-select v-model="beat.movement" @change="movementChanged(beat)"><el-option v-for="v in movements" :key="v" :value="v" :label="v" /></el-select></label>
            <label>速度<el-select v-model="beat.speed"><el-option v-for="v in speeds" :key="v" :value="v" :label="v" /></el-select></label>
          </div>
          <label class="block">方向<el-input v-model="beat.direction" :disabled="beat.movement === 'STATIC'" /></label>
          <label class="block">起始节点<el-input v-model="beat.startCue" /></label>
          <label class="block">收束节点<el-input v-model="beat.stopCue" /></label>
          <label class="block">戏剧目的<el-input v-model="beat.narrativePurpose" /></label>
        </div>
        <el-button size="small" @click="addBeat">添加节拍</el-button>
        <p class="text-xs text-slate-500">节拍须从 0 连续覆盖 {{ duration }} 秒；10 秒内最多 3 段，否则最多 5 段。锁定字段：{{ draft.lockedFields?.join(', ') || '无' }}</p>
        <p class="text-xs text-slate-500">技能记录（只读）：{{ draft.skills?.map(s => s.name + '@' + s.version).join(', ') || '无' }}</p>
      </template>
      <template v-else-if="plan">
        <p class="text-sm">{{ plan.shotSize }} · {{ plan.cameraAngle }} · {{ plan.duration }} 秒</p>
        <p class="text-sm">{{ plan.narrativeIntent }}</p>
        <p class="text-sm">{{ plan.subjectAction }} {{ plan.gaze }}</p>
        <div v-for="(b, i) in plan.cameraBeats" :key="i" class="text-xs text-slate-600">{{ b.startSec }}–{{ b.endSec }}s · {{ b.movement }} · {{ b.direction }} · {{ b.speed }} · {{ b.startCue }} → {{ b.stopCue }} · {{ b.narrativePurpose }}</div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { shotApi } from '@/api/drama'
import type { DirectorPlan, CameraBeat } from '@/types/drama'
const props = defineProps<{ shotId?: string | number; plan?: DirectorPlan | null; mode?: 'AUTO' | 'MANUAL'; revision?: number; duration?: number; shotType?: string; cameraMovement?: string; shotTypeLocked?: boolean; cameraMovementLocked?: boolean; inputsChanged?: boolean; inputHash?: string }>()
const emit = defineEmits<{ saved: [data: { directorPlan?: DirectorPlan; directorPlanMode: 'AUTO' | 'MANUAL'; directorPlanRevision: number; directorPlanInputHash?: string }]; dirty: [value: boolean] }>()
const contentId = useId()
const expanded = ref(false)
const editing = ref(false)
const saving = ref(false)
const draft = ref<DirectorPlan>()
const baseline = ref('')
const editingRevision = ref(0)
const mode = computed(() => props.mode || 'AUTO')
const revision = computed(() => props.revision ?? 0)
const sizes = ['EXTREME_CLOSE_UP', 'CLOSE_UP', 'MEDIUM_CLOSE_UP', 'MEDIUM_SHOT', 'FULL_SHOT', 'LONG_SHOT', 'OVER_SHOULDER', 'TOP_DOWN']
const angles = ['EYE_LEVEL', 'LOW_ANGLE', 'HIGH_ANGLE', 'DUTCH_ANGLE', 'OVER_THE_SHOULDER', 'BIRDS_EYE']
const movements = ['STATIC', 'PUSH_IN', 'PULL_OUT', 'PAN_LEFT', 'PAN_RIGHT', 'TILT_UP', 'TILT_DOWN', 'TRACKING', 'ORBIT', 'ZOOM_IN']
const speeds = ['NONE', 'SLOW', 'NORMAL', 'FAST']
const clone = <T,>(v: T): T => JSON.parse(JSON.stringify(v))
const dirty = computed(() => editing.value && JSON.stringify(draft.value) !== baseline.value)
watch(dirty, v => emit('dirty', v))
watch(() => props.duration, v => { if (editing.value && draft.value) draft.value.duration = v })
function begin() {
  editingRevision.value = revision.value
  draft.value = props.plan ? clone(props.plan) : {
    schemaVersion: '1.0', duration: props.duration, shotSize: props.shotTypeLocked ? props.shotType : 'MEDIUM_SHOT', cameraAngle: 'EYE_LEVEL',
    cameraBeats: [{ startSec: 0, endSec: props.duration || 0, movement: props.cameraMovementLocked ? props.cameraMovement || 'STATIC' : 'STATIC', speed: props.cameraMovementLocked && props.cameraMovement !== 'STATIC' ? 'SLOW' : 'NONE' }],
    lockedFields: ['duration'], skills: []
  }
  baseline.value = props.plan ? JSON.stringify(draft.value) : ''
  draft.value.duration = props.duration
  editing.value = true
}
function cancel() { editing.value = false; draft.value = undefined }
function movementChanged(beat: CameraBeat) { if (beat.movement === 'STATIC') { beat.direction = undefined; beat.speed = 'NONE' } else if (beat.speed === 'NONE') beat.speed = 'SLOW' }
function addBeat() { draft.value?.cameraBeats?.push({ startSec: draft.value.cameraBeats.at(-1)?.endSec || 0, endSec: props.duration || 0, movement: 'STATIC', speed: 'NONE' }) }
async function persist(nextMode: 'AUTO' | 'MANUAL', plan?: DirectorPlan) {
  saving.value = true
  try {
    const data = { directorPlanMode: nextMode, directorPlanRevision: nextMode === 'MANUAL' ? editingRevision.value : revision.value, directorPlan: plan, duration: props.duration,
      directorPlanInputHash: props.inputHash,
      shotType: props.shotType, cameraMovement: props.cameraMovement, shotTypeLocked: props.shotTypeLocked, cameraMovementLocked: props.cameraMovementLocked }
    const saved = props.shotId ? await shotApi.saveDirectorPlan(props.shotId, data) : data
    emit('saved', saved)
    cancel()
  } finally { saving.value = false }
}
async function submit() { if (saving.value) throw new Error('正在保存导演计划，请稍候'); if (dirty.value) await persist('MANUAL', clone(draft.value!)); else cancel() }
async function saveClick() { try { await submit(); ElMessage.success('导演计划已保存') } catch (e: any) { ElMessage.error(e.message || '保存失败') } }
async function reset() { try { await persist('AUTO'); ElMessage.success('已恢复 AI 自动规划') } catch (e: any) { ElMessage.error(e.message || '恢复失败') } }
function getDraft() { return dirty.value ? { plan: clone(draft.value!), revision: editingRevision.value } : undefined }
function discardDraftIfUnchanged(plan?: DirectorPlan) {
  if (plan && editing.value && JSON.stringify(draft.value) === JSON.stringify(plan)) cancel()
}
defineExpose({ submit, getDraft, discardDraftIfUnchanged })
</script>
