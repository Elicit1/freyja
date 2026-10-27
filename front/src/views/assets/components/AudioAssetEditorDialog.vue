<template>
  <el-dialog :model-value="modelValue" class="audio-asset-editor" width="min(1040px, 94vw)" top="5vh" append-to-body destroy-on-close
    :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="close">
    <template #header>
      <div class="editor-heading">
        <span class="editor-symbol"><el-icon :size="22"><Headset /></el-icon></span>
        <div><h2>{{ assetId ? '编辑' : '上传' }}{{ mediaLabel }}素材</h2><p>{{ assetId ? '整理素材信息，方便后续查找与引用' : '先预览文件、填写信息，确认后再保存到素材库' }}</p></div>
      </div>
    </template>

    <div v-loading="loading" class="editor-columns">
      <section class="editor-preview" aria-label="文件预览">
        <div class="section-heading"><h3>文件预览</h3><span v-if="file || assetId" class="preview-caption">{{ assetId ? '已入库文件' : '本地预览' }}</span></div>
        <input :id="inputId" ref="fileInput" class="file-input" type="file" :accept="accept" :disabled="busy" @change="onFileChange" />
        <div v-if="!previewUrl" class="drop-zone" :class="{ 'is-dragging': dragging }" role="button" tabindex="0" aria-label="选择或拖入文件"
          @click="fileInput?.click()" @keydown.enter.prevent="fileInput?.click()" @keydown.space.prevent="fileInput?.click()"
          @dragover.prevent="dragging = true" @dragleave.prevent="dragging = false" @drop.prevent="onDrop">
          <span class="drop-symbol"><el-icon :size="30"><UploadFilled /></el-icon></span>
          <strong>将{{ mediaLabel }}拖到这里</strong><span>或点击选择本地文件</span><span class="file-formats">{{ formatHint }}</span>
        </div>
        <div v-else class="preview-stage is-audio">
          <span class="audio-art"><el-icon :size="38"><Headset /></el-icon></span><strong>{{ draft.name || '音频预览' }}</strong><span>{{ audioKindLabel(draft.audioKind) }}</span><audio :key="previewUrl" :src="previewUrl" controls preload="metadata" aria-label="音频试听" @loadedmetadata="readMetadata" @error="previewFailed = true" />
        </div>
        <div v-if="previewUrl" class="file-summary">
          <span class="file-extension">{{ extension }}</span><div class="file-summary-copy"><strong :title="filename">{{ filename }}</strong><span>{{ sizeLabel }}<template v-if="duration != null"> · {{ duration.toFixed(1) }} 秒</template></span></div>
          <el-icon v-if="!previewFailed" class="file-ready"><CircleCheckFilled /></el-icon>
        </div>
        <div v-if="file && !assetId" class="file-actions"><el-button text :disabled="busy" :icon="Refresh" @click="fileInput?.click()">更换文件</el-button><el-button text :disabled="busy" :icon="Delete" @click="clearFile">移除</el-button></div>
        <p class="preview-note">{{ formatHint }} · 最大 30 MB · 最长 10 分钟</p>
        <p v-if="previewFailed" class="preview-note">浏览器暂时无法预览这个文件。你仍可上传，保存时会检查文件是否有效。</p>
        <p v-if="durationError" class="inline-warning" role="alert">{{ durationError }}</p>
        <div class="reference-note"><el-icon><InfoFilled /></el-icon><p>用于镜头参考时，单段需为 2～15 秒，所选音频总长不超过 15 秒。素材库也可以保存较长的原始音频。</p></div>
        <div v-if="file || assetId" class="preview-details"><span>{{ assetId ? '文件已归档' : '文件只在本地预览' }}</span><p>{{ assetId ? '编辑信息不会替换原始文件，已有镜头仍可继续引用。' : '点击“上传并保存”后才会创建素材；取消不会留下未完成的素材。' }}</p></div>
      </section>

      <el-form ref="formRef" :model="draft" :rules="rules" label-position="top" class="editor-form" :disabled="busy || loading" @submit.prevent="save">
        <div class="section-heading"><h3>素材信息</h3><span class="preview-caption">带 * 为必填</span></div>
        <el-form-item label="素材名称" prop="name"><el-input v-model="draft.name" maxlength="200" show-word-limit placeholder="选择文件后自动填写，可自行修改" /></el-form-item>
        <div class="scope-fields">
          <el-form-item label="所属库" prop="dramaId"><el-select v-model="draft.dramaId" filterable placeholder="选择所属库" @change="changeDrama"><el-option label="全局公共库" value="0" /><el-option v-for="drama in dramas" :key="String(drama.id)" :value="String(drama.id)" :label="drama.title" /></el-select></el-form-item>
          <el-form-item label="关联分镜（可选）"><el-select v-model="draft.shotId" clearable filterable :loading="shotsLoading" :disabled="!draft.dramaId || draft.dramaId === '0'" :placeholder="draft.dramaId === '0' ? '公共库无需关联' : '选择分镜'" no-data-text="当前短剧暂无分镜"><el-option v-for="shot in shots" :key="String(shot.id)" :value="String(shot.id)" :label="shot.shotName || `镜 ${shot.shotNo}`" /></el-select></el-form-item>
        </div>
        <el-form-item label="音频种类" prop="audioKind">
          <div class="audio-kinds" role="radiogroup" aria-label="音频种类"><label v-for="kind in AUDIO_KIND_OPTIONS" :key="kind.value" class="kind-option" :class="{ 'is-selected': draft.audioKind === kind.value }" :title="kind.description"><input v-model="draft.audioKind" type="radio" :name="inputId + '-kind'" :value="kind.value" :disabled="busy" /><span>{{ kind.label }}</span><el-icon v-if="draft.audioKind === kind.value"><CircleCheckFilled /></el-icon></label></div>
          <p class="field-help">{{ AUDIO_KIND_OPTIONS.find(kind => kind.value === draft.audioKind)?.description || '选择最方便你整理和查找素材的种类。' }}</p>
        </el-form-item>
        <el-form-item label="素材描述（可选）"><el-input v-model="draft.description" type="textarea" :rows="3" maxlength="4000" show-word-limit placeholder="例如：低沉鼓点、雨声环境，或这段声音的适用场景" /></el-form-item>
        <el-collapse v-model="expanded" class="editor-more">
          <el-collapse-item name="transcript" title="对白、歌词与语言（可选）"><el-form-item label="原文"><el-input v-model="draft.text" type="textarea" :rows="3" maxlength="10000" placeholder="仅填写确认的对白或歌词，不确定的内容可以留空" /></el-form-item><el-form-item label="语言"><el-select v-model="draft.language" clearable placeholder="未指定"><el-option v-for="language in REFERENCE_AUDIO_LANGUAGE_OPTIONS" :key="language.value" :value="language.value" :label="language.label" /></el-select></el-form-item></el-collapse-item>
          <el-collapse-item name="more" title="更多设置"><div class="scope-fields"><el-form-item label="排序"><el-input-number v-model="draft.sortOrder" :min="0" :max="9999" /></el-form-item><el-form-item label="状态"><el-radio-group v-model="draft.status"><el-radio :value="1">启用</el-radio><el-radio :value="0">停用</el-radio></el-radio-group></el-form-item></div><el-form-item label="备注"><el-input v-model="draft.remark" type="textarea" :rows="2" maxlength="1000" /></el-form-item></el-collapse-item>
        </el-collapse>
      </el-form>
    </div>
    <div v-if="error" class="editor-error" role="alert"><el-icon><WarningFilled /></el-icon><span>{{ error }}</span></div>
    <div v-if="busy && !assetId" class="upload-progress" aria-live="polite"><div><span>{{ progress < 100 ? '正在上传文件' : '文件已传输，正在检查并保存' }}</span><span>{{ progress }}%</span></div><el-progress :percentage="progress" :show-text="false" :stroke-width="4" /></div>
    <template #footer><div class="editor-footer"><span class="footer-status" aria-live="polite"><i :class="{ 'is-ready': !!previewUrl, 'is-busy': busy }" />{{ busy ? '正在保存，请稍候' : assetId ? '文件已入库，可编辑素材信息' : file ? '已选择文件，等待确认' : '请先选择一个文件' }}</span><div><el-button :disabled="busy" @click="close(false)">取消</el-button><el-button type="primary" :loading="busy || validating" :disabled="loading || assetLoadFailed || (!assetId && !file) || !!durationError" :icon="assetId ? undefined : Upload" @click="save">{{ assetId ? '保存修改' : error ? '重试上传' : '上传并保存' }}</el-button></div></div></template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, useId, watch } from 'vue'
