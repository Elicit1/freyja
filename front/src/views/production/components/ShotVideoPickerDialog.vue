<template>
  <el-dialog
    v-model="visible"
    title="🎞️ 选择分镜视频源"
    width="1000px"
    top="6vh"
    destroy-on-close
    append-to-body
    class="shot-video-picker-dialog"
  >
    <div class="flex flex-col space-y-4 -mt-2">
      <!-- 顶部筛选控制栏 -->
      <div class="p-3.5 rounded-xl bg-slate-50 border border-slate-200/80 flex flex-wrap items-center gap-3">
        <!-- 短剧下拉 -->
        <div class="flex items-center gap-1.5 min-w-[190px]">
          <span class="text-xs text-slate-500 font-medium whitespace-nowrap">短剧:</span>
          <el-select
            v-model="queryParams.dramaId"
            placeholder="全部短剧"
            clearable
            filterable
            size="small"
            class="w-full"
            @change="handleDramaChange"
          >
            <el-option
              v-for="d in dramaOptions"
              :key="String(d.id)"
              :label="d.title"
              :value="String(d.id)"
            />
          </el-select>
        </div>

        <!-- 剧集下拉 -->
        <div class="flex items-center gap-1.5 min-w-[170px]">
          <span class="text-xs text-slate-500 font-medium whitespace-nowrap">剧集:</span>
          <el-select
            v-model="queryParams.episodeId"
            placeholder="全部剧集"
            clearable
            size="small"
            :disabled="!queryParams.dramaId"
            class="w-full"
            @change="handleEpisodeChange"
          >
            <el-option
              v-for="ep in episodeOptions"
              :key="String(ep.id)"
              :label="`第${ep.episodeNo}集 ${ep.title || ''}`"
              :value="String(ep.id)"
            />
          </el-select>
        </div>

        <!-- 场次下拉 -->
        <div class="flex items-center gap-1.5 min-w-[170px]">
          <span class="text-xs text-slate-500 font-medium whitespace-nowrap">场次:</span>
          <el-select
            v-model="queryParams.sceneId"
            placeholder="全部场次"
            clearable
            size="small"
            :disabled="!queryParams.episodeId"
            class="w-full"
            @change="handleSceneChange"
          >
            <el-option
              v-for="sc in sceneOptions"
              :key="String(sc.id)"
              :label="`场次${sc.sceneNo} ${sc.name || ''}`"
              :value="String(sc.id)"
            />
          </el-select>
        </div>

        <!-- 搜索输入框 -->
        <div class="flex-1 min-w-[180px]">
          <el-input
            v-model="queryParams.keyword"
            placeholder="搜索镜头编号 / 镜头名称..."
            clearable
            size="small"
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          >
            <template #prefix>
              <el-icon class="text-slate-400"><Search /></el-icon>
            </template>
          </el-input>
        </div>

        <!-- 搜索与重置按钮 -->
        <div class="flex items-center gap-2">
          <el-button type="primary" size="small" @click="handleSearch">
            <el-icon class="mr-1"><Search /></el-icon> 搜索
          </el-button>
          <el-button size="small" plain @click="handleReset">
            重置
          </el-button>
        </div>
      </div>

      <!-- 分镜卡片列表主体 -->
      <div v-loading="loading" class="min-h-[380px] max-h-[520px] overflow-y-auto pr-1 space-y-3 custom-scrollbar">
        <!-- 空状态提示 -->
        <div
          v-if="!loading && shotList.length === 0"
          class="py-16 text-center text-slate-400 flex flex-col items-center justify-center space-y-2"
        >
          <div class="text-4xl">🎬</div>
          <div class="text-sm font-medium text-slate-600">当前筛选范围内没有已生成视频的分镜</div>
          <div class="text-xs text-slate-400">请先在剧作大纲或渲染工坊完成分镜视频生成与归档</div>
        </div>

        <!-- 卡片项 -->
        <div
          v-for="shot in shotList"
          :key="String(shot.shotId)"
          class="p-3.5 bg-white rounded-xl border border-slate-200/80 hover:border-indigo-400 hover:shadow-md transition-all flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-4"
        >
          <!-- 左侧：缩略图/首帧预览与信息 -->
          <div class="flex items-center gap-3.5 flex-1 min-w-0">
            <!-- 封面图/视频预览 -->
            <div class="w-32 h-20 bg-slate-900 rounded-lg overflow-hidden flex-shrink-0 relative group flex items-center justify-center border border-slate-200">
              <img
                v-if="shot.posterUrl"
                :src="shot.posterUrl"
                class="w-full h-full object-cover"
                loading="lazy"
              />
              <div v-else class="text-slate-500 text-xs flex flex-col items-center">
                <el-icon class="text-lg mb-0.5"><Film /></el-icon>
                <span>无预览图</span>
              </div>

              <!-- 播放悬浮按钮 -->
              <div
                v-if="shot.videoUrl"
                class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center cursor-pointer"
                @click="previewVideo(shot.videoUrl, `S${shot.shotNo} ${shot.shotName || ''}`)"
              >
                <div class="w-8 h-8 rounded-full bg-white/90 text-slate-900 flex items-center justify-center shadow">
                  <el-icon><VideoPlay /></el-icon>
                </div>
              </div>

              <!-- 模式与时长角标 -->
              <div class="absolute bottom-1 right-1 px-1.5 py-0.5 rounded bg-black/70 backdrop-blur text-[10px] font-mono text-white pointer-events-none">
                {{ shot.duration ? shot.duration + 's' : '视频' }}
              </div>
            </div>

            <!-- 分镜元信息 -->
            <div class="flex-1 min-w-0 space-y-1">
              <!-- 层级面包屑 -->
              <div class="text-[11px] text-slate-500 truncate flex items-center gap-1.5">
                <span class="font-medium text-slate-700 max-w-[140px] truncate" :title="shot.dramaTitle">
                  {{ shot.dramaTitle || '未命名短剧' }}
                </span>
                <span class="text-slate-300">/</span>
                <span>第{{ shot.episodeNo || 1 }}集</span>
                <span v-if="shot.sceneNo !== undefined && shot.sceneNo !== null" class="text-slate-300">/</span>
                <span v-if="shot.sceneNo !== undefined && shot.sceneNo !== null">场次{{ shot.sceneNo }}</span>
              </div>

              <!-- 镜头编号与名称 -->
              <div class="flex items-center gap-2">
                <span class="font-mono font-bold text-sm text-indigo-600 bg-indigo-50 border border-indigo-200 px-1.5 py-0.5 rounded">
                  S{{ shot.shotNo }}
                </span>
                <span class="font-semibold text-sm text-slate-800 truncate" :title="shot.shotName">
                  {{ shot.shotName || '镜头描述' }}
                </span>
              </div>

              <!-- 状态标签与版本数 -->
              <div class="flex items-center gap-2 text-xs text-slate-500">
                <span v-if="shot.generationMode" class="text-[10px] px-1.5 py-0.5 rounded bg-slate-100 text-slate-600">
                  {{ shot.generationMode }}
                </span>
                <span class="text-emerald-600 bg-emerald-50 border border-emerald-200 text-[10px] px-1.5 py-0.5 rounded flex items-center gap-1">
                  <span class="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>
                  当前视频
                </span>
                <span v-if="(shot.videoTakeCount || 0) > 1" class="text-indigo-600 text-[11px] font-medium">
                  共 {{ shot.videoTakeCount }} 个候选版本
                </span>
              </div>
            </div>
          </div>

          <!-- 右侧：选择与操作按钮组 -->
          <div class="flex items-center gap-2 flex-shrink-0">
            <el-button
              size="small"
              plain
              @click="previewVideo(shot.videoUrl, `S${shot.shotNo} ${shot.shotName || ''}`)"
            >
              <el-icon class="mr-1"><VideoPlay /></el-icon> 预览
            </el-button>

            <!-- 历史版本抽屉按钮 -->
            <el-button
              v-if="(shot.videoTakeCount || 0) > 0"
              size="small"
              plain
              type="info"
              @click="openTakesDialog(shot)"
            >
              <el-icon class="mr-1"><Clock /></el-icon> 选历史 Take ({{ shot.videoTakeCount }})
            </el-button>

            <!-- 选择当前视频主按钮 -->
            <el-button
              size="small"
              type="primary"
              @click="selectCurrentVideo(shot)"
            >
              <el-icon class="mr-1"><Check /></el-icon> 选此当前视频
            </el-button>
          </div>
        </div>
      </div>

      <!-- 分页栏 -->
      <div class="pt-2 flex justify-between items-center border-t border-slate-100">
        <span class="text-xs text-slate-500">
          共找到 <strong class="text-indigo-600 font-mono">{{ total }}</strong> 个可用分镜视频
        </span>
        <el-pagination
          v-model:current-page="queryParams.current"
          v-model:page-size="queryParams.size"
          :total="total"
          :page-sizes="[10, 20, 30, 50]"
          layout="total, prev, pager, next"
          size="small"
          @current-change="loadShotList"
          @size-change="handleSizeChange"
        />
      </div>
    </div>

    <!-- 视频独立快速弹层播放器 -->
    <el-dialog
      v-model="videoPlayerVisible"
      :title="`视频预览 - ${activePreviewTitle}`"
      width="720px"
      destroy-on-close
      append-to-body
    >
      <div class="aspect-video w-full bg-black rounded-lg overflow-hidden flex items-center justify-center">
        <video
          v-if="activePreviewUrl"
          :src="activePreviewUrl"
          controls
          autoplay
          preload="metadata"
          class="w-full h-full object-contain"
        />
      </div>
    </el-dialog>

    <!-- 历史 Take 候选选择弹框 -->
    <el-dialog
      v-model="takesDialogVisible"
      :title="`🎞️ [S${activeShotForTakes?.shotNo || ''} ${activeShotForTakes?.shotName || '分镜'}] 历史 Take 候选版本`"
      width="min(900px, 92vw)"
      top="5vh"
      destroy-on-close
      append-to-body
    >
      <div class="flex flex-col max-h-[76vh] -mx-2 -my-2">
        <div class="px-4 py-3 bg-[var(--surface-muted)] border-b border-[var(--border-default)] flex items-center justify-between text-xs text-[var(--text-secondary)] select-none">
          <div class="flex items-center gap-2">
            <span>共 <strong class="text-[var(--brand)] font-mono">{{ takesList.length }}</strong> 个历史视频版本</span>
            <span class="text-[var(--border-strong)]">|</span>
            <span class="text-[var(--text-muted)]">可指定抽卡历史作为后处理源</span>
          </div>
          <el-button size="small" link type="primary" :loading="takesLoading" @click="loadTakesForActiveShot">
            🔄 刷新
          </el-button>
        </div>

        <div v-loading="takesLoading" class="flex-1 overflow-y-auto px-6 py-4 space-y-4 custom-scrollbar">
          <div
            v-if="!takesLoading && takesList.length === 0"
            class="py-16 text-center text-[var(--text-muted)] select-none"
          >
            <div class="text-3xl mb-2">🎞️</div>
            <div class="text-sm font-medium text-[var(--text-secondary)]">该镜头尚无历史 Take 版本记录</div>
          </div>

          <div v-else class="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div
              v-for="take in takesList"
              :key="String(take.id)"
              class="studio-card !p-0 overflow-hidden flex flex-col justify-between transition-all"
              :class="take.current ? '!border-emerald-400 ring-2 ring-emerald-100' : 'hover:!border-[var(--brand)]'"
            >
              <!-- 头部 -->
              <div class="px-3.5 py-2.5 bg-[var(--surface-muted)] border-b border-[var(--border-default)] flex items-center justify-between">
                <div class="flex items-center gap-1.5">
                  <span class="font-mono font-bold text-xs px-2 py-0.5 rounded bg-[var(--text-primary)] text-[var(--surface)]">
                    Take #{{ take.takeNo }}
                  </span>
                  <span v-if="take.sourceType === 'LEGACY_BACKFILL'" class="text-[10px] text-amber-600 bg-amber-50 border border-amber-200 px-1.5 py-0.2 rounded">
                    历史回填
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
                  <span v-else class="text-[11px] text-[var(--text-muted)] font-mono">
                    候选版本
                  </span>
                </div>
              </div>

              <!-- 播放画框 -->
              <div class="aspect-video w-full bg-black relative flex items-center justify-center overflow-hidden">
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

              <!-- 底部信息与选择按钮 -->
              <div class="p-3 text-[11px] space-y-2 text-[var(--text-secondary)] bg-[var(--surface)]">
                <div class="flex items-center justify-between text-[var(--text-muted)]">
                  <span class="truncate max-w-[140px]" :title="take.modelCode || '默认视频模型'">
                    🤖 {{ take.modelCode || '默认视频模型' }}
                  </span>
                  <span class="font-mono text-[10px]">
                    {{ take.duration ? take.duration + 's' : '' }}
                  </span>
                </div>

                <el-button
                  type="primary"
                  size="small"
                  class="w-full"
                  :disabled="!take.videoUrl"
                  @click="selectSpecificTake(take)"
                >
                  <el-icon class="mr-1"><Check /></el-icon> 选择此 Take #{{ take.takeNo }} 版本
                </el-button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </el-dialog>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Search,
  Film,
  VideoPlay,
  Check,
  Clock
} from '@element-plus/icons-vue'
import { dramaApi, episodeApi, dramaSceneApi, shotApi } from '@/api/drama'
import {
  getSourceShots,
  type ShotVideoSourceOptionVO,
  type ShotVideoSourceQuery
} from '@/api/videoProcessing'
import type { DramaOption, DramaEpisode, DramaScene, ShotVideoTake } from '@/types/drama'

