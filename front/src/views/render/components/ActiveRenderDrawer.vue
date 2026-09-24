<template>
  <el-drawer
    v-model="visible"
    title="实时渲染任务队列"
    size="520px"
    direction="rtl"
    destroy-on-close
    append-to-body
    class="active-render-drawer"
  >
    <template #header>
      <div class="flex items-center justify-between pr-4 w-full">
        <div class="flex items-center gap-2">
          <span class="text-base font-bold text-[var(--text-primary)]">实时渲染任务队列</span>
          <el-tag :type="renderTaskStore.activeCount > 0 ? 'primary' : 'info'" size="small" effect="dark" round>
            {{ renderTaskStore.activeCount }}
          </el-tag>
        </div>
        <div class="flex items-center gap-1.5 text-xs">
          <span
            class="w-2 h-2 rounded-full"
            :class="renderTaskStore.connected ? 'bg-emerald-500 animate-pulse' : 'bg-rose-500'"
          ></span>
          <span :class="renderTaskStore.connected ? 'text-emerald-500' : 'text-rose-500'">
            {{ renderTaskStore.connected ? '在线' : '重连中...' }}
          </span>
        </div>
      </div>
    </template>

    <!-- 任务流内容区 -->
    <div class="flex flex-col h-full">
      <div v-if="renderTaskStore.activeTasks.length === 0" class="flex-1 flex flex-col items-center justify-center text-[var(--text-muted)] py-16">
        <div class="w-14 h-14 rounded-2xl bg-[var(--surface-raised)] border border-[var(--border)] flex items-center justify-center mb-3 text-[var(--text-muted)]">
          <el-icon :size="26"><VideoCamera /></el-icon>
        </div>
        <div class="text-sm font-medium text-[var(--text-secondary)]">当前没有正在排队或渲染的任务</div>
        <div class="text-xs text-[var(--text-muted)] mt-1">在分镜工作台或资产库提交生成后，将在此实时推流展示</div>
      </div>

      <el-scrollbar v-else class="flex-1 pr-2">
        <div class="space-y-3.5 pb-4">
          <div
            v-for="task in renderTaskStore.activeTasks"
            :key="task.taskId"
            class="studio-card p-4 transition-all relative overflow-hidden"
          >
            <!-- 顶部类型与状态 -->
            <div class="flex items-start justify-between gap-2 mb-2.5">
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2 mb-1">
                  <el-tag size="small" :type="getTypeTagType(task.taskType)" effect="plain">
                    {{ getTypeLabel(task.taskType) }}
                  </el-tag>
                  <span class="text-xs font-semibold text-[var(--text-primary)] truncate" :title="task.taskName">
                    {{ task.taskName }}
                  </span>
                </div>
                <div class="text-[11px] text-[var(--text-muted)] flex items-center gap-2 font-mono">
                  <span>ID: {{ task.taskId }}</span>
                  <span v-if="task.modelCode">[{{ task.modelCode }}]</span>
                </div>
              </div>

              <!-- 取消操作 -->
              <el-popconfirm
                title="确定要取消此渲染任务吗？"
                confirm-button-text="取消任务"
                cancel-button-text="保留"
                @confirm="handleCancel(task.taskId)"
              >
                <template #reference>
                  <el-button type="danger" link size="small" class="!text-[var(--text-muted)] hover:!text-rose-500">
                    <el-icon><Close /></el-icon>
                  </el-button>
                </template>
              </el-popconfirm>
            </div>

            <!-- 动态进度条 -->
            <div class="my-3">
              <div class="flex items-center justify-between text-xs mb-1.5">
                <span class="text-[var(--text-secondary)] font-medium flex items-center gap-1.5">
                  <el-icon v-if="task.status === 'RENDERING'" class="is-loading text-[var(--brand)]"><Loading /></el-icon>
                  <span>{{ task.currentNode || (task.status === 'QUEUED' ? '排队等待调度中...' : '渲染处理中...') }}</span>
                </span>
                <StatusPill
                  :status="task.status === 'QUEUED' ? 'warning' : 'processing'"
                  :label="task.status === 'QUEUED' ? '排队中' : '运行中'"
                  size="small"
                />
              </div>
              <el-progress
                :percentage="100"
                :status="task.status === 'QUEUED' ? 'warning' : undefined"
                :stroke-width="5"
                :indeterminate="task.status === 'RENDERING'"
                :show-text="false"
              />
            </div>

            <!-- Prompt 摘要 -->
            <div v-if="task.prompt" class="text-[11px] text-[var(--text-secondary)] bg-[var(--surface-raised)] p-2.5 rounded-lg border border-[var(--border)] line-clamp-2">
              <span class="font-medium text-[var(--text-primary)]">Prompt:</span> {{ task.prompt }}
            </div>
          </div>
        </div>
      </el-scrollbar>

      <!-- 底部快捷跳转完整大盘栏 -->
      <div class="pt-3 border-t border-[var(--border)] mt-auto flex items-center justify-between bg-[var(--surface)]">
        <el-button size="small" text @click="renderTaskStore.refreshActiveTasks">
          <el-icon class="mr-1"><Refresh /></el-icon> 刷新缓存
        </el-button>
        <el-button type="primary" size="small" @click="goToTaskCenter">
          进入渲染中心 <el-icon class="ml-1"><ArrowRight /></el-icon>
        </el-button>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useRenderTaskStore } from '@/store/renderTask'
import { ElMessage } from 'element-plus'
import { Loading, Close, Refresh, ArrowRight, VideoCamera } from '@element-plus/icons-vue'
import StatusPill from '@/components/status/StatusPill.vue'

const visible = ref(false)
const router = useRouter()
const renderTaskStore = useRenderTaskStore()

function open() {
  visible.value = true
}

function getTypeLabel(type?: string) {
  switch (type) {
    case 'SHOT_FRAME': return '首尾关键帧'
    case 'SHOT_VIDEO': return '视频渲染'
    case 'GROUP_SERIAL': return '旧镜头组任务'
    case 'ASSET_IMAGE': return '资产生图'
    default: return '任务'
  }
}

function getTypeTagType(type?: string): '' | 'success' | 'warning' | 'danger' | 'info' {
  switch (type) {
    case 'SHOT_FRAME': return 'success'
    case 'SHOT_VIDEO': return ''
    case 'GROUP_SERIAL': return 'warning'
    case 'ASSET_IMAGE': return 'info'
    default: return 'info'
  }
}

async function handleCancel(taskId: string) {
  try {
    await renderTaskStore.cancelTask(taskId)
    ElMessage.success('已取消渲染任务')
  } catch (e: any) {
    ElMessage.error(e.message || '取消任务失败')
  }
}

function goToTaskCenter() {
  visible.value = false
  router.push('/render/tasks')
}

defineExpose({ open })
</script>
