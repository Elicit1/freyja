<template>
  <div class="space-y-4">
    <!-- 列表展示区（未编辑时展示） -->
    <div v-show="!isEditing" class="space-y-4">
      <!-- 筛选搜索栏 -->
      <div class="studio-card p-4">
      <el-form :model="queryParams" inline class="flex flex-wrap items-center gap-2 mb-0">
        <el-form-item label="人物名称">
          <el-input
            v-model="queryParams.name"
            placeholder="搜索姓名/规范名/别名"
            clearable
            @keyup.enter="handleSearch"
            class="!w-44"
          />
        </el-form-item>

        <el-form-item label="身份状态">
          <el-select v-model="queryParams.identityStatus" placeholder="全部状态" clearable class="!w-36">
            <el-option label="已确认正式名 (Confirmed)" value="CONFIRMED" />
            <el-option label="临时称谓未定名 (Partial)" value="PARTIAL" />
            <el-option label="待消歧决议 (Unresolved)" value="UNRESOLVED" />
            <el-option label="已合并 (Merged)" value="MERGED" />
          </el-select>
        </el-form-item>

        <el-form-item label="角色定位">
          <el-select v-model="queryParams.roleType" placeholder="全部" clearable class="!w-36">
            <el-option label="主角 (Protagonist)" value="PROTAGONIST" />
            <el-option label="反派 (Antagonist)" value="ANTAGONIST" />
            <el-option label="配角 (Supporting)" value="SUPPORTING" />
            <el-option label="路人/群演 (Extra)" value="EXTRA" />
          </el-select>
        </el-form-item>

        <el-form-item label="性别">
          <el-select v-model="queryParams.gender" placeholder="全部" clearable class="!w-28">
            <el-option label="男 (Male)" value="MALE" />
            <el-option label="女 (Female)" value="FEMALE" />
            <el-option label="其他" value="OTHER" />
            <el-option label="未知" value="UNKNOWN" />
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
        <el-button type="primary" :icon="Plus" @click="handleCreate">新建人物</el-button>
        <el-button type="warning" plain :icon="Connection" @click="handleOpenMerge">合并重复角色</el-button>
        <span class="text-xs text-[var(--text-muted)]">共 {{ total }} 位人物资产</span>
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
      <div v-if="characterList.length > 0" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
        <div
          v-for="char in characterList"
          :key="String(char.id)"
          class="studio-card overflow-hidden flex flex-col justify-between group transition-all cursor-pointer hover:-translate-y-0.5 hover:shadow-md"
          @click="handleEdit(char)"
        >
          <!-- 卡片头部背景与封面 -->
          <div class="relative bg-[var(--surface-muted)] p-3.5 border-b border-[var(--border-default)] flex items-start justify-between">
            <div class="flex items-center gap-3">
              <div v-if="char.referenceImageUrl || char.avatarUrl" class="relative group cursor-pointer shrink-0" @click.stop>
                <el-image
                  :src="char.referenceImageUrl || char.avatarUrl"
                  :preview-src-list="getCharacterImages(char)"
                  preview-teleported
                  fit="cover"
                  class="w-[52px] h-[52px] rounded-lg shadow-xs border border-[var(--border-default)] block"
                />
                <div class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity rounded-lg flex items-center justify-center text-white text-[10px] pointer-events-none">
                  预览
                </div>
              </div>
              <el-avatar
                v-else
                :size="52"
                shape="square"
                class="rounded-lg shadow-xs border border-[var(--border-default)] shrink-0 !bg-[var(--surface)] text-[var(--text-secondary)]"
              >
                <el-icon><UserFilled /></el-icon>
              </el-avatar>
              <div>
                <div class="font-bold text-[var(--text-primary)] text-sm leading-tight flex items-center gap-1.5 flex-wrap">
                  <span>{{ char.canonicalName || char.name }}</span>
                  <el-tag
                    v-if="char.identityStatus && char.identityStatus !== 'CONFIRMED'"
                    :type="getIdentityStatusTagType(char.identityStatus)"
                    size="small"
                    effect="dark"
                    class="text-[10px]"
                  >
                    {{ getIdentityStatusLabel(char.identityStatus) }}
                  </el-tag>
                  <el-tag :type="getRoleTagType(char.roleType)" size="small" effect="plain">
                    {{ getRoleLabel(char.roleType) }}
                  </el-tag>
                </div>
                <div class="text-xs text-[var(--text-muted)] mt-1 flex items-center gap-2">
                  <span>{{ getGenderLabel(char.gender) }}</span>
                  <span v-if="char.ageGroup">· {{ char.ageGroup }}</span>
                  <span v-if="isGlobalDrama(char.dramaId)" class="text-[var(--brand)] font-medium">· 公共库</span>
                  <span v-else-if="char.dramaId" class="text-[var(--brand)] font-medium">· {{ getDramaLabel(char.dramaId) }}</span>
                </div>
              </div>
            </div>

            <div @click.stop>
              <el-dropdown trigger="click" :persistent="false" placement="bottom-end">
                <el-button
                  circle
                  size="small"
                  :icon="MoreFilled"
                  class="!border-[var(--border-default)] !bg-[var(--surface)] hover:!border-[var(--brand)]"
                  @click.stop
                />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item :icon="Connection" @click="handleQuickMerge(char)">
                      作为源角色合并
                    </el-dropdown-item>
                    <el-dropdown-item :icon="Document" @click="handleOpenEvidences(char)">
                      📜 身份证据链
                    </el-dropdown-item>
                    <el-dropdown-item :icon="Delete" divided class="!text-red-500" @click="handleDelete(char)">
                      删除人物
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>

          <!-- 卡片内容体 -->
          <div class="p-4 space-y-2.5 flex-1">
            <!-- 触发词一键复制 -->
            <div v-if="char.triggerWords" class="flex items-center justify-between bg-gray-50 px-2.5 py-1.5 rounded-lg border border-gray-100">
              <div class="text-xs text-gray-600 truncate font-mono flex items-center gap-1">
                <span class="text-blue-500">🎯</span>
                <span class="truncate">{{ char.triggerWords }}</span>
              </div>
              <el-button type="primary" link size="small" @click.stop="copyText(char.triggerWords)">复制</el-button>
            </div>

            <!-- 外貌特征与视觉设定 -->
            <div v-if="char.appearanceDesc || char.appearancePrompt" class="text-xs text-slate-600 line-clamp-2 bg-slate-50 p-2 rounded border border-slate-100">
              <span v-if="char.appearanceDesc" class="text-slate-700">{{ char.appearanceDesc }}</span>
              <span v-else class="font-mono text-slate-500">{{ char.appearancePrompt }}</span>
            </div>

            <!-- LoRA 与多模态参考图徽标 -->
            <div class="flex items-center gap-1.5 flex-wrap pt-1">
              <el-tag v-if="char.loraName" size="small" type="warning" effect="light" class="truncate max-w-[180px]">
                ⚡ LoRA: {{ char.loraName }}
              </el-tag>
              <div
                v-if="char.referenceImageUrl || char.avatarUrl"
                class="inline-flex items-center gap-1 bg-indigo-50 border border-indigo-200 px-1.5 py-0.5 rounded text-[11px] text-indigo-700"
                title="已设置人物设定参考图"
              >
                <span>🖼️ 设定图已就绪</span>
              </div>
            </div>
          </div>

          <div class="px-4 py-2 bg-gray-50/80 border-t border-gray-100 text-[11px] flex items-center justify-between">
            <span class="text-slate-400">点击卡片编辑人物与造型</span>
            <span v-if="char.outfitCount" class="text-slate-500 font-mono text-[10px]">
              共 {{ char.outfitCount }} 套造型
            </span>
          </div>
        </div>
      </div>

      <el-empty v-else-if="!loading" description="暂无人物资产，点击上方新建人物" />
    </div>

    <!-- 2. 表格列表视图 -->
    <el-card v-else shadow="never" class="!border-gray-200">
      <el-table v-loading="loading" :data="characterList" stripe style="width: 100%">
        <el-table-column label="人物/规范名" min-width="180">
          <template #default="{ row }">
            <div class="flex items-center gap-2.5">
              <div v-if="row.referenceImageUrl || row.avatarUrl" class="relative group cursor-pointer shrink-0">
                <el-image
                  :src="row.referenceImageUrl || row.avatarUrl"
                  :preview-src-list="getCharacterImages(row)"
                  preview-teleported
                  fit="cover"
                  class="w-10 h-10 rounded border border-gray-200 block"
                />
                <div class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity rounded flex items-center justify-center text-white text-[9px] pointer-events-none">
                  🔍
                </div>
              </div>
              <el-avatar v-else :size="40" shape="square">
                <el-icon><UserFilled /></el-icon>
              </el-avatar>
              <div>
                <div class="font-bold text-gray-900 flex items-center gap-1.5">
                  <span>{{ row.canonicalName || row.name }}</span>
                  <el-tag
                    v-if="row.identityStatus && row.identityStatus !== 'CONFIRMED'"
                    :type="getIdentityStatusTagType(row.identityStatus)"
                    size="small"
                  >
                    {{ getIdentityStatusLabel(row.identityStatus) }}
                  </el-tag>
                </div>
                <div v-if="row.displayName && row.displayName !== row.canonicalName" class="text-xs text-gray-400">
                  初始提及: {{ row.displayName }}
                </div>
                <div class="text-xs text-gray-400 font-mono">{{ row.triggerWords || '无触发词' }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="定位" width="110">
          <template #default="{ row }">
            <el-tag :type="getRoleTagType(row.roleType)" size="small">
              {{ getRoleLabel(row.roleType) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="性别" width="80">
          <template #default="{ row }">
            {{ getGenderLabel(row.gender) }}
          </template>
        </el-table-column>

        <el-table-column label="所属库" width="130">
          <template #default="{ row }">
            <el-tag :type="isGlobalDrama(row.dramaId) ? 'success' : 'info'" size="small" effect="plain">
              {{ getDramaLabel(row.dramaId) }}
            </el-tag>
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
            <el-popconfirm title="确定删除该人物及其所有造型吗？" @confirm="handleDelete(row)">
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

    <!-- 角色合并对话框 -->
    <el-dialog v-model="mergeDialogVisible" title="⚡ 跨集角色合并 (Character Merge)" width="520px" destroy-on-close>
      <div class="space-y-3">
        <el-alert
          type="info"
          show-icon
          :closable="false"
          title="合并说明"
          description="将源角色全量迁移并合并到目标主角色中。源角色的所有别名、证据链、造型与历史分镜引用将自动迁移到目标角色，源角色将被标记为 MERGED。"
        />

        <el-form label-position="top" size="small">
          <el-form-item label="待合并源角色 (Source Character)" required>
            <el-select v-model="mergeForm.sourceCharacterId" placeholder="选择要合并的角色" class="w-full" filterable>
              <el-option
                v-for="c in characterList"
                :key="c.id"
                :label="`${c.canonicalName || c.name} (ID: ${c.id}) [${getIdentityStatusLabel(c.identityStatus)}]`"
                :value="c.id"
                :disabled="c.id === mergeForm.targetCharacterId || c.identityStatus === 'MERGED'"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="目标主角色 (Target Master Character)" required>
            <el-select v-model="mergeForm.targetCharacterId" placeholder="选择保留的目标角色" class="w-full" filterable>
              <el-option
                v-for="c in characterList"
                :key="c.id"
                :label="`${c.canonicalName || c.name} (ID: ${c.id}) [${getIdentityStatusLabel(c.identityStatus)}]`"
                :value="c.id"
                :disabled="c.id === mergeForm.sourceCharacterId || c.identityStatus === 'MERGED'"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="合并原因与备注">
            <el-input v-model="mergeForm.reason" type="textarea" :rows="2" placeholder="例如: 剧本第3集已确认'年轻女人'即为'林婉清'" />
          </el-form-item>
        </el-form>
      </div>

      <template #footer>
        <div class="flex justify-end gap-2">
          <el-button @click="mergeDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="mergeLoading" @click="handleConfirmMerge">确认合并</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 身份证据链抽屉 -->
    <el-drawer
      v-model="evidenceDrawerVisible"
      :title="`📜 角色身份依据与消歧证据链 - ${selectedCharacter?.canonicalName || selectedCharacter?.name || ''}`"
      size="520px"
      destroy-on-close
      append-to-body
    >
      <div v-loading="evidenceLoading" class="p-4 space-y-4">
        <div v-if="selectedCharacter" class="bg-blue-50/80 border border-blue-100 p-4 rounded-xl flex items-center gap-4">
          <el-avatar :size="48" :src="selectedCharacter.avatarUrl || selectedCharacter.faceImageUrl" shape="square" class="rounded-lg shadow-xs" />
          <div>
            <div class="font-bold text-gray-900 text-base">{{ selectedCharacter.canonicalName || selectedCharacter.name }}</div>
            <div class="text-xs text-gray-500 mt-0.5">ID: {{ selectedCharacter.id }} · 状态: {{ getIdentityStatusLabel(selectedCharacter.identityStatus) }}</div>
          </div>
        </div>

        <div v-if="evidenceList.length > 0" class="space-y-3 mt-4">
          <div
            v-for="ev in evidenceList"
            :key="ev.id"
            class="bg-slate-50 hover:bg-slate-50/80 border border-slate-200 rounded-xl p-4 text-xs space-y-2 shadow-2xs transition-all"
          >
            <div class="flex items-center justify-between">
              <el-tag size="small" type="primary" effect="plain">{{ ev.evidenceType }}</el-tag>
              <span class="text-slate-500 font-mono font-medium">证据置信度: {{ (ev.confidence * 100).toFixed(0) }}%</span>
            </div>
            <div class="font-medium text-slate-800 bg-white p-3 rounded-lg border border-slate-100 text-xs font-mono leading-relaxed">
              “{{ ev.sourceText }}”
            </div>
            <div v-if="ev.reason" class="text-slate-600 text-xs pt-0.5">
              推导说明: {{ ev.reason }}
            </div>
            <div class="text-[11px] text-slate-400 pt-1 text-right">{{ ev.createTime }}</div>
          </div>
        </div>
        <el-empty v-else-if="!evidenceLoading" description="暂无沉淀的身份依据" />
      </div>
    </el-drawer>

    <!-- 角色主内容区编辑组件 -->
    <CharacterDrawer
      ref="characterDrawerRef"
      @success="handleDrawerSuccess"
      @cancel="emit('asset-cancelled')"
      @visible-change="isEditing = $event"
      @open-evidences="handleOpenEvidences"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Search,
  Refresh,
  Plus,
  Delete,
  MoreFilled,
  UserFilled,
  Connection,
  Document
} from '@element-plus/icons-vue'
import { characterApi } from '@/api/res-character'
import { dramaApi } from '@/api/drama'
import type { ResCharacter, ResCharacterQuery, ResCharacterEvidence } from '@/types/resource'
import type { DramaOption } from '@/types/drama'
import CharacterDrawer from './CharacterDrawer.vue'

const emit = defineEmits<{
  (e: 'asset-created', assetId: string | number): void
  (e: 'asset-cancelled'): void
}>()

const isEditing = ref(false)
const loading = ref(false)
const viewMode = ref<'grid' | 'table'>('grid')
const characterList = ref<ResCharacter[]>([])
const total = ref(0)
const dramaOptions = ref<DramaOption[]>([])

const characterDrawerRef = ref<InstanceType<typeof CharacterDrawer>>()

// 角色合并状态
const mergeDialogVisible = ref(false)
const mergeLoading = ref(false)
const mergeForm = reactive({
  sourceCharacterId: undefined as string | number | undefined,
  targetCharacterId: undefined as string | number | undefined,
  reason: ''
})

// 证据链抽屉状态
const evidenceDrawerVisible = ref(false)
const evidenceLoading = ref(false)
const selectedCharacter = ref<ResCharacter | null>(null)
const evidenceList = ref<ResCharacterEvidence[]>([])

const queryParams = reactive<ResCharacterQuery>({
  current: 1,
  size: 12,
  dramaId: undefined,
  name: '',
  gender: '',
  roleType: '',
  identityStatus: '',
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
    const res = await characterApi.getPage(queryParams)
    characterList.value = res.records || []
    total.value = res.total || 0
  } catch (error) {
    ElMessage.error('加载人物列表失败')
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
  queryParams.gender = ''
  queryParams.roleType = ''
  queryParams.identityStatus = ''
  queryParams.dramaId = undefined
  queryParams.status = undefined
  fetchData()
}

function handleCreate(dramaId?: string | number) {
  characterDrawerRef.value?.open(undefined, dramaId)
}

function handleDrawerSuccess(assetId?: string | number) {
  fetchData()
  if (assetId !== undefined && assetId !== null && assetId !== '') {
    emit('asset-created', assetId)
  }
}

function handleEdit(char: ResCharacter) {
  characterDrawerRef.value?.open(char)
}

function handleOpenMerge() {
  mergeForm.sourceCharacterId = undefined
  mergeForm.targetCharacterId = undefined
  mergeForm.reason = ''
  mergeDialogVisible.value = true
}

function handleQuickMerge(char: ResCharacter) {
  mergeForm.sourceCharacterId = char.id
  mergeForm.targetCharacterId = undefined
  mergeForm.reason = ''
  mergeDialogVisible.value = true
}

async function handleConfirmMerge() {
  if (!mergeForm.sourceCharacterId || !mergeForm.targetCharacterId) {
    ElMessage.warning('请选择源角色和目标角色')
    return
  }
  try {
    mergeLoading.value = true
    await characterApi.merge({
      sourceCharacterId: mergeForm.sourceCharacterId,
      targetCharacterId: mergeForm.targetCharacterId,
      reason: mergeForm.reason
    })
    ElMessage.success('角色合并成功，分镜引用与别名已全量迁移')
    mergeDialogVisible.value = false
    fetchData()
  } catch (error: any) {
    ElMessage.error(error.message || '角色合并失败')
  } finally {
    mergeLoading.value = false
  }
}

async function handleOpenEvidences(char: ResCharacter) {
  selectedCharacter.value = char
  evidenceDrawerVisible.value = true
  if (!char.id) return
  try {
    evidenceLoading.value = true
    evidenceList.value = await characterApi.getEvidences(char.id)
  } catch (error) {
    ElMessage.error('加载身份依据失败')
  } finally {
    evidenceLoading.value = false
  }
}

async function handleDelete(char: ResCharacter) {
  if (!char.id) return
  try {
    await characterApi.delete(char.id)
    ElMessage.success('人物角色已成功删除')
    fetchData()
  } catch (error) {
    ElMessage.error('删除人物失败')
  }
}

function copyText(text: string) {
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success('已复制触发词')
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

function getRoleLabel(roleType: string) {
  const map: Record<string, string> = {
    PROTAGONIST: '主角',
    ANTAGONIST: '反派',
    SUPPORTING: '配角',
    EXTRA: '群演/路人'
  }
  return map[roleType] || roleType
}

function getRoleTagType(roleType: string): 'primary' | 'danger' | 'warning' | 'info' {
  const map: Record<string, 'primary' | 'danger' | 'warning' | 'info'> = {
    PROTAGONIST: 'primary',
    ANTAGONIST: 'danger',
    SUPPORTING: 'warning',
    EXTRA: 'info'
  }
  return map[roleType] || 'info'
}

function getGenderLabel(gender: string) {
  const map: Record<string, string> = {
    MALE: '男',
    FEMALE: '女',
    OTHER: '其他',
    UNKNOWN: '未知'
  }
  return map[gender] || gender
}

function getIdentityStatusLabel(status?: string) {
  const map: Record<string, string> = {
    CONFIRMED: '已确认姓名',
    PARTIAL: '临时称谓',
    UNRESOLVED: '待消歧',
    MERGED: '已合并'
  }
  return status ? map[status] || status : '已确认'
}

function getIdentityStatusTagType(status?: string): 'success' | 'warning' | 'danger' | 'info' {
  const map: Record<string, 'success' | 'warning' | 'danger' | 'info'> = {
    CONFIRMED: 'success',
    PARTIAL: 'warning',
    UNRESOLVED: 'danger',
    MERGED: 'info'
  }
  return status ? map[status] || 'info' : 'success'
}

function getCharacterImages(char: ResCharacter): string[] {
  return [char.referenceImageUrl || char.avatarUrl].filter(Boolean) as string[]
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
