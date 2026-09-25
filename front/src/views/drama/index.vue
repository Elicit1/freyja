<template>
  <div class="h-[calc(100vh-96px)] flex flex-col studio-card overflow-hidden">
    <!-- ======================================================== -->
    <!-- 1. 短剧项目卡片画廊视图 (Gallery View) -->
    <!-- ======================================================== -->
    <div v-if="viewMode === 'gallery'" class="h-full flex flex-col overflow-hidden bg-[var(--app-bg)]">
      <!-- 顶部工作区标头 -->
      <WorkspaceHeader
        title="短剧项目库"
        subtitle="管理短剧设定、画幅比例与故事大纲，调度分镜卡片流生产"
        :icon="Film"
      >
        <template #tag>
          <span class="studio-badge bg-[var(--brand-soft)] text-[var(--brand)] font-mono border border-[var(--brand-soft)]">
            共 {{ total }} 部
          </span>
        </template>
        <template #actions>
          <el-button
            type="warning"
            plain
            class="!font-medium"
            @click="handleOpenDecompose()"
          >
            <el-icon class="mr-1"><MagicStick /></el-icon> AI 剧本拆解
          </el-button>
          <el-button type="primary" :icon="Plus" class="!shadow-xs" @click="handleCreateDrama">
            新建短剧
          </el-button>
        </template>
      </WorkspaceHeader>

      <!-- 顶部筛选栏 -->
      <FilterToolbar
        v-model="query.title"
        search-placeholder="搜索短剧名称..."
        @search="handleSearch"
        @reset="handleResetQuery"
      >
        <template #filters>
          <DictSelect
            v-model="query.genre"
            dict-type="drama_genre"
            placeholder="题材分类"
            clearable
            class="!w-36"
            @change="handleSearch"
          />

          <DictSelect
            v-model="query.status"
            dict-type="drama_status"
            placeholder="项目状态"
            clearable
            class="!w-32"
            @change="handleSearch"
          />
        </template>
      </FilterToolbar>

      <!-- 卡片列表滚动区域 -->
      <div class="flex-1 overflow-y-auto p-6 custom-scrollbar" v-loading="loading">
        <!-- 空状态 -->
        <WorkspaceEmptyState
          v-if="!loading && dramaList.length === 0"
          type="empty"
          title="暂无短剧项目"
          description="设定画幅比例、题材类型与故事梗概，开启分镜卡片流与 AI 工业化生产。"
          action-text="立即创建短剧"
          @action="handleCreateDrama"
        />

        <!-- 卡片网格 -->
        <div v-else class="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 2xl:grid-cols-5 gap-5">
          <!-- 新建卡片快捷入口 -->
          <div
            class="border-2 border-dashed border-[var(--border-default)] hover:border-[var(--brand)] hover:bg-[var(--surface-muted)]/50 rounded-xl min-h-[380px] flex flex-col items-center justify-center p-6 text-[var(--text-muted)] hover:text-[var(--brand)] transition-all cursor-pointer group select-none"
            @click="handleCreateDrama"
          >
            <div class="w-14 h-14 rounded-2xl bg-[var(--surface-muted)] group-hover:bg-[var(--brand-soft)] flex items-center justify-center text-2xl mb-3 transition-colors">
              <el-icon><Plus /></el-icon>
            </div>
            <span class="text-sm font-bold text-[var(--text-primary)] group-hover:text-[var(--brand)]">新建短剧项目</span>
            <span class="text-[11px] text-[var(--text-muted)] mt-1 text-center">设定题材、画幅与故事梗概</span>
          </div>

          <!-- 短剧卡片 -->
          <div
            v-for="drama in dramaList"
            :key="String(drama.id)"
            class="studio-card overflow-hidden transition-all flex flex-col group relative"
          >
            <!-- 封面图与顶部标签 (海报 3:4 比例) -->
            <div
              class="aspect-[3/4] bg-[var(--surface-muted)] relative overflow-hidden cursor-pointer"
              @click="enterWorkbench(drama)"
            >
              <img
                v-if="drama.coverUrl"
                :src="drama.coverUrl"
                class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                alt="短剧封面"
              />
              <div
                v-else
                class="w-full h-full flex flex-col items-center justify-center p-4 text-center select-none text-[var(--text-muted)] bg-[var(--surface-muted)] group-hover:text-[var(--brand)] transition-colors"
              >
                <el-icon class="text-4xl mb-2 opacity-50"><Film /></el-icon>
                <span class="text-xs font-bold line-clamp-2 px-2 text-[var(--text-secondary)]">{{ drama.title }}</span>
              </div>

              <!-- 悬浮阴影遮罩 -->
              <div class="absolute inset-0 bg-gradient-to-t from-black/75 via-transparent to-black/30 pointer-events-none"></div>

              <!-- 顶部左右标签 -->
              <div class="absolute top-2.5 left-2.5 right-2.5 flex items-center justify-between pointer-events-none">
                <DictTag :dict-type="'drama_genre'" :value="drama.genre" size="small" class="!bg-black/60 !text-white !border-0 backdrop-blur-md" />
                <DictTag :dict-type="'drama_status'" :value="drama.status" size="small" />
              </div>

              <!-- 底部左侧：画幅与风格 -->
              <div class="absolute bottom-2.5 left-2.5 right-2.5 flex items-center justify-between text-[11px] text-white/90 pointer-events-none">
                <span class="px-2 py-0.5 rounded bg-black/60 backdrop-blur-md font-mono">
                  {{ drama.aspectRatio || '9:16' }}
                </span>
                <span v-if="drama.stylePreset" class="px-2 py-0.5 rounded bg-black/60 backdrop-blur-md truncate max-w-[120px]">
                  {{ drama.stylePreset }}
                </span>
              </div>
            </div>

            <!-- 卡片主体内容 -->
            <div class="p-3.5 flex-1 flex flex-col justify-between gap-3 bg-[var(--surface)]">
              <div>
                <div class="flex items-center justify-between mb-1">
                  <h3
                    class="text-sm font-bold text-[var(--text-primary)] hover:text-[var(--brand)] cursor-pointer truncate flex-1 mr-2 transition-colors"
                    :title="drama.title"
                    @click="enterWorkbench(drama)"
                  >
                    {{ drama.title }}
                  </h3>
                </div>

                <p class="text-xs text-[var(--text-secondary)] line-clamp-2 leading-relaxed min-h-[32px]">
                  {{ drama.synopsis || '暂无故事梗概，点击进入工作台完善剧本。' }}
                </p>
              </div>

              <!-- 生产统计指标条 -->
              <div class="bg-[var(--surface-muted)] rounded-lg p-2.5 space-y-1.5 border border-[var(--border-default)]">
                <div class="flex items-center justify-between text-xs text-[var(--text-secondary)]">
                  <span class="font-mono">集数: <strong class="text-[var(--text-primary)]">{{ drama.episodeCount || 0 }}</strong>/{{ drama.targetEpisodes }}</span>
                  <span class="font-mono">分镜: <strong class="text-emerald-600">{{ drama.renderedShotCount || 0 }}</strong>/{{ drama.shotCount || 0 }}</span>
                </div>

                <el-progress
                  :percentage="drama.progressPercentage || 0"
                  :stroke-width="5"
                  :color="customColors"
                  :show-text="false"
                />
              </div>

              <!-- 操作底部栏 -->
              <div class="pt-2 border-t border-[var(--border-default)] flex items-center justify-between gap-1.5">
                <el-button
                  type="primary"
                  size="small"
                  class="flex-1 !font-medium"
                  @click="enterWorkbench(drama)"
                >
                  <el-icon class="mr-1"><VideoCamera /></el-icon> 进入工作台
                </el-button>

                <el-tooltip content="为此短剧 AI 拆解剧本" placement="top">
                  <el-button
                    type="warning"
                    plain
                    size="small"
                    class="!px-2"
                    @click="handleOpenDecompose(drama)"
                  >
                    <el-icon><MagicStick /></el-icon>
                  </el-button>
                </el-tooltip>

                <el-button
                  type="default"
                  size="small"
                  class="!border-[var(--border-default)] !px-2"
                  :icon="Edit"
                  @click="handleEditDrama(drama.id)"
                />

                <el-button
                  type="danger"
                  plain
                  size="small"
                  class="!px-2"
                  :icon="Delete"
                  @click="handleDeleteDrama(drama)"
                />
              </div>
            </div>
          </div>
        </div>

        <!-- 分页控件 -->
        <div v-if="total > (query.size || 20)" class="mt-8 flex justify-end">
          <el-pagination
            v-model:current-page="query.current"
            v-model:page-size="query.size"
            :total="total"
            :page-sizes="[10, 20, 30, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="loadDramaList"
            @current-change="loadDramaList"
          />
        </div>
      </div>
    </div>

    <!-- ======================================================== -->
    <!-- 2. 短剧工作台模式 (Workbench View: 大纲树 + 分镜工作区) -->
    <!-- ======================================================== -->
    <div v-else class="h-full flex flex-col overflow-hidden bg-[var(--app-bg)]">
      <!-- 顶部工作台标头导航 -->
      <WorkspaceHeader
        :title="currentDrama?.title"
        :subtitle="`题材: ${currentDrama?.genre || '未设定'} · 画幅: ${currentDrama?.aspectRatio || '9:16'}`"
        :icon="Film"
      >
        <template #left-prefix>
          <el-button
            type="default"
            class="!rounded-lg !border-[var(--border-default)] !text-[var(--text-secondary)] hover:!text-[var(--brand)]"
            size="small"
            @click="backToGallery"
          >
            <el-icon class="mr-1"><ArrowLeft /></el-icon> 项目库
          </el-button>
          <div class="h-4 w-px bg-[var(--border-default)]"></div>
        </template>

        <template #tag>
          <DictTag v-if="currentDrama?.genre" :dict-type="'drama_genre'" :value="currentDrama.genre" size="small" />
        </template>

        <template #stats>
          <div v-if="dramaStats" class="hidden md:flex items-center gap-4 text-xs bg-[var(--surface-muted)] px-3 py-1.5 rounded-lg border border-[var(--border-default)]">
            <span class="text-[var(--text-secondary)]">制作进度:</span>
            <div class="w-28">
              <el-progress
                :percentage="dramaStats.progressPercentage || 0"
                :stroke-width="5"
                :color="customColors"
                :show-text="false"
              />
            </div>
            <span class="font-mono font-bold text-emerald-600">
              {{ dramaStats.renderedShots }}/{{ dramaStats.totalShots }} 镜
            </span>
          </div>
        </template>

        <template #actions>
          <el-button link size="small" class="!text-[var(--text-secondary)]" @click="handleEditCurrentDrama">
            <el-icon class="mr-1"><Edit /></el-icon> 编辑项目
          </el-button>

          <el-button
            type="warning"
            plain
            size="small"
            class="!font-medium"
            @click="handleOpenDecompose(currentDrama || currentDramaId)"
          >
            <el-icon class="mr-1"><MagicStick /></el-icon> AI 剧本拆解
          </el-button>
        </template>
      </WorkspaceHeader>

      <!-- 双栏工作区：大纲导航 + 分镜卡片流 -->
      <div class="flex-1 flex overflow-hidden">
        <!-- 左侧大纲导航 (280px) -->
        <div class="w-72 flex-shrink-0 h-full">
          <OutlineTree
            :tree-data="treeData"
            :selected-episode-id="selectedEpisodeId"
            :selected-scene-id="selectedSceneId"
            @select-episode="handleSelectEpisode"
            @select-scene="handleSelectScene"
            @add-episode="handleCreateEpisode"
            @edit-episode="handleEditEpisode"
            @delete-episode="handleDeleteEpisode"
            @add-scene="handleCreateScene"
            @edit-scene="handleEditScene"
            @delete-scene="handleDeleteScene"
          />
        </div>

        <!-- 右侧分镜卡片流 -->
        <div class="flex-1 h-full overflow-hidden relative">
          <StoryboardGrid
            :shots="shots"
            :shot-groups="shotGroups"
            :current-scene="currentScene"
            :current-episode="currentEpisode"
            :loading="shotsLoading"
            @add-shot="handleCreateShot"
            @edit-shot="handleEditShot"
            @delete-shot="handleDeleteShot"
            @clone-shot="handleCloneShot"
            @preview-prompt="handlePreviewShotPrompt"
            @render-shot="handleRenderShot"
            @video-history="handleOpenVideoHistory"
            @process-video="handleProcessVideo"
            @reorder-shots="handleReorderShots"
            @refresh="selectedSceneId ? loadShots(selectedSceneId) : refreshTreeAndShots()"
          />
          <ShotDrawer ref="shotDrawerRef" @success="refreshShotsAndStats" @deleted="refreshShotsAndStats" />
        </div>
      </div>
    </div>

    <!-- 弹窗与抽屉套件 -->
    <DramaModal ref="dramaModalRef" @success="handleDramaModalSuccess" />
    <EpisodeModal ref="episodeModalRef" @success="handleEpisodeSuccess" />
    <SceneModal ref="sceneModalRef" @success="handleSceneSuccess" />
    <ShotPromptPreviewModal ref="promptPreviewModalRef" @success="refreshShotsAndStats" />
    <ShotRenderStepModal ref="stepRenderModalRef" @success="refreshShotsAndStats" />
    <ShotVideoHistoryDrawer
      v-if="historyShot?.id"
      v-model="historyVisible"
      :shot-id="historyShot.id"
      :shot-no="historyShot.shotNo"
      :shot-name="historyShot.shotName"
      @selected="handleHistorySelected"
      @deleted="handleHistorySelected"
    />
    <ShotFirstEndFrameRenderModal ref="firstEndFrameRenderModalRef" @success="refreshShotsAndStats" />
    <ShotMultiModalRefRenderModal ref="multiModalRefRenderModalRef" @success="refreshShotsAndStats" />
    <ScriptDecomposeModal
      v-for="panel in decomposePanels"
      :key="panel.key"
      :ref="instance => setDecomposePanelRef(panel.key, instance)"
      @success="handleDecomposeSuccess"
      @panel-close="closeDecomposePanel(panel.key)"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted, computed, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, ArrowLeft, Film, VideoCamera, MagicStick } from '@element-plus/icons-vue'
