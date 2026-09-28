<template>
  <div class="space-y-4">
    <!-- 列表展示区（未编辑时展示） -->
    <div v-show="!isEditing" class="space-y-4">
      <!-- 筛选搜索栏 -->
      <div class="studio-card p-4">
        <el-form :model="queryParams" inline class="flex flex-wrap items-center gap-2 mb-0">
          <el-form-item label="关键帧名称">
            <el-input
              v-model="queryParams.name"
              placeholder="搜索关键帧名称"
              clearable
              @keyup.enter="handleSearch"
              class="!w-44"
            />
          </el-form-item>

          <el-form-item label="类型">
            <el-select v-model="queryParams.frameType" placeholder="全部类型" clearable class="!w-40">
              <el-option label="普通关键帧" value="KEYFRAME" />
              <el-option label="首帧 (FIRST)" value="FIRST_FRAME" />
              <el-option label="尾帧 (END)" value="END_FRAME" />
              <el-option label="动作节奏帧" value="ACTION_BEAT" />
            </el-select>
          </el-form-item>

          <el-form-item label="所属库">
            <el-select
              v-model="queryParams.dramaId"
              placeholder="全部所属库"
              clearable
              class="!w-44"
              filterable
              @change="handleFilterDramaChange"
            >
              <el-option label="🌐 全局公共库" :value="0" />
              <el-option
                v-for="d in dramaOptions"
                :key="String(d.id)"
                :label="`🎬 ${d.title}`"
                :value="d.id"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="分镜">
            <el-select
              v-model="queryParams.shotId"
              placeholder="全部镜头"
              clearable
              class="!w-40"
              filterable
              :disabled="!queryParams.dramaId || queryParams.dramaId === 0"
            >
              <el-option
                v-for="s in filterShotOptions"
                :key="String(s.id)"
                :label="s.shotName ? `[${s.shotName}]` : `[镜 ${s.shotNo}]`"
                :value="s.id"
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
          <el-button type="primary" :icon="Plus" @click="handleCreate">新建关键帧</el-button>
          <span class="text-xs text-[var(--text-muted)]">共 {{ total }} 个关键帧资产</span>
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
        <div v-if="keyframeList.length > 0" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
          <div
            v-for="item in keyframeList"
            :key="String(item.id)"
            class="studio-card overflow-hidden flex flex-col justify-between group transition-all cursor-pointer hover:-translate-y-0.5 hover:shadow-md"
            @click="handleEdit(item)"
          >
            <!-- 关键帧封面 -->
            <div class="relative h-44 bg-[var(--surface-muted)] overflow-hidden">
              <div class="w-full h-full" @click.stop>
                <el-image
                  v-if="item.frameUrl"
                  :src="item.frameUrl"
                  :preview-src-list="[item.frameUrl]"
                  preview-teleported
                  fit="cover"
                  class="w-full h-full group-hover:scale-105 transition-transform duration-300 cursor-pointer"
                />
                <div v-else class="w-full h-full flex flex-col items-center justify-center text-[var(--text-muted)] gap-1">
                  <el-icon class="text-3xl opacity-40"><PictureFilled /></el-icon>
                  <span class="text-[11px] font-mono opacity-60">暂无关键帧图像</span>
                </div>
              </div>

              <!-- 顶部半透明浮层 Tag -->
              <div class="absolute top-2 left-2 flex items-center gap-1.5 pointer-events-none">
                <el-tag size="small" effect="dark" :type="getFrameTypeTag(item.frameType)">
                  {{ getFrameTypeLabel(item.frameType) }}
                </el-tag>
                <el-tag size="small" effect="dark" :type="isGlobalDrama(item.dramaId) ? 'success' : 'warning'">
                  {{ item.dramaTitle || getDramaLabel(item.dramaId) }}
                </el-tag>
              </div>

              <div v-if="item.shotName" class="absolute bottom-2 left-2 pointer-events-none">
                <el-tag size="small" effect="plain" type="info" class="!bg-black/60 !text-white !border-none text-[11px]">
                  镜头: {{ item.shotName }}
                </el-tag>
              </div>

              <!-- 状态开关浮动右上角 -->
              <div class="absolute top-2 right-2" @click.stop>
                <el-switch
                  :model-value="item.status === 1"
                  inline-prompt
                  active-text="启"
                  inactive-text="停"
                  style="--el-switch-on-color: var(--brand); --el-switch-off-color: var(--border-strong);"
                  @change="handleStatusToggle(item)"
                />
              </div>
            </div>

            <!-- 卡片信息区 -->
            <div class="p-3.5 flex flex-col flex-1 justify-between gap-2">
              <div>
                <div class="flex items-center justify-between gap-1 mb-1">
                  <h3 class="text-sm font-bold text-[var(--text-main)] truncate" :title="item.name">
                    {{ item.name }}
                  </h3>
                  <span v-if="item.aspectRatio" class="text-[10px] text-[var(--text-muted)] bg-slate-100 px-1 py-0.5 rounded font-mono">
                    {{ item.aspectRatio }}
                  </span>
                </div>

                <p class="text-xs text-[var(--text-muted)] line-clamp-2 min-h-[32px] leading-relaxed">
                  {{ item.description || '暂无画面动作描述' }}
                </p>

                <!-- Prompt 预览 -->
                <div v-if="item.prompt" class="mt-2 bg-[var(--surface-muted)] p-1.5 rounded text-[11px] text-[var(--text-sub)] line-clamp-2 font-mono">
                  {{ item.prompt }}
                </div>
              </div>

              <!-- 卡片底部操作按钮 -->
              <div class="flex items-center justify-between pt-2 border-t border-[var(--border-default)] text-xs text-[var(--text-muted)]">
                <span>{{ formatDate(item.updateTime) }}</span>
                <div class="flex items-center gap-1" @click.stop>
                  <el-button type="primary" link size="small" @click="handleEdit(item)">
                    <el-icon class="mr-0.5"><Edit /></el-icon>编辑
                  </el-button>
                  <el-button type="danger" link size="small" @click="handleDelete(item)">
                    <el-icon class="mr-0.5"><Delete /></el-icon>删除
                  </el-button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div v-else class="studio-card p-12 text-center text-[var(--text-muted)] flex flex-col items-center justify-center min-h-[300px]">
          <el-icon class="text-4xl opacity-30 mb-2"><PictureFilled /></el-icon>
          <p class="text-sm">暂无关键帧资产，可点击上方「新建关键帧」开始建档</p>
        </div>
      </div>

      <!-- 2. 表格列表视图 -->
      <div v-else class="studio-card p-4">
        <el-table :data="keyframeList" v-loading="loading" stripe style="width: 100%">
          <el-table-column label="关键帧图像" width="100" align="center">
            <template #default="{ row }">
              <el-image
                v-if="row.frameUrl"
                :src="row.frameUrl"
                :preview-src-list="[row.frameUrl]"
                preview-teleported
                fit="cover"
                class="w-14 h-10 rounded border border-[var(--border-default)] cursor-pointer"
              />
              <span v-else class="text-xs text-[var(--text-muted)]">无图像</span>
            </template>
          </el-table-column>

          <el-table-column prop="name" label="关键帧名称" min-width="140" show-overflow-tooltip />

          <el-table-column prop="frameType" label="类型" width="110" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="getFrameTypeTag(row.frameType)">
                {{ getFrameTypeLabel(row.frameType) }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="归属短剧" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">
              <el-tag size="small" :type="isGlobalDrama(row.dramaId) ? 'success' : 'warning'">
                {{ row.dramaTitle || getDramaLabel(row.dramaId) }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="关联分镜" min-width="110" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.shotName" class="font-mono text-xs bg-slate-100 px-1.5 py-0.5 rounded text-slate-700">
                {{ row.shotName }}
              </span>
              <span v-else class="text-xs text-[var(--text-muted)]">通用未指定</span>
            </template>
          </el-table-column>

          <el-table-column prop="description" label="画面描述" min-width="180" show-overflow-tooltip />

          <el-table-column prop="status" label="状态" width="80" align="center">
            <template #default="{ row }">
              <el-switch
                :model-value="row.status === 1"
                inline-prompt
                active-text="启"
                inactive-text="停"
                @change="handleStatusToggle(row)"
              />
            </template>
          </el-table-column>

          <el-table-column prop="updateTime" label="更新时间" width="160">
            <template #default="{ row }">
              {{ formatDate(row.updateTime) }}
            </template>
          </el-table-column>

          <el-table-column label="操作" width="140" fixed="right" align="center">
            <template #default="{ row }">
              <div class="flex items-center justify-center gap-1">
                <el-button type="primary" link size="small" @click="handleEdit(row)">编辑</el-button>
                <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 分页栏 -->
      <div class="flex justify-end pt-2">
        <el-pagination
          v-model:current-page="queryParams.current"
          v-model:page-size="queryParams.size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="fetchList"
          @current-change="fetchList"
        />
      </div>
    </div>

    <!-- 新建/编辑抽屉 -->
    <KeyframeDrawer
      ref="drawerRef"
      @success="handleDrawerSuccess"
      @cancel="handleDrawerCancel"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Plus, Search, Refresh, Edit, Delete, PictureFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { keyframeApi } from '@/api/res-keyframe'
import { dramaApi, shotApi } from '@/api/drama'
import KeyframeDrawer from './KeyframeDrawer.vue'
import type { ResKeyframe, ResKeyframeQuery } from '@/types/resource'

const emit = defineEmits<{
  (e: 'assetCreated', payload: ResKeyframe): void
  (e: 'assetCancelled'): void
}>()

const loading = ref(false)
const isEditing = ref(false)
const viewMode = ref<'grid' | 'table'>('grid')
const keyframeList = ref<ResKeyframe[]>([])
const total = ref(0)
const drawerRef = ref<InstanceType<typeof KeyframeDrawer>>()

const dramaOptions = ref<Array<{ id: string | number; title: string }>>([])
const filterShotOptions = ref<any[]>([])

const queryParams = reactive<ResKeyframeQuery>({
  current: 1,
  size: 12,
  dramaId: undefined,
  shotId: undefined,
  name: '',
  frameType: '',
  status: undefined
})

function isGlobalDrama(dramaId?: string | number): boolean {
  return !dramaId || String(dramaId) === '0'
}

function getDramaLabel(dramaId?: string | number): string {
  if (isGlobalDrama(dramaId)) return '🌐 全局公共库'
  const drama = dramaOptions.value.find(d => String(d.id) === String(dramaId))
  return drama ? `🎬 ${drama.title}` : `短剧 #${dramaId}`
}

function getFrameTypeLabel(type?: string): string {
  switch (type) {
    case 'FIRST_FRAME': return '首帧'
    case 'END_FRAME': return '尾帧'
    case 'KEYFRAME': return '普通关键帧'
    case 'ACTION_BEAT': return '动作节奏帧'
    default: return type || '普通关键帧'
  }
}

function getFrameTypeTag(type?: string): '' | 'success' | 'warning' | 'info' | 'danger' {
  switch (type) {
    case 'FIRST_FRAME': return 'success'
    case 'END_FRAME': return 'danger'
    case 'KEYFRAME': return 'warning'
    case 'ACTION_BEAT': return 'info'
    default: return ''
  }
}

function formatDate(dateStr?: string): string {
  if (!dateStr) return '-'
  return dateStr.replace('T', ' ').substring(0, 19)
}

async function loadDramaOptions() {
  try {
    const res = await dramaApi.getOptions()
    dramaOptions.value = res || []
  } catch (error) {
    console.error('加载短剧列表失败', error)
  }
}

async function handleFilterDramaChange(val?: number) {
  queryParams.shotId = undefined
  if (!val || val === 0) {
    filterShotOptions.value = []
    return
  }
  try {
    const res = await shotApi.getOptionsByDrama(val)
    filterShotOptions.value = res || []
  } catch (error) {
    filterShotOptions.value = []
  }
}

async function fetchList() {
  try {
    loading.value = true
    const res = await keyframeApi.getPage(queryParams)
    keyframeList.value = res.records || []
    total.value = res.total || 0
  } catch (error) {
    ElMessage.error('获取关键帧列表失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  queryParams.current = 1
  fetchList()
}

function handleReset() {
  queryParams.current = 1
  queryParams.name = ''
  queryParams.frameType = ''
  queryParams.dramaId = undefined
  queryParams.shotId = undefined
  queryParams.status = undefined
  filterShotOptions.value = []
  fetchList()
}

function handleCreate() {
  isEditing.value = true
  drawerRef.value?.open()
}

function handleEdit(row: ResKeyframe) {
  isEditing.value = true
  drawerRef.value?.open(row)
}

function handleDrawerSuccess(payload: ResKeyframe) {
  isEditing.value = false
  fetchList()
  emit('assetCreated', payload)
}

function handleDrawerCancel() {
  isEditing.value = false
  emit('assetCancelled')
}

async function handleStatusToggle(row: ResKeyframe) {
  const newStatus = row.status === 1 ? 0 : 1
  try {
    await keyframeApi.changeStatus(row.id!, newStatus)
    row.status = newStatus
    ElMessage.success(`关键帧已${newStatus === 1 ? '启用' : '停用'}`)
  } catch (error: any) {
    ElMessage.error(error.message || '更新状态失败')
  }
}

async function handleDelete(row: ResKeyframe) {
  try {
    await ElMessageBox.confirm(
      `确定要删除关键帧「${row.name}」吗？删除后将无法恢复。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '确定删除',
        cancelButtonText: '取消'
      }
    )
    await keyframeApi.delete(row.id!)
    ElMessage.success('关键帧删除成功')
    fetchList()
  } catch (error) {
    // 用户取消删除
  }
}

onMounted(async () => {
  await loadDramaOptions()
  await fetchList()
})

defineExpose({
  openCreate: handleCreate,
  fetchList
})
</script>
