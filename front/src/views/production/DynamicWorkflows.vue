<template>
  <ProductionWorkspace title="动态工作流" subtitle="组合基础生成、二采放大与人脸修复，保存可复用的制作流程" :icon="Connection">
    <template #actions><el-button @click="load">刷新</el-button><el-button type="primary" @click="create">新建工作流</el-button></template>
    <div class="workflow-workspace" v-loading="loading">
      <aside class="studio-card preset-list"><h3>工作流预设</h3><el-empty v-if="!presets.length" description="创建第一个制作流程" :image-size="72" />
        <button v-for="preset in presets" :key="String(preset.id)" :class="['preset-card', { active: edit.id === String(preset.id) }]" @click="open(preset)"><strong>{{ preset.name }}</strong><span>v{{ preset.version }} · {{ preset.status === 1 ? '启用' : '停用' }}</span><small>{{ summary(JSON.parse(preset.optionsJson)) }}</small></button>
      </aside>
      <section class="studio-card preset-editor">
        <div class="editor-heading"><div><h3>{{ edit.id ? '编辑工作流' : '新建工作流' }}</h3><p>修改会生成新版本，已提交任务继续使用原版本。</p></div><el-tag v-if="edit.version">v{{ edit.version }}</el-tag></div>
        <el-form label-position="top" :disabled="saving">
          <el-form-item label="工作流名称"><el-input v-model="edit.name" maxlength="120" placeholder="例如：高清人物成片" /></el-form-item>
          <div class="editor-fields"><el-form-item label="视频提供商"><el-select v-model="edit.providerId" @change="providerChanged"><el-option v-for="p in providers" :key="String(p.id)" :value="String(p.id)" :label="p.providerName" /></el-select></el-form-item>
          <el-form-item label="基础模型"><el-select v-model="edit.modelId" :loading="modelsLoading" @change="modelChanged"><el-option v-for="m in availableModels" :key="String(m.id)" :value="String(m.id)" :label="m.modelName || m.modelCode" /></el-select></el-form-item></div>
          <div class="stage-card"><div><strong>二采放大</strong><p>选择目标尺寸，在一采基础上重绘高清细节。</p></div><el-switch v-model="edit.options.upscale" aria-label="二采放大" /></div>
          <el-form-item v-if="edit.options.upscale" label="放大重绘强度"><el-input-number v-model="edit.options.upscaleDenoise" :min=".05" :max=".6" :step=".05" :precision="2" /></el-form-item>
          <FreyjaUpscaleOptions v-if="edit.options.upscale" v-model="edit.options" :capability="upscaleCapability" :disabled="saving" />
          <div class="stage-card"><div><strong>人脸修复</strong><p>{{ supportsFace ? '跟踪并精修面部，在放大之后执行。' : '请选择支持人脸修复的参考生成基础模型。' }}</p></div><el-switch v-model="edit.options.faceRepair" :disabled="!supportsFace" aria-label="人脸修复" /></div>
          <el-form-item v-if="edit.options.faceRepair" label="人脸重绘强度"><el-input-number v-model="edit.options.faceDenoise" :min=".05" :max=".6" :step=".05" :precision="2" /></el-form-item>
          <el-form-item label="工作流状态"><el-switch v-model="edit.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" /></el-form-item>
        </el-form>
        <div class="pipeline"><span>基础生成</span><span v-if="edit.options.upscale">→ 二采放大</span><span v-if="edit.options.faceRepair">→ 人脸修复</span><span>→ 成片</span></div>
        <p class="editor-note">接续使用基础生成结果。增强步骤独立保存，失败后可以继续处理。重绘可能改变细节，建议根据素材选择强度。</p>
        <el-alert v-if="error" :title="error" type="error" :closable="false" />
        <div class="editor-actions"><el-button v-if="edit.id" type="danger" plain :disabled="saving" @click="remove">删除</el-button><el-button type="primary" :loading="saving" :disabled="!edit.modelId || !edit.name.trim() || modelsLoading" @click="save">{{ edit.id ? '保存新版本' : '创建工作流' }}</el-button></div>
      </section>
    </div>
  </ProductionWorkspace>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Connection } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ProductionWorkspace from '@/components/workspace/ProductionWorkspace.vue'
