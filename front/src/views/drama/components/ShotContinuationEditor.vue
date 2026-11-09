<template>
  <div class="continuation-workbench" v-loading="busy || !ready">
    <div class="continuation-scroll">
      <section class="continuation-master">
        <div class="master-heading"><div><span class="eyebrow">单镜头 · 接续创作</span><h2>接续总控</h2><p>先规划时间轴，再逐段编写内容和挂载参考。</p></div><div class="flex gap-2"><el-button :disabled="!draft.segments.length" @click="planningDialog?.open()">✦ AI 自动规划</el-button><el-button type="primary" plain :loading="previewing" @click="preview(true)">自动分段</el-button></div></div>
        <ContinuationModeSelector v-model="draft.continuity" :overlap-frames="draft.overlapFrames" :capabilities="plan?.continuationModes" @update:model-value="draft.schemaVersion = 2" />
        <div class="master-fields"><label>成片总时长（秒）<el-input-number v-model="draft.durationSeconds" :min="0.5" :max="300" :step="0.5" /></label><label>每段生成时长<el-select :model-value="selectedPreset" @change="applyPreset"><el-option v-for="p in presets" :key="p.id" :value="p.id" :label="p.label" /><el-option value="custom" label="自定义帧数" /></el-select></label><label>段长（帧）<el-input-number v-model="draft.segmentFrames" :min="124" :max="362" :step="17" /></label><label>共享上下文（帧）<el-input-number v-model="draft.overlapFrames" :min="5" :max="141" :step="17" /></label></div>
        <div class="timeline" aria-label="接续片段时间轴"><button v-for="(s,i) in draft.segments" :key="s.id" :class="{selected:activeId===s.id}" :style="{flex: Math.max(1,s.endFrame-s.startFrame)}" @click="activeId=s.id"><b>{{ String(i+1).padStart(2,'0') }}</b><span>{{ seconds(s.startFrame) }}–{{ seconds(s.endFrame) }}s</span><small>{{ i ? modeLabel(s.continuityOverride?.mode || draft.continuity?.mode) : '起始段' }}</small></button><p v-if="!draft.segments.length">点击自动分段，预览接续计划</p></div>
        <div class="master-summary"><span>{{ draft.segments.length }} 个片段 · {{ Math.max(0,draft.segments.length-1) }} 个接续点 · {{ draft.durationSeconds }} 秒成片</span><div class="flex gap-2"><el-button text size="small" @click="appendSegment">＋ 手动加段</el-button><el-button text size="small" @click="preview(false)">校验时间轴</el-button><el-button text size="small" @click="resetPlan">重建</el-button></div></div>
        <el-alert v-if="planError" :title="planError" type="warning" :closable="false" /><el-alert v-for="issue in plan?.issues || []" :key="issue" :title="issue" type="warning" :closable="false" />
        <el-collapse><el-collapse-item title="参考素材分配与接续选项" name="sources"><div class="space-y-3"><div class="flex gap-3 flex-wrap"><el-checkbox v-model="draft.contextWindows">启用上下文窗口（渲染时校验模型支持）</el-checkbox><el-checkbox v-if="lastTaskId" :model-value="!!draft.reuseFromTaskId" @change="(value: boolean | string | number) => draft.reuseFromTaskId = value ? lastTaskId : undefined">复用输入未变的已生成片段</el-checkbox></div><div v-if="generationMode === 'REFERENCE_MODE'" class="flex gap-3 flex-wrap items-center"><el-checkbox v-model="draft.autoSplitReferenceVideo">上传后按当前时间轴自动切分参考视频</el-checkbox><el-upload :show-file-list="false" accept="video/*" :before-upload="uploadTrayVideo"><el-button size="small">上传完整参考视频</el-button></el-upload><el-button size="small" @click="chooseVideo()">选择分镜 / Take 视频</el-button></div><p class="text-xs text-[var(--text-muted)]">素材只用于明确指定的片段；长视频按各段真实重叠范围切分，源时长不足会提示。</p><div v-if="imageTray.length || videoTray.length"><el-select v-model="selectedTargets" multiple placeholder="选择素材目标片段（未选时视频按全时间轴分配）" class="w-full"><el-option v-for="(s,i) in draft.segments" :key="s.id" :value="s.id" :label="`片段 ${i+1} · ${s.name || '未命名'}`" /></el-select><div v-for="img in imageTray" :key="img.id || img.imageUrl" class="tray-row"><el-image :src="img.imageUrl" fit="cover" class="w-10 h-10 rounded" /><span>{{ img.name }}</span><template v-if="generationMode === 'FIRST_LAST_FRAME'"><el-button size="small" @click="mountAnchor(img,true)">设为首帧</el-button><el-button size="small" @click="mountAnchor(img,false)">设为尾帧</el-button></template><el-button v-else size="small" @click="mountImage(img)">挂载所选段</el-button></div><div v-for="video in videoTray" :key="video.id" class="tray-row"><span>{{ video.name || '参考视频' }}</span><el-input-number v-model="video.startSeconds" :min="0" :step="0.5" /><span>至</span><el-input-number v-model="video.endSeconds" :min="0.1" :step="0.5" /><el-button size="small" :disabled="generationMode !== 'REFERENCE_MODE'" @click="mountVideo(video)">挂载所选源范围</el-button><el-button size="small" :disabled="generationMode !== 'REFERENCE_MODE'" @click="distributeVideo(video)">按时间轴切分挂载</el-button></div></div></div></el-collapse-item></el-collapse>
      </section>
      <div class="continuation-editors"><aside class="segment-nav"><div class="nav-heading">接续片段 <span>{{ draft.segments.length }}</span></div><button v-for="(s,i) in draft.segments" :key="s.id" :class="{active:activeId===s.id}" @click="activeId=s.id"><span class="segment-no">{{ String(i+1).padStart(2,'0') }}</span><div><strong>{{ s.name || `片段 ${i+1}` }}</strong><small>{{ seconds(s.startFrame) }}–{{ seconds(s.endFrame) }} 秒</small><small>{{ s.locked ? '已锁定' : s.scriptContent ? '已编写剧本' : '待完善内容' }}</small></div></button></aside><main class="segment-panel"><template v-if="activeSegment"><div class="segment-panel-heading"><strong>{{ activeSegment.name || `片段 ${draft.segments.indexOf(activeSegment)+1}` }}</strong><div><el-checkbox v-model="activeSegment.locked">锁定自动规划</el-checkbox><el-button text type="danger" size="small" @click="confirmRemove(draft.segments.indexOf(activeSegment))">移除</el-button></div></div><ContinuationSegmentEditor :key="activeSegment.id" :segment="activeSegment" :continuity="draft.continuity" :continuation-modes="plan?.continuationModes" :anchor-segments="draft.segments.slice(0,draft.segments.indexOf(activeSegment))" :index="draft.segments.indexOf(activeSegment)" :range="plan?.ranges.find(r=>r.segmentId===activeId)" :generation-mode="generationMode" :shot-id="shotId" :drama-id="dramaId" :characters="characters" :scenes="scenes" :props-options="propsOptions" :keyframes="keyframes" :active-section="sections[activeId] || 'script'" @update:active-section="sections[activeId]=$event" @plan="planOne" /></template><el-empty v-else description="先自动分段或添加片段" /></main></div>
    </div>
    <footer class="continuation-footer"><span>每个片段独立配置 · 上一段尾部作为接续上下文</span><div><el-button :disabled="!ready" @click="emit('request-save')">保存接续草稿</el-button><el-button type="primary" :disabled="!draft.segments.length" @click="emit('request-render')">配置并渲染</el-button></div></footer>
    <ShotVideoPickerDialog ref="videoPicker" @selected="selectedVideo" /><ShotContinuationPlanDialog ref="planningDialog" :segments="draft.segments" :busy="busy" @submit="planPrompts" /><ShotVideoRenderDialog ref="renderDialog" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { continuationApi } from '@/api/continuation'
