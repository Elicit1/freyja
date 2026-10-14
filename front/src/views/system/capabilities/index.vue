<template>
  <div class="capability-page" v-loading="loading">
    <div class="heading">
      <div><h1>AI 能力管理</h1><p>统一管理内置助手与外部 MCP 的业务能力、工具说明和创作规则。</p></div>
      <div class="actions"><el-button @click="load">刷新</el-button><el-button :disabled="busy || !dirty" @click="save">保存草稿</el-button><el-button type="primary" :disabled="busy || dirty" @click="publish">发布版本</el-button></div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="mb-4" />
    <template v-if="view && draft">
      <el-alert :title="`当前发布修订 ${view.active.revision} · 配置修订 ${view.revision}${dirty ? ' · 有未保存修改' : ''}`" type="info" :closable="false" />
      <el-alert v-for="issue in view.issues" :key="`${issue.source}-${issue.bindingId}`" class="mt-3" type="warning" :closable="false"
        :title="`${issue.source === 'draft' ? '草稿' : '已发布'}能力 ${issue.name} 需要处理`" :description="issue.message" />
      <el-tabs v-model="tab" class="mt-4">
        <el-tab-pane label="能力与入口绑定" name="bindings">
          <div class="toolbar"><el-input v-model="keyword" placeholder="搜索工具名称或说明" clearable /><el-select v-model="consumer"><el-option value="ALL" label="全部入口" /><el-option value="INTERNAL_AGENT" label="内置助手" /><el-option value="MCP" label="外部 MCP" /></el-select></div>
          <el-table :data="bindingRows" stripe row-key="id">
            <el-table-column label="工具" min-width="240"><template #default="{ row }"><strong>{{ row.name }}</strong><p class="muted">{{ row.description }}</p></template></el-table-column>
            <el-table-column label="入口" width="110"><template #default="{ row }">{{ row.consumer === 'MCP' ? '外部 MCP' : '内置助手' }}</template></el-table-column>
            <el-table-column label="执行与项目要求" min-width="220"><template #default="{ row }"><p v-if="row.consumer === 'MCP'" class="handler-id"><code>{{ row.handlerKey }}</code></p><el-tag :type="row.effect === 'WRITE' ? 'warning' : 'info'" size="small">{{ row.effect === 'WRITE' ? '可修改数据' : '只读' }}</el-tag><p class="muted">{{ row.projectRequired ? (row.consumer === 'MCP' ? '调用时需指定项目' : '需预先绑定项目') : '不要求预先绑定项目' }}</p></template></el-table-column>
            <el-table-column label="启用" width="90"><template #default="{ row }"><el-switch v-model="row.enabled" :disabled="row.required || busy" @change="dirty = true" /></template></el-table-column>
            <el-table-column label="操作" width="100"><template #default="{ row }"><el-button link type="primary" @click="edit(row)">{{ view.issues.some(i => i.source === 'draft' && i.bindingId === row.id) ? '修复 / 编辑' : '编辑' }}</el-button></template></el-table-column>
          </el-table>
          <el-pagination v-model:current-page="bindingPage" v-model:page-size="bindingPageSize" :total="bindingTotal" :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next" class="list-pagination" />
          <p class="hint">只读工具仅查询数据；可修改数据的工具会写入或更新记录。项目要求说明调用前提，实际可访问的数据仍受项目授权限制。</p>
        </el-tab-pane>
        <el-tab-pane label="公共创作规则" name="rules"><p class="hint">公共规则同时用于内置助手、新建任务、视频提示词生成和 MCP 指引。已有任务保留创建时的版本。</p><el-input v-model="draft.rules" type="textarea" :rows="16" @input="dirty = true" /></el-tab-pane>
        <el-tab-pane label="外部创作指南" name="guide"><p class="hint">发布后同时更新 MCP 初始化说明与 freyja://guide；公共规则会追加到该指南。</p><el-input v-model="draft.instructions" type="textarea" :rows="20" @input="dirty = true" /></el-tab-pane>
        <el-tab-pane label="发布记录" name="releases"><el-table :data="view.releases"><el-table-column prop="revision" label="修订" width="90" /><el-table-column prop="note" label="说明" /><el-table-column prop="createTime" label="时间" width="200" /><el-table-column label="操作" width="100"><template #default="{ row }"><el-button link :disabled="busy || row.id === view.active.id" @click="rollback(row.id)">回滚</el-button></template></el-table-column></el-table></el-tab-pane>
      </el-tabs>
    </template>
    <el-dialog v-model="editing" title="编辑能力绑定" width="min(900px, 95vw)">
      <el-form v-if="selected" label-position="top"><el-form-item label="工具名称"><el-input v-model="selected.name" maxlength="128" /></el-form-item><el-form-item label="工具说明"><el-input v-model="selected.description" type="textarea" :rows="5" /></el-form-item><el-form-item label="参数 Schema"><el-input v-model="schemaText" type="textarea" :rows="17" class="schema-editor" /><p class="hint">可以修改字段说明或收紧支持的约束；字段、类型、处理器与权限边界会在发布前校验。</p><el-button v-if="view?.repairs[selected.id]" link type="warning" @click="restoreSchema">恢复当前参数结构</el-button></el-form-item></el-form>
      <details v-if="selected" class="technical-details"><summary>技术信息</summary><p>底层处理器：<code>{{ selected.handlerKey }}</code></p><p class="muted">注册名称保留了初始实现来源；实际调用入口由当前绑定决定。内置助手直接调用共享业务处理器。</p></details>
      <template #footer><el-button @click="editing = false">取消</el-button><el-button type="primary" @click="applyEdit">应用到草稿</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useLocalPagination } from '@/composables/useLocalPagination'
