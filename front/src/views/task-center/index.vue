<template>
  <ProductionWorkspace title="任务中心" subtitle="让每一次生成，都有迹可循" :icon="Operation" class="task-center-workspace" body-class="task-center-body">
    <template #tag><span :class="['connection-state', { connected: store.connected }]"><i></i>{{ store.connected ? '实时同步' : '连接恢复中' }}</span></template>
    <template #actions><el-button class="refresh-button" :loading="store.loading" @click="refresh"><el-icon><Refresh /></el-icon>刷新任务</el-button></template>

    <div class="task-center-layout">
      <section class="task-metrics" aria-label="任务概况">
        <div class="metric-tile"><span class="metric-icon processing"><el-icon><Loading /></el-icon></span><div><span class="metric-label">处理中</span><strong>{{ processingCount }}</strong><span class="metric-caption">正在执行的任务</span></div></div>
        <div class="metric-tile"><span class="metric-icon waiting"><el-icon><Clock /></el-icon></span><div><span class="metric-label">排队等待</span><strong>{{ queuedCount }}</strong><span class="metric-caption">等待开始执行</span></div></div>
        <div class="metric-tile"><span class="metric-icon completed"><el-icon><CircleCheck /></el-icon></span><div><span class="metric-label">最近完成</span><strong>{{ completedCount }}</strong><span class="metric-caption">最近记录中的成功任务</span></div></div>
        <div class="metric-tile"><span class="metric-icon attention"><el-icon><Warning /></el-icon></span><div><span class="metric-label">需要关注</span><strong>{{ attentionCount }}</strong><span class="metric-caption">异常或等待你的处理</span></div></div>
      </section>

      <section class="task-collection" aria-label="任务列表" :aria-busy="store.loading">
        <div class="collection-heading">
          <el-tabs v-model="activeTab" class="task-tabs"><el-tab-pane name="active"><template #label>进行中 <span class="tab-count">{{ store.activeTasks.length }}</span></template></el-tab-pane><el-tab-pane name="history"><template #label>最近任务 <span class="tab-count">{{ store.recentTasks.length }}</span></template></el-tab-pane></el-tabs>
          <span class="collection-caption">{{ activeTab === 'active' ? '每一步进展，都在这里' : '回看生成记录与处理结果' }}</span>
        </div>
        <div class="task-filters">
          <el-input v-model="keyword" aria-label="搜索任务" clearable placeholder="搜索任务、项目或任务 ID" :prefix-icon="Search" class="task-search" />
          <el-select v-model="categoryFilter" aria-label="筛选任务类型" placeholder="全部类型" class="category-filter"><el-option label="全部类型" value="" /><el-option v-for="category in categories" :key="category.value" :value="category.value" :label="category.label" /></el-select>
          <el-select v-model="statusFilter" aria-label="筛选任务状态" placeholder="全部状态" class="status-filter"><el-option label="全部状态" value="" /><el-option v-for="option in statusOptions" :key="option.value" :value="option.value" :label="option.label" /></el-select>
          <div class="view-options" aria-label="任务展示方式"><button type="button" :class="{ selected: viewMode === 'grid' }" :aria-pressed="viewMode === 'grid'" aria-label="卡片视图" @click="viewMode = 'grid'"><el-icon><Grid /></el-icon></button><button type="button" :class="{ selected: viewMode === 'list' }" :aria-pressed="viewMode === 'list'" aria-label="列表视图" @click="viewMode = 'list'"><el-icon><List /></el-icon></button></div>
        </div>

        <el-scrollbar class="task-scroll" v-loading="store.loading && !sourceItems.length">
          <div v-if="!items.length" class="task-empty">
            <span class="empty-icon"><el-icon><component :is="hasFilters ? Search : activeTab === 'active' ? CircleCheck : Film" /></el-icon></span>
            <h3>{{ hasFilters ? '没有找到匹配的任务' : activeTab === 'active' ? '一切就绪，等待下一次创作' : '你的创作记录，将从这里开始' }}</h3>
            <p>{{ hasFilters ? '试试其他关键词，或清除筛选看看全部任务。' : activeTab === 'active' ? '从剧作、资产或制作工作台开始生成，我们会为你跟进每一步。' : '提交任务后，可以在这里回看最近的执行记录与结果。' }}</p>
            <el-button v-if="hasFilters" @click="clearFilters">清除筛选</el-button>
          </div>
          <div v-else :class="['task-grid', { 'task-list': viewMode === 'list' }]">
            <article v-for="task in items" :key="`${task.sourceType}:${task.taskId}`" :class="['task-card', { 'task-card-attention': needsAttention(task.status) }]">
              <header class="task-card-heading"><span :class="['task-category-icon', { render: task.category === 'RENDER', analysis: task.category === 'AI_ANALYSIS' }]"><el-icon><component :is="categoryIcon(taskCategory(task))" /></el-icon></span><div class="task-heading-copy"><div class="task-category-label">{{ getCategoryLabel(taskCategory(task)) }}<span v-if="task.unread" class="unread-dot" aria-label="未读任务"></span></div><button type="button" class="task-title" :title="task.title" @click="openTask(task)">{{ task.title }}</button></div><StatusPill :status="statusTone(task.status)" :label="statusLabel(task.status)" size="small" /></header>
              <p class="task-context" :title="getContextLabel(task)">{{ getContextLabel(task) }}</p>
              <div v-if="isActive(task.status)" class="task-progress">
                <div class="progress-heading"><span><i :class="{ queued: isQueued(task.status), attention: needsAttention(task.status) }"></i>{{ task.currentStage || (isQueued(task.status) ? '等待任务调度' : needsAttention(task.status) ? statusLabel(task.status) : '任务正在处理中') }}</span><strong v-if="!isQueued(task.status) && progressValue(task) !== undefined">{{ progressValue(task) }}<small>%</small></strong><span v-else class="progress-note">{{ isQueued(task.status) ? '等待中' : needsAttention(task.status) ? '等待处理' : '持续更新' }}</span></div>
                <el-progress v-if="!isQueued(task.status) && progressValue(task) !== undefined" :percentage="progressValue(task)" :show-text="false" :stroke-width="5" />
                <div v-else-if="isQueued(task.status)" class="queue-track"></div>
                <div v-else-if="needsAttention(task.status)" class="queue-track attention-track"></div>
                <div v-else class="unknown-progress"><span></span></div>
              </div>
              <div v-else class="task-outcome"><el-icon><component :is="task.status === 'SUCCESS' ? CircleCheck : needsAttention(task.status) ? Warning : Finished" /></el-icon>{{ task.currentStage || (task.status === 'SUCCESS' ? '已完成，可查看任务结果' : statusLabel(task.status)) }}</div>
              <p v-if="task.errorMessage && needsAttention(task.status)" class="task-error">{{ task.errorMessage }}</p>
              <footer class="task-card-footer"><time :datetime="task.finishedAt || task.startedAt || task.createdAt">{{ formatDate(task.finishedAt || task.startedAt || task.createdAt) }}</time><div class="task-card-actions"><el-popconfirm v-if="canCancel(task)" title="确定取消这个任务吗？" confirm-button-text="取消任务" cancel-button-text="继续执行" @confirm="cancelTask(task)"><template #reference><button type="button" class="cancel-task" :aria-label="`取消任务：${task.title}`">取消</button></template></el-popconfirm><button type="button" class="open-task" @click="openTask(task)">查看任务<el-icon><ArrowRight /></el-icon></button></div></footer>
            </article>
          </div>
        </el-scrollbar>
        <footer class="collection-footer"><span><i :class="{ connected: store.connected }"></i>{{ activeTab === 'active' ? '当前活跃任务' : '最近任务 · 最多显示 50 项' }}</span><span aria-live="polite">显示 {{ items.length }} / {{ sourceItems.length }} 项<span v-if="hasFilters"> · 已筛选</span></span></footer>
      </section>
    </div>

    <!-- AI 任务详情抽屉 -->
    <VideoRenderBatchDrawer ref="videoBatchDrawer" />
    <ShotVideoExportDrawer ref="videoExportDrawer" />
    <el-drawer
      v-model="inspectorVisible"
      :title="inspectorTask?.title || 'AI 任务'"
      size="min(720px, 92vw)"
      append-to-body
      @closed="handleInspectorClosed"
    >
      <div v-if="inspectorTask" class="flex h-full flex-col gap-4">
        <div class="text-xs text-[var(--text-muted)]">任务 {{ inspectorTask.taskId }} · {{ statusLabel(inspectorTask.status) }}</div>
        <div class="text-sm font-medium">实时记录</div>
        <pre class="min-h-32 max-h-[38vh] overflow-auto rounded-lg bg-[var(--surface-muted)] p-3 text-xs whitespace-pre-wrap break-all">{{ eventLog || '等待事件...' }}</pre>
        <div class="text-sm font-medium">结果</div>
        <pre class="min-h-32 flex-1 overflow-auto rounded-lg bg-[var(--surface-muted)] p-3 text-xs whitespace-pre-wrap break-all">{{ resultText || inspectorTask.errorMessage || '任务尚未完成' }}</pre>
      </div>
    </el-drawer>
  </ProductionWorkspace>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, CircleCheck, Clock, Connection, Document, Film, Finished, Grid, List, Loading, MagicStick, Operation, Refresh, Search, VideoCamera, Warning } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import ProductionWorkspace from '@/components/workspace/ProductionWorkspace.vue'