import { shotApi } from '@/api/drama'
import { shotVideoReferenceApi } from '@/api/shot-video-reference'
import { runAiTaskResult } from '@/api/ai-task-stream'
import { assetApi } from '@/api/res-asset'
import type { DramaShot, ShotRefImage, ShotRefVideo } from '@/types/drama'
import type { ContinuationDraft, ContinuationPlan, ContinuationPreset, ContinuationSegment } from '@/types/continuation'
import type { ResCharacterOption, ResSceneOption, ResPropOption, ResKeyframeOption } from '@/types/resource'
import ContinuationModeSelector from './ContinuationModeSelector.vue'
import ContinuationSegmentEditor from './ContinuationSegmentEditor.vue'
import ShotContinuationPlanDialog from './ShotContinuationPlanDialog.vue'
import ShotVideoRenderDialog, { type VideoRenderConfiguration } from './ShotVideoRenderDialog.vue'
import ShotVideoPickerDialog from '@/views/production/components/ShotVideoPickerDialog.vue'
const props = defineProps<{ shotId: string; enabled: boolean; generationMode: string; aspectRatio?: string; dramaId?: string | number; characters: ResCharacterOption[]; scenes: ResSceneOption[]; propsOptions: ResPropOption[]; keyframes: ResKeyframeOption[]; sourceImages?: ShotRefImage[]; sourceVideos?: ShotRefVideo[]; sourceFirst?: string; sourceLast?: string }>()
const emit = defineEmits<{ (event: 'saved' | 'request-render' | 'request-save'): void }>()
const busy = ref(false), previewing = ref(false), ready = ref(false), planError = ref('')
const draft = ref<ContinuationDraft>({ schemaVersion: 2, enabled: true, durationSeconds: 15, segmentFrames: 124, overlapFrames: 22, contextWindows: false, autoSplitReferenceVideo: true, videoSources: [], segments: [] })
const presets = ref<ContinuationPreset[]>([])
const selectedPreset = computed(() => presets.value.find(p => p.segmentFrames === draft.value.segmentFrames && p.overlapFrames === draft.value.overlapFrames)?.id || 'custom')
const plan = ref<ContinuationPlan>(), imageTray = ref<ShotRefImage[]>([]), videoTray = ref<ShotRefVideo[]>([]), selectedTargets = ref<string[]>([])
const lastTaskId = ref<string>(), activeId = ref(''), sections = ref<Record<string,string>>({})
const activeSegment = computed(() => draft.value.segments.find(s => s.id === activeId.value))
const generationMode = computed(() => props.generationMode)
const videoPicker = ref<InstanceType<typeof ShotVideoPickerDialog>>(), videoTarget = ref<ContinuationSegment>()
const planningDialog = ref<InstanceType<typeof ShotContinuationPlanDialog>>(), renderDialog = ref<InstanceType<typeof ShotVideoRenderDialog>>()
let alive = true, revision = 0, previewRequest = 0, applyingPreview = false
let previewTimer: ReturnType<typeof setTimeout> | undefined
function modeLabel(mode?: string) { return ({ GUIDE: '标准引导', GUIDE_CHROMA: '彩噪引导', LATENT_REDRAW: '引导＋重绘', LATENT_PROTECTED: '抗劣化接续' } as Record<string,string>)[mode || 'GUIDE'] }
function normalize(value: ContinuationDraft) { value.continuity ??= { mode: 'GUIDE' }; value.videoSources ??= []; value.autoSplitReferenceVideo ??= true; value.segments.forEach(s => { s.characterRefs ??= []; s.propRefs ??= []; s.referenceAudios ??= []; s.referenceImages ??= []; s.referenceVideos ??= [] }); return value }
watch(draft, () => {
  if (applyingPreview || !ready.value) return
  revision++; plan.value = undefined; planError.value = ''; clearTimeout(previewTimer)
  if (props.enabled && !busy.value) previewTimer = setTimeout(() => preview(false), 650)
}, { deep: true, flush: 'sync' })
watch(busy, value => { if (!value && alive && ready.value && props.enabled && !plan.value) { clearTimeout(previewTimer); previewTimer = setTimeout(() => preview(false), 650) } })
watch(() => props.enabled, value => {
  draft.value.enabled = value
  if (value && ready.value) { syncParentSources(); void preview(!draft.value.segments.length) }
})
function syncParentSources() {
  for (const image of props.sourceImages || []) if (!imageTray.value.some(v => v.id === image.id || v.imageUrl === image.imageUrl)) imageTray.value.push(clone(image))
  for (const video of props.sourceVideos || []) if (!videoTray.value.some(v => v.id === video.id)) videoTray.value.push(clone(video))
  for (const [url,name] of [[props.sourceFirst,'镜头原首帧'],[props.sourceLast,'镜头原尾帧']]) if (url && !imageTray.value.some(v => v.imageUrl === url)) imageTray.value.push({ id: crypto.randomUUID(), sourceType: 'UPLOAD', imageUrl: url, name: name! })
}
watch(() => props.generationMode, () => { revision++; plan.value = undefined })
onBeforeUnmount(() => { alive = false; revision++; clearTimeout(previewTimer) })
const seconds = (frames: number) => (frames / 24).toFixed(2)
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value))
async function load() {
  busy.value = true
  try {
    const [saved, shot, profiles] = await Promise.all([continuationApi.get(props.shotId), shotApi.getById(props.shotId), continuationApi.presets(props.shotId)])
    if (!alive) return
    draft.value = normalize(saved); draft.value.enabled = props.enabled; presets.value = profiles
    imageTray.value = clone(saved.imageSources || shot.refImages || [])
    if (shot.previewImageUrl && !imageTray.value.some(i => i.imageUrl === shot.previewImageUrl)) imageTray.value.push({ id: 'shot-first-frame', sourceType: 'UPLOAD', imageUrl: shot.previewImageUrl, name: '镜头原首帧' })
    if (shot.endFrameImageUrl && !imageTray.value.some(i => i.imageUrl === shot.endFrameImageUrl)) imageTray.value.push({ id: 'shot-last-frame', sourceType: 'UPLOAD', imageUrl: shot.endFrameImageUrl, name: '镜头原尾帧' })
    videoTray.value = clone(saved.videoSources || [])
    for (const video of shot.refVideos || []) if (!videoTray.value.some(v => v.id === video.id)) videoTray.value.push(clone(video))
    syncParentSources(); lastTaskId.value = shot.latestTaskId; activeId.value = saved.segments[0]?.id || ''; ready.value = true
  } catch (e: any) { ElMessage.error(e.message || '读取接续配置失败') } finally { busy.value = false }
  if (props.enabled && ready.value) await preview(!draft.value.segments.length)
}
void load()
async function applyPreset(id: string) {
  const value = presets.value.find(p => p.id === id); if (!value) return
  const candidate = clone(draft.value); candidate.segmentFrames = value.segmentFrames; candidate.overlapFrames = value.overlapFrames
  await preview(true, candidate)
}
async function preview(auto: boolean, candidate?: ContinuationDraft) {
  clearTimeout(previewTimer); if (!ready.value) return
  previewing.value = true; const before = revision, request = ++previewRequest
  try {
    const value = await continuationApi.plan(props.shotId, clone(candidate || draft.value), auto)
    if (!alive || before !== revision || request !== previewRequest) return
    applyingPreview = true
    try { draft.value = normalize(value.draft); plan.value = value; if (!draft.value.segments.some(s => s.id === activeId.value)) activeId.value = draft.value.segments[0]?.id || '' } finally { applyingPreview = false }
  } catch (e: any) { if (before === revision && request === previewRequest) { planError.value = e.message || '计划校验失败'; if (auto) ElMessage.error(planError.value) } }
  finally { if (request === previewRequest) previewing.value = false }
}
async function planPrompts(options: { providerId: string; modelCode: string; selectedSegmentIds?: string[]; requirements: string }) {
  const before = revision; busy.value = true
  try {
    const result = await runAiTaskResult<ContinuationDraft>(`/drama/shot/${props.shotId}/continuation/plan-task`, { draft: clone(draft.value), ...options })
    if (!alive || before !== revision) throw new Error('计划已修改；AI 结果保留在任务中心，本次未覆盖编辑')
    draft.value = normalize(result); planningDialog.value?.close()
  } catch (e: any) { ElMessage.error(e.message || 'AI 规划失败') } finally { busy.value = false }
  if (alive) await preview(false)
}
function appendSegment() { const start = draft.value.segments.length ? draft.value.segments.at(-1)!.endFrame - draft.value.overlapFrames : 0; draft.value.segments.push({ id: crypto.randomUUID(), startFrame: start, endFrame: start + draft.value.segmentFrames, prompt: '', locked: false, characterRefs: [], propRefs: [], referenceAudios: [], referenceImages: [], referenceVideos: [], controlNetEnabled: false, controlType: 'CANNY', controlStrength: 1 }) }
function removeSegment(index: number) { const s = draft.value.segments.splice(index, 1)[0]; if (!s) return; for (const image of s.referenceImages) if (!imageTray.value.some(x => x.id === image.id)) imageTray.value.push(image); for (const url of [s.firstFrameImageUrl, s.lastFrameImageUrl]) if (url && !imageTray.value.some(x => x.imageUrl === url)) imageTray.value.push({ id: crypto.randomUUID(), sourceType: 'UPLOAD', imageUrl: url, name: '片段锚点' } as ShotRefImage); for (const video of s.referenceVideos) if (!videoTray.value.some(x => x.id === video.id)) videoTray.value.push(video) }
async function resetPlan() { try { await ElMessageBox.confirm('重建会清除各段剧本、导演与绑定，图片和视频退回待分配。是否继续？', '重建分段计划', { type: 'warning' }) } catch { return }; while (draft.value.segments.length) removeSegment(0); selectedTargets.value = []; await preview(true) }
function targets() { const values = draft.value.segments.filter(s => selectedTargets.value.includes(s.id)); if (!values.length) ElMessage.warning('请先选择目标片段'); return values }
function mountImage(image: ShotRefImage) { for (const s of targets()) if (!s.referenceImages.some(x => x.id === image.id)) s.referenceImages.push({ ...clone(image), bindingMode: 'SNAPSHOT' }) }
function mountAnchor(image: ShotRefImage, first: boolean) { for (const s of targets()) if (first) s.firstFrameImageUrl = image.imageUrl; else s.lastFrameImageUrl = image.imageUrl }
function mountVideo(video: ShotRefVideo) { for (const s of targets()) if (!s.referenceVideos.some(x => x.id === video.id)) s.referenceVideos.push(clone(video)) }
async function splitVideo(video: ShotRefVideo, targetIds?: string[]) {
  const before = revision, shotId = props.shotId
  const value = await continuationApi.splitReference(shotId, clone(draft.value), clone(video), targetIds)
  if (!alive || props.shotId !== shotId || before !== revision) throw new Error('分段计划已变化，请重新切分参考视频')
  draft.value = normalize(value.draft)
  return value.segmentCount
}
async function distributeVideo(video: ShotRefVideo) { busy.value = true; try { const count = await splitVideo(video, selectedTargets.value); ElMessage.success(`已按当前计划绑定参考视频，共 ${selectedTargets.value.length || count} 段`) } catch (e: any) { ElMessage.error(e.message || '切分参考视频失败') } finally { busy.value = false } }
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
  if (!alive || props.shotId !== shotId) return
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
async function save(enabled = props.enabled, control?: NonNullable<ContinuationDraft['controlNet']>): Promise<boolean> {
  if (!ready.value || busy.value) { ElMessage.warning('接续配置仍在加载或处理中，请稍后保存'); return false }
  busy.value = true
  try { draft.value.enabled = enabled; if (control) draft.value.controlNet = clone(control); draft.value.videoSources = clone(videoTray.value); draft.value.imageSources = clone(imageTray.value); await continuationApi.save(props.shotId, clone(draft.value)); emit('saved'); return true }
  catch (e: any) { ElMessage.error(e.message || '保存失败'); return false } finally { busy.value = false }
}
async function openRender() {
  if (!ready.value || !draft.value.segments.length) return ElMessage.warning('请先生成分段计划')
  const shot = await shotApi.getById(props.shotId)
  renderDialog.value?.open({ ...shot, generationMode: props.generationMode as DramaShot['generationMode'], duration: draft.value.durationSeconds }, props.aspectRatio, render, draft.value.segments.length)
}
async function render(config: VideoRenderConfiguration, onProgress: (message: string) => void) {
  onProgress('正在保存接续计划…')
  if (!await save(true)) throw new Error('请先保存接续计划')
  busy.value = true
  try {
    const before = revision
    onProgress('正在检查模型与接续计划…')
    const accepted = await continuationApi.preview(props.shotId, clone(draft.value), false, config.providerId, config.modelCode)
    if (before !== revision || !alive || !props.enabled) throw new Error('计划已变化，请重新提交')
    plan.value = accepted
    if (!accepted.renderable) throw new Error(accepted.issues.join('；') || '当前模型不支持此计划')
    onProgress('正在准备接续素材…')
    let prepared = await continuationApi.prepare(props.shotId, config.providerId, config.modelCode, config.size, accepted.planHash)
    const deadline = Date.now() + 15 * 60 * 1000
    while (prepared.status === 'PREPARING') {
      onProgress(prepared.progressMessage || '正在准备接续素材…')
      if (!alive || before !== revision || !props.enabled) throw new Error('配置已变化，准备结果保留，请重新提交')
      if (Date.now() > deadline) throw new Error('素材仍在后台准备，可稍后重新提交复用准备结果')
      await new Promise(resolve => setTimeout(resolve, 1200)); prepared = await continuationApi.preparation(props.shotId, prepared.id)
    }
    if (prepared.status !== 'READY') throw new Error(prepared.errorMessage || '素材准备失败')
    if (!alive || before !== revision || !props.enabled) throw new Error('配置已变化，请重新提交')
    onProgress('素材已就绪，正在提交渲染任务…')
    await shotApi.submitRender(props.shotId, { providerId: config.providerId, workflowTemplateId: config.modelCode, size: config.size, expectedRenderFingerprint: accepted.planHash }); emit('saved')
  } finally { busy.value = false }
}
defineExpose({ save, openRender, isReady: () => ready.value })

