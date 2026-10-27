<template>
  <div class="space-y-4">
    <el-form inline class="production-asset-filters studio-card p-4" @submit.prevent="search">
      <el-form-item label="素材名称"><el-input v-model="query.name" placeholder="搜索音频素材" clearable @keyup.enter="search" /></el-form-item>
      <el-form-item v-if="!selection" label="所属库">
        <el-select v-model="query.dramaId" placeholder="全部所属库" clearable filterable class="!w-44" @change="query.shotId = undefined; loadFilterShots()">
          <el-option label="全局公共库" value="0" />
          <el-option v-for="drama in dramas" :key="String(drama.id)" :value="String(drama.id)" :label="drama.title" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="!selection" label="分镜">
        <el-select v-model="query.shotId" placeholder="全部分镜" clearable class="!w-40" :disabled="!query.dramaId || query.dramaId === '0'">
          <el-option v-for="shot in filterShots" :key="String(shot.id)" :value="String(shot.id)" :label="shot.shotName || `镜 ${shot.shotNo}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="种类"><el-select v-model="query.audioKind" placeholder="全部种类" clearable class="!w-40"><el-option v-for="kind in AUDIO_KIND_OPTIONS" :key="kind.value" :value="kind.value" :label="kind.label" /></el-select></el-form-item>
      <el-form-item label="来源"><el-select v-model="query.sourceType" placeholder="全部来源" clearable class="!w-36"><el-option label="素材中心上传" value="MANUAL_UPLOAD" /><el-option label="分镜上传" value="SHOT_UPLOAD" /></el-select></el-form-item>
      <el-form-item v-if="!selection" label="状态"><el-select v-model="query.status" placeholder="全部状态" clearable class="!w-28"><el-option label="启用" :value="1" /><el-option label="停用" :value="0" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
    </el-form>
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div class="flex items-center gap-3"><el-button type="primary" @click="handleCreate(selection ? String(dramaId || '0') : undefined)">上传音频</el-button><span class="text-xs text-[var(--text-muted)]">共 {{ total }} 个音频素材{{ selection ? ' · 可选公共库及当前短剧已启用素材' : '' }}</span></div>
      <el-radio-group v-model="view" size="small"><el-radio-button value="grid">卡片网格</el-radio-button><el-radio-button value="table">表格列表</el-radio-button></el-radio-group>
    </div>
    <div v-if="view === 'grid'" v-loading="loading" class="min-h-48">
      <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        <article v-for="item in audios" :key="item.id" class="studio-card overflow-hidden">
          <audio :src="item.audioUrl" controls preload="metadata" class="w-full h-12" @play="onlyPlay" />
          <div class="p-3 space-y-2">
            <div class="flex items-center justify-between gap-2"><strong class="truncate text-sm">{{ item.name }}</strong><el-tag size="small" :type="item.status === 1 ? 'success' : 'info'">{{ item.status === 1 ? '启用' : '停用' }}</el-tag></div>
            <div class="text-xs text-[var(--text-muted)]">{{ item.duration ?? '待探测' }} 秒 · {{ audioKindLabel(item.audioKind) }}</div>
            <div class="flex flex-wrap gap-1"><el-tag size="small">{{ item.dramaTitle }}</el-tag><el-tag size="small" type="info">{{ item.sourceType === 'SHOT_UPLOAD' ? '分镜上传' : '素材中心上传' }}</el-tag><el-tag v-if="item.shotName" size="small" type="info">{{ item.shotName }}</el-tag></div>
            <p class="text-xs text-[var(--text-muted)] line-clamp-2 min-h-8">{{ item.description || '暂无素材描述' }}</p>
            <div class="flex justify-end gap-2"><el-button v-if="selection" type="primary" size="small" @click="emit('selected', item)">使用此音频</el-button><template v-else><el-button size="small" link type="primary" @click="openAsset(item.id)">编辑</el-button><el-button size="small" link type="danger" @click="remove(item)">删除</el-button></template></div>
          </div>
        </article>
      </div>
      <el-empty v-if="!loading && !audios.length" description="暂无音频素材，可在资产中心上传，分镜上传的音频也会自动入库" />
    </div>
    <el-table v-else v-loading="loading" :data="audios" stripe>
      <el-table-column label="音频" width="300"><template #default="{ row }"><audio :src="row.audioUrl" controls preload="metadata" class="w-full h-12" @play="onlyPlay" /></template></el-table-column>
      <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
      <el-table-column prop="dramaTitle" label="所属库" min-width="120" />
      <el-table-column prop="shotName" label="关联分镜" min-width="100" />
      <el-table-column prop="duration" label="时长（秒）" width="110" />
      <el-table-column label="种类" width="130"><template #default="{ row }">{{ audioKindLabel(row.audioKind) }}</template></el-table-column>
      <el-table-column label="来源" width="120"><template #default="{ row }">{{ row.sourceType === 'SHOT_UPLOAD' ? '分镜上传' : '素材中心上传' }}</template></el-table-column>
      <el-table-column label="状态" width="80"><template #default="{ row }">{{ row.status === 1 ? '启用' : '停用' }}</template></el-table-column>
      <el-table-column label="操作" width="140" fixed="right"><template #default="{ row }"><el-button v-if="selection" link type="primary" @click="emit('selected', row)">使用此音频</el-button><template v-else><el-button link type="primary" @click="openAsset(row.id)">编辑</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template></template></el-table-column>
    </el-table>
    <el-pagination class="production-pagination justify-end" v-model:current-page="query.current" v-model:page-size="query.size" :total="total" :page-sizes="[12, 24, 48]" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="search" />

    <el-drawer v-model="editorVisible" :title="editing.id ? '编辑音频素材' : '上传音频素材'" size="640px" destroy-on-close :close-on-click-modal="false" :show-close="!uploading && !saving">
      <el-form label-position="top" :disabled="uploading || saving || editorLoading" v-loading="editorLoading">
        <el-form-item label="素材名称" required><el-input v-model="editing.name" maxlength="200" placeholder="上传时可留空，使用文件名" /></el-form-item>
        <div class="grid grid-cols-2 gap-4">
          <el-form-item label="所属库"><el-select v-model="editing.dramaId" filterable class="w-full" @change="editing.shotId = undefined; loadEditorShots()"><el-option label="全局公共库" value="0" /><el-option v-for="drama in dramas" :key="String(drama.id)" :value="String(drama.id)" :label="drama.title" /></el-select></el-form-item>
          <el-form-item label="关联分镜"><el-select v-model="editing.shotId" clearable filterable class="w-full" :disabled="!editing.dramaId || editing.dramaId === '0'"><el-option v-for="shot in editorShots" :key="String(shot.id)" :value="String(shot.id)" :label="shot.shotName || `镜 ${shot.shotNo}`" /></el-select></el-form-item>
        </div>
        <el-form-item label="音频种类" required><el-select v-model="editing.audioKind" placeholder="请选择种类" class="w-full"><el-option v-for="kind in AUDIO_KIND_OPTIONS" :key="kind.value" :value="kind.value" :label="kind.label" /></el-select></el-form-item>
        <p class="text-xs text-[var(--text-muted)] mb-4">{{ AUDIO_KIND_OPTIONS.find(kind => kind.value === editing.audioKind)?.description || '种类帮助整理素材；镜头中的参考关系与具体用途需要单独选择。' }}</p>
        <el-upload v-if="!editing.id" :show-file-list="false" :before-upload="upload" accept=".mp3,.wav,.flac,.m4a,.ogg" :disabled="uploading"><el-button type="primary" :loading="uploading">选择并上传音频</el-button><template #tip><p class="text-xs text-[var(--text-muted)] mt-2">支持 MP3 / WAV / FLAC / M4A / OGG，最大 30 MiB、10 分钟。H3 参考音频单段须为 2～15 秒，上传前请裁好所需片段。上传后即入库，可继续编辑素材信息。</p></template></el-upload>
        <template v-else><audio :src="editing.audioUrl" controls preload="metadata" class="w-full h-12" @play="onlyPlay" /><p class="text-xs text-[var(--text-muted)] mb-4">{{ editing.originalFilename || editing.name }} · {{ editing.duration ?? '待探测' }} 秒 · {{ audioKindLabel(editing.audioKind) }}</p></template>
        <el-form-item label="对白 / 歌词 / 转录文本"><el-input v-model="editing.text" type="textarea" :rows="3" maxlength="10000" placeholder="仅填写已知原文；不确定的内容保持为空" /></el-form-item>
        <el-form-item label="语言"><el-select v-model="editing.language" clearable placeholder="未声明"><el-option v-for="option in REFERENCE_AUDIO_LANGUAGE_OPTIONS" :key="option.value" :value="option.value" :label="option.label" /></el-select></el-form-item>
        <el-form-item label="素材描述"><el-input v-model="editing.description" type="textarea" :rows="4" maxlength="4000" placeholder="记录声音内容、音乐风格、音效质感、节奏或适用用途" /></el-form-item>
        <div class="grid grid-cols-2 gap-4"><el-form-item label="排序"><el-input-number v-model="editing.sortOrder" :min="0" :max="9999" /></el-form-item><el-form-item label="状态"><el-radio-group v-model="editing.status"><el-radio :value="1">启用</el-radio><el-radio :value="0">停用</el-radio></el-radio-group></el-form-item></div>
        <el-form-item label="备注"><el-input v-model="editing.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="uploading || saving" @click="editorVisible = false">返回列表</el-button><el-button type="primary" :disabled="!editing.id || uploading || editorLoading" :loading="saving" @click="save">保存配置</el-button></template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { AUDIO_KIND_OPTIONS, audioKindLabel } from '@/constants/audioAssetOptions'
