<template>
  <div class="mcp-page" v-loading="loading">
    <div class="page-heading">
      <div><h1>MCP 服务</h1><p>让第三方 Agent 直接配置业务和完整分镜，由 Freyja 校验并保存。</p></div>
      <el-button :disabled="busy" @click="load(true)">刷新状态</el-button>
    </div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
    <template v-if="settings">
      <div class="summary-grid">
        <el-card shadow="never"><div class="summary-label">服务状态</div><div class="status-line">
          <el-tag :type="settings.enabled ? 'success' : 'info'">{{ settings.enabled ? '已开启' : '已关闭' }}</el-tag>
          <el-switch :model-value="settings.enabled" :loading="busy" :disabled="busy || (!settings.tokenConfigured && !settings.enabled)" @change="switchServer" />
        </div><p class="hint">{{ settings.tokenConfigured ? '开关立即生效，关闭后拒绝新请求。' : '先在下方配置访问令牌，再开启服务。' }}</p></el-card>
        <el-card shadow="never"><div class="summary-label">已发布工具</div><strong class="summary-number">{{ settings.tools.filter(t => t.published).length }}<small> / {{ settings.tools.length }}</small></strong><p class="hint">只发布已开启且满足权限的业务工具。</p></el-card>
        <el-card shadow="never"><div class="summary-label">连接方式</div><strong>{{ settings.protocol }}</strong><div class="endpoint"><code>{{ connectionUrl }}</code><el-button link type="primary" @click="copy(connectionUrl)">复制</el-button></div></el-card>
      </div>

      <el-tabs v-model="tab">
        <el-tab-pane label="连接与权限" name="settings">
          <el-card shadow="never">
            <el-form label-position="top" class="settings-form" :disabled="busy" @submit.prevent="save">
              <el-form-item label="访问令牌">
                <div class="token-row"><el-input v-model="form.token" type="password" show-password autocomplete="new-password" :placeholder="settings.tokenConfigured ? '已配置；留空保留当前令牌' : '至少 24 个字符'" />
                  <el-button @click="generateToken">生成令牌</el-button><el-button :disabled="!form.token" @click="copy(form.token)">复制新令牌</el-button></div>
                <p class="hint">保存后不回显令牌。请将令牌保存在第三方客户端中，更换后原令牌立即失效。</p>
              </el-form-item>
              <div class="permission-grid">
                <el-form-item label="允许业务写入"><el-switch v-model="form.writeEnabled" /><p class="hint">开放创建、修改、追加导入等操作；具体工具仍需单独开启。</p></el-form-item>
                <el-form-item label="允许新建项目"><el-switch v-model="form.allowCreateProjects" :disabled="!form.writeEnabled" /><p class="hint">外部 Agent 可创建项目，并访问通过 MCP 创建的项目。</p></el-form-item>
              </div>
              <el-form-item label="项目授权"><el-switch v-model="form.allowAllProjects" active-text="允许访问全部项目" />
                <el-input v-if="!form.allowAllProjects" v-model="form.projectIds" type="textarea" :rows="3" placeholder="每行一个项目 ID，或用逗号分隔；留空仅允许公共库读取" />
                <p class="hint">公共资产始终只读。指定项目 ID 应完整保留，多个客户端使用同一令牌时共享授权。</p>
              </el-form-item>
              <el-form-item label="允许的浏览器来源"><el-input v-model="form.origins" type="textarea" :rows="2" placeholder="例如 https://studio.example，每行一个；原生桌面客户端可留空" /></el-form-item>
              <el-button type="primary" :loading="busy" :disabled="busy" native-type="submit">保存并生效</el-button>
            </el-form>
          </el-card>
          <el-card shadow="never" class="connection-card"><h3>客户端连接</h3><p class="hint">将令牌设置为客户端的 FREYJA_MCP_TOKEN 环境变量，然后填写下面的连接配置。远程客户端需使用可访问的服务地址。</p>
            <pre>{{ clientConfig }}</pre><el-button @click="copy(clientConfig)">复制连接配置</el-button>
          </el-card>
        </el-tab-pane>
        <el-tab-pane label="业务工具" name="tools">
          <div class="tool-toolbar"><el-input v-model="keyword" clearable placeholder="搜索工具名称或说明" class="search-input" /><el-select v-model="effect" class="effect-select"><el-option label="全部工具" value="ALL" /><el-option label="读取工具" value="READ" /><el-option label="写入工具" value="WRITE" /></el-select><span class="hint">工具开关即时保存，整体关闭时仍可配置。</span></div>
          <el-table :data="filteredTools" row-key="name" class="tools-table">
            <el-table-column label="工具" min-width="300"><template #default="{ row }"><strong>{{ labels[row.name] || row.name }}</strong><div><code>{{ row.name }}</code></div><p class="tool-description">{{ row.description }}</p></template></el-table-column>
            <el-table-column label="操作" width="85"><template #default="{ row }"><el-tag :type="row.effect === 'WRITE' ? 'warning' : 'info'" size="small">{{ row.effect === 'WRITE' ? '写入' : '读取' }}</el-tag></template></el-table-column>
            <el-table-column label="状态" min-width="130"><template #default="{ row }"><el-tag :type="row.published ? 'success' : 'info'" size="small">{{ row.published ? '已发布' : !row.enabled ? '已停用' : !settings.enabled ? '服务已关闭' : '写入权限未开启' }}</el-tag></template></el-table-column>
            <el-table-column label="开启" width="90"><template #default="{ row }"><el-switch :model-value="row.enabled" :disabled="busy" :loading="pendingTool === row.name" @change="(value: string | number | boolean) => switchTool(row, value)" /></template></el-table-column>
            <el-table-column label="参数" width="90"><template #default="{ row }"><el-button link type="primary" @click="schemaTool = row">查看</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
      <p class="boundary-note">外部 Agent 可上传图片用于人物、造型、场景、关键图和道具参考，直接填写提示词工作台卡片并按顺序关联参考媒体；导演计划、人物及造型英文 Prompt 均可省略。内部 Agent、AI 提示词生成、模型推理和 Skill 系统始终不通过此服务开放。</p>
    </template>
    <el-dialog :model-value="!!schemaTool" :title="schemaTool ? `工具参数 · ${schemaTool.name}` : '工具参数'" width="min(800px, 94vw)" @close="schemaTool = null"><pre class="schema-preview">{{ JSON.stringify(schemaTool?.inputSchema, null, 2) }}</pre></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { mcpApi, type McpSettings, type McpToolInfo } from '@/api/mcp'

