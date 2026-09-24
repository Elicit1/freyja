<template>
  <div class="h-[calc(100vh-96px)] flex flex-col studio-card overflow-hidden">
    <!-- 顶部工作台标头 -->
    <WorkspaceHeader
      title="数据字典管理"
      subtitle="维护全局基础枚举字典类型与数据项，变更实时同步 Redis 缓存"
      :icon="Tickets"
    >
      <template #tag>
        <span class="studio-badge bg-[var(--brand-soft)] text-[var(--brand)] font-mono border border-[var(--brand-soft)]">
          基础配置中心
        </span>
      </template>

      <template #actions>
        <el-button
          v-if="currentType"
          type="warning"
          plain
          size="small"
          :icon="RefreshRight"
          @click="handleClearCache(currentType.dictType)"
        >
          刷新当前缓存
        </el-button>
        <el-button
          v-if="currentType"
          type="primary"
          size="small"
          :icon="Plus"
          @click="handleOpenDataDialog()"
        >
          新增字典项
        </el-button>
      </template>
    </WorkspaceHeader>

    <!-- 主控台左右分栏 -->
    <div class="flex-1 flex overflow-hidden border-t border-[var(--border-default)]">
      <!-- ========================================================================= -->
      <!-- 左侧栏：字典类型管理 (280px ~ 320px)                                      -->
      <!-- ========================================================================= -->
      <div class="w-80 shrink-0 border-r border-[var(--border-default)] flex flex-col bg-[var(--surface-muted)] select-none">
        <!-- 搜索与主操作 -->
        <div class="p-3.5 border-b border-[var(--border-default)] bg-[var(--surface)] space-y-2.5">
          <div class="flex items-center justify-between">
            <span class="text-xs font-bold text-[var(--text-primary)] tracking-wide">字典类型</span>
            <el-button
              type="primary"
              size="small"
              link
              :icon="Plus"
              class="!text-xs"
              @click="handleOpenTypeDialog()"
            >
              新增类型
            </el-button>
          </div>

          <el-input
            v-model="typeSearchKeyword"
            placeholder="搜索名称或编码..."
            size="small"
            clearable
            :prefix-icon="Search"
            @input="handleFilterTypeKeyword"
          />
        </div>

        <!-- 字典类型列表流 -->
        <el-scrollbar v-loading="typeLoading" class="flex-1 p-2.5">
          <div v-if="filteredTypeList.length === 0" class="py-12 text-center text-xs text-[var(--text-muted)]">
            <WorkspaceEmptyState
              description="无匹配字典类型"
              action-text="创建字典类型"
              @action="handleOpenTypeDialog()"
            />
          </div>

          <div v-else class="space-y-1.5">
            <div
              v-for="item in filteredTypeList"
              :key="String(item.id)"
              class="p-3 rounded-xl cursor-pointer transition-all border relative group"
              :class="currentType?.id === item.id
                ? 'bg-[var(--surface)] border-[var(--brand)] shadow-xs border-l-4 border-l-[var(--brand)]'
                : 'bg-transparent border-transparent hover:bg-[var(--surface-hover)] hover:border-[var(--border-default)]'"
              @click="handleSelectType(item)"
            >
              <div class="flex items-start justify-between gap-2">
                <div class="flex-1 min-w-0">
                  <div class="flex items-center gap-1.5 mb-1">
                    <span
                      class="text-xs font-bold truncate"
                      :class="currentType?.id === item.id ? 'text-[var(--brand)]' : 'text-[var(--text-primary)]'"
                      :title="item.dictName"
                    >
                      {{ item.dictName }}
                    </span>
                  </div>
                  <div class="text-[11px] font-mono text-[var(--text-muted)] truncate" :title="item.dictType">
                    {{ item.dictType }}
                  </div>
                </div>

                <!-- 状态与更多操作 -->
                <div class="flex items-center gap-1 shrink-0">
                  <StatusPill
                    :status="item.status === 1 ? 'success' : 'disabled'"
                    :label="item.status === 1 ? '启用' : '停用'"
                    size="small"
                  />

                  <!-- 悬停快捷操作 -->
                  <el-dropdown trigger="click" @command="(cmd: string) => handleTypeCommand(cmd, item)">
                    <el-button
                      link
                      size="small"
                      class="!p-1 opacity-0 group-hover:opacity-100 transition-opacity !text-[var(--text-muted)] hover:!text-[var(--text-primary)]"
                      @click.stop
                    >
                      <el-icon><MoreFilled /></el-icon>
                    </el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item command="edit">
                          <el-icon><Edit /></el-icon>编辑类型
                        </el-dropdown-item>
                        <el-dropdown-item command="refresh">
                          <el-icon><RefreshRight /></el-icon>刷新缓存
                        </el-dropdown-item>
                        <el-dropdown-item command="delete" divided class="!text-rose-600">
                          <el-icon><Delete /></el-icon>删除类型
                        </el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </div>
            </div>
          </div>
        </el-scrollbar>

        <!-- 左侧底部统计 -->
        <div class="p-2.5 border-t border-[var(--border-default)] bg-[var(--surface)] flex items-center justify-between text-[11px] text-[var(--text-muted)]">
          <span>共 {{ typeList.length }} 个类型</span>
          <el-button link size="small" class="!text-[11px] !p-0" @click="fetchTypeList">
            <el-icon class="mr-0.5"><Refresh /></el-icon>刷新
          </el-button>
        </div>
      </div>

      <!-- ========================================================================= -->
      <!-- 右侧栏：当前字典详情与字典项数据表格 (flex-1)                             -->
      <!-- ========================================================================= -->
      <div class="flex-1 flex flex-col overflow-hidden bg-[var(--surface)]">
        <!-- 未选中任何字典类型时的空状态 -->
        <div v-if="!currentType" class="flex-1 flex items-center justify-center p-12">
          <WorkspaceEmptyState
            description="请在左侧选择一个字典类型查看其枚举数据项"
            action-text="新增字典类型"
            @action="handleOpenTypeDialog()"
          />
        </div>

        <!-- 选中字典后的主工作区 -->
        <template v-else>
          <!-- 顶部字典概览卡片 -->
          <div class="p-4 border-b border-[var(--border-default)] bg-[var(--surface)] flex flex-wrap items-center justify-between gap-3">
            <div class="flex items-center gap-3">
              <div class="w-10 h-10 rounded-xl bg-[var(--brand-soft)] text-[var(--brand)] flex items-center justify-center font-bold text-sm shrink-0">
                <el-icon :size="20"><CollectionTag /></el-icon>
              </div>
              <div>
                <div class="flex items-center gap-2">
                  <h3 class="font-bold text-sm text-[var(--text-primary)]">{{ currentType.dictName }}</h3>
                  <span class="font-mono text-xs text-[var(--text-muted)] bg-[var(--surface-muted)] px-2 py-0.5 rounded border border-[var(--border-default)]">
                    {{ currentType.dictType }}
                  </span>
                  <StatusPill
                    :status="currentType.status === 1 ? 'success' : 'disabled'"
                    :label="currentType.status === 1 ? '已启用' : '已停用'"
                    size="small"
                  />
                </div>
                <p class="text-xs text-[var(--text-secondary)] mt-0.5 max-w-xl truncate">
                  {{ currentType.remark || '暂无说明备注' }}
                </p>
              </div>
            </div>

            <!-- 数据项过滤工具 -->
            <div class="flex items-center gap-2">
              <el-input
                v-model="dataQuery.dictLabel"
                placeholder="搜索字典标签/值..."
                size="small"
                clearable
                class="!w-48"
                @keyup.enter="handleSearchData"
                @clear="handleSearchData"
              >
                <template #prefix>
                  <el-icon class="text-[var(--text-muted)]"><Search /></el-icon>
                </template>
              </el-input>

              <el-select
                v-model="dataQuery.status"
                placeholder="状态"
                size="small"
                clearable
                class="!w-24"
                @change="handleSearchData"
              >
                <el-option label="启用" :value="1" />
                <el-option label="停用" :value="0" />
              </el-select>

              <el-button size="small" @click="handleResetData">
                重置
              </el-button>
            </div>
          </div>

          <!-- 数据项表格区 -->
          <div class="flex-1 overflow-hidden p-4 bg-[var(--app-bg)] flex flex-col">
            <div class="flex-1 studio-card overflow-hidden flex flex-col p-2">
              <el-table
                v-loading="dataLoading"
                :data="dataList"
                height="100%"
                size="small"
                class="w-full"
              >
                <el-table-column label="字典标签 (Label)" min-width="160">
                  <template #default="{ row }">
                    <div class="flex items-center gap-2">
                      <DictTag :dict-type="currentType.dictType" :value="row.dictValue" size="small" />
                      <span class="text-xs font-semibold text-[var(--text-primary)]">{{ row.dictLabel }}</span>
                    </div>
                  </template>
                </el-table-column>

                <el-table-column label="字典键值 (Value)" min-width="150">
                  <template #default="{ row }">
                    <span class="font-mono text-xs text-[var(--text-secondary)] bg-[var(--surface-muted)] px-2 py-0.5 rounded border border-[var(--border-default)]">
                      {{ row.dictValue }}
                    </span>
                  </template>
                </el-table-column>

                <el-table-column label="排序值" width="90" align="center">
                  <template #default="{ row }">
                    <span class="font-mono text-xs text-[var(--text-muted)]">{{ row.sortOrder ?? 0 }}</span>
                  </template>
                </el-table-column>

                <el-table-column label="状态" width="100" align="center">
                  <template #default="{ row }">
                    <StatusPill
                      :status="row.status === 1 ? 'success' : 'disabled'"
                      :label="row.status === 1 ? '启用' : '停用'"
                      size="small"
                    />
                  </template>
                </el-table-column>

                <el-table-column label="备注说明" min-width="160" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span class="text-xs text-[var(--text-muted)]">{{ row.remark || '-' }}</span>
                  </template>
                </el-table-column>

                <el-table-column label="更新时间" width="160">
                  <template #default="{ row }">
                    <span class="text-xs text-[var(--text-muted)]">{{ formatTime(row.updateTime || row.createTime) }}</span>
                  </template>
                </el-table-column>

                <el-table-column label="操作" width="140" align="center" fixed="right">
                  <template #default="{ row }">
                    <el-button type="primary" link size="small" @click="handleOpenDataDialog(row)">
                      编辑
                    </el-button>
                    <el-popconfirm
                      title="确定删除此字典数据项吗？"
                      confirm-button-text="删除"
                      cancel-button-text="取消"
                      @confirm="handleDeleteData(row.id)"
                    >
                      <template #reference>
                        <el-button type="danger" link size="small">
                          删除
                        </el-button>
                      </template>
                    </el-popconfirm>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <!-- 数据项分页 -->
            <div v-if="dataQuery.pageSize && dataTotal > dataQuery.pageSize" class="mt-3 flex justify-end">
              <el-pagination
                v-model:current-page="dataQuery.pageNum"
                v-model:page-size="dataQuery.pageSize"
                :total="dataTotal"
                :page-sizes="[10, 20, 50]"
                layout="total, prev, pager, next"
                @size-change="fetchDataList"
                @current-change="fetchDataList"
              />
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- ========================================================================= -->
    <!-- 弹窗：新增 / 编辑字典类型                                                  -->
    <!-- ========================================================================= -->
    <el-dialog
      v-model="typeDialogVisible"
      :title="typeForm.id ? '修改字典类型' : '新增字典类型'"
      width="520px"
      append-to-body
      destroy-on-close
      class="studio-dialog"
    >
      <el-form
        ref="typeFormRef"
        :model="typeForm"
        :rules="typeRules"
        label-width="110px"
        size="default"
        class="space-y-3 pt-2"
      >
        <el-form-item label="类型编码" prop="dictType">
          <el-input
            v-model="typeForm.dictType"
            placeholder="例如: sys_gender / video_ratio"
            :disabled="!!typeForm.id"
            class="font-mono"
          />
          <div v-if="typeForm.id" class="text-[11px] text-[var(--text-muted)] mt-1">
            字典类型编码作为系统索引标识，创建后不可修改。
          </div>
        </el-form-item>

        <el-form-item label="类型名称" prop="dictName">
          <el-input v-model="typeForm.dictName" placeholder="例如: 用户性别 / 视频比例" />
        </el-form-item>

        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="typeForm.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="说明备注" prop="remark">
          <el-input
            v-model="typeForm.remark"
            type="textarea"
            :rows="3"
            placeholder="填写该字典类型的业务用途与说明..."
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="flex justify-end gap-2">
          <el-button @click="typeDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="typeSubmitting" @click="handleSubmitType">
            确定保存
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- ========================================================================= -->
    <!-- 弹窗：新增 / 编辑字典数据项                                                -->
    <!-- ========================================================================= -->
    <el-dialog
      v-model="dataDialogVisible"
      :title="dataForm.id ? '修改字典数据项' : '新增字典数据项'"
      width="520px"
      append-to-body
      destroy-on-close
      class="studio-dialog"
    >
      <el-form
        ref="dataFormRef"
        :model="dataForm"
        :rules="dataRules"
        label-width="110px"
        size="default"
        class="space-y-3 pt-2"
      >
        <el-form-item label="所属字典类型">
          <el-input :model-value="`${currentType?.dictName} (${currentType?.dictType})`" disabled class="font-mono" />
        </el-form-item>

        <el-form-item label="字典标签" prop="dictLabel">
          <el-input v-model="dataForm.dictLabel" placeholder="展示文字，如: 男 / 16:9" />
        </el-form-item>

        <el-form-item label="字典键值" prop="dictValue">
          <el-input v-model="dataForm.dictValue" placeholder="存储键值，如: 1 / 16_9" class="font-mono" />
        </el-form-item>

        <el-form-item label="显示排序" prop="sortOrder">
          <el-input-number v-model="dataForm.sortOrder" :min="0" :max="9999" class="!w-32" />
        </el-form-item>

        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="dataForm.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="说明备注" prop="remark">
          <el-input
            v-model="dataForm.remark"
            type="textarea"
            :rows="3"
            placeholder="数据项补充说明..."
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="flex justify-end gap-2">
          <el-button @click="dataDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="dataSubmitting" @click="handleSubmitData">
            确定保存
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  Tickets,
  Search,
  Refresh,
  Plus,
  Edit,
  Delete,
  MoreFilled,
  RefreshRight,
  CollectionTag
} from '@element-plus/icons-vue'
import WorkspaceHeader from '@/components/workspace/WorkspaceHeader.vue'
import WorkspaceEmptyState from '@/components/workspace/WorkspaceEmptyState.vue'
import StatusPill from '@/components/status/StatusPill.vue'
import DictTag from '@/components/DictTag.vue'
import { dictTypeApi, dictDataApi, dictCommonApi } from '@/api/dict'
import { useDictStore } from '@/store/dict'
import type { SysDictType, SysDictData, DictDataQuery } from '@/types/dict'