import { REFERENCE_AUDIO_LANGUAGE_OPTIONS } from '@/constants/referenceMediaOptions'
import { audioAssetApi } from '@/api/res-audio'
import { dramaApi, shotApi } from '@/api/drama'
import type { DramaOption } from '@/types/drama'
import type { ResAudio, ResAudioQuery } from '@/types/resource'
const props = defineProps<{ selection?: boolean; dramaId?: string | number; shotId?: string | number }>()
const emit = defineEmits<{ (e: 'selected', audio: ResAudio): void; (e: 'asset-created', id: string): void; (e: 'asset-cancelled'): void }>()
const query = reactive<ResAudioQuery>({ current: 1, size: 12 })
const audios = ref<ResAudio[]>([]), total = ref(0), loading = ref(false), view = ref('grid')
const dramas = ref<DramaOption[]>([])
type ShotOption = { id: string; shotName?: string; shotNo: number }
const filterShots = ref<ShotOption[]>([]), editorShots = ref<ShotOption[]>([])
const editorVisible = ref(false), editing = ref<Partial<ResAudio>>({}), uploading = ref(false), saving = ref(false), editorLoading = ref(false)
let listVersion = 0, editVersion = 0, alive = true
async function load() {
  const version = ++listVersion; loading.value = true
  try { const result = await audioAssetApi.getPage({ ...query, dramaId: query.dramaId || undefined, shotId: query.shotId || undefined, audioKind: query.audioKind || undefined, sourceType: query.sourceType || undefined, availableDramaId: props.selection ? String(props.dramaId || '0') : undefined, status: props.selection ? 1 : query.status }); if (alive && version === listVersion) { audios.value = result.records; total.value = result.total } }
  catch { /* request interceptor displays errors */ }
  finally { if (version === listVersion) loading.value = false }
}
function search() { query.current = 1; void load() }
function reset() { Object.assign(query, { current: 1, name: undefined, dramaId: undefined, shotId: undefined, sourceType: undefined, audioKind: undefined, status: undefined }); filterShots.value = []; void load() }
async function loadFilterShots() { const id = query.dramaId; filterShots.value = id && id !== '0' ? await shotApi.getOptionsByDrama(id) : [] }
async function loadEditorShots() { const id = editing.value.dramaId; const version = editVersion; const options = id && id !== '0' ? await shotApi.getOptionsByDrama(id) : []; if (version === editVersion && id === editing.value.dramaId) editorShots.value = options }
function handleCreate(dramaId?: string) { editVersion++; editorLoading.value = false; editing.value = { dramaId: dramaId || query.dramaId || '0', shotId: props.selection && props.shotId ? String(props.shotId) : undefined, name: '', audioKind: undefined, status: 1, sortOrder: 0 }; editorShots.value = []; editorVisible.value = true; void loadEditorShots() }
async function openAsset(id: string) { const version = ++editVersion; editorVisible.value = true; editorLoading.value = true; try { const row = await audioAssetApi.getById(String(id)); if (alive && version === editVersion) { editing.value = row; await loadEditorShots() } } finally { if (version === editVersion) editorLoading.value = false } }
async function upload(file: File) {
  if (!editing.value.audioKind) { ElMessage.warning('请先选择音频种类'); return false }
  if (file.size > 30 * 1024 * 1024) { ElMessage.warning('音频不能超过 30 MiB'); return false }
  uploading.value = true; const version = editVersion
  try { const row = await audioAssetApi.upload(file, editing.value.dramaId, editing.value.shotId || undefined, editing.value.name, props.selection ? 'SHOT_UPLOAD' : 'MANUAL_UPLOAD', { ...editing.value, language: editing.value.language || undefined }); if (alive && version === editVersion) { editing.value = { ...editing.value, ...row }; ElMessage.success('音频已入库'); void load() } }
  finally { uploading.value = false }
  return false
}
async function save() {
  if (!editing.value.name?.trim()) { ElMessage.warning('请填写素材名称'); return }
  saving.value = true
  try { await audioAssetApi.update({ ...editing.value, shotId: editing.value.shotId || undefined, language: editing.value.language || undefined }); editorVisible.value = false; ElMessage.success('保存成功'); emit('asset-created', String(editing.value.id)); await load() }
  finally { saving.value = false }
}
async function remove(row: ResAudio) {
  try { await ElMessageBox.confirm(`确定删除音频素材「${row.name}」？未被其他素材、分镜或历史记录引用的音频文件也会删除。`, '删除确认', { type: 'warning' }) }
  catch { return }
  await audioAssetApi.delete(row.id); ElMessage.success('已删除'); await load()
}
onMounted(() => { void load(); void dramaApi.getOptions().then(rows => dramas.value = rows) })
function onlyPlay(event: Event) { document.querySelectorAll<HTMLAudioElement>('audio').forEach(audio => { if (audio !== event.target) audio.pause() }) }
onBeforeUnmount(() => { alive = false; listVersion++; editVersion++ })
defineExpose({ handleCreate, openAsset })
</script>