const settings = ref<McpSettings | null>(null)
const loading = ref(false), busy = ref(false), loadError = ref(''), pendingTool = ref('')
const tab = ref('settings'), keyword = ref(''), effect = ref('ALL'), schemaTool = ref<McpToolInfo | null>(null)
const form = reactive({ token: '', writeEnabled: false, allowCreateProjects: false, allowAllProjects: false, projectIds: '', origins: '' })
const connectionUrl = computed(() => new URL('/api/mcp', window.location.origin).toString())
const clientConfig = computed(() => `[mcp_servers.freyja]\nurl = "${connectionUrl.value}"\nbearer_token_env_var = "FREYJA_MCP_TOKEN"\ntool_timeout_sec = 300`)
const filteredTools = computed(() => settings.value?.tools.filter(t => (effect.value === 'ALL' || t.effect === effect.value) && `${t.name} ${t.description} ${labels[t.name] || ''}`.toLowerCase().includes(keyword.value.toLowerCase())) || [])
const labels: Record<string, string> = { search_projects: '搜索项目', get_project: '项目详情', get_project_outline: '读取大纲', list_shots: '分镜列表', get_shot: '完整分镜配置', search_assets: '搜索资产', get_asset: '资产详情', list_character_looks: '人物造型', get_business_options: '业务字典', batch_read_records: '批量读取', batch_create_records: '批量创建', batch_update_records: '批量配置', append_storyboard: '追加分镜导入', get_business_operation: '业务请求结果', clone_shot: '复制分镜', set_default_character_look: '设置默认造型', reorder_group_shots: '组内分镜重排', list_shot_video_versions: '视频版本列表', select_shot_video_version: '选择视频版本', upload_asset_image: '上传资产参考图' }
function accept(value: McpSettings, syncForm = false) {
  settings.value = value
  if (syncForm) Object.assign(form, { token: '', writeEnabled: value.writeEnabled, allowCreateProjects: value.allowCreateProjects, allowAllProjects: value.allowAllProjects, projectIds: value.allowedProjectIds.join('\n'), origins: value.allowedOrigins.join('\n') })
}
async function load(syncForm = false) {
  loading.value = true; loadError.value = ''
  try { accept(await mcpApi.get(), syncForm) } catch (error) { loadError.value = error instanceof Error ? error.message : '读取 MCP 配置失败' } finally { loading.value = false }
}
function splitList(value: string) { return [...new Set(value.split(/[\s,，]+/).map(v => v.trim()).filter(Boolean))] }
async function save() {
  if (!settings.value) return
  if (form.token && form.token.length < 24) { ElMessage.error('访问令牌至少需要 24 个字符'); return }
  const ids = splitList(form.projectIds)
  if (ids.some(id => !/^[1-9][0-9]{0,18}$/.test(id))) { ElMessage.error('请填写完整的正整数项目 ID'); return }
  busy.value = true
  try {
    accept(await mcpApi.save({ revision: settings.value.revision, enabled: settings.value.enabled, writeEnabled: form.writeEnabled, allowAllProjects: form.allowAllProjects,
      allowCreateProjects: form.allowCreateProjects, allowedProjectIds: ids, allowedOrigins: splitList(form.origins), token: form.token || undefined }), true)
    ElMessage.success('MCP 配置已保存并生效')
  } catch { await load() } finally { busy.value = false }
}
async function switchServer(value: string | number | boolean) {
  if (!settings.value) return; busy.value = true
  try { accept(await mcpApi.enabled(settings.value.revision, value === true)); ElMessage.success(value === true ? 'MCP 服务已开启' : 'MCP 服务已关闭') }
  catch { await load() } finally { busy.value = false }
}
async function switchTool(tool: McpToolInfo, value: string | number | boolean) {
  if (!settings.value) return; busy.value = true; pendingTool.value = tool.name
  try { accept(await mcpApi.tool(tool.name, settings.value.revision, value === true)); ElMessage.success('工具开关已生效') }
  catch { await load() } finally { busy.value = false; pendingTool.value = '' }
}
function generateToken() { const bytes = crypto.getRandomValues(new Uint8Array(32)); form.token = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('') }
async function copy(value: string) { try { await navigator.clipboard.writeText(value); ElMessage.success('已复制') } catch { ElMessage.error('复制失败，请手动选择并复制') } }
onMounted(() => load(true))
</script>