const dictStore = useDictStore()

// ======================== 字典类型管理 ========================
const typeLoading = ref(false)
const typeList = ref<SysDictType[]>([])
const typeSearchKeyword = ref('')
const currentType = ref<SysDictType | null>(null)

const filteredTypeList = computed(() => {
  if (!typeSearchKeyword.value.trim()) return typeList.value
  const kw = typeSearchKeyword.value.trim().toLowerCase()
  return typeList.value.filter(
    t => t.dictName.toLowerCase().includes(kw) || t.dictType.toLowerCase().includes(kw)
  )
})

const fetchTypeList = async () => {
  typeLoading.value = true
  try {
    const res = await dictTypeApi.getPage({ pageNum: 1, pageSize: 200 })
    typeList.value = res.records || []
    if (typeList.value.length > 0) {
      if (!currentType.value) {
        handleSelectType(typeList.value[0])
      } else {
        const found = typeList.value.find(t => String(t.id) === String(currentType.value?.id))
        if (found) currentType.value = found
      }
    } else {
      currentType.value = null
      dataList.value = []
    }
  } finally {
    typeLoading.value = false
  }
}

const handleFilterTypeKeyword = () => {
  // 本地过滤响应
}

const handleSelectType = (item: SysDictType) => {
  currentType.value = item
  dataQuery.dictType = item.dictType
  dataQuery.dictLabel = ''
  dataQuery.status = undefined
  dataQuery.pageNum = 1
  fetchDataList()
}