import DictSelect from '@/components/DictSelect.vue'
import DictTag from '@/components/DictTag.vue'
import WorkspaceHeader from '@/components/workspace/WorkspaceHeader.vue'
import FilterToolbar from '@/components/workspace/FilterToolbar.vue'
import WorkspaceEmptyState from '@/components/workspace/WorkspaceEmptyState.vue'
import { useRenderTaskStore } from '@/store/renderTask'
import {
  dramaApi,
  episodeApi,
  dramaSceneApi,
  shotApi,
  shotGroupApi
} from '@/api/drama'
import type {
  Drama,
  DramaQuery,
  DramaTree,
  DramaStats,
  DramaEpisode,
  DramaScene,
  DramaShot,
  DramaShotGroup
} from '@/types/drama'

// 子组件引入
import OutlineTree from './components/OutlineTree.vue'
import StoryboardGrid from './components/StoryboardGrid.vue'
import DramaModal from './components/DramaModal.vue'
import EpisodeModal from './components/EpisodeModal.vue'
import SceneModal from './components/SceneModal.vue'
import ShotDrawer from './components/ShotDrawer.vue'
import ShotPromptPreviewModal from './components/ShotPromptPreviewModal.vue'
import ShotRenderStepModal from './components/ShotRenderStepModal.vue'
import ShotVideoHistoryDrawer from './components/ShotVideoHistoryDrawer.vue'
import ShotFirstEndFrameRenderModal from './components/ShotFirstEndFrameRenderModal.vue'
import ShotMultiModalRefRenderModal from './components/ShotMultiModalRefRenderModal.vue'
import ScriptDecomposeModal from './components/ScriptDecomposeModal.vue'

