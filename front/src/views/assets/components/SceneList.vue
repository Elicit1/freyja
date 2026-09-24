<template>
  <div class="space-y-4">
    <!-- 列表展示区（未编辑时展示） -->
    <div v-show="!isEditing" class="space-y-4">
      <!-- 筛选搜索栏 -->
      <div class="studio-card p-4">
      <el-form :model="queryParams" inline class="flex flex-wrap items-center gap-2 mb-0">
        <el-form-item label="场景名称">
          <el-input
            v-model="queryParams.name"
            placeholder="搜索场景名称"
            clearable
            @keyup.enter="handleSearch"
            class="!w-44"
          />
        </el-form-item>

        <el-form-item label="空间类型">
          <el-select v-model="queryParams.sceneType" placeholder="全部" clearable class="!w-32">
            <el-option label="室内 (Indoor)" value="INDOOR" />
            <el-option label="室外 (Outdoor)" value="OUTDOOR" />
            <el-option label="摄影棚 (Studio)" value="STUDIO" />
            <el-option label="虚构/奇幻 (Virtual)" value="VIRTUAL" />
          </el-select>
        </el-form-item>

        <el-form-item label="时段">
          <el-select v-model="queryParams.timeOfDay" placeholder="全部" clearable class="!w-28">
            <el-option label="日间 (Day)" value="DAY" />
            <el-option label="夜间 (Night)" value="NIGHT" />
            <el-option label="黄昏 (Sunset)" value="SUNSET" />
            <el-option label="拂晓 (Dawn)" value="DAWN" />
          </el-select>
        </el-form-item>

        <el-form-item label="所属库">
          <el-select v-model="queryParams.dramaId" placeholder="全部所属库" clearable class="!w-40" filterable>
            <el-option label="🌐 全局公共库" :value="0" />
            <el-option
              v-for="d in dramaOptions"
              :key="String(d.id)"
              :label="`🎬 ${d.title}`"
              :value="d.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="状态">
          <el-select v-model="queryParams.status" placeholder="全部" clearable class="!w-28">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 操作与视图切换栏 -->
    <div class="flex items-center justify-between flex-wrap gap-2">
      <div class="flex items-center gap-2">
        <el-button type="primary" :icon="Plus" @click="handleCreate">新建场景</el-button>
        <span class="text-xs text-[var(--text-muted)]">共 {{ total }} 个场景资产</span>
      </div>

      <div class="flex items-center gap-2">
        <el-radio-group v-model="viewMode" size="small">
          <el-radio-button value="grid">卡片网格</el-radio-button>
          <el-radio-button value="table">表格列表</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <!-- 1. 卡片网格视图 -->
    <div v-if="viewMode === 'grid'" v-loading="loading" class="min-h-[360px]">
      <div v-if="sceneList.length > 0" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
        <div
          v-for="scene in sceneList"
          :key="String(scene.id)"
          class="studio-card overflow-hidden flex flex-col justify-between group transition-all cursor-pointer hover:-translate-y-0.5 hover:shadow-md"
          @click="handleEdit(scene)"
        >
          <!-- 卡片封面图 (16:9 固定比例) -->
          <div class="relative aspect-video bg-[var(--surface-muted)] overflow-hidden">
            <div class="w-full h-full" @click.stop>
              <el-image
                v-if="scene.coverUrl || scene.referenceImageUrl"
                :src="scene.coverUrl || scene.referenceImageUrl"
                :preview-src-list="getSceneImages(scene)"
                preview-teleported
                fit="cover"
                class="w-full h-full group-hover:scale-105 transition-transform duration-300 cursor-pointer"
              />
              <div v-else class="w-full h-full flex flex-col items-center justify-center text-[var(--text-muted)] gap-1">
                <el-icon class="text-3xl opacity-40"><Picture /></el-icon>
                <span class="text-[11px] font-mono opacity-60">暂无场景预览</span>
              </div>
            </div>

            <!-- 顶部半透明浮层 Tag -->
            <div class="absolute top-2 left-2 flex items-center gap-1.5 pointer-events-none">
              <el-tag v-if="scene.sceneType" size="small" effect="dark" :type="getSceneTypeTag(scene.sceneType)">
                {{ getSceneTypeLabel(scene.sceneType) }}
              </el-tag>
              <el-tag v-if="scene.timeOfDay" size="small" effect="dark" type="info">
                {{ getTimeOfDayLabel(scene.timeOfDay) }}
              </el-tag>
              <el-tag size="small" effect="dark" :type="isGlobalDrama(scene.dramaId) ? 'success' : 'warning'">
                {{ getDramaLabel(scene.dramaId) }}
              </el-tag>
            </div>

            <!-- 右上角更多操作 -->
            <div class="absolute top-2 right-2 z-10" @click.stop>
              <el-dropdown trigger="click" :persistent="false" placement="bottom-end">
                <el-button
                  circle
                  size="small"
                  :icon="MoreFilled"
                  class="!border-none !bg-black/40 !text-white hover:!bg-black/60"
                  @click.stop
                />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item :icon="Edit" @click="handleEdit(scene)">编辑场景</el-dropdown-item>
                    <el-dropdown-item :icon="Delete" divided class="!text-[var(--danger)]" @click="handleDeleteWithConfirm(scene)">
                      删除场景
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>

            <!-- 底部半透明阴影标题 -->
            <div class="absolute bottom-0 inset-x-0 bg-gradient-to-t from-black/80 via-black/40 to-transparent p-3 pt-6 text-white pointer-events-none">
              <div class="font-bold text-sm leading-snug truncate">{{ scene.name }}</div>
              <div class="text-[11px] text-white/80 flex items-center gap-2 mt-0.5">
                <span v-if="scene.weatherAtmosphere">{{ scene.weatherAtmosphere }}</span>
                <span v-if="isGlobalDrama(scene.dramaId)" class="text-emerald-300">· 公共库</span>
                <span v-else class="text-amber-300">· {{ getDramaLabel(scene.dramaId) }}</span>
              </div>
            </div>
          </div>

          <!-- 卡片内容体 -->
          <div class="p-4 space-y-2.5 flex-1">
            <!-- 中文场景描述 -->
            <div class="bg-gray-50 rounded-lg p-2.5 border border-gray-100 relative min-h-[58px]">
              <div class="text-xs text-gray-700 leading-relaxed line-clamp-3">
                {{ scene.description || '暂无中文场景描述（可点击右下角编辑补充）' }}
              </div>
            </div>

            <!-- LoRA 与参考图标签 -->
            <div class="flex items-center gap-1.5 flex-wrap pt-1">
              <el-tag v-if="scene.loraName" size="small" type="warning" effect="light" class="truncate max-w-[180px]">
                ⚡ LoRA: {{ scene.loraName }}
              </el-tag>
              <div
                v-if="scene.referenceImageUrl"
                class="inline-flex items-center gap-1 bg-slate-100 hover:bg-blue-50 border border-slate-200 hover:border-blue-200 px-1.5 py-0.5 rounded text-[11px] text-slate-700 hover:text-blue-700 transition-colors cursor-pointer"
                title="点击预览空间参考图"
                @click.stop
              >
                <el-image
                  :src="scene.referenceImageUrl"
                  :preview-src-list="[scene.referenceImageUrl]"
                  preview-teleported
                  fit="cover"
                  class="w-3.5 h-3.5 rounded shrink-0 block"
                />
                <span>空间参考图</span>
              </div>
            </div>
          </div>

          <!-- 卡片底部操作 -->
          <div class="px-4 py-2.5 bg-gray-50/80 border-t border-gray-100 flex items-center justify-between text-xs">
            <span class="text-gray-400">ID: {{ scene.id }}</span>
            <span class="text-[11px] text-slate-400 group-hover:text-indigo-600 transition-colors">
              点击卡片编辑
            </span>
          </div>
        </div>
      </div>

      <el-empty v-else-if="!loading" description="暂无场景环境资产，点击上方新建场景" />
    </div>

    <!-- 2. 表格列表视图 -->
    <el-card v-else shadow="never" class="!border-gray-200">
      <el-table v-loading="loading" :data="sceneList" stripe style="width: 100%">
        <el-table-column label="场景名称" min-width="180">
          <template #default="{ row }">
            <div class="flex items-center gap-2.5">
              <div v-if="row.coverUrl || row.referenceImageUrl" class="relative group cursor-pointer shrink-0">
                <el-image
                  :src="row.coverUrl || row.referenceImageUrl"
                  :preview-src-list="getSceneImages(row)"
                  preview-teleported
                  fit="cover"
                  class="w-12 h-9 rounded object-cover block border border-gray-200"
                />
                <div class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity rounded flex items-center justify-center text-white text-[9px] pointer-events-none">
                  🔍
                </div>
              </div>
              <div v-else class="w-12 h-9 rounded bg-gray-100 flex items-center justify-center text-xs text-gray-400 shrink-0">
                🏞️
              </div>
              <div class="truncate">
                <div class="font-bold text-gray-900 truncate">{{ row.name }}</div>
                <div class="text-xs text-gray-400">{{ row.weatherAtmosphere || '默认天气' }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="空间类型" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.sceneType" :type="getSceneTypeTag(row.sceneType)" size="small">
              {{ getSceneTypeLabel(row.sceneType) }}
            </el-tag>
            <span v-else class="text-xs text-gray-400">未选择</span>
          </template>
        </el-table-column>

        <el-table-column label="时段" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.timeOfDay" type="info" size="small">
              {{ getTimeOfDayLabel(row.timeOfDay) }}
            </el-tag>
            <span v-else class="text-xs text-gray-400">未选择</span>
          </template>
        </el-table-column>

        <el-table-column label="所属库" width="130">
          <template #default="{ row }">
            <el-tag :type="isGlobalDrama(row.dramaId) ? 'success' : 'info'" size="small" effect="plain">
              {{ getDramaLabel(row.dramaId) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="中文场景描述" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-xs text-gray-700">{{ row.description || '暂无描述' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="风格 LoRA" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.loraName" class="text-xs font-mono text-indigo-600">
              {{ row.loraName }} ({{ row.loraWeight }})
            </span>
            <span v-else class="text-xs text-gray-400">无</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="140" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleEdit(row)">编辑</el-button>
            <el-popconfirm title="确定删除该场景环境吗？" @confirm="handleDelete(row)">
              <template #reference>
                <el-button type="danger" link size="small">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 分页器 -->
    <div class="flex justify-end pt-2">
      <el-pagination
        v-model:current-page="queryParams.current"
        v-model:page-size="queryParams.size"
        :total="total"
        :page-sizes="[8, 12, 24, 48]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="fetchData"
        @current-change="fetchData"
      />
    </div>
    </div>

    <!-- 场景主内容区编辑组件 -->
    <SceneDrawer ref="sceneDrawerRef" @success="handleDrawerSuccess" @cancel="emit('asset-cancelled')" @visible-change="isEditing = $event" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Search,
  Refresh,
  Plus,
  Edit,
  Delete,
  MoreFilled
} from '@element-plus/icons-vue'
import { sceneApi } from '@/api/res-scene'
import { dramaApi } from '@/api/drama'
import type { ResScene, ResSceneQuery } from '@/types/resource'
import type { DramaOption } from '@/types/drama'
import SceneDrawer from './SceneDrawer.vue'

const emit = defineEmits<{
  (e: 'asset-created', assetId: string | number): void
  (e: 'asset-cancelled'): void
}>()

const isEditing = ref(false)
const loading = ref(false)
const viewMode = ref<'grid' | 'table'>('grid')
const sceneList = ref<ResScene[]>([])
const total = ref(0)
const dramaOptions = ref<DramaOption[]>([])

const sceneDrawerRef = ref<InstanceType<typeof SceneDrawer>>()

const queryParams = reactive<ResSceneQuery>({
  current: 1,
  size: 12,
  dramaId: undefined,
  name: '',
  sceneType: '',
  timeOfDay: '',
  weatherAtmosphere: '',
  status: undefined
})

onMounted(() => {
  loadDramaOptions()
  fetchData()
})

async function loadDramaOptions() {
  try {
    const res = await dramaApi.getOptions()
    dramaOptions.value = res || []
  } catch (err) {
    console.error('加载短剧列表失败', err)
  }
}

async function fetchData() {
  try {
    loading.value = true
    const res = await sceneApi.getPage(queryParams)
    sceneList.value = res.records || []
    total.value = res.total || 0
  } catch (error) {
    ElMessage.error('加载场景列表失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  queryParams.current = 1
  fetchData()
}

function handleReset() {
  queryParams.current = 1
  queryParams.name = ''
  queryParams.sceneType = ''
  queryParams.timeOfDay = ''
  queryParams.weatherAtmosphere = ''
  queryParams.dramaId = undefined
  queryParams.status = undefined
  fetchData()
}

function handleCreate(dramaId?: string | number) {
  sceneDrawerRef.value?.open(undefined, dramaId)
}

function handleDrawerSuccess(assetId?: string | number) {
  fetchData()
  if (assetId !== undefined && assetId !== null && assetId !== '') {
    emit('asset-created', assetId)
  }
}

function handleEdit(scene: ResScene) {
  sceneDrawerRef.value?.open(scene)
}

async function handleDeleteWithConfirm(scene: ResScene) {
  if (!scene.id) return
  try {
    await ElMessageBox.confirm(`确定要删除场景「${scene.name}」吗？此操作无法撤销。`, '删除确认', {
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await handleDelete(scene)
  } catch {
    // 用户取消删除
  }
}

async function handleDelete(scene: ResScene) {
  if (!scene.id) return
  try {
    await sceneApi.delete(scene.id)
    ElMessage.success('场景已成功删除')
    fetchData()
  } catch (error) {
    ElMessage.error('删除场景失败')
  }
}


function getSceneTypeLabel(sceneType?: string) {
  if (!sceneType) return ''
  const map: Record<string, string> = {
    INDOOR: '室内',
    OUTDOOR: '室外',
    STUDIO: '摄影棚',
    VIRTUAL: '虚构/奇幻'
  }
  return map[sceneType] || sceneType
}

function getSceneTypeTag(sceneType?: string): 'success' | 'warning' | 'primary' | 'info' {
  if (!sceneType) return 'info'
  const map: Record<string, 'success' | 'warning' | 'primary' | 'info'> = {
    INDOOR: 'primary',
    OUTDOOR: 'success',
    STUDIO: 'warning',
    VIRTUAL: 'info'
  }
  return map[sceneType] || 'info'
}

function getTimeOfDayLabel(timeOfDay?: string) {
  if (!timeOfDay) return ''
  const map: Record<string, string> = {
    DAY: '日间',
    NIGHT: '夜间',
    SUNSET: '黄昏',
    DAWN: '拂晓'
  }
  return map[timeOfDay] || timeOfDay
}

function getSceneImages(scene: ResScene): string[] {
  return [scene.coverUrl, scene.referenceImageUrl].filter(Boolean) as string[]
}

function isGlobalDrama(dramaId?: string | number): boolean {
  return dramaId === undefined || dramaId === null || dramaId === 0 || dramaId === '0' || String(dramaId) === '0'
}

function getDramaLabel(dramaId?: string | number): string {
  if (isGlobalDrama(dramaId)) {
    return '公共库'
  }
  const found = dramaOptions.value.find(d => String(d.id) === String(dramaId))
  return found ? found.title : `短剧#${dramaId}`
}

defineExpose({ fetchData, handleCreate })
</script>
