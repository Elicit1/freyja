<template>
  <button
    type="button"
    class="task-center-trigger"
    :class="{ 'is-active': store.attentionCount > 0 }"
    @click="drawerVisible = true"
  >
    <span
      class="task-center-trigger__dot"
      :class="store.runningCount > 0 ? 'is-running' : 'is-ready'"
    ></span>
    <span>{{ store.runningCount > 0 ? `任务中心 (${store.runningCount})` : '任务中心' }}</span>
  </button>

  <Teleport to="body">
    <el-drawer
      v-model="drawerVisible"
      title="任务中心"
      direction="rtl"
      size="520px"
      append-to-body
      destroy-on-close
      class="task-center-drawer"
      @open="handleDrawerOpen"
    >
      <template #header>
        <div class="flex items-center justify-between pr-4 w-full">
          <div>
            <div class="text-base font-bold text-[var(--text-primary)]">任务中心</div>
            <div class="text-xs text-[var(--text-muted)] mt-1">AI 分析、渲染和后处理任务统一管理</div>
          </div>
          <el-tag v-if="store.runningCount > 0" type="primary" effect="dark" round size="small">
            {{ store.runningCount }} 运行中
          </el-tag>
        </div>
      </template>

      <div class="flex flex-col h-full">
        <el-tabs v-model="activeTab" class="task-center-tabs">
          <el-tab-pane name="active">
            <template #label>进行中 <el-badge v-if="store.runningCount" :value="store.runningCount" class="ml-1" /></template>
          </el-tab-pane>
          <el-tab-pane name="recent" label="最近完成" />
        </el-tabs>

        <el-scrollbar class="flex-1 pr-1">
          <div v-if="displayTasks.length === 0" class="flex flex-col items-center justify-center py-20 text-[var(--text-muted)]">
            <el-icon :size="32"><Finished /></el-icon>
            <span class="text-sm mt-3">{{ activeTab === 'active' ? '当前没有运行中的任务' : '暂无最近任务' }}</span>
          </div>

          <div v-else class="space-y-3 pb-5">
            <button
              v-for="task in displayTasks"
              :key="`${task.sourceType}:${task.taskId}`"
              type="button"
              class="task-card w-full text-left"
              @click="openTask(task)"
            >
              <div class="flex items-start justify-between gap-3">
                <div class="min-w-0 flex-1">
                  <div class="flex items-center gap-2">
                    <span class="task-card__type">{{ getCategoryLabel(task.category) }}</span>
                    <span class="font-semibold text-sm truncate text-[var(--text-primary)]">{{ task.title }}</span>
                  </div>
                  <div class="text-[11px] text-[var(--text-muted)] mt-1 truncate">
                    {{ getContextLabel(task) }}
                  </div>
                </div>
                <StatusPill :status="statusTone(task.status)" :label="statusLabel(task.status)" size="small" />
              </div>

              <div v-if="isActive(task.status) && task.progress !== undefined && task.progress !== null" class="mt-3">
                <div class="flex items-center justify-between text-[11px] mb-1 text-[var(--text-secondary)]">
                  <span>{{ task.currentStage || '处理中...' }}</span>
                  <span>{{ task.progress }}%</span>
                </div>
                <el-progress :percentage="task.progress" :show-text="false" :stroke-width="5" />
              </div>
              <div v-else-if="isActive(task.status)" class="mt-3 flex items-center gap-2 text-[11px] text-[var(--text-secondary)]">
                <span class="inline-block h-2 w-2 rounded-full bg-[var(--brand)] animate-pulse"></span>
                <span>{{ task.currentStage || '后台处理中...' }}</span>
              </div>
              <div v-if="task.status === 'FAILED' && task.errorMessage" class="text-xs text-rose-600 mt-2 line-clamp-2">
                {{ task.errorMessage }}
              </div>
              <div class="flex items-center justify-between mt-3 text-[11px] text-[var(--text-muted)]">
                <span>{{ formatTime(task.startedAt || task.createdAt) }}</span>
                <span class="flex items-center gap-2">
                  <span v-if="canCancel(task)" class="text-rose-500 hover:underline" @click.stop="cancelTask(task)">取消</span>
                  <span class="text-[var(--brand)]">点击打开 <el-icon><ArrowRight /></el-icon></span>
                </span>
              </div>
            </button>
          </div>
        </el-scrollbar>

        <div class="border-t border-[var(--border)] pt-3 mt-auto flex items-center justify-between">
          <el-button text size="small" :loading="store.loading" @click="refresh">
            <el-icon class="mr-1"><Refresh /></el-icon>刷新
          </el-button>
          <el-button type="primary" size="small" @click="goToTaskCenter">查看全部任务</el-button>
        </div>
      </div>
    </el-drawer>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Finished, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { taskCenterApi } from '@/api/task-center'
import { useTaskCenterStore } from '@/store/taskCenter'
import type { TaskCenterItem } from '@/api/task-center'
import StatusPill from '@/components/status/StatusPill.vue'

