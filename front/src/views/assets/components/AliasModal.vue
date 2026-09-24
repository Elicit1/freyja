<template>
  <el-dialog
    v-model="visible"
    :title="`【${character?.canonicalName || character?.name || '角色'}】别名与历史称谓管理`"
    width="760px"
    destroy-on-close
  >
    <div class="space-y-4">
      <!-- 头部操作与说明区 -->
      <div class="flex items-center justify-between bg-slate-50 p-3 rounded-lg border border-slate-200">
        <div class="text-xs text-slate-600">
          💡 别名库用于大模型剧本拆解与跨集角色实体消歧（Entity Resolution）。系统将自动利用别名、代词与外貌描述进行跨场景精准角色对齐。
        </div>
        <el-button type="primary" size="small" :icon="Plus" @click="handleOpenForm()">
          新增别名
        </el-button>
      </div>

      <!-- 别名列表展示 -->
      <div v-loading="loading" class="min-h-[220px]">
        <el-table
          v-if="aliasList.length > 0"
          :data="aliasList"
          stripe
          size="small"
          class="w-full border rounded-lg overflow-hidden"
        >
          <el-table-column label="别名 / 提及称谓" min-width="160">
            <template #default="{ row }">
              <div class="flex items-center gap-1.5 font-medium text-slate-800">
                <span class="text-blue-600">🏷️</span>
                <span>{{ row.alias }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="称谓类型" width="130" align="center">
            <template #default="{ row }">
              <el-tag :type="getAliasTagType(row.aliasType)" size="small" effect="light">
                {{ getAliasTypeLabel(row.aliasType) }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="置信度" width="100" align="center">
            <template #default="{ row }">
              <span class="font-mono text-xs font-semibold text-slate-600">
                {{ row.confidence != null ? `${(Number(row.confidence) * 100).toFixed(0)}%` : '100%' }}
              </span>
            </template>
          </el-table-column>

          <el-table-column label="状态" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
                {{ row.status === 1 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="备注说明" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="text-xs text-slate-500">{{ row.remark || '-' }}</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="120" fixed="right" align="center">
            <template #default="{ row }">
              <div class="flex items-center justify-center gap-1">
                <el-button type="primary" link size="small" @click="handleOpenForm(row)">编辑</el-button>
                <el-popconfirm
                  title="确定删除该别名吗？"
                  @confirm="handleDelete(row)"
                >
                  <template #reference>
                    <el-button type="danger" link size="small">删除</el-button>
                  </template>
                </el-popconfirm>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <el-empty
          v-else-if="!loading"
          description="暂无别名记录，点击上方按钮新增"
        />
      </div>
    </div>

    <!-- 内嵌的新增/编辑别名弹窗 -->
    <el-dialog
      v-model="formDialogVisible"
      :title="editingAlias.id ? '编辑角色别名' : '新增角色别名'"
      width="480px"
      append-to-body
      destroy-on-close
    >
      <el-form
        ref="aliasFormRef"
        :model="editingAlias"
        :rules="aliasRules"
        label-width="100px"
        label-position="right"
      >
        <el-form-item label="别名/称谓" prop="alias">
          <el-input
            v-model="editingAlias.alias"
            placeholder="如: 婉清 / 那个年轻女人 / 林总 / 她"
            maxlength="100"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="称谓类型" prop="aliasType">
          <el-select v-model="editingAlias.aliasType" placeholder="选择别名类型" class="w-full">
            <el-option label="正式名 / 化名 (NAME)" value="NAME" />
            <el-option label="外貌描述 (DESCRIPTION)" value="DESCRIPTION" />
            <el-option label="头衔 / 尊称 (TITLE)" value="TITLE" />
            <el-option label="昵称 / 小名 (NICKNAME)" value="NICKNAME" />
            <el-option label="职业身份 (ROLE)" value="ROLE" />
            <el-option label="代词 (PRONOUN)" value="PRONOUN" />
            <el-option label="其他 (OTHER)" value="OTHER" />
          </el-select>
        </el-form-item>

        <el-form-item label="匹配置信度" prop="confidence">
          <div class="w-full flex items-center gap-3">
            <el-slider
              v-model="sliderConfidence"
              :min="10"
              :max="100"
              :step="5"
              class="flex-1"
            />
            <span class="font-mono text-xs w-10 text-right">{{ sliderConfidence }}%</span>
          </div>
        </el-form-item>

        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="editingAlias.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="备注说明" prop="remark">
          <el-input
            v-model="editingAlias.remark"
            type="textarea"
            :rows="2"
            placeholder="如: 第3集正式确认的身份称呼"
            maxlength="200"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="flex justify-end gap-2">
          <el-button @click="formDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="formSaving" @click="handleSaveAlias">
            保存
          </el-button>
        </div>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { characterApi } from '@/api/res-character'
import type { ResCharacter, ResCharacterAlias } from '@/types/resource'

const emit = defineEmits<{
  (e: 'updated'): void
}>()

const visible = ref(false)
const loading = ref(false)
const character = ref<ResCharacter | null>(null)
const aliasList = ref<ResCharacterAlias[]>([])

// 弹窗表单状态
const formDialogVisible = ref(false)
const formSaving = ref(false)
const aliasFormRef = ref<FormInstance>()
const sliderConfidence = ref(100)

const editingAlias = reactive<Partial<ResCharacterAlias>>({
  id: undefined,
  characterId: 0,
  dramaId: undefined,
  alias: '',
  aliasType: 'NAME',
  confidence: 1.0,
  status: 1,
  remark: ''
})

const aliasRules: FormRules = {
  alias: [{ required: true, message: '请输入别名或提及称谓', trigger: 'blur' }],
  aliasType: [{ required: true, message: '请选择别名类型', trigger: 'change' }]
}

function open(char: ResCharacter, targetAlias?: ResCharacterAlias) {
  character.value = char
  visible.value = true
  fetchAliases().then(() => {
    if (targetAlias) {
      handleOpenForm(targetAlias)
    }
  })
}

async function fetchAliases() {
  if (!character.value?.id) return
  try {
    loading.value = true
    const res = await characterApi.getAliases(character.value.id)
    aliasList.value = res || []
  } catch (error) {
    ElMessage.error('加载角色别名失败')
  } finally {
    loading.value = false
  }
}

function handleOpenForm(row?: ResCharacterAlias) {
  if (row) {
    Object.assign(editingAlias, {
      id: row.id,
      characterId: row.characterId || character.value?.id,
      dramaId: row.dramaId !== undefined ? row.dramaId : character.value?.dramaId,
      alias: row.alias,
      aliasType: row.aliasType || 'NAME',
      confidence: row.confidence ?? 1.0,
      status: row.status ?? 1,
      remark: row.remark || ''
    })
    sliderConfidence.value = Math.round((row.confidence ?? 1.0) * 100)
  } else {
    Object.assign(editingAlias, {
      id: undefined,
      characterId: character.value?.id || 0,
      dramaId: character.value?.dramaId,
      alias: '',
      aliasType: 'NAME',
      confidence: 1.0,
      status: 1,
      remark: ''
    })
    sliderConfidence.value = 100
  }
  formDialogVisible.value = true
}

async function handleSaveAlias() {
  if (!aliasFormRef.value) return
  await aliasFormRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      formSaving.value = true
      const payload: Partial<ResCharacterAlias> = {
        id: editingAlias.id,
        characterId: character.value?.id || editingAlias.characterId,
        dramaId: character.value?.dramaId !== undefined ? character.value?.dramaId : editingAlias.dramaId,
        alias: (editingAlias.alias || '').trim(),
        aliasType: editingAlias.aliasType || 'NAME',
        confidence: Number((sliderConfidence.value / 100).toFixed(2)),
        status: editingAlias.status ?? 1,
        remark: editingAlias.remark || ''
      }

      if (payload.id) {
        await characterApi.updateAlias(payload)
        ElMessage.success('别名更新成功')
      } else {
        await characterApi.addAlias(payload)
        ElMessage.success('新增别名成功')
      }
      formDialogVisible.value = false
      await fetchAliases()
      emit('updated')
    } catch (error: any) {
      ElMessage.error(error.message || '保存别名失败')
    } finally {
      formSaving.value = false
    }
  })
}

async function handleDelete(row: ResCharacterAlias) {
  if (!row.id) return
  try {
    await characterApi.deleteAlias(row.id)
    ElMessage.success('别名删除成功')
    await fetchAliases()
    emit('updated')
  } catch (error: any) {
    ElMessage.error(error.message || '删除别名失败')
  }
}

function getAliasTypeLabel(type?: string): string {
  const map: Record<string, string> = {
    NAME: '正式名/化名',
    DESCRIPTION: '外貌描述',
    TITLE: '头衔/尊称',
    NICKNAME: '昵称/小名',
    ROLE: '职业身份',
    PRONOUN: '代词',
    OTHER: '其他'
  }
  return type ? map[type] || type : '正式名'
}

function getAliasTagType(type?: string): 'success' | 'warning' | 'primary' | 'danger' | 'info' {
  const map: Record<string, 'success' | 'warning' | 'primary' | 'danger' | 'info'> = {
    NAME: 'success',
    DESCRIPTION: 'warning',
    TITLE: 'primary',
    NICKNAME: 'danger',
    ROLE: 'warning',
    PRONOUN: 'info',
    OTHER: 'info'
  }
  return type ? map[type] || 'info' : 'info'
}

defineExpose({ open, fetchAliases })
</script>