const handleTypeCommand = (cmd: string, item: SysDictType) => {
  if (cmd === 'edit') {
    handleOpenTypeDialog(item)
  } else if (cmd === 'refresh') {
    handleClearCache(item.dictType)
  } else if (cmd === 'delete') {
    ElMessageBox.confirm(
      `确定删除字典类型 [${item.dictName}] 吗？此操作将逻辑删除该类型记录。`,
      '删除确认',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    ).then(() => {
      handleDeleteType(item.id)
    }).catch(() => {})
  }
}

const handleDeleteType = async (id?: number | string) => {
  if (!id) return
  await dictTypeApi.delete(id)
  ElMessage.success('删除字典类型成功')
  if (String(currentType.value?.id) === String(id)) {
    currentType.value = null
  }
  fetchTypeList()
}

const handleClearCache = async (dictType: string) => {
  await dictCommonApi.clearCache(dictType)
  dictStore.removeDict(dictType)
  ElMessage.success(`已刷新 [${dictType}] 字典缓存`)
}

// 字典类型新增/编辑弹窗
const typeDialogVisible = ref(false)
const typeSubmitting = ref(false)
const typeFormRef = ref<FormInstance>()
const typeForm = reactive<Partial<SysDictType>>({
  id: undefined,
  dictType: '',
  dictName: '',
  status: 1,
  remark: ''
})