// 视图模式: 'gallery' 卡片画廊 | 'workbench' 大纲工作台
const viewMode = ref<'gallery' | 'workbench'>('gallery')

// 卡片列表状态
const loading = ref(false)
const dramaList = ref<Drama[]>([])
const total = ref(0)
const query = reactive<DramaQuery>({
  current: 1,
  size: 20,
  title: '',
  genre: '',
  status: ''
})

// 工作台状态
const currentDrama = ref<Drama | null>(null)
const currentDramaId = ref<string | number>()
const treeData = ref<DramaTree | null>(null)
const dramaStats = ref<DramaStats | null>(null)
const selectedEpisodeId = ref<string | number | null>(null)
const selectedSceneId = ref<string | number | null>(null)
const shots = ref<DramaShot[]>([])
const shotGroups = ref<DramaShotGroup[]>([])
const shotsLoading = ref(false)

// 弹窗 Refs
const dramaModalRef = ref()
const episodeModalRef = ref()
const sceneModalRef = ref()
const shotDrawerRef = ref()
const promptPreviewModalRef = ref()
const stepRenderModalRef = ref()
const firstEndFrameRenderModalRef = ref()
const multiModalRefRenderModalRef = ref()
type DecomposePanelSession = { key: string }
const decomposePanels = ref<DecomposePanelSession[]>([])
const decomposePanelRefs = ref<Record<string, any>>({})
let decomposePanelSequence = 0
const historyVisible = ref(false)
const historyShot = ref<DramaShot | null>(null)
const route = useRoute()
const router = useRouter()