export interface SelectedVideoProcessSource {
  sourceType: 'SHOT_CURRENT' | 'SHOT_VIDEO_TAKE'
  sourceVideoUrl: string
  sourceDramaId: string
  sourceEpisodeId?: string
  sourceSceneId?: string
  sourceShotId: string
  sourceVideoTakeId?: string
  dramaTitle?: string
  episodeTitle?: string
  sceneName?: string
  shotNo?: number
  shotName?: string
  takeNo?: number
  posterUrl?: string
}

const emit = defineEmits<{
  (e: 'selected', source: SelectedVideoProcessSource): void
}>()

// Dialog visibility
const visible = ref(false)
const loading = ref(false)

// Cascading Options
const dramaOptions = ref<DramaOption[]>([])
const episodeOptions = ref<DramaEpisode[]>([])
const sceneOptions = ref<DramaScene[]>([])

// Query Params
const queryParams = reactive<ShotVideoSourceQuery>({
  dramaId: undefined,
  episodeId: undefined,
  sceneId: undefined,
  keyword: '',
  current: 1,
  size: 20
})

// Data list
const shotList = ref<ShotVideoSourceOptionVO[]>([])
const total = ref(0)

// Quick Preview Video Dialog
const videoPlayerVisible = ref(false)
const activePreviewUrl = ref('')
const activePreviewTitle = ref('')

