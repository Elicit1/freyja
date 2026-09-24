<template>
  <div class="h-full flex flex-col bg-[var(--app-bg)]">
    <!-- 1. 场次顶栏信息与快捷操作 -->
    <div class="bg-[var(--surface)] border-b border-[var(--border-default)] px-6 py-3 flex flex-wrap items-center justify-between gap-4 transition-colors select-none">
      <div class="flex items-center gap-3 flex-wrap min-w-0">
        <div class="flex items-center gap-2 flex-wrap">
          <h2 class="text-base font-bold text-[var(--text-primary)] truncate max-w-sm">
            {{ currentScene?.name || (currentEpisode ? `${currentEpisode.title} (未选场次)` : '请从大纲选择场次') }}
          </h2>
          <span v-if="currentScene?.sceneType" class="studio-badge bg-[var(--surface-muted)] text-[var(--text-secondary)] border border-[var(--border-default)]">
            {{ currentScene.sceneType }}
          </span>
          <span v-if="currentScene?.timeOfDay" class="studio-badge bg-amber-50 text-amber-700 border border-amber-200">
            {{ currentScene.timeOfDay }}
          </span>
          <span v-if="currentScene?.resSceneName" class="studio-badge bg-emerald-50 text-emerald-700 border border-emerald-200">
            🏙️ {{ currentScene.resSceneName }}
          </span>
          <span class="studio-badge bg-[var(--brand-soft)] text-[var(--brand)] font-mono border border-[var(--brand-soft)]">
            共 {{ displayedGroups.length }} 组 · {{ totalShotsCount }} 镜
          </span>
        </div>
      </div>

      <!-- 右侧操作按钮组 -->
      <div v-if="currentScene" class="flex items-center gap-2.5 shrink-0">
        <el-button type="primary" :icon="Plus" class="!shadow-xs" @click="handleCreateGroup">
          新建镜头组
        </el-button>
      </div>
    </div>

    <!-- 2. 主分镜工作台卡片流 (按 ShotGroup 连续镜头组聚合) -->
    <div class="flex-1 overflow-y-auto p-6 custom-scrollbar">
      <!-- 空状态：未选场次 -->
      <div
        v-if="!currentScene"
        class="h-full flex flex-col items-center justify-center text-center p-8 bg-[var(--surface)] border border-dashed border-[var(--border-default)] rounded-2xl select-none"
      >
        <el-icon class="text-5xl text-[var(--text-muted)] mb-3 opacity-50"><Film /></el-icon>
        <h3 class="text-base font-bold text-[var(--text-primary)] mb-1">请从左侧大纲树中选择场次</h3>
        <p class="text-xs text-[var(--text-secondary)] max-w-sm">
          选择具体场次后，右侧将呈现该场次的【连续镜头组 ShotGroup】及 16:9 影视分镜流。
        </p>
      </div>

      <div v-else v-loading="loading" class="space-y-6">
        <!-- 场次内无镜头组时的空状态引导 -->
        <div
          v-if="displayedGroups.length === 0"
          class="flex flex-col items-center justify-center text-center p-12 bg-[var(--surface)] border border-dashed border-[var(--border-default)] rounded-2xl my-4 select-none"
        >
          <el-icon class="text-5xl text-[var(--text-muted)] mb-3 opacity-40"><VideoCamera /></el-icon>
          <h3 class="text-base font-bold text-[var(--text-primary)] mb-1">当前场次暂无镜头组</h3>
          <p class="text-xs text-[var(--text-secondary)] max-w-sm mb-4">
            分镜卡片依托于镜头组进行叙事连续性管理。点击下方按钮新建第 1 个镜头组。
          </p>
          <el-button type="primary" :icon="Plus" @click="handleCreateGroup">
            新建镜头组
          </el-button>
        </div>

        <!-- 镜头组循环展示 (Shot Groups) -->
        <div
          v-for="(group, gIdx) in displayedGroups"
          :key="String(group.id || gIdx)"
          class="studio-card overflow-hidden transition-all shadow-xs"
        >
          <!-- 镜头组 Header -->
          <div class="px-5 py-3 bg-[var(--surface-muted)] border-b border-[var(--border-default)] flex flex-wrap items-center justify-between gap-3 select-none">
            <div class="flex items-center gap-2.5 flex-wrap min-w-0">
              <!-- 折叠/展开箭头 -->
              <el-icon
                class="text-xs text-[var(--text-muted)] hover:text-[var(--text-primary)] cursor-pointer transition-transform duration-200"
                :class="isGroupCollapsed(group.id) ? '-rotate-90' : ''"
                @click="toggleGroupCollapse(group.id)"
              >
                <ArrowDown />
              </el-icon>

              <span class="text-xs font-black font-mono px-2 py-0.5 bg-[var(--brand-soft)] text-[var(--brand)] rounded font-semibold">
                GROUP {{ group.groupNo || (gIdx + 1) }}
              </span>

              <span class="text-sm font-bold text-[var(--text-primary)] truncate max-w-xs">
                {{ group.name }}
              </span>

              <!-- 统计指标 -->
              <span class="text-xs text-[var(--text-muted)] font-mono">
                {{ group.shots?.length || 0 }} 镜 · {{ group.totalDuration || calculateGroupDuration(group) }}s
              </span>

              <!-- 戏剧意图 -->
              <span v-if="group.purpose" class="text-xs text-[var(--text-secondary)] bg-[var(--surface)] px-2 py-0.5 rounded border border-[var(--border-default)] truncate max-w-xs" :title="group.purpose">
                🎯 {{ group.purpose }}
              </span>
            </div>

            <!-- 组右侧操作栏 -->
            <div class="flex items-center gap-2 shrink-0">
              <el-button
                type="primary"
                link
                size="small"
                @click="handleAddShotToGroup(group.id!)"
              >
                <el-icon class="mr-0.5"><Plus /></el-icon> 添加镜头
              </el-button>

              <el-dropdown trigger="click" size="small" @command="(cmd: string) => handleGroupCommand(cmd, group)">
                <el-button link size="small" class="text-[var(--text-muted)] hover:text-[var(--text-primary)] !p-1">
                  <el-icon><MoreFilled /></el-icon>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="edit">编辑组属性</el-dropdown-item>
                    <el-dropdown-item command="split" :disabled="(group.shots?.length || 0) < 2">
                      拆分镜头组
                    </el-dropdown-item>
                    <el-dropdown-item command="merge" :disabled="displayedGroups.length < 2">
                      合并镜头组
                    </el-dropdown-item>
                    <el-dropdown-item command="delete" divided class="!text-[var(--danger)]">
                      删除镜头组
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>

          <!-- 组内分镜卡片瀑布流 (展开状态) -->
          <div v-show="!isGroupCollapsed(group.id)" class="p-5 bg-[var(--surface)]">
            <div v-if="!group.shots?.length" class="text-center py-8 text-xs text-[var(--text-muted)] bg-[var(--surface-muted)] border border-dashed border-[var(--border-default)] rounded-xl">
              本镜头组暂无分镜卡片，
              <el-button link type="primary" size="small" @click="handleAddShotToGroup(group.id!)">
                立即添加第 1 镜
              </el-button>
            </div>

            <div v-else class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4 gap-5">
              <!-- 分镜卡片 (外层 3D 翻转容器) -->
              <div
                v-for="(shot, index) in group.shots"
                :key="String(shot.id)"
                class="shot-card-container h-full relative"
                :class="{ 'is-flipping': flippingShotId === shot.id }"
              >
                <div
                  class="shot-card h-full bg-[var(--surface)] rounded-xl overflow-hidden border border-[var(--border-default)] hover:border-[var(--border-strong)] transition-all flex flex-col justify-between group relative cursor-grab active:cursor-grabbing shadow-card hover:shadow-hover"
                  draggable="true"
                  @dragstart="handleGroupDragStart(group.id!, index)"
                  @dragover.prevent
                  @dragenter.prevent
                  @drop="handleGroupDrop(group.id!, index)"
                >
                  <!-- 16:9 首帧预览/视频画框区 -->
                  <div class="aspect-video w-full bg-[var(--surface-muted)] border-b border-[var(--border-default)] relative overflow-hidden flex items-center justify-center group-hover:brightness-95 transition-all">
                    <!-- 视频成片 -->
                    <video
                      v-if="shot.videoUrl"
                      :src="shot.videoUrl"
                      controls
                      class="w-full h-full object-cover"
                    ></video>

                    <!-- 首帧图片 -->
                    <div v-else-if="shot.previewImageUrl" class="w-full h-full relative group/img">
                      <el-image
                        :src="shot.previewImageUrl"
                        :preview-src-list="[shot.previewImageUrl]"
                        preview-teleported
                        fit="cover"
                        class="w-full h-full cursor-pointer block"
                        alt="首帧预览"
                        @click.stop
                      />
                      <div class="absolute inset-0 bg-black/40 opacity-0 group-hover/img:opacity-100 transition-opacity flex items-center justify-center text-white text-xs pointer-events-none gap-1">
                        <el-icon><ZoomIn /></el-icon>
                        <span>预览大图</span>
                      </div>
                    </div>

                    <!-- 渲染中动画 -->
                    <div v-else-if="shot.renderStatus === 'RENDERING' || shot.renderStatus === 'QUEUED'" class="flex flex-col items-center gap-2 text-[var(--brand)]">
                      <el-icon class="is-loading text-2xl"><Loading /></el-icon>
                      <span class="text-xs font-medium">
                        {{ shot.renderStatus === 'QUEUED' ? '排队中...' : (shot.currentNode || '渲染生成中...') }}
                      </span>
                    </div>

                    <!-- 未渲染中性专业占位 -->
                    <div v-else class="flex flex-col items-center gap-1.5 text-[var(--text-muted)] select-none">
                      <el-icon class="text-2xl opacity-40"><Picture /></el-icon>
                      <span class="text-[11px] font-mono opacity-60">待渲染生成</span>
                    </div>

                    <!-- 顶部左侧：镜头序号 + 模式切换 -->
                    <div class="absolute top-2.5 left-2.5 flex items-center gap-1.5 pointer-events-auto">
                      <span class="text-xs font-mono font-black px-1.5 py-0.5 rounded bg-black/60 text-white backdrop-blur-md">
                        #{{ shot.shotNo }}
                      </span>

                      <!-- 模式切换翻转按钮 -->
                      <el-tooltip :content="`当前为【${shot.generationMode === 'REFERENCE_MODE' ? '参考图与音频模式' : '首尾帧模式'}】，点击翻转切换`" placement="top">
                        <button
                          type="button"
                          class="px-1.5 py-0.5 rounded text-[10px] font-bold flex items-center gap-1 backdrop-blur-md transition-all cursor-pointer border border-white/20 text-white"
                          :class="shot.generationMode === 'REFERENCE_MODE' ? 'bg-sky-600/80 hover:bg-sky-700' : 'bg-purple-600/80 hover:bg-purple-700'"
                          @click.stop="handleToggleShotMode(shot)"
                        >
                          <span>{{ shot.generationMode === 'REFERENCE_MODE' ? '参考模式' : '首尾模式' }}</span>
                          <el-icon class="text-[10px]"><Refresh /></el-icon>
                        </button>
                      </el-tooltip>
                    </div>

                    <!-- 顶部右侧：时长与状态药丸 -->
                    <div class="absolute top-2.5 right-2.5 flex items-center gap-1 pointer-events-auto">
                      <span class="text-[10px] font-mono px-1.5 py-0.5 rounded bg-black/60 text-white backdrop-blur-md">
                        {{ shot.duration || 3 }}s
                      </span>
                      <StatusPill
                        :status="shot.renderStatus || 'disabled'"
                        :label="getStatusLabel(shot.renderStatus)"
                        size="small"
                        class="!backdrop-blur-md"
                      />
                    </div>

                    <!-- 底部左侧：景别与运镜 -->
                    <div class="absolute bottom-2 left-2 flex gap-1 pointer-events-none">
                      <span v-if="shot.shotType" class="text-[10px] bg-black/60 backdrop-blur-md text-white px-1.5 py-0.5 rounded">
                        {{ shot.shotType }}
                      </span>
                      <span v-if="shot.cameraMovement && shot.cameraMovement !== 'STATIC'" class="text-[10px] bg-black/60 backdrop-blur-md text-amber-300 px-1.5 py-0.5 rounded">
                        {{ shot.cameraMovement }}
                      </span>
                    </div>
                  </div>

                  <!-- 卡片中段：剧本摘要与画面描述 -->
                  <div class="p-3.5 flex-1 flex flex-col justify-between gap-2.5">
                    <div class="min-h-[36px] space-y-1">
                      <p v-if="shot.scriptContent" class="text-[11px] text-[var(--brand)] bg-[var(--brand-soft)] p-1 rounded line-clamp-2 leading-relaxed font-sans" :title="shot.scriptContent">
                        <span class="font-bold">剧本:</span> {{ shot.scriptContent }}
                      </p>
                      <p class="text-xs text-[var(--text-primary)] line-clamp-2 leading-relaxed" :title="shot.actionDescription">
                        {{ shot.actionDescription || '暂无画面动作描述' }}
                      </p>
                    </div>

                    <!-- 出场角色与 Prompt 状态 -->
                    <div class="flex items-center justify-between text-xs text-[var(--text-muted)] pt-2 border-t border-[var(--border-default)]">
                      <div class="flex items-center gap-1 overflow-hidden">
                        <span class="text-[10px]">角色:</span>
                        <div v-if="shot.characterRefs?.length" class="flex items-center -space-x-1.5">
                          <el-avatar
                            v-for="(refItem, cIdx) in shot.characterRefs"
                            :key="cIdx"
                            :size="20"
                            :src="refItem.avatarUrl"
                            :title="`${refItem.characterName || '角色'} (${refItem.outfitName || '日常装'})`"
                            class="border border-[var(--surface)] ring-1 ring-[var(--border-default)] text-[10px]"
                          >
                            {{ refItem.characterName?.charAt(0) || '角' }}
                          </el-avatar>
                        </div>
                        <span v-else class="text-[10px]">无</span>
                      </div>

                      <span v-if="shot.prompt" class="text-[10px] text-emerald-600 font-mono flex items-center gap-0.5">
                        ✓ Prompt 就绪
                      </span>
                      <span v-else class="text-[10px] text-[var(--text-muted)]">未生成</span>
                    </div>
                  </div>

                  <!-- 卡片操作底部工具栏 -->
                  <div class="px-3 py-2 bg-[var(--surface-muted)] border-t border-[var(--border-default)] flex items-center justify-between">
                    <div>
                      <el-button type="primary" size="small" class="!font-medium !shadow-xs" @click="$emit('render-shot', shot)">
                        <el-icon class="mr-0.5"><VideoCamera /></el-icon> 渲染
                      </el-button>
                      <el-button
                        size="small"
                        plain
                        class="!font-medium !ml-1.5"
                        :disabled="!shot.videoTakeCount"
                        @click="$emit('video-history', shot)"
                      >
                        <el-icon class="mr-0.5"><Clock /></el-icon>
                        历史<span v-if="shot.videoTakeCount" class="ml-0.5">{{ shot.videoTakeCount }}</span>
                      </el-button>
                    </div>

                    <div class="flex items-center gap-1.5">
                      <el-button size="small" class="!border-[var(--border-default)]" @click="$emit('edit-shot', shot.id!)">
                        编辑
                      </el-button>
                      <el-dropdown trigger="click" size="small" @command="(cmd: string) => handleCardCommand(cmd, shot.id!)">
                        <el-button link size="small" class="text-[var(--text-muted)] hover:text-[var(--text-primary)] !p-1">
                          <el-icon><MoreFilled /></el-icon>
                        </el-button>
                        <template #dropdown>
                          <el-dropdown-menu>
                            <el-dropdown-item command="preview">预览完整 Prompt</el-dropdown-item>
                            <el-dropdown-item command="upscale" :disabled="!shot.videoUrl">视频超分</el-dropdown-item>
                            <el-dropdown-item command="interpolate" :disabled="!shot.videoUrl">视频插帧</el-dropdown-item>
                            <el-dropdown-item command="clone" divided>复制分镜</el-dropdown-item>
                            <el-dropdown-item command="delete" divided class="!text-[var(--danger)]">删除镜头</el-dropdown-item>
                          </el-dropdown-menu>
                        </template>
                      </el-dropdown>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 组内快捷添加分镜入口 -->
              <div
                class="border-2 border-dashed border-[var(--border-default)] hover:border-[var(--brand)] hover:bg-[var(--surface-muted)]/60 rounded-xl min-h-[340px] flex flex-col items-center justify-center p-6 text-[var(--text-muted)] hover:text-[var(--brand)] transition-all cursor-pointer group select-none"
                @click="handleAddShotToGroup(group.id!)"
              >
                <div class="w-12 h-12 rounded-full bg-[var(--surface-muted)] group-hover:bg-[var(--brand-soft)] flex items-center justify-center text-xl mb-2 transition-colors">
                  <el-icon><Plus /></el-icon>
                </div>
                <span class="text-xs font-bold text-[var(--text-primary)] group-hover:text-[var(--brand)]">加镜到组 {{ group.groupNo || (gIdx + 1) }}</span>
                <span class="text-[10px] text-[var(--text-muted)] mt-1">归属本组统一管理</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 弹窗套件 -->
    <ShotGroupModal ref="groupModalRef" @success="$emit('refresh')" />
    <SplitGroupModal ref="splitModalRef" @success="$emit('refresh')" />
    <MergeGroupModal ref="mergeModalRef" @success="$emit('refresh')" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Loading, MoreFilled, Film, VideoCamera, ArrowDown, ZoomIn, Picture, Refresh, Clock } from '@element-plus/icons-vue'
