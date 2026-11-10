<template>
  <section class="studio-card workflow-selector" v-loading="loading">
    <div class="workflow-heading"><strong>动态工作流</strong><router-link to="/production/dynamic-workflows">管理预设</router-link></div>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-else-if="!providerId || !modelId">选择基础模型后，可追加二采放大和人脸修复。</p>
    <p v-else-if="!capability?.supported">当前模型未提供动态工作流能力。</p>
    <template v-else>
      <el-select :model-value="selected" :disabled="disabled" aria-label="动态工作流预设" @update:model-value="select">
        <el-option value="" label="基础生成，不追加增强" />
        <el-option value="custom" label="本次自定义" />
        <el-option v-for="preset in presets.filter(p => p.status === 1)" :key="String(preset.id)" :value="String(preset.id)" :label="`${preset.name} · v${preset.version}`" />
      </el-select>
      <template v-if="modelValue">
        <div class="workflow-switches">
          <div class="workflow-option"><el-checkbox :model-value="options.upscale" :disabled="disabled" @update:model-value="patch('upscale', !!$event)">二采放大</el-checkbox><span>对全画面重新采样，增加输出尺寸。</span></div>
          <div class="workflow-option"><el-checkbox :model-value="options.faceRepair" :disabled="disabled || !capability.stages.includes('FACE')" @update:model-value="patch('faceRepair', !!$event)">人脸修复</el-checkbox><span>{{ capability.stages.includes('FACE') ? '跟踪人物面部，精修后融合回画面。' : '此模块需要参考生成基础模型。' }}</span></div>
        </div>
        <div v-if="options.upscale || options.faceRepair" class="workflow-parameters">
          <label v-if="options.upscale">放大重绘强度<el-input-number :model-value="options.upscaleDenoise" :min="0.05" :max="0.6" :step="0.05" :precision="2" :disabled="disabled" @update:model-value="patch('upscaleDenoise', $event ?? .25)" /></label>
          <label v-if="options.faceRepair">人脸重绘强度<el-input-number :model-value="options.faceDenoise" :min="0.05" :max="0.6" :step="0.05" :precision="2" :disabled="disabled" @update:model-value="patch('faceDenoise', $event ?? .4)" /></label>
        </div>
        <div class="workflow-path" aria-label="本次处理顺序"><span>基础生成</span><span v-if="options.upscale">→ 二采放大</span><span v-if="options.faceRepair">→ 人脸修复</span><span>→ 输出 {{ outputSize }}</span></div>
        <FreyjaUpscaleOptions v-if="options.upscale" :model-value="options" :capability="capability.freyjaUpscale" :disabled="disabled" @update:model-value="emit('update:modelValue', { ...modelValue, options: $event })" />
        <el-checkbox :model-value="!!modelValue.reusePrevious" :disabled="disabled" @update:model-value="emit('update:modelValue', { ...modelValue, reusePrevious: !!$event })">复用本镜头上一任务中输入相同的已完成步骤</el-checkbox>
        <p>增强设置应用于全部接续片段。开启增强会增加处理时间；接续使用基础生成结果。</p>
      </template>
    </template>
  </section>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { dynamicWorkflowApi, defaultWorkflowOptions, normalizeWorkflowOptions, type WorkflowOptions, type WorkflowSelection, type WorkflowPreset, type WorkflowCapability } from '@/api/dynamic-workflow'
import FreyjaUpscaleOptions from './FreyjaUpscaleOptions.vue'
const props = defineProps<{ modelValue?: WorkflowSelection; providerId?: string; modelId?: string; modelCode?: string; size?: string; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: WorkflowSelection | undefined] }>()
const presets = ref<WorkflowPreset[]>([]), capabilities = ref<WorkflowCapability[]>([]), loading = ref(false), error = ref('')
let revision = 0
const capability = computed(() => capabilities.value.find(c => c.modelCode === props.modelCode && c.supported))
const options = computed(() => props.modelValue?.options ? normalizeWorkflowOptions(props.modelValue.options) : defaultWorkflowOptions())
const selected = computed(() => props.modelValue ? props.modelValue.presetId || 'custom' : '')
const outputSize = computed(() => { const [w, h] = (props.size || '').split('x').map(Number); if (options.value.upscale && options.value.upscaleOutput === 'FULL_HD') return w && h && w < h ? '1080 × 1920' : '1920 × 1080'; return w && h ? `${w * (options.value.upscale ? 2 : 1)} × ${h * (options.value.upscale ? 2 : 1)}` : '原尺寸' })
watch(() => [props.providerId, props.modelId], async () => {
  const token = ++revision; presets.value = []; capabilities.value = []; error.value = ''; emit('update:modelValue', undefined)
  if (!props.providerId || !props.modelId) return
  loading.value = true
  try { const [list, caps] = await Promise.all([dynamicWorkflowApi.list(props.providerId, props.modelId), dynamicWorkflowApi.capabilities(props.providerId)]); if (token === revision) { presets.value = list; capabilities.value = caps.models } }
  catch (e: any) { if (token === revision) error.value = e.message || '无法读取动态工作流' }
  finally { if (token === revision) loading.value = false }
}, { immediate: true })
function select(value: string) {
  if (props.disabled) return
  if (!value) return emit('update:modelValue', undefined)
  const preset = presets.value.find(p => String(p.id) === value)
  emit('update:modelValue', preset ? { presetId: String(preset.id), version: preset.version, options: normalizeWorkflowOptions(JSON.parse(preset.optionsJson)) } : { options: defaultWorkflowOptions() })
}
function patch<K extends keyof WorkflowOptions>(key: K, value: WorkflowOptions[K]) { emit('update:modelValue', { ...props.modelValue, options: { ...options.value, [key]: value } }) }
</script>
<style scoped>
.workflow-selector{padding:16px;display:grid;gap:12px;color:var(--text-primary)}.workflow-heading{display:flex;justify-content:space-between;gap:12px}.workflow-heading a{font-size:12px;color:var(--brand)}p,.workflow-switches span{margin:0;font-size:12px;color:var(--text-secondary);line-height:1.7}.workflow-switches,.workflow-parameters{display:grid;grid-template-columns:1fr 1fr;gap:16px}.workflow-option,.workflow-parameters label{display:flex;flex-direction:column;gap:6px}.workflow-parameters label{font-size:12px}.workflow-path{display:flex;flex-wrap:wrap;gap:8px;padding:12px;background:var(--surface-muted);border-radius:var(--radius-sm);font-size:12px}@media(max-width:600px){.workflow-switches,.workflow-parameters{grid-template-columns:1fr}}
.workflow-selector :deep(.el-checkbox){height:auto;white-space:normal;align-items:flex-start}.workflow-selector :deep(.el-checkbox__label){white-space:normal;line-height:1.6}.workflow-selector :deep(.el-checkbox__input){margin-top:4px}
</style>
