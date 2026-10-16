<template>
  <el-dialog v-model="visible" title="视频打包下载" width="min(1200px, 96vw)" :close-on-click-modal="false" destroy-on-close>
    <div class="flex items-center gap-3 mb-4"><span>短剧</span><el-select v-model="dramaId" filterable class="!w-80" placeholder="选择短剧" @change="loadTree"><el-option v-for="drama in dramas" :key="String(drama.id)" :value="String(drama.id)" :label="drama.title" /></el-select><el-button @click="openHistory">导出历史</el-button></div>
    <div class="flex gap-5 min-h-[450px]" v-loading="loading">
      <aside class="w-72 shrink-0 border-r border-[var(--border-default)] pr-4">
        <el-input v-model="filter" clearable placeholder="筛选分集、场景、镜头组或分镜" class="mb-2" />
        <div class="flex flex-wrap gap-1 mb-2"><el-button link size="small" @click="selectAll(false)">全选整个短剧</el-button><el-button v-if="filter" link size="small" @click="selectAll(true)">仅选择搜索结果</el-button><el-button link size="small" @click="clear">清空</el-button></div>
        <el-scrollbar height="390px"><el-tree ref="tree" :data="nodes" show-checkbox node-key="key" :props="{ label: 'label', children: 'children' }" :filter-node-method="filterNode" @check="selectionChanged"><template #default="{ data }"><span class="text-xs truncate" :title="data.label">{{ data.label }} <span class="text-[var(--text-muted)]">{{ data.videoCount }}/{{ data.shotCount }}</span></span></template></el-tree></el-scrollbar>
      </aside>
      <section class="flex-1 min-w-0 space-y-3">
        <el-input v-model="name" placeholder="导出任务名称（可选）" maxlength="100" />
        <el-checkbox v-model="skipMissing">跳过未绑定当前视频的分镜</el-checkbox>
        <el-alert type="info" title="每镜导出一个当前绑定视频，保留分集 / 场景 / 镜头组目录。正在渲染的镜头使用当前已有视频，不等待新结果。" :closable="false" />
        <div class="text-sm">已选 {{ selected.length }} 镜<span v-if="preview"> · 可导出 {{ preview.accepted }} · 无视频 {{ preview.missing }} · 不可导出 {{ preview.blocked }} · 视频大小 {{ exportBytes(preview.knownBytes) }}</span></div>
        <el-table :data="preview?.items || []" height="300" size="small" empty-text="选择范围后点击检查">
          <el-table-column label="镜头" min-width="180"><template #default="{ row }"><div>{{ row.name }}</div><div class="text-xs text-[var(--text-muted)]">{{ row.episodeName }} / {{ row.sceneName }} / {{ row.groupName }}</div></template></el-table-column>
          <el-table-column label="当前版本" width="100"><template #default="{ row }">{{ row.takeNo == null ? '直接绑定' : `Take ${row.takeNo}` }}</template></el-table-column>
          <el-table-column label="大小" width="100"><template #default="{ row }">{{ row.sourceSize == null ? '未知' : exportBytes(row.sourceSize) }}</template></el-table-column>
          <el-table-column label="检查结果" min-width="170"><template #default="{ row }"><span :class="row.status === 'READY' ? 'text-emerald-600' : 'text-amber-600'">{{ row.errorMessage || exportLabels[row.status] }}</span></template></el-table-column>
        </el-table>
      </section>
    </div>
    <template #footer><el-button @click="visible = false">关闭</el-button><el-button :disabled="!selected.length || submitting" :loading="checking" @click="check">检查</el-button><el-button type="primary" :disabled="!canSubmit" :loading="submitting" @click="submit">生成 ZIP（{{ preview?.accepted || 0 }} 个视频）</el-button></template>
  </el-dialog>
  <el-dialog v-model="historyVisible" title="视频打包历史" width="min(850px, 94vw)" append-to-body><el-table :data="history" v-loading="historyLoading"><el-table-column prop="name" label="任务" min-width="200" /><el-table-column label="状态" width="120"><template #default="{ row }">{{ exportLabels[row.status] }}</template></el-table-column><el-table-column prop="createdAt" label="创建时间" width="190" /><el-table-column width="90"><template #default="{ row }"><el-button link type="primary" @click="details?.open(String(row.id))">详情</el-button></template></el-table-column></el-table><el-pagination v-model:current-page="historyPage" :page-size="20" :total="historyTotal" layout="total,prev,pager,next" @current-change="loadHistory" /></el-dialog>
  <ShotVideoExportDrawer ref="details" />