const customColors = [
  { color: '#909399', percentage: 20 },
  { color: '#e6a23c', percentage: 50 },
  { color: '#409eff', percentage: 80 },
  { color: '#67c23a', percentage: 100 }
]

const currentEpisode = computed<DramaEpisode | null>(() => {
  if (!treeData.value?.episodes || !selectedEpisodeId.value) return null
  const found = treeData.value.episodes.find(e => String(e.id) === String(selectedEpisodeId.value))
  return (found as unknown as DramaEpisode) || null
})

const currentScene = computed<DramaScene | null>(() => {
  if (!treeData.value?.episodes || !selectedSceneId.value) return null
  for (const ep of treeData.value.episodes) {
    if (ep.scenes) {
      const found = ep.scenes.find(s => String(s.id) === String(selectedSceneId.value))
      if (found) return found as unknown as DramaScene
    }
  }
  return null
})

const renderTaskStore = useRenderTaskStore()

function handleRenderTaskEvent(e: Event) {
  const customEv = e as CustomEvent
  const detail = customEv?.detail
  if (!detail || !detail.task) return
  const task = detail.task
  if (viewMode.value === 'workbench' && selectedSceneId.value) {
    if (!task.sceneId || String(task.sceneId) === String(selectedSceneId.value) || (currentDramaId.value && String(task.dramaId) === String(currentDramaId.value))) {
      loadShots(selectedSceneId.value)
    }
  }
}

