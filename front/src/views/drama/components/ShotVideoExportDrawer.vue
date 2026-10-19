<template>
  <el-drawer v-model="visible" :title="task?.name || '视频打包下载'" size="min(1080px, 96vw)" append-to-body @closed="stop">
    <div v-if="task" class="space-y-4">
      <div class="flex justify-between items-center gap-3">
        <el-tag>{{ exportLabels[task.status] || task.status }}</el-tag>
        <div class="flex gap-2"><el-button v-if="active" type="danger" plain :loading="busy" @click="cancel">取消打包</el-button><el-button v-if="['FAILED', 'PARTIAL_SUCCESS'].includes(task.status)" :loading="busy" @click="retry">按原快照重试</el-button><el-button type="primary" :disabled="!task.downloadAvailable" :loading="downloading" @click="download">{{ task.concatVideos ? '下载拼接 MP4' : '下载 ZIP' }}</el-button></div>
      </div>
      <el-progress :percentage="task.progress" />
      <div class="text-sm">导出方式：{{ task.concatVideos ? '按剧作顺序拼接为单个 MP4' : '原视频 ZIP（不拼接）' }}</div>
      <div class="text-sm">{{ task.currentStage }} · 已读取 {{ exportBytes(task.bytesRead) }}</div>
      <div class="text-sm">已处理 {{ task.processed }}/{{ task.total }} · 成功 {{ task.succeeded }} · 失败 {{ task.failed }} · 跳过 {{ task.skipped }}</div>
      <el-alert v-if="task.errorMessage" type="error" :title="task.errorMessage" :closable="false" />
      <el-alert v-if="task.status === 'PARTIAL_SUCCESS'" type="warning" :title="task.concatVideos ? '部分镜头缺失或读取失败，拼接视频仅包含成功镜头，请核对下方明细。' : '部分镜头缺失或读取失败，ZIP 中包含失败清单，成功视频可正常下载。'" :closable="false" />
      <div v-if="task.expiresAt" class="text-sm text-[var(--text-secondary)]">{{ task.filename }} · {{ exportBytes(task.size) }} · 保留至 {{ task.expiresAt.replace('T', ' ') }}<span v-if="!task.downloadAvailable">（产物已过期）</span></div>
      <el-table :data="items" size="small" v-loading="loading">
        <el-table-column prop="executionOrder" label="顺序" width="65" />
        <el-table-column label="镜头" min-width="200"><template #default="{ row }"><div>{{ row.name }}</div><div class="text-xs text-[var(--text-muted)]">{{ row.episodeName }} / {{ row.sceneName }} / {{ row.groupName }}</div></template></el-table-column>
        <el-table-column label="当前版本" width="110"><template #default="{ row }">{{ row.takeNo == null ? '直接绑定' : `Take ${row.takeNo}` }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }">{{ exportLabels[row.status] || row.status }}</template></el-table-column>
        <el-table-column label="大小" width="100"><template #default="{ row }">{{ row.size == null ? '—' : exportBytes(row.size) }}</template></el-table-column>
        <el-table-column prop="errorMessage" label="说明" min-width="160" show-overflow-tooltip />
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="50" :total="total" layout="total, prev, pager, next" @current-change="refresh" />
      <p class="text-xs text-[var(--text-muted)]">导出使用创建任务时的绑定快照，不包含后续新视频。关闭窗口后任务继续运行。</p>
    </div>
  </el-drawer>
</template>
<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useTaskCenterStore } from '@/store/taskCenter'
import { videoExportApi, exportLabels, exportBytes, type ExportTask, type ExportItem } from '@/api/shot-video-export'
const store = useTaskCenterStore(), visible = ref(false), task = ref<ExportTask | null>(null), items = ref<ExportItem[]>([])
const page = ref(1), total = ref(0), loading = ref(false), busy = ref(false), downloading = ref(false)
const active = computed(() => task.value && ['QUEUED', 'PACKAGING', 'UPLOADING'].includes(task.value.status))
let id = '', fetching = false, requested = false, retryRequestId = ''
async function refresh() {
  if (fetching) { requested = true; return }
  fetching = true; loading.value = true
  const currentId = id, currentPage = page.value
  try { const [detail, rows] = await Promise.all([videoExportApi.detail(currentId), videoExportApi.items(currentId, currentPage)]); if (id === currentId && page.value === currentPage) { task.value = detail; items.value = rows.records; total.value = Number(rows.total) } }
  catch (e: any) { ElMessage.error(e.message || '读取导出任务失败') }
  finally { fetching = false; loading.value = false; if (requested && visible.value) { requested = false; void refresh() } }
}
function updated(event: Event) { const data = (event as CustomEvent).detail; if (visible.value && data?.sourceType === 'SHOT_VIDEO_EXPORT' && String(data.taskId) === id) void refresh() }
function connected() { if (visible.value) void refresh() }
async function open(taskId: string) { stop(); id = String(taskId); retryRequestId = crypto.randomUUID(); page.value = 1; items.value = []; task.value = null; visible.value = true; window.addEventListener('task-center-task-updated', updated); window.addEventListener('task-center-connected', connected); store.start(); await refresh() }
function stop() { window.removeEventListener('task-center-task-updated', updated); window.removeEventListener('task-center-connected', connected); requested = false }
async function cancel() { busy.value = true; try { const result = await videoExportApi.cancel(id); if (!result.cancelled) ElMessage.warning(result.message); else ElMessage.success(result.message); await refresh() } catch (e: any) { ElMessage.error(e.message || '取消失败') } finally { busy.value = false } }
async function retry() { busy.value = true; try { const created = await videoExportApi.retry(id, retryRequestId); await open(String(created.id)); ElMessage.success('已按原快照重新加入打包队列') } catch (e: any) { ElMessage.error(e.message || '重试失败') } finally { busy.value = false } }
async function download() { downloading.value = true; try { const link = await videoExportApi.download(id); const anchor = document.createElement('a'); anchor.href = link.url; anchor.download = link.filename; anchor.rel = 'noopener'; document.body.appendChild(anchor); anchor.click(); anchor.remove() } catch (e: any) { ElMessage.error(e.message || '获取下载链接失败'); await refresh() } finally { downloading.value = false } }
onUnmounted(stop)
defineExpose({ open })
</script>
