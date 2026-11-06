<template>
  <el-dialog v-model="visible" width="min(720px, 94vw)" top="8vh" append-to-body destroy-on-close :close-on-click-modal="!submitting" :show-close="!submitting" class="shot-render-dialog">
    <template #header><div class="render-heading"><span class="render-icon">▶</span><div><h2>视频渲染</h2><p>{{ shot?.shotName || `镜头 ${shot?.shotNo || ''}` }} · {{ continuation ? '接续生成' : shot?.generationMode === 'REFERENCE_MODE' ? '多模态参考' : '首尾帧模式' }}</p></div></div></template>
    <div v-loading="loading" class="render-body">
      <div class="render-summary"><div><span class="render-eyebrow">本次任务</span><strong>{{ continuation ? `${segmentCount} 个片段串行生成` : '单镜头生成' }}</strong><p>最终成片 {{ shot?.duration || 0 }} 秒</p></div><div class="flex items-center gap-3"><el-button link type="primary" :disabled="submitting" @click="editShot">编辑镜头</el-button><el-tag effect="plain">{{ continuation ? '读取各片段配置' : '使用已保存的镜头配置' }}</el-tag></div></div>
      <el-form label-position="top" class="render-fields" :disabled="submitting">
        <el-form-item label="视频提供商"><el-select v-model="config.providerId" placeholder="请选择视频提供商" @change="loadModels"><el-option v-for="p in providers" :key="String(p.id)" :value="String(p.id)" :label="p.providerName" /></el-select></el-form-item>
        <el-form-item label="视频模型"><el-select v-model="config.modelCode" placeholder="请选择视频模型" :disabled="!config.providerId || modelsLoading" :loading="modelsLoading" filterable><el-option v-for="m in models" :key="String(m.id)" :value="m.modelCode" :label="m.modelName" /></el-select></el-form-item>
        <el-form-item label="画幅"><el-radio-group v-model="ratio" @change="setRatio"><el-radio-button value="16:9">16:9</el-radio-button><el-radio-button value="9:16">9:16</el-radio-button><el-radio-button value="1:1">1:1</el-radio-button></el-radio-group></el-form-item>
        <el-form-item label="生成尺寸"><el-select v-model="config.size" filterable allow-create default-first-option><el-option v-for="s in sizeOptions" :key="s" :value="s" :label="s.replace('x', ' × ')" /></el-select></el-form-item>
      </el-form>
      <div v-if="!continuation" class="render-assets"><div v-for="item in anchors" :key="item.label" class="render-anchor"><el-image v-if="item.url" :src="item.url" fit="cover" :preview-src-list="[item.url]" preview-teleported /><span v-else class="anchor-empty">—</span><span>{{ item.label }}</span></div><div class="render-asset-counts"><span>参考图 <b>{{ shot?.refImages?.length || 0 }}</b></span><span>视频 <b>{{ shot?.refVideos?.length || 0 }}</b></span><span>音频 <b>{{ shot?.refAudios?.length || 0 }}</b></span></div></div>
      <p class="render-note">{{ continuation ? '剧本、导演、提示词和素材来自主页面各片段；提交前校验模型能力与最新计划。' : '剧本、提示词与参考素材在镜头中配置，这里只设置本次生成参数。' }}</p>
      <el-collapse v-if="shot?.videoUrl || shot?.videoPrompt || shot?.prompt" class="render-review"><el-collapse-item title="查看提示词与已有结果" name="review"><p class="render-prompt">{{ shot?.generationMode === 'REFERENCE_MODE' ? shot?.prompt : shot?.videoPrompt || shot?.prompt }}</p><video v-if="shot?.videoUrl" :src="shot.videoUrl" controls class="render-result" /><el-button v-if="shot?.id" link type="primary" @click="historyVisible = true">视频历史与版本重选</el-button></el-collapse-item></el-collapse>
      <el-alert v-if="error" type="error" :closable="false" :title="error" />
      <p v-if="status" role="status" class="render-status">{{ status }}</p>
    </div>
    <template #footer><div class="render-footer"><el-button :disabled="submitting" @click="visible = false">取消</el-button><el-button type="primary" :loading="submitting" :disabled="loading || !config.providerId || !config.modelCode || modelsLoading" @click="submit">校验并生成视频</el-button></div></template>
    <ShotVideoHistoryDrawer v-if="shot?.id" v-model="historyVisible" :shot-id="shot.id" :shot-no="shot.shotNo" :shot-name="shot.shotName" @selected="onTake" />
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { aiProviderApi } from '@/api/ai-provider'
import { dramaApi, shotApi } from '@/api/drama'
import { shotVideoReferenceApi } from '@/api/shot-video-reference'
import { resolveGenerationSize, validateGenerationSize } from '@/utils/generation-size'
import type { AiModel, AiProviderVO } from '@/types/ai-provider'
import type { DramaShot } from '@/types/drama'
import ShotVideoHistoryDrawer from './ShotVideoHistoryDrawer.vue'
export interface VideoRenderConfiguration { providerId: string; modelCode: string; size: string }
const emit = defineEmits<{ success: [shotId: string | number]; edit: [shot: DramaShot] }>()
const visible = ref(false), loading = ref(false), submitting = ref(false), modelsLoading = ref(false), historyVisible = ref(false)
const shot = ref<DramaShot>(), providers = ref<AiProviderVO[]>([]), models = ref<AiModel[]>([])
const ratio = ref('16:9'), error = ref(''), status = ref(''), continuation = ref(false), segmentCount = ref(0)
const config = reactive<VideoRenderConfiguration>({ providerId: '', modelCode: '', size: '960x544' })
let submitContinuation: ((configuration: VideoRenderConfiguration) => Promise<void>) | undefined
let session = 0, modelRequest = 0
const sizeOptions = computed(() => ratio.value === '9:16' ? ['544x960','576x1024','736x1280','768x1344'] : ratio.value === '1:1' ? ['768x768','1024x1024'] : ['960x544','1024x576','1280x736','1344x768'])
const anchors = computed(() => shot.value?.generationMode === 'FIRST_LAST_FRAME' ? [{ label: '首帧（可选）', url: shot.value.previewImageUrl }, { label: '尾帧（可选）', url: shot.value.endFrameImageUrl }] : [])
function editShot() { if (shot.value && !submitting.value) { visible.value = false; emit('edit', shot.value) } }
function setRatio() { config.size = resolveGenerationSize(ratio.value, 'video') }
async function open(value: DramaShot, aspectRatio?: string, onSubmit?: (configuration: VideoRenderConfiguration) => Promise<void>, count = 0) {
  const token = ++session; shot.value = JSON.parse(JSON.stringify(value)); visible.value = true; loading.value = true; error.value = ''; status.value = ''
  continuation.value = Boolean(onSubmit); segmentCount.value = count; submitContinuation = onSubmit; models.value = []; config.providerId = ''; config.modelCode = ''
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
  const validated = validateGenerationSize(config.size, 'video'); if (!validated.valid) return ElMessage.warning(validated.message)
  const token = session, id = String(shot.value.id), values = { ...config, size: validated.normalized! }; submitting.value = true; error.value = ''
  const current = () => token === session && visible.value
  try {
    if (submitContinuation) await submitContinuation(values)
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
      status.value = '正在提交视频任务…'; await shotApi.submitRender(id, { providerId: values.providerId, workflowTemplateId: values.modelCode, size: values.size, preflightId, controlNet })
    }
    if (current()) { emit('success', id); visible.value = false; ElMessage.success('视频任务已加入任务中心') }
  } catch (e: any) { if (current()) error.value = e.message || '提交失败' } finally { if (token === session) { submitting.value = false; status.value = '' } }
}
function onTake(value: { takeId: string; videoUrl: string }) { if (shot.value) { shot.value.videoUrl = value.videoUrl; emit('success', String(shot.value.id)) } }
onBeforeUnmount(() => { session++; modelRequest++ })
defineExpose({ open })
</script>

