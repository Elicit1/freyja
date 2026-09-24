<template>
  <div class="h-[calc(100vh-96px)] flex flex-col studio-card overflow-hidden">
    <!-- 顶部工作区标头与实时网关状态 -->
    <WorkspaceHeader
      title="渲染与任务中心"
      subtitle="实时监控 AI 步进生图、I2V 视频渲染与 FastAPI 调度网关队列"
      :icon="VideoCamera"
    >
      <template #tag>
        <span
          class="studio-badge"
          :class="renderTaskStore.connected ? 'bg-emerald-50 text-emerald-700 border border-emerald-200' : 'bg-rose-50 text-rose-700 border border-rose-200'"
        >
          <span class="w-1.5 h-1.5 rounded-full" :class="renderTaskStore.connected ? 'bg-emerald-500 animate-pulse' : 'bg-rose-500'"></span>
          {{ renderTaskStore.connected ? '网关在线' : '网关离线重连中' }}
        </span>
      </template>

      <template #stats>
        <div class="hidden md:flex items-center gap-3 text-xs bg-[var(--surface-muted)] px-3 py-1.5 rounded-lg border border-[var(--border-default)]">
          <div class="flex items-center gap-1.5">
            <span class="text-[var(--text-secondary)]">渲染中:</span>
            <span class="font-bold text-[var(--brand)] font-mono">{{ renderingCount }}</span>
          </div>
          <div class="h-3 w-px bg-[var(--border-default)]"></div>
          <div class="flex items-center gap-1.5">
            <span class="text-[var(--text-secondary)]">排队等待:</span>
            <span class="font-bold text-amber-600 font-mono">{{ queuedCount }}</span>
          </div>
          <div class="h-3 w-px bg-[var(--border-default)]"></div>
          <div class="flex items-center gap-1.5">
            <span class="text-[var(--text-secondary)]">历史任务:</span>
            <span class="font-bold text-[var(--text-primary)] font-mono">{{ historyTotal }}</span>
          </div>
        </div>
      </template>

      <template #actions>
        <el-button size="small" plain class="!border-[var(--border-default)]" @click="handleRefreshActive">
          <el-icon class="mr-1"><Refresh /></el-icon> 刷新缓存
        </el-button>
      </template>
    </WorkspaceHeader>

    <!-- 核心双 Tab 面板 -->
    <div class="flex-1 bg-[var(--surface)] flex flex-col overflow-hidden">
      <div class="px-6 border-b border-[var(--border-default)] select-none">
        <el-tabs v-model="activeTab" class="studio-tabs" @tab-change="handleTabChange">
          <!-- Tab 1: 实时活跃任务 (Redis + WebSocket 推流) -->
          <el-tab-pane name="active">
            <template #label>
              <div class="flex items-center gap-2 py-1 text-xs font-semibold">
                <el-icon><VideoCamera /></el-icon>
                <span>实时任务队列</span>
                <el-badge
                  :value="renderTaskStore.activeCount"
                  :hidden="renderTaskStore.activeCount === 0"
                  type="primary"
                  class="scale-90"
                />
              </div>
            </template>
          </el-tab-pane>

          <!-- Tab 2: 历史归档记录 (MySQL) -->
          <el-tab-pane name="history">
            <template #label>
              <div class="flex items-center gap-2 py-1 text-xs font-semibold">
                <el-icon><Tickets /></el-icon>
                <span>历史渲染记录</span>
              </div>
            </template>
          </el-tab-pane>
        </el-tabs>
      </div>

      <!-- Tab 1 内容：实时队列 -->
      <div v-show="activeTab === 'active'" class="flex-1 overflow-hidden flex flex-col p-6 bg-[var(--app-bg)]">
        <!-- 空状态 -->
        <div
          v-if="renderTaskStore.activeTasks.length === 0"
          class="flex-1 flex flex-col items-center justify-center text-[var(--text-muted)] py-16"
        >
          <WorkspaceEmptyState
            type="empty"
            title="当前没有正在排队或渲染的任务"
            description="在剧作分镜大纲或资产库提交生成，任务将自动进入队列并在此实时推流展示。"
          />
        </div>

        <!-- 活跃任务网格流 -->
        <el-scrollbar v-else class="flex-1 pr-2">
          <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 pb-6">
            <div
              v-for="task in renderTaskStore.activeTasks"
              :key="task.taskId"
              class="studio-card p-4 flex flex-col justify-between relative hover:border-[var(--brand)] transition-all"
            >
              <div>
                <!-- 头部 -->
                <div class="flex items-start justify-between gap-2 mb-2">
                  <div class="flex-1 min-w-0">
                    <div class="flex items-center gap-1.5 mb-1.5 flex-wrap">
                      <span class="studio-badge bg-[var(--surface-muted)] text-[var(--text-secondary)] border border-[var(--border-default)]">
                        {{ getTypeLabel(task.taskType) }}
                      </span>
                      <StatusPill
                        :status="task.status === 'QUEUED' ? 'pending' : 'processing'"
                        :label="task.status === 'QUEUED' ? '排队等待' : '渲染中'"
                        size="small"
                      />
                    </div>
                    <div class="text-sm font-bold text-[var(--text-primary)] truncate" :title="task.taskName">
                      {{ task.taskName }}
                    </div>
                  </div>

                  <el-popconfirm
                    title="确定要取消此渲染任务吗？"
                    confirm-button-text="取消"
                    cancel-button-text="保留"
                    @confirm="handleCancelTask(task.taskId)"
                  >
                    <template #reference>
                      <el-button type="danger" text size="small" class="!px-1.5">
                        取消
                      </el-button>
                    </template>
                  </el-popconfirm>
                </div>

                <div class="text-[11px] text-[var(--text-muted)] font-mono mb-2">
                  ID: {{ task.taskId }}
                </div>

                <!-- 进度与当前阶段 -->
                <div class="my-3 bg-[var(--surface-muted)] p-3 rounded-lg border border-[var(--border-default)]">
                  <div class="flex items-center justify-between text-xs mb-1.5">
                    <span class="text-[var(--text-secondary)] font-medium truncate flex items-center gap-1.5">
                      <el-icon v-if="task.status === 'RENDERING'" class="is-loading text-[var(--brand)]"><Loading /></el-icon>
                      <span v-else-if="task.status === 'QUEUED'" class="text-amber-500 font-mono">⏳</span>
                      {{ task.currentNode || (task.status === 'QUEUED' ? '排队等待调度' : '渲染处理中...') }}
                    </span>
                    <span class="text-[10px] text-[var(--text-muted)]">
                      {{ task.status === 'QUEUED' ? '就绪' : '运行中' }}
                    </span>
                  </div>
                  <el-progress
                    :percentage="100"
                    :status="task.status === 'QUEUED' ? 'warning' : undefined"
                    :stroke-width="5"
                    :indeterminate="task.status === 'RENDERING'"
                    :show-text="false"
                  />
                </div>

                <!-- Prompt 预览 -->
                <div v-if="task.prompt" class="text-xs text-[var(--text-secondary)] bg-[var(--surface-muted)] p-2.5 rounded-lg border border-[var(--border-default)] line-clamp-3 leading-relaxed">
                  <span class="font-bold text-[var(--text-primary)]">Prompt:</span> {{ task.prompt }}
                </div>
              </div>

              <!-- 底部提交时间 -->
              <div class="pt-3 mt-3 border-t border-[var(--border-default)] flex items-center justify-between text-[11px] text-[var(--text-muted)]">
                <span>提交时间: {{ formatTime(task.submitTime) }}</span>
                <span v-if="task.modelCode" class="font-mono text-[var(--text-secondary)]">[{{ task.modelCode }}]</span>
              </div>
            </div>
          </div>
        </el-scrollbar>
      </div>

      <!-- Tab 2 内容：历史归档记录 -->
      <div v-show="activeTab === 'history'" class="flex-1 overflow-hidden flex flex-col p-6 bg-[var(--app-bg)]">
        <!-- 筛选栏 -->
        <div class="studio-card p-3 mb-4 flex flex-wrap items-center gap-3">
          <el-input
            v-model="historyQuery.keyword"
            placeholder="搜索任务名称/Prompt/ID"
            size="default"
            clearable
            class="!w-56"
            @keyup.enter="loadHistoryTasks"
          >
            <template #prefix>
              <el-icon class="text-[var(--text-muted)]"><Search /></el-icon>
            </template>
          </el-input>

          <el-select
            v-model="historyQuery.taskType"
            placeholder="任务类型"
            size="default"
            clearable
            class="!w-36"
            @change="loadHistoryTasks"
          >
            <el-option label="首尾帧生图" value="SHOT_FRAME" />
            <el-option label="分镜视频渲染" value="SHOT_VIDEO" />
            <el-option label="资产生图" value="ASSET_IMAGE" />
          </el-select>

          <el-select
            v-model="historyQuery.status"
            placeholder="执行结果"
            size="default"
            clearable
            class="!w-32"
            @change="loadHistoryTasks"
          >
            <el-option label="成功 (SUCCESS)" value="SUCCESS" />
            <el-option label="失败 (FAILED)" value="FAILED" />
            <el-option label="已取消 (CANCELLED)" value="CANCELLED" />
          </el-select>

          <el-button type="primary" size="default" @click="loadHistoryTasks">
            查询
          </el-button>
          <el-button size="default" @click="resetHistoryQuery">
            重置
          </el-button>
        </div>

        <!-- 表格区 -->
        <div class="flex-1 overflow-hidden studio-card p-2">
          <el-table
            v-loading="historyLoading"
            :data="historyTasks"
            height="100%"
            size="small"
            class="w-full"
          >
            <el-table-column label="任务名称 / 业务编号" min-width="200">
              <template #default="{ row }">
                <div class="font-bold text-[var(--text-primary)] text-xs truncate" :title="row.taskName">
                  {{ row.taskName }}
                </div>
                <div class="text-[11px] text-[var(--text-muted)] font-mono truncate">
                  {{ row.taskId }}
                </div>
              </template>
            </el-table-column>

            <el-table-column label="类型" width="130" align="center">
              <template #default="{ row }">
                <span class="studio-badge bg-[var(--surface-muted)] text-[var(--text-secondary)] border border-[var(--border-default)]">
                  {{ getTypeLabel(row.taskType) }}
                </span>
              </template>
            </el-table-column>

            <el-table-column label="执行状态" width="110" align="center">
              <template #default="{ row }">
                <StatusPill
                  :status="row.status === 'SUCCESS' ? 'success' : (row.status === 'FAILED' ? 'failed' : 'disabled')"
                  :label="row.status === 'SUCCESS' ? '成功' : (row.status === 'FAILED' ? '失败' : '已取消')"
                  size="small"
                />
              </template>
            </el-table-column>

            <el-table-column label="模型与耗时" width="150">
              <template #default="{ row }">
                <div class="text-xs font-mono text-[var(--text-primary)]">
                  {{ row.modelCode || '默认' }}
                </div>
                <div class="text-[11px] text-[var(--text-muted)]">
                  {{ (row.costMs || row.durationMs) ? `${(((row.costMs || row.durationMs) as number) / 1000).toFixed(1)}s` : '未知' }}
                </div>
              </template>
            </el-table-column>

            <el-table-column label="提交时间" width="160">
              <template #default="{ row }">
                <span class="text-xs text-[var(--text-secondary)]">{{ formatTime(row.submitTime || row.createTime) }}</span>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="100" align="center" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="handleViewDetail(row)">
                  查看详情
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 历史分页 -->
        <div v-if="historyTotal > historyQuery.size" class="mt-4 flex justify-end">
          <el-pagination
            v-model:current-page="historyQuery.current"
            v-model:page-size="historyQuery.size"
            :total="historyTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, prev, pager, next"
            @size-change="loadHistoryTasks"
            @current-change="loadHistoryTasks"
          />
        </div>
      </div>
    </div>

    <!-- 渲染任务详情弹窗 -->
    <el-dialog
      v-model="detailVisible"
      title="渲染任务详情"
      width="680px"
      append-to-body
      destroy-on-close
      class="studio-dialog"
    >
      <div v-loading="detailLoading" class="flex flex-col gap-4 py-1">
        <template v-if="currentDetail">
          <!-- 产物大图/视频展示区 -->
          <div v-if="currentDetail.outputUrl" class="rounded-xl overflow-hidden border border-[var(--border-default)] bg-black/5 dark:bg-black/40 flex items-center justify-center relative min-h-[160px] max-h-[380px]">
            <video
              v-if="isVideoOutput(currentDetail)"
              :src="currentDetail.outputUrl"
              controls
              autoplay
              loop
              class="w-full max-h-[380px] object-contain"
            />
            <el-image
              v-else
              :src="currentDetail.outputUrl"
              fit="contain"
              class="w-full max-h-[380px] object-contain cursor-pointer"
              :preview-src-list="[currentDetail.outputUrl]"
              preview-teleported
            />
          </div>

          <!-- 失败异常提示 -->
          <div
            v-if="currentDetail.status === 'FAILED' && (currentDetail.errorMessage || currentDetail.errorDetail)"
            class="p-3.5 rounded-xl border border-rose-200 dark:border-rose-900/40 bg-rose-50 dark:bg-rose-950/30 text-xs text-rose-600 dark:text-rose-400"
          >
            <div class="font-bold flex items-center gap-1.5 mb-1 text-sm">
              <el-icon><WarningFilled /></el-icon> 渲染失败原因
            </div>
            <div class="font-mono break-all whitespace-pre-wrap">
              {{ currentDetail.errorMessage || currentDetail.errorDetail }}
            </div>
          </div>

          <!-- 任务核心元数据网格 -->
          <div class="studio-card p-4 space-y-3">
            <div class="flex items-center justify-between border-b border-[var(--border-default)] pb-2.5">
              <div class="font-bold text-sm text-[var(--text-primary)] truncate max-w-[420px]" :title="currentDetail.taskName">
                {{ currentDetail.taskName }}
              </div>
              <StatusPill
                :status="currentDetail.status === 'SUCCESS' ? 'success' : (currentDetail.status === 'FAILED' ? 'failed' : (currentDetail.status === 'RENDERING' ? 'processing' : (currentDetail.status === 'QUEUED' ? 'warning' : 'disabled')))"
                :label="currentDetail.status === 'SUCCESS' ? '执行成功' : (currentDetail.status === 'FAILED' ? '执行失败' : (currentDetail.status === 'RENDERING' ? '渲染中' : (currentDetail.status === 'QUEUED' ? '排队中' : '已取消')))"
                size="small"
              />
            </div>

            <div class="grid grid-cols-2 gap-y-2.5 gap-x-4 text-xs">
              <div class="flex items-center gap-2">
                <span class="text-[var(--text-muted)] w-16 shrink-0">任务 ID:</span>
                <span class="font-mono text-[var(--text-secondary)] truncate select-all">{{ currentDetail.taskId }}</span>
                <el-button link size="small" class="!p-0" @click="copyToClipboard(currentDetail.taskId, '任务ID')">
                  <el-icon :size="13"><DocumentCopy /></el-icon>
                </el-button>
              </div>

              <div class="flex items-center gap-2">
                <span class="text-[var(--text-muted)] w-16 shrink-0">任务类型:</span>
                <span class="text-[var(--text-primary)] font-medium">{{ getTypeLabel(currentDetail.taskType) }}</span>
              </div>

              <div v-if="currentDetail.dramaTitle" class="flex items-center gap-2">
                <span class="text-[var(--text-muted)] w-16 shrink-0">关联短剧:</span>
                <span class="text-[var(--text-primary)] truncate">{{ currentDetail.dramaTitle }}</span>
              </div>

              <div v-if="currentDetail.episodeName || currentDetail.shotNo" class="flex items-center gap-2">
                <span class="text-[var(--text-muted)] w-16 shrink-0">集数/镜头:</span>
                <span class="text-[var(--text-primary)]">
                  {{ currentDetail.episodeName ? currentDetail.episodeName + ' / ' : '' }}
                  {{ currentDetail.shotNo ? `第 ${currentDetail.shotNo} 镜` : '' }}
                </span>
              </div>

              <div class="flex items-center gap-2">
                <span class="text-[var(--text-muted)] w-16 shrink-0">AI 模型:</span>
                <span class="font-mono text-[var(--text-primary)]">{{ currentDetail.modelCode || '默认' }}</span>
              </div>

              <div v-if="currentDetail.providerName" class="flex items-center gap-2">
                <span class="text-[var(--text-muted)] w-16 shrink-0">提供商:</span>
                <span class="text-[var(--text-primary)]">{{ currentDetail.providerName }}</span>
              </div>

              <div class="flex items-center gap-2">
                <span class="text-[var(--text-muted)] w-16 shrink-0">耗时:</span>
                <span class="font-mono text-[var(--text-primary)]">
                  {{ (currentDetail.costMs || currentDetail.durationMs) ? `${(((currentDetail.costMs || currentDetail.durationMs) as number) / 1000).toFixed(2)} 秒` : '未知' }}
                </span>
              </div>

              <div class="flex items-center gap-2">
                <span class="text-[var(--text-muted)] w-16 shrink-0">提交时间:</span>
                <span class="text-[var(--text-secondary)]">{{ formatTime(currentDetail.submitTime || currentDetail.createTime) }}</span>
              </div>

              <div v-if="currentDetail.finishTime" class="flex items-center gap-2 col-span-2">
                <span class="text-[var(--text-muted)] w-16 shrink-0">完成时间:</span>
                <span class="text-[var(--text-secondary)]">{{ formatTime(currentDetail.finishTime) }}</span>
              </div>
            </div>
          </div>

          <!-- Prompt 提示词展示 -->
          <div v-if="currentDetail.prompt" class="studio-card p-3.5 space-y-1.5">
            <div class="flex items-center justify-between text-xs">
              <span class="font-bold text-[var(--text-primary)]">正向提示词 (Prompt):</span>
              <el-button link size="small" class="!p-0 !text-xs" @click="copyToClipboard(currentDetail.prompt, 'Prompt')">
                <el-icon :size="12" class="mr-1"><DocumentCopy /></el-icon>复制
              </el-button>
            </div>
            <div class="text-xs text-[var(--text-secondary)] bg-[var(--surface-muted)] p-2.5 rounded-lg border border-[var(--border-default)] leading-relaxed max-h-36 overflow-y-auto font-mono select-all">
              {{ currentDetail.prompt }}
            </div>
          </div>

          <!-- Negative Prompt 负向提示词 -->
          <div v-if="currentDetail.negativePrompt" class="studio-card p-3.5 space-y-1.5">
            <div class="flex items-center justify-between text-xs">
              <span class="font-bold text-[var(--text-primary)]">反向提示词 (Negative Prompt):</span>
              <el-button link size="small" class="!p-0 !text-xs" @click="copyToClipboard(currentDetail.negativePrompt, 'Negative Prompt')">
                <el-icon :size="12" class="mr-1"><DocumentCopy /></el-icon>复制
              </el-button>
            </div>
            <div class="text-xs text-[var(--text-muted)] bg-[var(--surface-muted)] p-2.5 rounded-lg border border-[var(--border-default)] leading-relaxed max-h-24 overflow-y-auto font-mono select-all">
              {{ currentDetail.negativePrompt }}
            </div>
          </div>
        </template>
      </div>

      <template #footer>
        <div class="flex justify-end gap-2">
          <el-button @click="detailVisible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { VideoCamera, Tickets, Refresh, Search, Loading, DocumentCopy, WarningFilled } from '@element-plus/icons-vue'