const typeRules: FormRules = {
  dictType: [
    { required: true, message: '请输入字典类型编码', trigger: 'blur' },
    { min: 2, max: 64, message: '长度在 2 到 64 个字符', trigger: 'blur' }
  ],
  dictName: [
    { required: true, message: '请输入字典类型名称', trigger: 'blur' },
    { min: 2, max: 100, message: '长度在 2 到 100 个字符', trigger: 'blur' }
  ],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
}

const handleOpenTypeDialog = async (row?: SysDictType) => {
  if (row && row.id) {
    try {
      const detail = await dictTypeApi.getDetail(row.id)
      typeForm.id = detail.id
      typeForm.dictType = detail.dictType
      typeForm.dictName = detail.dictName
      typeForm.status = detail.status
      typeForm.remark = detail.remark || ''
    } catch {
      typeForm.id = row.id
      typeForm.dictType = row.dictType
      typeForm.dictName = row.dictName
      typeForm.status = row.status
      typeForm.remark = row.remark || ''
    }
  } else {
    typeForm.id = undefined
    typeForm.dictType = ''
    typeForm.dictName = ''
    typeForm.status = 1
    typeForm.remark = ''
  }
  typeDialogVisible.value = true
}

const handleSubmitType = async () => {
  if (!typeFormRef.value) return
  await typeFormRef.value.validate()

  typeSubmitting.value = true
  try {
    if (typeForm.id) {
      await dictTypeApi.update(typeForm)
      ElMessage.success('修改字典类型成功')
    } else {
      await dictTypeApi.add(typeForm)
      ElMessage.success('新增字典类型成功')
    }
    typeDialogVisible.value = false
    fetchTypeList()
  } finally {
    typeSubmitting.value = false
  }
}

