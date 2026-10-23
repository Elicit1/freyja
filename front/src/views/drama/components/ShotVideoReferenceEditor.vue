<template>
  <section class="space-y-2 pt-2 border-t border-sky-100">
    <div class="flex flex-wrap items-center justify-between gap-2 text-xs">
      <span class="font-bold text-gray-700">参考视频列表 ({{ modelValue.length }}{{ countLimit == null ? '' : ` / ${countLimit}` }}):</span>
      <div class="flex flex-wrap items-center justify-end gap-2">
        <el-upload :show-file-list="false" :before-upload="upload" accept="video/mp4,video/webm,video/quicktime,video/x-matroska" :disabled="!canAdd || uploading">
          <el-button size="small" type="primary" plain :loading="uploading" :disabled="!canAdd">上传视频</el-button>
        </el-upload>
        <el-button size="small" type="primary" plain :disabled="!canAdd" @click="materialPicker?.open(dramaId)">+ 从视频素材库选取</el-button>
      </div>
    </div>
    <p v-if="notice" class="text-xs text-slate-500">{{ notice }}</p>
    <el-alert v-if="countLimit != null && modelValue.length > countLimit" :title="`已有 ${modelValue.length} 段视频，超过当前模型配置的 ${countLimit} 段；请移除多余引用或更换模型。`" type="warning" :closable="false" />
    <el-collapse v-if="modelValue.length" v-model="expanded" class="reference-video-list !border-0 space-y-2">
      <el-collapse-item v-for="(item, index) in modelValue" :key="item.id" :name="item.id" class="border border-slate-200 rounded-lg overflow-hidden bg-white">
        <template #title>
          <div class="flex flex-wrap items-center gap-2 flex-1 pr-3 text-xs">
            <strong class="font-mono text-sky-700 bg-sky-100 px-1.5 py-0.5 rounded text-[11px]">Video {{ index + 1 }}</strong>
            <span class="truncate max-w-52">{{ item.name || '视频参考' }}</span>
            <span class="text-slate-500">{{ item.startSeconds ?? 0 }} ～ {{ item.endSeconds ?? '源片结尾' }} 秒</span>
            <div class="ml-auto flex gap-1" @click.stop @keydown.stop>
              <el-button size="small" link :disabled="disabled || index === 0" @click="move(index, -1)">上移</el-button>
              <el-button size="small" link :disabled="disabled || index === modelValue.length - 1" @click="move(index, 1)">下移</el-button>
              <el-button size="small" link type="danger" :disabled="disabled" @click="remove(item.id)">移除</el-button>
            </div>
          </div>
        </template>
        <div v-if="expanded.includes(item.id)" class="space-y-3 bg-white p-3">
          <p v-if="item.sourceShotId" class="text-xs text-slate-500 break-all">来源分镜 {{ item.sourceShotId }} · {{ item.sourceVideoTakeId ? `Take ${item.sourceVideoTakeId}` : '预检时固定当前 Take' }}</p>
          <video v-if="sourceInfo[item.id]?.url" :ref="element => setPlayer(item.id, element)" :src="sourceInfo[item.id]?.url" controls preload="metadata" class="w-full h-44 bg-black rounded" @timeupdate="limitPlayback(item)" />
          <div v-loading="sourceInfo[item.id]?.probing" class="flex flex-wrap gap-4 items-center text-xs">
            <label>起点（秒）<el-input-number :model-value="item.startSeconds ?? 0" :disabled="disabled" :min="0" :step="0.1" :precision="2" size="small" @update:model-value="patch(item.id, { startSeconds: $event ?? undefined })" /></label>
            <label>终点（秒）<el-input-number :model-value="item.endSeconds" :disabled="disabled" :min="0.01" :step="0.1" :precision="2" size="small" placeholder="源片结尾" @update:model-value="patch(item.id, { endSeconds: $event ?? undefined })" /></label>
            <el-button size="small" :disabled="!sourceInfo[item.id]?.url" @click="playRange(item)">预览所选范围</el-button>
          </div>
          <p v-if="sourceInfo[item.id]?.metadata" class="text-xs text-slate-500">源视频 {{ sourceInfo[item.id]?.metadata?.duration }} 秒 · {{ sourceInfo[item.id]?.metadata?.width }} × {{ sourceInfo[item.id]?.metadata?.height }} · {{ sourceInfo[item.id]?.metadata?.fps }} FPS · {{ sourceInfo[item.id]?.metadata?.hasAudio ? '有音轨' : '无音轨' }}</p>
          <p v-if="option?.referenceVideo.maxClipSeconds" class="text-xs text-slate-500">单段上限 {{ option.referenceVideo.maxClipSeconds }} 秒；全部片段总长上限 {{ option.referenceVideo.maxTotalClipSeconds }} 秒。</p>
          <el-checkbox :model-value="item.includeAudio === true" :disabled="disabled || !option?.referenceVideo.supportsVideoAudio && !item.includeAudio" @update:model-value="patch(item.id, { includeAudio: $event === true })">使用视频原音作为生成参考</el-checkbox>
          <p class="text-xs text-slate-500">{{ purpose === 'draft' || purpose === 'prompt' ? '默认仅参考画面；如需参考原音，请在渲染工坊选择支持该能力的模型后开启。' : '默认仅参考画面。原音不会直接混入结果；开启前需模型支持且源片含音轨。' }}</p>
          <el-input :model-value="item.referenceDescription" :disabled="disabled" type="textarea" :rows="2" maxlength="1000" show-word-limit :placeholder="`明确参考什么，例如：参考 Video ${index + 1} 的推进运镜和起身节奏；人物外观使用 Picture 1。`" @update:model-value="patch(item.id, { referenceDescription: $event })" />
          <video v-if="preflight?.status === 'READY' && preflight.previewUrls?.[index]" :src="preflight.previewUrls[index]" controls preload="metadata" class="w-full h-44 bg-black rounded" />
        </div>
      </el-collapse-item>
    </el-collapse>
    <el-button v-if="modelValue.length && purpose !== 'prompt'" size="small" type="primary" plain :disabled="disabled || countLimit != null && modelValue.length > countLimit" :loading="preparing" @click="emit('prepare')">{{ purpose === 'draft' ? '保存并进入渲染工坊' : '保存并预检全部参考片段' }}</el-button>
    <el-alert v-if="preflight?.status === 'READY'" title="全部片段已准备完成。生成前还会检查模型与视频版本。" type="success" :closable="false" />
    <el-alert v-if="preflight?.errorMessage" :title="preflight.errorMessage" type="error" :closable="false" />
    <div v-if="!modelValue.length" class="text-center py-4 text-xs text-gray-400 bg-gray-50 rounded border border-dashed border-gray-200">暂未绑定参考视频，点击上方按钮上传或从视频素材库选取</div>
    <VideoMaterialPickerDialog ref="materialPicker" @selected="selectMaterial" />
  </section>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onBeforeUnmount, type ComponentPublicInstance } from 'vue'