import StatusPill from '@/components/status/StatusPill.vue'
import VideoRenderBatchDrawer from '@/views/drama/components/VideoRenderBatchDrawer.vue'
import ShotVideoExportDrawer from '@/views/drama/components/ShotVideoExportDrawer.vue'
import { taskCenterApi } from '@/api/task-center'
import { useTaskCenterStore } from '@/store/taskCenter'
import type { TaskCenterItem } from '@/api/task-center'

const router = useRouter()
const route = useRoute()
const store = useTaskCenterStore()
const activeTab = ref<'active' | 'history'>('active')
const keyword = ref(''), categoryFilter = ref(''), statusFilter = ref('')
const viewMode = ref<'grid' | 'list'>('grid')
const sourceItems = computed(() => activeTab.value === 'active' ? store.activeTasks : store.recentTasks)
const hasFilters = computed(() => !!(keyword.value.trim() || categoryFilter.value || statusFilter.value))
const categories = [
  { value: 'RENDER', label: '图片 / 视频渲染' }, { value: 'AI_ANALYSIS', label: 'AI 分析' },
  { value: 'MEDIA_PROCESS', label: '视频处理' }, { value: 'AGENT', label: 'Agent' },
  { value: 'VIDEO_RENDER_BATCH', label: '批量渲染' }, { value: 'FILE_EXPORT', label: '视频打包' }
]
const statusOptions = computed(() => activeTab.value === 'active'
  ? [{ value: 'processing', label: '处理中' }, { value: 'queued', label: '排队等待' }, { value: 'attention', label: '需要关注' }]
  : [{ value: 'completed', label: '已完成' }, { value: 'attention', label: '需要关注' }, { value: 'cancelled', label: '已取消' }])