// Takes dialog state
const takesDialogVisible = ref(false)
const takesLoading = ref(false)
const activeShotForTakes = ref<ShotVideoSourceOptionVO | null>(null)
const takesList = ref<ShotVideoTake[]>([])

/**
 * 打开对话框
 */
async function open(presetDramaId?: string | number) {
  visible.value = true
  if (presetDramaId) {
    queryParams.dramaId = String(presetDramaId)
  }
  queryParams.current = 1
  await loadDramaOptions()
  if (queryParams.dramaId) {
    await loadEpisodeOptions(queryParams.dramaId)
  }
  await loadShotList()
}

/**
 * 加载短剧下拉列表
 */
async function loadDramaOptions() {
  try {
    const list = await dramaApi.getOptions()
    dramaOptions.value = list || []
  } catch (err) {
    console.error('Failed to load drama options:', err)
  }
}

/**
 * 加载剧集下拉列表
 */
async function loadEpisodeOptions(dramaId: string | number) {
  try {
    const list = await episodeApi.getListByDramaId(String(dramaId))
    episodeOptions.value = list || []
  } catch (err) {
    console.error('Failed to load episodes:', err)
  }
}

/**
 * 加载场次下拉列表
 */
async function loadSceneOptions(episodeId: string | number) {
  try {
    const list = await dramaSceneApi.getListByEpisodeId(String(episodeId))
    sceneOptions.value = list || []
  } catch (err) {
    console.error('Failed to load scenes:', err)
  }
}