async function confirmRemove(index: number) { try { await ElMessageBox.confirm('移除此段会清除本段文字、导演与音频配置，图片和视频退回待分配。', '移除片段', { type: 'warning' }); removeSegment(index); activeId.value = draft.value.segments[Math.max(0,index-1)]?.id || '' } catch {} }
function planOne() { planningDialog.value?.open(activeId.value) }
</script>
<style scoped>
.continuation-workbench{display:flex;flex:1;min-height:0;flex-direction:column;background:var(--surface-muted,#f7f8fb)}.continuation-scroll{flex:1;min-height:0;overflow:auto;padding:20px}.continuation-master{background:var(--surface,#fff);border:1px solid var(--border-default,#e5e7eb);border-radius:12px;padding:20px;margin-bottom:18px}.master-heading{display:flex;justify-content:space-between;align-items:center;gap:16px}.eyebrow{font-size:10px;color:var(--brand);letter-spacing:1px}h2{font-size:19px;font-weight:650;margin:5px 0}.master-heading p{font-size:12px;color:var(--text-muted);margin:0}.master-fields{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:18px;margin:22px 0}.master-fields label{display:grid;gap:8px;font-size:12px;color:var(--text-secondary)}.master-fields .el-input-number,.master-fields .el-select{width:100%}.timeline{display:flex;gap:5px;overflow:auto}.timeline button{min-width:88px;background:var(--brand-soft,#f3efff);border:1px solid var(--border-default);color:var(--brand,#7c3aed);border-radius:7px;display:grid;gap:5px;padding:10px}.timeline button.selected{background:var(--brand,#7c3aed);color:white}.timeline span{font-size:10px;white-space:nowrap}.master-summary{display:flex;justify-content:space-between;align-items:center;gap:12px;margin:10px 0;color:var(--text-secondary);font-size:12px}.continuation-editors{display:grid;grid-template-columns:210px minmax(0,1fr);gap:18px;align-items:start}.segment-nav,.segment-panel{background:var(--surface,#fff);border:1px solid var(--border-default,#e5e7eb);border-radius:12px;overflow:hidden}.nav-heading{font-size:12px;font-weight:600;padding:18px;border-bottom:1px solid var(--border-default)}.nav-heading span{float:right;color:var(--text-muted)}.segment-nav button{display:flex;gap:12px;padding:16px;width:100%;text-align:left;border-left:3px solid transparent;border-bottom:1px solid var(--border-default)}.segment-nav button.active{background:var(--brand-soft,#f5f1ff);border-left-color:var(--brand)}.segment-no{font-family:monospace;color:var(--brand);font-size:13px}.segment-nav strong{display:block;font-size:12px}.segment-nav small{display:block;font-size:10px;color:var(--text-muted);margin-top:6px}.segment-panel-heading{display:flex;justify-content:space-between;align-items:center;padding:14px 20px;border-bottom:1px solid var(--border-default);font-size:13px}.continuation-footer{display:flex;justify-content:space-between;align-items:center;padding:12px 20px;background:var(--surface);border-top:1px solid var(--border-default);font-size:12px;color:var(--text-muted)}.tray-row{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin:12px 0;font-size:12px}.tray-row .el-input-number{width:135px}@media(max-width:900px){.master-fields{grid-template-columns:repeat(2,1fr)}.continuation-editors{grid-template-columns:1fr}.segment-nav{display:flex;overflow:auto}.segment-nav button{min-width:160px}.nav-heading{display:none}.master-heading,.master-summary{flex-wrap:wrap}}@media(max-width:600px){.continuation-scroll{padding:10px}.continuation-footer>span{display:none}.master-fields{grid-template-columns:1fr}}
</style>
