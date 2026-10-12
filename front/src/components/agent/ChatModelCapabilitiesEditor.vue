<template>
  <div class="capability-editor">
    <div class="flex items-center justify-between mb-3"><strong>对话模型能力</strong><el-button size="small" :loading="loading" :disabled="!providerId || !modelCode" @click="discover">从提供商读取能力</el-button></div>
    <el-row :gutter="16">
      <el-col :span="12"><el-form-item label="上下文窗口"><el-input-number :model-value="caps.contextWindow" :min="2048" :max="2147483647" :step="1024" @update:model-value="set('contextWindow', $event)" /></el-form-item></el-col>
      <el-col :span="12"><el-form-item label="最大输出能力"><el-input-number :model-value="caps.maxOutputTokens" :min="1" :max="2147483647" :step="1024" @update:model-value="set('maxOutputTokens', $event)" /></el-form-item></el-col>
      <el-col :span="12"><el-form-item label="建议输出预算"><el-input-number :model-value="caps.recommendedOutputTokens" :min="1" :max="caps.maxOutputTokens || 2147483647" :step="1024" @update:model-value="set('recommendedOutputTokens', $event)" /></el-form-item></el-col>
      <el-col :span="12"><el-form-item label="工具调用能力"><el-select :model-value="caps.toolCalling" clearable @update:model-value="set('toolCalling', $event)"><el-option label="支持" :value="true" /><el-option label="不支持" :value="false" /></el-select></el-form-item></el-col>
    </el-row>
    <el-form-item label="思考控制协议"><el-select :model-value="caps.reasoningProtocol || 'NONE'" @update:model-value="set('reasoningProtocol', $event)"><el-option label="未声明 / 模型不支持控制" value="NONE" /><el-option label="DeepSeek Chat" value="DEEPSEEK_CHAT" /><el-option label="OpenAI reasoning_effort" value="OPENAI" /><el-option label="Ollama think" value="OLLAMA" /></el-select></el-form-item>
    <el-form-item label="输出预算参数"><el-select :model-value="caps.outputTokenParameter || 'max_tokens'" @update:model-value="set('outputTokenParameter', $event)"><el-option label="max_tokens（DeepSeek 与多数兼容接口）" value="max_tokens" /><el-option label="max_completion_tokens（部分 OpenAI 推理模型）" value="max_completion_tokens" /></el-select></el-form-item>
    <el-form-item label="支持的推理强度"><el-select :model-value="caps.reasoningEfforts || []" multiple @update:model-value="set('reasoningEfforts', $event)"><el-option v-for="value in ['low', 'medium', 'high', 'max']" :key="value" :label="value" :value="value" /></el-select></el-form-item>
    <p class="text-xs text-[var(--text-secondary)]">能力上限与请求预算独立。读取后仍需保存；提供商未返回的能力可手动填写。思考协议按提供商文档选择，系统不会根据模型名称猜测。</p>
  </div>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { aiProviderApi } from '@/api/ai-provider'
import type { ChatCapabilities } from './modelCapabilities'
const props = defineProps<{ modelValue?: string; providerId?: string | number; modelCode?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const loading = ref(false)
const root = computed(() => { try { return JSON.parse(props.modelValue || '{}') } catch { return {} } })
const caps = computed<ChatCapabilities>(() => root.value.capabilities || {})
function set(key: string, value: unknown) { emit('update:modelValue', JSON.stringify({ ...root.value, capabilities: { ...caps.value, [key]: value === '' ? undefined : value } })) }
async function discover() {
  if (!props.providerId) return
  loading.value = true
  try {
    const data = (await aiProviderApi.getModelCapabilities(String(props.providerId))).find(m => m.modelCode === props.modelCode)
    if (!data) { ElMessage.warning('提供商未返回该模型，请手动填写能力'); return }
    const next = { ...caps.value }
    if (data.contextWindow) next.contextWindow = data.contextWindow
    if (data.maxOutputTokens) next.maxOutputTokens = data.maxOutputTokens
    if (data.reasoningEfforts?.length) next.reasoningEfforts = data.reasoningEfforts
    emit('update:modelValue', JSON.stringify({ ...root.value, capabilities: next }))
    ElMessage.success('已读取能力，请确认思考协议并保存模型')
  } finally { loading.value = false }
}
</script>
<style scoped>.capability-editor { margin: 12px 0; padding: 16px; border: 1px solid var(--border-default); border-radius: 10px; }</style>
