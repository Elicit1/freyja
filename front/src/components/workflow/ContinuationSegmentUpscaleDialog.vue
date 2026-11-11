<template>
  <el-dialog v-model="visible" title="重跑接续片段" width="min(1120px,94vw)" top="5vh" append-to-body destroy-on-close :close-on-click-modal="!submitting" :close-on-press-escape="!submitting" :show-close="!submitting">
    <div v-loading="loading" class="segment-upscale">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-alert v-if="submittedTask" :title="`已提交 ${selected.length} 个片段的重跑`" :description="`完成后在视频历史中查看新的完整版本。任务：${submittedTask}`" type="success" :closable="false" show-icon />
      <template v-if="summary?.supported">
        <p>选择需要重新处理的片段，完成后合成为一个新版本。其余片段沿用当前结果。</p>
        <el-radio-group v-model="rerunMode" :disabled="locked" aria-label="重跑范围"><el-radio-button value="BASE_ONLY">仅一采</el-radio-button><el-radio-button value="UPSCALE_ONLY">仅二采</el-radio-button><el-radio-button value="BASE_AND_UPSCALE">一采和二采</el-radio-button></el-radio-group>
        <p>{{ rerunMode === 'UPSCALE_ONLY' ? '复用原始一采 latent，重新二采后按原设置修脸。' : rerunMode === 'BASE_ONLY' ? '使用新种子重新一采，跳过二采，按原设置修脸，合成时对齐原输出尺寸。' : '使用新种子重新一采，再执行所选二采方式，最后按原设置修脸。' }} 未选片段保持原结果；若接续处动作变化，建议连同相邻片段重跑。</p>
        <div class="selection-workspace">
          <section class="segment-picker" aria-label="选择重跑片段">
            <div class="section-heading"><h3>选择片段 <span>{{ selected.length }} / {{ summary.segments.length }}</span></h3><div class="selection-actions">
              <el-checkbox :model-value="allSelected" :indeterminate="partlySelected" :disabled="locked || !selectable.length" @update:model-value="toggleAll">全选可用片段</el-checkbox>
              <el-button link :disabled="locked || !selected.length" @click="selectedIds = []">清空</el-button>
            </div></div>
            <el-checkbox-group v-model="selectedIds" :disabled="locked" aria-label="待重跑片段" class="segment-grid">
              <article v-for="segment in summary.segments" :key="segment.id" class="segment-card" :class="{ 'is-selected': selectedIds.includes(segment.id), 'is-unavailable': !isAllowed(segment) }">
                <el-checkbox :value="segment.id" :disabled="!isAllowed(segment)" class="segment-checkbox"><strong>片段 {{ segment.index }}</strong><span class="segment-name">{{ segment.name }}</span></el-checkbox>
                <div class="clip-poster">
                  <video :src="segment.videoUrl" muted playsinline preload="metadata" aria-hidden="true" />
                  <span class="clip-state">{{ !isAllowed(segment) ? '不可重跑' : selectedIds.includes(segment.id) ? '本次重跑' : '沿用当前结果' }}</span>
                  <el-button size="small" class="preview-button" :aria-label="`预览片段 ${segment.index}`" @click="previewId = segment.id">预览</el-button>
                </div>
                <p class="clip-range">保留范围 {{ time(segment.startFrame) }}–{{ time(segment.endFrame) }}</p>
                <p v-if="!isAllowed(segment)" class="unavailable-reason">{{ segment.reason }}</p>
              </article>
            </el-checkbox-group>
          </section>
          <section class="selection-review" aria-label="已选片段与预览">
            <div class="section-heading"><h3>{{ submittedTask ? '已提交重跑' : '本次重跑' }} <span>{{ selected.length }} 个片段</span></h3><small>按原时间顺序合成</small></div>
            <ol v-if="selected.length" class="selected-list" aria-label="已选择的重跑片段" aria-live="polite">
              <li v-for="segment in selected" :key="segment.id" :class="{ 'is-previewing': previewId === segment.id }">
                <button type="button" class="selected-preview" @click="previewId = segment.id"><strong>片段 {{ segment.index }} · {{ segment.name }}</strong><small>{{ time(segment.startFrame) }}–{{ time(segment.endFrame) }}</small></button>
                <el-button link :disabled="locked" :aria-label="`移除片段 ${segment.index}`" @click="removeSegment(segment.id)">移除</el-button>
              </li>
            </ol>
            <div v-else class="selection-empty">还没有选择片段<br><small>勾选左侧片段，或全选可用片段。</small></div>
            <div v-if="previewSegment" class="preview-panel">
              <div class="preview-heading"><strong>片段 {{ previewSegment.index }} · {{ previewSegment.name }}</strong><span>原片段预览</span></div>
              <video :key="previewSegment.id" :src="previewSegment.videoUrl" controls playsinline preload="metadata" class="segment-preview" />
              <p>这里展示当前版本。新的结果将在重跑完成后保存到视频历史。</p>
            </div>
          </section>
        </div>
        <section v-if="rerunMode !== 'BASE_ONLY'" class="parameter-settings" aria-label="本次二采参数">
          <div class="section-heading"><h3>二采参数</h3><small>统一应用于选中的 {{ selected.length }} 个片段</small></div>
          <div class="denoise-row"><span>重绘强度</span><el-input-number v-model="options.upscaleDenoise" aria-label="重绘强度" :min="0.05" :max="0.6" :step="0.05" :precision="2" :disabled="locked" /><p>数值越高，重绘变化越明显；出现双轮廓时可先降低。</p></div>
          <FreyjaUpscaleOptions v-model="options" :capability="capability?.freyjaUpscale" :disabled="locked" preserve-size />
          <p>保留原始动作接续、裁切范围和输出尺寸；各片段已开启的人脸修复会在二采后继续执行。</p>
        </section>
      </template>
      <p v-else-if="summary">{{ summary.reason }}</p>
    </div>
    <template #footer><div class="dialog-footer"><p v-if="summary?.supported" role="status">已选 <strong>{{ selected.length }}</strong> 个片段 · 保留时长 {{ selectedSeconds }} 秒<span>其余 {{ summary.segments.length - selected.length }} 个片段沿用当前结果</span></p><div><el-button :disabled="submitting" @click="visible = false">关闭</el-button><el-button v-if="!submittedTask" type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">重跑 {{ selected.length }} 个片段并合成</el-button></div></div></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { videoEnhancementApi, defaultWorkflowOptions, normalizeWorkflowOptions, type ContinuationEnhancementSummary, type WorkflowCapability, type SegmentRerunMode } from '@/api/video-enhancements'