import { aiProviderApi } from '@/api/ai-provider'
import type { AiModel, AiProviderVO } from '@/types/ai-provider'
import { dynamicWorkflowApi, defaultWorkflowOptions, normalizeWorkflowOptions, type WorkflowPreset, type WorkflowCapability, type WorkflowOptions } from '@/api/dynamic-workflow'
import FreyjaUpscaleOptions from '@/components/workflow/FreyjaUpscaleOptions.vue'
const presets = ref<WorkflowPreset[]>([]), providers = ref<AiProviderVO[]>([]), models = ref<AiModel[]>([]), caps = ref<WorkflowCapability[]>([])
const loading = ref(false), saving = ref(false), modelsLoading = ref(false), error = ref('')
const edit = reactive({ id: undefined as string | undefined, version: undefined as number | undefined, name: '', providerId: '', modelId: '', status: 1, options: defaultWorkflowOptions() })
let modelRevision = 0
const availableModels = computed(() => models.value.filter(m => m.status === 1 && caps.value.some(c => c.modelCode === m.modelCode && c.supported)))
const supportsFace = computed(() => caps.value.some(c => c.modelCode === models.value.find(m => String(m.id) === edit.modelId)?.modelCode && c.stages.includes('FACE')))
const upscaleCapability = computed(() => caps.value.find(c => c.modelCode === models.value.find(m => String(m.id) === edit.modelId)?.modelCode)?.freyjaUpscale)
function summary(options: WorkflowOptions) { return ['基础生成', ...(options.upscale ? ['二采放大'] : []), ...(options.faceRepair ? ['人脸修复'] : [])].join(' → ') }
function create() { modelRevision++; Object.assign(edit, { id: undefined, version: undefined, name: '', providerId: '', modelId: '', status: 1, options: defaultWorkflowOptions() }); models.value = []; caps.value = []; error.value = ''; modelsLoading.value = false }
async function load() { loading.value = true; error.value = ''; try { [presets.value, providers.value] = await Promise.all([dynamicWorkflowApi.list(), aiProviderApi.getListEnabled()]) } catch (e: any) { error.value = e.message || '加载工作流失败' } finally { loading.value = false } }
async function loadModels() {
  const token = ++modelRevision, provider = edit.providerId; models.value = []; caps.value = []; error.value = ''; if (!provider) return; modelsLoading.value = true
  try { const [list, capability] = await Promise.all([aiProviderApi.getModelList(provider), dynamicWorkflowApi.capabilities(provider)]); if (token === modelRevision) { models.value = list; caps.value = capability.models } }
  catch (e: any) { if (token === modelRevision) error.value = e.message || '加载模型能力失败' } finally { if (token === modelRevision) modelsLoading.value = false }
}
async function providerChanged() { edit.modelId = ''; edit.options.faceRepair = false; await loadModels() }
function modelChanged() { if (!supportsFace.value) edit.options.faceRepair = false }
async function open(preset: WorkflowPreset) { Object.assign(edit, { id: String(preset.id), version: preset.version, name: preset.name, providerId: String(preset.providerId), modelId: String(preset.modelId), status: preset.status, options: normalizeWorkflowOptions(JSON.parse(preset.optionsJson)) }); await loadModels() }
async function save() { saving.value = true; error.value = ''; try { const saved = await dynamicWorkflowApi.save(JSON.parse(JSON.stringify(edit))); await load(); await open(saved); ElMessage.success('工作流已保存') } catch (e: any) { error.value = e.message || '保存失败' } finally { saving.value = false } }
async function remove() { if (!edit.id || !edit.version) return; try { await ElMessageBox.confirm('删除后不能用于新任务，已有任务保留原快照。', '删除工作流'); await dynamicWorkflowApi.remove(edit.id, edit.version); create(); await load() } catch (e: any) { if (e?.message) error.value = e.message } }
onMounted(load)
</script>
<style scoped>
.workflow-workspace{height:100%;min-height:0;display:grid;grid-template-columns:280px minmax(0,1fr);gap:20px}.preset-list,.preset-editor{padding:20px;overflow:auto}.preset-list h3,.editor-heading h3{margin:0 0 14px;font-size:16px}.preset-card{display:flex;flex-direction:column;gap:7px;width:100%;text-align:left;padding:14px;margin-bottom:10px;border:1px solid var(--border-default);border-radius:var(--radius-sm);color:var(--text-primary);background:var(--surface-muted);cursor:pointer}.preset-card.active{border-color:var(--brand);background:var(--brand-soft)}.preset-card span,.preset-card small{font-size:12px;color:var(--text-secondary)}.editor-heading{display:flex;justify-content:space-between;gap:12px}.editor-heading p,.editor-note,.stage-card p{font-size:12px;color:var(--text-secondary);line-height:1.7;margin:0 0 16px}.editor-fields{display:grid;grid-template-columns:1fr 1fr;gap:16px}.editor-fields .el-select{width:100%}.stage-card{display:flex;align-items:center;justify-content:space-between;gap:16px;border:1px solid var(--border-default);border-radius:var(--radius-sm);padding:16px;margin-bottom:16px;background:var(--surface-muted)}.stage-card p{margin:6px 0 0}.pipeline{display:flex;gap:10px;flex-wrap:wrap;padding:16px;border-radius:var(--radius-sm);background:var(--brand-soft);color:var(--brand);font-size:13px;margin-bottom:16px}.editor-actions{display:flex;justify-content:flex-end;gap:12px;margin-top:20px}@media(max-width:850px){.workflow-workspace{grid-template-columns:1fr;overflow:auto;height:100%;align-content:start}.preset-list{max-height:240px}.preset-editor{overflow:visible}.editor-fields{grid-template-columns:1fr}}
/* Keep the save action clear of the global assistant launcher when the form grows. */
.editor-actions{padding-right:64px}
</style>
