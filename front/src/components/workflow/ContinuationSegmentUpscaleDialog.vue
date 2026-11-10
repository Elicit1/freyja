<template>
  <el-dialog v-model="visible" title="替换接续片段的二采结果" width="min(900px,94vw)" append-to-body destroy-on-close>
    <div v-loading="loading" class="segment-upscale">
      <p v-if="error" role="alert">{{ error }}</p>
      <template v-if="summary?.supported">
        <p>重跑所选片段的二采及已开启的后续人脸修复，保留其余片段、原始动作接续和裁切范围，重新合成为一个完整视频版本。</p>
        <el-select v-model="segmentId" aria-label="待替换片段" :disabled="submitting">
          <el-option v-for="segment in summary.segments" :key="segment.id" :value="segment.id" :label="`${segment.index}. ${segment.name}（${(segment.startFrame / 24).toFixed(2)}–${(segment.endFrame / 24).toFixed(2)} 秒）${segment.allowed ? '' : '：' + segment.reason}`" :disabled="!segment.allowed" />
        </el-select>
        <video v-if="selected" :src="selected.videoUrl" controls preload="metadata" class="segment-preview" />
        <label>重绘强度 <el-input-number v-model="options.upscaleDenoise" :min="0.05" :max="0.6" :step="0.05" :precision="2" :disabled="submitting" /></label>
        <FreyjaUpscaleOptions v-model="options" :capability="capability" :disabled="submitting" preserve-size />
        <p>直接复用归档的一采 latent。若二采出现双轮廓，可先降低重绘强度，再在显存允许时用「整段整帧采样」做对照。完整视频的输出尺寸保持一致。</p>
      </template>
      <p v-else-if="summary">{{ summary.reason }}</p>
    </div>
    <template #footer><el-button :disabled="submitting" @click="visible = false">关闭</el-button><el-button type="primary" :loading="submitting" :disabled="loading || !selected || !capability?.supported" @click="submit">重跑此片段二采并重新合成</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { videoEnhancementApi, defaultWorkflowOptions, normalizeWorkflowOptions, type ContinuationEnhancementSummary, type WorkflowCapability } from '@/api/video-enhancements'
import FreyjaUpscaleOptions from './FreyjaUpscaleOptions.vue'
const visible = ref(false), loading = ref(false), submitting = ref(false), error = ref(''), taskId = ref(''), segmentId = ref('')
const summary = ref<ContinuationEnhancementSummary>(), options = ref(defaultWorkflowOptions()), capability = ref<WorkflowCapability['freyjaUpscale']>()
const selected = computed(() => summary.value?.segments.find(segment => segment.id === segmentId.value && segment.allowed))
watch(segmentId, () => { if (selected.value) options.value = { ...normalizeWorkflowOptions(selected.value.options || summary.value?.options || {}), upscaleEngine: 'FREYJA' } })
let requestId = '', submittedBody = '', revision = 0
async function open(task: string) {
  const token = ++revision
  visible.value = true; loading.value = true; error.value = ''; summary.value = undefined; capability.value = undefined; segmentId.value = ''; taskId.value = task; requestId = ''; submittedBody = ''
  try {
    const result = await videoEnhancementApi.segments(task)
    if (token !== revision) return
    summary.value = result
    if (!result.supported) return
    options.value = { ...normalizeWorkflowOptions(result.options || {}), upscaleEngine: 'FREYJA' }
    segmentId.value = result.segments.find(segment => segment.allowed)?.id || ''
    const catalog = await videoEnhancementApi.capabilities(result.providerId!, true)
    if (token !== revision) return
    capability.value = catalog.models.find(model => model.modelCode === result.modelCode && model.supported)?.freyjaUpscale
    if (!capability.value?.nativeLatentInput) { capability.value = undefined; error.value = '当前网关未提供原始 latent 二采，请先更新网关和算力节点。' }
  } catch (e: any) { if (token === revision) error.value = e.message || '无法读取片段' }
  finally { if (token === revision) loading.value = false }
}
async function submit() {
  if (!selected.value || submitting.value) return
  const body = JSON.stringify([taskId.value, segmentId.value, options.value])
  if (body !== submittedBody) { requestId = crypto.randomUUID(); submittedBody = body }
  submitting.value = true; error.value = ''
  try {
    const result = await videoEnhancementApi.replaceSegment(taskId.value, segmentId.value, options.value, requestId)
    ElMessage.success(`已加入渲染队列：${result.taskId}。完成后在视频历史中查看新的完整版本。`)
    visible.value = false
  } catch (e: any) { error.value = e.message || '提交失败，可重试原请求' }
  finally { submitting.value = false }
}
defineExpose({ open })
</script>
<style scoped>
.segment-upscale{display:grid;gap:14px;color:var(--text-primary)}p{margin:0;font-size:12px;line-height:1.7;color:var(--text-secondary)}.segment-preview{width:100%;max-height:300px;background:#000}label{display:flex;align-items:center;gap:12px}
</style>