onMounted(() => {
  renderTaskStore.initWebSocket()
  window.addEventListener('render-task-status-change', handleRenderTaskEvent)
  loadDramaList()
  void resumeRouteContext()
})

// 剧作页在查询参数变化时保持挂载；监听完整地址以处理同页打开另一任务或项目。
watch(
  () => route.fullPath,
  () => {
    if (routeQueryValue(route.query.resumeAction)
      || routeQueryValue(route.query.resumeShotId)
      || routeQueryValue(route.query.dramaId)
      || routeQueryValue(route.query.id)) {
      void resumeRouteContext()
    }
  }
)

onUnmounted(() => {
  window.removeEventListener('render-task-status-change', handleRenderTaskEvent)
})

async function loadDramaList() {
  loading.value = true
  try {
    const res = await dramaApi.getPage(query)
    if (res) {
      dramaList.value = res.records || []
      total.value = res.total || 0
    }
  } catch (e: any) {
    ElMessage.error(e.message || '加载短剧列表失败')
  } finally {
    loading.value = false
  }
}

function routeQueryValue(value: unknown): string | undefined {
  if (Array.isArray(value)) return typeof value[0] === 'string' ? value[0] : undefined
  return typeof value === 'string' ? value : undefined
}

async function resumeRouteContext() {
  const resumeAction = routeQueryValue(route.query.resumeAction)
  const resumeTaskId = routeQueryValue(route.query.taskId)
  if (resumeAction === 'SCRIPT_DECOMPOSE' && resumeTaskId) {
    await nextTick()
    await openDecomposeTaskPanel(resumeTaskId)
    const cleanQuery = { ...route.query }
    delete cleanQuery.resumeAction
    delete cleanQuery.taskSource
    delete cleanQuery.taskId
    await router.replace({ query: cleanQuery })
    return
  }
  const shotId = routeQueryValue(route.query.resumeShotId)
  const dramaId = routeQueryValue(route.query.dramaId) || routeQueryValue(route.query.id)
  if (!dramaId) return

  try {
    const detail = await dramaApi.getById(dramaId)
    if (!detail) return

    currentDrama.value = detail
    currentDramaId.value = dramaId
    viewMode.value = 'workbench'

    if (!shotId) {
      await loadDramaWorkspace(dramaId)
      const cleanQuery = { ...route.query }
      delete cleanQuery.id
      delete cleanQuery.dramaId
      delete cleanQuery.resumeAction
      delete cleanQuery.taskSource
      delete cleanQuery.taskId
      await router.replace({ query: cleanQuery })
      return
    }

    await loadDramaWorkspace(
      dramaId,
      routeQueryValue(route.query.sceneId),
      routeQueryValue(route.query.episodeId)
    )
    await nextTick()

    const drawer = shotDrawerRef.value
    if (drawer) {
      await drawer.openEdit(shotId, dramaId, detail.aspectRatio)
      const resumeAction = routeQueryValue(route.query.resumeAction)
      const taskId = routeQueryValue(route.query.taskId)
      if (resumeAction === 'SHOT_PROMPT_DERIVE' && taskId) {
        await drawer.openPromptTask(taskId)
      }
      const assetType = routeQueryValue(route.query.newAssetType)
      const assetId = routeQueryValue(route.query.newAssetId)
      if ((assetType === 'character' || assetType === 'scene' || assetType === 'prop') && assetId) {
        await drawer.bindAsset(assetType, assetId)
      }
    }

    const cleanQuery = { ...route.query }
    for (const key of ['resumeShotId', 'resumeAction', 'taskSource', 'taskId', 'newAssetType', 'newAssetId', 'id', 'dramaId', 'episodeId', 'sceneId']) {
      delete cleanQuery[key]
    }
    await router.replace({ query: cleanQuery })
  } catch (e: any) {
    ElMessage.error(e.message || '返回分镜失败')
  }
}

function handleSearch() {
  query.current = 1
  loadDramaList()
}

