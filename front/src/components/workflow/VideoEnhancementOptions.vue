<template>
  <section class="video-enhancements" aria-label="视频增强" :aria-busy="loading">
    <div class="enhancement-switches">
      <div :class="['enhancement-option', { selected: options.upscale, unavailable: !canUpscale }]"><span class="enhancement-icon"><el-icon><FullScreen /></el-icon></span><div class="enhancement-copy"><strong>二采放大</strong><p>{{ capability?.supported && !canUpscale ? '当前视频模型未提供二采放大' : '提升分辨率，丰富画面细节' }}</p></div><el-switch :model-value="options.upscale" aria-label="二采放大" :disabled="disabled || !canUpscale" @update:model-value="patch('upscale', !!$event)" /></div>
      <div :class="['enhancement-option', { selected: options.faceRepair, unavailable: !canRepairFace }]"><span class="enhancement-icon"><el-icon><View /></el-icon></span><div class="enhancement-copy"><strong>人脸修复</strong><p>{{ capability?.supported && !canRepairFace ? '当前基础模型不支持修脸' : '跟踪面部，精修并自然融合' }}</p></div><el-switch :model-value="options.faceRepair" aria-label="人脸修复" :disabled="disabled || !canRepairFace" @update:model-value="patch('faceRepair', !!$event)" /></div>
    </div>
    <p v-if="!providerId || !modelId || !modelCode">选择视频模型后可开启增强。</p>
    <p v-else-if="loading" role="status">正在读取视频增强能力…</p>
    <p v-else-if="error" role="alert">{{ error }}。可直接进行基础渲染。<el-button link type="primary" :disabled="disabled" @click="loadCapabilities(true)">重试</el-button></p>
    <p v-else-if="loaded && !capability">网关未登记模型 {{ modelCode }}，请核对模型编码及网关配置。<el-button link type="primary" :disabled="disabled" @click="loadCapabilities(true)">刷新能力</el-button></p>
    <p v-else-if="loaded && !capability?.supported">当前模型未提供视频增强能力，请选择支持增强的 H3 视频模型。<el-button link type="primary" :disabled="disabled" @click="loadCapabilities(true)">刷新能力</el-button></p>
    <template v-if="options.upscale || options.faceRepair">
      <el-collapse v-model="openPanels" class="enhancement-advanced"><el-collapse-item name="parameters"><template #title><span class="advanced-heading"><el-icon><Setting /></el-icon>{{ options.upscale ? '二采配置' : '人脸修复参数' }}<span v-if="options.upscale">方式、输出尺寸、步数与显存</span></span></template>
        <div class="enhancement-parameters">
          <label v-if="options.upscale">放大重绘强度<el-input-number :model-value="options.upscaleDenoise" :min="0.05" :max="0.6" :step="0.05" :precision="2" :disabled="disabled" @update:model-value="patch('upscaleDenoise', $event ?? .25)" /></label>
          <label v-if="options.faceRepair">人脸重绘强度<el-input-number :model-value="options.faceDenoise" :min="0.05" :max="0.6" :step="0.05" :precision="2" :disabled="disabled" @update:model-value="patch('faceDenoise', $event ?? .4)" /></label>
        </div>
        <FreyjaUpscaleOptions v-if="options.upscale" :model-value="options" :capability="capability?.freyjaUpscale" :disabled="disabled" @update:model-value="update" />
      </el-collapse-item></el-collapse>
      <p class="enhancement-result"><el-icon><CircleCheck /></el-icon>输出 {{ outputSize }}<span>先生成，再增强；人脸修复在放大后执行。</span></p>
    </template>
  </section>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { CircleCheck, FullScreen, Setting, View } from '@element-plus/icons-vue'