import WorkspaceHeader from '@/components/workspace/WorkspaceHeader.vue'
import StatusPill from '@/components/status/StatusPill.vue'
import WorkspaceEmptyState from '@/components/workspace/WorkspaceEmptyState.vue'
import { useRenderTaskStore } from '@/store/renderTask'
import { renderTaskApi, type RenderTaskVO } from '@/api/render-task'

const renderTaskStore = useRenderTaskStore()

const activeTab = ref<'active' | 'history'>('active')

const queuedCount = computed(() => {
  return renderTaskStore.activeTasks.filter(t => t.status === 'QUEUED').length
})

const renderingCount = computed(() => {
  return renderTaskStore.activeTasks.filter(t => t.status === 'RENDERING').length
})

function handleTabChange(tabName: any) {
  if (tabName === 'history' && historyTasks.value.length === 0) {
    loadHistoryTasks()
  }
}

function handleRefreshActive() {
  renderTaskStore.refreshActiveTasks()
  ElMessage.success('已刷新活跃任务队列缓存')
}

async function handleCancelTask(taskId: string) {
  try {
    await renderTaskStore.cancelTask(taskId)
    ElMessage.success('任务已请求取消')
  } catch (e: any) {
    ElMessage.error(e?.message || '取消任务失败')
  }
}