/**
 * 短剧切换
 */
async function handleDramaChange(val?: string) {
  queryParams.episodeId = undefined
  queryParams.sceneId = undefined
  episodeOptions.value = []
  sceneOptions.value = []
  if (val) {
    await loadEpisodeOptions(val)
  }
  handleSearch()
}

/**
 * 剧集切换
 */
async function handleEpisodeChange(val?: string) {
  queryParams.sceneId = undefined
  sceneOptions.value = []
  if (val) {
    await loadSceneOptions(val)
  }
  handleSearch()
}

/**
 * 场次切换
 */
function handleSceneChange() {
  handleSearch()
}

/**
 * 搜索/分页重置为第一页
 */
function handleSearch() {
  queryParams.current = 1
  loadShotList()
}

/**
 * 重置全部筛选
 */
function handleReset() {
  queryParams.dramaId = undefined
  queryParams.episodeId = undefined
  queryParams.sceneId = undefined
  queryParams.keyword = ''
  queryParams.current = 1
  episodeOptions.value = []
  sceneOptions.value = []
  loadShotList()
}

/**
 * 每页条数变更
 */
function handleSizeChange(size: number) {
  queryParams.size = size
  handleSearch()
}

/**
 * 查询分镜视频列表
 */
async function loadShotList() {
  loading.value = true
  try {
    const res = await getSourceShots({
      dramaId: queryParams.dramaId ? String(queryParams.dramaId) : undefined,
      episodeId: queryParams.episodeId ? String(queryParams.episodeId) : undefined,
      sceneId: queryParams.sceneId ? String(queryParams.sceneId) : undefined,
      keyword: queryParams.keyword ? queryParams.keyword.trim() : undefined,
      current: queryParams.current,
      size: queryParams.size
    })
    shotList.value = res.records || []
    total.value = res.total || 0
  } catch (err: any) {
    ElMessage.error(err?.message || '获取分镜视频列表失败')
  } finally {
    loading.value = false
  }
}