<style scoped>
.render-heading{display:flex;align-items:center;gap:12px}.render-icon{display:grid;place-items:center;width:42px;height:42px;border-radius:12px;background:var(--brand-soft,#f3efff);color:var(--brand,#7c3aed)}h2{margin:0;font-size:19px;font-weight:650}.render-heading p{margin:3px 0 0;color:var(--text-muted);font-size:12px}.render-body{display:grid;gap:20px;padding-top:8px}.render-summary{display:flex;align-items:center;justify-content:space-between;background:var(--surface-muted,#f8f9fc);border:1px solid var(--border-default,#e8e8ef);border-radius:12px;padding:16px}.render-eyebrow{display:block;font-size:11px;color:var(--text-muted);margin-bottom:5px}.render-summary strong{display:block;font-size:17px}.render-summary p{font-size:12px;color:var(--text-secondary);margin:5px 0 0}.render-fields{display:grid;grid-template-columns:1fr 1fr;gap:0 20px}.render-fields :deep(.el-select){width:100%}.render-assets{display:flex;gap:12px;align-items:center}.render-anchor{display:grid;gap:5px;text-align:center;font-size:11px;color:var(--text-secondary)}.render-anchor .el-image,.anchor-empty{width:100px;height:64px;background:var(--surface-muted);border-radius:8px}.anchor-empty{display:grid;place-items:center}.render-asset-counts{display:flex;gap:20px;color:var(--text-secondary);font-size:12px}.render-asset-counts b{margin-left:4px}.render-note{color:var(--text-muted);font-size:12px;line-height:1.7;margin:0}.render-prompt{white-space:pre-wrap;max-height:160px;overflow:auto;font-size:12px;color:var(--text-secondary)}.render-result{width:100%;max-height:280px;background:#111;border-radius:8px}.render-footer{display:flex;justify-content:flex-end;gap:8px}.render-status{font-size:12px;color:var(--brand)}@media(max-width:640px){.render-fields{grid-template-columns:1fr}.render-summary{gap:12px;align-items:flex-start;flex-direction:column}}
</style>