import StatusPill from '@/components/status/StatusPill.vue'
import { shotGroupApi, shotApi } from '@/api/drama'
import type { DramaShot, DramaScene, DramaEpisode, DramaShotGroup } from '@/types/drama'

// 模态弹窗引入
import ShotGroupModal from './ShotGroupModal.vue'
import SplitGroupModal from './SplitGroupModal.vue'
import MergeGroupModal from './MergeGroupModal.vue'

const props = defineProps<{
  shots: DramaShot[]
  shotGroups?: DramaShotGroup[]
  currentScene: DramaScene | null
  currentEpisode: DramaEpisode | null
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'add-shot', groupId?: any): void
  (e: 'edit-shot', shotId: any): void
  (e: 'delete-shot', shotId: any): void
  (e: 'clone-shot', shotId: any): void
  (e: 'preview-prompt', shot: DramaShot): void
  (e: 'render-shot', shot: DramaShot): void
  (e: 'video-history', shot: DramaShot): void
  (e: 'process-video', payload: { shot: DramaShot; operation: 'upscale' | 'interpolate' }): void
  (e: 'reorder-shots', shotIds: any[]): void
  (e: 'refresh'): void
}>()

const groupModalRef = ref()
const splitModalRef = ref()
const mergeModalRef = ref()