/**
 * 快速播放预览
 */
function previewVideo(url: string, title: string) {
  if (!url) {
    ElMessage.warning('视频播放链接不存在')
    return
  }
  activePreviewUrl.value = url
  activePreviewTitle.value = title
  videoPlayerVisible.value = true
}

/**
 * 选择分镜当前视频 (SHOT_CURRENT)
 */
function selectCurrentVideo(shot: ShotVideoSourceOptionVO) {
  emit('selected', {
    sourceType: 'SHOT_CURRENT',
    sourceVideoUrl: shot.videoUrl,
    sourceDramaId: shot.dramaId ? String(shot.dramaId) : '',
    sourceEpisodeId: shot.episodeId ? String(shot.episodeId) : undefined,
    sourceSceneId: shot.sceneId ? String(shot.sceneId) : undefined,
    sourceShotId: String(shot.shotId),
    sourceVideoTakeId: shot.currentVideoTakeId ? String(shot.currentVideoTakeId) : undefined,
    dramaTitle: shot.dramaTitle,
    episodeTitle: shot.episodeTitle,
    sceneName: shot.sceneName,
    shotNo: shot.shotNo,
    shotName: shot.shotName,
    posterUrl: shot.posterUrl
  })
  visible.value = false
}

/**
 * 打开历史 Take 抽屉
 */
async function openTakesDialog(shot: ShotVideoSourceOptionVO) {
  activeShotForTakes.value = shot
  takesDialogVisible.value = true
  await loadTakesForActiveShot()
}

/**
 * 加载当前镜头的 Takes 候选
 */
async function loadTakesForActiveShot() {
  if (!activeShotForTakes.value) return
  takesLoading.value = true
  try {
    const res = await shotApi.getVideoTakes(String(activeShotForTakes.value.shotId), {
      current: 1,
      size: 50
    })
    takesList.value = res.records || []
  } catch (err: any) {
    ElMessage.error(err?.message || '加载历史 Take 失败')
  } finally {
    takesLoading.value = false
  }
}

/**
 * 选择特定的历史 Take (SHOT_VIDEO_TAKE)
 */
function selectSpecificTake(take: ShotVideoTake) {
  if (!activeShotForTakes.value) return
  const shot = activeShotForTakes.value
  emit('selected', {
    sourceType: 'SHOT_VIDEO_TAKE',
    sourceVideoUrl: take.videoUrl,
    sourceDramaId: shot.dramaId ? String(shot.dramaId) : '',
    sourceEpisodeId: shot.episodeId ? String(shot.episodeId) : undefined,
    sourceSceneId: shot.sceneId ? String(shot.sceneId) : undefined,
    sourceShotId: String(shot.shotId),
    sourceVideoTakeId: String(take.id),
    dramaTitle: shot.dramaTitle,
    episodeTitle: shot.episodeTitle,
    sceneName: shot.sceneName,
    shotNo: shot.shotNo,
    shotName: shot.shotName,
    takeNo: take.takeNo,
    posterUrl: take.firstFrameUrl || shot.posterUrl
  })
  takesDialogVisible.value = false
  visible.value = false
}

defineExpose({
  open
})
</script>

<style scoped>
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: rgba(148, 163, 184, 0.4);
  border-radius: 3px;
}
.custom-scrollbar::-webkit-scrollbar-thumb:hover {
  background: rgba(148, 163, 184, 0.6);
}
</style>
