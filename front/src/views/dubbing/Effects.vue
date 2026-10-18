<template>
  <div class="effects-page">
    <header class="page-heading"><div><p class="eyebrow">SOUND EFFECTS</p><h1>特效配置</h1><p>通过可视化参数配置声音特效，保存后可在角色配音中选择。</p></div><div><el-button @click="router.push('/dubbing/characters')">角色配音</el-button><el-button type="primary" @click="open()">创建特效</el-button></div></header>
    <div class="search-bar"><el-input v-model="name" clearable placeholder="按特效名称模糊查询" @keyup.enter="search" /><el-button type="primary" @click="search">查询</el-button><el-button @click="name = ''; search()">重置</el-button></div>
    <el-alert v-if="filters.length && !filters.some(f => f.available)" title="未检测到可用的 FFmpeg 音频滤镜，请检查服务器 FFmpeg 配置。" type="warning" :closable="false" show-icon class="tool-warning" />
    <el-table :data="items" v-loading="loading" class="effect-table">
      <el-table-column prop="name" label="特效名称" min-width="160" />
      <el-table-column prop="description" label="描述" min-width="230" show-overflow-tooltip />
      <el-table-column label="处理步骤" min-width="160"><template #default="{ row }"><span>{{ stepLabels(row) }}</span></template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
      <el-table-column prop="version" label="版本" width="70" />
      <el-table-column label="操作" width="180" fixed="right"><template #default="{ row }"><el-button text type="primary" @click="open(row)">编辑 / 试听</el-button><el-button text :type="row.enabled ? 'warning' : 'success'" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column>
      <template #empty><el-empty description="暂无匹配的声音特效" /></template>
    </el-table>
    <el-pagination v-model:current-page="current" :total="total" :page-size="10" layout="total, prev, pager, next" @current-change="load" />
    <el-dialog v-model="visible" :title="form.id ? '编辑声音特效' : '创建声音特效'" width="min(850px, 95vw)" destroy-on-close :close-on-click-modal="false" @closed="cleanupPreview">
      <el-form label-position="top" @submit.prevent>
        <div class="name-row"><el-form-item label="特效名称" required><el-input v-model="form.name" maxlength="128" show-word-limit /></el-form-item><el-form-item label="状态"><el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" /></el-form-item></div>
        <el-form-item label="描述"><el-input v-model="form.description" maxlength="1000" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="处理步骤" required><FilterSteps v-model="form.steps" :filters="filters" class="!w-full" /></el-form-item>
      </el-form>
      <section class="preview-section"><h3>试听效果</h3><p>选择一段本地音频，对比原声与当前参数的处理结果。试听不会调用 TTS。</p>
        <input type="file" accept="audio/*" @change="chooseFile" />
        <div class="players"><div><label>原始音频</label><audio v-if="originalUrl" :src="originalUrl" controls @play="onlyPlay" /><span v-else>请选择测试音频</span></div><div><label>特效预览</label><audio v-if="processedUrl" :src="processedUrl" controls @play="onlyPlay" /><el-button v-else :disabled="!file || !form.steps.length" :loading="previewing" @click="preview">生成试听</el-button></div></div>
      </section>
      <el-alert title="保存模板不会改变已生成配音；配音保留生成时的参数快照。需要时可在配音卡片中应用最新模板并重新处理。" type="info" :closable="false" class="save-hint" />
      <template #footer><el-button @click="visible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存特效</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { ref, reactive, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { dubbingApi, type Effect, type Filter, type Step } from '@/api/dubbing'
