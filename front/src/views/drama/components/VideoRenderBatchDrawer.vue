<template>
  <el-drawer v-model="visible" :title="batch?.name || '批量视频渲染'" size="min(1080px, 96vw)" append-to-body @closed="stop">
    <div v-if="batch" class="space-y-4">
      <div class="flex justify-between"><span>{{ labels[batch.status] || batch.status }} · {{ batch.currentPhase }}<span v-if="batch.currentShot"> · {{ batch.currentShot }}</span></span><el-button v-if="canCancel" type="danger" plain :loading="cancelling" @click="cancel">取消整个批次</el-button></div>
      <el-progress :percentage="batch.progress" />
      <div>已处理 {{ batch.processed }}/{{ batch.total }} · 成功 {{ batch.succeeded }} · 失败 {{ batch.failed }} · 取消 {{ batch.cancelled }} · 跳过 {{ batch.skipped }}</div>
      <div class="flex gap-6 text-sm text-[var(--text-secondary)]"><span>REF2VA {{ batch.refProcessed }}/{{ batch.refTotal }}</span><span>FL2VA {{ batch.flProcessed }}/{{ batch.flTotal }}</span></div>
      <el-alert v-if="batch.upstreamStatus" type="warning" :closable="false" title="上游状态待核实，全局视频队列暂停派发；请先核实远端任务。" />
      <div v-if="batch.items.some(i => i.status === 'FAILED' && !i.archiveRecoverable)" class="flex gap-3"><el-select v-model="retrySource" class="!w-56"><el-option value="ORIGINAL_SNAPSHOT" label="按原始输入重试失败项" /><el-option value="CURRENT_SHOT" label="按当前分镜配置重试" /></el-select><el-button :loading="retrying" @click="retry">检查并重试失败项</el-button></div>
      <el-table :data="batch.items" size="small">
        <el-table-column prop="phase" label="阶段" width="90" /><el-table-column prop="name" label="分镜" min-width="150" />
        <el-table-column label="状态" width="100"><template #default="{ row }">{{ labels[row.status] || row.status }}</template></el-table-column>
        <el-table-column prop="errorMessage" label="说明" min-width="200" show-overflow-tooltip />
        <el-table-column label="操作" width="300"><template #default="{ row }"><el-button v-if="row.outputUrl" link type="primary" @click="videoUrl = row.outputUrl">预览</el-button><el-button link @click="openShot(row.shotId)">分镜</el-button><el-button v-if="row.status === 'FAILED' && row.archiveRecoverable && !batch.cancelRequested" link type="primary" @click="retryArchive(row.taskId)">重试归档</el-button><el-button v-if="row.status === 'NEEDS_REVIEW' && !batch.cancelRequested" link type="primary" :loading="recoveringTask === row.taskId" :disabled="!!recoveringTask" @click="recoverResult(row.taskId)">查询并取回结果</el-button><el-button v-if="row.status === 'NEEDS_REVIEW' || (row.status === 'CANCELLED' && row.upstreamStatus === 'UNCONFIRMED')" link type="warning" @click="reconcile(row.taskId)">核实</el-button></template></el-table-column>
      </el-table>
    </div>
    <el-dialog :model-value="!!videoUrl" title="视频结果" width="min(900px,90vw)" append-to-body @close="videoUrl = ''"><video v-if="videoUrl" :src="videoUrl" controls class="w-full max-h-[70vh]" /></el-dialog>
  </el-drawer>
