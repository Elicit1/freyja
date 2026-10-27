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

    <AudioAssetEditorDialog v-model="editorVisible" :dramas="dramas" :asset-id="editingId" :initial-drama-id="initialDramaId" :initial-shot-id="initialShotId" :source-type="selection ? 'SHOT_UPLOAD' : 'MANUAL_UPLOAD'" @saved="saved" @cancelled="emit('asset-cancelled')" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AudioAssetEditorDialog from './AudioAssetEditorDialog.vue'
import { AUDIO_KIND_OPTIONS, audioKindLabel } from '@/constants/audioAssetOptions'
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
const filterShots = ref<ShotOption[]>([])
const editorVisible = ref(false), editingId = ref<string>(), initialDramaId = ref('0'), initialShotId = ref<string>()
let listVersion = 0, alive = true
async function load() {
  const version = ++listVersion; loading.value = true
  try { const result = await audioAssetApi.getPage({ ...query, dramaId: query.dramaId || undefined, shotId: query.shotId || undefined, audioKind: query.audioKind || undefined, sourceType: query.sourceType || undefined, availableDramaId: props.selection ? String(props.dramaId || '0') : undefined, status: props.selection ? 1 : query.status }); if (alive && version === listVersion) { audios.value = result.records; total.value = result.total } }
  catch { /* request interceptor displays errors */ }
  finally { if (version === listVersion) loading.value = false }
}
function search() { query.current = 1; void load() }
function reset() { Object.assign(query, { current: 1, name: undefined, dramaId: undefined, shotId: undefined, sourceType: undefined, audioKind: undefined, status: undefined }); filterShots.value = []; void load() }
async function loadFilterShots() { const id = query.dramaId; filterShots.value = id && id !== '0' ? await shotApi.getOptionsByDrama(id) : [] }
function handleCreate(dramaId?: string) { editingId.value = undefined; initialDramaId.value = dramaId || query.dramaId || '0'; initialShotId.value = props.selection && props.shotId ? String(props.shotId) : undefined; editorVisible.value = true }
function openAsset(id: string) { editingId.value = String(id); editorVisible.value = true }
async function saved(asset: ResAudio) { emit('asset-created', String(asset.id)); await load(); if (alive && props.selection && asset.status === 1) emit('selected', asset) }
async function remove(row: ResAudio) {
  try { await ElMessageBox.confirm(`确定删除音频素材「${row.name}」？未被其他素材、分镜或历史记录引用的音频文件也会删除。`, '删除确认', { type: 'warning' }) }
  catch { return }
  await audioAssetApi.delete(row.id); ElMessage.success('已删除'); await load()
}
onMounted(() => { void load(); void dramaApi.getOptions().then(rows => dramas.value = rows) })
function onlyPlay(event: Event) { document.querySelectorAll<HTMLAudioElement>('audio').forEach(audio => { if (audio !== event.target) audio.pause() }) }
onBeforeUnmount(() => { alive = false; listVersion++ })
defineExpose({ handleCreate, openAsset })
</script>
