<template>
  <el-dialog
    v-model="visible"
    :title="`🎞️ [S${shotNo || ''} ${shotName || '分镜'}] 视频抽卡历史与版本重选`"
    width="min(960px, 92vw)"
    top="5vh"
    class="shot-video-history-dialog"
    destroy-on-close
    append-to-body
  >
    <div class="flex flex-col max-h-[76vh] -mx-2 -my-2">
      <!-- 顶栏状态与快捷刷新 -->
      <div class="px-4 py-3 bg-[var(--surface-muted)] border-b border-[var(--border-default)] flex items-center justify-between text-xs text-[var(--text-secondary)] select-none">
        <div class="flex items-center gap-2">
          <span>共 <strong class="text-[var(--brand)] font-mono">{{ total }}</strong> 个历史视频版本</span>
          <span class="text-[var(--border-strong)]">|</span>
          <span class="text-[var(--text-muted)]">每次重新抽卡均会保留为独立候选</span>
        </div>
        <el-button size="small" link type="primary" :loading="loading" @click="loadTakes">
          🔄 刷新列表
        </el-button>
      </div>

      <!-- 历史版本卡片列表 -->
      <div v-loading="loading" class="flex-1 overflow-y-auto px-6 py-4 space-y-4 custom-scrollbar">
        <!-- 空状态 -->
        <div
          v-if="!loading && takes.length === 0"
          class="py-16 text-center text-[var(--text-muted)] select-none"
        >
          <div class="text-4xl mb-2">🎬</div>
          <div class="text-sm font-medium text-[var(--text-secondary)]">尚无成功生成的视频版本</div>
          <div class="text-xs text-[var(--text-muted)] mt-1">在渲染工坊生成视频后，所有成功版本均将汇聚于此</div>
        </div>

        <!-- 候选版本网格卡片 -->
        <div v-else class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div
            v-for="take in takes"
            :key="String(take.id)"
            class="studio-card !p-0 overflow-hidden flex flex-col justify-between transition-all"
            :class="take.current ? '!border-emerald-400 ring-2 ring-emerald-100' : 'hover:!border-[var(--brand)]'"
          >
            <!-- 头部：Take 编号 + 当前标识 -->
            <div class="px-3.5 py-2.5 bg-[var(--surface-muted)] border-b border-[var(--border-default)] flex items-center justify-between">
              <div class="flex items-center gap-1.5">
                <span class="font-mono font-bold text-xs px-2 py-0.5 rounded bg-[var(--text-primary)] text-[var(--surface)]">
                  Take #{{ take.takeNo }}
                </span>
                <span v-if="take.sourceType === 'LEGACY_BACKFILL'" class="text-[10px] text-amber-600 bg-amber-50 border border-amber-200 px-1.5 py-0.2 rounded">
                  历史回填
                </span>
                <span v-else-if="take.sourceType === 'VIDEO_UPSCALE'" class="text-[10px] text-indigo-600 bg-indigo-50 border border-indigo-200 px-1.5 py-0.5 rounded">
                  ✨ 超分增强
                </span>
                <span v-else-if="take.sourceType === 'FRAME_INTERPOLATION'" class="text-[10px] text-cyan-600 bg-cyan-50 border border-cyan-200 px-1.5 py-0.5 rounded">
                  🎞️ 平滑补帧
                </span>
              </div>

              <div>
                <span
                  v-if="take.current"
                  class="inline-flex items-center gap-1 text-[11px] font-bold text-emerald-700 bg-emerald-100/80 border border-emerald-200 px-2 py-0.5 rounded-full"
                >
                  <span class="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse"></span>
                  当前使用
                </span>
                <span v-else-if="take.status === 'UNAVAILABLE'" class="text-[10px] text-rose-600 bg-rose-50 border border-rose-200 px-2 py-0.5 rounded">
                  不可用
                </span>
                <span v-else class="text-[11px] text-[var(--text-muted)] font-mono">
                  候选版本
                </span>
              </div>
            </div>

            <!-- 视频播放画框 (16:9 / 9:16 自适应居中) -->
            <div class="aspect-video w-full bg-black relative flex items-center justify-center overflow-hidden group">
              <video
                v-if="take.videoUrl"
                :src="take.videoUrl"
                :poster="take.firstFrameUrl"
                preload="metadata"
                controls
                class="w-full h-full object-contain"
              ></video>
              <div v-else class="text-xs text-[var(--text-muted)]">
                无播放地址
              </div>
            </div>

            <!-- 参数快照信息 -->
            <div class="p-3 text-[11px] space-y-1.5 text-[var(--text-secondary)] bg-[var(--surface)]">
              <div class="flex items-center justify-between text-[var(--text-muted)]">
                <span class="truncate max-w-[150px]" :title="take.modelCode || '默认视频模型'">
                  🤖 {{ take.modelCode || '默认视频模型' }}
                </span>
                <span class="font-mono text-[10px]">
                  {{ formatTime(take.createTime) }}
                </span>
              </div>

              <div class="flex items-center gap-2 text-[var(--text-muted)] font-mono text-[10px]">
                <span v-if="take.size">📐 {{ take.size }}</span>
                <span v-if="take.duration">⏱️ {{ take.duration }}s</span>
                <span v-if="take.seed !== undefined && take.seed !== null">🎲 {{ take.seed }}</span>
              </div>

              <div v-if="take.promptSnapshot" class="text-[10px] text-[var(--text-muted)] line-clamp-2 bg-[var(--surface-muted)] p-1.5 rounded border border-[var(--border-default)]" :title="take.promptSnapshot">
                {{ take.promptSnapshot }}
              </div>
            </div>

            <!-- 底部选择操作按钮 -->
            <div class="px-3.5 py-2.5 bg-[var(--surface-muted)] border-t border-[var(--border-default)] flex items-center justify-between">
              <span class="text-[10px] text-[var(--text-muted)]">
                ID: {{ take.id.slice(-6) }}
              </span>

              <div>
                <el-button
                  size="small"
                  type="danger"
                  plain
                  :loading="deletingTakeId === String(take.id)"
                  :disabled="!!selectingTakeId || !!deletingTakeId"
                  @click="handleDeleteTake(take)"
                >删除</el-button>
                <el-button
                  v-if="take.current"
                  size="small"
                  type="success"
                  plain
                  disabled
                  class="!font-medium"
                >
                  ✓ 当前镜头视频
                </el-button>
                <el-button
                  v-else
                  size="small"
                  type="primary"
                  :loading="selectingTakeId === String(take.id)"
                  :disabled="take.status === 'UNAVAILABLE' || !!selectingTakeId"
                  class="!font-medium"
                  @click="handleSelectTake(take)"
                >
                  设为当前视频
                </el-button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 分页底栏 -->
      <div v-if="total > pageSize" class="px-4 py-3 bg-[var(--surface)] border-t border-[var(--border-default)] flex items-center justify-end select-none">
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          size="small"
          @current-change="loadTakes"
        />
      </div>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { shotApi } from '@/api/drama'
