<template>
  <div class="h-[calc(100vh-96px)] flex flex-col studio-card overflow-hidden">
    <WorkspaceHeader
      title="AI Skills 知识技能库"
      subtitle="导入标准 Agent Skills ZIP，保留入口与附属资源并按需注入导演规划"
      :icon="Cpu"
    >
      <template #actions>
        <el-button type="primary" size="small" :icon="Upload" @click="openUploadDialog()">
          上传标准 Skill ZIP
        </el-button>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="loadSkills">刷新</el-button>
        <el-button type="warning" plain size="small" :icon="Refresh" :loading="refreshingCache" @click="handleRefreshCache">
          刷新 Skill 缓存
        </el-button>
      </template>
    </WorkspaceHeader>

    <div class="flex-1 p-4 overflow-y-auto">
      <el-table v-loading="loading" :data="skillList" row-key="id" stripe class="w-full">
        <el-table-column label="技能标识 / 名称" min-width="220">
          <template #default="{ row }">
            <div class="flex flex-col">
              <span class="font-bold text-sm text-[var(--text-primary)]">{{ row.displayName || row.name }}</span>
              <span class="font-mono text-xs text-[var(--text-muted)]">{{ row.name }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="当前版本" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.currentVersion" type="success" size="small" class="font-mono">v{{ row.currentVersion }}</el-tag>
            <span v-else class="text-xs text-[var(--text-muted)]">无版本</span>
          </template>
        </el-table-column>
        <el-table-column label="AI 目录" width="100" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled === 1" inline-prompt active-text="启用" inactive-text="停用" @change="(v: boolean) => handleToggleEnabled(row, v)" />
          </template>
        </el-table-column>
        <el-table-column label="目录描述" min-width="300">
          <template #default="{ row }"><p class="text-xs text-[var(--text-secondary)] line-clamp-2" :title="row.description">{{ row.description }}</p></template>
        </el-table-column>
        <el-table-column label="操作" width="350" fixed="right" align="center">
          <template #default="{ row }">
            <div class="flex items-center justify-center gap-1.5">
              <el-button type="primary" link size="small" :icon="Document" :disabled="!row.currentVersionId" @click="handlePreviewCurrent(row)">预览</el-button>
              <el-button type="primary" link size="small" :icon="Download" :disabled="!row.currentVersionId" :loading="downloadingSkillId === String(row.id)" @click="handleDownloadSkill(row)">下载包</el-button>
              <el-button type="success" link size="small" :icon="Upload" @click="openUploadDialog(row)">更新标准包</el-button>
              <el-button type="info" link size="small" :icon="Clock" @click="handleOpenVersions(row)">历史版本</el-button>
              <el-popconfirm title="确认删除整个 Skill 及其历史版本吗？" @confirm="handleDeleteSkill(row)">
                <template #reference><el-button type="danger" link size="small">删除</el-button></template>
              </el-popconfirm>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="uploadDialogVisible" :title="editingSkill ? `更新 Skill：${editingSkill.name}` : '上传标准 Skill ZIP'" width="560px" append-to-body destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="展示名称"><el-input v-model="uploadForm.displayName" placeholder="电影摄影与分镜运镜" /></el-form-item>
        <el-alert type="info" :closable="false" title="标准 Agent Skill 包" description="请上传 ZIP 目录包，根目录必须包含带 YAML frontmatter 的 SKILL.md；scripts、references、assets 等附属文件会按版本保留。压缩包最大 20MB，解压后最大 50MB。" class="mb-4" />
        <el-upload ref="uploadRef" drag action="#" :auto-upload="false" :limit="1" accept=".zip,application/zip" :on-change="handleFileChange" class="w-full">
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">将标准 Skill ZIP 拖到此处，或 <em>点击选择 .zip</em></div>
          <template #tip><div class="el-upload__tip text-xs text-[var(--text-muted)]">仅支持标准 Skill ZIP；名称和描述以 SKILL.md frontmatter 为准</div></template>
        </el-upload>
      </el-form>
      <template #footer>
        <el-button @click="uploadDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" :disabled="!selectedFile" @click="submitUpload">上传并生成新版本</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="versionDrawerVisible" :title="`Skill 历史版本 - ${activeSkill?.displayName || activeSkill?.name}`" size="620px" append-to-body>
      <el-table v-loading="loadingVersions" :data="versionList" row-key="id" size="small">
        <el-table-column label="版本" width="90" align="center"><template #default="{ row }"><span class="font-mono font-bold">v{{ row.version }}</span></template></el-table-column>
        <el-table-column label="状态" width="90" align="center"><template #default="{ row }"><el-tag :type="row.status === 'CURRENT' ? 'success' : 'info'" size="small">{{ row.status === 'CURRENT' ? '当前' : '历史' }}</el-tag></template></el-table-column>
        <el-table-column label="大小" width="90" align="center"><template #default="{ row }"><span class="font-mono text-xs">{{ formatBytes(row.contentSize) }}</span></template></el-table-column>
        <el-table-column label="创建时间" min-width="145"><template #default="{ row }"><span class="text-xs text-[var(--text-muted)] font-mono">{{ formatDateTime(row.createTime) }}</span></template></el-table-column>
        <el-table-column label="操作" width="140" align="center"><template #default="{ row }"><el-button v-if="row.status !== 'CURRENT'" type="warning" link size="small" @click="handleSwitchVersion(row)">回滚</el-button><el-button type="primary" link size="small" @click="handlePreviewVersion(row.id)">预览</el-button><el-button type="primary" link size="small" :icon="Download" :loading="downloadingVersionId === String(row.id)" @click="handleDownloadVersion(row)">下载</el-button></template></el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="previewDialogVisible" :title="`预览 Skill v${previewData?.version?.version || ''}`" width="820px" append-to-body>
      <div v-loading="loadingPreview" class="max-h-[70vh] overflow-y-auto">
        <div class="mb-3 text-xs text-[var(--text-muted)]">SHA-256：{{ previewData?.version?.contentHash }}</div>
        <div v-if="previewData?.files?.length" class="mb-3 rounded-lg border border-[var(--border-default)] p-3">
          <div class="mb-2 text-xs font-semibold text-[var(--text-secondary)]">标准包文件（{{ previewData.files.length }}）</div>
          <div class="flex flex-wrap gap-1.5">
            <el-tag v-for="file in previewData.files" :key="file.relativePath" size="small" effect="plain">{{ file.relativePath }}</el-tag>
          </div>
        </div>
        <pre class="bg-[var(--surface-muted)] p-4 rounded-lg font-mono text-xs whitespace-pre-wrap break-words leading-relaxed border border-[var(--border-default)]">{{ previewData?.content || '暂无 SKILL.md 正文' }}</pre>
      </div>
      <template #footer><el-button @click="previewDialogVisible = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Cpu, Upload, Refresh, Document, Clock, UploadFilled, Download } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile } from 'element-plus'
import WorkspaceHeader from '@/components/workspace/WorkspaceHeader.vue'
import { skillApi } from '@/api/skill'
import type { AiSkill, AiSkillPreview, AiSkillVersion } from '@/types/skill'

const loading = ref(false)
const refreshingCache = ref(false)
const skillList = ref<AiSkill[]>([])
const uploadDialogVisible = ref(false)
const uploading = ref(false)
const selectedFile = ref<File | null>(null)
const editingSkill = ref<AiSkill | null>(null)
const uploadForm = reactive({ displayName: '' })
const versionDrawerVisible = ref(false)
const loadingVersions = ref(false)
const activeSkill = ref<AiSkill | null>(null)
const versionList = ref<AiSkillVersion[]>([])
const previewDialogVisible = ref(false)
const loadingPreview = ref(false)
const previewData = ref<AiSkillPreview | null>(null)
const downloadingSkillId = ref<string | null>(null)
const downloadingVersionId = ref<string | null>(null)

const loadSkills = async () => {
  loading.value = true
  try { skillList.value = (await skillApi.list()) || [] } catch (e: any) { ElMessage.error(e?.message || '加载技能列表失败') } finally { loading.value = false }
}

const openUploadDialog = (skill?: AiSkill) => {
  editingSkill.value = skill || null
  uploadForm.displayName = skill?.displayName || ''
  selectedFile.value = null
  uploadDialogVisible.value = true
}

const handleFileChange = (file: UploadFile) => { selectedFile.value = file.raw || null }

const submitUpload = async () => {
  if (!selectedFile.value) return ElMessage.warning('请选择标准 Skill ZIP 文件')
  uploading.value = true
  try {
    const preview = await skillApi.upload(selectedFile.value, {
      skillId: editingSkill.value ? String(editingSkill.value.id) : undefined,
      displayName: uploadForm.displayName
    })
    ElMessage.success(`Skill 已生成 v${preview.version.version} 并生效`)
    uploadDialogVisible.value = false
    await loadSkills()
    await handlePreviewVersion(String(preview.version.id))
  } catch (e: any) { ElMessage.error(e?.message || '上传标准 Skill 包失败') } finally { uploading.value = false }
}

const handleToggleEnabled = async (skill: AiSkill, enabled: boolean) => {
  try { await skillApi.setEnabled(String(skill.id), enabled ? 1 : 0); skill.enabled = enabled ? 1 : 0 } catch (e: any) { ElMessage.error(e?.message || '更新状态失败') }
}

const handleRefreshCache = async () => {
  refreshingCache.value = true
  try {
    await skillApi.refreshCache()
    ElMessage.success('Skill 目录与正文缓存已刷新')
    await loadSkills()
  } catch (e: any) {
    ElMessage.error(e?.message || '刷新 Skill 缓存失败')
  } finally {
    refreshingCache.value = false
  }
}

const handleDeleteSkill = async (skill: AiSkill) => {
  try { await skillApi.delete(String(skill.id)); ElMessage.success(`已删除 Skill [${skill.name}]`); await loadSkills() } catch (e: any) { ElMessage.error(e?.message || '删除 Skill 失败') }
}

const handleOpenVersions = async (skill: AiSkill) => { activeSkill.value = skill; versionDrawerVisible.value = true; await loadSkillVersions(String(skill.id)) }
const loadSkillVersions = async (id: string) => { loadingVersions.value = true; try { versionList.value = (await skillApi.listVersions(id)) || [] } catch (e: any) { ElMessage.error(e?.message || '获取版本列表失败') } finally { loadingVersions.value = false } }
const handlePreviewCurrent = async (skill: AiSkill) => { if (skill.currentVersionId) await handlePreviewVersion(String(skill.currentVersionId)) }
const handlePreviewVersion = async (id: string) => { loadingPreview.value = true; previewDialogVisible.value = true; try { previewData.value = await skillApi.previewVersion(id) } catch (e: any) { ElMessage.error(e?.message || '加载版本预览失败') } finally { loadingPreview.value = false } }
const handleSwitchVersion = async (version: AiSkillVersion) => {
  if (!activeSkill.value) return
  try { await ElMessageBox.confirm(`确认回滚到 v${version.version} 吗？`, '版本回滚'); await skillApi.switchVersion(String(activeSkill.value.id), String(version.id)); ElMessage.success(`已切换到 v${version.version}`); await loadSkills(); await loadSkillVersions(String(activeSkill.value.id)) } catch (e: any) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e?.message || '切换版本失败') }
}