import { ElMessage } from 'element-plus'
import { videoAssetApi } from '@/api/res-video'
import type { ResVideo } from '@/types/resource'
import VideoMaterialPickerDialog from '@/views/assets/components/VideoMaterialPickerDialog.vue'
import type { VideoProbeInfoVO } from '@/api/videoProcessing'
import { shotVideoReferenceApi } from '@/api/shot-video-reference'
import type { ShotRefVideo, ShotVideoModelOption, VideoReferencePreflight } from '@/types/drama'

const props = defineProps<{ modelValue: ShotRefVideo[]; shotId?: string; dramaId?: string | number; providerId?: string; modelCode?: string; maxVideos?: number; option?: ShotVideoModelOption; targetDuration?: number; contextFingerprint?: string; purpose?: 'render' | 'draft' | 'prompt'; disabled?: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: ShotRefVideo[]): void; (e: 'prepare'): void; (e: 'prepared', value?: VideoReferencePreflight): void }>()
const countLimit = computed(() => {
  const values = [props.maxVideos, props.option?.maxVideos, props.option?.referenceVideo.maxVideos].filter((n): n is number => typeof n === 'number' && n >= 0)
  return values.length ? Math.min(...values) : undefined
})
const canAdd = computed(() => !props.disabled && (countLimit.value == null || props.modelValue.length < countLimit.value))
const notice = computed(() => {
  if (props.purpose === 'prompt') return '请为各段视频填写参考说明。AI 将结合片段范围和说明生成 <Video N> 引用；当前提示词生成使用文字说明，不会自动识别视频内容。确认采纳时，视频配置与提示词一起保存。'
  if (props.purpose === 'draft') return ''
  return props.option?.reason || (!props.option ? '请选择模型；素材可保存为草稿，视频生成能力须完成验证后启用。' : '')
})
const materialPicker = ref<InstanceType<typeof VideoMaterialPickerDialog>>()
const expanded = ref<string[]>([])
const players = new Map<string, HTMLVideoElement>()
const sourceInfo = reactive<Record<string, { url: string; metadata?: VideoProbeInfoVO; probing: boolean }>>({})
const signatures = new Map<string, string>(), probeVersions = new Map<string, number>()
const uploading = ref(false), preparing = ref(false), preflight = ref<VideoReferencePreflight>()
let revision = 0, alive = true
watch(() => props.shotId, () => {
  expanded.value = []; signatures.clear(); players.clear()
  for (const [id, version] of probeVersions) probeVersions.set(id, version + 1)
  for (const id of Object.keys(sourceInfo)) delete sourceInfo[id]
})
function setPlayer(id: string, element: Element | ComponentPublicInstance | null) { if (element instanceof HTMLVideoElement) players.set(id, element); else players.delete(id) }
function patch(id: string, value: Partial<ShotRefVideo>) { if (!props.disabled) emit('update:modelValue', props.modelValue.map(item => item.id === id ? { ...item, ...value } : item)) }
function remove(id: string) { if (!props.disabled) emit('update:modelValue', props.modelValue.filter(item => item.id !== id)) }
function move(index: number, direction: number) {
  const target = index + direction
  if (props.disabled || target < 0 || target >= props.modelValue.length) return
  const items = [...props.modelValue]; [items[index], items[target]] = [items[target]!, items[index]!]
  emit('update:modelValue', items)
}
function append(item: ShotRefVideo) {
  if (!canAdd.value) { ElMessage.warning('参考视频数量已达到当前模型配置上限'); return }
  if (props.modelValue.some(existing => existing.id === item.id)) item = { ...item, id: `video_${crypto.randomUUID()}` }
  emit('update:modelValue', [...props.modelValue, item])
}
function selectMaterial(video: ResVideo) {
  if (!canAdd.value) return
  if (video.status !== 1 || String(video.dramaId) !== '0' && String(video.dramaId) !== String(props.dramaId)) { ElMessage.warning('请选择公共库或当前短剧已启用的视频素材'); return }
  append({ id: `video_asset_${video.id}`, name: video.name, sourceType: 'DIRECT_URL', sourceVideoUrl: video.videoUrl,
    startSeconds: 0, includeAudio: false, referenceDescription: video.description?.slice(0, 1000) })
}
async function upload(file: File) {
  if (!canAdd.value || uploading.value) return false
  if (file.size > 256 * 1024 * 1024) { ElMessage.warning('参考源视频不能超过 256 MB'); return false }
  uploading.value = true
  const shotId = props.shotId
  try {
    const result = await videoAssetApi.upload(file, props.dramaId, shotId, undefined, 'SHOT_UPLOAD')
    if (!alive || shotId !== props.shotId || !canAdd.value) return false
    append({ id: `video_asset_${result.id}`, name: result.name, sourceType: 'DIRECT_URL', sourceVideoUrl: result.videoUrl, startSeconds: 0, includeAudio: false })
  } catch (error: any) { ElMessage.error(error.message || '上传失败') }
  finally { uploading.value = false }
  return false
}
watch(() => JSON.stringify([props.shotId, props.modelValue, props.providerId, props.modelCode, props.contextFingerprint]), () => { revision++; preflight.value = undefined; preparing.value = false; emit('prepared', undefined) })
watch(() => JSON.stringify([props.shotId, props.modelValue.map(item => [item.id, item.sourceType, item.sourceVideoUrl, item.sourceShotId, item.sourceVideoTakeId]), expanded.value]), () => {
  const ids = new Set(props.modelValue.map(item => item.id))
  for (const id of Object.keys(sourceInfo)) if (!ids.has(id)) { delete sourceInfo[id]; signatures.delete(id); probeVersions.set(id, (probeVersions.get(id) || 0) + 1) }
  for (const item of props.modelValue) {
    if (!expanded.value.includes(item.id)) continue
    const signature = JSON.stringify([props.shotId, item.id, item.sourceType, item.sourceVideoUrl, item.sourceShotId, item.sourceVideoTakeId])
    if (signatures.get(item.id) === signature) continue
    signatures.set(item.id, signature); void probeSource(item)
  }
}, { immediate: true })
async function probeSource(item: ShotRefVideo) {
  const version = (probeVersions.get(item.id) || 0) + 1; probeVersions.set(item.id, version)
  sourceInfo[item.id] = { url: item.sourceVideoUrl || '', probing: !!props.shotId }
  if (!props.shotId) return
  try {
    const result = await shotVideoReferenceApi.probe(props.shotId, item)
    if (!alive || version !== probeVersions.get(item.id)) return
    sourceInfo[item.id] = { url: result.sourceUrl || item.sourceVideoUrl || '', metadata: result, probing: false }
  } catch (error: any) { if (alive && version === probeVersions.get(item.id)) { sourceInfo[item.id]!.probing = false; ElMessage.warning(error.message || '源视频待服务端预检') } }
}
function playRange(item: ShotRefVideo) { const player = players.get(item.id); if (player) { player.currentTime = item.startSeconds || 0; void player.play() } }
function limitPlayback(item: ShotRefVideo) { const player = players.get(item.id); if (item.endSeconds && player && player.currentTime >= item.endSeconds) player.pause() }
async function prepare() {
  if (!props.shotId || !props.providerId || !props.modelCode) { ElMessage.warning('请选择提供商与模型'); return }
  const current = ++revision, shotId = props.shotId
  preparing.value = true
  try {
    let result = await shotVideoReferenceApi.prepare(shotId, props.providerId, props.modelCode)
    while (result.status === 'PREPARING' && current === revision) {
      preflight.value = result; await new Promise(resolve => window.setTimeout(resolve, 1200))
      if (current !== revision) return
      result = await shotVideoReferenceApi.get(shotId, result.preflightId)
    }
    if (current !== revision) return
    preflight.value = result; emit('prepared', result.status === 'READY' ? result : undefined)
  } catch (error: any) { if (current === revision) ElMessage.error(error.message || '片段预检失败') }
  finally { if (current === revision) preparing.value = false }
}
onBeforeUnmount(() => { alive = false; revision++; probeVersions.clear() })
defineExpose({ prepare })
</script>

<style scoped>
.reference-video-list :deep(.el-collapse-item__header) {
  padding: 0 12px;
  border-bottom: 0;
}
.reference-video-list :deep(.el-collapse-item__wrap) {
  border-bottom: 0;
}
.reference-video-list :deep(.el-collapse-item__content) {
  padding-bottom: 0;
}
</style>