function isQueued(status: string) { return ['QUEUED', 'PENDING'].includes(status) }
function needsAttention(status: string) { return ['FAILED', 'PARTIAL_FAILED', 'PARTIAL_SUCCESS', 'CANCEL_UNCONFIRMED', 'NEEDS_REVIEW', 'BLOCKED', 'WAITING_USER'].includes(status) }
function isProcessing(status: string) { return ['RUNNING', 'RENDERING', 'RETRYING', 'WAITING_TASK', 'CANCELLING'].includes(status) }
const processingCount = computed(() => store.activeTasks.filter(task => isProcessing(task.status)).length)
const queuedCount = computed(() => store.activeTasks.filter(task => isQueued(task.status)).length)
const completedCount = computed(() => store.recentTasks.filter(task => task.status === 'SUCCESS').length)
const attentionCount = computed(() => new Set([...store.activeTasks, ...store.recentTasks].filter(task => needsAttention(task.status)).map(task => `${task.sourceType}:${task.taskId}`)).size)
const items = computed(() => {
  const query = keyword.value.trim().toLocaleLowerCase()
  return sourceItems.value.filter(task => {
    if (categoryFilter.value && taskCategory(task) !== categoryFilter.value) return false
    if (query && ![task.title, task.taskId, task.modelCode, task.dramaTitle, task.episodeName, task.sceneName, task.shotName].some(value => value?.toLocaleLowerCase().includes(query))) return false
    return !statusFilter.value || (statusFilter.value === 'processing' && isProcessing(task.status))
      || (statusFilter.value === 'queued' && isQueued(task.status)) || (statusFilter.value === 'attention' && needsAttention(task.status))
      || (statusFilter.value === 'completed' && task.status === 'SUCCESS') || (statusFilter.value === 'cancelled' && task.status === 'CANCELLED')
  })
})
watch(activeTab, () => { statusFilter.value = '' })
function clearFilters() { keyword.value = ''; categoryFilter.value = ''; statusFilter.value = '' }
function taskCategory(task: TaskCenterItem) { return task.sourceType === 'VIDEO_RENDER_BATCH' ? 'VIDEO_RENDER_BATCH' : task.category }
function categoryIcon(category: string) { return ({ RENDER: VideoCamera, AI_ANALYSIS: MagicStick, MEDIA_PROCESS: Film, AGENT: Connection, VIDEO_RENDER_BATCH: Grid, FILE_EXPORT: Document } as Record<string, typeof VideoCamera>)[category] || Operation }
function progressValue(task: TaskCenterItem) { return typeof task.progress === 'number' && Number.isFinite(task.progress) ? Math.min(100, Math.max(0, Math.round(task.progress))) : undefined }
const inspectorVisible = ref(false)
const inspectorTask = ref<TaskCenterItem | null>(null)
const eventLog = ref('')
const resultText = ref('')
const videoBatchDrawer = ref<InstanceType<typeof VideoRenderBatchDrawer>>()
const videoExportDrawer = ref<InstanceType<typeof ShotVideoExportDrawer>>()
let unsubscribeAi: (() => void) | null = null
let lastOpenedTaskId: string | null = null