const dragGroupId = ref<any>(null)
const dragIndex = ref<number | null>(null)
const flippingShotId = ref<any>(null)

// 镜头组折叠状态管理
const collapsedGroupIds = ref<Set<string>>(new Set())

function isGroupCollapsed(groupId: any): boolean {
  return collapsedGroupIds.value.has(String(groupId))
}

function toggleGroupCollapse(groupId: any) {
  const key = String(groupId)
  if (collapsedGroupIds.value.has(key)) {
    collapsedGroupIds.value.delete(key)
  } else {
    collapsedGroupIds.value.add(key)
  }
}

// 组合计算：若后端返回了 shotGroups，优先按其分组；否则平铺 shots 构造一个虚拟 group
const displayedGroups = computed<DramaShotGroup[]>(() => {
  if (props.shotGroups && props.shotGroups.length > 0) {
    return props.shotGroups
  }
  if (props.shots && props.shots.length > 0) {
    return [
      {
        id: props.shots[0]?.shotGroupId || 1,
        sceneId: props.currentScene?.id || 1,
        groupNo: 1,
        name: `${props.currentScene?.name || '场次'} - 连续镜头组`,
        purpose: '默认动作与对白连续单元',
        shots: props.shots,
        shotCount: props.shots.length,
        totalDuration: props.shots.reduce((acc, cur) => acc + (cur.duration || 3), 0)
      }
    ]
  }
  return []
})

