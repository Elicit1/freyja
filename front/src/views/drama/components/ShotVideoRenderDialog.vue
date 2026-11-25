<template>
  <el-dialog v-model="visible" width="min(1120px, calc(100vw - 40px))" top="5dvh" append-to-body destroy-on-close :close-on-click-modal="!submitting" :close-on-press-escape="!submitting" :show-close="!submitting" class="shot-render-dialog">
    <template #header>
      <div class="render-heading">
        <span class="render-icon"><el-icon><VideoCamera /></el-icon></span>
        <div class="render-title-block"><div class="render-title-line"><h2>视频渲染</h2><span class="render-mode">{{ modeLabel }}</span></div><p><span v-if="shot?.shotNo">镜头 {{ String(shot.shotNo).padStart(2, '0') }}<span class="heading-divider">/</span></span>{{ shot?.shotName || '未命名镜头' }}</p></div>
        <span class="render-header-note">把故事，变成画面</span>
      </div>
    </template>

    <div v-loading="loading" class="render-body">
      <div class="render-preview-column"><ShotRenderPreview :shot="shot" :continuation="continuation" :segment-count="segmentCount" :disabled="submitting" @edit="editShot" @history="historyVisible = true" /></div>
      <div class="render-settings-column">
        <section class="settings-section" aria-labelledby="generation-settings-title">
          <div class="section-heading"><span class="section-number">01</span><div><h3 id="generation-settings-title">生成设置</h3><p>选择本次镜头的生成方式与画幅</p></div><span class="section-hint">{{ continuation ? `${segmentCount} 个片段` : '单镜头' }}</span></div>
          <el-form label-position="top" class="render-fields" :disabled="submitting">
            <el-form-item label="视频提供商"><el-select v-model="config.providerId" placeholder="选择视频提供商" @change="loadModels"><el-option v-for="p in providers" :key="String(p.id)" :value="String(p.id)" :label="p.providerName" /></el-select></el-form-item>
            <el-form-item label="视频模型"><el-select v-model="config.modelCode" :placeholder="config.providerId ? '选择视频模型' : '请先选择提供商'" :disabled="!config.providerId || modelsLoading" :loading="modelsLoading" filterable><el-option v-for="m in models" :key="String(m.id)" :value="m.modelCode" :label="m.modelName" /></el-select></el-form-item>
            <el-form-item label="画幅比例" class="ratio-field">
              <el-radio-group v-model="ratio" class="ratio-choices" aria-label="画幅比例" @change="setRatio"><el-radio v-for="option in ratioOptions" :key="option" :value="option" border><span :class="['ratio-shape', { portrait: option === '9:16', square: option === '1:1' }]"></span><span class="ratio-choice-text"><strong>{{ option }}</strong><span>{{ option === '16:9' ? '横屏' : option === '9:16' ? '竖屏' : '方形' }}</span></span></el-radio></el-radio-group>
              <span v-if="!ratioOptions.length" class="field-warning">当前模型没有可用画幅，请检查模型配置。</span>
            </el-form-item>
            <el-form-item label="基础生成尺寸" class="size-field"><el-select v-model="config.size" filterable placeholder="选择生成尺寸"><el-option v-for="s in sizeOptions" :key="s" :value="s" :label="s.replace('x', ' × ')" /></el-select><span class="field-help">开启二采后，可进一步提升成片分辨率。</span></el-form-item>
          </el-form>
        </section>

        <section class="settings-section enhancement-section" aria-labelledby="enhancement-settings-title">
          <div class="section-heading"><span class="section-number">02</span><div><h3 id="enhancement-settings-title">画面增强</h3><p>按需精修，让成片更进一步</p></div><span class="section-hint">可选</span></div>
          <VideoEnhancementOptions v-model="config.dynamicWorkflow" :provider-id="config.providerId" :model-id="selectedModelId" :model-code="config.modelCode" :size="config.size" :disabled="submitting" />
        </section>

        <div class="render-flow" aria-label="生成流程"><span class="flow-label">本次流程</span><div><span class="flow-stage"><i></i>基础生成</span><template v-if="config.dynamicWorkflow?.options?.upscale"><el-icon><ArrowRight /></el-icon><span class="flow-stage enabled">二采放大</span></template><template v-if="config.dynamicWorkflow?.options?.faceRepair"><el-icon><ArrowRight /></el-icon><span class="flow-stage enabled">人脸修复</span></template><el-icon><ArrowRight /></el-icon><span class="flow-stage">成片</span></div><p>输出尺寸 <strong>{{ outputSize }}</strong></p></div>
        <p class="render-note"><el-icon><InfoFilled /></el-icon>{{ continuation ? '提交前将校验各片段的模型能力与最新配置。' : '沿用镜头中保存的剧本、提示词与参考素材。' }}</p>
        <el-alert v-if="error" type="error" :closable="false" :title="error" show-icon />
        <div v-if="status" role="status" class="render-status"><el-icon class="is-loading"><Loading /></el-icon>{{ status }}</div>
      </div>
    </div>

    <template #footer>
      <div class="render-footer">
        <div class="render-output-summary"><span class="output-icon"><el-icon><Film /></el-icon></span><div><div class="output-specs"><strong>{{ shot?.duration || 0 }} 秒</strong><span>·</span><strong>{{ outputSize }}</strong><span v-if="config.modelCode">·</span><span v-if="config.modelCode" class="output-model" :title="selectedModelName">{{ selectedModelName }}</span></div><p>任务将在后台生成，可前往任务中心查看进度</p></div></div>
        <div class="render-actions"><el-button :disabled="submitting" @click="editShot">返回编辑</el-button><el-button type="primary" :loading="submitting" :disabled="loading || !config.providerId || !config.modelCode || !config.size || !ratio || modelsLoading" @click="submit"><el-icon v-if="!submitting"><VideoPlay /></el-icon>{{ submitting ? '正在提交' : continuation ? '开始接续生成' : '开始生成' }}</el-button></div>
      </div>
    </template>
    <ShotVideoHistoryDrawer v-if="shot?.id" v-model="historyVisible" :shot-id="shot.id" :shot-no="shot.shotNo" :shot-name="shot.shotName" @selected="onTake" />
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowRight, Film, InfoFilled, Loading, VideoCamera, VideoPlay } from '@element-plus/icons-vue'
import { aiProviderApi } from '@/api/ai-provider'
import { dramaApi, shotApi } from '@/api/drama'
import { shotVideoReferenceApi } from '@/api/shot-video-reference'
import { resolveGenerationSize, validateGenerationSize } from '@/utils/generation-size'
import type { AiModel, AiProviderVO } from '@/types/ai-provider'
import type { DramaShot } from '@/types/drama'
import ShotVideoHistoryDrawer from './ShotVideoHistoryDrawer.vue'
import ShotRenderPreview from './ShotRenderPreview.vue'
import VideoEnhancementOptions from '@/components/workflow/VideoEnhancementOptions.vue'
import type { WorkflowSelection } from '@/api/video-enhancements'
export interface VideoRenderConfiguration { providerId: string; modelCode: string; size: string; dynamicWorkflow?: WorkflowSelection }
export type ContinuationSubmit = (configuration: VideoRenderConfiguration, onProgress: (message: string) => void) => Promise<void>
const emit = defineEmits<{ success: [shotId: string | number]; edit: [shot: DramaShot] }>()
const visible = ref(false), loading = ref(false), submitting = ref(false), modelsLoading = ref(false), historyVisible = ref(false)
const shot = ref<DramaShot>(), providers = ref<AiProviderVO[]>([]), models = ref<AiModel[]>([])
const ratio = ref('16:9'), error = ref(''), status = ref(''), continuation = ref(false), segmentCount = ref(0)
const config = reactive<VideoRenderConfiguration>({ providerId: '', modelCode: '', size: '960x544' })
const modeLabel = computed(() => continuation.value ? '片段接续' : shot.value?.generationMode === 'REFERENCE_MODE' ? '多模态参考' : '首尾帧')
const selectedModelName = computed(() => models.value.find(m => m.modelCode === config.modelCode)?.modelName || config.modelCode)
const outputSize = computed(() => {
  const [width, height] = config.size.split('x').map(Number)
  if (!width || !height) return '待选择尺寸'
  const enhancement = config.dynamicWorkflow?.options
  if (enhancement?.upscale && enhancement.upscaleOutput === 'FULL_HD') return width < height ? '1080 × 1920' : '1920 × 1080'
  const scale = enhancement?.upscale ? 2 : 1
  return `${width * scale} × ${height * scale}`
})
const selectedModelId = computed(() => { const id = models.value.find(m => m.modelCode === config.modelCode)?.id; return id == null ? undefined : String(id) })
let submitContinuation: ContinuationSubmit | undefined
let session = 0, modelRequest = 0
const presetSizes = computed(() => ratio.value === '9:16' ? ['544x960','576x1024','736x1280','768x1344'] : ratio.value === '1:1' ? ['768x768','1024x1024'] : ['960x544','1024x576','1280x736','1344x768'])
const modelVideoOptions = computed(() => {
  const selected = models.value.find(m => m.modelCode === config.modelCode)
  try { return JSON.parse(selected?.paramsJson || '{}').videoGeneration || {} } catch { return { supportedSizes: [], supportedAspectRatios: [] } }
})
const ratioOptions = computed(() => ['16:9','9:16','1:1'].filter(v => !Array.isArray(modelVideoOptions.value.supportedAspectRatios) || modelVideoOptions.value.supportedAspectRatios.includes(v)))
const sizeOptions = computed(() => {
  const declared = modelVideoOptions.value.supportedSizes
  if (!Array.isArray(declared)) return presetSizes.value
  const [rw,rh] = ratio.value.split(':').map(Number)
  return declared.filter((s: unknown): s is string => typeof s === 'string' && validateGenerationSize(s,'video').valid).filter((s: string) => { const [w,h] = s.split('x').map(Number); return Math.abs(w! / h! - rw! / rh!) < 0.08 })
})
watch([sizeOptions, ratioOptions], () => { if (!ratioOptions.value.includes(ratio.value)) ratio.value = ratioOptions.value[0] || ''; if (!sizeOptions.value.includes(config.size)) config.size = sizeOptions.value[0] || '' })
function editShot() { if (shot.value && !submitting.value) { visible.value = false; emit('edit', shot.value) } }
function setRatio() { config.size = resolveGenerationSize(ratio.value, 'video') }
async function open(value: DramaShot, aspectRatio?: string, onSubmit?: ContinuationSubmit, count = 0) {
  const token = ++session; shot.value = JSON.parse(JSON.stringify(value)); visible.value = true; loading.value = true; historyVisible.value = false; error.value = ''; status.value = ''
  continuation.value = Boolean(onSubmit); segmentCount.value = count; submitContinuation = onSubmit; models.value = []; config.providerId = ''; config.modelCode = ''
  config.dynamicWorkflow = undefined
  ratio.value = ['16:9','9:16','1:1'].includes(aspectRatio || '') ? aspectRatio! : '16:9'; setRatio()
  try {
    const type = value.generationMode === 'REFERENCE_MODE' ? 'TXT2VIDEO_REF' : 'TXT2VIDEO_FIRST_LAST'
    const list = await aiProviderApi.getListEnabled()
    const choices = await Promise.all(list.map(async p => ({ provider: p, models: await aiProviderApi.getModelList(String(p.id), type) })))
    if (token !== session) return
    providers.value = choices.filter(p => p.models.some(m => m.status === 1)).map(p => p.provider)
    if (!providers.value.length) error.value = '没有当前生成模式可用的视频提供商，请先在模型中心配置。'
    if (!aspectRatio && value.dramaId) {
      const drama = await dramaApi.getById(value.dramaId)
      if (token === session && ['16:9','9:16','1:1'].includes(drama.aspectRatio || '')) { ratio.value = drama.aspectRatio!; setRatio() }
    }
  } catch (e: any) { if (token === session) error.value = e.message || '加载渲染配置失败' } finally { if (token === session) loading.value = false }
}
async function loadModels() {
  const token = ++modelRequest, currentSession = session, provider = config.providerId; modelsLoading.value = true; models.value = []; config.modelCode = ''; error.value = ''
  try { const all = await aiProviderApi.getModelList(provider, shot.value?.generationMode === 'REFERENCE_MODE' ? 'TXT2VIDEO_REF' : 'TXT2VIDEO_FIRST_LAST'); if (token === modelRequest && currentSession === session) models.value = all.filter(m => m.status === 1) }
  catch (e: any) { if (token === modelRequest && currentSession === session) error.value = e.message || '加载模型失败' }
  finally { if (token === modelRequest && currentSession === session) modelsLoading.value = false }
}
async function submit() {
  if (submitting.value || !shot.value?.id || !models.value.some(m => m.modelCode === config.modelCode)) return
  if (!providers.value.some(p => String(p.id) === config.providerId) || !ratioOptions.value.includes(ratio.value) || !sizeOptions.value.includes(config.size)) return ElMessage.warning('提供商、画幅或尺寸选项已变化，请重新选择')
  const validated = validateGenerationSize(config.size, 'video'); if (!validated.valid) return ElMessage.warning(validated.message)
  const token = session, id = String(shot.value.id), values = { ...config, size: validated.normalized! }; submitting.value = true; error.value = ''
  const current = () => token === session && visible.value
  try {
    if (submitContinuation) await submitContinuation(values, message => { if (current()) status.value = message })
    else {
      const fresh = await shotApi.getById(id); if (!current()) return
      if (JSON.parse(fresh.continuationJson || '{}').enabled) throw new Error('此镜头已开启接续，请从镜头接续工作台提交。')
      if (fresh.duration > 15) throw new Error('普通模式最多15秒，请在镜头中开启接续或调整时长。')
      if (fresh.generationMode !== shot.value.generationMode) throw new Error('镜头生成模式已变化，请重新打开渲染配置。')
      let preflightId: string | undefined
      if (fresh.refVideos?.length) {
        status.value = '正在准备参考视频…'; let prepared = await shotVideoReferenceApi.prepare(id, values.providerId, values.modelCode); const deadline = Date.now() + 6 * 60 * 1000
        while (prepared.status === 'PREPARING') { if (!current()) return; if (Date.now() > deadline) throw new Error('素材仍在后台准备，请稍后重试。'); await new Promise(resolve => setTimeout(resolve, 1200)); if (!current()) return; prepared = await shotVideoReferenceApi.get(id, prepared.preflightId) }
        if (prepared.status !== 'READY') throw new Error(prepared.errorMessage || '参考视频准备失败'); preflightId = prepared.preflightId
      }
      if (!current()) return
      const controlNet = JSON.parse(fresh.continuationJson || '{}').controlNet
      status.value = '正在提交视频任务…'; await shotApi.submitRender(id, { providerId: values.providerId, workflowTemplateId: values.modelCode, size: values.size, preflightId, controlNet, dynamicWorkflow: values.dynamicWorkflow })
    }
    if (current()) { emit('success', id); visible.value = false; ElMessage.success('视频任务已加入任务中心') }
  } catch (e: any) { if (current()) error.value = e.message || '提交失败' } finally { if (token === session) { submitting.value = false; status.value = '' } }
}
function onTake(value: { takeId: string; videoUrl: string }) { if (shot.value) { shot.value.videoUrl = value.videoUrl; emit('success', String(shot.value.id)) } }
onBeforeUnmount(() => { session++; modelRequest++ })
defineExpose({ open })
</script>