function prettyResult(value: string) {
  try { return JSON.stringify(JSON.parse(value), null, 2) }
  catch { return value }
}

function closeInspector() {
  unsubscribeAi?.()
  unsubscribeAi = null
}

function clearRequestedTaskQuery(taskId: string) {
  if (route.query.taskSource !== 'AI_TASK' || route.query.taskId !== taskId) return
  const query = { ...route.query }
  delete query.taskSource
  delete query.taskId
  void router.replace({ query })
}

function handleInspectorClosed() {
  closeInspector()
  if (inspectorTask.value) clearRequestedTaskQuery(inspectorTask.value.taskId)
}

onUnmounted(closeInspector)

async function inspectAiTask(task: TaskCenterItem) {
  closeInspector()
  inspectorTask.value = task
  inspectorVisible.value = true
  eventLog.value = ''
  resultText.value = ''
  unsubscribeAi = store.subscribeAi(task.taskId, event => {
    if (event.type === 'result') resultText.value = prettyResult(event.data)
    else if (event.type === 'error') eventLog.value += `\n错误：${event.data}`
    else if (event.type === 'stage' || event.type === 'chunk') eventLog.value += event.data
    else if (event.type === 'channel_chunk') {
      try {
        const part = JSON.parse(event.data)
        eventLog.value += `[${part.channel || 'ALL'}] ${part.chunk || ''}`
      } catch { eventLog.value += event.data }
    }
  })
  try {
    const detail = await taskCenterApi.getAiTask(task.taskId)
    if (inspectorTask.value?.taskId === task.taskId) {
      if (detail.outputPayload && !resultText.value) resultText.value = prettyResult(detail.outputPayload)
      if (detail.errorMessage && !eventLog.value.includes(detail.errorMessage)) eventLog.value += `\n错误：${detail.errorMessage}`
    }
  } catch (error) {
    console.warn('读取 AI 任务结果失败', error)
  }
}