const totalShotsCount = computed(() => {
  let count = 0
  for (const g of displayedGroups.value) {
    count += g.shots?.length || 0
  }
  return count
})

function calculateGroupDuration(group: DramaShotGroup): number {
  if (!group.shots) return 0
  return group.shots.reduce((acc, s) => acc + (s.duration || 3), 0)
}

function handleCreateGroup() {
  if (!props.currentScene?.id) {
    ElMessage.warning('请先选择场次')
    return
  }
  const nextNo = (displayedGroups.value.length || 0) + 1
  groupModalRef.value?.openCreate(props.currentScene.id, nextNo)
}

function handleAddShotToGroup(groupId: any) {
  emit('add-shot', groupId)
}

function handleGroupCommand(cmd: string, group: DramaShotGroup) {
  if (cmd === 'edit') {
    groupModalRef.value?.openEdit(group)
  } else if (cmd === 'split') {
    splitModalRef.value?.open(group, group.shots || [])
  } else if (cmd === 'merge') {
    mergeModalRef.value?.open(displayedGroups.value, group.id)
  } else if (cmd === 'delete') {
    if (!group.id) return
    ElMessageBox.confirm(`确定要删除镜头组【${group.name}】吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '确定删除',
      cancelButtonText: '取消'
    }).then(async () => {
      try {
        await shotGroupApi.delete(group.id!)
        ElMessage.success('镜头组删除成功')
        emit('refresh')
      } catch (e: any) {
        ElMessage.error(e.message || '删除失败')
      }
    }).catch(() => {})
  }
}

function handleGroupDragStart(groupId: any, index: number) {
  dragGroupId.value = groupId
  dragIndex.value = index
}

async function handleGroupDrop(groupId: any, targetIndex: number) {
  if (dragGroupId.value !== groupId || dragIndex.value === null || dragIndex.value === targetIndex) return
  const group = displayedGroups.value.find(g => String(g.id) === String(groupId))
  if (!group || !group.shots) return

  const list = [...group.shots]
  const [removed] = list.splice(dragIndex.value, 1)
  list.splice(targetIndex, 0, removed)
  dragIndex.value = null
  dragGroupId.value = null

  const newIds = list.map(s => s.id!).filter(Boolean)
  try {
    await shotGroupApi.reorderShots(groupId, newIds)
    ElMessage.success('分镜已重排')
    emit('refresh')
  } catch (e: any) {
    ElMessage.error(e.message || '重排失败')
  }
}

function getStatusLabel(status?: string) {
  switch (status) {
    case 'SUCCESS': return '渲染成功'
    case 'RENDERING': return '渲染中'
    case 'QUEUED': return '队列中'
    case 'FAILED': return '失败'
    default: return '待渲染'
  }
}

function handleCardCommand(cmd: string, shotId: any) {
  if (cmd === 'preview') {
    const s = props.shots.find(item => String(item.id) === String(shotId))
    if (s) emit('preview-prompt', s)
  } else if (cmd === 'clone') {
    emit('clone-shot', shotId)
  } else if (cmd === 'upscale' || cmd === 'interpolate') {
    const shot = props.shots.find(item => String(item.id) === String(shotId))
    if (shot) emit('process-video', { shot, operation: cmd })
  } else if (cmd === 'delete') {
    emit('delete-shot', shotId)
  }
}

// 🔄 镜头生成模式一键切换并伴随 3D 翻转动效
async function handleToggleShotMode(shot: DramaShot) {
  if (!shot.id) return
  if (flippingShotId.value) return
  const currentMode = shot.generationMode || 'FIRST_LAST_FRAME'
  const newMode = currentMode === 'REFERENCE_MODE' ? 'FIRST_LAST_FRAME' : 'REFERENCE_MODE'

  flippingShotId.value = shot.id
  setTimeout(() => {
    shot.generationMode = newMode
  }, 250)

  setTimeout(() => {
    flippingShotId.value = null
  }, 520)

  try {
    await shotApi.update({
      id: shot.id,
      generationMode: newMode
    })
    ElMessage.success({
      message: `分镜 #${shot.shotNo} 已切换为【${newMode === 'REFERENCE_MODE' ? '参考图与音频模式' : '首尾帧模式'}】`,
      duration: 1800
    })
  } catch (error: any) {
    shot.generationMode = currentMode
    ElMessage.error(error?.message || '模式切换失败')
  }
}
</script>

<style scoped>
.shot-card-container {
  perspective: 1200px;
}

.shot-card {
  transform-style: preserve-3d;
  transition: box-shadow 0.25s ease, border-color 0.25s ease;
  will-change: transform;
}

@keyframes flipCardHalf {
  0% {
    transform: rotateY(0deg);
  }
  50% {
    transform: rotateY(90deg) scale(0.97);
  }
  50.01% {
    transform: rotateY(-90deg) scale(0.97);
  }
  100% {
    transform: rotateY(0deg) scale(1);
  }
}

.shot-card-container.is-flipping .shot-card {
  animation: flipCardHalf 0.5s cubic-bezier(0.4, 0.2, 0.2, 1) forwards;
}

.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: var(--border-default);
  border-radius: 4px;
}
</style>
