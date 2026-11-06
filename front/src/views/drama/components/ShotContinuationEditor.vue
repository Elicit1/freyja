<template>
  <el-button plain type="primary" @click="open">接续模式与片段计划</el-button>
  <el-dialog v-model="visible" title="单镜头接续 · 片段计划" width="1000px" append-to-body :close-on-click-modal="false">
    <div v-loading="busy" class="space-y-4">
      <div class="flex flex-wrap items-center gap-4">
        <el-switch v-model="draft.enabled" active-text="接续模式" />
        <span>目标秒数</span><el-input-number v-model="draft.durationSeconds" :min="0.5" :max="draft.enabled ? 300 : Math.max(15, draft.durationSeconds)" :step="0.5" />
        <el-tag v-if="!draft.enabled && draft.durationSeconds > 15" type="danger">普通模式最多 15 秒，请缩短或开启接续</el-tag>
      </div>
      <div class="grid grid-cols-3 gap-3">
        <el-select v-model="providerId" placeholder="视频提供商" @change="loadModels"><el-option v-for="p in providers" :key="String(p.id)" :label="p.providerName" :value="String(p.id)" /></el-select>
        <el-select v-model="modelCode" placeholder="视频模型"><el-option v-for="m in models" :key="String(m.id)" :label="m.modelName" :value="m.modelCode" /></el-select>
        <el-input v-model="size" placeholder="实际尺寸，例如 960x544" />
      </div>
      <template v-if="draft.enabled">
        <div class="flex flex-wrap gap-3 items-center">
          <span>每段时长档位</span><el-select :model-value="selectedPreset" class="!w-48" @change="applyPreset"><el-option v-for="p in presets" :key="p.id" :value="p.id" :label="p.label" /><el-option label="自定义帧数" value="custom" /></el-select>
          <span class="text-xs text-slate-500">实际最多 {{ seconds(draft.segmentFrames) }} 秒 / 段，含 {{ seconds(draft.overlapFrames) }} 秒共享上下文</span>
          <span>段长（帧）</span><el-input-number v-model="draft.segmentFrames" :min="124" :max="362" :step="17" />
          <span>共享上下文（帧）</span><el-input-number v-model="draft.overlapFrames" :min="5" :max="141" :step="17" />
          <el-checkbox v-model="draft.contextWindows">启用已验证的上下文窗口</el-checkbox>
          <el-checkbox v-model="draft.lockContext">锁定共享上下文</el-checkbox>
          <el-button @click="preview(true)">自动拆分</el-button><el-button @click="appendSegment">手动加段</el-button><el-button @click="resetPlan">重建计划，素材退回待分配</el-button>
        </div>
        <div class="flex gap-3 items-center"><el-select v-model="chatModelId" placeholder="选择 AI 文本规划模型" class="!w-72"><el-option v-for="m in chatModels" :key="String(m.id)" :value="String(m.id)" :label="m.modelName" /></el-select><el-button :disabled="!draft.segments.length || !chatModelId" @click="planPrompts">AI 编写各段提示词</el-button></div>
        <el-alert type="info" :closable="false" title="自动拆分后可直接编辑。图片、视频只参与明确挂载的片段；上一段的生成上下文由系统传递。" />
        <el-alert v-if="generationMode === 'FIRST_LAST_FRAME'" type="info" :closable="false" title="当前为首尾帧工作流，请按片段设置首尾帧。需要普通图片或视频参考时，请在镜头中切换为参考生成模式。" />
        <el-checkbox v-if="lastTaskId" :model-value="!!draft.reuseFromTaskId" @change="(value: boolean | string | number) => draft.reuseFromTaskId = value ? lastTaskId : undefined">复用上次任务中输入未变的片段</el-checkbox>
        <div v-if="generationMode === 'REFERENCE_MODE'" class="flex gap-2 flex-wrap items-center"><el-checkbox v-model="draft.autoSplitReferenceVideo">上传后按当前分段自动切分并挂载到全部片段</el-checkbox><el-upload :show-file-list="false" accept="video/*" :before-upload="uploadTrayVideo"><el-button>上传参考视频</el-button></el-upload><el-button @click="chooseVideo()">选择分镜 / Take 参考视频</el-button></div>
        <div v-if="generationMode === 'REFERENCE_MODE'" class="text-xs text-slate-500">上传后展示各段源范围；渲染前后台实际裁片。切片包含真实重叠，源范围不足会提示，不拉伸、不循环。关闭自动切分后可手动选择目标片段。</div>
        <div v-if="imageTray.length || videoTray.length" class="p-3 bg-slate-50 rounded space-y-2">
          <div>待分配素材 <el-select v-model="selectedTargets" multiple placeholder="选择目标片段" class="!w-80"><el-option v-for="(s, i) in draft.segments" :key="s.id" :label="`片段 ${i + 1}`" :value="s.id" /></el-select></div>
          <div class="flex gap-2 flex-wrap" v-for="img in imageTray" :key="img.id"><span>{{ img.name || '参考图' }}</span><template v-if="generationMode === 'FIRST_LAST_FRAME'"><el-button size="small" @click="mountAnchor(img, true)">设为所选段首帧</el-button><el-button size="small" @click="mountAnchor(img, false)">设为所选段尾帧</el-button></template><el-button v-else size="small" @click="mountImage(img)">挂载图片</el-button></div>
          <div class="flex gap-2 flex-wrap items-center" v-for="video in videoTray" :key="video.id"><span>{{ video.name || '参考视频' }}</span><span>源范围（秒）</span><el-input-number v-model="video.startSeconds" :min="0" :step="0.5" /><span>至</span><el-input-number v-model="video.endSeconds" :min="0.1" :step="0.5" /><el-button size="small" :disabled="generationMode !== 'REFERENCE_MODE'" @click="mountVideo(video)">挂载所选源片段</el-button><el-button size="small" :disabled="generationMode !== 'REFERENCE_MODE'" @click="distributeVideo(video)">按时间轴分配</el-button><span class="text-xs text-slate-500">源起点对应镜头 0 秒</span></div>
        </div>
        <div v-for="(segment, index) in draft.segments" :key="segment.id" class="p-4 border border-slate-200 rounded-lg space-y-3">
          <div class="flex gap-3 items-center flex-wrap"><strong>片段 {{ index + 1 }}</strong><span>{{ seconds(segment.startFrame) }}～{{ seconds(segment.endFrame) }} 秒</span><el-checkbox v-model="segment.locked">锁定自动拆分</el-checkbox><el-button size="small" type="danger" text @click="removeSegment(index)">移除片段，素材退回待分配</el-button></div>
          <div class="flex gap-3 items-center"><span>生成帧范围</span><el-input-number v-model="segment.startFrame" :min="0" :step="17" /><span>至</span><el-input-number v-model="segment.endFrame" :min="22" :step="17" /></div>
          <el-input v-model="segment.prompt" type="textarea" :rows="3" placeholder="本段画面、动作与对白；可用本段 Picture / Video 标签，不需要逐项填写素材说明" />
          <div v-if="generationMode === 'FIRST_LAST_FRAME'" class="space-y-2">
            <div class="flex gap-2 items-center"><span>首帧 · Picture 1</span><el-input v-model="segment.firstFrameImageUrl" clearable placeholder="本段首帧地址，可从待分配素材挂载" /><el-upload :show-file-list="false" accept="image/*" :before-upload="(file: File) => uploadAnchor(file, segment, true)"><el-button>上传首帧</el-button></el-upload></div>
            <div class="flex gap-2 items-center"><span>尾帧 · Picture 2</span><el-input v-model="segment.lastFrameImageUrl" clearable placeholder="本段尾帧地址（可选）" /><el-upload :show-file-list="false" accept="image/*" :before-upload="(file: File) => uploadAnchor(file, segment, false)"><el-button>上传尾帧</el-button></el-upload></div>
          </div>
          <div v-else class="flex gap-3 flex-wrap items-start">
            <div v-for="(img, n) in segment.referenceImages" :key="img.id" class="w-24"><el-image :src="img.imageUrl" class="w-24 h-20" fit="cover" :preview-src-list="[img.imageUrl]" preview-teleported /><div class="text-xs">Picture {{ n + 1 }} <el-button text size="small" @click="segment.referenceImages.splice(n, 1)">移除</el-button></div></div>
            <el-upload :show-file-list="false" accept="image/*" :before-upload="(file: File) => upload(file, segment, 'image')"><el-button>添加参考图</el-button></el-upload>
            <el-upload :show-file-list="false" accept="video/*" :before-upload="(file: File) => upload(file, segment, 'video')"><el-button>上传本段参考视频</el-button></el-upload>
            <el-button @click="chooseVideo(segment)">选择分镜 / Take 视频</el-button>
          </div>
          <div v-for="(video, n) in segment.referenceVideos" :key="video.id" class="flex gap-2 flex-wrap items-center text-sm">
            <span>Video {{ n + 1 }} · {{ video.name || '视频' }}</span><span>源范围（秒）</span><el-input-number v-model="video.startSeconds" :min="0" :step="0.5" @change="clearFrameRange(video)" /><span>至</span><el-input-number v-model="video.endSeconds" :min="0.1" :step="0.5" @change="clearFrameRange(video)" /><el-tag v-if="video.sourceStartFrame !== undefined" size="small">按计划精确切帧</el-tag><el-button text type="danger" @click="removeVideo(segment, n)">移除</el-button>
            <el-checkbox v-model="video.includeAudio">参考原音</el-checkbox>
          </div>
          <div v-if="segment.referenceVideos.length" class="flex gap-3 items-center flex-wrap">
            <el-switch v-model="segment.controlNetEnabled" active-text="ControlNet（可选）" />
            <template v-if="segment.controlNetEnabled"><el-select v-model="segment.controlVideoId" placeholder="本段控制视频"><el-option v-for="(v, n) in segment.referenceVideos" :key="v.id" :value="v.id" :label="`Video ${n + 1}`" /></el-select><el-select v-model="segment.controlType"><el-option v-for="option in H3_CONTROL_TYPES" :key="option.value" :label="option.label" :value="option.value" /></el-select><el-input-number v-model="segment.controlStrength" :min="0" :max="2" :step="0.1" /></template>
          </div>
          <div v-if="index > 0" class="text-xs text-slate-500">接续上下文：来自片段 {{ index }} 的实际生成尾部，不计入本段参考素材编号。</div>
        </div>
        <el-button :loading="previewing" @click="preview(false)">校验并预览当前计划</el-button>
        <div v-if="plan" class="p-3 bg-sky-50 rounded space-y-2">
          <strong>共 {{ plan.segmentCount }} 段，{{ Math.max(0, plan.segmentCount - 1) }} 个接续点，最终 {{ seconds(plan.outputFrames) }} 秒</strong>
          <div v-for="(r, i) in plan.ranges" :key="r.segmentId" class="text-sm">片段 {{ i + 1 }}：生成 {{ seconds(r.startFrame) }}～{{ seconds(r.endFrame) }} 秒 · 共享 {{ r.overlapFrames }} 帧 · 成片保留 {{ seconds(r.keepStartFrame) }}～{{ seconds(r.keepEndFrame) }} 秒</div>
          <el-alert v-for="issue in plan.issues" :key="issue" :title="issue" type="warning" :closable="false" />
        </div>
      </template>
    </div>
    <template #footer><el-button @click="visible = false">关闭</el-button><el-button :loading="busy" :disabled="previewing" @click="save">保存草稿</el-button><el-button type="primary" :loading="busy" :disabled="!draft.enabled || !plan?.renderable || busy || previewing" @click="render">开始接续渲染</el-button></template>
    <ShotVideoPickerDialog ref="videoPicker" @selected="selectedVideo" />
  </el-dialog>