function handleResetQuery() {
  query.title = ''
  query.genre = ''
  query.status = ''
  query.current = 1
  loadDramaList()
}

function enterWorkbench(drama: Drama) {
  currentDrama.value = drama
  currentDramaId.value = drama.id
  viewMode.value = 'workbench'
  if (drama.id) {
    loadDramaWorkspace(drama.id)
  }
}

function backToGallery() {
  shotDrawerRef.value?.close()
  viewMode.value = 'gallery'
  currentDrama.value = null
  currentDramaId.value = undefined
  loadDramaList()
}

function handleCreateDrama() {
  dramaModalRef.value?.openCreate()
}

function handleEditDrama(id?: any) {
  if (id) {
    dramaModalRef.value?.openEdit(id)
  }
}

function handleEditCurrentDrama() {
  if (currentDramaId.value) {
    dramaModalRef.value?.openEdit(currentDramaId.value)
  }
}

function handleDeleteDrama(drama: Drama) {
  ElMessageBox.confirm(`确定要删除短剧《${drama.title}》及其下所有剧集、场次与分镜吗？`, '删除确认', {
    type: 'warning',
    confirmButtonText: '确定删除',
    cancelButtonText: '取消'
  }).then(async () => {
    try {
      await dramaApi.delete(drama.id!)
      ElMessage.success('短剧删除成功')
      loadDramaList()
    } catch (e: any) {
      ElMessage.error(e.message || '删除失败')
    }
  }).catch(() => {})
}

function handleDramaModalSuccess(newId: any) {
  if (viewMode.value === 'gallery') {
    loadDramaList()
  } else if (String(currentDramaId.value) === String(newId)) {
    loadDramaWorkspace(newId)
  }
}

function handleOpenDecompose(target?: any) {
  void openDecomposePanel(target, selectedEpisodeId.value || undefined)
}

async function openDecomposePanel(target?: any, episodeId?: string | number) {
  const key = `decompose-${Date.now()}-${++decomposePanelSequence}`
  decomposePanels.value.push({ key })
  await nextTick()
  await decomposePanelRefs.value[key]?.open(target, episodeId)
}

async function openDecomposeTaskPanel(taskId: string) {
  const key = `decompose-task-${String(taskId)}-${Date.now()}-${++decomposePanelSequence}`
  decomposePanels.value.push({ key })
  await nextTick()
  await decomposePanelRefs.value[key]?.openTask(taskId)
}

function setDecomposePanelRef(key: string, instance: any) {
  if (instance) decomposePanelRefs.value[key] = instance
  else delete decomposePanelRefs.value[key]
}

function closeDecomposePanel(key: string) {
  delete decomposePanelRefs.value[key]
  decomposePanels.value = decomposePanels.value.filter(panel => panel.key !== key)
}

async function handleDecomposeSuccess(dramaId: any) {
  if (viewMode.value === 'gallery') {
    await loadDramaList()
    const target = dramaList.value.find(d => String(d.id) === String(dramaId))
    if (target) {
      enterWorkbench(target)
    } else {
      const detail = await dramaApi.getById(dramaId)
      if (detail) {
        enterWorkbench(detail)
      }
    }
  } else {
    await loadDramaWorkspace(dramaId)
  }
}

