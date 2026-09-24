<template>
  <div class="h-[calc(100vh-96px)] flex flex-col studio-card overflow-hidden">
    <WorkspaceHeader
      title="任务中心"
      subtitle="统一查看 AI 分析、图片视频渲染与视频后处理任务"
      :icon="Operation"
    >
      <template #stats>
        <div class="hidden md:flex items-center gap-3 text-xs bg-[var(--surface-muted)] px-3 py-1.5 rounded-lg border border-[var(--border-default)]">
          <span>运行中 <b class="text-[var(--brand)]">{{ store.runningCount }}</b></span>
          <span class="h-3 w-px bg-[var(--border-default)]"></span>
          <span>AI 分析 <b class="text-violet-600">{{ aiCount }}</b></span>
          <span class="h-3 w-px bg-[var(--border-default)]"></span>
          <span>渲染 <b class="text-amber-600">{{ renderCount }}</b></span>
        </div>
      </template>
      <template #actions>
        <el-button size="small" plain :loading="store.loading" @click="refresh">
          <el-icon class="mr-1"><Refresh /></el-icon>刷新
        </el-button>
      </template>
    </WorkspaceHeader>

    <div class="flex-1 min-h-0 bg-[var(--app-bg)] p-6">
      <div class="h-full studio-card flex flex-col overflow-hidden">
        <div class="px-5 border-b border-[var(--border-default)]">
          <el-tabs v-model="activeTab">
            <el-tab-pane name="active">
              <template #label>进行中 <el-badge v-if="store.runningCount" :value="store.runningCount" class="ml-1" /></template>
            </el-tab-pane>
            <el-tab-pane name="history" label="最近任务" />
          </el-tabs>
        </div>

        <el-scrollbar class="flex-1 p-5">
          <div v-if="items.length === 0" class="h-full flex flex-col items-center justify-center text-[var(--text-muted)]">
            <el-icon :size="42"><Finished /></el-icon>
            <div class="text-sm mt-3">{{ activeTab === 'active' ? '当前没有运行中的任务' : '暂无历史任务' }}</div>
            <div class="text-xs mt-1">从剧作、资产或制作工作台提交任务后，会统一显示在这里</div>
          </div>
          <div v-else class="grid grid-cols-1 lg:grid-cols-2 xl:grid-cols-3 gap-4">
            <button
              v-for="task in items"
              :key="`${task.sourceType}:${task.taskId}`"
              type="button"
              class="task-page-card text-left"
              @click="openTask(task)"
            >
              <div class="flex items-start justify-between gap-3">
                <div class="min-w-0 flex-1">
                  <div class="flex items-center gap-2 mb-1.5">
                    <span class="task-kind">{{ getCategoryLabel(task.category) }}</span>
                    <span class="text-sm font-bold truncate text-[var(--text-primary)]">{{ task.title }}</span>
                  </div>
                  <div class="text-xs text-[var(--text-muted)] truncate">{{ getContextLabel(task) }}</div>
                </div>
                <StatusPill :status="statusTone(task.status)" :label="statusLabel(task.status)" size="small" />
              </div>
              <div v-if="isActive(task.status) && task.progress !== undefined && task.progress !== null" class="mt-5">
                <div class="flex justify-between text-xs text-[var(--text-secondary)] mb-1.5">
                  <span>{{ task.currentStage || '处理中...' }}</span>
                  <span>{{ task.progress }}%</span>
                </div>
                <el-progress :percentage="task.progress" :show-text="false" :stroke-width="6" />
              </div>
              <div v-else-if="isActive(task.status)" class="mt-5 flex items-center gap-2 text-xs text-[var(--text-secondary)]">
                <span class="inline-block h-2 w-2 rounded-full bg-[var(--brand)] animate-pulse"></span>
                <span>{{ task.currentStage || '后台处理中...' }}</span>
              </div>
              <div v-if="task.status === 'FAILED'" class="mt-4 text-xs text-rose-600 line-clamp-2">
                {{ task.errorMessage || '任务执行失败' }}
              </div>
              <div class="mt-5 pt-3 border-t border-[var(--border-default)] flex items-center justify-between text-xs text-[var(--text-muted)]">
                <span>{{ formatDate(task.finishedAt || task.startedAt || task.createdAt) }}</span>
                <span class="flex items-center gap-2">
                  <span v-if="canCancel(task)" class="text-rose-500 hover:underline" @click.stop="cancelTask(task)">取消</span>
                  <span class="text-[var(--brand)]">打开任务 <el-icon><ArrowRight /></el-icon></span>
                </span>
              </div>
            </button>
          </div>
        </el-scrollbar>
      </div>
    </div>
  </div>
  <el-drawer v-model="inspectorVisible" :title="inspectorTask?.title || 'AI 任务'" size="min(720px, 92vw)" @closed="closeInspector">
    <div v-if="inspectorTask" class="flex h-full flex-col gap-4">
      <div class="text-xs text-[var(--text-muted)]">任务 {{ inspectorTask.taskId }} · {{ statusLabel(inspectorTask.status) }}</div>
      <div class="text-sm font-medium">实时记录</div>
      <pre class="min-h-32 max-h-[38vh] overflow-auto rounded-lg bg-[var(--surface-muted)] p-3 text-xs whitespace-pre-wrap break-all">{{ eventLog || '等待事件...' }}</pre>
      <div class="text-sm font-medium">结果</div>
      <pre class="min-h-32 flex-1 overflow-auto rounded-lg bg-[var(--surface-muted)] p-3 text-xs whitespace-pre-wrap break-all">{{ resultText || inspectorTask.errorMessage || '任务尚未完成' }}</pre>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Finished, Operation, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import WorkspaceHeader from '@/components/workspace/WorkspaceHeader.vue'