// 历史归档部分
const historyLoading = ref(false)
const historyTasks = ref<any[]>([])
const historyTotal = ref(0)
const historyQuery = reactive({
  current: 1,
  size: 20,
  keyword: '',
  taskType: '',
  status: ''
})

async function loadHistoryTasks() {
  historyLoading.value = true
  try {
    const res = await renderTaskApi.getHistoryTasks(historyQuery)
    if (res) {
      historyTasks.value = res.records || []
      historyTotal.value = res.total || 0
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '加载历史任务失败')
  } finally {
    historyLoading.value = false
  }
}

function resetHistoryQuery() {
  historyQuery.keyword = ''
  historyQuery.taskType = ''
  historyQuery.status = ''
  historyQuery.current = 1
  loadHistoryTasks()
}

function getTypeLabel(taskType?: string) {
  switch (taskType) {
    case 'SHOT_FRAME': return '首尾帧生图'
    case 'SHOT_VIDEO': return '视频渲染'
    case 'GROUP_SERIAL': return '旧镜头组任务'
    case 'ASSET_IMAGE': return '资产生图'
    default: return taskType || '渲染任务'
  }
}

function formatTime(str?: string) {
  if (!str) return '-'
  try {
    const d = new Date(str)
    return `${d.getMonth() + 1}月${d.getDate()}日 ${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`
  } catch {
    return str
  }
}

