<template>
  <section class="border border-violet-200 rounded-xl p-4 space-y-3 bg-violet-50/20">
    <div class="flex flex-wrap justify-between items-center gap-2">
      <strong class="text-sm text-violet-950">参考视频（首期最多 1 段）</strong>
      <div class="flex gap-2">
        <el-upload :show-file-list="false" :before-upload="upload" accept="video/mp4,video/webm,video/quicktime,video/x-matroska" :disabled="disabled || modelValue.length > 0 || uploading">
          <el-button size="small" :loading="uploading" :disabled="disabled || modelValue.length > 0">上传视频</el-button>
        </el-upload>
        <el-button size="small" :disabled="disabled || modelValue.length > 0" @click="picker?.open(dramaId)">选择分镜 / 历史 Take</el-button>
      </div>
    </div>
    <p class="text-xs text-slate-500">目标生成 {{ targetDuration || '待设置' }} 秒；参考片段时长单独设置。原视频不会覆盖目标分镜。</p>
    <el-alert v-if="notice" :title="notice" type="info" :closable="false" />
    <div v-for="item in modelValue" :key="item.id" class="space-y-3 bg-white border border-violet-100 rounded-lg p-3">
      <div class="flex items-center justify-between text-xs">
        <strong>Video 1 · {{ item.name || '视频参考' }}</strong>
        <el-button size="small" link type="danger" :disabled="disabled" @click="emit('update:modelValue', [])">移除</el-button>
      </div>
      <p v-if="item.sourceShotId" class="text-xs text-slate-500 break-all">来源分镜 {{ item.sourceShotId }} · {{ item.sourceVideoTakeId ? `Take ${item.sourceVideoTakeId}` : '预检时固定当前 Take' }}</p>
      <video v-if="sourceUrl" :ref="setPlayer" :src="sourceUrl" controls preload="metadata" class="w-full h-44 bg-black rounded" @timeupdate="limitPlayback" />
      <div v-loading="probing" class="flex flex-wrap gap-4 items-center text-xs">
        <label>起点（秒）<el-input-number :model-value="item.startSeconds ?? 0" :disabled="disabled" :min="0" :step="0.1" :precision="2" size="small" @update:model-value="patch({ startSeconds: $event ?? undefined })" /></label>
        <label>终点（秒）<el-input-number :model-value="item.endSeconds" :disabled="disabled" :min="0.01" :step="0.1" :precision="2" size="small" placeholder="源片结尾" @update:model-value="patch({ endSeconds: $event ?? undefined })" /></label>
        <el-button size="small" :disabled="!sourceUrl" @click="playRange">预览所选范围</el-button>
      </div>
      <p v-if="metadata" class="text-xs text-slate-500">源视频 {{ metadata.duration }} 秒 · {{ metadata.width }} × {{ metadata.height }} · {{ metadata.fps }} FPS · {{ metadata.hasAudio ? '有音轨' : '无音轨' }}</p>
      <p v-if="option?.referenceVideo.maxClipSeconds" class="text-xs text-slate-500">当前模型片段上限 {{ option.referenceVideo.maxClipSeconds }} 秒；服务端将按实测结果检查并生成预览。</p>
      <el-checkbox :model-value="item.includeAudio === true" :disabled="disabled || !option?.referenceVideo.supportsVideoAudio && !item.includeAudio" @update:model-value="patch({ includeAudio: $event === true })">使用视频原音作为生成参考</el-checkbox>
      <p class="text-xs text-slate-500">{{ purpose === 'draft' || purpose === 'prompt' ? '默认仅参考画面；如需参考原音，请在渲染工坊选择支持该能力的模型后开启。' : '默认仅参考画面。原音不会直接混入结果；开启前需模型支持且源片含音轨。' }}</p>
      <el-input :model-value="item.referenceDescription" :disabled="disabled" type="textarea" :rows="2" maxlength="1000" show-word-limit placeholder="明确参考什么，例如：参考 Video 1 的推进运镜和起身节奏；人物外观使用 Picture 1。" @update:model-value="patch({ referenceDescription: $event })" />
      <el-button v-if="purpose !== 'prompt'" size="small" type="primary" plain :disabled="disabled" :loading="preparing" @click="emit('prepare')">{{ purpose === 'draft' ? '保存并进入渲染工坊' : '保存并预检参考片段' }}</el-button>
      <el-alert v-if="preflight?.status === 'READY'" title="片段已准备完成。生成前还会检查模型与视频版本。" type="success" :closable="false" />
      <el-alert v-if="preflight?.errorMessage" :title="preflight.errorMessage" type="error" :closable="false" />
      <video v-if="preflight?.status === 'READY' && preflight.previewUrls?.[0]" :src="preflight.previewUrls[0]" controls preload="metadata" class="w-full h-44 bg-black rounded" />
    </div>
    <p v-if="!modelValue.length" class="text-center text-xs text-slate-400 py-3">可上传视频或选用当前短剧已生成的视频版本。</p>
    <ShotVideoPickerDialog ref="picker" @selected="selectSource" />
  </section>
</template>

<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount, type ComponentPublicInstance } from 'vue'
import { ElMessage } from 'element-plus'
import { assetApi } from '@/api/res-asset'
import type { VideoProbeInfoVO } from '@/api/videoProcessing'
import { shotVideoReferenceApi } from '@/api/shot-video-reference'
import type { ShotRefVideo, ShotVideoModelOption, VideoReferencePreflight } from '@/types/drama'
import ShotVideoPickerDialog, { type SelectedVideoProcessSource } from '@/views/production/components/ShotVideoPickerDialog.vue'