</template>

<script setup lang="ts">
import { H3_CONTROL_TYPES } from '@/constants/h3ControlTypes'
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { continuationApi } from '@/api/continuation'
import { shotApi } from '@/api/drama'
import { shotVideoReferenceApi } from '@/api/shot-video-reference'
import { aiProviderApi, aiModelApi } from '@/api/ai-provider'
import { runAiTaskResult } from '@/api/ai-task-stream'
import { assetApi } from '@/api/res-asset'
import type { AiModel, AiProviderVO } from '@/types/ai-provider'
import type { ShotRefImage, ShotRefVideo } from '@/types/drama'
import type { ContinuationDraft, ContinuationPlan, ContinuationPreset, ContinuationSegment } from '@/types/continuation'
import ShotVideoPickerDialog from '@/views/production/components/ShotVideoPickerDialog.vue'
const props = defineProps<{ shotId: string }>()
const emit = defineEmits<{ (event: 'saved'): void }>()
const visible = ref(false), busy = ref(false), previewing = ref(false)
const draft = ref<ContinuationDraft>({ schemaVersion: 1, enabled: false, durationSeconds: 15, segmentFrames: 124, overlapFrames: 22, contextWindows: false, autoSplitReferenceVideo: true, videoSources: [], segments: [] })
const presets = ref<ContinuationPreset[]>([])
const selectedPreset = computed(() => presets.value.find(p => p.segmentFrames === draft.value.segmentFrames && p.overlapFrames === draft.value.overlapFrames)?.id || 'custom')
const plan = ref<ContinuationPlan>(), providerId = ref(''), modelCode = ref(''), size = ref('960x544')
const providers = ref<AiProviderVO[]>([]), models = ref<AiModel[]>([]), imageTray = ref<ShotRefImage[]>([]), videoTray = ref<ShotRefVideo[]>([]), selectedTargets = ref<string[]>([])
const chatModels = ref<AiModel[]>([]), chatModelId = ref('')
const lastTaskId = ref<string>()
const generationMode = ref('REFERENCE_MODE')
const videoPicker = ref<InstanceType<typeof ShotVideoPickerDialog>>(), videoTarget = ref<ContinuationSegment>()
let revision = 0
let previewRequest = 0, applyingPreview = false
let previewTimer: ReturnType<typeof setTimeout> | undefined
watch(() => props.shotId, () => { visible.value = false; revision++; plan.value = undefined; selectedTargets.value = []; clearTimeout(previewTimer) })
watch([draft, providerId, modelCode, size], () => {
  if (applyingPreview) return
  revision++; plan.value = undefined; clearTimeout(previewTimer)
  if (visible.value && !busy.value && draft.value.enabled && providerId.value && modelCode.value) previewTimer = setTimeout(() => preview(!draft.value.segments.length), 700)
}, { deep: true, flush: 'sync' })
watch(busy, value => { if (!value && visible.value && !plan.value && draft.value.enabled && providerId.value && modelCode.value) { clearTimeout(previewTimer); previewTimer = setTimeout(() => preview(!draft.value.segments.length), 700) } })
onBeforeUnmount(() => clearTimeout(previewTimer))
const seconds = (frames: number) => (frames / 24).toFixed(3)
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value))
async function open() {
  visible.value = true; busy.value = true
  const shotId = props.shotId
  try {
    const [saved, shot, enabled, profiles] = await Promise.all([continuationApi.get(shotId), shotApi.getById(shotId), aiProviderApi.getListEnabled(), continuationApi.presets(shotId)])
    if (!visible.value || props.shotId !== shotId) return
    draft.value = saved; if (!shot.continuationJson) draft.value.durationSeconds = shot.duration || 15
    draft.value.autoSplitReferenceVideo ??= true; draft.value.videoSources ??= []; presets.value = profiles
    generationMode.value = shot.generationMode || 'REFERENCE_MODE'
    imageTray.value = [...(shot.refImages || [])]
    if (shot.previewImageUrl) imageTray.value.push({ id: 'shot-first-frame', sourceType: 'UPLOAD', imageUrl: shot.previewImageUrl, name: '镜头原首帧' } as ShotRefImage)
    if (shot.endFrameImageUrl) imageTray.value.push({ id: 'shot-last-frame', sourceType: 'UPLOAD', imageUrl: shot.endFrameImageUrl, name: '镜头原尾帧' } as ShotRefImage)
    videoTray.value = clone(draft.value.videoSources)
    for (const video of shot.refVideos || []) if (!videoTray.value.some(v => v.id === video.id)) videoTray.value.push(video)
    providers.value = enabled
    lastTaskId.value = shot.latestTaskId
    providerId.value ||= enabled[0] ? String(enabled[0].id) : ''; await loadModels()
    const chatPage = await aiModelApi.getPage({ modelType: 'CHAT', status: 1, pageSize: 100 }); chatModels.value = chatPage.records; chatModelId.value ||= chatModels.value[0] ? String(chatModels.value[0].id) : ''
  } catch (e: any) { ElMessage.error(e.message || '读取接续配置失败') } finally { busy.value = false }
  if (draft.value.enabled) await preview(!draft.value.segments.length)
}
async function loadModels() { if (!providerId.value) return; const all = await aiProviderApi.getModelList(providerId.value); const type = generationMode.value === 'FIRST_LAST_FRAME' ? 'TXT2VIDEO_FIRST_LAST' : 'TXT2VIDEO_REF'; models.value = all.filter(m => m.status === 1 && m.modelType === type); if (!models.value.some(m => m.modelCode === modelCode.value)) modelCode.value = models.value[0]?.modelCode || '' }
async function applyPreset(id: string) {
  const value = presets.value.find(p => p.id === id); if (!value) return
  const candidate = clone(draft.value); candidate.segmentFrames = value.segmentFrames; candidate.overlapFrames = value.overlapFrames
  await preview(true, candidate)
}
async function preview(auto: boolean, candidate?: ContinuationDraft) {
  clearTimeout(previewTimer)
  if (!providerId.value || !modelCode.value) return ElMessage.warning('请选择提供商和模型')
  previewing.value = true; const before = revision, request = ++previewRequest
  try {
    const value = await continuationApi.preview(props.shotId, clone(candidate || draft.value), auto, providerId.value, modelCode.value)
    if (!visible.value || before !== revision || request !== previewRequest) return
    applyingPreview = true
    try { draft.value = value.draft; plan.value = value } finally { applyingPreview = false }
  }
  catch (e: any) { if (before === revision && request === previewRequest) ElMessage.error(e.message || '计划校验失败') }
  finally { if (request === previewRequest) previewing.value = false }
}
async function planPrompts() {
  const model = chatModels.value.find(m => String(m.id) === chatModelId.value); if (!model) return
  const before = revision; busy.value = true
  try {
    const result = await runAiTaskResult<ContinuationDraft>(`/drama/shot/${props.shotId}/continuation/plan-task`, { draft: clone(draft.value), providerId: String(model.providerId), modelCode: model.modelCode })
    if (!visible.value || before !== revision) throw new Error('计划已修改；AI 结果保留在任务中心，本次未覆盖编辑')
    draft.value = result
  } catch (e: any) { ElMessage.error(e.message || 'AI 规划失败') } finally { busy.value = false }
  if (visible.value) await preview(false)
}
function appendSegment() { const start = draft.value.segments.length ? draft.value.segments.at(-1)!.endFrame - draft.value.overlapFrames : 0; draft.value.segments.push({ id: crypto.randomUUID(), startFrame: start, endFrame: start + draft.value.segmentFrames, prompt: '', locked: false, referenceImages: [], referenceVideos: [], controlNetEnabled: false, controlType: 'CANNY', controlStrength: 1 }) }
function removeSegment(index: number) { const s = draft.value.segments.splice(index, 1)[0]; if (!s) return; for (const image of s.referenceImages) if (!imageTray.value.some(x => x.id === image.id)) imageTray.value.push(image); for (const url of [s.firstFrameImageUrl, s.lastFrameImageUrl]) if (url && !imageTray.value.some(x => x.imageUrl === url)) imageTray.value.push({ id: crypto.randomUUID(), sourceType: 'UPLOAD', imageUrl: url, name: '片段锚点' } as ShotRefImage); for (const video of s.referenceVideos) if (!videoTray.value.some(x => x.id === video.id)) videoTray.value.push(video) }
async function resetPlan() { while (draft.value.segments.length) removeSegment(0); selectedTargets.value = []; await preview(true) }
function targets() { const values = draft.value.segments.filter(s => selectedTargets.value.includes(s.id)); if (!values.length) ElMessage.warning('请先选择目标片段'); return values }
function mountImage(image: ShotRefImage) { for (const s of targets()) if (!s.referenceImages.some(x => x.id === image.id)) s.referenceImages.push(clone(image)) }
function mountAnchor(image: ShotRefImage, first: boolean) { for (const s of targets()) if (first) s.firstFrameImageUrl = image.imageUrl; else s.lastFrameImageUrl = image.imageUrl }
function mountVideo(video: ShotRefVideo) { for (const s of targets()) if (!s.referenceVideos.some(x => x.id === video.id)) s.referenceVideos.push(clone(video)) }
async function splitVideo(video: ShotRefVideo, targetIds?: string[]) {
  const before = revision, shotId = props.shotId
  const value = await continuationApi.splitReference(shotId, clone(draft.value), clone(video), targetIds)
  if (!visible.value || props.shotId !== shotId || before !== revision) throw new Error('分段计划已变化，请重新切分参考视频')
  draft.value = value.draft
  return value.segmentCount
}
async function distributeVideo(video: ShotRefVideo) { busy.value = true; try { const count = await splitVideo(video, selectedTargets.value); ElMessage.success(`已按当前计划绑定参考视频，共 ${selectedTargets.value.length || count} 段`) } catch (e: any) { ElMessage.error(e.message || '切分参考视频失败') } finally { busy.value = false } }
function clearFrameRange(video: ShotRefVideo) { delete video.sourceStartFrame; delete video.sourceEndFrame }
function removeVideo(s: ContinuationSegment, index: number) { const removed = s.referenceVideos.splice(index, 1)[0]; if (removed?.id === s.controlVideoId) s.controlVideoId = undefined; if (!s.referenceVideos.length) s.controlNetEnabled = false }
async function uploadAnchor(file: File, segment: ContinuationSegment, first: boolean) {
  busy.value = true
  try { const result = await assetApi.upload(file, 'continuation'); if (first) segment.firstFrameImageUrl = result.url; else segment.lastFrameImageUrl = result.url }
  catch (e: any) { ElMessage.error(e.message || '上传失败') } finally { busy.value = false } return false
}
async function upload(file: File, segment: ContinuationSegment, kind: 'image' | 'video') {
  busy.value = true
  try { const result = await assetApi.upload(file, 'continuation'); if (kind === 'image') segment.referenceImages.push({ id: crypto.randomUUID(), sourceType: 'UPLOAD', imageUrl: result.url, name: file.name } as ShotRefImage); else segment.referenceVideos.push({ id: crypto.randomUUID(), name: file.name, sourceType: 'DIRECT_URL', sourceVideoUrl: result.url, startSeconds: 0, endSeconds: Math.min(15, (segment.endFrame - segment.startFrame) / 24), includeAudio: false }) }
  catch (e: any) { ElMessage.error(e.message || '上传失败') } finally { busy.value = false } return false
}
function chooseVideo(segment?: ContinuationSegment) { videoTarget.value = segment; videoPicker.value?.open() }
async function addVideoSource(video: ShotRefVideo, target?: ContinuationSegment) {
  if (!target && draft.value.autoSplitReferenceVideo) {
    videoTray.value.push(video)
    try {
      const count = await splitVideo(video)
      Object.assign(video, draft.value.videoSources?.find(v => v.id === video.id))
      ElMessage.success(`参考视频已按当前计划自动分为 ${count} 段，可在各段查看源范围`)
    } catch (e) {
      draft.value.videoSources ||= []; draft.value.videoSources.push(clone(video))
      throw e
    }
    return
  }
  const shotId = props.shotId; const measured = await shotVideoReferenceApi.probe(shotId, video)
  if (!visible.value || props.shotId !== shotId) return
  video.endSeconds = target ? Math.min(measured.duration || 10, (target.endFrame - target.startFrame) / 24) : measured.duration
  if (target) target.referenceVideos.push(video)
  else {
    videoTray.value.push(video)
    draft.value.videoSources ||= []; draft.value.videoSources.push(clone(video))
  }
}
async function uploadTrayVideo(file: File) {
  busy.value = true
  try { const result = await assetApi.upload(file, 'continuation'); await addVideoSource({ id: crypto.randomUUID(), sourceType: 'DIRECT_URL', sourceVideoUrl: result.url, name: file.name, startSeconds: 0, includeAudio: false }) }
  catch (e: any) { ElMessage.error(e.message || '视频读取失败') } finally { busy.value = false } return false
}
async function selectedVideo(source: { sourceType: ShotRefVideo['sourceType']; sourceVideoUrl: string; sourceShotId?: string; sourceVideoTakeId?: string; shotName?: string }) {
  const target = videoTarget.value; busy.value = true
  try { await addVideoSource({ id: crypto.randomUUID(), name: source.shotName, sourceType: source.sourceType, sourceVideoUrl: source.sourceType === 'DIRECT_URL' ? source.sourceVideoUrl : undefined, sourceShotId: source.sourceShotId, sourceVideoTakeId: source.sourceType === 'SHOT_VIDEO_TAKE' ? source.sourceVideoTakeId : undefined, startSeconds: 0, includeAudio: false }, target) }
  catch (e: any) { ElMessage.error(e.message || '视频读取失败') } finally { busy.value = false }
}
async function save() { busy.value = true; try { await continuationApi.save(props.shotId, clone(draft.value)); emit('saved'); ElMessage.success('接续草稿已保存') } catch (e: any) { ElMessage.error(e.message || '保存失败') } finally { busy.value = false } }
async function render() {
  const accepted = plan.value; if (!accepted?.renderable) return
  busy.value = true
  try {
    const inputs = JSON.stringify([props.shotId, draft.value, providerId.value, modelCode.value, size.value])
    await continuationApi.save(props.shotId, clone(draft.value))
    let prepared = await continuationApi.prepare(props.shotId, providerId.value, modelCode.value, size.value, accepted.planHash)
    const deadline = Date.now() + 15 * 60 * 1000
    while (prepared.status === 'PREPARING') {
      if (!visible.value || inputs !== JSON.stringify([props.shotId, draft.value, providerId.value, modelCode.value, size.value])) throw new Error('配置已变化，准备结果保留，请重新预览后提交')
      if (Date.now() > deadline) throw new Error('素材仍在后台准备，可稍后重新提交复用准备结果')
      await new Promise(resolve => window.setTimeout(resolve, 1200)); prepared = await continuationApi.preparation(props.shotId, prepared.id)
    }
    if (prepared.status !== 'READY') throw new Error(prepared.errorMessage || '素材准备失败')
    if (!visible.value || inputs !== JSON.stringify([props.shotId, draft.value, providerId.value, modelCode.value, size.value])) throw new Error('配置已变化，请重新预览')
    const result = await shotApi.submitRender(props.shotId, { providerId: providerId.value, workflowTemplateId: modelCode.value, size: size.value, expectedRenderFingerprint: accepted.planHash }); emit('saved'); ElMessage.success(`接续任务 ${result.taskId} 已加入任务中心`); visible.value = false
  }
  catch (e: any) { ElMessage.error(e.message || '提交失败') } finally { busy.value = false }
}
</script>
