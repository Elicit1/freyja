<template>
  <div class="space-y-4">
    <!-- 列表展示区（未编辑时展示） -->
    <div v-show="!isEditing" class="space-y-4">
      <!-- 筛选搜索栏 -->
      <div class="studio-card p-4">
      <el-form :model="queryParams" inline class="flex flex-wrap items-center gap-2 mb-0">
        <el-form-item label="道具名称">
          <el-input
            v-model="queryParams.name"
            placeholder="搜索道具名称"
            clearable
            @keyup.enter="handleSearch"
            class="!w-44"
          />
        </el-form-item>

        <el-form-item label="道具类型">
          <el-select v-model="queryParams.propType" placeholder="全部" clearable class="!w-44">
            <el-option label="核心叙事道具 (Key Prop)" value="KEY_PROP" />
            <el-option label="武器装备 (Weapon)" value="WEAPON" />
            <el-option label="服饰配饰 (Accessory)" value="COSTUME_ACCESSORY" />
            <el-option label="日常杂物 (Daily)" value="DAILY" />
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
        <el-button type="primary" :icon="Plus" @click="handleCreate">新建道具</el-button>
        <span class="text-xs text-[var(--text-muted)]">共 {{ total }} 个道具资产</span>
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
      <div v-if="propList.length > 0" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
        <div
          v-for="prop in propList"
          :key="String(prop.id)"
          class="studio-card overflow-hidden flex flex-col justify-between group transition-all cursor-pointer hover:-translate-y-0.5 hover:shadow-md"
          @click="handleEdit(prop)"
        >
          <!-- 卡片封面图 -->
          <div class="relative h-44 bg-[var(--surface-muted)] overflow-hidden">
            <div class="w-full h-full" @click.stop>
              <el-image
                v-if="prop.coverUrl"
                :src="prop.coverUrl"
                :preview-src-list="[prop.coverUrl]"
                preview-teleported
                fit="cover"
                class="w-full h-full group-hover:scale-105 transition-transform duration-300 cursor-pointer"
              />
              <div v-else class="w-full h-full flex flex-col items-center justify-center text-[var(--text-muted)] gap-1">
                <el-icon class="text-3xl opacity-40"><Box /></el-icon>
                <span class="text-[11px] font-mono opacity-60">暂无道具预览</span>
              </div>
            </div>

            <!-- 顶部半透明浮层 Tag -->
            <div class="absolute top-2 left-2 flex items-center gap-1.5 pointer-events-none">
              <el-tag size="small" effect="dark" :type="getPropTypeTag(prop.propType)">
                {{ getPropTypeLabel(prop.propType) }}
              </el-tag>
              <el-tag size="small" effect="dark" :type="isGlobalDrama(prop.dramaId) ? 'success' : 'warning'">
                {{ getDramaLabel(prop.dramaId) }}
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
                    <el-dropdown-item :icon="Edit" @click="handleEdit(prop)">编辑道具</el-dropdown-item>
                    <el-dropdown-item :icon="Delete" divided class="!text-[var(--danger)]" @click="handleDelete(prop)">
                      删除道具
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>

            <!-- 底部半透明阴影标题 -->
            <div class="absolute bottom-0 inset-x-0 bg-gradient-to-t from-black/80 via-black/40 to-transparent p-3 pt-6 text-white pointer-events-none">
              <div class="font-bold text-sm leading-snug truncate">{{ prop.name }}</div>
              <div class="text-[11px] text-white/80 flex items-center gap-2 mt-0.5">
                <span>{{ getPropTypeLabel(prop.propType) }}</span>
                <span v-if="isGlobalDrama(prop.dramaId)" class="text-emerald-300">· 公共库</span>
                <span v-else class="text-amber-300">· {{ getDramaLabel(prop.dramaId) }}</span>
              </div>
            </div>
          </div>

          <!-- 卡片内容体 -->
          <div class="p-4 space-y-2.5 flex-1">
            <!-- 中文特征描述 -->
            <div class="bg-gray-50 rounded-lg p-2.5 border border-gray-100 relative min-h-[58px]">
              <div class="text-xs text-gray-700 leading-relaxed line-clamp-3">
                {{ prop.description || '暂无中文特征描述（可点击右下角编辑补充）' }}
              </div>
            </div>

            <!-- 备注信息 -->
            <div v-if="prop.remark" class="text-xs text-gray-400 truncate flex items-center gap-1">
              <span>📝</span>
              <span class="truncate">{{ prop.remark }}</span>
            </div>
          </div>

          <!-- 卡片底部操作 -->
          <div class="px-4 py-2.5 bg-gray-50/80 border-t border-gray-100 flex items-center justify-between text-xs">
            <div class="flex items-center gap-2">
              <span class="text-gray-400">ID: {{ prop.id }}</span>
              <el-tag :type="prop.status === 1 ? 'success' : 'danger'" size="small" effect="plain">
                {{ prop.status === 1 ? '启用' : '停用' }}
              </el-tag>
            </div>
            <span class="text-[11px] text-slate-400 group-hover:text-indigo-600 transition-colors">
              点击卡片编辑
            </span>
          </div>
        </div>
      </div>

      <el-empty v-else-if="!loading" description="暂无道具资产，点击上方新建道具" />
    </div>

    <!-- 2. 表格列表视图 -->
    <el-card v-else shadow="never" class="!border-gray-200">
      <el-table v-loading="loading" :data="propList" stripe style="width: 100%">
        <el-table-column label="道具名称" min-width="180">
          <template #default="{ row }">
            <div class="flex items-center gap-2.5">
              <div v-if="row.coverUrl" class="relative group cursor-pointer shrink-0">
                <el-image
                  :src="row.coverUrl"
                  :preview-src-list="[row.coverUrl]"
                  preview-teleported
                  fit="cover"
                  class="w-12 h-12 rounded object-cover block border border-gray-200"
                />
                <div class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity rounded flex items-center justify-center text-white text-[9px] pointer-events-none">
                  🔍
                </div>
              </div>
              <div
                v-else
                class="w-12 h-12 rounded bg-gray-100 flex items-center justify-center text-base text-gray-400 shrink-0"
              >
                {{ getPropIcon(row.propType) }}
              </div>
              <div class="truncate">
                <div class="font-bold text-gray-900 truncate">{{ row.name }}</div>
                <div class="text-xs text-gray-400 truncate max-w-[200px]" :title="row.description">
                  {{ row.description || '-' }}
                </div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="道具分类" width="130">
          <template #default="{ row }">
            <el-tag :type="getPropTypeTag(row.propType)" size="small">
              {{ getPropTypeLabel(row.propType) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="所属库" width="130">
          <template #default="{ row }">
            <el-tag :type="isGlobalDrama(row.dramaId) ? 'success' : 'info'" size="small" effect="plain">
              {{ getDramaLabel(row.dramaId) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="中文特征描述" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="text-xs text-gray-700">{{ row.description || '暂无描述' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="排序" width="70" align="center" prop="sortOrder" />

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
            <el-popconfirm title="确定删除该道具资产吗？" @confirm="handleDelete(row)">
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

    <!-- 道具主内容区编辑组件 -->
    <PropDrawer ref="propDrawerRef" @success="handleDrawerSuccess" @cancel="emit('asset-cancelled')" @visible-change="isEditing = $event" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, MoreFilled, Edit, Delete } from '@element-plus/icons-vue'
import { propApi } from '@/api/res-prop'
import { dramaApi } from '@/api/drama'
import type { ResProp, ResPropQuery } from '@/types/resource'
import type { DramaOption } from '@/types/drama'
import PropDrawer from './PropDrawer.vue'

const emit = defineEmits<{
  (e: 'asset-created', assetId: string | number): void
  (e: 'asset-cancelled'): void
}>()

const isEditing = ref(false)
const loading = ref(false)
const viewMode = ref<'grid' | 'table'>('grid')
const total = ref(0)
const propList = ref<ResProp[]>([])
const dramaOptions = ref<DramaOption[]>([])
const propDrawerRef = ref<InstanceType<typeof PropDrawer>>()

const queryParams = reactive<ResPropQuery>({
  current: 1,
  size: 12,
  dramaId: undefined,
  name: '',
  propType: undefined,
  status: undefined
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
    const res = await propApi.getPage(queryParams)
    propList.value = res.records || []
    total.value = res.total || 0
  } catch (error) {
    console.error('获取道具列表失败', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  queryParams.current = 1
  fetchData()
}

function handleReset() {
  queryParams.name = ''
  queryParams.propType = undefined
  queryParams.dramaId = undefined
  queryParams.status = undefined
  queryParams.current = 1
  fetchData()
}

function handleCreate(dramaId?: string | number) {
  propDrawerRef.value?.open(undefined, dramaId)
}

function handleDrawerSuccess(assetId?: string | number) {
  fetchData()
  if (assetId !== undefined && assetId !== null && assetId !== '') {
    emit('asset-created', assetId)
  }
}

function handleEdit(prop: ResProp) {
  propDrawerRef.value?.open(prop)
}

async function handleDelete(prop: ResProp) {
  if (!prop.id) return
  try {
    await ElMessageBox.confirm(`确定要删除道具资产「${prop.name}」吗？此操作无法撤销。`, '删除确认', {
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await propApi.delete(prop.id)
    ElMessage.success('道具删除成功')
    fetchData()
  } catch (err) {
    // cancelled or error
  }
}


function getPropTypeLabel(type?: string): string {
  switch (type) {
    case 'KEY_PROP':
      return '核心叙事道具'
    case 'WEAPON':
      return '武器装备'
    case 'COSTUME_ACCESSORY':
      return '服饰配饰'
    case 'DAILY':
      return '日常杂物'
    default:
      return type || '道具'
  }
}

function getPropTypeTag(type?: string): 'primary' | 'success' | 'warning' | 'info' | 'danger' {
  switch (type) {
    case 'KEY_PROP':
      return 'warning'
    case 'WEAPON':
      return 'danger'
    case 'COSTUME_ACCESSORY':
      return 'success'
    case 'DAILY':
      return 'info'
    default:
      return 'primary'
  }
}

function getPropIcon(type?: string): string {
  switch (type) {
    case 'WEAPON':
      return '⚔️'
    case 'COSTUME_ACCESSORY':
      return '💍'
    case 'KEY_PROP':
      return '🗝️'
    case 'DAILY':
    default:
      return '📦'
  }
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

onMounted(() => {
  loadDramaOptions()
  fetchData()
})

defineExpose({ fetchData, handleCreate })
</script>