<style>
.shot-render-dialog.el-dialog { --el-dialog-padding-primary: 0; display: flex; flex-direction: column; margin-bottom: 0; padding: 0; max-height: 90dvh; overflow: hidden; border: 1px solid var(--border-default); border-radius: 16px; background: var(--surface-raised); box-shadow: var(--shadow-floating); }
.shot-render-dialog > .el-dialog__header { flex-shrink: 0; margin: 0; padding: 23px 28px; border-bottom: 1px solid var(--border-default); }
.shot-render-dialog > .el-dialog__header .el-dialog__headerbtn { top: 22px; right: 20px; width: 28px; height: 28px; border-radius: 7px; }
.shot-render-dialog > .el-dialog__header .el-dialog__headerbtn:hover { background: var(--surface-hover); }
.shot-render-dialog > .el-dialog__body { flex: 0 1 auto; min-height: 0; padding: 0; overflow-y: auto; overscroll-behavior-y: contain; }
.shot-render-dialog > .el-dialog__footer { flex-shrink: 0; padding: 18px 28px; border-top: 1px solid var(--border-default); background: var(--surface-raised); }
@media(max-width: 760px) { .shot-render-dialog > .el-dialog__header { padding: 19px 20px; }.shot-render-dialog > .el-dialog__footer { padding: 16px 20px; } }
</style>

