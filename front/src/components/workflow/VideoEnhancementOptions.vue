<template>
  <section class="video-enhancements" aria-label="视频增强" :aria-busy="loading">
    <div class="enhancement-switches">
      <div class="enhancement-option"><div><strong>二采放大</strong><el-switch :model-value="options.upscale" aria-label="二采放大" :disabled="disabled || !canUpscale" @update:model-value="patch('upscale', !!$event)" /></div><p>基础视频生成后，放大并进行第二次采样。</p></div>
      <div class="enhancement-option"><div><strong>人脸修复</strong><el-switch :model-value="options.faceRepair" aria-label="人脸修复" :disabled="disabled || !canRepairFace" @update:model-value="patch('faceRepair', !!$event)" /></div><p>{{ capability?.supported && !canRepairFace ? '当前基础模型不支持人脸修复。' : '跟踪面部，精修后融合回画面。' }}</p></div>
    </div>
    <p v-if="!providerId || !modelId || !modelCode">选择视频模型后可开启增强。</p>
    <p v-else-if="loading" role="status">正在读取视频增强能力…</p>
    <p v-else-if="error" role="alert">{{ error }}。可直接进行基础渲染。<el-button link type="primary" :disabled="disabled" @click="loadCapabilities(true)">重试</el-button></p>
    <p v-else-if="loaded && !capability">网关未登记模型 {{ modelCode }}，请核对模型编码及网关配置。<el-button link type="primary" :disabled="disabled" @click="loadCapabilities(true)">刷新能力</el-button></p>
    <p v-else-if="loaded && !capability?.supported">当前模型未提供二采放大和人脸修复能力，请选择基础 REF2VA 模型。<el-button link type="primary" :disabled="disabled" @click="loadCapabilities(true)">刷新能力</el-button></p>
    <template v-if="options.upscale || options.faceRepair">
      <div class="enhancement-path" aria-label="本次处理顺序"><span>基础生成</span><span v-if="options.upscale">→ 二采放大</span><span v-if="options.faceRepair">→ 人脸修复</span><span>→ 输出 {{ outputSize }}</span></div>
      <el-collapse><el-collapse-item title="增强参数" name="parameters">
        <div class="enhancement-parameters">
          <label v-if="options.upscale">放大重绘强度<el-input-number :model-value="options.upscaleDenoise" :min="0.05" :max="0.6" :step="0.05" :precision="2" :disabled="disabled" @update:model-value="patch('upscaleDenoise', $event ?? .25)" /></label>
          <label v-if="options.faceRepair">人脸重绘强度<el-input-number :model-value="options.faceDenoise" :min="0.05" :max="0.6" :step="0.05" :precision="2" :disabled="disabled" @update:model-value="patch('faceDenoise', $event ?? .4)" /></label>
        </div>
        <FreyjaUpscaleOptions v-if="options.upscale" :model-value="options" :capability="capability?.freyjaUpscale" :disabled="disabled" @update:model-value="update" />
      </el-collapse-item></el-collapse>
      <p>提交后按开关自动追加处理。接续时，每段分别增强，下一段仍使用基础视频和原始 latent。</p>
    </template>
  </section>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
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
.video-enhancements{display:grid;gap:12px;color:var(--text-primary)}.enhancement-switches,.enhancement-parameters{display:grid;grid-template-columns:1fr 1fr;gap:16px}.enhancement-option>div{display:flex;align-items:center;justify-content:space-between;gap:12px}.enhancement-option strong{font-size:13px}p{margin:0;font-size:12px;color:var(--text-secondary);line-height:1.7}.enhancement-parameters{margin-bottom:12px}.enhancement-parameters label{display:grid;gap:6px;font-size:12px}.enhancement-path{display:flex;flex-wrap:wrap;gap:8px;font-size:12px;color:var(--text-secondary)}@media(max-width:600px){.enhancement-switches,.enhancement-parameters{grid-template-columns:1fr}}
</style>