<style scoped>
.mcp-page { padding: 24px; color: var(--text-primary); max-width: 1500px; margin: 0 auto; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 22px; }
h1 { font-size: 24px; font-weight: 700; margin: 0 0 8px; } h3 { font-weight: 600; margin-bottom: 12px; }
.page-heading p, .hint, .boundary-note { color: var(--text-secondary); font-size: 13px; line-height: 1.7; }
.summary-grid { display: grid; grid-template-columns: 1fr 1fr 1.5fr; gap: 16px; margin-bottom: 20px; }
.summary-label { font-size: 13px; color: var(--text-secondary); margin-bottom: 14px; }
.status-line, .token-row, .tool-toolbar, .endpoint { display: flex; align-items: center; gap: 12px; }
.status-line { justify-content: space-between; }.summary-number { font-size: 30px; line-height: 1; }.summary-number small { font-size: 16px; font-weight: 400; }
.endpoint { margin-top: 10px; overflow-wrap: anywhere; }.endpoint code { min-width: 0; word-break: break-all; font-size: 12px; }
.settings-form { max-width: 900px; }.settings-form .el-form-item { margin-bottom: 24px; }.settings-form .el-form-item :deep(.el-form-item__content) { display: block; }
.token-row .el-input { flex: 1; }.permission-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 28px; }
.hint { margin-top: 10px; }.settings-form .el-textarea { margin-top: 12px; }.connection-card { margin-top: 18px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; padding: 16px; background: var(--app-bg); border-radius: 8px; font-size: 12px; line-height: 1.7; margin: 12px 0; }
.tool-toolbar { margin: 12px 0 20px; flex-wrap: wrap; }.search-input { max-width: 320px; }.effect-select { width: 140px; }.tool-toolbar .hint { margin: 0; }
.tools-table code { color: var(--text-secondary); font-size: 12px; }.tool-description { font-size: 12px; line-height: 1.7; color: var(--text-secondary); margin: 8px 0; }
.boundary-note { margin-top: 20px; }.schema-preview { max-height: 65vh; overflow: auto; }
@media (max-width: 900px) { .summary-grid { grid-template-columns: 1fr; }.permission-grid { grid-template-columns: 1fr; }.token-row { flex-wrap: wrap; }.token-row .el-input { flex-basis: 100%; }.mcp-page { padding: 16px; } }
</style>