// ======================== 字典数据项管理 ========================
const dataLoading = ref(false)
const dataList = ref<SysDictData[]>([])
const dataTotal = ref(0)
const dataQuery = reactive<DictDataQuery>({
  pageNum: 1,
  pageSize: 20,
  dictType: '',
  dictLabel: '',
  status: undefined
})

const fetchDataList = async () => {
  if (!dataQuery.dictType) return
  dataLoading.value = true
  try {
    const res = await dictDataApi.getPage(dataQuery)
    dataList.value = res.records || []
    dataTotal.value = res.total || 0
  } finally {
    dataLoading.value = false
  }
}

const handleSearchData = () => {
  dataQuery.pageNum = 1
  fetchDataList()
}

const handleResetData = () => {
  dataQuery.dictLabel = ''
  dataQuery.status = undefined
  dataQuery.pageNum = 1
  fetchDataList()
}

const handleDeleteData = async (id?: number | string) => {
  if (!id) return
  await dictDataApi.delete(id)
  ElMessage.success('删除字典数据项成功')
  if (currentType.value) {
    dictStore.removeDict(currentType.value.dictType)
  }
  fetchDataList()
}

// 字典数据项新增/编辑弹窗
const dataDialogVisible = ref(false)
const dataSubmitting = ref(false)
const dataFormRef = ref<FormInstance>()
const dataForm = reactive<Partial<SysDictData>>({
  id: undefined,
  dictType: '',
  dictLabel: '',
  dictValue: '',
  sortOrder: 0,
  status: 1,
  remark: ''
})