import StatusPill from '@/components/status/StatusPill.vue'
import { taskCenterApi } from '@/api/task-center'
import { useTaskCenterStore } from '@/store/taskCenter'
import type { TaskCenterItem } from '@/api/task-center'

const router = useRouter()
const store = useTaskCenterStore()
const activeTab = ref<'active' | 'history'>('active')
const items = computed(() => activeTab.value === 'active' ? store.activeTasks : store.recentTasks)
const aiCount = computed(() => store.activeTasks.filter(task => task.category === 'AI_ANALYSIS').length)
const renderCount = computed(() => store.activeTasks.filter(task => task.category === 'RENDER').length)
const inspectorVisible = ref(false)
const inspectorTask = ref<TaskCenterItem | null>(null)
const eventLog = ref('')
const resultText = ref('')
let unsubscribeAi: (() => void) | null = null

function prettyResult(value: string) {
  try { return JSON.stringify(JSON.parse(value), null, 2) }
  catch { return value }
}

function closeInspector() {
  unsubscribeAi?.()
  unsubscribeAi = null
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

onMounted(() => {
  store.start()
  void refresh()
})

async function refresh() {
  await Promise.all([store.refreshActive(), store.refreshHistory()])
}

async function openTask(task: TaskCenterItem) {
  store.markRead(task.taskId, task.sourceType)
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
  return task.sourceType === 'RENDER_TASK' && isActive(task.status)
}

async function cancelTask(task: TaskCenterItem) {
  try {
    await taskCenterApi.cancelTask(task.sourceType, task.taskId)
    ElMessage.success('任务已取消')
    await store.refreshActive()
  } catch (error: any) {
    ElMessage.error(error?.message || '取消任务失败')
  }
}

function isActive(status: string) { return ['QUEUED', 'PENDING', 'RUNNING', 'RETRYING'].includes(status) }
function getCategoryLabel(category?: string) { return category === 'AI_ANALYSIS' ? 'AI 分析' : category === 'MEDIA_PROCESS' ? '视频处理' : '渲染' }
function statusLabel(status: string) { return ({ QUEUED: '排队中', PENDING: '等待中', RUNNING: '运行中', RETRYING: '重试中', SUCCESS: '已完成', PARTIAL_SUCCESS: '部分完成', FAILED: '失败', CANCELLED: '已取消' } as Record<string, string>)[status] || status }
function statusTone(status: string): string { return status === 'SUCCESS' ? 'success' : status === 'FAILED' ? 'danger' : isActive(status) ? 'processing' : 'disabled' }
function getContextLabel(task: TaskCenterItem) { return [task.dramaTitle, task.episodeName, task.sceneName, task.shotName || (task.shotNo ? `镜头 ${task.shotNo}` : undefined)].filter(Boolean).join(' / ') || `任务 ID ${task.taskId}` }
function formatDate(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '刚刚提交' }
</script>

<style scoped>
.task-page-card { display: block; min-height: 170px; padding: 16px; background: var(--surface); border: 1px solid var(--border-default); border-radius: 14px; transition: border-color .16s ease, box-shadow .16s ease; }
.task-page-card:hover { border-color: var(--brand); box-shadow: 0 8px 22px rgba(20, 24, 36, .08); }
.task-kind { color: var(--brand); font-size: 10px; font-weight: 700; white-space: nowrap; }
</style>