async function loadDramaWorkspace(dramaId: any, targetSceneId?: any, targetEpisodeId?: any) {
  try {
    const [treeRes, statsRes, detailRes] = await Promise.all([
      dramaApi.getTree(dramaId),
      dramaApi.getStats(dramaId),
      dramaApi.getById(dramaId)
    ])
    treeData.value = treeRes || null
    dramaStats.value = statsRes || null
    if (detailRes) {
      currentDrama.value = detailRes
    }

    const episodes = treeData.value?.episodes || []
    if (episodes.length === 0) {
      selectedEpisodeId.value = null
      selectedSceneId.value = null
      shots.value = []
      shotGroups.value = []
      return
    }

    // 1. 若显式指定了 targetSceneId，优先定位到该场次及其所属剧集
    if (targetSceneId) {
      for (const ep of episodes) {
        const sc = ep.scenes?.find(s => String(s.id) === String(targetSceneId))
        if (sc) {
          selectedEpisodeId.value = ep.id
          selectedSceneId.value = sc.id
          await loadShots(sc.id)
          return
        }
      }
    }

    // 2. 若显式指定了 targetEpisodeId，定位到该剧集
    if (targetEpisodeId) {
      const ep = episodes.find(e => String(e.id) === String(targetEpisodeId))
      if (ep) {
        selectedEpisodeId.value = ep.id
        if (ep.scenes?.length) {
          selectedSceneId.value = ep.scenes[0].id
          await loadShots(ep.scenes[0].id)
        } else {
          selectedSceneId.value = null
          shots.value = []
          shotGroups.value = []
        }
        return
      }
    }

    // 3. 尝试保留当前已选中的场次
    if (selectedSceneId.value) {
      for (const ep of episodes) {
        const sc = ep.scenes?.find(s => String(s.id) === String(selectedSceneId.value))
        if (sc) {
          selectedEpisodeId.value = ep.id
          selectedSceneId.value = sc.id
          await loadShots(sc.id)
          return
        }
      }
    }

    // 4. 尝试保留当前已选中的剧集
    if (selectedEpisodeId.value) {
      const ep = episodes.find(e => String(e.id) === String(selectedEpisodeId.value))
      if (ep) {
        selectedEpisodeId.value = ep.id
        if (ep.scenes?.length) {
          selectedSceneId.value = ep.scenes[0].id
          await loadShots(ep.scenes[0].id)
        } else {
          selectedSceneId.value = null
          shots.value = []
          shotGroups.value = []
        }
        return
      }
    }

    // 5. 默认回退：选择第 1 集的第 1 场
    const firstEp = episodes[0]
    selectedEpisodeId.value = firstEp.id
    if (firstEp.scenes?.length) {
      const firstSc = firstEp.scenes[0]
      selectedSceneId.value = firstSc.id
      await loadShots(firstSc.id)
    } else {
      selectedSceneId.value = null
      shots.value = []
      shotGroups.value = []
    }
  } catch (e: any) {
    ElMessage.error(e.message || '加载工作台大纲失败')
  }
}

async function loadShots(sceneId: any) {
  shotsLoading.value = true
  try {
    const [shotsRes, groupsRes] = await Promise.all([
      shotApi.getListBySceneId(sceneId),
      shotGroupApi.getListBySceneId(sceneId)
    ])
    shots.value = shotsRes || []
    shotGroups.value = groupsRes || []
  } catch (e: any) {
    ElMessage.error(e.message || '加载分镜与镜头组失败')
  } finally {
    shotsLoading.value = false
  }
}

function handleSelectEpisode(epId: any) {
  shotDrawerRef.value?.close()
  selectedEpisodeId.value = epId
  const ep = treeData.value?.episodes.find(e => String(e.id) === String(epId))
  if (ep?.scenes?.length) {
    selectedSceneId.value = ep.scenes[0].id
    loadShots(ep.scenes[0].id)
  } else {
    selectedSceneId.value = null
    shots.value = []
    shotGroups.value = []
  }
}

function handleSelectScene(sceneId: any, epId: any) {
  shotDrawerRef.value?.close()
  selectedEpisodeId.value = epId
  selectedSceneId.value = sceneId
  loadShots(sceneId)
}

function handleCreateEpisode() {
  if (!currentDramaId.value) return
  const nextNo = (treeData.value?.episodes?.length || 0) + 1
  episodeModalRef.value?.openCreate(currentDramaId.value, nextNo)
}

function handleEditEpisode(epId: any) {
  episodeModalRef.value?.openEdit(epId)
}

function handleDeleteEpisode(epId: any) {
  ElMessageBox.confirm('确定要删除此剧集及其下所有场次分镜吗？', '删除确认', {
    type: 'warning',
    confirmButtonText: '确定删除',
    cancelButtonText: '取消'
  }).then(async () => {
    try {
      await episodeApi.delete(epId)
      ElMessage.success('剧集删除成功')
      refreshTreeAndShots()
    } catch (e: any) {
      ElMessage.error(e.message || '删除剧集失败')
    }
  }).catch(() => {})
}

function handleCreateScene(epId?: any) {
  const targetEpId = epId || selectedEpisodeId.value
  if (!targetEpId || !currentDramaId.value) {
    ElMessage.warning('请先创建或选择剧集')
    return
  }
  const ep = treeData.value?.episodes.find(e => String(e.id) === String(targetEpId))
  const nextNo = (ep?.scenes?.length || 0) + 1
  sceneModalRef.value?.openCreate(currentDramaId.value, targetEpId, nextNo)
}

function handleEditScene(sceneId: any) {
  if (!currentDramaId.value) return
  sceneModalRef.value?.openEdit(sceneId, currentDramaId.value)
}

function handleDeleteScene(sceneId: any) {
  ElMessageBox.confirm('确定要删除此场次及其下所有分镜吗？', '删除确认', {
    type: 'warning',
    confirmButtonText: '确定删除',
    cancelButtonText: '取消'
  }).then(async () => {
    try {
      await dramaSceneApi.delete(sceneId)
      ElMessage.success('场次删除成功')
      refreshTreeAndShots()
    } catch (e: any) {
      ElMessage.error(e.message || '删除场次失败')
    }
  }).catch(() => {})
}

