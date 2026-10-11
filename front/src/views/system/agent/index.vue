<template>
  <div class="agent-config-page studio-card">
    <WorkspaceHeader title="Agent 配置" subtitle="设置助手的模型、能力、记忆与虚拟形象" :icon="ChatDotRound">
      <template #actions>
        <el-button size="small" :loading="validating" @click="validate">校验配置</el-button>
        <el-button size="small" @click="reload">重新加载</el-button>
        <el-button type="primary" size="small" :loading="saving" :disabled="!form" @click="save">保存配置</el-button>
      </template>
    </WorkspaceHeader>
    <div v-loading="loading" class="config-body">
      <aside class="agent-list">
        <button v-for="item in configs" :key="item.agentCode" class="agent-list-item" :class="{ selected: selectedCode === item.agentCode }" @click="selectAgent(item.agentCode)">
          <span class="font-semibold">{{ item.displayName }}</span>
          <el-tag size="small" :type="item.enabled ? 'success' : 'info'">{{ item.enabled ? '启用' : '停用' }}</el-tag>
          <small>{{ item.model.modelCode || '尚未选择模型' }}</small>
        </button>
      </aside>
      <main v-if="form" class="config-form">
        <div class="config-meta">
          <span>{{ form.agentCode }} · 配置 v{{ form.revision }}</span>
          <el-tag v-if="dirty" type="warning" size="small">有未保存修改</el-tag>
        </div>
        <el-alert v-if="validation" :title="validation.valid ? '配置校验通过' : '请修复以下配置问题'" :type="validation.valid ? 'success' : 'error'" :closable="false" class="mb-3">
          <div v-for="issue in validation.errors" :key="issue.field">{{ issue.field }}：{{ issue.message }}</div>
          <div v-for="issue in validation.warnings" :key="issue.code" class="text-[var(--text-secondary)]">{{ issue.message }}</div>
        </el-alert>
        <el-tabs v-model="tab">
          <el-tab-pane label="基本信息" name="basic">
            <el-form label-position="top">
              <el-form-item label="Agent 名称"><el-input v-model="form.displayName" maxlength="100" /></el-form-item>
              <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="3" maxlength="500" /></el-form-item>
              <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
              <p class="field-hint">保存启用后可创建新任务。停用后，已经运行的任务仍会继续。</p>
            </el-form>
          </el-tab-pane>
          <el-tab-pane label="模型" name="model">
            <el-form label-position="top">
              <el-form-item label="AI 提供商"><el-select v-model="form.model.providerId" clearable class="w-full" @change="providerChanged">
                <el-option v-for="provider in providers" :key="String(provider.id)" :label="provider.providerName" :value="String(provider.id)" />
              </el-select></el-form-item>
              <el-form-item label="CHAT 模型"><el-select v-model="form.model.modelCode" clearable class="w-full" placeholder="选择支持工具调用的对话模型">
                <el-option v-for="model in models" :key="String(model.id)" :label="model.modelName + (capability(model) === false ? ' · 不支持工具调用' : capability(model) === undefined ? ' · 能力待验证' : '')"
                  :value="model.modelCode" :disabled="capability(model) === false && form.tools.names.length > 0" />
              </el-select></el-form-item>
              <p class="field-hint mb-4">提供商密钥在 AI 提供商页面维护。请选择支持工具调用的模型。</p>
              <div class="grid grid-cols-2 gap-4">
                <el-form-item label="Temperature"><el-input-number v-model="form.model.temperature" :min="0" :max="2" :step="0.1" /></el-form-item>
                <el-form-item label="Top P"><el-input-number v-model="form.model.topP" :min="0.01" :max="1" :step="0.05" /></el-form-item>
                <el-form-item label="最大输出 Tokens"><el-input-number v-model="form.model.maxTokens" :min="1" :max="32768" :step="512" /></el-form-item>
              </div>
            </el-form>
          </el-tab-pane>
          <el-tab-pane label="系统指令" name="instructions">
            <el-form label-position="top">
              <el-form-item label="助手职责、工作偏好和回复风格"><el-input v-model="form.instructions.systemPrompt" type="textarea" :rows="12" maxlength="30000" /></el-form-item>
            </el-form>
            <details class="field-hint"><summary>查看模板固定约束</summary><pre class="whitespace-pre-wrap mt-2">{{ catalog?.fixedInstructions }}</pre></details>
          </el-tab-pane>
          <el-tab-pane label="工具与 Skills" name="tools">
            <el-checkbox-group v-model="form.tools.names" class="tool-grid">
              <label v-for="tool in catalog?.tools" :key="tool.name" class="tool-card">
                <el-checkbox :value="tool.name" :disabled="catalog?.requiredTools.includes(tool.name)">{{ toolLabel(tool.name) }}</el-checkbox>
                <el-tag size="small" :type="tool.effect === 'WRITE' ? 'warning' : 'info'">{{ tool.effect === 'WRITE' ? '修改' : '读取' }}</el-tag>
                <p>{{ tool.description }}</p>
                <small>{{ tool.projectRequired ? '需要绑定项目' : '通用能力' }} · v{{ tool.version }}</small>
              </label>
            </el-checkbox-group>
            <el-divider>专业 Skills</el-divider>
            <el-form label-position="top">
              <el-form-item label="允许使用的 Skills"><el-select v-model="form.skills.names" multiple filterable class="w-full" placeholder="选择已发布的技能">
                <el-option v-for="skill in catalog?.skills" :key="String(skill.versionId)" :label="skill.displayName + ' · v' + skill.version" :value="skill.name" />
              </el-select></el-form-item>
              <el-form-item label="按需加载"><el-switch v-model="form.skills.dynamicLoading" /></el-form-item>
              <p class="field-hint">开启后由 Agent 从所选 Skills 中按需加载；关闭后在上下文中预加载所选正文。每个任务固定技能版本。</p>
            </el-form>
          </el-tab-pane>
          <el-tab-pane label="记忆" name="memory">
            <el-form label-position="top">
              <el-form-item label="自动压缩历史摘要"><el-switch v-model="form.memory.summaryEnabled" /></el-form-item>
              <el-form-item label="近期历史窗口"><el-input-number v-model="form.memory.recentMessages" :min="4" :max="200" /></el-form-item>
              <el-form-item label="上下文预算 Tokens"><el-input-number v-model="form.memory.contextTokens" :min="2048" :max="100000" :step="1024" /></el-form-item>
              <el-form-item label="明确规则作用域"><el-checkbox-group v-model="form.memory.scopes"><el-checkbox value="SESSION">会话规则</el-checkbox><el-checkbox value="PROJECT">当前项目规则</el-checkbox></el-checkbox-group></el-form-item>
              <p class="field-hint">历史与明确规则保存到 MySQL。更改策略不会删除记忆；工具调用及结果始终成对保留。</p>
            </el-form>
          </el-tab-pane>
          <el-tab-pane label="执行参数" name="runtime">
            <el-form label-position="top" class="grid grid-cols-2 gap-x-5">
              <el-form-item label="最多模型轮数"><el-input-number v-model="form.runtime.maxModelTurns" :min="1" :max="100" /></el-form-item>
              <el-form-item label="最多工具调用"><el-input-number v-model="form.runtime.maxToolCalls" :min="0" :max="300" /></el-form-item>
              <el-form-item label="执行时限（秒）"><el-input-number v-model="form.runtime.executionTimeoutSeconds" :min="10" :max="7200" /></el-form-item>
              <el-form-item label="单次模型超时（秒）"><el-input-number v-model="form.runtime.modelTimeoutSeconds" :min="5" :max="600" /></el-form-item>
              <el-form-item label="外部任务等待时限（秒）"><el-input-number v-model="form.runtime.externalWaitTimeoutSeconds" :min="10" :max="86400" /></el-form-item>
              <el-form-item label="失败重试次数"><el-input-number v-model="form.runtime.maxRetries" :min="0" :max="5" /></el-form-item>
              <el-form-item label="Agent 并发上限"><el-input-number v-model="form.runtime.maxConcurrentRuns" :min="1" :max="4" /></el-form-item>
            </el-form>
            <p class="field-hint">等待用户或外部任务时不占用执行线程。全局同时执行最多 4 个会话。</p>
          </el-tab-pane>
          <el-tab-pane label="虚拟形象" name="avatar">
            <el-form label-position="top">
              <el-form-item label="显示虚拟形象"><el-switch v-model="form.avatar.visible" /></el-form-item>
              <div class="avatar-preset">
                <el-upload :show-file-list="false" accept=".zip" :http-request="upload" :disabled="uploading"><el-button type="primary" :loading="uploading">上传 ZIP</el-button></el-upload>
                <el-button @click="useFreyjaAvatar">恢复默认</el-button>
                <a class="avatar-download" :href="freyjaPetDownload" download="freyja.codex-pet.zip">下载默认宠物包</a>
              </div>
              <p class="field-hint mb-4">当前形象：{{ form.avatar.pet.displayName }}。支持 Codex Pet v1/v2 ZIP（pet.json 和 PNG/WebP 精灵图），最多 20 MB。上传或恢复默认后保存生效。</p>
              <a class="avatar-download" href="https://codex-pet.org/zh/codex-pets/" target="_blank" rel="noopener noreferrer">浏览 Codex Pet 样式</a>
              <div class="avatar-grid">
                <div v-for="(state, row) in petRows" :key="state.name" class="avatar-card">
                  <div class="avatar-preview"><AgentAvatarMedia :avatar="form.avatar" state="IDLE" :animation-row="row" :label="state.label" /></div>
                  <strong>{{ state.label }}</strong>
                </div>
              </div>
              <el-divider>气泡提醒</el-divider>
              <el-form-item label="任务完成"><el-switch v-model="form.avatar.notifications.completed" /></el-form-item>
              <el-form-item label="任务失败"><el-switch v-model="form.avatar.notifications.failed" /></el-form-item>
              <el-form-item label="需要用户处理"><el-switch v-model="form.avatar.notifications.needsAttention" /></el-form-item>
            </el-form>
          </el-tab-pane>
        </el-tabs>
      </main>
      <el-empty v-if="!loading && !form" description="暂无可配置的 Agent" />
    </div>
    <div class="config-footer">执行配置用于新任务，运行中的任务保留原配置。虚拟形象保存后立即更新。</div>
  </div>