async function openRequestedAiTask() {
  if (route.query.taskSource === 'SHOT_VIDEO_EXPORT' && typeof route.query.taskId === 'string') {
    await videoExportDrawer.value?.open(route.query.taskId)
    return
  }
  const taskId = route.query.taskId
  if (route.query.taskSource === 'VIDEO_RENDER_BATCH' && typeof taskId === 'string') {
    if (lastOpenedTaskId !== taskId) { lastOpenedTaskId = taskId; await videoBatchDrawer.value?.open(taskId) }
    return
  }
  if (route.query.taskSource !== 'AI_TASK' || typeof taskId !== 'string') {
    lastOpenedTaskId = null
    return
  }
  if (lastOpenedTaskId === taskId) return
  lastOpenedTaskId = taskId
  try {
    const task = await taskCenterApi.getTask('AI_TASK', taskId)
    if (route.query.taskSource !== 'AI_TASK' || route.query.taskId !== taskId) return
    if (task) {
      activeTab.value = isActive(task.status) ? 'active' : 'history'
      store.markRead(task.taskId, task.sourceType)
      await inspectAiTask(task)
    } else {
      ElMessage.warning('任务不存在或已过期')
      clearRequestedTaskQuery(taskId)
    }
  } catch (error) {
    console.warn('打开 AI 任务失败', error)
    ElMessage.error('打开 AI 任务失败')
    clearRequestedTaskQuery(taskId)
  }
}

onMounted(() => {
  store.start()
  void refresh()
  void openRequestedAiTask()
})

watch(() => [route.query.taskSource, route.query.taskId], () => {
  void openRequestedAiTask()
})

async function refresh() {
  await Promise.all([store.refreshActive(), store.refreshHistory()])
}

async function openTask(task: TaskCenterItem) {
  store.markRead(task.taskId, task.sourceType)
  if (task.sourceType === 'VIDEO_RENDER_BATCH') { await videoBatchDrawer.value?.open(task.taskId); return }
  if (task.sourceType === 'SHOT_VIDEO_EXPORT') { await videoExportDrawer.value?.open(task.taskId); return }
  if (task.resumeAction === 'DUBBING_CHARACTER' && task.targetId) {
    await router.push({ path: '/dubbing/characters', query: { characterId: task.targetId } })
    return
  }
  if (task.resumeAction === 'AGENT_CHAT' && task.targetId) {
    window.dispatchEvent(new CustomEvent('agent-open-chat', { detail: { sessionId: task.targetId, runId: task.taskId } }))
    return
  }
  if ((task.resumeAction === 'SHOT_PROMPT_DERIVE' || task.resumeAction === 'SHOT_RENDER' || task.resumeAction === 'SHOT_DETAIL') && task.shotId) {
    await router.push({
      path: '/drama',
      query: cleanQuery({ dramaId: task.dramaId, episodeId: task.episodeId, sceneId: task.sceneId, resumeShotId: task.shotId, resumeAction: task.resumeAction, taskSource: task.sourceType, taskId: task.taskId })
    })
    return
  }
  if (task.resumeAction === 'SCRIPT_DECOMPOSE') {
    await router.push({ path: '/drama', query: cleanQuery({ dramaId: task.dramaId, resumeAction: task.resumeAction, taskId: task.taskId }) })
    return
  }
  if (task.sourceType === 'AI_TASK') await inspectAiTask(task)
}

function cleanQuery(values: Record<string, string | undefined>) {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value !== undefined && value !== ''))
}

function canCancel(task: TaskCenterItem) {
  return ['RENDER_TASK', 'AI_TASK', 'AGENT_RUN', 'VIDEO_RENDER_BATCH', 'SHOT_VIDEO_EXPORT'].includes(task.sourceType) && isActive(task.status) && task.status !== 'CANCELLING'
}

async function cancelTask(task: TaskCenterItem) {
  try {
    const result = await taskCenterApi.cancelTask(task.sourceType, task.taskId)
    if (!result.cancelled) throw new Error(result.message || '取消任务失败')
    if (result.upstreamStatus !== 'CONFIRMED') ElMessage.warning(result.message)
    else ElMessage.success(result.message || '任务已取消')
    await store.refreshActive()
  } catch (error: any) {
    ElMessage.error(error?.message || '取消任务失败')
  }
}