const router = useRouter()
const store = useTaskCenterStore()
const drawerVisible = ref(false)
const activeTab = ref<'active' | 'recent'>('active')

const displayTasks = computed(() => activeTab.value === 'active' ? store.activeTasks : store.recentTasks)

onMounted(() => store.start())

function handleDrawerOpen() {
  void store.refreshActive()
  if (activeTab.value === 'recent') void store.refreshHistory()
}

function refresh() {
  void store.refreshActive()
  if (activeTab.value === 'recent') void store.refreshHistory()
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

async function openTask(task: TaskCenterItem) {
  store.markRead(task.taskId, task.sourceType)
  drawerVisible.value = false

  if (task.resumeAction === 'SHOT_PROMPT_DERIVE' && task.shotId) {
    await router.push({
      path: '/drama',
      query: cleanQuery({
        dramaId: task.dramaId,
        episodeId: task.episodeId,
        sceneId: task.sceneId,
        resumeShotId: task.shotId,
        resumeAction: 'SHOT_PROMPT_DERIVE',
        taskSource: task.sourceType,
        taskId: task.taskId
      })
    })
    return
  }

  if ((task.resumeAction === 'SHOT_RENDER' || task.resumeAction === 'SHOT_DETAIL') && task.shotId) {
    await router.push({
      path: '/drama',
      query: cleanQuery({ dramaId: task.dramaId, episodeId: task.episodeId, sceneId: task.sceneId, resumeShotId: task.shotId, resumeAction: task.resumeAction, taskId: task.taskId })
    })
    return
  }

  if (task.resumeAction === 'SCRIPT_DECOMPOSE') {
    await router.push({ path: '/drama', query: cleanQuery({ dramaId: task.dramaId, resumeAction: task.resumeAction, taskId: task.taskId }) })
    return
  }

  await router.push('/render/tasks')
}

function cleanQuery(values: Record<string, string | undefined>) {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value !== undefined && value !== ''))
}

function goToTaskCenter() {
  drawerVisible.value = false
  void router.push('/render/tasks')
}

function isActive(status: string) {
  return ['QUEUED', 'PENDING', 'RUNNING', 'RETRYING'].includes(status)
}

function statusLabel(status: string) {
  return ({ QUEUED: '排队中', PENDING: '等待中', RUNNING: '运行中', RETRYING: '重试中', SUCCESS: '已完成', PARTIAL_SUCCESS: '部分完成', FAILED: '失败', CANCELLED: '已取消' } as Record<string, string>)[status] || status
}

function statusTone(status: string): 'success' | 'warning' | 'danger' | 'processing' | 'pending' | 'disabled' {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'CANCELLED') return 'disabled'
  if (status === 'QUEUED' || status === 'PENDING') return 'pending'
  return 'processing'
}

function getCategoryLabel(category?: string) {
  if (category === 'AI_ANALYSIS') return 'AI 分析'
  if (category === 'MEDIA_PROCESS') return '视频处理'
  return '渲染'
}

function getContextLabel(task: TaskCenterItem) {
  const parts = [task.dramaTitle, task.episodeName, task.sceneName, task.shotName || (task.shotNo ? `镜头 ${task.shotNo}` : undefined)].filter(Boolean)
  return parts.length ? parts.join(' / ') : `${task.sourceType === 'AI_TASK' ? 'AI 任务' : '后台任务'} · ${task.taskId}`
}

function formatTime(value?: string) {
  if (!value) return '刚刚提交'
  const time = new Date(value).getTime()
  if (Number.isNaN(time)) return value
  const seconds = Math.max(0, Math.floor((Date.now() - time) / 1000))
  if (seconds < 60) return `${seconds} 秒前`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`
  return `${Math.floor(seconds / 3600)} 小时前`
}
</script>

<style scoped>
.task-center-trigger {
  height: 32px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 500;
  background: var(--surface-muted);
  border: 1px solid var(--border-default);
  border-radius: 999px;
  cursor: pointer;
  transition: all .16s ease;
}
.task-center-trigger:hover,
.task-center-trigger.is-active {
  color: var(--brand);
  background: var(--brand-soft);
  border-color: var(--brand);
}
.task-center-trigger__dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
}
.task-center-trigger__dot.is-running {
  background: var(--brand);
  animation: task-center-pulse 1.4s ease-in-out infinite;
}
.task-center-trigger__dot.is-ready { background: var(--success); }
@keyframes task-center-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: .45; transform: scale(.78); }
}
.task-card {
  display: block;
  padding: 13px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
  transition: border-color .16s ease, box-shadow .16s ease;
}
.task-card:hover { border-color: var(--brand); box-shadow: 0 5px 16px rgba(20, 24, 36, .08); }
.task-card__type { color: var(--brand); font-size: 10px; font-weight: 700; white-space: nowrap; }
</style>