const triggerBlobDownload = (blob: Blob, filename: string) => {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

const handleDownloadSkill = async (skill: AiSkill) => {
  if (!skill.currentVersionId) {
    return ElMessage.warning('该 Skill 暂无已发布版本，无法下载')
  }
  downloadingSkillId.value = String(skill.id)
  try {
    const blob = await skillApi.download(String(skill.id))
    const versionLabel = skill.currentVersion ? `v${skill.currentVersion}` : 'current'
    triggerBlobDownload(blob, `${skill.name}-${versionLabel}.zip`)
    ElMessage.success(`Skill [${skill.name}] 包已开始下载`)
  } catch (e: any) {
    ElMessage.error(e?.message || '下载 Skill 包失败')
  } finally {
    downloadingSkillId.value = null
  }
}

const handleDownloadVersion = async (version: AiSkillVersion) => {
  downloadingVersionId.value = String(version.id)
  try {
    const blob = await skillApi.downloadVersion(String(version.id))
    const skillName = version.skillName || activeSkill.value?.name || 'skill'
    triggerBlobDownload(blob, `${skillName}-v${version.version}.zip`)
    ElMessage.success(`Skill 版本 v${version.version} 包已开始下载`)
  } catch (e: any) {
    ElMessage.error(e?.message || '下载 Skill 版本包失败')
  } finally {
    downloadingVersionId.value = null
  }
}

const formatDateTime = (val?: string) => val ? val.replace('T', ' ').substring(0, 19) : '-'
const formatBytes = (bytes?: number | string) => {
  const size = typeof bytes === 'string' ? Number(bytes) : bytes
  return !size ? '0 B' : size < 1024 ? `${size} B` : size < 1024 * 1024 ? `${(size / 1024).toFixed(1)} KB` : `${(size / (1024 * 1024)).toFixed(2)} MB`
}
onMounted(loadSkills)
</script>

<style scoped>
.studio-badge { display: inline-flex; align-items: center; padding: 2px 8px; border-radius: 999px; }
</style>