import FilterSteps from './components/FilterSteps.vue'
const router = useRouter(), name = ref(''), current = ref(1), total = ref(0), items = ref<Effect[]>([]), filters = ref<Filter[]>([]), loading = ref(false), visible = ref(false), saving = ref(false), previewing = ref(false)
const form = reactive<{ id?: string; name: string; description: string; steps: Step[]; enabled: boolean; version?: number }>({ name: '', description: '', steps: [], enabled: true })
const file = ref<File>(), originalUrl = ref(''), processedUrl = ref('')
let queryVersion = 0, previewVersion = 0
function stepLabels(row: Effect) { return (JSON.parse(row.stepsJson) as Step[]).map(s => filters.value.find(f => f.type === s.type)?.label || s.type).join(' → ') }
async function load() { const version = ++queryVersion; loading.value = true; try { const result = await dubbingApi.effects({ current: current.value, size: 10, name: name.value || undefined }); if (version === queryVersion) { items.value = result.records; total.value = Number(result.total) } } finally { if (version === queryVersion) loading.value = false } }
function search() { current.value = 1; void load() }
function cleanupPreview() { ++previewVersion; document.querySelectorAll<HTMLAudioElement>('.el-dialog audio').forEach(a => a.pause()); if (originalUrl.value) URL.revokeObjectURL(originalUrl.value); file.value = undefined; originalUrl.value = ''; processedUrl.value = '' }
function open(effect?: Effect) { cleanupPreview(); Object.assign(form, { id: effect?.id, name: effect?.name || '', description: effect?.description || '', steps: JSON.parse(effect?.stepsJson || '[]'), enabled: effect ? effect.enabled === 1 : true, version: effect?.version }); visible.value = true }
function chooseFile(event: Event) { cleanupPreview(); const selected = (event.target as HTMLInputElement).files?.[0]; if (!selected) return; if (selected.size > 7 * 1024 * 1024) { ElMessage.warning('测试音频最大7 MiB'); return } file.value = selected; originalUrl.value = URL.createObjectURL(selected) }
function onlyPlay(event: Event) { document.querySelectorAll<HTMLAudioElement>('audio').forEach(a => { if (a !== event.target) a.pause() }) }
watch(() => form.steps, () => { ++previewVersion; processedUrl.value = '' }, { deep: true })
async function preview() { if (!file.value) return; const version = previewVersion; previewing.value = true; try { const data = new FormData(); data.append('file', file.value); data.append('steps', JSON.stringify(form.steps)); const result = await dubbingApi.preview(data); if (version === previewVersion && visible.value) processedUrl.value = result.audioUrl } finally { previewing.value = false } }
async function save() { if (!form.name.trim() || !form.steps.length) { ElMessage.warning('请填写名称并添加处理步骤'); return } saving.value = true; try { await dubbingApi.saveEffect({ ...form, name: form.name.trim() }); visible.value = false; await load(); ElMessage.success('特效已保存') } finally { saving.value = false } }
async function toggle(effect: Effect) { await dubbingApi.saveEffect({ id: effect.id, name: effect.name, description: effect.description, steps: JSON.parse(effect.stepsJson), enabled: effect.enabled !== 1, version: effect.version }); await load() }
onMounted(async () => { await Promise.allSettled([load(), dubbingApi.filters().then(result => { filters.value = result })]) })
onBeforeUnmount(() => { ++queryVersion; cleanupPreview() })
</script>
<style scoped>
.effects-page{max-width:1450px;margin:auto}.page-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:26px}.eyebrow{font-size:11px;letter-spacing:.18em;font-weight:700;color:var(--el-color-primary);margin-bottom:6px}h1{font-size:28px;font-weight:700}.page-heading p:not(.eyebrow){font-size:13px;color:var(--el-text-color-secondary);margin-top:8px}.search-bar{display:flex;gap:12px;padding:20px;border:1px solid var(--el-border-color-light);border-radius:14px;background:var(--el-bg-color);margin-bottom:20px}.search-bar>.el-input{max-width:360px}.effect-table{border-radius:14px;border:1px solid var(--el-border-color-light)}.el-pagination{justify-content:end;margin-top:22px}.tool-warning{margin-bottom:18px}.name-row{display:grid;grid-template-columns:1fr 180px;gap:24px}.preview-section{border:1px solid var(--el-border-color-light);border-radius:12px;padding:18px;margin-top:16px}.preview-section h3{font-size:15px;font-weight:600}.preview-section p{font-size:12px;color:var(--el-text-color-secondary);margin:8px 0 14px}.players{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-top:18px}.players>div{min-width:0}.players label{display:block;font-size:12px;margin-bottom:10px}.players span{font-size:12px;color:var(--el-text-color-secondary)}audio{width:100%;height:38px}.save-hint{margin-top:18px}@media(max-width:700px){.page-heading{align-items:start;flex-direction:column}.name-row,.players{grid-template-columns:1fr}.search-bar{flex-wrap:wrap}.search-bar>.el-input{max-width:none;width:100%}}
</style>