function handleCreateShot(groupId?: any) {
  if (!currentDramaId.value || !selectedEpisodeId.value || !selectedSceneId.value) {
    ElMessage.warning('请先在左侧选择场次')
    return
  }
  const nextShotNo = (shots.value?.length || 0) + 1
  shotDrawerRef.value?.openCreate(
    currentDramaId.value,
    selectedEpisodeId.value,
    selectedSceneId.value,
    nextShotNo,
    currentScene.value?.resSceneId,
    groupId,
    currentDrama.value?.aspectRatio
  )
}

function handleEditShot(shotId: any) {
  if (!currentDramaId.value) return
  shotDrawerRef.value?.openEdit(shotId, currentDramaId.value, currentDrama.value?.aspectRatio)
}

function handleDeleteShot(shotId: any) {
  ElMessageBox.confirm('确定要删除此分镜吗？', '删除确认', {
    type: 'warning',
    confirmButtonText: '确定删除',
    cancelButtonText: '取消'
  }).then(async () => {
    try {
      await shotApi.delete(shotId)
      ElMessage.success('分镜删除成功')
      refreshShotsAndStats()
    } catch (e: any) {
      ElMessage.error(e.message || '删除分镜失败')
    }
  }).catch(() => {})
}

async function handleCloneShot(shotId: any) {
  try {
    await shotApi.clone(shotId)
    ElMessage.success('分镜复制成功')
    refreshShotsAndStats()
  } catch (e: any) {
    ElMessage.error(e.message || '复制失败')
  }
}

function handlePreviewShotPrompt(shot: DramaShot) {
  if (!shot.id) return
  promptPreviewModalRef.value?.open(
    shot.id,
    shot.shotNo,
    shot.shotName,
    shot.shotType,
    shot.cameraMovement
  )
}

function handleRenderShot(shotOrId: DramaShot | number | string) {
  const shot = typeof shotOrId === 'object'
    ? shotOrId
    : shots.value.find(s => String(s.id) === String(shotOrId))
  if (!shot || !shot.id) return

  const dramaRatio = currentDrama.value?.aspectRatio

  if (shot.generationMode === 'REFERENCE_MODE') {
    multiModalRefRenderModalRef.value?.open(shot, dramaRatio)
  } else {
    firstEndFrameRenderModalRef.value?.open(shot, undefined, dramaRatio)
  }
}

function handleOpenVideoHistory(shot: DramaShot) {
  if (!shot.id) return
  historyShot.value = shot
  historyVisible.value = true
}

function handleProcessVideo({ shot, operation }: { shot: DramaShot; operation: 'upscale' | 'interpolate' }) {
  if (!shot.id || !shot.videoUrl) return
  router.push({
    name: operation === 'upscale' ? 'VideoUpscale' : 'FrameInterpolation',
    query: { shotId: String(shot.id) }
  })
}

async function handleHistorySelected() {
  if (selectedSceneId.value) {
    await loadShots(selectedSceneId.value)
  }
}

async function handleReorderShots(newShotIds: any[]) {
  if (!selectedSceneId.value) return
  try {
    await shotApi.reorder({
      sceneId: selectedSceneId.value as number,
      shotIds: newShotIds
    })
    ElMessage.success('分镜顺序调整已保存')
    loadShots(selectedSceneId.value)
  } catch (e: any) {
    ElMessage.error(e.message || '重排排序失败')
  }
}

async function handleSceneSuccess(sceneId?: any, episodeId?: any) {
  if (currentDramaId.value) {
    await loadDramaWorkspace(currentDramaId.value, sceneId, episodeId)
  }
}

async function handleEpisodeSuccess(episodeId?: any) {
  if (currentDramaId.value) {
    await loadDramaWorkspace(currentDramaId.value, undefined, episodeId)
  }
}

async function refreshTreeAndShots(targetSceneId?: any, targetEpisodeId?: any) {
  if (currentDramaId.value) {
    await loadDramaWorkspace(currentDramaId.value, targetSceneId, targetEpisodeId)
  }
}

async function refreshShotsAndStats() {
  if (selectedSceneId.value) {
    loadShots(selectedSceneId.value)
  }
  if (currentDramaId.value) {
    const statsRes = await dramaApi.getStats(currentDramaId.value as number)
    dramaStats.value = statsRes || null
  }
}
</script>

<style scoped>
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: var(--border-default);
  border-radius: 6px;
}
</style>
