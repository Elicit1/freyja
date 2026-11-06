<template>
  <el-dialog v-model="visible" title="AI 自动规划" width="min(620px, 94vw)" append-to-body :close-on-click-modal="!busy" :show-close="!busy">
    <p class="text-sm text-[var(--text-secondary)] mb-5">根据镜头剧本和当前时间范围，规划所选片段；保留素材绑定和已锁定片段。</p>
    <el-form label-position="top" :disabled="busy"><el-form-item label="AI 提供商"><el-select v-model="providerId" class="w-full" placeholder="请选择 AI 提供商" @change="loadModels"><el-option v-for="p in providers" :key="String(p.id)" :value="String(p.id)" :label="p.providerName" /></el-select></el-form-item><el-form-item label="文本模型"><el-select v-model="modelCode" class="w-full" placeholder="请选择文本模型" :disabled="!providerId || loading" :loading="loading"><el-option v-for="m in models" :key="String(m.id)" :value="m.modelCode" :label="m.modelName" /></el-select></el-form-item><el-form-item label="作用范围"><el-radio-group v-model="scope"><el-radio value="unlocked">全部未锁定片段</el-radio><el-radio value="selected">指定片段</el-radio></el-radio-group><el-select v-if="scope === 'selected'" v-model="selected" multiple class="w-full mt-2" placeholder="选择需要规划的片段"><el-option v-for="(s, i) in segments" :key="s.id" :value="s.id" :disabled="s.locked" :label="`${String(i + 1).padStart(2, '0')} · ${s.name || '未命名'}${s.locked ? '（已锁定）' : ''}`" /></el-select></el-form-item><el-form-item label="补充规划要求（可选）"><el-input v-model="requirements" type="textarea" :rows="3" :maxlength="4000" show-word-limit placeholder="例如：保持动作连续，第三段突出转身动作" /></el-form-item></el-form>
    <el-alert v-if="error" type="error" :closable="false" :title="error" />
    <template #footer><el-button :disabled="busy" @click="visible = false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!providerId || !modelCode || loading || (scope === 'selected' && !selected.length)" @click="emit('submit', { providerId, modelCode, selectedSegmentIds: scope === 'selected' ? selected : undefined, requirements })">开始规划</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { ref, onBeforeUnmount } from 'vue'
import { aiProviderApi } from '@/api/ai-provider'
import type { AiModel, AiProviderVO } from '@/types/ai-provider'
import type { ContinuationSegment } from '@/types/continuation'
defineProps<{ segments: ContinuationSegment[]; busy: boolean }>()
const emit = defineEmits<{ submit: [value: { providerId: string; modelCode: string; selectedSegmentIds?: string[]; requirements: string }] }>()
const visible = ref(false), loading = ref(false), error = ref(''), providerId = ref(''), modelCode = ref(''), scope = ref('unlocked'), requirements = ref(''), selected = ref<string[]>([])
const providers = ref<AiProviderVO[]>([]), models = ref<AiModel[]>([])
let request = 0
async function open(segmentId?: string) { scope.value = segmentId ? 'selected' : 'unlocked'; selected.value = segmentId ? [segmentId] : []; visible.value = true; error.value = ''; const token = ++request; try { const list = await aiProviderApi.getListEnabled(); const choices = await Promise.all(list.map(async p => ({ p, models: await aiProviderApi.getModelList(String(p.id), 'CHAT') }))); if (token !== request) return; providers.value = choices.filter(c => c.models.some(m => m.status === 1)).map(c => c.p); if (!providers.value.length) error.value = '没有可用的文本模型，请先在模型中心配置。' } catch (e: any) { if (token === request) error.value = e.message || '读取模型失败' } }
async function loadModels() { const token = ++request; modelCode.value = ''; models.value = []; loading.value = true; try { const all = await aiProviderApi.getModelList(providerId.value, 'CHAT'); if (token === request) models.value = all.filter(m => m.status === 1) } catch (e: any) { if (token === request) error.value = e.message || '读取模型失败' } finally { if (token === request) loading.value = false } }
onBeforeUnmount(() => request++)
defineExpose({ open, close: () => { visible.value = false } })
</script>