const props = defineProps<{ modelValue: ShotRefVideo[]; shotId?: string; dramaId?: string | number; providerId?: string; modelCode?: string; option?: ShotVideoModelOption; targetDuration?: number; contextFingerprint?: string; purpose?: 'render' | 'draft' | 'prompt'; disabled?: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: ShotRefVideo[]): void; (e: 'prepare'): void; (e: 'prepared', value?: VideoReferencePreflight): void }>()
const notice = computed(() => {
  if (props.purpose === 'prompt') return '请填写具体参考说明。AI 将结合片段范围和说明生成 <Video 1> 引用；当前提示词生成使用文字说明，不会自动识别视频内容。确认采纳时，视频配置与提示词一起保存。'
  if (props.purpose === 'draft') return '在此添加视频、设置片段范围和参考说明后保存；进入渲染工坊选择模型并预检片段。'
  return props.option?.reason || (!props.option ? '请选择模型；素材可保存为草稿，视频生成能力须完成验证后启用。' : '')
})
const picker = ref<InstanceType<typeof ShotVideoPickerDialog>>()
const player = ref<HTMLVideoElement>()
const sourceUrl = ref(''), metadata = ref<VideoProbeInfoVO>(), probing = ref(false), uploading = ref(false), preparing = ref(false)
const preflight = ref<VideoReferencePreflight>()
let revision = 0, probeRevision = 0, alive = true
function setPlayer(element: Element | ComponentPublicInstance | null) { player.value = element instanceof HTMLVideoElement ? element : undefined }

function patch(value: Partial<ShotRefVideo>) { if (!props.disabled && props.modelValue[0]) emit('update:modelValue', [{ ...props.modelValue[0], ...value }]) }
function selectSource(source: SelectedVideoProcessSource) {
  if (props.disabled) return
  if (props.dramaId && source.sourceDramaId !== String(props.dramaId)) { ElMessage.warning('请选择当前短剧的视频'); return }
  emit('update:modelValue', [{ id: `video_${crypto.randomUUID()}`, name: source.shotName || '分镜视频', sourceType: source.sourceType,
    sourceShotId: String(source.sourceShotId), sourceVideoTakeId: source.sourceType === 'SHOT_VIDEO_TAKE' && source.sourceVideoTakeId ? String(source.sourceVideoTakeId) : undefined,
    startSeconds: 0, includeAudio: false }])
}
async function upload(file: File) {
  if (props.disabled) return false
  if (file.size > 256 * 1024 * 1024) { ElMessage.warning('参考源视频不能超过 256 MB'); return false }
  uploading.value = true
  const shotId = props.shotId
  try {
    const result = await assetApi.upload(file, 'reference-video')
    if (!alive || props.disabled || shotId !== props.shotId || props.modelValue.length) return false
    emit('update:modelValue', [{ id: `video_${crypto.randomUUID()}`, name: file.name, sourceType: 'DIRECT_URL', sourceVideoUrl: result.url, startSeconds: 0, includeAudio: false }])
  } catch (error: any) { ElMessage.error(error.message || '上传失败') }
  finally { uploading.value = false }
  return false
}
watch(() => JSON.stringify([props.shotId, props.modelValue, props.providerId, props.modelCode, props.contextFingerprint]), () => { revision++; preflight.value = undefined; preparing.value = false; emit('prepared', undefined) })
watch(() => JSON.stringify([props.shotId, props.modelValue.map(item => [item.id, item.sourceType, item.sourceVideoUrl, item.sourceShotId, item.sourceVideoTakeId])]), async () => {
  const item = props.modelValue[0], current = ++probeRevision
  metadata.value = undefined; sourceUrl.value = item?.sourceVideoUrl || ''; probing.value = false
  if (!item) return
  probing.value = true
  try {
    if (!props.shotId) return
    const result = await shotVideoReferenceApi.probe(props.shotId, item)
    if (current !== probeRevision) return
    metadata.value = result; sourceUrl.value = result.sourceUrl || item.sourceVideoUrl || ''
  } catch (error: any) { if (current === probeRevision) ElMessage.warning(error.message || '源视频待服务端预检') }
  finally { if (current === probeRevision) probing.value = false }
}, { immediate: true })
function playRange() { if (player.value) { player.value.currentTime = props.modelValue[0]?.startSeconds || 0; void player.value.play() } }
function limitPlayback() { const end = props.modelValue[0]?.endSeconds; if (end && player.value && player.value.currentTime >= end) player.value.pause() }

async function prepare() {
  if (!props.shotId || !props.providerId || !props.modelCode) { ElMessage.warning('请选择提供商与模型'); return }
  const current = ++revision, shotId = props.shotId
  preparing.value = true
  try {
    let result = await shotVideoReferenceApi.prepare(shotId, props.providerId, props.modelCode)
    while (result.status === 'PREPARING' && current === revision) {
      preflight.value = result
      await new Promise(resolve => window.setTimeout(resolve, 1200))
      if (current !== revision) return
      result = await shotVideoReferenceApi.get(shotId, result.preflightId)
    }
    if (current !== revision) return
    preflight.value = result; emit('prepared', result.status === 'READY' ? result : undefined)
  } catch (error: any) { if (current === revision) ElMessage.error(error.message || '片段预检失败') }
  finally { if (current === revision) preparing.value = false }
}
onBeforeUnmount(() => { alive = false; revision++; probeRevision++ })
defineExpose({ prepare })
</script>
