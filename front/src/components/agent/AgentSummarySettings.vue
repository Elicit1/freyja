<template>
  <div class="summary-settings">
    <el-form label-position="top" class="grid grid-cols-2 gap-x-4">
      <el-form-item label="摘要提供商"><el-select :model-value="modelValue.providerId || undefined" clearable placeholder="跟随本次主任务模型" @update:model-value="providerChanged"><el-option v-for="provider in providers" :key="String(provider.id)" :value="String(provider.id)" :label="provider.providerName" /></el-select></el-form-item>
      <el-form-item label="独立摘要模型"><el-select :model-value="modelValue.modelCode || undefined" :disabled="!modelValue.providerId" clearable @update:model-value="set('modelCode', $event)"><el-option v-for="model in summaryModels" :key="String(model.id)" :value="model.modelCode" :label="model.modelName" /></el-select></el-form-item>
      <el-form-item label="摘要输出预算"><el-input-number :model-value="modelValue.maxTokens" :min="1" :max="caps.maxOutputTokens || 2147483647" :step="1024" @update:model-value="set('maxTokens', $event)" /></el-form-item>
      <el-form-item label="截断重试输出预算"><el-input-number :model-value="modelValue.retryMaxTokens" :min="modelValue.maxTokens" :max="caps.maxOutputTokens || 2147483647" :step="1024" @update:model-value="set('retryMaxTokens', $event)" /></el-form-item>
      <el-form-item label="摘要思考模式"><el-select :model-value="modelValue.reasoningMode" @update:model-value="set('reasoningMode', $event)"><el-option v-for="mode in reasoningModes" :key="mode.value" :value="mode.value" :label="mode.label" :disabled="!supportsReasoning(mode.value, caps)" /></el-select></el-form-item>
      <el-form-item label="摘要超时（秒）"><el-input-number :model-value="modelValue.timeoutSeconds" :min="5" :max="1800" @update:model-value="set('timeoutSeconds', $event)" /></el-form-item>
      <el-form-item label="正文目标（字）"><el-input-number :model-value="modelValue.targetCharacters" :min="100" :max="12000" @update:model-value="set('targetCharacters', $event)" /></el-form-item>
      <el-form-item label="正文硬上限（字）"><el-input-number :model-value="modelValue.maxCharacters" :min="modelValue.targetCharacters" :max="24000" @update:model-value="set('maxCharacters', $event)" /></el-form-item>
      <el-form-item label="摘要恢复尝试次数"><el-input-number :model-value="modelValue.maxRetries" :min="0" :max="3" @update:model-value="set('maxRetries', $event)" /></el-form-item>
    </el-form>
    <el-alert v-if="caps.reasoningProtocol === 'NONE' || !caps.reasoningProtocol" type="warning" :closable="false" title="模型尚未声明思考控制协议，关闭思考可能无法生效。请在模型中心选择对应协议，或选择已配置能力的摘要模型。" />
    <p class="text-xs text-[var(--text-secondary)] mt-3">摘要默认关闭思考，独立于主任务。完整性或长度校验失败时保留原历史；实际预算会收敛到摘要模型容量内。</p>
  </div>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { aiProviderApi } from '@/api/ai-provider'
import type { AiModel, AiProviderVO } from '@/types/ai-provider'
import type { AgentSummaryConfig } from '@/types/agent'
import { modelCapabilities, reasoningModes, supportsReasoning } from './modelCapabilities'
const props = defineProps<{ modelValue: AgentSummaryConfig; providers: AiProviderVO[]; mainProviderId?: string | null; mainModelCode?: string | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: AgentSummaryConfig] }>()
const summaryModels = ref<AiModel[]>([])
let generation = 0
watch(() => props.modelValue.providerId || props.mainProviderId, async id => {
  const current = ++generation
  const models = id ? await aiProviderApi.getModelList(String(id), 'CHAT') : []
  if (current === generation) summaryModels.value = models
}, { immediate: true })
const selected = computed(() => summaryModels.value.find(m => m.modelCode === (props.modelValue.providerId ? props.modelValue.modelCode : props.mainModelCode)))
const caps = computed(() => modelCapabilities(selected.value, props.providers.find(p => String(p.id) === String(props.modelValue.providerId || props.mainProviderId))))
function set(key: string, value: unknown) { emit('update:modelValue', { ...props.modelValue, [key]: value === '' ? undefined : value }) }
function providerChanged(value?: string) { emit('update:modelValue', { ...props.modelValue, providerId: value || undefined, modelCode: undefined }) }
</script>