</template>
<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { videoBatchApi, type VideoBatch, type VideoBatchRequest } from '@/api/video-render-batch'
import { useTaskCenterStore } from '@/store/taskCenter'
const taskCenter = useTaskCenterStore()
const router = useRouter(), visible = ref(false), batch = ref<VideoBatch | null>(null), videoUrl = ref('')
const recoveringTask = ref('')
const cancelling = ref(false), retrying = ref(false), retrySource = ref<'ORIGINAL_SNAPSHOT' | 'CURRENT_SHOT'>('ORIGINAL_SNAPSHOT')
let batchId = '', fetching = false, refreshRequested = false
const labels: Record<string, string> = { QUEUED: '排队中', DISPATCHING: '准备输入', RUNNING: '执行中', RENDERING: '渲染中', SUCCESS: '成功', FAILED: '失败', CANCELLED: '已取消', CANCELLING: '正在取消', SKIPPED: '已跳过', NEEDS_REVIEW: '待核实', PARTIAL_FAILED: '部分失败' }
const canCancel = computed(() => batch.value && !batch.value.cancelRequested && ['QUEUED', 'RUNNING', 'NEEDS_REVIEW'].includes(batch.value.status))
async function refresh() {
  if (fetching) { refreshRequested = true; return }
  fetching = true
  const id = batchId
  try { const result = await videoBatchApi.detail(id); if (id === batchId) batch.value = result }
  catch (e: any) { ElMessage.error(e.message || '读取批次失败') }
  finally { fetching = false; if (refreshRequested && visible.value) { refreshRequested = false; void refresh() } }
}
function onBatchUpdated(event: Event) {
  const task = (event as CustomEvent).detail
  if (visible.value && task?.sourceType === 'VIDEO_RENDER_BATCH' && String(task.taskId) === batchId) void refresh()
}
function onConnected() { if (visible.value) void refresh() }
async function open(id: string) {
  stop(); batchId = String(id); batch.value = null; visible.value = true
  window.addEventListener('task-center-task-updated', onBatchUpdated)
  window.addEventListener('task-center-connected', onConnected)
  taskCenter.start()
  await refresh()
}
function stop() {
  window.removeEventListener('task-center-task-updated', onBatchUpdated)
  window.removeEventListener('task-center-connected', onConnected)
  refreshRequested = false; videoUrl.value = ''
}
async function cancel() { cancelling.value = true; try { const result = await videoBatchApi.cancel(batchId); ElMessage.success(result.message); await refresh() } catch (e: any) { ElMessage.error(e.message || '取消失败') } finally { cancelling.value = false } }
async function openShot(shotId: string) { visible.value = false; await router.push({ path: '/drama', query: { dramaId: String(batch.value!.dramaId), resumeShotId: String(shotId), resumeAction: 'SHOT_DETAIL' } }) }
async function retry() {
  if (!batch.value) return
  retrying.value = true
  try {
    const options = batch.value.options
    const req: VideoBatchRequest = { ...options, name: `${batch.value.name} · 重试`, retryOfBatchId: batchId, inputSource: retrySource.value, clientRequestId: crypto.randomUUID(), existingVideoPolicy: 'CREATE_NEW_TAKE', shotIds: batch.value.items.filter(i => i.status === 'FAILED' && !i.archiveRecoverable).map(i => String(i.shotId)), expectedPreviewHash: undefined }
    const preview = await videoBatchApi.preview(req)
    if (!preview.accepted) { ElMessage.warning('没有可重试项，请检查模型和分镜配置'); return }
    await ElMessageBox.confirm(`可提交 ${preview.accepted} 镜，跳过或不可用 ${preview.skipped + preview.blocked} 镜。将创建新的渲染批次。`, '重试检查')
    const created = await videoBatchApi.create({ ...req, expectedPreviewHash: preview.previewHash }); await open(String(created.id))
  } catch (e: any) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '重试失败') } finally { retrying.value = false }
}
async function recoverResult(taskId: string) {
  recoveringTask.value = taskId
  try {
    const result = await videoBatchApi.recoverResult(batchId, taskId)
    if (result.status === 'QUEUED' || result.status === 'SUCCESS') ElMessage.success(result.message)
    else ElMessage.warning(result.message)
    await refresh()
  } catch (e: any) { ElMessage.error(e.message || '查询结果失败') }
  finally { recoveringTask.value = '' }
}
async function reconcile(taskId: string) {
  try { const { value } = await ElMessageBox.prompt('仅在已确认远端任务停止后填写核实依据（如网关任务记录）。此操作释放全局视频队列，不会重新生成视频。', '核实上游已停止', { inputValidator: value => !!value?.trim() || '请填写核实依据' }); await videoBatchApi.reconcile(batchId, taskId, value); await refresh() }
  catch (e: any) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '核实失败') }
}
async function retryArchive(taskId: string) { try { await videoBatchApi.retryArchive(batchId, taskId); ElMessage.success('已加入归档恢复队列，不重新生成视频'); await refresh() } catch (e: any) { ElMessage.error(e.message || '归档重试失败') } }
onUnmounted(stop)
defineExpose({ open })
</script>
