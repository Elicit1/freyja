<template>
  <div class="capability-editor" :data-scope="scope">
    <div class="flex items-center justify-between gap-2 mb-3">
      <strong>{{ scope === 'provider' ? '对话模型默认能力' : '对话模型能力' }}</strong>
      <div v-if="scope === 'provider'" class="flex flex-wrap gap-2">
        <el-button size="small" @click="applyDeepSeekDefaults">采用 DeepSeek 默认能力</el-button>
        <el-button size="small" @click="applyMiMoDefaults">采用 MiMo 默认能力</el-button>
      </div>
      <el-button v-else size="small" :loading="loading" :disabled="!providerId || !modelCode" @click="discover">从提供商读取能力</el-button>
    </div>
    <el-row :gutter="16">
      <el-col :span="12"><el-form-item label-position="top" label="上下文窗口"><el-input-number class="!w-full" :model-value="caps.contextWindow" :placeholder="inherited('contextWindow')" :min="2048" :max="2147483647" :step="1024" @update:model-value="set('contextWindow', $event)" /></el-form-item></el-col>
      <el-col :span="12"><el-form-item label-position="top" label="最大输出能力"><el-input-number class="!w-full" :model-value="caps.maxOutputTokens" :placeholder="inherited('maxOutputTokens')" :min="1" :max="2147483647" :step="1024" @update:model-value="set('maxOutputTokens', $event)" /></el-form-item></el-col>
      <el-col :span="12"><el-form-item label-position="top" label="建议输出预算"><el-input-number class="!w-full" :model-value="caps.recommendedOutputTokens" :placeholder="inherited('recommendedOutputTokens')" :min="1" :max="effective.maxOutputTokens || 2147483647" :step="1024" @update:model-value="set('recommendedOutputTokens', $event)" /></el-form-item></el-col>
      <el-col :span="12"><el-form-item label-position="top" label="工具调用能力"><el-select :model-value="caps.toolCalling" clearable :placeholder="effective.toolCalling == null ? '未声明' : '继承：' + (effective.toolCalling ? '支持' : '不支持')" @update:model-value="set('toolCalling', $event)"><el-option label="支持" :value="true" /><el-option label="不支持" :value="false" /></el-select></el-form-item></el-col>
    </el-row>
    <el-form-item label-position="top" label="思考控制协议"><el-select :model-value="caps.reasoningProtocol || (scope === 'provider' ? 'NONE' : 'INHERIT')" @update:model-value="set('reasoningProtocol', $event)">
      <el-option v-if="scope === 'model'" :label="'跟随提供商（' + protocolLabel + '）'" value="INHERIT" />
      <el-option v-for="protocol in reasoningProtocols" :key="protocol.value" :label="protocol.label" :value="protocol.value" />
    </el-select></el-form-item>
    <el-form-item label-position="top" label="输出预算参数"><el-select :model-value="caps.outputTokenParameter || (scope === 'provider' ? 'max_tokens' : 'INHERIT')" @update:model-value="set('outputTokenParameter', $event)">
      <el-option v-if="scope === 'model'" :label="'跟随提供商（' + (defaults.outputTokenParameter || 'max_tokens') + '）'" value="INHERIT" />
      <el-option label="max_tokens" value="max_tokens" /><el-option label="max_completion_tokens（MiMo / 部分 OpenAI 模型）" value="max_completion_tokens" />
    </el-select></el-form-item>
    <p v-if="effective.reasoningProtocol === 'MIMO_CHAT'" class="text-xs text-[var(--text-secondary)] mb-3">MiMo 支持跟随、开启和关闭思考，不提供推理强度档位；输出预算包含思考与正文。思考开启时使用官方采样参数，关闭后可自定义温度和 top_p。</p>
    <el-form-item v-else label="支持的推理强度"><el-select :model-value="caps.reasoningEfforts" multiple clearable :placeholder="defaults.reasoningEfforts?.length ? '继承：' + defaults.reasoningEfforts.join(', ') : '未声明'" @update:model-value="set('reasoningEfforts', $event)"><el-option v-for="value in ['low', 'medium', 'high', 'max']" :key="value" :label="value" :value="value" /></el-select></el-form-item>
    <p v-if="effective.reasoningProtocol === 'DEEPSEEK_CHAT'" class="text-xs text-[var(--text-secondary)] mb-3">DeepSeek 关闭思考时不发送推理强度；思考开启时温度与惩罚参数不生效，top_p 按官方范围收敛至 0.95–1.0。</p>
    <p class="text-xs text-[var(--text-secondary)]">{{ scope === 'provider' ? '旗下 CHAT 模型继承这些默认值，模型可逐字段覆盖。仅填写该提供商下通用的能力；默认能力不会改变已开始的任务。' : '未填写的字段跟随提供商；清空覆盖后恢复继承。读取能力后仍需保存，未返回的能力可手动填写。' }}</p>
  </div>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { aiProviderApi } from '@/api/ai-provider'