import { ElMessage, ElMessageBox } from 'element-plus'
import { capabilitiesApi, type CapabilityBinding, type CapabilityRelease, type CapabilityView } from '@/api/capabilities'

const view = ref<CapabilityView>(), draft = ref<CapabilityRelease>()
const loading = ref(false), busy = ref(false), dirty = ref(false), error = ref('')
const tab = ref('bindings'), keyword = ref(''), consumer = ref('ALL')
const editing = ref(false), selected = ref<CapabilityBinding>(), schemaText = ref('')
const filtered = computed(() => draft.value?.bindings.filter(b => (consumer.value === 'ALL' || b.consumer === consumer.value) && `${b.name} ${b.description}`.toLowerCase().includes(keyword.value.toLowerCase())) || [])
const { page: bindingPage, pageSize: bindingPageSize, total: bindingTotal, rows: bindingRows, reset: resetBindings } = useLocalPagination(filtered)
watch([keyword, consumer], resetBindings)
function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }
function accept(value: CapabilityView) { view.value = { ...value, issues: value.issues || [], repairs: value.repairs || {} }; draft.value = clone(value.draft); dirty.value = false }
async function load() { loading.value = true; error.value = ''; try { accept(await capabilitiesApi.get()) } catch (e) { error.value = e instanceof Error ? e.message : '读取能力目录失败' } finally { loading.value = false } }
async function save() { if (!view.value || !draft.value) return; busy.value = true; error.value = ''; try { accept(await capabilitiesApi.save(view.value.revision, draft.value)); ElMessage.success('草稿已保存，发布后生效') } catch (e) { error.value = e instanceof Error ? e.message : '保存失败' } finally { busy.value = false } }
async function publish() {
  if (!view.value) return
  try { const { value } = await ElMessageBox.prompt('填写本次发布说明', '发布统一能力目录', { inputPlaceholder: '例如：统一视频声音规则', inputValue: '' }); busy.value = true; accept(await capabilitiesApi.publish(view.value.revision, value)); ElMessage.success('版本已发布') }
  catch (e) { if (e !== 'cancel' && e !== 'close') error.value = e instanceof Error ? e.message : '发布失败' } finally { busy.value = false }
}
async function rollback(id: string) {
  if (!view.value) return
  try { await ElMessageBox.confirm('新任务与后续 MCP 请求将使用所选版本。', '回滚能力目录'); busy.value = true; accept(await capabilitiesApi.rollback(view.value.revision, id)); ElMessage.success('已发布回滚版本') }
  catch (e) { if (e !== 'cancel' && e !== 'close') error.value = e instanceof Error ? e.message : '回滚失败' } finally { busy.value = false }
}
function edit(binding: CapabilityBinding) { selected.value = clone(binding); schemaText.value = JSON.stringify(binding.schema, null, 2); editing.value = true }
async function restoreSchema() {
  if (!selected.value || !view.value?.repairs[selected.value.id]) return
  try {
    await ElMessageBox.confirm('这会替换该能力的参数结构及其自定义参数说明；工具名称和工具说明会保留。', '恢复当前参数结构')
    schemaText.value = JSON.stringify(view.value.repairs[selected.value.id], null, 2)
  } catch { /* 取消恢复 */ }
}
function applyEdit() {
  if (!selected.value || !draft.value) return
  try { const schema = JSON.parse(schemaText.value); if (!schema || Array.isArray(schema) || typeof schema !== 'object') throw new Error(); selected.value.schema = schema }
  catch { ElMessage.error('Schema 必须是有效的 JSON 对象'); return }
  const index = draft.value.bindings.findIndex(b => b.id === selected.value?.id)
  if (index >= 0) draft.value.bindings[index] = clone(selected.value)
  dirty.value = true; editing.value = false
}
onMounted(load)
</script>

<style scoped>
.capability-page { padding: 24px; color: var(--text-primary); }
.list-pagination { margin-top: 18px; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
.heading, .actions, .toolbar { display: flex; gap: 12px; align-items: center; }
.heading { justify-content: space-between; margin-bottom: 20px; }
h1 { font-size: 24px; font-weight: 700; } p { margin-top: 8px; }
.muted, .hint, .heading p { color: var(--text-secondary); font-size: 12px; line-height: 1.6; }
.hint { margin: 14px 0; }.toolbar { margin: 16px 0; }.toolbar .el-input { max-width: 360px; }.toolbar .el-select { width: 150px; }
code { overflow-wrap: anywhere; font-size: 12px; }.schema-editor :deep(textarea) { font-family: monospace; }
.technical-details { margin-top: 12px; }.technical-details summary { cursor: pointer; color: var(--text-secondary); }
.handler-id { margin: 0 0 8px; }
@media (max-width: 850px) { .heading { align-items: flex-start; flex-direction: column; }.actions { flex-wrap: wrap; } }
</style>