import { CircleCheckFilled, Delete, Headset, InfoFilled, Refresh, Upload, UploadFilled, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import type { AxiosProgressEvent } from 'axios'
import { audioAssetApi } from '@/api/res-audio'
import { shotApi } from '@/api/drama'
import { AUDIO_KIND_OPTIONS, audioKindLabel } from '@/constants/audioAssetOptions'
import { REFERENCE_AUDIO_LANGUAGE_OPTIONS } from '@/constants/referenceMediaOptions'
import type { ResAudio } from '@/types/resource'
import type { DramaOption } from '@/types/drama'

type Asset = ResAudio
type Draft = Partial<ResAudio>
const props = withDefaults(defineProps<{ modelValue: boolean; dramas: DramaOption[]; assetId?: string; initialDramaId?: string; initialShotId?: string; sourceType?: 'MANUAL_UPLOAD' | 'SHOT_UPLOAD' }>(), { sourceType: 'MANUAL_UPLOAD' })
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [asset: Asset]; cancelled: [] }>()
const mediaLabel = '音频'
const inputId = useId(), fileInput = ref<HTMLInputElement>(), formRef = ref<FormInstance>()
const file = shallowRef<File>(), localUrl = ref(''), draft = ref<Draft>({}), busy = ref(false), loading = ref(false), dragging = ref(false), error = ref(''), progress = ref(0), previewFailed = ref(false)
const expanded = ref<string[]>([]), duration = ref<number>(), shotsLoading = ref(false)
const shots = ref<{ id: string; shotName?: string; shotNo: number }[]>([])
const validating = ref(false), assetLoadFailed = ref(false)
let sequence = 0, shotSequence = 0, alive = true, autoName = ''
const previewUrl = computed(() => localUrl.value || draft.value.audioUrl)
const filename = computed(() => file.value?.name || draft.value.originalFilename || draft.value.name || '')
const extension = computed(() => filename.value.split('.').pop()?.toUpperCase() || 'AUDIO')
const sizeLabel = computed(() => { const bytes = file.value?.size ?? Number(draft.value.fileSize); return Number.isFinite(bytes) && bytes > 0 ? bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB` : '大小未知' })
const formatHint = computed(() => 'MP3 / WAV / FLAC / M4A / OGG')
const accept = computed(() => '.mp3,.wav,.flac,.m4a,.ogg')
const durationError = computed(() => duration.value != null && duration.value > 600 ? '素材最长支持 10 分钟，请选择较短的文件。' : '')
const rules: FormRules = { name: [{ required: true, whitespace: true, message: '请填写素材名称', trigger: 'blur' }], dramaId: [{ required: true, message: '请选择所属库', trigger: 'change' }], audioKind: [{ required: true, message: '请选择音频种类', trigger: 'change' }] }

function clearFile() {
  if (localUrl.value) URL.revokeObjectURL(localUrl.value)
  file.value = undefined; localUrl.value = ''; duration.value = undefined; previewFailed.value = false; error.value = ''
  if (fileInput.value) fileInput.value.value = ''
}
function selectFile(chosen?: File) {
  if (!chosen || busy.value || props.assetId) return
  const allowed = ['mp3', 'wav', 'flac', 'm4a', 'ogg']
  if (!allowed.includes(chosen.name.split('.').pop()?.toLowerCase() || '')) { error.value = `请选择 ${formatHint.value} 格式的文件`; return }
  if (chosen.size === 0 || chosen.size > 30 * 1024 * 1024) { error.value = `请选择非空文件，大小不超过 30 MB`; return }
  const nextName = chosen.name.replace(/\.[^.]+$/, '').slice(0, 200)
  const useFilename = !draft.value.name?.trim() || draft.value.name === autoName
  clearFile(); file.value = chosen; localUrl.value = URL.createObjectURL(chosen)
  if (useFilename) draft.value.name = nextName
  autoName = nextName
}
function onFileChange(event: Event) { selectFile((event.target as HTMLInputElement).files?.[0]) }
function onDrop(event: DragEvent) { dragging.value = false; if ((event.dataTransfer?.files.length || 0) > 1) { error.value = '一次请选择一个文件'; return } selectFile(event.dataTransfer?.files[0]) }
function readMetadata(event: Event) {
  const element = event.target as HTMLMediaElement
  if (Number.isFinite(element.duration) && element.duration > 0) duration.value = element.duration
  previewFailed.value = false
}
async function loadShots() {
  const version = ++shotSequence, id = draft.value.dramaId; shots.value = []
  if (!id || id === '0') { shotsLoading.value = false; return }
  shotsLoading.value = true
  try { const values = await shotApi.getOptionsByDrama(String(id)); if (alive && version === shotSequence && props.modelValue) shots.value = values }
  catch { /* The request interceptor shows the error. */ }
  finally { if (version === shotSequence) shotsLoading.value = false }
}
function changeDrama() { draft.value.shotId = undefined; void loadShots() }
function close(value: boolean) { if (busy.value) return; if (!value) { emit('update:modelValue', false); emit('cancelled') } }
async function save() {
  if (busy.value || validating.value || loading.value || assetLoadFailed.value || durationError.value) return
  validating.value = true
  const valid = await formRef.value?.validate().catch(() => false)
  validating.value = false
  if (!valid || (!props.assetId && !file.value)) return
  busy.value = true; progress.value = 0; error.value = ''
  const version = sequence
  const metadata = { ...draft.value, name: draft.value.name!.trim(), dramaId: draft.value.dramaId || undefined, shotId: draft.value.shotId || undefined, language: draft.value.language || undefined }
  const onProgress = (event: AxiosProgressEvent) => { if (alive && version === sequence && event.total) progress.value = Math.min(100, Math.round(event.loaded / event.total * 100)) }
  try {
    let result: Asset
    if (props.assetId) {
      await audioAssetApi.update(metadata); result = await audioAssetApi.getById(props.assetId)
    } else {
      result = await audioAssetApi.upload(file.value!, metadata.dramaId, metadata.shotId, metadata.name, props.sourceType, metadata, onProgress)
    }
    if (!alive || version !== sequence) return
    ElMessage.success(props.assetId ? '素材信息已保存' : `${mediaLabel}已上传并保存`)
    emit('saved', result); emit('update:modelValue', false)
  } catch (failure) { if (alive && version === sequence) error.value = failure instanceof Error ? failure.message : '保存失败，请检查网络后重试。已填写的信息会保留。' }
  finally { if (alive && version === sequence) busy.value = false }
}
watch(() => props.modelValue, async visible => {
  const version = ++sequence; ++shotSequence; clearFile(); error.value = ''; expanded.value = []; progress.value = 0; autoName = ''; loading.value = false; assetLoadFailed.value = false; shots.value = []
  if (!visible) return
  draft.value = { dramaId: props.initialDramaId || '0', shotId: props.initialShotId || undefined, name: '', sortOrder: 0, status: 1 }
  if (props.assetId) {
    loading.value = true
    try { const asset = await audioAssetApi.getById(props.assetId); if (!alive || version !== sequence) return; draft.value = { ...asset }; duration.value = asset.duration }
    catch { if (version === sequence) { assetLoadFailed.value = true; error.value = '素材加载失败，请关闭后重试。' } }
    finally { if (version === sequence) loading.value = false }
  }
  if (version !== sequence) return
  await nextTick(); formRef.value?.clearValidate(); void loadShots()
}, { immediate: true })
onBeforeUnmount(() => { alive = false; sequence++; shotSequence++; clearFile() })
</script>

<style scoped>
.editor-heading{display:flex;align-items:center;gap:12px;padding:2px 0}.editor-symbol{display:grid;place-items:center;width:42px;height:42px;border-radius:12px;background:var(--brand-soft);color:var(--brand);flex-shrink:0}.editor-heading h2{font-size:17px;font-weight:650;color:var(--text-primary);margin:0 0 5px}.editor-heading p,.preview-caption,.preview-note,.field-help{font-size:12px;color:var(--text-secondary);line-height:1.65;margin:0}.editor-columns{display:grid;grid-template-columns:minmax(0,1.05fr) minmax(0,1fr);gap:28px}.editor-preview{min-width:0}.section-heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;gap:8px}.section-heading h3{font-size:13px;font-weight:650;margin:0;color:var(--text-primary)}.editor-form{min-width:0;border-left:1px solid var(--border-default);padding-left:28px}.editor-form :deep(.el-form-item){margin-bottom:19px}.editor-form :deep(.el-form-item__label){font-size:12px;font-weight:550;color:var(--text-primary);padding-bottom:6px}.editor-form :deep(.el-select){width:100%}.scope-fields{display:grid;grid-template-columns:1fr 1fr;gap:12px}.file-input{display:none}.drop-zone{min-height:300px;border:1.5px dashed var(--border-strong);background:var(--surface-muted);border-radius:12px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:9px;cursor:pointer;color:var(--text-secondary);transition:border-color .15s,background .15s}.drop-zone:hover,.drop-zone:focus-visible,.drop-zone.is-dragging{border-color:var(--brand);background:var(--brand-soft);outline:2px solid transparent}.drop-symbol{display:grid;place-items:center;width:60px;height:60px;color:var(--brand);background:var(--brand-soft);border-radius:16px;margin-bottom:8px}.drop-zone strong{font-size:15px;color:var(--text-primary)}.drop-zone>span{font-size:12px}.drop-zone .file-formats{margin-top:8px;font-size:11px;color:var(--text-secondary)}.preview-stage{aspect-ratio:16/10;display:flex;align-items:center;justify-content:center;border-radius:12px;background:#0b0f17;overflow:hidden}.preview-stage video{width:100%;height:100%;object-fit:contain}.preview-stage.is-audio{background:var(--surface-muted);border:1px solid var(--border-default);flex-direction:column;gap:10px;padding:24px;aspect-ratio:auto;min-height:260px}.audio-art{width:72px;height:72px;display:grid;place-items:center;color:var(--brand);border-radius:22px;background:var(--brand-soft);margin-bottom:8px}.preview-stage.is-audio strong{max-width:100%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:14px;color:var(--text-primary)}.preview-stage.is-audio>span:not(.audio-art){font-size:12px;color:var(--text-secondary)}.preview-stage audio{width:100%;height:38px;margin-top:12px}.file-summary{display:flex;align-items:center;gap:12px;border:1px solid var(--border-default);background:var(--surface-muted);padding:14px;border-radius:10px;margin-top:14px}.file-extension{font:600 10px ui-monospace,monospace;background:var(--surface);border:1px solid var(--border-default);border-radius:8px;width:42px;height:44px;display:grid;place-items:center;flex-shrink:0;color:var(--text-secondary)}.file-summary-copy{min-width:0;flex:1}.file-summary-copy strong{font-size:12px;display:block;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;color:var(--text-primary)}.file-summary-copy span{font:11px ui-monospace,monospace;color:var(--text-secondary);display:block;margin-top:6px}.file-ready{color:var(--success);font-size:18px}.file-actions{display:flex;justify-content:flex-end;gap:6px;margin:4px 0 6px}.preview-note{margin-top:10px;font-size:11px}.preview-details{border-top:1px solid var(--border-default);margin-top:24px;padding-top:16px}.preview-details span{font-size:12px;font-weight:550;color:var(--text-primary)}.preview-details p{font-size:12px;line-height:1.75;margin-top:7px;color:var(--text-secondary)}.reference-note{display:flex;gap:8px;margin-top:16px;padding:12px;border-radius:9px;background:var(--brand-soft);color:var(--brand)}.reference-note .el-icon{flex-shrink:0;margin-top:3px}.reference-note p{font-size:12px;line-height:1.7;margin:0}.audio-kinds{display:grid;grid-template-columns:1fr 1fr;gap:7px;width:100%}.kind-option{display:flex;align-items:center;justify-content:space-between;border:1px solid var(--border-default);border-radius:8px;padding:9px 10px;cursor:pointer;font-size:12px;line-height:20px;color:var(--text-secondary);transition:border-color .15s,background .15s}.kind-option input{position:absolute;opacity:0;width:1px;height:1px}.kind-option.is-selected,.kind-option:hover,.kind-option:focus-within{border-color:var(--brand);background:var(--brand-soft);color:var(--brand)}.field-help{margin-top:8px;font-size:11px}.editor-more{border-top:1px solid var(--border-default);border-bottom:0}.editor-more :deep(.el-collapse-item__header){font-size:12px;color:var(--text-secondary);border-color:var(--border-default);font-weight:500}.editor-more :deep(.el-collapse-item__wrap){border-bottom:0}.editor-more :deep(.el-collapse-item__content){padding:8px 0 0}.editor-error,.inline-warning{color:var(--danger);font-size:12px;line-height:1.6}.editor-error{display:flex;align-items:center;gap:8px;padding:10px 12px;border-radius:8px;background:color-mix(in srgb,var(--danger) 7%,var(--surface));margin-top:16px}.editor-error .el-icon{flex-shrink:0}.inline-warning{margin-top:10px}.upload-progress{padding-top:18px}.upload-progress>div:first-child{display:flex;justify-content:space-between;font-size:12px;color:var(--text-secondary);margin-bottom:8px}.editor-footer{display:flex;justify-content:space-between;align-items:center;gap:16px}.footer-status{font-size:12px;color:var(--text-secondary);display:flex;align-items:center;gap:8px;text-align:left}.footer-status i{width:7px;height:7px;background:var(--text-muted);border-radius:50%;flex-shrink:0}.footer-status i.is-ready{background:var(--success)}.footer-status i.is-busy{background:var(--brand)}.editor-footer>div{display:flex;gap:8px;flex-shrink:0}.editor-footer :deep(.el-button+.el-button){margin-left:0}
:global(.audio-asset-editor.el-dialog){padding:0;overflow:hidden;border-radius:var(--radius-md)}:global(.audio-asset-editor .el-dialog__header){margin:0;padding:22px 26px;border-bottom:1px solid var(--border-default)}:global(.audio-asset-editor .el-dialog__headerbtn){top:19px;right:17px}:global(.audio-asset-editor .el-dialog__body){padding:24px 26px;max-height:calc(86vh - 156px);overflow:auto}:global(.audio-asset-editor .el-dialog__footer){padding:16px 26px;background:var(--surface-muted);border-top:1px solid var(--border-default)}
@media(max-width:760px){.editor-columns{grid-template-columns:1fr;gap:22px}.editor-form{padding:0;border-left:0;border-top:1px solid var(--border-default);padding-top:20px}.drop-zone{min-height:220px}.preview-stage{max-height:290px}.editor-heading p{max-width:88%;font-size:11px}.editor-footer{flex-wrap:wrap;gap:12px}.editor-footer>div{width:100%;justify-content:flex-end}:global(.audio-asset-editor .el-dialog__body){padding:18px}:global(.audio-asset-editor .el-dialog__header){padding:18px}:global(.audio-asset-editor .el-dialog__footer){padding:14px 18px}}
</style>