</template>
<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox, type UploadRequestOptions } from 'element-plus'
import { ChatDotRound } from '@element-plus/icons-vue'
import WorkspaceHeader from '@/components/workspace/WorkspaceHeader.vue'
import AgentAvatarMedia from '@/components/agent/AgentAvatarMedia.vue'
import { freyjaDefaultPet, freyjaPetDownload, petRows } from '@/components/agent/avatarPresets'
import { agentConfigApi } from '@/api/agent'
import { aiProviderApi } from '@/api/ai-provider'
import { useAgentStore } from '@/store/agent'
import type { AgentConfig, AgentCatalog, ValidationResult } from '@/types/agent'
import type { AiProviderVO, AiModel } from '@/types/ai-provider'
const configs = ref<AgentConfig[]>([])
const form = ref<AgentConfig>()
const catalog = ref<AgentCatalog>()
const providers = ref<AiProviderVO[]>([])
const models = ref<AiModel[]>([])
const selectedCode = ref('')
const tab = ref('basic')
const loading = ref(false), saving = ref(false), validating = ref(false), uploading = ref(false)
const validation = ref<ValidationResult>()
const saved = ref('')
const dirty = computed(() => !!form.value && JSON.stringify(form.value) !== saved.value)
const toolNames: Record<string, string> = {
  start_script_decomposition: '拆解小说', retry_decomposition_segment: '重试失败段落', commit_decomposition: '保存拆解结果',
  list_preparation_shots: '读取制作分镜', get_reference_candidates: '盘点参考素材', ensure_text_assets: '保存文字资产',
  save_shot_production_plans: '应用制作方案', prepare_shot_prompts: '准备分镜提示词', inspect_preparation: '检查并保存报告', get_preparation_report: '读取交付报告',
  get_project_overview: '读取项目概要', list_project_assets: '查询资产', get_shot_detail: '查询分镜',
  save_session_note: '保存明确规则', list_session_notes: '读取规则', load_skill: '加载技能', read_skill_file: '读取技能参考'
}
function toolLabel(name: string) { return toolNames[name] || name }
function capability(model: AiModel): boolean | undefined {
  try { const value = JSON.parse(model.paramsJson || '{}').capabilities?.toolCalling; return typeof value === 'boolean' ? value : undefined }
  catch { return undefined }
}
async function confirmDiscard() {
  if (!dirty.value) return true
  try { await ElMessageBox.confirm('当前配置尚未保存，是否放弃这些修改？', '未保存修改', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning' }); return true }
  catch { return false }
}
async function load(code: string) {
  loading.value = true
  try {
    const [config, directory] = await Promise.all([agentConfigApi.get(code), agentConfigApi.catalog(code)])
    form.value = config; catalog.value = directory; selectedCode.value = code; validation.value = undefined
    saved.value = JSON.stringify(config)
    models.value = config.model.providerId ? await aiProviderApi.getModelList(config.model.providerId, 'CHAT') : []
  } finally { loading.value = false }
}
async function selectAgent(code: string) { if (code !== selectedCode.value && await confirmDiscard()) await load(code) }
async function reload() { if (selectedCode.value && await confirmDiscard()) await load(selectedCode.value) }
async function providerChanged(value?: string) {
  if (!form.value) return
  form.value.model.modelCode = undefined
  form.value.model.providerId = value || undefined
  models.value = value ? await aiProviderApi.getModelList(value, 'CHAT') : []
}
function payload() {
  const data: AgentConfig = JSON.parse(JSON.stringify(form.value))
  data.model.providerId ||= undefined
  data.model.modelCode ||= undefined
  data.avatar.stateMedia = {}
  return data
}
async function validate() {
  if (!form.value) return
  validating.value = true
  try { validation.value = await agentConfigApi.validate(selectedCode.value, payload()) }
  finally { validating.value = false }
}
async function save() {
  if (!form.value) return
  saving.value = true
  try {
    validation.value = await agentConfigApi.validate(selectedCode.value, payload())
    if (!validation.value.valid) return
    const updated = await agentConfigApi.save(selectedCode.value, payload())
    form.value = updated; saved.value = JSON.stringify(updated)
    configs.value = configs.value.map(item => item.agentCode === updated.agentCode ? updated : item)
    await useAgentStore().refreshProfiles()
    ElMessage.success('Agent 配置已保存')
  } finally { saving.value = false }
}
async function upload(options: UploadRequestOptions) {
  uploading.value = true
  try {
    const media = await agentConfigApi.uploadAvatar(options.file)
    if (form.value) { form.value.avatar.pet = media; form.value.avatar.stateMedia = {}; form.value.avatar.visible = true }
    ElMessage.success('宠物包已导入，保存后生效')
    return media
  } finally { uploading.value = false }
}
function useFreyjaAvatar() {
  if (!form.value) return
  form.value.avatar.pet = structuredClone(freyjaDefaultPet)
  form.value.avatar.stateMedia = {}
  form.value.avatar.visible = true
  ElMessage.success('已恢复默认形象，保存后生效')
}
onBeforeRouteLeave(async () => await confirmDiscard())
onMounted(async () => {
  loading.value = true
  try {
    const [list, enabled] = await Promise.all([agentConfigApi.list(), aiProviderApi.getListEnabled()])
    configs.value = list; providers.value = enabled
    if (list[0]) await load(list[0].agentCode)
  } finally { loading.value = false }
})
</script>
<style scoped>
.agent-config-page { min-height: calc(100vh - 96px); overflow: hidden; display: flex; flex-direction: column; }
.config-body { display: grid; grid-template-columns: 190px minmax(350px, 1fr); flex: 1; min-height: 600px; }
.agent-list { padding: 14px; border-right: 1px solid var(--border-default); background: var(--surface-muted); }
.agent-list-item { width: 100%; padding: 13px; margin-bottom: 8px; text-align: left; display: flex; flex-wrap: wrap; gap: 8px; border: 1px solid var(--border-default); border-radius: 12px; background: var(--surface); color: var(--text-primary); cursor: pointer; }
.agent-list-item.selected { border-color: var(--brand); background: var(--brand-soft); }
.agent-list-item small { width: 100%; color: var(--text-secondary); overflow-wrap: anywhere; }
.config-form { padding: 18px 22px; min-width: 0; }
.config-meta { display: flex; justify-content: space-between; align-items: center; gap: 10px; font-size: 12px; color: var(--text-secondary); margin-bottom: 15px; }
.config-footer { padding: 12px 20px; border-top: 1px solid var(--border-default); font-size: 12px; color: var(--text-secondary); }
.field-hint { font-size: 12px; color: var(--text-secondary); line-height: 1.7; }
.tool-grid { display: grid; gap: 10px; }
.tool-card { display: block; padding: 12px; border: 1px solid var(--border-default); border-radius: 10px; }
.tool-card p { margin: 5px 0; font-size: 12px; color: var(--text-secondary); line-height: 1.6; }
.tool-card small { color: var(--text-secondary); font-size: 10px; }
.avatar-grid { display: grid; grid-template-columns: repeat(3, minmax(100px, 1fr)); gap: 12px; }
.avatar-preset { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 14px; }
.avatar-download { color: var(--brand); font-size: 11px; text-decoration: none; }
.avatar-download:hover { text-decoration: underline; }
.avatar-card { display: flex; flex-direction: column; align-items: center; padding: 12px; border-radius: 12px; border: 1px solid var(--border-default); gap: 7px; text-align: center; }
.avatar-preview { width: 144px; height: 156px; }
@media (max-width: 1300px) { .config-body { grid-template-columns: 160px minmax(300px, 1fr); } }
@media (max-width: 760px) { .config-body { display: block; } .agent-list { display: flex; gap: 10px; } .avatar-grid { grid-template-columns: repeat(2, 1fr); } }
</style>