function isActive(status: string) { return ['QUEUED', 'PENDING', 'RUNNING', 'RENDERING', 'RETRYING', 'WAITING_TASK', 'WAITING_USER', 'BLOCKED', 'CANCELLING', 'NEEDS_REVIEW'].includes(status) }
function getCategoryLabel(category?: string) { return category === 'VIDEO_RENDER_BATCH' ? '批量渲染' : category === 'FILE_EXPORT' ? '视频打包' : category === 'AGENT' ? 'Agent' : category === 'AI_ANALYSIS' ? 'AI 分析' : category === 'MEDIA_PROCESS' ? '视频处理' : '渲染' }
function statusLabel(status: string) { return ({ QUEUED: '排队中', PENDING: '等待中', RUNNING: '运行中', RENDERING: '渲染中', RETRYING: '重试中', WAITING_TASK: '子任务执行中', BLOCKED: '需要调整摘要配置', WAITING_USER: '等待回答', CANCELLING: '正在取消', SUCCESS: '已完成', PARTIAL_SUCCESS: '部分完成', PARTIAL_FAILED: '部分失败', NEEDS_REVIEW: '已中断', FAILED: '失败', CANCELLED: '已取消', CANCEL_UNCONFIRMED: '请求已中断（上游未确认）' } as Record<string, string>)[status] || status }
function statusTone(status: string): string { return status === 'SUCCESS' ? 'success' : status === 'FAILED' ? 'failed' : needsAttention(status) ? 'warning' : isQueued(status) ? 'pending' : isActive(status) ? 'processing' : 'disabled' }
function getContextLabel(task: TaskCenterItem) { return [task.dramaTitle, task.episodeName, task.sceneName, task.shotName || (task.shotNo ? `镜头 ${task.shotNo}` : undefined)].filter(Boolean).join(' / ') || `任务 ID ${task.taskId}` }
function formatDate(value?: string) { if (!value) return '刚刚提交'; const date = new Date(value); return Number.isNaN(date.getTime()) ? '时间暂不可用' : date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }) }
</script>

