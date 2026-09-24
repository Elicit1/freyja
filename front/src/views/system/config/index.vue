<template>
  <div class="h-[calc(100vh-96px)] flex flex-col studio-card overflow-hidden">
    <!-- 顶部工作台标头 -->
    <WorkspaceHeader
      title="系统参数配置"
      subtitle="维护全局大模型系统提示词模版、FastAPI生图调度超时与业务动态参数，变更实时同步 Redis 缓存"
      :icon="Operation"
    >
      <template #tag>
        <span class="studio-badge bg-[var(--brand-soft)] text-[var(--brand)] font-mono border border-[var(--brand-soft)]">
          核心调度底座
        </span>
      </template>

      <template #actions>
        <el-button
          type="warning"
          plain
          size="small"
          :icon="RefreshRight"
          :loading="refreshingCache"
          @click="handleRefreshCache"
        >
          刷新 Redis 缓存
        </el-button>
        <el-button
          type="primary"
          size="small"
          :icon="Plus"
          @click="handleOpenDialog()"
        >
          新增系统参数
        </el-button>
      </template>
    </WorkspaceHeader>

    <!-- 检索与筛选工具栏 -->
    <div class="p-3.5 border-b border-[var(--border-default)] bg-[var(--surface-muted)] flex flex-wrap items-center justify-between gap-3">
      <div class="flex flex-wrap items-center gap-2.5">
        <el-input
          v-model="queryParams.configName"
          placeholder="搜索配置名称..."
          clearable
          size="small"
          class="!w-44"
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />

        <el-input
          v-model="queryParams.configKey"
          placeholder="搜索参数键名 (Key)..."
          clearable
          size="small"
          class="!w-48 font-mono"
          :prefix-icon="Search"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />

        <el-select
          v-model="queryParams.configType"
          placeholder="类型: 全部"
          clearable
          size="small"
          class="!w-32"
          @change="handleSearch"
        >
          <el-option label="TEXT (提示词)" value="TEXT" />
          <el-option label="STRING (字符串)" value="STRING" />
          <el-option label="JSON (结构体)" value="JSON" />
          <el-option label="NUMBER (数值)" value="NUMBER" />
          <el-option label="BOOLEAN (布尔)" value="BOOLEAN" />
        </el-select>

        <el-select
          v-model="queryParams.isBuiltin"
          placeholder="属性: 全部"
          clearable
          size="small"
          class="!w-28"
          @change="handleSearch"
        >
          <el-option label="系统内置" :value="1" />
          <el-option label="用户自定义" :value="0" />
        </el-select>

        <el-select
          v-model="queryParams.status"
          placeholder="状态: 全部"
          clearable
          size="small"
          class="!w-28"
          @change="handleSearch"
        >
          <el-option label="启用" :value="1" />
          <el-option label="停用" :value="0" />
        </el-select>

        <el-button size="small" :icon="Refresh" @click="handleReset">
          重置
        </el-button>
      </div>

      <!-- 快捷提示 -->
      <div class="text-xs text-[var(--text-secondary)] flex items-center gap-1.5 shrink-0">
        <el-icon class="text-[var(--brand)]"><InfoFilled /></el-icon>
        <span>系统内置参数禁止物理删除，修改后实时对 AI 拆解及渲染流水线生效</span>
      </div>
    </div>

    <!-- 数据列表主区域 -->
    <div class="flex-1 overflow-hidden flex flex-col bg-[var(--surface)]">
      <el-table
        v-loading="loading"
        :data="configList"
        row-key="id"
        size="small"
        class="w-full flex-1"
        header-cell-class-name="!bg-[var(--surface-muted)] !text-[var(--text-secondary)] !font-medium"
      >
        <!-- 配置名称与分类标识 -->
        <el-table-column prop="configName" label="配置名称" min-width="180">
          <template #default="{ row }">
            <div class="flex flex-col gap-0.5 py-1">
              <div class="flex items-center gap-1.5">
                <span class="font-semibold text-[var(--text-primary)] text-xs">{{ row.configName }}</span>
                <span
                  v-if="row.isBuiltin === 1"
                  class="text-[10px] px-1.5 py-0.2 rounded border bg-purple-500/10 text-purple-600 dark:text-purple-400 border-purple-500/20 shrink-0 font-medium"
                >
                  内置
                </span>
              </div>
              <span v-if="row.remark" class="text-[11px] text-[var(--text-muted)] line-clamp-1 truncate max-w-xs">
                {{ row.remark }}
              </span>
            </div>
          </template>
        </el-table-column>

        <!-- 配置键名 Key -->
        <el-table-column prop="configKey" label="参数键名 (Key)" min-width="220">
          <template #default="{ row }">
            <div class="flex items-center gap-1.5 font-mono text-xs text-[var(--brand)]">
              <span class="truncate max-w-[200px]" :title="row.configKey">{{ row.configKey }}</span>
              <el-tooltip content="点击复制键名" placement="top">
                <el-button
                  link
                  type="primary"
                  size="small"
                  :icon="DocumentCopy"
                  class="!p-0.5 opacity-60 hover:opacity-100"
                  @click="copyToClipboard(row.configKey, '参数键名已复制')"
                />
              </el-tooltip>
            </div>
          </template>
        </el-table-column>

        <!-- 类型 Badge -->
        <el-table-column prop="configType" label="类型" width="110" align="center">
          <template #default="{ row }">
            <span
              class="text-[10px] font-mono font-medium px-2 py-0.5 rounded border inline-block"
              :class="getTypeBadgeClasses(row.configType)"
            >
              {{ row.configType }}
            </span>
          </template>
        </el-table-column>

        <!-- 键值内容预览 -->
        <el-table-column prop="configValue" label="配置内容预览" min-width="280">
          <template #default="{ row }">
            <div class="flex items-center justify-between gap-2 py-0.5">
              <div class="font-mono text-xs text-[var(--text-secondary)] truncate max-w-[240px]">
                {{ row.configValue || '(空)' }}
              </div>
              <div class="flex items-center gap-1 shrink-0">
                <el-button
                  v-if="row.configType === 'TEXT' || (row.configValue && row.configValue.length > 30)"
                  link
                  type="primary"
                  size="small"
                  class="!text-xs"
                  @click="handleViewValue(row)"
                >
                  查看完整
                </el-button>
                <el-tooltip content="复制键值内容" placement="top">
                  <el-button
                    link
                    size="small"
                    :icon="DocumentCopy"
                    class="!p-1 text-[var(--text-muted)] hover:text-[var(--text-primary)]"
                    @click="copyToClipboard(row.configValue, '键值已复制')"
                  />
                </el-tooltip>
              </div>
            </div>
          </template>
        </el-table-column>

        <!-- 状态 -->
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">
            <StatusPill
              :status="row.status === 1 ? 'success' : 'disabled'"
              :label="row.status === 1 ? '启用' : '停用'"
              size="small"
            />
          </template>
        </el-table-column>

        <!-- 更新时间 -->
        <el-table-column prop="updateTime" label="更新时间" width="160" align="center">
          <template #default="{ row }">
            <span class="text-xs text-[var(--text-muted)] font-mono">{{ row.updateTime || row.createTime || '-' }}</span>
          </template>
        </el-table-column>

        <!-- 操作区 -->
        <el-table-column label="操作" width="140" fixed="right" align="center">
          <template #default="{ row }">
            <div class="flex items-center justify-center gap-1">
              <el-button
                link
                type="primary"
                size="small"
                :icon="Edit"
                @click="handleOpenDialog(row)"
              >
                编辑
              </el-button>
              <el-tooltip
                :disabled="row.isBuiltin !== 1"
                content="系统内置参数已关联业务逻辑，禁止删除"
                placement="top"
              >
                <span>
                  <el-button
                    link
                    type="danger"
                    size="small"
                    :icon="Delete"
                    :disabled="row.isBuiltin === 1"
                    @click="handleDelete(row)"
                  >
                    删除
                  </el-button>
                </span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <!-- 底部分页 -->
      <div class="p-3 border-t border-[var(--border-default)] flex items-center justify-between bg-[var(--surface)] text-xs text-[var(--text-secondary)]">
        <div>
          共 <span class="font-mono font-semibold text-[var(--text-primary)]">{{ total }}</span> 项配置
        </div>
        <el-pagination
          v-model:current-page="queryParams.pageNum"
          v-model:page-size="queryParams.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          size="small"
          layout="sizes, prev, pager, next"
          @size-change="fetchData"
          @current-change="fetchData"
        />
      </div>
    </div>

    <!-- ========================================================================= -->
    <!-- 对话框：新增 / 编辑参数配置                                                -->
    <!-- ========================================================================= -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑系统参数配置' : '新增系统参数配置'"
      width="760px"
      destroy-on-close
      class="studio-dialog"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="rules"
        label-width="100px"
        size="default"
        class="space-y-3 pt-2"
      >
        <el-form-item label="配置名称" prop="configName">
          <el-input
            v-model="formData.configName"
            placeholder="如: Worker AI 分镜生成系统提示词"
          />
        </el-form-item>

        <el-form-item label="参数键名" prop="configKey">
          <el-input
            v-model="formData.configKey"
            placeholder="如: ai.prompt.shot_worker_system"
            :disabled="isEdit && formData.isBuiltin === 1"
            class="font-mono"
          />
          <div v-if="isEdit && formData.isBuiltin === 1" class="text-[11px] text-amber-600 dark:text-amber-400 mt-1 flex items-center gap-1">
            <el-icon><InfoFilled /></el-icon>
            <span>系统内置核心参数键名不允许修改，以防止核心拆解与生图流水线调用异常。</span>
          </div>
        </el-form-item>

        <div class="grid grid-cols-2 gap-4">
          <el-form-item label="配置类型" prop="configType">
            <el-select v-model="formData.configType" class="w-full">
              <el-option label="TEXT (大文本 / AI 提示词)" value="TEXT" />
              <el-option label="STRING (短文本 / 字符串)" value="STRING" />
              <el-option label="JSON (结构体 / 字典)" value="JSON" />
              <el-option label="NUMBER (数值 / 超时秒数)" value="NUMBER" />
              <el-option label="BOOLEAN (布尔开关)" value="BOOLEAN" />
            </el-select>
          </el-form-item>

          <el-form-item label="状态" prop="status">
            <el-radio-group v-model="formData.status">
              <el-radio :value="1">启用</el-radio>
              <el-radio :value="0">停用</el-radio>
            </el-radio-group>
          </el-form-item>
        </div>

        <el-form-item label="配置键值" prop="configValue">
          <div class="w-full space-y-1.5">
            <el-input
              v-model="formData.configValue"
              type="textarea"
              :rows="formData.configType === 'TEXT' ? 12 : 5"
              placeholder="请输入参数内容，支持长文本、换行、JSON 与模版占位符..."
              class="font-mono text-xs"
            />
            <div class="flex items-center justify-between text-[11px] text-[var(--text-muted)]">
              <div class="flex items-center gap-2">
                <span v-if="formData.configType === 'TEXT'">支持 Markdown 与多行 Prompt 提示词</span>
                <el-button
                  v-if="formData.configType === 'JSON'"
                  link
                  type="primary"
                  size="small"
                  class="!text-xs !p-0"
                  @click="handleFormatJson"
                >
                  格式化 JSON
                </el-button>
              </div>
              <span class="font-mono">字符统计: {{ (formData.configValue || '').length }}</span>
            </div>
          </div>
        </el-form-item>

        <el-form-item label="备注说明" prop="remark">
          <el-input
            v-model="formData.remark"
            type="textarea"
            :rows="2"
            placeholder="说明该参数的使用场景、变量含义或修改注意事项..."
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="flex justify-end gap-2">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitLoading" @click="handleSubmit">
            保存配置
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- ========================================================================= -->
    <!-- 对话框：查看完整内容 / 提示词检视器                                        -->
    <!-- ========================================================================= -->
    <el-dialog
      v-model="viewDialogVisible"
      :title="`配置检视 - ${viewingRow?.configName || ''}`"
      width="820px"
      destroy-on-close
      class="studio-dialog"
    >
      <div v-if="viewingRow" class="space-y-3 pt-1">
        <!-- 基础元数据栏 -->
        <div class="flex flex-wrap items-center justify-between gap-2 p-2.5 rounded-lg bg-[var(--surface-muted)] border border-[var(--border-default)]">
          <div class="flex items-center gap-2">
            <span class="text-xs text-[var(--text-secondary)] font-medium">键名:</span>
            <span class="font-mono text-xs font-semibold text-[var(--brand)]">{{ viewingRow.configKey }}</span>
            <span
              class="text-[10px] font-mono px-2 py-0.2 rounded border inline-block"
              :class="getTypeBadgeClasses(viewingRow.configType)"
            >
              {{ viewingRow.configType }}
            </span>
          </div>

          <div class="flex items-center gap-2">
            <el-button
              type="primary"
              size="small"
              plain
              :icon="DocumentCopy"
              @click="copyToClipboard(viewingRow.configValue, '完整内容已复制到剪贴板')"
            >
              复制全部内容
            </el-button>
          </div>
        </div>

        <!-- 文本检视大盘 -->
        <div class="relative">
          <el-input
            :model-value="viewingRow.configValue"
            type="textarea"
            :rows="16"
            readonly
            class="font-mono text-xs rounded leading-relaxed bg-[var(--surface-muted)]"
          />
        </div>

        <div v-if="viewingRow.remark" class="text-xs text-[var(--text-secondary)] bg-[var(--surface-muted)] p-2.5 rounded border border-[var(--border-default)]">
          <span class="font-semibold text-[var(--text-primary)]">业务备注：</span>
          {{ viewingRow.remark }}
        </div>
      </div>

      <template #footer>
        <div class="flex justify-between items-center">
          <span class="text-xs text-[var(--text-muted)] font-mono">
            总长度: {{ (viewingRow?.configValue || '').length }} 字符
          </span>
          <div class="flex items-center gap-2">
            <el-button @click="viewDialogVisible = false">关闭</el-button>
            <el-button
              type="primary"
              :icon="Edit"
              @click="handleEditFromView"
            >
              直接编辑
            </el-button>
          </div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  Operation,
  Search,
  Refresh,
  Plus,
  Edit,
  Delete,
  RefreshRight,
  InfoFilled,
  DocumentCopy
} from '@element-plus/icons-vue'
import WorkspaceHeader from '@/components/workspace/WorkspaceHeader.vue'
import StatusPill from '@/components/status/StatusPill.vue'
import { sysConfigApi } from '@/api/sys-config'
import type { SysConfig, SysConfigQuery, SysConfigDTO } from '@/types/sys-config'