import { videoEnhancementApi, defaultWorkflowOptions, normalizeWorkflowOptions, type WorkflowOptions, type WorkflowSelection, type WorkflowCapability } from '@/api/video-enhancements'
import FreyjaUpscaleOptions from './FreyjaUpscaleOptions.vue'
const props = defineProps<{ modelValue?: WorkflowSelection; providerId?: string; modelId?: string; modelCode?: string; size?: string; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: WorkflowSelection | undefined] }>()
const capabilities = ref<WorkflowCapability[]>([]), loading = ref(false), loaded = ref(false), error = ref('')
let revision = 0
const capability = computed(() => capabilities.value.find(c => c.modelCode === props.modelCode && c.supported) || capabilities.value.find(c => c.modelCode === props.modelCode))
const canUpscale = computed(() => !loading.value && loaded.value && !!props.modelId && !!capability.value?.supported && capability.value.stages.includes('UPSCALE'))
const canRepairFace = computed(() => !loading.value && loaded.value && !!props.modelId && !!capability.value?.supported && capability.value.stages.includes('FACE'))
const options = computed(() => props.modelValue?.options ? normalizeWorkflowOptions(props.modelValue.options) : defaultWorkflowOptions())
const openPanels = ref<string[]>([])
watch(() => options.value.upscale, enabled => {
  openPanels.value = enabled ? ['parameters'] : []
}, { immediate: true })
const outputSize = computed(() => { const [w,h] = (props.size || '').split('x').map(Number); if (options.value.upscale && options.value.upscaleOutput === 'FULL_HD') return w && h && w < h ? '1080 × 1920' : '1920 × 1080'; return w && h ? `${w * (options.value.upscale ? 2 : 1)} × ${h * (options.value.upscale ? 2 : 1)}` : '原尺寸' })
watch(() => [props.providerId, props.modelId, props.modelCode], () => {
  if (props.modelValue) emit('update:modelValue', undefined)
})
async function loadCapabilities(refresh = false) {
  const token = ++revision; capabilities.value = []; error.value = ''; loading.value = false; loaded.value = false
  if (!props.providerId) return
  loading.value = true
  try { const result = await videoEnhancementApi.capabilities(props.providerId, refresh); if (token === revision) { capabilities.value = result.models; loaded.value = true } }
  catch (e: any) { if (token === revision) error.value = e.code === 'ECONNABORTED' ? '读取视频增强能力超时，请检查网关连接' : e.message || '无法读取视频增强能力' }
  finally { if (token === revision) loading.value = false }
}
watch(() => props.providerId, () => loadCapabilities(), { immediate: true })
function update(value: WorkflowOptions) { if (!props.disabled) emit('update:modelValue', value.upscale || value.faceRepair ? { options: value } : undefined) }
function patch<K extends keyof WorkflowOptions>(key: K, value: WorkflowOptions[K]) { update({ ...options.value, [key]: value }) }
onBeforeUnmount(() => { revision++ })
</script>
<style scoped>
.video-enhancements { display: grid; gap: 12px; color: var(--text-primary); container-type: inline-size; }
.enhancement-switches { display: grid; grid-template-columns: 1fr; gap: 10px; }
.enhancement-option { display: flex; align-items: center; gap: 12px; min-width: 0; padding: 14px; border: 1px solid var(--border-default); border-radius: 9px; background: var(--surface); transition: border-color .15s, background .15s; }
.enhancement-option.selected { border-color: color-mix(in srgb, var(--brand) 40%, var(--border-default)); background: var(--brand-soft); }
.enhancement-icon { display: grid; place-items: center; width: 32px; height: 32px; flex-shrink: 0; border: 1px solid var(--border-default); border-radius: 8px; background: var(--surface-muted); color: var(--text-secondary); font-size: 16px; }
.selected .enhancement-icon { border-color: transparent; background: var(--surface); color: var(--brand); }
.enhancement-copy { flex: 1; min-width: 0; }
.enhancement-option strong { font-size: 12px; font-weight: 550; }
.enhancement-option p { margin-top: 5px; font-size: 10px; line-height: 1.5; }
.enhancement-option .el-switch { flex-shrink: 0; --el-switch-height: 18px; --el-switch-on-color: var(--brand); }
.unavailable .enhancement-icon { opacity: .65; }
p { margin: 0; font-size: 11px; color: var(--text-secondary); line-height: 1.7; overflow-wrap: anywhere; }
.video-enhancements > p { color: var(--text-muted); font-size: 10px; }
.video-enhancements > p[role="alert"] { color: var(--danger); }
.enhancement-parameters { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin: 4px 0 16px; }
.enhancement-parameters label { display: grid; gap: 8px; min-width: 0; font-size: 11px; color: var(--text-secondary); }
.enhancement-parameters .el-input-number { width: 100%; }
.enhancement-advanced { --el-collapse-border-color: var(--border-default); border-bottom: 0; }
.enhancement-advanced :deep(.el-collapse-item__header), .enhancement-advanced :deep(.el-collapse-item__wrap) { background: transparent; }
.enhancement-advanced :deep(.el-collapse-item__header) { height: 42px; border-bottom: 0; }
.enhancement-advanced :deep(.el-collapse-item__content) { padding-bottom: 0; }
.advanced-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; font-size: 11px; color: var(--text-secondary); }
.advanced-heading > span { margin-left: 3px; font-size: 9px; color: var(--text-muted); }
.enhancement-result { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; font-variant-numeric: tabular-nums; }
.enhancement-result .el-icon { color: var(--success); }
.enhancement-result > span { width: 100%; padding-left: 17px; font-size: 10px; }
@container(min-width: 640px) { .enhancement-switches { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@container(max-width: 260px) { .enhancement-parameters { grid-template-columns: 1fr; }.enhancement-option { gap: 8px; padding: 12px 10px; }.enhancement-icon { display: none; } }
</style>