</template>
<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage, ElTree } from 'element-plus'
import { dramaApi } from '@/api/drama'
import type { DramaOption } from '@/types/drama'
import { videoExportApi, exportLabels, exportBytes, type ExportNode, type ExportPreview, type ExportRequest, type ExportTask } from '@/api/shot-video-export'
import ShotVideoExportDrawer from './ShotVideoExportDrawer.vue'
const visible = ref(false), loading = ref(false), checking = ref(false), submitting = ref(false), dramaId = ref(''), name = ref(''), filter = ref(''), skipMissing = ref(true)
const dramas = ref<DramaOption[]>([]), nodes = ref<ExportNode[]>([]), selected = ref<string[]>([]), tree = ref<InstanceType<typeof ElTree>>(), preview = ref<ExportPreview | null>(null), details = ref<InstanceType<typeof ShotVideoExportDrawer>>()
const historyVisible = ref(false), historyLoading = ref(false), history = ref<ExportTask[]>([]), historyPage = ref(1), historyTotal = ref(0)
let version = 0, requestId = '', pending: ExportRequest | null = null
const canSubmit = computed(() => !!preview.value?.accepted && !preview.value.blocked && (skipMissing.value || !preview.value.missing) && !checking.value && !loading.value)
function invalidate() { version++; preview.value = null; pending = null; requestId = crypto.randomUUID() }
watch([name, skipMissing], invalidate, { flush: 'sync' })
watch(filter, value => tree.value?.filter(value))
function filterNode(value: string, data: Record<string, any>) { return !value || String(data.searchText).toLowerCase().includes(value.toLowerCase()) }
function selectionChanged() { selected.value = (tree.value?.getCheckedNodes(false, false) || []).filter(n => n.type === 'SHOT').map(n => String(n.id)); invalidate() }
function clear() { tree.value?.setCheckedKeys([]); selected.value = []; invalidate() }
function selectAll(searchOnly: boolean) { const keys: string[] = []; const visit = (list: ExportNode[]) => list.forEach(n => { if (n.type === 'SHOT' && (!searchOnly || filterNode(filter.value, n))) keys.push(n.key); visit(n.children) }); visit(nodes.value); tree.value?.setCheckedKeys(keys); selectionChanged() }
async function loadTree() {
  const id = dramaId.value; loading.value = true; nodes.value = []; selected.value = []; filter.value = ''; invalidate()
  if (!id) { loading.value = false; return }
  try { const result = await videoExportApi.tree(id); if (id !== dramaId.value) return; const annotate = (list: ExportNode[], parent = '') => list.forEach(n => { n.searchText = `${parent} ${n.label}`; annotate(n.children, n.searchText) }); annotate(result); nodes.value = result; await nextTick(); clear() }
  catch (e: any) { ElMessage.error(e.message || '读取分镜失败') } finally { if (id === dramaId.value) loading.value = false }
}
async function open(id?: string | number) {
  visible.value = true; name.value = ''; skipMissing.value = true
  try { dramas.value = await dramaApi.getOptions(); dramaId.value = id == null ? String(dramas.value[0]?.id || '') : String(id); await loadTree() } catch (e: any) { ElMessage.error(e.message || '读取短剧失败') }
}
async function check() {
  const current = version; checking.value = true
  const request: ExportRequest = { dramaId: dramaId.value, name: name.value.trim() || undefined, shotIds: [...selected.value], missingVideoPolicy: skipMissing.value ? 'SKIP' : 'FAIL' }
  try { const result = await videoExportApi.preview(request); if (current !== version) return; preview.value = result; pending = { ...request, shotIds: result.shotIds, expectedPreviewHash: result.previewHash, clientRequestId: requestId } }
  catch (e: any) { ElMessage.error(e.message || '检查失败') } finally { checking.value = false }
}
async function submit() { if (!pending || !canSubmit.value) return; submitting.value = true; try { const task = await videoExportApi.create(pending); visible.value = false; ElMessage.success('已加入后台打包队列，可以关闭窗口'); await details.value?.open(String(task.id)) } catch (e: any) { ElMessage.error(e.message || '提交失败，请重新检查后重试') } finally { submitting.value = false } }
async function loadHistory() { historyLoading.value = true; try { const result = await videoExportApi.list(dramaId.value || undefined, historyPage.value); history.value = result.records; historyTotal.value = Number(result.total) } catch (e: any) { ElMessage.error(e.message || '读取历史失败') } finally { historyLoading.value = false } }
async function openHistory() { historyVisible.value = true; historyPage.value = 1; await loadHistory() }
defineExpose({ open })
</script>