import type { ShotVideoTake } from '@/types/drama'

const props = defineProps<{
  modelValue: boolean
  shotId: string | number
  shotNo?: number
  shotName?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', val: boolean): void
  (e: 'selected', payload: { takeId: string; videoUrl: string }): void
  (e: 'deleted'): void
}>()

const visible = ref(false)
const loading = ref(false)
const takes = ref<ShotVideoTake[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = 12
const selectingTakeId = ref<string | null>(null)
const deletingTakeId = ref<string | null>(null)

watch(
  () => props.modelValue,
  (val) => {
    visible.value = val
    if (val && props.shotId) {
      currentPage.value = 1
      loadTakes()
    }
  },
  { immediate: true }
)

watch(
  () => props.shotId,
  (shotId) => {
    if (visible.value && shotId) {
      currentPage.value = 1
      loadTakes()
    }
  }
)

watch(
  () => visible.value,
  (val) => {
    emit('update:modelValue', val)
  }
)

async function loadTakes() {
  if (!props.shotId) return
  loading.value = true
  try {
    const res = await shotApi.getVideoTakes(props.shotId, {
      current: currentPage.value,
      size: pageSize
    })
    takes.value = res.records || []
    total.value = res.total || 0
  } catch (err: any) {
    ElMessage.error(err.message || '加载视频候选历史失败')
  } finally {
    loading.value = false
  }
}

async function handleSelectTake(take: ShotVideoTake) {
  const takeId = String(take.id)
  const shotId = String(props.shotId)

  try {
    await ElMessageBox.confirm(
      `将 Take #${take.takeNo} 设为当前分镜视频？不会删除其他候选版本，且随时可以重新选择。`,
      '确认切换视频版本',
      {
        confirmButtonText: '确定切换',
        cancelButtonText: '取消',
        type: 'info'
      }
    )
  } catch {
    return
  }

  selectingTakeId.value = takeId
  try {
    const res = await shotApi.selectVideoTake(shotId, takeId)
    // 更新本地列表状态
    takes.value.forEach(t => {
      t.current = String(t.id) === String(res.currentTakeId)
    })
    ElMessage.success(`已将 Take #${take.takeNo} 设为当前视频`)
    emit('selected', {
      takeId: String(res.currentTakeId),
      videoUrl: res.videoUrl
    })
  } catch (err: any) {
    ElMessage.error(err.message || '切换视频版本失败')
  } finally {
    selectingTakeId.value = null
  }
}

async function handleDeleteTake(take: ShotVideoTake) {
  try {
    await ElMessageBox.confirm(
      `确定删除 Take #${take.takeNo} 及对应的 MinIO 视频文件吗？此操作无法恢复${take.current ? '，当前分镜视频也会清空' : ''}。`,
      '删除视频版本',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  deletingTakeId.value = String(take.id)
  try {
    await shotApi.deleteVideoTake(String(props.shotId), String(take.id))
    ElMessage.success(`Take #${take.takeNo} 已删除`)
    if (takes.value.length === 1 && currentPage.value > 1) currentPage.value--
    await loadTakes()
    emit('deleted')
  } catch (err: any) {
    ElMessage.error(err?.message || '删除视频版本失败')
  } finally {
    deletingTakeId.value = null
  }
}

function formatTime(timeStr?: string) {
  if (!timeStr) return ''
  return timeStr.replace('T', ' ').substring(0, 19)
}
</script>