const dataRules: FormRules = {
  dictLabel: [
    { required: true, message: '请输入字典标签', trigger: 'blur' },
    { max: 100, message: '标签长度不可超过 100 字符', trigger: 'blur' }
  ],
  dictValue: [
    { required: true, message: '请输入字典键值', trigger: 'blur' },
    { max: 100, message: '键值长度不可超过 100 字符', trigger: 'blur' }
  ],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
}

const handleOpenDataDialog = async (row?: SysDictData) => {
  if (row && row.id) {
    try {
      const detail = await dictDataApi.getDetail(row.id)
      dataForm.id = detail.id
      dataForm.dictType = detail.dictType
      dataForm.dictLabel = detail.dictLabel
      dataForm.dictValue = detail.dictValue
      dataForm.sortOrder = detail.sortOrder ?? 0
      dataForm.status = detail.status
      dataForm.remark = detail.remark || ''
    } catch {
      dataForm.id = row.id
      dataForm.dictType = row.dictType
      dataForm.dictLabel = row.dictLabel
      dataForm.dictValue = row.dictValue
      dataForm.sortOrder = row.sortOrder ?? 0
      dataForm.status = row.status
      dataForm.remark = row.remark || ''
    }
  } else {
    dataForm.id = undefined
    dataForm.dictType = currentType.value?.dictType || ''
    dataForm.dictLabel = ''
    dataForm.dictValue = ''
    dataForm.sortOrder = 0
    dataForm.status = 1
    dataForm.remark = ''
  }
  dataDialogVisible.value = true
}

const handleSubmitData = async () => {
  if (!dataFormRef.value) return
  await dataFormRef.value.validate()

  dataSubmitting.value = true
  try {
    if (dataForm.id) {
      await dictDataApi.update(dataForm)
      ElMessage.success('修改数据项成功')
    } else {
      await dictDataApi.add(dataForm)
      ElMessage.success('新增数据项成功')
    }
    if (currentType.value) {
      dictStore.removeDict(currentType.value.dictType)
    }
    dataDialogVisible.value = false
    fetchDataList()
  } finally {
    dataSubmitting.value = false
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

onMounted(() => {
  fetchTypeList()
})
</script>