<style scoped>
.task-center-workspace { container-type: inline-size; }
.task-center-workspace :deep(.task-center-body) { padding: 22px; overflow: hidden; }
.connection-state { display: inline-flex; align-items: center; gap: 6px; margin-left: 5px; padding: 4px 8px; border: 1px solid var(--border-default); border-radius: 5px; background: var(--surface-muted); font-size: 10px; font-weight: 400; color: var(--text-muted); }
.connection-state i, .collection-footer i { width: 5px; height: 5px; border-radius: 50%; background: var(--warning); }
.connection-state.connected { color: var(--success); background: color-mix(in srgb, var(--success) 6%, var(--surface)); border-color: color-mix(in srgb, var(--success) 15%, var(--border-default)); }.connection-state.connected i, .collection-footer i.connected { background: var(--success); }
.refresh-button { display: flex; gap: 6px; height: 33px; padding: 0 12px; border-radius: 7px; font-size: 11px; }
.task-center-layout { display: flex; flex-direction: column; gap: 20px; height: 100%; min-height: 0; }
.task-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; flex-shrink: 0; }
.metric-tile { display: flex; align-items: flex-start; gap: 13px; padding: 20px; border: 1px solid var(--border-default); border-radius: 11px; background: var(--surface); }
.metric-icon { display: grid; place-items: center; width: 34px; height: 34px; flex-shrink: 0; border-radius: 9px; font-size: 17px; }
.metric-icon.processing { color: var(--brand); background: var(--brand-soft); }.metric-icon.waiting { color: var(--warning); background: color-mix(in srgb, var(--warning) 9%, transparent); }.metric-icon.completed { color: var(--success); background: color-mix(in srgb, var(--success) 8%, transparent); }.metric-icon.attention { color: var(--danger); background: color-mix(in srgb, var(--danger) 7%, transparent); }
.metric-tile > div { display: grid; min-width: 0; gap: 8px; }.metric-label { margin-top: 2px; font-size: 11px; font-weight: 500; color: var(--text-secondary); }.metric-tile strong { font-size: 27px; font-weight: 600; line-height: 1.1; letter-spacing: -1px; font-variant-numeric: tabular-nums; color: var(--text-primary); }.metric-caption { font-size: 10px; color: var(--text-muted); }
.task-collection { display: flex; flex-direction: column; flex: 1; min-height: 0; overflow: hidden; border: 1px solid var(--border-default); border-radius: 12px; background: var(--surface); container-type: inline-size; }
.collection-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 57px; padding: 0 20px; border-bottom: 1px solid var(--border-default); flex-shrink: 0; }
.task-tabs :deep(.el-tabs__header) { margin: 0; }.task-tabs :deep(.el-tabs__nav-wrap::after) { display: none; }.task-tabs :deep(.el-tabs__item) { display: flex; align-items: center; gap: 8px; height: 57px; padding: 0 16px; font-size: 12px; color: var(--text-secondary); }.task-tabs :deep(.el-tabs__item.is-active) { color: var(--text-primary); font-weight: 600; }.task-tabs :deep(.el-tabs__active-bar) { height: 2px; background: var(--brand); }.task-tabs :deep(.el-tabs__content) { display: none; }
.tab-count { padding: 2px 6px; border-radius: 5px; font-size: 10px; line-height: 1.3; background: var(--surface-muted); color: var(--text-muted); }.task-tabs :deep(.is-active) .tab-count { background: var(--brand-soft); color: var(--brand); }.collection-caption { font-size: 10px; color: var(--text-muted); }
.task-filters { display: flex; align-items: center; gap: 10px; flex-shrink: 0; padding: 17px 20px; }.task-search { max-width: 360px; flex: 1; min-width: 140px; }.task-filters :deep(.el-input__wrapper), .task-filters :deep(.el-select__wrapper) { min-height: 35px; border-radius: 7px; background: var(--surface); font-size: 11px; }.category-filter { width: 145px; }.status-filter { width: 125px; }.view-options { display: flex; gap: 3px; margin-left: auto; padding: 3px; border: 1px solid var(--border-default); border-radius: 7px; background: var(--surface-muted); }.view-options button { display: grid; place-items: center; width: 27px; height: 25px; border: 0; border-radius: 4px; color: var(--text-muted); background: none; cursor: pointer; }.view-options .selected { background: var(--surface); color: var(--brand); box-shadow: var(--shadow-xs); }
.task-scroll { flex: 1; min-height: 0; }.task-scroll :deep(.el-scrollbar__view) { min-height: 100%; }.task-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); align-content: start; gap: 16px; padding: 3px 20px 24px; }.task-grid.task-list { grid-template-columns: 1fr; }
.task-card { display: flex; flex-direction: column; gap: 14px; min-width: 0; padding: 19px; border: 1px solid var(--border-default); border-radius: 10px; background: var(--surface); transition: border-color .15s, box-shadow .15s; }.task-card:hover, .task-card:focus-within { border-color: color-mix(in srgb, var(--brand) 40%, var(--border-default)); box-shadow: var(--shadow-card); }.task-card-attention { border-color: color-mix(in srgb, var(--warning) 26%, var(--border-default)); }
.task-card-heading { display: flex; align-items: center; gap: 11px; min-width: 0; }.task-category-icon { display: grid; place-items: center; width: 35px; height: 35px; flex-shrink: 0; border: 1px solid var(--border-default); border-radius: 9px; background: var(--surface-muted); color: var(--text-secondary); font-size: 17px; }.task-category-icon.render { color: var(--brand); background: var(--brand-soft); border-color: transparent; }.task-category-icon.analysis { color: var(--accent); background: color-mix(in srgb, var(--accent) 8%, transparent); border-color: transparent; }.task-heading-copy { flex: 1; min-width: 0; }.task-category-label { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; font-size: 9px; color: var(--text-muted); }.unread-dot { width: 5px; height: 5px; border-radius: 50%; background: var(--brand); }.task-title { display: block; max-width: 100%; padding: 0; overflow: hidden; border: 0; background: none; color: var(--text-primary); font-size: 13px; font-weight: 550; text-align: left; white-space: nowrap; text-overflow: ellipsis; cursor: pointer; }.task-title:hover { color: var(--brand); }.task-card-heading :deep(.status-pill) { flex-shrink: 0; }
.task-context { margin: -3px 0 0; overflow: hidden; font-size: 10px; color: var(--text-muted); white-space: nowrap; text-overflow: ellipsis; }.task-progress { display: grid; gap: 11px; padding-top: 4px; }.progress-heading { display: flex; align-items: center; gap: 16px; justify-content: space-between; font-size: 11px; color: var(--text-secondary); }.progress-heading > span:first-child { display: flex; align-items: center; gap: 7px; min-width: 0; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }.progress-heading i { width: 5px; height: 5px; border-radius: 50%; background: var(--brand); flex-shrink: 0; }.progress-heading i.queued { background: var(--warning); }.progress-heading i.attention { background: var(--danger); }.progress-heading strong { flex-shrink: 0; font-size: 15px; font-weight: 550; color: var(--text-primary); font-variant-numeric: tabular-nums; }.progress-heading small { margin-left: 2px; font-size: 10px; font-weight: 400; color: var(--text-muted); }.progress-heading .progress-note { flex-shrink: 0; font-size: 10px; color: var(--text-muted); }.attention-track { background: color-mix(in srgb, var(--warning) 15%, var(--surface-muted)) !important; }.queue-track, .unknown-progress { height: 5px; overflow: hidden; border-radius: 10px; background: var(--surface-hover); }.unknown-progress > span { display: block; width: 25%; height: 100%; border-radius: inherit; background: var(--brand); opacity: .45; animation: task-progress-drift 2s ease-in-out infinite alternate; }.task-progress :deep(.el-progress-bar__outer) { background: var(--surface-hover); }
.task-outcome { display: flex; align-items: center; gap: 7px; min-height: 29px; padding-top: 4px; font-size: 11px; color: var(--text-secondary); }.task-outcome .el-icon { color: var(--success); }.task-card-attention .task-outcome .el-icon { color: var(--warning); }.task-error { margin: 0; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; font-size: 10px; line-height: 1.7; color: var(--danger); }
.task-card-footer { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-top: auto; padding-top: 13px; border-top: 1px solid var(--border-default); }.task-card-footer time { font-size: 10px; color: var(--text-muted); font-variant-numeric: tabular-nums; }.task-card-actions { display: flex; align-items: center; gap: 16px; }.task-card-actions button { display: flex; align-items: center; gap: 6px; padding: 0; border: 0; background: none; font-size: 10px; cursor: pointer; }.cancel-task { color: var(--text-muted); }.cancel-task:hover { color: var(--danger); }.open-task { color: var(--brand); }
.collection-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 20px; border-top: 1px solid var(--border-default); background: var(--surface-muted); flex-shrink: 0; font-size: 10px; color: var(--text-muted); }.collection-footer > span:first-child { display: flex; align-items: center; gap: 7px; }
.task-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 300px; height: 100%; padding: 40px 24px; text-align: center; }.empty-icon { display: grid; place-items: center; width: 62px; height: 62px; margin-bottom: 23px; border: 1px solid var(--border-default); border-radius: 18px; background: var(--surface-muted); color: var(--brand); font-size: 27px; }.task-empty h3 { margin: 0; font-size: 15px; font-weight: 550; color: var(--text-primary); }.task-empty p { max-width: 330px; margin: 12px 0 0; font-size: 12px; line-height: 1.8; color: var(--text-secondary); }.task-empty .el-button { margin-top: 22px; font-size: 11px; }
.task-list .task-card { gap: 11px; }.task-list .task-context { margin-left: 46px; }.task-list .task-progress, .task-list .task-outcome { margin-left: 46px; }.task-list .task-card-heading { gap: 11px; }
button:focus-visible { outline: 2px solid var(--brand); outline-offset: 3px; }
@keyframes task-progress-drift { from { transform: translateX(0); } to { transform: translateX(300%); } }
@container(max-width: 760px) { .task-grid { grid-template-columns: 1fr; }.task-filters { flex-wrap: wrap; }.task-search { max-width: none; flex-basis: calc(100% - 80px); }.view-options { order: 0; }.category-filter, .status-filter { order: 1; width: calc(50% - 5px); }.view-options { margin-left: 0; }.collection-caption { display: none; } }
@container(max-width: 680px) { .task-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }.metric-tile { padding: 14px; gap: 10px; }.metric-tile strong { font-size: 24px; }.metric-caption { display: none; }.task-center-workspace :deep(.task-center-body) { padding: 14px; }.task-center-layout { gap: 14px; } }
@container(max-width: 380px) { .metric-tile { padding: 12px; gap: 8px; }.metric-icon { width: 28px; height: 28px; font-size: 15px; }.task-card { padding: 15px; }.task-grid { padding: 3px 14px 18px; }.task-filters { padding: 14px; }.collection-heading { padding: 0 14px; }.task-card-actions { gap: 12px; }.collection-footer { padding: 12px 14px; font-size: 9px; } }
@media(prefers-reduced-motion: reduce) { .unknown-progress > span { animation: none; }.task-card { transition: none; } }
</style>