const loading = ref(false)
const refreshingCache = ref(false)
const configList = ref<SysConfig[]>([])
const total = ref(0)

const queryParams = reactive<SysConfigQuery>({
  pageNum: 1,
  pageSize: 15,
  configName: '',
  configKey: '',
  configType: '',
  isBuiltin: undefined,
  status: undefined
})

const dialogVisible = ref(false)
const isEdit = ref(false)
const submitLoading = ref(false)
const formRef = ref<FormInstance>()

const formData = reactive<SysConfigDTO>({
  id: undefined,
  configName: '',
  configKey: '',
  configValue: '',
  configType: 'STRING',
  isBuiltin: 0,
  status: 1,
  remark: ''
})

const rules: FormRules = {
  configName: [{ required: true, message: '请输入配置名称', trigger: 'blur' }],
  configKey: [{ required: true, message: '请输入配置键名', trigger: 'blur' }],
  configValue: [{ required: true, message: '请输入配置键值', trigger: 'blur' }],
  configType: [{ required: true, message: '请选择配置类型', trigger: 'change' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
}

// 检视对话框
const viewDialogVisible = ref(false)
const viewingRow = ref<SysConfig | null>(null)

const fetchData = async () => {
  loading.value = true
  try {
    const res = await sysConfigApi.getPage(queryParams)
    configList.value = res.records || []
    total.value = res.total || 0
  } catch (err: any) {
    ElMessage.error(err?.message || '获取配置列表失败')
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  queryParams.pageNum = 1
  fetchData()
}

const handleReset = () => {
  queryParams.configName = ''
  queryParams.configKey = ''
  queryParams.configType = ''
  queryParams.isBuiltin = undefined
  queryParams.status = undefined
  queryParams.pageNum = 1
  fetchData()
}

const handleRefreshCache = async () => {
  refreshingCache.value = true
  try {
    await sysConfigApi.refreshCache()
    ElMessage.success('Redis 参数配置缓存已成功刷新并预热')
  } catch (err: any) {
    ElMessage.error(err?.message || '刷新缓存失败')
  } finally {
    refreshingCache.value = false
  }
}

const handleOpenDialog = (row?: SysConfig) => {
  if (row) {
    isEdit.value = true
    formData.id = row.id ? String(row.id) : undefined
    formData.configName = row.configName
    formData.configKey = row.configKey
    formData.configValue = row.configValue
    formData.configType = row.configType
    formData.isBuiltin = row.isBuiltin
    formData.status = row.status
    formData.remark = row.remark || ''
  } else {
    isEdit.value = false
    formData.id = undefined
    formData.configName = ''
    formData.configKey = ''
    formData.configValue = ''
    formData.configType = 'STRING'
    formData.isBuiltin = 0
    formData.status = 1
    formData.remark = ''
  }
  dialogVisible.value = true
}

const handleFormatJson = () => {
  if (!formData.configValue.trim()) return
  try {
    const parsed = JSON.parse(formData.configValue)
    formData.configValue = JSON.stringify(parsed, null, 2)
    ElMessage.success('JSON 格式化成功')
  } catch (err: any) {
    ElMessage.warning('当前输入不是合法的 JSON 格式，无法格式化: ' + (err?.message || ''))
  }
}

const handleSubmit = async () => {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitLoading.value = true
    try {
      if (isEdit.value) {
        await sysConfigApi.update(formData)
        ElMessage.success('配置修改成功')
      } else {
        await sysConfigApi.create(formData)
        ElMessage.success('配置创建成功')
      }
      dialogVisible.value = false
      fetchData()
    } catch (err: any) {
      ElMessage.error(err?.message || '操作失败')
    } finally {
      submitLoading.value = false
    }
  })
}

const handleDelete = (row: SysConfig) => {
  if (row.isBuiltin === 1) {
    ElMessage.warning('系统内置配置禁止删除')
    return
  }
  ElMessageBox.confirm(`确定要删除参数配置「${row.configName} (${row.configKey})」吗？`, '删除确认', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await sysConfigApi.delete(String(row.id))
      ElMessage.success('删除成功')
      fetchData()
    } catch (err: any) {
      ElMessage.error(err?.message || '删除失败')
    }
  }).catch(() => {})
}

const handleViewValue = (row: SysConfig) => {
  viewingRow.value = row
  viewDialogVisible.value = true
}

const handleEditFromView = () => {
  if (!viewingRow.value) return
  const row = viewingRow.value
  viewDialogVisible.value = false
  handleOpenDialog(row)
}

const copyToClipboard = async (text: string, successMsg = '复制成功') => {
  if (!text) {
    ElMessage.warning('内容为空')
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(successMsg)
  } catch {
    ElMessage.info('复制失败，请手动选择复制')
  }
}

const getTypeBadgeClasses = (type: string) => {
  switch (type) {
    case 'TEXT':
      return 'bg-blue-500/10 text-blue-600 dark:text-blue-400 border-blue-500/20'
    case 'JSON':
      return 'bg-amber-500/10 text-amber-600 dark:text-amber-400 border-amber-500/20'
    case 'NUMBER':
      return 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/20'
    case 'BOOLEAN':
      return 'bg-rose-500/10 text-rose-600 dark:text-rose-400 border-rose-500/20'
    default:
      return 'bg-[var(--surface-muted)] text-[var(--text-secondary)] border-[var(--border-default)]'
  }
}

onMounted(() => {
  fetchData()
})
</script>