import { providerCapabilities, reasoningProtocols } from './modelCapabilities'
import type { ChatCapabilities } from './modelCapabilities'
const props = withDefaults(defineProps<{ modelValue?: string; providerId?: string | number; modelCode?: string; scope?: 'provider' | 'model'; providerDefaults?: string }>(), { scope: 'model' })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const loading = ref(false)
const root = computed(() => { try { return JSON.parse(props.modelValue || '{}') } catch { return {} } })
const caps = computed<ChatCapabilities>(() => props.scope === 'provider' ? root.value : root.value.capabilities || {})
const defaults = computed<ChatCapabilities>(() => props.scope === 'provider' ? {} : providerCapabilities({ chatCapabilitiesJson: props.providerDefaults }))
const effective = computed<ChatCapabilities>(() => ({ ...defaults.value, ...Object.fromEntries(Object.entries(caps.value).filter(([, value]) => value != null)) }))
const protocolLabel = computed(() => reasoningProtocols.find(p => p.value === (defaults.value.reasoningProtocol || 'NONE'))?.label || '未声明')
function inherited(key: keyof ChatCapabilities) { return defaults.value[key] == null ? '未声明' : '继承：' + defaults.value[key] }
function publish(next: ChatCapabilities) { emit('update:modelValue', JSON.stringify(props.scope === 'provider' ? next : { ...root.value, capabilities: next })) }
function set(key: keyof ChatCapabilities, value: unknown) {
  const next = { ...caps.value, [key]: value }
  if (value === '' || value == null || value === 'INHERIT' || Array.isArray(value) && !value.length) delete next[key]
  if (key === 'reasoningProtocol' && (next.reasoningProtocol || defaults.value.reasoningProtocol) === 'MIMO_CHAT') next.reasoningEfforts = []
  publish(next)
}
function applyMiMoDefaults() {
  publish({ ...caps.value, reasoningProtocol: 'MIMO_CHAT', outputTokenParameter: 'max_completion_tokens', maxOutputTokens: 131072, recommendedOutputTokens: 65536, toolCalling: true, reasoningEfforts: [] })
  ElMessage.success('已采用 MiMo 默认能力，请保存提供商')
}
function applyDeepSeekDefaults() {
  publish({ ...caps.value, reasoningProtocol: 'DEEPSEEK_CHAT', outputTokenParameter: 'max_tokens', contextWindow: 1048576, maxOutputTokens: 393216, recommendedOutputTokens: 65536, toolCalling: true, reasoningEfforts: ['low', 'high', 'max'] })
  ElMessage.success('已采用当前官方 DeepSeek 默认能力，请保存提供商；旧模型或网关请单独覆盖')
}
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
    publish(next)
    ElMessage.success('已读取能力，请确认并保存模型')
  } finally { loading.value = false }
}
</script>
<style scoped>.capability-editor { margin: 12px 0; padding: 16px; border: 1px solid var(--border-default); border-radius: 10px; }</style>