<style scoped>
.render-heading { display: flex; align-items: center; gap: 13px; padding-right: 28px; }
.render-icon { display: grid; place-items: center; width: 42px; height: 42px; flex-shrink: 0; border: 1px solid color-mix(in srgb, var(--brand) 15%, transparent); border-radius: 12px; background: var(--brand-soft); color: var(--brand); font-size: 21px; }
.render-title-block { min-width: 0; }
.render-title-line { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
h2 { margin: 0; font-size: 18px; line-height: 1.35; font-weight: 650; letter-spacing: -.3px; color: var(--text-primary); }
.render-mode { padding: 3px 7px; border: 1px solid var(--border-default); border-radius: 5px; font-size: 10px; line-height: 1.2; color: var(--text-secondary); background: var(--surface-muted); }
.render-title-block p { margin: 5px 0 0; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-size: 11px; color: var(--text-muted); }
.heading-divider { margin: 0 8px; color: var(--border-strong); }
.render-header-note { margin-left: auto; margin-right: 16px; white-space: nowrap; font-size: 11px; letter-spacing: 1px; color: var(--text-muted); }
.render-body { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr); }
.render-preview-column { min-width: 0; padding: 24px 28px 26px; border-right: 1px solid var(--border-default); background: var(--surface-muted); }
.render-settings-column { display: flex; flex-direction: column; gap: 24px; min-width: 0; padding: 26px 28px; }
.settings-section { display: grid; gap: 22px; }
.section-heading { display: flex; align-items: center; gap: 10px; }
.section-number { display: grid; place-items: center; width: 27px; height: 27px; flex-shrink: 0; border: 1px solid var(--border-default); border-radius: 7px; background: var(--surface-muted); font-size: 10px; font-weight: 600; color: var(--text-secondary); }
h3 { margin: 0; font-size: 13px; font-weight: 650; color: var(--text-primary); }
.section-heading p { margin: 4px 0 0; font-size: 10px; color: var(--text-muted); }
.section-hint { margin-left: auto; font-size: 10px; color: var(--text-muted); }
.render-fields { display: grid; grid-template-columns: minmax(0, 1fr); gap: 17px; }
.render-fields :deep(.el-form-item) { margin: 0; min-width: 0; }
.render-fields :deep(.el-form-item__label) { height: auto; margin: 0 0 8px; padding: 0; font-size: 11px; font-weight: 500; line-height: 1.4; color: var(--text-secondary); }
.render-fields :deep(.el-form-item__content) { line-height: normal; }
.render-fields :deep(.el-select) { width: 100%; }
.render-fields :deep(.el-select__wrapper) { min-height: 39px; padding: 8px 12px; background: var(--surface); border-radius: 7px; font-size: 12px; }
.ratio-choices { display: flex; flex-wrap: nowrap; gap: 9px; width: 100%; }
.ratio-choices :deep(.el-radio) { flex: 1; min-width: 0; height: 59px; margin: 0; padding: 10px 12px; border-color: var(--border-default); border-radius: 8px; }
.ratio-choices :deep(.el-radio.is-checked) { border-color: var(--brand); background: var(--brand-soft); }
.ratio-choices :deep(.el-radio__input) { position: absolute; opacity: 0; }
.ratio-choices :deep(.el-radio__label) { display: flex; align-items: center; justify-content: center; gap: 10px; width: 100%; padding: 0; }
.ratio-choices :deep(.el-radio:has(input:focus-visible)) { outline: 2px solid var(--brand); outline-offset: 2px; }
.ratio-shape { width: 26px; height: 16px; flex-shrink: 0; border: 1.5px solid currentColor; border-radius: 3px; color: var(--text-muted); }
.ratio-shape.portrait { width: 15px; height: 25px; }.ratio-shape.square { width: 21px; height: 21px; }
.ratio-choice-text { display: grid; gap: 4px; color: var(--text-secondary); }
.ratio-choice-text strong { font-size: 11px; font-weight: 600; }.ratio-choice-text > span { font-size: 9px; color: var(--text-muted); }
.ratio-choices :deep(.is-checked) .ratio-shape, .ratio-choices :deep(.is-checked) .ratio-choice-text strong { color: var(--brand); }
.field-help { display: block; margin-top: 7px; font-size: 10px; line-height: 1.6; color: var(--text-muted); }
.field-warning { color: var(--danger); font-size: 11px; }
.enhancement-section { padding-top: 23px; border-top: 1px solid var(--border-default); gap: 17px; }
.render-flow { display: grid; gap: 12px; padding: 14px 15px; border: 1px solid var(--border-default); border-radius: 9px; background: var(--surface-muted); }
.flow-label { font-size: 10px; color: var(--text-muted); }
.render-flow > div { display: flex; align-items: center; flex-wrap: wrap; gap: 9px; font-size: 10px; color: var(--text-muted); }
.flow-stage { display: flex; align-items: center; gap: 6px; font-size: 11px; color: var(--text-secondary); }.flow-stage.enabled { color: var(--brand); }
.flow-stage i { width: 5px; height: 5px; border-radius: 50%; background: var(--brand); }
.render-flow p { display: flex; align-items: center; justify-content: space-between; margin: 0; padding-top: 10px; border-top: 1px solid var(--border-default); color: var(--text-muted); font-size: 10px; }
.render-flow p strong { font-size: 11px; color: var(--text-primary); font-weight: 500; font-variant-numeric: tabular-nums; }
.render-note { display: flex; align-items: flex-start; gap: 7px; margin: -9px 0 0; font-size: 10px; line-height: 1.8; color: var(--text-muted); }.render-note .el-icon { flex-shrink: 0; margin-top: 3px; }
.render-status { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--brand); }
.render-footer { display: flex; align-items: center; justify-content: space-between; gap: 24px; text-align: left; }
.render-output-summary { display: flex; align-items: center; gap: 11px; min-width: 0; }.output-icon { display: grid; place-items: center; width: 34px; height: 34px; flex-shrink: 0; border: 1px solid var(--border-default); border-radius: 8px; color: var(--text-secondary); font-size: 17px; }
.render-output-summary > div { min-width: 0; }.output-specs { display: flex; align-items: center; gap: 8px; font-size: 11px; color: var(--text-muted); }.output-specs strong { flex-shrink: 0; font-weight: 550; color: var(--text-primary); font-variant-numeric: tabular-nums; }.output-model { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; max-width: 270px; color: var(--text-secondary); font-size: 10px; }
.render-output-summary p { margin: 6px 0 0; font-size: 10px; color: var(--text-muted); }
.render-actions { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }.render-actions .el-button { height: 39px; margin: 0; padding: 0 17px; border-radius: 8px; font-size: 12px; }.render-actions .el-button--primary :deep(.el-icon) { margin-right: 6px; }.render-actions .el-button--primary { min-width: 128px; gap: 7px; box-shadow: 0 2px 4px color-mix(in srgb, var(--brand) 15%, transparent); }
@media(max-width: 980px) and (min-width: 761px) { .render-preview-column, .render-settings-column { padding: 24px 20px; }.ratio-choices :deep(.el-radio) { padding: 8px; }.ratio-choices :deep(.el-radio__label) { gap: 7px; }.output-model { max-width: 160px; } }
@media(max-width: 760px) { .render-header-note { display: none; }.render-body { display: block; }.render-preview-column { padding: 20px; border: 0; border-bottom: 1px solid var(--border-default); }.render-settings-column { padding: 22px 20px; gap: 22px; }.render-footer { flex-direction: column; align-items: stretch; gap: 16px; }.output-icon { display: none; }.output-model { max-width: 180px; }.render-actions { justify-content: flex-end; }.render-actions .el-button { flex: 1; }.render-fields { gap: 16px; } }
@media(prefers-reduced-motion: reduce) { * { scroll-behavior: auto; transition: none; } }
</style>
