<template>
  <div class="h-full min-h-0 flex flex-col overflow-hidden">
    <DramaLibrary v-if="viewMode === 'gallery'" :items="dramaList" :total="total" :loading="loading" :query="query"
      @update:query="Object.assign(query, $event)" @search="handleSearch" @reset="handleResetQuery" @load="loadDramaList"
      @create="handleCreateDrama" @export="videoExportModalRef?.open()" @decompose="handleOpenDecompose" @enter="enterWorkbench" @edit="handleEditDrama" @delete="handleDeleteDrama" />

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
          <el-button plain size="small" @click="videoExportModalRef?.open(currentDramaId)">视频打包下载</el-button>
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
            @insert-shot="handleInsertShot"
            @edit-shot="handleEditShot"
            @delete-shot="handleDeleteShot"
            @clone-shot="handleCloneShot"
            @preview-prompt="handlePreviewShotPrompt"
            @render-shot="handleRenderShot"
            @batch-render="batchRenderModalRef?.open(currentDramaId, currentDrama?.aspectRatio)"
            @video-history="handleOpenVideoHistory"
            @process-video="handleProcessVideo"
            @reorder-shots="handleReorderShots"
            @refresh="refreshShotsAndStats"
          />
          <ShotDrawer ref="shotDrawerRef" @success="refreshShotsAndStats" @deleted="refreshShotsAndStats" />
        </div>
      </div>
    </div>

    <!-- 弹窗与抽屉套件 -->
    <DramaModal ref="dramaModalRef" @success="handleDramaModalSuccess" />
    <ShotVideoBatchRenderModal ref="batchRenderModalRef" />
    <ShotVideoExportModal ref="videoExportModalRef" />
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
import { Edit, ArrowLeft, Film, MagicStick } from '@element-plus/icons-vue'
import DictTag from '@/components/DictTag.vue'
import WorkspaceHeader from '@/components/workspace/WorkspaceHeader.vue'
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
import DramaLibrary from './components/DramaLibrary.vue'
import OutlineTree from './components/OutlineTree.vue'
import StoryboardGrid from './components/StoryboardGrid.vue'
import ShotVideoBatchRenderModal from './components/ShotVideoBatchRenderModal.vue'
import ShotVideoExportModal from './components/ShotVideoExportModal.vue'
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
  size: 12,
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
const batchRenderModalRef = ref()
const videoExportModalRef = ref<InstanceType<typeof ShotVideoExportModal>>()
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
  if (viewMode.value !== 'workbench' || !currentDramaId.value
    || String(task.dramaId) !== String(currentDramaId.value)) return
  if (task.taskType === 'SHOT_VIDEO') {
    void refreshShotsAndStats()
  } else if (selectedSceneId.value && String(task.sceneId) === String(selectedSceneId.value)) {
    void loadShots(selectedSceneId.value)
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
      if ((assetType === 'character' || assetType === 'scene' || assetType === 'prop' || assetType === 'keyframe') && assetId) {
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

function handleInsertShot({ shot, position }: { shot: DramaShot; position: 'before' | 'after' }) {
  if (!shot.id || !currentDramaId.value || !selectedEpisodeId.value || !selectedSceneId.value) return
  const group = shotGroups.value.find(item => String(item.id) === String(shot.shotGroupId))
  const index = group?.shots?.findIndex(item => String(item.id) === String(shot.id)) ?? -1
  const nextShotNo = index >= 0 ? index + (position === 'after' ? 2 : 1) : shot.shotNo + (position === 'after' ? 1 : 0)
  shotDrawerRef.value?.openCreate(
    currentDramaId.value, selectedEpisodeId.value, selectedSceneId.value, nextShotNo,
    currentScene.value?.resSceneId, shot.shotGroupId, currentDrama.value?.aspectRatio,
    { shotId: shot.id, position }
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
  await refreshShotsAndStats()
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
    void loadShots(selectedSceneId.value)
  }
  if (currentDramaId.value) {
    const dramaId = currentDramaId.value
    try {
      const [treeRes, statsRes] = await Promise.all([
        dramaApi.getTree(dramaId),
        dramaApi.getStats(dramaId)
      ])
      if (String(currentDramaId.value) !== String(dramaId)) return
      treeData.value = treeRes || null
      dramaStats.value = statsRes || null
    } catch (e: any) {
      ElMessage.error(e.message || '刷新制作进度失败')
    }
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