import FreyjaUpscaleOptions from './FreyjaUpscaleOptions.vue'
const visible = ref(false), loading = ref(false), submitting = ref(false), error = ref(''), taskId = ref(''), previewId = ref(''), submittedTask = ref('')
const selectedIds = ref<string[]>([])
const rerunMode = ref<SegmentRerunMode>('UPSCALE_ONLY')
const summary = ref<ContinuationEnhancementSummary>(), options = ref(defaultWorkflowOptions()), capability = ref<WorkflowCapability>()
function isAllowed(segment: ContinuationEnhancementSummary['segments'][number]) { return rerunMode.value === 'UPSCALE_ONLY' ? segment.allowed : segment.baseAllowed !== false }
const selectable = computed(() => summary.value?.segments.filter(isAllowed) || [])
const selected = computed(() => selectable.value.filter(segment => selectedIds.value.includes(segment.id)))
const previewSegment = computed(() => summary.value?.segments.find(segment => segment.id === previewId.value))
const allSelected = computed(() => !!selectable.value.length && selected.value.length === selectable.value.length)
const partlySelected = computed(() => selected.value.length > 0 && !allSelected.value)
const selectedSeconds = computed(() => (selected.value.reduce((frames, segment) => frames + segment.endFrame - segment.startFrame, 0) / 24).toFixed(1))
const locked = computed(() => submitting.value || !!submittedTask.value)
const canSubmit = computed(() => !loading.value && !locked.value && !!selected.value.length && (rerunMode.value === 'BASE_ONLY' || !!capability.value?.freyjaUpscale?.supported))
watch(rerunMode, () => { selectedIds.value = selectedIds.value.filter(id => selectable.value.some(segment => segment.id === id)) })
watch(selected, segments => {
  if (!segments.some(segment => segment.id === previewId.value)) previewId.value = segments[0]?.id || ''
})
function time(frame: number) { return `${(frame / 24).toFixed(2)} 秒` }
function toggleAll(checked: string | number | boolean) { if (!locked.value) selectedIds.value = checked ? selectable.value.map(segment => segment.id) : [] }
function removeSegment(id: string) { if (!locked.value) selectedIds.value = selectedIds.value.filter(value => value !== id) }
let requestId = '', submittedBody = '', revision = 0
async function open(task: string) {
  if (submitting.value) return
  const token = ++revision
  rerunMode.value = 'UPSCALE_ONLY'
  visible.value = true; loading.value = true; error.value = ''; summary.value = undefined; capability.value = undefined; selectedIds.value = []; previewId.value = ''; submittedTask.value = ''; taskId.value = task; requestId = ''; submittedBody = ''
  try {
    const result = await videoEnhancementApi.segments(task)
    if (token !== revision) return
    summary.value = result
    if (!result.supported) return
    const first = result.segments.find(segment => segment.allowed)
    options.value = { ...normalizeWorkflowOptions(first?.options || result.options || {}), upscale: true, upscaleEngine: 'FREYJA' }
    selectedIds.value = first ? [first.id] : []
    const catalog = await videoEnhancementApi.capabilities(result.providerId!, true)
    if (token !== revision) return
    capability.value = catalog.models.find(model => model.modelCode === result.modelCode && model.supported)
    if (!capability.value?.freyjaUpscale?.nativeLatentInput) { capability.value = undefined; error.value = '当前服务暂不支持片段重跑，请先更新网关和算力节点。' }
  } catch (e: any) { if (token === revision) error.value = e.message || '无法读取片段' }
  finally { if (token === revision) loading.value = false }
}
async function submit() {
  if (!canSubmit.value) return
  const ids = selected.value.map(segment => segment.id)
  const body = JSON.stringify([taskId.value, ids, options.value, rerunMode.value])
  if (body !== submittedBody) { requestId = crypto.randomUUID(); submittedBody = body }
  submitting.value = true; error.value = ''
  try {
    const result = await videoEnhancementApi.replaceSegments(taskId.value, ids, options.value, requestId, rerunMode.value)
    submittedTask.value = result.taskId
    ElMessage.success(`已提交 ${ids.length} 个片段，将合成为一个新的完整版本。`)
  } catch (e: any) { error.value = e.message || '提交失败，可重试原请求' }
  finally { submitting.value = false }
}
defineExpose({ open })
</script>
<style scoped>
.segment-upscale { display: grid; gap: 20px; min-height: 160px; max-height: calc(90vh - 140px); overflow-y: auto; padding-right: 4px; color: var(--text-primary); }
p { margin: 0; font-size: 12px; line-height: 1.7; color: var(--text-secondary); }
.selection-workspace { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr); gap: 24px; }
.segment-picker, .selection-review { min-width: 0; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
h3 { margin: 0; font-size: 14px; font-weight: 600; }
h3 span { margin-left: 8px; color: var(--brand); font-size: 12px; font-weight: 400; }
small { color: var(--text-secondary); font-size: 12px; }
.selection-actions { display: flex; align-items: center; gap: 14px; }
.selection-actions .el-checkbox { margin: 0; height: auto; }
.selection-actions :deep(.el-checkbox__label) { font-size: 12px; }
.segment-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; max-height: 440px; overflow-y: auto; padding: 2px; }
.segment-card { min-width: 0; padding: 10px; border: 1px solid var(--border-default); border-radius: var(--radius-sm); background: var(--surface-raised); }
.segment-card.is-selected { border-color: var(--brand); background: var(--brand-soft); }
.segment-card.is-unavailable { background: var(--surface-muted); }
.segment-checkbox { display: flex; margin: 0 0 8px; height: auto; width: 100%; }
.segment-checkbox :deep(.el-checkbox__label) { display: flex; align-items: baseline; gap: 6px; min-width: 0; font-size: 12px; }
.segment-checkbox strong { flex-shrink: 0; font-weight: 500; }
.segment-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.clip-poster { position: relative; overflow: hidden; border-radius: 4px; background: #11131a; aspect-ratio: 16 / 8; }
.clip-poster video { width: 100%; height: 100%; object-fit: contain; }
.clip-state { position: absolute; top: 6px; left: 6px; padding: 2px 6px; background: rgba(0, 0, 0, .65); color: #fff; font-size: 10px; border-radius: 3px; line-height: 1.5; }
.preview-button { position: absolute; bottom: 6px; right: 6px; }
.clip-range { margin-top: 6px; font-variant-numeric: tabular-nums; }
.unavailable-reason { margin-top: 4px; color: var(--warning); }
.selected-list { display: grid; gap: 6px; list-style: none; padding: 0 2px; margin: 0 0 14px; max-height: 180px; overflow-y: auto; }
.selected-list li { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border: 1px solid var(--border-default); border-radius: 6px; }
.selected-list li.is-previewing { border-color: var(--brand); background: var(--brand-soft); }
.selected-preview { display: grid; gap: 4px; flex: 1; min-width: 0; text-align: left; cursor: pointer; background: transparent; border: 0; color: var(--text-primary); }
.selected-preview strong { overflow-wrap: anywhere; font-size: 12px; font-weight: 500; }
.selected-preview:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; }
.selection-empty { padding: 32px 16px; margin-bottom: 14px; text-align: center; background: var(--surface-muted); border-radius: var(--radius-sm); font-size: 13px; line-height: 2; }
.preview-panel { overflow: hidden; border: 1px solid var(--border-default); border-radius: var(--radius-sm); }
.preview-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px; background: var(--surface-muted); font-size: 12px; }
.preview-heading strong { font-weight: 500; overflow-wrap: anywhere; }
.preview-heading span { flex-shrink: 0; color: var(--text-secondary); font-size: 11px; }
.segment-preview { display: block; width: 100%; max-height: 240px; background: #11131a; }
.preview-panel p { padding: 10px; }
.parameter-settings { border-top: 1px solid var(--border-default); padding-top: 18px; }
.parameter-settings :deep(.upscale-options) { margin-bottom: 10px; }
.denoise-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 14px; font-size: 12px; }
.dialog-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; text-align: left; }
.dialog-footer > p { font-size: 12px; line-height: 1.7; color: var(--text-secondary); }
.dialog-footer strong { color: var(--brand); }
.dialog-footer p span { display: block; }
@media (max-width: 760px) {
  .selection-workspace { grid-template-columns: 1fr; gap: 20px; }
  .segment-grid { max-height: 360px; }
  .dialog-footer > div { display: flex; justify-content: flex-end; width: 100%; }
}
@media (max-width: 420px) { .segment-grid { grid-template-columns: 1fr; } }
</style>