// 详情弹窗逻辑
const detailVisible = ref(false)
const detailLoading = ref(false)
const currentDetail = ref<(RenderTaskVO & { durationMs?: number; createTime?: string }) | null>(null)

async function handleViewDetail(row: any) {
  currentDetail.value = row
  detailVisible.value = true
  if (row.taskId) {
    detailLoading.value = true
    try {
      const res = await renderTaskApi.getTaskById(row.taskId)
      if (res) {
        currentDetail.value = res
      }
    } catch (e) {
      console.warn('获取任务详情失败，使用列表缓存数据', e)
    } finally {
      detailLoading.value = false
    }
  }
}

function isVideoOutput(task?: any) {
  if (!task || !task.outputUrl) return false
  if (task.taskType === 'SHOT_VIDEO') return true
  const lower = String(task.outputUrl).toLowerCase()
  return lower.endsWith('.mp4') || lower.endsWith('.webm') || lower.endsWith('.mov') || lower.includes('/video')
}

function copyToClipboard(text?: string, label = '内容') {
  if (!text) return
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success(`已复制${label}到剪贴板`)
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

onMounted(() => {
  renderTaskStore.initWebSocket()
})
</script>

<style scoped>
:deep(.studio-tabs .el-tabs__header) {
  margin-bottom: 0;
  border-bottom: none;
}
:deep(.studio-tabs .el-tabs__nav-wrap::after) {
  display: none;
}
:deep(.studio-tabs .el-tabs__item) {
  padding: 0 16px;
  height: 44px;
  line-height: 44px;
  color: var(--text-secondary);
  transition: all var(--transition-fast);
}
:deep(.studio-tabs .el-tabs__item.is-active) {
  color: var(--brand);
  font-weight: 600;
}
:deep(.studio-tabs .el-tabs__active-bar) {
  background-color: var(--brand);
  height: 2.5px;
  border-radius: 2px;
}
</style>
